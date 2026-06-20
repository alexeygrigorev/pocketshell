package com.pocketshell.core.terminal.bridge

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.OutputStream
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

public class TerminalSeedGateOverflowException(
    public val pendingBytes: Int,
    public val incomingBytes: Int,
    public val maxBytes: Int,
) : IllegalStateException(
    "terminal seed gate live buffer overflow: pendingBytes=$pendingBytes " +
        "incomingBytes=$incomingBytes maxBytes=$maxBytes",
)

/**
 * Bridges raw bytes from a remote source (typically an SSH `Session.startShell`
 * channel) into the vendored Termux [TerminalEmulator] / [TerminalSession]
 * machinery, and forwards user input from the emulator back to the same
 * remote stream.
 *
 * ## Why this exists
 *
 * Upstream Termux's [TerminalSession] is wired to spawn a *local* PTY
 * subprocess via the JNI `libtermux.so` library
 * ([com.termux.terminal.JNI.createSubprocess], ...). PocketShell's data flow
 * is the inverse — bytes come from a remote SSH stream, not a local PTY —
 * so we must keep `TerminalSession.initializeEmulator` (which fires the JNI
 * subprocess) from ever running, while still keeping the rest of the
 * session/view/emulator stack happy.
 *
 * Strategy (recommended path "A" in the issue #9 brief):
 *
 * 1. Construct a real [TerminalSession]. Its constructor does not touch JNI.
 * 2. Pre-populate `TerminalSession.mEmulator` via reflection so that
 *    `updateSize`'s "create new emulator" branch is never taken. The else
 *    branch instead calls `JNI.setPtyWindowSize` + `mEmulator.resize`.
 * 3. The PocketShell stub `libtermux.so` (`shared/core-terminal/src/main/cpp/`)
 *    makes `setPtyWindowSize` a safe no-op, so `JNI.setPtyWindowSize` is
 *    callable without crashing — even though we never actually drive a local
 *    PTY.
 * 4. Set `TerminalSession.mShellPid` to a positive value via reflection so
 *    that `TerminalSession.write(byte[], int, int)`'s
 *    `if (mShellPid > 0)` gate accepts user input writes.
 * 5. Run two coroutines: one drains the session's
 *    `mTerminalToProcessIOQueue` and forwards bytes to the SSH stdin; the
 *    other reads bytes from the SSH stdout and dispatches them into the
 *    emulator via the same `mProcessToTerminalIOQueue` + `MSG_NEW_INPUT`
 *    path the upstream input thread uses.
 *
 * The bridge speaks only via [TerminalSession]'s public surface plus four
 * package-private fields (`mEmulator`, `mShellPid`, `mProcessToTerminalIOQueue`,
 * `mMainThreadHandler`), accessed by reflection so the bridge can live
 * outside the `com.termux.terminal` package without modifying the vendored
 * sources. The four lookups are cached at object-construction time.
 *
 * ## Threading
 *
 * The Termux handler is a [Handler] tied to the main looper. All calls to
 * [feedBytes] and [enqueueInput] post a message there so the emulator runs
 * on the UI thread, matching upstream's contract ("All terminal emulation
 * and callback methods will be performed on the main thread."). Callers
 * may invoke [feedBytes] from any thread.
 *
 * @property session The underlying [TerminalSession] consumed by
 *   [com.termux.view.TerminalView].
 * @property emulator The pre-installed emulator. Resized by
 *   [com.termux.view.TerminalView.updateSize] on every layout change once the
 *   view has a non-zero size.
 */
public class SshTerminalBridge(
    columns: Int = INITIAL_COLUMNS,
    rows: Int = INITIAL_ROWS,
    cellWidthPixels: Int = INITIAL_CELL_WIDTH_PX,
    cellHeightPixels: Int = INITIAL_CELL_HEIGHT_PX,
    transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    client: TerminalSessionClient = NoOpTerminalSessionClient(),
    traceSink: TraceSink? = null,
    private val nowMillis: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val traceState = traceSink?.let { TraceState(it) }
    private val terminalSessionClient: TerminalSessionClient =
        traceState?.let { TracingTerminalSessionClient(client, it) } ?: client

    /**
     * The vendored [TerminalSession] that [com.termux.view.TerminalView]
     * speaks to. Constructed with dummy shell args — they are never read
     * because we bypass `initializeEmulator`.
     */
    public val session: TerminalSession = TerminalSession(
        /* shellPath = */ "/system/bin/sh",
        /* cwd = */ "/",
        /* args = */ emptyArray(),
        /* env = */ emptyArray(),
        /* transcriptRows = */ transcriptRows,
        /* client = */ terminalSessionClient,
    )

    /**
     * The terminal emulator that the [com.termux.view.TerminalView] will
     * render. Pre-installed so `TerminalSession.updateSize`'s "first call"
     * branch (which would create an emulator AND spawn a local subprocess
     * via JNI) is never taken — the else branch fires instead, which only
     * calls our stubbed `JNI.setPtyWindowSize` + `mEmulator.resize`.
     */
    public val emulator: TerminalEmulator = TerminalEmulator(
        /* session = */ session,
        /* columns = */ columns,
        /* rows = */ rows,
        /* cellWidthPixels = */ cellWidthPixels,
        /* cellHeightPixels = */ cellHeightPixels,
        /* transcriptRows = */ transcriptRows,
        /* client = */ terminalSessionClient,
    )

    /**
     * Background thread that reads bytes the View pushes into
     * `mTerminalToProcessIOQueue` (via `session.write(...)`) and forwards
     * them to whatever [OutputStream] was set with [setRemoteStdin]. Stays
     * alive until [stop] is called.
     */
    @Volatile
    private var inputDrainerThread: Thread? = null
    private val stopped = AtomicBoolean(false)
    private val feedLock = Any()

    /**
     * Issue #468: seed gate. tmux reattach paints a pane from a
     * `capture-pane` snapshot ([seedThenOpenGate]) *and* attaches a live
     * `%output` producer that starts streaming immediately. If those two
     * sources race, the snapshot's `ESC[2J` clear can wipe live deltas that
     * already landed, and the snapshot (taken at a different instant than the
     * live grid) leaves the emulator in a state the next cursor-relative live
     * delta does not expect — stranding lines, mashing spinner frames, and
     * leaving most of the screen blank (the reported garble under heavy
     * output, same family as #259).
     *
     * The gate makes seed-before-live deterministic: while gated, live
     * [feedBytes] bytes are appended to [gatedLiveBuffer] in arrival order
     * instead of reaching the emulator. [seedThenOpenGate] applies the
     * snapshot, then flushes the buffered live bytes in order, then opens the
     * gate so subsequent live bytes flow straight through. If no seed ever
     * arrives (capture-pane failed, or the surface is a plain non-tmux SSH
     * shell that never seeds), [openGateFlushingPending] flushes the buffer
     * ungated so output is never permanently swallowed.
     *
     * A bridge that is never gated ([gated] starts `false`) behaves exactly
     * as before: every [feedBytes] reaches the emulator synchronously.
     */
    private val gateLock = Any()

    @Volatile
    private var gated: Boolean = false

    private val gatedLiveBuffer = java.io.ByteArrayOutputStream()

    @Volatile
    private var remoteStdin: OutputStream? = null

    /**
     * Issue #803: owns the frame-budgeted main-looper drain of the
     * process→terminal queue. The off-main live `%output` path writes chunks to
     * the queue and then calls [MainThreadDrainScheduler.requestDrain]; the
     * scheduler parses at most one per-frame byte budget on the main thread per
     * turn and yields to the next frame, so a dense colored-diff burst cannot pin
     * the looper in one unbounded back-to-back VT parse (the #803 ANR). Lazily
     * built so it captures the session's main-looper handler after construction.
     */
    private val drainScheduler: MainThreadDrainScheduler by lazy {
        MainThreadDrainScheduler(
            handler = SessionReflection.getMainThreadHandler(session),
            msgNewInput = MSG_NEW_INPUT,
            availableBytes = { SessionReflection.availableProcessOutputBytes(session) },
        )
    }

    init {
        // Install the pre-built emulator on the session so `updateSize`'s
        // null-check fails and the JNI-spawning branch is skipped.
        SessionReflection.setEmulator(session, emulator)

        // Make `TerminalSession.write` accept input. Upstream gates writes on
        // `if (mShellPid > 0)`. We have no real shell pid; any positive value
        // unlocks the queue. The value 1 is arbitrary — nobody reads it from
        // session state because we never call `finishIfRunning` (which would
        // SIGKILL pid 1 on the device, which is `init` — but we explicitly
        // never call it, and the public API has no other consumer of the
        // field).
        SessionReflection.setShellPid(session, FAKE_SHELL_PID)
    }

    /**
     * Set the [OutputStream] that will receive bytes written by the user
     * (typed characters, IME commits, etc.) via the [TerminalSession]. Pass
     * the SSH shell channel's stdin here. Pass `null` to detach.
     *
     * The stream is invoked from a dedicated drainer thread; callers do not
     * need to thread-shift before passing the stream.
     */
    public fun setRemoteStdin(stream: OutputStream?) {
        remoteStdin = stream
        if (stream != null && inputDrainerThread == null) {
            startInputDrainer()
        }
    }

    /**
     * Push [count] bytes from [data] starting at [offset] into the
     * emulator's input queue, then nudge the session's main-thread handler
     * so it actually drains the queue and renders the bytes.
     *
     * Safe to call from any thread. Equivalent of what the upstream
     * `TermSessionInputReader` thread does after reading from the local PTY
     * file descriptor.
     */
    public fun feedBytes(data: ByteArray, offset: Int = 0, count: Int = data.size) {
        require(offset >= 0) { "offset < 0" }
        require(count >= 0) { "count < 0" }
        require(offset <= data.size) { "offset > data.size" }
        require(count <= data.size - offset) { "offset + count > data.size" }
        if (count == 0) return

        // Issue #468: hold live output behind the seed gate (in arrival
        // order) until the `capture-pane` snapshot has been applied. Snapshot
        // both the flag and append inside [gateLock] so a concurrent
        // [seedThenOpenGate] cannot flush the buffer between our read of
        // [gated] and our append (which would reorder live bytes around the
        // seed). The lock is held only for the cheap buffer copy; the emulator
        // feed below runs without it.
        synchronized(gateLock) {
            if (gated) {
                val pendingBytes = gatedLiveBuffer.size()
                if (pendingBytes + count > MAX_SEED_GATE_LIVE_BUFFER_BYTES) {
                    gatedLiveBuffer.reset()
                    gated = false
                    throw TerminalSeedGateOverflowException(
                        pendingBytes = pendingBytes,
                        incomingBytes = count,
                        maxBytes = MAX_SEED_GATE_LIVE_BUFFER_BYTES,
                    )
                }
                gatedLiveBuffer.write(data, offset, count)
                return
            }
        }

        feedBytesToEmulator(data, offset, count)
    }

    private fun feedBytesToEmulator(data: ByteArray, offset: Int, count: Int) {
        if (count == 0) return
        val traceState = traceState
        if (traceState == null) {
            feedBytesUntraced(data = data, offset = offset, count = count)
        } else {
            feedBytesTraced(data = data, offset = offset, count = count, traceState = traceState)
        }
    }

    private fun feedBytesUntraced(data: ByteArray, offset: Int, count: Int) {
        val handler = SessionReflection.getMainThreadHandler(session)
        val isHandlerLooper = Looper.myLooper() == handler.looper
        val seedDrainStartedAtMillis = nowMillis()
        // #829: when the FINAL chunk's on-main seed drain exceeds the time budget we
        // hand its already-queued tail to the frame-budgeted scheduler so the rest
        // paints across frames instead of pinning the main thread inline. Only the
        // final chunk may hand off (deadlock-safety, see dispatchMainLooperDrains).
        var seedDrainHandedOff = false
        synchronized(feedLock) {
            var remaining = count
            var chunkOffset = offset
            while (remaining > 0) {
                val chunkLength = minOf(remaining, PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES)
                val written = SessionReflection.writeProcessToTerminalQueue(
                    session = session,
                    data = data,
                    offset = chunkOffset,
                    length = chunkLength,
                )
                if (!written) return
                val isLastChunk = remaining - chunkLength <= 0
                if (isHandlerLooper && !seedDrainHandedOff) {
                    // On-main seed/reattach feed: drain it synchronously so the
                    // `capture-pane` snapshot paints before live `%output` flows.
                    // Time-bounded (#829): on the final chunk, if this on-main drain
                    // exceeds SEED_DRAIN_MAX_MILLIS it stops and hands the rest to the
                    // frame-budgeted scheduler rather than running unbounded. Earlier
                    // chunks must drain fully (allowHandoff=false) so the next write
                    // doesn't block on a full queue (see dispatchMainLooperDrains).
                    val budgetExhausted = dispatchMainLooperDrains(
                        handler = handler,
                        queuedBytes = chunkLength,
                        startedAtMillis = seedDrainStartedAtMillis,
                        allowHandoff = isLastChunk,
                    )
                    if (budgetExhausted) {
                        seedDrainHandedOff = true
                        drainScheduler.requestDrain()
                    }
                } else if (isHandlerLooper) {
                    // Budget already exhausted earlier in this feed: queue + yield.
                    drainScheduler.requestDrain()
                } else {
                    // Off-main live `%output`: hand continuation to the #803
                    // frame-budgeted scheduler. requestDrain() is idempotent across
                    // a burst — many chunks collapse into AT MOST one pending
                    // main-looper drain turn, which parses one per-frame budget and
                    // yields, so a dense colored diff can't pin the main thread.
                    drainScheduler.requestDrain()
                }
                chunkOffset += chunkLength
                remaining -= chunkLength
            }
        }
    }

    private fun feedBytesTraced(data: ByteArray, offset: Int, count: Int, traceState: TraceState) {
        val handler = SessionReflection.getMainThreadHandler(session)
        val isHandlerLooper = Looper.myLooper() == handler.looper
        val traceSink = traceState.traceSink
        val pendingDrainMessages = traceState.pendingDrainMessages
        val feedStartedAtNanos = System.nanoTime()
        val seedDrainStartedAtMillis = nowMillis()
        var seedDrainHandedOff = false
        var chunks = 0
        traceSink.onFeedStarted(count)
        synchronized(feedLock) {
            var remaining = count
            var chunkOffset = offset
            while (remaining > 0) {
                val chunkLength = minOf(remaining, PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES)
                val writeStartedAtNanos = System.nanoTime()
                val written = SessionReflection.writeProcessToTerminalQueue(
                    session = session,
                    data = data,
                    offset = chunkOffset,
                    length = chunkLength,
                )
                val writeNanos = System.nanoTime() - writeStartedAtNanos
                if (!written) return
                chunks += 1
                traceSink.onProcessQueueWrite(
                    bytes = chunkLength,
                    durationNanos = writeNanos,
                    waitedForDrain = writeNanos >= TRACE_WAIT_THRESHOLD_NANOS,
                )
                val pending = PendingDrainMessage(
                    bytes = chunkLength,
                    scheduledAtNanos = System.nanoTime(),
                )
                pendingDrainMessages.add(pending)
                traceSink.onDrainMessageScheduled(
                    bytes = chunkLength,
                    pendingMessages = pendingDrainMessages.size,
                    directDispatch = isHandlerLooper,
                )
                val isLastChunk = remaining - chunkLength <= 0
                if (isHandlerLooper && !seedDrainHandedOff) {
                    // On-main seed drain, time-bounded (#829): on the final chunk it
                    // stops and hands the remainder to the frame-budgeted scheduler
                    // past the budget. Earlier chunks drain fully (allowHandoff=false)
                    // so the next write doesn't block on a full queue.
                    val budgetExhausted = dispatchMainLooperDrains(
                        handler = handler,
                        queuedBytes = chunkLength,
                        startedAtMillis = seedDrainStartedAtMillis,
                        allowHandoff = isLastChunk,
                    ) { bytes, durationNanos ->
                        traceSink.onDirectDrainDispatched(bytes = bytes, durationNanos = durationNanos)
                    }
                    if (budgetExhausted) {
                        seedDrainHandedOff = true
                        drainScheduler.requestDrain()
                    }
                } else if (isHandlerLooper) {
                    // Budget already exhausted earlier in this feed: queue + yield.
                    drainScheduler.requestDrain()
                } else {
                    // Off-main live `%output`: #803 frame-budgeted scheduler owns
                    // continuation (see [feedBytesUntraced]). The per-slice trace
                    // callbacks (onProcessOutputDrained / onScreenUpdated) still
                    // fire from each scheduler-driven slice drain.
                    drainScheduler.requestDrain()
                }
                chunkOffset += chunkLength
                remaining -= chunkLength
            }
        }
        traceSink.onFeedCompleted(
            bytes = count,
            chunks = chunks,
            durationNanos = System.nanoTime() - feedStartedAtNanos,
        )
    }

    /**
     * Synchronously drain up to [queuedBytes] of the just-written on-main seed
     * chunk, one [PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES] slice per
     * `dispatchMessage`. The seed path is NOT the live-`%output` ANR path (it runs
     * one-shot before live bytes flow), so it deliberately drains inline so the
     * `capture-pane` snapshot paints before the live stream starts.
     *
     * Issue #829: the seed drain is now TIME-BOUNDED for symmetry with the live
     * [MainThreadDrainScheduler] turn budget. The budget is measured from
     * [startedAtMillis] (the start of the WHOLE on-main feed, shared across its
     * chunks), so a pathologically large seed cannot pin the main thread in one
     * unbounded inline run. After at least one slice (forward progress), if the
     * feed has spent more than [SEED_DRAIN_MAX_MILLIS] of main-thread wall time
     * this stops early and returns `true`; the caller then hands the remainder —
     * already FIFO-queued — to the frame-budgeted scheduler, which paces the rest
     * across frames. The granularity floor is one slice, so the worst-case inline
     * cost is [SEED_DRAIN_MAX_MILLIS] plus one 2 KB slice's parse.
     *
     * Deadlock-safety: handoff is only honored when [allowHandoff] is `true` (the
     * caller passes it ONLY for the final chunk of the feed). The bridge runs the
     * on-main feed synchronously while holding the looper, so a later chunk's
     * `writeProcessToTerminalQueue` would BLOCK forever on a full 64 KB queue with
     * no async drainer able to run (the scheduler's posted runnable can't execute
     * while this synchronous feed owns the looper). For non-final chunks we
     * therefore drain the chunk FULLY (ignoring the budget for handoff) so the next
     * write has room; only the final chunk — after which the feed returns and frees
     * the looper — may hand its tail to the scheduler.
     *
     * @return `true` if the time budget was exhausted AND [allowHandoff] is set, so
     *   the caller should hand continuation to the frame-budgeted scheduler;
     *   `false` if this chunk fully drained (within budget, or handoff disallowed).
     */
    private inline fun dispatchMainLooperDrains(
        handler: Handler,
        queuedBytes: Int,
        startedAtMillis: Long,
        allowHandoff: Boolean,
        onDispatched: (bytes: Int, durationNanos: Long) -> Unit = { _, _ -> },
    ): Boolean {
        var remaining = queuedBytes
        while (remaining > 0) {
            val drainBudget = minOf(remaining, PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES)
            val dispatchStartedAtNanos = System.nanoTime()
            handler.dispatchMessage(Message.obtain(handler, MSG_NEW_INPUT))
            onDispatched(drainBudget, System.nanoTime() - dispatchStartedAtNanos)
            remaining -= drainBudget
            // Time budget (#829): checked AFTER at least one slice so the seed always
            // makes forward progress. Stop inline draining once the on-main feed has
            // exceeded its wall-time budget — but ONLY on the final chunk, where the
            // feed is about to return and the scheduler can safely drain the tail
            // (see deadlock-safety above).
            if (allowHandoff && nowMillis() - startedAtMillis >= SEED_DRAIN_MAX_MILLIS) {
                return true
            }
        }
        return false
    }

    /**
     * Issue #468: close the seed gate. Subsequent live [feedBytes] bytes are
     * buffered (in arrival order) instead of reaching the emulator until
     * [seedThenOpenGate] or [openGateFlushingPending] runs. Called by the
     * Compose surface immediately after attaching a tmux pane's live
     * `%output` producer, so the live stream cannot paint the emulator before
     * the `capture-pane` seed lands.
     *
     * Idempotent. No-op once the gate is already closed.
     */
    public fun closeSeedGate() {
        synchronized(gateLock) {
            gated = true
        }
    }

    /**
     * Issue #468: apply the seed snapshot, then flush any live bytes that
     * arrived while the gate was closed (in their original arrival order),
     * then open the gate so future live bytes flow straight through.
     *
     * The whole operation is atomic with respect to concurrent [feedBytes]:
     * we drain and clear the pending buffer under [gateLock], so a live
     * `feedBytes` racing this call either lands in the buffer we are about to
     * flush (ordered before the post-seed flush) or sees `gated == false`
     * and feeds the emulator after the flush. Either way the emulator sees
     * `seed -> all buffered live -> later live` with no reordering and no
     * `ESC[2J` clear wiping live state.
     *
     * Safe to call when the gate was never closed: the seed is applied and
     * the (empty) buffer flush is a no-op.
     */
    public fun seedThenOpenGate(seedBytes: ByteArray) {
        synchronized(gateLock) {
            if (seedBytes.isNotEmpty()) {
                feedBytesToEmulator(seedBytes, 0, seedBytes.size)
            }
            flushAndOpenGateLocked()
        }
    }

    /**
     * Issue #468: open the gate without a seed, flushing buffered live bytes
     * in order. Used when the `capture-pane` seed never arrives (capture
     * failed, older tmux, or a bounded fallback timeout) so live output is
     * never permanently swallowed.
     *
     * Safe and idempotent when the gate is already open.
     */
    public fun openGateFlushingPending() {
        synchronized(gateLock) {
            flushAndOpenGateLocked()
        }
    }

    private fun flushAndOpenGateLocked() {
        // Caller holds [gateLock].
        val pending = if (gatedLiveBuffer.size() > 0) {
            gatedLiveBuffer.toByteArray()
        } else {
            null
        }
        gatedLiveBuffer.reset()
        gated = false
        if (pending != null) {
            feedBytesToEmulator(pending, 0, pending.size)
        }
    }

    /**
     * Stop the drainer thread and detach the remote stdin reference. Does
     * NOT close the underlying [TerminalSession] — its lifecycle belongs to
     * the caller; the emulator is still consumable by anyone holding a
     * reference (e.g. for end-of-session message rendering).
     */
    public fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        // Issue #803: drop any scheduled main-looper drain turn so a torn-down
        // session leaves no posted runnable behind.
        drainScheduler.cancel()
        SessionReflection.closeTerminalToProcessQueue(session)
        inputDrainerThread?.interrupt()
        inputDrainerThread = null
        remoteStdin = null
    }

    private fun startInputDrainer() {
        val t = Thread({ drainInputLoop() }, "PocketShellInputDrainer")
        t.isDaemon = true
        inputDrainerThread = t
        t.start()
    }

    private fun drainInputLoop() {
        val buffer = ByteArray(4096)
        while (!stopped.get()) {
            val read = SessionReflection.readTerminalToProcessQueue(session, buffer)
            if (read == -1) return
            if (read == 0) continue
            val stream = remoteStdin ?: continue
            try {
                stream.write(buffer, 0, read)
                stream.flush()
            } catch (t: Throwable) {
                // Stream is broken; bail. Caller is responsible for
                // detecting the SSH disconnect and tearing the bridge
                // down via stop().
                return
            }
        }
    }

    /**
     * No-op implementation of [TerminalSessionClient] used as the default
     * client when the caller doesn't supply one. Production callers (the
     * Compose adapter) install a client that bridges
     * [TerminalSessionClient.onTextChanged] to
     * [com.termux.view.TerminalView.onScreenUpdated], which is what drives
     * the canvas redraw.
     */
    public class NoOpTerminalSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    /**
     * Optional, test/debug-facing timing hook for terminal burst output.
     *
     * The bridge never installs a sink in production by default. Tests and
     * diagnostics can use it to count producer writes, observe when writes
     * wait behind a full Termux queue, count bytes actually drained by
     * Termux's main-thread handler, and estimate drain/update cost from the
     * time between scheduling `MSG_NEW_INPUT` and Termux's `onTextChanged`
     * callback.
     */
    public abstract class TraceSink {
        public open fun onFeedStarted(bytes: Int) = Unit
        public open fun onProcessQueueWrite(bytes: Int, durationNanos: Long, waitedForDrain: Boolean) = Unit
        public open fun onDrainMessageScheduled(bytes: Int, pendingMessages: Int, directDispatch: Boolean) = Unit
        public open fun onProcessOutputDrained(bytes: Int) = Unit
        public open fun onScreenUpdated(bytes: Int, scheduleToCallbackNanos: Long, callbackDurationNanos: Long) = Unit
        public open fun onDirectDrainDispatched(bytes: Int, durationNanos: Long) = Unit
        public open fun onFeedCompleted(bytes: Int, chunks: Int, durationNanos: Long) = Unit
    }

    public companion object {
        /**
         * Constant chosen to match the message id `TerminalSession.MSG_NEW_INPUT`
         * (= 1). Defined `private static final int` in the vendored source;
         * we hardcode it here so we don't need to reflect into a private
         * field per-call. If a future Termux refresh changes this value, the
         * vendored source diff will catch the mismatch.
         */
        public const val MSG_NEW_INPUT: Int = 1

        /**
         * Arbitrary positive value used as the "fake" shell pid so that
         * `TerminalSession.write` accepts user input writes. We never
         * actually SIGKILL this pid (would require calling
         * `TerminalSession.finishIfRunning`, which we explicitly do not).
         */
        public const val FAKE_SHELL_PID: Int = 1

        /** Initial emulator size; the View will resize on first layout. */
        public const val INITIAL_COLUMNS: Int = 80
        public const val INITIAL_ROWS: Int = 24

        /**
         * Initial cell pixel dimensions. These are deliberately small but
         * non-zero so [TerminalEmulator] constructor's internal asserts (it
         * requires positive dimensions) succeed. The [com.termux.view.TerminalView]
         * recomputes the cell pixel dimensions from the renderer's font
         * metrics on first layout and resizes the emulator accordingly via
         * `emulator.resize(...)`.
         */
        public const val INITIAL_CELL_WIDTH_PX: Int = 8
        public const val INITIAL_CELL_HEIGHT_PX: Int = 16

        /**
         * Scrollback buffer depth. Matches the upstream Termux default
         * recommended in `TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS`.
         */
        public const val DEFAULT_TRANSCRIPT_ROWS: Int = 2000

        /**
         * Matches `TerminalSession.mProcessToTerminalIOQueue`.
         *
         * `ByteQueue.write` blocks until the full requested length has been
         * accepted, so bridge feeds must never submit more than one queueful
         * before posting `MSG_NEW_INPUT`; otherwise a large tmux `%output`
         * chunk can fill the queue and wait forever before the main-thread
         * drain has even been scheduled.
         */
        internal const val PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES: Int = 64 * 1024

        /**
         * Must match `TerminalSession.MainThreadHandler.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES`
         * (the vendored `mReceiveBuffer` size) — the bytes ONE
         * `dispatchMessage(MSG_NEW_INPUT)` parses in a single uninterruptible
         * `mEmulator.append`. #796 shrank it from 16 KB to 2 KB so the
         * [MainThreadDrainScheduler] can apply an elapsed-time budget across
         * slices and yield mid-burst (one atomic append of clear-heavy
         * alt-screen content at 16 KB pinned the looper for >1 s). The on-main
         * seed drain ([dispatchMainLooperDrains]) issues one dispatch per slice,
         * so this must stay in lockstep with the vendored buffer size.
         */
        internal const val PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES: Int = 2 * 1024
        public const val MAX_SEED_GATE_LIVE_BUFFER_BYTES: Int = 2 * 1024 * 1024

        /**
         * Issue #829: time budget for the on-main, synchronous seed drain
         * ([dispatchMainLooperDrains]). The seed path is NOT the live-`%output`
         * ANR path — it runs one-shot before live bytes flow — so it drains inline
         * to paint the `capture-pane` snapshot first. But an inline drain with no
         * ceiling could still pin the main thread on a pathologically large seed,
         * so it is bounded: once the on-main feed has spent this much main-thread
         * wall time, the seed drain stops and hands the remainder to the
         * frame-budgeted [MainThreadDrainScheduler]. Set GENEROUSLY relative to the
         * live per-turn budget ([MainThreadDrainScheduler.DEFAULT_MAX_TURN_MILLIS],
         * ~8 ms) because the seed is a one-shot paint, not a sustained per-frame
         * cost — the common small seed always drains fully within budget; the cap
         * only bounds the pathological case.
         */
        internal const val SEED_DRAIN_MAX_MILLIS: Long = 24L

        private const val TRACE_WAIT_THRESHOLD_NANOS: Long = 1_000_000
    }
}

private class PendingDrainMessage(
    val bytes: Int,
    val scheduledAtNanos: Long,
) {
    var remainingBytes: Int = bytes
}

private class TraceState(
    val traceSink: SshTerminalBridge.TraceSink,
    val pendingDrainMessages: ConcurrentLinkedQueue<PendingDrainMessage> = ConcurrentLinkedQueue(),
    val pendingScreenUpdates: ConcurrentLinkedQueue<PolledDrain> = ConcurrentLinkedQueue(),
)

private class TracingTerminalSessionClient(
    private val delegate: TerminalSessionClient,
    private val traceState: TraceState,
) : TerminalSessionClient {

    override fun onProcessOutputDrained(session: TerminalSession, bytes: Int) {
        if (bytes > 0) {
            traceState.traceSink.onProcessOutputDrained(bytes)
            traceState.pendingScreenUpdates.add(pollPendingDrainBytes(bytes))
        }
        delegate.onProcessOutputDrained(session, bytes)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        val pending = traceState.pendingScreenUpdates.poll() ?: PolledDrain(bytes = 0, scheduledAtNanos = null)
        val callbackStartedAtNanos = System.nanoTime()
        try {
            delegate.onTextChanged(changedSession)
        } finally {
            traceState.traceSink.onScreenUpdated(
                bytes = pending.bytes,
                scheduleToCallbackNanos = pending.scheduledAtNanos?.let { callbackStartedAtNanos - it } ?: -1L,
                callbackDurationNanos = System.nanoTime() - callbackStartedAtNanos,
            )
        }
    }

    private fun pollPendingDrainBytes(bytesRead: Int): PolledDrain {
        var bytes = 0
        var scheduledAtNanos: Long? = null
        while (bytes < bytesRead) {
            val next = traceState.pendingDrainMessages.peek() ?: break
            val bytesToTake = minOf(
                bytesRead - bytes,
                next.remainingBytes,
            )
            if (bytesToTake <= 0) {
                traceState.pendingDrainMessages.poll()
                continue
            }
            next.remainingBytes -= bytesToTake
            bytes += bytesToTake
            if (next.remainingBytes == 0) {
                traceState.pendingDrainMessages.poll()
            }
            if (scheduledAtNanos == null) {
                scheduledAtNanos = next.scheduledAtNanos
            }
        }
        return PolledDrain(bytes = bytes, scheduledAtNanos = scheduledAtNanos)
    }

    override fun onTitleChanged(changedSession: TerminalSession) = delegate.onTitleChanged(changedSession)
    override fun onSessionFinished(finishedSession: TerminalSession) = delegate.onSessionFinished(finishedSession)
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = delegate.onCopyTextToClipboard(session, text)
    override fun onPasteTextFromClipboard(session: TerminalSession?) = delegate.onPasteTextFromClipboard(session)
    override fun onBell(session: TerminalSession) = delegate.onBell(session)
    override fun onColorsChanged(session: TerminalSession) = delegate.onColorsChanged(session)
    override fun onTerminalCursorStateChange(state: Boolean) = delegate.onTerminalCursorStateChange(state)
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = delegate.setTerminalShellPid(session, pid)
    override fun getTerminalCursorStyle(): Int? = delegate.getTerminalCursorStyle()
    override fun logError(tag: String?, message: String?) = delegate.logError(tag, message)
    override fun logWarn(tag: String?, message: String?) = delegate.logWarn(tag, message)
    override fun logInfo(tag: String?, message: String?) = delegate.logInfo(tag, message)
    override fun logDebug(tag: String?, message: String?) = delegate.logDebug(tag, message)
    override fun logVerbose(tag: String?, message: String?) = delegate.logVerbose(tag, message)
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
        delegate.logStackTraceWithMessage(tag, message, e)

    override fun logStackTrace(tag: String?, e: Exception?) = delegate.logStackTrace(tag, e)
}

private data class PolledDrain(
    val bytes: Int,
    val scheduledAtNanos: Long?,
)

/**
 * Reflective access to package-private fields of [TerminalSession]. We
 * deliberately keep this isolated in one private helper so the rest of the
 * codebase consumes only well-named accessors. The reflection targets are
 * stable across the Termux releases we vendor (verified against the
 * upstream commit pinned in `shared/core-terminal/VENDORED.md`).
 *
 * If a future Termux refresh renames any of these fields, the bridge will
 * fail at first use with a clear [IllegalStateException] from
 * [resolveField] — easier to spot than a silent behavioural regression.
 */
private object SessionReflection {

    private val mEmulatorField: Field by lazy {
        resolveField(TerminalSession::class.java, "mEmulator")
    }
    private val mShellPidField: Field by lazy {
        resolveField(TerminalSession::class.java, "mShellPid")
    }
    private val mProcessToTerminalIOQueueField: Field by lazy {
        resolveField(TerminalSession::class.java, "mProcessToTerminalIOQueue")
    }
    private val mTerminalToProcessIOQueueField: Field by lazy {
        resolveField(TerminalSession::class.java, "mTerminalToProcessIOQueue")
    }
    private val mMainThreadHandlerField: Field by lazy {
        resolveField(TerminalSession::class.java, "mMainThreadHandler")
    }

    private val availableProcessOutputBytesMethod by lazy {
        // `TerminalSession.availableProcessOutputBytes() -> int` (package-private,
        // added for #803). Reflected so the bridge + scheduler can live outside
        // `com.termux.terminal`.
        TerminalSession::class.java
            .getDeclaredMethod("availableProcessOutputBytes")
            .apply { isAccessible = true }
    }

    private val byteQueueWriteMethod by lazy {
        // `ByteQueue.write(byte[] buffer, int offset, int length) -> boolean`
        // Package-private class; access via reflection so the bridge does
        // not need to live in `com.termux.terminal`.
        Class.forName("com.termux.terminal.ByteQueue")
            .getDeclaredMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }

    private val byteQueueReadMethod by lazy {
        // `ByteQueue.read(byte[] buffer, boolean block) -> int`
        Class.forName("com.termux.terminal.ByteQueue")
            .getDeclaredMethod("read", ByteArray::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }

    private val byteQueueCloseMethod by lazy {
        // `ByteQueue.close()`: wakes a blocking `read(..., block = true)`.
        Class.forName("com.termux.terminal.ByteQueue")
            .getDeclaredMethod("close")
            .apply { isAccessible = true }
    }

    fun setEmulator(session: TerminalSession, emulator: TerminalEmulator) {
        mEmulatorField.set(session, emulator)
    }

    fun setShellPid(session: TerminalSession, pid: Int) {
        mShellPidField.setInt(session, pid)
    }

    fun getMainThreadHandler(session: TerminalSession): Handler {
        return mMainThreadHandlerField.get(session) as Handler
    }

    /**
     * Issue #803: bytes still buffered in the process→terminal queue (not yet
     * parsed by the emulator). The [MainThreadDrainScheduler] reads this on the
     * main looper to drive frame-budgeted drain continuation.
     */
    fun availableProcessOutputBytes(session: TerminalSession): Int {
        return availableProcessOutputBytesMethod.invoke(session) as Int
    }

    /**
     * Push bytes into the session's "incoming from PTY" queue. Returns
     * `true` if the queue accepted them (false if the queue is closed,
     * matching `ByteQueue.write`'s contract).
     */
    fun writeProcessToTerminalQueue(
        session: TerminalSession,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        val queue = mProcessToTerminalIOQueueField.get(session)
        return byteQueueWriteMethod.invoke(queue, data, offset, length) as Boolean
    }

    /**
     * Block-read from the session's "outgoing to PTY" queue. Returns the
     * number of bytes read, or -1 if the queue was closed.
     */
    fun readTerminalToProcessQueue(session: TerminalSession, buffer: ByteArray): Int {
        val queue = mTerminalToProcessIOQueueField.get(session)
        return byteQueueReadMethod.invoke(queue, buffer, true) as Int
    }

    fun closeTerminalToProcessQueue(session: TerminalSession) {
        val queue = mTerminalToProcessIOQueueField.get(session)
        byteQueueCloseMethod.invoke(queue)
    }

    private fun resolveField(cls: Class<*>, name: String): Field {
        return try {
            cls.getDeclaredField(name).apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            throw IllegalStateException(
                "PocketShell SshTerminalBridge: expected field `$name` on " +
                    "${cls.name} (upstream Termux). Vendored source was " +
                    "refreshed without updating the bridge?",
                e,
            )
        }
    }
}
