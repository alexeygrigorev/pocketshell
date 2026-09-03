package com.pocketshell.core.transport

/**
 * Handle on a pending delayed close armed by
 * [HostConnection.scheduleGraceClose].
 */
interface GraceHandle {
    /**
     * Aborts the pending close. After this returns no timer belonging to this
     * handle may still fire (the D21 no-background-work contract). Idempotent.
     */
    fun cancel()

    /** Wall-clock epoch milliseconds at which the close fires if not cancelled. */
    val deadlineMs: Long
}
