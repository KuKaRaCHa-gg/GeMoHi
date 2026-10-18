package com.example.geomoneyhide

import com.google.gson.Gson
import com.google.gson.JsonParseException

/**
 * Modèle sérialisé dans game_data.json.
 *
 * Tous les champs sont nullables : Gson n'appelle pas les constructeurs Kotlin et peut laisser
 * des null même pour des types déclarés non nuls. Les champs historiques inutiles
 * (exploredAreas, playerPositions) sont simplement ignorés à la lecture.
 * Classes conservées par R8 (voir proguard-rules.pro).
 */
data class SaveData(
    val version: Int? = null,
    val collectedPiecesCount: Int? = null,
    val exploredCells: List<String?>? = null,
    val zoneColor: Int? = null,
    /** Format v2 : pièces avec identifiant stable. */
    val coins: List<SavedCoin?>? = null,
    val nextCoinId: Long? = null,
    /** Format v1 (hérité) : positions des pièces sans identifiant. */
    val pieces: List<SavedLatLng?>? = null
)

data class SavedCoin(val id: Long? = null, val latitude: Double? = null, val longitude: Double? = null)

data class SavedLatLng(val latitude: Double? = null, val longitude: Double? = null)

/**
 * Conversion entre [GameState] et JSON, avec migration des anciennes sauvegardes.
 */
object SaveCodec {
    const val CURRENT_VERSION = 2

    private val gson = Gson()

    fun encode(state: GameState): String {
        val data = SaveData(
            version = CURRENT_VERSION,
            collectedPiecesCount = state.coinsCollected,
            exploredCells = state.exploredCells.toList(),
            zoneColor = state.circleColor,
            coins = state.coins.values.map { SavedCoin(it.id, it.latitude, it.longitude) },
            nextCoinId = state.nextCoinId,
            pieces = null
        )
        return gson.toJson(data)
    }

    /**
     * Applique le contenu JSON à [state].
     * @throws JsonParseException si le JSON est illisible
     */
    fun decodeInto(json: String, state: GameState) {
        val data = gson.fromJson(json, SaveData::class.java)
            ?: throw JsonParseException("Sauvegarde vide")

        val cells = data.exploredCells.orEmpty()
            .filterNotNull()
            .filter { GridMath.parseCellId(it) != null }

        val coins = ArrayList<Coin>()
        var nextId = data.nextCoinId ?: 1L
        data.coins.orEmpty().filterNotNull().forEach { saved ->
            val lat = saved.latitude ?: return@forEach
            val lng = saved.longitude ?: return@forEach
            if (!isValidPosition(lat, lng)) return@forEach
            val id = saved.id ?: return@forEach
            coins.add(Coin(id, lat, lng))
        }
        nextId = maxOf(nextId, (coins.maxOfOrNull { it.id } ?: 0L) + 1)
        // Migration v1 : les anciennes pièces reçoivent un nouvel identifiant
        data.pieces.orEmpty().filterNotNull().forEach { legacy ->
            val lat = legacy.latitude ?: return@forEach
            val lng = legacy.longitude ?: return@forEach
            if (!isValidPosition(lat, lng)) return@forEach
            coins.add(Coin(nextId++, lat, lng))
        }

        state.restore(
            coinsCollected = data.collectedPiecesCount ?: 0,
            exploredCells = cells,
            color = data.zoneColor ?: GameState.DEFAULT_COLOR,
            coins = coins.distinctBy { it.id },
            nextCoinId = nextId
        )
    }

    private fun isValidPosition(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0
}
