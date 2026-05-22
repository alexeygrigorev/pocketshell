package com.pocketshell.app.usage

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.usage.UsageStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRemoteSourceTest {

    private val source = UsageRemoteSource()

    @Test
    fun detectHeru_installedWhenCommandExists() = runTest {
        val session = FakeSshSession(
            mapOf(UsageRemoteSource.DETECT_HERU_COMMAND to ExecResult("/usr/bin/heru\n", "", 0)),
        )

        assertEquals(UsageToolStatus.Installed, source.detectHeru(session))
        assertEquals(listOf(UsageRemoteSource.DETECT_HERU_COMMAND), session.recorded)
    }

    @Test
    fun detectHeru_missingWhenCommandFails() = runTest {
        val session = FakeSshSession(
            mapOf(UsageRemoteSource.DETECT_HERU_COMMAND to ExecResult("", "", 1)),
        )

        assertEquals(UsageToolStatus.Missing, source.detectHeru(session))
    }

    @Test
    fun fetchUsage_runsDefaultCommandAndParsesRecords() = runTest {
        val session = FakeSshSession(
            mapOf(
                UsageRemoteSource.defaultUsageCommand to ExecResult(
                    """[{"provider":"codex","status":"blocked","windows":[{"name":"weekly","used":100,"limit":100,"unit":"percent"}]}]""",
                    "",
                    0,
                ),
            ),
        )

        val result = source.fetchUsage(session)

        assertTrue(result is UsageFetchResult.Success)
        val success = result as UsageFetchResult.Success
        assertEquals(UsageStatus.Blocked, success.records.single().status)
        assertEquals(listOf(UsageRemoteSource.defaultUsageCommand), session.recorded)
    }

    @Test
    fun fetchUsage_usesCommandOverride() = runTest {
        val session = FakeSshSession(
            mapOf(
                "custom-usage --json" to ExecResult(
                    """{"provider":"claude","status":"ok","windows":[]}""",
                    "",
                    0,
                ),
            ),
        )

        val result = source.fetchUsage(session, commandOverride = "custom-usage --json")

        assertTrue(result is UsageFetchResult.Success)
        assertEquals(listOf("custom-usage --json"), session.recorded)
    }

    @Test
    fun fetchUsage_exit127IsToolMissing() = runTest {
        val session = FakeSshSession(
            mapOf(UsageRemoteSource.defaultUsageCommand to ExecResult("", "heru: not found", 127)),
        )

        assertEquals(UsageFetchResult.ToolMissing, source.fetchUsage(session))
    }

    @Test
    fun detectHeru_propagatesCancellation() = runTest {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source.detectHeru(session) }
        }
    }

    @Test
    fun fetchUsage_propagatesCancellation() = runTest {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source.fetchUsage(session) }
        }
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

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

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

        override fun close() = Unit
    }
}
