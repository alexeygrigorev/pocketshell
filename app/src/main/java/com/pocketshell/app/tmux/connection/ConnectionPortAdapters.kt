package com.pocketshell.app.tmux.connection

import android.os.SystemClock
import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.ssh.SshLeaseCloseReason
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

    /**
     * The warm-lease predicate the single controller grace check ANDs against. The
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
 *
 * Issue #1632: this is the EMITTER of the lease `Down` edge, so it is where the
 * local-intent token is stamped. [SshLeaseManager] already names WHY it closed at
 * each of its own `emitStateLocked` call sites; [SelfInflictedClose] turns that
 * named reason into [TransportUpDown.Down.locallyInitiated] once, here, so no
 * downstream consumer ever has to infer "was that teardown us?" from a reason
 * string. That inference gap is the #1632 defect: recovery's own
 * `sshLeaseManager.disconnect()` looked, downstream, exactly like a remote drop.
 */
internal fun leaseStateToTransportEdge(event: SshLeaseStateEvent): TransportUpDown? {
    val host = hostKeyFor(event.key)
    return when (event.state) {
        SshLeaseConnectionState.Connected -> TransportUpDown.Up(host)
        SshLeaseConnectionState.Closed ->
            TransportUpDown.Down(
                host = host,
                reason = transportDropReason(event.closeReason),
                locallyInitiated = SelfInflictedClose.isSelfInflictedLeaseClose(event.closeReason),
            )

        SshLeaseConnectionState.Connecting,
        SshLeaseConnectionState.Idle,
        -> null
    }
}

/**
 * Issue #969 — canonical, lower-snake reconnect-cause token for a lease `Closed`
 * event's [SshLeaseCloseReason]. The keepalive watchdog (#945) close is NAMED
 * `keepalive_dead` so a keepalive-driven drop is no longer an anonymous
 * `lease_down:Disconnected` in the reconnect trail — the #964 attribution
 * ambiguity. Every other reason keeps its existing enum name so genuine
 * lease-downs (`Disconnected`), explicit teardowns, idle expiry, etc. stay
 * distinct (not mislabelled as keepalive death). Exposed as a top-level pure
 * function so it is unit-testable without a real lease manager.
 */
internal fun transportDropReason(closeReason: SshLeaseCloseReason?): String =
    when (closeReason) {
        SshLeaseCloseReason.KeepaliveDead -> KEEPALIVE_DEAD_REASON
        null -> "closed"
        else -> closeReason.name
    }

/**
 * Canonical reconnect-cause token (#969) for a transport the always-on keepalive
 * (#945) declared dead. Matches the lower-snake convention of the other
 * [com.pocketshell.app.diagnostics.ReconnectCauseTrail] causes.
 */
internal const val KEEPALIVE_DEAD_REASON: String = "keepalive_dead"

/**
 * EPIC #687 Phase-2, slice 1c-iv-b-A2 (#739) — a [TmuxPort] over the VM's CURRENT
 * control-mode client, which is swapped on every attach/reconnect/switch
 * (`TmuxSessionViewModel.clientRef`). It tracks the live client through a
 * [setClient]-updated [MutableStateFlow], so its [disconnected] oracle always
 * reflects whichever client is currently attached — the REAL transport-drop signal
 * the [ConnectionEffectDriver] observes, not a stub `emptyFlow`.
 *
 * This is an OBSERVE-ONLY port: it exposes the [disconnected] drop oracle (and the
 * typed [disconnectedClients] stream) the driver collects; it performs NO control IO.
 *
 * @param activePaneIdFor resolves a [SessionId] to the active pane id (the VM owns the
 *   session→pane mapping). Retained on the VM-owned constructor call site; not read here.
 * @param scrollbackLines capture depth. Retained on the VM-owned constructor call site;
 *   not read here.
 */
@Suppress("unused") // activePaneIdFor/scrollbackLines: VM-owned constructor call site.
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
