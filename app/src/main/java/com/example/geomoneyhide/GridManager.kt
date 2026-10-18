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

        /** Nombre maximal de polygones affichés en même temps. */
        private const val MAX_POLYGONS = 1500

        /** Marge autour de la zone visible (fraction de sa taille) pour éviter les trous en déplaçant la carte. */
        private const val VIEW_MARGIN = 0.5

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

    /** Centres des cellules déjà calculés (évite de reparser les identifiants à chaque déplacement). */
    private val centers = HashMap<String, Pair<Double, Double>>()

    /**
     * Associe la carte. Seules les cellules proches de la zone affichée sont dessinées,
     * et la sélection est recalculée à chaque arrêt de la caméra : après des années
     * d'exploration, dessiner des milliers de polygones d'un coup rendait la carte très lente.
     */
    fun attachMap(googleMap: GoogleMap) {
        map = googleMap
        polygons.clear()
        googleMap.setOnCameraIdleListener { syncCells() }
        syncCells()
    }

    /** Oublie la carte (activité détruite) ; les polygones disparaissent avec elle. */
    fun detachMap() {
        map?.setOnCameraIdleListener(null)
        map = null
        polygons.clear()
    }

    /**
     * Affiche les cellules explorées situées dans la zone visible (agrandie d'une marge),
     * au plus [MAX_POLYGONS], les plus proches du centre d'abord, et retire les autres.
     */
    fun syncCells() {
        val googleMap = map ?: return
        val visible = googleMap.projection.visibleRegion.latLngBounds
        val latMargin = (visible.northeast.latitude - visible.southwest.latitude) * VIEW_MARGIN
        val south = visible.southwest.latitude - latMargin
        val north = visible.northeast.latitude + latMargin
        // Zone à cheval sur l'antiméridien : on ne filtre pas en longitude
        val wraps = visible.southwest.longitude > visible.northeast.longitude
        val lngMargin = (visible.northeast.longitude - visible.southwest.longitude) * VIEW_MARGIN
        val west = visible.southwest.longitude - lngMargin
        val east = visible.northeast.longitude + lngMargin
        val centerLat = (visible.southwest.latitude + visible.northeast.latitude) / 2
        val centerLng = (visible.southwest.longitude + visible.northeast.longitude) / 2

        var wanted = gameState.exploredCells.filter { cellId ->
            val (lat, lng) = centerOf(cellId) ?: return@filter false
            lat in south..north && (wraps || lng in west..east)
        }
        if (wanted.size > MAX_POLYGONS) {
            wanted = wanted.sortedBy { cellId ->
                val (lat, lng) = centerOf(cellId)!!
                (lat - centerLat) * (lat - centerLat) + (lng - centerLng) * (lng - centerLng)
            }.take(MAX_POLYGONS)
        }
        val wantedSet = wanted.toHashSet()

        val iterator = polygons.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in wantedSet) {
                entry.value.remove()
                iterator.remove()
            }
        }
        for (cellId in wanted) drawExploredCell(cellId)
    }

    private fun centerOf(cellId: String): Pair<Double, Double>? =
        centers[cellId] ?: GridMath.parseCellId(cellId)?.let { cell ->
            GridMath.centerOf(cell).also { centers[cellId] = it }
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
        polygons[cellId] = googleMap.addPolygon(options)
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
