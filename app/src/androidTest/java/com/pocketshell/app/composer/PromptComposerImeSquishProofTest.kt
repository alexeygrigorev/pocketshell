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
import androidx.compose.runtime.Composable
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
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #567 / #780 — composer + soft keyboard SQUISH proof, made
 * ENVIRONMENT-INDEPENDENT (#780).
 *
 * ## What this proves
 *
 * The maintainer's keyboard-up screenshot showed the draft field crushed to a
 * single line and the Send/attach row crammed together (Send half-clipped) while
 * the keyboard was up. The #567 fix is the single-subtract
 * `availableAboveKeyboard = maxHeight - (ime - navBars)` in [SheetContent]'s
 * `BoxWithConstraints` (with NO additional `imePadding()`). This test asserts the
 * composer body is NOT squished when the keyboard is up: the "Prompt Composer"
 * header stays on-screen, the body fits within the room above the keyboard, and
 * Send + attach stay reachable just above the keyboard with only a small gap.
 *
 * ## Why this is CI-DETERMINISTIC (the #780 fix)
 *
 * The previous version raised a REAL soft keyboard and read the live `ime()`
 * inset. That is the one environment-dependent variable: the CI swiftshader AVD
 * cannot reliably raise a real IME (sometimes never within 30s, sometimes raises
 * then fails geometry mid-animation), while the dev-box AVD raises it fine — so
 * local went green and CI stayed red. There is no real keyboard here at all.
 *
 * Instead we:
 *  - compose the PRODUCTION [SheetContent] (the exact pure-renderer that
 *    [PromptComposerSheet] delegates to — same `availableAboveKeyboard` math,
 *    same pinned-header / sticky-controls layout) directly in the activity
 *    window (NOT inside a `ModalBottomSheet` dialog window, so its
 *    `WindowInsets.ime` / `.navigationBars` / `.statusBars` consumers read the
 *    activity decor insets we control);
 *  - host it in a FIXED-height [Box] ([CONTAINER_HEIGHT_DP]) so the room-above-
 *    keyboard math is a known constant on every device regardless of physical
 *    screen size, and tag that container so all geometry is measured RELATIVE to
 *    it (never relative to the device decor, which varies per AVD);
 *  - dispatch a SYNTHETIC [WindowInsetsCompat] carrying a known `Type.ime()`
 *    bottom inset to the decor view via [ViewCompat.dispatchApplyWindowInsets].
 *    The spike `SyntheticImeInsetSpikeTest` confirmed Compose's `WindowInsets.ime`
 *    reflects this dispatched value exactly with no served editor and no system
 *    IME involvement. We read the inset that Compose ACTUALLY consumed from
 *    inside the composition (not via the system's `getRootWindowInsets`, which
 *    can be re-supplied by the real window), so the measured keyboard height and
 *    the laid-out geometry come from the same source of truth.
 *
 * Because the keyboard height, nav-bar height, status-bar height, AND the
 * container height are all fixed synthetic inputs, the layout math is fully
 * deterministic: it produces the SAME geometry on the dev-box AVD and on CI
 * swiftshader. Local-green now implies CI-green — there is no remaining
 * environment-dependent input. No `assumeTrue` / silent skip: the synthetic
 * inset is asserted to have actually applied before any geometry is judged, and
 * the squish invariants then run unconditionally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerImeSquishProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Compose-observed insets (px), captured from INSIDE the composition so the
    // measured keyboard height is exactly what the laid-out composer reacted to.
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
                    remotePath = "/tmp/Screenshot_20260606-135541.png",
                    displayName = "Screenshot_20260606-135541.png",
                ),
                PromptComposerViewModel.StagedAttachment(
                    remotePath = "/tmp/Screenshot_20260606-135556.png",
                    displayName = "Screenshot_20260606-135556.png",
                ),
            ),
        )

    @Test
    fun composerNotSquishedWithDraftAndAttachmentsWhenImeUp() {
        compose.activityRule.scenario.onActivity { activity ->
            // Edge-to-edge so the synthetic insets we dispatch are honoured by the
            // window the same way a real device honours the IME inset.
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        compose.setContent {
            PocketShellTheme {
                // Capture the insets Compose actually sees, in px, every frame.
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
                    // Fixed-height host modelling the composer's WINDOW exactly as
                    // production sees it: the `ModalBottomSheet` dialog window does
                    // NOT reposition above the soft keyboard (#567 note) — its
                    // bottom sits at screen-bottom-minus-navbar, partly BEHIND the
                    // keyboard, and the content is TOP-anchored within it. So this
                    // container is the un-resized window area and SheetContent's
                    // BoxWithConstraints sees `maxHeight = CONTAINER_HEIGHT_DP`,
                    // then caps its body to `maxHeight - (ime - navBars)` to keep it
                    // above the keyboard — the exact #567 lever. All geometry below
                    // is measured RELATIVE to this tagged container, never relative
                    // to the device decor (which varies per AVD).
                    Box(
                        modifier = Modifier
                            .width(CONTAINER_WIDTH_DP.dp)
                            .height(CONTAINER_HEIGHT_DP.dp)
                            .testTag(CONTAINER_TAG),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SheetContent(
                            state = idleStateWithDraftAndAttachments(),
                            onClose = {},
                            onDraftChange = {},
                            onMicTap = {},
                            onSend = {},
                            // A non-null onAttachFiles makes the attach control
                            // render (the #567 scenario stages attachments).
                            onAttachFiles = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // Drive a known soft-IME inset WITHOUT a real keyboard. The spike proved
        // Compose's WindowInsets.ime mirrors this dispatched value exactly.
        applySyntheticInsets(
            imeBottomPx = (IME_HEIGHT_DP * displayDensity()).toInt(),
            navBarBottomPx = (NAV_BAR_DP * displayDensity()).toInt(),
            statusBarTopPx = (STATUS_BAR_DP * displayDensity()).toInt(),
        )
        compose.waitForIdle()

        val density = displayDensity()
        val imeBottomPx = observedImeBottomPx.value
        val navBottomPx = observedNavBottomPx.value
        val statusTopPx = observedStatusTopPx.value

        // The synthetic IME inset MUST have actually reached Compose before we
        // judge geometry — otherwise we would be measuring a keyboard-DOWN layout
        // and the squish invariants would pass vacuously. This is a HARD
        // assertion, never a skip (#736): if the dispatch did not apply, fail
        // loud. It is environment-independent (the spike showed it always applies
        // because there is no system IME in the loop).
        val expectedImePx = (IME_HEIGHT_DP * density).toInt()
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "issue #567 keyboard-up squish geometry. observedImeBottomPx=" +
                "$imeBottomPx (expected ~$expectedImePx).",
            imeBottomPx > 0,
        )

        // Geometry, all RELATIVE to the fixed container (device-independent).
        val containerBounds = compose.onNodeWithTag(CONTAINER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val draftBounds = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val headerBounds = compose.onNodeWithTag(COMPOSER_CLOSE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val attachBounds = compose.onNodeWithTag(COMPOSER_ATTACH_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        // The keyboard intrudes into this window by (ime - navBars); the room left
        // above it is measured from the container bottom up — exactly the lever
        // SheetContent's `availableAboveKeyboard = maxHeight - (ime - navBars)`
        // uses. `imeTop` is the top edge of that synthetic keyboard within the
        // container's coordinate space.
        val keyboardIntrusionPx = (imeBottomPx - navBottomPx).coerceAtLeast(0)
        val containerTop = containerBounds.top
        val containerBottom = containerBounds.bottom
        val imeTop = containerBottom - keyboardIntrusionPx
        val roomAboveKeyboardPx = imeTop - containerTop

        val draftHeightDp = draftBounds.height / density
        val bodyTopPx = headerBounds.top
        val bodyBottomPx = maxOf(sendBounds.bottom, attachBounds.bottom)
        val bodyHeightPx = bodyBottomPx - bodyTopPx
        val slopPx = SLOP_DP * density

        // Log geometry FIRST, before any squish assertion, so a future failure is
        // always diagnosable straight from the CI log.
        println(
            "ISSUE567_SQUISH draftHeightDp=$draftHeightDp draftTop=${draftBounds.top} " +
                "draftBottom=${draftBounds.bottom} sendBottom=${sendBounds.bottom} " +
                "attachBottom=${attachBounds.bottom} imeTop=$imeTop " +
                "containerTop=$containerTop containerBottom=$containerBottom " +
                "imeBottomPx=$imeBottomPx navBottomPx=$navBottomPx statusTopPx=$statusTopPx " +
                "bodyTop=$bodyTopPx bodyBottom=$bodyBottomPx bodyHeightPx=$bodyHeightPx " +
                "roomAboveKeyboardPx=$roomAboveKeyboardPx density=$density",
        )

        // These invariants are calibrated against the measured squished-base vs
        // fixed geometry (issue #780 red-on-base evidence):
        //
        //   pre-#567 squish (double-subtract): draftHeightDp≈78, draftBottom≈437,
        //     sendBottom=0, attachBottom=0  — the draft is crushed to ~a line and
        //     the Send/attach controls are pushed off the bottom of the capped body
        //     (clipped out, bottom == 0). This is exactly the maintainer's
        //     screenshot (draft single line, controls crammed/clipped).
        //   #567 fix (single-subtract):       draftHeightDp≈192, draftBottom≈735,
        //     sendBottom≈1213, attachBottom≈1213 — full-size draft, controls laid
        //     out below it and above the keyboard.

        // 1) The Send + attach controls must actually be LAID OUT below the draft
        //    (not collapsed/clipped to the top of the body). The squish pushed them
        //    off the bottom of the double-subtracted body (bottom == 0), which is
        //    far above the draft bottom; this catches that directly.
        assertTrue(
            "Send control is collapsed/clipped above the draft (squish). " +
                "sendBottom=${sendBounds.bottom} draftBottom=${draftBounds.bottom}",
            sendBounds.bottom >= draftBounds.bottom,
        )
        assertTrue(
            "Attach control is collapsed/clipped above the draft (squish). " +
                "attachBottom=${attachBounds.bottom} draftBottom=${draftBounds.bottom}",
            attachBounds.bottom >= draftBounds.bottom,
        )

        // 2) The Send + attach controls must stay ABOVE the keyboard (reachable,
        //    not occluded) once laid out below the draft.
        assertTrue(
            "Send must stay above the IME (squish/occlusion). " +
                "sendBottom=${sendBounds.bottom} imeTop=$imeTop slopPx=$slopPx",
            sendBounds.bottom <= imeTop + slopPx,
        )
        assertTrue(
            "Attach must stay above the IME (squish/occlusion). " +
                "attachBottom=${attachBounds.bottom} imeTop=$imeTop slopPx=$slopPx",
            attachBounds.bottom <= imeTop + slopPx,
        )

        // 3) The draft field must keep a sensible multi-line height — NOT crushed
        //    to a thin strip. The double-subtract crushed it to ~78dp (the
        //    maintainer's "single line"); the fix gives it ~192dp.
        //    [MIN_DRAFT_HEIGHT_DP] sits well between the two.
        assertTrue(
            "Draft field crushed to a thin strip (squish). " +
                "draftHeightDp=$draftHeightDp minDp=$MIN_DRAFT_HEIGHT_DP",
            draftHeightDp >= MIN_DRAFT_HEIGHT_DP,
        )

        // 4) The "Prompt Composer" header must be ON SCREEN — not shoved off the
        //    top of the window. A clear sanity guard: the header top must sit at or
        //    below the container's top edge (never negative / clipped above it).
        assertTrue(
            "Composer header pushed off the top of the sheet (squish). " +
                "headerTop=$bodyTopPx containerTop=$containerTop statusTopPx=$statusTopPx",
            bodyTopPx >= containerTop - slopPx,
        )

        // 5) The body (header -> controls) must be lifted to sit just above the
        //    keyboard (small gap), not crammed at the window top with a void below
        //    (the #615 jump-to-top symptom). Only meaningful once the controls are
        //    laid out below the draft (#1), so this guards the fixed layout's gap.
        val gapBelowControlsPx = imeTop.toFloat() - bodyBottomPx
        val gapBelowControlsDp = gapBelowControlsPx / density
        println("ISSUE567_GAP gapBelowControlsDp=$gapBelowControlsDp")
        assertTrue(
            "Composer controls sit far above the keyboard (void). " +
                "gapBelowControlsDp=$gapBelowControlsDp",
            gapBelowControlsDp <= GAP_BELOW_CONTROLS_MAX_DP,
        )
    }

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }

    /**
     * Dispatch a synthetic [WindowInsetsCompat] to the decor view so Compose's
     * `WindowInsets.ime` / `.navigationBars` / `.statusBars` consumers read a
     * known keyboard-up layout with NO real soft keyboard. This is the crux of
     * the #780 fix: the IME inset is a fixed test input, not an
     * environment-dependent system event.
     */
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
        const val CONTAINER_TAG = "issue780-composer-host"

        // Fixed synthetic host geometry (in DP) — makes the layout deterministic
        // on every AVD regardless of physical screen size. The container is sized
        // generously so the un-squished composer body fits comfortably above the
        // synthetic keyboard, while the squished (pre-#567) layout overflows it.
        const val CONTAINER_HEIGHT_DP = 740f
        const val CONTAINER_WIDTH_DP = 392f

        // Synthetic system insets. A realistic soft-keyboard height (~300dp)
        // leaves a constrained room above it — the same pressure that exposed the
        // #567 squish on-device — but bounded and reproducible.
        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val STATUS_BAR_DP = 28f

        // Minimum draft-field height that counts as "not crushed". Measured: the
        // double-subtract squish crushed it to ~78dp; the #567 fix gives ~192dp.
        // 130dp sits comfortably between, so it is red-on-base and green-on-fix.
        const val MIN_DRAFT_HEIGHT_DP = 130f

        // Density-scaled slop so a sub-dp rounding wobble never flips a boundary.
        const val SLOP_DP = 4f

        // Max gap the controls may sit above the keyboard before it reads as a
        // "void" (the #615 jump-to-top symptom).
        const val GAP_BELOW_CONTROLS_MAX_DP = 64f
    }
}
