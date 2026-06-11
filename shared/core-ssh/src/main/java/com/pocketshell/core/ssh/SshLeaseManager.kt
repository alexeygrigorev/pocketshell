package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    // blocking read. This bound runs the connect as a child the lease can
    // CANCEL on expiry; cancelling that child propagates into
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
    private val inFlightConnects: MutableMap<SshLeaseKey, CompletableDeferred<Entry?>> =
        linkedMapOf()
    private var closed: Boolean = false
    private var nextEntryId: Long = 1L
    private var processStarted: Boolean = true
    private val _stateEvents = MutableSharedFlow<SshLeaseStateEvent>(
        extraBufferCapacity = STATE_EVENT_BUFFER_CAPACITY,
    )

    public val stateEvents: SharedFlow<SshLeaseStateEvent> = _stateEvents.asSharedFlow()

    init {
        require(idleTtlMillis >= 0) { "idleTtlMillis must be >= 0" }
        require(maxIdleLeases >= 0) { "maxIdleLeases must be >= 0" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be > 0" }
    }

    public suspend fun acquire(target: SshLeaseTarget): Result<SshLease> {
        val key = target.leaseKey

        // Decide our role under the lock: reuse a live entry, await an
        // in-flight connect started by a concurrent acquire, or become the
        // owner of a fresh connect.
        val decision = mutex.withLock {
            if (closed) return Result.failure(SshLeaseManagerClosedException())
            val existing = entries[key]?.takeIf { it.session.isConnected }
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
                    val deferred = CompletableDeferred<Entry?>()
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
     * in-flight connect for this key. If that connect failed, or the shared
     * entry was closed/disconnected by the time we awoke, fall back to a fresh
     * owned connect so the caller still gets a lease (never silently fails just
     * because the connect it joined lost its transport).
     */
    private suspend fun awaitSharedConnect(
        target: SshLeaseTarget,
        deferred: CompletableDeferred<Entry?>,
    ): Result<SshLease> {
        val key = target.leaseKey
        val shared = deferred.await()
        val reused = mutex.withLock {
            if (closed) return Result.failure(SshLeaseManagerClosedException())
            val entry = shared
                ?.takeIf { entries[key] === it && it.session.isConnected }
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
        // The shared connect did not yield a usable entry for us; dial our own.
        return acquire(target)
    }

    /**
     * Issue #620: own the cold connect for [target]. Concurrent acquires for
     * the same key park on [deferred] and reuse the entry we register here, so
     * the host-detail warm-lease handshake and the FIRST session-open tap share
     * ONE SSH connect instead of racing two.
     */
    private suspend fun runOwnedConnect(
        target: SshLeaseTarget,
        deferred: CompletableDeferred<Entry?>,
    ): Result<SshLease> {
        val key = target.leaseKey
        try {
            val session = boundedConnect(target).getOrElse { error ->
                // Connect failed (including a bounded handshake timeout): clear
                // the in-flight slot, retract the optimistic Connecting hint, and
                // wake any awaiters so they fall back to their own dial rather
                // than blocking forever.
                mutex.withLock {
                    if (inFlightConnects[key] === deferred) inFlightConnects.remove(key)
                    retractConnectingHintLocked(key)
                }
                deferred.complete(null)
                return Result.failure(error)
            }
            return mutex.withLock {
                if (inFlightConnects[key] === deferred) inFlightConnects.remove(key)
                if (closed) {
                    runCatching { session.close() }
                    retractConnectingHintLocked(key)
                    deferred.complete(null)
                    return@withLock Result.failure(SshLeaseManagerClosedException())
                }
                val raced = entries[key]
                if (raced != null && raced.session.isConnected) {
                    // A live entry appeared for this key while we were dialing
                    // (e.g. a different code path acquired directly). Drop our
                    // redundant transport and reuse the live one; awaiters reuse
                    // it too via the deferred.
                    runCatching { session.close() }
                    raced.closeJob?.cancel()
                    raced.closeJob = null
                    raced.idleSinceMillis = null
                    raced.refCount += 1
                    emitStateLocked(key, SshLeaseConnectionState.Connected)
                    deferred.complete(raced)
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
                    deferred.complete(entry)
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
        } finally {
            // Cancellation (or any non-local exit) must never strand the
            // in-flight slot or leave awaiters blocked on a deferred that
            // would never complete. Clear our slot and wake awaiters; they
            // fall back to their own dial. No-op when the success/failure
            // paths above already completed the deferred.
            if (!deferred.isCompleted) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlightConnects[key] === deferred) inFlightConnects.remove(key)
                        retractConnectingHintLocked(key)
                    }
                }
                deferred.complete(null)
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
     * read has none. We run the dial as a CHILD coroutine and, on bound expiry,
     * CANCEL that child. Cancelling it propagates into
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
     * the acquire itself still flows straight through (the child is cancelled
     * with the parent), preserving the #620 coalescing cleanup contract.
     */
    private suspend fun boundedConnect(target: SshLeaseTarget): Result<SshSession> =
        withContext(connectTimeoutContext) {
            // Dial as a child of this withContext scope. The connector already
            // returns Result, so a normal connect failure is a value, not a
            // thrown child failure; a defensively caught throw is folded into a
            // failure Result too so a misbehaving connector can never crash the
            // lease scope.
            val dial = async {
                try {
                    connector.connect(target)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce // never swallow cancellation
                } catch (t: Throwable) {
                    // A misbehaving connector that THROWS instead of returning a
                    // failure Result must not crash the lease scope.
                    Result.failure(t)
                }
            }
            withTimeoutOrNull(connectTimeoutMillis) { dial.await() }
                ?: run {
                    // Stop awaiting the wedged handshake so this coroutine can
                    // resume; cancelling the child fires
                    // SshConnection.connect's invokeOnCancellation, which
                    // disconnects the half-open transport and unparks the
                    // blocking read. NonCancellable + cancelAndJoin guards the
                    // cleanup so the disconnect actually runs and the child is
                    // fully settled (never left dangling) before we return,
                    // even if the acquire itself is racing cancellation.
                    withContext(NonCancellable) { dial.cancelAndJoin() }
                    Result.failure(SshLeaseConnectTimeoutException(target.leaseKey, connectTimeoutMillis))
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
    private fun retractConnectingHintLocked(key: SshLeaseKey) {
        if (entries[key]?.session?.isConnected == true) return
        if (inFlightConnects.containsKey(key)) return
        emitStateLocked(
            key = key,
            state = SshLeaseConnectionState.Closed,
            closeReason = SshLeaseCloseReason.Disconnected,
        )
    }

    private sealed interface AcquireDecision {
        data class Reuse(val entry: Entry) : AcquireDecision
        data class Await(val deferred: CompletableDeferred<Entry?>) : AcquireDecision
        data class Own(val deferred: CompletableDeferred<Entry?>) : AcquireDecision
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
            entries[key]?.session?.isConnected == true
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
            if (entries[key]?.session?.isConnected == true) return@withLock true
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
        val pendingToWake = mutableListOf<CompletableDeferred<Entry?>>()
        runCatching {
            kotlinx.coroutines.runBlocking {
                mutex.withLock {
                    closed = true
                    toClose += entries.values
                    entries.clear()
                    // Issue #620: wake any acquires parked on an in-flight
                    // connect so they observe `closed` and fail fast instead of
                    // blocking forever on a deferred that would never complete.
                    pendingToWake += inFlightConnects.values
                    inFlightConnects.clear()
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
        pendingToWake.forEach { it.complete(null) }
        toClose.forEach { it.close() }
    }

    private suspend fun release(key: SshLeaseKey, entryId: Long) {
        val closeNow = mutex.withLock {
            val entry = entries[key] ?: return
            if (entry.id != entryId) return
            if (entry.refCount <= 0) return
            entry.refCount -= 1
            if (entry.refCount > 0) return
            if (!entry.session.isConnected || !processStarted || idleTtlMillis == 0L || maxIdleLeases == 0) {
                entries.remove(key)
                emitStateLocked(
                    key = key,
                    state = SshLeaseConnectionState.Closed,
                    closeReason = when {
                        !entry.session.isConnected -> SshLeaseCloseReason.Disconnected
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
        public const val DEFAULT_MAX_IDLE_LEASES: Int = 2

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
    val keepAliveSeconds: Int = SshConnection.DEFAULT_KEEP_ALIVE_SECONDS,
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
            keepAliveSeconds = target.keepAliveSeconds,
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

    public suspend fun release() {
        releaseMutex.withLock {
            if (released) return
            withContext(NonCancellable) {
                releaseAction(key, entryId)
            }
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
