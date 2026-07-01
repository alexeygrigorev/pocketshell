package com.pocketshell.app.sessions.service

import com.pocketshell.app.sessions.ActiveTmuxClients

data class SessionConnectionSnapshot(
    val liveSessionCount: Int,
    val primaryHostName: String,
    /**
     * Issue #1123: while the app is BACKGROUNDED within the grace window this is the
     * wall-clock (`System.currentTimeMillis()`) instant the session will be torn down —
     * the deadline the bounded foreground-service notification renders as a live
     * count-down ("disconnecting in MM:SS") via the system chronometer. Null while
     * foreground (no count-down), while a port-forward pins the connection always-on
     * (issue #1159 Part 3 — no teardown, so no count-down), or when there is no live hold.
     */
    val disconnectAtWallClockMillis: Long? = null,
    /**
     * Issue #1159 (Part 3): true while a port-forward is active, pinning the connection
     * always-on (no bounded-grace teardown — explicit user intent, the D21 carve-out).
     * The foreground-service notification then reads "Port forwarding active" instead of
     * a count-down.
     */
    val portForwardActive: Boolean = false,
) {
    val isHoldingConnection: Boolean
        get() = liveSessionCount > 0

    companion object {
        val Empty = SessionConnectionSnapshot(
            liveSessionCount = 0,
            primaryHostName = "",
            disconnectAtWallClockMillis = null,
            portForwardActive = false,
        )

        fun fromEntries(entries: Collection<ActiveTmuxClients.Entry>): SessionConnectionSnapshot {
            val liveEntries = entries.filter { entry -> !entry.client.disconnected.value }
            return SessionConnectionSnapshot(
                liveSessionCount = liveEntries.size,
                primaryHostName = liveEntries.firstOrNull()?.hostName.orEmpty(),
            )
        }
    }
}
