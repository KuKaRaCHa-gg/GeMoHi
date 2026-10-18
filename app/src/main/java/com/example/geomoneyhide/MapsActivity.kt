package com.example.geomoneyhide

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.max

/**
 * Écran principal : carte, HUD (géré par [UIManager]) et orchestration du cycle de vie.
 *
 * L'état du jeu vit dans [GameRepository] (partagé avec [LocationService]) ; l'activité
 * l'observe et resynchronise la carte à chaque retour au premier plan.
 */
class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val DEFAULT_ZOOM = 15f
        private const val PREFS_NAME = "core_prefs"
        private const val PREF_BACKGROUND_PROMPT_DONE = "background_prompt_done"
    }

    private val gameState: GameState get() = GameRepository.state

    private var map: GoogleMap? = null
    private val gridManager = GridManager(GameRepository.state)
    private val pieceManager = PieceManager(this, GameRepository.state)
    private lateinit var uiManager: UIManager

    /** Vrai pendant qu'une demande de permission système est affichée (onPause sans départ). */
    private var permissionRequestInProgress = false
    private var cameraCentered = false

    private val foregroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionRequestInProgress = false
            val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) {
                onLocationPermissionGranted()
                if (!fine) uiManager.showMessage(getString(R.string.core_permission_approximate_only))
                maybeAskBackgroundPermission()
            } else {
                uiManager.showMessage(getString(R.string.core_permission_location_denied))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Le service fonctionne même sans notification visible : on enchaîne dans tous les cas
            permissionRequestInProgress = false
            requestBackgroundPermission()
        }

    private val backgroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequestInProgress = false
            uiManager.showMessage(
                getString(
                    if (granted) R.string.core_permission_background_granted
                    else R.string.core_permission_background_denied
                )
            )
        }

    private val gameListener = object : GameRepository.Listener {
        override fun onGameUpdated(update: GameUpdate) {
            update.newCellId?.let { gridManager.drawExploredCell(it) }
            if (update.coinsChanged) pieceManager.syncMarkers()
            if (update.discovered > 0) uiManager.showDiscovered(update.discovered)
            if (update.collected > 0) uiManager.showCollected(update.collected)
            if (update.spawned > 0) uiManager.showSpawned(update.spawned)
            refreshStats(animate = true)
        }

        override fun onLocationChanged(location: Location) {
            if (!cameraCentered) centerOnUser(animate = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameRepository.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_maps)

        uiManager = UIManager(this, gameState) {
            // La couleur vient d'être modifiée dans gameState.circleColor
            gridManager.updateCellColors()
            GameRepository.markDirty()
        }
        uiManager.bindViews()
        uiManager.setRecenterAction { centerOnUser(animate = true) }
        refreshStats(animate = false)

        // La carte s'initialise même sans permission (sans le point « ma position »)
        (supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment)?.getMapAsync(this)

        if (savedInstanceState == null) {
            if (LocationTracker.hasLocationPermission(this)) {
                maybeAskBackgroundPermission()
            } else {
                permissionRequestInProgress = true
                foregroundPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        uiManager.applyMapStyle(googleMap)
        gridManager.attachMap(googleMap)
        pieceManager.attachMap(googleMap)
        enableMyLocationLayer()
        if (GameRepository.lastLocation != null) {
            centerOnUser(animate = false)
        } else {
            LocationTracker.fetchLastLocation(this) { if (!cameraCentered) centerOnUser(animate = false) }
        }
    }

    override fun onStart() {
        super.onStart()
        GameRepository.addListener(gameListener)
        // Rattrape ce qui a été enregistré en arrière-plan
        gridManager.syncCells()
        pieceManager.syncMarkers()
        refreshStats(animate = false)
    }

    override fun onResume() {
        super.onResume()
        // Le suivi de premier plan remplace celui du service (une seule demande à la fois)
        LocationService.stop(this)
        LocationTracker.start(this, LocationTracker.Mode.FOREGROUND)
    }

    override fun onPause() {
        super.onPause()
        // Rotation ou boîte de dialogue de permission : on garde le suivi actuel
        if (isChangingConfigurations || permissionRequestInProgress) return
        LocationTracker.stop(LocationTracker.Mode.FOREGROUND)
        if (LocationTracker.hasLocationPermission(this) && hasBackgroundPermission()) {
            LocationService.start(this)
        }
    }

    override fun onStop() {
        GameRepository.removeListener(gameListener)
        GameRepository.flush()
        super.onStop()
    }

    override fun onDestroy() {
        gridManager.detachMap()
        pieceManager.detachMap()
        map = null
        super.onDestroy()
    }

    private fun refreshStats(animate: Boolean) {
        uiManager.updateStats(gameState.coinsCollected, gameState.surfaceKm2, gameState.cellCount, animate)
    }

    /** Déplace la caméra sur la dernière position connue du joueur. */
    private fun centerOnUser(animate: Boolean) {
        val googleMap = map ?: return
        val location = GameRepository.lastLocation
        if (location == null) {
            if (animate) uiManager.showMessage(getString(R.string.core_location_unavailable))
            return
        }
        val target = LatLng(location.latitude, location.longitude)
        val zoom = if (cameraCentered) max(googleMap.cameraPosition.zoom, DEFAULT_ZOOM) else DEFAULT_ZOOM
        val update = CameraUpdateFactory.newLatLngZoom(target, zoom)
        if (animate) googleMap.animateCamera(update) else googleMap.moveCamera(update)
        cameraCentered = true
    }

    private fun onLocationPermissionGranted() {
        enableMyLocationLayer()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            LocationTracker.start(this, LocationTracker.Mode.FOREGROUND)
        }
        LocationTracker.fetchLastLocation(this) { if (!cameraCentered) centerOnUser(animate = false) }
    }

    @SuppressLint("MissingPermission") // permission vérifiée juste avant
    private fun enableMyLocationLayer() {
        val googleMap = map ?: return
        if (LocationTracker.hasLocationPermission(this)) googleMap.isMyLocationEnabled = true
    }

    private fun hasBackgroundPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Propose une seule fois l'exploration en arrière-plan, avec une explication préalable.
     * N'est appelée qu'une fois la localisation de premier plan accordée.
     */
    private fun maybeAskBackgroundPermission() {
        if (!LocationTracker.hasLocationPermission(this)) return
        if (hasBackgroundPermission() && hasNotificationPermission()) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BACKGROUND_PROMPT_DONE, false)) return
        prefs.edit { putBoolean(PREF_BACKGROUND_PROMPT_DONE, true) }

        if (hasBackgroundPermission()) {
            // Seule la permission de notification manque (Android 13+)
            requestNotificationPermission()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.core_permission_background_title)
            .setMessage(R.string.core_permission_background_message)
            .setPositiveButton(R.string.core_permission_background_accept) { _, _ ->
                if (hasNotificationPermission()) requestBackgroundPermission() else requestNotificationPermission()
            }
            .setNegativeButton(R.string.core_permission_background_decline, null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionRequestInProgress = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestBackgroundPermission()
        }
    }

    private fun requestBackgroundPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasBackgroundPermission()) return
        if (!LocationTracker.hasLocationPermission(this)) return
        permissionRequestInProgress = true
        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}
