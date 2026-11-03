package com.kbs.tvremote

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/** Home-screen widget with Volume -, Mute, Volume +, Play and Pause (keys sent by [KeyService]). */
class RemoteWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_remote)
        KeyReceiver.BUTTONS.forEach { (viewId, key) -> views.setOnClickPendingIntent(viewId, KeyService.intent(context, key)) }
        manager.updateAppWidget(ids, views)
    }
}
