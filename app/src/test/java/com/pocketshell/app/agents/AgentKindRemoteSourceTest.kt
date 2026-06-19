package com.pocketshell.app.agents

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Epic #821 slice A2 (hard-cut, D22) — unit coverage for the foreign-session
 * ONE-SHOT kind guess client seam ([AgentKindRemoteSource]).
 *
 * This is part of the D31 class-covering regression suite for the
 * agent-detection rewrite: it pins the daemon-RPC envelope mapping across the
 * WHOLE class of agent kinds (claude / codex / opencode) AND the two non-agent
 * verdicts (`none` = a confirmed shell, `unknown` = pid/cgroup unreadable), so
 * a foreign session is classified by the host daemon rather than the deleted
 * output-parsing detector.
 */
class AgentKindRemoteSourceTest {

    private val source = AgentKindRemoteSource()

    @Test
    fun classifiesEveryAgentKindAndBothNonAgentVerdicts() = runTest {
        // One envelope row per class member: the three agent engines + the two
        // non-agent verdicts. This is the class-covering assertion.
        val session = FakeSshSession(
            stdout = """
                {"results":[
                  {"pane_id":"%1","agent_kind":"claude","scope":"a.scope","evidence_pid":11},
                  {"pane_id":"%2","agent_kind":"codex","scope":"b.scope","evidence_pid":22},
                  {"pane_id":"%3","agent_kind":"opencode","scope":"c.scope","evidence_pid":33},
                  {"pane_id":"%4","agent_kind":"none","scope":"d.scope"},
                  {"pane_id":"%5","agent_kind":"unknown","scope":""}
                ]}
            """.trimIndent(),
        )

        val result = source.classify(
            session = session,
            panes = listOf(
                AgentKindRemoteSource.PaneRef("%1", 11),
                AgentKindRemoteSource.PaneRef("%2", 22),
                AgentKindRemoteSource.PaneRef("%3", 33),
                AgentKindRemoteSource.PaneRef("%4", 44),
                AgentKindRemoteSource.PaneRef("%5", 55),
            ),
        )

        assertEquals(AgentKind.ClaudeCode, result["%1"]?.kind)
        assertEquals(AgentKind.Codex, result["%2"]?.kind)
        assertEquals(AgentKind.OpenCode, result["%3"]?.kind)
        // `none` = a readable scope with no agent — a confirmed shell.
        assertNull("`none` is a shell, not an agent kind", result["%4"]?.kind)
        assertTrue("`none` must be flagged as a shell", result["%4"]?.isShell == true)
        // `unknown` = pid/cgroup unreadable — not an agent and not a known shell.
        assertNull("`unknown` is not an agent kind", result["%5"]?.kind)
        assertTrue("`unknown` must NOT be flagged as a shell", result["%5"]?.isShell == false)
    }

    @Test
    fun sendsTheRpcRequestShapeAsStdinJson() = runTest {
        val session = FakeSshSession(stdout = """{"results":[]}""")

        source.classify(
            session = session,
            panes = listOf(
                AgentKindRemoteSource.PaneRef("%7", 2647034),
                AgentKindRemoteSource.PaneRef("%8", 99),
            ),
        )

        val command = session.commands.single()
        // The pane snapshot is piped as the byte-for-byte RPC request shape
        // (`{"panes":[{"pane_id","pane_pid"}]}`) on stdin to `agents kind`.
        assertTrue("must pipe the panes JSON; got: $command", command.contains("\"panes\""))
        assertTrue(command.contains("\"pane_id\":\"%7\""))
        assertTrue(command.contains("\"pane_pid\":2647034"))
        assertTrue("must invoke the agents kind subcommand; got: $command", command.contains("agents kind"))
    }

    @Test
    fun emptyPaneListMakesNoRoundTrip() = runTest {
        val session = FakeSshSession(stdout = "should-not-be-read")

        val result = source.classify(session = session, panes = emptyList())

        assertTrue(result.isEmpty())
        assertTrue("no exec must run for an empty pane list", session.commands.isEmpty())
    }

    @Test
    fun nonZeroExitYieldsEmptyMap() = runTest {
        val session = FakeSshSession(stdout = "", exitCode = 127)

        val result = source.classify(
            session = session,
            panes = listOf(AgentKindRemoteSource.PaneRef("%1", 11)),
        )

        assertTrue("a tool-missing / error exit must degrade to an empty verdict map", result.isEmpty())
    }

    @Test
    fun malformedJsonYieldsEmptyMap() = runTest {
        val session = FakeSshSession(stdout = "not json at all")

        val result = source.classify(
            session = session,
            panes = listOf(AgentKindRemoteSource.PaneRef("%1", 11)),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun cancellationPropagates() {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                source.classify(
                    session = session,
                    panes = listOf(AgentKindRemoteSource.PaneRef("%1", 11)),
                )
            }
        }
    }

    private open class FakeSshSession(
        private val stdout: String,
        private val exitCode: Int = 0,
    ) : SshSession {
        val commands = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            commands += command
            return ExecResult(stdout, "", exitCode)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")

        override fun close() = Unit
    }

    private class ThrowingSshSession(private val throwable: Throwable) : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = throw throwable
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")

        override fun close() = Unit
    }
}
