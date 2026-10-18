package com.example.geomoneyhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GridMathTest {

    private val random = Random(42)

    private fun assertInsideOwnCell(lat: Double, lng: Double) {
        val cell = GridMath.cellIndexOf(lat, lng)
        val bounds = GridMath.boundsOf(cell)
        assertTrue("($lat, $lng) hors de sa cellule ${cell.id} : $bounds", bounds.contains(lat, lng))
    }

    @Test
    fun randomPoints_fallInsideTheirCellBounds() {
        repeat(200_000) {
            val lat = -85.0 + random.nextDouble() * 170.0
            val lng = -180.0 + random.nextDouble() * 360.0
            assertInsideOwnCell(lat, lng)
        }
    }

    @Test
    fun highLatitudesAndNegativeCoordinates_areConsistent() {
        repeat(50_000) {
            val lat = (if (random.nextBoolean()) 1 else -1) * (70.0 + random.nextDouble() * 19.0)
            val lng = -180.0 + random.nextDouble() * 360.0
            assertInsideOwnCell(lat, lng)
        }
        // Zone locale autour de Paris et de Buenos Aires (coordonnées négatives)
        repeat(50_000) {
            assertInsideOwnCell(48.85 + random.nextGaussian() * 0.05, 2.35 + random.nextGaussian() * 0.05)
            assertInsideOwnCell(-34.6 + random.nextGaussian() * 0.05, -58.38 + random.nextGaussian() * 0.05)
        }
    }

    @Test
    fun cellsAreAboutOneKilometreWide() {
        val b = GridMath.boundsOf(GridMath.cellIndexOf(48.85, 2.35))
        val height = GridMath.distanceMeters(b.south, b.west, b.north, b.west)
        val mid = (b.south + b.north) / 2
        val width = GridMath.distanceMeters(mid, b.west, mid, b.east)
        assertEquals(1000.0, height, 10.0)
        assertEquals(1000.0, width, 10.0)
    }

    @Test
    fun parseCellId_roundTripsAndRejectsGarbage() {
        val cell = CellIndex(-12, 345)
        assertEquals(cell, GridMath.parseCellId(cell.id))
        assertNull(GridMath.parseCellId(""))
        assertNull(GridMath.parseCellId("12"))
        assertNull(GridMath.parseCellId("a:b"))
        assertNull(GridMath.parseCellId("1:"))
        assertNull(GridMath.parseCellId(":1"))
    }

    @Test
    fun cellsAround_containsEveryCellWhoseCenterIsWithinRadius() {
        val radius = 500.0
        repeat(2_000) {
            val lat = -80.0 + random.nextDouble() * 160.0
            val lng = -179.0 + random.nextDouble() * 358.0
            val candidates = GridMath.cellsAround(lat, lng, radius).toSet()
            // Recherche exhaustive sur un voisinage large
            val origin = GridMath.cellIndexOf(lat, lng)
            for (dx in -3..3) {
                val row = origin.x + dx
                val rowLat = GridMath.rowMidLatitude(row)
                val col = GridMath.cellIndexOf(rowLat, lng).y
                for (dy in -6..6) {
                    val cell = CellIndex(row, col + dy)
                    val (cLat, cLng) = GridMath.centerOf(cell)
                    if (GridMath.distanceMeters(lat, lng, cLat, cLng) < radius) {
                        assertTrue("$cell manquante autour de ($lat, $lng)", cell in candidates)
                    }
                }
            }
            assertTrue(candidates.size <= 16)
        }
    }

    @Test
    fun offset_movesByRequestedDistance() {
        repeat(1_000) {
            val lat = -60.0 + random.nextDouble() * 120.0
            val lng = random.nextDouble() * 100.0
            val d = 500.0 + random.nextDouble() * 1500.0
            val (lat2, lng2) = GridMath.offset(lat, lng, d, random.nextDouble() * 2 * Math.PI)
            assertEquals(d, GridMath.distanceMeters(lat, lng, lat2, lng2), d * 0.01)
        }
    }
}
