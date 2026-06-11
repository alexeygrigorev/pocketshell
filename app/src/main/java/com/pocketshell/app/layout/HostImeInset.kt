package com.pocketshell.app.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Reads the soft-keyboard (IME) bottom inset in pixels from the **host
 * activity window**, not from the local Compose `WindowInsets.ime`.
 *
 * ## Why this exists (#616 / #615)
 *
 * `WindowInsets.ime.getBottom(density)` is the obvious read, and it works on
 * the emulator. But on the maintainer's real device it returns **0 even while
 * the soft keyboard is up**. #615 first hit this in the Prompt Composer (the
 * Send button slipped under the keyboard) and fixed it by reading the IME inset
 * from the host activity decor view instead. #616 is the terminal-side twin:
 * `TmuxSessionScreen` derived `isImeVisible` and the keyboard pan offset from
 * the same unreliable `WindowInsets.ime` read, so on the device `isImeVisible`
 * stayed `false` while the keyboard was up. That collapsed the terminal hotkey
 * [com.pocketshell.uikit.components.KeyBar] (Ctrl/Tab/Esc/arrows) into the
 * IME-hidden chip strip — exactly when the user is typing and needs the
 * shortcut keys most. Only Gboard's own toolbar showed above the keys.
 *
 * The host activity window's `WindowInsetsCompat.Type.ime()` IS reliable on
 * that device, so every IME-visibility / IME-pan decision must read it from
 * here. This is the single source of truth shared by the composer and the
 * terminal so they can never drift apart again.
 *
 * ## Mechanics (no background work — D21-clean)
 *
 * Registers an [OnApplyWindowInsetsListener] on the host view's root while this
 * composable is in composition and seeds the value from the current insets so a
 * screen that mounts with the keyboard already up (rotation, re-open, returning
 * to a focused field) reflects it immediately. The listener is removed on
 * dispose, so nothing runs while the screen is gone.
 *
 * @return px height of the IME inset on the host window; 0 when the keyboard is
 * hidden. Clamped at 0.
 */
@Composable
internal fun rememberHostImeBottomPx(): State<Int> {
    val hostView = LocalView.current
    val imeBottomPx = remember { mutableIntStateOf(0) }
    // rememberUpdatedState keeps the listener writing into the same backing
    // state across recompositions without re-registering the listener.
    val currentState by rememberUpdatedState(imeBottomPx)
    DisposableEffect(hostView) {
        val decor = hostView.rootView
        val listener = OnApplyWindowInsetsListener { _, insets ->
            currentState.intValue = insets
                .getInsets(WindowInsetsCompat.Type.ime())
                .bottom
                .coerceAtLeast(0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(decor, listener)
        // Seed from the current insets so a screen that mounts with the
        // keyboard already up lifts/pans immediately.
        ViewCompat.getRootWindowInsets(decor)?.let { current ->
            currentState.intValue = current
                .getInsets(WindowInsetsCompat.Type.ime())
                .bottom
                .coerceAtLeast(0)
        }
        ViewCompat.requestApplyInsets(decor)
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(decor, null)
        }
    }
    return imeBottomPx
}
