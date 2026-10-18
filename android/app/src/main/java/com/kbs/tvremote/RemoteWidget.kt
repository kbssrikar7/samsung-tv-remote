package com.kbs.tvremote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.edit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Home-screen widget with Volume -, Mute, Volume +, Play and Pause. Each tap opens a short-lived
 * connection to the TV with the token the app already holds, sends one key and closes it.
 */
class RemoteWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_remote)
        BUTTONS.forEach { (viewId, key) -> views.setOnClickPendingIntent(viewId, keyIntent(context, key)) }
        manager.updateAppWidget(ids, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_KEY) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val pending = goAsync()
        Thread {
            try {
                sendOnce(context.applicationContext, key)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun keyIntent(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        key.hashCode(),
        Intent(context, RemoteWidget::class.java).setAction(ACTION_KEY).putExtra(EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Blocking: connects with the stored pairing, sends [key], disconnects. */
    private fun sendOnce(context: Context, key: String) {
        val prefs = TvPrefs.of(context)
        val ip = prefs.getString(TvPrefs.IP, null) ?: return
        val connected = CountDownLatch(1)
        val client = TvClient(object : TvClient.Listener {
            override fun onStateChanged(state: TvClient.State, detail: String?) {
                if (state == TvClient.State.CONNECTED || state == TvClient.State.DISCONNECTED) connected.countDown()
            }

            override fun onTokenIssued(token: String) = prefs.edit { putString(TvPrefs.token(ip), token) }
        }, prefs)
        client.connect(ip, prefs.getString(TvPrefs.token(ip), null))
        connected.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        if (client.state == TvClient.State.CONNECTED) {
            client.sendKey(key)
            Thread.sleep(SEND_GRACE_MS) // let the frame leave before closing the socket
        }
        client.disconnect()
    }

    companion object {
        private const val ACTION_KEY = "com.kbs.tvremote.widget.KEY"
        private const val EXTRA_KEY = "key"
        private const val CONNECT_TIMEOUT_S = 5L
        private const val SEND_GRACE_MS = 200L

        private val BUTTONS = listOf(
            R.id.widget_vol_down to "KEY_VOLDOWN",
            R.id.widget_mute to "KEY_MUTE",
            R.id.widget_vol_up to "KEY_VOLUP",
            R.id.widget_play to "KEY_PLAY",
            R.id.widget_pause to "KEY_PAUSE",
        )
    }
}
