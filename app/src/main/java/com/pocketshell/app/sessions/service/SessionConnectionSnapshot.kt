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
     * foreground (no count-down) or when there is no live hold.
     */
    val disconnectAtWallClockMillis: Long? = null,
) {
    val isHoldingConnection: Boolean
        get() = liveSessionCount > 0

    companion object {
        val Empty = SessionConnectionSnapshot(
            liveSessionCount = 0,
            primaryHostName = "",
            disconnectAtWallClockMillis = null,
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
