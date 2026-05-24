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
fun showTerminalSoftKeyboard(rootView: View): Boolean {
    val terminalView = rootView.findTerminalViewDescendant() ?: return false
    val focused = terminalView.requestFocus()
    val imm = terminalView.context
        .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    // `SHOW_IMPLICIT` matches the upstream Termux tap-on-viewport path
    // (see `PocketShellTerminalViewClient.onSingleTapUp`). Using the same
    // flag means the system keyboard policy treats the chip-tap the same
    // way it treats a tap on the terminal viewport — important because the
    // user already expects that tap interaction to "feel" like the chip.
    return imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT) && focused
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
