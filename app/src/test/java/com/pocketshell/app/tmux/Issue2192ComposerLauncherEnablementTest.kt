package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.connection.SessionId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2192 — the composer launcher must stay a local open even when the
 * post-reconnect surface is wedged. These proofs drive the SAME production
 * helpers [TmuxSessionScreen] now uses, so a future fold of launcher-open back
 * into `sessionLive && panePresent` is RED here, not only on the device.
 */
class Issue2192ComposerLauncherEnablementTest {

    @Test
    fun pre2192BaselineDisablesLauncherOnBothWedges() {
        // RED baseline: the pre-#2192 call-site formula
        // `controlsInputEnabled = sessionLive && pane != null` disabled the
        // launcher itself. Both candidate reconnect wedges collapse to that.
        assertFalse(
            "Wedge A: raw not-Connected, pane present → launcher was dead",
            pre2192LauncherEnabled(sessionLive = false, panePresent = true),
        )
        assertFalse(
            "Wedge B: Connected, surfacePane null → launcher was dead",
            pre2192LauncherEnabled(sessionLive = true, panePresent = false),
        )
        assertTrue(
            "happy path stayed enabled (the formula was not always-false)",
            pre2192LauncherEnabled(sessionLive = true, panePresent = true),
        )
    }

    @Test
    fun wedgeA_rawNotConnectedKeepsLauncherOpenAndGatesPaneBound() {
        val enablement = tmuxSessionBottomControlEnablement(
            sessionLive = false,
            panePresent = true,
        )
        assertTrue(
            "Wedge A: launcher open is local and must not wait for Connected",
            enablement.launcherOpenEnabled,
        )
        assertFalse(
            "Wedge A: Enter / keyboard / control-bytes stay gated (#249)",
            enablement.paneBoundEnabled,
        )
    }

    @Test
    fun wedgeB_missingSurfacePaneKeepsLauncherOpenAndGatesPaneBound() {
        val enablement = tmuxSessionBottomControlEnablement(
            sessionLive = true,
            panePresent = false,
        )
        assertTrue(
            "Wedge B: launcher open must survive a null surface pane",
            enablement.launcherOpenEnabled,
        )
        assertFalse(
            "Wedge B: no pane means no pane-bound write",
            enablement.paneBoundEnabled,
        )
    }

    @Test
    fun genuinelyDisconnectedKeepsLauncherOpen() {
        val enablement = tmuxSessionBottomControlEnablement(
            sessionLive = false,
            panePresent = false,
        )
        assertTrue(
            "offline / reconnecting still opens the sheet (#1613 queue)",
            enablement.launcherOpenEnabled,
        )
        assertFalse(enablement.paneBoundEnabled)
    }

    @Test
    fun livePaneKeepsBothEnabled() {
        val enablement = tmuxSessionBottomControlEnablement(
            sessionLive = true,
            panePresent = true,
        )
        assertTrue(enablement.launcherOpenEnabled)
        assertTrue(enablement.paneBoundEnabled)
    }

    @Test
    fun visibleUnifiedPaneFallbackStaysOnTargetAfterReseed() {
        val targetSessionId = SessionId("tmux:7:\$0:100")
        val targetEpoch = UnifiedPagerTargetEpoch(
            targetSessionId = targetSessionId,
            instance = 1L,
        )
        val foreign = fakePane("%foreign", sessionId = "\$1", sessionCreated = 200L)
        val target = fakePane("%target", sessionId = "\$0", sessionCreated = 100L)
        val pages = unifiedPagerPages(
            panes = listOf(foreign, target),
            targetEpoch = targetEpoch,
            targetSessionName = "target",
            sessionNameForPane = { pane ->
                if (pane === target) "target" else "foreign"
            },
        )
        assertSame(
            "a valid page keeps following the pane the pager is showing",
            foreign,
            tmuxSessionVisibleUnifiedPane(
                pages = pages,
                currentPage = 0,
                targetSessionId = targetSessionId,
            ),
        )
        assertSame(
            "Wedge B rebind: an out-of-bounds page must land on the current target, " +
                "never the first foreign cached session",
            target,
            tmuxSessionVisibleUnifiedPane(
                pages = pages,
                currentPage = 4,
                targetSessionId = targetSessionId,
            ),
        )
        assertSame(
            target,
            tmuxSessionVisibleUnifiedPane(
                pages = pages,
                currentPage = -1,
                targetSessionId = targetSessionId,
            ),
        )
        assertNull(
            "no target-owned page is safer than selecting a foreign session",
            tmuxSessionVisibleUnifiedPane(
                pages = unifiedPagerPages(
                    panes = listOf(foreign),
                    targetEpoch = targetEpoch,
                    targetSessionName = "target",
                    sessionNameForPane = { "foreign" },
                ),
                currentPage = 4,
                targetSessionId = targetSessionId,
            ),
        )
    }

    @Test
    fun visibleUnifiedPaneStaysNullWhenTheListIsEmpty() {
        val targetSessionId = SessionId("tmux:7:\$0:100")
        assertNull(
            tmuxSessionVisibleUnifiedPane(
                pages = emptyList(),
                currentPage = 0,
                targetSessionId = targetSessionId,
            ),
        )
        assertNull(
            tmuxSessionVisibleUnifiedPane(
                pages = emptyList(),
                currentPage = 3,
                targetSessionId = targetSessionId,
            ),
        )
    }

    private fun pre2192LauncherEnabled(sessionLive: Boolean, panePresent: Boolean): Boolean =
        sessionLive && panePresent

    private fun fakePane(
        paneId: String,
        sessionId: String,
        sessionCreated: Long,
    ): TmuxPaneState = TmuxPaneState(
        paneId = paneId,
        windowId = "@0",
        sessionId = sessionId,
        sessionCreated = sessionCreated,
        title = "work",
        cwd = "/repo",
        currentCommand = "bash",
        paneTty = "/dev/pts/1",
        terminalState = TerminalSurfaceState(),
    )
}
