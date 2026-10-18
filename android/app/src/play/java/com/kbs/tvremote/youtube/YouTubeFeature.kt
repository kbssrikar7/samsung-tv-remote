package com.kbs.tvremote.youtube

import android.content.SharedPreferences
import okhttp3.OkHttpClient

/** Play build: no in-app YouTube; searches open the official YouTube app instead. */
object YouTubeFeature {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun create(http: OkHttpClient, prefs: SharedPreferences): YouTubeRemote? = null
}
