package com.pocketshell.app.usage

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageResetEventsRemoteSourceTest {

    private val source = UsageResetEventsRemoteSource()
    private val command = PocketshellCommand.wrap(UsageResetEventsRemoteSource.RESET_EVENTS_ARGS)

    @Test
    fun fetch_runsPathRobustResetEventsCommand() = runTest {
        val session = FakeSshSession(mapOf(command to ExecResult("""{"reset_events": []}""", "", 0)))
        source.fetchResetEvents(session)
        assertEquals(listOf(command), session.recorded)
    }

    @Test
    fun fetch_parsesEventsFromDocument() = runTest {
        val doc = """{"reset_events":[{"provider":"codex","reset_key":"k1","detected_at":"2026-06-11T12:00:00Z"}]}"""
        val session = FakeSshSession(mapOf(command to ExecResult(doc, "", 0)))
        val events = source.fetchResetEvents(session)
        assertEquals(1, events.size)
        assertEquals("codex", events.single().provider)
    }

    @Test
    fun fetch_nonZeroExit_returnsEmpty() = runTest {
        // An older CLI that doesn't know --reset-events exits non-zero → empty.
        val session = FakeSshSession(mapOf(command to ExecResult("", "unknown flag", 2)))
        assertTrue(source.fetchResetEvents(session).isEmpty())
    }

    @Test
    fun fetch_transportFailure_returnsEmpty() = runTest {
        val session = ThrowingSshSession(RuntimeException("boom"))
        assertTrue(source.fetchResetEvents(session).isEmpty())
    }

    private class FakeSshSession(
        private val canned: Map<String, ExecResult>,
    ) : SshSession {
        val recorded = mutableListOf<String>()
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult {
            recorded += command
            return canned[command] ?: ExecResult("", "missing stub", 127)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")
        override fun startShell(): SshShell = error("shell not used")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private class ThrowingSshSession(private val throwable: Throwable) : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = throw throwable
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")
        override fun startShell(): SshShell = error("shell not used")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }
}
