package com.pocketshell.app.proof.signals

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The window-inset padding `MainActivity` puts around EVERY screen, so a
 * component test that mounts a production surface with a bare `setContent`
 * measures it in the window state production actually has (issue #2180, F2).
 *
 * ## Why this exists
 *
 * This app targets SDK 35, and Android 15 makes every activity edge-to-edge
 * whether it asks or not — measured on the AVD as a plain `ComponentActivity`
 * whose Compose root spans `0..2400` with a 74 px status strip and a 126 px
 * navigation strip, with no `enableEdgeToEdge()` call in the fixture at all.
 * `MainActivity` then pads its top-level `Surface` with
 * `WindowInsets.safeDrawing.exclude(WindowInsets.ime)` (`MainActivity.kt:460`),
 * which is what keeps real screens off the bars. The `ime` exclusion is
 * deliberate and is copied verbatim: keyboard avoidance is owned per screen
 * because padding the window would resize the embedded `TerminalView`.
 *
 * A bare `setContent` harness inherits the edge-to-edge window and NOT that
 * padding, so it renders production chrome 126 px lower than production ever
 * does. Before #2180 that divergence was invisible — `assertNodeFullyWithinRoot`
 * compared against the raw root, which includes the bar strip, so a bottom
 * control sitting under the navigation bar passed. Repairing the assertion
 * surfaced 21 such cases across 6 classes; each one was a proof measuring a
 * window production never presents.
 *
 * Applying this to the harness root is therefore the F2-correct fix, not a way
 * to make an assertion go quiet: it moves the fixture TOWARDS the reported
 * state rather than relaxing what is asserted about it.
 */
@Composable
fun Modifier.productionWindowChromePadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
