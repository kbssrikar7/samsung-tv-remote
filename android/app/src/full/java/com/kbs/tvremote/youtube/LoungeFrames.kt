package com.kbs.tvremote.youtube

import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import java.io.EOFException
import java.io.IOException

/** Read exactly the declared UTF-16 length, independent of lines and network packet boundaries. */
internal object LoungeFrames {
    fun read(source: BufferedSource): JSONArray? {
        if (source.exhausted()) return null
        val header = source.readUtf8LineStrict(64).trim()
        val length = header.toIntOrNull()
            ?.takeIf { it in 1..1_048_576 } ?: throw IOException("Invalid YouTube playback frame length")
        val text = StringBuilder()
        while (text.length < length) {
            if (source.exhausted()) throw EOFException("Incomplete YouTube playback frame")
            val codePoint = source.readUtf8CodePoint()
            text.appendCodePoint(codePoint)
        }
        if (text.length != length) throw IOException("Invalid Unicode playback frame boundary")
        return try { JSONArray(text.toString()) }
        catch (error: JSONException) { throw IOException("Invalid YouTube playback frame", error) }
    }
}
