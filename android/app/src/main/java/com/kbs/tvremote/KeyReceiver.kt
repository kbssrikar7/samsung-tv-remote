package com.kbs.tvremote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sends one remote key while the app is closed (widget and notification buttons). Each tap opens
 * a short-lived connection with the pairing token the app already holds, sends the key and closes
 * it, so nothing keeps running in the background between taps.
 */
class KeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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
        private const val EXTRA_KEY = "key"
        private const val CONNECT_TIMEOUT_S = 5L
        private const val SEND_GRACE_MS = 200L

        /** The volume and playback buttons shared by the widget and the notification. */
        val BUTTONS = listOf(
            R.id.remote_vol_down to "KEY_VOLDOWN",
            R.id.remote_mute to "KEY_MUTE",
            R.id.remote_vol_up to "KEY_VOLUP",
            R.id.remote_play to "KEY_PLAY",
            R.id.remote_pause to "KEY_PAUSE",
        )

        fun intent(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            Intent(context, KeyReceiver::class.java).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
