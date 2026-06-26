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
        Log.i(
            TAG,
            "terminal-network-change sequence=${change.sequence} reason=${change.reason} " +
                "previous=${change.previous.logValue} current=${change.current.logValue}",
        )
        DiagnosticEvents.record(
            "network",
            "validated_default_changed",
            *change.networkDiagnosticFields(),
        )
        ReconnectCauseTrail.record(
            stage = "network_callback",
            outcome = "validated_default_changed",
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
)

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
 * sets are identical AND that set is a *pure single-WIFI* network (no cellular /
 * VPN / ethernet on either side). That suppresses the band-steer/mesh reassoc
 * (the spurious case) while a real transport change still flips identity and
 * reconnects. We deliberately scope the relaxation to pure `{WIFI}`: a VPN or a
 * tethered/multi-transport network re-establishing can legitimately need the
 * fresh lease, so those keep the strict handle check.
 */
internal fun TerminalNetworkSnapshot.Validated.hasSameNetworkIdentityAs(
    other: TerminalNetworkSnapshot.Validated,
): Boolean =
    networkHandle == other.networkHandle ||
        (isPureWifi() && other.isPureWifi() && transports == other.transports)

private val PURE_WIFI_TRANSPORTS: Set<String> = setOf("WIFI")

private fun TerminalNetworkSnapshot.Validated.isPureWifi(): Boolean =
    transports == PURE_WIFI_TRANSPORTS

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

internal class TerminalNetworkChangeDetector(
    initial: TerminalNetworkSnapshot,
) {
    private var current: TerminalNetworkSnapshot = initial
    private var lastValidated: TerminalNetworkSnapshot.Validated? =
        initial as? TerminalNetworkSnapshot.Validated
    private var sequence: Long = 0L

    fun update(
        snapshot: TerminalNetworkSnapshot,
        reason: String,
    ): TerminalNetworkChange? {
        val previous = current
        val previousValidated = lastValidated
        if (previous.hasSameNetworkIdentityAs(snapshot)) {
            current = snapshot
            if (snapshot is TerminalNetworkSnapshot.Validated) lastValidated = snapshot
            return null
        }
        current = snapshot
        if (snapshot !is TerminalNetworkSnapshot.Validated) return null
        if (previousValidated == null) {
            lastValidated = snapshot
            return null
        }
        if (snapshot.hasSameNetworkIdentityAs(previousValidated)) {
            lastValidated = snapshot
            return null
        }
        lastValidated = snapshot
        sequence += 1L
        return TerminalNetworkChange(
            previous = previous,
            current = snapshot,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
        )
    }
}
