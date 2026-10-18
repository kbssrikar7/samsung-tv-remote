package com.kbs.tvremote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * A silent, ongoing notification with Volume -, Mute, Volume +, Play and Pause, so the remote is
 * one pull-down away when the app is closed. It is only a notification: no service runs behind
 * it, and each button press is handled by [KeyReceiver].
 */
object RemoteNotification {

    private const val CHANNEL_ID = "remote_controls"
    private const val NOTIFICATION_ID = 1
    private const val KEY_ENABLED = "notification_enabled"

    fun isEnabled(context: Context) = TvPrefs.of(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        TvPrefs.of(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) show(context) else NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts (or refreshes) the notification if the user wants it and it is allowed. */
    fun show(context: Context) {
        if (!isEnabled(context) || !hasPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "TV remote buttons", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Volume and play buttons in the notification bar"
                setShowBadge(false)
            }
        )
        val views = RemoteViews(context.packageName, R.layout.notification_remote)
        KeyReceiver.BUTTONS.forEach { (viewId, key) -> views.setOnClickPendingIntent(viewId, KeyReceiver.intent(context, key)) }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle("TV remote")
            .setCustomContentView(views)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        @Suppress("MissingPermission") // checked by hasPermission above
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Restores the notification after a reboot or an app update. */
    class Restorer : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                show(context)
            }
        }
    }
}
