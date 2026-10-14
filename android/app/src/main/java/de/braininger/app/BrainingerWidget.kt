package de.braininger.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Startbildschirm-Widget mit „Gedanke“ und „Gespräch“, während der Aufnahme mit „Stopp“. */
class BrainingerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        aktualisieren(context)
    }

    companion object {
        fun aktualisieren(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BrainingerWidget::class.java))
            if (ids.isEmpty()) return
            val laeuft = AufnahmeStatus.zustand.value.laeuft

            val ansicht = RemoteViews(context.packageName, R.layout.widget_braininger).apply {
                setViewVisibility(R.id.widget_bereit, if (laeuft) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_stopp, if (laeuft) View.VISIBLE else View.GONE)
                setOnClickPendingIntent(R.id.widget_gedanke, tipp(context, StartActivity.WAS_GEDANKE, 10))
                setOnClickPendingIntent(R.id.widget_gespraech, tipp(context, StartActivity.WAS_GESPRAECH, 11))
                setOnClickPendingIntent(R.id.widget_stopp, tipp(context, StartActivity.WAS_STOPP, 12))
            }
            manager.updateAppWidget(ids, ansicht)
        }

        private fun tipp(context: Context, was: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context, code,
                Intent(context, StartActivity::class.java)
                    .putExtra(StartActivity.EXTRA_WAS, was)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
