package com.kbs.tvremote

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An app installed on the TV, shown as a shortcut button. */
data class TvShortcut(val id: String, val name: String, val kind: TvApps.Kind)

/**
 * Popular Tizen apps the remote offers shortcuts for. IDs vary by TV generation and region;
 * probe known alternatives and skip apps the TV reports as unavailable.
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
        "3201512006785" to Kind.VIDEO, // Prime Video (older TVs)
        "3201711015117" to Kind.VIDEO, // JioHotstar
        "3201901017640" to Kind.VIDEO, // Disney+
        "3202009021709" to Kind.VIDEO, // Disney+ (alternative generations/regions)
        "3202204027038" to Kind.VIDEO, // Disney+ (alternative generations/regions)
        "3201807016597" to Kind.VIDEO, // Apple TV
        "3201512006963" to Kind.VIDEO, // Plex
        "3201606009684" to Kind.MUSIC, // Spotify
    )

    /** Blocking. Asks the TV which of the candidate apps are installed, in candidate order. */
    suspend fun detect(client: TvClient, ip: String, includeTizenBrew: Boolean): List<TvShortcut> {
        val candidates = if (includeTizenBrew) CANDIDATES + (TIZENBREW to Kind.TIZENBREW) else CANDIDATES
        return candidates.mapNotNull { (id, kind) ->
            currentCoroutineContext().ensureActive()
            client.appStatus(id, ip)?.let { TvShortcut(id, it.name.ifEmpty { "App" }, kind) }
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
