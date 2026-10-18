package com.kbs.tvremote.youtube

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Favourite videos and recent searches, kept on the phone. */
class Library(private val prefs: SharedPreferences) {

    fun favourites(): List<Video> = runCatching {
        val array = JSONArray(prefs.getString(KEY_FAVOURITES, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Video(
                id = o.getString("id"),
                title = o.getString("title"),
                channel = o.optString("channel"),
                duration = o.optString("duration"),
                thumbnail = o.optString("thumbnail"),
            )
        }
    }.getOrDefault(emptyList())

    /** Adds [video] to the front of the favourites, or removes it if it is already there. */
    fun toggleFavourite(video: Video): List<Video> {
        val current = favourites()
        val updated = if (current.any { it.id == video.id }) current.filterNot { it.id == video.id } else listOf(video) + current
        val array = JSONArray()
        updated.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("channel", it.channel)
                    .put("duration", it.duration)
                    .put("thumbnail", it.thumbnail)
            )
        }
        prefs.edit { putString(KEY_FAVOURITES, array.toString()) }
        return updated
    }

    fun recentSearches(): List<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_RECENTS, "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    /** Records [query] as the most recent search and returns the updated list. */
    fun addRecentSearch(query: String): List<String> {
        val updated = (listOf(query) + recentSearches().filterNot { it.equals(query, ignoreCase = true) }).take(MAX_RECENTS)
        prefs.edit { putString(KEY_RECENTS, JSONArray(updated).toString()) }
        return updated
    }

    private companion object {
        const val KEY_FAVOURITES = "yt_favourites"
        const val KEY_RECENTS = "yt_recent_searches"
        const val MAX_RECENTS = 8
    }
}
