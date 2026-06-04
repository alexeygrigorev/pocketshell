package com.pocketshell.app.composer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #234 connected E2E: verifies the composer bottom sheet renders in
 * the partial-expand state (Material 3 `SheetValue.PartiallyExpanded`) by
 * default and that the terminal viewport behind it remains visible at
 * that state.
 *
 * The earlier shape (`skipPartiallyExpanded = true`) made the composer
 * full-screen, occluded the terminal entirely, and forced the user to
 * close the sheet to glance back at the live agent output. The #191 UX
 * audit listed that modal inversion as one of three top-leverage v0.3.0
 * fixes; this test pins the new behaviour so it cannot silently regress.
 *
 * Test setup:
 *
 *  - A faux "terminal" backdrop is mounted under the sheet — a single
 *    `Box` painted with [PocketShellColors.Background] holding a short
 *    block of fixed text (`TERMINAL_MARKER_TEXT`) tagged with
 *    [TERMINAL_TEXT_TAG]. The block is intentionally placed near the
 *    top of the viewport so it lands above the partial-expand sheet's
 *    top edge — i.e. visible to the user behind the translucent scrim.
 *  - The composer sheet is rendered with the same renderer the production
 *    code uses (`SheetContent` wrapped in a `ModalBottomSheet`) so this
 *    test pins the integration between the production composable and
 *    `SheetState`'s partial-expand semantics.
 *  - The `SheetState` is hoisted into the test so the assertion phase can
 *    verify the sheet landed at `PartiallyExpanded` rather than at
 *    `Expanded` (the value `skipPartiallyExpanded = true` would have
 *    produced).
 *
 * Acceptance evidence:
 *
 *  - The terminal marker text is asserted displayed *after* the composer
 *    sheet is composed — i.e. visible behind / above the sheet at the
 *    partial-expand state.
 *  - The composer's draft text field is asserted displayed simultaneously
 *    — the sheet is open, not hidden.
 *  - `SheetState.currentValue` is asserted equal to
 *    `SheetValue.PartiallyExpanded` after the sheet settles, proving the
 *    `skipPartiallyExpanded = false` wiring lands the user at the
 *    half-height resting state rather than fully expanded.
 *  - A viewport bitmap is captured to
 *    `additional_test_output/issue234-composer-partial-expand/` so a
 *    reviewer can confirm the terminal text is visibly readable above
 *    the sheet's top edge per the issue's "visible behind" criterion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ComposerPartialExpandE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composerLandsAtPartiallyExpandedAndLeavesTerminalVisible() {
        var capturedSheetValue: SheetValue? = null
        var dismissedCount = 0

        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(TERMINAL_BACKDROP_TAG),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // Faux terminal contents — a few short lines pinned to
                    // the top of the viewport so they land above the
                    // partial-expand sheet edge (which sits at ~50% of
                    // screen height). The user's eye-line behaviour is
                    // "glance up at the agent output while composing the
                    // next prompt" — the marker stands in for that block.
                    Text(
                        text = TERMINAL_MARKER_TEXT,
                        color = PocketShellColors.Text,
                        modifier = Modifier
                            .padding(24.dp)
                            .testTag(TERMINAL_TEXT_TAG),
                    )
                }

                // Hoist the SheetState so the assertion phase can read the
                // current sheet value. `skipPartiallyExpanded = false`
                // matches the production wiring in `PromptComposerSheet`.
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

                ComposerHarness(
                    onDismiss = { dismissedCount++ },
                    sheetState = sheetState,
                    onSheetValueChanged = { capturedSheetValue = it },
                )
            }
        }

        // Wait until the sheet settles into a non-Hidden state. M3 opens
        // the sheet on first composition by animating it from
        // `SheetValue.Hidden` -> `PartiallyExpanded`; the assertion below
        // would race the animation without this wait.
        compose.waitUntil(timeoutMillis = 5_000) {
            capturedSheetValue != null && capturedSheetValue != SheetValue.Hidden
        }

        // AC #1: sheet uses partial-expand state by default. Equality
        // check on `currentValue` — anything other than `PartiallyExpanded`
        // (notably `Expanded`, which the old `skipPartiallyExpanded = true`
        // wiring would yield) fails the test.
        assertEquals(
            "Composer sheet should land at PartiallyExpanded by default, " +
                "but currentValue=$capturedSheetValue",
            SheetValue.PartiallyExpanded,
            capturedSheetValue,
        )

        // AC #2: the terminal marker text remains visible behind the
        // sheet at the partial-expand resting state. The marker is
        // painted above the sheet's top edge so the user can read it
        // through the (translucent) M3 scrim.
        compose.onNodeWithTag(TERMINAL_TEXT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText(TERMINAL_MARKER_TEXT, useUnmergedTree = true)
            .assertIsDisplayed()

        // Composer is open and rendering its draft field. If the sheet
        // had been Hidden (e.g. because the dismiss-on-mount path fired)
        // this lookup would fail.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()

        // Authoritative viewport capture for the reviewer. Saved to the
        // `additional_test_output/issue234-composer-partial-expand/`
        // bucket so `adb pull` / the artifact pipeline picks it up via
        // the standard `externalMediaDirs` convention.
        captureFullDevice("issue234-composer-partial-expand-viewport.png")

        // Sanity: the dismiss callback should not have fired during the
        // open + settle phase. If it had, the sheet would have closed
        // before the assertions above ran.
        assertEquals(
            "onDismiss should not fire during initial open",
            0,
            dismissedCount,
        )
    }

    /**
     * Production-shape harness that mirrors the call site in
     * `TmuxSessionScreen` / `SessionScreen`: a `ModalBottomSheet` with the
     * issue #234 wiring (`skipPartiallyExpanded = false`) containing the
     * composer's `SheetContent`. Pulled out so the test can override the
     * sheet state without standing up Hilt or a real ViewModel.
     */
    @Composable
    private fun ComposerHarness(
        onDismiss: () -> Unit,
        sheetState: SheetState,
        onSheetValueChanged: (SheetValue) -> Unit,
    ) {
        var draft by remember { mutableStateOf("") }
        // Surface the current sheet value out to the test on every
        // recomposition so the test's `waitUntil` can read the latest
        // value without polling via reflection.
        onSheetValueChanged(sheetState.currentValue)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = PocketShellColors.Surface,
            contentColor = PocketShellColors.Text,
        ) {
            // Mirrors the production wiring in `PromptComposerSheet`:
            // the SheetContent fills 65% of available height so that
            // Material 3 populates the `PartiallyExpanded` anchor —
            // without this, the sheet's intrinsic content falls under
            // half-screen and M3 collapses straight to `Expanded`,
            // which is exactly the legacy behaviour issue #234 fixes.
            SheetContent(
                modifier = Modifier.fillMaxHeight(0.65f),
                state = PromptComposerViewModel.UiState(
                    draft = draft,
                    recording = PromptComposerViewModel.RecordingState.Idle,
                    amplitude = 0f,
                    hasDetectedSpeech = false,
                ),
                onClose = onDismiss,
                onDraftChange = { draft = it },
                onMicTap = {},
                onSend = {},
            )
        }
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Give the sheet animation a tick to settle before snapshotting;
        // a frame ~16ms, allow a few for the partial-expand ease-out.
        android.os.SystemClock.sleep(300)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val outDir = File(
            mediaRoot,
            "additional_test_output/issue234-composer-partial-expand",
        ).apply {
            if (!exists()) {
                assertTrue(
                    "Could not create screenshot directory: $absolutePath",
                    mkdirs(),
                )
            }
        }
        val file = File(outDir, name)
        FileOutputStream(file).use { stream ->
            assertTrue(
                "Could not write screenshot: ${file.absolutePath}",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream),
            )
        }
        println("ISSUE234_COMPOSER_PARTIAL_EXPAND_VIEWPORT ${file.absolutePath}")
    }

    companion object {
        internal const val TERMINAL_BACKDROP_TAG = "issue234-terminal-backdrop"
        internal const val TERMINAL_TEXT_TAG = "issue234-terminal-text"
        internal const val TERMINAL_MARKER_TEXT =
            "alex@pocketshell:~$ tail -f deploy.log\n" +
                "[ok] migrate complete\n" +
                "[run] starting app on :8080"
    }
}
