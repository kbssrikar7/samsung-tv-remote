package com.kbs.tvremote.youtube

/** A YouTube video as shown in search results, favourites and now playing. */
data class Video(
    val id: String,
    val title: String,
    val channel: String,
    val duration: String,
    val thumbnail: String,
)

enum class PlaybackState { PLAYING, PAUSED, BUFFERING, ENDED, STOPPED }

/** What the TV's YouTube reports about playback. Times are in seconds. */
data class Playback(val videoId: String, val state: PlaybackState, val position: Double, val duration: Double)

/**
 * Searching YouTube on the phone and controlling YouTube on the TV. Only the GitHub ("full")
 * build provides an implementation, because it relies on YouTube's unofficial web and TV-link
 * APIs; the Play build gets null from [YouTubeFeature] and hands searches to the YouTube app.
 * Every call is blocking: use a background dispatcher.
 */
interface YouTubeRemote {
    val isLinked: Boolean

    /** Links to the TV with the 12-digit code from YouTube → Settings → Link with TV code. */
    fun link(code: String)
    fun unlink()

    fun search(query: String): List<Video>

    /** Title and channel of a video started on the TV rather than from search. */
    fun details(videoId: String): Video?

    fun play(videoId: String)
    fun playNext(videoId: String)
    fun addToQueue(videoId: String)
    fun resume()
    fun pause()
    fun seekTo(seconds: Double)

    /** Follows playback on the TV until [stopWatching]; null means nothing is playing. */
    fun watch(onPlayback: (Playback?) -> Unit)
    fun stopWatching()
}
