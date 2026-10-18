package com.example.geomoneyhide

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.transition.TransitionManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Interface du jeu : HUD (solde, surface, zones, boosts), barre d'actions, feuilles
 * (Marché, couleur, règles, crédits), retours animés et style de la carte.
 *
 * N'utilise de [GameState] que : circleColor, coinBalance, isOwned, purchase,
 * boostRemainingMs et selectedMapTheme.
 */
class UIManager(
    private val activity: AppCompatActivity,
    private val gameState: GameState,
    private val onColorChanged: () -> Unit
) {

    private companion object {
        const val TAG = "UIManager"
        const val EARTH_SURFACE_KM2 = 510_072_000.0
        const val CUSTOM_COLOR_ID = "color_custom"
        const val DEFAULT_OPACITY_PERCENT = 30
        const val MIN_OPACITY_PERCENT = 5
        const val MAX_OPACITY_PERCENT = 90
        const val CUSTOM_SATURATION = 0.78f
        const val CUSTOM_VALUE = 0.95f
        const val TICK_MS = 1_000L
        val LOCALE: Locale = Locale.FRANCE
    }

    /** Apparence d'une couleur de zone du Marché. */
    private class ZoneColor(val id: String, @StringRes val name: Int, @ColorRes val color: Int)

    private val zoneColors = listOf(
        ZoneColor("color_green", R.string.item_color_green, R.color.zone_green),
        ZoneColor("color_blue", R.string.item_color_blue, R.color.zone_blue),
        ZoneColor("color_violet", R.string.item_color_violet, R.color.zone_violet),
        ZoneColor("color_orange", R.string.item_color_orange, R.color.zone_orange),
        ZoneColor("color_pink", R.string.item_color_pink, R.color.zone_pink),
        ZoneColor("color_cyan", R.string.item_color_cyan, R.color.zone_cyan),
        ZoneColor("color_red", R.string.item_color_red, R.color.zone_red),
        ZoneColor("color_gold", R.string.item_color_gold, R.color.zone_gold)
    )

    // ------------------------------------------------------------------ Vues du HUD
    private var bound = false
    private lateinit var root: ViewGroup
    private lateinit var hudCard: ViewGroup
    private lateinit var bottomBar: View
    private lateinit var fab: FloatingActionButton
    private lateinit var coinPill: View
    private lateinit var coinIcon: ImageView
    private lateinit var coinsText: TextView
    private lateinit var coinGainText: TextView
    private lateinit var surfaceTile: View
    private lateinit var surfaceText: TextView
    private lateinit var percentText: TextView
    private lateinit var cellsTile: View
    private lateinit var cellsText: TextView
    private lateinit var cellsLabel: TextView
    private lateinit var boostsContainer: LinearLayout
    private val boostPills = HashMap<String, TextView>()

    // ------------------------------------------------------------------ État d'affichage
    private var shownCoins = 0
    private var shownSurface = 0.0
    private var shownCells = 0
    private var coinsAnimator: ValueAnimator? = null
    private var surfaceAnimator: ValueAnimator? = null
    private var cellsAnimator: ValueAnimator? = null
    private var celebrateAnimator: AnimatorSet? = null

    private var map: GoogleMap? = null
    private var recenterAction: (() -> Unit)? = null
    private var systemBarLeft = 0
    private var systemBarRight = 0

    /** Rafraîchit le Marché s'il est ouvert (solde + cartes). */
    private var shopRefresher: (() -> Unit)? = null

    private val boostTicker = Runnable { updateBoosts() }

    // =====================================================================================
    // API publique
    // =====================================================================================

    fun bindViews() {
        root = activity.findViewById(R.id.ui_container)
        hudCard = activity.findViewById(R.id.hud_card)
        bottomBar = activity.findViewById(R.id.bottom_bar)
        fab = activity.findViewById(R.id.fab_refresh)
        coinPill = activity.findViewById(R.id.hud_coin_pill)
        coinIcon = activity.findViewById(R.id.hud_coin_icon)
        coinsText = activity.findViewById(R.id.hud_coins)
        coinGainText = activity.findViewById(R.id.hud_coin_gain)
        surfaceTile = activity.findViewById(R.id.hud_surface_tile)
        surfaceText = activity.findViewById(R.id.hud_surface)
        percentText = activity.findViewById(R.id.hud_percent)
        cellsTile = activity.findViewById(R.id.hud_cells_tile)
        cellsText = activity.findViewById(R.id.hud_cells)
        cellsLabel = activity.findViewById(R.id.hud_cells_label)
        boostsContainer = activity.findViewById(R.id.hud_boosts)

        activity.findViewById<View>(R.id.button_shop).setOnClickListener { showShopSheet() }
        activity.findViewById<View>(R.id.button_color).setOnClickListener { showColorPicker() }
        activity.findViewById<View>(R.id.button_rules).setOnClickListener { showRulesSheet() }
        activity.findViewById<View>(R.id.button_credits).setOnClickListener { showCreditsSheet() }
        fab.setOnClickListener { recenterAction?.invoke() }

        // Pièce en « 3D » lors de la rotation
        coinIcon.cameraDistance = 8_000f * activity.resources.displayMetrics.density

        applyWindowInsets()

        // Padding de la carte recalculé à chaque changement de taille du HUD
        val relayout = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) updateMapPadding()
        }
        hudCard.addOnLayoutChangeListener(relayout)
        bottomBar.addOnLayoutChangeListener(relayout)
        root.addOnLayoutChangeListener(relayout)

        bound = true
        renderCoins(shownCoins)
        renderSurface(shownSurface)
        renderCells(shownCells)
        updateBoosts()
    }

    fun updateStats(coins: Int, surfaceKm2: Double, cellCount: Int, animate: Boolean = true) {
        if (!bound) return
        val canAnimate = animate && ValueAnimator.areAnimatorsEnabled()

        coinsAnimator?.cancel()
        coinsAnimator = if (canAnimate && coins != shownCoins) {
            ValueAnimator.ofInt(shownCoins, coins).apply {
                duration = counterDuration(abs(coins - shownCoins))
                interpolator = DecelerateInterpolator()
                addUpdateListener { renderCoins(it.animatedValue as Int) }
                start()
            }
        } else {
            renderCoins(coins)
            null
        }

        surfaceAnimator?.cancel()
        surfaceAnimator = if (canAnimate && surfaceKm2 != shownSurface) {
            ValueAnimator.ofFloat(shownSurface.toFloat(), surfaceKm2.toFloat()).apply {
                duration = counterDuration(abs(surfaceKm2 - shownSurface).roundToInt())
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    renderSurface(if (it.animatedFraction >= 1f) surfaceKm2 else (it.animatedValue as Float).toDouble())
                }
                start()
            }
        } else {
            renderSurface(surfaceKm2)
            null
        }

        cellsAnimator?.cancel()
        cellsAnimator = if (canAnimate && cellCount != shownCells) {
            ValueAnimator.ofInt(shownCells, cellCount).apply {
                duration = counterDuration(abs(cellCount - shownCells))
                interpolator = DecelerateInterpolator()
                addUpdateListener { renderCells(it.animatedValue as Int) }
                start()
            }
        } else {
            renderCells(cellCount)
            null
        }

        shopRefresher?.invoke()
    }

    fun showCollected(count: Int) {
        if (!bound || count <= 0) return
        celebrate(count)
        showSnack(
            root, quantity(R.plurals.feedback_collected, count),
            R.drawable.ic_coin, color(R.color.gold_400), anchored = true
        )
    }

    fun showDiscovered(count: Int) {
        if (!bound || count <= 0) return
        celebrate(count)
        showSnack(
            root, quantity(R.plurals.feedback_discovered, count),
            R.drawable.ic_sparkle, color(R.color.teal_400), anchored = true
        )
    }

    fun showSpawned(count: Int) {
        if (!bound || count <= 0) return
        showSnack(
            root, quantity(R.plurals.feedback_spawned, count),
            R.drawable.ic_near_me, color(R.color.gold_300), anchored = true
        )
    }

    fun showMessage(text: String) {
        if (!bound) return
        showSnack(root, text, R.drawable.ic_info, color(R.color.text_secondary), anchored = true)
    }

    fun applyMapStyle(map: GoogleMap) {
        this.map = map
        try {
            map.uiSettings.apply {
                isMapToolbarEnabled = false
                isMyLocationButtonEnabled = false
                isCompassEnabled = true
                isZoomControlsEnabled = false
                isIndoorLevelPickerEnabled = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Réglages de la carte impossibles", e)
        }
        applySelectedTheme(map)
        updateMapPadding()
        if (bound) root.post { updateMapPadding() }
    }

    fun setRecenterAction(action: () -> Unit) {
        recenterAction = action
    }

    /** Met à jour les pastilles de boosts actifs du HUD (se rafraîchit seul chaque seconde). */
    fun updateBoosts() {
        if (!bound) return
        boostsContainer.removeCallbacks(boostTicker)

        val active = ShopCatalog.items
            .filter { it.category == ShopCategory.BOOST }
            .mapNotNull { item -> gameState.boostRemainingMs(item.id).takeIf { it > 0L }?.let { item to it } }
        val activeIds = active.map { it.first.id }.toSet()

        val wasVisible = boostsContainer.isVisible
        val shouldShow = active.isNotEmpty()
        if (wasVisible != shouldShow || boostPills.keys != activeIds) {
            TransitionManager.beginDelayedTransition(hudCard)
        }

        // Retire les pastilles expirées
        val expired = boostPills.keys.filter { it !in activeIds }
        expired.forEach { id -> boostPills.remove(id)?.let { boostsContainer.removeView(it) } }

        // Crée / met à jour les pastilles actives
        for ((item, remaining) in active) {
            val pill = boostPills.getOrPut(item.id) {
                val view = LayoutInflater.from(activity)
                    .inflate(R.layout.item_boost_pill, boostsContainer, false) as TextView
                tintedDrawable(boostIcon(item.id), color(R.color.accent_boost), 16)?.let {
                    view.setCompoundDrawablesRelative(it, null, null, null)
                }
                boostsContainer.addView(view)
                view
            }
            val name = activity.getString(itemName(item.id))
            val time = formatDuration(remaining)
            pill.text = activity.getString(R.string.boost_pill, name, time)
            pill.contentDescription = activity.getString(R.string.boost_pill_a11y, name, time)
        }

        boostsContainer.isVisible = shouldShow
        // Pas de rafraîchissement quand l'écran n'est plus visible (relancé par MapsActivity.onResume)
        if (shouldShow && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            boostsContainer.postDelayed(boostTicker, TICK_MS)
        }
    }

    // =====================================================================================
    // HUD : rendu des compteurs
    // =====================================================================================

    private fun renderCoins(value: Int) {
        shownCoins = value
        coinsText.text = formatInt(value)
        coinPill.contentDescription = activity.getString(R.string.shop_balance_a11y, value)
    }

    private fun renderSurface(value: Double) {
        shownSurface = value
        val surface = formatSurface(value)
        val percent = formatEarthPercent(value)
        surfaceText.text = activity.getString(R.string.hud_surface_value, surface)
        percentText.text = activity.getString(R.string.hud_earth_percent, percent)
        surfaceTile.contentDescription = activity.getString(R.string.hud_surface_a11y, surface, percent)
    }

    private fun renderCells(value: Int) {
        shownCells = value
        cellsText.text = formatInt(value)
        cellsLabel.text = activity.resources.getQuantityString(R.plurals.hud_cells_unit, value)
        cellsTile.contentDescription = quantity(R.plurals.hud_cells_a11y, value)
    }

    private fun counterDuration(delta: Int): Long = (450L + delta.coerceAtMost(50) * 12L).coerceAtMost(1_100L)

    // =====================================================================================
    // Animations de récompense
    // =====================================================================================

    /** Pièce qui tourne + rebond du compteur + bulle « +N » + vibration légère. */
    private fun celebrate(count: Int) {
        if (!bound) return
        val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        coinPill.performHapticFeedback(haptic)
        if (!ValueAnimator.areAnimatorsEnabled()) return

        celebrateAnimator?.cancel()
        coinIcon.rotationY = 0f
        celebrateAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(coinIcon, View.ROTATION_Y, 0f, 360f).setDuration(650),
                ObjectAnimator.ofFloat(coinIcon, View.SCALE_X, 1f, 1.4f, 1f).setDuration(650),
                ObjectAnimator.ofFloat(coinIcon, View.SCALE_Y, 1f, 1.4f, 1f).setDuration(650),
                ObjectAnimator.ofFloat(coinPill, View.SCALE_X, 1f, 1.08f, 1f).setDuration(420),
                ObjectAnimator.ofFloat(coinPill, View.SCALE_Y, 1f, 1.08f, 1f).setDuration(420)
            )
            interpolator = DecelerateInterpolator()
            start()
        }

        val travel = activity.resources.getDimension(R.dimen.coin_gain_travel)
        coinGainText.animate().cancel()
        coinGainText.text = activity.getString(R.string.coin_gain, count)
        coinGainText.visibility = View.VISIBLE
        coinGainText.alpha = 0f
        coinGainText.translationY = travel
        coinGainText.scaleX = 0.7f
        coinGainText.scaleY = 0.7f
        coinGainText.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(0)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                coinGainText.animate()
                    .alpha(0f).translationY(-travel)
                    .setStartDelay(750)
                    .setDuration(380)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        coinGainText.visibility = View.INVISIBLE
                        coinGainText.animate().setStartDelay(0)
                    }
                    .start()
            }
            .start()
    }

    private fun flipCoin(icon: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        icon.cameraDistance = 8_000f * activity.resources.displayMetrics.density
        ObjectAnimator.ofFloat(icon, View.ROTATION_Y, 0f, 360f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun shake(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val d = dp(6).toFloat()
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -d, d, -d * 0.6f, d * 0.6f, 0f).apply {
            duration = 380
            start()
        }
    }

    // =====================================================================================
    // Bord à bord & carte
    // =====================================================================================

    private fun applyWindowInsets() {
        val base = activity.resources.getDimensionPixelSize(R.dimen.hud_margin)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            systemBarLeft = bars.left
            systemBarRight = bars.right
            hudCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = base + bars.top
                marginStart = base + bars.left
                marginEnd = base + bars.right
            }
            bottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = base + bars.bottom
                marginStart = base + bars.left
                marginEnd = base + bars.right
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Évite que le logo Google et la boussole soient masqués par le HUD. */
    private fun updateMapPadding() {
        val googleMap = map ?: return
        if (!bound || root.height == 0) return
        val extra = activity.resources.getDimensionPixelSize(R.dimen.map_padding_extra)
        val top = hudCard.bottom + extra
        val bottom = (root.height - bottomBar.top).coerceAtLeast(0) + extra
        try {
            googleMap.setPadding(systemBarLeft, top, systemBarRight, bottom)
        } catch (e: Exception) {
            Log.w(TAG, "Padding de la carte impossible", e)
        }
    }

    private fun applySelectedTheme(googleMap: GoogleMap) {
        val raw = mapStyleRes(gameState.selectedMapTheme)
        try {
            val ok = if (raw == null) {
                googleMap.setMapStyle(null)
            } else {
                googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(activity, raw))
            }
            if (!ok) Log.w(TAG, "Style de carte refusé : ${gameState.selectedMapTheme}")
        } catch (e: Exception) {
            Log.e(TAG, "Impossible d'appliquer le style de carte", e)
        }
    }

    private fun mapStyleRes(themeId: String): Int? = when (themeId) {
        "theme_classic" -> null
        "theme_retro" -> R.raw.map_style_retro
        "theme_neon" -> R.raw.map_style_neon
        else -> R.raw.map_style_night
    }

    // =====================================================================================
    // Snackbars
    // =====================================================================================

    private fun showSnack(
        host: View,
        text: CharSequence,
        @DrawableRes icon: Int?,
        @ColorInt iconTint: Int,
        anchored: Boolean
    ) {
        try {
            val snackbar = Snackbar.make(host, text, Snackbar.LENGTH_SHORT)
            if (anchored && bound) snackbar.setAnchorView(fab)
            val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            if (icon != null && textView != null) {
                tintedDrawable(icon, iconTint, 20)?.let {
                    textView.setCompoundDrawablesRelative(it, null, null, null)
                    textView.compoundDrawablePadding = dp(12)
                }
            }
            snackbar.show()
        } catch (e: Exception) {
            Log.w(TAG, "Snackbar impossible : $text", e)
        }
    }

    // =====================================================================================
    // Marché
    // =====================================================================================

    private fun showShopSheet(initial: ShopCategory = ShopCategory.ZONE_COLOR) {
        val dialog = BottomSheetDialog(activity)
        val inflater = LayoutInflater.from(dialog.context)
        val sheet = inflater.inflate(R.layout.bottom_sheet_shop, null)
        val balancePill = sheet.findViewById<View>(R.id.shop_balance_pill)
        val balanceIcon = sheet.findViewById<ImageView>(R.id.shop_balance_icon)
        val balanceText = sheet.findViewById<TextView>(R.id.shop_balance)
        val tabs = sheet.findViewById<TabLayout>(R.id.shop_tabs)
        val list = sheet.findViewById<LinearLayout>(R.id.shop_list)

        val categories = listOf(ShopCategory.ZONE_COLOR, ShopCategory.MAP_THEME, ShopCategory.BOOST)
        var currentCategory = initial
        val cards = ArrayList<Pair<ShopItem, View>>()

        var shownBalance = gameState.coinBalance
        var balanceAnimator: ValueAnimator? = null
        fun renderBalance(value: Int) {
            shownBalance = value
            balanceText.text = formatInt(value)
            balancePill.contentDescription = activity.getString(R.string.shop_balance_a11y, value)
        }
        fun refreshBalance(animate: Boolean) {
            val target = gameState.coinBalance
            if (target == shownBalance) return
            balanceAnimator?.cancel()
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                balanceAnimator = ValueAnimator.ofInt(shownBalance, target).apply {
                    duration = counterDuration(abs(target - shownBalance))
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { renderBalance(it.animatedValue as Int) }
                    start()
                }
            } else {
                renderBalance(target)
            }
        }
        renderBalance(shownBalance)

        fun refreshCards() {
            for ((item, card) in cards) bindShopCard(item, card)
        }

        fun onItemAction(item: ShopItem) {
            val usable = item.category != ShopCategory.BOOST && gameState.isOwned(item.id)
            if (usable) {
                if (item.id == CUSTOM_COLOR_ID) {
                    dialog.dismiss()
                    showColorPicker()
                    return
                }
                useItem(item, sheet)
            } else {
                val success = performPurchase(item, sheet, onFailure = { shake(balancePill) })
                if (success) {
                    flipCoin(balanceIcon)
                    refreshBalance(animate = true)
                    when (item.category) {
                        ShopCategory.ZONE_COLOR -> if (item.id != CUSTOM_COLOR_ID) applyZoneColor(item.id)
                        ShopCategory.MAP_THEME -> selectTheme(item.id)
                        ShopCategory.BOOST -> Unit
                    }
                }
            }
            refreshCards()
        }

        fun renderList() {
            list.removeAllViews()
            cards.clear()
            ShopCatalog.items.filter { it.category == currentCategory }.forEach { item ->
                val card = inflater.inflate(R.layout.item_shop, list, false)
                card.findViewById<MaterialButton>(R.id.shop_item_action).setOnClickListener { onItemAction(item) }
                bindShopCard(item, card)
                list.addView(card)
                cards.add(item to card)
            }
        }

        tabs.getTabAt(categories.indexOf(initial).coerceAtLeast(0))?.select()
        renderList()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentCategory = categories.getOrElse(tab.position) { ShopCategory.ZONE_COLOR }
                renderList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        // Compte à rebours des boosts + solde en direct
        val ticker = object : Runnable {
            override fun run() {
                refreshBalance(animate = true)
                refreshCards()
                sheet.postDelayed(this, TICK_MS)
            }
        }
        shopRefresher = {
            refreshBalance(animate = true)
            refreshCards()
        }
        dialog.setOnShowListener { sheet.postDelayed(ticker, TICK_MS) }
        dialog.setOnDismissListener {
            sheet.removeCallbacks(ticker)
            balanceAnimator?.cancel()
            shopRefresher = null
        }

        dialog.setContentView(sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    /** Remplit / met à jour une carte d'article du Marché. */
    private fun bindShopCard(item: ShopItem, card: View) {
        val preview = card.findViewById<FrameLayout>(R.id.shop_item_preview)
        val icon = card.findViewById<ImageView>(R.id.shop_item_icon)
        val nameView = card.findViewById<TextView>(R.id.shop_item_name)
        val descView = card.findViewById<TextView>(R.id.shop_item_desc)
        val stateView = card.findViewById<TextView>(R.id.shop_item_state)
        val action = card.findViewById<MaterialButton>(R.id.shop_item_action)

        val name = activity.getString(itemName(item.id))
        nameView.text = name
        val description = activity.getString(itemDescription(item.id))
        descView.text = if (item.durationMinutes > 0) {
            description + "\n" + activity.getString(R.string.shop_duration, item.durationMinutes)
        } else {
            description
        }

        // Aperçu
        when (item.category) {
            ShopCategory.ZONE_COLOR -> {
                if (item.id == CUSTOM_COLOR_ID) {
                    preview.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        gradientType = GradientDrawable.SWEEP_GRADIENT
                        colors = rainbowColors()
                    }
                    icon.setImageResource(R.drawable.ic_palette)
                    icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
                } else {
                    preview.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(zoneColorValue(item.id))
                        setStroke(dp(1), color(R.color.hud_stroke))
                    }
                    icon.setImageDrawable(null)
                }
            }
            ShopCategory.MAP_THEME -> {
                preview.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, themePreviewColors(item.id)
                ).apply {
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), color(R.color.hud_stroke))
                }
                icon.setImageResource(R.drawable.ic_map)
                icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            ShopCategory.BOOST -> {
                preview.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(R.color.boost_pill_bg))
                }
                icon.setImageResource(boostIcon(item.id))
                icon.imageTintList = ColorStateList.valueOf(color(R.color.accent_boost))
            }
        }

        // État + bouton
        val owned = gameState.isOwned(item.id)
        stateView.isVisible = false
        action.isEnabled = true
        action.alpha = 1f
        if (item.category == ShopCategory.BOOST) {
            val remaining = gameState.boostRemainingMs(item.id)
            if (remaining > 0L) {
                stateView.text = activity.getString(R.string.shop_active, formatDuration(remaining))
                stateView.isVisible = true
            }
            showPrice(action, item, name)
        } else if (!owned) {
            showPrice(action, item, name)
        } else {
            if (item.price > 0) {
                stateView.text = activity.getString(R.string.shop_owned)
                stateView.isVisible = true
            }
            if (isEquipped(item)) {
                action.text = activity.getString(R.string.shop_equipped)
                action.setIconResource(R.drawable.ic_check)
                action.isEnabled = false
                action.alpha = 0.6f
                action.contentDescription = null
            } else {
                action.text = activity.getString(R.string.shop_use)
                action.icon = null
                action.contentDescription = activity.getString(R.string.shop_use_a11y, name)
            }
        }
    }

    private fun showPrice(action: MaterialButton, item: ShopItem, name: String) {
        action.text = if (item.price == 0) activity.getString(R.string.shop_free) else formatInt(item.price)
        action.setIconResource(R.drawable.ic_coin)
        action.contentDescription = activity.getString(R.string.shop_buy_a11y, name, item.price)
    }

    private fun isEquipped(item: ShopItem): Boolean = when (item.category) {
        ShopCategory.MAP_THEME -> gameState.selectedMapTheme == item.id
        ShopCategory.ZONE_COLOR ->
            item.id != CUSTOM_COLOR_ID && opaque(gameState.circleColor) == zoneColorValue(item.id)
        ShopCategory.BOOST -> false
    }

    private fun useItem(item: ShopItem, host: View) {
        when (item.category) {
            ShopCategory.ZONE_COLOR -> {
                applyZoneColor(item.id)
                showSnack(host, activity.getString(R.string.color_applied), R.drawable.ic_palette,
                    zoneColorValue(item.id), anchored = false)
            }
            ShopCategory.MAP_THEME -> {
                selectTheme(item.id)
                showSnack(host, activity.getString(R.string.shop_theme_applied, activity.getString(itemName(item.id))),
                    R.drawable.ic_map, color(R.color.gold_400), anchored = false)
            }
            ShopCategory.BOOST -> Unit
        }
    }

    /** Achat + retour visuel. Renvoie vrai en cas de succès. */
    private fun performPurchase(item: ShopItem, host: View, onFailure: () -> Unit = {}): Boolean {
        val name = activity.getString(itemName(item.id))
        return when (gameState.purchase(item.id)) {
            PurchaseResult.SUCCESS -> {
                showSnack(host, activity.getString(R.string.shop_result_success, name),
                    R.drawable.ic_coin, color(R.color.gold_400), anchored = false)
                host.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                updateBoosts()
                true
            }
            PurchaseResult.NOT_ENOUGH_COINS -> {
                val missing = (item.price - gameState.coinBalance).coerceAtLeast(1)
                showSnack(host, activity.getString(R.string.shop_result_not_enough, missing),
                    R.drawable.ic_lock, color(R.color.red_300), anchored = false)
                onFailure()
                false
            }
            PurchaseResult.ALREADY_OWNED -> {
                showSnack(host, activity.getString(R.string.shop_result_owned, name),
                    R.drawable.ic_check, color(R.color.teal_400), anchored = false)
                false
            }
        }
    }

    /** Applique une couleur de la palette en conservant l'opacité actuelle. */
    private fun applyZoneColor(itemId: String) {
        val alpha = Color.alpha(gameState.circleColor).takeIf { it > 0 }
            ?: percentToAlpha(DEFAULT_OPACITY_PERCENT)
        gameState.circleColor = ColorUtils.setAlphaComponent(zoneColorValue(itemId), alpha)
        onColorChanged()
    }

    private fun selectTheme(themeId: String) {
        gameState.selectedMapTheme = themeId
        map?.let { applySelectedTheme(it) }
    }

    // =====================================================================================
    // Sélecteur de couleur
    // =====================================================================================

    private fun showColorPicker() {
        val dialog = BottomSheetDialog(activity)
        val inflater = LayoutInflater.from(dialog.context)
        val sheet = inflater.inflate(R.layout.bottom_sheet_color, null)
        val previewFrame = sheet.findViewById<View>(R.id.color_preview_frame)
        val previewZone = sheet.findViewById<View>(R.id.color_preview_zone)
        val previewLabel = sheet.findViewById<TextView>(R.id.color_preview_label)
        val rows = listOf(
            sheet.findViewById<LinearLayout>(R.id.swatch_row_1),
            sheet.findViewById<LinearLayout>(R.id.swatch_row_2)
        )
        val unlockCustom = sheet.findViewById<MaterialButton>(R.id.color_unlock_custom)
        val hueStrip = sheet.findViewById<View>(R.id.color_hue_strip)
        val hueSlider = sheet.findViewById<Slider>(R.id.color_hue)
        val opacitySlider = sheet.findViewById<Slider>(R.id.color_opacity)

        // État initial = couleur actuelle
        val current = gameState.circleColor
        var rgb = opaque(current)
        var opacity = alphaToPercent(Color.alpha(current)).coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
        var selectedId: String? = zoneColors.firstOrNull { zoneColorValue(it.id) == rgb }?.id

        val hsv = FloatArray(3)
        Color.colorToHSV(rgb, hsv)
        hueSlider.value = hsv[0].roundToInt().coerceIn(0, 360).toFloat()
        opacitySlider.value = opacity.toFloat()
        hueSlider.setLabelFormatter { activity.getString(R.string.color_degrees, it.roundToInt()) }
        opacitySlider.setLabelFormatter { activity.getString(R.string.color_percent, it.roundToInt()) }

        hueStrip.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, rainbowColors()).apply {
            cornerRadius = activity.resources.getDimension(R.dimen.hue_strip_height) / 2f
        }

        val swatchViews = HashMap<String, Pair<ImageView, TextView>>()
        lateinit var renderAll: () -> Unit

        fun renderSwatch(zone: ZoneColor) {
            val (image, label) = swatchViews[zone.id] ?: return
            val price = ShopCatalog.byId(zone.id)?.price ?: 0
            val owned = gameState.isOwned(zone.id)
            val selected = owned && zone.id == selectedId
            val value = zoneColorValue(zone.id)
            image.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (owned) value else ColorUtils.setAlphaComponent(value, 80))
                if (selected) {
                    setStroke(dp(3), Color.WHITE)
                } else {
                    setStroke(dp(1), color(R.color.hud_stroke))
                }
            }
            when {
                selected -> {
                    image.setImageResource(R.drawable.ic_check)
                    image.imageTintList = ColorStateList.valueOf(color(R.color.night_900))
                }
                !owned -> {
                    image.setImageResource(R.drawable.ic_lock)
                    image.imageTintList = ColorStateList.valueOf(Color.WHITE)
                }
                else -> image.setImageDrawable(null)
            }
            val name = activity.getString(zone.name)
            image.contentDescription = when {
                selected -> activity.getString(R.string.color_swatch_selected, name)
                !owned -> activity.getString(R.string.color_swatch_locked, name, price)
                else -> name
            }
            label.text = formatInt(price)
            label.visibility = if (owned) View.INVISIBLE else View.VISIBLE
        }

        fun renderCustom() {
            val owned = gameState.isOwned(CUSTOM_COLOR_ID)
            hueSlider.isEnabled = owned
            hueStrip.alpha = if (owned) 1f else 0.3f
            unlockCustom.isVisible = !owned
            val price = ShopCatalog.byId(CUSTOM_COLOR_ID)?.price ?: 0
            unlockCustom.text = formatInt(price)
            unlockCustom.contentDescription = activity.getString(R.string.color_custom_locked_desc, price)
        }

        fun selectZone(zone: ZoneColor) {
            selectedId = zone.id
            rgb = zoneColorValue(zone.id)
            val h = FloatArray(3)
            Color.colorToHSV(rgb, h)
            hueSlider.value = h[0].roundToInt().coerceIn(0, 360).toFloat()
            renderAll()
        }

        fun askUnlock(itemId: String, onUnlocked: () -> Unit) {
            val item = ShopCatalog.byId(itemId) ?: return
            MaterialAlertDialogBuilder(activity)
                .setIcon(R.drawable.ic_lock)
                .setTitle(activity.getString(R.string.color_unlock_title, activity.getString(itemName(itemId))))
                .setMessage(activity.getString(R.string.color_unlock_message, item.price, gameState.coinBalance))
                .setPositiveButton(R.string.color_unlock_buy) { _, _ ->
                    if (performPurchase(item, sheet)) onUnlocked()
                }
                .setNeutralButton(R.string.color_unlock_open_shop) { _, _ ->
                    dialog.dismiss()
                    showShopSheet(ShopCategory.ZONE_COLOR)
                }
                .setNegativeButton(R.string.color_cancel, null)
                .show()
        }

        zoneColors.forEachIndexed { index, zone ->
            val cell = LinearLayout(dialog.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            val size = activity.resources.getDimensionPixelSize(R.dimen.swatch_size)
            val image = ImageView(dialog.context).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
            }
            val label = TextView(dialog.context).apply {
                setTextAppearance(R.style.TextAppearance_GeoMoneyHide_StatLabel)
                setTextColor(color(R.color.gold_400))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                gravity = android.view.Gravity.CENTER
                tintedDrawable(R.drawable.ic_coin, color(R.color.gold_400), 12)?.let {
                    setCompoundDrawablesRelative(it, null, null, null)
                    compoundDrawablePadding = dp(3)
                }
            }
            cell.addView(image, LinearLayout.LayoutParams(size, size))
            cell.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
            rows[index / 4].addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            swatchViews[zone.id] = image to label
            image.setOnClickListener {
                if (gameState.isOwned(zone.id)) {
                    selectZone(zone)
                } else {
                    askUnlock(zone.id) { selectZone(zone) }
                }
            }
        }

        renderAll = {
            val argb = ColorUtils.setAlphaComponent(rgb, percentToAlpha(opacity))
            previewZone.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(argb)
                setStroke(activity.resources.getDimensionPixelSize(R.dimen.preview_zone_stroke), rgb)
            }
            val label = activity.getString(R.string.color_preview_label, hex(rgb), opacity)
            previewLabel.text = label
            previewFrame.contentDescription = activity.getString(R.string.color_preview_desc) + " : " + label
            val rgbList = ColorStateList.valueOf(rgb)
            hueSlider.thumbTintList = rgbList
            opacitySlider.thumbTintList = rgbList
            opacitySlider.trackActiveTintList = rgbList
            zoneColors.forEach { renderSwatch(it) }
            renderCustom()
        }

        hueSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                rgb = Color.HSVToColor(floatArrayOf(value, CUSTOM_SATURATION, CUSTOM_VALUE))
                selectedId = null
                renderAll()
            }
        }
        opacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                opacity = value.roundToInt()
                renderAll()
            }
        }
        unlockCustom.setOnClickListener { askUnlock(CUSTOM_COLOR_ID) { renderAll() } }

        sheet.findViewById<View>(R.id.color_cancel).setOnClickListener { dialog.dismiss() }
        sheet.findViewById<View>(R.id.color_apply).setOnClickListener {
            gameState.circleColor = ColorUtils.setAlphaComponent(rgb, percentToAlpha(opacity))
            onColorChanged()
            dialog.dismiss()
            showSnack(root, activity.getString(R.string.color_applied), R.drawable.ic_palette, rgb, anchored = true)
        }

        renderAll()
        dialog.setContentView(sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    // =====================================================================================
    // Règles & crédits
    // =====================================================================================

    private fun showRulesSheet() {
        val dialog = BottomSheetDialog(activity)
        val inflater = LayoutInflater.from(dialog.context)
        val sheet = inflater.inflate(R.layout.bottom_sheet_rules, null)
        val list = sheet.findViewById<LinearLayout>(R.id.rules_list)

        addInfoRow(inflater, list, R.drawable.ic_explore, R.string.rule_explore_title, R.string.rule_explore_body, R.color.accent_surface, R.color.stat_surface_bg)
        addInfoRow(inflater, list, R.drawable.ic_grid, R.string.rule_zones_title, R.string.rule_zones_body, R.color.accent_cells, R.color.stat_cells_bg)
        addInfoRow(inflater, list, R.drawable.ic_coin, R.string.rule_collect_title, R.string.rule_collect_body, R.color.gold_400, R.color.coin_pill_bg)
        addInfoRow(inflater, list, R.drawable.ic_sparkle, R.string.rule_discover_title, R.string.rule_discover_body, R.color.gold_400, R.color.coin_pill_bg)
        addInfoRow(inflater, list, R.drawable.ic_autorenew, R.string.rule_regen_title, R.string.rule_regen_body, R.color.accent_surface, R.color.stat_surface_bg)
        addInfoRow(inflater, list, R.drawable.ic_store, R.string.rule_shop_title, R.string.rule_shop_body, R.color.gold_400, R.color.coin_pill_bg)
        addInfoRow(inflater, list, R.drawable.ic_palette, R.string.rule_custom_title, R.string.rule_custom_body, R.color.accent_color, R.color.stat_cells_bg)

        sheet.findViewById<View>(R.id.rules_close).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun showCreditsSheet() {
        val dialog = BottomSheetDialog(activity)
        val inflater = LayoutInflater.from(dialog.context)
        val sheet = inflater.inflate(R.layout.bottom_sheet_credits, null)
        val list = sheet.findViewById<LinearLayout>(R.id.credits_list)

        addInfoRow(inflater, list, R.drawable.ic_school, R.string.credits_dev_title, R.string.credits_dev_body, R.color.gold_400, R.color.coin_pill_bg)

        val logo = sheet.findViewById<View>(R.id.credits_logo)
        sheet.findViewById<View>(R.id.credits_close).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener { flipCoin(logo) }
        dialog.show()
    }

    private fun addInfoRow(
        inflater: LayoutInflater,
        container: LinearLayout,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes body: Int,
        @ColorRes tint: Int,
        @ColorRes bubble: Int
    ) {
        val row = inflater.inflate(R.layout.item_info_row, container, false)
        row.findViewById<View>(R.id.info_bubble).backgroundTintList = ColorStateList.valueOf(color(bubble))
        row.findViewById<ImageView>(R.id.info_icon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(tint))
        }
        row.findViewById<TextView>(R.id.info_title).setText(title)
        row.findViewById<TextView>(R.id.info_body).setText(body)
        container.addView(row)
    }

    // =====================================================================================
    // Correspondances id → apparence
    // =====================================================================================

    @StringRes
    private fun itemName(id: String): Int = when (id) {
        "color_green" -> R.string.item_color_green
        "color_blue" -> R.string.item_color_blue
        "color_violet" -> R.string.item_color_violet
        "color_orange" -> R.string.item_color_orange
        "color_pink" -> R.string.item_color_pink
        "color_cyan" -> R.string.item_color_cyan
        "color_red" -> R.string.item_color_red
        "color_gold" -> R.string.item_color_gold
        CUSTOM_COLOR_ID -> R.string.item_color_custom
        "theme_night" -> R.string.item_theme_night
        "theme_classic" -> R.string.item_theme_classic
        "theme_retro" -> R.string.item_theme_retro
        "theme_neon" -> R.string.item_theme_neon
        "boost_magnet" -> R.string.item_boost_magnet
        "boost_double" -> R.string.item_boost_double
        else -> R.string.shop_title
    }

    @StringRes
    private fun itemDescription(id: String): Int = when (id) {
        "color_gold" -> R.string.item_color_gold_desc
        CUSTOM_COLOR_ID -> R.string.item_color_custom_desc
        "theme_night" -> R.string.item_theme_night_desc
        "theme_classic" -> R.string.item_theme_classic_desc
        "theme_retro" -> R.string.item_theme_retro_desc
        "theme_neon" -> R.string.item_theme_neon_desc
        "boost_magnet" -> R.string.item_boost_magnet_desc
        "boost_double" -> R.string.item_boost_double_desc
        else -> R.string.item_color_desc
    }

    @DrawableRes
    private fun boostIcon(id: String): Int = when (id) {
        "boost_magnet" -> R.drawable.ic_magnet
        else -> R.drawable.ic_bolt
    }

    @ColorInt
    private fun zoneColorValue(id: String): Int {
        val res = zoneColors.firstOrNull { it.id == id }?.color ?: R.color.zone_green
        return color(res)
    }

    private fun themePreviewColors(id: String): IntArray = when (id) {
        "theme_classic" -> intArrayOf(color(R.color.theme_classic_start), color(R.color.theme_classic_end))
        "theme_retro" -> intArrayOf(color(R.color.theme_retro_start), color(R.color.theme_retro_end))
        "theme_neon" -> intArrayOf(color(R.color.theme_neon_start), color(R.color.theme_neon_mid), color(R.color.theme_neon_end))
        else -> intArrayOf(color(R.color.theme_night_start), color(R.color.theme_night_end))
    }

    private fun rainbowColors(): IntArray = IntArray(7) { i ->
        Color.HSVToColor(floatArrayOf((i * 60f) % 360f, CUSTOM_SATURATION, CUSTOM_VALUE))
    }

    // =====================================================================================
    // Outils
    // =====================================================================================

    @ColorInt
    private fun color(@ColorRes res: Int): Int = ContextCompat.getColor(activity, res)

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private fun quantity(plural: Int, count: Int): String =
        activity.resources.getQuantityString(plural, count, count)

    private fun tintedDrawable(@DrawableRes res: Int, @ColorInt tint: Int, sizeDp: Int) =
        AppCompatResources.getDrawable(activity, res)?.mutate()?.let { drawable ->
            val wrapped = DrawableCompat.wrap(drawable)
            DrawableCompat.setTint(wrapped, tint)
            val size = dp(sizeDp)
            wrapped.setBounds(0, 0, size, size)
            wrapped
        }

    private fun opaque(argb: Int): Int = argb or 0xFF000000.toInt()

    private fun percentToAlpha(percent: Int): Int = (percent * 255f / 100f).roundToInt().coerceIn(0, 255)

    private fun alphaToPercent(alpha: Int): Int = (alpha * 100f / 255f).roundToInt()

    private fun hex(rgb: Int): String = String.format(Locale.ROOT, "#%06X", rgb and 0xFFFFFF)

    private fun formatInt(value: Int): String = NumberFormat.getIntegerInstance(LOCALE).format(value)

    private fun formatSurface(km2: Double): String = NumberFormat.getNumberInstance(LOCALE).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = if (km2 < 100.0) 1 else 0
    }.format(km2)

    /**
     * Pourcentage de la surface terrestre avec décimales adaptatives (2 chiffres significatifs)
     * et décimales groupées par 3 : 1 km² → « 0,000 000 2 ».
     */
    private fun formatEarthPercent(km2: Double): String {
        val percent = km2 / EARTH_SURFACE_KM2 * 100.0
        if (percent <= 0.0) return "0"
        val decimals = if (percent >= 1.0) 2 else (ceil(-log10(percent)).toInt() + 1).coerceIn(2, 12)
        val formatted = NumberFormat.getNumberInstance(LOCALE).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = decimals
        }.format(percent)
        val separator = DecimalFormatSymbols.getInstance(LOCALE).decimalSeparator
        val index = formatted.indexOf(separator)
        if (index < 0) return formatted
        val fraction = formatted.substring(index + 1)
        if (fraction.length <= 3) return formatted
        return formatted.substring(0, index + 1) + fraction.chunked(3).joinToString(" ")
    }

    /** « 12:34 » ou « 1:02:03 ». */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(LOCALE, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(LOCALE, "%02d:%02d", minutes, seconds)
        }
    }
}
