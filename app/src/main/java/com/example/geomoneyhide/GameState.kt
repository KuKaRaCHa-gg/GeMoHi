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
 * Résultat d'un traitement de position : ce qui a changé dans l'état du jeu.
 */
data class GameUpdate(
    /** Identifiant de la cellule nouvellement explorée, ou null. */
    val newCellId: String? = null,
    /** Pièces ramassées automatiquement en découvrant la nouvelle cellule. */
    val discovered: Int = 0,
    /** Pièces ramassées par proximité (rayon de collecte). */
    val collected: Int = 0,
    /** Nouvelles pièces apparues. */
    val spawned: Int = 0
) {
    val coinsChanged: Boolean get() = discovered > 0 || collected > 0 || spawned > 0
    val hasChanges: Boolean get() = newCellId != null || coinsChanged

    companion object {
        val NONE = GameUpdate()
    }
}

/**
 * État du jeu et règles pures (sans dépendance Android, donc testable en JVM).
 *
 * Toutes les mutations doivent être faites sur un seul thread (le thread principal) ;
 * la persistance et la diffusion des changements sont assurées par [GameRepository].
 */
class GameState {

    companion object {
        const val EARTH_SURFACE_KM2 = 510_072_000.0 // Surface totale de la Terre en km²
        const val DEFAULT_COLOR = 0x2200FF00         // Vert clair translucide

        const val COINS_PER_KM2 = 3                  // Objectif : 3 pièces actives par km² exploré
        const val COLLECTION_RADIUS_M = 20.0         // Rayon de collecte par proximité
        const val SPAWN_MIN_DISTANCE_M = 500.0       // Distance minimale d'apparition autour du joueur
        const val SPAWN_MAX_DISTANCE_M = 2000.0      // Distance maximale d'apparition autour du joueur
        const val MIN_DISTANCE_FROM_EXPLORED_M = 500.0 // Distance minimale au centre des cellules explorées
        const val SPAWN_ATTEMPTS = 30                // Tentatives par pièce avant abandon
        const val INITIAL_COINS_MAX = 10             // Apparition initiale : 1 à 10 pièces
        const val MAX_SPAWN_PER_STEP = 25            // Évite des rafales de centaines de marqueurs
    }

    /** Nombre total de pièces collectées. */
    var coinsCollected: Int = 0
        internal set

    /** Couleur ARGB des cellules explorées (modifiée par l'interface). */
    var circleColor: Int = DEFAULT_COLOR

    private val exploredCellSet = HashSet<String>()
    private val coinMap = LinkedHashMap<Long, Coin>()
    internal var nextCoinId: Long = 1L
        private set

    /** Cellules explorées (identifiants "x:y"). */
    val exploredCells: Set<String> get() = exploredCellSet

    /** Pièces actives, indexées par identifiant. */
    val coins: Map<Long, Coin> get() = coinMap

    val cellCount: Int get() = exploredCellSet.size

    /** Surface explorée en km² (1 cellule = 1 km²). */
    val surfaceKm2: Double get() = exploredCellSet.size * GridMath.CELL_AREA_KM2

    /** Alias conservé pour compatibilité. */
    val totalSurface: Double get() = surfaceKm2

    /**
     * Traite une nouvelle position du joueur :
     * 1. explore la cellule courante (et ramasse les pièces qu'elle contient) ;
     * 2. ramasse les pièces dans le rayon de collecte ;
     * 3. fait apparaître des pièces si aucune n'existe ou si une cellule vient d'être découverte
     *    (objectif : [COINS_PER_KM2] pièces par km² exploré).
     */
    fun recordPosition(latitude: Double, longitude: Double, random: Random): GameUpdate {
        val cellId = GridMath.cellIdOf(latitude, longitude)
        val isNewCell = exploredCellSet.add(cellId)

        val discovered = if (isNewCell) removeCoins { GridMath.cellIdOf(it.latitude, it.longitude) == cellId } else 0
        val collected = removeCoins {
            GridMath.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= COLLECTION_RADIUS_M
        }
        coinsCollected += discovered + collected

        var spawned = 0
        if (isNewCell || coinMap.isEmpty()) {
            var toSpawn = cellCount * COINS_PER_KM2 - coinMap.size
            if (coinMap.isEmpty()) toSpawn = max(toSpawn, random.nextInt(INITIAL_COINS_MAX) + 1)
            spawned = spawnCoins(min(toSpawn, MAX_SPAWN_PER_STEP), latitude, longitude, random)
        }

        return GameUpdate(if (isNewCell) cellId else null, discovered, collected, spawned)
    }

    /**
     * Fait apparaître jusqu'à [count] pièces entre [SPAWN_MIN_DISTANCE_M] et [SPAWN_MAX_DISTANCE_M]
     * du point donné, hors des cellules explorées et loin de celles-ci.
     * @return le nombre de pièces réellement créées
     */
    fun spawnCoins(count: Int, latitude: Double, longitude: Double, random: Random): Int {
        var spawned = 0
        repeat(count) {
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

    /** Remplace tout l'état (utilisé au chargement de la sauvegarde). */
    internal fun restore(
        coinsCollected: Int,
        exploredCells: Collection<String>,
        color: Int,
        coins: Collection<Coin>,
        nextCoinId: Long
    ) {
        this.coinsCollected = max(0, coinsCollected)
        circleColor = color
        exploredCellSet.clear()
        exploredCellSet.addAll(exploredCells)
        coinMap.clear()
        for (coin in coins) coinMap[coin.id] = coin
        val maxId = coinMap.keys.maxOrNull() ?: 0L
        this.nextCoinId = max(nextCoinId, maxId + 1)
    }
}
