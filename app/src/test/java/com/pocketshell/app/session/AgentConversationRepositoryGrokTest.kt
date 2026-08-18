package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.GrokBuildParser
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #2155: the tmux `-t` target every recorded-source-resolving exec reads
 * `@ps_agent_source_generation` / `@ps_agent_source` against. It is now a
 * required parameter of `detectRecordedSessionForPane` — the source is resolved
 * LIVE inside that exec instead of being handed in by a caller that may be
 * holding a path from a PREVIOUS agent launch in the same session.
 */
private const val RECORDED_SESSION_TARGET: String = "$3"

/**
 * Issue #2193: Grok Build conversation-source + tail-coalesce cases extracted
 * from [AgentConversationRepositoryTest] so that file stays under the 128 KiB
 * hygiene threshold. Same package so they share the internal
 * [parseTranscriptTailLines] helper.
 */
class AgentConversationRepositoryGrokTest {
    @Test
    fun recordedGrokSessionBindsToGrokOverANewerClaudeSibling() = runTest {
        val now = System.currentTimeMillis() / 1000
        val grokPath =
            "/home/testuser/.grok/sessions/%2Fworkspace%2Fproj/grok-sess/updates.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/newer.jsonl
                grok|${now - 300}|/workspace/proj|$grokPath
            """.trimIndent(),
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            sessionTarget = RECORDED_SESSION_TARGET,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/3",
            paneCommand = "grok",
            recordedKind = AgentKind.GrokBuild,
        )

        assertEquals(AgentKind.GrokBuild, detection?.agent)
        assertEquals("grok-sess", detection?.sessionId)
        assertEquals(grokPath, detection?.sourcePath)
        assertFalse(
            "recorded Grok (session id in path) must not require a process scan; " +
                "got ${session.execCommands}",
            session.execCommands.any { it.contains("ps -eo pid,ppid,tty,comm,args") },
        )
    }

    @Test
    fun recordedAgentKindFromOptionMapsGrok() {
        val repo = AgentConversationRepository()
        assertEquals(AgentKind.GrokBuild, repo.recordedAgentKindFromOption("grok"))
        assertEquals(AgentKind.GrokBuild, repo.recordedAgentKindFromOption("GROK"))
    }

    @Test
    fun kindOfOwnedTranscriptPathRecognisesGrokUpdatesJsonl() {
        val repo = AgentConversationRepository()
        assertEquals(
            AgentKind.GrokBuild,
            repo.kindOfOwnedTranscriptPath(
                "/home/me/.grok/sessions/%2Fhome%2Fme%2Fproj/sess/updates.jsonl",
            ),
        )
        assertEquals(
            null,
            repo.kindOfOwnedTranscriptPath("/tmp/backup/updates.jsonl"),
        )
    }

    @Test
    fun parseTranscriptTailLinesCoalescesGrokChunks() {
        // Multi-turn, real user-chunk shape: eventId only, no promptId /
        // promptIndex. G6 mutation: User messageId `?: "0"` collapses both
        // user turns onto grok:user:s:0 and the 2-User count fails.
        val user1 = """{"timestamp":1787072184,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"Hi"}},"_meta":{"eventId":"u1"}}}"""
        val asst1a = """{"timestamp":1787072185,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hello "}},"_meta":{"eventId":"a1","promptId":"p1"}}}"""
        val asst1b = """{"timestamp":1787072186,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"there"}},"_meta":{"eventId":"a2","promptId":"p1"}}}"""
        val thought = """{"timestamp":1787072186,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"thinking"}}}}"""
        val tool = """{"timestamp":1787072187,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"tool_call","toolCallId":"c1","title":"bash","rawInput":{"command":"pwd"}}}}"""
        val result = """{"timestamp":1787072188,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"/tmp"}}]}}}"""
        val user2 = """{"timestamp":1787072189,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"Again"}},"_meta":{"eventId":"u2"}}}"""
        val asst2a = """{"timestamp":1787072190,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Still "}},"_meta":{"eventId":"a3","promptId":"p2"}}}"""
        val asst2b = """{"timestamp":1787072191,"method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"here"}},"_meta":{"eventId":"a4","promptId":"p2"}}}"""

        val events = parseTranscriptTailLines(
            GrokBuildParser(),
            AgentKind.GrokBuild,
            sequenceOf(user1, asst1a, asst1b, thought, tool, result, user2, asst2a, asst2b),
        )

        val users = events.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User }
        val assistants = events.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.Assistant }
        assertEquals("two user turns, not one collapsed Message", 2, users.size)
        assertEquals(2, assistants.size)
        assertEquals(1, events.filterIsInstance<ConversationEvent.ToolCall>().size)
        assertEquals(1, events.filterIsInstance<ConversationEvent.ToolResult>().size)
        assertEquals("Hi", users[0].text)
        assertEquals("Again", users[1].text)
        assertEquals("Hello there", assistants[0].text)
        assertEquals("Still here", assistants[1].text)
        assertTrue(users[0].id != users[1].id)
        assertEquals("bash", (events.filterIsInstance<ConversationEvent.ToolCall>().single()).name)
        assertEquals("/tmp", (events.filterIsInstance<ConversationEvent.ToolResult>().single()).output)
    }

    private class FakeSshSession(
        private val detectionOutput: String = "",
    ) : SshSession {
        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val stdout = when {
                command.contains("claude_dir=") -> detectionOutput
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            throw NotImplementedError()
        }

        override fun startShell(): SshShell {
            throw NotImplementedError()
        }

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }
}
