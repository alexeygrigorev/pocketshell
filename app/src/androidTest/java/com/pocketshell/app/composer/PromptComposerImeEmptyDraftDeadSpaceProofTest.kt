package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #790 — Prompt Composer EMPTY-DRAFT keyboard-up DEAD-SPACE proof.
 *
 * ## What this proves (the #790 test-validity gap)
 *
 * The maintainer's keyboard-up screenshot showed a tiny "Compose prompt…" field
 * pinned near the top of the sheet and a LARGE empty dark band running down to
 * the keyboard. The existing #567 / #682 / #780 proofs all use a NON-empty draft
 * + attachments and only assert "not squished / Send above keyboard" — they pass
 * even with the dead band, because the void below an empty field is not a squish.
 * This proof closes that gap: it composes the EXACT reported state (EMPTY draft,
 * keyboard up) and HARD-asserts the gap between the draft field and the
 * Send/attach controls row is small — i.e. the sheet wraps compactly just above
 * the keyboard with NO reserved void.
 *
 * Red-on-base / green-on-fix:
 *  - BASE (fixed `height(imeScrollRegionHeight)`): the keyboard-up scroll region
 *    is forced to its full ~360dp height while the empty field stays at its 96dp
 *    min, so the Send/attach row is pushed ~250dp+ below the field bottom — the
 *    void. The gap assertion FAILS.
 *  - FIX (#790, `heightIn(max = imeScrollRegionMaxHeight)` — wrap-content up to a
 *    cap): the region collapses to the empty field's height, the Send/attach row
 *    sits right below the field, and the gap collapses to the small banner/spacer
 *    chrome. The gap assertion PASSES.
 *
 * ## Why this is CI-DETERMINISTIC (the #780 model, reused verbatim)
 *
 * There is NO real soft keyboard. We compose the production [SheetContent] in a
 * FIXED-height [Box] host (so the room-above-keyboard math is a known constant on
 * every AVD) and dispatch a SYNTHETIC `Type.ime()` window inset to the decor
 * view. Compose's `WindowInsets.ime` mirrors the dispatched value exactly (proven
 * by `SyntheticImeInsetSpikeTest`), so the keyboard-up geometry is fully
 * deterministic and local-green implies CI-green. The synthetic inset is
 * HARD-asserted to have applied before any geometry is judged — NO `assumeTrue` /
 * `assumeFalse(isRunningOnCi())` self-skip (F3): if the inset did not reach
 * Compose the test FAILS loud rather than passing vacuously on a keyboard-down
 * layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerImeEmptyDraftDeadSpaceProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Compose-observed insets (px), captured from INSIDE the composition so the
    // measured keyboard height is exactly what the laid-out composer reacted to.
    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)
    private val observedStatusTopPx = mutableStateOf(0)

    private fun emptyDraftIdleState(): PromptComposerViewModel.UiState =
        PromptComposerViewModel.UiState(
            draft = "",
            recording = PromptComposerViewModel.RecordingState.Idle,
            attachments = emptyList(),
        )

    @Test
    fun emptyDraftHasNoDeadBandAboveKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        compose.setContent {
            PocketShellTheme {
                val density = LocalDensity.current
                observedImeBottomPx.value = WindowInsets.ime.getBottom(density)
                observedNavBottomPx.value = WindowInsets.navigationBars.getBottom(density)
                observedStatusTopPx.value = WindowInsets.statusBars.getTop(density)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = "alex@pocketshell:~$ tail -f deploy.log",
                        color = PocketShellColors.Text,
                    )
                    // Fixed-height host modelling the composer window exactly as
                    // production sees it (#780 note): the ModalBottomSheet window
                    // does NOT reposition above the keyboard; its content is
                    // top-anchored and the body is capped to the room above the
                    // keyboard. All geometry below is measured RELATIVE to this
                    // tagged container.
                    Box(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONTAINER_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SheetContent(
                            state = emptyDraftIdleState(),
                            onClose = {},
                            onDraftChange = {},
                            onMicTap = {},
                            onSend = {},
                            // A non-null onAttachFiles makes the attach control
                            // render so we can assert it sits right below the field.
                            onAttachFiles = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * displayDensity()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * displayDensity()).toInt(),
            statusBarTopPx = (STATUS_BAR_DP * displayDensity()).toInt(),
        )
        compose.waitForIdle()

        val density = displayDensity()
        val imeBottomPx = observedImeBottomPx.value
        val navBottomPx = observedNavBottomPx.value

        // HARD-assert the synthetic IME inset reached Compose (#780 / F3). Without
        // it we would measure a keyboard-DOWN layout and the dead-band assertion
        // would pass vacuously. Never a skip.
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #790 empty-draft keyboard-up dead-space geometry. " +
                "observedImeBottomPx=$imeBottomPx (expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        val containerBounds = compose.onNodeWithTag(CONTAINER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val draftBounds = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val attachBounds = compose.onNodeWithTag(COMPOSER_ATTACH_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        val keyboardIntrusionPx = (imeBottomPx - navBottomPx).coerceAtLeast(0)
        val imeTopPx = containerBounds.bottom - keyboardIntrusionPx

        // The controls row top is the highest edge of the sticky Send/attach row.
        val controlsTopPx = minOf(sendBounds.top, attachBounds.top)
        // The void the maintainer reported: the gap between the bottom of the
        // (empty, ~96dp) draft field and the top of the Send/attach controls row.
        val deadBandPx = controlsTopPx - draftBounds.bottom
        val deadBandDp = deadBandPx / density
        val draftHeightDp = draftBounds.height / density

        println(
            "ISSUE790_DEADBAND deadBandDp=$deadBandDp draftHeightDp=$draftHeightDp " +
                "draftTop=${draftBounds.top} draftBottom=${draftBounds.bottom} " +
                "controlsTop=$controlsTopPx sendTop=${sendBounds.top} " +
                "attachTop=${attachBounds.top} imeTopPx=$imeTopPx " +
                "containerTop=${containerBounds.top} containerBottom=${containerBounds.bottom} " +
                "density=$density",
        )

        // 1) THE #790 SYMPTOM. With an empty draft + keyboard up the sheet must
        //    wrap compactly: the Send/attach row sits just below the field, NOT
        //    pushed far down by a reserved full-height region. On base the gap is
        //    the ~250dp+ void; the fix collapses it to the small banner/spacer
        //    chrome. [DEAD_BAND_MAX_DP] sits well between the two.
        assertTrue(
            "Empty-draft composer leaves a large dead band between the field and " +
                "the Send/attach controls with the keyboard up (issue #790). " +
                "deadBandDp=$deadBandDp maxDp=$DEAD_BAND_MAX_DP draftBottom=" +
                "${draftBounds.bottom} controlsTop=$controlsTopPx.",
            deadBandDp <= DEAD_BAND_MAX_DP,
        )

        // 2) The empty field must still be a sensible compact composer field, not
        //    crushed to nothing — its min height is honoured.
        assertTrue(
            "Empty draft field crushed below its min height. " +
                "draftHeightDp=$draftHeightDp minDp=$MIN_EMPTY_DRAFT_HEIGHT_DP.",
            draftHeightDp >= MIN_EMPTY_DRAFT_HEIGHT_DP,
        )

        // 3) CONTAINMENT (F2/F3): the Send + attach controls stay fully above the
        //    keyboard and within the window — reachable, not occluded. Uses the
        //    boundsInRoot containment helpers, NOT a bare assertIsDisplayed().
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = COMPOSER_SEND_ENTER_TAG,
            keyboardTopPx = imeTopPx,
            useUnmergedTree = true,
        )
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = COMPOSER_ATTACH_TAG,
            keyboardTopPx = imeTopPx,
            useUnmergedTree = true,
        )
        compose.assertNodeFullyWithinRoot(tag = COMPOSER_DRAFT_TAG, useUnmergedTree = true)
    }

    private fun applySyntheticInsets(
        imeBottomPx: Int,
        navBarBottomPx: Int,
        statusBarTopPx: Int,
    ) {
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, navBarBottomPx),
                )
                .setInsets(
                    WindowInsetsCompat.Type.statusBars(),
                    Insets.of(0, statusBarTopPx, 0, 0),
                )
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, statusBarTopPx, 0, navBarBottomPx),
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
    }

    private fun displayDensity(): Float =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private companion object {
        const val CONTAINER_TAG = "issue790-composer-host"

        // Fixed synthetic host geometry (DP) — deterministic on every AVD. Same
        // proportions as the #780 proof so the keyboard-up math is identical.
        const val CONTAINER_HEIGHT_DP = 740f
        const val CONTAINER_WIDTH_DP = 392f

        // Synthetic system insets (a realistic ~300dp soft keyboard).
        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val STATUS_BAR_DP = 28f

        // Max gap allowed between the empty field's bottom and the Send/attach
        // controls top before it reads as the #790 dead band. Measured: the fixed
        // base region pushes the controls ~250dp+ below the field; the wrap fix
        // collapses the gap to the small banner/spacer chrome (well under 80dp).
        const val DEAD_BAND_MAX_DP = 80f

        // The empty field's honoured min height (96dp in PromptComposerSheet),
        // less a dp of slop.
        const val MIN_EMPTY_DRAFT_HEIGHT_DP = 90f
    }
}
