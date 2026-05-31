package com.pocketshell.app.proof

import android.os.Bundle

internal object LongRunningInstrumentationHeartbeat {
    const val STREAM_KEY: String = "stream"
    const val DEFAULT_INTERVAL_MS: Long = 15_000L

    fun sleepSliceMs(
        nowMs: Long,
        deadlineMs: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): Long {
        require(intervalMs > 0) { "heartbeat interval must be positive" }
        return (deadlineMs - nowMs)
            .coerceAtMost(intervalMs)
            .coerceAtLeast(0L)
    }

    fun line(
        elapsedMs: Long,
        nextTickIndex: Int,
        label: String,
    ): String = "LONG_RUNNING_HEARTBEAT elapsed_ms=$elapsedMs " +
        "next_tick_index=$nextTickIndex label=$label"

    fun streamBundle(line: String): Bundle =
        Bundle().apply {
            putString(STREAM_KEY, "$line\n")
        }
}
