package com.pocketshell.app.sessions.service

import com.pocketshell.app.sessions.ActiveTmuxClients

data class SessionConnectionSnapshot(
    val liveSessionCount: Int,
    val primaryHostName: String,
) {
    val isHoldingConnection: Boolean
        get() = liveSessionCount > 0

    companion object {
        val Empty = SessionConnectionSnapshot(
            liveSessionCount = 0,
            primaryHostName = "",
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
