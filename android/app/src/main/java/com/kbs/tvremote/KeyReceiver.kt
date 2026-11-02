package com.kbs.tvremote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
                sendKeyOnce(context.applicationContext, key)
            } catch (_: Exception) {
                // A failed background button must not crash the app process.
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** Blocking: connects with the stored pairing, sends [key], disconnects. */
        fun sendKeyOnce(context: Context, key: String): Boolean {
            val prefs = TvPrefs.of(context)
            val ip = prefs.getString(TvPrefs.IP, null)?.takeIf(TvPrefs::isValidIp) ?: return false
            val connected = CountDownLatch(1)
            val expected = TvPrefs.identity(prefs, ip)
            val client = TvClient(object : TvClient.Listener {
                override fun onStateChanged(state: TvClient.State, detail: String?) {
                    if (state == TvClient.State.CONNECTED || state == TvClient.State.DISCONNECTED) connected.countDown()
                }

                override fun onTokenIssued(token: String) = prefs.edit { putString(TvPrefs.token(ip), token) }
            }, prefs, TvNetwork(context))
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECT_TIMEOUT_S)
                val info = if (expected.known) client.deviceInfo(ip, timeoutMs = 2000) else null
                val verified = info != null && expected.matches(info.identity)
                if (expected.known && info?.identity?.known == true && !verified) return false
                if (expected.known && !verified && !prefs.contains(TvClient.pinKey(ip))) return false
                Handler(Looper.getMainLooper()).post {
                    // Selection/recovery may have changed while the background preflight ran.
                    if (prefs.getString(TvPrefs.IP, null) != ip ||
                        (expected.known && !expected.matches(TvPrefs.identity(prefs, ip)))) {
                        connected.countDown()
                    } else client.connect(ip, prefs.getString(TvPrefs.token(ip), null), allowPairing = false,
                        allowPlain = !expected.known || verified)
                }
                connected.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                val sent = client.state == TvClient.State.CONNECTED && client.sendKey(key)
                if (sent) Thread.sleep(SEND_GRACE_MS)
                return sent
            } finally { client.disconnect() }
        }

        private const val EXTRA_KEY = "key"
        private const val CONNECT_TIMEOUT_S = 8L
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
