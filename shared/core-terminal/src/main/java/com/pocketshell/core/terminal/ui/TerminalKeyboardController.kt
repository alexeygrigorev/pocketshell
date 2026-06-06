package com.pocketshell.core.terminal.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.termux.view.TerminalView

/**
 * Issue #131: single entry point used by the session screens to bring up
 * the soft keyboard when the user taps the "keyboard" chip on the bottom
 * toolbar.
 *
 * Why this lives in `:shared:core-terminal` rather than in `:app`: the
 * [TerminalView] is a vendored view owned by this module, and the screens
 * have no other reason to depend on its type. Funneling the
 * `requestFocus + InputMethodManager.showSoftInput` choreography through a
 * helper here keeps the `TerminalView` import inside the module that already
 * owns it and avoids accidental skew between `SessionScreen` and
 * `TmuxSessionScreen` (which both need exactly the same behaviour).
 *
 * The helper is deliberately a no-op when:
 *
 * 1. No [TerminalView] descendant exists under [rootView] yet (e.g. the
 *    surface hasn't finished its first composition / `AndroidView.factory`
 *    hasn't run). The chip stays harmless rather than throwing.
 * 2. The view doesn't accept focus (`requestFocus()` returns `false`).
 *    Same rationale — we'd rather silently drop the request than surface a
 *    crash to the user.
 *
 * It does NOT toggle / dismiss when the keyboard is already up. The issue
 * is explicit: "When the keyboard is already shown, tapping the chip is a
 * no-op." Detecting that state from the app side is racy (the IME's
 * `mInputShown` lives in `system_server` and is asynchronous), but
 * [InputMethodManager.showSoftInput] itself is idempotent — calling it
 * while the IME is already visible is documented as a successful no-op.
 * That matches the requested "no-op when already shown" contract without
 * adding fragile state tracking on the client side.
 */
fun showTerminalSoftKeyboard(
    rootView: View,
    onLocalTerminalError: ((Throwable) -> Unit)? = null,
): Boolean {
    return runCatching {
        val terminalView = rootView.findTerminalViewDescendant() ?: return@runCatching false
        val focused = terminalView.requestFocus()
        val imm = terminalView.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return@runCatching false
        // `SHOW_IMPLICIT` is appropriate here because the user explicitly
        // tapped PocketShell's "show keyboard" control. Terminal viewport
        // taps intentionally do not call IMM.
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT) && focused
    }.getOrElse { cause ->
        onLocalTerminalError?.invoke(cause)
        false
    }
}

/**
 * Issue #184: snap the embedded [TerminalView]'s scrollback viewport back
 * to the latest line (`mTopRow = 0`) so the cursor row — which always sits
 * inside the live emulator window, not in transcript history — is visible
 * to the user. The session screen calls this when the soft keyboard
 * becomes visible: even if the user had scrolled the transcript up
 * earlier, the moment they ask for the IME they have committed to typing
 * at the prompt and the prompt's cursor row needs to be inside the
 * visible viewport.
 *
 * Returns `true` when a [TerminalView] descendant was found and its
 * scroll position was reset, `false` when no terminal view is mounted
 * yet (e.g. the screen has not finished its first composition). The
 * caller treats `false` as a no-op rather than an error — the same
 * defensive contract as [showTerminalSoftKeyboard].
 *
 * Implementation is intentionally a one-call shot of `setTopRow(0) +
 * onScreenUpdated()`. The vendored [TerminalView] already pins the
 * viewport to bottom whenever the emulator size changes (see
 * `TerminalView.updateSize` → `mTopRow = 0`); this helper handles the
 * orthogonal "user scrolled then tapped IME" case where the size has
 * not changed but the visible viewport no longer covers the cursor row.
 */
fun pinTerminalToBottom(
    rootView: View,
    onLocalTerminalError: ((Throwable) -> Unit)? = null,
): Boolean {
    return runCatching {
        val terminalView = rootView.findTerminalViewDescendant() ?: return@runCatching false
        terminalView.setTopRow(0)
        terminalView.onScreenUpdated()
        true
    }.getOrElse { cause ->
        onLocalTerminalError?.invoke(cause)
        false
    }
}

/**
 * Depth-first search for the first [TerminalView] under this view. The
 * embedded [TerminalView] sits inside an `AndroidView` wrapper that the
 * Compose hierarchy nests several layers deep, so a one-step `children`
 * walk isn't enough. Recursion bottoms out at the first match — there is
 * only ever one [TerminalView] per pane.
 */
internal fun View.findTerminalViewDescendant(): TerminalView? {
    if (this is TerminalView) return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        val match = getChildAt(index).findTerminalViewDescendant()
        if (match != null) return match
    }
    return null
}
