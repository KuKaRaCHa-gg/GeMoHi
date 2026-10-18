package com.example.geomoneyhide

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions

/**
 * Affichage de la grille sur la carte : un polygone par cellule explorée.
 *
 * Indépendant de la carte à la construction : [attachMap] est appelé dans onMapReady.
 * Les calculs (identifiants, limites) sont délégués à [GridMath].
 */
class GridManager(private val gameState: GameState) {

    companion object {
        /** Taille de la cellule en mètres (carré de 1 km). */
        const val GRID_SIZE = GridMath.CELL_SIZE_M

        private const val POLYGON_Z_INDEX = 0f
        private const val STROKE_WIDTH_PX = 2f

        fun getCellId(location: LatLng): String = GridMath.cellIdOf(location.latitude, location.longitude)

        /**
         * Coins de la cellule dans l'ordre SO, NO, NE, SE, ou null si l'identifiant est invalide.
         */
        fun getCellBounds(cellId: String): Array<LatLng>? {
            val cell = GridMath.parseCellId(cellId) ?: return null
            val b = GridMath.boundsOf(cell)
            return arrayOf(
                LatLng(b.south, b.west),
                LatLng(b.north, b.west),
                LatLng(b.north, b.east),
                LatLng(b.south, b.east)
            )
        }
    }

    private var map: GoogleMap? = null
    private val polygons = HashMap<String, Polygon>()

    /** Associe la carte et dessine toutes les cellules connues. */
    fun attachMap(googleMap: GoogleMap) {
        map = googleMap
        polygons.clear()
        syncCells()
    }

    /** Oublie la carte (activité détruite) ; les polygones disparaissent avec elle. */
    fun detachMap() {
        map = null
        polygons.clear()
    }

    /** Dessine les cellules explorées qui ne le sont pas encore (aucun redessin inutile). */
    fun syncCells() {
        if (map == null || polygons.size == gameState.cellCount) return
        for (cellId in gameState.exploredCells) drawExploredCell(cellId)
    }

    /** Dessine une cellule si elle n'est pas déjà affichée. */
    fun drawExploredCell(cellId: String) {
        val googleMap = map ?: return
        if (polygons.containsKey(cellId)) return
        val bounds = getCellBounds(cellId) ?: return
        val color = gameState.circleColor
        val options = PolygonOptions()
            .add(bounds[0], bounds[1], bounds[2], bounds[3])
            .fillColor(color)
            .strokeColor(color)
            .strokeWidth(STROKE_WIDTH_PX)
            .zIndex(POLYGON_Z_INDEX)
        googleMap.addPolygon(options)?.let { polygons[cellId] = it }
    }

    /** Applique [GameState.circleColor] aux polygones existants. */
    fun updateCellColors() {
        val color = gameState.circleColor
        for (polygon in polygons.values) {
            polygon.fillColor = color
            polygon.strokeColor = color
        }
    }

    /** Surface explorée en km². */
    fun calculateExploredSurface(): Double = gameState.surfaceKm2
}
