package com.pocketshell.app.startup

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Issue #271: durable startup/connect timing markers for logcat-based
 * cold-launch measurements. The process-relative clock starts when this object
 * is first touched, which production wires from App.onCreate before other app
 * work starts.
 */
internal object StartupTiming {
    private const val TAG = "PsStartup"

    private val processStartElapsedMs = SystemClock.elapsedRealtime()
    private val onceKeys = ConcurrentHashMap.newKeySet<String>()

    fun mark(event: String, vararg fields: Pair<String, Any?>) {
        val now = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            buildString {
                append("event=").append(event)
                append(" processElapsedMs=").append(now - processStartElapsedMs)
                append(" elapsedRealtimeMs=").append(now)
                for ((key, value) in fields) {
                    append(' ')
                    append(key)
                    append('=')
                    append(value.toLogValue())
                }
            },
        )
    }

    fun markOnce(event: String, vararg fields: Pair<String, Any?>) {
        if (onceKeys.add(event)) {
            mark(event, *fields)
        }
    }
}

private fun Any?.toLogValue(): String = when (this) {
    null -> "null"
    is Boolean, is Number -> toString()
    else -> toString()
        .replace('\n', '_')
        .replace('\r', '_')
        .replace('\t', '_')
        .replace(' ', '_')
}
