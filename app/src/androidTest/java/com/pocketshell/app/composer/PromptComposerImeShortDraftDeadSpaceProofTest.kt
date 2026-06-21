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
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #873 — Prompt Composer SHORT-DRAFT keyboard-up DEAD-SPACE proof on the
 * REAL resized-sheet window.
 *
 * ## The reported state this proof pins (maintainer dogfood 2026-06-20)
 *
 * The maintainer's keyboard-up screenshot showed a SHORT draft ("gghh", ~1 line)
 * with the soft keyboard up, and ~1cm of empty dark space between the bottom of
 * the input text and the Send/mic action row. The input box itself was grown
 * tall and the text sat at its top, leaving a large empty band below.
 *
 * ## Why the existing #790 dead-space proof did NOT catch this
 *
 * [PromptComposerImeEmptyDraftDeadSpaceProofTest] composes the EMPTY-draft state
 * in an OVER-LARGE 740dp host. In a 740dp host the keyboard overlaps the host's
 * bottom and ~464dp is left above the keyboard, so the `weight(1f, fill = false)`
 * scroll region genuinely wraps to the field's height and the controls sit just
 * below — the gap collapses, the test passes. But the REAL `ModalBottomSheet`
 * window RESIZES to ~470dp when the keyboard raises (the #801 finding), and the
 * field's own `heightIn(min, max = 220.dp)` lets the weighted region grow the
 * draft box toward its 220dp max in that tight window, putting the text at the
 * top and a dead band below — exactly the maintainer's screenshot. This proof
 * models that REAL resized window (470dp, keyboard at its bottom edge) with a
 * SHORT one-line draft, which is the case neither prior proof exercised.
 *
 * Red-on-base / green-on-fix:
 *  - BASE (`maxHeight = 220.dp` + `weight(1f, fill = false)` with no compaction):
 *    in the tight 470dp window the weighted region inflates the short-draft box
 *    toward ~220dp, so the gap between the (one-line) text and the controls is
 *    the ~1cm dead band. The dead-band assertion FAILS.
 *  - FIX: the composer wraps to the short draft's content so the Send/attach/mic
 *    row sits right below the one-line field with no reserved void. The dead-band
 *    assertion PASSES, and the controls stay above the keyboard.
 *
 * ## Determinism (#780 model — synthetic ime() inset, no real keyboard, no skip)
 *
 * A synthetic `Type.ime()` inset is dispatched to the decor so `WindowInsets.ime`
 * reports keyboard-up, read back from INSIDE the composition and HARD-asserted to
 * have applied before any geometry is judged (never a skip / `assumeTrue`).
 * Containment uses the #657 / F1 helpers, never a bare `assertIsDisplayed()`.
 * Both a SHELL pane (`agentKind = null`) and an AGENT pane are exercised (G2
 * class coverage).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerImeShortDraftDeadSpaceProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)
    private val observedStatusTopPx = mutableStateOf(0)

    private fun shortDraftIdleState(): PromptComposerViewModel.UiState =
        PromptComposerViewModel.UiState(
            // The maintainer's exact reported draft: one short word.
            draft = "gghh",
            recording = PromptComposerViewModel.RecordingState.Idle,
            attachments = emptyList(),
        )

    @Test
    fun shellPaneShortDraftHasNoDeadBandOnResizedSheetWhenImeUp() {
        runShortDraftProof(agentKind = null)
    }

    @Test
    fun agentPaneShortDraftHasNoDeadBandOnResizedSheetWhenImeUp() {
        runShortDraftProof(agentKind = AgentKind.ClaudeCode)
    }

    private fun runShortDraftProof(agentKind: AgentKind?) {
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
                    FauxTerminalBackdrop()
                    // RESIZED-sheet host: the measured real pixel_7 sheet content
                    // area above the keyboard is ~470dp. The keyboard sits at this
                    // host's BOTTOM edge — the sheet window already resized to it —
                    // so the room above the keyboard IS HOST_HEIGHT_DP. This is the
                    // window the maintainer's screenshot was taken in. All geometry
                    // below is measured RELATIVE to this tagged host.
                    Box(
                        modifier = Modifier
                            .width(HOST_WIDTH_DP.dp)
                            .height(HOST_HEIGHT_DP.dp)
                            .testTag(HOST_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SheetContent(
                            state = shortDraftIdleState(),
                            onClose = {},
                            onDraftChange = {},
                            onMicTap = {},
                            onSend = {},
                            onAttachFiles = {},
                            agentKind = agentKind,
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

        // HARD-assert the synthetic IME inset reached Compose (#780 / F3). Without
        // it we would measure a keyboard-DOWN layout and the dead-band assertion
        // would pass vacuously. Never a skip.
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #873 short-draft keyboard-up dead-space geometry. " +
                "observedImeBottomPx=$imeBottomPx (expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        val hostBounds = compose.onNodeWithTag(HOST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val draftBounds = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val attachBounds = compose.onNodeWithTag(COMPOSER_ATTACH_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val micBounds = compose.onNodeWithTag(COMPOSER_MIC_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        // The sheet already resized; the keyboard top is at the host's bottom edge.
        val keyboardTopPx = hostBounds.bottom

        // The void the maintainer reported: the gap between the bottom of the
        // SHORT (one-line) draft field and the top of the Send/attach controls row.
        val controlsTopPx = minOf(sendBounds.top, attachBounds.top, micBounds.top)
        val deadBandPx = controlsTopPx - draftBounds.bottom
        val deadBandDp = deadBandPx / density
        val draftHeightDp = draftBounds.height / density

        println(
            "ISSUE873_SHORTDRAFT agent=$agentKind deadBandDp=$deadBandDp " +
                "draftHeightDp=$draftHeightDp draftTop=${draftBounds.top} " +
                "draftBottom=${draftBounds.bottom} controlsTop=$controlsTopPx " +
                "sendTop=${sendBounds.top} attachTop=${attachBounds.top} " +
                "micTop=${micBounds.top} keyboardTopPx=$keyboardTopPx " +
                "hostHeightDp=${(hostBounds.bottom - hostBounds.top) / density} " +
                "density=$density",
        )

        // 1) THE #873 SYMPTOM. With a SHORT one-line draft + keyboard up on the
        //    real resized window the sheet must wrap compactly: the Send/attach/mic
        //    row sits just below the one-line field, NOT pushed ~1cm down by a
        //    field box inflated toward its 220dp max. On base the gap is the dead
        //    band; the fix collapses it to the small spacer chrome.
        //    [DEAD_BAND_MAX_DP] sits well between the two.
        assertTrue(
            "Short-draft composer leaves a dead band between the one-line field " +
                "and the Send/attach controls with the keyboard up (issue #873). " +
                "deadBandDp=$deadBandDp maxDp=$DEAD_BAND_MAX_DP draftBottom=" +
                "${draftBounds.bottom} controlsTop=$controlsTopPx.",
            deadBandDp <= DEAD_BAND_MAX_DP,
        )

        // 2) The short-draft field must be COMPACT — not inflated to a tall box for
        //    one line of text. The dead space lived INSIDE an over-tall field box;
        //    a compact field keeps it near its min height for one line.
        assertTrue(
            "Short-draft field inflated to a tall box (the #873 dead space lives " +
                "inside it). draftHeightDp=$draftHeightDp maxDp=$SHORT_DRAFT_MAX_HEIGHT_DP.",
            draftHeightDp <= SHORT_DRAFT_MAX_HEIGHT_DP,
        )

        // 3) CONTAINMENT (F2/F3): the draft + Send + attach + mic controls stay
        //    fully above the keyboard and within the window — reachable, not
        //    occluded/clipped. Uses the boundsInRoot containment helpers, NOT a
        //    bare assertIsDisplayed().
        compose.assertNodeFullyWithinRoot(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
        compose.assertNodeFullyAboveImeOrKeyboard(
            COMPOSER_SEND_ENTER_TAG,
            keyboardTopPx = keyboardTopPx,
            useUnmergedTree = true,
        )
        compose.assertNodeFullyAboveImeOrKeyboard(
            COMPOSER_ATTACH_TAG,
            keyboardTopPx = keyboardTopPx,
            useUnmergedTree = true,
        )
        compose.assertNodeFullyAboveImeOrKeyboard(
            COMPOSER_MIC_TAG,
            keyboardTopPx = keyboardTopPx,
            useUnmergedTree = true,
        )
        // The draft field itself must stay above the keyboard too (the maintainer
        // also reported the input box being cut above the IME).
        compose.assertNodeFullyAboveImeOrKeyboard(
            COMPOSER_DRAFT_TAG,
            keyboardTopPx = keyboardTopPx,
            useUnmergedTree = true,
        )
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

    @androidx.compose.runtime.Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }

    private companion object {
        const val HOST_TAG = "issue873-resized-sheet-host"

        // The real pixel_7 sheet content area above the keyboard is ~470–543dp
        // depending on how far the sheet is expanded (#801 measured ~470 at
        // partial-expand; a more-expanded sheet leaves ~543 above the keyboard,
        // which is where the maintainer's screenshot was taken). We model the
        // LARGER room (543dp) because that is where the `weight(1f)` region
        // inflates the short-draft field box the most — the worst case for the
        // dead space. Its height IS the room above the keyboard; the keyboard sits
        // at its bottom edge.
        const val HOST_HEIGHT_DP = 543f
        const val HOST_WIDTH_DP = 392f

        // The synthetic IME inset only needs WindowInsets.ime to report keyboard-up.
        const val IME_HEIGHT_DP = 343f
        const val NAV_BAR_DP = 48f
        const val STATUS_BAR_DP = 52f

        // Max gap allowed between the SHORT field's bottom and the Send/attach
        // controls top before it reads as the #873 dead band. The gap to the
        // controls is small in both states; the maintainer's dead space lives
        // INSIDE the inflated field box (assertion 2), so this stays a loose guard.
        const val DEAD_BAND_MAX_DP = 48f

        // Max height for a COMPACT one-line short-draft field. The field's own
        // padding + one body line is ~56dp. With the `weight(1f)` region inflating
        // it in the tight resized window the base box grows to ~80dp at 470dp and
        // toward its 220dp max on a more-expanded sheet — that extra height is the
        // ~1cm dead space the maintainer circled (text centered, empty above +
        // below). 70dp sits just above the compact ~56dp one-line field and well
        // below the inflated base box, so it is red-on-base / green-on-fix.
        const val SHORT_DRAFT_MAX_HEIGHT_DP = 70f
    }
}
