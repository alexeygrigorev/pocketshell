package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxOutboundQueueBindingTest {
    @Test
    fun nameOnlyTargetPromotesBadgeComposerAndDrainToOneDurableKey() {
        val fallback = tmuxOutboundQueueBinding(7L, "work", emptyList(), null, null, false)
        assertEquals("7/work", fallback.targetKey)
        assertNull(fallback.durableKey)

        val correlatedNavigation = tmuxOutboundQueueBinding(7L, "work", emptyList(), "\$9", 123L, false)
        assertEquals("7/work", correlatedNavigation.targetKey)
        assertNull(correlatedNavigation.durableKey)
        assertEquals("\$9", correlatedNavigation.tmuxSessionId)
        assertEquals(123L, correlatedNavigation.sessionCreated)

        val pane = TmuxPaneState(
            paneId = "%3",
            windowId = "@1",
            sessionId = "\$9",
            sessionCreated = 123L,
            title = "work",
            terminalState = TerminalSurfaceState(),
        )
        val cachedWhileDown = tmuxOutboundQueueBinding(7L, "work", listOf(pane), null, null, false)
        assertEquals("7/work", cachedWhileDown.targetKey)
        assertEquals("\$9", cachedWhileDown.tmuxSessionId)
        assertEquals(123L, cachedWhileDown.sessionCreated)

        val settled = tmuxOutboundQueueBinding(7L, "work", listOf(pane), null, null, true)

        assertEquals("tmux:7:\$9:123", settled.targetKey)
        assertEquals(settled.targetKey, settled.durableKey)
        assertEquals(setOf("%3"), settled.generationPaneIds)
        assertEquals("7/work", settled.fallbackKey)
    }

    @Test
    fun staleLiveStatusCannotPromoteUntilWireTruthSettlesThenPromotionIsExactAndIdempotent() {
        val pane = TmuxPaneState(
            paneId = "%3",
            windowId = "@1",
            sessionId = "\$9",
            sessionCreated = 123L,
            title = "work",
            terminalState = TerminalSurfaceState(),
        )
        assertFalse(outboundGenerationSettled(sessionLive = true, wireWritable = false))
        val held = tmuxOutboundQueueBinding(
            7L, "work", listOf(pane), "\$9", 123L,
            outboundGenerationSettled(sessionLive = true, wireWritable = false),
        )
        assertEquals("7/work", held.targetKey)
        assertNull(held.durableKey)

        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue(
            sessionKey = held.targetKey,
            cleanText = "dictated while wire down",
            paneId = "%3",
            tmuxSessionId = "\$9",
            tmuxSessionCreated = 123L,
        )
        assertEquals(listOf(row), store.itemsFor("7/work"))
        assertTrue(store.itemsFor("tmux:7:\$9:123").isEmpty())

        assertTrue(outboundGenerationSettled(sessionLive = true, wireWritable = true))
        val live = tmuxOutboundQueueBinding(
            7L, "work", listOf(pane), "\$9", 123L,
            outboundGenerationSettled(sessionLive = true, wireWritable = true),
        )
        val first = store.promoteSessionIdentity(
            held.targetKey, requireNotNull(live.durableKey), live.generationPaneIds, "\$9", 123L,
        )
        val second = store.promoteSessionIdentity(
            held.targetKey, requireNotNull(live.durableKey), live.generationPaneIds, "\$9", 123L,
        )
        assertEquals(listOf(row.id), first.map { it.id })
        assertTrue(second.isEmpty())
        assertEquals(row.copy(sessionKey = live.targetKey), store.itemsFor(live.targetKey).single())
        assertTrue(store.itemsFor(held.targetKey).isEmpty())
    }

    @Test
    fun disconnectedRecordedRouteIsExactGenerationAndPaneBound() {
        val evidence = RecordedAgentRouteEvidence("tmux:1:\$1:100", "%3", AgentKind.ClaudeCode)
        assertEquals(
            AgentKind.ClaudeCode,
            tmuxPresumedAgentKind(null, null, evidence, "tmux:1:\$1:100", "%3"),
        )
        assertEquals(evidence, retainRecordedAgentRouteEvidence(evidence, "tmux:1:\$1:100", "%3"))
        assertNull(retainRecordedAgentRouteEvidence(evidence, "tmux:1:\$1:200", "%3"))
        assertNull(retainRecordedAgentRouteEvidence(evidence, "tmux:1:\$1:100", "%9"))
        assertNull(tmuxPresumedAgentKind(null, null, evidence, "tmux:1:\$1:200", "%3"))
        assertNull(tmuxPresumedAgentKind(null, null, evidence, "tmux:1:\$1:100", "%9"))
        assertNull(SessionAgentKind.Unknown.toRecordedAgentKindOrNull())
        assertNull(SessionAgentKind.Shell.toRecordedAgentKindOrNull())

        val detection = AgentDetection(
            AgentKind.Codex,
            "/tmp/codex.jsonl",
            "codex",
            AgentDetection.Confidence.ProcessConfirmed,
        )
        assertEquals(
            AgentKind.Codex,
            tmuxDisconnectedAgentKind(detection, evidence, "tmux:1:\$1:100", "%3"),
        )
        assertEquals(
            AgentKind.ClaudeCode,
            tmuxDisconnectedAgentKind(null, evidence, "tmux:1:\$1:100", "%3"),
        )
        assertNull(tmuxDisconnectedAgentKind(detection, evidence, "tmux:1:\$1:200", "%3"))
        assertNull(tmuxDisconnectedAgentKind(detection, evidence, null, "%3"))
        assertEquals("%3", tmuxComposerPaneIdForSnapshot(null, evidence, "tmux:1:\$1:100"))
        assertNull(tmuxComposerPaneIdForSnapshot(null, evidence, "tmux:1:\$1:200"))
    }

    @Test
    fun disconnectedKnownAgentSnapshotsAgentRouteButLiveTerminalKeepsRawBytes() {
        assertEquals(
            TmuxComposerSendRoute.AgentPayload,
            tmuxComposerSendRouteForConnection(
                false, AgentKind.ClaudeCode, false, AgentKind.ClaudeCode, null, true,
            ),
        )
        assertEquals(
            TmuxComposerSendRoute.RawBytes,
            tmuxComposerSendRouteForConnection(
                true, AgentKind.ClaudeCode, false, AgentKind.ClaudeCode, null, true,
            ),
        )
    }

    @Test
    fun delayedRecordedKindReadsCannotOverwriteNewerAAfterABA() {
        assertFalse(recordedAgentRefreshStillCurrent(1, 3, "tmux:1:\$1:100", "%3", "tmux:1:\$1:100", "%3"))
        assertFalse(recordedAgentRefreshStillCurrent(2, 3, "tmux:1:\$2:200", "%9", "tmux:1:\$1:100", "%3"))
        assertTrue(recordedAgentRefreshStillCurrent(3, 3, "tmux:1:\$1:100", "%3", "tmux:1:\$1:100", "%3"))
    }

    @Test
    fun latestRecordedKindTokenDoesNotRequireDurableRouteEvidence() {
        val tracker = RecordedAgentRouteTracker()
        val nameOnly = tracker.begin(durableSessionKey = null, paneId = "%3")

        assertTrue(tracker.isLatest(nameOnly))
        assertFalse(tracker.isCurrent(nameOnly, durableSessionKey = null, paneId = "%3"))

        val newer = tracker.begin(durableSessionKey = null, paneId = "%3")
        assertFalse(tracker.isLatest(nameOnly))
        assertTrue(tracker.isLatest(newer))
    }
}
