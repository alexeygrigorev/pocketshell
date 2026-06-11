package com.pocketshell.core.connection

import kotlinx.coroutines.flow.Flow

/**
 * The thin async boundary the ViewModel will later implement to adapt the
 * existing `SshLeaseManager` + `TmuxClient` to the [ConnectionController]. These
 * keep `android.*` and IO OUT of the controller so the lifecycle logic stays a
 * pure, virtual-clock-testable state machine.
 *
 * NOTE (slice 1): this module is NOT yet wired into the VM — the VM-side
 * adapters that implement these ports are a later, #661-gated slice. The
 * interfaces are defined here (the owning module of the seam) so the adapters
 * have one contract to target.
 */

/** Replaces every `SystemClock.elapsedRealtime()` touchpoint. Injected so the
 *  within-grace / beyond-grace decision is deterministic under a virtual clock. */
interface Clock {
    fun nowMs(): Long
}

/** Transport up/down signal — the shape of `SshLeaseManager.stateEvents` (#329
 *  portfwd-reconnect subscription consumes this; the type shape is preserved). */
sealed interface TransportUpDown {
    data class Up(val host: HostKey) : TransportUpDown
    data class Down(val host: HostKey, val reason: String) : TransportUpDown
}

/** An opaque handle to a warm lease, returned by [TransportPort.ensureLease]. */
interface LeaseHandle {
    val host: HostKey
}

/**
 * Wraps `SshLeaseManager`. The controller decides WHEN to ensure/evict; the VM
 * adapter performs the actual lease IO (coalescing onto the #620 in-flight
 * connect, the #680 evict-and-retry-once heal — both pinned by the merged
 * #687-Phase-1 characterization suite).
 */
interface TransportPort {
    /** Coalesces onto the #620 in-flight connect; never fires a second handshake. */
    suspend fun ensureLease(host: HostKey): LeaseHandle

    /** `liveLeaseKeys` / `hasLiveOrConnectingLease` — the warm-lease predicate
     *  that the single 60s grace check ANDs against. */
    fun isWarm(host: HostKey): Boolean

    /** #680 evict-and-retry-once: closes the poisoned warm lease so the next
     *  [ensureLease] dials fresh. */
    suspend fun evictStale(host: HostKey)

    /** == `SshLeaseManager.stateEvents` shape; the controller treats Up/Down as
     *  [ConnectionEvent.TransportLive]/[ConnectionEvent.TransportDropped]. */
    val transportEvents: Flow<TransportUpDown>
}

/**
 * Wraps `TmuxClient`. The controller decides WHICH target/pane to attach,
 * select, seed, or detach; the VM adapter performs the control-mode IO under the
 * existing `sendMutex`.
 */
interface TmuxPort {
    /** Cold/warm attach to a target session. */
    suspend fun attach(targetId: SessionId)

    /** Fast-switch primitive: `select-window` on an already-attached control
     *  channel — no second `-CC`, no re-handshake. */
    suspend fun selectWindow(targetId: SessionId)

    /** Single capture of the active pane, returned as an id-tagged [Seed]. */
    suspend fun seedActivePane(targetId: SessionId): Seed

    /** Clean background detach of the control channel (lease stays warm). */
    suspend fun detachCleanly()

    /** The transport-drop oracle — `TmuxClient.disconnected`. */
    val disconnected: Flow<Boolean>
}
