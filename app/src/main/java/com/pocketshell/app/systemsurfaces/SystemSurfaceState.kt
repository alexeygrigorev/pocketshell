package com.pocketshell.app.systemsurfaces

import android.content.Context
import android.content.SharedPreferences

data class SessionWidgetState(
    val activeSessionCount: Int,
)

class SystemSurfaceStateStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSessionWidgetState(): SessionWidgetState =
        SessionWidgetState(
            activeSessionCount = prefs.safeInt(KEY_ACTIVE_SESSION_COUNT, 0).coerceAtLeast(0),
        )

    fun setActiveSessionCount(count: Int) {
        prefs.edit()
            .putInt(KEY_ACTIVE_SESSION_COUNT, count.coerceAtLeast(0))
            .apply()
    }

    private fun SharedPreferences.safeInt(key: String, default: Int): Int =
        runCatching { getInt(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private companion object {
        const val PREFS_NAME = "system_surfaces"
        const val KEY_ACTIVE_SESSION_COUNT = "active_session_count"
    }
}

fun activeSessionCountText(count: Int): String =
    when (val safeCount = count.coerceAtLeast(0)) {
        1 -> "1 active session"
        else -> "$safeCount active sessions"
    }

internal const val SYSTEM_SURFACES_TAG: String = "PsSystemSurfaces"
