package com.pocketshell.core.terminal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Android Studio / Preview-tooling renders for [TerminalSurface]. Three
 * variants are exposed so visual regressions across the common surface
 * shapes are catchable at PR-review time:
 *
 * 1. [TerminalSurfaceUnattachedPreview] — the default Pixel 7 viewport with
 *    no attached session. Exercises the unattached canvas + design-language
 *    background-fill path.
 * 2. [TerminalSurfaceAttachedPreview] — same viewport, but the state has
 *    received synthetic bytes via [TerminalSurfaceState.emitOutputForTesting]
 *    so the [TerminalSurfaceState]'s `output` flow has emitted at least
 *    once. The visible canvas is still the default unattached View (no JNI
 *    in the preview tooling), but the state-holder side is meaningfully
 *    different — a reviewer scanning the inspector tree can confirm the
 *    flow is wired without needing to spin up an emulator.
 * 3. [TerminalSurfaceWidePreview] — a wide / landscape-ish viewport
 *    (640x360 dp). The surface should fill the parent without stretching
 *    the canvas or leaking white space; layout regressions show up
 *    immediately when this preview goes weird.
 *
 * Per the JNI deferral note in [TerminalSurface], previews must NOT attach
 * a real [com.termux.terminal.TerminalSession] — that would crash with
 * `UnsatisfiedLinkError` once the View's layout pass triggers
 * [com.termux.terminal.TerminalSession.updateSize] (which dispatches into
 * `libtermux.so`). The attached preview synthesises *output bytes* on the
 * state holder's flow instead, which exercises the public state surface
 * without touching JNI.
 *
 * The unattached surface renders as the design-language background colour
 * with the [com.termux.view.TerminalView]'s default black canvas inset;
 * that is enough for reviewers to eyeball sizing, padding, and typeface
 * behaviour. Issue #9 will swap in a richer preview once the JNI is wired
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

/**
 * "Attached" variant — exercises the [TerminalSurfaceState.output] flow by
 * pushing a synthetic line of bytes through [TerminalSurfaceState.emitOutputForTesting]
 * from a [LaunchedEffect]. The visible canvas is still the unattached
 * [com.termux.view.TerminalView] (the preview tooling has no JNI), but the
 * state-holder side reflects a non-empty session-output history. Useful
 * for reviewers eyeballing the surface in its "live" shape without standing
 * up a real session.
 */
@Preview(
    name = "TerminalSurface — attached (synthetic bytes)",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 360,
    heightDp = 640,
)
@Composable
private fun TerminalSurfaceAttachedPreview() {
    val state = rememberTerminalSurfaceState()
    // Drive a synthetic emission once the preview composes. The bytes never
    // reach a real emulator (no session attached), so this is purely a
    // state-holder-side smoke test that the flow plumbing is reachable from
    // the preview tooling.
    LaunchedEffect(state) {
        state.emitOutputForTesting("$ echo hello pocketshell\nhello pocketshell\n".toByteArray())
    }
    TerminalSurface(
        state = state,
        modifier = Modifier,
    )
}

/**
 * Wide-viewport variant — flips the default 360x640 ratio to a landscape-
 * ish 640x360. Catches resize / layout regressions that only surface when
 * the surface is wider than it is tall (split-screen, tablet portrait
 * with a side panel, etc.).
 */
@Preview(
    name = "TerminalSurface — wide / landscape",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 640,
    heightDp = 360,
)
@Composable
private fun TerminalSurfaceWidePreview() {
    val state = rememberTerminalSurfaceState()
    TerminalSurface(
        state = state,
        modifier = Modifier,
    )
}
