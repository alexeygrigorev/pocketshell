package com.pocketshell.app.sessions

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal const val SSH_SOURCE_TMUX_CONNECT: String = "tmux-connect"
internal const val SSH_SOURCE_SESSION_PICKER_LIST: String = "session-picker-list"
internal const val SSH_SOURCE_FOLDER_LIST_PROBE: String = "folder-list-probe"
internal const val SSH_SOURCE_START_DIRECTORY_AUTOCOMPLETE: String = "start-directory-autocomplete"

/**
 * Process-local observability for fresh SSH opens that participate in
 * session switching. Tests snapshot these source counters around a
 * same-host switch to prove the path used the live tmux/SSH transport.
 */
internal object SshOpenTelemetry {
    private const val LOG_TAG: String = "PsSshOpen"

    private val total: AtomicInteger = AtomicInteger(0)
    private val bySource: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

    fun record(source: String, host: String, port: Int, user: String): Int {
        val sourceCount = sourceCounter(source).incrementAndGet()
        val totalCount = total.incrementAndGet()
        Log.d(
            LOG_TAG,
            "ssh-open source=$source sourceCount=$sourceCount total=$totalCount " +
                "host=$host port=$port user=$user",
        )
        return sourceCount
    }

    fun count(source: String): Int = bySource[source]?.get() ?: 0

    fun snapshot(): Map<String, Int> =
        bySource.mapValues { (_, counter) -> counter.get() }

    fun resetForTest() {
        total.set(0)
        bySource.values.forEach { it.set(0) }
    }

    private fun sourceCounter(source: String): AtomicInteger {
        bySource[source]?.let { return it }
        synchronized(bySource) {
            return bySource.getOrPut(source) { AtomicInteger(0) }
        }
    }
}
