package com.pocketshell.core.terminal.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.bridge.TerminalQueryResponseSanitizer
import com.pocketshell.core.terminal.selection.DefaultTerminalMatcher
import com.pocketshell.core.terminal.selection.TerminalMatch
import com.pocketshell.core.terminal.selection.TerminalMatcher
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

enum class TerminalRawInputPolicy {
    FlushSmartText,
    ClearSmartText,
}

/**
 * Compose-friendly state holder for [TerminalSurface].
 *
 * Bridges the imperative, callback-driven Termux terminal API into a small
 * Compose-friendly surface: a write channel into the session, a read [Flow]
 * of bytes the session emits, and a coarse "attached" flag the UI can
 * observe.
 *
 * ## Lifecycle
 *
 * - Create via [rememberTerminalSurfaceState] from a `@Composable` function.
 *   The returned state outlives recompositions but is bound to the calling
 *   composition's scope.
 * - Call [attach] to bind a live [TerminalSession] obtained from elsewhere
 *   (issue #9 wires SSH-backed sessions; this issue ships the surface
 *   without a session source).
 * - Call [writeInput] to forward user input (typed text, key bindings) into
 *   the session. Calls made before [attach] are dropped — the state holder
 *   does not buffer pre-attach input.
 * - Collect [output] to observe bytes the session emits (typically driven by
 *   the session's PTY producer thread; not yet wired in this issue).
 * - Call [detach] to release the session reference.
 *
 * ## Why a `TerminalSession?` and not the emulator directly
 *
 * Termux's [TerminalView] is hard-wired to read from a
 * [com.termux.terminal.TerminalSession] (via `attachSession`). We mirror that
 * contract so this state holder can sit transparently between the View and
 * whatever produces the session.
 *
 * ## JNI handling (cross-reference `VENDORED.md`)
 *
 * Issue #9 ships a stub `libtermux.so` (built from
 * `shared/core-terminal/src/main/cpp/pocketshell_termux_stub.c`) so that the
 * vendored `com.termux.terminal.JNI` static initializer no longer throws
 * `UnsatisfiedLinkError`. The stub's `setPtyWindowSize` is a safe no-op,
 * which is the only JNI entry point reached at runtime once a session is
 * attached via [attachExternalProducer] — the bridge pre-populates
 * `TerminalSession.mEmulator` so the JNI subprocess-spawning path
 * (`initializeEmulator` → `JNI.createSubprocess`) is never taken.
 *
 * Callers that construct their own [TerminalSession] directly (without going
 * through [SshTerminalBridge]) still must NOT call
 * [TerminalSession.initializeEmulator] — the stub `createSubprocess`
 * intentionally returns a bogus fd that would break the upstream input/output
 * threads. Use [attachExternalProducer] for the supported path.
 *
 * @see TerminalSurface
 * @see rememberTerminalSurfaceState
 */
@Stable
class TerminalSurfaceState(
    private val externalProducerDispatcher: CoroutineDispatcher,
) {

    public constructor() : this(Dispatchers.IO)

    private var _session: TerminalSession? by mutableStateOf(null)

    /**
     * Backing flow for [output]. Replay = 0 — bytes are only delivered to
     * collectors active at the time they arrive. The live terminal producer
     * treats this flow as a best-effort side channel: terminal rendering is
     * authoritative and must not wait behind slow diagnostic collectors.
     */
    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Bytes the terminal session emitted since the most recent active
     * collection started. Cold collectors get no replay, and slow collectors
     * may miss live chunks — by the time the UI is in the composition, the
     * [TerminalView] already owns the authoritative byte stream via the
     * emulator. This flow exists for non-View consumers (e.g. logging,
     * automation, tests) and must not backpressure terminal rendering.
     *
     * Not yet wired in issue #8 — the producer side belongs to #9's
     * PTY/SSH plumbing. The flow is exposed today so downstream callers can
     * subscribe without waiting for a follow-up API change.
     */
    val output: SharedFlow<ByteArray> get() = _output.asSharedFlow()

    /**
     * True iff a [TerminalSession] is currently attached to this state.
     * Backed by Compose state, so reading it inside a composable reactively
     * recomposes when the value changes.
     */
    val isAttached: Boolean get() = _session != null

    /**
     * The currently attached [TerminalSession], or `null` when [isAttached]
     * is false. Exposed `internal` so the [TerminalSurface] composable can
     * push it into the underlying [TerminalView]; production code should
     * stay on the public surface ([writeInput], [output]).
     */
    internal val session: TerminalSession? get() = _session

    /**
     * Render requests emitted by Termux session callbacks. This is deliberately
     * not Compose state: remote output can arrive in tight bursts, and each
     * burst should redraw the Android [TerminalView] without recomposing the
     * Compose wrapper.
     */
    private val _renderRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val renderRequests: SharedFlow<Unit> get() = _renderRequests.asSharedFlow()

    /**
     * Callback fired when the embedded text-selection action mode's "Copy"
     * button is tapped. Issue #175 wires the [TerminalSurface] composable to
     * install a default sink that copies the selected text into the system
     * clipboard, but tests substitute a recording fake so they can assert
     * what bytes flowed without standing up a real
     * [android.content.ClipboardManager].
     *
     * `null` (the default) means: drop the text on the floor — matching the
     * pre-#175 behaviour where the vendored `TextSelectionCursorController`
     * called into the session's client but the client was a no-op. New
     * surfaces should install a sink via [setOnCopySelection] before
     * presenting the surface to the user.
     */
    @Volatile
    private var onCopySelection: ((String) -> Unit)? = null

    @Volatile
    private var smartTextStagingBridge: ((TerminalRawInputPolicy) -> Unit)? = null

    /**
     * Install or replace the [onCopySelection] callback. Pass `null` to
     * detach. Called by [TerminalSurface] on composition to wire the
     * system clipboard; tests override with a recording fake.
     */
    public fun setOnCopySelection(callback: ((String) -> Unit)?) {
        onCopySelection = callback
    }

    /**
     * Apply a SmartText staging policy before app-level raw input paths send
     * bytes outside [TerminalView]'s IME/key-event path. Enter-like actions
     * can flush staged text first; interrupt/navigation/hotkey actions clear
     * it so stale text cannot later flush after raw bytes.
     */
    public fun prepareForRawTerminalInput(policy: TerminalRawInputPolicy) {
        smartTextStagingBridge?.invoke(policy)
    }

    internal fun setSmartTextStagingBridge(callback: ((TerminalRawInputPolicy) -> Unit)?) {
        smartTextStagingBridge = callback
    }

    public fun setSmartTextStagingBridgeForTest(callback: ((TerminalRawInputPolicy) -> Unit)?) {
        setSmartTextStagingBridge(callback)
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            _renderRequests.tryEmit(Unit)
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            // The vendored TextSelectionCursorController's COPY action calls
            // TerminalSession.onCopyTextToClipboard(selectedText). Forward to
            // the host-provided sink so the selected text actually reaches
            // the system clipboard (issue #175). A null sink (pre-#175
            // behaviour) silently drops the text — kept for tests that do not
            // care about clipboard side effects.
            onCopySelection?.invoke(text)
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            _renderRequests.tryEmit(Unit)
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            _renderRequests.tryEmit(Unit)
        }

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
     * Bind a live [TerminalSession] to this state holder. The next
     * recomposition of [TerminalSurface] will attach it to the underlying
     * [TerminalView].
     *
     * Attaching twice with the same session is a no-op. Attaching a new
     * session over an existing one replaces it; the old session is NOT
     * stopped — that is the caller's responsibility (issue #9 owns SSH
     * session lifecycle).
     */
    fun attach(session: TerminalSession) {
        if (_session === session) return
        _session = session
    }

    /**
     * Release the current session reference. The [TerminalView] keeps
     * rendering its existing emulator state until something else replaces
     * it; this method only severs the state-holder ↔ session link.
     */
    fun detach() {
        _session = null
    }

    /**
     * Forward bytes into the attached session as user input (e.g. typed
     * characters, key codes already encoded as terminal escape sequences).
     *
     * No-op when [isAttached] is false. Empty arrays are also a no-op.
     *
     * Issue #8 does not invoke this from any code path; it is provided for
     * #9's input wiring and for tests that drive the surface synthetically.
     */
    fun writeInput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val s = _session ?: return
        s.write(bytes, 0, bytes.size)
    }

    /**
     * Append already-known remote output directly into the attached
     * emulator. This is used by tmux reattach to seed a new pane with a
     * `capture-pane` snapshot before future `%output` events arrive.
     */
    fun appendRemoteOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val activeBridge = bridge ?: return
        val clean = if (sanitizeQueryResponses) {
            TerminalQueryResponseSanitizer.sanitize(bytes)
        } else {
            bytes
        }
        if (clean.isEmpty()) return
        // Issue #468: apply this seed snapshot, then release any live `%output`
        // bytes that were buffered behind the seed gate while the
        // `capture-pane` round-trip was in flight — in their original arrival
        // order, after the seed. When the gate was never closed (plain SSH
        // surface, or a re-seed) this is just an ordinary feed plus an
        // already-open-gate no-op.
        activeBridge.seedThenOpenGate(clean)
        _output.tryEmit(clean)
        bufferTick.value = bufferTick.value + 1
    }

    /**
     * Issue #468: open the seed gate without applying a snapshot, flushing any
     * buffered live `%output` in order. Called when the `capture-pane` seed
     * never arrives (capture failed, older tmux) so live output is never
     * permanently swallowed. No-op when no producer is attached or the gate
     * is already open.
     */
    fun openSeedGateWithoutSeed() {
        bridge?.openGateFlushingPending()
    }

    /**
     * Test-only seam: push synthetic output bytes into [output] without
     * needing a real PTY. Used by [TerminalSurface]'s `@Preview` and by the
     * module's unit tests to exercise the flow without standing up a
     * session.
     *
     * Best-effort side channel: if a collector falls behind, the oldest
     * buffered side-channel bytes may be dropped (`BufferOverflow.DROP_OLDEST`)
     * so rendering-side output never waits on diagnostics or tests. Returns
     * true after the payload is accepted by the side channel; this flow is
     * never closed.
     */
    internal suspend fun emitOutputForTesting(bytes: ByteArray): Boolean {
        _output.emit(bytes)
        bufferTick.value = bufferTick.value + 1
        return true
    }

    /**
     * Matcher used by [flowOfMatches] to extract tap-target candidates from
     * the screen buffer. Defaults to the catch-all [DefaultTerminalMatcher];
     * tests substitute fakes via [setMatcher] without rebuilding the state
     * holder. The field is `@Volatile` because the flow runs on a background
     * dispatcher and the caller may swap matchers at any time.
     */
    @Volatile
    private var matcher: TerminalMatcher = DefaultTerminalMatcher()

    /**
     * Counter that ticks on every meaningful change to the visible screen
     * (bytes appended via [attachExternalProducer] or pushed through
     * [emitOutputForTesting]). [flowOfMatches] debounces collections of this
     * tick to amortise matcher invocations across rapid output bursts.
     *
     * Using a counter rather than re-emitting the whole transcript keeps the
     * tick allocation-free; the matcher pulls the text on demand when the
     * debounce window closes.
     */
    private val bufferTick = MutableStateFlow(0L)

    /**
     * Read-only view of [bufferTick] for tests that want to observe ticks
     * without subscribing to the (debounced, heavier-weight) [flowOfMatches].
     */
    internal val bufferTicks: StateFlow<Long> get() = bufferTick.asStateFlow()

    /**
     * Stream of [TerminalMatch] lists derived from the visible terminal
     * transcript. Each emission reflects the most recent screen state at the
     * moment the debounce window closed — intermediate burst emissions are
     * coalesced.
     *
     * Cadence: [MATCH_DEBOUNCE_MS] of silence after the last output before a
     * match pass runs. Tuned to be longer than a typical `printf`-burst
     * (which fires many small writes in <10 ms) but short enough that a user
     * who pauses for half a second sees fresh matches.
     *
     * Collectors get an immediate first emission (the tick StateFlow seeds
     * with `0L`) so the UI does not have to wait for the first output to
     * render an (empty) match list.
     *
     * The matcher runs on the collector's dispatcher; callers that find this
     * too expensive can `.flowOn(Dispatchers.Default)` at their consumption
     * site. We deliberately do not pin the dispatcher here — different
     * surfaces (UI, automation, logging) have different ideal placements.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val flowOfMatches: Flow<List<TerminalMatch>> = bufferTick
        .debounce(MATCH_DEBOUNCE_MS)
        .map { snapshotMatches() }
        .distinctUntilChanged()

    /**
     * Replace the active [TerminalMatcher]. The next [flowOfMatches] emission
     * uses the new matcher; in-flight emissions complete with the matcher
     * that was active when the debounce window closed (a benign race that
     * does not corrupt state).
     *
     * Exposed primarily for tests; production code is expected to stick with
     * [DefaultTerminalMatcher].
     */
    fun setMatcher(matcher: TerminalMatcher) {
        this.matcher = matcher
        // Force a re-run so collectors of [flowOfMatches] pick up the change
        // without waiting for the next output byte.
        bufferTick.value = bufferTick.value + 1
    }

    internal fun currentMatcher(): TerminalMatcher = matcher

    /**
     * Pull the current visible-transcript text from the attached session and
     * run the matcher across it. Returns an empty list when no session is
     * attached or the session has no emulator yet (the View has not yet laid
     * out, etc.).
     *
     * Visible for [emitMatchesForTesting] and for the debounced
     * [flowOfMatches] map step.
     */
    internal fun snapshotMatches(): List<TerminalMatch> {
        val emulator = _session?.emulator ?: return emptyList()
        val text = try {
            emulator.screen.transcriptText
        } catch (_: Throwable) {
            // The vendored emulator can throw `ArrayIndexOutOfBoundsException`
            // mid-resize; treat that as "no matches this tick" rather than
            // surfacing the crash to the UI thread.
            return emptyList()
        }
        return matcher.matches(text)
    }

    /**
     * Test-only seam: read the text the bridge's emulator has actually
     * rendered onto its cell grid. Used by the issue #248 regression test to
     * prove a query-response leak never reaches the visible transcript.
     * Returns an empty string when no bridge is attached.
     */
    internal fun renderedTranscriptForTesting(): String =
        bridge?.emulator?.screen?.transcriptText.orEmpty()

    /**
     * Bridge currently feeding bytes into the emulator. Created by
     * [attachExternalProducer]; held so subsequent calls to [writeInput]
     * route through the same session, and so [detachExternalProducer]
     * (or composition disposal) can stop the drainer thread.
     */
    @Volatile
    private var bridge: SshTerminalBridge? = null

    /**
     * Issue #248: when true, terminal query-*response* sequences are stripped
     * from inbound bytes before they reach the emulator's cell grid. Set by
     * [attachExternalProducer] for tmux control-mode bridges (where a
     * `capture-pane` replay can otherwise seed a window with raw OSC/DA reply
     * text). Kept false for plain SSH surfaces so they keep full fidelity.
     */
    @Volatile
    private var sanitizeQueryResponses: Boolean = false

    /**
     * Job owning the producer-collection coroutine. Cancelled by
     * [detachExternalProducer] or when the surface leaves the composition.
     */
    private var producerJob: Job? = null

    /**
     * Attach a remote byte producer (typically the stdout of an SSH shell
     * channel from `core-ssh`) to this state's terminal emulator, and
     * optionally a stdin sink that the emulator's user-input writes are
     * forwarded to.
     *
     * Internally builds a [SshTerminalBridge] which:
     *
     * - Constructs a real [TerminalSession] (no JNI subprocess — see the
     *   bridge's doc).
     * - Pre-installs a [com.termux.terminal.TerminalEmulator] on the session
     *   so [com.termux.view.TerminalView]'s first layout pass does not call
     *   into `JNI.createSubprocess`. A PocketShell-shipped stub
     *   `libtermux.so` no-ops `JNI.setPtyWindowSize` so subsequent resizes
     *   are safe too.
     * - Starts a background drainer that forwards user input from the
     *   session's outbound queue to [remoteStdin].
     * - Launches a coroutine parented by [scope] that collects [stdout] on
     *   a background dispatcher and pumps each byte array into the emulator.
     *
     * The [TerminalSurface] composable will pick up the bridge's
     * [TerminalSession] from this state and attach it to the view, so the
     * canvas redraws as bytes arrive.
     *
     * Replays / multiple attachments: calling [attachExternalProducer] a
     * second time stops the previous bridge first, then starts a new one.
     *
     * @param scope the [CoroutineScope] in which the stdout-collection
     *   coroutine runs. Pass the calling composable's scope (from
     *   `rememberCoroutineScope()`) so cancellation follows composition
     *   lifecycle. The returned [Job] is also a child of this scope.
     * @param stdout the byte stream produced by the remote shell. Each
     *   emission is appended to the emulator atomically.
     * @param remoteStdin optional sink for user input typed into the
     *   emulator. If `null`, the emulator still renders output but user
     *   keystrokes are dropped at the bridge's input drainer.
     * @param suppressQueryResponses when true, terminal-generated query
     *   replies are not written back to [remoteStdin], AND inbound query
     *   *response* sequences (DA/OSC-colour/cursor-position replies) are
     *   stripped from output before it reaches the emulator's cell grid
     *   (issue #248). Keep this false for normal SSH surfaces; tmux
     *   control-mode bridges enable it because replies would otherwise leak
     *   through `send-keys` (outbound) or paint raw reply text onto the grid
     *   after a `capture-pane` replay (inbound).
     * @return a [Job] that completes when the producer flow terminates or
     *   the scope is cancelled.
     */
    public fun attachExternalProducer(
        scope: CoroutineScope,
        stdout: Flow<ByteArray>,
        remoteStdin: OutputStream? = null,
        suppressQueryResponses: Boolean = false,
        awaitSeed: Boolean = false,
        onTerminalFeedFailure: ((Throwable) -> Unit)? = null,
    ): Job {
        // Tear down any existing bridge so we never have two producers
        // racing on the same emulator.
        detachExternalProducer()

        val newBridge = SshTerminalBridge(client = sessionClient)
        // Issue #468: for tmux panes the live `%output` producer is attached
        // here but the pane is painted from a `capture-pane` snapshot a moment
        // later (via [appendRemoteOutput]). Close the seed gate up front so
        // live deltas are buffered in order and cannot race the snapshot —
        // the snapshot's `ESC[2J` clear would otherwise wipe live bytes and
        // strand frames (the #468 garble). The gate is opened by the seed in
        // [appendRemoteOutput], or by [openSeedGateWithoutSeed] if no seed
        // ever lands.
        if (awaitSeed) {
            newBridge.closeSeedGate()
        }
        newBridge.setRemoteStdin(remoteStdin)
        newBridge.emulator.setSuppressQueryResponses(suppressQueryResponses)
        // Issue #248: tmux control-mode bridges also strip inbound query
        // *responses* (DA/OSC-colour/cursor-position replies) so a
        // `capture-pane` replay can't paint raw reply text onto the grid.
        // Mirror the suppression scope: only bridge mode strips, plain SSH
        // surfaces stay untouched.
        sanitizeQueryResponses = suppressQueryResponses
        bridge = newBridge

        // Bind the bridge's session to the View via the existing `attach`
        // pathway. `TerminalView.attachSession` zeros its internal emulator
        // reference and triggers a re-layout, at which point the View's
        // `updateSize` resizes the bridge's pre-installed emulator to match
        // the on-screen size.
        attach(newBridge.session)

        val job = scope.launch(externalProducerDispatcher) {
            try {
                stdout.collect { bytes ->
                    if (bytes.isNotEmpty()) {
                        try {
                            val clean = if (suppressQueryResponses) {
                                TerminalQueryResponseSanitizer.sanitize(bytes)
                            } else {
                                bytes
                            }
                            if (clean.isNotEmpty()) {
                                newBridge.feedBytes(clean)
                                _output.tryEmit(clean)
                                // Tick the buffer signal so debounced consumers of
                                // [flowOfMatches] re-run the detector. Cheap: just a
                                // counter increment.
                                bufferTick.value = bufferTick.value + 1
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            runCatching { onTerminalFeedFailure?.invoke(t) }
                            throw TerminalProducerFeedFailure(t)
                        }
                    }
                }
            } catch (t: TerminalProducerFeedFailure) {
                // Feed/render failures are local to the terminal surface. The
                // owner callback above decides how to expose recovery without
                // letting the producer exception escape into SSH/tmux lifecycle.
            } finally {
                // If the producer flow completes naturally (SSH session
                // closed), tear the bridge down so the View's references
                // drop cleanly.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    detachCompletedExternalProducer(newBridge)
                }
            }
        }
        producerJob = job
        return job
    }

    /**
     * Stop the producer-collection coroutine and the bridge's input
     * drainer. Safe to call multiple times; no-op when nothing is attached.
     *
     * The underlying [TerminalSession] reference is also detached from
     * this state so the next [TerminalSurface] composition does not see a
     * stale session.
     */
    public fun detachExternalProducer() {
        producerJob?.cancel()
        producerJob = null
        bridge?.stop()
        bridge = null
        sanitizeQueryResponses = false
        detach()
    }

    private fun detachCompletedExternalProducer(completedBridge: SshTerminalBridge) {
        if (bridge !== completedBridge) return
        producerJob = null
        completedBridge.stop()
        bridge = null
        sanitizeQueryResponses = false
        detach()
    }

    private companion object {
        /**
         * Debounce window for [flowOfMatches]. Tuned so a single `printf`
         * burst (many small writes within ~50 ms) coalesces into one match
         * pass, but a user who pauses for half a second sees fresh matches.
         */
        private const val MATCH_DEBOUNCE_MS = 250L
    }
}

private class TerminalProducerFeedFailure(cause: Throwable) : RuntimeException(cause)
