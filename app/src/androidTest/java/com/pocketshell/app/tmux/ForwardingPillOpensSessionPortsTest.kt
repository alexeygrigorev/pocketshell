package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.portfwd.SESSION_PORTS_ALL_HOST_PORTS_TAG
import com.pocketshell.app.portfwd.SESSION_PORTS_EMPTY_TAG
import com.pocketshell.app.portfwd.SESSION_PORTS_OVERLAY_TAG
import com.pocketshell.app.portfwd.SessionForwardingIndicatorState
import com.pocketshell.app.portfwd.SessionPortRow
import com.pocketshell.app.portfwd.SessionPortsOverlay
import com.pocketshell.app.portfwd.SessionPortsPanelState
import com.pocketshell.app.portfwd.sessionPortRowTag
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.pocketshell.app.insets.dispatchSyntheticWindowInsets
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.proof.signals.assertNodeFullyWithinSystemBarsContentArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2176 addendum — the forwarding pill must be a real, reachable entry
 * point into the ports panel.
 *
 * The maintainer's report: the `⇄ 58p` pill tells him a great deal of forwarding
 * is happening and then does nothing. Before this change it had **no `clickable`
 * modifier at all**, so it was a dead end by construction.
 *
 * Each assertion here is chosen against a specific way this could ship broken:
 *
 *  - **Reachability, not presence.** An 11sp label with 3dp of padding is about
 *    20dp tall — visible, and effectively untappable. `assertIsDisplayed()` is
 *    satisfied by layout participation and would pass on both the too-small and
 *    the pushed-off-edge versions (the #641/#747 class), so the load-bearing
 *    checks are the 48dp touch floor and viewport CONTAINMENT.
 *  - **Actually actionable.** A `clickable` that never reaches the callback is
 *    the classic silent no-op, so the tap is driven through the production
 *    chrome and the panel must actually appear.
 *  - **Accessible.** A control announced only as status leaves a screen-reader
 *    user with no way to know it can be used.
 *  - **Sensible while restoring.** The amber `…` state is transient; opening
 *    onto a misleading empty list would be worse than the dead end it replaced.
 */
@RunWith(AndroidJUnit4::class)
class ForwardingPillOpensSessionPortsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rootTag = "issue2176-chrome-root"

    /** The nav-bar inset Compose actually consumed, read from inside the composition. */
    private val observedNavBottomPx = mutableStateOf(0)

    /**
     * The narrowest realistic phone content width, used ONLY by the chrome-pressure
     * test. The behaviour tests use the device's own width: constraining them to
     * 360dp would make them fail for a reason (title width starvation) that has
     * nothing to do with what they assert.
     */
    private val narrowPhoneContentWidth = 360.dp

    private fun row(port: Int, localPort: Int? = null) = SessionPortRow(
        port = port,
        localPort = localPort,
        process = "node",
        matchedText = "Local: http://localhost:$port/",
        firstSeenAtEpochMs = port.toLong(),
    )

    /**
     * The production chrome wired to the production panel, exactly as
     * `TmuxSessionScreen` wires them: the pill's tap flips the overlay flag.
     */
    private fun showChrome(
        forwarding: SessionForwardingIndicatorState,
        panel: SessionPortsPanelState,
        onOpenHostPorts: () -> Unit = {},
        contentWidth: Dp? = null,
    ) {
        compose.setContent {
            PocketShellTheme {
                var showPorts by remember { mutableStateOf(false) }
                // Read the insets Compose ACTUALLY consumed, from inside the
                // composition, so the geometry judged below and the inset it is
                // judged against come from one source of truth.
                val density = LocalDensity.current
                observedNavBottomPx.value = WindowInsets.navigationBars.getBottom(density)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(rootTag),
                ) {
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        Box(
                            modifier = contentWidth
                                ?.let { Modifier.width(it) }
                                ?: Modifier.fillMaxWidth(),
                        ) {
                            ConsolidatedTopChrome(
                                sessionName = "pocketshell",
                                onBack = {},
                                onMore = {},
                                tabLabels = listOf("Terminal", "Conversation"),
                                selectedTabIndex = 0,
                                connectionStatus = ConnectionStatus.Connected,
                                forwardingState = forwarding,
                                onOpenSessionPorts = { showPorts = true },
                            )
                        }
                    }
                    SessionPortsOverlay(
                        visible = showPorts,
                        hostName = "hetzner",
                        sessionName = "pocketshell",
                        state = panel,
                        onDismiss = { showPorts = false },
                        onOpenForwarded = {},
                        onRequestForward = {},
                        onOpenHostPorts = onOpenHostPorts,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun busyForwarding(restoring: Boolean = false) = SessionForwardingIndicatorState(
        active = true,
        // The maintainer's own screenshot: `58p`. A count this high is exactly
        // when "which one is mine?" is unanswerable from the host-wide list.
        tunnelCount = 58,
        restoring = restoring,
    )

    /**
     * Addendum criterion 1 (reachability). The pill's own box must meet the 48dp
     * touch floor in BOTH axes and lie fully inside the viewport.
     */
    @Test
    fun theForwardingPillIsARealReachableTapTarget() {
        showChrome(busyForwarding(), SessionPortsPanelState(rows = listOf(row(5173))))

        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assertIsDisplayed()
        // A bare assertIsDisplayed() would pass on the ~20dp-tall pill this
        // replaced, and on one shoved off the right edge. These are the real
        // checks. Height carries the 48dp a11y floor; width is not FORCED to 48dp
        // (see ForwardingStatusPill's KDoc — that measurably starved the title and
        // worsened the #747 off-screen-kebab pressure), so it is asserted as the
        // genuinely-tappable size the label plus glyph plus padding produces.
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assertWidthIsAtLeast(40.dp)
        compose.assertNodeFullyWithinRoot(TMUX_PORT_FORWARD_PILL_TAG)
    }

    /**
     * The adjacency this change could plausibly break (#747): the trailing cluster
     * does not shrink, so making the pill bigger risks pushing the kebab off the
     * right edge — the exact defect #747 shipped. At the narrowest realistic phone
     * width, with the widest realistic cluster (agent tab pill + a busy forwarding
     * pill + a non-live status pill), BOTH the pill and the kebab must stay fully
     * inside the viewport.
     */
    @Test
    fun theWiderPillDoesNotPushTheKebabOffScreenOnANarrowPhone() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(rootTag),
                ) {
                    Box(modifier = Modifier.width(narrowPhoneContentWidth)) {
                        ConsolidatedTopChrome(
                            sessionName = "3d-models",
                            agentName = "claude-3-5-sonnet",
                            onBack = {},
                            onMore = {},
                            tabLabels = listOf("Terminal", "Conversation"),
                            selectedTabIndex = 1,
                            connectionStatus = ConnectionStatus.Error,
                            forwardingState = busyForwarding(),
                            onOpenSessionPorts = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.assertNodeFullyWithinRoot(TMUX_PORT_FORWARD_PILL_TAG)
        compose.assertNodeFullyWithinRoot(TMUX_FULL_CHROME_MORE_BUTTON_TAG)
    }

    /** ...and it carries a real click action, not merely a large box. */
    @Test
    fun theForwardingPillExposesAClickAction() {
        showChrome(busyForwarding(), SessionPortsPanelState(rows = listOf(row(5173))))

        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assert(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
        )
    }

    /**
     * Addendum criterion 1 (behaviour): tapping it opens the ports panel — the
     * dead end is gone.
     */
    @Test
    fun tappingTheForwardingPillOpensThePortsPanel() {
        showChrome(busyForwarding(), SessionPortsPanelState(rows = listOf(row(5173, 18173))))

        compose.onNodeWithTag(SESSION_PORTS_OVERLAY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SESSION_PORTS_OVERLAY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(sessionPortRowTag(5173)).assertIsDisplayed()
    }

    /**
     * Addendum criterion 2: the description must convey that the pill DOES
     * something, not just what is happening. It keeps the #1487 status wording so
     * nothing is lost.
     */
    @Test
    fun theForwardingPillIsDescribedAsActionable() {
        showChrome(busyForwarding(), SessionPortsPanelState())

        val description = compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .joinToString(" ")

        assertTrue(
            "the status wording must survive; got: $description",
            description.contains("58 ports forwarding active for this host"),
        )
        assertTrue(
            "...and it must say the pill is actionable; got: $description",
            description.contains("Opens session ports", ignoreCase = true),
        )
    }

    /**
     * Addendum criterion 3: tapping while RESTORING (amber `…`) opens the same
     * panel with this session's real, durable rows — a transient reconnect does
     * not empty them — and the host-wide route is labelled "reconnecting…" rather
     * than quoting a tunnel count that is momentarily meaningless.
     */
    @Test
    fun tappingWhileRestoringOpensARealListAndDoesNotQuoteAStaleCount() {
        showChrome(
            busyForwarding(restoring = true),
            SessionPortsPanelState(
                rows = listOf(row(5173)),
                hostTunnelCount = 58,
                hostRestoring = true,
            ),
        )

        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SESSION_PORTS_OVERLAY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(sessionPortRowTag(5173)).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_PORTS_EMPTY_TAG).assertDoesNotExist()
        compose.onNodeWithText("All host ports — reconnecting…").assertIsDisplayed()
    }

    /**
     * ...and if this session genuinely has no ports of its own, the panel says so
     * honestly AND still offers the host-wide route — which is the maintainer's
     * literal request ("so I can manually look for it") preserved in the one case
     * where the curated list cannot answer it.
     */
    @Test
    fun anEmptySessionStillOffersTheHostWideRoute() {
        var openedHostPanel = 0
        showChrome(
            busyForwarding(),
            SessionPortsPanelState(rows = emptyList(), hostTunnelCount = 58),
            onOpenHostPorts = { openedHostPanel += 1 },
        )

        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SESSION_PORTS_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithText("All host ports (58 forwarding)").assertIsDisplayed()

        compose.onNodeWithTag(SESSION_PORTS_ALL_HOST_PORTS_TAG).performClick()
        compose.waitForIdle()

        assertEquals(1, openedHostPanel)
    }

    /**
     * The maintainer's literally-requested route must be REACHABLE, not merely
     * laid out — on BOTH panel states.
     *
     * ## Why `assertNodeFullyWithinRoot` was the wrong instrument here
     *
     * `MainActivity` calls `enableEdgeToEdge()`, and under edge-to-edge the
     * Compose root INCLUDES the strip behind the system navigation bar. The
     * round-1 build drew this footer inside that strip — the Back triangle cut
     * through the label and taps there went to the system bar — and
     * `assertNodeFullyWithinRoot(SESSION_PORTS_ALL_HOST_PORTS_TAG)` was **green
     * the whole time**. No mutation could redden it, which is exactly the
     * D32/G6 wrong-cost shape: an assertion that passes, and would still pass
     * with the bug present.
     *
     * So this asserts against the area inside the system-bar insets, and it is
     * driven by a SYNTHETIC navigation-bar inset (the #780 model) so the check is
     * identical on the dev-box AVD and on CI swiftshader regardless of whether
     * that device has a 3-button bar, a gesture pill, or none. The inset is
     * HARD-asserted to have actually reached Compose first — no `assumeTrue`, no
     * silent skip: an inset that never applied would make every containment check
     * below vacuous.
     *
     * Named mutation this must redden: delete `.navigationBarsPadding()` from the
     * panel `Column` in `SessionPortsPanel.kt` (i.e. restore the round-1 defect).
     */
    @Test
    fun thePanelAndItsHostWideRouteStayAboveTheSystemNavigationBar() {
        enableEdgeToEdgeWindow()
        showChrome(
            busyForwarding(),
            // Populated: the footer AND the last data row are both bottom-most
            // candidates for the nav-bar strip, and the reviewer observed the
            // defect on both the empty and the populated state.
            SessionPortsPanelState(
                rows = listOf(row(5173, 18173), row(8000)),
                hostTunnelCount = 58,
            ),
        )
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).performClick()
        compose.waitForIdle()
        applySyntheticSystemBars()
        compose.waitForIdle()

        val navBottomPx = observedNavBottomPx.value.toFloat()
        assertTrue(
            "the synthetic navigationBars inset never reached Compose, so a " +
                "containment check against it would be vacuous. observedNavBottomPx=" +
                "${observedNavBottomPx.value} (expected ~${expectedNavBarPx()})",
            navBottomPx > 0f,
        )

        compose.assertNodeFullyWithinSystemBarsContentArea(
            SESSION_PORTS_ALL_HOST_PORTS_TAG,
            bottomInsetPx = navBottomPx,
        )
        compose.assertNodeFullyWithinSystemBarsContentArea(
            sessionPortRowTag(8000),
            bottomInsetPx = navBottomPx,
        )
    }

    /** ...and the same holds for the empty state, which has no rows to push it up. */
    @Test
    fun theEmptyPanelsHostWideRouteStaysAboveTheSystemNavigationBar() {
        enableEdgeToEdgeWindow()
        showChrome(
            busyForwarding(),
            SessionPortsPanelState(rows = emptyList(), hostTunnelCount = 58),
        )
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).performClick()
        compose.waitForIdle()
        applySyntheticSystemBars()
        compose.waitForIdle()

        val navBottomPx = observedNavBottomPx.value.toFloat()
        assertTrue(
            "the synthetic navigationBars inset never reached Compose. " +
                "observedNavBottomPx=${observedNavBottomPx.value}",
            navBottomPx > 0f,
        )
        compose.onNodeWithTag(SESSION_PORTS_EMPTY_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinSystemBarsContentArea(
            SESSION_PORTS_ALL_HOST_PORTS_TAG,
            bottomInsetPx = navBottomPx,
        )
    }

    /**
     * The touch target grows; the PAINTED chip does not.
     *
     * Round 1 put the 48dp floor on the same node as `.background(...)` and
     * BEFORE it in the modifier chain, so the paint grew with it: a 48dp
     * dark-teal block, visibly taller and boxier than the 32dp tab pill beside it
     * in a 56dp row, against the maintainer's compact-chip screenshot. Nothing
     * caught it, because the only size assertion was a MINIMUM on the tag that
     * carries both roles.
     *
     * The property is relative, so it stays honest across densities and font
     * scales: the hit area is at least 48dp, and the painted chip is no taller
     * than its immediate neighbour in the same row (and strictly shorter than its
     * own hit area).
     *
     * Named mutation this must redden: move `.defaultMinSize(minHeight =
     * PocketShellDensity.tapTargetMin)` back onto the painted `Row`, ahead of
     * `.background(...)`.
     */
    @Test
    fun theForwardingPillTouchTargetGrowsWithoutGrowingThePaintedChip() {
        showChrome(busyForwarding(), SessionPortsPanelState(rows = listOf(row(5173))))

        val density = compose.density.density
        val touchDp = compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG)
            .fetchSemanticsNode().size.height / density
        val paintDp = compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_PAINT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().size.height / density
        val neighbourDp = compose.onNodeWithTag(TMUX_TABS_TAG)
            .fetchSemanticsNode().size.height / density
        // Log the geometry BEFORE asserting, so a future failure is diagnosable
        // straight from the instrumentation log.
        println(
            "ISSUE2176_PILL touchDp=$touchDp paintDp=$paintDp neighbourDp=$neighbourDp",
        )

        assertTrue(
            "the hit area must still meet the 48dp floor; touchDp=$touchDp",
            touchDp >= 48f - 1f,
        )
        assertTrue(
            "the PAINTED pill must not have grown to the touch floor — it must " +
                "stay the same class of compact chip as the tab pill beside it " +
                "(the maintainer's screenshot shows the two within a couple of dp; " +
                "the 48dp block was ~16dp taller). paintDp=$paintDp " +
                "neighbourDp=$neighbourDp touchDp=$touchDp",
            paintDp <= neighbourDp + NEIGHBOUR_HEIGHT_SLOP_DP,
        )
        assertTrue(
            "the painted chip must be strictly shorter than its own hit area, or " +
                "the floor is being carried by the paint. paintDp=$paintDp " +
                "touchDp=$touchDp",
            paintDp < touchDp - 1f,
        )
    }

    /**
     * Addendum criterion 4: leaving the panel returns to the session the user came
     * from, still there. Because the panel is an OVERLAY over the live session —
     * not a navigation destination — dismissing it performs no navigation and no
     * session rebind, so the session underneath is the same one, unchanged. This
     * asserts exactly that: the chrome for `pocketshell` is still composed and
     * still says `pocketshell` after the panel closes.
     */
    @Test
    fun leavingThePanelReturnsToTheSameLiveSession() {
        showChrome(busyForwarding(), SessionPortsPanelState(rows = listOf(row(5173))))
        compose.onNodeWithText("pocketshell").assertIsDisplayed()

        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_PORTS_OVERLAY_TAG).assertIsDisplayed()

        // The device Back gesture, which the panel's BackHandler owns while it is
        // up — the same key the user presses.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag(SESSION_PORTS_OVERLAY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assertIsDisplayed()
        compose.onNodeWithText("pocketshell").assertIsDisplayed()
    }

    /** The pill stays absent — and cannot be tapped — when nothing is forwarding. */
    @Test
    fun theForwardingPillIsAbsentWhenNothingIsForwarding() {
        showChrome(SessionForwardingIndicatorState(), SessionPortsPanelState())

        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assertDoesNotExist()
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Put the test window in the same edge-to-edge mode `MainActivity` uses
     * (`enableEdgeToEdge()` ⇒ `setDecorFitsSystemWindows(false)`), so the content
     * genuinely extends behind the system bars and the synthetic insets are
     * honoured the way a real device honours them. Without this the decor
     * consumes the insets and the defect cannot occur at all — the check would
     * pass for the wrong reason.
     */
    private fun enableEdgeToEdgeWindow() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
    }

    /**
     * Dispatch a known system-bar inset set with NO dependence on the device's
     * own navigation mode. Deliberately re-dispatched immediately before the
     * geometry is read: a real window can re-supply its own insets on a layout
     * pass, and a device in gesture mode reports a nav inset near zero, which
     * would quietly turn every containment check below into a no-op.
     */
    private fun applySyntheticSystemBars() {
        compose.activityRule.scenario.onActivity { activity ->
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, expectedNavBarPx()),
                )
                .setInsets(
                    WindowInsetsCompat.Type.statusBars(),
                    Insets.of(0, expectedStatusBarPx(), 0, 0),
                )
                .build()
            dispatchSyntheticWindowInsets(activity.window.decorView, insets)
        }
    }

    /** A 3-button navigation bar, the state in the maintainer's screenshot. */
    private fun expectedNavBarPx(): Int = (NAV_BAR_DP * compose.density.density).toInt()

    private fun expectedStatusBarPx(): Int = (STATUS_BAR_DP * compose.density.density).toInt()

    private companion object {
        const val NAV_BAR_DP = 48f
        const val STATUS_BAR_DP = 28f

        /**
         * How far the painted chip may exceed the segmented toggle beside it.
         * They are different components with different glyph/label metrics, so a
         * couple of dp of difference is the design, not a defect — the
         * maintainer's screenshot shows exactly that. 4dp keeps the separation
         * from the 48dp block (≈16dp taller than the toggle) decisive.
         */
        const val NEIGHBOUR_HEIGHT_SLOP_DP = 4f
    }
}
