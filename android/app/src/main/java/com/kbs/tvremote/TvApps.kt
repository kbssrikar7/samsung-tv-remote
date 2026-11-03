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
 * TV apps the remote offers shortcuts for: only YouTube. The GitHub build also probes TizenBrew,
 * which is not shown as a button but opens YouTube (TizenTube) instead of the stock app.
 */
object TvApps {

    // VIDEO and MUSIC are no longer offered but stay so shortcuts saved by older versions parse.
    enum class Kind { YOUTUBE, TIZENBREW, VIDEO, MUSIC }

    const val YOUTUBE = "111299001912"
    const val TIZENBREW = "xvvl3S1bvH.TizenBrewStandalone"

    private val CANDIDATES = listOf(YOUTUBE to Kind.YOUTUBE)
    private val KNOWN_IDS = CANDIDATES.map { it.first }.toSet() + TIZENBREW

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
        }.filter { it.id in KNOWN_IDS } // Drops shortcuts saved by versions that offered more apps.
    }.getOrDefault(emptyList())

    fun save(prefs: SharedPreferences, ip: String, apps: List<TvShortcut>) {
        val array = JSONArray()
        apps.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name)) }
        prefs.edit { putString(TvPrefs.apps(ip), array.toString()) }
    }
}
