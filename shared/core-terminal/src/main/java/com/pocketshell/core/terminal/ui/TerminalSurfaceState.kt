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
     * Force-full-repaint requests (PocketShell #721). Unlike [renderRequests]
     * — which feed the #469 dirty-region path and repaint only changed rows —
     * a signal here means "the EXISTING screen content must be redrawn from the
     * buffer, regardless of the renderer's dirty cache". It is emitted at the
     * rare reveal/seed boundaries where freshly-written cells would otherwise
     * paint over a black/cleared canvas: namely a tmux reattach re-seed
     * ([appendRemoteOutput]). [TerminalSurface] collects this and calls
     * [com.termux.view.TerminalView.forceFullRepaint]. Kept off Compose state
     * for the same burst-coalescing reasons as [renderRequests].
     *
     * `replay = 1` (PocketShell #879): on a beyond-grace reconnect the pane,
     * its [TerminalSurfaceState], and its [TerminalView] are all re-created and
     * the active pane is seeded from a full `capture-pane` snapshot BEFORE the
     * fresh [TerminalSurface] subscribes its repaint collector (the #640
     * seed-before-reveal contract). With `replay = 0` that seed's repaint
     * `tryEmit` fired while no collector was attached, so the late-subscribing
     * [TerminalView] never received it — Termux's #469 dirty cache then clipped
     * the next draw to only the changed rows over a black canvas, leaving the
     * seeded-but-"clean" rows black (the #553/#721 partial-blank-after-reconnect
     * class on the previously-untested full-reconnect path). Replaying the
     * most-recent full-repaint request to a late subscriber closes that drop.
     * Harmless in steady state: one coalesced full repaint on bind, then the
     * #469 dirty path resumes.
     */
    private val _fullRepaintRequests = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val fullRepaintRequests: SharedFlow<Unit> get() = _fullRepaintRequests.asSharedFlow()

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
        // Issue #721: a re-seed applies the captured snapshot to the emulator
        // buffer but does not change which rows the renderer's #469 dirty cache
        // considers stale — so a plain render request would repaint only freshly
        // changed cells and leave the rest of the existing screen black. Signal a
        // FULL repaint so every row is redrawn straight from the buffer.
        _fullRepaintRequests.tryEmit(Unit)
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
     * Test-only seam (PocketShell #879): fire a full-repaint request exactly
     * as the reattach re-seed does in [appendRemoteOutput], WITHOUT needing a
     * real [SshTerminalBridge]. Lets a unit test reproduce the beyond-grace
     * re-create ordering — seed emits the repaint BEFORE the fresh
     * [TerminalSurface]'s collector subscribes — and assert that with
     * `replay = 1` a late subscriber still receives the most-recent request
     * (with `replay = 0` it was silently dropped, leaving the View black).
     */
    internal fun emitFullRepaintRequestForTesting(): Boolean =
        _fullRepaintRequests.tryEmit(Unit)

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
     * Issue #662: true when the attached emulator's visible screen has rendered
     * NOTHING — every cell is blank/whitespace. A black pane on a LIVE tmux
     * connection (the maintainer's "every window is black, just a cursor"
     * symptom) is exactly this state: the grid was cleared (capture-pane seed
     * never landed, or a reflow/resize wiped it) and the idle remote app emits
     * no fresh `%output` to repaint it. The tmux ViewModel polls this after a
     * warm attach / window-switch and re-seeds a blank pane from a fresh
     * `capture-pane` so the user never stares at a black pane while tmux's grid
     * still holds the content.
     *
     * Returns false (NOT blank) when no emulator is attached yet — an
     * unattached surface is "not yet a black pane", and re-seeding it would be
     * premature. The emulator can throw mid-resize; that is treated as
     * "unknown", i.e. not blank, so we never spuriously re-seed on a transient.
     */
    fun visibleScreenIsBlank(): Boolean {
        val emulator = bridge?.emulator ?: _session?.emulator ?: return false
        val text = try {
            emulator.screen.transcriptText
        } catch (_: Throwable) {
            return false
        }
        return text.isBlank()
    }

    /**
     * Issue #553 (epic #687 Phase 3, J2) — true when the visible screen is PARTIALLY
     * blank: it has SOME live content (so [visibleScreenIsBlank] is false) but the vast
     * majority of the viewport is empty. This is the maintainer's "only a timer, rest
     * blank" symptom after a within-grace reattach: tmux's `-CC` control client never
     * re-emits an idle pane's existing frame, so a reflow during a brief link blip can
     * wipe the static viewport while a single per-second status/timer line keeps
     * repainting. Because that ONE live line makes `transcriptText` non-blank, the
     * blank-gated heal ([visibleScreenIsBlank]) SKIPS the pane and the static content is
     * never restored.
     *
     * This is a DIAGNOSTIC signal only: the P3 within-grace reattach reseed restores the
     * FULL viewport UNCONDITIONALLY (it does not gate on this), so the heal decision is
     * never at the mercy of a fragile threshold. It is exposed so the ViewModel can log
     * the partial-blank state and tests can assert the exact "one live line, rest blank"
     * pre-reseed condition.
     *
     * Returns false when no emulator is attached, when the screen is FULLY blank (that is
     * [visibleScreenIsBlank]'s job), or when a comfortable share of the visible rows
     * carry content (a normally-painted viewport is NOT "partially blank").
     *
     * NOTE — this is a best-effort HEURISTIC, not a precise oracle. `transcriptText` is
     * trimmed of trailing blank rows, so the live-line FRACTION is measured against the
     * emulator's actual row count (`mRows`), and a lone timer line is indistinguishable
     * from a single-line fresh prompt. That ambiguity is acceptable precisely because the
     * P3 reattach reseed does NOT branch on this — it restores the full viewport
     * unconditionally — so a false positive only adds a diagnostic log line, never a wrong
     * heal decision.
     */
    fun visibleScreenIsPartiallyBlank(): Boolean {
        val emulator = bridge?.emulator ?: _session?.emulator ?: return false
        val text = try {
            emulator.screen.transcriptText
        } catch (_: Throwable) {
            return false
        }
        if (text.isBlank()) return false
        val nonBlank = text.split('\n').count { it.isNotBlank() }
        // A "partial blank" is a viewport with a handful of live lines amid an otherwise
        // empty grid. Bound the absolute live-line count AND the live FRACTION of the
        // emulator's visible rows (`mRows`, NOT the trimmed transcript) so the timer-only
        // case holds and a normally-populated shell/agent pane is excluded.
        if (nonBlank > PARTIAL_BLANK_MAX_LIVE_LINES) return false
        val totalRows = try {
            emulator.mRows
        } catch (_: Throwable) {
            return false
        }
        if (totalRows <= 0) return false
        val liveFraction = nonBlank.toDouble() / totalRows.toDouble()
        return liveFraction <= PARTIAL_BLANK_MAX_LIVE_FRACTION
    }

    /**
     * Issue #941 (black-screen residual B1) — true when the visible screen is FULLY
     * blank ([visibleScreenIsBlank]) OR partially blank ([visibleScreenIsPartiallyBlank]):
     * the union "the active pane looks lost (black, or one-live-line-rest-black)".
     *
     * The maintainer's "I sent a message and everything became black" symptom is a
     * PARTIAL black — a `%output` overpaint (or a plain session switch reveal) wiped the
     * static viewport and left one live line. The reattach/manual-Redraw heals already
     * branch on `blank || partialBlank` inline (see
     * [com.pocketshell.app.tmux.TmuxSessionViewModel.reseedActivePaneForReattach] and
     * `maybeHealActivePaneOnNoOpResize`), but the SWITCH-reveal gate and the connected
     * blank watchdog used pure `visibleScreenIsBlank()` — so a partial-black pane reached
     * via a switch or a send-overpaint reads "not blank", passes the gate, and is never
     * reseeded. This combined oracle is the single "needs heal" predicate those gates now
     * share, so the partial-black case is healed on EVERY reveal/watchdog path, not only
     * on reattach.
     *
     * Inherits [visibleScreenIsPartiallyBlank]'s best-effort-heuristic caveat: a lone
     * fresh-prompt line is indistinguishable from a lone timer line, so this can be true
     * for a legitimately-mostly-empty pane. That is acceptable because the heal it gates
     * is a `capture-pane` full-viewport restore — re-seeding a real prompt re-paints the
     * SAME authoritative content (idempotent, no visible change), and the callers guard
     * against reseed-thrash by only healing once per reveal / per send-quiescence, not on
     * a tight loop.
     */
    fun visibleScreenIsBlankOrPartiallyBlank(): Boolean =
        visibleScreenIsBlank() || visibleScreenIsPartiallyBlank()

    /**
     * Issue #966/#967: the count of non-whitespace cells the emulator has
     * actually rendered onto its visible grid. Cheap (a single transcript read +
     * char scan) so a watchdog can call it every tick without an IO round-trip.
     *
     * Returns 0 when no emulator is attached or the emulator throws mid-resize
     * ("unknown" → treated as empty, never spuriously a stale-render heal on a
     * transient).
     */
    fun renderedNonBlankCharCount(): Int {
        val emulator = bridge?.emulator ?: _session?.emulator ?: return 0
        val text = try {
            // Issue #966/#967: measure ONLY the visible screen, NOT the scrollback.
            // After a `CSI 2J` the visible viewport is black but `transcriptText`
            // still carries the scrolled-off burst, which would mask the stale
            // render (the #966 burst variant the user perceives as a black pane).
            emulator.screen.visibleScreenText
        } catch (_: Throwable) {
            return 0
        }
        return text.count { !it.isWhitespace() }
    }

    /**
     * Issue #1175 — the number of VISIBLE viewport rows the emulator's grid holds
     * (the pane's on-screen height). NOT a black/blank predicate: a plain geometry
     * read of the same `emulator.screen.visibleScreenRows` the divergence/lost-frame
     * predicates already use, exposed so the `black_frame_observed` diagnostic can
     * carry the pane geometry that distinguishes a tall-grid black pane (#807) from a
     * short one. Reuses the existing emulator read; adds no new black/blank predicate.
     *
     * Returns 0 when no emulator is attached or the emulator throws mid-resize
     * ("unknown" → 0), matching the defensive contract of the predicates above.
     */
    fun visibleRowCount(): Int {
        val emulator = bridge?.emulator ?: _session?.emulator ?: return 0
        return try {
            emulator.screen.visibleScreenRows
        } catch (_: Throwable) {
            0
        }
    }

    // -------------------------------------------------------------------------
    // Issue #1192 — SURFACE-paint confirmation seam (distinct from the MODEL grid).
    //
    // Every predicate above reads the emulator MODEL grid (`renderedNonBlankCharCount`,
    // `visibleScreenIsBlank`, `visibleRenderLostFrameVsCapture`, …). The heal oracle
    // ([com.pocketshell.app.tmux.TmuxSessionViewModel.healActivePaneIfStaleRender])
    // compares that MODEL against tmux's authoritative capture, so a black screen
    // where the on-screen SURFACE is blank while the model grid still matches tmux is
    // un-catchable BY CONSTRUCTION — the model never diverges (spike #874 GAP-1).
    //
    // This is the minimal paint-confirmation the vendored [com.termux.view.TerminalView]
    // reports from `onDraw`: `true` when it painted the emulator content (the normal
    // `mRenderer.render` path), `false` when it painted the BLACK fallback (the
    // `mEmulator == null` window or a render that threw — #966/#967). The ViewModel's
    // existing gated stale-render watchdog READS [surfaceIsBlackWhileModelHasContent]
    // on the tick it already pays for — NO new poll/timer (respects #1164). Volatile
    // longs, not Compose state: `onDraw` fires on the render thread and this is a
    // best-effort diagnostic, never a gate on rendering.
    // -------------------------------------------------------------------------

    @Volatile
    private var lastSurfaceContentPaintAtMs: Long = 0L

    @Volatile
    private var lastSurfaceBlankPaintAtMs: Long = 0L

    /**
     * Issue #1192 — called by [com.termux.view.TerminalView.onDraw] (wired in
     * [TerminalSurface]) once per painted frame. [paintedEmulatorContent] is `true`
     * for the normal `mRenderer.render` path and `false` for the black fallback
     * (`mEmulator == null`, or a render that threw and cleared the canvas). [atMs]
     * is an `elapsedRealtime()` monotonic stamp. Cheap: one volatile write, no
     * allocation, called only from `onDraw`.
     */
    fun onSurfaceFramePainted(paintedEmulatorContent: Boolean, atMs: Long) {
        if (paintedEmulatorContent) {
            lastSurfaceContentPaintAtMs = atMs
        } else {
            lastSurfaceBlankPaintAtMs = atMs
        }
    }

    /**
     * Issue #1192 — the SURFACE-only-black detector (the sixth black-frame class the
     * heal oracle cannot see). Returns `true` when BOTH:
     *
     *  1. the MODEL grid holds content ([renderedNonBlankCharCount] > 0) — so the
     *     oracle, comparing model-vs-tmux, would call this pane HEALTHY; AND
     *  2. the SURFACE is CONFIRMED black — its most recent painted frame was the
     *     black fallback ([lastSurfaceBlankPaintAtMs] is newer than
     *     [lastSurfaceContentPaintAtMs]), i.e. the View is drawing black while the
     *     model still carries the frame.
     *
     * Requires POSITIVE evidence of a black paint (a blank-paint stamp exists AND is
     * the most-recent frame): a surface that has simply not painted yet (the cold
     * pre-first-`onDraw` transient) returns `false`, so a freshly-attached-but-seeded
     * pane is never spuriously fingerprinted. Once the View paints the content, the
     * content stamp overtakes and this returns `false` again. DIAGNOSTICS ONLY — this
     * gates no reseed/heal (#721 already self-heals every KNOWN surface-blank trigger).
     */
    fun surfaceIsBlackWhileModelHasContent(): Boolean {
        if (renderedNonBlankCharCount() <= 0) return false
        return lastSurfaceBlankPaintAtMs > 0L &&
            lastSurfaceBlankPaintAtMs > lastSurfaceContentPaintAtMs
    }

    /**
     * Test-only (#1192): drive the paint-confirmation seam exactly as
     * [com.termux.view.TerminalView.onDraw] does, so a JVM unit test can reproduce
     * the surface-black-but-model-intact state WITHOUT a real Android View / render
     * thread (the #780 synthetic-state model — the CI JVM cannot run `onDraw`).
     */
    fun recordSurfaceFramePaintedForTest(paintedEmulatorContent: Boolean, atMs: Long) =
        onSurfaceFramePainted(paintedEmulatorContent, atMs)

    /**
     * Issue #966/#967 — the "mostly-black / stale render on a LIVE transport"
     * oracle. The v0.4.17 black-screen heal ([visibleScreenIsBlank] /
     * [visibleScreenIsPartiallyBlank]) only engages when the pane is FULLY blank
     * or has ≤3 live lines, so the maintainer's #966 pane — a connected Claude
     * window with a lone cursor + "3" + a scattered status line ("24m 3 / 8 / 4 /
     * 3 / 31") — reads NEITHER fully blank NOR cleanly partial-blank (the
     * fragments can exceed 3 live lines or scatter so the live FRACTION looks
     * normal), so the heal SKIPS it and the user is stranded on a black-with-
     * fragments pane while tmux's grid still holds the full content.
     *
     * This oracle closes that gap by diffing the RENDERED grid against what tmux
     * says the pane SHOULD contain (the authoritative `capture-pane` text the
     * caller passes in). It returns true — "the local render is stale/mostly-
     * black relative to tmux, re-seed it" — when BOTH:
     *
     *  1. tmux's authoritative capture carries SUBSTANTIAL content
     *     (≥ [STALE_RENDER_MIN_CAPTURE_CHARS] non-blank chars), i.e. there is a
     *     real frame to restore — not a legitimately near-empty pane; AND
     *  2. the rendered grid carries DRAMATICALLY LESS than the capture — its
     *     non-blank char count is ≤ [STALE_RENDER_MAX_RENDERED_FRACTION] of the
     *     capture's. A scattered-fragment / mostly-black render is exactly this:
     *     a handful of glyphs against a full-screen authoritative frame.
     *
     * Why diff against `capture-pane` instead of a pure heuristic: re-seeding is
     * idempotent (a full clear+repaint of tmux's authoritative grid), so a false
     * positive only re-paints the SAME content the user already sees — no visible
     * change. Anchoring on divergence-from-tmux (not "few glyphs") is what keeps
     * a legitimately sparse-but-CORRECT pane (a real shell with little output)
     * from over-firing: when the render already matches tmux, the rendered count
     * is NOT a small fraction of the capture, so this returns false.
     *
     * Returns false when no emulator is attached (nothing rendered to compare),
     * when the capture is itself near-empty (no real frame to restore — defer to
     * the blank oracle), or when the render already carries a comparable amount
     * of content (a healthy pane).
     */
    fun visibleScreenDivergesFromCapture(captureText: String): Boolean {
        val emulator = bridge?.emulator ?: _session?.emulator ?: return false
        // Compare the rendered VISIBLE viewport against the VISIBLE-equivalent of
        // tmux's capture. The heal's `capture-pane` includes scrollback (`-S -N`),
        // so comparing rendered-visible against the FULL capture would falsely read
        // a healthy pane (whose visible grid matches only the BOTTOM of tmux's
        // grid) as stale. Take the capture's last [visibleRows] non-blank lines —
        // the visible-tail — so the diff is apples-to-apples and scale-invariant.
        val visibleRows = try {
            emulator.screen.visibleScreenRows
        } catch (_: Throwable) {
            return false
        }
        if (visibleRows <= 0) return false
        val captureVisibleNonBlank = captureText
            .split('\n')
            .filter { it.isNotBlank() }
            .takeLast(visibleRows)
            .sumOf { line -> line.count { !it.isWhitespace() } }
        // tmux has (near) nothing visible for this pane → not a stale-render case;
        // the fully/partially-blank oracle owns the genuinely-empty pane.
        if (captureVisibleNonBlank < STALE_RENDER_MIN_CAPTURE_CHARS) return false
        val renderedNonBlank = renderedNonBlankCharCount()
        // The render carries a healthy share of tmux's visible content → not stale.
        val staleCeiling = (captureVisibleNonBlank * STALE_RENDER_MAX_RENDERED_FRACTION).toInt()
        return renderedNonBlank <= staleCeiling
    }

    /**
     * Issue #989 — the NON-DESTRUCTIVE-swap guard for the manual Redraw / attach
     * reseed. The seed path repaints `capture-pane` into the live buffer with a
     * leading `CSI 2J` clear (`toTerminalViewportBytes`), so the swap is in-place
     * and the clear destroys the prior frame BEFORE the new content lands. For an
     * idle alternate-screen agent (Claude/Codex) a `capture-pane` can come back
     * near-blank-but-non-empty (whitespace rows + a stray fragment) — it passes
     * the `output.isEmpty()` guard, so without this gate the clear lands and the
     * full prior frame is wiped to black-with-fragments (the maintainer's #989
     * screenshot).
     *
     * This returns true — "do NOT paint this capture; keep the last frame" — when
     * BOTH:
     *
     *  1. the fresh capture's visible tail carries near-nothing
     *     (< [NON_DESTRUCTIVE_SWAP_MIN_CAPTURE_CHARS] non-blank chars), i.e. there
     *     is no real frame to swap IN; AND
     *  2. the currently-rendered viewport carries MATERIALLY MORE than the capture
     *     would restore — so painting it would CLEAR visible content to (near)
     *     black, a net LOSS.
     *
     * It is the inverse discipline of [visibleScreenDivergesFromCapture]: that one
     * paints only when the capture has substantially MORE than the render (heal a
     * stale render); this one REFUSES to paint when the capture has substantially
     * LESS than the render (never clear-to-black). Together the reseed only ever
     * swaps TOWARD more content.
     *
     * Returns false (paint normally) when no emulator is attached, when the
     * current render is itself (near) blank (a genuinely black pane SHOULD accept
     * even a sparse capture — there is nothing to lose, and the first real frame
     * of a freshly-attached black pane must still land), or when the capture
     * carries a comparable-or-greater amount of content than the render.
     */
    fun captureWouldClearVisibleContent(captureText: String): Boolean {
        val emulator = bridge?.emulator ?: _session?.emulator ?: return false
        val visibleRows = try {
            emulator.screen.visibleScreenRows
        } catch (_: Throwable) {
            return false
        }
        if (visibleRows <= 0) return false
        val captureVisibleNonBlank = captureText
            .split('\n')
            .filter { it.isNotBlank() }
            .takeLast(visibleRows)
            .sumOf { line -> line.count { !it.isWhitespace() } }
        // The incoming capture carries real content → swapping it in is safe
        // (it is not a near-blank wipe). Defer to the normal paint.
        if (captureVisibleNonBlank >= NON_DESTRUCTIVE_SWAP_MIN_CAPTURE_CHARS) return false
        val renderedNonBlank = renderedNonBlankCharCount()
        // The current render carries little/nothing → there is nothing to lose by
        // painting the (also near-blank) capture. A freshly-attached black pane's
        // FIRST seed must still land, so a (near) blank render NEVER blocks here.
        if (renderedNonBlank < NON_DESTRUCTIVE_SWAP_MIN_RENDER_CHARS) return false
        // The render has materially MORE than this capture would restore → painting
        // would clear visible content to (near) black. Keep the last frame.
        return captureVisibleNonBlank * NON_DESTRUCTIVE_SWAP_CLEAR_RATIO < renderedNonBlank
    }

    /**
     * Issue #1176 (unifies #966/#1138/#1153) — the SINGLE coherent "the live render LOST the
     * frame" oracle. It answers ONE question against tmux's authoritative `capture-pane`: **how
     * much of the visible content tmux holds for this pane is actually on screen?** The render
     * has lost the frame when it reproduces at most [LOST_FRAME_MAX_RENDERED_FRACTION] of tmux's
     * visible non-blank content while tmux holds materially more.
     *
     * ## Why one metric (the #1176 dead-zone this closes)
     *
     * The pre-#1176 oracle UNIONED two ceilings on DIFFERENT metrics that do not compose:
     *  - a 25%-**char** ceiling (the #966 [visibleScreenDivergesFromCapture]), and
     *  - a 50%-**line** ceiling (the #1153 half-black band).
     *
     * A render carrying >25% of tmux's chars spread across >50% of the rows — a black BAND
     * covering ~a third-to-a-half of the screen — cleared BOTH ceilings and was judged HEALTHY,
     * so it was NEVER healed on any path. That was the residual black screen the maintainer kept
     * hitting on the latest release (spike #874). A char-fraction and a line-fraction ceiling can
     * each be "just above threshold" while the pane is plainly half-black. Collapsing them into
     * a SINGLE content-coverage judgment removes the dead-zone: a black band loses chars (fewer
     * non-blank cells) regardless of whether the loss reads as scattered sparsity (#966), a lone
     * surviving status line (#1138), or a contiguous band (#1153) — so one char-coverage ceiling
     * subsumes all three cases with no gap between them.
     *
     * ## The anti-thrash / distinguish-by-design gates (preserved from #1138)
     *
     *  1. tmux's visible tail must carry ≥ [STALE_RENDER_MIN_CAPTURE_CHARS] non-blank chars —
     *     there is a REAL frame to restore, not the #807 by-design near-empty alt-screen void; AND
     *  2. tmux must carry at least [PARTIAL_BLACK_HEAL_MIN_EXTRA_CHARS] MORE non-blank chars than
     *     the render — a genuine unrepainted frame, not a legitimately-short prompt whose capture
     *     ≈ render (no reseed-thrash on every watchdog tick).
     *
     * Only then does the coverage ceiling decide. A DENSE, correctly-painted pane reproduces
     * ~all of tmux's visible content (its coverage sits ABOVE the ceiling) and never heals; a
     * pane that is merely a few rows behind a streaming agent (capture holds slightly more) sits
     * above the ceiling too and is left alone — no clear-to-repaint flicker on a lagging-but-fine
     * pane. Re-seeding is idempotent (a full clear+repaint of tmux's authoritative grid), so the
     * heal only ever swaps TOWARD more content.
     *
     * Returns false when no emulator is attached (nothing rendered to judge).
     */
    fun visibleRenderLostFrameVsCapture(captureText: String): Boolean {
        val emulator = bridge?.emulator ?: _session?.emulator ?: return false
        val visibleRows = try {
            emulator.screen.visibleScreenRows
        } catch (_: Throwable) {
            return false
        }
        if (visibleRows <= 0) return false
        val captureVisibleNonBlank = captureText
            .split('\n')
            .filter { it.isNotBlank() }
            .takeLast(visibleRows)
            .sumOf { line -> line.count { !it.isWhitespace() } }
        // Gate 1 — the #807 by-design void: tmux has (near) nothing for this pane, so there is no
        // real frame to restore. Defer; do NOT heal a genuinely-empty pane to itself.
        if (captureVisibleNonBlank < STALE_RENDER_MIN_CAPTURE_CHARS) return false
        val renderedNonBlank = renderedNonBlankCharCount()
        // Gate 2 — anti-thrash / distinguish-by-design: tmux must carry MATERIALLY MORE visible
        // content than the render (a legitimately-short prompt or the agent's own clear has
        // capture ≈ render and sits under this gap — left alone).
        if (captureVisibleNonBlank - renderedNonBlank < PARTIAL_BLACK_HEAL_MIN_EXTRA_CHARS) return false
        // THE unified judgment: the render reproduces at most [LOST_FRAME_MAX_RENDERED_FRACTION]
        // of tmux's visible non-blank content → a black band / mostly-black / scattered render
        // that lost the frame tmux still holds. A fully-painted or only-slightly-behind pane sits
        // above the ceiling and is not healed.
        return renderedNonBlank.toDouble() <=
            captureVisibleNonBlank.toDouble() * LOST_FRAME_MAX_RENDERED_FRACTION
    }

    /**
     * Issue #1176 (GAP C) — the CHEAP, LOCAL-ONLY capture-gate the switch-reveal
     * ([com.pocketshell.app.tmux.TmuxSessionViewModel.awaitActivePaneSeededOrLoading]) and the
     * no-op-resize heal ([com.pocketshell.app.tmux.TmuxSessionViewModel.maybeHealActivePaneOnNoOpResize])
     * run to decide whether paying for an authoritative `capture-pane` diff (the unified
     * [visibleRenderLostFrameVsCapture] oracle) is worthwhile before revealing / after a keyboard
     * toggle. It is TRUE when the rendered viewport is sparse/banded enough to POSSIBLY be a lost
     * frame: fully blank, the ≤3-line partial-black, OR a black BAND whose live share of the
     * visible rows is at most [MAY_HAVE_LOST_FRAME_MAX_LIVE_FRACTION].
     *
     * It fires for the SAME two states the pre-#1176 gates already captured for — fully blank OR
     * the ≤3-line partial-black — PLUS the exact GAP the dead-zone left: a >3-line black BAND with
     * MORE than half the visible rows live (so the pre-#1176 gates read it "painted") but still up
     * to [MAY_HAVE_LOST_FRAME_MAX_LIVE_FRACTION] of them — the band between the old 50%-line ceiling
     * and a confidently-dense pane. A genuinely-sparse-but-correct SMALL pane (≤ half the rows live
     * — a short prompt) is deliberately NOT flagged: the pre-#1176 no-op-resize contract pays
     * nothing for it, and the steady-state watchdog remains its net. It does NOT confirm a lost
     * frame — only the `capture-pane` diff can, since a sparse-but-correct pane looks identical
     * locally — so a confidently-full pane (live rows ABOVE the ceiling) skips the capture entirely,
     * and every flagged pane is confirmed against tmux by the unified oracle before any heal fires.
     */
    fun visibleRenderMayHaveLostFrame(): Boolean {
        if (visibleScreenIsBlankOrPartiallyBlank()) return true
        val emulator = bridge?.emulator ?: _session?.emulator ?: return false
        val visibleRows = try {
            emulator.screen.visibleScreenRows
        } catch (_: Throwable) {
            return false
        }
        if (visibleRows <= 0) return false
        val liveLines = renderedVisibleNonBlankLineCount()
        if (liveLines <= 0) return true
        val liveFraction = liveLines.toDouble() / visibleRows.toDouble()
        // The #1176 dead-zone the pre-#1176 gates MISSED: MORE than half the rows live yet not
        // confidently dense. Below the lower bound the pane is genuinely sparse (the pre-#1176
        // no-op contract skips it — the "cheap no-op" for a short correct prompt); above the upper
        // bound it is confidently full.
        return liveFraction > MAY_HAVE_LOST_FRAME_MIN_LIVE_FRACTION &&
            liveFraction <= MAY_HAVE_LOST_FRAME_MAX_LIVE_FRACTION
    }

    /**
     * Issue #1153 — the count of non-blank lines the emulator has actually rendered onto its
     * currently VISIBLE viewport (scrollback excluded), the sibling of [renderedNonBlankCharCount]
     * measured in LINES. Used to judge the live-line FRACTION of the visible grid — how large the
     * black BAND left by a half-repainted send overpaint is — for [visibleRenderLostFrameVsCapture]
     * case (c) and the cheap send-heal pre-check [visibleScreenLooksSparseForSendHeal].
     *
     * Returns 0 when no emulator is attached or the visible screen is blank.
     */
    private fun renderedVisibleNonBlankLineCount(): Int {
        val emulator = bridge?.emulator ?: _session?.emulator ?: return 0
        val text = try {
            emulator.screen.visibleScreenText
        } catch (_: Throwable) {
            return 0
        }
        if (text.isBlank()) return 0
        return text.split('\n').count { it.isNotBlank() }
    }

    /**
     * Issue #1153 — a CHEAP, LOCAL-ONLY pre-check the post-send overpaint heal
     * ([com.pocketshell.app.tmux.TmuxSessionViewModel.scheduleSendOverpaintHeal]) runs each
     * settle tick to decide whether paying for an authoritative `capture-pane` diff is
     * worthwhile. It is TRUE when the rendered viewport is sparse enough to POSSIBLY be a
     * half-repainted send overpaint: fully blank, the ≤3-line partial-black
     * ([visibleScreenIsBlankOrPartiallyBlank]), OR a >3-line half-black band whose live share
     * of the visible rows is at most [LOST_FRAME_MAX_LIVE_FRACTION].
     *
     * It does NOT confirm a lost frame — only the `capture-pane` diff ([visibleRenderLostFrameVsCapture])
     * can, since a legitimately-sparse-but-correct pane looks identical locally. This gate exists
     * only to keep a DENSE, normally-painted agent response (its live rows sit above the ceiling)
     * from paying for a capture round-trip on every send.
     */
    fun visibleScreenLooksSparseForSendHeal(): Boolean {
        if (visibleScreenIsBlankOrPartiallyBlank()) return true
        val emulator = bridge?.emulator ?: _session?.emulator ?: return false
        val visibleRows = try {
            emulator.screen.visibleScreenRows
        } catch (_: Throwable) {
            return false
        }
        if (visibleRows <= 0) return false
        val liveLines = renderedVisibleNonBlankLineCount()
        if (liveLines <= 0) return false
        return liveLines.toDouble() / visibleRows.toDouble() <= LOST_FRAME_MAX_LIVE_FRACTION
    }

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

        /**
         * Issue #553 (J2): upper bound on live (non-blank) lines for
         * [visibleScreenIsPartiallyBlank] to still classify the viewport as partially
         * blank. The "only a timer, rest blank" symptom is a single repainting status
         * line (occasionally a couple); a normally-populated pane has many more.
         */
        private const val PARTIAL_BLANK_MAX_LIVE_LINES = 3

        /**
         * Issue #553 (J2): upper bound on the live-line FRACTION of the visible rows for
         * [visibleScreenIsPartiallyBlank]. Combined with [PARTIAL_BLANK_MAX_LIVE_LINES]
         * this excludes a small-but-fully-painted pane (e.g. a 3-row prompt on a 4-row
         * viewport) while catching a lone timer line on an otherwise empty grid.
         */
        private const val PARTIAL_BLANK_MAX_LIVE_FRACTION = 0.25

        /**
         * Issue #966/#967: minimum non-blank chars the authoritative `capture-pane`
         * text must carry for [visibleScreenDivergesFromCapture] to treat a sparse
         * render as STALE (rather than a legitimately near-empty pane). A real
         * full-screen agent/TUI frame is hundreds of chars; this floor keeps the
         * oracle off a genuinely-tiny pane (a bare prompt) where there is no real
         * frame to restore.
         */
        private const val STALE_RENDER_MIN_CAPTURE_CHARS = 40

        /**
         * Issue #966/#967: the rendered grid is judged STALE/mostly-black when its
         * non-blank char count is at most this FRACTION of the authoritative
         * capture's. The #966 pane shows a handful of scattered glyphs against a
         * full-screen frame — well under a quarter of tmux's content. A healthy
         * pane renders a comparable amount to tmux, so it sits ABOVE this ceiling
         * and never heals.
         */
        private const val STALE_RENDER_MAX_RENDERED_FRACTION = 0.25

        /**
         * Issue #989: a fresh `capture-pane` whose visible tail carries FEWER than
         * this many non-blank chars is treated as "no real frame to swap in" by
         * [captureWouldClearVisibleContent] — so it cannot clear an existing
         * content-rich frame to black. An idle alt-screen agent's near-blank
         * capture (a few stray status fragments) sits well under this floor; a
         * real frame is hundreds of chars and sails over it.
         */
        private const val NON_DESTRUCTIVE_SWAP_MIN_CAPTURE_CHARS = 24

        /**
         * Issue #989: the current render must carry AT LEAST this many non-blank
         * chars before [captureWouldClearVisibleContent] will refuse a near-blank
         * capture. Below this the render is itself (near) black — there is nothing
         * to lose, and a freshly-attached black pane's first real seed must still
         * land — so the guard never blocks.
         */
        private const val NON_DESTRUCTIVE_SWAP_MIN_RENDER_CHARS = 24

        /**
         * Issue #989: the render is judged "would be cleared to black" when it
         * carries MORE than this multiple of the near-blank capture's visible
         * content. A content-rich frame (hundreds of chars) against a stray-
         * fragment capture (a handful) is far over this ratio; a render that
         * already roughly matches the sparse capture sits under it and paints
         * normally (idempotent re-seed).
         */
        private const val NON_DESTRUCTIVE_SWAP_CLEAR_RATIO = 3

        /**
         * Issue #1138: the minimum EXTRA non-blank chars tmux's authoritative
         * `capture-pane` frame must carry OVER the local render before the
         * steady-state watchdog heals a PARTIAL-BLACK pane ([visibleRenderLostFrameVsCapture]).
         *
         * The maintainer's live-streaming ALT-SCREEN agent pane (Codex/Claude) shows only
         * the live status line while the upper alt-screen rows stayed black. An alt-screen
         * agent frame is SPARSE (a header + a large blank conversation area + an input/status
         * line), so its non-blank content is small and the surviving status line is a LARGE
         * fraction of it — above the 25% [STALE_RENDER_MAX_RENDERED_FRACTION] divergence
         * ceiling, so the #966 divergence oracle reads it "healthy" and never heals it.
         *
         * Requiring tmux to carry at least this many MORE non-blank chars than the render is
         * the anti-thrash guard: a legitimately-short prompt (tmux ALSO has only those few
         * lines → capture ≈ render) and the #807 by-design alt-screen void (the agent's OWN
         * empty frame → capture ≈ render) both sit UNDER this gap and are left alone. Only a
         * genuine unrepainted frame — tmux holds the full frame, the render shows the band —
         * clears it. One line's worth of real content (≈40 chars).
         */
        private const val PARTIAL_BLACK_HEAL_MIN_EXTRA_CHARS = 40

        /**
         * Issue #1153: the upper bound on the rendered live-line FRACTION of the visible rows for
         * the cheap send-heal pre-check [visibleScreenLooksSparseForSendHeal]. The maintainer's
         * composer Send (with-attachment, so multi-line → bracketed-paste + submit) left the
         * alt-screen agent pane "partly redrew but still too black": a surviving input box + a few
         * conversation lines + status (MORE than the ≤3 live lines
         * [PARTIAL_BLANK_MAX_LIVE_LINES] caps at) over a large black BAND. Half-or-more of the
         * visible rows black is "materially black"; a DENSE, normally-painted response paints
         * well over half its rows and sits ABOVE this ceiling, so it never heals. It is HIGHER
         * than [PARTIAL_BLANK_MAX_LIVE_FRACTION] (0.25) precisely to catch the >3-line band the
         * narrow partial-black heuristic misses, and the capture-diff gate
         * ([PARTIAL_BLACK_HEAL_MIN_EXTRA_CHARS]) still guards against healing a sparse-but-correct
         * pane where tmux holds no more content than the render.
         *
         * NOTE (#1176): this is now ONLY the send-heal LOCAL cost-gate. The authoritative
         * capture-vs-render decision moved to the unified [LOST_FRAME_MAX_RENDERED_FRACTION]
         * char-coverage ceiling in [visibleRenderLostFrameVsCapture]; this 0.5 line-fraction no
         * longer bounds that oracle.
         */
        private const val LOST_FRAME_MAX_LIVE_FRACTION = 0.5

        /**
         * Issue #1176 — the SINGLE char-coverage ceiling of the unified
         * [visibleRenderLostFrameVsCapture] oracle: the render has LOST the frame when its
         * visible non-blank char count is at most this FRACTION of tmux's authoritative visible
         * content (with the [STALE_RENDER_MIN_CAPTURE_CHARS] real-frame floor and the
         * [PARTIAL_BLACK_HEAL_MIN_EXTRA_CHARS] materially-more gate both passed first).
         *
         * It REPLACES the pre-#1176 two-cliff union (a 25%-char ceiling OR a 50%-line ceiling)
         * with one coherent judgment, closing the dead-zone where a black BAND carrying >25% of
         * tmux's chars across >50% of the rows cleared BOTH old ceilings and was never healed.
         * At 0.75 it heals a pane missing a quarter or more of tmux's visible content — the whole
         * "half-to-two-thirds black band" the maintainer kept hitting (spike #874) — while a
         * DENSE, correct pane (coverage ≈ 1.0) and a merely-a-few-rows-behind streaming pane
         * (coverage ~0.84+, measured) both sit above the ceiling and are left alone (no
         * clear-to-repaint flicker on a lagging-but-fine pane).
         */
        private const val LOST_FRAME_MAX_RENDERED_FRACTION = 0.75

        /**
         * Issue #1176 (GAP C) — the live-line FRACTION ceiling of the LOCAL capture-gate
         * [visibleRenderMayHaveLostFrame] used by the switch-reveal and no-op-resize heals. It is
         * aligned with the unified oracle's [LOST_FRAME_MAX_RENDERED_FRACTION] (0.75) so a
         * confidently-full pane (live rows above the ceiling) skips the authoritative
         * `capture-pane` diff while ANY pane that could be a dead-zone black band (live rows below
         * it) is confirmed against tmux by [visibleRenderLostFrameVsCapture] before any heal. It
         * is deliberately HIGHER than the send-heal cost-gate's [LOST_FRAME_MAX_LIVE_FRACTION]
         * (0.5) so the reveal/resize gates never MISS the #1176 dead-zone band the way the narrow
         * pre-check did.
         */
        private const val MAY_HAVE_LOST_FRAME_MAX_LIVE_FRACTION = 0.75

        /**
         * Issue #1176 (GAP C) — the LOWER bound of the [visibleRenderMayHaveLostFrame] band
         * trigger. A >3-line pane with at most this live-fraction is genuinely SPARSE (a short
         * correct prompt), NOT the dead-zone band the pre-#1176 no-op-resize gate missed — flagging
         * it would break the "cheap no-op" contract (capturing a correct small pane on routine
         * layout churn). The dead-zone is specifically the band with MORE than half the rows live
         * (spike #874: >50% rows + >25% chars), so the local capture-gate only opens ABOVE this
         * 0.5 boundary; the genuinely-sparse pane's net stays the steady-state watchdog + the
         * send-heal path (whose own cost-gate is [LOST_FRAME_MAX_LIVE_FRACTION] = 0.5).
         */
        private const val MAY_HAVE_LOST_FRAME_MIN_LIVE_FRACTION = 0.5
    }
}

private class TerminalProducerFeedFailure(cause: Throwable) : RuntimeException(cause)
