package com.example.geomoneyhide

/** Catégories d'articles du Marché. */
enum class ShopCategory { ZONE_COLOR, MAP_THEME, BOOST }

/**
 * Article du Marché. [durationMinutes] ne concerne que les boosts (consommables).
 * Les noms, icônes et couleurs sont associés aux [id] côté interface.
 */
data class ShopItem(
    val id: String,
    val category: ShopCategory,
    val price: Int,
    val durationMinutes: Int = 0
) {
    val isConsumable: Boolean get() = category == ShopCategory.BOOST
    val isFree: Boolean get() = price == 0 && !isConsumable
}

/** Résultat d'un achat. */
enum class PurchaseResult { SUCCESS, NOT_ENOUGH_COINS, ALREADY_OWNED }

/** Catalogue figé du Marché (identifiants et prix). */
object ShopCatalog {
    const val BOOST_MAGNET = "boost_magnet"
    const val BOOST_DOUBLE = "boost_double"
    const val DEFAULT_MAP_THEME = "theme_night"

    val items: List<ShopItem> = listOf(
        // Couleurs de zone (déblocage permanent)
        ShopItem("color_green", ShopCategory.ZONE_COLOR, 0),
        ShopItem("color_blue", ShopCategory.ZONE_COLOR, 0),
        ShopItem("color_violet", ShopCategory.ZONE_COLOR, 50),
        ShopItem("color_orange", ShopCategory.ZONE_COLOR, 50),
        ShopItem("color_pink", ShopCategory.ZONE_COLOR, 80),
        ShopItem("color_cyan", ShopCategory.ZONE_COLOR, 80),
        ShopItem("color_red", ShopCategory.ZONE_COLOR, 120),
        ShopItem("color_gold", ShopCategory.ZONE_COLOR, 200),
        ShopItem("color_custom", ShopCategory.ZONE_COLOR, 300), // débloque le curseur de teinte libre
        // Thèmes de carte (déblocage permanent)
        ShopItem("theme_night", ShopCategory.MAP_THEME, 0),
        ShopItem("theme_classic", ShopCategory.MAP_THEME, 0),
        ShopItem("theme_retro", ShopCategory.MAP_THEME, 150),
        ShopItem("theme_neon", ShopCategory.MAP_THEME, 250),
        // Boosts (consommables, rachetables : un nouvel achat prolonge la durée)
        ShopItem(BOOST_MAGNET, ShopCategory.BOOST, 30, durationMinutes = 30), // rayon de collecte 20 m → 60 m
        ShopItem(BOOST_DOUBLE, ShopCategory.BOOST, 40, durationMinutes = 30)  // chaque pièce compte double
    )

    private val itemsById: Map<String, ShopItem> = items.associateBy { it.id }

    fun byId(id: String): ShopItem? = itemsById[id]
}
