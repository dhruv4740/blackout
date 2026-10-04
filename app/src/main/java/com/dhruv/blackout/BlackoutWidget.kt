package com.dhruv.blackout

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** One-tap home-screen widget (via ToggleActivity) that shows whether the black screen is on. */
class BlackoutWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, ToggleActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val views = views(context, Blackout.isArmed(context)).apply {
            setOnClickPendingIntent(R.id.widget_root, pi)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        /** Visuals only (no click), so screenshot tests can apply it. */
        fun views(context: Context, armed: Boolean) = RemoteViews(context.packageName, R.layout.widget).apply {
            setInt(R.id.widget_root, "setBackgroundResource", if (armed) R.drawable.widget_bg_on else R.drawable.widget_bg)
            setTextViewText(R.id.widget_status, if (armed) "On · tap to unlock" else "Tap to start")
            setTextColor(R.id.widget_status, if (armed) 0xFF4ADE80.toInt() else 0xFF8A8A8A.toInt())
        }

        fun refreshAll(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, BlackoutWidget::class.java))
            if (ids.isNotEmpty()) context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(ComponentName(context, BlackoutWidget::class.java))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
