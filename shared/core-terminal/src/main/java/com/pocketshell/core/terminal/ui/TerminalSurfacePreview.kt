package com.pocketshell.core.terminal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Android Studio / Preview-tooling render for [TerminalSurface]. The
 * surface is intentionally left without an attached session — per the JNI
 * deferral note in [TerminalSurface], a real [com.termux.terminal.TerminalSession]
 * would crash with `UnsatisfiedLinkError` the moment the view's layout pass
 * triggers [com.termux.terminal.TerminalSession.updateSize] (which
 * dispatches into `libtermux.so`).
 *
 * The unattached surface renders as the design-language background colour
 * with the [com.termux.view.TerminalView]'s default black canvas inset;
 * that is enough for reviewers to eyeball sizing, padding, and typeface
 * behaviour. Issue #9 will swap in a real preview once the JNI is wired
 * (or, more likely, a synthetic byte feeder for previews specifically).
 */
@Preview(
    name = "TerminalSurface — unattached",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 360,
    heightDp = 640,
)
@Composable
private fun TerminalSurfaceUnattachedPreview() {
    val state = rememberTerminalSurfaceState()
    TerminalSurface(
        state = state,
        modifier = Modifier,
    )
}
