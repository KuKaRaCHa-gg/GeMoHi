package com.example.geomoneyhide

import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Pièce active sur la carte. [id] est stable (jamais réutilisé) et sert de clé aux marqueurs.
 */
data class Coin(val id: Long, val latitude: Double, val longitude: Double)

/**
 * Description d'un changement de l'état du jeu.
 */
data class GameUpdate(
    /** Identifiant de la cellule nouvellement explorée, ou null. */
    val newCellId: String? = null,
    /** Pièces ramassées automatiquement en découvrant la nouvelle cellule. */
    val discovered: Int = 0,
    /** Pièces ramassées par proximité (rayon de collecte). */
    val collected: Int = 0,
    /** Nouvelles pièces apparues. */
    val spawned: Int = 0,
    /** Pièces retirées car trop éloignées du joueur. */
    val despawned: Int = 0,
    /** Article acheté au Marché, ou null. */
    val purchasedItemId: String? = null,
    /** Réglage modifié (ex. thème de carte) sans autre changement. */
    val settingsChanged: Boolean = false
) {
    val coinsChanged: Boolean get() = discovered > 0 || collected > 0 || spawned > 0 || despawned > 0
    val hasChanges: Boolean
        get() = newCellId != null || coinsChanged || purchasedItemId != null || settingsChanged
}

/**
 * État du jeu et règles pures (sans dépendance Android, donc testable en JVM).
 *
 * Toutes les mutations doivent être faites sur un seul thread (le thread principal).
 * Les changements faits hors du flux de positions (achats, thème) sont signalés via
 * [changeListener], que [GameRepository] branche sur la sauvegarde et les observateurs.
 */
class GameState(
    /** Horloge (epoch ms), injectable pour les tests. */
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        const val EARTH_SURFACE_KM2 = 510_072_000.0 // Surface totale de la Terre en km²
        const val DEFAULT_COLOR = 0x2200FF00         // Vert clair translucide

        const val COINS_PER_KM2 = 3                  // 3 pièces actives par km² exploré...
        const val MAX_ACTIVE_COINS = 25              // ...dans la limite de 25 pièces actives
        const val COLLECTION_RADIUS_M = 20.0         // Rayon de collecte par proximité
        const val MAGNET_COLLECTION_RADIUS_M = 60.0  // Rayon de collecte avec l'aimant
        const val DESPAWN_DISTANCE_M = 3000.0        // Les pièces plus lointaines disparaissent
        const val SPAWN_MIN_DISTANCE_M = 500.0       // Distance minimale d'apparition autour du joueur
        const val SPAWN_MAX_DISTANCE_M = 2000.0      // Distance maximale d'apparition autour du joueur
        const val MIN_DISTANCE_FROM_EXPLORED_M = 500.0 // Distance minimale au centre des cellules explorées
        const val SPAWN_ATTEMPTS = 30                // Tentatives par pièce avant abandon
        const val INITIAL_COINS_MAX = 10             // Apparition initiale : 1 à 10 pièces
    }

    /** Total des pièces collectées depuis le début (statistique, jamais dépensé). */
    var coinsCollected: Int = 0
        internal set

    /** Solde dépensable au Marché. */
    var coinBalance: Int = 0
        internal set

    /** Couleur ARGB des cellules explorées (modifiée par l'interface). */
    var circleColor: Int = DEFAULT_COLOR

    /** Thème de carte sélectionné (identifiant du catalogue), sauvegardé. */
    var selectedMapTheme: String = ShopCatalog.DEFAULT_MAP_THEME
        set(value) {
            if (field == value) return
            field = value
            changeListener?.invoke(GameUpdate(settingsChanged = true))
        }

    /** Branché par [GameRepository] : sauvegarde + notification des observateurs. */
    internal var changeListener: ((GameUpdate) -> Unit)? = null

    private val exploredCellSet = HashSet<String>()
    private val coinMap = LinkedHashMap<Long, Coin>()
    private val ownedItemSet = HashSet<String>()
    private val boostExpiryMap = HashMap<String, Long>()
    internal var nextCoinId: Long = 1L
        private set

    /** Cellules explorées (identifiants "x:y"). */
    val exploredCells: Set<String> get() = exploredCellSet

    /** Pièces actives (au plus [MAX_ACTIVE_COINS]), indexées par identifiant. */
    val coins: Map<Long, Coin> get() = coinMap

    /** Articles permanents achetés (hors articles gratuits). */
    val ownedItems: Set<String> get() = ownedItemSet

    /** Expirations des boosts (epoch ms). */
    internal val boostExpiries: Map<String, Long> get() = boostExpiryMap

    val cellCount: Int get() = exploredCellSet.size

    /** Surface explorée en km² (1 cellule = 1 km²). */
    val surfaceKm2: Double get() = exploredCellSet.size * GridMath.CELL_AREA_KM2

    /** Alias conservé pour compatibilité. */
    val totalSurface: Double get() = surfaceKm2

    // ---------------------------------------------------------------------------------------
    // Marché
    // ---------------------------------------------------------------------------------------

    /** Vrai si l'article est possédé : gratuit, déjà acheté, ou boost actif. */
    fun isOwned(itemId: String): Boolean {
        val item = ShopCatalog.byId(itemId) ?: return false
        return when {
            item.isConsumable -> boostRemainingMs(itemId) > 0
            item.isFree -> true
            else -> itemId in ownedItemSet
        }
    }

    /**
     * Achète un article. Un boost peut être racheté : sa durée est alors prolongée.
     * Un identifiant inconnu renvoie [PurchaseResult.NOT_ENOUGH_COINS] (achat impossible).
     */
    fun purchase(itemId: String): PurchaseResult {
        val item = ShopCatalog.byId(itemId) ?: return PurchaseResult.NOT_ENOUGH_COINS
        if (!item.isConsumable && isOwned(itemId)) return PurchaseResult.ALREADY_OWNED
        if (coinBalance < item.price) return PurchaseResult.NOT_ENOUGH_COINS

        coinBalance -= item.price
        if (item.isConsumable) {
            val now = clock()
            val start = max(now, boostExpiryMap[itemId] ?: 0L)
            boostExpiryMap[itemId] = start + item.durationMinutes * 60_000L
        } else {
            ownedItemSet.add(itemId)
        }
        changeListener?.invoke(GameUpdate(purchasedItemId = itemId))
        return PurchaseResult.SUCCESS
    }

    /** Temps restant d'un boost en ms (0 s'il est inactif). */
    fun boostRemainingMs(itemId: String): Long {
        val expiry = boostExpiryMap[itemId] ?: return 0L
        return max(0L, expiry - clock())
    }

    private fun isBoostActive(itemId: String): Boolean = boostRemainingMs(itemId) > 0

    // ---------------------------------------------------------------------------------------
    // Exploration et pièces
    // ---------------------------------------------------------------------------------------

    /**
     * Traite une nouvelle position du joueur :
     * 1. explore la cellule courante (et ramasse les pièces qu'elle contient) ;
     * 2. ramasse les pièces dans le rayon de collecte (élargi par l'aimant) ;
     * 3. retire les pièces à plus de [DESPAWN_DISTANCE_M] ;
     * 4. complète jusqu'à min(3 × km², [MAX_ACTIVE_COINS]) pièces.
     */
    fun recordPosition(latitude: Double, longitude: Double, random: Random): GameUpdate {
        val cellId = GridMath.cellIdOf(latitude, longitude)
        val isNewCell = exploredCellSet.add(cellId)

        val discovered = if (isNewCell) removeCoins { GridMath.cellIdOf(it.latitude, it.longitude) == cellId } else 0
        val radius = if (isBoostActive(ShopCatalog.BOOST_MAGNET)) MAGNET_COLLECTION_RADIUS_M else COLLECTION_RADIUS_M
        val collected = removeCoins {
            GridMath.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= radius
        }
        val despawned = removeCoins {
            GridMath.distanceMeters(latitude, longitude, it.latitude, it.longitude) > DESPAWN_DISTANCE_M
        }

        val picked = discovered + collected
        if (picked > 0) {
            val gain = if (isBoostActive(ShopCatalog.BOOST_DOUBLE)) picked * 2 else picked
            coinsCollected += gain
            coinBalance += gain
        }

        var spawned = 0
        if (isNewCell || despawned > 0 || coinMap.isEmpty()) {
            var target = min(cellCount * COINS_PER_KM2, MAX_ACTIVE_COINS)
            if (coinMap.isEmpty()) target = max(target, random.nextInt(INITIAL_COINS_MAX) + 1)
            spawned = spawnCoins(target - coinMap.size, latitude, longitude, random)
        }

        return GameUpdate(
            newCellId = if (isNewCell) cellId else null,
            discovered = discovered,
            collected = collected,
            spawned = spawned,
            despawned = despawned
        )
    }

    /**
     * Fait apparaître jusqu'à [count] pièces (sans dépasser [MAX_ACTIVE_COINS]) entre
     * [SPAWN_MIN_DISTANCE_M] et [SPAWN_MAX_DISTANCE_M] du point donné, hors des cellules explorées.
     * @return le nombre de pièces réellement créées
     */
    fun spawnCoins(count: Int, latitude: Double, longitude: Double, random: Random): Int {
        var spawned = 0
        repeat(min(count, MAX_ACTIVE_COINS - coinMap.size)) {
            val position = findSpawnPosition(latitude, longitude, random) ?: return@repeat
            val coin = Coin(nextCoinId++, position.first, position.second)
            coinMap[coin.id] = coin
            spawned++
        }
        return spawned
    }

    private fun findSpawnPosition(latitude: Double, longitude: Double, random: Random): Pair<Double, Double>? {
        repeat(SPAWN_ATTEMPTS) {
            val distance = SPAWN_MIN_DISTANCE_M + random.nextDouble() * (SPAWN_MAX_DISTANCE_M - SPAWN_MIN_DISTANCE_M)
            val angle = random.nextDouble() * 2 * PI
            val candidate = GridMath.offset(latitude, longitude, distance, angle)
            if (isValidSpawnPosition(candidate.first, candidate.second)) return candidate
        }
        return null
    }

    /**
     * Une position est valide si sa cellule n'est pas explorée et si aucun centre de cellule
     * explorée n'est à moins de [MIN_DISTANCE_FROM_EXPLORED_M]. Seules les cellules voisines
     * sont examinées (coût constant, indépendant du nombre de cellules explorées).
     */
    fun isValidSpawnPosition(latitude: Double, longitude: Double): Boolean {
        if (GridMath.cellIdOf(latitude, longitude) in exploredCellSet) return false
        for (cell in GridMath.cellsAround(latitude, longitude, MIN_DISTANCE_FROM_EXPLORED_M)) {
            if (cell.id !in exploredCellSet) continue
            val (cLat, cLng) = GridMath.centerOf(cell)
            if (GridMath.distanceMeters(latitude, longitude, cLat, cLng) < MIN_DISTANCE_FROM_EXPLORED_M) return false
        }
        return true
    }

    private inline fun removeCoins(predicate: (Coin) -> Boolean): Int {
        var removed = 0
        val iterator = coinMap.values.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next())) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    /** Remplace tout l'état (chargement de la sauvegarde). Applique les plafonds. */
    internal fun restore(
        coinsCollected: Int,
        coinBalance: Int,
        exploredCells: Collection<String>,
        color: Int,
        coins: Collection<Coin>,
        nextCoinId: Long,
        ownedItems: Collection<String>,
        boostExpiries: Map<String, Long>,
        mapTheme: String
    ) {
        this.coinsCollected = max(0, coinsCollected)
        this.coinBalance = max(0, coinBalance)
        circleColor = color
        exploredCellSet.clear()
        exploredCellSet.addAll(exploredCells)
        coinMap.clear()
        for (coin in coins) {
            if (coinMap.size >= MAX_ACTIVE_COINS) break
            coinMap[coin.id] = coin
        }
        val maxId = coinMap.keys.maxOrNull() ?: 0L
        this.nextCoinId = max(nextCoinId, maxId + 1)
        ownedItemSet.clear()
        ownedItems.filterTo(ownedItemSet) { ShopCatalog.byId(it)?.isConsumable == false }
        boostExpiryMap.clear()
        val now = clock()
        for ((id, expiry) in boostExpiries) {
            if (ShopCatalog.byId(id)?.isConsumable == true && expiry > now) boostExpiryMap[id] = expiry
        }
        // Sans déclencher changeListener (pas de sauvegarde au chargement)
        val theme = ShopCatalog.byId(mapTheme)
        val listener = changeListener
        changeListener = null
        selectedMapTheme = if (theme?.category == ShopCategory.MAP_THEME) mapTheme else ShopCatalog.DEFAULT_MAP_THEME
        changeListener = listener
    }
}
