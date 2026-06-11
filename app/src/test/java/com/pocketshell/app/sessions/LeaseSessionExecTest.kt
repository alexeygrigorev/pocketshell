package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Verifies the shared borrow-a-session helper (#699): two actions reuse ONE
 * warm transport (no per-action handshake), release does NOT close the
 * transport, cancellation still releases the refcount, and a stale-channel
 * symptom evicts + retries once on a fresh connection.
 */
class LeaseSessionExecTest {

    @Test
    fun twoBorrowsReuseOneWarmTransport() = runTest {
        val session = FakeSshSession()
        val connector = CountingConnector(session)
        val manager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L)

        val first = LeaseSessionExec.withSession(manager, TARGET) { it.exec("echo a") }
        val second = LeaseSessionExec.withSession(manager, TARGET) { it.exec("echo b") }

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        // The whole point: ONE handshake serves both actions (warm reuse).
        assertEquals(1, connector.connectCount)
        // Release must NOT tear the shared transport down.
        assertFalse("warm transport must stay open after release", session.closed)
        assertEquals(listOf("echo a", "echo b"), session.execCommands)
    }

    @Test
    fun leaseKeyMatchesSessionScreenEncoding() {
        val target = LeaseSessionTarget(
            hostId = 42L,
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyPath = "/tmp/key",
            passphrase = null,
        )
        val key = target.toSshLeaseTarget().leaseKey
        // Byte-identical to SshHostTmuxSessionsGateway / SshFolderListGateway.
        assertEquals("10.0.2.2", key.host)
        assertEquals(2222, key.port)
        assertEquals("testuser", key.user)
        assertEquals("42:/tmp/key", key.credentialId)
        assertEquals("accept-all", key.knownHostsId)
    }

    @Test
    fun cancelledBorrowReleasesLease() = runTest {
        val session = FakeSshSession(cancelOnExec = true)
        val manager = SshLeaseManager(
            connector = CountingConnector(session),
            scope = this,
            idleTtlMillis = 0L,
        )

        val job = launch {
            try {
                LeaseSessionExec.withSession(manager, TARGET) { it.exec("x") }
            } catch (_: CancellationException) {
                // expected
            }
        }
        job.join()

        // idleTtl=0 means a released lease closes immediately; proves release ran.
        assertTrue("cancelled borrow must release the lease", session.closed)
    }

    @Test
    fun staleChannelSymptomEvictsAndRetriesOnceOnFreshTransport() = runTest {
        val deadSession = FakeSshSession(
            resultForCommand = { throw IOException("channel open failed") },
        )
        val healthySession = FakeSshSession()
        val connector = SequenceConnector(listOf(deadSession, healthySession))
        val manager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L)

        val result = LeaseSessionExec.withSession(manager, TARGET) { it.exec("probe") }

        assertTrue("retry on a fresh transport should succeed", result.isSuccess)
        assertEquals(2, connector.connectCount)
        assertTrue("poisoned transport must be evicted (closed)", deadSession.closed)
        assertFalse("healed transport must stay warm", healthySession.closed)
        assertEquals(listOf("probe"), healthySession.execCommands)
    }

    @Test
    fun nonStaleFailureDoesNotRetry() = runTest {
        val session = FakeSshSession(
            resultForCommand = { throw IllegalStateException("boom") },
        )
        val connector = CountingConnector(session)
        val manager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L)

        val result = LeaseSessionExec.withSession(manager, TARGET) { it.exec("probe") }

        assertTrue(result.isFailure)
        // A genuine non-transport error must not trigger a second handshake.
        assertEquals(1, connector.connectCount)
        assertFalse("non-stale failure must keep the transport warm", session.closed)
    }

    private class CountingConnector(private val session: FakeSshSession) : SshLeaseConnector {
        var connectCount: Int = 0
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    private class SequenceConnector(private val sessions: List<FakeSshSession>) : SshLeaseConnector {
        var connectCount: Int = 0
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val s = sessions[connectCount.coerceAtMost(sessions.lastIndex)]
            connectCount += 1
            return Result.success(s)
        }
    }

    private class FakeSshSession(
        private val cancelOnExec: Boolean = false,
        private val resultForCommand: (String) -> ExecResult = { ExecResult("", "", 0) },
    ) : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        var closed: Boolean = false
        override val isConnected: Boolean get() = !closed
        override suspend fun exec(command: String): ExecResult {
            if (cancelOnExec) {
                currentCoroutineContext()[Job]?.cancel()
                throw CancellationException("cancelled during borrow")
            }
            execCommands += command
            return resultForCommand(command)
        }
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String =
            error("not used")
        override fun close() {
            closed = true
        }
    }

    private companion object {
        val TARGET = LeaseSessionTarget(
            hostId = 42L,
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyPath = "/tmp/pocketshell-test-key",
            passphrase = null,
        )
    }
}
