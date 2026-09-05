package com.pocketshell.next.composer

/**
 * The production Send path (#682 / #2529).
 *
 * Order is load-bearing: Send used to dispatch first, which focused the
 * terminal (or left the composer field focused) and popped the IME back up.
 * Flush the live editor (IME composing region included), drop focus, hide
 * the keyboard, THEN dispatch.
 */
internal enum class ComposerSendStep {
    FlushDraft,
    ClearFocus,
    HideKeyboard,
    Dispatch,
}

internal fun commitComposerSend(
    flushDraft: () -> Unit,
    clearFocus: () -> Unit,
    hideKeyboard: () -> Unit,
    dispatch: () -> Unit,
) {
    flushDraft()
    clearFocus()
    hideKeyboard()
    dispatch()
}
