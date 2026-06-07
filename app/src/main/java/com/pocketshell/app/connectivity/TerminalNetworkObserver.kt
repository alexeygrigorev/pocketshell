package com.pocketshell.app.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.pocketshell.app.diagnostics.DiagnosticEvents
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

    init {
        val manager = cm
        if (manager == null) {
            Log.w(TAG, "connectivity-manager-missing")
        } else {
            runCatching {
                manager.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
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
                    },
                )
            }.onFailure {
                Log.w(TAG, "register-default-network-callback-failed", it)
            }
        }
    }

    fun refresh(reason: String = "refresh"): TerminalNetworkChange? =
        updateFromSnapshot(currentSnapshot(), reason)

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
    )

internal fun TerminalNetworkSnapshot.Validated.hasSameNetworkIdentityAs(
    other: TerminalNetworkSnapshot.Validated,
): Boolean = networkHandle == other.networkHandle

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
