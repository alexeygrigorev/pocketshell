package com.pocketshell.app.usage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.usage.PocketshellUsageJsonParser
import com.pocketshell.core.usage.UsageProviderRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant

/**
 * Robolectric tests for [UsageViewModel].
 *
 * The view model is constructed with a fake [HostUsageFetcher] so SSH /
 * pocketshell I/O is entirely bypassed. Hosts are seeded into an in-memory
 * Room database; the fake fetcher classifies each one by hostname.
 *
 * This covers the routing surface only — the SSH-backed
 * [SshHostUsageFetcher] is exercised in the connected
 * [com.pocketshell.app.usage.UsageScreenE2eTest] against
 * `tests/docker/agents/` and `tests/docker/bootstrap-ready/`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UsageViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun refresh_partitionsHostsByPocketshellStatus() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        val installedHostId = db.hostDao().insert(
            HostEntity(
                name = "agents",
                hostname = "h1.example",
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                lastBootstrapAt = System.currentTimeMillis(),
            ),
        )
        val missingHostId = db.hostDao().insert(
            HostEntity(
                name = "bare",
                hostname = "h2.example",
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                lastBootstrapAt = System.currentTimeMillis(),
            ),
        )
        db.hostDao().insert(
            HostEntity(
                name = "offline",
                hostname = "h3.example",
                username = "u",
                keyId = keyId,
            ),
        )

        val parser = PocketshellUsageJsonParser()
        val populatedRecords = parser.parse(
            """{"provider":"codex","status":"limited","short_term":null,"long_term":{"percent_remaining":25.0,"reset_at":null},"block_reason":null,"error":null,"details":{}}""",
        )
        val fetcher = FakeFetcher(
            scripts = mapOf(
                "h1.example" to HostUsageFetch.Records(populatedRecords, Instant.now()),
                "h2.example" to HostUsageFetch.ToolMissing,
                "h3.example" to HostUsageFetch.Skipped,
            ),
        )

        val viewModel = UsageViewModel(db.hostDao(), fetcher)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1, state.hosts.size)
        assertEquals(installedHostId, state.hosts.single().hostId)
        assertEquals("codex", state.hosts.single().records.single().provider)
        assertEquals(1, state.missingToolHosts.size)
        assertEquals(missingHostId, state.missingToolHosts.single().hostId)
        assertFalse("expected refresh to have settled", state.isRefreshing)
    }

    @Test
    fun refresh_canBeReInvoked() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "agents",
                hostname = "h1.example",
                username = "u",
                keyId = keyId,
            ),
        )

        val fetcher = FakeFetcher(
            scripts = mapOf("h1.example" to HostUsageFetch.ToolMissing),
        )
        val viewModel = UsageViewModel(db.hostDao(), fetcher)
        advanceUntilIdle()
        val first = fetcher.callCount
        assertTrue("expected init refresh to fire", first >= 1)

        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(
            "expected refresh() to re-fire the fetcher, got $first → ${fetcher.callCount}",
            fetcher.callCount > first,
        )
    }

    @Test
    fun refresh_skipsHostsWithIncompatiblePocketshellCli() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        val compatibleId = db.hostDao().insert(
            HostEntity(
                name = "compatible",
                hostname = "ok.example",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = true,
                pocketshellVersionCompatible = true,
            ),
        )
        db.hostDao().insert(
            HostEntity(
                name = "mismatch",
                hostname = "mismatch.example",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = true,
                pocketshellCliVersion = "0.3.6",
                pocketshellExpectedCliVersion = "0.3.7",
                pocketshellVersionCompatible = false,
            ),
        )
        val parser = PocketshellUsageJsonParser()
        val records = parser.parse(
            """{"provider":"codex","status":"ok","short_term":null,"long_term":null,"block_reason":null,"error":null,"details":{}}""",
        )
        val fetcher = FakeFetcher(
            scripts = mapOf(
                "ok.example" to HostUsageFetch.Records(records, Instant.now()),
                "mismatch.example" to HostUsageFetch.ToolMissing,
            ),
        )

        val viewModel = UsageViewModel(db.hostDao(), fetcher)
        advanceUntilIdle()

        assertEquals(listOf("ok.example"), fetcher.fetchedHostnames)
        val state = viewModel.state.value
        assertEquals(1, state.hosts.size)
        assertEquals(compatibleId, state.hosts.single().hostId)
        assertTrue(state.missingToolHosts.isEmpty())
        assertFalse(state.isRefreshing)
    }

    @Test
    fun emptyHostList_yieldsEmptyState() = runTest {
        val fetcher = FakeFetcher(scripts = emptyMap())
        val viewModel = UsageViewModel(db.hostDao(), fetcher)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(0, state.hosts.size)
        assertEquals(0, state.missingToolHosts.size)
        assertFalse(state.isRefreshing)
        assertNotNull(state)
    }

    @Test
    fun sshHostUsageFetcher_forwardsHostUsageCommandOverrideToRemoteSource() = runTest {
        val keyFile = Files.createTempFile("pocketshell-usage-fetcher", ".key").toFile()
        keyFile.deleteOnExit()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = keyFile.absolutePath),
        )
        val host = HostEntity(
            name = "custom",
            hostname = "custom.example",
            username = "u",
            keyId = keyId,
            usageCommandOverride = "mycorp-usage --json",
        )
        val session = FakeSshSession(
            canned = mapOf(
                "mycorp-usage --json" to ExecResult(
                    stdout = """{"provider":"codex","status":"ok","short_term":null,"long_term":null,"block_reason":null,"error":null,"details":{}}""",
                    stderr = "",
                    exitCode = 0,
                ),
            ),
        )
        val fetcher = SshHostUsageFetcher(
            sshKeyDao = db.sshKeyDao(),
            remoteSource = UsageRemoteSource(),
            connector = SshHostUsageConnector { _, _ -> Result.success(session) },
        )

        val result = fetcher.fetch(host)

        assertTrue(result is HostUsageFetch.Records)
        // #490: the detail fetcher no longer pre-gates on a separate detect
        // probe. It runs the SAME single usage command the host-list summary
        // scheduler runs (here the per-host override, verbatim), so the two
        // surfaces can never disagree about "installed vs not".
        assertEquals(
            listOf("mycorp-usage --json"),
            session.recorded,
        )
        assertTrue("session should be closed after fetch", session.closed)
    }

    @Test
    fun sshHostUsageFetcher_offPathPocketshellResolvesViaWrapper_notReportedMissing() = runTest {
        // #484 / #490: a host whose `pocketshell` is in ~/.local/bin but off the
        // non-interactive SSH PATH. A bare `command -v pocketshell` fails, yet
        // the PATH-robust wrapper resolves and runs it. The detail must show
        // Records (NOT "not installed"), matching the summary.
        val keyFile = Files.createTempFile("pocketshell-offpath", ".key").toFile()
        keyFile.deleteOnExit()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = keyFile.absolutePath),
        )
        val host = HostEntity(
            name = "offpath",
            hostname = "offpath.example",
            username = "u",
            keyId = keyId,
        )
        val wrappedUsage = com.pocketshell.app.pocketshell.PocketshellCommand.wrap(
            UsageRemoteSource.DEFAULT_USAGE_ARGS,
        )
        val session = FakeSshSession(
            canned = mapOf(
                // Only the PATH-robust wrapped command succeeds; a bare probe
                // (modelled by `barePocketshellFails`) would have failed.
                wrappedUsage to ExecResult(
                    stdout = """{"provider":"claude","status":"ok","short_term":null,"long_term":null,"block_reason":null,"error":null,"details":{}}""",
                    stderr = "",
                    exitCode = 0,
                ),
            ),
            barePocketshellFails = true,
        )
        val fetcher = SshHostUsageFetcher(
            sshKeyDao = db.sshKeyDao(),
            remoteSource = UsageRemoteSource(),
            connector = SshHostUsageConnector { _, _ -> Result.success(session) },
        )

        val result = fetcher.fetch(host)

        assertTrue("off-PATH pocketshell must resolve, not read as missing", result is HostUsageFetch.Records)
        assertEquals(listOf(wrappedUsage), session.recorded)
    }

    @Test
    fun sshHostUsageFetcher_genuinelyAbsentReportsToolMissing() = runTest {
        // The wrapper resolved nothing and exited 127 -> genuinely absent.
        val keyFile = Files.createTempFile("pocketshell-absent", ".key").toFile()
        keyFile.deleteOnExit()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = keyFile.absolutePath),
        )
        val host = HostEntity(
            name = "absent",
            hostname = "absent.example",
            username = "u",
            keyId = keyId,
        )
        val wrappedUsage = com.pocketshell.app.pocketshell.PocketshellCommand.wrap(
            UsageRemoteSource.DEFAULT_USAGE_ARGS,
        )
        val session = FakeSshSession(
            canned = mapOf(wrappedUsage to ExecResult("", "", 127)),
        )
        val fetcher = SshHostUsageFetcher(
            sshKeyDao = db.sshKeyDao(),
            remoteSource = UsageRemoteSource(),
            connector = SshHostUsageConnector { _, _ -> Result.success(session) },
        )

        val result = fetcher.fetch(host)

        assertEquals(HostUsageFetch.ToolMissing, result)
    }

    private class FakeFetcher(
        private val scripts: Map<String, HostUsageFetch>,
    ) : HostUsageFetcher {
        var callCount: Int = 0
            private set
        val fetchedHostnames: MutableList<String> = mutableListOf()

        override suspend fun fetch(host: HostEntity): HostUsageFetch {
            callCount += 1
            fetchedHostnames += host.hostname
            return scripts[host.hostname] ?: HostUsageFetch.Skipped
        }
    }

    private class FakeSshSession(
        private val canned: Map<String, ExecResult>,
        private val barePocketshellFails: Boolean = false,
    ) : SshSession {
        val recorded = mutableListOf<String>()
        var closed: Boolean = false
            private set

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            // Model the #484 PATH bug: a bare, non-PATH-robust probe fails.
            if (barePocketshellFails && command == "command -v pocketshell") {
                return ExecResult("", "", 1)
            }
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
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }

    @Suppress("unused")
    private fun ensureProviderRecordIsImported(): UsageProviderRecord? = null
}
