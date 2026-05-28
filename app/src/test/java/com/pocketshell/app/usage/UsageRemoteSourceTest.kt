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
    fun detectPocketshell_installedWhenCommandExists() = runTest {
        val session = FakeSshSession(
            mapOf(UsageRemoteSource.DETECT_POCKETSHELL_COMMAND to ExecResult("/usr/bin/pocketshell\n", "", 0)),
        )

        assertEquals(UsageToolStatus.Installed, source.detectPocketshell(session))
        assertEquals(listOf(UsageRemoteSource.DETECT_POCKETSHELL_COMMAND), session.recorded)
    }

    @Test
    fun detectPocketshell_missingWhenCommandFails() = runTest {
        val session = FakeSshSession(
            mapOf(UsageRemoteSource.DETECT_POCKETSHELL_COMMAND to ExecResult("", "", 1)),
        )

        assertEquals(UsageToolStatus.Missing, source.detectPocketshell(session))
    }

    @Test
    fun fetchUsage_runsDefaultCommandAndParsesRecords() = runTest {
        val session = FakeSshSession(
            mapOf(
                UsageRemoteSource.defaultUsageCommand to ExecResult(
                    """{"provider":"codex","status":"blocked","short_term":null,"long_term":null,"block_reason":"weekly limit reached","error":null,"details":{}}""",
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
                    """{"provider":"claude","status":"ok","short_term":null,"long_term":null,"block_reason":null,"error":null,"details":{}}""",
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
            mapOf(UsageRemoteSource.defaultUsageCommand to ExecResult("", "pocketshell: not found", 127)),
        )

        assertEquals(UsageFetchResult.ToolMissing, source.fetchUsage(session))
    }

    @Test
    fun detectPocketshell_propagatesCancellation() = runTest {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source.detectPocketshell(session) }
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
}
