package com.pocketshell.app.portfwd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2176: the per-session Ports panel on device.
 *
 * Covers the acceptance criteria that are about what the user SEES and what a
 * tap DOES — the two row actions, the honest empty state, and a long list — over
 * the production [SessionPortsOverlay] composable, not a stand-in.
 *
 * Containment (`assertNodeFullyWithinRoot`) rather than a bare
 * `assertIsDisplayed()` for the row's action button: "the node is in the
 * hierarchy" is not "the user can see and tap it", and a control pushed off the
 * right edge still reports displayed (the #641 class). The row carries four
 * columns plus a button, so horizontal crowding is a real risk here.
 */
@RunWith(AndroidJUnit4::class)
class SessionPortsOverlayUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun row(
        port: Int,
        localPort: Int? = null,
        process: String = "node",
        matchedText: String = "Local: http://localhost:$port/",
    ) = SessionPortRow(
        port = port,
        localPort = localPort,
        process = process,
        matchedText = matchedText,
        firstSeenAtEpochMs = port.toLong(),
    )

    private class Taps {
        var opened: SessionPortRow? = null
        var forwardRequested: SessionPortRow? = null
        var dismissed: Boolean = false
        var hostPanelOpened: Int = 0
    }

    private fun show(state: SessionPortsPanelState): Taps {
        val taps = Taps()
        composeRule.setContent {
            PocketShellTheme {
                SessionPortsOverlay(
                    visible = true,
                    hostName = "hetzner",
                    sessionName = "pocketshell",
                    state = state,
                    onDismiss = { taps.dismissed = true },
                    onOpenForwarded = { taps.opened = it },
                    onRequestForward = { taps.forwardRequested = it },
                    onOpenHostPorts = { taps.hostPanelOpened += 1 },
                )
            }
        }
        return taps
    }

    /**
     * Acceptance criterion 5: an already-forwarded row opens straight away. The
     * label says "Open" so the user knows it will, and the tap resolves to the
     * open callback with the loopback URL — never to the confirm.
     */
    @Test
    fun tappingAForwardedRowOpensItDirectly() {
        val taps = show(
            SessionPortsPanelState(rows = listOf(row(5173, localPort = 18173))),
        )

        composeRule.onNodeWithTag(sessionPortRowTag(5173)).assertIsDisplayed()
        composeRule.onNodeWithText("Open").assertIsDisplayed()
        composeRule.onNodeWithTag(sessionPortActionTag(5173)).performClick()
        composeRule.waitForIdle()

        assertEquals(5173, taps.opened?.port)
        assertEquals("http://127.0.0.1:18173", taps.opened?.localUrl)
        assertNull("a forwarded row must not ask to forward", taps.forwardRequested)
    }

    /**
     * Acceptance criterion 6: a not-forwarded row asks first. Nothing is opened
     * by the tap itself — the confirm owns that — so a decline leaves the user
     * exactly where they were, with no tunnel and no dead browser tab.
     */
    @Test
    fun tappingANotForwardedRowAsksBeforeForwarding() {
        val taps = show(SessionPortsPanelState(rows = listOf(row(8000))))

        composeRule.onNodeWithText("Forward").assertIsDisplayed()
        composeRule.onNodeWithTag(sessionPortActionTag(8000)).performClick()
        composeRule.waitForIdle()

        assertEquals(8000, taps.forwardRequested?.port)
        assertNull("nothing may be opened before the user confirms", taps.opened)
    }

    /** Tapping the row body does the same thing as its button. */
    @Test
    fun tappingTheRowBodyDoesTheSameAsItsButton() {
        val taps = show(SessionPortsPanelState(rows = listOf(row(8000))))

        composeRule.onNodeWithTag(sessionPortRowTag(8000)).performClick()
        composeRule.waitForIdle()

        assertEquals(8000, taps.forwardRequested?.port)
        assertNull(taps.opened)
    }

    /**
     * Acceptance criterion 8 (empty): zero ports renders an explanation, not a
     * blank surface. An empty screen reads as "broken"; "no ports yet" reads as
     * "nothing has started".
     */
    @Test
    fun zeroPortsRendersAnHonestEmptyState() {
        show(SessionPortsPanelState(rows = emptyList()))

        composeRule.onNodeWithTag(SESSION_PORTS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("No ports from this session yet.").assertIsDisplayed()
        composeRule.assertNodeFullyWithinRoot(SESSION_PORTS_EMPTY_TAG)
    }

    /**
     * Acceptance criterion 8 (many): a long list renders and stays usable — the
     * first rows' action buttons are fully within the viewport, not clipped off
     * the right edge by the four data columns.
     *
     * The list deliberately spans ports far outside the host-wide
     * `3000..10000` denoise range, because a session's own ports are never
     * hidden by that filter.
     */
    @Test
    fun manyPortsRenderWithReachableActions() {
        val ports = listOf(22, 80, 443) + (3000..3020).toList() + listOf(41337, 54321)
        show(SessionPortsPanelState(rows = ports.map { row(it) }))

        composeRule.onNodeWithTag(SESSION_PORTS_OVERLAY_TAG).assertIsDisplayed()
        for (port in listOf(22, 80, 443)) {
            composeRule.onNodeWithTag(sessionPortRowTag(port)).assertIsDisplayed()
            composeRule.assertNodeFullyWithinRoot(sessionPortActionTag(port))
        }
    }

    /** A row with no captured line still renders; the caption is simply absent. */
    @Test
    fun aRowWithoutMatchedTextStillRenders() {
        show(SessionPortsPanelState(rows = listOf(row(9000, matchedText = ""))))

        composeRule.onNodeWithTag(sessionPortRowTag(9000)).assertIsDisplayed()
        composeRule.assertNodeFullyWithinRoot(sessionPortActionTag(9000))
    }

    /** The close affordance dismisses the panel. */
    @Test
    fun theCloseAffordanceDismissesThePanel() {
        val taps = show(SessionPortsPanelState(rows = listOf(row(5173))))

        composeRule.onNodeWithTag(SESSION_PORTS_CLOSE_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue(taps.dismissed)
    }
}
