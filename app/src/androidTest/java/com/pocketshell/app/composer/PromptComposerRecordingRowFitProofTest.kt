package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1152 — the recording-not-locked composer bottom row overflowed its
 * width and CLIPPED `Send` off the right edge (the cyan "S/e" sliver the
 * maintainer circled — audit D1). The old single row packed the editing-tools
 * group + Lock + Insert + Send onto one line; the content (~421dp of pills + gaps)
 * exceeded a phone's usable width, and with no scroll/weight the trailing `Send`
 * fell off the right edge.
 *
 * The maintainer's directive is FIT EVERYTHING — never hide a control to make
 * room. The fix relays the recording states into a TWO-ROW layout (secondary row:
 * tools + Discard + Lock; primary row: Insert + Send) so EVERY control fits with
 * the editing tools still mounted.
 *
 * ## What this proves (red → green, class-covering)
 *
 * It composes the PRODUCTION [SheetContent] in the recording state — both
 * NOT-LOCKED and LOCKED — pinned to a FIXED phone-width band, keyboard-UP (the
 * #780 synthetic-inset model), at font scale 1.0 AND 1.3 (the widest-content
 * case, since this is a width-overflow bug). It then asserts, for every
 * recording-row pill (Discard / Lock / Insert / Send) AND the editing-tools
 * group:
 *  1. the pill is fully within the band horizontally (no edge spill), AND
 *  2. the pill is NOT CRUSHED — its width stays at/above a not-a-sliver floor.
 *
 * Both are needed because at a real phone width the single-row overflow does not
 * push `Send` cleanly off the edge — the un-weighted Row squeezes the trailing
 * `Send` down to a ~33dp sliver AT the band edge (the maintainer's cyan "S/e";
 * base evidence: `Send widthDp=33`). A bare containment / `assertIsDisplayed()`
 * still passes on that crushed sliver — the exact #657/F1 trap — so the
 * not-crushed floor is the LOAD-BEARING assertion that goes RED on base and GREEN
 * with the two-row fit (`Send widthDp≈90`).
 *
 * ## Why the band, not just [assertNodeFullyWithinRoot]
 *
 * The overflow at a phone width is ~20–30dp — razor-thin against the DEVICE
 * screen, so a slightly-wider CI AVD would mask it if we measured only against
 * `onRoot()` (the whole screen). We therefore host [SheetContent] in a
 * `requiredWidth` band anchored top-START and measure containment RELATIVE to the
 * band, so the red is DETERMINISTIC on every AVD regardless of physical width
 * (the #813 model). [assertNodeFullyWithinRoot] is asserted too as an additive
 * "never off the whole screen" guard, but the band-relative check is the
 * load-bearing red→green.
 *
 * ## Determinism
 *
 * Band width + height + the synthetic IME/nav/status insets are all fixed
 * synthetic inputs, so the geometry is identical on the dev-box AVD and CI
 * swiftshader. The synthetic ime() inset is HARD-asserted to have applied before
 * any geometry is judged (no `assumeTrue` skip — the #780/#736 rule): the row is
 * proven horizontally contained with the keyboard UP.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerRecordingRowFitProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val observedImeBottomPx = mutableStateOf(0)
    private val observedNavBottomPx = mutableStateOf(0)

    @Test
    fun recordingNotLockedRowPillsFullyWithinBand_phoneWidth() {
        assertRecordingRowFits(locked = false, fontScale = 1.0f)
    }

    @Test
    fun recordingNotLockedRowPillsFullyWithinBand_largeFont() {
        // 1.3 is the widest-content case — this is a width-overflow bug, so cover
        // the class (largest font) not just the reported 1.0 instance.
        assertRecordingRowFits(locked = false, fontScale = 1.3f)
    }

    @Test
    fun recordingLockedRowPillsFullyWithinBand_phoneWidth() {
        assertRecordingRowFits(locked = true, fontScale = 1.0f)
    }

    @Test
    fun recordingLockedRowPillsFullyWithinBand_largeFont() {
        assertRecordingRowFits(locked = true, fontScale = 1.3f)
    }

    private fun assertRecordingRowFits(locked: Boolean, fontScale: Float) {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        val state = PromptComposerViewModel.UiState(
            draft = "",
            recording = PromptComposerViewModel.RecordingState.Recording,
            recordingLocked = locked,
            amplitude = 0.5f,
            hasDetectedSpeech = true,
            recordingElapsedMs = 14_000L,
        )

        compose.setContent {
            PocketShellTheme {
                val base = LocalDensity.current
                // Scale the composition's font scale so the widest-content case is
                // reproduced deterministically (independent of the AVD's own font
                // setting).
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = fontScale),
                ) {
                    observedImeBottomPx.value = WindowInsets.ime.getBottom(LocalDensity.current)
                    observedNavBottomPx.value =
                        WindowInsets.navigationBars.getBottom(LocalDensity.current)
                    WindowInsets.statusBars.getTop(LocalDensity.current)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Background),
                        // top-START so the row overflow maps predictably off the
                        // band's RIGHT edge (the clipped-Send symptom), and the
                        // band's left aligns with the screen left.
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .requiredWidth(BAND_WIDTH_DP.dp)
                                .requiredHeight(BAND_HEIGHT_DP.dp)
                                .testTag(BAND_TAG),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            SheetContent(
                                state = state,
                                onClose = {},
                                onDraftChange = {},
                                onMicTap = {},
                                onSend = {},
                                // Non-null so the editing tools + snippets render
                                // (they must stay MOUNTED during recording, and
                                // must fit — the maintainer directive).
                                onAttachFiles = {},
                                onSnippets = {},
                                onCancelRecording = {},
                                onLockRecording = {},
                            )
                        }
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

        // HARD-assert the synthetic keyboard actually reached Compose — otherwise
        // we would be judging a keyboard-DOWN layout and the "keyboard up"
        // acceptance would pass vacuously (the #780/#736 rule; no assumeTrue skip).
        assertTrue(
            "Synthetic ime() inset did not reach Compose; cannot validate the " +
                "keyboard-up recording row. observedImeBottomPx=${observedImeBottomPx.value}",
            observedImeBottomPx.value > 0,
        )

        val density = displayDensity()
        val slopPx = CONTAINMENT_SLOP_DP * density
        val band = bounds(BAND_TAG)

        // The pills that MUST be present + fully within the band in this state,
        // each with a not-a-sliver minimum width. A labelled pill's natural width
        // is 70–102dp; base crushes the trailing `Send` to ~33dp, so a 56dp floor
        // cleanly separates crushed (red) from intact (green). The editing-tools
        // group is a fixed 3×40dp icon pill, so it uses the icon-width floor.
        val pills = buildList {
            add(Triple("Discard", COMPOSER_CANCEL_RECORDING_TAG, LABELLED_PILL_MIN_DP))
            if (!locked) add(Triple("Lock", COMPOSER_LOCK_RECORDING_TAG, LABELLED_PILL_MIN_DP))
            add(Triple("Insert", COMPOSER_TO_FIELD_TAG, LABELLED_PILL_MIN_DP))
            add(Triple("Send", COMPOSER_STOP_SEND_TAG, LABELLED_PILL_MIN_DP))
            // The editing tools group stays mounted during recording (the "fit
            // everything, don't hide" directive) — prove it is present + fits too.
            add(Triple("Tools(attach)", COMPOSER_ATTACH_TAG, ICON_MIN_DP))
        }

        for ((label, tag, minDp) in pills) {
            val pill = bounds(tag)
            val overflowRightPx = pill.right - band.right
            val overflowLeftPx = band.left - pill.left
            val widthDp = (pill.right - pill.left) / density
            val heightDp = (pill.bottom - pill.top) / density
            // Log geometry FIRST so a failure is diagnosable straight from the log.
            println(
                "ISSUE1152_FIT locked=$locked font=$fontScale $label " +
                    "pill=[${pill.left}..${pill.right}] band=[${band.left}..${band.right}] " +
                    "widthDp=$widthDp heightDp=$heightDp minDp=$minDp " +
                    "overflowRightPx=$overflowRightPx overflowLeftPx=$overflowLeftPx",
            )
            // LOAD-BEARING: the pill must not be crushed to a sliver. On base the
            // un-weighted overflow squeezes `Send` to ~33dp (the "S/e" sliver);
            // the two-row fit keeps every pill at its full width.
            assertTrue(
                "Recording-row pill '$label' is CRUSHED to a sliver (audit D1 — " +
                    "the overflow squeezes it below a legible width). widthDp=$widthDp " +
                    "minDp=$minDp locked=$locked font=$fontScale. A crushed pill still " +
                    "reports assertIsDisplayed() (the #657/F1 trap), so this width " +
                    "floor is the real check.",
                widthDp >= minDp,
            )
            assertTrue(
                "Recording-row pill '$label' spills off the RIGHT of the band " +
                    "(clipped/off-edge — audit D1). pill.right=${pill.right} " +
                    "band.right=${band.right} overflowRightPx=$overflowRightPx " +
                    "widthDp=$widthDp locked=$locked font=$fontScale",
                pill.right <= band.right + slopPx,
            )
            assertTrue(
                "Recording-row pill '$label' spills off the LEFT of the band. " +
                    "pill.left=${pill.left} band.left=${band.left} " +
                    "overflowLeftPx=$overflowLeftPx locked=$locked font=$fontScale",
                pill.left >= band.left - slopPx,
            )
            // Additive brief-named guard: never off the WHOLE screen either.
            compose.assertNodeFullyWithinRoot(tag)
        }
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

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
        const val BAND_TAG = "issue1152-recording-band"

        // A real phone content width (Pixel-class, matches #780's 392dp). The old
        // single-row content (~421dp) overflows it, so BASE (pre-fix) is RED here;
        // the two-row fix fits with comfortable margin (secondary row ~330dp,
        // primary row ~215dp) at both 1.0 and 1.3 font scale.
        const val BAND_WIDTH_DP = 392f
        const val BAND_HEIGHT_DP = 760f

        const val IME_HEIGHT_DP = 300f
        const val NAV_BAR_DP = 24f
        const val STATUS_BAR_DP = 28f

        const val CONTAINMENT_SLOP_DP = 1f

        // A labelled recording pill's natural width is 70–102dp (Discard/Lock/
        // Insert/Send) across font scale 1.0–1.3; base crushes the trailing `Send`
        // to ~33dp. 56dp is the not-a-sliver floor between them.
        const val LABELLED_PILL_MIN_DP = 56f
        // The editing-tools group is a fixed 3×40dp icon pill; 36dp is its floor.
        const val ICON_MIN_DP = 36f
    }
}
