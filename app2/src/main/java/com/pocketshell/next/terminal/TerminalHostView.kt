package com.pocketshell.next.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketshell.uikit.theme.PocketShellColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Test tag on the terminal surface itself. */
const val SESSION_TERMINAL_TAG: String = "session-terminal"

/**
 * Terminal glyph size in RAW DEVICE PIXELS.
 *
 * Upstream's `setTextSize` javadoc says "density-independent pixels" and is
 * wrong about its own code: the value goes straight to `Paint.setTextSize`,
 * which takes pixels, with no `TypedValue.applyDimension` anywhere on the path.
 * 28 is the size the pre-rewrite client shipped — big enough to read on a phone,
 * small enough to leave a usable column count at 1080 px wide.
 */
private const val TERMINAL_TEXT_SIZE_RAW_PX: Int = 28

/**
 * The terminal's own palette entries, in ARGB.
 *
 * Two places need the background and both matter: `setDefaultBackgroundColor`
 * paints the View (including the strip below the last drawn row), while the
 * emulator's `mColors` decides what a cell with DEFAULT attributes is filled
 * with — upstream's renderer skips painting a cell whose background equals the
 * palette default, so the two have to agree or the grid and the gutter end up
 * different blacks.
 *
 * They are the ui-kit tokens, not terminal-only constants: a third black next
 * to the app's chrome is exactly the seam a phone screen shows up.
 */
private val TERMINAL_BACKGROUND_ARGB: Int = PocketShellColors.Background.toArgb()
private val TERMINAL_FOREGROUND_ARGB: Int = PocketShellColors.Text.toArgb()
private val TERMINAL_CURSOR_ARGB: Int = PocketShellColors.Accent.toArgb()

/** `assets/` path of the face vendored inside `:shared:core-terminal`. */
private const val TERMINAL_FONT_ASSET: String = "fonts/JetBrainsMono-Regular.ttf"

private var cachedTerminalTypeface: android.graphics.Typeface? = null

/**
 * The bundled monospace face, or the platform's own if it cannot be loaded.
 *
 * Cached because `createFromAsset` parses the TTF on every call, and this runs
 * on the main thread while a terminal is being attached. The REGULAR face is
 * loaded deliberately: the renderer applies bold per cell from the terminal's
 * own attributes, so a pre-bolded typeface would render every cell bold.
 */
private fun terminalTypeface(context: android.content.Context): android.graphics.Typeface {
    cachedTerminalTypeface?.let { return it }
    val loaded = runCatching {
        android.graphics.Typeface.createFromAsset(
            context.applicationContext.assets,
            TERMINAL_FONT_ASSET,
        )
    }.getOrNull() ?: android.graphics.Typeface.MONOSPACE
    cachedTerminalTypeface = loaded
    return loaded
}

/**
 * The vendored [TerminalView], hosted in Compose (rewrite task U-4).
 *
 * Thin on purpose. The pre-rewrite client wrapped the same view in
 * `TerminalSurface` + `TerminalSurfaceState` (~2,900 lines of render
 * coalescing, heal watchdogs, black-frame detection and viewport bookkeeping)
 * because it fed the emulator from a racing pair of tmux sources. app2 feeds it
 * one PTY stream, so what is left is the interop itself:
 *
 *  1. build the view, give it a client, attach the session;
 *  2. install a [TerminalSessionClient] that turns the emulator's
 *     "text changed" callback into a repaint — without it the vendored drain
 *     parses bytes into a grid nobody ever draws;
 *  3. report the size the view computes from its own font metrics back to the
 *     session layer, which is the ONLY path to `pty.resize`.
 *
 * ## Why the size comes from here
 *
 * `TerminalView.updateSize()` owns the cell metrics (it has the renderer) and
 * resizes the emulator itself on every layout pass, then calls
 * [TerminalViewClient.onEmulatorSet]. That callback is therefore the exact
 * moment the emulator's geometry changed, and reporting it from there is what
 * keeps the remote's `SIGWINCH` in step with what is on screen. Anything richer
 * — rotation handling, IME insets, the key bar — is task U-5.
 *
 * @param session the live emulator front end, from [SessionUiState.Live].
 * @param onResized called with the view's computed size in character cells.
 */
@Composable
fun TerminalHostView(
    session: TerminalSession,
    onResized: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held across recompositions so a state change in the enclosing screen does
    // not detach and re-attach the terminal (which would reset the viewport).
    val repaintClient = remember(session) { RepaintingSessionClient() }

    DisposableEffect(session) {
        session.updateTerminalSessionClient(repaintClient)
        onDispose {
            repaintClient.view = null
            // Back to a client with no view behind it: the session can outlive
            // this composition (the ViewModel owns it), and a stale view
            // reference would keep an unattached View alive and repainting.
            session.updateTerminalSessionClient(NoOpTerminalSessionClient())
        }
    }

    AndroidView(
        modifier = modifier.testTag(SESSION_TERMINAL_TAG),
        factory = { context ->
            TerminalView(context, null).apply {
                // The view is the keyboard target: hardware keys, IME commits
                // and the on-screen keyboard all dispatch to the focused view,
                // and upstream Termux leaves focusability to its host.
                isFocusable = true
                isFocusableInTouchMode = true
                // Android draws a translucent white "default focus highlight"
                // over any focusable View that does not style focus itself
                // (API 26+). On a full-screen terminal that is a permanent ~12%
                // white wash over every pixel — the grid renders visibly lighter
                // than the chrome above it and no palette change can fix it,
                // because it is painted on top of the finished frame.
                defaultFocusHighlightEnabled = false
                setTerminalViewClient(SessionTerminalViewClient(this, onResized))
                // Order matters and is not cosmetic: upstream's `TerminalView`
                // has NO renderer until `setTextSize` builds one, and
                // `updateSize()` reads `mRenderer.mFontWidth` inside a
                // `catch (Throwable)`. Attaching a session first therefore
                // swallows a NullPointerException and leaves the view with a
                // null emulator — a permanently blank terminal with nothing in
                // the log. `setTypeface` then reads `mRenderer.mTextSize`, so it
                // has to come after `setTextSize`.
                setTextSize(TERMINAL_TEXT_SIZE_RAW_PX)
                setTypeface(terminalTypeface(context))
                setDefaultBackgroundColor(TERMINAL_BACKGROUND_ARGB)
                attachSession(session)
                repaintClient.view = this
            }
        },
        update = { view ->
            repaintClient.view = view
            if (view.currentSession !== session) {
                view.attachSession(session)
            }
            // Focus is requested here rather than in `factory`: the view is not
            // attached to a window yet at construction time, so `requestFocus()`
            // there is a guaranteed no-op.
            if (!view.isFocused) view.requestFocus()
        },
        onRelease = { view ->
            if (repaintClient.view === view) repaintClient.view = null
        },
    )
}

/**
 * Bridges the vendored session's "the grid changed" callback to the view's
 * repaint. Everything else stays a no-op for now: clipboard, bell and colour
 * changes belong to later polish tasks, and a wrong implementation of any of
 * them would be worse than none.
 */
private class RepaintingSessionClient : TerminalSessionClient by NoOpTerminalSessionClient() {

    @Volatile
    var view: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }
}

/**
 * The view's client. Everything it is asked is answered with the plain
 * terminal-safe default:
 *
 * - no smart-text IME (autocorrect rewriting shell tokens is exactly the wrong
 *   behaviour in a terminal, and the vendored default agrees);
 * - Back is Back, not Escape — app2 has no key bar yet (task U-5), so mapping
 *   the system Back gesture onto Escape would leave no way off the screen;
 * - no modifier latches, because there is nothing to latch them from yet.
 */
private class SessionTerminalViewClient(
    private val view: TerminalView,
    private val onResized: (cols: Int, rows: Int) -> Unit,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1.0f

    override fun onSingleTapUp(e: MotionEvent?) {
        // Tapping the terminal raises the keyboard, which is the only way to
        // type on a phone. The view is already the focus target.
        view.requestFocus()
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

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean =
        false

    /**
     * Fired by `TerminalView.updateSize()` right after it resized the emulator,
     * which makes it the one trustworthy "the geometry changed" signal — it
     * carries the size the renderer actually computed, not one this layer
     * guessed from density.
     */
    override fun onEmulatorSet() {
        val emulator = view.mEmulator ?: return
        // The emulator does not exist until the view's first non-zero layout, so
        // this — not the factory — is the only moment its palette can be set.
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] =
            TERMINAL_BACKGROUND_ARGB
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] =
            TERMINAL_FOREGROUND_ARGB
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = TERMINAL_CURSOR_ARGB
        onResized(emulator.mColumns, emulator.mRows)
    }

    override fun logError(tag: String?, message: String?) = Unit

    override fun logWarn(tag: String?, message: String?) = Unit

    override fun logInfo(tag: String?, message: String?) = Unit

    override fun logDebug(tag: String?, message: String?) = Unit

    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit

    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
