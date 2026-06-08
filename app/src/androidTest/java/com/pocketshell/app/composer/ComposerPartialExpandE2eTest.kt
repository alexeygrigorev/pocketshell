package com.pocketshell.app.composer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        // the standard app-specific external-files convention.
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

    @Test
    fun sendButtonRemainsAboveImeWhenDraftFocused() {
        var capturedSheetValue: SheetValue? = null
        val sendModes = mutableListOf<Boolean>()

        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    ComposerHarness(
                        onDismiss = {},
                        sheetState = sheetState,
                        onSheetValueChanged = { capturedSheetValue = it },
                        onSend = { withEnter -> sendModes += withEnter },
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            capturedSheetValue != null && capturedSheetValue != SheetValue.Hidden
        }

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("printf issue615")

        compose.activity.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }
        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #615 geometry",
            imeShown,
        )

        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()

        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val imeTop = readDecorHeightPx() - readImeBottomPx()

        assertTrue(
            "Send button must stay above the IME. sendBottom=${sendBounds.bottom} imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), sendModes)
        }

        compose.activity.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
    }

    @Test
    fun sendButtonRemainsAboveImeWithLongDraftAndAttachments() {
        var capturedSheetValue: SheetValue? = null
        val sendModes = mutableListOf<Boolean>()

        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                    ComposerHarness(
                        onDismiss = {},
                        sheetState = sheetState,
                        onSheetValueChanged = { capturedSheetValue = it },
                        onSend = { withEnter -> sendModes += withEnter },
                        attachments = issue615Attachments(),
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            capturedSheetValue != null && capturedSheetValue != SheetValue.Hidden
        }

        val longDraft = buildString {
            append("Open a new session after checking these screenshots. ")
            repeat(12) {
                append("Keep the folder picker visible while typing the prompt. ")
            }
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput(longDraft)

        compose.activity.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }
        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #615 geometry",
            imeShown,
        )

        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()

        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val imeTop = readDecorHeightPx() - readImeBottomPx()

        assertTrue(
            "Send button must stay above the IME with attachments. sendBottom=${sendBounds.bottom} imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), sendModes)
        }

        compose.activity.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
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
        onSend: (Boolean) -> Unit = {},
        attachments: List<PromptComposerViewModel.StagedAttachment> = emptyList(),
    ) {
        var draft by remember { mutableStateOf("") }
        var isImeVisible by remember { mutableStateOf(false) }
        // Surface the current sheet value out to the test on every
        // recomposition so the test's `waitUntil` can read the latest
        // value without polling via reflection.
        onSheetValueChanged(sheetState.currentValue)
        LaunchedEffect(isImeVisible, sheetState.currentValue) {
            if (shouldAutoExpandPromptComposerForIme(
                    isImeVisible = isImeVisible,
                    currentValue = sheetState.currentValue,
                    expandedForIme = false,
                )
            ) {
                runCatching { sheetState.expand() }
            }
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = PocketShellColors.Surface,
            contentColor = PocketShellColors.Text,
            contentWindowInsets = {
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                )
            },
        ) {
            val sheetDensity = LocalDensity.current
            val contentImeVisible = WindowInsets.ime.getBottom(sheetDensity) > 0
            LaunchedEffect(contentImeVisible) {
                isImeVisible = contentImeVisible
            }
            // Mirrors the production wiring in `PromptComposerSheet`:
            // the SheetContent fills 65% of available height so that
            // Material 3 populates the `PartiallyExpanded` anchor —
            // without this, the sheet's intrinsic content falls under
            // half-screen and M3 collapses straight to `Expanded`,
            // which is exactly the legacy behaviour issue #234 fixes.
            SheetContent(
                modifier = Modifier.fillMaxHeight(promptComposerSheetHeightFraction(contentImeVisible)),
                state = PromptComposerViewModel.UiState(
                    draft = draft,
                    recording = PromptComposerViewModel.RecordingState.Idle,
                    amplitude = 0f,
                    hasDetectedSpeech = false,
                    attachments = attachments,
                ),
                onClose = onDismiss,
                onDraftChange = { draft = it },
                onMicTap = {},
                onSend = onSend,
            )
        }
    }

    private fun issue615Attachments(): List<PromptComposerViewModel.StagedAttachment> =
        listOf(
            PromptComposerViewModel.StagedAttachment(
                remotePath = "~/.pocketshell/attachments/host-1/issue615-folder.png",
                displayName = "issue615-folder.png",
                mimeType = "image/png",
            ),
            PromptComposerViewModel.StagedAttachment(
                remotePath = "~/.pocketshell/attachments/host-1/issue615-session.png",
                displayName = "issue615-session.png",
                mimeType = "image/png",
            ),
        )

    private fun readImeBottomPx(): Int {
        var result = 0
        compose.activityRule.scenario.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            result = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return result
    }

    private fun readDecorHeightPx(): Int {
        var result = 0
        compose.activityRule.scenario.onActivity { activity ->
            result = activity.window.decorView.height
        }
        return result
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Give the sheet animation a tick to settle before snapshotting;
        // a frame ~16ms, allow a few for the partial-expand ease-out.
        android.os.SystemClock.sleep(300)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
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
