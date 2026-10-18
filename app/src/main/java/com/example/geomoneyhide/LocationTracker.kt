package com.example.geomoneyhide

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Propriétaire unique de la demande de localisation du processus.
 *
 * Un seul [LocationCallback] est utilisé : chaque nouvel appel à requestLocationUpdates
 * remplace la demande précédente, il n'y a donc jamais deux demandes simultanées.
 * Le mode [Mode.FOREGROUND] est utilisé par l'activité, [Mode.BACKGROUND] par le service.
 * Les positions sont transmises à [GameRepository] sur le thread principal.
 */
object LocationTracker {

    enum class Mode { FOREGROUND, BACKGROUND }

    private const val TAG = "LocationTracker"

    private var client: FusedLocationProviderClient? = null
    private var currentMode: Mode? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) GameRepository.onLocation(location)
        }
    }

    fun hasLocationPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPreciseLocationPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun buildRequest(mode: Mode): LocationRequest = when (mode) {
        Mode.FOREGROUND -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        Mode.BACKGROUND -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(10_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()
    }

    /**
     * Démarre (ou bascule vers) le mode demandé.
     * @return false si la permission de localisation manque
     */
    @MainThread
    @SuppressLint("MissingPermission") // permission vérifiée juste avant
    fun start(context: Context, mode: Mode): Boolean {
        if (!hasLocationPermission(context)) return false
        if (currentMode == mode) return true
        val fused = client ?: LocationServices.getFusedLocationProviderClient(context.applicationContext)
            .also { client = it }
        try {
            fused.requestLocationUpdates(buildRequest(mode), callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission révoquée, suivi impossible", e)
            currentMode = null
            return false
        }
        currentMode = mode
        return true
    }

    /**
     * Démarre le mode arrière-plan, sauf si l'activité suit déjà la position au premier plan.
     * @return false si le suivi d'arrière-plan n'a pas été démarré
     */
    @MainThread
    fun startBackground(context: Context): Boolean {
        if (currentMode == Mode.FOREGROUND) return false
        return start(context, Mode.BACKGROUND)
    }

    /**
     * Arrête le suivi seulement s'il est encore dans le mode [mode] : le service qui s'arrête
     * ne coupe pas le suivi que l'activité vient de reprendre.
     */
    @MainThread
    fun stop(mode: Mode) {
        if (currentMode != mode) return
        client?.removeLocationUpdates(callback)
        currentMode = null
    }

    /** Récupère la dernière position connue du système et la transmet au repository. */
    @MainThread
    @SuppressLint("MissingPermission") // permission vérifiée juste avant
    fun fetchLastLocation(context: Context, onResult: () -> Unit) {
        if (!hasLocationPermission(context)) return
        val fused = client ?: LocationServices.getFusedLocationProviderClient(context.applicationContext)
            .also { client = it }
        fused.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                GameRepository.onLastKnownLocation(location)
                onResult()
            }
        }
    }
}
