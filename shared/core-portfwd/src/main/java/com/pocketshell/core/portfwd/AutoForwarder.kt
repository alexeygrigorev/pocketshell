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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    // Wall clock for failed-port TTL bookkeeping. Injectable so unit tests
    // can drive time deterministically alongside the coroutine
    // `TestScope` virtual clock — `System.currentTimeMillis()` doesn't
    // advance with `advanceTimeBy`, so we'd otherwise have no test seam
    // for the TTL eviction logic.
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // We use a StateFlow so the UI can render the current set of tunnels
    // even if it subscribes after a scan has already run.
    private val tunnelsState = MutableStateFlow<List<TunnelInfo>>(emptyList())

    // Mutable state guarded by [mutex]. All reads + writes from the scan
    // loop, public togglePort, public stop go through withLock.
    private val mutex = Mutex()
    private val tunnels = mutableMapOf<Int, SshPortForward>()
    private val manualPorts = mutableSetOf<Int>()
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
        // Close tunnels synchronously — these don't suspend, so there's no
        // need to launch into a scope that may have just been cancelled.
        synchronized(this) {
            tunnels.values.forEach { runCatching { it.close() } }
            tunnels.clear()
            manualPorts.clear()
            processNames.clear()
            localPortMap.clear()
            failedPorts.clear()
            priorTotalBytes.clear()
        }
        tunnelsState.value = emptyList()
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
        mutex.withLock {
            if (remotePort in tunnels) {
                stopTunnelLocked(remotePort)
                manualPorts.remove(remotePort)
            } else {
                manualPorts.add(remotePort)
                forwardPortLocked(remotePort)
            }
            updateStateLocked()
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

        mutex.withLock {
            // Evict TTL'd deny-list entries up front so the rest of the
            // scan sees an accurate "is this port denied?" view.
            evictExpiredFailedPortsLocked()

            // Update process-name cache for everything we just saw.
            remotePorts.forEach { rp ->
                processNames[rp.port] = rp.processName
            }

            // Open new forwards for newly-discovered ports.
            remotePorts.forEach { rp ->
                if (rp.port !in tunnels && rp.port !in failedPorts) {
                    if (shouldForwardPort(rp.port)) {
                        forwardPortLocked(rp.port)
                    }
                }
            }

            // Tear down forwards whose remote port has vanished — but keep
            // ones the user manually toggled on; those persist across
            // disappearances (the user may have just restarted the service).
            tunnels.keys.toList().forEach { remotePort ->
                if (remotePort !in remotePortSet && remotePort !in manualPorts) {
                    stopTunnelLocked(remotePort)
                }
            }

            // Reset the failed-port memo for ports that have disappeared, so
            // a future "service restarted on the same port" attempt gets a
            // fresh try rather than being suppressed forever.
            failedPorts.keys.removeAll { it !in remotePortSet }

            updateStateLocked()
        }
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

    private fun forwardPortLocked(remotePort: Int) {
        if (remotePort in tunnels) return
        try {
            // resolveLocalPortLocked() throws when localPortRange is
            // exhausted — that's a forward-creation failure too, so it
            // belongs inside the same catch as the openLocalPortForward
            // call. Without this widening, an exhausted range would
            // crash the scan loop instead of being memoed in failedPorts.
            val localPort = resolveLocalPortLocked(remotePort)
            // Forward 127.0.0.1:<remotePort> on the remote's side — the
            // standard "service bound to localhost on the dev box" case.
            // Matches the legacy ssh-auto-forward-android behaviour.
            val forward = session.openLocalPortForward(
                remoteHost = "127.0.0.1",
                remotePort = remotePort,
                localPort = localPort,
            )
            tunnels[remotePort] = forward
            localPortMap[remotePort] = localPort
        } catch (_: Throwable) {
            // Forward couldn't be created (local port already in use,
            // remote refused the channel, allocator exhausted, ...).
            // Remember so we don't pummel the remote on every scan; the
            // next disappearance of this remote port will clear the
            // memo. We also TTL the entry
            // ([AutoForwardConfig.failedPortTtlMs]) so a transient
            // failure isn't sticky across re-scans on a still-listening
            // remote port.
            failedPorts[remotePort] = clock()
        }
    }

    private fun stopTunnelLocked(remotePort: Int) {
        val tunnel = tunnels.remove(remotePort) ?: return
        runCatching { tunnel.close() }
        localPortMap.remove(remotePort)
        priorTotalBytes.remove(remotePort)
    }

    private fun resolveLocalPortLocked(remotePort: Int): Int {
        // Persisted user-defined remapping wins over everything else
        // (issue #203 expanded scope, ported from
        // `ssh-auto-forward-android.AutoForwarder.resolveLocalPort`).
        // The user has explicitly said "I want remote N on local M" —
        // honour it whether or not N falls inside the auto-forward
        // window. Without this branch, a user who remapped port 22 to
        // local 2222 would never see the override take effect.
        portRemappings[remotePort]?.let { return it }
        // When the remote port is inside the auto-forward window we mirror
        // it locally — much friendlier UX (`localhost:3000` ↔ `remote:3000`)
        // than allocating a random port. Otherwise we hand out the next
        // free one in `localPortRange`.
        if (remotePort in config.skipPortsBelow..config.maxAutoPort) {
            return remotePort
        }
        return allocateLocalPortLocked()
    }

    private fun allocateLocalPortLocked(): Int {
        val used = localPortMap.values.toSet()
        // Wrap nextLocalPort back to the start of the range if it has
        // walked off the end.
        if (nextLocalPort !in config.localPortRange) {
            nextLocalPort = config.localPortRange.first
        }
        var candidate = nextLocalPort
        while (candidate in used) {
            candidate++
            if (candidate !in config.localPortRange) {
                candidate = config.localPortRange.first
            }
            if (candidate == nextLocalPort) {
                // Range fully exhausted — every slot is allocated. Fail
                // fast with a clear message rather than silently handing
                // out an already-used port (or, worse, looping forever).
                // The caller (forwardPortLocked) catches Throwable and
                // memos the remote port; this is the right shape.
                val low = config.localPortRange.first
                val high = config.localPortRange.last
                throw RuntimeException("no free local ports in range $low..$high")
            }
        }
        nextLocalPort = candidate + 1
        return candidate
    }

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
