package org.lior.sixstep

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

/** Hearing the phone's own output (the Spotify test page's "Listen", Android 10+). Android allows that only to a
 *  foreground service of type mediaProjection, started after the user said yes to the "record or cast" prompt; the
 *  projection is taken here, after startForeground, as Android 14 requires. Only the sound's onset curve is used. */
class CaptureService : Service() {
    companion object {
        const val CHANNEL = "listen"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }

    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 29 || intent == null) { stopSelf(); return START_NOT_STICKY }
        start(intent)
        return START_NOT_STICKY
    }

    @RequiresApi(29)
    private fun start(intent: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.listen_channel), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_volume)
            .setContentTitle(getString(R.string.listen_notification))
            .setOngoing(true)
            .build()
        try {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            AudioSync.failed("capture", "startForeground: ${e.message}"); stopSelf(); return
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = consentData(intent)
        val p = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data ?: throw IllegalStateException("no consent data"))
        } catch (e: Exception) { null }
        if (p == null) { AudioSync.failed("capture", "no media projection"); stopSelf(); return }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { AudioSync.stopCapture(); stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = p
        AudioSync.startCapture(p)
    }

    @Suppress("DEPRECATION")
    private fun consentData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra<Intent>(EXTRA_DATA)

    override fun onDestroy() {
        AudioSync.stopCapture()
        projection?.stop(); projection = null
        super.onDestroy()
    }
}
