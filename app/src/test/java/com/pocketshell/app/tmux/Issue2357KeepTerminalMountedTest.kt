package com.pocketshell.app.tmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2357 — a Live pane must produce a placed Termux `TerminalView`
 * without a user gesture.
 *
 * CI run 33100430182 (shard 0, `BackgroundGraceReconnectE2eTest`, both
 * class attempts) timed out after 150s at `waitForTerminalViewAttached`
 * while logcat already showed `Attaching -> Live` with `paneCount=1` at
 * 753ms. The wait is `decorView.findTerminalView()?.currentSession != null
 * && mEmulator != null`. The pager that owns that `AndroidView` was gated
 * on `!terminalHeld`, so a reveal-identity mismatch that keeps the fusion
 * held after the connection is Live never composed the view.
 *
 * These assertions pin the production helper the screen now calls. The
 * mutation that must redden [liveHeldPaneStillMountsTheTerminalPager] is
 * reverting to `!terminalHeld && !defer && hasPanes` — that formula is
 * false for the CI tuple and is asserted as vacuity below.
 */
class Issue2357KeepTerminalMountedTest {

    @Test
    fun liveHeldPaneStillMountsTheTerminalPager() {
        // The CI tuple: tmux Live, pane listing non-empty, reveal fusion
        // still held (stale route generation vs live `$N`).
        val terminalHeld = true
        val deferTerminalAttachForSwap = false
        val hasPanes = true
        val sessionLive = true

        val shippedBefore =
            !terminalHeld && !deferTerminalAttachForSwap && hasPanes
        assertFalse(
            "vacuity: the pre-#2357 !terminalHeld formula unmounts the " +
                "Live+held CI state, so a green here is not a no-op rename",
            shippedBefore,
        )
        assertTrue(
            "REGRESSION (#2357): a Live pane must keep TmuxTerminalPager " +
                "composed so the Termux AndroidView can be placed without a " +
                "gesture (CI swiftshader never places an uncomposed interop child)",
            shouldKeepTerminalMounted(
                terminalHeld = terminalHeld,
                deferTerminalAttachForSwap = deferTerminalAttachForSwap,
                hasPanes = hasPanes,
                sessionLive = sessionLive,
            ),
        )
    }

    @Test
    fun liveUnheldPaneStaysMounted() {
        assertTrue(
            shouldKeepTerminalMounted(
                terminalHeld = false,
                deferTerminalAttachForSwap = false,
                hasPanes = true,
                sessionLive = true,
            ),
        )
    }

    @Test
    fun connectingHoldWithoutLiveStillUnmounts() {
        // Not-Live + held is Connecting/Attaching/Switching. Mounting the
        // AndroidView under the owned placeholder + pull-to-reconnect box
        // collapses TerminalView to 0x0 (#822). Keep that unmounted.
        assertFalse(
            shouldKeepTerminalMounted(
                terminalHeld = true,
                deferTerminalAttachForSwap = false,
                hasPanes = true,
                sessionLive = false,
            ),
        )
    }

    @Test
    fun emptyPanesNeverMount() {
        assertFalse(
            shouldKeepTerminalMounted(
                terminalHeld = false,
                deferTerminalAttachForSwap = false,
                hasPanes = false,
                sessionLive = true,
            ),
        )
    }

    @Test
    fun conversationToTerminalSwapLatchStillDefersOneFrame() {
        // Issue #605: the Conversation → Terminal edge must not compose
        // the AndroidView on the same frame as IME/toolbar teardown.
        assertFalse(
            shouldKeepTerminalMounted(
                terminalHeld = false,
                deferTerminalAttachForSwap = true,
                hasPanes = true,
                sessionLive = true,
            ),
        )
    }

    @Test
    fun notLiveUnheldWithPanesStillMounts() {
        assertTrue(
            shouldKeepTerminalMounted(
                terminalHeld = false,
                deferTerminalAttachForSwap = false,
                hasPanes = true,
                sessionLive = false,
            ),
        )
    }

    @Test
    fun sessionScreenCallsTheKeepMountedHelper() {
        val screen = source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionScreen.kt")
        assertTrue(
            "TmuxSessionScreen must call shouldKeepTerminalMounted so a " +
                "helper-only fix cannot silently leave the screen on the " +
                "pre-#2357 inline formula",
            screen.contains("shouldKeepTerminalMounted("),
        )
        assertFalse(
            "do not re-inline !terminalHeld && !defer && unifiedPanes " +
                "as the mount gate (that is the #2357 hang)",
            Regex(
                """keepTerminalMounted\s*=\s*!terminalHeld\s*&&""",
            ).containsMatchIn(screen),
        )
    }

    private fun source(relativePath: String): String {
        val userDir = checkNotNull(System.getProperty("user.dir")) { "user.dir is unset" }
        var cursor = File(userDir).absoluteFile
        while (true) {
            val candidate = File(cursor, relativePath)
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: break
        }
        error("Cannot locate $relativePath from $userDir")
    }
}
