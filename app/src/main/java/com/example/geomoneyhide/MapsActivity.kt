package com.example.geomoneyhide

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val BACKGROUND_LOCATION_PERMISSION_CODE = 2
        private const val TAG = "MapsActivity"
    }

    // Carte Google Maps
    private var mMap: GoogleMap? = null

    // Services de localisation Google
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // Récepteur pour les mises à jour de localisation en arrière-plan
    private var locationReceiver: BroadcastReceiver? = null

    // Éléments d'interface utilisateur
    private lateinit var surfaceDiscoveredTextView: TextView
    private lateinit var coinsCollectedTextView: TextView
    private lateinit var changeColorButton: Button
    private lateinit var rulesButton: Button
    private lateinit var creditsButton: Button

    // Gestionnaires du jeu
    private lateinit var gameState: GameState
    private lateinit var gridManager: GridManager
    private lateinit var pieceManager: PieceManager
    private lateinit var uiManager: UIManager

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: Démarrage de l'application GeoMoneyHide")

        try {
            // Configure l'affichage en mode plein écran
            WindowCompat.setDecorFitsSystemWindows(window, false)
            Log.v(TAG, "onCreate: Mode plein écran configuré")

            // Définit la mise en page à partir du XML
            setContentView(R.layout.activity_maps)
            Log.v(TAG, "onCreate: Layout principal chargé")

            // Applique les marges pour respecter les barres système
            try {
                ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ui_container)) { view, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                    // Applique les marges aux conteneurs
                    findViewById<LinearLayout>(R.id.top_info_container).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        topMargin = insets.top + 16
                        leftMargin = insets.left + 16
                        rightMargin = insets.right + 16
                    }

                    findViewById<LinearLayout>(R.id.bottom_button_container).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        bottomMargin = insets.bottom + 16
                        leftMargin = insets.left + 16
                        rightMargin = insets.right + 16
                    }

                    WindowInsetsCompat.CONSUMED
                }
                Log.v(TAG, "onCreate: Insets appliqués correctement")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: Erreur lors de l'application des insets: ${e.message}", e)
            }

            // Récupère les références vers les éléments d'interface
            try {
                Log.v(TAG, "onCreate: Récupération des références UI")
                surfaceDiscoveredTextView = findViewById(R.id.surface_discovered)
                coinsCollectedTextView = findViewById(R.id.coins_collected)
                changeColorButton = findViewById(R.id.change_color_button)
                rulesButton = findViewById(R.id.rules_button)
                creditsButton = findViewById(R.id.credits_button)
                Log.v(TAG, "onCreate: Références UI récupérées avec succès")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: Erreur lors de la récupération des références UI: ${e.message}", e)
                throw e
            }

            // Initialise le gestionnaire d'état du jeu
            try {
                Log.d(TAG, "onCreate: Initialisation du GameState")
                gameState = GameState(this)
                Log.d(TAG, "onCreate: GameState initialisé avec succès")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: Erreur lors de l'initialisation du GameState: ${e.message}", e)
                throw e
            }

            // Initialise le client de localisation fusionnée de Google
            try {
                Log.d(TAG, "onCreate: Initialisation du FusedLocationClient")
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                Log.d(TAG, "onCreate: FusedLocationClient initialisé avec succès")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: Erreur lors de l'initialisation du FusedLocationClient: ${e.message}", e)
                throw e
            }

            // Crée un récepteur pour les mises à jour de localisation en arrière-plan
            try {
                Log.d(TAG, "onCreate: Configuration du récepteur de localisation")
                locationReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        try {
                            val latitude = intent.getDoubleExtra("latitude", 0.0)
                            val longitude = intent.getDoubleExtra("longitude", 0.0)

                            if (latitude != 0.0 || longitude != 0.0) {
                                val location = LatLng(latitude, longitude)
                                markCellAsExplored(location)
                                pieceManager.checkCollectedPieces(location)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "locationReceiver.onReceive: Erreur: ${e.message}", e)
                        }
                    }
                }
                Log.d(TAG, "onCreate: Récepteur de localisation configuré avec succès")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: Erreur lors de la configuration du récepteur de localisation: ${e.message}", e)
                throw e
            }

            // Enregistre le récepteur pour recevoir les broadcasts de localisation
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"), Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"))
                }
                Log.d(TAG, "onCreate: Récepteur de localisation enregistré avec succès")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: Erreur lors de l'enregistrement du récepteur: ${e.message}", e)
                throw e
            }

            // Charge les données sauvegardées
            try {
                Log.i(TAG, "onCreate: Tentative de chargement des données sauvegardées")
                if (!gameState.loadGameData()) {
                    Log.w(TAG, "onCreate: Échec du chargement des données, initialisation avec valeurs par défaut")
                    Toast.makeText(this, "Impossible de charger les données sauvegardées", Toast.LENGTH_SHORT).show()
                } else {
                    Log.i(TAG, "onCreate: Données chargées avec succès (${gameState.exploredCells.size} cellules, ${gameState.coinsCollected} pièces)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: Erreur lors du chargement des données: ${e.message}", e)
            }

            // Met à jour l'interface avec les données chargées
            try {
                updateCoinsCollectedText()
                updateSurfaceDiscoveredText()
                Log.v(TAG, "onCreate: Interface mise à jour avec les données chargées")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: Erreur lors de la mise à jour de l'interface: ${e.message}", e)
            }

            // Vérifie et demande les permissions nécessaires
            if (!hasLocationPermission()) {
                Log.i(TAG, "onCreate: Demande de permission de localisation standard")
                requestLocationPermission()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
                Log.i(TAG, "onCreate: Demande de permission de localisation en arrière-plan")
                requestBackgroundLocationPermission()
            } else {
                Log.i(TAG, "onCreate: Toutes les permissions sont accordées, initialisation de la carte")
                initializeMap()
            }

            Log.i(TAG, "onCreate: Initialisation complète de l'activité")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Exception critique: ${e.message}", e)
            Toast.makeText(this, "Erreur lors du démarrage: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Met à jour le texte affichant le nombre de pièces collectées
     */
    fun updateCoinsCollectedText() {
        Log.v(TAG, "updateCoinsCollectedText: Mise à jour du compteur de pièces: ${gameState.coinsCollected}")
        coinsCollectedTextView.text = "Pièces collectées : ${gameState.coinsCollected}"
    }

    /**
     * Met à jour le texte affichant la surface découverte
     * Calcule aussi le pourcentage par rapport à la surface terrestre
     */
    fun updateSurfaceDiscoveredText() {
        // Calcule le pourcentage de la surface terrestre découverte
        val earthPercentage = (gameState.totalSurface / GameState.EARTH_SURFACE_KM2) * 100.0
        Log.v(TAG, "updateSurfaceDiscoveredText: Mise à jour - Surface: ${gameState.totalSurface}km², Pourcentage: $earthPercentage%")

        // Formate le pourcentage pour l'affichage
        val formattedPercentage = if (earthPercentage < 0.000001) {
            "<0.000001%" // Pour les très petites valeurs
        } else {
            String.format("%.8f%%", earthPercentage) // Affiche 8 décimales
        }

        // Met à jour le texte dans l'interface
        surfaceDiscoveredTextView.text = "Surface découverte : ${gameState.totalSurface.toInt()} km² ($formattedPercentage)"
    }

    /**
     * Vérifie si l'application a la permission de localisation standard
     * @return true si la permission est accordée, false sinon
     */
    private fun hasLocationPermission(): Boolean {
        val hasPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.v(TAG, "hasLocationPermission: Permission de localisation standard: $hasPermission")
        return hasPermission
    }

    /**
     * Vérifie si l'application a la permission de localisation en arrière-plan
     * @return true si la permission est accordée, false sinon
     */
    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Sur Android 10+, vérification spécifique
            val hasPermission = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            Log.v(TAG, "hasBackgroundLocationPermission: Permission d'arrière-plan: $hasPermission (Android ${Build.VERSION.SDK_INT})")
            return hasPermission
        } else {
            // Sur les versions antérieures, pas besoin de permission spécifique
            Log.v(TAG, "hasBackgroundLocationPermission: Permission d'arrière-plan non requise sur Android ${Build.VERSION.SDK_INT}")
            return true
        }
    }

    /**
     * Demande la permission de localisation standard à l'utilisateur
     */
    private fun requestLocationPermission() {
        Log.i(TAG, "requestLocationPermission: Demande de permission ACCESS_FINE_LOCATION")
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Demande la permission de localisation en arrière-plan à l'utilisateur
     * Ne fonctionne que sur Android 10 et supérieur
     */
    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i(TAG, "requestBackgroundLocationPermission: Demande de permission ACCESS_BACKGROUND_LOCATION")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                BACKGROUND_LOCATION_PERMISSION_CODE
            )
        }
    }

    /**
     * Initialise le fragment de carte Google Maps
     */
    private fun initializeMap() {
        Log.i(TAG, "initializeMap: Initialisation du fragment de carte")
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment?.getMapAsync(this) // Appelle onMapReady quand la carte est prête
    }

    /**
     * Méthode appelée quand la carte Google Maps est prête
     * Initialise les gestionnaires et les fonctionnalités de la carte
     */
    override fun onMapReady(googleMap: GoogleMap) {
        Log.i(TAG, "onMapReady: Google Maps initialisée avec succès")
        mMap = googleMap

        // Initialise les gestionnaires qui ont besoin de la carte
        Log.d(TAG, "onMapReady: Initialisation des gestionnaires")
        gridManager = GridManager(gameState, mMap)
        pieceManager = PieceManager(this, gameState, gridManager, mMap)
        uiManager = UIManager(this, gameState, gridManager)

        // Configure les écouteurs de boutons
        Log.v(TAG, "onMapReady: Configuration des écouteurs de boutons")
        changeColorButton.setOnClickListener { uiManager.showColorPickerDialog() }
        rulesButton.setOnClickListener { uiManager.showRulesDialog() }
        creditsButton.setOnClickListener { uiManager.showCreditsDialog() }

        // Vérifie encore les permissions (par sécurité)
        if (!hasLocationPermission()) {
            Log.e(TAG, "onMapReady: Permission de localisation non disponible")
            return
        }

        try {
            // Active le bouton de localisation sur la carte
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "onMapReady: Permissions insuffisantes pour activer la localisation sur la carte")
                return
            }

            Log.d(TAG, "onMapReady: Activation du bouton 'Ma position' sur la carte")
            mMap?.isMyLocationEnabled = true

            // Restaure d'abord les cellules explorées
            Log.i(TAG, "onMapReady: Restauration de ${gameState.exploredCells.size} cellules explorées")
            gridManager.restoreExploredCells()

            // Puis restaure les pièces sur la carte
            Log.i(TAG, "onMapReady: Restauration de ${gameState.pieces.size} pièces")
            pieceManager.restorePiecesOnMap()

            // Récupère la dernière position connue
            Log.d(TAG, "onMapReady: Récupération de la dernière position connue")
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    val userLocation = LatLng(it.latitude, it.longitude)
                    Log.i(TAG, "onMapReady: Dernière position: (${it.latitude}, ${it.longitude})")

                    // Centre la carte sur la position de l'utilisateur
                    mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 15f))
                    Log.d(TAG, "onMapReady: Carte centrée sur la position de l'utilisateur")

                    // Marque la cellule actuelle comme explorée
                    Log.d(TAG, "onMapReady: Marquage de la cellule actuelle comme explorée")
                    markCellAsExplored(userLocation)

                    // Génère des pièces seulement si nous n'en avons pas encore
                    if (gameState.pieces.isEmpty()) {
                        Log.i(TAG, "onMapReady: Aucune pièce existante, génération de nouvelles pièces")
                        pieceManager.spawnPiecesAroundUser(userLocation)
                    }
                } ?: Log.e(TAG, "onMapReady: Dernière position non disponible")
            }

            // Démarre le suivi continu de la position
            Log.d(TAG, "onMapReady: Démarrage du suivi de position")
            startLocationUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "onMapReady: Exception lors de l'initialisation: ${e.message}", e)
            e.printStackTrace()
            Toast.makeText(this, "Erreur lors de l'initialisation de la carte", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Démarre le suivi continu de la position de l'utilisateur
     * Utilise une fréquence de mise à jour adaptée à l'application
     */
    private fun startLocationUpdates() {
        Log.i(TAG, "startLocationUpdates: Configuration du suivi de position")

        // Configure la requête de localisation
        val locationRequest = LocationRequest.create()
            .setInterval(5000)          // Mise à jour environ toutes les 5 secondes
            .setFastestInterval(2000)   // Mais pas plus vite que toutes les 2 secondes
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY) // Haute précision (GPS)

        Log.d(TAG, "startLocationUpdates: Intervalle: 5000ms, Intervalle min: 2000ms, Précision: HIGH_ACCURACY")

        // Crée un callback pour traiter les mises à jour de position
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    val userLocation = LatLng(location.latitude, location.longitude)
                    Log.d(TAG, "locationCallback: Nouvelle position: (${location.latitude}, ${location.longitude})")

                    // Marque la cellule actuelle comme explorée
                    markCellAsExplored(userLocation)

                    // Vérifie si l'utilisateur a collecté des pièces
                    pieceManager.checkCollectedPieces(userLocation)
                }
            }
        }

        // Vérifie les permissions et démarre les mises à jour
        if (hasLocationPermission()) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "startLocationUpdates: Permissions insuffisantes")
                return
            }

            Log.i(TAG, "startLocationUpdates: Démarrage des mises à jour de position")
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, null)
        } else {
            Log.e(TAG, "startLocationUpdates: Permission de localisation non disponible")
        }
    }

    /**
     * Démarre le service de localisation en arrière-plan
     */
    private fun startLocationService() {
        Log.i(TAG, "startLocationService: Démarrage du service de localisation en arrière-plan")
        val serviceIntent = Intent(this, LocationService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    /**
     * Arrête le service de localisation en arrière-plan
     */
    private fun stopLocationService() {
        Log.i(TAG, "stopLocationService: Arrêt du service de localisation en arrière-plan")
        val serviceIntent = Intent(this, LocationService::class.java)
        stopService(serviceIntent)
    }

    /**
     * Marque une cellule comme explorée et gère la logique associée
     * @param location La position géographique à explorer
     */
    fun markCellAsExplored(location: LatLng) {
        // Obtient l'identifiant de la cellule correspondant à la position
        val cellId = gridManager.getCellId(location)
        Log.v(TAG, "markCellAsExplored: Position (${location.latitude}, ${location.longitude}) → Cellule $cellId")

        // Vérifie si la cellule a déjà été explorée
        if (gameState.exploredCells.contains(cellId)) {
            Log.v(TAG, "markCellAsExplored: Cellule $cellId déjà explorée, ignorée")
            return // Si déjà explorée, ne rien faire
        }

        // Marque la cellule comme explorée
        Log.i(TAG, "markCellAsExplored: Nouvelle cellule découverte: $cellId")
        gameState.exploredCells.add(cellId)
        gameState.playerPositions.add(location)

        // Dessine visuellement la cellule sur la carte
        Log.d(TAG, "markCellAsExplored: Dessin de la cellule $cellId sur la carte")
        gridManager.drawExploredCell(cellId)

        // Recalcule la surface totale explorée (en km²)
        // Formule: nombre de cellules * (taille de cellule en m)² / 1_000_000 pour convertir en km²
        gameState.totalSurface = gameState.exploredCells.size * (GridManager.GRID_SIZE * GridManager.GRID_SIZE) / 1_000_000
        Log.d(TAG, "markCellAsExplored: Surface totale recalculée: ${gameState.totalSurface}km² (${gameState.exploredCells.size} cellules)")

        // Met à jour le texte d'affichage de la surface
        updateSurfaceDiscoveredText()

        // Collecte toutes les pièces présentes dans la cellule
        Log.d(TAG, "markCellAsExplored: Vérification des pièces dans la cellule $cellId")
        pieceManager.collectAllPiecesInCell(cellId)

        // Maintient le nombre correct de pièces (3 * surface en km²)
        Log.d(TAG, "markCellAsExplored: Ajustement du nombre de pièces")
        pieceManager.adjustPieceCount(location)

        // Sauvegarde les données du jeu
        Log.d(TAG, "markCellAsExplored: Sauvegarde des données du jeu")
        gameState.saveGameData()
    }

    /**
     * Gère le résultat des demandes de permission
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult: Code: $requestCode, Résultat: ${grantResults.getOrNull(0)}")

        when (requestCode) {
            // Résultat de la demande de permission de localisation standard
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "onRequestPermissionsResult: Permission de localisation standard accordée")
                    // Si accordée, initialiser la carte
                    initializeMap()

                    // Et demander la permission d'arrière-plan si nécessaire
                    if (!hasBackgroundLocationPermission()) {
                        requestBackgroundLocationPermission()
                    }
                } else {
                    Log.e(TAG, "onRequestPermissionsResult: Permission de localisation standard refusée")
                    // Si refusée, informer l'utilisateur que c'est nécessaire
                    Toast.makeText(
                        this,
                        "Permission de localisation nécessaire pour jouer",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            // Résultat de la demande de permission de localisation en arrière-plan
            BACKGROUND_LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "onRequestPermissionsResult: Permission de localisation en arrière-plan accordée")
                    Toast.makeText(
                        this,
                        "L'application peut maintenant collecter des pièces en arrière-plan",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.w(TAG, "onRequestPermissionsResult: Permission de localisation en arrière-plan refusée")
                    Toast.makeText(
                        this,
                        "L'application ne fonctionnera qu'au premier plan",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Méthode appelée quand l'application revient au premier plan
     */
    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: Application revenue au premier plan")

        // Quand l'app est au premier plan, utiliser les mises à jour directes
        if (hasLocationPermission()) {
            Log.d(TAG, "onResume: Démarrage des mises à jour de localisation")
            startLocationUpdates()

            // Arrête le service d'arrière-plan s'il fonctionnait
            Log.d(TAG, "onResume: Arrêt du service d'arrière-plan")
            stopLocationService()
        } else {
            Log.w(TAG, "onResume: Permission de localisation non disponible")
        }
    }

    /**
     * Méthode appelée quand l'application passe en arrière-plan
     */
    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause: Application passée en arrière-plan")

        // Arrête les mises à jour directes pour économiser la batterie
        if (locationCallback != null) {
            Log.d(TAG, "onPause: Arrêt des mises à jour directes de localisation")
            fusedLocationClient.removeLocationUpdates(locationCallback!!)
        }

        // Démarre le service d'arrière-plan si on a la permission
        if (hasBackgroundLocationPermission()) {
            Log.d(TAG, "onPause: Démarrage du service de localisation en arrière-plan")
            startLocationService()
        } else {
            Log.w(TAG, "onPause: Permission d'arrière-plan non disponible, jeu en pause")
        }
    }

    /**
     * Méthode appelée lors de la destruction de l'activité
     * Nettoie toutes les ressources
     */
    override fun onDestroy() {
        Log.i(TAG, "onDestroy: Nettoyage des ressources de l'application")

        // Arrête les mises à jour de localisation
        locationCallback?.let {
            Log.d(TAG, "onDestroy: Arrêt des mises à jour de localisation")
            fusedLocationClient.removeLocationUpdates(it)
        }

        // Désenregistre le récepteur de broadcast
        locationReceiver?.let {
            Log.d(TAG, "onDestroy: Désenregistrement du récepteur de localisation")
            unregisterReceiver(it)
        }

        // Arrête le service d'arrière-plan
        Log.d(TAG, "onDestroy: Arrêt du service d'arrière-plan")
        stopLocationService()

        super.onDestroy()
    }

    /**
     * Fonction d'extension pour convertir des dp en pixels
     * Utile pour adapter l'interface à différentes densités d'écran
     */
    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}