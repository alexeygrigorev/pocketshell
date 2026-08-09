package com.pocketshell.app.composer

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.insets.SyntheticImeStage
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1622 — SCREENSHOT STAGING HARNESS (not a regression gate).
 *
 * Holds the production composer sheet on screen, with a device-representative
 * status-bar inset and (per case) a synthetic keyboard, long enough for a
 * host-side `adb exec-out screencap` to capture the before/after evidence the
 * issue asks for. Every method no-ops unless `issue1622HoldMs` is passed, so
 * normal and CI runs never block on it — the same opt-in shape as
 * [PromptComposerImeDeadSpaceScreenshotHarness].
 *
 * ## Why this cannot simply raise the real keyboard
 *
 * The sibling #790 harness raises the REAL soft IME. That is the right shape for
 * the #790 symptom, and the wrong one here: a modal sheet's window on a bare AVD
 * reports a ~0 top inset, so mechanism A — the dead 45 dp of `safeDrawing.Top`
 * padding the maintainer measured on his Pixel — is simply ABSENT from any
 * default-emulator screenshot. That absence is exactly what made the July
 * re-audit call mechanism A a non-defect. So this harness drives the state
 * through [SyntheticImeStage], which dispatches the status-bar / nav-bar / ime
 * insets into the modal's OWN window and hard-asserts the dispatch landed.
 *
 * The keyboard-up cases carry a synthetic ime INSET, so the bottom strip of the
 * screenshot is empty rather than showing painted keys — the sheet's own
 * geometry above that boundary is what the picture is for.
 *
 * No assertion about geometry is made here.
 * [Issue1622ComposerSheetGeometryProofTest] is the gate; this only stages the
 * picture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class Issue1622DeadBandScreenshotHarness {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stage by lazy { SyntheticImeStage(compose) }

    @After
    fun releaseSyntheticInsets() {
        runCatching { stage.dispatch(imeBottomPx = 0, requireExtraWindowRoot = false) }
    }

    @Test
    fun emptyDraftKeyboardDownHold() { stageAndHold(draft = "", keyboardUp = false) }

    @Test
    fun emptyDraftKeyboardUpHold() { stageAndHold(draft = "", keyboardUp = true) }

    @Test
    fun longDraftKeyboardUpHold() { stageAndHold(draft = LONG_DRAFT, keyboardUp = true) }

    private fun stageAndHold(draft: String, keyboardUp: Boolean) {
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("issue1622HoldMs")?.toLongOrNull() ?: 0L
        if (holdMs <= 0L) return

        stage.startEdgeToEdge()
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    FauxTerminalBackdrop()
                    ComposerModalBottomSheet(
                        onDismissRequest = {},
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().testTag(STAGE_TAG)) {
                            SheetContent(
                                state = PromptComposerViewModel.UiState(
                                    draft = draft,
                                    recording = PromptComposerViewModel.RecordingState.Idle,
                                ),
                                onClose = {},
                                onDraftChange = {},
                                onMicTap = {},
                                onSend = {},
                                onAttachFiles = {},
                            )
                        }
                    }
                }
            }
        }
        assertTrue(
            "The composer sheet must mount before it is photographed.",
            stage.awaitStable(STAGE_TAG),
        )
        stage.hideRealImeAndAssertHidden(
            "Issue #1622 staging must not mix a real keyboard with the synthetic boundary.",
        )
        stage.dispatch(
            imeBottomPx = if (keyboardUp) stage.imeBottomPx else 0,
            requireExtraWindowRoot = true,
        )
        assertTrue("Staged geometry must settle before the hold.", stage.awaitStable(STAGE_TAG))

        Log.i(HOLD_LOG_TAG, "ISSUE1622_HOLD_BEGIN keyboardUp=$keyboardUp draft=${draft.length} ms=$holdMs")
        android.os.SystemClock.sleep(holdMs)
        Log.i(HOLD_LOG_TAG, "ISSUE1622_HOLD_END")
    }

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }

    private companion object {
        const val HOLD_LOG_TAG = "Issue1622Hold"
        const val STAGE_TAG = "issue1622:staging:sheet-content"
        val LONG_DRAFT = (1..40).joinToString("\n") {
            "line $it — reduce the connector cell width and re-run the journey suite"
        }
    }
}
