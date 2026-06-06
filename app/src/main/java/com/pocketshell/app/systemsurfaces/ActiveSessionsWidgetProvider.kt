package com.pocketshell.app.systemsurfaces

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.pocketshell.app.MainActivity
import com.pocketshell.app.R

class ActiveSessionsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun updateAll(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ActiveSessionsWidgetProvider::class.java)
            updateWidgets(context, appWidgetManager, appWidgetManager.getAppWidgetIds(component))
        }

        private fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            if (appWidgetIds.isEmpty()) return
            val state = runCatching { SystemSurfaceStateStore(context).readSessionWidgetState() }
                .getOrElse {
                    Log.w(SYSTEM_SURFACES_TAG, "widget state read failed", it)
                    SessionWidgetState(activeSessionCount = 0)
                }
            for (widgetId in appWidgetIds) {
                runCatching {
                    appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, state))
                }.onFailure {
                    Log.w(SYSTEM_SURFACES_TAG, "widget update failed for id=$widgetId", it)
                }
            }
        }

        private fun buildRemoteViews(
            context: Context,
            state: SessionWidgetState,
        ): RemoteViews {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(context.packageName, R.layout.widget_active_sessions).apply {
                setTextViewText(R.id.widget_session_count, state.activeSessionCount.toString())
                setTextViewText(R.id.widget_session_label, activeSessionCountText(state.activeSessionCount))
                setOnClickPendingIntent(R.id.widget_active_sessions_root, pendingIntent)
            }
        }
    }
}
