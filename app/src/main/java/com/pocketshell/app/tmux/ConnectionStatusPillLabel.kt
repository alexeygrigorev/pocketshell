package com.pocketshell.app.tmux

import com.pocketshell.uikit.model.ConnectionStatus

/**
 * Issue #2130: honest, complete words for the breadcrumb connection pill.
 *
 * The preferred label is the full state name (`Reconnecting` / `Disconnected`).
 * When the yielding chrome slot is too narrow to hold that word — the
 * reported `Reco` clip — we step down to a shorter complete word rather than
 * letting Compose clip a prefix. A fragment is never a candidate.
 */
internal object ConnectionStatusPillLabels {
    const val RECONNECTING = "Reconnecting"
    const val RECONNECTING_COMPACT = "Retrying"
    const val RECONNECTING_MIN = "Retry"
    const val DISCONNECTED = "Disconnected"
    const val DISCONNECTED_COMPACT = "Offline"
    const val CONNECTING = "Connecting"

    fun candidates(status: ConnectionStatus): List<String> = when (status) {
        ConnectionStatus.Connected -> emptyList()
        ConnectionStatus.Connecting -> listOf(RECONNECTING, RECONNECTING_COMPACT, RECONNECTING_MIN)
        ConnectionStatus.Error -> listOf(DISCONNECTED, DISCONNECTED_COMPACT)
        ConnectionStatus.Idle -> listOf(CONNECTING)
    }

    /**
     * Longest complete candidate whose measured width fits [availableWidthPx].
     * If nothing fits, still return the shortest complete word — never a
     * truncated prefix like `Reco`.
     */
    fun pick(
        status: ConnectionStatus,
        availableWidthPx: Int,
        measurePx: (String) -> Int,
    ): String {
        val options = candidates(status)
        if (options.isEmpty()) return ""
        return options.firstOrNull { measurePx(it) <= availableWidthPx } ?: options.last()
    }
}
