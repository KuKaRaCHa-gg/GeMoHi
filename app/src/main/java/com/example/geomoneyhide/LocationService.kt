package com.example.geomoneyhide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

/**
 * Service de localisation qui fonctionne en arrière-plan
 * Il fonctionne comme un service de premier plan avec une notification permanente.
 */
class LocationService : Service() {
    // Client pour accéder aux services de localisation Google
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Callback pour recevoir les mises à jour de position
    private lateinit var locationCallback: LocationCallback

    /**
     * Méthode appelée lors de la création du service
     * Initialise les services de localisation et démarre le service en premier plan
     */
    override fun onCreate() {
        super.onCreate()
        // Initialise le client de localisation
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Crée le canal de notification (obligatoire depuis Android 8.0)
        createNotificationChannel()

        // Crée et affiche la notification permanente
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Configure les mises à jour de localisation
        setupLocationUpdates()
    }

    /**
     * Configure la fréquence et la précision des mises à jour de position
     * Définit également comment les nouvelles positions sont traitées
     */
    private fun setupLocationUpdates() {
        // Configure les paramètres de demande de localisation
        val locationRequest = LocationRequest.create()
            .setInterval(10000)  // Intervalle normal: 10 secondes
            .setFastestInterval(5000)  // Intervalle minimum: 5 secondes
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)  // Précision maximale (GPS)

        // Définit le callback pour traiter les nouvelles positions
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Envoie la dernière position connue à l'activité via un broadcast
                val intent = Intent("LOCATION_UPDATE")
                locationResult.lastLocation?.let { intent.putExtra("latitude", it.latitude) }
                locationResult.lastLocation?.let { intent.putExtra("longitude", it.longitude) }
                sendBroadcast(intent)
            }
        }

        try {
            // Démarre les mises à jour de localisation
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null  // Looper par défaut
            )
        } catch (e: SecurityException) {
            // Gère les problèmes de permission (permissions non accordées)
        }
    }

    /**
     * Crée un canal de notification pour le service
     * Obligatoire depuis Android 8.0 (API 26)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Crée un canal avec une priorité basse pour réduire l'intrusion
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GeoMoneyHide Location",  // Nom visible par l'utilisateur
                NotificationManager.IMPORTANCE_LOW  // Priorité basse (pas de son ni vibration)
            )

            // Enregistre le canal auprès du système
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Crée la notification permanente affichée pendant l'exécution du service
     * @return La notification configurée
     */
    private fun createNotification(): Notification {
        // Intent pour ouvrir l'application quand l'utilisateur touche la notification
        val notificationIntent = Intent(this, MapsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE  // Drapeau de sécurité recommandé
        )

        // Construit et configure la notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoMoneyHide actif")  // Titre de la notification
            .setContentText("Collecte de pièces en cours...")  // Description
            .setSmallIcon(R.mipmap.ic_launcher)  // Icône dans la barre d'état
            .setContentIntent(pendingIntent)  // Action au toucher
            .build()
    }

    /**
     * Méthode requise pour l'interface Service, mais non utilisée car ce n'est pas un service lié
     * @return null car ce service n'est pas destiné à être lié
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Méthode appelée lors de la destruction du service
     * Nettoie les ressources et arrête les mises à jour de localisation
     */
    override fun onDestroy() {
        super.onDestroy()
        // Arrête les mises à jour de localisation pour économiser la batterie
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    companion object {
        // Identificateur unique pour la notification
        private const val NOTIFICATION_ID = 12345

        // Identificateur du canal de notification
        private const val CHANNEL_ID = "GeoMoneyHideChannel"
    }
}

