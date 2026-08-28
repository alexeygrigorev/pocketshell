package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2173 — composer stays on the host/name fallback after tmux is
 * already Live with a durable `tmux:` identity.
 *
 * CI runs 33118097377 / 33127956735 failed every method of
 * `OutboundExactlyOnceAcrossFlapE2eTest` with
 * `composer=2/issue1526-exactly-once` while
 * `tmux=tmux:2:$0:…` and `status=Connected`. The screen's fused
 * [TmuxSessionConnectionRuntime.targetSessionId] had adopted the exact
 * generation, but [tmuxOutboundQueueBinding] only stamped
 * [TmuxOutboundQueueBinding.durableKey] from a pane row that already
 * carried `sessionCreated`. Inline-reveal / name-only panes leave that
 * field null, so the composer LaunchedEffect never left the fallback.
 *
 * The mutation that must redden [adoptedIdentityOwnsTheQueueWhenLivePanesLackSessionCreated]
 * is restoring `durable = paneGeneration?.takeIf { generationSettled }?.let { … }`
 * as the only durable source.
 */
class Issue2173ComposerDurableTargetTest {

    @Test
    fun adoptedIdentityOwnsTheQueueWhenLivePanesLackSessionCreated() {
        val pane = TmuxPaneState(
            paneId = "%0",
            windowId = "@1",
            sessionId = "\$0",
            sessionCreated = null,
            title = "issue1526-exactly-once",
            terminalState = TerminalSurfaceState(),
        )
        val paneOnlyDurable = pane.takeIf {
            it.sessionId.isNotBlank() && it.sessionCreated != null
        }?.let { durableTmuxSessionKey(2L, it.sessionId, it.sessionCreated) }
        assertNull(
            "vacuity: the pre-#2173 pane-only formula yields no durable key " +
                "for the CI tuple (sessionCreated=null), so a green targetKey " +
                "assert below is not a no-op rename",
            paneOnlyDurable,
        )
        val shippedBefore = tmuxOutboundQueueBinding(
            hostId = 2L,
            sessionName = "issue1526-exactly-once",
            panes = listOf(pane),
            navigationTmuxSessionId = "\$0",
            navigationSessionCreated = 1_787_876_465L,
            generationSettled = true,
        )
        assertEquals(
            "REGRESSION (#2173): Connected + live pane + adopted \$0 generation " +
                "must own the durable composer/queue key, not host/name fallback",
            "tmux:2:\$0:1787876465",
            shippedBefore.targetKey,
        )
        assertEquals(shippedBefore.targetKey, shippedBefore.durableKey)
        assertEquals(setOf("%0"), shippedBefore.generationPaneIds)
        assertEquals("2/issue1526-exactly-once", shippedBefore.fallbackKey)
    }

    @Test
    fun emptyPanesStillDoNotOwnTheQueueFromNavigationAlone() {
        val held = tmuxOutboundQueueBinding(
            7L, "work", emptyList(), "\$9", 123L, true,
        )
        assertEquals("7/work", held.targetKey)
        assertNull(held.durableKey)
    }

    @Test
    fun livePaneOfADifferentGenerationDoesNotStealTheAdoptedKey() {
        val other = TmuxPaneState(
            paneId = "%3",
            windowId = "@1",
            sessionId = "\$8",
            sessionCreated = 99L,
            title = "other",
            terminalState = TerminalSurfaceState(),
        )
        val held = tmuxOutboundQueueBinding(
            7L, "work", listOf(other), "\$9", 123L, true,
        )
        assertEquals("7/work", held.targetKey)
        assertNull(held.durableKey)
    }

    @Test
    fun sessionScreenWiresAdoptedRevealIdentityIntoTheBinding() {
        val screen = source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionScreen.kt")
        assertTrue(
            "TmuxSessionScreen must pass the fused conn.targetSessionId " +
                "into tmuxOutboundQueueBinding so a name-only route that " +
                "later adopts tmux:host:\$N:created actually promotes " +
                "(#2173 CI: composer stuck on host/name while tmux=tmux:…)",
            screen.contains("conn.targetSessionId"),
        )
        assertTrue(
            "the outbound binding remember keys must include the adopted " +
                "targetSessionId; otherwise identity adoption never " +
                "recomputes targetKey",
            Regex(
                """remember\(\s*hostId,\s*sessionName,\s*conn\.panes,.*conn\.targetSessionId""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(screen),
        )
        assertFalse(
            "do not keep the name-only navigation pair as the sole " +
                "identity args (that is the #2173 stuck fallback)",
            Regex(
                """tmuxOutboundQueueBinding\(\s*hostId,\s*sessionName,\s*conn\.panes,\s*tmuxSessionId,\s*sessionCreated,""",
            ).containsMatchIn(screen),
        )
        assertFalse(
            "do not bypass outboundGenerationSettled with adopted+panes+sessionLive " +
                "(that OR promoted on stale Connected and was the #2173r " +
                "pager-recomposition suspicion)",
            screen.contains("adopted != null && conn.panes.isNotEmpty()"),
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
