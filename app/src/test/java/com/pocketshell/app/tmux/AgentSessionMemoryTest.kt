package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #495: unit tests for [AgentSessionMemory] — the stable-identity
 * memory that lets a reconnect restore the Conversation tab immediately.
 */
class AgentSessionMemoryTest {

    private fun claude(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/projects/p/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    @Test
    fun remembersAndRecallsByStableHostSessionWindowIdentity() {
        val memory = AgentSessionMemory()
        val detection = claude()

        memory.remember(
            hostId = 7L,
            sessionName = "work",
            windowId = "@2",
            detection = detection,
            wasOnConversation = true,
        )

        val recalled = memory.recall(hostId = 7L, sessionName = "work", windowId = "@2")
        assertEquals(detection, recalled?.detection)
        assertTrue("user was on Conversation", recalled!!.wasOnConversation)
    }

    @Test
    fun recallIsScopedToHostSessionAndWindow() {
        val memory = AgentSessionMemory()
        memory.remember(7L, "work", "@2", claude(), wasOnConversation = false)

        assertNull("different window", memory.recall(7L, "work", "@9"))
        assertNull("different session", memory.recall(7L, "other", "@2"))
        assertNull("different host", memory.recall(8L, "work", "@2"))
    }

    @Test
    fun durableIdentityPreventsSameNameWindowRecallAcrossRecreatedSessions() {
        val memory = AgentSessionMemory()
        val detection = claude()

        memory.remember(
            hostId = 7L,
            sessionName = "work",
            windowId = "@0",
            detection = detection,
            wasOnConversation = true,
            durableSessionKey = "tmux:7:\$0:100",
        )

        assertNull(
            "same-name successor with a new tmux identity must not inherit the killed session's agent row",
            memory.recall(
                hostId = 7L,
                sessionName = "work",
                windowId = "@0",
                durableSessionKey = "tmux:7:\$1:200",
            ),
        )
        assertEquals(
            detection,
            memory.recall(
                hostId = 7L,
                sessionName = "work",
                windowId = "@0",
                durableSessionKey = "tmux:7:\$0:100",
            )?.detection,
        )
    }

    @Test
    fun forgetReconcilesAnExitedAgentSoItIsNotRestored() {
        val memory = AgentSessionMemory()
        memory.remember(7L, "work", "@2", claude(), wasOnConversation = true)

        memory.forget(hostId = 7L, sessionName = "work", windowId = "@2")

        assertNull(
            "a forgotten (exited) agent window must not be recalled",
            memory.recall(7L, "work", "@2"),
        )
    }

    @Test
    fun forgetSessionClearsEveryRememberedWindowForKilledSessionName() {
        val memory = AgentSessionMemory()
        memory.remember(7L, "work", "@2", claude(), wasOnConversation = true)
        memory.remember(7L, "work", "@3", claude(), wasOnConversation = false)
        memory.remember(7L, "deploy", "@2", claude(), wasOnConversation = true)
        memory.remember(8L, "work", "@2", claude(), wasOnConversation = true)

        memory.forgetSession(hostId = 7L, sessionName = "work")

        assertNull(
            "a same-name successor must not recall window memory from the killed session",
            memory.recall(7L, "work", "@2"),
        )
        assertNull(memory.recall(7L, "work", "@3"))
        assertEquals(claude(), memory.recall(7L, "deploy", "@2")?.detection)
        assertEquals(claude(), memory.recall(8L, "work", "@2")?.detection)
    }

    @Test
    fun rememberOverwritesPriorVerdictForTheSameWindow() {
        val memory = AgentSessionMemory()
        memory.remember(7L, "work", "@2", claude(), wasOnConversation = true)

        val refined = claude().copy(confidence = AgentDetection.Confidence.RecentFile)
        memory.remember(7L, "work", "@2", refined, wasOnConversation = false)

        val recalled = memory.recall(7L, "work", "@2")!!
        assertEquals(AgentDetection.Confidence.RecentFile, recalled.detection.confidence)
        assertFalse("tab choice updated to Terminal", recalled.wasOnConversation)
    }

    @Test
    fun blankSessionOrWindowIsNotRememberedOrRecalled() {
        val memory = AgentSessionMemory()
        memory.remember(7L, "", "@2", claude(), wasOnConversation = true)
        memory.remember(7L, "work", "", claude(), wasOnConversation = true)

        assertNull(memory.recall(7L, "", "@2"))
        assertNull(memory.recall(7L, "work", ""))
    }
}
