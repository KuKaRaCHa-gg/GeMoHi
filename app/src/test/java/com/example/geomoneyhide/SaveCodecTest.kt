package com.example.geomoneyhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SaveCodecTest {

    @Test
    fun legacySave_isMigrated() {
        val pieces = (0 until 3000).joinToString(",") { """{"latitude":48.${1000 + it},"longitude":2.35}""" }
        val json = """
            {"exploredAreas":[{"latitude":48.85,"longitude":2.35}],
             "collectedPiecesCount":1234,
             "playerPositions":[{"latitude":48.85,"longitude":2.35}],
             "exploredCells":["5432:163","bad",null,"-3851:-5321"],
             "zoneColor":-2147418368,
             "pieces":[$pieces,null,{"latitude":null,"longitude":2.0}]}
        """.trimIndent()
        val state = GameState()

        assertTrue(SaveCodec.decodeInto(json, state))

        assertEquals(1234, state.coinsCollected)
        assertEquals(1234, state.coinBalance) // les pièces historiques deviennent dépensables
        assertEquals(-2147418368, state.circleColor)
        assertEquals(2, state.cellCount)
        assertEquals(GameState.MAX_ACTIVE_COINS, state.coins.size)
        assertEquals(state.coins.size, state.coins.keys.toSet().size)
        assertEquals(ShopCatalog.DEFAULT_MAP_THEME, state.selectedMapTheme)
    }

    @Test
    fun legacyCellMigration_mapsOldCellCenterIntoNewCell() {
        val random = Random(9)
        repeat(10_000) {
            val old = CellIndex(random.nextInt(19_000) - 9_500, random.nextInt(40_000) - 20_000)
            val newId = SaveCodec.migrateLegacyCell(old)
            // Le centre de l'ancienne cellule (telle qu'elle était dessinée) est dans la nouvelle
            val m = GridMath.METERS_PER_DEGREE
            val latC = (old.x + 0.5) * 1000.0 / m
            val lngC = (old.y + 0.5) * 1000.0 / (m * Math.cos(Math.toRadians(latC)))
            if (lngC < -180 || lngC > 180) return@repeat
            val bounds = GridMath.boundsOf(GridMath.parseCellId(newId)!!)
            assertTrue(bounds.contains(latC, lngC))
        }
    }

    @Test
    fun roundTrip_preservesEverything() {
        var now = 5_000L
        val state = GameState { now }
        state.recordPosition(48.85, 2.35, Random(1))
        state.recordPosition(48.87, 2.36, Random(2))
        state.coinBalance = 500
        state.purchase("color_gold")
        state.purchase(ShopCatalog.BOOST_DOUBLE)
        state.selectedMapTheme = "theme_classic"

        val json = SaveCodec.encode(state)
        val copy = GameState { now }
        assertFalse(SaveCodec.decodeInto(json, copy))

        assertEquals(state.exploredCells, copy.exploredCells)
        assertEquals(state.coins, copy.coins)
        assertEquals(state.coinsCollected, copy.coinsCollected)
        assertEquals(state.coinBalance, copy.coinBalance)
        assertTrue(copy.isOwned("color_gold"))
        assertEquals(state.boostRemainingMs(ShopCatalog.BOOST_DOUBLE), copy.boostRemainingMs(ShopCatalog.BOOST_DOUBLE))
        assertEquals("theme_classic", copy.selectedMapTheme)
        assertTrue(copy.nextCoinId > (copy.coins.keys.maxOrNull() ?: 0L))
        now += 0
    }

    @Test(expected = com.google.gson.JsonParseException::class)
    fun corruptedSave_throws() {
        SaveCodec.decodeInto("{not json", GameState())
    }

    @Test
    fun emptyObject_givesDefaults() {
        val state = GameState()
        SaveCodec.decodeInto("{}", state)
        assertEquals(0, state.cellCount)
        assertEquals(GameState.DEFAULT_COLOR, state.circleColor)
    }
}
