package com.example.geomoneyhide

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.MainThread
import com.google.gson.JsonParseException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Source de vérité unique du jeu, partagée par l'activité et le service d'arrière-plan.
 *
 * - Reçoit les positions (via [LocationTracker]) et applique les règles de [GameState].
 * - Sauvegarde de façon différée et atomique sur un thread dédié, uniquement si l'état a changé.
 * - Notifie les observateurs ([Listener]) des changements.
 *
 * Toutes les méthodes publiques doivent être appelées sur le thread principal.
 */
object GameRepository {

    /** Observateur de l'état du jeu (appelé sur le thread principal). */
    interface Listener {
        /** L'état a changé (exploration, pièces, achat, réglage). */
        fun onGameUpdated(update: GameUpdate) {}

        /** Nouvelle position reçue (même imprécise). */
        fun onLocationChanged(location: Location) {}
    }

    private const val TAG = "GameRepository"
    private const val SAVE_FILE_NAME = "game_data.json"
    private const val SAVE_DELAY_MS = 3_000L

    /** Au-delà de cette imprécision, une position ne sert ni à explorer ni à collecter. */
    private const val MAX_ACCURACY_M = 100f

    /** Âge maximal d'une « dernière position connue » pour être prise en compte dans le jeu. */
    private const val MAX_LAST_LOCATION_AGE_MS = 2 * 60 * 1000L

    val state = GameState()

    /** Dernière position connue du joueur (même imprécise), pour recentrer la carte. */
    var lastLocation: Location? = null
        private set

    private var saveFile: AtomicFile? = null
    private val listeners = LinkedHashSet<Listener>()
    private val random = Random()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GameSave").apply { isDaemon = false }
    }

    // Révision de l'état en mémoire et dernière révision confiée à l'écriture
    private var revision = 0L
    private var savedRevision = 0L
    private var saveScheduled = false

    /** Vrai si la sauvegarde existe mais n'a pas pu être lue : on ne l'écrase surtout pas. */
    private var saveBlocked = false
    private val saveRunnable = Runnable { flush() }

    val isInitialized: Boolean get() = saveFile != null

    /**
     * Charge la sauvegarde une seule fois par processus. Sans effet si déjà initialisé.
     */
    @MainThread
    fun init(context: Context) {
        if (saveFile != null) return
        val file = AtomicFile(File(context.applicationContext.filesDir, SAVE_FILE_NAME))
        saveFile = file
        load(file)
        // Achats et réglages : sauvegarde différée + notification des observateurs
        state.changeListener = { update ->
            markDirty()
            dispatch(update)
        }
    }

    private fun load(file: AtomicFile) {
        try {
            // readFully() restaure aussi une éventuelle écriture interrompue
            val json = String(file.readFully(), Charsets.UTF_8)
            if (SaveCodec.decodeInto(json, state)) markDirty() // réécriture au nouveau format
            Log.i(TAG, "Sauvegarde chargée : ${state.cellCount} cellules, ${state.coins.size} pièces actives")
        } catch (e: FileNotFoundException) {
            // Première partie : aucune sauvegarde
        } catch (e: IOException) {
            Log.e(TAG, "Lecture de la sauvegarde impossible, écriture désactivée", e)
            saveBlocked = true
        } catch (e: JsonParseException) {
            // Fichier corrompu : on le garde de côté et on repart d'un état vierge
            Log.e(TAG, "Sauvegarde corrompue, mise de côté", e)
            file.baseFile.renameTo(File(file.baseFile.path + ".corrupt"))
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Traite une position reçue (premier plan ou arrière-plan).
     */
    @MainThread
    fun onLocation(location: Location) {
        lastLocation = location
        for (listener in listeners.toList()) listener.onLocationChanged(location)
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val update = state.recordPosition(location.latitude, location.longitude, random)
        if (update.hasChanges) {
            markDirty()
            dispatch(update)
        }
    }

    /**
     * Utilise la « dernière position connue » du système si elle est récente ;
     * sinon, elle sert seulement au recentrage de la carte.
     */
    @MainThread
    fun onLastKnownLocation(location: Location) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (ageMs in 0..MAX_LAST_LOCATION_AGE_MS) {
            onLocation(location)
        } else if (lastLocation == null) {
            lastLocation = location
            for (listener in listeners.toList()) listener.onLocationChanged(location)
        }
    }

    /** Signale une modification faite hors du repository (ex. couleur) et planifie la sauvegarde. */
    @MainThread
    fun markDirty() {
        revision++
        if (!saveScheduled) {
            saveScheduled = true
            mainHandler.postDelayed(saveRunnable, SAVE_DELAY_MS)
        }
    }

    /**
     * Sauvegarde immédiatement (en tâche de fond) si l'état a changé depuis la dernière écriture.
     * À appeler à l'arrêt de l'activité ou du service.
     */
    @MainThread
    fun flush() {
        mainHandler.removeCallbacks(saveRunnable)
        saveScheduled = false
        val file = saveFile ?: return
        if (saveBlocked || revision == savedRevision) return
        val rev = revision
        val json = SaveCodec.encode(state) // instantané pris sur le thread principal
        savedRevision = rev
        ioExecutor.execute {
            if (!write(file, json)) {
                // Échec : on forcera une nouvelle tentative à la prochaine sauvegarde
                mainHandler.post { if (savedRevision == rev) savedRevision = -1 }
            }
        }
    }

    private fun write(file: AtomicFile, json: String): Boolean {
        var stream: FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Échec de la sauvegarde", e)
            if (stream != null) file.failWrite(stream)
            false
        }
    }

    private fun dispatch(update: GameUpdate) {
        // Copie : un observateur peut se désinscrire pendant la notification
        for (listener in listeners.toList()) listener.onGameUpdated(update)
    }
}
