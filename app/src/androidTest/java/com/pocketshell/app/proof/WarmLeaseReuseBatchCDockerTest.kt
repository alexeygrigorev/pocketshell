package com.pocketshell.app.proof

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.projects.RepoBrowserUiState
import com.pocketshell.app.projects.RepoBrowserViewModel
import com.pocketshell.app.projects.WatchedFoldersViewModel
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.usage.SshHostUsageFetcher
import com.pocketshell.app.usage.UsageRemoteSource
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import androidx.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #699 Batch C: proves the three remaining per-action-handshake offenders
 * migrated in this batch — the **usage** detail fetcher (per-poll dial), the
 * projects **repo-browser** VM (per list/clone/open dial), and the **watched
 * folders** discover probe (per-tap dial) — now SHARE the app-wide warm SSH
 * transport instead of dialing a fresh ~3-4s handshake per action.
 *
 * All three are pointed at ONE [SshLeaseManager] (the app-singleton stand-in)
 * whose connector COUNTS real handshakes. After exercising every surface
 * against the deterministic Docker `agents` fixture on `10.0.2.2:2222`, the test
 * asserts exactly ONE connect happened: the cold dial for the first action,
 * then every other action across all three surfaces reuses the warm transport.
 *
 * The remote commands themselves may legitimately come back "tool missing" /
 * "tool unavailable" on the bare fixture (it does not ship the `pocketshell`
 * CLI or a real GitHub repo set) — that is irrelevant to the proof. The point
 * is that the SSH transport is opened ONCE and reused, which is exactly what
 * the handshake counter measures. The watched-folders discover probe runs a
 * plain `ls`, so it also demonstrates a real, successful exec over the warm
 * transport.
 */
@RunWith(AndroidJUnit4::class)
class WarmLeaseReuseBatchCDockerTest {

    private class CountingConnector(
        private val delegate: SshLeaseConnector,
    ) : SshLeaseConnector {
        val connectCount = AtomicInteger(0)
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount.incrementAndGet()
            return delegate.connect(target)
        }
    }

    @Test
    fun usageRepoBrowserAndWatchedFoldersAllReuseOneWarmTransport() = runBlocking {
        val keyContent = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        val key = SshKey.Pem(keyContent)
        waitForSshFixtureReady(key)

        val keyPath = writeKeyToFile(keyContent)
        val connector = CountingConnector(DefaultSshLeaseConnector())
        val leaseManager = SshLeaseManager(connector = connector)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(name = "warm-lease-c", privateKeyPath = keyPath),
            )
            val host = HostEntity(
                id = HOST_ID,
                name = "warm-lease-batch-c",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            )
            db.hostDao().insert(host)

            // --- Surface 1: usage detail fetcher (per-poll dial) ---
            // The fetcher resolves the host's key from the DAO and borrows a
            // session from the warm lease. Three reads must pay ONE handshake.
            val usageFetcher = SshHostUsageFetcher(
                sshKeyDao = db.sshKeyDao(),
                remoteSource = UsageRemoteSource(),
                sshLeaseManager = leaseManager,
            )
            repeat(3) { usageFetcher.fetch(host) }

            // --- Surface 2: repo-browser VM (per list/clone/open dial) ---
            // bind() kicks the remote+local enumeration over the warm lease.
            val repoVm = RepoBrowserViewModel(
                reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
                sshLeaseManager = leaseManager,
            )
            repoVm.bind(
                RepoBrowserViewModel.SshCredentials(
                    hostId = HOST_ID,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyPath = keyPath,
                    passphrase = null,
                ),
            )
            // Wait until the load settles to a terminal state (Ready / Failed /
            // ToolUnavailable) so the enumeration actually ran over the lease.
            withTimeout(30_000L) {
                while (repoVm.state.value is RepoBrowserUiState.Loading) {
                    delay(100)
                }
            }

            // --- Surface 3: watched-folders discover probe (per-tap dial) ---
            // The discover runs a plain `ls -d` over the warm lease.
            val watchedVm = WatchedFoldersViewModel(
                projectRootDao = db.projectRootDao(),
                sshLeaseManager = leaseManager,
            )
            watchedVm.bind(
                hostId = HOST_ID,
                hostName = host.name,
                sshCredentials = WatchedFoldersViewModel.SshCredentials(
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyPath = keyPath,
                    passphrase = null,
                ),
            )
            watchedVm.discoverFromRemote()
            // Wait until discovery finishes (the discovering flag drops).
            withTimeout(30_000L) {
                while (watchedVm.state.value.discovering) {
                    delay(100)
                }
            }
            // A clean `ls` discover should NOT have surfaced a connect/exec
            // error — proving the warm transport actually served the probe.
            assertTrue(
                "watched-folders discover should run over the warm transport, " +
                    "got error=${watchedVm.state.value.discoverError}",
                watchedVm.state.value.discoverError == null,
            )

            // === The acceptance assertion: ONE handshake for ALL actions ===
            val connects = connector.connectCount.get()
            val summary = buildString {
                appendLine("issue699_batch_c_warm_lease_reuse")
                appendLine("ssh_connects_total=$connects")
                appendLine("usage_reads=3")
                appendLine("repo_browser_loads=1")
                appendLine("watched_folders_discovers=1")
                appendLine("repo_browser_state=${repoVm.state.value::class.simpleName}")
                appendLine("watched_folders_discover_error=${watchedVm.state.value.discoverError}")
            }
            Log.i(LOG_TAG, summary)
            println("ISSUE699_BATCH_C_SUMMARY\n$summary")
            writeArtifact("issue699-batch-c-warm-lease-summary.txt", summary)

            assertEquals(
                "all actions across usage + repo-browser + watched-folders must share " +
                    "ONE warm transport (no per-action handshake)\n$summary",
                1,
                connects,
            )
        } finally {
            db.close()
        }
    }

    private fun writeKeyToFile(content: String): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.filesDir, "issue699-batch-c-warm-lease-key")
        file.writeText(content)
        file.setReadable(false, false)
        file.setReadable(true, true)
        return file.absolutePath
    }

    private fun writeArtifact(name: String, content: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$ARTIFACT_DIR")
        check(dir.exists() || dir.mkdirs()) { "Could not create artifact dir: ${dir.absolutePath}" }
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(content.toByteArray()) }
        println("ISSUE699_BATCH_C_ARTIFACT ${file.absolutePath}")
    }

    private companion object {
        const val LOG_TAG: String = "PocketShellWarmLease699C"
        const val ARTIFACT_DIR: String = "issue699-batch-c-warm-lease"
        const val HOST_ID: Long = 6991L
    }
}
