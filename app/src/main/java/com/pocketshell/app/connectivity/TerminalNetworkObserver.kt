package com.pocketshell.app.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passive default-network change signal for live terminal transports.
 *
 * Android keeps TCP sockets bound to the network they were opened on; a
 * wifi-cellular handoff can leave sshj reading from a socket that only reports
 * death on a later read/write. This class does not reconnect anything by
 * itself. It only emits in-process validated-default-network changes so the
 * foreground terminal flow can reconnect proactively.
 */
@Singleton
class TerminalNetworkObserver @Inject constructor(
    @ApplicationContext appContext: Context,
) {
    private val cm: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val detector = TerminalNetworkChangeDetector(initial = currentSnapshot())
    private val _changes = MutableSharedFlow<TerminalNetworkChange>(
        extraBufferCapacity = 16,
    )

    val changes: SharedFlow<TerminalNetworkChange> = _changes.asSharedFlow()

    /**
     * The registered default-network callback, retained so [close] can
     * unregister it.
     *
     * Issue #956 (#935 S3-3): the registration used to have no matching
     * unregister. Safe while this stays a process-singleton, but a latent
     * leak / ghost-callback risk if it is ever scoped tighter or
     * re-created. We retain the reference and unregister it in [close] —
     * hard-cut, no leftover unregistered path.
     */
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var closed: Boolean = false

    init {
        val manager = cm
        if (manager == null) {
            Log.w(TAG, "connectivity-manager-missing")
        } else {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refresh("default-network-available")
                }

                override fun onLost(network: Network) {
                    refresh("default-network-lost")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    updateFromSnapshot(
                        snapshotFrom(network, networkCapabilities),
                        "default-network-capabilities",
                    )
                }

                override fun onUnavailable() {
                    updateFromSnapshot(
                        TerminalNetworkSnapshot.NoValidatedNetwork,
                        "default-network-unavailable",
                    )
                }
            }
            runCatching {
                manager.registerDefaultNetworkCallback(cb)
            }.onSuccess {
                callback = cb
            }.onFailure {
                Log.w(TAG, "register-default-network-callback-failed", it)
            }
        }
    }

    /**
     * Unregisters the default-network [ConnectivityManager.NetworkCallback].
     * Idempotent — a second call (or a call before registration ever
     * succeeded) is a no-op. Gives the registration a defined teardown so a
     * scoped/re-created observer can no longer leak a ghost callback
     * (#956, #935 S3-3). Does NOT touch any reconnect/handoff logic — that is
     * owned elsewhere; this only releases the platform callback.
     */
    fun close() {
        if (closed) return
        closed = true
        val cb = callback ?: return
        callback = null
        runCatching { cm?.unregisterNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "unregister-default-network-callback-failed", it) }
    }

    fun refresh(reason: String = "refresh"): TerminalNetworkChange? =
        updateFromSnapshot(currentSnapshot(), reason)

    /**
     * Issue #875 (Angle C) test seam: push a SYNTHETIC default-network snapshot
     * through the REAL detector + REAL emit pipeline (the same code production
     * runs on a `ConnectivityManager` callback), so a connected journey can drive
     * a same-SSID wifi reassociation deterministically (the AVD can't mint a new
     * `networkHandle` on demand). Returns the emitted change, or null when the
     * detector suppressed it (the fixed pure-WIFI reassoc case). Not for production.
     */
    @androidx.annotation.VisibleForTesting
    fun emitSyntheticSnapshotForTest(
        snapshot: TerminalNetworkSnapshot,
        reason: String,
    ): TerminalNetworkChange? = updateFromSnapshot(snapshot, reason)

    private fun updateFromSnapshot(
        snapshot: TerminalNetworkSnapshot,
        reason: String,
    ): TerminalNetworkChange? {
        val change = detector.update(snapshot = snapshot, reason = reason) ?: return null
        // Issue #997: the trail outcome is kind-aware so a bare loss / restore is
        // visible in the device trail (not mislabelled as a validated handoff).
        val outcome = when (change.kind) {
            TerminalNetworkChangeKind.NetworkLost -> "network_lost"
            TerminalNetworkChangeKind.NetworkRestored -> "network_restored"
            TerminalNetworkChangeKind.ValidatedIdentityChange -> "validated_default_changed"
        }
        Log.i(
            TAG,
            "terminal-network-change kind=${change.kind} sequence=${change.sequence} " +
                "reason=${change.reason} " +
                "previous=${change.previous.logValue} current=${change.current.logValue}",
        )
        DiagnosticEvents.record(
            "network",
            outcome,
            *change.networkDiagnosticFields(),
        )
        ReconnectCauseTrail.record(
            stage = "network_callback",
            outcome = outcome,
            cause = reason,
            "sequence" to change.sequence,
            "previousNetworkHandle" to change.previous.networkHandle,
            "currentNetworkHandle" to change.current.networkHandle,
            "previousValidatedNetworkHandle" to change.previousValidated?.networkHandle,
            "previousTransports" to change.previous.transportSetLogValue,
            "currentTransports" to change.current.transportSetLogValue,
        )
        _changes.tryEmit(change)
        return change
    }

    private fun currentSnapshot(): TerminalNetworkSnapshot {
        val manager = cm ?: return TerminalNetworkSnapshot.NoValidatedNetwork
        val active = manager.activeNetwork ?: return TerminalNetworkSnapshot.NoValidatedNetwork
        val capabilities = manager.getNetworkCapabilities(active)
            ?: return TerminalNetworkSnapshot.NoValidatedNetwork
        return snapshotFrom(active, capabilities)
    }

    private fun snapshotFrom(
        network: Network,
        capabilities: NetworkCapabilities,
    ): TerminalNetworkSnapshot =
        if (
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            TerminalNetworkSnapshot.Validated(
                networkHandle = network.networkHandle.toString(),
                transports = capabilities.transportNames(),
            )
        } else {
            TerminalNetworkSnapshot.NoValidatedNetwork
        }

    companion object {
        private const val TAG = "PsTerminalNetwork"
    }
}

data class TerminalNetworkChange(
    val previous: TerminalNetworkSnapshot,
    val current: TerminalNetworkSnapshot,
    val previousValidated: TerminalNetworkSnapshot.Validated?,
    val reason: String,
    val sequence: Long,
    val deferredFromBackground: Boolean = false,
    /**
     * Issue #997: the event class. The detector models two ORTHOGONAL signals:
     *
     * - [TerminalNetworkChangeKind.ValidatedIdentityChange] — the original #548
     *   signal: the default network's validated *identity* changed while never
     *   losing validation (WIFI→CELLULAR handoff, VPN up/down). The proven-alive
     *   ride-through (#981) and the same-identity suppression (#875) apply here.
     * - [TerminalNetworkChangeKind.NetworkLost] — a bare availability LOSS
     *   (`onLost` / airplane mode / `onUnavailable`): the default network dropped
     *   to no-validated-network. Surfaced IMMEDIATELY so the UI can flip to
     *   reconnecting and probes can suspend, but it does NOT tear the lease down
     *   (a loss is frequently transient — pocket, elevator). The pre-#997 detector
     *   swallowed this entirely (`return null` on any non-validated snapshot), so a
     *   clean drop was only noticed reactively ~90s later by the keepalive.
     * - [TerminalNetworkChangeKind.NetworkRestored] — validation returned after a
     *   loss. Recovers even from a non-Connected (loss-suspended) state, even when
     *   the restored identity equals the pre-loss one (the airplane-mode round-trip
     *   the pre-#997 detector also swallowed at `:333`). Issue #1042 (cause #1)
     *   makes the recovery LIVENESS-FIRST: a brief no-validated-network window
     *   (tunnel, elevator, RAT handover, congestion re-validation) does NOT mean
     *   the TCP socket died — cellular hits those windows constantly — so the VM
     *   rides through with NO redial when the existing transport is proven alive
     *   (keepalive within its window, or a single bounded probe answers), and only
     *   forces the #997 fresh-lease redial when the transport does NOT answer (a
     *   genuinely dead post-outage socket still reconnects).
     */
    val kind: TerminalNetworkChangeKind = TerminalNetworkChangeKind.ValidatedIdentityChange,
)

/**
 * Issue #997: the orthogonal event classes a [TerminalNetworkChange] can carry.
 * See [TerminalNetworkChange.kind].
 */
enum class TerminalNetworkChangeKind {
    /** The default network's validated identity changed (the #548 handoff). */
    ValidatedIdentityChange,

    /** A bare availability loss — surfaced immediately, lease held, no churn. */
    NetworkLost,

    /** Validation returned after a loss — drives a fast reconnect. */
    NetworkRestored,
}

sealed interface TerminalNetworkSnapshot {
    val logValue: String
    val networkHandle: String?
    val transportSetLogValue: String

    data class Validated(
        override val networkHandle: String,
        val transports: Set<String> = emptySet(),
    ) : TerminalNetworkSnapshot {
        override val logValue: String = "validated:$networkHandle"
        override val transportSetLogValue: String =
            transports.toSortedSet().joinToString(",").ifBlank { "UNKNOWN" }
    }

    object NoValidatedNetwork : TerminalNetworkSnapshot {
        override val logValue: String = "none"
        override val networkHandle: String? = null
        override val transportSetLogValue: String = "none"
    }
}

internal fun TerminalNetworkChange.networkDiagnosticFields(): Array<Pair<String, Any?>> =
    arrayOf(
        "sequence" to sequence,
        "kind" to kind.name,
        "reason" to reason,
        "previous" to previous.logValue,
        "current" to current.logValue,
        "previousNetworkHandle" to previous.networkHandle,
        "currentNetworkHandle" to current.networkHandle,
        "previousTransports" to previous.transportSetLogValue,
        "currentTransports" to current.transportSetLogValue,
        "previousValidatedNetworkHandle" to previousValidated?.networkHandle,
        "previousValidatedTransports" to previousValidated?.transportSetLogValue,
        "deferredFromBackground" to deferredFromBackground,
    )

/**
 * Issue #875 (Angle C — same-SSID wifi reassoc → false validated-handoff).
 *
 * The `#548` proactive-reconnect feature treats any change in the default
 * network's identity as a handoff worth a fresh-lease reconnect. Identity used
 * to be `networkHandle` equality ALONE — but a *physically stable* wifi still
 * mints a NEW `networkHandle` when the supplicant re-associates (a 2.4↔5 GHz
 * band-steer, a mesh/extender AP roam, a brief RF re-association). SSH almost
 * always survives a same-AP/same-IP reassoc, so the resulting teardown+redial is
 * a self-inflicted ~1s flap on stable wifi — exactly the maintainer's report.
 *
 * A genuine handoff that #548 must still catch crosses transports (WIFI→CELLULAR,
 * a VPN coming up/down, ETHERNET↔WIFI). So we now treat two validated snapshots
 * as the SAME network identity when EITHER the handle matches OR the transport
 * sets are identical AND that set is a *single benign-reassoc* transport (no VPN /
 * ethernet / tethering / multi-transport on either side). That suppresses the
 * band-steer/mesh reassoc (the spurious case) while a real transport change still
 * flips identity and reconnects.
 *
 * Issue #1042 (cause #2 — cellular churn): the relaxation used to be scoped to
 * pure `{WIFI}` ONLY, so a `{CELLULAR}` RAT/band re-association (or a v4↔v6
 * dual-stack re-validation) that mints a NEW `networkHandle` while staying on the
 * SAME single cellular transport was treated as a real handoff → a self-inflicted
 * fresh-lease redial on a quiet/idle cellular session (the maintainer's "constant
 * reconnects on mobile internet"). Cellular hits these handle-churning
 * re-validations FAR more often than Wi-Fi. So we now treat a same-transport-set
 * reassoc as the same identity for `{WIFI}` *or* `{CELLULAR}` (a dual-stack v4↔v6
 * flip keeps the same single transport set, so it is covered too). We deliberately
 * keep the strict handle check for any set carrying VPN / ethernet / multiple
 * transports: a VPN coming up or a real cross-transport WIFI↔CELLULAR handoff
 * still flips identity and redials.
 */
internal fun TerminalNetworkSnapshot.Validated.hasSameNetworkIdentityAs(
    other: TerminalNetworkSnapshot.Validated,
): Boolean =
    networkHandle == other.networkHandle ||
        (transports == other.transports && isBenignReassocTransportSet())

/**
 * Issue #1042: the single-transport networks whose supplicant/RAT re-association
 * mints a new `networkHandle` on a PHYSICALLY STABLE link that SSH almost always
 * survives — a Wi-Fi band-steer / mesh roam, or a cellular RAT/band/dual-stack
 * re-validation. A new handle on one of these, with an unchanged transport set, is
 * a benign reassoc (suppress the redial), NOT a real handoff.
 */
private val BENIGN_REASSOC_TRANSPORT_SETS: Set<Set<String>> = setOf(
    setOf("WIFI"),
    setOf("CELLULAR"),
)

private fun TerminalNetworkSnapshot.Validated.isBenignReassocTransportSet(): Boolean =
    transports in BENIGN_REASSOC_TRANSPORT_SETS

internal fun TerminalNetworkSnapshot.hasSameNetworkIdentityAs(
    other: TerminalNetworkSnapshot,
): Boolean =
    when {
        this is TerminalNetworkSnapshot.Validated && other is TerminalNetworkSnapshot.Validated ->
            hasSameNetworkIdentityAs(other)
        this is TerminalNetworkSnapshot.NoValidatedNetwork &&
            other is TerminalNetworkSnapshot.NoValidatedNetwork -> true
        else -> false
    }

private fun NetworkCapabilities.transportNames(): Set<String> =
    buildSet {
        TRANSPORT_PROBES.forEach { probe ->
            if (probe.isPresentIn(this@transportNames)) add(probe.name)
        }
    }

private data class TransportProbe(
    val value: Int,
    val name: String,
    val minSdk: Int = Build.VERSION_CODES.M,
) {
    fun isPresentIn(capabilities: NetworkCapabilities): Boolean =
        Build.VERSION.SDK_INT >= minSdk &&
            runCatching { capabilities.hasTransport(value) }.getOrDefault(false)
}

private val TRANSPORT_PROBES = listOf(
    TransportProbe(NetworkCapabilities.TRANSPORT_WIFI, "WIFI"),
    TransportProbe(NetworkCapabilities.TRANSPORT_CELLULAR, "CELLULAR"),
    TransportProbe(NetworkCapabilities.TRANSPORT_VPN, "VPN"),
    TransportProbe(NetworkCapabilities.TRANSPORT_ETHERNET, "ETHERNET"),
    TransportProbe(NetworkCapabilities.TRANSPORT_BLUETOOTH, "BLUETOOTH"),
    TransportProbe(NetworkCapabilities.TRANSPORT_WIFI_AWARE, "WIFI_AWARE", Build.VERSION_CODES.O),
    TransportProbe(NetworkCapabilities.TRANSPORT_LOWPAN, "LOWPAN", Build.VERSION_CODES.O_MR1),
    TransportProbe(NetworkCapabilities.TRANSPORT_USB, "USB", Build.VERSION_CODES.S),
    TransportProbe(NetworkCapabilities.TRANSPORT_THREAD, "THREAD", Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
    TransportProbe(NetworkCapabilities.TRANSPORT_SATELLITE, "SATELLITE", Build.VERSION_CODES.VANILLA_ICE_CREAM),
)

/**
 * Issue #995 (#935 H2): `update` is driven directly from concurrent
 * `ConnectivityManager` binder callbacks (`onAvailable` / `onLost` /
 * `onCapabilitiesChanged` / `onUnavailable` all dispatch on the framework's
 * own threads), so two callbacks can enter `update` at once. The detector's
 * identity state (`current` / `lastValidated` / `sequence`) was mutated with
 * NO synchronization — a cross-thread race that can either (a) suppress a real
 * handoff (both threads read the same stale `current`, one wins the write, the
 * transport-changing snapshot's emission is lost → no reconnect → dead socket)
 * or (b) tear/duplicate the change (two threads both increment `sequence` off a
 * stale read → a duplicate/torn emission → spurious reconnect).
 *
 * The whole compare-and-mutate (read `current`/`lastValidated`, decide, write
 * back, bump `sequence`) must be ONE atomic critical section. We guard the
 * entire body with a single lock so concurrent callbacks serialize: each
 * `update` sees the result of the previous one, so no handoff is dropped and no
 * duplicate sequence is emitted. This only makes the state mutation thread-safe
 * — the handoff POLICY (what counts as a handoff) is unchanged (that is #981).
 */
internal class TerminalNetworkChangeDetector(
    initial: TerminalNetworkSnapshot,
) {
    private val lock = Any()
    private var current: TerminalNetworkSnapshot = initial
    private var lastValidated: TerminalNetworkSnapshot.Validated? =
        initial as? TerminalNetworkSnapshot.Validated
    private var sequence: Long = 0L

    /**
     * Issue #997: the second, orthogonal state machine — whether the default
     * network is currently in a bare-LOSS window (no validated network). Set
     * when a [TerminalNetworkChangeKind.NetworkLost] is emitted, cleared when a
     * [TerminalNetworkChangeKind.NetworkRestored] is emitted. Like the identity
     * state it lives UNDER the same `lock` (#995): concurrent `onLost`/
     * `onAvailable` binder callbacks must see one another's writes so a loss is
     * surfaced exactly once and the matching restore reconnects exactly once.
     */
    private var lost: Boolean = false

    fun update(
        snapshot: TerminalNetworkSnapshot,
        reason: String,
    ): TerminalNetworkChange? = synchronized(lock) {
        val previous = current
        val previousValidated = lastValidated

        // Issue #997: the bare availability-LOSS / RESTORE signal, orthogonal to
        // the #548 validated-identity-change signal below. Evaluated FIRST so a
        // loss/restore is surfaced even when the identity-change path would have
        // swallowed it (a non-validated snapshot, or a same-identity restore).
        if (snapshot !is TerminalNetworkSnapshot.Validated) {
            // Transition into the loss window. Idempotent: repeated
            // NoValidatedNetwork callbacks while already lost emit nothing (no
            // churn during the loss). `current` is still advanced so the identity
            // state stays coherent for the eventual restore.
            current = snapshot
            if (lost) return@synchronized null
            lost = true
            sequence += 1L
            return@synchronized TerminalNetworkChange(
                previous = previous,
                current = snapshot,
                previousValidated = previousValidated,
                reason = reason,
                sequence = sequence,
                kind = TerminalNetworkChangeKind.NetworkLost,
            )
        }
        if (lost) {
            // Validation returned after a loss. Emit RESTORED even when the
            // restored identity equals the pre-loss one — that same-identity
            // restore is exactly the airplane-mode round-trip the pre-#997
            // detector swallowed at `:333`, leaving a dead socket un-recovered.
            current = snapshot
            lastValidated = snapshot
            lost = false
            sequence += 1L
            return@synchronized TerminalNetworkChange(
                previous = previous,
                current = snapshot,
                previousValidated = previousValidated,
                reason = reason,
                sequence = sequence,
                kind = TerminalNetworkChangeKind.NetworkRestored,
            )
        }

        // --- The original #548 validated-identity-change signal (unchanged). ---
        if (previous.hasSameNetworkIdentityAs(snapshot)) {
            current = snapshot
            lastValidated = snapshot
            return@synchronized null
        }
        current = snapshot
        if (previousValidated == null) {
            lastValidated = snapshot
            return@synchronized null
        }
        if (snapshot.hasSameNetworkIdentityAs(previousValidated)) {
            lastValidated = snapshot
            return@synchronized null
        }
        lastValidated = snapshot
        sequence += 1L
        TerminalNetworkChange(
            previous = previous,
            current = snapshot,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
        )
    }
}
