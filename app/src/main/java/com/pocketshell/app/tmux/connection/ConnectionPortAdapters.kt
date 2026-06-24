package com.pocketshell.app.tmux.connection

import android.os.SystemClock
import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.LeaseHandle
import com.pocketshell.core.connection.Seed
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.ssh.SshLeaseConnectionState
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseStateEvent
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update

/**
 * EPIC #687 Phase-2, slice 1c — the production adapters that bridge the VM's
 * existing `SshLeaseManager` + `TmuxClient` collaborators to the pure-JVM
 * `:shared:core-connection` ports (`Clock` / `TransportPort` / `TmuxPort`).
 *
 * These keep `android.*` and the suspend IO OUT of `ConnectionController`, so
 * the lifecycle state machine stays a virtual-clock-testable reducer. The
 * controller decides WHEN to ensure/evict/attach/select/seed/detach; these
 * adapters perform the actual lease + control-mode IO.
 *
 * Slice 1c-i scope (this file): the adapters are real, compilable, and
 * unit-tested against the same `SshLeaseManager`/`TmuxClient` doubles the VM
 * suite already uses. They are NOT yet the lifecycle source of truth — the VM's
 * `reduceConnection` decision path stays inline until the atomic 1c-ii swap, so
 * there is no permanent dual decision path. The adapters exist so 1c-ii is a
 * body-swap against a verified seam, not a from-scratch wiring.
 */

/** Production [Clock]: the same monotonic source every grace/probe touchpoint in
 *  the VM uses (`SystemClock.elapsedRealtime()`). */
object SystemElapsedClock : Clock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Mint the controller's [HostKey] from the existing [SshLeaseKey] coordinates —
 * ONE place, so the lease key the transport actually keys on and the controller
 * key never drift (plan §3). The host string is opaque to the controller; it
 * only ever compares it for equality and round-trips it back to the adapter via
 * [hostKeyToLeaseKey]'s reverse is NOT needed — the adapter holds the
 * lease-key→host mapping (see [SshLeaseTransportPort]).
 */
fun hostKeyFor(leaseKey: SshLeaseKey): HostKey =
    HostKey(
        // Stable, collision-free encoding of the full lease identity. Mirrors
        // the lease key's own equality contract so two attaches to the same host
        // mint the same HostKey and a different credential/known-hosts mints a
        // different one.
        "${leaseKey.user}@${leaseKey.host}:${leaseKey.port}" +
            "/${leaseKey.credentialId}/${leaseKey.knownHostsId}",
    )

/**
 * [TransportPort] over [SshLeaseManager]. The published
 * [SshLeaseManager.stateEvents] type is UNCHANGED (pinned for #329) — this
 * adapter maps it internally to [TransportUpDown]; it does not replace the
 * SharedFlow.
 *
 * @param leaseManager the VM's existing lease manager.
 * @param leaseKeyFor resolves a controller [HostKey] back to the [SshLeaseTarget]
 *   the manager dials. The VM already builds an [SshLeaseTarget] per
 *   `ConnectionTarget`; the adapter is handed that resolver so there is exactly
 *   one place the host coordinates are derived (no drift with [hostKeyFor]).
 */
class SshLeaseTransportPort(
    private val leaseManager: SshLeaseManager,
    private val leaseKeyFor: (HostKey) -> SshLeaseTarget,
) : TransportPort {

    override suspend fun ensureLease(host: HostKey): LeaseHandle {
        // Coalesces onto the #620 in-flight connect — never a second handshake.
        leaseManager.acquire(leaseKeyFor(host)).getOrThrow()
        return object : LeaseHandle {
            override val host: HostKey = host
        }
    }

    /**
     * The warm-lease predicate the single 60s grace check ANDs against. The
     * controller calls this synchronously from its reducer, so this is a
     * non-suspending best-effort read off the lease manager's live set; the
     * authoritative `hasLiveOrConnectingLease` suspend query backs the reseed
     * path. NOTE: the controller only consults [isWarm] from inside [submit]
     * (synchronous), so the adapter must expose a non-suspending snapshot. The
     * VM owns that snapshot ([warmSnapshot]); 1c-ii feeds it here.
     */
    override fun isWarm(host: HostKey): Boolean = warmSnapshot(host)

    /** Non-suspending warm snapshot injected by the VM (the live-lease-key set
     *  the VM already maintains off [SshLeaseManager.stateEvents]). Defaults to
     *  "not warm" until the VM wires it in 1c-ii. */
    var warmSnapshot: (HostKey) -> Boolean = { false }

    override suspend fun evictStale(host: HostKey) {
        // #680 evict-and-retry-once: close the poisoned warm lease so the next
        // ensureLease dials fresh.
        leaseManager.evictIdle(leaseKeyFor(host).leaseKey)
    }

    /**
     * Maps the pinned [SshLeaseManager.stateEvents] to the controller's
     * [TransportUpDown] edges. Only the two TERMINAL transport edges the
     * controller acts on are surfaced: `Connected`→[TransportUpDown.Up] (heal /
     * `TransportLive`) and `Closed`→[TransportUpDown.Down] (the drop the
     * controller turns into `TransportDropped`). The mid-handshake `Connecting`
     * and steady-state `Idle` lease states are NOT transport up/down edges, so
     * they are dropped (filtered out) rather than mis-reported as a spurious
     * up or down — the controller must never see a fake edge.
     */
    override val transportEvents: Flow<TransportUpDown> =
        leaseManager.stateEvents.mapNotNull { event -> leaseStateToTransportEdge(event) }
}

/**
 * Pure mapping of the PINNED [SshLeaseStateEvent] shape (#329 — the published
 * type is unchanged) to the controller's [TransportUpDown] edges. Only the two
 * TERMINAL transport edges the controller acts on are surfaced:
 * `Connected`→[TransportUpDown.Up] (heal / `TransportLive`) and
 * `Closed`→[TransportUpDown.Down] (the drop the controller turns into
 * `TransportDropped`). The mid-handshake `Connecting` and steady-state `Idle`
 * lease states are NOT transport up/down edges, so they map to `null` (filtered
 * out) rather than being mis-reported as a spurious up or down — the controller
 * must never see a fake edge. Exposed as a top-level pure function so it is
 * unit-testable without a real lease manager.
 */
internal fun leaseStateToTransportEdge(event: SshLeaseStateEvent): TransportUpDown? {
    val host = hostKeyFor(event.key)
    return when (event.state) {
        SshLeaseConnectionState.Connected -> TransportUpDown.Up(host)
        SshLeaseConnectionState.Closed ->
            TransportUpDown.Down(host, reason = event.closeReason?.name ?: "closed")
        SshLeaseConnectionState.Connecting,
        SshLeaseConnectionState.Idle,
        -> null
    }
}

/**
 * EPIC #687 Phase-2, slice 1c-iv-b-A2 (#739) — a [TmuxPort] over the VM's CURRENT
 * control-mode client, which is swapped on every attach/reconnect/switch
 * (`TmuxSessionViewModel.clientRef`). It tracks the live client through a
 * [setClient]-updated [MutableStateFlow], so its [disconnected] oracle always
 * reflects whichever client is currently attached — the REAL transport-drop signal
 * the [ConnectionEffectDriver] observes, not a stub `emptyFlow`.
 *
 * The control IO methods ([attach]/[selectWindow]/[seedActivePane]/[detachCleanly])
 * delegate to the current client so this is a faithful real port — but in this
 * OBSERVE-ONLY slice the driver never invokes them (it only collects [disconnected]).
 * They exist so a later sub-slice can let the driver ACT against the same seam with
 * no re-wiring. When no client is attached, [disconnected] reports the idle "true"
 * (no live channel) and the IO methods throw — the driver never reaches them.
 *
 * @param activePaneIdFor resolves a [SessionId] to the active pane id to capture
 *   (the VM owns the session→pane mapping). Only consulted by [seedActivePane].
 * @param scrollbackLines capture depth for the seed (matches the VM's reseed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentClientTmuxPort(
    private val activePaneIdFor: (SessionId) -> String,
    private val scrollbackLines: Int,
) : TmuxPort {

    private val currentClient = MutableStateFlow<TmuxClient?>(null)

    /** Point the port at the freshly attached control client. Called by the VM from
     *  `attachClient`, so [disconnected] follows the live channel. */
    fun setClient(client: TmuxClient?) {
        currentClient.update { client }
    }

    private fun requireClient(): TmuxClient =
        currentClient.value
            ?: error("CurrentClientTmuxPort IO with no attached client (observe-only slice)")

    override suspend fun attach(targetId: SessionId) {
        requireClient().connect()
    }

    override suspend fun selectWindow(targetId: SessionId) {
        requireClient().sendCommand("select-window -t ${targetId.value}")
    }

    override suspend fun seedActivePane(targetId: SessionId): Seed {
        val paneId = activePaneIdFor(targetId)
        val capture =
            requireClient().captureWithCursor(paneId = paneId, scrollbackLines = scrollbackLines)
        val frame = capture.capture.output.joinToString("\n")
        return Seed(targetId = targetId, paneId = paneId, frame = frame)
    }

    override suspend fun detachCleanly() {
        requireClient().detachCleanly()
    }

    /**
     * The transport-drop oracle, flattened over the current client so a client swap
     * re-points the collected `disconnected` StateFlow without resubscribing the
     * driver. No attached client ⇒ "disconnected = true" (there is no live channel).
     */
    override val disconnected: Flow<Boolean> =
        currentClient.flatMapLatest { client ->
            client?.disconnected ?: flowOf(true)
        }

    /**
     * Typed current-client drop stream for the ViewModel-owned stale-client guard.
     * Unlike [disconnected], this carries the client instance that produced the true
     * edge, so a late old-client close can be rejected before the controller is moved.
     */
    val disconnectedClients: Flow<TmuxClient> =
        currentClient.flatMapLatest { client ->
            client?.disconnected
                ?.filter { it }
                ?.map { client }
                ?: emptyFlow()
        }
}
