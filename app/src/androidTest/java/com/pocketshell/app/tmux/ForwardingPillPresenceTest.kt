package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.SessionForwardingIndicatorState
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1487: the always-visible in-session port-forwarding pill in the session
 * top chrome ([ConsolidatedTopChrome]). This is the on-device acceptance for the
 * pill's reachability + no-collision contract (F2/F3 containment, not a bare
 * `assertIsDisplayed()`):
 *
 *  - when forwarding is ACTIVE the pill renders in the header row, is fully
 *    contained in the window ([assertNodeFullyWithinRoot]), and does NOT overlap
 *    the Terminal/Conversation toggle ([TMUX_TABS_TAG]) — the exact "not
 *    colliding with the toggle or the clock" acceptance criterion — across the
 *    single-tunnel, multiple-tunnel, restoring, and long-agent-title classes;
 *  - under extreme width pressure the pill yields BEFORE the toggle (#1320 — the
 *    toggle is a primary control that never clips), so it never collides;
 *  - when forwarding is INACTIVE there is NO pill node at all (no empty pill / no
 *    layout gap — the #641-class absent-direction trap).
 *
 * Composes the PRODUCTION `ConsolidatedTopChrome` with a synthetically-injected
 * active [SessionForwardingIndicatorState] (the #780 model — no `assumeTrue`
 * skip, hard-fail otherwise) so the state is entered deterministically without a
 * live tunnel. Wired into `scripts/ci-journey-suite.sh` so it runs in the
 * per-push emulator-journey gate.
 */
@RunWith(AndroidJUnit4::class)
class ForwardingPillPresenceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pillShownContainedAndNotCollidingWithToggle_singleTunnel() {
        mountChrome(
            sessionName = "scratch",
            forwardingState = SessionForwardingIndicatorState(active = true, tunnelCount = 1),
        )
        assertPillVisibleContainedAndClearOfToggle()
        capture("pf-pill-single-tunnel.png")
    }

    @Test
    fun pillShownContainedAndNotCollidingWithToggle_multipleTunnels() {
        mountChrome(
            sessionName = "scratch",
            forwardingState = SessionForwardingIndicatorState(active = true, tunnelCount = 3),
        )
        assertPillVisibleContainedAndClearOfToggle()
        capture("pf-pill-multiple-tunnels.png")
    }

    @Test
    fun pillShownContainedAndNotCollidingWithToggle_restoring() {
        mountChrome(
            sessionName = "scratch",
            forwardingState =
                SessionForwardingIndicatorState(active = true, tunnelCount = 0, restoring = true),
        )
        assertPillVisibleContainedAndClearOfToggle()
        capture("pf-pill-restoring.png")
    }

    @Test
    fun pillShownContainedAndNotCollidingWithToggle_longAgentTitle() {
        // A realistic long agent title on a 360dp phone: the title ellipsises
        // (its #1320 weight(1f) slot), the pill and toggle both stay visible.
        mountChrome(
            sessionName = "really-long-agent-session-title-that-overflows",
            forwardingState = SessionForwardingIndicatorState(active = true, tunnelCount = 2),
        )
        assertPillVisibleContainedAndClearOfToggle()
        capture("pf-pill-long-agent-title.png")
    }

    @Test
    fun pillYieldsBeforeToggleUnderExtremeWidthPressure() {
        // 300dp + a pathological title: the pill sits in the #1320 weighted
        // yielding region, so under extreme pressure it clips BEFORE the toggle.
        // The toggle (a primary control) must stay fully contained and the pill
        // must never collide with it — even if the pill itself yields.
        mountChrome(
            sessionName = "really-long-agent-session-title-that-absolutely-overflows-everything",
            forwardingState = SessionForwardingIndicatorState(active = true, tunnelCount = 4),
            widthDp = 300,
        )
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(TMUX_TABS_TAG)
        // If the pill is present it must be clear of the toggle; if it yielded
        // (not present) that is acceptable — the toggle never yields (#1320).
        if (pillPresent()) {
            assertPillClearOfToggle()
        }
        capture("pf-pill-extreme-width-pressure.png")
    }

    @Test
    fun pillAbsent_whenForwardingInactive() {
        mountChrome(
            sessionName = "scratch",
            forwardingState = SessionForwardingIndicatorState(active = false, tunnelCount = 0),
        )
        // No pill node at all — no empty pill, no layout gap.
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assertDoesNotExist()
        // The toggle is still fully contained — the header didn't reflow.
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(TMUX_TABS_TAG)
        capture("pf-pill-absent-inactive.png")
    }

    private fun mountChrome(
        sessionName: String,
        forwardingState: SessionForwardingIndicatorState,
        widthDp: Int = 360,
    ) {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = sessionName,
                        agentName = sessionName,
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 0,
                        onBack = {},
                        onMore = {},
                        forwardingState = forwardingState,
                        modifier = Modifier.width(widthDp.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertPillVisibleContainedAndClearOfToggle() {
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assertIsDisplayed()
        // Containment, NOT a bare assertIsDisplayed() (F1/F3).
        compose.assertNodeFullyWithinRoot(TMUX_PORT_FORWARD_PILL_TAG)
        // The toggle must also stay fully contained and never yield (#1320).
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(TMUX_TABS_TAG)
        assertPillClearOfToggle()
    }

    private fun assertPillClearOfToggle() {
        val slopPx = compose.density.density // 1dp
        val pill = compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG)
            .fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithTag(TMUX_TABS_TAG)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the forwarding pill (right=${pill.right}) must sit entirely to the " +
                "LEFT of the Terminal/Conversation toggle (left=${toggle.left}) — it " +
                "must not overlap the toggle (#1487 / #1320). pill=$pill toggle=$toggle",
            pill.right <= toggle.left + slopPx,
        )
    }

    private fun pillPresent(): Boolean =
        compose.onAllNodesWithTag(TMUX_PORT_FORWARD_PILL_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(150)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/tmux-forwarding-pill")
        check(dir.exists() || dir.mkdirs()) { "cannot create dir ${dir.absolutePath}" }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "cannot write ${file.absolutePath}"
                }
            }
            println("FORWARDING_PILL_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG = "tmux:forwarding-pill-presence-root"
    }
}
