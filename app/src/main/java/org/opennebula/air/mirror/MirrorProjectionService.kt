package org.opennebula.air.mirror

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
import org.opennebula.air.MainActivity
import org.opennebula.air.R

/** Foreground service for MediaProjection. Does not own the projection token. */
class MirrorProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsProjectionForeground()
        started?.invoke()
        started = null
        return START_STICKY
    }

    private fun startAsProjectionForeground() {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("OpenNebula screen mirroring")
            .setContentText("Phone screen is on the glasses")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen mirroring", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val DISPLAY_NAME = "opennebula-mirror"
        private const val CHANNEL = "opennebula_mirror"
        private const val NOTIF_ID = 42

        @Volatile private var started: (() -> Unit)? = null

        fun start(context: Context, onStarted: () -> Unit) {
            started = onStarted
            context.startForegroundService(Intent(context, MirrorProjectionService::class.java))
        }

        fun stop(context: Context) {
            started = null
            context.stopService(Intent(context, MirrorProjectionService::class.java))
        }
    }
}
