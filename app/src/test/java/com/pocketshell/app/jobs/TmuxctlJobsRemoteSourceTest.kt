package com.pocketshell.app.jobs

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxctlJobsRemoteSourceTest {

    private val source = TmuxctlJobsRemoteSource(TmuxctlJobsParser())

    @Test
    fun list_runsTmuxctlAndParsesRows() = runTest {
        val output = """
            ID  ENABLED  SESSION  EVERY  DELAY  SOURCE  NEXT RUN             DETAIL
            1   yes      codex    15m    200    inline  2026-04-03 00:15:00 check
        """.trimIndent()
        val session = FakeSshSession(
            mapOf(pathAware("tmuxctl jobs list --session 'codex'") to ExecResult(output, "", 0)),
        )

        val result = source.list(session, sessionName = "codex")

        assertTrue(result is RecurringJobsCommandResult.Jobs)
        assertEquals(listOf(1), (result as RecurringJobsCommandResult.Jobs).jobs.map { it.id })
        assertEquals(listOf(pathAware("tmuxctl jobs list --session 'codex'")), session.recorded)
    }

    @Test
    fun list_quotesSessionNamesWithSpaces() = runTest {
        val session = FakeSshSession(
            mapOf(pathAware("tmuxctl jobs list --session 'agent main'") to ExecResult("", "", 0)),
        )

        val result = source.list(session, sessionName = "agent main")

        assertTrue(result is RecurringJobsCommandResult.Jobs)
        assertEquals(listOf(pathAware("tmuxctl jobs list --session 'agent main'")), session.recorded)
    }

    @Test
    fun add_quotesShellArguments() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("tmuxctl jobs add 'agent main' --every '15m' --message 'it'\"'\"'s ok'") to
                    ExecResult("Created job 3\n", "", 0),
            ),
        )

        val result = source.add(
            session,
            RecurringJobDraft(
                sessionName = "agent main",
                every = "15m",
                message = "it's ok",
            ),
        )

        assertEquals(RecurringJobsCommandResult.Success, result)
    }

    @Test
    fun editBuildsOnlyProvidedOptions() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("tmuxctl jobs edit 4 --every '30m' --disable") to ExecResult("Updated job 4\n", "", 0),
            ),
        )

        assertEquals(
            RecurringJobsCommandResult.Success,
            source.edit(session, jobId = 4, every = "30m", enabled = false),
        )
    }

    @Test
    fun removeRunsRemoveCommand() = runTest {
        val session = FakeSshSession(
            mapOf(pathAware("tmuxctl jobs remove 4") to ExecResult("Removed job 4\n", "", 0)),
        )

        assertEquals(RecurringJobsCommandResult.Success, source.remove(session, 4))
    }

    @Test
    fun exit127IsToolMissing() = runTest {
        val session = FakeSshSession(
            mapOf(pathAware("tmuxctl jobs list") to ExecResult("", "tmuxctl: not found", 127)),
        )

        assertEquals(RecurringJobsCommandResult.ToolMissing, source.list(session))
    }

    @Test
    fun cancellationPropagates() = runTest {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { source.list(session) }
        }
    }

    private class FakeSshSession(
        private val canned: Map<String, ExecResult>,
    ) : SshSession {
        val recorded = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            return canned[command] ?: ExecResult("", "missing stub for $command", 127)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private class ThrowingSshSession(
        private val throwable: Throwable,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            throw throwable
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private fun pathAware(command: String): String =
        "PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\"; $command"
}
