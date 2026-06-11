package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #680: a refresh probe over a pooled lease whose transport went STALE
 * between acquire and exec throws `SshException("SSH session is not connected")`
 * (sshj's `isConnected` lies until its keepalive trips, so the lease is handed
 * back as alive then `ensureConnected()` fails on the exec). That is a FALSE
 * NEGATIVE — the host is connectable — yet the folder screen surfaced a scary
 * persistent "Couldn't refresh sessions: SSH session is not connected" banner.
 *
 * The gateway must HEAL + RETRY ONCE on a fresh lease WITHIN the same refresh:
 * the transient symptom recovers silently (the refresh returns Sessions, no
 * false error), and only a GENUINE disconnect — where the fresh connect or the
 * retried exec also fails — surfaces an accurate error.
 */
class FolderListGatewayStaleChannelHealTest {

    @Test
    fun notConnectedProbeHealsAndRetriesOnFreshLeaseWithinSameRefresh() = runBlocking {
        val stale = NotConnectedSession()
        val healthy = HealthySession()
        val connector = TwoSessionConnector(stale, healthy)
        // A non-zero idle TTL models the real pool: a released-but-connected
        // session is RETAINED. The stale session reports isConnected=true so the
        // pool would keep handing it back without #680's evict-and-retry.
        val manager = SshLeaseManager(connector = connector, idleTtlMillis = 60_000L)
        val gateway = newGateway(manager)

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue(
            "a stale 'not connected' probe must HEAL within the same refresh and " +
                "return Sessions (no false 'not connected' banner), got $result",
            result is FolderListResult.Sessions,
        )
        assertEquals(
            "the poisoned lease must be evicted and a fresh transport dialled",
            2,
            connector.connectCount,
        )
        assertTrue("the poisoned transport must have been evicted (closed)", stale.closed)
    }

    @Test
    fun genuineDisconnectStillSurfacesAnError() = runBlocking {
        // Both the first lease and the heal-retry lease are dead: the host is
        // genuinely unreachable. The refresh must NOT pretend success — it
        // surfaces an accurate failure.
        val firstDead = NotConnectedSession()
        val secondDead = NotConnectedSession()
        val connector = TwoSessionConnector(firstDead, secondDead)
        val manager = SshLeaseManager(connector = connector, idleTtlMillis = 60_000L)
        val gateway = newGateway(manager)

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue(
            "a genuine disconnect (heal retry also fails) must surface an error, got $result",
            result is FolderListResult.ConnectFailed,
        )
        assertEquals(
            "the heal must retry exactly once on a fresh lease before giving up",
            2,
            connector.connectCount,
        )
    }

    @Test
    fun healHappensAtMostOncePerRefresh() = runBlocking {
        // Three stale sessions queued; the gateway must only dial twice (initial
        // + one heal) and then give up rather than loop on the symptom forever.
        val connector = StaleForeverConnector()
        val manager = SshLeaseManager(connector = connector, idleTtlMillis = 60_000L)
        val gateway = newGateway(manager)

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue(result is FolderListResult.ConnectFailed)
        assertEquals(
            "the heal retry must fire at most once (initial + 1), not loop",
            2,
            connector.connectCount,
        )
    }

    private fun newGateway(manager: SshLeaseManager): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = manager,
            sessionListParser = com.pocketshell.app.sessions.HostTmuxSessionListParser(),
        )

    private class TwoSessionConnector(
        private val first: SshSession,
        private val second: SshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = when (connectCount) {
                0 -> first
                1 -> second
                else -> error("unexpected lease connect $connectCount for ${target.leaseKey}")
            }
            connectCount += 1
            return Result.success(next)
        }
    }

    private class StaleForeverConnector : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(NotConnectedSession())
        }
    }

    /**
     * Pooled session that is handed back as `isConnected=true` (sshj's
     * connectivity lies until its keepalive trips) but throws the exact
     * `ensureConnected()` failure on exec — reproducing the #680 false negative.
     */
    private class NotConnectedSession : SshSession {
        @Volatile
        var closed: Boolean = false

        // Reports connected so the lease pool retains + reuses it: the failure
        // only shows up when the exec actually touches the dead transport.
        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            throw SshException("SSH session is not connected")

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

    /** Fresh transport whose probe enumerates to a (possibly empty) session set. */
    private class HealthySession : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

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
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 7L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 1L,
        )

        // A watched root forces the SSH-exec probe path (the live-client
        // shortcut is skipped when watchedRoots is non-empty).
        val WATCHED_ROOTS: List<ProjectRootEntity> = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "code", path = "~/code"),
        )
    }
}
