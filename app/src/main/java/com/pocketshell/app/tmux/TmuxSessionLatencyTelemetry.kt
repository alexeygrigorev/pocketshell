package com.pocketshell.app.tmux

import android.os.SystemClock
import com.pocketshell.app.diagnostics.DiagnosticEvents

/**
 * Lightweight latency/event sink for the tmux terminal attach path.
 *
 * Production keeps this in memory only; connected tests snapshot it and write
 * durable artifacts. The warm-switch events split the visible cached-frame
 * path from the later remote-refresh path so tests can gate the user's first
 * selected terminal frame without turning cold attach into a 100 ms contract.
 */
public object TmuxSessionLatencyTelemetry {
    private val lock = Any()
    private val events: MutableList<Event> = mutableListOf()

    public fun record(
        name: String,
        durationMs: Long,
        sessionName: String? = null,
        paneId: String? = null,
        trigger: TmuxConnectTrigger? = null,
        detail: String = "",
    ) {
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        synchronized(lock) {
            events += Event(
                name = name,
                durationMs = durationMs,
                elapsedRealtimeMs = elapsedRealtimeMs,
                sessionName = sessionName,
                paneId = paneId,
                trigger = trigger?.logValue,
                detail = detail,
            )
        }
        DiagnosticEvents.record(
            "connection",
            "tmux_latency",
            "operation" to name,
            "durationMs" to durationMs,
            "elapsedRealtimeMs" to elapsedRealtimeMs,
            "session" to sessionName,
            "paneId" to paneId,
            "trigger" to trigger?.logValue,
            "detail" to detail,
        )
    }

    public fun snapshot(): List<Event> = synchronized(lock) { events.toList() }

    public fun resetForTest() {
        synchronized(lock) {
            events.clear()
        }
    }

    public data class Event(
        val name: String,
        val durationMs: Long,
        val elapsedRealtimeMs: Long,
        val sessionName: String?,
        val paneId: String?,
        val trigger: String?,
        val detail: String,
    ) {
        public fun toArtifactLine(prefix: String = "tmux_latency"): String = buildString {
            append(prefix)
            append("_")
            append(name)
            append("_ms=")
            append(durationMs)
            sessionName?.let { append(" session=").append(it) }
            paneId?.let { append(" pane=").append(it) }
            trigger?.let { append(" trigger=").append(it) }
            if (detail.isNotBlank()) append(" ").append(detail)
        }
    }
}

public const val TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS: Long = 100L
public const val TMUX_WARM_SWITCH_CI_ADVISORY_P95_MS: Long = 5_000L
