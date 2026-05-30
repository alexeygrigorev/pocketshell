package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class WarmSshConnectionsTest {
    @Test
    fun warmReusesSameHostConnectionUntilTaken() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueConnector(first, second)
        val warm = WarmSshConnections(connector)

        assertTrue(warm.warm(TARGET, passphrase = null).isSuccess)
        assertTrue(warm.warm(TARGET, passphrase = null).isSuccess)

        assertEquals(1, connector.connectCount)
        assertSame(first, warm.take(TARGET))
        assertFalse(first.closed)

        assertTrue(warm.warm(TARGET, passphrase = null).isSuccess)
        assertEquals(2, connector.connectCount)
        assertSame(second, warm.take(TARGET))
    }

    @Test
    fun warmingDifferentHostClosesPreviousConnection() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueConnector(first, second)
        val warm = WarmSshConnections(connector)

        warm.warm(TARGET, passphrase = null).getOrThrow()
        warm.warm(OTHER_TARGET, passphrase = null).getOrThrow()

        assertTrue(first.closed)
        assertFalse(second.closed)
        assertEquals(2, connector.connectCount)
    }

    @Test
    fun closeReleasesMatchingWarmConnection() = runTest {
        val session = FakeSshSession()
        val warm = WarmSshConnections(QueueConnector(session))

        warm.warm(TARGET, passphrase = null).getOrThrow()
        warm.close(TARGET)

        assertTrue(session.closed)
        assertEquals(null, warm.take(TARGET))
    }

    @Test
    fun closeIfIdleSynchronouslyReleasesMatchingWarmConnection() = runTest {
        val session = FakeSshSession()
        val warm = WarmSshConnections(QueueConnector(session))

        warm.warm(TARGET, passphrase = null).getOrThrow()

        assertTrue(warm.closeIfIdle(TARGET))
        assertTrue(session.closed)
        assertEquals(null, warm.take(TARGET))
    }

    @Test
    fun closeIfIdleAbandonsInFlightWarmConnectAfterCancellation() = runTest {
        val connectStarted = CompletableDeferred<Unit>()
        val connectResult = CompletableDeferred<FakeSshSession>()
        val connector = SshConnector { _, _ ->
            connectStarted.complete(Unit)
            val session = withContext(NonCancellable) { connectResult.await() }
            Result.success(session)
        }
        val warm = WarmSshConnections(connector)

        val warmJob = launch { warm.warm(TARGET, passphrase = null) }
        connectStarted.await()

        warmJob.cancel()
        assertFalse(warm.closeIfIdle(TARGET))

        val session = FakeSshSession()
        connectResult.complete(session)
        warmJob.join()

        assertTrue(session.closed)
        assertEquals(null, warm.take(TARGET))
    }

    private class QueueConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: WarmSshTarget, passphrase: CharArray?): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected connect $connectCount for $target")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession : SshSession {
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val TARGET: WarmSshTarget = WarmSshTarget(
            hostId = 1L,
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyPath = "/tmp/key",
        )

        val OTHER_TARGET: WarmSshTarget = WarmSshTarget(
            hostId = 2L,
            hostname = "example.test",
            port = 22,
            username = "deploy",
            keyPath = "/tmp/key2",
        )
    }
}
