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
import com.pocketshell.app.session.LastSessionStore

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
            // Issue #1239: the widget tap is the fastest re-entry surface, so it
            // deep-links straight into the most recently-attached session instead
            // of dropping the user on the host-list top. The persisted snapshot is
            // read best-effort; when there is none (cold install / stale / killed)
            // the extras are omitted and MainActivity opens the host list — the
            // same non-dead-end fallback the host-card Resume affordance uses.
            val lastSession = runCatching { LastSessionStore(context).peek() }
                .onFailure { Log.w(SYSTEM_SURFACES_TAG, "widget last-session read failed", it) }
                .getOrNull()
            val intent = widgetLaunchIntent(context, lastSession)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                // FLAG_UPDATE_CURRENT replaces the extras of the existing
                // PendingIntent, so the deep-link target follows the latest
                // last-session snapshot rather than latching the first one.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(context.packageName, R.layout.widget_active_sessions).apply {
                setTextViewText(R.id.widget_session_count, state.activeSessionCount.toString())
                setTextViewText(R.id.widget_session_label, activeSessionCountText(state.activeSessionCount))
                setOnClickPendingIntent(R.id.widget_active_sessions_root, pendingIntent)
            }
        }

        /**
         * Issue #1239: build the widget-tap launch intent. When a fresh
         * last-session snapshot exists it carries the `EXTRA_OPEN_SESSION_*`
         * deep-link extras so [MainActivity] routes straight into that live
         * tmux session (same extras the share-into-session flow uses, consumed by
         * `MainActivity.shareSessionDestinationFromIntent`). With no snapshot the
         * bare intent opens the host list (non-dead-end fallback). Extracted +
         * `internal` so a JVM unit test can assert the deep-link extras directly.
         */
        @JvmStatic
        internal fun widgetLaunchIntent(
            context: Context,
            lastSession: LastSessionStore.LastSession?,
        ): Intent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (lastSession == null) return intent
            return intent
                .putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_ID, lastSession.hostId)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_NAME, lastSession.hostName)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION_HOSTNAME, lastSession.hostname)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION_PORT, lastSession.port)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION_USERNAME, lastSession.username)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION_KEY_PATH, lastSession.keyPath)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION_NAME, lastSession.sessionName)
        }
    }
}
