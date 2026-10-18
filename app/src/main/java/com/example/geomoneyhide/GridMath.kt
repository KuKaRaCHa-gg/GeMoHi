package com.example.geomoneyhide

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Indices entiers d'une cellule de la grille.
 * [x] = rangée (latitude), [y] = colonne (longitude). L'identifiant texte est "x:y".
 */
data class CellIndex(val x: Int, val y: Int) {
    val id: String get() = "$x:$y"
}

/** Limites géographiques d'une cellule (en degrés). */
data class CellBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude >= south && latitude < north && longitude >= west && longitude < east
}

/**
 * Calculs purs (sans dépendance Android) sur la grille de cellules de 1 km × 1 km.
 *
 * La rangée (latitude) est calculée en premier ; l'indice de longitude utilise ensuite le
 * cosinus de la latitude **au milieu de la rangée**. Ainsi [cellIndexOf] et [boundsOf] sont
 * strictement cohérents : un point appartient toujours aux limites dessinées de sa cellule.
 */
object GridMath {
    /** Côté d'une cellule en mètres. */
    const val CELL_SIZE_M = 1000.0

    /** Surface d'une cellule en km². */
    const val CELL_AREA_KM2 = (CELL_SIZE_M * CELL_SIZE_M) / 1_000_000.0

    /** Mètres par degré de latitude (approximation sphérique utilisée par la grille). */
    const val METERS_PER_DEGREE = 111_320.0

    /** Rayon terrestre moyen en mètres (pour les distances). */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Évite une division par zéro aux pôles. */
    private const val MIN_COS = 1e-6

    /** Latitude du milieu de la rangée [x]. */
    fun rowMidLatitude(x: Int): Double = (x + 0.5) * CELL_SIZE_M / METERS_PER_DEGREE

    /** Mètres par degré de longitude pour la rangée [x]. */
    private fun metersPerLngDegree(x: Int): Double =
        METERS_PER_DEGREE * max(cos(Math.toRadians(rowMidLatitude(x))), MIN_COS)

    fun rowOf(latitude: Double): Int = floor(latitude * METERS_PER_DEGREE / CELL_SIZE_M).toInt()

    fun cellIndexOf(latitude: Double, longitude: Double): CellIndex {
        val x = rowOf(latitude)
        val y = floor(longitude * metersPerLngDegree(x) / CELL_SIZE_M).toInt()
        return CellIndex(x, y)
    }

    fun cellIdOf(latitude: Double, longitude: Double): String = cellIndexOf(latitude, longitude).id

    /** Analyse un identifiant "x:y" ; renvoie null s'il est invalide. */
    fun parseCellId(cellId: String): CellIndex? {
        val sep = cellId.indexOf(':')
        if (sep <= 0 || sep == cellId.length - 1) return null
        val x = cellId.substring(0, sep).toIntOrNull() ?: return null
        val y = cellId.substring(sep + 1).toIntOrNull() ?: return null
        return CellIndex(x, y)
    }

    fun boundsOf(cell: CellIndex): CellBounds {
        val south = cell.x * CELL_SIZE_M / METERS_PER_DEGREE
        val north = (cell.x + 1) * CELL_SIZE_M / METERS_PER_DEGREE
        val lngFactor = metersPerLngDegree(cell.x)
        val west = cell.y * CELL_SIZE_M / lngFactor
        val east = (cell.y + 1) * CELL_SIZE_M / lngFactor
        return CellBounds(south, west, north, east)
    }

    /** Centre géographique de la cellule : (latitude, longitude). */
    fun centerOf(cell: CellIndex): Pair<Double, Double> {
        val b = boundsOf(cell)
        return Pair((b.south + b.north) / 2, (b.west + b.east) / 2)
    }

    /**
     * Cellules susceptibles d'avoir leur centre à moins de [radiusM] du point.
     * Renvoie un sur-ensemble (boîte englobante) : l'appelant vérifie la distance exacte.
     * Coût proportionnel à la surface du rayon, indépendant du nombre de cellules explorées.
     */
    fun cellsAround(latitude: Double, longitude: Double, radiusM: Double): List<CellIndex> {
        val dLat = radiusM / METERS_PER_DEGREE
        val rowMin = rowOf(latitude - dLat)
        val rowMax = rowOf(latitude + dLat)
        // Le degré de longitude est le plus court à la latitude la plus éloignée de l'équateur
        val maxAbsLat = min(max(abs(latitude - dLat), abs(latitude + dLat)), 89.9)
        val dLng = radiusM / (METERS_PER_DEGREE * max(cos(Math.toRadians(maxAbsLat)), MIN_COS))
        val result = ArrayList<CellIndex>()
        for (x in rowMin..rowMax) {
            val factor = metersPerLngDegree(x)
            val colMin = floor((longitude - dLng) * factor / CELL_SIZE_M).toInt()
            val colMax = floor((longitude + dLng) * factor / CELL_SIZE_M).toInt()
            for (y in colMin..colMax) result.add(CellIndex(x, y))
        }
        return result
    }

    /** Distance orthodromique (formule de haversine) en mètres. */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Décale un point de [distanceM] mètres selon [angleRad] (0 = nord, sens horaire).
     * Approximation locale, suffisante pour quelques kilomètres.
     */
    fun offset(latitude: Double, longitude: Double, distanceM: Double, angleRad: Double): Pair<Double, Double> {
        val dLat = distanceM * cos(angleRad) / METERS_PER_DEGREE
        val dLng = distanceM * sin(angleRad) /
            (METERS_PER_DEGREE * max(cos(Math.toRadians(latitude)), MIN_COS))
        return Pair(latitude + dLat, longitude + dLng)
    }
}
