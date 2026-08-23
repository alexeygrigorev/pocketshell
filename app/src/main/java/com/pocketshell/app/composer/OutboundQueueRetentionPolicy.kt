package com.pocketshell.app.composer

/**
 * Issue #1589: the pure storage/lifecycle policy for parked outbound rows.
 *
 * This is not a delivery authority and never claims or sends a row. Age alone
 * never authorizes deletion. A name-only fallback key, a failed/partial
 * reconcile, current-screen absence, or a same-name recreate is never proof
 * that a target is dead.
 */
public sealed class OutboundDisposalAuthorization {
    /** User tapped Delete on one visible retryable row. */
    public data class ExplicitDiscard(val rowId: String) : OutboundDisposalAuthorization()
}

public object OutboundQueueRetentionPolicy {

    public fun isDraftSidecarScope(outboundItemId: String): Boolean =
        outboundItemId.startsWith("draft/")

    public fun isOrphanSidecar(outboundItemId: String, liveRowIds: Set<String>): Boolean =
        outboundItemId.isNotBlank() &&
            !isDraftSidecarScope(outboundItemId) &&
            outboundItemId !in liveRowIds

    public fun mayDiscard(
        row: OutboundItem,
        authorization: OutboundDisposalAuthorization,
        nowMs: Long = 0L,
        createdAtMs: Long = row.createdAtMs,
    ): Boolean {
        // [nowMs]/[createdAtMs] exist so a clock mutation can prove age is ignored.
        if (row.state == OutboundState.Delivered) return false
        return when (authorization) {
            is OutboundDisposalAuthorization.ExplicitDiscard ->
                row.id == authorization.rowId && row.state.isExplicitlyDiscardable
        }
    }

}
