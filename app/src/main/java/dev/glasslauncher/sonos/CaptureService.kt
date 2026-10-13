package dev.glasslauncher.sonos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import dev.glasslauncher.R

/** Foreground service of type mediaProjection: Android requires it before a projection can be used. */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing on Sonos", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_airplay).setContentTitle("Playing on Sonos").setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1210, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else { stopSelf(); SonosRoutes.stop(this); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); SonosRoutes.stop(this); return START_NOT_STICKY }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (mp == null) { stopSelf(); SonosRoutes.stop(this); return START_NOT_STICKY }
        SonosRoutes.onProjection(this, mp)
        return START_NOT_STICKY
    }

    private companion object { const val CHANNEL = "sonos-capture" }
}
