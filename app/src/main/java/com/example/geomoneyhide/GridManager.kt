package com.example.geomoneyhide

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import kotlin.math.cos
import kotlin.math.floor

/**
 * Gestionnaire de grille pour le jeu GeoMoneyHide
 *
 * Cette classe s'occupe de:
 * - Diviser la carte en cellules de taille fixe (1000m x 1000m)
 * - Calculer les identifiants uniques de chaque cellule basés sur la position géographique
 * - Dessiner et mettre à jour les cellules explorées sur la carte
 */
class GridManager(
    private val gameState: GameState,
    private val map: GoogleMap?
) {
    companion object {
        // Taille de la cellule en mètres (carré de 1km)
        const val GRID_SIZE = 1000.0

        // Constante représentant le nombre de mètres par degré de latitude à l'équateur
        private const val METERS_PER_DEGREE = 111320.0
    }

    /**
     * Calcule l'identifiant unique d'une cellule à partir de coordonnées géographiques
     *
     * Le principe:
     * 1. Convertit latitude/longitude en indices de grille (x,y)
     * 2. Prend en compte la distorsion de la longitude à différentes latitudes
     *
     * @param location Les coordonnées GPS pour lesquelles calculer l'ID de cellule
     * @return L'identifiant de cellule au format "x:y"
     */
    fun getCellId(location: LatLng): String {
        try {
            // Convertit la latitude en index de grille
            // (111320m = ~1° de latitude à l'équateur)
            val x = floor(location.latitude * METERS_PER_DEGREE / GRID_SIZE).toInt()

            // Facteur de correction pour la longitude (varie selon la latitude)
            val cosLat = cos(Math.toRadians(location.latitude))

            // Convertit la longitude en index de grille avec correction
            val y = floor(location.longitude * METERS_PER_DEGREE * cosLat / GRID_SIZE).toInt()

            // Format "x:y" pour identifier uniquement chaque cellule
            return "$x:$y"
        } catch (e: Exception) {
            return "0:0" // Valeur par défaut en cas d'erreur
        }
    }

    /**
     * Calcule les coordonnées des quatre coins d'une cellule à partir de son identifiant
     *
     * @param cellId L'identifiant de la cellule au format "x:y"
     * @return Tableau des 4 coins de la cellule dans l'ordre: SO, NO, NE, SE
     */
    fun getCellBounds(cellId: String): Array<LatLng> {
        try {
            // Décompose l'identifiant en composantes x et y
            val parts = cellId.split(":")
            if (parts.size != 2) {
                throw IllegalArgumentException("Format de cellId invalide: $cellId")
            }

            val x = parts[0].toInt()
            val y = parts[1].toInt()

            // Calcule les latitudes nord et sud de la cellule
            val latSouth = (x * GRID_SIZE) / METERS_PER_DEGREE
            val latNorth = ((x + 1) * GRID_SIZE) / METERS_PER_DEGREE

            // Calcule le facteur de correction pour la longitude à cette latitude
            val midLat = (latSouth + latNorth) / 2
            val lngFactor = METERS_PER_DEGREE * cos(Math.toRadians(midLat))

            // Calcule les longitudes est et ouest de la cellule
            val lngWest = (y * GRID_SIZE) / lngFactor
            val lngEast = ((y + 1) * GRID_SIZE) / lngFactor

            // Retourne les 4 coins dans l'ordre: SO, NO, NE, SE
            return arrayOf(
                LatLng(latSouth, lngWest), // Sud-Ouest
                LatLng(latNorth, lngWest), // Nord-Ouest
                LatLng(latNorth, lngEast), // Nord-Est
                LatLng(latSouth, lngEast)  // Sud-Est
            )
        } catch (e: Exception) {
            // Cellule par défaut centrée à 0,0 en cas d'erreur
            return arrayOf(
                LatLng(-0.01, -0.01),
                LatLng(0.01, -0.01),
                LatLng(0.01, 0.01),
                LatLng(-0.01, 0.01)
            )
        }
    }

    /**
     * Dessine une cellule explorée sur la carte sous forme de polygone coloré
     *
     * @param cellId L'identifiant de la cellule à dessiner
     */
    fun drawExploredCell(cellId: String) {
        try {
            // Vérifications de sécurité
            if (map == null) {
                return
            }

            // Évite de dessiner plusieurs fois la même cellule
            if (gameState.cellPolygons.containsKey(cellId)) {
                return
            }

            // Récupère les coordonnées des coins de la cellule
            val bounds = getCellBounds(cellId)
            val color = gameState.circleColor

            // Crée le polygone avec les options de style
            val polygonOptions = PolygonOptions()
                .add(bounds[0], bounds[1], bounds[2], bounds[3])
                .fillColor(color)
                .strokeWidth(2f)
                .strokeColor(color)

            // Ajoute le polygone à la carte et stocke la référence
            map.addPolygon(polygonOptions)?.let { polygon ->
                gameState.cellPolygons[cellId] = polygon
            }
        } catch (e: Exception) {
            // Ignore les erreurs silencieusement
        }
    }

    /**
     * Met à jour la couleur de toutes les cellules explorées
     * Utilisé lorsque l'utilisateur change la couleur des cellules
     */
    fun updateCellColors() {
        try {
            val color = gameState.circleColor

            // Parcourt toutes les cellules et met à jour leur couleur
            for ((_, polygon) in gameState.cellPolygons) {
                try {
                    polygon.fillColor = color
                    polygon.strokeColor = color
                } catch (e: Exception) {
                    // Ignore les erreurs silencieusement
                }
            }
        } catch (e: Exception) {
            // Ignore les erreurs silencieusement
        }
    }

    /**
     * Restaure visuellement toutes les cellules explorées précédemment
     * Appelé au chargement du jeu pour redessiner les cellules sauvegardées
     */
    fun restoreExploredCells() {
        try {
            // Pour chaque cellule enregistrée, la redessine sur la carte
            for (cellId in gameState.exploredCells) {
                try {
                    drawExploredCell(cellId)
                } catch (e: Exception) {
                    // Ignore les erreurs silencieusement
                }
            }
        } catch (e: Exception) {
            // Ignore les erreurs silencieusement
        }
    }

    /**
     * Calcule la surface totale explorée en kilomètres carrés
     *
     * @return Surface explorée en km²
     */
    fun calculateExploredSurface(): Double {
        // Chaque cellule fait 1km², donc la surface totale est simplement le nombre de cellules
        return gameState.exploredCells.size.toDouble()
    }
}