package com.pocketshell.core.portfwd

import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.TransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long-lived supervisor that pairs a single [AutoForwarder] (per-connection
 * scan-and-forward engine) with the outer connection lifecycle:
 *
 *  - Calls the supplied [connectionFactory] to acquire a fresh [HostConnection]
 *  - Drives an [AutoForwarder] over that connection for as long as it stays
 *    connected
 *  - On disconnect or factory failure, applies exponential backoff and
 *    reconnects, up to [maxReconnectAttempts] (after which the supervisor
 *    surfaces `ConnectionLost`)
 *  - On a [PermanentConnectionFailure] from the factory, stops retrying
 *    immediately and surfaces `ConnectionLost` — a dial that can only succeed
 *    after the user acts elsewhere must not be repeated on a timer forever
 *    (issue #2491)
 *  - On a network-recovery hint via [reconnectNow], cancels the current
 *    backoff and reconnects immediately
 *
 * This is the piece that lets the foreground service (issue #203 expanded
 * scope, ported from `ssh-auto-forward-android`'s `AutoForwarder.kt`
 * reconnect loop) keep tunnels alive across transient network drops while
 * the app process is held alive. The supervisor is DB-free for the same
 * reason [AutoForwarder] is — persistence is the caller's concern.
 *
 * Design rationale — why a supervisor on top of [AutoForwarder] rather
 * than folding the reconnect loop back into [AutoForwarder] itself:
 *
 *  - [AutoForwarder] today takes a single [HostConnection] and is fully
 *    covered by 24+ unit tests that pass that connection in directly. Adding
 *    a reconnect loop inside it would force every existing test to grow a
 *    factory callback even when reconnect is irrelevant.
 *  - The supervisor lets us layer reconnect tests independently of
 *    forwarder tests (factories that fail N times, connections that drop
 *    mid-scan, ...).
 *  - The foreground service (`ForwardingService.kt`) owns one supervisor
 *    per active host. The supervisor presents a stable `flowOfTunnels()` /
 *    `flowOfEvents()` surface even across connection swaps, so the service's
 *    notification can keep rendering through a reconnect window without
 *    needing to re-subscribe.
 *
 * Lifecycle contract: [start], [stop], and [reconnectNow] may be called
 * concurrently from any thread. [start] is idempotent and publishes exactly
 * one loop job; [stop] is terminal and linearizes with job/connection
 * publication, so a late connect result is closed rather than mounted.
 * [reconnectNow] only publishes a wake/force intent while the supervisor is
 * live. [lifecycleLock] protects those lifecycle fields and all state
 * publication; blocking connection/forwarder teardown happens after releasing
 * the lock.
 */
public class AutoForwarderSupervisor(
    /**
     * Produces a fresh [HostConnection] each time the supervisor needs to
     * connect (initial connect + after every drop). The factory is
     * expected to throw on failure; the supervisor catches the throwable
     * and applies backoff before retrying.
     *
     * Modelled as a suspending lambda so callers (e.g. the foreground
     * service) can run the real `SshConnection.connect(...)` flow inside
     * it, including DAO lookups for the host / key.
     */
    private val connectionFactory: suspend () -> HostConnection,
    private val config: AutoForwardConfig = AutoForwardConfig(),
    private val initialRemappings: Map<Int, Int> = emptyMap(),
    /**
     * Initial delay between reconnect attempts in milliseconds. Doubles
     * each attempt up to [maxReconnectDelayMs]. Matches the upstream
     * `ssh-auto-forward-android.AutoForwarder.INITIAL_RECONNECT_DELAY`.
     */
    private val initialReconnectDelayMs: Long = 5_000L,
    /**
     * Cap on the backoff delay. Matches upstream
     * `MAX_RECONNECT_DELAY = 60_000L`. Even after long outages the
     * supervisor still pokes the server every minute so reconnect happens
     * within a human-tolerable window once the network is back.
     */
    private val maxReconnectDelayMs: Long = 60_000L,
    /**
     * Bound on consecutive reconnect failures before the supervisor
     * surfaces [Event.ConnectionLost] and stops trying. The UI / service
     * can choose to call [reconnectNow] to reset the counter and try
     * again, e.g. on user action or a network-availability callback.
     *
     * `null` means "keep retrying a TRANSIENT failure forever" (matches the
     * upstream JSch client's default behaviour), which is what the
     * foreground-service caller wants: a phone that loses its network in a
     * tunnel has to self-heal without anyone tapping anything, and no cap
     * survives a ten-minute outage. The permanently-misconfigured host that a
     * finite cap used to be the only answer for is handled directly instead —
     * the factory throws [PermanentConnectionFailure] and the supervisor goes
     * terminal on the first one (issue #2491) rather than waiting out N
     * pointless handshakes.
     */
    private val maxReconnectAttempts: Int? = null,
    /**
     * Dispatcher the terminal [stop] teardown closes the live connection
     * on. [stop] is reached on the Android Main thread (the port-forward
     * panel's auto-forward toggle-off and the foreground service's
     * `ACTION_STOP` both call it synchronously via
     * `ForwardingController.stopForwarding`/`stopAllForwarding` →
     * `ActiveHost.stopOwnedSupervisor`), and `HostConnection.close()` drives an
     * `SSH_MSG_DISCONNECT` socket write that blocks the caller until the
     * disconnect finishes — on a wedged socket that froze the UI (the #1085
     * freeze-hunt F-E finding). Defaults to [Dispatchers.IO] so the connection
     * close runs off the caller's thread; injectable so tests can pin it.
     */
    private val teardownDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Connection-level events emitted to the caller (service / UI). */
    public sealed class Event {
        public data class Connected(val attempts: Int) : Event()
        public data class Disconnected(val reason: String) : Event()
        public data class Error(val message: String) : Event()
        /**
         * Surfaced after [maxReconnectAttempts] consecutive failures, or
         * immediately when the factory reports a [PermanentConnectionFailure].
         * The supervisor stops trying until the caller invokes [reconnectNow]
         * or [stop] + restart with a new supervisor. [lastError] is the reason
         * to show the user — it is the only place the "why" survives, so a UI
         * can distinguish "needs attention" from "reconnecting".
         */
        public data class ConnectionLost(val lastError: String) : Event()
    }

    /** Long-lived tunnel snapshot stream — bridges per-forwarder swaps. */
    private val tunnelsState = MutableStateFlow<List<TunnelInfo>>(emptyList())

    /** Connection state — UI can render "Reconnecting…" between dials. */
    private val connectionState = MutableStateFlow(ConnectionState.Idle)

    /** Event stream for one-shot notifications (connected, disconnected, ...). */
    private val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 16)

    /**
     * Wakeup channel for the backoff sleep — [reconnectNow] completes
     * the deferred so the supervisor retries immediately on a network
     * hint. A fresh CompletableDeferred is created for each sleep so
     * subsequent reconnectNow() calls don't carry over.
     */
    @Volatile
    private var reconnectWaiter: CompletableDeferred<Unit>? = null

    @Volatile
    private var reconnectImmediately: Boolean = false

    /**
     * Completed by [reconnectNow] to unpark the mounted-forwarder wait without
     * the transport itself having dropped — the "the OS says the network came
     * back, this connection is stale even though sshj still calls it live" case.
     * Installed while a forwarder is mounted, cleared when it unmounts, so a
     * later [reconnectNow] can never complete a stale waiter.
     */
    @Volatile
    private var forcedDropWaiter: CompletableDeferred<Unit>? = null

    /** Serializes lifecycle check-then-act transitions and resource ownership. */
    private val lifecycleLock = Any()

    /**
     * User-desired manual port forwards (issue #439), independent of the
     * live transport. The per-connection [AutoForwarder] is rebuilt on every
     * reconnect, so the user's explicit opt-ins (which may be outside the
     * auto-forward window or not currently listening) would otherwise be
     * lost on a drop+reconnect. This supervisor-level set is the
     * authoritative desired state: it is seeded into every freshly-built
     * forwarder so active forwards auto-restore when SSH comes back.
     *
     * Guarded by [desiredLock]. A [java.util.Collections.synchronizedSet]
     * isn't enough because [togglePort] does a read-modify-write
     * (contains → add/remove) that must be atomic against the loop's
     * snapshot read in [runConnectAndReconnectLoop].
     */
    private val desiredManualPorts: MutableSet<Int> = mutableSetOf()
    private val desiredLock = Any()

    @Volatile
    private var supervisorJob: Job? = null

    @Volatile
    private var currentForwarder: AutoForwarder? = null

    @Volatile
    private var currentConnection: HostConnection? = null

    @Volatile
    private var stopped: Boolean = false

    public fun flowOfTunnels(): Flow<List<TunnelInfo>> = tunnelsState.asStateFlow()

    public fun flowOfConnectionState(): StateFlow<ConnectionState> = connectionState.asStateFlow()

    public fun flowOfEvents(): SharedFlow<Event> = eventsFlow.asSharedFlow()

    public enum class ConnectionState {
        Idle,
        Connecting,
        Connected,
        Reconnecting,
        Lost,
    }

    /**
     * Start the supervisor loop on [scope]. Idempotent. The returned
     * [Job] completes only when the supervisor is stopped (or the scope
     * is cancelled).
     *
     * Internally the supervisor launches `runConnectAndReconnectLoop`
     * inside a [coroutineScope] block so all child jobs (the per-connection
     * forwarder loop, the tunnel-snapshot bridge) are direct children of
     * the supervisor's own job. Cancelling that job — or calling
     * [stop] — tears the whole tree down deterministically, which the
     * unit-test [runTest] scope requires (otherwise child coroutines
     * outlive the test and trip `UncompletedCoroutinesError`).
     */
    public fun start(scope: CoroutineScope): Job = synchronized(lifecycleLock) {
        if (stopped) return@synchronized completedJob()
        supervisorJob?.let { return@synchronized it }
        val job = scope.launch {
            // Run inside a `coroutineScope { ... }` so the child
            // launches inside `runForwarderUntilConnectionDrops` (the
            // bridge collector + the AutoForwarder.start job) attach
            // to *this* coroutine's job. When the outer supervisor job
            // is cancelled or the scope ends, `coroutineScope` waits
            // for those children to complete before unwinding — which
            // is what `runTest` requires to satisfy
            // `UncompletedCoroutinesError`.
            kotlinx.coroutines.coroutineScope {
                runConnectAndReconnectLoop()
            }
        }
        // The loop's finally block normally owns the active resources when
        // cancellation completes. Register a last-resort claim as well so a
        // scope cancellation or an unusual dispatcher ordering cannot leak a
        // resource that survived that finally block. Job completion runs only
        // after the loop's finally blocks have run, so this cannot steal the
        // normal synchronous close from the cancelled loop.
        job.invokeOnCompletion { closeRemainingResourcesOffThread() }
        // stop() may be called re-entrantly by a dispatcher while launch() is
        // publishing the coroutine but before it returns. A cancelled
        // supervisor must never become the live, restartable job afterward.
        if (stopped) {
            job.cancel()
        } else {
            supervisorJob = job
        }
        job
    }

    /**
     * Cancel the supervisor loop, tear down any active forwarder, and
     * close the live connection. Idempotent.
     */
    public fun stop() {
        var waiterToWake: CompletableDeferred<Unit>? = null
        var jobToCancel: Job? = null
        synchronized(lifecycleLock) {
            if (stopped) return
            stopped = true
            waiterToWake = reconnectWaiter.also { reconnectWaiter = null }
            jobToCancel = supervisorJob.also { supervisorJob = null }
            reconnectImmediately = false
            connectionState.value = ConnectionState.Idle
            tunnelsState.value = emptyList()
        }
        // Complete/cancel outside the lifecycle lock: either operation can
        // resume code that calls back into this object.
        waiterToWake?.complete(Unit)
        jobToCancel?.cancel()
        // Snapshot, but do not clear, the resources for an immediate fallback.
        // The cancelled loop's finally block remains allowed to claim and
        // close them synchronously; retaining publication is what preserves
        // the panel's fast-close contract. The fallback is needed when a
        // dispatcher has not resumed that finally block yet (for example, a
        // caller may cancel a live supervisor while its test dispatcher is
        // parked), and it runs off the caller's thread.
        val resourcesToStop = synchronized(lifecycleLock) {
            currentForwarder to currentConnection
        }
        resourcesToStop.first?.stop()
        resourcesToStop.second?.let(::closeConnectionOffThread)
    }

    /**
     * Hint that the network changed — cancel the current backoff and
     * retry immediately. Resets the reconnect-attempt counter so a long
     * sleep doesn't combine with a fresh network event to skip the next
     * attempt window. No-op if the supervisor isn't currently in
     * backoff.
     */
    public fun reconnectNow(force: Boolean = false) {
        var waiterToWake: CompletableDeferred<Unit>? = null
        var dropToSignal: CompletableDeferred<Unit>? = null
        synchronized(lifecycleLock) {
            if (stopped || supervisorJob == null) return
            // Wake any pending backoff sleep. Completing the deferred is a
            // no-op if no sleep is in flight, so this is safe in every live
            // state. Clearing under the same lock prevents stop/reconnect
            // from racing over ownership of the waiter.
            waiterToWake = reconnectWaiter.also { reconnectWaiter = null }
            if (force || connectionState.value != ConnectionState.Connected) {
                // This intent also covers the tiny transition before the
                // waiter is installed: the loop consumes it instead of
                // entering a missed backoff sleep.
                reconnectImmediately = true
                dropToSignal = forcedDropWaiter
            }
        }
        waiterToWake?.complete(Unit)
        // A real default-network loss/recovery can leave sshj's client reporting
        // "connected" even though the phone-side forwards are dead. A
        // force/non-connected reconnect therefore has to tear the transport down
        // so the next connection restores desired forwards promptly (an ordinary
        // availability callback must NOT churn a healthy tunnel — hence the
        // `force ||` guard above).
        //
        // We do NOT close the connection here. Completing this signal unparks the
        // mounted-forwarder wait, and the loop's own `finally` closes the
        // connection on the loop's dispatcher — one owner for the teardown, and
        // no blocking socket work on the Main thread this is called from.
        dropToSignal?.complete(Unit)
    }

    /**
     * Forward a manual port toggle to the currently-running forwarder and
     * record the user's intent in the supervisor-level desired-state set
     * (issue #439). Recording happens even when no forwarder is mounted
     * (between connect attempts / during backoff) so a port the user
     * enabled is restored on the next reconnect rather than dropped.
     */
    public suspend fun togglePort(remotePort: Int) {
        val nowDesired = synchronized(desiredLock) {
            if (desiredManualPorts.remove(remotePort)) {
                false
            } else {
                desiredManualPorts.add(remotePort)
                true
            }
        }
        // Drive the live forwarder from the resolved desired state so the
        // toggle and the desired-state set never diverge (which would let
        // a reconnect re-open a port the user just turned off, or skip one
        // they just turned on). No-op when no forwarder is mounted; the
        // desired-state record above still survives to the next reconnect.
        val forwarder = synchronized(lifecycleLock) {
            currentForwarder.takeUnless { stopped }
        }
        forwarder?.ensurePort(remotePort, nowDesired)
    }

    /**
     * The set of remote ports the user has explicitly opted into
     * forwarding — the authoritative desired state that survives
     * transport drops (issue #439). Exposed for tests so they can assert
     * the desired-state set independently of the live forwarder.
     */
    internal fun desiredManualPortsSnapshot(): Set<Int> = synchronized(desiredLock) {
        desiredManualPorts.toSet()
    }

    private suspend fun runConnectAndReconnectLoop() {
        var reconnectDelay = initialReconnectDelayMs
        var attemptCount = 0
        var consecutiveFailures = 0

        while (!stopped) {
            // Reset per iteration: only the failure this iteration produced may
            // send the loop terminal, never a since-recovered older one.
            var permanentError: String? = null
            try {
                val nextState =
                    if (attemptCount == 0) ConnectionState.Connecting else ConnectionState.Reconnecting
                if (!setConnectionStateIfRunning(nextState)) break
                if (attemptCount > 0) {
                    markTunnelsStopped()
                }

                val connection = connectionFactory()
                attemptCount += 1
                // Snapshot the desired-state set so the fresh forwarder
                // re-opens every user-enabled manual port (issue #439).
                // Without this the manual opt-ins live only inside the
                // previous AutoForwarder instance, which is discarded on
                // reconnect, so the user's forwards would silently vanish.
                val manualPortsSnapshot = synchronized(desiredLock) {
                    desiredManualPorts.toSet()
                }
                val forwarder = AutoForwarder(
                    connection = connection,
                    config = config,
                    initialRemappings = initialRemappings,
                    initialManualPorts = manualPortsSnapshot,
                )
                // The factory may cross a terminal stop while it is in a
                // non-cooperative socket operation. Publish the pair only if
                // this loop is still live; otherwise close the late result
                // locally and never let it resurrect the supervisor.
                val stillActive = currentCoroutineContext().isActive
                val published = synchronized(lifecycleLock) {
                    if (!stillActive || stopped) {
                        false
                    } else {
                        currentConnection = connection
                        currentForwarder = forwarder
                        true
                    }
                }
                if (!published) {
                    forwarder.stop()
                    runCatching { connection.close() }
                    break
                }
                // Reset bookkeeping for the new connection — backoff
                // restarts at the initial delay and consecutive-failure
                // count resets so [maxReconnectAttempts] doesn't trip
                // off historical, since-recovered failures.
                reconnectDelay = initialReconnectDelayMs
                consecutiveFailures = 0
                // Do not let the previous connection's FORWARDING snapshot
                // satisfy restore/readiness observers while this fresh
                // forwarder is still doing its first scan.
                markTunnelsStopped()
                if (!setConnectionStateIfRunning(ConnectionState.Connected)) break
                emitEventIfRunning(Event.Connected(attemptCount))

                // Drive the forwarder loop on the supervisor scope and
                // mirror its tunnel snapshots out through our long-lived
                // tunnelsState.
                runForwarderUntilConnectionDrops()

                // We get here when the forwarder loop exited cleanly
                // (e.g. the connection dropped or stop() was called). Mark all
                // tunnels STOPPED on the way out so the UI can show
                // "reconnecting…" without the tunnels disappearing
                // entirely.
                if (!stopped) {
                    markTunnelsStopped()
                    emitEventIfRunning(Event.Disconnected("connection lost"))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (!stopped) {
                    consecutiveFailures += 1
                    val msg = t.message ?: t.javaClass.simpleName
                    // A dial that cannot succeed until the user acts elsewhere
                    // goes terminal now instead of burning a handshake every
                    // backoff window forever (issue #2491).
                    if (t is PermanentConnectionFailure) permanentError = msg
                    emitEventIfRunning(Event.Error(msg))
                    markTunnelsStopped()
                }
            } finally {
                // Always release the per-connection forwarder + connection
                // before sleeping into the next reconnect attempt.
                closeRemainingResources()
            }

            if (stopped) break

            if (takeImmediateReconnect()) {
                reconnectDelay = initialReconnectDelayMs
                continue
            }

            val attemptsExhausted =
                maxReconnectAttempts != null && consecutiveFailures >= maxReconnectAttempts
            if (permanentError != null || attemptsExhausted) {
                if (!setConnectionStateIfRunning(ConnectionState.Lost)) break
                emitEventIfRunning(
                    Event.ConnectionLost(permanentError ?: "max reconnect attempts reached"),
                )
                // Park until reconnectNow() (which completes the waiter)
                // or stop() (which cancels the supervisor job entirely).
                val waiter = CompletableDeferred<Unit>()
                if (!installReconnectWaiterOrConsumeImmediate(waiter)) {
                    if (stopped) break
                    consecutiveFailures = 0
                    reconnectDelay = initialReconnectDelayMs
                    continue
                }
                try {
                    waiter.await()
                } finally {
                    clearReconnectWaiter(waiter)
                }
                if (stopped) break
                clearImmediateReconnect()
                consecutiveFailures = 0
                reconnectDelay = initialReconnectDelayMs
                continue
            }

            if (!setConnectionStateIfRunning(ConnectionState.Reconnecting)) break
            // Sleep `reconnectDelay`. reconnectNow() / stop() complete the
            // waiter to wake us up early. Wrapped in withTimeoutOrNull so
            // the sleep terminates on either the deadline or the wake.
            val waiter = CompletableDeferred<Unit>()
            if (!installReconnectWaiterOrConsumeImmediate(waiter)) {
                if (stopped) break
                reconnectDelay = initialReconnectDelayMs
                continue
            }
            try {
                withTimeoutOrNull(reconnectDelay) {
                    waiter.await()
                }
            } finally {
                clearReconnectWaiter(waiter)
            }

            if (takeImmediateReconnect()) {
                reconnectDelay = initialReconnectDelayMs
            } else {
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(maxReconnectDelayMs)
            }
        }
    }

    private suspend fun runForwarderUntilConnectionDrops() {
        val resources = synchronized(lifecycleLock) {
            if (stopped) null else currentForwarder to currentConnection
        } ?: return
        val forwarder = resources.first ?: return
        val connection = resources.second ?: return
        // Run the forwarder + tunnel-bridge inside a nested coroutineScope
        // so we deterministically join both when the connection drops, the
        // supervisor stops, or the outer scope is cancelled. The nested
        // scope is a child of `parent` (which is itself the supervisor
        // coroutine's `coroutineScope` body), so any cancellation
        // propagates correctly and `runTest` sees a clean shutdown.
        // Unparked either by the transport settling into a terminal state (a real
        // drop) or by a forced [reconnectNow]. Published before the children
        // start so a reconnectNow racing the mount cannot miss it.
        val drop = CompletableDeferred<Unit>()
        val mounted = synchronized(lifecycleLock) {
            if (stopped) {
                false
            } else {
                forcedDropWaiter = drop
                true
            }
        }
        if (!mounted) return
        try {
            kotlinx.coroutines.coroutineScope {
                val bridgeJob = launch {
                    forwarder.flowOfTunnels().collect { publishTunnelsIfRunning(it) }
                }
                val forwarderJob = forwarder.start(this)
                // Wait for the drop. The old core-ssh session exposed only a
                // boolean `isConnected`, so this had to be a 1 s poll;
                // core-transport publishes a [TransportState] StateFlow, so the
                // supervisor simply awaits the terminal state. That removes both
                // the polling-cadence knob and the up-to-one-interval lag between
                // a real drop and the reconnect starting.
                val stateWatch = launch {
                    connection.state.first { it.isTerminal() }
                    drop.complete(Unit)
                }

                try {
                    drop.await()
                } finally {
                    stateWatch.cancel()
                    // Cancel the children so the nested coroutineScope can
                    // exit promptly on the drop. On outer cancellation
                    // both jobs are already cancelled by structured
                    // concurrency, but cancelling explicitly here lets the
                    // dropped-connection path exit without waiting for the next
                    // upstream cancellation event.
                    forwarderJob.cancel()
                    bridgeJob.cancel()
                }
            }
        } finally {
            // Unmounted: a later reconnectNow must not complete this waiter.
            synchronized(lifecycleLock) {
                if (forcedDropWaiter === drop) forcedDropWaiter = null
            }
        }
    }

    private fun completedJob(): Job = Job().apply { complete() }

    /**
     * Claims the resources a completed/cancelled loop left behind, clearing the
     * fields under the lifecycle lock so exactly ONE caller owns the teardown.
     */
    private fun claimResources(): Pair<AutoForwarder?, HostConnection?> =
        synchronized(lifecycleLock) {
            val forwarder = currentForwarder.also { currentForwarder = null }
            val connection = currentConnection.also { currentConnection = null }
            forwarder to connection
        }

    /**
     * Suspending teardown for the reconnect loop's `finally`: the loop must not
     * start the next dial until the previous connection is actually closed,
     * otherwise a flapping host accumulates transports.
     */
    private suspend fun closeRemainingResources() {
        val (forwarder, connection) = claimResources()
        // AutoForwarder.stop() already closes its forwards off the caller's
        // thread (it offloads to its own teardownDispatcher), so this call
        // returns promptly.
        forwarder?.stop()
        connection ?: return
        runCatching {
            withTimeoutOrNull(CONNECTION_CLOSE_TIMEOUT_MS) { connection.close() }
        }
    }

    /**
     * Non-suspending last-resort teardown, used from [Job.invokeOnCompletion]
     * where there is no coroutine to suspend in.
     */
    private fun closeRemainingResourcesOffThread() {
        val (forwarder, connection) = claimResources()
        forwarder?.stop()
        connection?.let(::closeConnectionOffThread)
    }

    /** Closes a connection without allowing a wedged socket to block its caller. */
    private fun closeConnectionOffThread(connection: HostConnection) {
        CoroutineScope(SupervisorJob() + teardownDispatcher).launch {
            runCatching {
                withTimeoutOrNull(CONNECTION_CLOSE_TIMEOUT_MS) { connection.close() }
            }
        }
    }

    private fun setConnectionStateIfRunning(state: ConnectionState): Boolean =
        synchronized(lifecycleLock) {
            if (stopped) {
                false
            } else {
                connectionState.value = state
                true
            }
        }

    private fun emitEventIfRunning(event: Event) {
        synchronized(lifecycleLock) {
            if (!stopped) eventsFlow.tryEmit(event)
        }
    }

    private fun publishTunnelsIfRunning(tunnels: List<TunnelInfo>) {
        synchronized(lifecycleLock) {
            if (!stopped) tunnelsState.value = tunnels
        }
    }

    private fun takeImmediateReconnect(): Boolean = synchronized(lifecycleLock) {
        if (stopped || !reconnectImmediately) {
            false
        } else {
            reconnectImmediately = false
            true
        }
    }

    private fun clearImmediateReconnect() {
        synchronized(lifecycleLock) {
            reconnectImmediately = false
        }
    }

    /**
     * Installs a waiter unless a reconnect intent already won the race. The
     * caller owns the returned waiter only when this returns true.
     */
    private fun installReconnectWaiterOrConsumeImmediate(
        waiter: CompletableDeferred<Unit>,
    ): Boolean = synchronized(lifecycleLock) {
        when {
            stopped -> false
            reconnectImmediately -> {
                reconnectImmediately = false
                false
            }
            else -> {
                reconnectWaiter = waiter
                true
            }
        }
    }

    private fun clearReconnectWaiter(waiter: CompletableDeferred<Unit>) {
        synchronized(lifecycleLock) {
            if (reconnectWaiter === waiter) reconnectWaiter = null
        }
    }

    private fun markTunnelsStopped() {
        synchronized(lifecycleLock) {
            if (stopped) return@synchronized
            val current = tunnelsState.value
            if (current.isEmpty()) return@synchronized
            tunnelsState.value = current.map { it.copy(status = TunnelInfo.Status.STOPPED) }
        }
    }

    private companion object {
        // Bound on the terminal [stop] connection close (see [teardownDispatcher]).
        // Long enough for a healthy disconnect, short enough that a wedged
        // socket can't pin the teardown worker.
        private const val CONNECTION_CLOSE_TIMEOUT_MS = 5_000L

        /**
         * Spent: the instance can never come back, so the supervisor must
         * re-dial. Every [TransportState.Closed] reason counts (issue #2487) —
         * forwarding dials its OWN connection precisely so a grace close never
         * reaches it, but a closed one is spent whichever way it got there.
         */
        private fun TransportState.isTerminal(): Boolean =
            this is TransportState.Lost || this is TransportState.Closed
    }
}
