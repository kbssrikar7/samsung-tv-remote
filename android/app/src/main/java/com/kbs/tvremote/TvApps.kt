package com.kbs.tvremote

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** An app installed on the TV, shown as a shortcut button. */
data class TvShortcut(val id: String, val name: String, val kind: TvApps.Kind)

/**
 * Popular Tizen apps the remote offers shortcuts for. Tizen app IDs are the same on every TV;
 * the TV answers 404 for apps that aren't installed, so unknown or missing IDs are just skipped.
 * IDs other than YouTube, JioHotstar and TizenBrew come from community lists of Tizen app IDs.
 */
object TvApps {

    enum class Kind { YOUTUBE, TIZENBREW, VIDEO, MUSIC }

    const val YOUTUBE = "111299001912"
    const val TIZENBREW = "xvvl3S1bvH.TizenBrewStandalone"

    private val CANDIDATES = listOf(
        YOUTUBE to Kind.YOUTUBE,
        "3201907018807" to Kind.VIDEO, // Netflix
        "11101200001" to Kind.VIDEO, // Netflix (older TVs)
        "3201910019365" to Kind.VIDEO, // Prime Video
        "3201711015117" to Kind.VIDEO, // JioHotstar
        "3201901017640" to Kind.VIDEO, // Disney+
        "3201807016597" to Kind.VIDEO, // Apple TV
        "3201512006963" to Kind.VIDEO, // Plex
        "3201606009684" to Kind.MUSIC, // Spotify
    )

    /** Blocking. Asks the TV which of the candidate apps are installed, in candidate order. */
    fun detect(client: TvClient, includeTizenBrew: Boolean): List<TvShortcut> {
        val candidates = if (includeTizenBrew) CANDIDATES + (TIZENBREW to Kind.TIZENBREW) else CANDIDATES
        return candidates.mapNotNull { (id, kind) ->
            client.appStatus(id)?.let { TvShortcut(id, it.name.ifEmpty { "App" }, kind) }
        }
    }

    fun load(prefs: SharedPreferences, ip: String): List<TvShortcut> = runCatching {
        val array = JSONArray(prefs.getString(TvPrefs.apps(ip), "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            TvShortcut(o.getString("id"), o.getString("name"), Kind.valueOf(o.getString("kind")))
        }
    }.getOrDefault(emptyList())

    fun save(prefs: SharedPreferences, ip: String, apps: List<TvShortcut>) {
        val array = JSONArray()
        apps.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name)) }
        prefs.edit { putString(TvPrefs.apps(ip), array.toString()) }
    }
}
