package com.danielrosehill.canopyvoice

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home screen widget: one tap opens the chat thread and starts listening, so Yossi needs no wake word. An explicit
 * request, like Talk, so it works with the wake word off and with listening paused.
 */
class YossiWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
        // Request codes 0 to 2 are the notification's and the tile's.
        val tap = PendingIntent.getActivity(context, 3, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_yossi)
            views.setOnClickPendingIntent(R.id.widget_root, tap)
            manager.updateAppWidget(id, views)
        }
    }
}
