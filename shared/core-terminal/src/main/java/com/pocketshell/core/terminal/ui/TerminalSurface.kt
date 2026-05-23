package com.pocketshell.core.terminal.ui

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketshell.core.terminal.selection.SelectionOverlay
import com.pocketshell.core.terminal.selection.TerminalMatch
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Background colour applied to the [TerminalView]'s parent surface. Matches
 * the deep-navy chrome from `docs/design-language.md` ("Background: deep
 * navy/charcoal, never pure black"). The vendored [TerminalView] itself
 * paints its own canvas via [TerminalView.onDraw] (defaulting to solid
 * black when no emulator is attached); this colour shows through while the
 * view is being measured and around any padding the caller applies.
 */
val DefaultTerminalBackground: Color = Color(0xFF0D1117)

/**
 * Default text size used by the embedded [TerminalView], expressed in **raw
 * device pixels** (not sp, not dp, not CSS px).
 *
 * Why raw pixels: the vendored [TerminalView.setTextSize] accepts an `int`
 * and passes it straight to [android.graphics.Paint.setTextSize], whose
 * `textSize` parameter is documented in pixels. There is no
 * `TypedValue.applyDimension` density conversion anywhere in the upstream
 * path, so whatever number we pass in here is the number of physical pixels
 * the renderer hands to the [android.graphics.Paint] used to draw each
 * glyph. Upstream Termux's javadoc on `setTextSize` says "density-independent
 * pixels" but that is inaccurate for the code as it actually runs — confirmed
 * by reading [com.termux.view.TerminalRenderer]'s constructor.
 *
 * Why 30: the real-phone report for #72 showed that 36 px was too coarse for
 * a Pixel-style viewport: common `ls` output wrapped early and the prompt
 * consumed too much of the terminal area. 30 px keeps the Termius-like mobile
 * density readable while giving the emulator enough columns for paths and
 * multi-column command output.
 *
 * Suffixed `_RAW_PX` (not just `_PX`) to make the unit unambiguous in IDE
 * autocomplete and search results.
 */
internal const val DEFAULT_TEXT_SIZE_RAW_PX: Int = 30

/**
 * Hosts the vendored [TerminalView] inside a Compose tree via
 * [AndroidView] interop.
 *
 * The surface is a thin Compose wrapper — it does not own the session,
 * does not parse output, and does not render any UI chrome (breadcrumbs,
 * keybar, chips are all Phase 1). Its only jobs are:
 *
 * 1. Construct one [TerminalView] per composition slot and reuse it across
 *    recompositions.
 * 2. Apply the design-language background colour and JetBrains-Mono-ish
 *    typeface (falling back to the system monospace when JetBrains Mono is
 *    not registered).
 * 3. Bind the [TerminalView] to whatever [TerminalSession] the [state]
 *    currently holds, attaching / detaching as the state changes.
 * 4. Wire the [TerminalView]'s required [TerminalViewClient] to a sane
 *    no-op default so the view does not NPE during input or measurement.
 *
 * @param state Compose-friendly state holder for the session and its I/O.
 *   Get one via [rememberTerminalSurfaceState].
 * @param modifier Standard Compose modifier (size, padding, etc.). The
 *   surface defaults to filling its parent; the caller controls layout
 *   via this modifier.
 * @param onKeyEvent Optional Compose-level key-event forwarder. When
 *   non-`null`, this callback is invoked for every key event that the
 *   Compose focus system routes to the surface (typically the design-
 *   language key bar dispatching modifier-bar codes). Return `true` to
 *   mark the event consumed; `false` lets it propagate to the underlying
 *   [TerminalView]. The vendored [TerminalView] has its own
 *   [android.view.View.dispatchKeyEvent] path; this slot exists so callers
 *   can intercept at the Compose layer without reaching the View directly.
 *   Defaults to `null`, which is equivalent to "don't consume — fall
 *   through to the View".
 *
 * ## JNI deferral
 *
 * Per `VENDORED.md` — this issue does NOT compile `libtermux.so`. The
 * [TerminalView] reaches into JNI only via
 * [TerminalSession.updateSize] → [TerminalSession.initializeEmulator],
 * which is gated on `state.session != null` and on the view receiving a
 * non-zero size. Until #9 wires a real session source, callers should
 * leave [TerminalSurfaceState] unattached; the surface still composes and
 * lays out cleanly, rendering as the default solid-black [TerminalView]
 * canvas.
 *
 * @see TerminalSurfaceState
 * @see rememberTerminalSurfaceState
 */
@Composable
fun TerminalSurface(
    state: TerminalSurfaceState,
    modifier: Modifier = Modifier,
    matchListener: ((TerminalMatch) -> Unit)? = null,
    onKeyEvent: ((ComposeKeyEvent) -> Boolean)? = null,
) {
    // Hoist the bridge so the same instance survives recompositions and we
    // do not leak listeners across configuration changes. AndroidView's
    // factory runs once; update runs every recomposition.
    val viewClient = remember { PocketShellTerminalViewClient() }

    // Subscribe to the detector flow only when the caller actually wants
    // match callbacks. The empty-flow fallback avoids spinning up a debounce
    // coroutine when no overlay is being rendered.
    val matchesFlow: Flow<List<TerminalMatch>> =
        if (matchListener != null) state.flowOfMatches else remember { flowOf(emptyList()) }
    val matches by matchesFlow.collectAsState(initial = emptyList<TerminalMatch>())
    val renderTick = state.renderTick

    // If the caller installed an onKeyEvent slot, chain it onto the
    // user-supplied modifier so the Compose focus system routes key events
    // through it before the embedded TerminalView gets a chance. We tack
    // the slot on at the end (after the caller's chain) so caller-supplied
    // focus / pointer modifiers run first. The slot defaults to `null`,
    // in which case we leave the modifier untouched — no allocation, no
    // KeyInputModifier added to the tree.
    val keyAwareModifier = if (onKeyEvent != null) {
        modifier.onKeyEvent { event -> onKeyEvent(event) }
    } else {
        modifier
    }

    // Layer the vendored TerminalView under the optional SelectionOverlay.
    // We use `androidx.compose.ui.layout.Layout` rather than `Box` because
    // this module does not depend on `compose-foundation`. The Layout
    // measures both children with the incoming constraints and places them
    // at origin so they perfectly overlap.
    Layout(
        modifier = keyAwareModifier,
        content = {
            AndroidView(
                factory = { context ->
                    TerminalView(context, /* attributes = */ null).applyPocketShellDefaults(viewClient)
                },
                update = { view ->
                    val current = view.currentSession
                    val desired = state.session
                    // Attach / detach as the state's session reference
                    // changes. `attachSession` early-returns when given the
                    // same instance, so this is idempotent across
                    // recompositions.
                    if (desired != null) {
                        if (desired !== current) {
                            view.attachSession(desired)
                        }
                    } else if (desired !== current) {
                        // No public detach on TerminalView; clear the
                        // field via an attach of an empty marker is not
                        // possible because TerminalSession is `final`
                        // and we cannot construct a sentinel without
                        // driving JNI. The view simply keeps its last
                        // session reference until the next attach. This
                        // is acceptable for #8 — #9 will always have a
                        // fresh session on hand when changing sessions.
                    }
                    if (desired != null && view.currentSession === desired && renderTick > 0L) {
                        view.onScreenUpdated()
                    }
                },
            )
            if (matchListener != null) {
                SelectionOverlay(
                    matches = matches,
                    onTap = matchListener,
                )
            }
        },
    ) { measurables, constraints ->
        // Measure each child with the full incoming constraints and stack
        // them at (0, 0). The first child is the AndroidView; subsequent
        // children (the overlay, if present) are placed on top in source
        // order, exactly as `Box` would do for centred / aligned children.
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) {
            placeables.forEach { it.place(0, 0) }
        }
    }

    // Intentionally no `DisposableEffect` here. Resource ownership is
    // delegated cleanly:
    //
    // - The vendored `TerminalView` is owned by `AndroidView`, which
    //   detaches it from its parent on disposal (the standard interop
    //   contract).
    // - The optional external producer is launched in the *caller's*
    //   scope via [TerminalSurfaceState.attachExternalProducer]; cancelling
    //   that scope (e.g. when the host composable leaves the composition)
    //   triggers the bridge's `finally { detachExternalProducer() }` path,
    //   which stops the drainer thread and releases the session.
    // - The `NoOpTerminalViewClient` is a plain object with no listeners
    //   registered anywhere; nothing to detach.
    //
    // An earlier revision had an empty `DisposableEffect(state) { onDispose
    // { } }` here as a TODO marker. It allocated a Compose slot per
    // composition without doing any work and was removed in #31 once the
    // ownership story above was verified.
}

/**
 * Convenience factory for creating a [TerminalSurfaceState] that survives
 * recompositions. The returned instance is bound to the current
 * composition's lifecycle — when the composable leaves, the state is
 * garbage-collected like any other `remember`-ed object.
 *
 * Usage:
 *
 * ```kotlin
 * val terminal = rememberTerminalSurfaceState()
 * TerminalSurface(terminal, modifier = Modifier.fillMaxSize())
 * ```
 *
 * Issue #8 ships this without saving across configuration changes; once
 * #9 lands and sessions carry meaningful per-pane state, we will revisit
 * with `rememberSaveable` and a custom `Saver`.
 */
@Composable
fun rememberTerminalSurfaceState(): TerminalSurfaceState =
    remember { TerminalSurfaceState() }

internal fun TerminalView.applyPocketShellDefaults(viewClient: TerminalViewClient): TerminalView {
    setTerminalViewClient(viewClient)
    if (viewClient is PocketShellTerminalViewClient) {
        viewClient.bind(this)
    }
    // Termux's setTypeface reads mRenderer.mTextSize, so text size must create
    // the renderer before we swap in the app typeface.
    setTextSize(DEFAULT_TEXT_SIZE_RAW_PX)
    setTypeface(Typeface.create("JetBrains Mono", Typeface.NORMAL))
    setBackgroundColor(DefaultTerminalBackground.toArgb())
    isFocusable = true
    isFocusableInTouchMode = true
    return this
}

/**
 * [TerminalViewClient] used by PocketShell's embedded terminal.
 *
 * The vendored [TerminalView] owns text and hardware-key routing once the IME
 * is open, but it delegates the "single tap" action to its client. Upstream
 * Termux's app client uses that callback to summon the soft keyboard; without
 * the same bridge here PocketShell could render a connected terminal while
 * leaving phone users with no way to type into it.
 */
internal class PocketShellTerminalViewClient : TerminalViewClient, TerminalSessionClient {
    private var terminalView: TerminalView? = null

    fun bind(view: TerminalView) {
        terminalView = view
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent?) {
        val view = terminalView ?: return
        view.requestFocus()
        val inputMethodManager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
