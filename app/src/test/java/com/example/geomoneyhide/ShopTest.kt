package com.example.geomoneyhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopTest {

    private var now = 1_000_000L
    private fun newState(balance: Int): GameState = GameState { now }.apply { coinBalance = balance }

    @Test
    fun catalog_hasUniqueIdsAndExpectedPrices() {
        assertEquals(ShopCatalog.items.size, ShopCatalog.items.map { it.id }.toSet().size)
        assertEquals(300, ShopCatalog.byId("color_custom")?.price)
        assertEquals(250, ShopCatalog.byId("theme_neon")?.price)
        assertEquals(30, ShopCatalog.byId(ShopCatalog.BOOST_MAGNET)?.durationMinutes)
        assertNotNull(ShopCatalog.byId(ShopCatalog.DEFAULT_MAP_THEME))
    }

    @Test
    fun freeItems_areAlwaysOwned() {
        val state = newState(0)
        assertTrue(state.isOwned("color_green"))
        assertTrue(state.isOwned("theme_night"))
        assertFalse(state.isOwned("color_gold"))
        assertFalse(state.isOwned("inconnu"))
        assertEquals(PurchaseResult.ALREADY_OWNED, state.purchase("color_blue"))
    }

    @Test
    fun purchase_deductsBalanceAndNotifies() {
        val state = newState(100)
        var notified = 0
        state.changeListener = { notified++ }

        assertEquals(PurchaseResult.SUCCESS, state.purchase("color_violet"))
        assertEquals(50, state.coinBalance)
        assertTrue(state.isOwned("color_violet"))
        assertEquals(1, notified)

        assertEquals(PurchaseResult.ALREADY_OWNED, state.purchase("color_violet"))
        assertEquals(PurchaseResult.NOT_ENOUGH_COINS, state.purchase("color_red"))
        assertEquals(50, state.coinBalance)
        assertEquals(1, notified)
    }

    @Test
    fun boost_isRebuyableAndExtendsDuration() {
        val state = newState(100)
        assertEquals(0L, state.boostRemainingMs(ShopCatalog.BOOST_MAGNET))
        assertEquals(PurchaseResult.SUCCESS, state.purchase(ShopCatalog.BOOST_MAGNET))
        assertEquals(30 * 60_000L, state.boostRemainingMs(ShopCatalog.BOOST_MAGNET))
        now += 10 * 60_000L
        assertEquals(PurchaseResult.SUCCESS, state.purchase(ShopCatalog.BOOST_MAGNET))
        assertEquals(50 * 60_000L, state.boostRemainingMs(ShopCatalog.BOOST_MAGNET))
        assertEquals(40, state.coinBalance)
        now += 51 * 60_000L
        assertEquals(0L, state.boostRemainingMs(ShopCatalog.BOOST_MAGNET))
        assertFalse(state.isOwned(ShopCatalog.BOOST_MAGNET))
    }

    @Test
    fun magnetAndDouble_affectCollection() {
        val lat = 48.8566
        val lng = 2.3522
        val state = newState(200)
        state.recordPosition(lat, lng, java.util.Random(1))
        val coin = state.coins.values.first()
        // Position à ~40 m de la pièce : hors du rayon normal, dans le rayon de l'aimant
        val (nearLat, nearLng) = GridMath.offset(coin.latitude, coin.longitude, 40.0, 0.0)
        state.purchase(ShopCatalog.BOOST_MAGNET)
        state.purchase(ShopCatalog.BOOST_DOUBLE)
        val balance = state.coinBalance
        val total = state.coinsCollected

        val update = state.recordPosition(nearLat, nearLng, java.util.Random(2))

        assertFalse(state.coins.containsKey(coin.id))
        val picked = update.collected + update.discovered
        assertTrue(picked >= 1)
        assertEquals(balance + 2 * picked, state.coinBalance)
        assertEquals(total + 2 * picked, state.coinsCollected)
    }
}
