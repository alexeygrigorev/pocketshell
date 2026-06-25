package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #947 — unit-level RED→GREEN for the host upgrade seam that the banner's
 * one-tap **Update** button drives over the warm SSH session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostPocketshellUpgradeTest {

    @Test
    fun success_onExitZero() = runTest {
        val session = FakeSession { ExecResult("upgraded", "", 0) }
        val upgrade = HostPocketshellUpgrade().apply {
            execDispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        val result = upgrade.run(session)
        assertEquals(HostPocketshellUpgrade.Result.Success, result)
        assertTrue("the bounded upgrade command must have run", session.ran)
    }

    @Test
    fun failure_surfacesStderr_onNonZeroExit() = runTest {
        val session = FakeSession { ExecResult("", "uv: resolution failed\nE: cap hit", 1) }
        val upgrade = HostPocketshellUpgrade().apply {
            execDispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        val result = upgrade.run(session)
        assertTrue(result is HostPocketshellUpgrade.Result.Failure)
        val message = (result as HostPocketshellUpgrade.Result.Failure).message
        assertTrue("surfaces stderr — was $message", message.contains("resolution failed"))
        assertTrue("includes exit code — was $message", message.contains("exit 1"))
    }

    @Test
    fun failure_noInstaller_onExit127() = runTest {
        val session = FakeSession { ExecResult("", "", 127) }
        val upgrade = HostPocketshellUpgrade().apply {
            execDispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        val result = upgrade.run(session)
        assertTrue(result is HostPocketshellUpgrade.Result.Failure)
        assertTrue(
            (result as HostPocketshellUpgrade.Result.Failure).message.contains("No uv / pipx / pip"),
        )
    }

    @Test
    fun failure_onThrow_doesNotEscape() = runTest {
        val session = FakeSession { throw RuntimeException("channel closed") }
        val upgrade = HostPocketshellUpgrade().apply {
            execDispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        val result = upgrade.run(session)
        assertTrue(result is HostPocketshellUpgrade.Result.Failure)
        assertTrue(
            (result as HostPocketshellUpgrade.Result.Failure).message.contains("channel closed"),
        )
    }

    @Test
    fun failure_onTimeout_closesSession() = runTest {
        val session = FakeSession { awaitCancellation() }
        val upgrade = HostPocketshellUpgrade().apply {
            execDispatcher = UnconfinedTestDispatcher(testScheduler)
            upgradeTimeoutMs = 25L
        }
        val result = upgrade.run(session)
        assertTrue("a wedged installer must time out to a Failure", result is HostPocketshellUpgrade.Result.Failure)
        assertTrue("timed-out exec closes the session for reconnect", session.closed)
    }

    private class FakeSession(
        private val onExec: suspend () -> ExecResult,
    ) : SshSession {
        @Volatile var ran: Boolean = false
        @Volatile var closed: Boolean = false
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            ran = true
            return onExec()
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() { closed = true }
    }
}
