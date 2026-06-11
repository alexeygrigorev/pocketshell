package com.pocketshell.app.usage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.usage.PocketshellUsageJsonParser
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageThresholdState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

        val viewModel = testViewModel(fetcher, testScheduler)
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
        val viewModel = testViewModel(fetcher, testScheduler)
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
    fun refresh_doesNotHideHostFlaggedRemoteNewerCompatibleFalse() = runTest {
        // Issue #525 regression: a host that #514 considers usable (remote
        // pocketshell CLI NEWER than the app's expected version) can still
        // carry a STALE `pocketshellVersionCompatible = false` written before
        // #514 replaced exact-`==` with semver semantics. The Usage panel must
        // NOT drop such a host to "0 hosts" — it must attempt a usage fetch and
        // render its records. The flag is unreliable, so it is not a valid sole
        // exclusion criterion for the panel.
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        val staleHostId = db.hostDao().insert(
            HostEntity(
                name = "hetzner",
                hostname = "stale.example",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = true,
                // Remote CLI is NEWER than the app expected (0.3.23 > 0.3.22),
                // which #514 treats as usable, but a stale `false` lingers.
                pocketshellCliVersion = "0.3.23",
                pocketshellExpectedCliVersion = "0.3.22",
                pocketshellVersionCompatible = false,
            ),
        )
        val parser = PocketshellUsageJsonParser()
        val records = parser.parse(
            """{"provider":"codex","status":"ok","short_term":null,"long_term":null,"block_reason":null,"error":null,"details":{}}""",
        )
        val fetcher = FakeFetcher(
            scripts = mapOf(
                "stale.example" to HostUsageFetch.Records(records, Instant.now()),
            ),
        )

        val viewModel = testViewModel(fetcher, testScheduler)
        advanceUntilIdle()

        // The stale-flagged host must have been fetched, not silently filtered.
        assertEquals(listOf("stale.example"), fetcher.fetchedHostnames)
        val state = viewModel.state.value
        assertEquals(
            "remote-newer host with stale incompat flag must appear in Usage",
            1,
            state.hosts.size,
        )
        assertEquals(staleHostId, state.hosts.single().hostId)
        assertEquals("codex", state.hosts.single().records.single().provider)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun emptyHostList_yieldsEmptyState() = runTest {
        val fetcher = FakeFetcher(scripts = emptyMap())
        val viewModel = testViewModel(fetcher, testScheduler)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(0, state.hosts.size)
        assertEquals(0, state.missingToolHosts.size)
        assertFalse(state.isRefreshing)
        assertNotNull(state)
    }

    @Test
    fun refresh_rendersCachedReadingInstantly_thenSwapsToLive() = runTest {
        // Issue #689: cached-first render. The cached records appear (with
        // their captured_at provenance + showingCached=true) BEFORE the live
        // fetch resolves, then the live records swap in and clear provenance.
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        db.hostDao().insert(
            HostEntity(name = "agents", hostname = "swr.example", username = "u", keyId = keyId),
        )
        val parser = PocketshellUsageJsonParser()
        val cachedRecords = parser.parse(
            """{"provider":"codex","status":"ok","short_term":{"percent_remaining":60.0},"long_term":null,"block_reason":null,"error":null,"details":{}}""",
        )
        val liveRecords = parser.parse(
            """{"provider":"codex","status":"ok","short_term":{"percent_remaining":42.0},"long_term":null,"block_reason":null,"error":null,"details":{}}""",
        )
        val capturedAt = Instant.parse("2026-06-11T09:00:00Z")
        val release = CountDownLatch(1)
        val fetcher = object : HostUsageFetcher {
            override suspend fun fetch(host: HostEntity): HostUsageFetch {
                release.await()
                return HostUsageFetch.Records(liveRecords, Instant.now())
            }

            override suspend fun fetchCached(host: HostEntity): HostCachedUsage =
                HostCachedUsage.Hit(cachedRecords, capturedAt)
        }

        val executor = Executors.newSingleThreadExecutor()
        val viewModel = UsageViewModel(
            hostDao = db.hostDao(),
            fetcher = fetcher,
            usageScheduler = null,
            refreshDispatcher = executor.asCoroutineDispatcher(),
            refreshTimeoutMillis = UsageViewModel.DEFAULT_REFRESH_TIMEOUT_MILLIS,
        )

        // Cached value visible while the live fetch is still blocked.
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.hosts.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        val cachedState = viewModel.state.value
        assertTrue("cached records should render before live resolves", cachedState.hosts.isNotEmpty())
        assertTrue("showingCached should be set during cached-first phase", cachedState.showingCached)
        assertTrue("should still be refreshing while live is pending", cachedState.isRefreshing)
        assertEquals(capturedAt, cachedState.hosts.single().capturedAt)
        // percent_remaining 60.0 → used = 100 - 60 = 40.0
        assertEquals(40.0, cachedState.hosts.single().records.single().windows.single().used, 0.001)

        // Release the live fetch; fresh records swap in, provenance clears.
        release.countDown()
        val freshDeadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.isRefreshing && System.currentTimeMillis() < freshDeadline) {
            Thread.sleep(10)
        }
        val freshState = viewModel.state.value
        assertFalse("refresh should settle after live resolves", freshState.isRefreshing)
        assertFalse(freshState.showingCached)
        // percent_remaining 42.0 → used = 100 - 42 = 58.0
        assertEquals(58.0, freshState.hosts.single().records.single().windows.single().used, 0.001)
        assertEquals("live data clears cached provenance", null, freshState.hosts.single().capturedAt)
        executor.shutdownNow()
    }

    @Test
    fun refresh_keepsCachedValue_whenLiveRefreshFails() = runTest {
        // Issue #689: a live-refresh failure must NOT blank a populated
        // cached reading. The cached records stay, flagged stale, and the
        // screen shows the honest "showing cached" provenance — no failure
        // panel for that host.
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        db.hostDao().insert(
            HostEntity(name = "agents", hostname = "fail.example", username = "u", keyId = keyId),
        )
        val cachedRecords = PocketshellUsageJsonParser().parse(
            """{"provider":"claude","status":"ok","short_term":{"percent_remaining":55.0},"long_term":null,"block_reason":null,"error":null,"details":{}}""",
        )
        val capturedAt = Instant.parse("2026-06-11T08:30:00Z")
        val fetcher = FakeFetcher(
            scripts = mapOf("fail.example" to HostUsageFetch.Failed("HTTP Error 500")),
            cachedScripts = mapOf("fail.example" to HostCachedUsage.Hit(cachedRecords, capturedAt)),
        )

        val viewModel = testViewModel(fetcher, testScheduler)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isRefreshing)
        assertTrue("cached value kept on live failure", state.hosts.isNotEmpty())
        assertEquals(0, state.failedHosts.size)
        assertTrue("stale flag set after live failure", state.refreshFailedShowingCached)
        assertEquals(capturedAt, state.hosts.single().staleSince)
        assertEquals(null, state.hosts.single().capturedAt)
    }

    @Test
    fun refresh_surfacesRecentResetBannerFromDetectedEvents() = runTest {
        // Issue #690: a recently-detected reset (read via fetchResetEvents)
        // populates the in-app "limits just reset" banner — the non-push
        // fallback shown on app open.
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        db.hostDao().insert(
            HostEntity(name = "agents", hostname = "reset.example", username = "u", keyId = keyId),
        )
        val records = PocketshellUsageJsonParser().parse(
            """{"provider":"codex","status":"ok","short_term":{"percent_remaining":100.0},"long_term":null,"block_reason":null,"error":null,"details":{}}""",
        )
        val recentReset = UsageResetEvent(
            provider = "codex",
            window = "short_term",
            detectedAt = Instant.now().minusSeconds(600),
            statedResetAt = Instant.now().plusSeconds(900),
            newResetAt = Instant.now().plusSeconds(3600),
            timing = "early",
            minutesEarly = 15,
            resetKey = "codex|short_term|recent",
        )
        val fetcher = FakeFetcher(
            scripts = mapOf("reset.example" to HostUsageFetch.Records(records, Instant.now())),
            resetScripts = mapOf("reset.example" to listOf(recentReset)),
        )

        val viewModel = testViewModel(fetcher, testScheduler)
        advanceUntilIdle()

        val banner = viewModel.state.value.resetBanner
        assertNotNull("recent reset should populate the banner", banner)
        assertEquals("codex|short_term|recent", banner!!.resetKey)
        assertTrue(banner.title.startsWith("Codex limits reset"))
    }

    @Test
    fun refresh_noResetEvents_leavesBannerNull() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        db.hostDao().insert(
            HostEntity(name = "agents", hostname = "noreset.example", username = "u", keyId = keyId),
        )
        val records = PocketshellUsageJsonParser().parse(
            """{"provider":"codex","status":"ok","short_term":{"percent_remaining":50.0},"long_term":null,"block_reason":null,"error":null,"details":{}}""",
        )
        val fetcher = FakeFetcher(
            scripts = mapOf("noreset.example" to HostUsageFetch.Records(records, Instant.now())),
        )

        val viewModel = testViewModel(fetcher, testScheduler)
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.resetBanner)
    }

    @Test
    fun exhaustedCodexRecord_isNotDroppedAndReachesExceededState() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "agents",
                hostname = "codex.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val records = PocketshellUsageJsonParser().parse(
            """{"provider":"codex","status":"exhausted",
              "short_term":null,"long_term":null,
              "block_reason":"Codex quota exhausted",
              "error":null,"details":{}}""".trimIndent(),
        )
        val fetcher = FakeFetcher(
            scripts = mapOf("codex.example" to HostUsageFetch.Records(records, Instant.now())),
        )

        val viewModel = testViewModel(fetcher, testScheduler)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse("refresh should settle for exhausted Codex", state.isRefreshing)
        assertEquals(1, state.hosts.size)
        assertEquals(hostId, state.hosts.single().hostId)
        val codex = state.hosts.single().records.single()
        assertEquals("codex", codex.provider)
        assertEquals(UsageThresholdState.Exceeded, codex.thresholdState())
        assertEquals("Exceeded", statusLabel(codex))
    }

    @Test
    fun refresh_surfacesUsageFetchFailureInsteadOfSilentlySkippingHost() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "agents",
                hostname = "claude401.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val fetcher = FakeFetcher(
            scripts = mapOf(
                "claude401.example" to HostUsageFetch.Failed("HTTP Error 401: Unauthorized"),
            ),
        )

        val viewModel = testViewModel(fetcher, testScheduler)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isRefreshing)
        assertEquals(emptyList<UsageHostSnapshot>(), state.hosts)
        assertEquals(1, state.failedHosts.size)
        assertEquals(hostId, state.failedHosts.single().hostId)
        assertEquals("agents", state.failedHosts.single().hostName)
        assertEquals("HTTP Error 401: Unauthorized", state.failedHosts.single().reason)
    }

    @Test
    fun refreshTimeoutClearsRefreshingInsteadOfLeavingPanelStuck() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "hung",
                hostname = "hung.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val fetcher = HangingFetcher()

        val viewModel = testViewModel(
            fetcher = fetcher,
            testScheduler = testScheduler,
            refreshTimeoutMillis = 1_000L,
        )
        advanceTimeBy(1_001L)
        advanceUntilIdle()

        assertTrue("hanging fetcher must have been invoked", fetcher.called)
        assertFalse("timed-out refresh must release the Usage panel", viewModel.state.value.isRefreshing)
        assertTrue(viewModel.state.value.hosts.isEmpty())
    }

    @Test
    fun refreshTimeoutClearsRefreshingWhenFetcherBlocksThread() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null/missing"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "blocked",
                hostname = "blocked.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val viewModel = UsageViewModel(
                hostDao = db.hostDao(),
                fetcher = BlockingThreadFetcher(started, release, finished),
                usageScheduler = null,
                refreshDispatcher = dispatcher,
                refreshTimeoutMillis = 1_000L,
            )

            assertTrue("blocking fetcher should start", started.await(1, TimeUnit.SECONDS))
            advanceTimeBy(1_001L)
            advanceUntilIdle()

            assertFalse(
                "non-cooperative SSH-style fetch must not leave the Usage panel refreshing",
                viewModel.state.value.isRefreshing,
            )
        } finally {
            release.countDown()
            assertTrue("blocking fetcher should drain after test release", finished.await(1, TimeUnit.SECONDS))
            dispatcher.close()
        }
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

    @Test
    fun sshHostUsageFetcher_nonJsonUsageFailureIsReturnedForUi() = runTest {
        val keyFile = Files.createTempFile("pocketshell-usage-failed", ".key").toFile()
        keyFile.deleteOnExit()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = keyFile.absolutePath),
        )
        val host = HostEntity(
            name = "claude401",
            hostname = "claude401.example",
            username = "u",
            keyId = keyId,
        )
        val wrappedUsage = com.pocketshell.app.pocketshell.PocketshellCommand.wrap(
            UsageRemoteSource.DEFAULT_USAGE_ARGS,
        )
        val session = FakeSshSession(
            canned = mapOf(
                wrappedUsage to ExecResult(
                    stdout = "",
                    stderr = "HTTP Error 401: Unauthorized",
                    exitCode = 1,
                ),
            ),
        )
        val fetcher = SshHostUsageFetcher(
            sshKeyDao = db.sshKeyDao(),
            remoteSource = UsageRemoteSource(),
            connector = SshHostUsageConnector { _, _ -> Result.success(session) },
        )

        val result = fetcher.fetch(host)

        assertTrue(result is HostUsageFetch.Failed)
        assertEquals("HTTP Error 401: Unauthorized", (result as HostUsageFetch.Failed).reason)
        assertTrue("session should be closed after failure", session.closed)
    }

    private class FakeFetcher(
        private val scripts: Map<String, HostUsageFetch>,
        private val cachedScripts: Map<String, HostCachedUsage> = emptyMap(),
        private val resetScripts: Map<String, List<UsageResetEvent>> = emptyMap(),
    ) : HostUsageFetcher {
        var callCount: Int = 0
            private set
        var cachedCallCount: Int = 0
            private set
        val fetchedHostnames: MutableList<String> = mutableListOf()

        override suspend fun fetch(host: HostEntity): HostUsageFetch {
            callCount += 1
            fetchedHostnames += host.hostname
            return scripts[host.hostname] ?: HostUsageFetch.Skipped
        }

        override suspend fun fetchCached(host: HostEntity): HostCachedUsage {
            cachedCallCount += 1
            return cachedScripts[host.hostname] ?: HostCachedUsage.None
        }

        override suspend fun fetchResetEvents(host: HostEntity): List<UsageResetEvent> =
            resetScripts[host.hostname] ?: emptyList()
    }

    private class HangingFetcher : HostUsageFetcher {
        var called: Boolean = false
            private set

        override suspend fun fetch(host: HostEntity): HostUsageFetch {
            called = true
            awaitCancellation()
        }
    }

    private class BlockingThreadFetcher(
        private val started: CountDownLatch,
        private val release: CountDownLatch,
        private val finished: CountDownLatch,
    ) : HostUsageFetcher {
        override suspend fun fetch(host: HostEntity): HostUsageFetch {
            started.countDown()
            try {
                release.await()
                return HostUsageFetch.Skipped
            } finally {
                finished.countDown()
            }
        }
    }

    private fun testViewModel(
        fetcher: HostUsageFetcher,
        testScheduler: TestCoroutineScheduler,
        refreshTimeoutMillis: Long = UsageViewModel.DEFAULT_REFRESH_TIMEOUT_MILLIS,
    ): UsageViewModel = UsageViewModel(
        hostDao = db.hostDao(),
        fetcher = fetcher,
        usageScheduler = null,
        refreshDispatcher = UnconfinedTestDispatcher(testScheduler),
        refreshTimeoutMillis = refreshTimeoutMillis,
    )

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
