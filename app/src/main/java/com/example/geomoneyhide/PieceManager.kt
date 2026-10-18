package com.example.geomoneyhide

import android.content.Context
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

/**
 * Affichage des pièces sur la carte.
 *
 * Les marqueurs sont indexés par l'identifiant stable de la pièce ([Coin.id]) : [syncMarkers]
 * compare l'état du jeu et les marqueurs affichés, retire ceux en trop et ajoute ceux qui
 * manquent. Aucune désynchronisation possible entre pièces et marqueurs.
 * La logique (apparition, collecte) est dans [GameState].
 */
class PieceManager(
    private val context: Context,
    private val gameState: GameState
) {
    companion object {
        private const val MARKER_Z_INDEX = 1f
    }

    private var map: GoogleMap? = null
    private val markers = HashMap<Long, Marker>()

    /** Associe la carte et affiche toutes les pièces actives. */
    fun attachMap(googleMap: GoogleMap) {
        map = googleMap
        markers.clear()
        syncMarkers()
    }

    /** Oublie la carte (activité détruite) ; les marqueurs disparaissent avec elle. */
    fun detachMap() {
        map = null
        markers.clear()
    }

    /** Met les marqueurs en conformité avec les pièces actives de [GameState]. */
    fun syncMarkers() {
        val googleMap = map ?: return
        val coins = gameState.coins

        val iterator = markers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!coins.containsKey(entry.key)) {
                entry.value.remove()
                iterator.remove()
            }
        }

        if (markers.size == coins.size) return
        val icon = MarkerIcons.coin(context)
        val title = context.getString(R.string.core_coin_marker_title)
        for (coin in coins.values) {
            if (markers.containsKey(coin.id)) continue
            val options = MarkerOptions()
                .position(LatLng(coin.latitude, coin.longitude))
                .title(title)
                .icon(icon)
                .anchor(0.5f, 0.5f)
                .zIndex(MARKER_Z_INDEX)
            googleMap.addMarker(options)?.let { markers[coin.id] = it }
        }
    }
}
