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

/**
 * Wraps `SshLeaseManager`. The controller consults the warm-lease predicate and
 * observes the transport up/down edge stream; the VM adapter owns the lease IO.
 */
interface TransportPort {
    /** `liveLeaseKeys` / `hasLiveOrConnectingLease` — the warm-lease predicate
     *  that the single 60s grace check ANDs against. */
    fun isWarm(host: HostKey): Boolean

    /** == `SshLeaseManager.stateEvents` shape; the controller treats Up/Down as
     *  [ConnectionEvent.TransportLive]/[ConnectionEvent.TransportDropped]. */
    val transportEvents: Flow<TransportUpDown>
}

/**
 * Wraps `TmuxClient`. The controller observes the transport-drop oracle; the VM
 * adapter owns the control-mode IO.
 */
interface TmuxPort {
    /** The transport-drop oracle — `TmuxClient.disconnected`. */
    val disconnected: Flow<Boolean>
}
