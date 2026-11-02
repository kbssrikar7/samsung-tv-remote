package com.kbs.tvremote

import android.content.SharedPreferences
import androidx.core.content.edit

/** Recovery never chooses another device just because it is the only TV, or has the same name. */
object TvRecovery {
    fun match(expected: TvIdentity, found: List<TvDiscovery.FoundTv>): TvDiscovery.FoundTv? =
        found.filter { expected.matches(it.identity) }.distinctBy { it.ip }.singleOrNull()

    fun relocate(prefs: SharedPreferences, oldIp: String, tv: TvDiscovery.FoundTv): Boolean {
        if (prefs.getString(TvPrefs.IP, null) != oldIp || !TvPrefs.isValidIp(tv.ip)) return false
        if (!TvPrefs.identity(prefs, oldIp).matches(tv.identity)) return false
        if (oldIp == tv.ip) { TvPrefs.remember(prefs, tv); return true }
        val old = prefs.all
        prefs.edit {
            // Move the original TV's certificate pin too. The new secure socket must still
            // present that certificate before it can receive the saved token.
            listOf("token_", "cert_", "legacy_", "mac_", "name_", "apps_", "id_").forEach { prefix ->
                val value = old[prefix + oldIp]
                remove(prefix + tv.ip)
                when (value) {
                    is String -> putString(prefix + tv.ip, value)
                    is Boolean -> putBoolean(prefix + tv.ip, value)
                }
                remove(prefix + oldIp)
            }
            putString(TvPrefs.IP, tv.ip)
        }
        TvPrefs.remember(prefs, tv)
        return true
    }
}
