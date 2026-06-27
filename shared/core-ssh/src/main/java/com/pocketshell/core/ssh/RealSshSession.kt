package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import net.schmizz.concurrent.Promise
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Session.Command
import net.schmizz.sshj.transport.TransportException
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * sshj-backed implementation of [SshSession].
 *
 * Internal to the module — callers only see the [SshSession] interface and
 * obtain instances via [SshConnection.connect].
 */
internal class RealSshSession(
    private val client: SSHClient,
    /**
     * Wall-clock ceiling for the Phase-2 exec read (#935 S4-2). Defaults to the
     * production [EXEC_READ_TIMEOUT_MS]; tests inject a short value so the
     * wedged-read bound can be exercised without a real 30s wait.
     */
    private val execReadTimeoutMs: Long = EXEC_READ_TIMEOUT_MS,
    /**
     * Per-block no-progress ceiling for upload copies. This is deliberately a
     * stall budget, not a whole-transfer budget, so large uploads over slow links
     * may run for longer than one window as long as bytes continue moving.
     */
    private val uploadStallTimeoutMs: Long = UPLOAD_STALL_TIMEOUT_MS,
    /**
     * Per-block no-progress ceiling for raw `cat` downloads. Tests inject a
     * short value to prove a wedged read is bounded without waiting a production
     * interval.
     */
    private val downloadStallTimeoutMs: Long = DOWNLOAD_STALL_TIMEOUT_MS,
) : SshSession {

    /**
     * Coroutine scope owning any background work (e.g. [tail] jobs). Closed
     * when the session is [close]d so all child jobs cancel deterministically.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Independent teardown scope for best-effort channel closes and keepalive-dead
     * transport teardown. It deliberately outlives [scope] until [close] finishes:
     * cancellation handlers often run while the session scope is being torn down,
     * and launching those channel-close writes on the same scope lets
     * `scope.cancel()` erase the cleanup before it reaches the dispatcher.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single-writer dispatch owner for this connection's transport (issue
     * #847 / #766 slice 1). EVERY transport-touching write + channel-lifecycle
     * operation — exec channel open/command/close, the `-CC` shell's stdin
     * write / resize / close, tail channel open/close, upload/download channel
     * open/close, and the final `disconnect()` — funnels through here so it
     * runs strictly one-at-a-time on one thread, in submission order, with no
     * op dispatched once teardown is enqueued.
     *
     * This kills the `Connection corrupted` desync (a channel open or command
     * write racing a KEX/rekey boundary or `die()` while another owner churns
     * exec channels on the SAME transport). Long-lived READS (the `-CC` reader
     * loop, `tail -F` line reads, `cat` download streaming) run OUTSIDE the
     * dispatcher — only the open/write/close packets that advance the encoder
     * sequence counter are serialised — so concurrent reads don't regress
     * connect/enumeration latency.
     */
    private val dispatcher = TransportDispatcher()

    /**
     * Monotonic ([System.nanoTime]) timestamp of the most recent inbound
     * transport activity — bumped on a successful keepalive reply (issue #945)
     * AND on ANY decoded application bytes the server sends back over the
     * transport: the live `-CC` control-mode reader's output, an exec/list/cat
     * round-trip's stdout/stderr, and each `tail -F` line (issue #974). The
     * keepalive loop reads this to SKIP a ping when the link produced server
     * bytes within the last interval (OpenSSH reset-on-server-traffic
     * semantics), so a busy link is self-evidently alive and we never ping —
     * nor tear down — a channel that is actively streaming.
     *
     * ## Issue #974 — honour the contract: live data proves the transport alive
     *
     * Before #974 this was bumped at EXACTLY ONE site — only on a keepalive
     * global-request reply ([sendKeepAlive]) — even though the contract docstring
     * ([TransportKeepAlive.KeepAliveIo.lastInboundActivityNanos]) promised it
     * tracks "any bytes from the server." That gap meant live `-CC` data did NOT
     * count as proof of life: on a stable-but-jittery Wi-Fi link, a few
     * delayed/missed 30s keepalive replies (power-save / bufferbloat straddling
     * three ping windows) could trip the keepalive's `countMax` death budget and
     * tear a link the user saw as perfectly stable — WHILE the `-CC` reader was
     * still delivering bytes. And because #964's
     * [isTransportProvenAliveWithinKeepAliveWindow] reads the SAME field, the
     * `LivenessProbe` deferral collapsed at the same instant. Recording inbound
     * data here closes both: real server bytes legitimately reset the miss
     * counter and keep [isTransportProvenAliveWithinKeepAliveWindow] true, so a
     * link with live data is never spuriously declared dead.
     *
     * Bound to DECODED application bytes from the server (the channel streams
     * sshj already decrypted/decoded), NOT raw socket reads — a genuinely
     * half-open peer that sends no bytes still produces no activity here, so the
     * keepalive's own ride-through budget still catches a truly-dead link
     * promptly (the fix must not make a dead link look alive forever).
     */
    @Volatile
    private var lastInboundActivityNanos: Long = System.nanoTime()

    /**
     * Monotonic timestamp of the most recent outbound upload payload bytes. This
     * mirrors [lastInboundActivityNanos] for the high-volume client-to-server
     * path: an upload that is steadily writing payload is active even when the
     * server is quiet until EOF.
     */
    @Volatile
    private var lastOutboundActivityNanos: Long = System.nanoTime()

    /**
     * Record inbound transport activity (issue #974). Called from EVERY path
     * that reads decoded application bytes the server sent back — the `-CC`
     * shell reader, exec/list/cat round-trips, and `tail -F` lines — so live
     * data proves the transport alive to the keepalive loop's reset-on-inbound
     * shortcut and to #964's [isTransportProvenAliveWithinKeepAliveWindow]
     * deferral oracle, exactly as the [TransportKeepAlive.KeepAliveIo] contract
     * promises. Cheap volatile store, safe to call from any reader thread.
     */
    private fun recordInboundActivity() {
        lastInboundActivityNanos = System.nanoTime()
    }

    /**
     * Record client-to-server payload activity for upload streaming. Kept
     * separate from inbound keepalive proof because outbound bytes alone do not
     * prove the server can still reply, but they do prove the upload copy loop is
     * not stalled.
     */
    private fun recordOutboundActivity() {
        lastOutboundActivityNanos = System.nanoTime()
    }

    /**
     * Issue #974 — `core-ssh`-internal accessor on the raw inbound-activity
     * timestamp so [KeepAliveIntegrationTest] can drive a synthetic
     * [TransportKeepAlive] off the SAME field the production keepalive reads,
     * proving that live `-CC`/exec data bumps it (the reproduce-first gate). Not
     * part of the public [SshSession] surface — production code uses the
     * keepalive loop + [isTransportProvenAliveWithinKeepAliveWindow] instead.
     */
    internal fun lastInboundActivityNanosForTest(): Long = lastInboundActivityNanos

    internal fun lastOutboundActivityNanosForTest(): Long = lastOutboundActivityNanos

    /**
     * Issue #985/#983 — single-flight guard for the keepalive reply wait. Holds
     * the throwaway [awaitKeepAliveReply] `retrieve()` coroutine launched for the
     * most recent ping that has NOT yet completed (the reply has not landed and the
     * transport has not errored it out). [sendKeepAlive] skips launching a SECOND
     * `retrieve()` while a prior one is still pending — a still-outstanding reply is
     * "not yet a fresh confirmation," so we wait on the EXISTING job rather than
     * stacking an unbounded queue of `retrieve()` coroutines on a slow/dead link
     * (bounded job growth: at most one pending reply job at a time).
     *
     * Crucially this guard ONLY suppresses launching a DUPLICATE `retrieve()`; it
     * does NOT suppress miss accounting. On a genuinely dead peer the pending job
     * never completes, so [sendKeepAlive] must STILL return `false` (a miss) once
     * the local no-activity budget elapses — otherwise the 90s `onKeepAliveDead`
     * teardown would never fire (see [awaitKeepAliveReply]).
     */
    private val keepAliveSingleFlightMutex = Mutex()
    private var pendingKeepAliveReply: Job? = null

    /**
     * Why this session's transport went down (issue #969 — reconnect
     * observability). Flipped to [SshSessionCloseCause.KeepaliveDead] by the
     * keepalive watchdog ([TransportKeepAlive.KeepAliveIo.onKeepAliveDead]) the
     * instant it declares the peer dead — BEFORE [close] runs — so the
     * [SshLeaseManager] reads it when the now-disconnected session surfaces a
     * lease `Closed` event and stamps `keepalive_dead` instead of an anonymous
     * disconnect. Pass-through state only; core-ssh never reaches into the app.
     */
    @Volatile
    private var transportCloseCause: SshSessionCloseCause = SshSessionCloseCause.Unknown

    override val closeCause: SshSessionCloseCause
        get() = transportCloseCause

    /**
     * Issue #945 — the always-on, dispatcher-serialized SSH transport keepalive
     * (the real "stays up like Terminus" fix). It is the SAFE successor to sshj's
     * removed `KeepAliveRunner` background writer (#847): every keepalive packet
     * is sent through [dispatcher] (`dispatcher.run`), so it is FIFO-serialized
     * against every channel open / `-CC` write / exec and can never race a
     * KEX/rekey boundary the way the un-ownable background thread did. On
     * [TransportKeepAlive.DEFAULT_COUNT_MAX] consecutive misses it closes the dead
     * transport so the existing reader-EOF / reconnect machinery surfaces the
     * drop and recovers (the SAME single recovery entrypoint the app-level
     * `LivenessProbe` uses — never a second reconnect writer).
     */
    private val keepAlive = TransportKeepAlive(
        io = object : TransportKeepAlive.KeepAliveIo {
            override fun isAlive(): Boolean = isConnected
            override fun lastInboundActivityNanos(): Long = lastInboundActivityNanos
            override suspend fun sendKeepAlive(): Boolean = this@RealSshSession.sendKeepAlive()
            override fun onKeepAliveDead(consecutiveMisses: Int) {
                // Issue #969: NAME the cause BEFORE the close so the lease layer
                // can read it when the now-disconnected session surfaces a
                // `Closed` event. A keepalive-driven drop is a proactive
                // silent-drop detection, not an anonymous disconnect.
                transportCloseCause = SshSessionCloseCause.KeepaliveDead
                KEEPALIVE_LOGGER.log(
                    Level.INFO,
                    "[$KEEPALIVE_LOG_TAG] transport keepalive declared the peer dead after " +
                        "$consecutiveMisses consecutive misses; closing the dead transport so the " +
                        "reconnect machinery recovers",
                )
                // Close the dead transport outside the session scope: close()
                // cancels that scope as part of teardown, so re-entering through
                // it can cancel the child close before the dispatcher drains.
                launchTeardown {
                    runCatching { close() }
                }
            }
        },
        // Issue #970: timing knobs are read from the test-override seam so the
        // realistic-wifi stability gate (the durable #964 proof) can shorten the
        // keepalive window deterministically — keeping the inbound-activity
        // timestamp fresh across a long jittery-but-live hold. Production keeps
        // the 30s / 3 defaults (the override is null unless a test set it).
        intervalMs = KeepAliveTestOverride.intervalMs(),
        countMax = KeepAliveTestOverride.countMax(),
        log = { msg -> KEEPALIVE_LOGGER.log(Level.FINE, "[$KEEPALIVE_LOG_TAG] $msg") },
    )

    init {
        // Start the always-on transport keepalive immediately. Under D21 the app
        // backgrounds and tmux holds state remotely, so a backgrounded transport
        // is intentionally torn down and the keepalive loop ends with it (its IO
        // `isAlive()` reads the live transport); the loop's value is the
        // FOREGROUNDED-but-flaky window — congested/bufferbloat/train-Wi-Fi links
        // and the transition windows — where it absorbs a transient gap the way
        // Terminus does, without the `-CC` `refresh-client` contention.
        keepAlive.start(scope)
    }

    override val isConnected: Boolean
        get() = !dispatcher.isClosed && client.isConnected && client.isAuthenticated

    /**
     * Issue #964 — the transport-liveness oracle the app-level `LivenessProbe`
     * defers to. True while the keepalive ([TransportKeepAlive]) has seen INBOUND
     * activity (its reply bumps [lastInboundActivityNanos]) within its
     * [TransportKeepAlive.RIDE_THROUGH_BUDGET_MS] window — i.e. the link is
     * provably alive at the transport layer, so the probe must not force a redial.
     * Once the transport is genuinely dead the keepalive stops bumping the
     * timestamp, this ages out past the ride-through window and returns `false`,
     * and the keepalive's own ride-through budget closes the dead transport — one
     * coherent liveness budget, not two competing ones.
     */
    override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean {
        if (!isConnected) return false
        val sinceActivityNanos = System.nanoTime() - lastInboundActivityNanos
        val rideThroughNanos = TransportKeepAlive.RIDE_THROUGH_BUDGET_MS * 1_000_000L
        return sinceActivityNanos in 0 until rideThroughNanos
    }

    override suspend fun sendKeepAlive(): Boolean {
        if (!isConnected) return false

        // Single-flight must gate the WIRE SEND, not just the reply observer.
        // If a prior keepalive promise is still pending, sending another global
        // request would enqueue a second sshj promise behind the unresolved one.
        // Count this tick against the same bounded no-activity budget, but do
        // not put a new packet on the transport.
        val marker = Job()
        val decision = keepAliveSingleFlightMutex.withLock {
            val pending = pendingKeepAliveReply
            if (pending != null && !pending.isCompleted) {
                KeepAliveSendDecision.AwaitExisting(lastInboundActivityNanos)
            } else {
                pendingKeepAliveReply = marker
                KeepAliveSendDecision.Send(marker, lastInboundActivityNanos)
            }
        }
        if (decision is KeepAliveSendDecision.AwaitExisting) {
            return awaitKeepAliveActivity(decision.before)
        }
        decision as KeepAliveSendDecision.Send

        // Issue #983 — split the op so the single-writer mutex is held ONLY for the
        // wire write, never for the multi-second reply wait. The
        // `keepalive@openssh.com` global request (wantReply=true) is the only
        // transport-mutating step here; sshj creates+enqueues the reply Promise into
        // its FIFO `globalReqPromises` ATOMICALLY with the write, under sshj's own
        // lock. OpenSSH does not implement the request type, so it answers
        // `SSH_MSG_REQUEST_FAILURE` — which still proves the peer alive (#945).
        val promise: Promise<SSHPacket, ConnectionException> =
            runCatching {
                dispatcher.run {
                    if (!isConnected) {
                        null
                    } else {
                        client.connection.sendGlobalRequest(
                            KEEPALIVE_REQUEST_NAME,
                            /* wantReply = */ true,
                            EMPTY_PAYLOAD,
                        )
                    }
                }
            }.getOrElse { t ->
                if (t is CancellationException) throw t
                // The send itself (write / dispatcher) failed. A REQUEST_FAILURE that
                // escaped here still proves the peer answered; otherwise it is a miss.
                clearKeepAliveMarker(decision.marker)
                return isKeepAliveServerAnswered(t)
            } ?: run {
                clearKeepAliveMarker(decision.marker)
                return false
            }

        // Issue #985 — confirm the reply OUTSIDE the mutex with a NON-ORPHANING wait.
        return awaitKeepAliveReply(promise, decision.marker, decision.before)
    }

    /**
     * Issue #985 + #983 — await the keepalive reply without ever orphaning the sshj
     * promise and without holding the transport dispatcher mutex.
     *
     * sshj's `Promise.tryRetrieve(timeout)` returns `null` on timeout but does NOT
     * remove the promise from sshj's FIFO `globalReqPromises` queue (sshj exposes no
     * per-promise cancel). So a single reply slower than a BOUNDED `tryRetrieve`
     * budget would abandon a promise at the queue HEAD, every subsequent reply would
     * be delivered to the wrong (already-abandoned) promise, and every keepalive
     * thereafter would be a structural miss → `onKeepAliveDead` tears a live
     * transport. That is the #985 self-corruption that never self-heals.
     *
     * The fix: launch an UNBOUNDED `promise.retrieve()` on a throwaway coroutine on
     * the session [scope]. Because the wait is unbounded, the promise is ALWAYS
     * eventually polled by sshj's reader thread when its reply lands (even at t+30s)
     * — or woken with an error on `notifyError` (transport death) / cancelled when
     * the session [scope] is cancelled in [close]. So the queue slot is never
     * abandoned and the FIFO can never desync. A late reply always polls its OWN
     * promise.
     *
     * Liveness here is derived from [lastInboundActivityNanos] advancing (the reply,
     * via [recordInboundActivity] below, OR any reader byte), NOT from the bounded
     * wait — so the wait can be unbounded while the MISS decision stays bounded.
     *
     * Miss accounting (the correctness requirement): if the local
     * [KEEPALIVE_REPLY_TIMEOUT_MS] no-activity budget elapses with no inbound
     * activity, this returns `false` (a miss) REGARDLESS of whether the
     * `retrieve()` job is still pending. On a genuinely dead peer the job blocks
     * forever, so treating "pending" as "not a miss" would silently break dead-peer
     * detection and the 90s teardown would never fire. The single-flight guard
     * ([pendingKeepAliveReply]) suppresses duplicate wire requests/retrieves,
     * never the miss.
     */
    private suspend fun awaitKeepAliveReply(
        promise: Promise<SSHPacket, ConnectionException>,
        marker: CompletableJob,
        before: Long,
    ): Boolean {
        val observer = scope.launch {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    // UNBOUNDED — never abandons the queue slot. Returns the
                    // REQUEST_SUCCESS packet, or throws the REQUEST_FAILURE
                    // ConnectionException (both prove the peer answered), or
                    // throws a transport-death error / is interrupted on
                    // scope-cancel (close()/notifyError).
                    promise.retrieve()
                }
            }.onSuccess {
                recordInboundActivity()
            }.onFailure { t ->
                if (isKeepAliveServerAnswered(t)) recordInboundActivity()
            }
        }
        keepAliveSingleFlightMutex.withLock {
            if (pendingKeepAliveReply === marker) {
                pendingKeepAliveReply = observer
            }
        }
        marker.complete()

        return awaitKeepAliveActivity(before)
    }

    private suspend fun clearKeepAliveMarker(marker: CompletableJob) {
        keepAliveSingleFlightMutex.withLock {
            if (pendingKeepAliveReply === marker) {
                pendingKeepAliveReply = null
            }
        }
        marker.complete()
    }

    private sealed interface KeepAliveSendDecision {
        data class Send(val marker: CompletableJob, val before: Long) : KeepAliveSendDecision
        data class AwaitExisting(val before: Long) : KeepAliveSendDecision
    }

    private suspend fun awaitKeepAliveActivity(before: Long): Boolean {
        // Locally wait up to KEEPALIVE_REPLY_TIMEOUT_MS for the inbound-activity
        // timestamp to advance (a reply OR any reader byte). "No inbound activity
        // within the budget" IS the liveness question, and it leaves the promise
        // intact — the late reply is delivered to its OWN promise, not the next one.
        val deadline = System.nanoTime() + KEEPALIVE_REPLY_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (lastInboundActivityNanos != before) return true
            if (!isConnected) return false
            delay(KEEPALIVE_POLL_MS)
        }
        // A miss — but the orphan-free retrieve job is STILL pending, so the late
        // reply (if any) is delivered to its OWN promise. Returning false here even
        // while the job is pending is REQUIRED so a genuinely dead peer is declared
        // dead within the keepalive budget (it never bumps the timestamp).
        return false
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun exec(command: String): ExecResult {
        val callerJob = currentCoroutineContext()[Job]
        val sessionChannelRef = AtomicReference<Session?>()
        val cmdRef = AtomicReference<Command?>()
        val cancelHandle = callerJob?.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause != null) {
                // Channel close is itself a transport write — serialise it
                // through the dispatcher rather than racing the wire from the
                // cancellation thread.
                launchTeardown {
                    closeCommandAndSessionChannel(
                        command = cmdRef.get(),
                        sessionChannel = sessionChannelRef.get(),
                    )
                }
            }
        }
        return try {
            // Phase 1 — open the channel + send the command, serialised against
            // every other transport op (the corruption-prone packets).
            val liveCommand = dispatcher.run {
                ensureConnected()
                val sessionChannel = try {
                    client.startSession()
                } catch (t: Throwable) {
                    throw SshException("Failed to open exec channel for `$command`: ${t.message}", t)
                }
                sessionChannelRef.set(sessionChannel)
                val cmd = try {
                    sessionChannel.exec(command)
                } catch (t: Throwable) {
                    runCatching { sessionChannel.close() }
                    sessionChannelRef.set(null)
                    throw SshException("Failed to start exec channel for `$command`: ${t.message}", t)
                }
                cmdRef.set(cmd)
                cmd
            }
            // Phase 2 — read stdout/stderr to EOF OUTSIDE the dispatcher so a
            // slow command never wedges the `-CC` write or other execs.
            //
            // #935 S4-2: the blocking `readBytes()`/`join()` were UNBOUNDED — on
            // a half-open / wedged transport (no FIN/RST) the JDK read parks
            // forever, hanging the calling coroutine (and any caller that didn't
            // wrap us in its own timeout — six gateways did not).
            //
            // The bound is a REAL wall-clock watchdog ([WallClockCeiling]), NOT a
            // coroutine `withTimeout`/`withTimeoutOrNull` (issue #940 / the #937
            // regression). `withTimeout` reads the delay source from the CALLER's
            // coroutine context — under `runTest`'s virtual, auto-advancing clock
            // (every `:shared:core-ssh:integrationTest`) the ceiling fires
            // INSTANTLY in virtual time and interrupts a HEALTHY live sshj read,
            // aborting it as `InterruptedException` (the 13/21 integration-suite
            // break). The wall-clock watchdog interrupts the worker thread only
            // after [execReadTimeoutMs] of REAL elapsed time, identically in
            // production and under any test scheduler, so a healthy read is never
            // cut short while a genuinely-wedged read is still reclaimed. The body
            // runs inside `runInterruptible(Dispatchers.IO)` so the watchdog's
            // `Thread.interrupt()` actually unparks the blocking JDK read.
            //
            // On a real timeout the watchdog interrupt makes the read throw; that
            // is mapped to a clear, RETRYABLE [SshExecTimeoutException], and we
            // then CLOSE the session (lease pool self-heals — a now-disconnected
            // session is discarded + re-dialed on next acquire). This is the SAME
            // close-on-timeout intent the three bespoke gateway wraps
            // (`FolderListGateway.execBounded`, `TreeRemoteSource`,
            // `AgentKindRemoteSource`) implement per-caller — pulled down to the
            // `exec` boundary so EVERY caller inherits it (D22 hard-cut), now with
            // the wall-clock mechanism #940 established for the dispatcher.
            try {
                runInterruptible(Dispatchers.IO) {
                    WallClockCeiling.runUnderWallClockCeiling(
                        timeoutMs = execReadTimeoutMs,
                        onTimeout = { cause -> SshExecTimeoutException(command, execReadTimeoutMs, cause) },
                    ) {
                        val output = readExecOutputConcurrently(liveCommand)
                        liveCommand.join()
                        // Issue #974: a completed exec round-trip is decoded server
                        // bytes — proof the transport is alive. Record it so a busy
                        // exec workload keeps the keepalive's inbound-activity
                        // timestamp fresh (honouring the contract).
                        recordInboundActivity()
                        // sshj returns null exitStatus when the server didn't send
                        // one (e.g. signal-killed). Map to -1 so the caller can
                        // still tell it wasn't a clean 0.
                        val exitCode = liveCommand.exitStatus ?: -1
                        ExecResult(stdout = output.stdout, stderr = output.stderr, exitCode = exitCode)
                    }
                }
            } catch (timeout: SshExecTimeoutException) {
                EXEC_LOGGER.warning(
                    "exec read wedged >${execReadTimeoutMs}ms (real wall-clock); closing wedged " +
                        "session + surfacing retryable SshExecTimeoutException. cmd=${command.takeLast(48)}",
                )
                // CLOSE the session to tear down the transport so the lease pool
                // discards the corpse (the watchdog already interrupted+unparked
                // the read; this also frees the channel deterministically).
                // NonCancellable so a cancelled/timed-out coroutine still closes.
                withContext(NonCancellable) {
                    runCatching { close() }
                }
                throw timeout
            }
        } finally {
            cancelHandle?.dispose()
            // Phase 3 — close the channel, serialised through the dispatcher
            // (channel close writes SSH_MSG_CHANNEL_CLOSE on the transport).
            //
            // Run under `NonCancellable`: when the caller cancels the exec (or
            // our own read-timeout bound unwinds), the coroutine is in the
            // cancelled state, so a plain `dispatcher.run` here would hit
            // `mutex.withLock`/`withContext` (cancellation points) and throw
            // `CancellationException` BEFORE running the close block — leaving the
            // channel teardown to race the async `scope.launch` cancel handler.
            // Wrapping in `NonCancellable` makes the channel close run
            // deterministically inside `exec` before it returns/throws, so the
            // SSH_MSG_CHANNEL_CLOSE is flushed and no orphaned channel leaks (the
            // `scope.launch` handler stays as belt-and-braces for the
            // can't-suspend-at-all path).
            withContext(NonCancellable) {
                closeCommandAndSessionChannel(
                    command = cmdRef.get(),
                    sessionChannel = sessionChannelRef.get(),
                )
            }
        }
    }

    private fun readExecOutputConcurrently(command: Command): ExecOutput {
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "pocketshell-exec-drain").apply { isDaemon = true }
        }
        val stdout = executor.submit<String> {
            command.inputStream.readBytesCapped(EXEC_STREAM_MAX_BYTES, "stdout")
                .toString(Charsets.UTF_8)
        }
        val stderr = executor.submit<String> {
            command.errorStream.readBytesCapped(EXEC_STREAM_MAX_BYTES, "stderr")
                .toString(Charsets.UTF_8)
        }
        return try {
            ExecOutput(
                stdout = awaitExecDrain(stdout),
                stderr = awaitExecDrain(stderr),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitExecDrain(future: Future<String>): String =
        try {
            future.get()
        } catch (e: ExecutionException) {
            val cause = e.cause
            when (cause) {
                is SshException -> throw cause
                is RuntimeException -> throw cause
                is Error -> throw cause
                null -> throw e
                else -> throw IOException("exec stream drain failed: ${cause.message}", cause)
            }
        }

    private fun InputStream.readBytesCapped(maxBytes: Int, streamName: String): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (total + read > maxBytes) {
                throw SshException(
                    "SSH exec $streamName exceeded ${maxBytes} bytes; refusing to buffer unbounded output",
                )
            }
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private data class ExecOutput(
        val stdout: String,
        val stderr: String,
    )

    override fun tail(path: String, onLine: (String) -> Unit): Job =
        tail(path, fromLineExclusive = -1, onLine = onLine)

    @OptIn(InternalCoroutinesApi::class)
    override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job {
        // Each tail owns its own exec channel — running `tail -F` keeps the
        // channel open for the lifetime of the job. Cancelling the job
        // closes the channel via `Command.close()` which signals the remote
        // tail to exit.
        return scope.launch {
            // Issue #621: the connectivity check must run INSIDE the launched
            // coroutine, not synchronously before it. A `startAgentConversationForPane`
            // crash (app v0.3.29) was traced to `tail()` calling `ensureConnected()`
            // on the caller's thread over an already-dead SSH session, throwing
            // `SshException("SSH session is not connected")` straight into
            // `AgentConversationRepository.tailEventsFromLine` / the main thread.
            // A silently-dead transport (sshj's `isConnected` lies until the 60s
            // keepalive trips) is an ordinary network-loss event, NOT a crash:
            // the reconnect state machine relaunches the tail on a fresh session
            // after reattach. Handle it like the `startSession()` transport-drop
            // below — log it as recoverable and end the tail job cleanly — so it
            // never reaches the crash reporter even if a caller forgets to wrap
            // the launch in a try/catch.
            if (!isConnected) {
                logTailRecoverableFailure(path, SshException("SSH session is not connected"))
                return@launch
            }
            val coroutineJob = currentCoroutineContext()[Job]
            // Issue #239 — agent-log tail must NOT propagate transport
            // failures to the coroutine root.
            //
            // The v0.2.8 maintainer device captured a crash where the
            // remote SSH socket aborted mid-tail (`Software caused
            // connection abort`). `client.startSession()` returned with
            // a `ConnectionException` (which extends `SSHException` /
            // `IOException`), the existing wrap turned it into an
            // `SshException`, and that propagated to the coroutine root
            // on the supervisor-scoped `launch`. The default uncaught
            // exception handler then routed it to `CrashReporter` —
            // turning an ordinary network-loss event into a crash
            // report.
            //
            // Per D21 (no background work) and the orthogonal reconnect
            // state machine in `TmuxSessionViewModel` (#145 + #173):
            // when the transport drops, the tmux event-loop coroutine
            // observes the same drop through its producer job and
            // routes through `_disconnected` -> reconnect; on
            // reconnect, `reconcilePanes` calls
            // `startAgentConversationForPane` again and a fresh
            // `tail()` is launched on the new session. The tail's own
            // job just needs to end cleanly so it doesn't cascade into
            // the crash reporter.
            //
            // Catch shape:
            //  - `SshException`: anything we wrapped ourselves
            //    (`Failed to start tail session ...` and friends).
            //  - `IOException`: covers `SocketException`,
            //    sshj's `SSHException` family (`TransportException`,
            //    `ConnectionException`, all extend `SSHException` which
            //    extends `IOException`), and any other I/O failure
            //    reading from the channel stream.
            //  - `CancellationException` is deliberately NOT caught:
            //    coroutine cancellation must always propagate so the
            //    structured-concurrency contract is preserved (Job
            //    cancellation by the caller still tears down the
            //    channel via the `invokeOnCompletion` handler below).
            //  - Genuine programming errors (NPE, IAE, ...) still
            //    propagate to the supervisor scope so they aren't
            //    silently swallowed.
            // Channel open + the `tail -F` exec write are transport-mutating
            // packets — serialise them through the dispatcher (issue #847). The
            // long-lived line READ below runs OUTSIDE the dispatcher so the
            // follow loop never wedges the `-CC` write or other ops.
            val sessionChannelRef = AtomicReference<Session?>()
            var cmd: Command? = null
            val cancelHandle = coroutineJob?.invokeOnCompletion(onCancelling = true) {
                // Close is a transport write — funnel through the dispatcher.
                launchTeardown {
                    closeCommandAndSessionChannel(
                        command = cmd,
                        sessionChannel = sessionChannelRef.get(),
                    )
                }
            }
            try {
                // -F follows by name, surviving rotation. Quote the path so
                // weird filenames don't break the shell parsing.
                val quoted = shellSingleQuote(path)
                val lineArg = if (fromLineExclusive >= 0) {
                    "-n +${fromLineExclusive + 1}"
                } else {
                    "-n 0"
                }
                cmd = try {
                    dispatcher.run {
                        val channel = client.startSession()
                        sessionChannelRef.set(channel)
                        channel.exec("tail -F $lineArg $quoted")
                    }
                } catch (e: SshException) {
                    logTailRecoverableFailure(path, e)
                    return@launch
                } catch (e: IOException) {
                    // Channel-open / exec race against transport drop —
                    // same recoverable-disconnect story as the
                    // startSession() catch above.
                    logTailRecoverableFailure(path, e)
                    return@launch
                } catch (t: Throwable) {
                    throw SshException("Failed to start tail session for `$path`: ${t.message}", t)
                }
                BufferedReader(InputStreamReader(cmd!!.inputStream, Charsets.UTF_8)).use { reader ->
                    while (isActive) {
                        val line = try {
                            reader.readLine() ?: break
                        } catch (e: IOException) {
                            // Mid-stream socket abort. The
                            // `invokeOnCompletion` handler below will
                            // close the channel for us. Surface as a
                            // clean job exit, not a crash.
                            logTailRecoverableFailure(path, e)
                            return@launch
                        }
                        // Issue #974: a tail line is decoded server bytes — proof
                        // the transport is alive. Record it BEFORE the (possibly
                        // slow) onLine callback so a steadily-tailing agent log
                        // keeps the keepalive's inbound-activity timestamp fresh.
                        recordInboundActivity()
                        onLine(line)
                        // Suspend per-line so a cancelled tail job exits
                        // promptly even when the remote is gushing output.
                        yield()
                    }
                }
            } finally {
                cancelHandle?.dispose()
                val closeCmd = cmd
                val closeChannel = sessionChannelRef.get()
                withContext(NonCancellable) {
                    closeCommandAndSessionChannel(
                        command = closeCmd,
                        sessionChannel = closeChannel,
                    )
                }
            }
        }
    }

    /**
     * Diagnostic log when [tail] swallows a recoverable transport failure
     * (issue #239). Logged through `java.util.logging` (same channel as
     * [SshjTransportThreadGuard]) so the same swallowed-disconnect event
     * is visible in logcat under a stable, grep-able tag without pulling
     * `android.util.Log` into the shared module.
     */
    private fun logTailRecoverableFailure(path: String, cause: Throwable) {
        TAIL_LOGGER.log(
            Level.INFO,
            "[$TAIL_LOG_TAG] tail($path) ended on transport drop; reconnect path will resume on next attach: ${cause.javaClass.simpleName}: ${cause.message}",
        )
    }

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward {
        ensureConnected()
        return try {
            // Issue #980: the forward opens/closes one direct-tcpip channel per
            // accepted local connection — transport-mutating packets that MUST go
            // through the single-writer dispatcher (the #847-safe path the
            // keepalive / `-CC` / exec writes use), never straight off the raw
            // client from the accept loop / copy threads. We hand the forward a
            // dispatcher-backed channel factory instead of the SSHClient so it
            // physically cannot become a second un-serialised writer.
            RealSshPortForward(
                channels = dispatcherBackedChannelTransport(),
                remoteHost = remoteHost,
                remotePort = remotePort,
                localPort = localPort,
            )
        } catch (t: Throwable) {
            throw SshException(
                "Failed to open local port forward 127.0.0.1:$localPort -> $remoteHost:$remotePort: ${t.message}",
                t,
            )
        }
    }

    /**
     * Build the dispatcher-backed [PortForwardChannelTransport] for a forward
     * on THIS session's transport (issue #980). The channel open + close run
     * through [dispatcher], serialised against every other transport writer, so
     * the forward can never become the #847 second writer. The serialisation
     * logic lives in [DispatcherBackedChannelTransport] so it is unit-testable
     * without a live SSH transport; here we only bind the real sshj open/close.
     */
    private fun dispatcherBackedChannelTransport(): PortForwardChannelTransport =
        DispatcherBackedChannelTransport(
            dispatcher = dispatcher,
            open = { remoteHost, remotePort -> client.newDirectConnection(remoteHost, remotePort) },
            close = { channel -> channel.close() },
        )

    override fun startShell(): SshShell {
        ensureConnected()
        // Two-step open mirroring sshj's idiomatic interactive-shell
        // recipe: `startSession()` to get a session channel, allocate a
        // PTY advertising [INTERACTIVE_PTY_TERM] ("xterm-256color") at
        // [INTERACTIVE_PTY_INITIAL_COLUMNS]x[INTERACTIVE_PTY_INITIAL_ROWS]
        // (the on-device TerminalView resizes the remote PTY to the real
        // grid on first layout), then `startShell()` to bind the channel
        // to the user's login shell.
        //
        // Issue #106: this used to call `sessionChannel.allocateDefaultPTY()`,
        // which advertises `TERM=vt100` in sshj 0.40. Real interactive
        // agent CLIs (opencode, Codex, Claude Code) probe TERM at startup
        // and fall back to a degraded line-mode rendering when they see
        // vt100 — the prompt input drops to the bottom of the scrolling
        // shell instead of rendering inside their alternate-screen input
        // box. The same root cause was fixed for the proof-of-life shell
        // entry point in #102; this is the second SSH-shell entry point.
        // The two entry points are deliberately not refactored into a
        // shared helper here (out of scope per #106 non-goals); the
        // [INTERACTIVE_PTY_TERM] constant exists so both call sites are
        // grep-able and the chosen terminfo entry is reviewable in one
        // place.
        //
        // Failures at any of the three steps are wrapped in SshException
        // so callers don't have to know about sshj's exception hierarchy.
        // If `startShell` itself fails we close the half-opened session
        // channel before propagating, so we never leak a channel on error.
        // Channel open + PTY alloc + shell start are all transport-mutating
        // packets — serialise the whole open through the dispatcher (issue
        // #847) so it can't race an exec channel open / the liveness probe / a
        // KEX boundary on the same transport.
        return dispatcher.runBlockingDispatch {
            val sessionChannel = try {
                client.startSession()
            } catch (t: Throwable) {
                throw SshException("Failed to open SSH session channel for shell: ${t.message}", t)
            }
            try {
                sessionChannel.allocatePTY(
                    /* term = */ INTERACTIVE_PTY_TERM,
                    /* cols = */ INTERACTIVE_PTY_INITIAL_COLUMNS,
                    /* rows = */ INTERACTIVE_PTY_INITIAL_ROWS,
                    /* widthPx = */ 0,
                    /* heightPx = */ 0,
                    /* modes = */ emptyMap(),
                )
                val shell = sessionChannel.startShell()
                RealSshShell(
                    sessionChannel = sessionChannel,
                    shell = shell,
                    dispatcher = dispatcher,
                    // Issue #974: the `-CC` control-mode reader is the highest-rate
                    // inbound stream on a foregrounded session — every byte it
                    // decodes proves the transport alive, so record it as inbound
                    // activity (honouring the keepalive contract).
                    onInboundActivity = ::recordInboundActivity,
                )
            } catch (t: Throwable) {
                runCatching { sessionChannel.close() }
                throw SshException("Failed to start remote shell: ${t.message}", t)
            }
        }
    }

    override suspend fun uploadFile(file: File, remotePath: String): String =
        withContext(Dispatchers.IO) {
            ensureConnected()
            if (!file.exists()) {
                throw SshException("Local file does not exist: ${file.absolutePath}")
            }
            file.inputStream().use { input ->
                uploadStreamInternal(
                    input = input,
                    length = file.length(),
                    name = file.name,
                    remotePath = remotePath,
                )
            }
            remotePath
        }

    override suspend fun uploadStream(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String = withContext(Dispatchers.IO) {
        ensureConnected()
        uploadStreamInternal(input, length, name, remotePath)
        remotePath
    }

    override suspend fun listDirectory(
        remotePath: String,
        maxEntries: Int,
    ): RemoteListing = withContext(Dispatchers.IO) {
        ensureConnected()
        // Listing route: a structured `exec` over `find -maxdepth 1` + `stat`,
        // NOT SFTP. The Alpine fixtures (`tests/docker/Dockerfile.ssh`) and many
        // minimal OpenSSH servers ship `openssh-server` *without* the separate
        // `openssh-sftp-server` package, so `Subsystem sftp` points at a missing
        // binary and `newSFTPClient()` dies with "EOF while reading packet".
        // The same reasoning is why `downloadFile`/`uploadStream` use a `cat`
        // exec channel rather than SCP/SFTP — we only require a POSIX shell plus
        // `find`/`stat`, which are present on busybox and coreutils alike.
        val probe = exec(buildListDirCommand(remotePath, maxEntries))
        if (probe.exitCode != PROBE_EXIT_OK) {
            throw classifyListFailure(remotePath, probe)
        }
        parseListing(probe.stdout, remotePath, maxEntries)
    }

    override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray =
        withContext(Dispatchers.IO) {
            ensureConnected()
            // 1. Size + existence probe. A single shell command prints either
            //    the regular-file byte count or a `no file` sentinel. This
            //    lets us refuse a huge file *before* streaming any bytes, so
            //    a multi-gigabyte file never reaches the JVM heap.
            val probe = exec(buildSizeProbeCommand(remotePath))
            when (val size = parseSizeProbe(probe.stdout)) {
                SIZE_PROBE_NO_FILE -> throw SshFileNotFoundException(remotePath)
                SIZE_PROBE_UNPARSEABLE -> {
                    // Size probe failed unexpectedly (shell missing wc, weird
                    // output). Fall through to a capped streaming read rather
                    // than assuming the file is fine or missing — the read
                    // itself enforces the cap.
                }
                else -> if (size > maxBytes) {
                    throw SshFileTooLargeException(remotePath, size, maxBytes)
                }
            }
            // 2. Stream the raw bytes via `cat`, enforcing the cap a second
            //    time while reading (defence against TOCTOU growth / a remote
            //    that ignored the probe).
            readRemoteBytesCapped(remotePath, maxBytes)
        }

    /**
     * Stream the raw bytes of [remotePath] over an `exec` channel running
     * `cat`, aborting with [SshFileTooLargeException] if more than [maxBytes]
     * arrive. Binary-safe — reads from sshj's raw channel stream with no
     * charset round-trip.
     */
    private suspend fun readRemoteBytesCapped(remotePath: String, maxBytes: Long): ByteArray {
        // Channel open + `cat` exec are transport-mutating packets — serialise
        // through the dispatcher (issue #847). The capped streaming read below
        // runs OUTSIDE the dispatcher so a large file never wedges the `-CC`
        // write or other ops.
        val quoted = quoteRemotePathForShell(remotePath)
        val (sessionChannel, command) = dispatcher.run {
            val channel = try {
                client.startSession()
            } catch (t: Throwable) {
                throw SshException("Could not open session channel to read $remotePath: ${t.message}", t)
            }
            val cmd: Command = try {
                channel.exec("cat $quoted")
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw SshException("Could not start remote `cat` for $remotePath: ${t.message}", t)
            }
            channel to cmd
        }
        try {
            try {
                val bytes = try {
                    runInterruptible(Dispatchers.IO) {
                        readRemoteBytesCappedBlocking(
                            input = command.inputStream,
                            remotePath = remotePath,
                            maxBytes = maxBytes,
                        )
                    }
                } catch (stall: TransferStallTimeoutException) {
                    runCatching { dispatcher.run { runCatching { command.close() } } }
                    runCatching { dispatcher.run { runCatching { sessionChannel.close() } } }
                    throw SshException(
                        "Download of $remotePath stalled for ${stall.timeoutMs}ms during ${stall.operation}",
                        stall,
                    )
                }
                try {
                    runInterruptible(Dispatchers.IO) {
                        runTransferStepWithStallTimeout(
                            operation = "waiting for remote `cat` to exit",
                            timeoutMs = downloadStallTimeoutMs,
                        ) {
                            command.join()
                        }
                    }
                } catch (stall: TransferStallTimeoutException) {
                    runCatching { dispatcher.run { runCatching { command.close() } } }
                    runCatching { dispatcher.run { runCatching { sessionChannel.close() } } }
                    throw SshException(
                        "Download of $remotePath stalled for ${stall.timeoutMs}ms during ${stall.operation}",
                        stall,
                    )
                }
                val exit = command.exitStatus ?: -1
                if (exit != 0) {
                    val stderr = runCatching {
                        runInterruptible(Dispatchers.IO) {
                            readTransferStderrCappedBlocking(
                                input = command.errorStream,
                                operation = "reading remote `cat` stderr",
                                timeoutMs = downloadStallTimeoutMs,
                            )
                        }
                    }.getOrDefault("").trim()
                    // `cat` on a missing/unreadable file exits non-zero; map a
                    // "No such file" stderr to the friendly not-found type.
                    if (stderr.contains("No such file", ignoreCase = true)) {
                        throw SshFileNotFoundException(remotePath)
                    }
                    throw SshException("Remote `cat` exited with status $exit reading $remotePath: $stderr")
                }
                return bytes
            } finally {
                withContext(NonCancellable) {
                    runCatching { dispatcher.run { runCatching { command.close() } } }
                }
            }
        } catch (e: SshException) {
            throw e
        } catch (t: Throwable) {
            throw SshException("Reading $remotePath failed: ${t.message}", t)
        } finally {
            withContext(NonCancellable) {
                runCatching { dispatcher.run { runCatching { sessionChannel.close() } } }
            }
        }
    }

    private fun readRemoteBytesCappedBlocking(
        input: InputStream,
        remotePath: String,
        maxBytes: Long,
    ): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        input.use {
            while (true) {
                if (Thread.interrupted()) throw InterruptedException("SSH download interrupted")
                val read = runTransferStepWithStallTimeout(
                    operation = "reading remote file bytes",
                    timeoutMs = downloadStallTimeoutMs,
                ) {
                    it.read(chunk)
                }
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw SshFileTooLargeException(remotePath, -1, maxBytes)
                }
                buffer.write(chunk, 0, read)
                recordInboundActivity()
            }
        }
        return buffer.toByteArray()
    }

    /**
     * Stream-to-remote-file primitive shared by [uploadFile] and
     * [uploadStream]. Uploads ATOMICALLY (issue #930): the bytes stream via an
     * `exec` channel running `cat > <temp-path>` on the remote, the transferred
     * size is verified, and ONLY on a fully-successful transfer is the temp file
     * renamed (`mv`) onto [remotePath]. Any mid-stream drop / timeout / short
     * read therefore leaves the REAL attachment path untouched, never a 0-byte
     * or partial corrupt artifact at the destination.
     *
     * The previous design ran `cat > <final-path>` directly, which truncated the
     * destination to 0 bytes the instant the channel opened — so a disconnect
     * mid-transfer left a 0-byte file at the real path (the #928 D7 device
     * forensics: 9 zero-byte attachment files). That non-atomic path is gone.
     *
     * Why not SCP or SFTP? Both require an extra binary on the remote
     * (`scp` from `openssh-client`, `sftp-server` from
     * `openssh-sftp-server`). The Alpine-based Docker fixtures used
     * by the connected emulator tests ship the SSH server alone, and
     * minimal real-world servers often do too. The exec-channel +
     * `cat` approach only needs a POSIX shell, `cat`, `wc`, `mv` and `rm`,
     * which are universally present.
     *
     * Bounding (issue #930, folds in #928 D5 W-4): each blocking byte-copy step
     * and the final `join()` run under a real wall-clock no-progress ceiling, so
     * a wedged channel fails fast without imposing a whole-upload duration cap on
     * slow but progressing transfers.
     *
     * [length] is the declared content length. When known (>= 0) it is also
     * verified against the bytes the remote actually received before the rename,
     * so a truncated source (declares more than it emits) is rejected rather
     * than renamed as a short/corrupt file.
     */
    private suspend fun uploadStreamInternal(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ) {
        // Atomic temp sibling of the final path: `<final>.part-<rand>`. A
        // dropped/timed-out transfer corrupts only THIS temp name, never the
        // real attachment path. The random suffix avoids collisions between
        // concurrent retries of the same attachment.
        val tempRemotePath = remotePath + ".part-" + java.util.UUID.randomUUID().toString().take(8)
        try {
            val copied = streamToRemoteTemp(input, name, remotePath, tempRemotePath, length)

            // Integrity check BEFORE the rename: the bytes that actually landed
            // in the temp file must match what we copied, and — when the caller
            // declared a length — must match that too. A mismatch means a
            // truncated/short transfer; fail (and clean up) rather than promote
            // a corrupt file to the real path.
            verifyTempSizeOrThrow(
                name = name,
                remotePath = remotePath,
                tempRemotePath = tempRemotePath,
                copiedBytes = copied,
                declaredLength = length,
            )

            // Promote to the real path ONLY now that the full, verified bytes
            // are on disk. `mv` within the same directory/filesystem is atomic.
            val mv = exec(
                "mv -f ${shellSingleQuote(tempRemotePath)} ${shellSingleQuote(remotePath)}",
            )
            if (mv.exitCode != 0) {
                throw SshException(
                    "Could not finalise upload of $name to $remotePath " +
                        "(rename failed, exit ${mv.exitCode}): ${mv.stderr.trim()}",
                )
            }
        } catch (e: CancellationException) {
            // The coroutine context is cancelled here, so we cannot suspend on
            // it — detach the temp cleanup onto the session scope.
            cleanupTempDetached(tempRemotePath)
            throw e
        } catch (t: Throwable) {
            // ANY failure leaves the real path untouched; remove the temp file
            // SYNCHRONOUSLY (the context is still active on this path) so a
            // partial upload never accumulates as a stray artifact and the
            // removal is observable by the time we return to the caller.
            cleanupTempBestEffort(tempRemotePath)
            if (t is SshException) throw t
            throw SshException("Upload of $name to $remotePath failed: ${t.message}", t)
        }
    }

    /**
     * Stream [input] into the remote [tempRemotePath] via `cat > <temp>`.
     * Returns the number of bytes copied. Throws [SshException] on a transport
     * failure, a non-zero remote exit, or a no-progress stall — the caller
     * cleans up the temp file.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun streamToRemoteTemp(
        input: InputStream,
        name: String,
        remotePath: String,
        tempRemotePath: String,
        declaredLength: Long,
    ): Long {
        val coroutineJob = currentCoroutineContext()[Job]
        // Channel open + `cat >` exec are transport-mutating packets — serialise
        // through the dispatcher (issue #847). The byte copy below runs OUTSIDE
        // the dispatcher so a large upload never wedges the `-CC` write.
        val quoted = shellSingleQuote(tempRemotePath)
        var command: Command? = null
        val sessionChannel = dispatcher.run {
            val channel = try {
                client.startSession()
            } catch (t: Throwable) {
                throw SshException(
                    "Could not open session channel for upload of $name to $remotePath: ${t.message}",
                    t,
                )
            }
            command = try {
                channel.exec("cat > $quoted")
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw SshException(
                    "Could not start remote `cat` for upload of $name to $remotePath: ${t.message}",
                    t,
                )
            }
            channel
        }
        val cancelHandle = coroutineJob?.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause != null) {
                runCatching { input.close() }
                launchTeardown {
                    closeCommandAndSessionChannel(
                        command = command,
                        sessionChannel = sessionChannel,
                    )
                }
            }
        }
        try {
            // Issue #930 follow-up: bound upload by no-progress windows, not by a
            // whole-transfer wall clock. A large upload over a slow but moving
            // link may legitimately exceed 60s; only a blocking read/write/flush
            // or remote EOF wait that makes no progress for the stall budget is
            // failed. The watchdog is a real wall-clock interrupt, independent of
            // caller coroutine clocks.
            val copied = try {
                runInterruptible(Dispatchers.IO) {
                    val output = command!!.outputStream
                    val n = copyToRemoteBlocking(input, output, declaredLength)
                    runTransferStepWithStallTimeout(
                        operation = "closing upload output",
                        timeoutMs = uploadStallTimeoutMs,
                    ) {
                        output.close()
                    }
                    // `outputStream.close()` sends EOF on the channel. The remote
                    // `cat` exits on EOF; `join()` waits for it so we can read
                    // the exit code.
                    runTransferStepWithStallTimeout(
                        operation = "waiting for remote upload `cat` to exit",
                        timeoutMs = uploadStallTimeoutMs,
                    ) {
                        command!!.join()
                    }
                    n
                }
            } catch (stall: TransferStallTimeoutException) {
                runCatching { input.close() }
                runCatching { dispatcher.run { runCatching { command?.close() } } }
                runCatching { dispatcher.run { runCatching { sessionChannel.close() } } }
                throw SshException(
                    "Upload of $name to $remotePath stalled for " +
                        "${stall.timeoutMs}ms during ${stall.operation}",
                    stall,
                )
            }
            val exit = command!!.exitStatus ?: -1
            if (exit != 0) {
                val stderr = runCatching {
                    runInterruptible(Dispatchers.IO) {
                        readTransferStderrCappedBlocking(
                            input = command!!.errorStream,
                            operation = "reading remote upload `cat` stderr",
                            timeoutMs = uploadStallTimeoutMs,
                        )
                    }
                }.getOrDefault("")
                throw SshException(
                    "Remote `cat` exited with status $exit while writing $tempRemotePath: ${stderr.trim()}",
                )
            }
            return copied
        } finally {
            cancelHandle?.dispose()
            withContext(NonCancellable) {
                closeCommandAndSessionChannel(
                    command = command,
                    sessionChannel = sessionChannel,
                )
            }
        }
    }

    /**
     * Confirm the bytes that landed in [tempRemotePath] match what we copied (and
     * the caller-declared [declaredLength], when known). A mismatch is a
     * truncated/short transfer — throw so the temp file is cleaned up and never
     * promoted to [remotePath].
     */
    private suspend fun verifyTempSizeOrThrow(
        name: String,
        remotePath: String,
        tempRemotePath: String,
        copiedBytes: Long,
        declaredLength: Long,
    ) {
        val stat = exec(
            "wc -c < ${shellSingleQuote(tempRemotePath)} 2>/dev/null || echo MISSING",
        )
        val out = stat.stdout.trim()
        val actual = out.toLongOrNull()
        if (actual == null) {
            throw SshException(
                "Upload integrity check failed for $name -> $remotePath: " +
                    "could not stat temp file $tempRemotePath (got '$out')",
            )
        }
        if (actual != copiedBytes) {
            throw SshException(
                "Upload integrity check failed for $name -> $remotePath: " +
                    "remote received $actual bytes but $copiedBytes were sent",
            )
        }
        if (declaredLength >= 0 && actual != declaredLength) {
            throw SshException(
                "Upload integrity check failed for $name -> $remotePath: " +
                    "declared $declaredLength bytes but $actual were transferred " +
                    "(truncated/short source)",
            )
        }
    }

    /**
     * Synchronously remove a partial upload temp file on a failure whose
     * coroutine context is still active. Best-effort: never throws (a failed
     * `rm` must not mask the original upload error). Bounded by a short timeout
     * so a half-dead transport can't wedge the cleanup.
     */
    private suspend fun cleanupTempBestEffort(tempRemotePath: String) {
        runCatching {
            withTimeoutOrNull(UPLOAD_CLEANUP_TIMEOUT_MS) {
                exec("rm -f ${shellSingleQuote(tempRemotePath)}")
            }
        }
    }

    /**
     * Detached temp cleanup for the caller-cancellation path, where the current
     * coroutine context is already cancelled and cannot be suspended on. Runs on
     * the session scope so the partial upload is still removed.
     */
    private fun cleanupTempDetached(tempRemotePath: String) {
        runCatching {
            launchTeardown {
                runCatching { exec("rm -f ${shellSingleQuote(tempRemotePath)}") }
            }
        }
    }

    private fun launchTeardown(block: suspend () -> Unit) {
        teardownScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                block()
            }
        }
    }

    private suspend fun closeCommandAndSessionChannel(
        command: Command?,
        sessionChannel: Session?,
    ) {
        runCatching {
            dispatcher.run {
                runCatching { command?.close() }
                runCatching { sessionChannel?.close() }
            }
        }
    }

    /**
     * Blocking byte-copy from [input] to [output]. Runs inside
     * [runInterruptible] (issue #930), so cancellation interrupts this thread:
     * each loop checks [Thread.interrupted] and the blocking `read`/`write` JDK
     * calls themselves throw on interrupt. Each blocking step is also guarded by
     * a per-step real wall-clock stall budget; total upload duration is
     * intentionally unbounded while bytes continue moving.
     */
    private fun copyToRemoteBlocking(
        input: InputStream,
        output: OutputStream,
        declaredLength: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("SSH upload interrupted")
            val read = runTransferStepWithStallTimeout(
                operation = "reading upload input",
                timeoutMs = uploadStallTimeoutMs,
            ) {
                input.read(buffer)
            }
            if (read < 0) break
            if (declaredLength >= 0 && total + read > declaredLength) {
                throw SshException(
                    "Upload stream exceeded declared length: declared $declaredLength bytes " +
                        "but source produced more",
                )
            }
            if (Thread.interrupted()) throw InterruptedException("SSH upload interrupted")
            runTransferStepWithStallTimeout(
                operation = "writing upload bytes",
                timeoutMs = uploadStallTimeoutMs,
            ) {
                output.write(buffer, 0, read)
            }
            total += read
            recordOutboundActivity()
        }
        runTransferStepWithStallTimeout(
            operation = "flushing upload bytes",
            timeoutMs = uploadStallTimeoutMs,
        ) {
            output.flush()
        }
        return total
    }

    private fun readTransferStderrCappedBlocking(
        input: InputStream,
        operation: String,
        timeoutMs: Long,
    ): String {
        val buffer = ByteArray(4 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        input.use {
            while (total < TRANSFER_STDERR_MAX_BYTES) {
                if (Thread.interrupted()) throw InterruptedException("SSH transfer stderr read interrupted")
                val limit = minOf(buffer.size, TRANSFER_STDERR_MAX_BYTES - total)
                val read = runTransferStepWithStallTimeout(operation, timeoutMs) {
                    it.read(buffer, 0, limit)
                }
                if (read < 0) {
                    return out.toString(Charsets.UTF_8.name())
                }
                out.write(buffer, 0, read)
                total += read
            }
        }
        return out.toString(Charsets.UTF_8.name()) +
            "\n[stderr truncated after $TRANSFER_STDERR_MAX_BYTES bytes]"
    }

    private fun <T> runTransferStepWithStallTimeout(
        operation: String,
        timeoutMs: Long,
        block: () -> T,
    ): T = WallClockCeiling.runUnderWallClockCeiling(
        timeoutMs = timeoutMs,
        onTimeout = { cause -> TransferStallTimeoutException(operation, timeoutMs, cause) },
        block = block,
    )

    override fun close() {
        // Issue #945: stop the transport keepalive before tearing the scope down
        // so no keepalive op is submitted to a dispatcher that is about to close.
        keepAlive.stop()
        // Cancel active exec/tail/upload jobs before the dispatcher drain. Their
        // cancellation handlers enqueue best-effort channel closes on
        // teardownScope, which remains alive until close() finishes, so those
        // closes can run before the final transport disconnect.
        scope.cancel()
        // Issue #151 + #239: `close()` is idempotent and silent by
        // contract. The v0.2.7 crash report showed the original
        // narrow-catch race: a teardown-before-reattach left the
        // transport already half-disconnected (cancelled mid-flight from
        // the tmux event-loop coroutine in `TmuxSessionViewModel`), and
        // sshj's `SSHClient.disconnect()` threw `TransportException` with
        // `DisconnectReason.BY_APPLICATION` ("Disconnected") because it
        // tried to send a disconnect packet over a transport that had
        // already gone down. That was swallowed for the
        // `BY_APPLICATION` case only — leaving every other
        // `TransportException` reason able to crash on the
        // `ViewModel.onCleared` -> `closeCurrentConnection` ->
        // `RealSshSession.close()` cascade.
        //
        // v0.2.8 confirmed the narrow catch is still wrong shape: the
        // maintainer device hit twin crashes (A + B in issue #239)
        // during `onCleared`, again with `BY_APPLICATION`, again from
        // the supervisor-scoped IO coroutine root. The Android lifecycle
        // (activity destroy -> ViewModelStore.clear ->
        // `onCleared` on every ViewModel) is the canonical close path
        // under D21 (no background work), and any `TransportException`
        // surfacing from that path has nowhere useful to go — the
        // session is being torn down anyway and the caller has no
        // actionable recovery.
        //
        // Per the issue: "swallows `TransportException` with reason
        // `BY_APPLICATION` (and likely any TransportException — close
        // is idempotent)." We widen to the full `TransportException`
        // family so every teardown-time transport fault (KEX failure,
        // MAC error, unexpected protocol errors, half-closed transport)
        // is treated the same way: log and no-op, never propagate.
        // Genuine "transport blew up while connected" diagnostics still
        // surface through the regular read/write path (sshj raises the
        // same exception on the producer-coroutine boundary), so
        // swallowing here does not hide a live-connection fault.
        //
        // The outer best-effort catches preserve the idempotency
        // guarantee for non-TransportException teardown failures
        // (`IOException`, exotic `RuntimeException`).
        //
        // Issue #166: `SSHClient.disconnect()` sends an
        // `SSH_MSG_DISCONNECT` packet over the live transport — a real
        // socket write. The non-suspending `close()` contract is dictated
        // by `AutoCloseable`, and historically several callers
        // (Compose `onDispose`, `ViewModel.onCleared`,
        // `HostTmuxSessionsGateway`, screen disposers) invoke this from
        // the Android Main thread. With Android's StrictMode
        // detectNetwork() enabled — and on real devices that policy is
        // always on for the Main thread — that socket write trips
        // `NetworkOnMainThreadException`. The pre-#166 RuntimeException
        // catch below hid the crash from the maintainer device but the
        // policy violation still aborted the disconnect mid-write,
        // leaving the sshj transport in a half-closed state and producing
        // logcat noise on every teardown.
        //
        // The fix dispatches the network-touching `disconnect()` call onto
        // `Dispatchers.IO` via `runBlocking(Dispatchers.IO) { ... }`:
        // the calling thread (which may be Main) still blocks until the
        // disconnect finishes (preserving the AutoCloseable ordering
        // contract), but the actual SSH_MSG_DISCONNECT socket write
        // happens on an IO worker thread, so the
        // BlockGuard / StrictMode `onNetwork` probe never fires on Main.
        // Hopping the suspending boundary higher (e.g. making
        // `SshSession.close()` itself a suspend) would ripple through
        // every caller; the AutoCloseable-preserving thread hop is the
        // surgical fix called for by the issue.
        try {
            // Issue #847: drain the dispatcher and run `disconnect()` as the
            // FINAL serialised operation. `closeAndAwaitDrain` (a) queues the
            // disconnect BEHIND any in-flight write/channel op so we never tear
            // the transport down underneath one (which is exactly the
            // write-racing-`die()` desync the actor exists to prevent), and (b)
            // marks the dispatcher closed under its lock so any later op is
            // rejected before it can touch the dead transport. The disconnect
            // socket write still happens on the dispatch (IO) thread, so the
            // StrictMode `NetworkOnMainThreadException` guard (issue #166) holds.
            runBlocking(Dispatchers.IO) {
                val drained = withTimeoutOrNull(SESSION_CLOSE_TIMEOUT_MS) {
                    dispatcher.closeAndAwaitDrain {
                        client.disconnect()
                    }
                    true
                } ?: false
                if (!drained) {
                    CLOSE_LOGGER.log(
                        Level.INFO,
                        "[$CLOSE_LOG_TAG] close() timed out after ${SESSION_CLOSE_TIMEOUT_MS}ms; " +
                            "forcing dispatcher shutdown",
                    )
                    dispatcher.closeNow()
                }
            }
        } catch (e: TransportException) {
            // Issue #239: close() is idempotent and silent by contract.
            // Every TransportException here is teardown-time and not
            // actionable — swallow and log.
            CLOSE_LOGGER.log(
                Level.INFO,
                "[$CLOSE_LOG_TAG] swallowed TransportException during close() " +
                    "(reason=${e.disconnectReason}): ${e.message}",
            )
        } catch (e: SSHException) {
            // sshj's `SSHException` is the parent of `TransportException`
            // (already handled) and `ConnectionException`. A
            // `ConnectionException` surfacing here is the same shape of
            // already-down-transport teardown noise — silently no-op so
            // the idempotency contract holds.
            CLOSE_LOGGER.log(
                Level.INFO,
                "[$CLOSE_LOG_TAG] swallowed SSHException during close(): ${e.message}",
            )
        } catch (e: IOException) {
            // sshj declares `disconnect()` as `throws IOException`. A
            // non-SSHException IO failure during shutdown is best-effort:
            // nothing the caller can do, propagating it would defeat the
            // idempotency contract. Swallowed deliberately to preserve the
            // pre-#151 `runCatching` semantics for this path.
            CLOSE_LOGGER.log(
                Level.INFO,
                "[$CLOSE_LOG_TAG] swallowed IOException during close(): ${e.message}",
            )
        } catch (e: RuntimeException) {
            // Belt-and-suspenders: the pre-#151 implementation wrapped the
            // whole disconnect in `runCatching`, which silently swallowed
            // every Throwable. With #166 the socket write now runs on
            // `Dispatchers.IO` so StrictMode `NetworkOnMainThreadException`
            // can no longer originate here — but we keep this catch for
            // any other RuntimeException sshj may surface during teardown
            // (e.g. an exotic state-machine error) so close() stays
            // idempotent in the face of unknown teardown failure modes.
            CLOSE_LOGGER.log(
                Level.INFO,
                "[$CLOSE_LOG_TAG] swallowed RuntimeException during close(): ${e.message}",
            )
        } finally {
            teardownScope.cancel()
        }
    }

    private fun ensureConnected() {
        if (!isConnected) throw SshException("SSH session is not connected")
    }

    /**
     * True iff [t] is the sshj-delivered response to our `keepalive@openssh.com`
     * global request that PROVES the peer answered (issue #945).
     *
     * OpenSSH does not implement the request type, so it replies
     * `SSH_MSG_REQUEST_FAILURE`, which sshj's `ConnectionImpl.gotGlobalReqResponse`
     * delivers to the promise as `deliverError(ConnectionException("Global request
     * [...] failed"))`. That exception means the SERVER ANSWERED — proof of life,
     * exactly as OpenSSH's own `ServerAliveInterval` treats it. We match the
     * stable sshj message text on the cause chain. A genuine transport death
     * (connection lost, MAC failure, reader EOF) carries a DIFFERENT message and
     * is therefore correctly a miss.
     */
    private fun isKeepAliveServerAnswered(t: Throwable): Boolean {
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 8) {
            val msg = cause.message
            if (msg != null && msg.contains("Global request", ignoreCase = true) &&
                msg.contains("failed", ignoreCase = true)
            ) {
                return true
            }
            cause = cause.cause
            depth += 1
        }
        return false
    }
}

/**
 * Quote [remotePath] for safe interpolation into a single remote shell command,
 * while still letting a leading `~` / `~/` expand to the remote `$HOME`
 * (issue #558 bug 3).
 *
 * The previous code single-quoted the whole path (`'...'`), which is correct for
 * arbitrary filenames but suppresses `~` expansion — so tapping
 * `~/git/pocketshell/.tmp/host-list-screen.png` reached the server literally and
 * produced a false "No such file". The client cannot expand `~` itself without
 * an extra round-trip (it does not know the remote `$HOME`), so instead we leave
 * the tilde unquoted and single-quote only the remainder:
 *
 *  - `~`            → `~`            (the shell expands `~` to `$HOME`)
 *  - `~/a b/c.png`  → `~/'a b/c.png'`(`~` expands; the rest is quote-safe)
 *  - `/etc/hosts`   → `'/etc/hosts'` (absolute path unchanged behaviour)
 *
 * Only a leading bare `~` or `~/` is treated as expandable; `~user/...` and any
 * `~` that is not the very first character are quoted literally, matching how a
 * POSIX shell only expands `~` at the start of a word.
 */
internal fun quoteRemotePathForShell(remotePath: String): String {
    return when {
        remotePath == "~" -> "~"
        remotePath.startsWith("~/") -> {
            val rest = remotePath.substring(2)
            if (rest.isEmpty()) "~/" else "~/" + shellSingleQuote(rest)
        }
        else -> shellSingleQuote(remotePath)
    }
}

/**
 * Exit code [buildListDirCommand] emits on a successful listing. Distinct from
 * the not-a-dir / permission / not-found sentinels so [classifyListFailure] can
 * map each shell-side outcome onto a typed exception.
 */
internal const val PROBE_EXIT_OK: Int = 0

/** [buildListDirCommand] exit code: the path exists but is not a directory. */
internal const val PROBE_EXIT_NOT_A_DIR: Int = 20

/** [buildListDirCommand] exit code: the path does not exist. */
internal const val PROBE_EXIT_NO_SUCH: Int = 21

/** [buildListDirCommand] exit code: the directory could not be read (perms). */
internal const val PROBE_EXIT_DENIED: Int = 22

/**
 * Field separator between `type|size|mtime|path` in [buildListDirCommand]
 * output. A vertical bar is shell-safe in `stat -c` and rare in real
 * filenames; the path is the *last* field so any bars inside a filename are
 * preserved by [parseListing] splitting on only the first three separators.
 */
internal const val LIST_FIELD_SEP: Char = '|'

private const val LIST_REMOTE_EXTRA_ROWS: Int = 2
private const val LIST_REMOTE_MAX_LINES: Int = 100_000

/**
 * Build the remote directory-listing command for [remotePath] (issue #528).
 *
 * Strategy (POSIX shell, busybox- and coreutils-safe — same baseline as
 * `downloadFile`'s `cat`):
 *  1. Guard the path: `! -e` -> exit [PROBE_EXIT_NO_SUCH]; not a directory ->
 *     [PROBE_EXIT_NOT_A_DIR]; not readable/executable -> [PROBE_EXIT_DENIED].
 *  2. List with `find <dir> -maxdepth 1` and `stat -c "%F|%s|%Y|%n"` each entry.
 *     `find` includes the directory itself as the first hit; [parseListing]
 *     drops the row whose path equals the listed directory. `stat` does not
 *     follow symlinks, so a link is reported as `symbolic link`.
 *  3. On success the guard already returned 0 via the `find` pipeline's own
 *     exit; we force a clean 0 so [classifyListFailure] only fires on the
 *     guard sentinels.
 *
 * The path is quoted via [quoteRemotePathForShell] so arbitrary filenames
 * (spaces, quotes, `$`) don't break parsing while a leading `~`/`~/` still
 * expands to `$HOME` (issue #558 bug 3). Filenames containing a literal newline
 * are a known limitation of the line-based parse (busybox `stat` cannot
 * NUL-terminate); such names are extremely rare and degrade to a skipped/garbled
 * row rather than a crash.
 */
internal fun buildListDirCommand(remotePath: String, maxEntries: Int? = null): String {
    val quoted = quoteRemotePathForShell(remotePath)
    val remoteLineLimit = maxEntries?.let { listDirectoryRemoteLineLimit(it) }
    return buildString {
        append("d=").append(quoted).append("; ")
        append("if [ ! -e \"\$d\" ]; then exit ").append(PROBE_EXIT_NO_SUCH).append("; fi; ")
        append("if [ ! -d \"\$d\" ]; then exit ").append(PROBE_EXIT_NOT_A_DIR).append("; fi; ")
        // Need read (to list names) and execute (to stat children).
        append("if [ ! -r \"\$d\" ] || [ ! -x \"\$d\" ]; then exit ")
            .append(PROBE_EXIT_DENIED).append("; fi; ")
        append("find \"\$d\" -maxdepth 1 -exec stat -c '%F")
            .append(LIST_FIELD_SEP).append("%s")
            .append(LIST_FIELD_SEP).append("%Y")
            .append(LIST_FIELD_SEP).append("%n' {} ").append("\\;").append(" 2>/dev/null")
        if (remoteLineLimit != null) {
            append(" | sed -n '1,").append(remoteLineLimit).append("p'")
        }
        append("; ")
        append("exit ").append(PROBE_EXIT_OK)
    }
}

internal fun listDirectoryRemoteLineLimit(maxEntries: Int): Int =
    (maxEntries.coerceAtLeast(0) + LIST_REMOTE_EXTRA_ROWS).coerceAtMost(LIST_REMOTE_MAX_LINES)

/**
 * Map a non-zero [buildListDirCommand] exit onto a typed [SshException].
 * Visible-for-test so the sentinel mapping is pinned without a live server.
 */
internal fun classifyListFailure(remotePath: String, probe: ExecResult): SshException =
    when (probe.exitCode) {
        PROBE_EXIT_NOT_A_DIR -> SshNotADirectoryException(remotePath)
        PROBE_EXIT_NO_SUCH -> SshFileNotFoundException(remotePath)
        PROBE_EXIT_DENIED -> SshPermissionDeniedException(remotePath)
        else -> SshException(
            "Listing $remotePath failed (exit ${probe.exitCode}): ${probe.stderr.trim()}",
        )
    }

/**
 * Parse [buildListDirCommand] stdout (one `type|size|mtime|path` line per
 * entry) into a [RemoteListing] relative to [listedDir]. Drops the listed
 * directory's own row and any `.`/`..`, caps at [maxEntries] (setting
 * `truncated`), and folds the busybox/coreutils `stat -c %F` human type string
 * onto [RemoteEntry.Type]. Pure — unit-tested without SSH.
 */
internal fun parseListing(
    stdout: String,
    listedDir: String,
    maxEntries: Int,
): RemoteListing {
    val normalizedDir = listedDir.trimEnd('/')
    val entries = ArrayList<RemoteEntry>()
    var truncated = false
    for (raw in stdout.lineSequence()) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) continue
        // Split on only the first three separators so a `|` inside a filename
        // is preserved in the path field.
        val parts = line.split(LIST_FIELD_SEP, limit = 4)
        if (parts.size < 4) continue
        val typeStr = parts[0].trim()
        val size = parts[1].trim().toLongOrNull() ?: 0L
        val mtime = parts[2].trim().toLongOrNull()?.takeIf { it > 0L }
        val fullPath = parts[3]
        // The listed directory itself is the first `find` hit — skip it.
        if (fullPath.trimEnd('/') == normalizedDir) continue
        val name = fullPath.substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == "..") continue
        if (entries.size >= maxEntries) {
            truncated = true
            break
        }
        val type = parseStatType(typeStr)
        entries += RemoteEntry(
            name = name,
            type = type,
            sizeBytes = if (type == RemoteEntry.Type.FILE) size else 0L,
            modifiedEpochSec = mtime,
        )
    }
    return RemoteListing(entries = entries, truncated = truncated)
}

/**
 * Fold a `stat -c %F` human-readable file-type string (busybox and coreutils
 * use the same words) onto [RemoteEntry.Type]. Unknown / device / socket /
 * fifo types map to [RemoteEntry.Type.OTHER]. Visible-for-test.
 */
internal fun parseStatType(statType: String): RemoteEntry.Type = when (statType.lowercase()) {
    "directory" -> RemoteEntry.Type.DIRECTORY
    "regular file", "regular empty file" -> RemoteEntry.Type.FILE
    "symbolic link" -> RemoteEntry.Type.SYMLINK
    else -> RemoteEntry.Type.OTHER
}

/**
 * Terminfo entry advertised when allocating the PTY for [RealSshSession.startShell].
 *
 * `xterm-256color` is the AOSP / Termux baseline and the terminfo entry that
 * real interactive agent CLIs (opencode, Codex, Claude Code) target. Anything
 * more conservative — notably the `vt100` that sshj 0.40's
 * `Session.allocateDefaultPTY` defaults to — pushes those CLIs into a degraded
 * line-mode rendering where the prompt input drops to the bottom of the
 * scrolling shell instead of rendering inside their alternate-screen input
 * box.
 *
 * Issue #106: kept as an `internal const` so the unit test in
 * `RealSshSessionPtyAllocationTest` can pin the value and so the chosen
 * terminfo entry is grep-able from the SSH-shell entry point per #102.
 */
internal const val INTERACTIVE_PTY_TERM: String = "xterm-256color"

/**
 * Initial PTY column count advertised on shell allocation.
 *
 * Matches sshj's historical `allocateDefaultPTY` default (80) so well-behaved
 * login shells that read the SSH-time TIOCGWINSZ see the same starting
 * geometry as before. The on-device terminal resizes the remote PTY to the
 * real on-screen grid via `changeWindowDimensions` once it lays out, so this
 * value is only ever observed by the brief pre-layout window.
 */
internal const val INTERACTIVE_PTY_INITIAL_COLUMNS: Int = 80

/**
 * Initial PTY row count. See [INTERACTIVE_PTY_INITIAL_COLUMNS] for the
 * rationale on keeping the 80x24 default; the real grid replaces this on
 * first layout.
 */
internal const val INTERACTIVE_PTY_INITIAL_ROWS: Int = 24

/**
 * Logcat-grep tag for `RealSshSession.close()` swallowing a teardown-time
 * transport fault (issue #239). Routed through `java.util.logging` for
 * the same reason [SshjTransportThreadGuard] uses that channel — no
 * Android-only dependency from this shared module.
 */
internal const val CLOSE_LOG_TAG: String = "issue239-close-teardown"

/**
 * Wall-clock ceiling for the Phase-2 stdout/stderr read (`readBytes()` +
 * `join()`) of a single [RealSshSession.exec] (#935 S4-2). A half-open / wedged
 * transport leaves the blocking JDK read parked forever; this is the boundary
 * bound that turns "the action silently never returns" into a fast, clear,
 * RETRYABLE [SshExecTimeoutException]. On expiry the session is closed so the
 * lease pool discards the corpse and re-dials on the next acquire.
 *
 * 30s is generous relative to a normal control exec (tens of ms — env edit,
 * profile list, start-dir autocomplete, manual-kind write, watched-folder
 * discovery, the file-viewer `mkdir`/`pwd`/`cat`) yet bounds an indefinite
 * hang. It sits ABOVE the three bespoke per-caller wraps
 * ([com.pocketshell.app.projects.SshFolderListGateway]'s `EXEC_READ_TIMEOUT_MS`
 * = 3.5s, `TreeRemoteSource`, `AgentKindRemoteSource`) so those tighter,
 * latency-sensitive enumeration bounds still fire FIRST on their hot paths and
 * this boundary bound is the safety net for every other caller that has no wrap
 * of its own.
 */
internal const val EXEC_READ_TIMEOUT_MS: Long = 30_000L

internal const val EXEC_STREAM_MAX_BYTES: Int = 8 * 1024 * 1024

private val EXEC_LOGGER: Logger = Logger.getLogger(RealSshSession::class.java.name + ".exec")

private class TransferStallTimeoutException(
    val operation: String,
    val timeoutMs: Long,
    cause: Throwable?,
) : IOException("$operation made no progress for ${timeoutMs}ms", cause)

/**
 * Hard ceiling on the caller-visible wait inside [RealSshSession.close] for the
 * dispatcher drain + final disconnect. The dispatcher's per-op watchdog still
 * owns normal write interruption; this outer bound prevents a synchronous close
 * caller from parking behind a half-open transport operation indefinitely.
 */
private const val SESSION_CLOSE_TIMEOUT_MS: Long = 2_000L

/**
 * No-progress ceiling for a single blocking upload step: source read, remote
 * channel write, final flush, or remote `cat` exit wait. This replaces the old
 * absolute whole-upload 60s cap. A large upload may run for much longer than
 * one window as long as each step keeps completing; a wedged channel still
 * fails fast with a clear error.
 */
internal const val UPLOAD_STALL_TIMEOUT_MS: Long = 60_000L

/**
 * No-progress ceiling for raw file downloads over `cat`. Each successful chunk
 * records inbound activity and resets the effective budget; a read/join/stderr
 * wait that makes no progress for this long is treated as a wedged transfer.
 */
internal const val DOWNLOAD_STALL_TIMEOUT_MS: Long = 60_000L

/**
 * Maximum stderr bytes captured from transfer helper commands (`cat` upload /
 * download). Diagnostics stay useful while a noisy or malicious remote cannot
 * grow memory unbounded when reporting a non-zero transfer exit.
 */
private const val TRANSFER_STDERR_MAX_BYTES: Int = 64 * 1024

/**
 * Wall-clock ceiling for removing a partial upload's temp file after a failed
 * transfer (issue #930). Short and bounded so a half-dead transport can't wedge
 * the cleanup; a failed/timed-out `rm` is swallowed best-effort.
 */
internal const val UPLOAD_CLEANUP_TIMEOUT_MS: Long = 10_000L

private val CLOSE_LOGGER: Logger = Logger.getLogger(RealSshSession::class.java.name + ".close")

/**
 * The SSH global-request name OpenSSH uses for its `ServerAliveInterval` keepalive
 * (issue #945). The server replies with `SSH_MSG_REQUEST_FAILURE` because it does
 * not implement this request type — and that FAILURE reply still proves the peer
 * is alive, exactly as OpenSSH's own client treats it.
 */
internal const val KEEPALIVE_REQUEST_NAME: String = "keepalive@openssh.com"

/**
 * Per-keepalive reply budget (issue #945). A healthy global-request round-trip is
 * sub-second even on a congested link; this generous ceiling means a single
 * momentarily-slow reply is a soft miss the loop absorbs (it takes
 * [TransportKeepAlive.DEFAULT_COUNT_MAX] consecutive misses to declare dead), not
 * a false positive. The send itself is also bounded by the dispatcher's per-op
 * wall-clock ceiling, so a wedged write can never park the keepalive forever.
 */
internal const val KEEPALIVE_REPLY_TIMEOUT_MS: Long = 5_000L

/**
 * Poll cadence for the local no-activity budget in
 * [RealSshSession.awaitKeepAliveReply] (issue #985). The reply confirmation is
 * derived from [RealSshSession.lastInboundActivityNanos] advancing (a reply OR any
 * reader byte) rather than a blocking promise wait, so we sample the timestamp at
 * this cadence while waiting up to [KEEPALIVE_REPLY_TIMEOUT_MS]. 100ms is fine
 * granularity for a sub-second healthy round-trip without burning CPU.
 */
internal const val KEEPALIVE_POLL_MS: Long = 100L

/** Empty payload for the [KEEPALIVE_REQUEST_NAME] global request (issue #945). */
private val EMPTY_PAYLOAD: ByteArray = ByteArray(0)

/**
 * Logcat-grep tag for the transport keepalive (issue #945) — declaring a dead
 * peer and the per-tick diagnostics. Routed through `java.util.logging` for the
 * same reason the other [RealSshSession] tags are: no Android-only dependency
 * from this shared module.
 */
internal const val KEEPALIVE_LOG_TAG: String = "issue945-keepalive"

private val KEEPALIVE_LOGGER: Logger =
    Logger.getLogger(RealSshSession::class.java.name + ".keepalive")

/**
 * Logcat-grep tag for `RealSshSession.tail()` swallowing a recoverable
 * transport drop (issue #239). The reconnect state machine in
 * `TmuxSessionViewModel` (#145 + #173) will resume the tail on the next
 * attach; this log line is the diagnostic breadcrumb that says the tail
 * coroutine ended cleanly instead of crashing the worker.
 */
internal const val TAIL_LOG_TAG: String = "issue239-tail-recover"

private val TAIL_LOGGER: Logger = Logger.getLogger(RealSshSession::class.java.name + ".tail")

/**
 * Sentinel emitted by [buildSizeProbeCommand] when the remote path is not a
 * regular file (missing, a directory, a device, ...). Kept distinct from any
 * numeric byte count so [parseSizeProbe] can map it to
 * [SshFileNotFoundException].
 */
internal const val SIZE_PROBE_NO_FILE_SENTINEL: String = "__PS_NOFILE__"

/** [parseSizeProbe] result for the [SIZE_PROBE_NO_FILE_SENTINEL] case. */
internal const val SIZE_PROBE_NO_FILE: Long = -1L

/** [parseSizeProbe] result when the probe output couldn't be parsed at all. */
internal const val SIZE_PROBE_UNPARSEABLE: Long = -2L

/**
 * Build the remote size-probe command for [remotePath] (issue #497).
 *
 * For a regular file it prints the byte count (`wc -c` — busybox-safe on the
 * Alpine fixtures and present on any POSIX shell); for anything that is not a
 * regular file it prints [SIZE_PROBE_NO_FILE_SENTINEL]. The path is resolved
 * by the remote login shell, so `~`-relative paths expand server-side (issue
 * #558 bug 3 — see [quoteRemotePathForShell]).
 *
 * Quoted via [quoteRemotePathForShell] so arbitrary filenames don't break shell
 * parsing while a leading `~`/`~/` still expands to `$HOME`.
 */
internal fun buildSizeProbeCommand(remotePath: String): String {
    val quoted = quoteRemotePathForShell(remotePath)
    return "if [ -f $quoted ]; then wc -c < $quoted; else echo $SIZE_PROBE_NO_FILE_SENTINEL; fi"
}

/**
 * Parse the stdout of [buildSizeProbeCommand] into a byte count.
 *
 * Returns:
 *  - the parsed non-negative size for a regular file,
 *  - [SIZE_PROBE_NO_FILE] when the sentinel was printed,
 *  - [SIZE_PROBE_UNPARSEABLE] when the output is neither (e.g. `wc` missing).
 *
 * `wc -c` on busybox may emit leading whitespace, so the numeric line is
 * trimmed before parsing. Visible-for-test so the parse rules are pinned
 * without a live SSH server.
 */
internal fun parseSizeProbe(stdout: String): Long {
    val trimmed = stdout.trim()
    if (trimmed == SIZE_PROBE_NO_FILE_SENTINEL) return SIZE_PROBE_NO_FILE
    // Take the last non-blank token — guards against a shell that prints a
    // banner before the count. `wc -c < file` outputs just the number.
    val token = trimmed.lines()
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
        ?: return SIZE_PROBE_UNPARSEABLE
    if (token == SIZE_PROBE_NO_FILE_SENTINEL) return SIZE_PROBE_NO_FILE
    return token.toLongOrNull()?.takeIf { it >= 0 } ?: SIZE_PROBE_UNPARSEABLE
}
