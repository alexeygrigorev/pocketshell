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
                UsageRemoteSource.DETECT_POCKETSHELL_COMMAND to ExecResult("/usr/bin/pocketshell\n", "", 0),
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
        assertEquals(
            listOf(UsageRemoteSource.DETECT_POCKETSHELL_COMMAND, "mycorp-usage --json"),
            session.recorded,
        )
        assertTrue("session should be closed after fetch", session.closed)
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
    ) : SshSession {
        val recorded = mutableListOf<String>()
        var closed: Boolean = false
            private set

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
