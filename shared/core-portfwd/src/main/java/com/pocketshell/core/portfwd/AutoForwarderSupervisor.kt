package com.pocketshell.core.portfwd

import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long-lived supervisor that pairs a single [AutoForwarder] (per-session
 * scan-and-forward engine) with the outer connection lifecycle:
 *
 *  - Calls the supplied [sessionFactory] to acquire a fresh [SshSession]
 *  - Drives an [AutoForwarder] over that session for as long as it stays
 *    connected
 *  - On disconnect or factory failure, applies exponential backoff and
 *    reconnects, up to [maxReconnectAttempts] (after which the supervisor
 *    surfaces `ConnectionLost`)
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
 *  - [AutoForwarder] today takes a single [SshSession] and is fully
 *    covered by 24+ unit tests that pass that session in directly. Adding
 *    a reconnect loop inside it would force every existing test to grow a
 *    factory callback even when reconnect is irrelevant.
 *  - The supervisor lets us layer reconnect tests independently of
 *    forwarder tests (factories that fail N times, sessions that drop
 *    mid-scan, ...).
 *  - The foreground service (`ForwardingService.kt`) owns one supervisor
 *    per active host. The supervisor presents a stable `flowOfTunnels()` /
 *    `flowOfEvents()` surface even across session swaps, so the service's
 *    notification can keep rendering through a reconnect window without
 *    needing to re-subscribe.
 *
 * Lifecycle contract: [start], [stop], and [reconnectNow] may be called
 * concurrently from any thread. [start] is idempotent and publishes exactly
 * one loop job; [stop] is terminal and linearizes with job/session
 * publication, so a late connect result is closed rather than mounted.
 * [reconnectNow] only publishes a wake/force intent while the supervisor is
 * live. [lifecycleLock] protects those lifecycle fields and all state
 * publication; blocking session/forwarder teardown happens after releasing
 * the lock.
 */
public class AutoForwarderSupervisor(
    /**
     * Produces a fresh [SshSession] each time the supervisor needs to
     * connect (initial connect + after every drop). The factory is
     * expected to throw on failure; the supervisor catches the throwable
     * and applies backoff before retrying.
     *
     * Modelled as a suspending lambda so callers (e.g. the foreground
     * service) can run the real `SshConnection.connect(...)` flow inside
     * it, including DAO lookups for the host / key.
     */
    private val sessionFactory: suspend () -> SshSession,
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
     * `null` means "keep retrying forever" (matches the upstream JSch
     * client's default behaviour). A finite cap is safer for the
     * foreground-service case so a permanently-misconfigured host
     * eventually clears the persistent notification instead of looping.
     */
    private val maxReconnectAttempts: Int? = null,
    /**
     * How often the supervisor polls live-session health while a
     * forwarder is mounted. The supervisor only reads
     * [SshSession.isConnected], which is in-process state on the sshj
     * client, so a 1 s cadence is cheap. Tunable so unit tests can
     * tighten it for deterministic test runtime.
     */
    private val sessionHealthPollMs: Long = 1_000L,
    /**
     * Dispatcher the terminal [stop] teardown closes the live SSH session
     * on. [stop] is reached on the Android Main thread (the port-forward
     * panel's auto-forward toggle-off and the foreground service's
     * `ACTION_STOP` both call it synchronously via
     * `ForwardingController.stopForwarding`/`stopAllForwarding` →
     * `ActiveHost.stopOwnedSupervisor`), and `SshSession.close()` drives an
     * `SSH_MSG_DISCONNECT` socket write that blocks the caller until the
     * disconnect finishes — on a wedged socket that froze the UI (the #1085
     * freeze-hunt F-E finding). Defaults to [Dispatchers.IO] so the session
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
         * Surfaced after [maxReconnectAttempts] consecutive failures. The
         * supervisor stops trying until the caller invokes [reconnectNow]
         * or [stop] + restart with a new supervisor.
         */
        public data class ConnectionLost(val lastError: String) : Event()
    }

    /** Long-lived tunnel snapshot stream — bridges per-forwarder swaps. */
    private val tunnelsState = MutableStateFlow<List<TunnelInfo>>(emptyList())

    /** Connection state — UI can render "Reconnecting…" between sessions. */
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

    /** Serializes lifecycle check-then-act transitions and resource ownership. */
    private val lifecycleLock = Any()

    /**
     * User-desired manual port forwards (issue #439), independent of the
     * live transport. The per-session [AutoForwarder] is rebuilt on every
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
    private var currentSession: SshSession? = null

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
     * inside a [coroutineScope] block so all child jobs (the per-session
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
            // launches inside `runForwarderUntilSessionDrops` (the
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
        job.invokeOnCompletion { closeRemainingResources(offThread = true) }
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
     * close the live session. Idempotent.
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
            currentForwarder to currentSession
        }
        resourcesToStop.first?.stop()
        resourcesToStop.second?.let(::closeSessionOffThread)
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
        var sessionToClose: SshSession? = null
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
                sessionToClose = currentSession
            }
        }
        waiterToWake?.complete(Unit)
        // A real default-network loss/recovery can leave sshj's session object
        // reporting "connected" even though the phone-side forwards are dead.
        // Force/non-connected reconnects close the transport so the next
        // session restores desired forwards promptly. Do not churn a healthy
        // connected tunnel on an ordinary availability callback.
        runCatching { sessionToClose?.close() }
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
            try {
                val nextState =
                    if (attemptCount == 0) ConnectionState.Connecting else ConnectionState.Reconnecting
                if (!setConnectionStateIfRunning(nextState)) break
                if (attemptCount > 0) {
                    markTunnelsStopped()
                }

                val session = sessionFactory()
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
                    session = session,
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
                        currentSession = session
                        currentForwarder = forwarder
                        true
                    }
                }
                if (!published) {
                    forwarder.stop()
                    runCatching { session.close() }
                    break
                }
                // Reset bookkeeping for the new session — backoff
                // restarts at the initial delay and consecutive-failure
                // count resets so [maxReconnectAttempts] doesn't trip
                // off historical, since-recovered failures.
                reconnectDelay = initialReconnectDelayMs
                consecutiveFailures = 0
                // Do not let the previous session's FORWARDING snapshot
                // satisfy restore/readiness observers while this fresh
                // forwarder is still doing its first scan.
                markTunnelsStopped()
                if (!setConnectionStateIfRunning(ConnectionState.Connected)) break
                emitEventIfRunning(Event.Connected(attemptCount))

                // Drive the forwarder loop on the supervisor scope and
                // mirror its tunnel snapshots out through our long-lived
                // tunnelsState.
                runForwarderUntilSessionDrops()

                // We get here when the forwarder loop exited cleanly
                // (e.g. session dropped or stop() was called). Mark all
                // tunnels STOPPED on the way out so the UI can show
                // "reconnecting…" without the tunnels disappearing
                // entirely.
                if (!stopped) {
                    markTunnelsStopped()
                    emitEventIfRunning(Event.Disconnected("session lost"))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (!stopped) {
                    consecutiveFailures += 1
                    val msg = t.message ?: t.javaClass.simpleName
                    emitEventIfRunning(Event.Error(msg))
                    markTunnelsStopped()
                }
            } finally {
                // Always release the per-session forwarder + session
                // before sleeping into the next reconnect attempt.
                closeRemainingResources()
            }

            if (stopped) break

            if (takeImmediateReconnect()) {
                reconnectDelay = initialReconnectDelayMs
                continue
            }

            if (maxReconnectAttempts != null && consecutiveFailures >= maxReconnectAttempts) {
                if (!setConnectionStateIfRunning(ConnectionState.Lost)) break
                emitEventIfRunning(Event.ConnectionLost("max reconnect attempts reached"))
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

    private suspend fun runForwarderUntilSessionDrops() {
        val resources = synchronized(lifecycleLock) {
            if (stopped) null else currentForwarder to currentSession
        } ?: return
        val forwarder = resources.first ?: return
        val session = resources.second ?: return
        // Run the forwarder + tunnel-bridge inside a nested coroutineScope
        // so we deterministically join both when the session drops, the
        // supervisor stops, or the outer scope is cancelled. The nested
        // scope is a child of `parent` (which is itself the supervisor
        // coroutine's `coroutineScope` body), so any cancellation
        // propagates correctly and `runTest` sees a clean shutdown.
        kotlinx.coroutines.coroutineScope {
            val bridgeJob = launch {
                forwarder.flowOfTunnels().collect { publishTunnelsIfRunning(it) }
            }
            val forwarderJob = forwarder.start(this)

            try {
                // Poll for disconnection — the per-session forwarder
                // doesn't own session liveness; that's the supervisor's
                // job. The poll cadence is bounded by
                // [sessionHealthPollMs] (default 1 s; tunable for tests).
                while (!stopped && session.isConnected && currentCoroutineContext().isActive) {
                    delay(sessionHealthPollMs)
                }
            } finally {
                // Cancel the children so the nested coroutineScope can
                // exit promptly on session drop. On outer cancellation
                // both jobs are already cancelled by structured
                // concurrency, but cancelling explicitly here lets the
                // session-dropped path exit without waiting for the next
                // upstream cancellation event.
                forwarderJob.cancel()
                bridgeJob.cancel()
            }
        }
    }

    private fun completedJob(): Job = Job().apply { complete() }

    /** Claims and tears down resources left by a completed/cancelled loop. */
    private fun closeRemainingResources(offThread: Boolean = false) {
        var forwarderToStop: AutoForwarder? = null
        var sessionToClose: SshSession? = null
        synchronized(lifecycleLock) {
            forwarderToStop = currentForwarder.also { currentForwarder = null }
            sessionToClose = currentSession.also { currentSession = null }
        }
        // AutoForwarder.stop() already closes its forwards off the caller's
        // thread (it offloads to its own teardownDispatcher), so this call
        // returns promptly.
        forwarderToStop?.stop()
        val session = sessionToClose ?: return
        if (!offThread) {
            runCatching { session.close() }
            return
        }
        closeSessionOffThread(session)
    }

    /** Closes a session without allowing a wedged socket to block its caller. */
    private fun closeSessionOffThread(session: SshSession) {
        CoroutineScope(SupervisorJob() + teardownDispatcher).launch {
            runCatching {
                withTimeoutOrNull(SESSION_CLOSE_TIMEOUT_MS) {
                    runInterruptible { session.close() }
                }
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
        // Bound on the terminal [stop] session-close (see [teardownDispatcher]).
        // Long enough for a healthy disconnect, short enough that a wedged
        // socket can't pin the teardown worker.
        private const val SESSION_CLOSE_TIMEOUT_MS = 5_000L
    }
}
