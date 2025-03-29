package com.example.geomoneyhide

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.Polygon
import com.google.gson.Gson
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Gère l'état du jeu et la persistance des données
 */
class GameState(private val context: Context) {

    companion object {
        private const val TAG = "GameState"
        private const val SAVE_FILE_NAME = "game_data.json"
        const val EARTH_SURFACE_KM2 = 510_072_000.0 // Surface totale de la Terre en km²
    }

    // Données du jeu
    val exploredAreas = mutableListOf<LatLng>()      // Liste des zones explorées
    var coinsCollected = 0                          // Nombre de pièces collectées
    val playerPositions = mutableListOf<LatLng>()   // Historique des positions
    val exploredCells = mutableHashSet<String>()    // Cellules visitées (identifiants)

    private fun <T> mutableHashSet(): MutableSet<T> {
        return HashSet()
    }

    var circleColor = 0x2200FF00                    // Couleur des zones (vert clair par défaut)
    val collectedPieces = mutableListOf<LatLng>()   // Pièces déjà collectées
    val pieces = mutableListOf<LatLng>()            // Pièces actives sur la carte
    val pieceMarkers = mutableListOf<Marker>()      // Marqueurs des pièces sur la carte
    val cellPolygons = mutableMapOf<String, Polygon>() // Polygones des cellules sur la carte

    // Statistiques calculées
    var totalSurface = 0.0  // Surface totale explorée en km²



    /**
     * Sauvegarde toutes les données du jeu dans un fichier JSON
     * @return true si la sauvegarde a réussi, false sinon
     */
    fun saveGameData(): Boolean {
        return try {

            // Prépare l'objet de données à sérialiser
            val gameData = GameData(
                exploredAreas = exploredAreas.toList(),
                collectedPiecesCount = coinsCollected,
                playerPositions = playerPositions.toList(),
                exploredCells = exploredCells.toList(),
                zoneColor = circleColor,
                pieces = pieces.toList()
            )

            // Utilise Gson pour convertir en JSON
            val gson = Gson()
            val jsonData = gson.toJson(gameData)

            // Écrit le JSON dans le fichier
            val file = File(context.filesDir, SAVE_FILE_NAME)
            FileWriter(file).use { writer ->
                writer.write(jsonData)
                writer.flush()
            }

            Log.i(TAG, "saveGameData: Données sauvegardées avec succès (${file.length()} octets)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveGameData: Erreur lors de la sauvegarde: ${e.message}", e)
            false
        }
    }

    /**
     * Charge les données du jeu depuis le fichier JSON
     * @return true si le chargement a réussi, false sinon
     */
    fun loadGameData(): Boolean {
        try {
            Log.i(TAG, "loadGameData: Tentative de chargement des données")
            val file = File(context.filesDir, SAVE_FILE_NAME)

            if (file.exists()) {
                try {

                    // Lit le fichier JSON
                    val jsonData = FileReader(file).use { it.readText() }

                    // Désérialise le JSON en objet GameData
                    val gson = Gson()
                    val gameData = gson.fromJson(jsonData, GameData::class.java)

                    // Applique les données chargées à l'état du jeu
                    exploredAreas.clear()
                    exploredAreas.addAll(gameData.exploredAreas ?: emptyList())

                    coinsCollected = gameData.collectedPiecesCount

                    playerPositions.clear()
                    playerPositions.addAll(gameData.playerPositions ?: emptyList())

                    exploredCells.clear()
                    exploredCells.addAll(gameData.exploredCells ?: emptyList())

                    // Charge la couleur ou utilise la valeur par défaut
                    circleColor = gameData.zoneColor ?: 0x2200FF00

                    pieces.clear()
                    pieces.addAll(gameData.pieces ?: emptyList())

                    // Calcule la surface en km² et le pourcentage
                    totalSurface = exploredCells.size * (GridManager.GRID_SIZE * GridManager.GRID_SIZE) / 1_000_000

                    return true
                } catch (e: Exception) {
                    // En cas d'erreur pendant la désérialisation, réinitialise les données
                    e.printStackTrace()
                    resetGameData()
                    return false
                }
            }
            return true // Aucune sauvegarde existante, considéré comme un succès
        } catch (e: Exception) {
            // Erreur d'accès au fichier
            e.printStackTrace()
            return false
        }
    }

    /**
     * Réinitialise toutes les données du jeu
     * Supprime également le fichier de sauvegarde s'il existe
     */
    fun resetGameData() {
        // Vide toutes les collections
        exploredAreas.clear()
        coinsCollected = 0
        playerPositions.clear()
        exploredCells.clear()
        circleColor = 0x2200FF00  // Réinitialise la couleur à vert clair
        pieces.clear()

        // Supprime le fichier de sauvegarde corrompu
        val file = File(context.filesDir, SAVE_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Classe de données pour la sérialisation/désérialisation JSON
     * Contient toutes les données qui doivent être sauvegardées
     */
    data class GameData(
        val exploredAreas: List<LatLng>,         // Zones explorées
        val collectedPiecesCount: Int,           // Nombre de pièces collectées
        val playerPositions: List<LatLng>,       // Positions du joueur
        val exploredCells: List<String>,         // Cellules explorées
        val zoneColor: Int,                      // Couleur des zones
        val pieces: List<LatLng>                 // Positions des pièces actives
    ) {
        // Constructeur vide avec valeurs par défaut, requis pour Gson
        constructor() : this(emptyList(), 0, emptyList(), emptyList(), 0x2200FF00, emptyList())
    }
}