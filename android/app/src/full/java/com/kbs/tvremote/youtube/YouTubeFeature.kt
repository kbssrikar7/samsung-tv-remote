package com.kbs.tvremote.youtube

import android.content.SharedPreferences
import okhttp3.OkHttpClient

/** GitHub build: in-app YouTube search and TV control. */
object YouTubeFeature {
    fun create(http: OkHttpClient, prefs: SharedPreferences): YouTubeRemote = InAppYouTube(YouTubeSearch(http), LoungeClient(http, prefs))
}

private class InAppYouTube(private val search: YouTubeSearch, private val lounge: LoungeClient) : YouTubeRemote {
    override val isLinked get() = lounge.isLinked
    override fun link(code: String) = lounge.pair(code)
    override fun unlink() = lounge.unlink()
    override fun search(query: String) = search.search(query)
    override fun details(videoId: String) = search.details(videoId)
    override fun play(videoId: String) = lounge.playVideo(videoId)
    override fun playNext(videoId: String) = lounge.playNext(videoId)
    override fun addToQueue(videoId: String) = lounge.addToQueue(videoId)
    override fun resume() = lounge.play()
    override fun pause() = lounge.pause()
    override fun seekTo(seconds: Double) = lounge.seekTo(seconds)
    override fun watch(onPlayback: (Playback?) -> Unit) = lounge.watch(onPlayback)
    override fun stopWatching() = lounge.stopWatching()
}
