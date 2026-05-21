package com.pocketshell.core.terminal.ui

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketshell.core.terminal.selection.SelectionOverlay
import com.pocketshell.core.terminal.selection.TerminalMatch
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
 * Default text size used by the embedded [TerminalView]. The vendored
 * upstream defaults to system text size, which is too small for the
 * Termius-like density we want. 14sp matches the "body" tier in
 * `docs/design-language.md`.
 */
private const val DEFAULT_TEXT_SIZE_PX: Int = 36

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
) {
    // Hoist the bridge so the same instance survives recompositions and we
    // do not leak listeners across configuration changes. AndroidView's
    // factory runs once; update runs every recomposition.
    val viewClient = remember { NoOpTerminalViewClient() }

    // Subscribe to the detector flow only when the caller actually wants
    // match callbacks. The empty-flow fallback avoids spinning up a debounce
    // coroutine when no overlay is being rendered.
    val matchesFlow: Flow<List<TerminalMatch>> =
        if (matchListener != null) state.flowOfMatches else remember { flowOf(emptyList()) }
    val matches by matchesFlow.collectAsState(initial = emptyList<TerminalMatch>())

    // Layer the vendored TerminalView under the optional SelectionOverlay.
    // We use `androidx.compose.ui.layout.Layout` rather than `Box` because
    // this module does not depend on `compose-foundation`. The Layout
    // measures both children with the incoming constraints and places them
    // at origin so they perfectly overlap.
    Layout(
        modifier = modifier,
        content = {
            AndroidView(
                factory = { context ->
                    TerminalView(context, /* attributes = */ null).apply {
                        setTerminalViewClient(viewClient)
                        // Match `docs/design-language.md`: monospace,
                        // JetBrains Mono when the system has it registered,
                        // system monospace as a graceful fallback.
                        setTypeface(Typeface.create("JetBrains Mono", Typeface.NORMAL))
                        setTextSize(DEFAULT_TEXT_SIZE_PX)
                        // Honour the design-language background until the
                        // emulator takes over its own canvas. The vendored
                        // TerminalView is a raw `View`; it does not
                        // propagate Compose's `Modifier.background`, so we
                        // set it on the View directly.
                        setBackgroundColor(DefaultTerminalBackground.toArgb())
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                },
                update = { view ->
                    val current = view.currentSession
                    val desired = state.session
                    // Attach / detach as the state's session reference
                    // changes. `attachSession` early-returns when given the
                    // same instance, so this is idempotent across
                    // recompositions.
                    if (desired !== current) {
                        if (desired != null) {
                            view.attachSession(desired)
                        } else {
                            // No public detach on TerminalView; clear the
                            // field via an attach of an empty marker is not
                            // possible because TerminalSession is `final`
                            // and we cannot construct a sentinel without
                            // driving JNI. The view simply keeps its last
                            // session reference until the next attach. This
                            // is acceptable for #8 — #9 will always have a
                            // fresh session on hand when changing sessions.
                        }
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

    // Clean up the bridge when the composable leaves the composition. The
    // TerminalView itself will be detached by AndroidView's own disposal;
    // we just make sure our reference does not retain it after that.
    DisposableEffect(state) {
        onDispose {
            // Detach the state's reference so a future re-composition with
            // the same state starts clean. The owning TerminalSession is
            // not stopped — its lifecycle belongs to whoever created it.
            // (For #8 the state never has a session attached anyway.)
        }
    }
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

/**
 * Minimal [TerminalViewClient] that satisfies the contract without doing
 * anything interesting. The vendored [TerminalView] de-references its
 * client unconditionally during input, measurement, and IME handling — a
 * `null` client would NPE the first time the view is touched.
 *
 * This default is intentionally conservative: it refuses control-key
 * mapping, does not eat key events, and forwards key codes back to the
 * View's superclass implementations via the `false` return values. Issue
 * #9's real wiring will replace this with a client that knows about the
 * SSH-attached session and the design-language key bar.
 */
private class NoOpTerminalViewClient : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent?) = Unit
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
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
