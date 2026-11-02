package com.kbs.tvremote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Where the app keeps the chosen TV and its pairing data; shared by the app and the widget. */
object TvPrefs {
    const val IP = "ip"
    fun token(ip: String) = "token_$ip"
    fun mac(ip: String) = "mac_$ip"
    fun name(ip: String) = "name_$ip"
    fun apps(ip: String) = "apps_$ip"
    fun id(ip: String) = "id_$ip"

    fun identity(prefs: SharedPreferences, ip: String) =
        TvIdentity.from(prefs.getString(id(ip), null), prefs.getString(mac(ip), null))

    fun remember(prefs: SharedPreferences, tv: TvDiscovery.FoundTv) {
        prefs.edit {
            if (tv.name.isNotBlank()) putString(name(tv.ip), tv.name)
            tv.identity.deviceId?.let { putString(id(tv.ip), it) }
            tv.mac?.takeIf { TvIdentity.from(null, it).mac != null }?.let { putString(mac(tv.ip), it) }
        }
    }

    fun isValidIp(value: String): Boolean {
        val parts = value.trim().split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                (part.length == 1 || part.first() != '0') &&
                (part.toIntOrNull() ?: -1) in 0..255
        } && parts.first().toInt() in 1..223
    }

    fun of(context: Context): SharedPreferences = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
}
