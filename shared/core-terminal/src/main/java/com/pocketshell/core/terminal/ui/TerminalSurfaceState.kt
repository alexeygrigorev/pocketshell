package com.pocketshell.core.terminal.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.OutputStream

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
class TerminalSurfaceState {

    private var _session: TerminalSession? by mutableStateOf(null)

    /**
     * Backing flow for [output]. Replay = 0 — bytes are only delivered to
     * collectors active at the time they arrive. `extraBufferCapacity` is
     * sized to absorb a small burst from the session's I/O thread without
     * dropping; `BufferOverflow.SUSPEND` keeps producers honest by
     * back-pressuring them when there is no collector.
     */
    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * Bytes the terminal session emitted since the most recent active
     * collection started. Cold collectors get no replay — by the time the UI
     * is in the composition, the [TerminalView] already owns the
     * authoritative byte stream via the emulator. This flow exists for
     * non-View consumers (e.g. logging, automation, tests).
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
     * Test-only seam: push synthetic output bytes into [output] without
     * needing a real PTY. Used by [TerminalSurface]'s `@Preview` and by the
     * module's unit tests to exercise the flow without standing up a
     * session.
     *
     * Suspends if the flow's buffer is full and no collector is consuming
     * (`BufferOverflow.SUSPEND`). Returns true if delivered, false if the
     * flow was closed (currently the flow is never closed, but the return
     * value matches `MutableSharedFlow.tryEmit`'s contract for symmetry).
     */
    internal suspend fun emitOutputForTesting(bytes: ByteArray): Boolean {
        _output.emit(bytes)
        return true
    }

    /**
     * Bridge currently feeding bytes into the emulator. Created by
     * [attachExternalProducer]; held so subsequent calls to [writeInput]
     * route through the same session, and so [detachExternalProducer]
     * (or composition disposal) can stop the drainer thread.
     */
    @Volatile
    private var bridge: SshTerminalBridge? = null

    /**
     * Scope owning the producer-collection coroutine. Cancelled by
     * [detachExternalProducer] or when the surface leaves the composition.
     */
    private var producerScope: CoroutineScope? = null

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
     * - Launches a coroutine in [scope] that collects [stdout] and pumps
     *   each byte array into the emulator.
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
     * @return a [Job] that completes when the producer flow terminates or
     *   the scope is cancelled.
     */
    public fun attachExternalProducer(
        scope: CoroutineScope,
        stdout: Flow<ByteArray>,
        remoteStdin: OutputStream? = null,
    ): Job {
        // Tear down any existing bridge so we never have two producers
        // racing on the same emulator.
        detachExternalProducer()

        val newBridge = SshTerminalBridge()
        newBridge.setRemoteStdin(remoteStdin)
        bridge = newBridge

        // Bind the bridge's session to the View via the existing `attach`
        // pathway. `TerminalView.attachSession` zeros its internal emulator
        // reference and triggers a re-layout, at which point the View's
        // `updateSize` resizes the bridge's pre-installed emulator to match
        // the on-screen size.
        attach(newBridge.session)

        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        producerScope = collectorScope

        return scope.launch {
            try {
                stdout.collect { bytes ->
                    if (bytes.isNotEmpty()) {
                        newBridge.feedBytes(bytes)
                        _output.emit(bytes)
                    }
                }
            } finally {
                // If the producer flow completes naturally (SSH session
                // closed), tear the bridge down so the View's references
                // drop cleanly.
                detachExternalProducer()
            }
        }
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
        producerScope?.cancel()
        producerScope = null
        bridge?.stop()
        bridge = null
        detach()
    }
}
