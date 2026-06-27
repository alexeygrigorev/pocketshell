package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Epic #821 Slice 1 — `ManualKindWriter` builds + runs the host-side
 * `@ps_agent_kind` write that classifies / re-classifies a session. These
 * unit tests cover the command shape (mirrors `record_agent_kind`), the
 * non-classification guard, the blank-name guard, and the failure surface.
 */
class ManualKindWriterTest {

    @Test
    fun `builds session-scoped set-option command for each pickable kind`() {
        assertEquals(
            manualKindCommand("work", "claude"),
            ManualKindWriter.buildSetOptionCommand("work", SessionAgentKind.Claude),
        )
        assertEquals(
            manualKindCommand("work", "codex"),
            ManualKindWriter.buildSetOptionCommand("work", SessionAgentKind.Codex),
        )
        assertEquals(
            manualKindCommand("work", "opencode"),
            ManualKindWriter.buildSetOptionCommand("work", SessionAgentKind.OpenCode),
        )
        // A manually-classified plain shell IS recordable (so it never
        // re-prompts as Unknown) — the one extra value over the wrapper.
        assertEquals(
            manualKindCommand("work", "shell"),
            ManualKindWriter.buildSetOptionCommand("work", SessionAgentKind.Shell),
        )
    }

    @Test
    fun `single-quotes a session name with shell metacharacters`() {
        assertEquals(
            manualKindCommand("a'\\''b c", "claude"),
            ManualKindWriter.buildSetOptionCommand("a'b c", SessionAgentKind.Claude),
        )
    }

    @Test
    fun `manual kind write clears stale recorded transcript source`() {
        val command = ManualKindWriter.buildSetOptionCommand("work", SessionAgentKind.Codex)!!

        assertTrue(command.contains("@ps_agent_kind codex"))
        assertTrue(command.contains("set-option -u -q -t 'work' @ps_agent_source_generation"))
        assertTrue(command.contains("set-option -u -q -t 'work' @ps_agent_source"))
    }

    @Test
    fun `returns null command for non-classification kinds`() {
        assertNull(ManualKindWriter.buildSetOptionCommand("work", SessionAgentKind.Probing))
        assertNull(ManualKindWriter.buildSetOptionCommand("work", SessionAgentKind.Exited))
        assertNull(ManualKindWriter.buildSetOptionCommand("work", SessionAgentKind.Unknown))
    }

    @Test
    fun `write runs the set-option command on the session`() = runTest {
        val session = RecordingSshSession()
        ManualKindWriter.write(session, "work", SessionAgentKind.Codex)
        assertEquals(
            listOf(manualKindCommand("work", "codex")),
            session.commands,
        )
    }

    @Test
    fun `write rejects a blank session name`() = runTest {
        val session = RecordingSshSession()
        val thrown = runCatching { ManualKindWriter.write(session, "   ", SessionAgentKind.Claude) }
            .exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertTrue(session.commands.isEmpty())
    }

    @Test
    fun `write rejects a non-classification kind`() = runTest {
        val session = RecordingSshSession()
        val thrown = runCatching { ManualKindWriter.write(session, "work", SessionAgentKind.Unknown) }
            .exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertTrue(session.commands.isEmpty())
    }

    @Test
    fun `write surfaces a tmux failure as a RuntimeException`() = runTest {
        val session = RecordingSshSession(
            execResult = ExecResult(stdout = "", stderr = "no server running", exitCode = 1),
        )
        val thrown = runCatching { ManualKindWriter.write(session, "work", SessionAgentKind.Claude) }
            .exceptionOrNull()
        if (thrown !is RuntimeException) fail("expected RuntimeException, got $thrown")
        assertTrue((thrown as RuntimeException).message!!.contains("no server running"))
    }

    private class RecordingSshSession(
        private val execResult: ExecResult = ExecResult(stdout = "", stderr = "", exitCode = 0),
    ) : SshSession {
        val commands: MutableList<String> = mutableListOf()
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            commands += command
            return execResult
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private companion object {
        fun manualKindCommand(target: String, kind: String): String =
            "tmux set-option -t '$target' @ps_agent_kind $kind" +
                " \\; set-option -u -q -t '$target' @ps_agent_source_generation" +
                " \\; set-option -u -q -t '$target' @ps_agent_source"
    }
}
