package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-scoped, DI-ready SSH connection lease pool keyed by host and credential
 * identity.
 *
 * A live [SshSession] may be shared only while callers hold leases for the
 * exact same [SshLeaseKey]. Releasing the final lease keeps the connection
 * warm for [idleTtlMillis], then closes it if no caller reacquires it. The
 * idle retention cap bounds how many unused transports can sit open at once.
 */
public class SshLeaseManager(
    private val connector: SshLeaseConnector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val idleTtlMillis: Long = DEFAULT_IDLE_TTL_MILLIS,
    private val maxIdleLeases: Int = DEFAULT_MAX_IDLE_LEASES,
    // Issue #687 (lease-acquire bounding): hard cap on how long a SINGLE owned
    // cold connect/handshake may run before the lease forcibly aborts it and
    // surfaces a bounded failure. The sshj handshake's blocking JDK socket read
    // is NOT a kotlinx suspension point, so a wedged/slow peer can pin the
    // acquire even when the caller wraps it in `withTimeout` — `withTimeout`
    // cancels the coroutine but the cancellation cannot interrupt the in-flight
    // blocking read. This bound runs the connect as a manager-owned dial job the
    // lease can CANCEL on expiry; cancelling that job propagates into
    // `SshConnection.connect`'s `invokeOnCancellation`, which DISCONNECTS the
    // half-open transport and unparks the blocking read (the same close-to-heal
    // trick `SshFolderListGateway.execBounded` uses for reads). Without this the
    // picker (#702 / #470) hangs forever in `Loading`: no `PsFolderProbe`, and
    // even the downstream 12s reconcile timeout never fires because the wedge
    // sits below a cancellable suspension. Must comfortably exceed the sshj
    // `timeoutMs` (the per-attempt TCP/auth socket timeout) so the lease only
    // trips when sshj's own bound has itself wedged.
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    // Issue #687: context the bounded connect (and its timeout clock) runs on.
    // Defaults to [Dispatchers.IO] so the timeout uses a REAL wall-clock delay
    // that races the blocking handshake — the handshake's blocking socket read
    // happens on real time, so the bound must too (under a virtual-time
    // `runTest` scheduler the clock would auto-advance past the bound while the
    // real connect is still in flight and trip it spuriously, which is exactly
    // why the bound has its own dispatcher rather than inheriting the caller's).
    // Virtual-time unit tests inject a `StandardTestDispatcher(testScheduler)`
    // to step the bound deterministically.
    private val connectTimeoutContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    // Issue #687: context the bounded connect's ABORT (the off-path
    // `connectAbortScope.launch { dial.cancel() }` that fires on timeout/expiry)
    // runs on. Defaults to [Dispatchers.IO] so the abort runs on a REAL
    // background thread: cancelling a wedged dial can drive a blocking
    // `invokeOnCancellation` cleanup (disconnecting the half-open transport to
    // unpark the socket read), and that blocking cleanup MUST NOT run on the
    // caller's resume thread — firing it off-path on the IO pool is what lets
    // the acquire return its bounded failure even while cleanup is still
    // tearing the socket down. Kept SEPARATE from [connectTimeoutContext] only
    // so virtual-time unit tests can pin the abort to the SAME test scheduler
    // as the dial: cancelling a coroutine that lives on a `TestCoroutineScheduler`
    // from a real `Dispatchers.IO` thread is a cross-thread mutation of a
    // scheduler kotlinx documents as single-thread-only — a heisenbug under CI
    // load. Production stays on real [Dispatchers.IO]; only the test injects a
    // `StandardTestDispatcher(testScheduler)`.
    private val abortTimeoutContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {
    private val mutex = Mutex()
    private val entries: MutableMap<SshLeaseKey, Entry> = linkedMapOf()
    // Issue #620: in-flight cold connects, keyed by lease key. The FIRST
    // acquire for a key that has no live entry "owns" the connect; concurrent
    // acquires for the same key await this deferred instead of dialing their
    // own redundant SSH handshake. This is what makes the FIRST session open
    // from host detail instant: host detail's warm-lease acquire and the
    // user's session-open tap share ONE handshake, so the tap reuses the warm
    // transport the moment it is up rather than racing a second 3-4s connect.
    private val inFlightConnects: MutableMap<SshLeaseKey, CompletableDeferred<Result<Entry>>> =
        linkedMapOf()
    private var closed: Boolean = false
    private var nextEntryId: Long = 1L
    private var processStarted: Boolean = true
    private val _stateEvents = MutableSharedFlow<SshLeaseStateEvent>(
        extraBufferCapacity = STATE_EVENT_BUFFER_CAPACITY,
    )

    // Issue #845: the last transport state PUBLISHED on [stateEvents] per key, so
    // [emitStateLocked] can keep [stateEvents] a true transport-state EDGE stream
    // and not re-announce a transport that was already up. Only mutated under
    // [mutex] (every [emitStateLocked] call site holds it), so no extra
    // synchronisation is needed. A key drops out of the map only on a `Closed`
    // edge (transport gone), so a fresh dial after a close re-emits `Connected`.
    private val lastPublishedState: MutableMap<SshLeaseKey, SshLeaseConnectionState> =
        hashMapOf()
    private val connectScope = CoroutineScope(SupervisorJob() + connectTimeoutContext)
    private val connectAbortScope = CoroutineScope(SupervisorJob() + abortTimeoutContext)

    public val stateEvents: SharedFlow<SshLeaseStateEvent> = _stateEvents.asSharedFlow()

    init {
        require(idleTtlMillis >= 0) { "idleTtlMillis must be >= 0" }
        require(maxIdleLeases >= 0) { "maxIdleLeases must be >= 0" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be > 0" }
    }

    /**
     * Issue #1222 — the SINGLE liveness predicate the whole pool trusts. A
     * session counts as live for pooling ONLY while it is BOTH transport-connected
     * AND has not had its async [SshSession.close] initiated.
     *
     * The #1144 async `close()` leaves [SshSession.isConnected] == `true` for up to
     * ~2 s while the `SSH_MSG_DISCONNECT` drains. Trusting [isConnected] alone kept
     * a keepalive-dead / mid-drain transport as a warm-idle lease for up to 60 s,
     * lost the `keepalive_dead` attribution (it surfaced `Idle`, not
     * `Closed(KeepaliveDead)`), and handed the dying transport to a concurrent
     * same-host acquire (→ `TransportClosedException` → spurious failed attach).
     * [SshSession.isCloseInitiated] is the authoritative "going away" signal, so
     * routing reuse / [hasLiveLease] / [hasLiveOrConnectingLease] / [release]
     * through THIS one predicate closes the whole class in a single place (no dual
     * path, hard-cut D22).
     */
    private fun SshSession.isLiveForLease(): Boolean = isConnected && !isCloseInitiated

    public suspend fun acquire(target: SshLeaseTarget): Result<SshLease> {
        val key = target.leaseKey

        // Decide our role under the lock: reuse a live entry, await an
        // in-flight connect started by a concurrent acquire, or become the
        // owner of a fresh connect.
        val decision = mutex.withLock {
            if (closed) return Result.failure(SshLeaseManagerClosedException())
            val existing = entries[key]?.takeIf { it.session.isLiveForLease() }
            if (existing != null) {
                existing.closeJob?.cancel()
                existing.closeJob = null
                existing.idleSinceMillis = null
                existing.refCount += 1
                emitStateLocked(key, SshLeaseConnectionState.Connected)
                AcquireDecision.Reuse(existing)
            } else {
                // No live entry; a stale (disconnected) one must go before a
                // fresh transport replaces it.
                entries.remove(key)?.close()
                val pending = inFlightConnects[key]
                if (pending != null) {
                    // Another acquire is already dialing this exact key. Join
                    // it instead of opening a second redundant handshake.
                    AcquireDecision.Await(pending)
                } else {
                    val deferred = CompletableDeferred<Result<Entry>>()
                    inFlightConnects[key] = deferred
                    // Announce the in-flight handshake so a synchronous consumer
                    // (the tmux warm-open hint) can treat a session open landing
                    // in this window as a warm "Attaching" rather than a cold
                    // "Connecting" overlay (#620).
                    emitStateLocked(key, SshLeaseConnectionState.Connecting)
                    AcquireDecision.Own(deferred)
                }
            }
        }

        return when (decision) {
            is AcquireDecision.Reuse ->
                Result.success(
                    SshLease(
                        key = key,
                        session = decision.entry.session,
                        isNewConnection = false,
                        entryId = decision.entry.id,
                        releaseAction = ::release,
                    ),
                )
            is AcquireDecision.Await -> awaitSharedConnect(target, decision.deferred)
            is AcquireDecision.Own -> runOwnedConnect(target, decision.deferred)
        }
    }

    /**
     * Issue #620: take a ref on the entry produced by another acquire's
     * in-flight connect for this key. If that connect failed, propagate the
     * owner failure to every waiter instead of letting waiters serially retry
     * one-by-one after a shared outage.
     */
    private suspend fun awaitSharedConnect(
        target: SshLeaseTarget,
        deferred: CompletableDeferred<Result<Entry>>,
    ): Result<SshLease> {
        val key = target.leaseKey
        val shared = deferred.await().getOrElse { error ->
            return Result.failure(error)
        }
        val reused = mutex.withLock {
            if (closed) return Result.failure(SshLeaseManagerClosedException())
            val entry = shared
                .takeIf { entries[key] === it && it.session.isLiveForLease() }
                ?: return@withLock null
            entry.closeJob?.cancel()
            entry.closeJob = null
            entry.idleSinceMillis = null
            entry.refCount += 1
            emitStateLocked(key, SshLeaseConnectionState.Connected)
            entry
        }
        if (reused != null) {
            return Result.success(
                SshLease(
                    key = key,
                    session = reused.session,
                    isNewConnection = false,
                    entryId = reused.id,
                    releaseAction = ::release,
                ),
            )
        }
        return Result.failure(
            SshException("Coalesced SSH connect for $key did not produce a live session"),
        )
    }

    /**
     * Issue #620: own the cold connect for [target]. Concurrent acquires for
     * the same key park on [deferred] and reuse the entry we register here, so
     * the host-detail warm-lease handshake and the FIRST session-open tap share
     * ONE SSH connect instead of racing two.
     */
    private suspend fun runOwnedConnect(
        target: SshLeaseTarget,
        deferred: CompletableDeferred<Result<Entry>>,
    ): Result<SshLease> {
        val key = target.leaseKey
        try {
            val session = boundedConnect(target).getOrElse { error ->
                // Connect failed (including a bounded handshake timeout): clear
                // the in-flight slot, retract the optimistic Connecting hint, and
                // wake any awaiters with the same failure rather than letting
                // them serially retry the same outage one-by-one.
                mutex.withLock {
                    if (inFlightConnects[key] === deferred) inFlightConnects.remove(key)
                    retractConnectingHintLocked(key)
                }
                deferred.complete(Result.failure(error))
                return Result.failure(error)
            }
            return withContext(NonCancellable) {
                mutex.withLock {
                    if (inFlightConnects[key] === deferred) inFlightConnects.remove(key)
                    if (closed) {
                        runCatching { session.close() }
                        retractConnectingHintLocked(key)
                        deferred.complete(Result.failure(SshLeaseManagerClosedException()))
                        return@withLock Result.failure(SshLeaseManagerClosedException())
                    }
                    val raced = entries[key]
                    if (raced != null && raced.session.isLiveForLease()) {
                        // A live entry appeared for this key while we were dialing
                        // (e.g. a different code path acquired directly). Drop our
                        // redundant transport and reuse the live one; awaiters reuse
                        // it too via the deferred. Run this registration/close block
                        // noncancellably so a successful-but-not-yet-registered
                        // transport cannot leak if the acquiring coroutine is
                        // cancelled just after boundedConnect returns.
                        runCatching { session.close() }
                        raced.closeJob?.cancel()
                        raced.closeJob = null
                        raced.idleSinceMillis = null
                        raced.refCount += 1
                        emitStateLocked(key, SshLeaseConnectionState.Connected)
                        deferred.complete(Result.success(raced))
                        Result.success(
                            SshLease(
                                key = key,
                                session = raced.session,
                                isNewConnection = false,
                                entryId = raced.id,
                                releaseAction = ::release,
                            ),
                        )
                    } else {
                        raced?.close()
                        val entry = Entry(id = nextEntryId++, key = key, session = session, refCount = 1)
                        entries[key] = entry
                        emitStateLocked(key, SshLeaseConnectionState.Connected)
                        deferred.complete(Result.success(entry))
                        Result.success(
                            SshLease(
                                key = key,
                                session = session,
                                isNewConnection = true,
                                entryId = entry.id,
                                releaseAction = ::release,
                            ),
                        )
                    }
                }
            }
        } finally {
            // Cancellation (or any non-local exit) must never strand the
            // in-flight slot or leave awaiters blocked on a deferred that
            // would never complete. Clear our slot and wake awaiters with the
            // cancellation rather than making them start serial fallback dials.
            // No-op when the success/failure paths above already completed the
            // deferred.
            if (!deferred.isCompleted) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlightConnects[key] === deferred) inFlightConnects.remove(key)
                        retractConnectingHintLocked(key, SshLeaseCloseReason.ConnectCancelled)
                    }
                }
                // Issue #1185: wake a coalescing awaiter with a TYPED, retryable
                // failure — NOT a bare CancellationException value. The awaiter
                // (a session the user selected that merely JOINED this owner's
                // in-flight connect) is NOT itself cancelled; its own coroutine is
                // alive. A bare CancellationException here was indistinguishable
                // from a genuine unreachable at the consumer, so the selected
                // session stranded on Disconnected + "Attaching…" with no re-dial.
                // The typed [SshLeaseConnectCoalescedCancelException] lets the
                // consumer tell "my superseded owner was cancelled — re-dial my own
                // fresh connect" apart from "the host is unreachable".
                deferred.complete(
                    Result.failure(SshLeaseConnectCoalescedCancelException(key)),
                )
            }
        }
    }

    /**
     * Issue #687 (lease-acquire bounding): dial [target] under a hard
     * [connectTimeoutMillis] cap that the lease enforces ITSELF rather than
     * trusting the caller's `withTimeout`.
     *
     * The sshj handshake bottoms out in a blocking JDK socket read, which is
     * NOT a kotlinx suspension point. A wedged/slow peer can therefore pin the
     * acquire indefinitely: ordinary coroutine cancellation (what `withTimeout`
     * does) unparks at the next suspension point, but the in-flight blocking
     * read has none. We run the dial as a manager-owned job and, on bound expiry,
     * CANCEL that job. Cancelling it propagates into
     * [SshConnection.connect]'s `suspendCancellableCoroutine`, whose
     * `invokeOnCancellation` disconnects the half-open client — closing the
     * socket is what tears down the transport and unparks the blocking read
     * (mirrors the close-to-heal trick `SshFolderListGateway.execBounded` uses
     * for wedged exec reads). On timeout we surface a bounded
     * [SshLeaseConnectTimeoutException] so callers (the picker) fall through /
     * show a retryable error instead of hanging forever.
     *
     * On the healthy path the child completes well within the bound and the
     * timeout coroutine is cancelled, so this adds no latency. Cancellation of
     * the acquire itself still schedules cancellation of the owned dial job,
     * preserving the #620 coalescing cleanup contract.
     */
    private suspend fun boundedConnect(target: SshLeaseTarget): Result<SshSession> =
        withContext(connectTimeoutContext) {
            // Dial outside this withContext's structured child tree. Timeout
            // expiry must be able to return even when the connector's cancellation
            // cleanup blocks inside disconnect(); a regular child would keep the
            // parent scope from completing until that cleanup returned.
            val abandoned = AtomicBoolean(false)
            val dial = connectScope.async {
                val result = try {
                    connector.connect(target)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce // never swallow cancellation
                } catch (t: Throwable) {
                    // A misbehaving connector that THROWS instead of returning a
                    // failure Result must not crash the lease scope.
                    Result.failure(t)
                }
                if (abandoned.get()) {
                    result.getOrNull()?.close()
                }
                result
            }
            try {
                withTimeoutOrNull(connectTimeoutMillis) { dial.await() }
                    ?: run {
                        // Stop awaiting the wedged handshake so this coroutine can
                        // resume; cancelling the detached dial fires
                        // SshConnection.connect's invokeOnCancellation, which
                        // disconnects the half-open transport and unparks the
                        // blocking read. Do NOT cancel on this coroutine: a
                        // blocking cancellation handler would make the timeout
                        // path hang. Fire it from the abort scope and return.
                        abandoned.set(true)
                        connectAbortScope.launch { dial.cancel() }
                        Result.failure(SshLeaseConnectTimeoutException(target.leaseKey, connectTimeoutMillis))
                    }
            } finally {
                if (!dial.isCompleted) {
                    abandoned.set(true)
                    connectAbortScope.launch { dial.cancel() }
                }
            }
        }

    /**
     * Issue #620: retract the optimistic [SshLeaseConnectionState.Connecting]
     * hint for [key] when an owned connect ended WITHOUT leaving a live entry
     * (failed / cancelled / manager closed). Emit [SshLeaseConnectionState.Closed]
     * only when there is genuinely no live transport left, so a synchronous
     * consumer drops the stale "Attaching" hint instead of treating a dead key
     * as warm. No-op when a live entry exists for the key (it already announced
     * Connected). Must be called while holding [mutex].
     */
    private fun retractConnectingHintLocked(
        key: SshLeaseKey,
        closeReason: SshLeaseCloseReason = SshLeaseCloseReason.Disconnected,
    ) {
        if (entries[key]?.session?.isLiveForLease() == true) return
        if (inFlightConnects.containsKey(key)) return
        if (lastPublishedState[key] != SshLeaseConnectionState.Connecting) return
        emitStateLocked(
            key = key,
            state = SshLeaseConnectionState.Closed,
            closeReason = closeReason,
        )
    }

    private sealed interface AcquireDecision {
        data class Reuse(val entry: Entry) : AcquireDecision
        data class Await(val deferred: CompletableDeferred<Result<Entry>>) : AcquireDecision
        data class Own(val deferred: CompletableDeferred<Result<Entry>>) : AcquireDecision
    }

    /**
     * Read-only probe: does the pool currently hold a live (still
     * `isConnected`) transport for [key], whether it is actively leased or
     * sitting warm/idle? Used by the tmux attach flow to tell a genuine COLD
     * connect (no live transport — full-screen Connecting overlay is correct)
     * apart from a WARM open of a session on a host whose SSH lease is already
     * up (#634 — attach instantly, no "Reconnecting", no blanking overlay).
     *
     * This does NOT acquire, retain, evict, or otherwise mutate any lease — it
     * only reads pool state, so calling it never changes the lease lifecycle.
     */
    public suspend fun hasLiveLease(key: SshLeaseKey): Boolean =
        mutex.withLock {
            if (closed) return@withLock false
            entries[key]?.session?.isLiveForLease() == true
        }

    /**
     * Issue #620: like [hasLiveLease], but ALSO true when a connect for [key] is
     * currently in flight (host detail's warm-lease acquire is mid-handshake).
     *
     * This is the signal the FIRST session-open from host detail needs: when the
     * user taps a session before the host's warm handshake has finished, the
     * open is NOT a genuine cold dial — it will coalesce onto the in-flight
     * connect and reuse the shared transport the instant it is up. So it should
     * show the green "Attaching" affordance, not the full-screen Connecting
     * overlay, exactly like a #634 warm open. Without this, the first open
     * flashes the blanking overlay even though no second handshake happens.
     *
     * Like [hasLiveLease], this only reads pool state and never mutates the
     * lease lifecycle.
     */
    public suspend fun hasLiveOrConnectingLease(key: SshLeaseKey): Boolean =
        mutex.withLock {
            if (closed) return@withLock false
            if (entries[key]?.session?.isLiveForLease() == true) return@withLock true
            inFlightConnects.containsKey(key)
        }

    public suspend fun disconnect(key: SshLeaseKey) {
        val entry = mutex.withLock {
            entries.remove(key)?.also {
                emitStateLocked(
                    key = key,
                    state = SshLeaseConnectionState.Closed,
                    closeReason = SshLeaseCloseReason.ExplicitDisconnect,
                )
            }
        }
        entry?.close()
    }

    /**
     * Evict a retained zero-reference lease without disturbing active holders.
     *
     * Network handoffs can leave a transport reporting [SshSession.isConnected]
     * even though new channels should be opened on a fresh TCP path. Callers
     * that need a fresh acquire can use this after their current lease has been
     * released; active leases are deliberately left in place.
     *
     * @return true when an idle lease existed and was closed.
     */
    public suspend fun evictIdle(key: SshLeaseKey): Boolean {
        val entry = mutex.withLock {
            val entry = entries[key] ?: return@withLock null
            if (entry.refCount != 0) return@withLock null
            entries.remove(key)
            emitStateLocked(
                key = key,
                state = SshLeaseConnectionState.Closed,
                closeReason = SshLeaseCloseReason.ForceRefresh,
            )
            entry
        } ?: return false
        entry.close()
        return true
    }

    /**
     * Apply the app process background policy for warm SSH transports.
     *
     * Active leases are left alone because the owning foreground flow must
     * detach/release its tmux channels in the correct order. Once those leases
     * release while the process is stopped, [release] closes them immediately
     * instead of starting another idle timer.
     */
    public suspend fun onProcessStopped() {
        val idleEntries = mutex.withLock {
            processStarted = false
            entries.values
                .filter { it.refCount == 0 }
                .also { idle ->
                    idle.forEach {
                        entries.remove(it.key)
                        emitStateLocked(
                            key = it.key,
                            state = SshLeaseConnectionState.Closed,
                            closeReason = SshLeaseCloseReason.ProcessStopped,
                        )
                    }
                }
        }
        idleEntries.forEach { it.close() }
    }

    public suspend fun onProcessStarted() {
        mutex.withLock {
            processStarted = true
        }
    }

    override fun close() {
        val toClose = mutableListOf<Entry>()
        val pendingToWake = mutableListOf<CompletableDeferred<Result<Entry>>>()
        runCatching {
            kotlinx.coroutines.runBlocking {
                mutex.withLock {
                    closed = true
                    toClose += entries.values
                    val keysWithEntries = entries.keys.toSet()
                    val pendingKeys = inFlightConnects.keys.toList()
                    entries.clear()
                    // Issue #620: wake any acquires parked on an in-flight
                    // connect so they observe `closed` and fail fast instead of
                    // blocking forever on a deferred that would never complete.
                    pendingToWake += inFlightConnects.values
                    inFlightConnects.clear()
                    pendingKeys
                        .filterNot { it in keysWithEntries }
                        .forEach {
                            retractConnectingHintLocked(
                                key = it,
                                closeReason = SshLeaseCloseReason.ManagerClosed,
                            )
                        }
                    toClose.forEach {
                        emitStateLocked(
                            key = it.key,
                            state = SshLeaseConnectionState.Closed,
                            closeReason = SshLeaseCloseReason.ManagerClosed,
                        )
                    }
                }
            }
        }
        pendingToWake.forEach { it.complete(Result.failure(SshLeaseManagerClosedException())) }
        toClose.forEach { it.close() }
        connectScope.cancel()
        connectAbortScope.cancel()
    }

    private suspend fun release(key: SshLeaseKey, entryId: Long) {
        // Issue #937 / S1-F2: the refcount bookkeeping MUST be atomic — it can
        // never be torn in half by the caller's teardown timeout, or a
        // cancelled release would leak a refcount and pin the pooled transport
        // open forever (the #699 invariant). So ONLY this fast, wedge-free
        // bookkeeping is wrapped in NonCancellable. The wedge-prone part — the
        // actual transport `close()` socket write below — is left OUTSIDE the
        // NonCancellable boundary so the caller's `withTimeoutOrNull` can
        // interrupt a release that blocks behind a half-open close. (The close
        // is itself bounded by the dispatcher's per-op ceiling + RealSshShell's
        // off-Main bound, so even when reached it cannot wedge unboundedly.)
        val closeNow = withContext(NonCancellable) {
            mutex.withLock {
                val entry = entries[key] ?: return@withContext null
                if (entry.id != entryId) return@withContext null
                if (entry.refCount <= 0) return@withContext null
                entry.refCount -= 1
                if (entry.refCount > 0) return@withContext null
                // Issue #1222: a session whose async close() has been INITIATED is
                // NOT live (isLiveForLease()==false even while isConnected still
                // transiently lies true during the ~2 s disconnect drain), so it
                // takes the removal branch instead of being parked warm/idle. This
                // is what stops a keepalive-dead / mid-drain transport from being
                // retained for up to 60 s and handed to a concurrent acquirer.
                if (!entry.session.isLiveForLease() || !processStarted || idleTtlMillis == 0L || maxIdleLeases == 0) {
                    entries.remove(key)
                    emitStateLocked(
                        key = key,
                        state = SshLeaseConnectionState.Closed,
                        closeReason = when {
                            // Issue #969: a transport the keepalive watchdog
                            // (#945) declared dead is NAMED `keepalive_dead`,
                            // not lumped into the anonymous `Disconnected` —
                            // resolving the #964 attribution ambiguity. Read the
                            // session's own cause so the lease layer never
                            // reaches up into the app (no layering violation).
                            // Issue #1222: checked FIRST so a keepalive-dead
                            // session released INSIDE the async-close window (when
                            // isConnected still lies true) is still stamped
                            // `keepalive_dead` on the transport EDGE, not lost as
                            // an anonymous `Idle`.
                            entry.session.closeCause == SshSessionCloseCause.KeepaliveDead ->
                                SshLeaseCloseReason.KeepaliveDead
                            // Issue #1222: a close-initiated OR already-disconnected
                            // transport is an anonymous `Disconnected` (a plain
                            // teardown reached mid-drain), distinct from an idle-TTL
                            // expiry — the transport is gone, not merely unused.
                            !entry.session.isLiveForLease() -> SshLeaseCloseReason.Disconnected
                            !processStarted -> SshLeaseCloseReason.ProcessStopped
                            else -> SshLeaseCloseReason.IdleExpired
                        },
                    )
                    return@withLock entry
                }

                entry.idleSinceMillis = nowMillis()
                entry.closeJob?.cancel()
                entry.closeJob = scope.launch {
                    delay(idleTtlMillis)
                    closeIfStillIdle(key, entryId)
                }
                emitStateLocked(key, SshLeaseConnectionState.Idle)
                trimIdleLocked()
            }
        }
        closeNow?.close()
    }

    private suspend fun closeIfStillIdle(key: SshLeaseKey, entryId: Long) {
        val expired = mutex.withLock {
            val entry = entries[key] ?: return
            if (entry.id != entryId) return
            if (entry.refCount != 0) return
            entries.remove(key)
            emitStateLocked(
                key = key,
                state = SshLeaseConnectionState.Closed,
                closeReason = SshLeaseCloseReason.IdleExpired,
            )
            entry
        }
        expired?.close()
    }

    private fun trimIdleLocked(): Entry? {
        val idle = entries.values
            .filter { it.refCount == 0 }
            .sortedBy { it.idleSinceMillis ?: Long.MAX_VALUE }
        if (idle.size <= maxIdleLeases) return null
        val oldest = idle.first()
        entries.remove(oldest.key)
        emitStateLocked(
            key = oldest.key,
            state = SshLeaseConnectionState.Closed,
            closeReason = SshLeaseCloseReason.IdleTrimmed,
        )
        return oldest
    }

    private fun emitStateLocked(
        key: SshLeaseKey,
        state: SshLeaseConnectionState,
        closeReason: SshLeaseCloseReason? = null,
    ) {
        // Issue #845 — keep [stateEvents] a true transport up/down EDGE stream.
        // `Connected` maps to the controller's `transport.up` heal edge
        // ([SshLeaseTransportPort.transportEvents]). A REUSE-acquire of a
        // transport that is already up (last published `Connected`, or `Idle` —
        // both mean the SSH session is still alive) is NOT a transport-up edge:
        // the transport never went down. Re-announcing it stormed ~9
        // `transport.up` per connection (the maintainer's #794 flap / 46-at-scale
        // churn) because many subsystems each reuse the SAME warm host. Suppress
        // the re-emit so one real connect ⇒ exactly one logical `Connected`.
        // A genuine reconnect re-emits, because a real drop publishes `Closed`
        // first (dropping the key from [lastPublishedState]), so the next
        // `Connected` is a transition from a not-live state and is emitted.
        if (state == SshLeaseConnectionState.Connected) {
            val prior = lastPublishedState[key]
            if (prior == SshLeaseConnectionState.Connected ||
                prior == SshLeaseConnectionState.Idle
            ) {
                // Transport was already up; this acquire merely reused it. No
                // edge — but keep the published state pinned at `Connected`.
                lastPublishedState[key] = SshLeaseConnectionState.Connected
                return
            }
        }
        if (state == SshLeaseConnectionState.Closed) {
            // Transport gone: forget the key so a future reconnect counts as a
            // genuine transition back into `Connected`.
            lastPublishedState.remove(key)
        } else {
            lastPublishedState[key] = state
        }
        _stateEvents.tryEmit(
            SshLeaseStateEvent(
                key = key,
                state = state,
                closeReason = closeReason,
            ),
        )
    }

    private class Entry(
        val id: Long,
        val key: SshLeaseKey,
        val session: SshSession,
        var refCount: Int,
        var idleSinceMillis: Long? = null,
        var closeJob: Job? = null,
    ) {
        fun close() {
            closeJob?.cancel()
            closeJob = null
            runCatching { session.close() }
        }
    }

    public companion object {
        public const val DEFAULT_IDLE_TTL_MILLIS: Long = 60_000L

        /**
         * Issue #996: maximum number of zero-ref (warm/idle) transports the pool
         * keeps open at once before [trimIdleLocked] evicts the LRU.
         *
         * Raised from 2 to 4. At 2, a 3-host round-trip (A->B->C->A) trimmed
         * host A's still-warm transport in the very gesture the user switched
         * BACK to it — a self-inflicted COLD redial (fresh handshake +
         * `Connecting` overlay) on a stable network. 4 covers a comfortable
         * 3-host active working set plus one in-flight, so a switch-back reuses
         * the warm lease instead of cold-redialing. The existing LRU + 60s-TTL
         * trim ([trimIdleLocked] / [DEFAULT_IDLE_TTL_MILLIS]) is RETAINED as the
         * ceiling: a burst beyond the cap still evicts the LRU, and a truly
         * abandoned host still closes after the TTL — the pool can never grow
         * unbounded. The maintainer may tune this number later for a larger
         * working set (cap = working-set + 1).
         */
        public const val DEFAULT_MAX_IDLE_LEASES: Int = 4

        /**
         * Issue #687: default hard cap on a single owned cold connect/handshake.
         *
         * sshj's own per-attempt socket timeout ([SshConnection.DEFAULT_TIMEOUT_MS]
         * = 30s) bounds an ordinary unreachable host. This lease-level cap is the
         * backstop for the case where sshj's bound itself fails to trip (a
         * half-open peer that accepts the TCP connection but stalls mid-handshake,
         * leaving the JDK read blocked). It sits comfortably above sshj's 30s so a
         * normal slow-but-progressing connect is never cut short, yet it
         * guarantees the acquire — and therefore the picker — always resolves.
         */
        public const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 35_000L
        private const val STATE_EVENT_BUFFER_CAPACITY: Int = 64
    }
}

public data class SshLeaseStateEvent(
    val key: SshLeaseKey,
    val state: SshLeaseConnectionState,
    val closeReason: SshLeaseCloseReason? = null,
)

public enum class SshLeaseConnectionState {
    // Issue #620: a cold connect for this key has STARTED (host detail's
    // warm-lease acquire is mid-handshake) but has not yet produced a live
    // transport. Lets a consumer treat a session open that lands in this window
    // as a warm "Attaching", not a blanking cold "Connecting" overlay, because
    // the open coalesces onto the in-flight handshake (no second dial).
    Connecting,
    Connected,
    Idle,
    Closed,
}

public enum class SshLeaseCloseReason {
    IdleExpired,
    IdleTrimmed,
    ProcessStopped,
    ExplicitDisconnect,
    ManagerClosed,
    Disconnected,
    ForceRefresh,

    /**
     * Issue #969: the held session's transport was closed by the always-on
     * keepalive watchdog ([SshSessionCloseCause.KeepaliveDead], #945) — a
     * proactive silent-drop detection. Distinct from the anonymous
     * [Disconnected] so the app names `keepalive_dead` in the reconnect trail
     * instead of an undifferentiated `lease_down` (the #964 ambiguity).
     */
    KeepaliveDead,

    /**
     * Issue #1185: the in-flight cold connect that owned this key was CANCELLED
     * before it produced a live transport — the user superseded the create/attach
     * flow (e.g. created a new session, then immediately selected an existing one
     * on the same host) so the owner's acquire coroutine died. Distinct from the
     * anonymous [Disconnected] so the shareable connection log (#1175) names
     * `connect_cancelled` instead of an undifferentiated `lease_down`, and so a
     * coalescing awaiter of that owner can re-dial its own fresh connect rather
     * than stranding on a terminal "unreachable".
     */
    ConnectCancelled,
}

public data class SshLeaseKey(
    val host: String,
    val port: Int,
    val user: String,
    val credentialId: String,
    val knownHostsId: String = "accept-all",
)

public data class SshLeaseTarget(
    val leaseKey: SshLeaseKey,
    val key: SshKey,
    val passphrase: CharArray? = null,
    val knownHosts: KnownHostsPolicy = KnownHostsPolicy.AcceptAll,
    val timeoutMs: Int = SshConnection.DEFAULT_TIMEOUT_MS,
)

public fun interface SshLeaseConnector {
    public suspend fun connect(target: SshLeaseTarget): Result<SshSession>
}

public class DefaultSshLeaseConnector : SshLeaseConnector {
    override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
        SshConnection.connect(
            host = target.leaseKey.host,
            port = target.leaseKey.port,
            user = target.leaseKey.user,
            key = target.key,
            passphrase = target.passphrase?.copyOf(),
            knownHosts = target.knownHosts,
            timeoutMs = target.timeoutMs,
        )
}

public class SshLease internal constructor(
    public val key: SshLeaseKey,
    public val session: SshSession,
    public val isNewConnection: Boolean,
    private val entryId: Long,
    private val releaseAction: suspend (SshLeaseKey, Long) -> Unit,
) {
    private val releaseMutex = Mutex()
    private var released: Boolean = false

    /**
     * Decrement the lease refcount.
     *
     * Issue #937 / S1-F2: the release MUST be interruptible by the caller's
     * teardown timeout. Previously the whole [releaseAction] ran inside
     * `withContext(NonCancellable)`, so the bounded teardown (#710 — the
     * `withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS) { lease.release() }` in
     * `TmuxSessionViewModel`/`GitHistoryViewModel`/`RecurringJobsViewModel`)
     * could NOT interrupt a release that blocked behind a wedged transport
     * close on the contended lease mutex — defeating the very ceiling meant to
     * stop the onCleared/activity-destroy ANR. The release now runs WITHOUT
     * NonCancellable, so when the caller's `withTimeoutOrNull` fires the
     * suspension is cancelled and the timeout actually takes effect.
     *
     * The bookkeeping is still correct under cancellation: [released] is only
     * set true AFTER [releaseAction] returns normally, so a cancelled release
     * leaves the lease un-released and a later retry/close path can still tear
     * it down. (The pool-side refcount/idle teardown that [releaseAction]
     * drives keeps its own NonCancellable boundary where it genuinely must not
     * be torn in half — see [SshLeaseManager]; the *caller-visible* wait here
     * is what must stay interruptible.)
     */
    public suspend fun release() {
        releaseMutex.withLock {
            if (released) return
            releaseAction(key, entryId)
            released = true
        }
    }
}

public class SshLeaseManagerClosedException : IllegalStateException("SSH lease manager is closed")

/**
 * Issue #687 (lease-acquire bounding): a single owned cold connect/handshake
 * exceeded [SshLeaseManager]'s `connectTimeoutMillis` and was forcibly aborted
 * (the half-open transport disconnected to unpark the wedged blocking read).
 *
 * This is a BOUNDED, retryable failure — callers (e.g. the session picker)
 * should fall through / surface a retry affordance rather than hang. It is
 * distinct from an ordinary connect failure so callers can tell a wedged
 * handshake apart from a refused/auth/DNS error.
 */
public class SshLeaseConnectTimeoutException(
    public val key: SshLeaseKey,
    public val timeoutMillis: Long,
) : IllegalStateException(
    "SSH lease connect to ${key.user}@${key.host}:${key.port} exceeded ${timeoutMillis}ms and was aborted",
)

/**
 * Issue #1185: a coalescing awaiter's in-flight connect OWNER (the create/attach
 * flow it merely joined via #620 coalescing) was cancelled before it produced a
 * live transport — the user superseded that flow (e.g. created a new session,
 * then immediately selected an existing session on the SAME host). The awaiter is
 * NOT itself cancelled; its own coroutine is alive, so it must be able to tell
 * this superseded-owner cancellation apart from a genuine unreachable/auth/DNS
 * failure and re-dial its OWN fresh connect (the owner's in-flight slot is already
 * cleared, so a retry becomes a fresh owner and dials cleanly).
 *
 * This is a NORMAL, RETRYABLE failure delivered as a `Result` value — NOT a
 * [kotlin.coroutines.cancellation.CancellationException] (which the consumer
 * would misclassify as a terminal connect failure, stranding the selected session
 * on a Disconnected pill + "Attaching…" spinner with no re-dial — the #1185 bug).
 * The message is preserved verbatim so any existing diagnostic string continues
 * to read the same.
 */
public class SshLeaseConnectCoalescedCancelException(
    public val key: SshLeaseKey,
) : IllegalStateException("SSH connect cancelled for $key")
