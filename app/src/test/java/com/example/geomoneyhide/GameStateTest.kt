package com.example.geomoneyhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GameStateTest {

    private val lat = 48.8566
    private val lng = 2.3522

    @Test
    fun firstPosition_exploresCellAndSpawnsCoinsFarFromIt() {
        val state = GameState()
        val update = state.recordPosition(lat, lng, Random(1))

        assertEquals(GridMath.cellIdOf(lat, lng), update.newCellId)
        assertEquals(1, state.cellCount)
        assertTrue(update.spawned in 1..GameState.MAX_ACTIVE_COINS)
        for (coin in state.coins.values) {
            val d = GridMath.distanceMeters(lat, lng, coin.latitude, coin.longitude)
            assertTrue(d >= GameState.SPAWN_MIN_DISTANCE_M - 1 && d <= GameState.SPAWN_MAX_DISTANCE_M + 1)
            assertTrue(state.isValidSpawnPosition(coin.latitude, coin.longitude))
        }
    }

    @Test
    fun samePosition_twice_changesNothing() {
        val state = GameState()
        state.recordPosition(lat, lng, Random(1))
        val second = state.recordPosition(lat, lng, Random(2))
        assertNull(second.newCellId)
        assertFalse(second.hasChanges)
    }

    @Test
    fun walkingOnCoin_collectsItAndCreditsBalance() {
        val state = GameState()
        state.recordPosition(lat, lng, Random(3))
        val coin = state.coins.values.first()
        val before = state.coins.size

        val update = state.recordPosition(coin.latitude, coin.longitude, Random(4))

        assertTrue(update.collected + update.discovered >= 1)
        assertFalse(state.coins.containsKey(coin.id))
        assertEquals(update.collected + update.discovered, state.coinsCollected)
        assertEquals(state.coinsCollected, state.coinBalance)
        assertTrue(state.coins.size <= GameState.MAX_ACTIVE_COINS)
        assertTrue(before > 0)
    }

    @Test
    fun activeCoins_neverExceedCap_evenAfterLongExploration() {
        val state = GameState()
        val random = Random(5)
        var la = lat
        var lo = lng
        repeat(2_000) {
            la += (random.nextDouble() - 0.5) * 0.02
            lo += (random.nextDouble() - 0.5) * 0.02
            state.recordPosition(la, lo, random)
            assertTrue(state.coins.size <= GameState.MAX_ACTIVE_COINS)
        }
        assertTrue(state.cellCount > 100)
    }

    @Test
    fun farCoins_areDespawnedWhenPlayerMovesAway() {
        val state = GameState()
        state.recordPosition(lat, lng, Random(6))
        val oldIds = state.coins.keys.toSet()
        // 20 km plus au nord
        val update = state.recordPosition(lat + 0.18, lng, Random(7))
        assertEquals(oldIds.size, update.despawned)
        assertTrue(state.coins.keys.none { it in oldIds })
        for (coin in state.coins.values) {
            assertTrue(GridMath.distanceMeters(lat + 0.18, lng, coin.latitude, coin.longitude) <= GameState.DESPAWN_DISTANCE_M)
        }
    }

    @Test
    fun spawnPosition_isRejectedInsideOrNextToExploredCell() {
        val state = GameState()
        state.recordPosition(lat, lng, Random(8))
        val cell = GridMath.cellIndexOf(lat, lng)
        val (cLat, cLng) = GridMath.centerOf(cell)
        assertFalse(state.isValidSpawnPosition(cLat, cLng))
        // À 5 km : valide
        assertTrue(state.isValidSpawnPosition(cLat + 0.045, cLng))
        val coinIds = state.coins.keys
        assertNotNull(coinIds)
    }
}
