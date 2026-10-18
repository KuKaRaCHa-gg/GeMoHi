package com.example.geomoneyhide

import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlin.math.cos

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
    /** Solde dépensable (v2). Absent des anciennes sauvegardes : vaut alors collectedPiecesCount. */
    val coinBalance: Int? = null,
    val ownedItems: List<String?>? = null,
    /** Expirations des boosts (epoch ms) par identifiant. */
    val boostExpiries: Map<String, Long?>? = null,
    val selectedMapTheme: String? = null,
    /** Format v1 (hérité) : positions des pièces sans identifiant. */
    val pieces: List<SavedLatLng?>? = null
)

data class SavedCoin(val id: Long? = null, val latitude: Double? = null, val longitude: Double? = null)

data class SavedLatLng(val latitude: Double? = null, val longitude: Double? = null)

/**
 * Conversion entre [GameState] et JSON, avec migration des anciennes sauvegardes.
 *
 * Migration v1 (sans champ version) → v2 :
 * - identifiants de cellules recalculés (l'ancienne formule décalait les cellules) ;
 * - anciennes pièces reçoivent un identifiant stable, limitées à [GameState.MAX_ACTIVE_COINS] ;
 * - le solde dépensable est initialisé avec le total des pièces collectées.
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
            coinBalance = state.coinBalance,
            ownedItems = state.ownedItems.toList(),
            boostExpiries = HashMap(state.boostExpiries),
            selectedMapTheme = state.selectedMapTheme,
            pieces = null
        )
        return gson.toJson(data)
    }

    /**
     * Applique le contenu JSON à [state].
     * @return true si une ancienne sauvegarde a été migrée (à réécrire au nouveau format)
     * @throws JsonParseException si le JSON est illisible
     */
    fun decodeInto(json: String, state: GameState): Boolean {
        val data = gson.fromJson(json, SaveData::class.java)
            ?: throw JsonParseException("Sauvegarde vide")

        val isLegacy = (data.version ?: 1) < 2
        val cells = data.exploredCells.orEmpty()
            .filterNotNull()
            .mapNotNull { id ->
                val cell = GridMath.parseCellId(id) ?: return@mapNotNull null
                if (isLegacy) migrateLegacyCell(cell) else id
            }
            .toHashSet()

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

        val collectedCount = data.collectedPiecesCount ?: 0
        val boosts = HashMap<String, Long>()
        data.boostExpiries.orEmpty().forEach { (id, expiry) -> if (expiry != null) boosts[id] = expiry }

        state.restore(
            coinsCollected = collectedCount,
            coinBalance = data.coinBalance ?: collectedCount,
            exploredCells = cells,
            color = data.zoneColor ?: GameState.DEFAULT_COLOR,
            coins = coins.distinctBy { it.id }.take(GameState.MAX_ACTIVE_COINS),
            nextCoinId = nextId,
            ownedItems = data.ownedItems.orEmpty().filterNotNull(),
            boostExpiries = boosts,
            mapTheme = data.selectedMapTheme ?: ShopCatalog.DEFAULT_MAP_THEME
        )
        return isLegacy
    }

    /**
     * Convertit un identifiant de l'ancienne grille : on prend le centre de l'ancienne cellule
     * (latitude du centre de rangée, longitude corrigée par le cosinus de cette latitude)
     * et on calcule sa cellule dans la nouvelle grille.
     */
    fun migrateLegacyCell(cell: CellIndex): String {
        val m = GridMath.METERS_PER_DEGREE
        val size = GridMath.CELL_SIZE_M
        val latCenter = (cell.x + 0.5) * size / m
        val lngCenter = (cell.y + 0.5) * size / (m * maxOf(cos(Math.toRadians(latCenter)), 1e-6))
        return GridMath.cellIdOf(latCenter, lngCenter)
    }

    private fun isValidPosition(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0
}
