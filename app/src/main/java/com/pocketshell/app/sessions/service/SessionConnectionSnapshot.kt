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
    /**
     * Issue #1440: the bounded grace window has elapsed and the connection is no longer a
     * live in-grace hold — the app has moved on to reconnecting. Set by
     * [SessionServiceController] when the deadline it scheduled on background fires while the
     * session is still held. The foreground-service notification then reads as RECONNECTING
     * (no "disconnecting in", no count-down) instead of freezing on the grace-hold copy with a
     * count-down that would drift PAST ZERO into a negative timer (the reported −06:51 defect).
     */
    val reconnecting: Boolean = false,
) {
    val isHoldingConnection: Boolean
        get() = liveSessionCount > 0

    /**
     * Issue #1440: the lifecycle phase the notification must faithfully render, resolved
     * against wall clock [nowMillis].
     *
     * A grace [disconnectAtWallClockMillis] is a LIVE count-down only while it is STRICTLY in
     * the future. Once it is at/in the past the grace window is over — rendering it as a
     * count-down chronometer would count PAST ZERO into a negative timer (the reported −06:51),
     * so a past deadline resolves to [Phase.RECONNECTING], never a count-down. The explicit
     * [reconnecting] flag forces the same phase even before the wall clock crosses the deadline
     * (the controller's scheduled flip), so the display never depends on a single racy `now`
     * comparison.
     */
    fun phaseAt(nowMillis: Long): Phase = when {
        reconnecting -> Phase.RECONNECTING
        disconnectAtWallClockMillis == null -> Phase.CONNECTED
        disconnectAtWallClockMillis > nowMillis -> Phase.HOLDING_GRACE
        else -> Phase.RECONNECTING
    }

    /** Issue #1440: the lifecycle phase the foreground-service notification renders. */
    enum class Phase {
        /** Live session held with no bounded-grace count-down (e.g. no deadline yet). */
        CONNECTED,

        /** Backgrounded within the bounded grace window — a live count-down to disconnect. */
        HOLDING_GRACE,

        /** Grace elapsed / connection dropped — the app is reconnecting. No count-down. */
        RECONNECTING,
    }

    companion object {
        val Empty = SessionConnectionSnapshot(
            liveSessionCount = 0,
            primaryHostName = "",
            disconnectAtWallClockMillis = null,
            portForwardActive = false,
            reconnecting = false,
        )

        fun fromEntries(entries: Collection<ActiveTmuxClients.Entry>): SessionConnectionSnapshot {
            val liveEntries = entries.filter { entry -> !entry.client.disconnected.value }
            // Issue #1440: fromEntries NEVER stamps a bounded-grace deadline — the deadline is
            // owned by [SessionServiceController] (set on background, cleared on foreground) and
            // merged in there. A raw entry snapshot therefore never carries a PAST deadline that
            // would keep the count-down branch selected once grace has elapsed.
            return SessionConnectionSnapshot(
                liveSessionCount = liveEntries.size,
                primaryHostName = liveEntries.firstOrNull()?.hostName.orEmpty(),
            )
        }
    }
}
