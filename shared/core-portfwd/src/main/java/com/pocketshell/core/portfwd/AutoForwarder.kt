package com.pocketshell.core.portfwd

import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

public fun interface LocalPortAvailability {
    public fun isAvailable(port: Int): Boolean
}

public object DefaultLocalPortAvailability : LocalPortAvailability {
    override fun isAvailable(port: Int): Boolean {
        if (port !in 1..65_535) return false
        return runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
        }.isSuccess
    }
}

/**
 * Periodically scans a remote SSH host for listening TCP ports and opens
 * local port forwards for each one that matches the [AutoForwardConfig]
 * policy. Removes forwards when their remote port disappears.
 *
 * This is the headless engine — it doesn't know anything about Android
 * lifecycle, foreground services, or a database. UI / persistence layers
 * collect snapshots from [flowOfTunnels] and drive [start] / [stop] from
 * their own scope.
 *
 * Ported from `ssh-auto-forward-android/.../ssh/AutoForwarder.kt`. The JSch
 * Session + repository injection have been dropped:
 *
 * - SSH I/O goes through the supplied [SshSession] (which is whatever the
 *   `core-ssh` module returns from `SshConnection.connect`)
 * - Persistence (Room-backed remappings, byte usage stats) is no longer
 *   this class's concern — port-byte stats land on each [TunnelInfo] and the
 *   caller decides what to do with them
 *
 * Lifecycle:
 *
 * ```kotlin
 * val forwarder = AutoForwarder(session, AutoForwardConfig())
 * val job = forwarder.start(scope)            // begins scan loop
 * forwarder.flowOfTunnels().collect { ... }   // observe tunnels
 * forwarder.stop()                            // tear down all tunnels + cancel
 * ```
 *
 * The same [AutoForwarder] is single-use: once [stop] has been called, the
 * forwarder is terminal — create a new one if you want to restart.
 */
public class AutoForwarder(
    private val session: SshSession,
    private val config: AutoForwardConfig = AutoForwardConfig(),
    /**
     * Persisted remote -> local port remappings (issue #203 expanded
     * scope). Each entry overrides the natural "mirror remote port
     * onto same local port" allocation for that remote port. Defaults
     * to an empty map so existing callers continue to mirror.
     *
     * The map is captured at construction; runtime mutation should be
     * done by tearing down the forwarder and creating a new one with an
     * updated map (the persistence + reconnect logic lives in the
     * caller — keeping AutoForwarder DB-free preserves the
     * `core-portfwd` module's storage independence).
     *
     * Ported from `ssh-auto-forward-android`
     * `AutoForwarder.loadRemappings(hostId)` / `resolveLocalPort`,
     * where the same map is loaded from
     * `PortRemappingRepository.getByHostId(hostId).first()` before the
     * scan loop starts.
     */
    private val initialRemappings: Map<Int, Int> = emptyMap(),
    /**
     * Remote ports the user has explicitly opted into forwarding (issue
     * #439). Unlike the auto-discovered set, these are forwarded even
     * when they fall outside the [AutoForwardConfig] window and even when
     * the remote port is not currently listening — they represent the
     * user's *desired state*.
     *
     * The forwarder swaps once per SSH session (the supervisor builds a
     * fresh [AutoForwarder] after every reconnect). The user's manual
     * opt-ins are desired state that must survive that swap, so the
     * supervisor re-seeds this set on each reconnect. Captured at
     * construction into [manualPorts] so the first scan re-opens them
     * automatically.
     */
    private val initialManualPorts: Set<Int> = emptySet(),
    // Wall clock for failed-port TTL bookkeeping. Injectable so unit tests
    // can drive time deterministically alongside the coroutine
    // `TestScope` virtual clock — `System.currentTimeMillis()` doesn't
    // advance with `advanceTimeBy`, so we'd otherwise have no test seam
    // for the TTL eviction logic.
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val localPortAvailability: LocalPortAvailability = DefaultLocalPortAvailability,
) {

    // We use a StateFlow so the UI can render the current set of tunnels
    // even if it subscribes after a scan has already run.
    private val tunnelsState = MutableStateFlow<List<TunnelInfo>>(emptyList())

    // Mutable state guarded by [mutex]. All reads + writes from the scan
    // loop, public togglePort, public stop go through withLock.
    private val mutex = Mutex()
    private val tunnels = mutableMapOf<Int, SshPortForward>()
    // Seeded from [initialManualPorts] so the user's desired-state ports
    // (issue #439) are re-forwarded on a fresh forwarder after reconnect.
    private val manualPorts = initialManualPorts.toMutableSet()
    private val processNames = mutableMapOf<Int, String>()
    private val localPortMap = mutableMapOf<Int, Int>()
    // Remote port -> timestamp (from [clock]) when it landed on the
    // deny-list. We evict entries older than [AutoForwardConfig.failedPortTtlMs]
    // on each scan tick so transient failures don't stick around forever.
    private val failedPorts = mutableMapOf<Int, Long>()
    // Byte counters from the previous scan tick, keyed by remote port.
    // Used to derive instantaneous throughput in TunnelInfo.speedBps.
    private val priorTotalBytes = mutableMapOf<Int, Long>()
    // Persisted remote -> local port remappings, captured at
    // construction from [initialRemappings]. Held as a private mutable
    // map so future extensions (e.g. runtime `remapPort()` after a
    // tunnel reconnect) can update entries without re-creating the
    // forwarder. Today, only [resolveLocalPortLocked] reads from it.
    private val portRemappings: Map<Int, Int> = initialRemappings.toMap()
    private var nextLocalPort: Int = config.localPortRange.first

    @Volatile
    private var loopJob: Job? = null

    @Volatile
    private var stopped: Boolean = false

    /** Observable stream of the current tunnel snapshot list. */
    public fun flowOfTunnels(): Flow<List<TunnelInfo>> = tunnelsState.asStateFlow()

    /**
     * Launch the scan loop on [scope]. Idempotent — calling [start] twice
     * returns the same [Job]; calling after [stop] returns a completed Job
     * (the forwarder is single-use).
     */
    public fun start(scope: CoroutineScope): Job {
        if (stopped) {
            // Single-use: once stopped we won't restart.
            return Job().apply { complete() }
        }
        loopJob?.let { return it }
        val job = scope.launch { scanLoop() }
        loopJob = job
        return job
    }

    /**
     * Cancel the scan loop, tear down every open forward, and clear state.
     * Idempotent; safe to call from any thread.
     */
    public fun stop() {
        if (stopped) return
        stopped = true
        loopJob?.cancel()
        loopJob = null

        val tunnelsToClose = runBlocking {
            mutex.withLock {
                val captured = tunnels.values.toList()
                tunnels.clear()
                manualPorts.clear()
                processNames.clear()
                localPortMap.clear()
                failedPorts.clear()
                priorTotalBytes.clear()
                tunnelsState.value = emptyList()
                captured
            }
        }
        tunnelsToClose.forEach { runCatching { it.close() } }
    }

    /**
     * Manually opt a remote port into (or out of) being forwarded,
     * overriding the [AutoForwardConfig] policy. If the port is currently
     * forwarded, this toggles it off; if not, this forces it on.
     *
     * Suspends because closing / opening the underlying SSH channel may
     * block on I/O.
     */
    public suspend fun togglePort(remotePort: Int) {
        val tunnelToClose = mutex.withLock {
            if (stopped) return
            if (remotePort in tunnels) {
                manualPorts.remove(remotePort)
                val tunnel = detachTunnelLocked(remotePort)
                updateStateLocked()
                tunnel
            } else {
                manualPorts.add(remotePort)
                null
            }
        }
        tunnelToClose?.let {
            runCatching { it.close() }
            return
        }
        forwardPort(remotePort)
    }

    /**
     * Force a remote port's manual-forward state to [enabled] (issue
     * #439). Unlike [togglePort] this is idempotent and absolute, so the
     * supervisor can drive the forwarder from its authoritative
     * desired-state set without first reading the live tunnel map. Used
     * when the user opts a port in/out so the desired state and the live
     * forwarder stay consistent across reconnects.
     */
    public suspend fun ensurePort(remotePort: Int, enabled: Boolean) {
        val tunnelToClose = mutex.withLock {
            if (stopped) return
            if (enabled) {
                manualPorts.add(remotePort)
                null
            } else {
                manualPorts.remove(remotePort)
                val tunnel = detachTunnelLocked(remotePort)
                localPortMap.remove(remotePort)
                updateStateLocked()
                tunnel
            }
        }
        tunnelToClose?.let { runCatching { it.close() } }
        if (enabled) {
            forwardPort(remotePort)
        }
    }

    private suspend fun scanLoop() {
        while (loopScopeActive()) {
            try {
                scanAndForward()
            } catch (_: Throwable) {
                // Don't crash the loop on a single bad scan — log via the
                // caller's event stream eventually, but for now retry on
                // the next tick.
            }
            delay(config.scanIntervalSec * 1000L)
        }
    }

    private fun loopScopeActive(): Boolean {
        return !stopped && loopJob?.isActive != false
    }

    private suspend fun scanAndForward() {
        val remotePorts = PortScanner.scan(session)
        val remotePortSet = remotePorts.map { it.port }.toSet()

        val tunnelsToClose = mutableListOf<SshPortForward>()
        val portsToOpen = mutex.withLock {
            if (stopped) return
            // Evict TTL'd deny-list entries up front so the rest of the
            // scan sees an accurate "is this port denied?" view.
            evictExpiredFailedPortsLocked()

            // Update process-name cache for everything we just saw.
            remotePorts.forEach { rp ->
                processNames[rp.port] = rp.processName
            }

            val discoveredPortsToOpen = remotePorts.mapNotNull { rp ->
                val remotePort = rp.port
                if (
                    remotePort !in tunnels &&
                    remotePort !in localPortMap &&
                    remotePort !in failedPorts &&
                    shouldForwardPort(remotePort)
                ) {
                    remotePort
                } else {
                    null
                }
            }

            // Re-open user-desired manual ports (issue #439) that aren't
            // up yet. After a reconnect the fresh forwarder is seeded with
            // the desired-state set but a manual port may not be in the
            // current `ss` scan (it can be below `skipPortsBelow`, above
            // `maxAutoPort`, or briefly not listening). Without this the
            // forward would only come back if the port happened to fall in
            // the auto window AND was listening — i.e. the reconnect bug.
            val manualPortsToOpen = manualPorts.mapNotNull { remotePort ->
                if (
                    remotePort !in tunnels &&
                    remotePort !in localPortMap &&
                    remotePort !in failedPorts
                ) {
                    remotePort
                } else {
                    null
                }
            }

            // Tear down forwards whose remote port has vanished — but keep
            // ones the user manually toggled on; those persist across
            // disappearances (the user may have just restarted the service).
            tunnels.keys.toList().forEach { remotePort ->
                if (remotePort !in remotePortSet && remotePort !in manualPorts) {
                    detachTunnelLocked(remotePort)?.let(tunnelsToClose::add)
                }
            }
            localPortMap.keys.toList().forEach { remotePort ->
                if (
                    remotePort !in tunnels &&
                    remotePort !in remotePortSet &&
                    remotePort !in manualPorts
                ) {
                    localPortMap.remove(remotePort)
                }
            }

            // Reset the failed-port memo for ports that have disappeared, so
            // a future "service restarted on the same port" attempt gets a
            // fresh try rather than being suppressed forever.
            failedPorts.keys.removeAll { it !in remotePortSet }

            updateStateLocked()
            (discoveredPortsToOpen + manualPortsToOpen).distinct()
        }

        tunnelsToClose.forEach { runCatching { it.close() } }
        portsToOpen.forEach { forwardPort(it) }
    }

    private suspend fun forwardPort(remotePort: Int) {
        val localPort = mutex.withLock {
            if (stopped || remotePort in tunnels || remotePort in localPortMap) return
            try {
                // resolveLocalPortLocked() throws when localPortRange is
                // exhausted — that's a forward-creation failure too.
                resolveLocalPortLocked(remotePort).also { resolvedPort ->
                    // Reserve the local port while the SSH open happens
                    // outside the mutex. This prevents a concurrent scan or
                    // manual toggle from allocating the same local port.
                    localPortMap[remotePort] = resolvedPort
                }
            } catch (_: Throwable) {
                failedPorts[remotePort] = clock()
                updateStateLocked()
                return
            }
        }

        val forward = try {
            // Forward 127.0.0.1:<remotePort> on the remote's side — the
            // standard "service bound to localhost on the dev box" case.
            // Matches the legacy ssh-auto-forward-android behaviour.
            session.openLocalPortForward(
                remoteHost = "127.0.0.1",
                remotePort = remotePort,
                localPort = localPort,
            )
        } catch (_: Throwable) {
            mutex.withLock {
                if (localPortMap[remotePort] == localPort) {
                    localPortMap.remove(remotePort)
                    failedPorts[remotePort] = clock()
                    updateStateLocked()
                }
            }
            return
        }

        var closeForward = false
        mutex.withLock {
            if (stopped || localPortMap[remotePort] != localPort || remotePort in tunnels) {
                localPortMap.remove(remotePort)
                closeForward = true
            } else {
                tunnels[remotePort] = forward
            }
            updateStateLocked()
        }
        if (closeForward) {
            runCatching { forward.close() }
        }
    }

    private fun detachTunnelLocked(remotePort: Int): SshPortForward? {
        val tunnel = tunnels.remove(remotePort) ?: return null
        localPortMap.remove(remotePort)
        priorTotalBytes.remove(remotePort)
        return tunnel
    }

    /**
     * Drop any deny-list entries older than [AutoForwardConfig.failedPortTtlMs].
     * Called from inside the scan loop while holding [mutex].
     */
    private fun evictExpiredFailedPortsLocked() {
        if (failedPorts.isEmpty()) return
        val now = clock()
        val ttl = config.failedPortTtlMs
        val expired = failedPorts.entries.filter { now - it.value > ttl }.map { it.key }
        expired.forEach { failedPorts.remove(it) }
    }

    private fun shouldForwardPort(remotePort: Int): Boolean {
        if (remotePort in manualPorts) return true
        return remotePort in config.skipPortsBelow..config.maxAutoPort
    }

    private fun resolveLocalPortLocked(remotePort: Int): Int {
        // Persisted user-defined remapping wins over everything else
        // (issue #203 expanded scope, ported from
        // `ssh-auto-forward-android.AutoForwarder.resolveLocalPort`).
        // The user has explicitly said "I want remote N on local M" —
        // honour it whether or not N falls inside the auto-forward
        // window. Without this branch, a user who remapped port 22 to
        // local 2222 would never see the override take effect.
        portRemappings[remotePort]?.let { return allocateLocalPortStartingAtLocked(it) }
        // When the remote port is inside the auto-forward window we mirror
        // it locally when possible — much friendlier UX
        // (`localhost:3000` ↔ `remote:3000`) than allocating a random port.
        // If that phone-side port is already occupied, walk upward to the next
        // available local port and surface that actual value in TunnelInfo.
        // Otherwise we hand out the next free one in `localPortRange`.
        if (remotePort in config.skipPortsBelow..config.maxAutoPort) {
            return allocateLocalPortStartingAtLocked(remotePort)
        }
        return allocateLocalPortLocked()
    }

    private fun allocateLocalPortStartingAtLocked(requestedPort: Int): Int {
        if (requestedPort !in 1..65_535) {
            throw RuntimeException("invalid local port $requestedPort")
        }
        var candidate = requestedPort
        while (candidate <= 65_535) {
            if (isLocalPortUsableLocked(candidate)) return candidate
            candidate++
        }
        throw RuntimeException("no free local ports at or above $requestedPort")
    }

    private fun allocateLocalPortLocked(): Int {
        // Wrap nextLocalPort back to the start of the range if it has
        // walked off the end.
        if (nextLocalPort !in config.localPortRange) {
            nextLocalPort = config.localPortRange.first
        }
        var candidate = nextLocalPort
        while (!isLocalPortUsableLocked(candidate)) {
            candidate++
            if (candidate !in config.localPortRange) {
                candidate = config.localPortRange.first
            }
            if (candidate == nextLocalPort) {
                // Range fully exhausted — every slot is allocated. Fail
                // fast with a clear message rather than silently handing
                // out an already-used port (or, worse, looping forever).
                // The caller (forwardPort) catches Throwable and memos
                // the remote port; this is the right shape.
                val low = config.localPortRange.first
                val high = config.localPortRange.last
                throw RuntimeException("no free local ports in range $low..$high")
            }
        }
        nextLocalPort = candidate + 1
        return candidate
    }

    private fun isLocalPortUsableLocked(port: Int): Boolean =
        port !in localPortMap.values && localPortAvailability.isAvailable(port)

    private fun updateStateLocked() {
        val intervalSec = config.scanIntervalSec.coerceAtLeast(1).toLong()
        val snapshot = mutableListOf<TunnelInfo>()
        // Surface failed ports too — a manually-toggled port that
        // couldn't be forwarded (e.g. range exhausted) still belongs in
        // the snapshot with status FAILED so the UI can show the user
        // why nothing happened. Without this, exhaustion is invisible.
        val knownPorts = (tunnels.keys + processNames.keys + failedPorts.keys)
            .distinct()
            .sorted()
        for (port in knownPorts) {
            val tunnel = tunnels[port]
            val status = when {
                tunnel != null -> TunnelInfo.Status.FORWARDING
                port in failedPorts -> TunnelInfo.Status.FAILED
                else -> TunnelInfo.Status.AVAILABLE
            }
            val bytesIn = tunnel?.bytesForwarded ?: 0L
            val bytesOut = tunnel?.bytesReceived ?: 0L
            val total = bytesIn + bytesOut
            val prior = priorTotalBytes[port] ?: total
            val speedBps = ((total - prior).coerceAtLeast(0L)) / intervalSec
            priorTotalBytes[port] = total
            snapshot.add(
                TunnelInfo(
                    remotePort = port,
                    localPort = tunnel?.localPort ?: localPortMap[port] ?: port,
                    process = processNames[port] ?: "",
                    status = status,
                    bytesIn = bytesIn,
                    bytesOut = bytesOut,
                    speedBps = speedBps,
                ),
            )
        }
        tunnelsState.value = snapshot
    }
}
