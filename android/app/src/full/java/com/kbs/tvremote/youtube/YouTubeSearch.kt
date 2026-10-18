package com.kbs.tvremote.youtube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Searches YouTube through InnerTube, the JSON API the youtube.com website itself calls.
 * It needs no API key; the response layout is undocumented, so parsing is defensive.
 */
class YouTubeSearch(private val http: OkHttpClient) {

    /** Blocking; call from a background dispatcher. */
    @Throws(IOException::class)
    fun search(query: String): List<Video> {
        val body = JSONObject()
            .put(
                "context", JSONObject().put(
                    "client", JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "en")
                )
            )
            .put("query", query)
            .put("params", VIDEOS_ONLY)
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube search failed (${response.code})")
            return parse(JSONObject(response.body!!.string()))
        }
    }

    /** Blocking. Title and channel of [videoId] (for videos started on the TV, not from search). */
    fun details(videoId: String): Video? = runCatching {
        val url = "https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=$videoId"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body!!.string())
            Video(
                id = videoId,
                title = json.optString("title"),
                channel = json.optString("author_name"),
                duration = "",
                thumbnail = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            )
        }
    }.getOrNull()

    private fun parse(root: JSONObject): List<Video> {
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return emptyList()
        val videos = mutableListOf<Video>()
        for (section in sections.objects()) {
            val items = section.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
            for (item in items.objects()) {
                val v = item.optJSONObject("videoRenderer") ?: continue
                val id = v.optString("videoId").ifEmpty { continue }
                videos += Video(
                    id = id,
                    title = v.optJSONObject("title")?.runsText() ?: continue,
                    channel = v.optJSONObject("ownerText")?.runsText().orEmpty(),
                    duration = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty(),
                    // Medium-quality thumbnail: always present and small enough for a list.
                    thumbnail = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                )
            }
        }
        return videos
    }

    private fun JSONObject.runsText(): String? {
        val runs = optJSONArray("runs") ?: return optString("simpleText").ifEmpty { null }
        return runs.objects().joinToString("") { it.optString("text") }.ifEmpty { null }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private companion object {
        const val CLIENT_VERSION = "2.20250101.00.00"

        /** Search filter "Type: Video" (excludes channels, playlists and shorts shelves). */
        const val VIDEOS_ONLY = "EgIQAQ%3D%3D"
    }
}
