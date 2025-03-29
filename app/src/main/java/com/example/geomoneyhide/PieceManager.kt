package com.example.geomoneyhide

import android.location.Location
import android.widget.Toast
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PieceManager(
    private val context: MapsActivity,
    private val gameState: GameState,
    private val gridManager: GridManager,
    private val map: GoogleMap?
) {
    companion object {
        private const val MIN_DISTANCE_PIECE = 5
        private const val MAX_DISTANCE_PIECE = 1000
        private const val COLLECTION_RADIUS = 20.0
    }

    /**
     * Génère des pièces aléatoirement autour de la position actuelle du joueur
     * Les pièces apparaissent uniquement dans les zones non explorées et à 500m de la zone explorée la plus proche
     */
    fun spawnPiecesAroundUser(currentPosition: LatLng) {
        val random = Random()
        val numPieces = random.nextInt(10) + 1
        val spawnAttempts = 30
        val minDistanceFromExplored = 500  // 500 mètres des zones explorées

        for (i in 0 until numPieces) {
            var validPosition = false
            var piecePosition: LatLng? = null
            var attempts = 0

            while (!validPosition && attempts < spawnAttempts) {
                // Distance entre 500m et 2000m pour s'assurer d'être assez loin
                val distance = 500 + random.nextDouble() * 1500
                val angle = random.nextDouble() * 2 * PI

                val deltaLat = distance * cos(angle) / 111320.0
                val deltaLng = distance * sin(angle) / (111320.0 * cos(Math.toRadians(currentPosition.latitude)))

                piecePosition = LatLng(
                    currentPosition.latitude + deltaLat,
                    currentPosition.longitude + deltaLng
                )

                // Vérifie si cette position est dans une cellule non explorée
                val pieceCell = gridManager.getCellId(piecePosition)
                if (!gameState.exploredCells.contains(pieceCell)) {
                    validPosition = isAtMinimumDistanceFromExploredCells(piecePosition, minDistanceFromExplored)
                }

                attempts++
            }

            if (validPosition && piecePosition != null) {
                gameState.pieces.add(piecePosition)

                val marker = map?.addMarker(
                    MarkerOptions()
                        .position(piecePosition)
                        .title("Pièce")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                )

                if (marker != null) {
                    gameState.pieceMarkers.add(marker)
                }
            }
        }

        gameState.saveGameData()
    }

    /**
     * Vérifie si le joueur a collecté des pièces à sa position actuelle
     */
    fun checkCollectedPieces(currentPosition: LatLng) {
        var piecesCollected = 0

        for (i in gameState.pieces.indices.reversed()) {
            val piecePosition = gameState.pieces[i]

            val results = FloatArray(1)
            Location.distanceBetween(
                currentPosition.latitude, currentPosition.longitude,
                piecePosition.latitude, piecePosition.longitude,
                results
            )

            if (results[0] <= COLLECTION_RADIUS) {
                // Collecte la pièce
                gameState.pieces.removeAt(i)
                gameState.collectedPieces.add(piecePosition)
                gameState.coinsCollected++

                // Supprime le marqueur correspondant
                if (i < gameState.pieceMarkers.size) {
                    gameState.pieceMarkers[i].remove()
                    gameState.pieceMarkers.removeAt(i)
                }

                piecesCollected++
            }
        }

        if (piecesCollected > 0) {
            Toast.makeText(context, "Vous avez collecté $piecesCollected pièce(s) !", Toast.LENGTH_SHORT).show()
            context.updateCoinsCollectedText()
            gameState.saveGameData()
        }
    }

    /**
     * Collecte toutes les pièces présentes dans une cellule spécifique
     */
    fun collectAllPiecesInCell(cellId: String) {
        val bounds = gridManager.getCellBounds(cellId)
        val sw = bounds[0]
        val ne = bounds[2]

        val piecesToRemove = mutableListOf<Int>()

        for (i in gameState.pieces.indices) {
            val piece = gameState.pieces[i]

            // Vérifie si la pièce est dans la cellule
            if (piece.latitude >= sw.latitude && piece.latitude <= ne.latitude &&
                piece.longitude >= sw.longitude && piece.longitude <= ne.longitude) {

                piecesToRemove.add(i)
                gameState.collectedPieces.add(piece)
                gameState.coinsCollected++
            }
        }

        // Supprime les pièces collectées
        for (i in piecesToRemove.reversed()) {
            if (i < gameState.pieceMarkers.size) {
                gameState.pieceMarkers[i].remove()
                gameState.pieceMarkers.removeAt(i)
            }
            gameState.pieces.removeAt(i)
        }

        if (piecesToRemove.isNotEmpty()) {
            Toast.makeText(
                context,
                "Vous avez découvert ${piecesToRemove.size} pièce(s) !",
                Toast.LENGTH_SHORT
            ).show()
            context.updateCoinsCollectedText()
        }
    }

    /**
     * Ajuste le nombre de pièces dans le jeu selon la surface découverte
     */
    fun adjustPieceCount(currentPosition: LatLng) {
        val targetPieceCount = (gameState.totalSurface * 3).toInt()
        val currentPieceCount = gameState.pieces.size

        if (currentPieceCount < targetPieceCount) {
            val piecesToAdd = targetPieceCount - currentPieceCount
            spawnNewPieces(piecesToAdd, currentPosition)
        }
    }

    /**
     * Restaure toutes les pièces sauvegardées sur la carte
     */
    fun restorePiecesOnMap() {
        // Efface d'abord tous les marqueurs existants
        for (marker in gameState.pieceMarkers) {
            marker.remove()
        }
        gameState.pieceMarkers.clear()

        // Crée de nouveaux marqueurs pour toutes les pièces chargées
        for (piecePosition in gameState.pieces) {
            val marker = map?.addMarker(
                MarkerOptions()
                    .position(piecePosition)
                    .title("Pièce")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
            )

            if (marker != null) {
                gameState.pieceMarkers.add(marker)
            }
        }
    }

    /**
     * Vérifie si une position est à une distance minimale de toutes les cellules explorées
     */
    private fun isAtMinimumDistanceFromExploredCells(position: LatLng, minDistance: Int): Boolean {
        for (cellId in gameState.exploredCells) {
            val bounds = gridManager.getCellBounds(cellId)

            // Calcule le centre approximatif de la cellule
            val cellCenter = LatLng(
                (bounds[0].latitude + bounds[2].latitude) / 2,
                (bounds[0].longitude + bounds[2].longitude) / 2
            )

            val results = FloatArray(1)
            Location.distanceBetween(
                position.latitude, position.longitude,
                cellCenter.latitude, cellCenter.longitude,
                results
            )

            // Si trop proche d'une cellule explorée, rejette la position
            if (results[0] < minDistance) {
                return false
            }
        }
        return true
    }

    /**
     * Génère un nombre spécifique de nouvelles pièces à 500m des zones explorées
     */
    fun spawnNewPieces(count: Int, currentPosition: LatLng) {
        val random = Random()
        val spawnAttempts = 30
        var spawnedPieces = 0
        val minDistanceFromExplored = 500

        for (i in 0 until count) {
            var validPosition = false
            var piecePosition: LatLng? = null
            var attempts = 0

            while (!validPosition && attempts < spawnAttempts) {
                val distance = 500 + random.nextDouble() * 1500
                val angle = random.nextDouble() * 2 * PI

                val deltaLat = distance * cos(angle) / 111320.0
                val deltaLng = distance * sin(angle) / (111320.0 * cos(Math.toRadians(currentPosition.latitude)))

                piecePosition = LatLng(
                    currentPosition.latitude + deltaLat,
                    currentPosition.longitude + deltaLng
                )

                val pieceCell = gridManager.getCellId(piecePosition)
                if (!gameState.exploredCells.contains(pieceCell)) {
                    validPosition = isAtMinimumDistanceFromExploredCells(piecePosition, minDistanceFromExplored)
                }

                attempts++
            }

            if (validPosition && piecePosition != null) {
                gameState.pieces.add(piecePosition)
                spawnedPieces++

                val marker = map?.addMarker(
                    MarkerOptions()
                        .position(piecePosition)
                        .title("Pièce")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                )

                if (marker != null) {
                    gameState.pieceMarkers.add(marker)
                }
            }
        }

        if (spawnedPieces > 0) {
            Toast.makeText(context, "$spawnedPieces nouvelles pièces sont apparues !", Toast.LENGTH_SHORT).show()
        }
    }
}