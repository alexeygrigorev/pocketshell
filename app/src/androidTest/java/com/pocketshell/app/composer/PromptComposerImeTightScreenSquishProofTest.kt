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
 * Issue #801 — keyboard-up SQUISH proof modelling the REAL resized-sheet window
 * (the v0.4.6 regression of #567).
 *
 * ## The root cause this proof pins (#801 diagnostic, measured on a pixel_7)
 *
 * Inside the production `ModalBottomSheet` dialog window on a real pixel_7, when
 * the soft keyboard (343dp) raises, the sheet's `BoxWithConstraints.maxHeight`
 * shrinks from 814dp to 470dp — i.e. the sheet window ITSELF resizes to sit just
 * above the keyboard, so `maxHeight` is ALREADY the room above the keyboard. The
 * pre-#801 code wrongly assumed the window did NOT resize and subtracted the
 * keyboard intrusion `(ime - navBars)` a SECOND time:
 * `availableAboveKeyboard = 470 - (343 - 48) = 176dp`. That ~176dp double-count
 * left no room for a full draft + the sticky control row, so the reserve-and-floor
 * math fired and crushed the field to one line and the controls into a sliver —
 * the #567 double-subtract, re-introduced because the sheet's resize behaviour was
 * mis-modelled. The #801 fix uses `maxHeight` directly (no second subtraction) and
 * weights the scroll region to the genuine room.
 *
 * ## Why a NEW test next to [PromptComposerImeSquishProofTest]
 *
 * The #780 proof models the UN-resized window (a 740dp host with the keyboard
 * overlapping its bottom) and leaves ~464dp above the keyboard — so it never
 * exercised the resized-sheet double-count and passed even while the real device
 * squished. This proof models the RESIZED sheet window the production code now
 * relies on: the host height IS the room above the keyboard (the measured 470dp),
 * the keyboard sits at the host's bottom edge. On the pre-#801 double-subtract the
 * effective room collapses to ~176dp and the draft/controls crush (RED); the #801
 * fix fills the genuine 470dp (GREEN).
 *
 * ## Determinism (#780 model — no real keyboard, no `assumeTrue`)
 *
 * A synthetic `Type.ime()` inset is dispatched to the decor so `WindowInsets.ime`
 * reports keyboard-up (the only thing the production code reads the IME inset for
 * now — the `keyboardUp` branch), read back from INSIDE the composition and
 * HARD-asserted to have applied before any geometry is judged (never a skip).
 * Containment is asserted with the #657 / F1 helpers ([assertNodeFullyWithinRoot]
 * / [assertNodeFullyAboveImeOrKeyboard]) — never a bare `assertIsDisplayed()`.
 * Both a SHELL pane (`agentKind = null`) and an AGENT pane
 * (`agentKind = ClaudeCode`) are exercised.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerImeTightScreenSquishProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)
    private val observedStatusTopPx = mutableStateOf(0)

    private fun idleStateWithDraftAndAttachments(): PromptComposerViewModel.UiState =
        PromptComposerViewModel.UiState(
            draft = "Reduce the connector/indent cell width\n" +
                "Wrote 23 lines to issue.md\nMake the tiles compact",
            recording = PromptComposerViewModel.RecordingState.Idle,
            attachments = listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = "/tmp/Screenshot_20260617-101010.png",
                    displayName = "Screenshot_20260617-101010.png",
                ),
            ),
        )

    @Test
    fun shellPaneComposerNotSquishedOnResizedSheetWhenImeUp() {
        runResizedSheetProof(agentKind = null)
    }

    @Test
    fun agentPaneComposerNotSquishedOnResizedSheetWhenImeUp() {
        runResizedSheetProof(agentKind = AgentKind.ClaudeCode)
    }

    private fun runResizedSheetProof(agentKind: AgentKind?) {
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
                    // area above the keyboard is [HOST_HEIGHT_DP] (≈ the measured
                    // 470dp). The keyboard sits at this host's BOTTOM edge — the
                    // sheet window already resized to it — so the room above the
                    // keyboard is exactly HOST_HEIGHT_DP. SheetContent's
                    // BoxWithConstraints sees `maxHeight = HOST_HEIGHT_DP` and (post
                    // #801) uses it DIRECTLY as the room above the keyboard. Pre-#801
                    // it subtracted `(ime - navBars)` again, collapsing the usable
                    // room to ~176dp → the squish. All geometry is measured RELATIVE
                    // to this tagged host.
                    Box(
                        modifier = Modifier
                            .width(HOST_WIDTH_DP.dp)
                            .height(HOST_HEIGHT_DP.dp)
                            .testTag(HOST_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SheetContent(
                            state = idleStateWithDraftAndAttachments(),
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

        // Drive a known soft-IME inset so `WindowInsets.ime` reports keyboard-up
        // (the production code's `keyboardUp` branch). The inset value itself no
        // longer feeds the room math (post #801) — only its presence matters.
        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * displayDensity()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * displayDensity()).toInt(),
            statusBarTopPx = (STATUS_BAR_DP * displayDensity()).toInt(),
        )
        compose.waitForIdle()

        val density = displayDensity()
        val imeBottomPx = observedImeBottomPx.value

        // HARD-assert the synthetic IME inset actually reached Compose; otherwise we
        // would judge a keyboard-DOWN layout and the invariants would pass
        // vacuously. Never a skip (#736 / F3).
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #801 keyboard-up squish. observedImeBottomPx=$imeBottomPx " +
                "(expected ~$expectedImePx).",
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

        // The sheet already resized so the keyboard top is at the host's bottom
        // edge. That is the keyboard top for the containment helpers.
        val keyboardTopPx = hostBounds.bottom
        val draftHeightDp = draftBounds.height / density

        println(
            "ISSUE801_RESIZED agent=$agentKind draftHeightDp=$draftHeightDp " +
                "draftTop=${draftBounds.top} draftBottom=${draftBounds.bottom} " +
                "sendBottom=${sendBounds.bottom} attachBottom=${attachBounds.bottom} " +
                "micBottom=${micBounds.bottom} keyboardTopPx=$keyboardTopPx " +
                "hostHeightDp=${(hostBounds.bottom - hostBounds.top) / density} density=$density",
        )

        // 1) Draft keeps a sensible MULTI-LINE height — not crushed to a single
        //    line. Issue #873: the field now WRAPS to its content (≈ its 56dp
        //    keyboard-up min for this 3-line draft, whose lines each fit in one
        //    visual line) instead of `fillMaxHeight`-inflating to the whole room
        //    above the keyboard (the old ~97dp+ that left dead space). The
        //    squish this proof guards is now carried by the control-row-not-crushed
        //    + controls-reachable checks below; here we only confirm the field is
        //    not crushed BELOW its multi-line min (the maintainer's ~36dp
        //    single-line crush).
        assertTrue(
            "Draft field crushed to a thin strip (the #801 squish). " +
                "draftHeightDp=$draftHeightDp minDp=$MIN_DRAFT_HEIGHT_DP",
            draftHeightDp >= MIN_DRAFT_HEIGHT_DP,
        )

        // 2) Send + attach + mic must be LAID OUT below the draft (not collapsed/
        //    clipped above it, which is how the squish hid them).
        assertTrue(
            "Send control collapsed/clipped above the draft (squish). " +
                "sendBottom=${sendBounds.bottom} draftBottom=${draftBounds.bottom}",
            sendBounds.bottom >= draftBounds.bottom,
        )
        assertTrue(
            "Attach control collapsed/clipped above the draft (squish). " +
                "attachBottom=${attachBounds.bottom} draftBottom=${draftBounds.bottom}",
            attachBounds.bottom >= draftBounds.bottom,
        )

        // 3) CONTAINMENT (#657 / F1): every control the maintainer said was
        //    crammed/hidden must be FULLY within the host AND above the keyboard —
        //    the property `assertIsDisplayed()` does NOT check. Load-bearing,
        //    unconditional.
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

        // 4) Control row keeps its full natural height — not crammed into a sliver.
        //    Mic/Send discs are ~44dp in the mockup; a squished row collapses them.
        val micHeightDp = micBounds.height / density
        assertTrue(
            "Control row crammed into a sliver (squish). micHeightDp=$micHeightDp " +
                "minDp=$MIN_CONTROL_HEIGHT_DP",
            micHeightDp >= MIN_CONTROL_HEIGHT_DP,
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
        const val HOST_TAG = "issue801-resized-sheet-host"

        // The measured real pixel_7 sheet content area above the keyboard is ~470dp
        // (814dp keyboard-down → 470dp keyboard-up). This host models that resized
        // window directly: its height IS the room above the keyboard, the keyboard
        // sits at its bottom edge. SheetContent reads `maxHeight = HOST_HEIGHT_DP`.
        const val HOST_HEIGHT_DP = 470f
        const val HOST_WIDTH_DP = 392f

        // The synthetic IME inset only needs to make WindowInsets.ime report
        // keyboard-up (the production `keyboardUp` branch). Its magnitude no longer
        // feeds the room math post #801, but a realistic value keeps the model
        // honest.
        const val IME_HEIGHT_DP = 343f
        const val NAV_BAR_DP = 48f
        const val STATUS_BAR_DP = 52f

        // The field must keep its multi-line content visible, never the ~36dp
        // single-line crush the maintainer reported. Issue #873: the field now
        // wraps to its 3-line content (~49dp) instead of `fillMaxHeight`-inflating
        // to the whole room above the keyboard, so 44dp (above the ~36dp
        // single-line crush, below the 3-line content) excludes the crush while
        // accepting the new compact field. (In this tight config the draft height
        // is NOT the primary red/green discriminator — the control-row height
        // below is; the old reserve+floor crushed the CONTROLS to a 16dp sliver.)
        const val MIN_DRAFT_HEIGHT_DP = 44f

        // THE primary discriminator: mic/Send discs are ~44dp in the mockup. The
        // pre-#801 reserve+floor crammed the control row into a ~16dp sliver (the
        // maintainer's "crammed in, can't see anything"); the #801 fix keeps it
        // full-size. 36dp is well above the 16dp sliver and below the full disc, so
        // it is decisively red-on-base / green-on-fix.
        const val MIN_CONTROL_HEIGHT_DP = 36f
    }
}
