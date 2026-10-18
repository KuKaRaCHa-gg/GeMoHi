package com.example.geomoneyhide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Service de premier plan (type « location ») qui poursuit l'exploration quand l'application
 * n'est plus visible. Il enregistre directement les positions dans [GameRepository] :
 * la partie progresse même si l'activité n'existe plus.
 */
class LocationService : Service() {

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "GeoMoneyHideChannel"

        /**
         * Démarre le service. Renvoie false si le système l'interdit
         * (ex. ForegroundServiceStartNotAllowedException depuis l'arrière-plan, API 31+).
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException hérite d'IllegalStateException
            Log.w(TAG, "Démarrage du service refusé par le système", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Démarrage du service refusé (permission)", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        GameRepository.init(this)
        createNotificationChannel()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), type)
        } catch (e: RuntimeException) {
            // SecurityException (permission manquante) ou refus du système (API 31+)
            Log.w(TAG, "Passage au premier plan impossible", e)
            stopSelf()
            return
        }

        if (!LocationTracker.start(this, LocationTracker.Mode.BACKGROUND)) {
            stopSelf()
        }
    }

    // Pas de redémarrage automatique : l'activité relance le service quand elle passe en arrière-plan
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LocationTracker.stop(LocationTracker.Mode.BACKGROUND)
        GameRepository.flush()
        super.onDestroy()
    }

    /** Canal de notification à faible importance (ni son ni vibration). */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.core_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.core_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val openApp = Intent(this, MapsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.core_notification_title))
            .setContentText(getString(R.string.core_notification_text))
            .setSmallIcon(R.drawable.ic_coin)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
