package com.kbs.tvremote

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/** The official YouTube app on the phone, used by the Play build's search hand-off. */
object YouTubeApp {
    private const val PACKAGE = "com.google.android.youtube"

    fun searchIntent(query: String): Intent = Intent(Intent.ACTION_SEARCH)
        .setPackage(PACKAGE)
        .putExtra(SearchManager.QUERY, query)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun webSearchIntent(query: String): Intent =
        Intent(Intent.ACTION_VIEW, ("https://www.youtube.com/results?search_query=" + Uri.encode(query)).toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** False when the app is missing or disabled; needs the manifest's package query. */
    fun isInstalled(context: Context): Boolean =
        context.packageManager.resolveActivity(searchIntent(""), 0) != null
}
