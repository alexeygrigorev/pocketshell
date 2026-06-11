package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
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
 * Issue #465 / #680: a folder-tree probe that hits an "open failed" — the
 * pooled SSH transport is alive but refuses to open the exec channel — must
 * EVICT the poisoned lease and open a FRESH transport so the host-detail tree
 * recovers, instead of dead-ending on a permanent `ConnectFailed` ("open
 * failed") screen with no way back but force-close.
 *
 * #680 strengthened #465: the heal now happens WITHIN the same refresh (evict
 * + retry once on a fresh lease) rather than only on the next poll, so the user
 * never even sees the transient `ConnectFailed` flash for a stale-channel
 * symptom.
 */
class FolderListGatewayOpenFailedEvictionTest {

    @Test
    fun openFailedProbeEvictsPoisonedLeaseAndHealsWithinSameRefresh() = runBlocking {
        val poisoned = OpenFailingSession()
        val healthy = HealthySession()
        val connector = TwoSessionConnector(poisoned, healthy)
        // A non-zero idle TTL models the real pool: a released-but-still-
        // connected session is RETAINED and reused on the next acquire. Without
        // the #465 eviction, the poisoned transport would be handed back and
        // the retry would fail again ("open failed") instead of opening a fresh
        // transport.
        val manager = SshLeaseManager(connector = connector, idleTtlMillis = 60_000L)
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = manager,
            sessionListParser = com.pocketshell.app.sessions.HostTmuxSessionListParser(),
        )

        // The live transport refuses the exec channel ("open failed"). #680: the
        // gateway evicts the poisoned lease and retries ONCE on a fresh
        // transport within this same refresh, so the result is already the
        // recovered Sessions — no transient ConnectFailed surfaces.
        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )
        assertTrue(
            "an open-failed probe must heal within the same refresh and return " +
                "Sessions (the poisoned lease evicted + a fresh transport opened), got $result",
            result is FolderListResult.Sessions,
        )
        assertEquals(
            "the poisoned lease must be evicted so the heal retry opens a fresh transport",
            2,
            connector.connectCount,
        )
        assertTrue("the poisoned transport must have been evicted (closed)", poisoned.closed)
    }

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

    /** Transport that stays connected but refuses the exec channel. */
    private class OpenFailingSession : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            throw java.io.IOException("open failed")

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
