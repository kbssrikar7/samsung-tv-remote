package com.kbs.tvremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sends widget button presses. A broadcast would be enough when the phone is idle, but with power
 * saving on Android cuts background apps off the network, and unlike notification buttons a
 * widget tap gets no temporary exemption. A short foreground service keeps network access for the
 * second or so a key takes. Taps are sent one by one, in order.
 */
class KeyService : Service() {

    private val sender: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must come first: Android stops the app if a foreground service doesn't start promptly.
        runCatching { goForeground() } // If Android refuses, still try as a normal background send.
        val key = intent?.getStringExtra(EXTRA_KEY)
        sender.execute {
            if (key != null) KeyReceiver.sendWithFeedback(applicationContext, key)
            // Only the newest tap's id stops the service, so earlier finishes keep it running.
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sending buttons to the TV", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown briefly while a widget button reaches the TV"
                setShowBadge(false)
            }
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle("Sending to your TV")
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /** Short services get about three minutes; a key needs seconds, so this only guards a hang. */
    @Deprecated("Replaced on Android 15 by the overload with a service type")
    override fun onTimeout(startId: Int) = stopSelf()

    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        sender.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_KEY = "key"
        private const val CHANNEL_ID = "sending_keys"
        private const val NOTIFICATION_ID = 2

        fun intent(context: Context, key: String): PendingIntent = PendingIntent.getForegroundService(
            context,
            key.hashCode(),
            Intent(context, KeyService::class.java).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
