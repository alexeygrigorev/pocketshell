package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #680: connected (emulator + Docker) proof that the host-detail refresh
 * probe HEALS a transient/stale-channel symptom instead of surfacing a false
 * "Couldn't refresh sessions: SSH session is not connected" banner while the
 * host is actually live.
 *
 * The maintainer's dogfood report: a refresh showed the red "not connected"
 * banner even though they connected to + used a session right after. The root
 * cause is the pooled lease's `isConnected` lying (sshj reports a silently-dead
 * transport as alive until its 60 s keepalive trips), so the pool hands the
 * corpse back and `RealSshSession.ensureConnected()` throws "SSH session is not
 * connected" on the exec — a FALSE NEGATIVE.
 *
 * We reproduce that exact shape against the LIVE Docker `agents` fixture by
 * injecting a [SshLeaseConnector] whose FIRST connect returns a session that
 * reports `isConnected = true` but throws the `ensureConnected()` failure on
 * exec (the sshj-lies stale lease), and whose subsequent connects dial the REAL
 * Docker host. The gateway must:
 *
 *  - heal the first stale lease and retry on a fresh real dial WITHIN the same
 *    refresh, returning [FolderListResult.Sessions] (no false error); and
 *  - still surface an accurate error when the host is GENUINELY unreachable.
 *
 * Docker service: `agents` on host port `2222` (already in the CI workflow).
 */
@RunWith(AndroidJUnit4::class)
class FolderListGatewayStaleChannelHealDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val createdSessions = mutableListOf<String>()

    @Before
    fun setUp(): Unit { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue680-heal-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        if (createdSessions.isEmpty()) {
            runCatching { keyFile.delete() }
            return@runBlocking
        }
        withTimeout(15_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrNull()?.use { session ->
                for (name in createdSessions) {
                    runCatching { session.exec("tmux kill-session -t $name 2>/dev/null || true") }
                }
            }
        }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun staleLeaseHealsToFreshDockerDialWithinSameRefresh(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folder = "/tmp/issue680-heal-$suffix"
        val session = "issue680-heal-$suffix"

        // Seed one real tmux session so the healed probe has something to find.
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { s ->
                s.exec("mkdir -p $folder")
                s.exec("tmux new-session -d -s $session -c $folder")
                createdSessions += session
            }
        }

        // First connect: a lying-stale lease (isConnected=true, exec throws
        // "not connected"). Subsequent connects: the REAL Docker dial.
        val real = DefaultSshLeaseConnector()
        val connectCount = AtomicInteger(0)
        val connector = SshLeaseConnector { target ->
            if (connectCount.getAndIncrement() == 0) {
                Result.success(LyingStaleSession())
            } else {
                real.connect(target)
            }
        }
        val manager = SshLeaseManager(connector = connector, idleTtlMillis = 60_000L)
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = manager,
            sessionListParser = HostTmuxSessionListParser(),
        )

        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(
                host = HOST,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                // A watched root forces the SSH-exec probe path.
                watchedRoots = listOf(
                    ProjectRootEntity(id = 1L, hostId = 1L, label = "tmp", path = "/tmp"),
                ),
            )
        }

        assertTrue(
            "a stale 'not connected' probe must HEAL to a fresh Docker dial within the " +
                "same refresh and return Sessions (NO false 'not connected' banner), got $result",
            result is FolderListResult.Sessions,
        )
        val rows = (result as FolderListResult.Sessions).rows
        assertTrue(
            "the healed (real) probe must enumerate our seeded session $session, got ${rows.map { it.sessionName }}",
            rows.any { it.sessionName == session },
        )
        assertEquals(
            "exactly one heal retry: the stale facade + one real dial",
            2,
            connectCount.get(),
        )
    } }

    @Test
    fun genuineDisconnectStillSurfacesAnError(): Unit { runBlocking {
        // Point the gateway at a dead port so BOTH the initial dial and the
        // heal retry genuinely fail to connect — the refresh must NOT pretend
        // success; it surfaces an accurate error.
        val gateway = SshFolderListGateway()
        val deadHost = HOST.copy(port = 59_999)
        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(
                host = deadHost,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                watchedRoots = listOf(
                    ProjectRootEntity(id = 1L, hostId = 1L, label = "tmp", path = "/tmp"),
                ),
            )
        }
        assertTrue(
            "a genuine disconnect must surface an error, got $result",
            result is FolderListResult.ConnectFailed || result is FolderListResult.Failed,
        )
    } }

    /**
     * Pooled session that LIES about connectivity (sshj reports a silently-dead
     * transport as alive until its keepalive trips) but throws the exact
     * `ensureConnected()` failure on exec — reproducing the #680 false negative.
     */
    private class LyingStaleSession : SshSession {
        @Volatile
        var closed: Boolean = false

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

    private companion object {
        val HOST: HostEntity = HostEntity(
            id = 1L,
            name = "issue680-heal-test",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
        )
    }
}
