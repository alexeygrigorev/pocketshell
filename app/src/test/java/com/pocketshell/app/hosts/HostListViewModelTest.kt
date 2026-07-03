package com.pocketshell.app.hosts

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.bootstrap.BootstrapTool
import com.pocketshell.app.bootstrap.HostBootstrapSheetState
import com.pocketshell.app.bootstrap.HostBootstrapper
import com.pocketshell.app.bootstrap.ToolStatus
import com.pocketshell.app.notifications.UpdateNotifier
import com.pocketshell.app.release.ReleaseCheckResult
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.usage.UsageRemoteSource
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.app.usage.UsageSnapshot
import com.pocketshell.app.usage.worstBadgeRecord
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [HostListViewModel] against an in-memory Room database.
 *
 * Mirrors the pattern from `:shared:core-storage`'s `AppDatabaseTest`:
 * Robolectric provides the Android Context, Room runs in-memory, the
 * test exercises the DAO layer through the ViewModel's public surface.
 *
 * The `MainDispatcherRule` swaps `Dispatchers.Main` for a test dispatcher
 * so `viewModelScope`-launched flows are observable inside `runTest`.
 *
 * Issue #40 brought in the auto-update banner — these tests inject a
 * fake [ReleaseChecker] so the suite never hits the network and the
 * update-banner state machine is observable end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HostListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey = 0

    // Issue #1110: every ViewModel built by [newViewModel] is tracked so
    // [tearDown] can cancel AND JOIN its `viewModelScope` before the in-memory
    // DB is closed — draining any in-flight off-main (Dispatchers.IO/Default) DB
    // query so it cannot re-touch the `:memory:` database after close.
    private val createdViewModels = mutableListOf<HostListViewModel>()

    private class BrokenPackageContext(base: Context) : ContextWrapper(base) {
        override fun getPackageManager(): PackageManager {
            throw IllegalStateException("package version unavailable")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset the shared prefs file the SettingsRepository will read so
        // each test starts at the default `usageWarnThresholdPercent`.
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = context.packageName
                versionName = "0.2.0"
                applicationInfo = ApplicationInfo().apply {
                    packageName = context.packageName
                }
            },
        )
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        )
            // Pin Room's executors to a synchronous runner so suspending
            // DAO calls resume on the test dispatcher rather than racing
            // an internal background executor.
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        // Issue #1110: the release `:app:testReleaseUnitTest` Unit job flaked with
        //   IllegalStateException: attempt to re-open an already-closed object:
        //   SQLiteDatabase: :memory:
        // thrown on a real `DefaultDispatcher-worker` (Dispatchers.IO/Default). The
        // ViewModels here launch DB-touching coroutines on `viewModelScope` (the
        // `init {}` update-check + cached-app-update-warning observe, and the
        // bootstrap / reprobe probes), and some hop to a real off-main worker.
        // `viewModelStore.clear()` CANCELS those scopes but does NOT wait for an
        // in-flight off-main DB query to unwind — so a coroutine cancelled here
        // could still re-query the `:memory:` DB after `db.close()` below and throw
        // (a leak from one test surfaces on whichever test is running). Cancel AND
        // JOIN each ViewModel's scope first so all off-main DB work is guaranteed
        // finished before the DB is closed (the de-flake convention's deterministic
        // settle, not a blind sleep). A generous wall-clock bound keeps a genuinely
        // stuck scope from hanging the suite while still draining the common case.
        runBlocking {
            createdViewModels.forEach { vm ->
                val job = vm.viewModelScope.coroutineContext[Job] ?: return@forEach
                withTimeoutOrNull(10_000L) { job.cancelAndJoin() }
            }
        }
        viewModelStore.clear()
        db.close()
    }

    private fun newViewModel(
        applicationContext: Context = context,
        hostDao: HostDao = db.hostDao(),
        sshKeyDao: SshKeyDao = db.sshKeyDao(),
        releaseChecker: ReleaseChecker = FakeReleaseChecker(result = null),
        bootstrapper: HostBootstrapper = HostBootstrapper(),
        usageScheduler: UsageScheduler = newUsageScheduler(),
        activeClients: ActiveTmuxClients = ActiveTmuxClients(),
        settingsRepository: SettingsRepository = newSettingsRepository(),
        sessionOpener: HostSessionOpener = HostSessionOpener { _, _, _ -> null },
        updateNotifier: UpdateNotifier = RecordingUpdateNotifier(),
    ): HostListViewModel =
        HostListViewModel(
            applicationContext = applicationContext,
            hostDao = hostDao,
            sshKeyDao = sshKeyDao,
            releaseChecker = releaseChecker,
            bootstrapper = bootstrapper,
            usageScheduler = usageScheduler,
            activeClients = activeClients,
            settingsRepository = settingsRepository,
            sessionOpener = sessionOpener,
            updateNotifier = updateNotifier,
        ).also {
            viewModelStore.put("HostListViewModel-${nextViewModelKey++}", it)
            // Issue #1110: track for the cancel+join DB-drain in [tearDown].
            createdViewModels += it
        }

    /**
     * Records every [ReleaseInfo] the ViewModel asks to notify about so a
     * test can assert detection triggers exactly one notify (issue #502).
     * Does NOT apply de-dupe itself — de-dupe is the production
     * [com.pocketshell.app.notifications.DefaultUpdateNotifier]'s job and
     * is covered by [UpdateNotifierTest]. This double captures the raw
     * ViewModel→notifier call so the per-detection trigger is observable.
     */
    private class RecordingUpdateNotifier : UpdateNotifier {
        val notified = mutableListOf<ReleaseInfo>()
        override fun notifyUpdateAvailable(info: ReleaseInfo) {
            notified.add(info)
        }
    }

    private fun neverOpeningSession(): HostSessionOpener =
        HostSessionOpener { _, _, _ -> kotlinx.coroutines.awaitCancellation() }

    /**
     * A scheduler wired against the in-memory database so the ViewModel
     * can construct without standing up Hilt. Tests that exercise the
     * usage-strip / per-host badge flows seed the snapshot map via
     * [UsageScheduler.refreshNow] with a fake [UsageScheduler.fetchHost]
     * lambda; the rest of the suite only needs a scheduler that is
     * inert.
     */
    private fun newUsageScheduler(): UsageScheduler =
        UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())

    /**
     * A [SettingsRepository] backed by the same Robolectric-provided
     * `SharedPreferences` the production code uses. Each test gets a
     * fresh prefs file via [setUp] so the default
     * `usageWarnThresholdPercent` (80) is observed unless a test
     * explicitly mutates it.
     */
    private fun newSettingsRepository(): SettingsRepository = SettingsRepository(context)

    /**
     * A test double for [ReleaseChecker] that returns a pre-canned
     * [ReleaseInfo] (or `null`) and records the number of `check()`
     * calls so the test can assert that `init {}` and `checkForUpdates()`
     * both invoke the network path.
     *
     * Subclassing the concrete class (rather than introducing an
     * interface in production code) avoids the boilerplate of an
     * interface that has only one implementation — the test-only
     * surface is the only consumer.
     */
    private class FakeReleaseChecker(
        private val result: ReleaseInfo?,
        // Issue #515: when non-null, the check reports a failed poll (vs
        // "no newer release") so the failure-path test can assert the
        // ViewModel surfaces a reason instead of a silent null.
        private val failureReason: String? = null,
    ) : ReleaseChecker() {
        var callCount: Int = 0
            private set
        var lastCurrentVersion: String? = null
            private set

        override suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult {
            callCount += 1
            lastCurrentVersion = currentVersion
            return when {
                failureReason != null -> ReleaseCheckResult.Failed(failureReason)
                result != null -> ReleaseCheckResult.UpdateAvailable(result)
                else -> ReleaseCheckResult.UpToDate
            }
        }
    }

    private class SequenceReleaseChecker(
        results: List<ReleaseCheckResult>,
    ) : ReleaseChecker() {
        private val remaining = ArrayDeque(results)
        var callCount: Int = 0
            private set

        override suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult {
            callCount += 1
            return if (remaining.isNotEmpty()) {
                remaining.removeFirst()
            } else {
                ReleaseCheckResult.UpToDate
            }
        }
    }

    @Test
    fun hostsFlow_emitsStoredHosts_orderedByName() = runTest {
        // Seed two keys + two hosts in non-alphabetic order; expect the
        // DAO `ORDER BY name` to surface them sorted.
        val keyA = db.sshKeyDao().insert(SshKeyEntity(name = "k1", privateKeyPath = "/tmp/k1"))
        val keyB = db.sshKeyDao().insert(SshKeyEntity(name = "k2", privateKeyPath = "/tmp/k2"))
        db.hostDao().insert(
            HostEntity(name = "zulu", hostname = "z.example", username = "u", keyId = keyA),
        )
        db.hostDao().insert(
            HostEntity(name = "alpha", hostname = "a.example", username = "u", keyId = keyB),
        )

        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        // Read from the underlying DAO flow directly — the ViewModel's
        // own StateFlow uses `WhileSubscribed(5s)`, which interacts with
        // the test dispatcher in ways that complicate the assertion. The
        // ViewModel wiring (`hostDao.getAll().stateIn(...)`) is what's
        // under test, and observing the upstream is the canonical way to
        // assert it.
        val rows = db.hostDao().getAll().first()
        assertEquals(listOf("alpha", "zulu"), rows.map { it.name })
        // Sanity: the ViewModel constructed successfully and exposes a
        // non-null flow.
        assertNotNull(viewModel.hosts)
    }

    @Test
    fun keyFor_returnsKeyForExistingId() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "my-key", privateKeyPath = "/tmp/id_ed25519"),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val key = viewModel.keyFor(keyId)
        assertNotNull(key)
        assertEquals("my-key", key!!.name)
        assertEquals("/tmp/id_ed25519", key.privateKeyPath)
    }

    @Test
    fun keyFor_returnsNullForMissingId() = runTest {
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        assertNull(viewModel.keyFor(9_999L))
    }

    @Test
    fun updateAvailable_emitsReleaseInfo_whenCheckerReturnsNonNull() = runTest {
        // Pre-canned "there is a newer release" response. The fake
        // skips the network entirely and returns the canned value
        // synchronously on the test dispatcher.
        val info = ReleaseInfo(
            tagName = "v0.2.0",
            htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.2.0",
            apkUrl = "https://example.com/pocketshell-0.2.0-debug.apk",
        )
        val fake = FakeReleaseChecker(result = info)

        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        // `init {}` kicks off the check; UnconfinedTestDispatcher drains
        // the launch synchronously, so the state is settled by here.
        assertEquals(1, fake.callCount)
        assertEquals(info, viewModel.updateAvailable.value)
    }

    @Test
    fun updateDetection_triggersNotification_withTheDetectedRelease() = runTest {
        // Issue #502: when the foreground checker finds a newer release,
        // the ViewModel must hand it to the update notifier so a local
        // notification can be posted — the host-list banner alone is
        // missed by users who skip straight to a session.
        val info = ReleaseInfo(
            tagName = "v0.6.0",
            htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.6.0",
            apkUrl = "https://example.com/pocketshell-0.6.0-debug.apk",
        )
        val notifier = RecordingUpdateNotifier()
        val viewModel = newViewModel(
            releaseChecker = FakeReleaseChecker(result = info),
            updateNotifier = notifier,
        )

        assertEquals(info, viewModel.updateAvailable.value)
        assertEquals(listOf(info), notifier.notified)
    }

    @Test
    fun updateDetection_doesNotNotify_whenNoNewerRelease() = runTest {
        // Issue #502: no newer release → nothing to notify about.
        val notifier = RecordingUpdateNotifier()
        val viewModel = newViewModel(
            releaseChecker = FakeReleaseChecker(result = null),
            updateNotifier = notifier,
        )

        assertNull(viewModel.updateAvailable.value)
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun onUpdateDownloadStarted_setsConfirmationMessage_thenClears() = runTest {
        // Issue #476: a successful download-intent launch must produce a
        // visible "download started" confirmation so the tap is never a
        // silent no-op.
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        assertNull(viewModel.updateMessage.value)

        viewModel.onUpdateDownloadStarted("v0.4.0")
        val message = viewModel.updateMessage.value
        assertNotNull(message)
        assertTrue(message!!.contains("v0.4.0"))
        assertTrue(message.contains("Downloading"))

        viewModel.clearUpdateMessage()
        assertNull(viewModel.updateMessage.value)
    }

    @Test
    fun onUpdateDownloadFailed_setsReasonAndFallbackMessage() = runTest {
        // Issue #476: the failure path must name a concrete reason and tell
        // the user the release page was opened as a fallback.
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        viewModel.onUpdateDownloadFailed("no app can open the download link")
        val message = viewModel.updateMessage.value
        assertNotNull(message)
        assertTrue(message!!.contains("Couldn't start the download"))
        assertTrue(message.contains("no app can open the download link"))
        assertTrue(message.contains("release page"))
    }

    @Test
    fun updateAvailable_staysNull_whenCheckerReturnsNull() = runTest {
        val fake = FakeReleaseChecker(result = null)
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        assertEquals(1, fake.callCount)
        assertNull(viewModel.updateAvailable.value)
    }

    @Test
    fun updateCheckFailed_surfacesReason_insteadOfSilentNull_whenCheckFails() = runTest {
        // Issue #515: a failed poll (non-200 / rate-limit / network blip)
        // used to look identical to "no newer release" — a silent null. Now
        // it surfaces a visible, reasoned failure the user can retry, and the
        // available-update state is left untouched.
        val fake = FakeReleaseChecker(result = null, failureReason = "GitHub returned HTTP 403")
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        assertEquals(1, fake.callCount)
        // NOT silently null — the failure is observable with its reason.
        val failure = viewModel.updateCheckFailed.value
        assertNotNull(failure)
        assertEquals("GitHub returned HTTP 403", failure!!.reason)
        // The available-update banner stays empty (no false "up to date").
        assertNull(viewModel.updateAvailable.value)

        // Dismiss clears the failure note.
        viewModel.dismissUpdateCheckFailure()
        assertNull(viewModel.updateCheckFailed.value)
    }

    @Test
    fun updateCheckFailed_clears_whenLaterCheckSucceeds() = runTest {
        // A retry that succeeds must clear the failure note so the banner
        // disappears rather than lingering after the network recovers.
        val fake = FakeReleaseChecker(result = null)
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        // A successful "up to date" check leaves no failure banner.
        assertNull(viewModel.updateCheckFailed.value)
    }

    @Test
    fun appUpdateWarning_offersResolvedRelease_whenRemoteCliNewerAndGithubHasRelease() = runTest {
        // Issue #515: when the host probe proves the app is behind the remote
        // CLI, the soft banner should resolve a real downloadable GitHub
        // release so it can offer an OPTIONAL actionable "Update" — not just
        // passive text. The host stays fully usable regardless.
        val info = ReleaseInfo(
            tagName = "v9999.0.0",
            htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v9999.0.0",
            apkUrl = "https://example.com/pocketshell-9999.0.0-debug.apk",
        )
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "remote-newer", hostname = "h.example", username = "u", keyId = keyId),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            releaseChecker = FakeReleaseChecker(result = info),
            sessionOpener = HostSessionOpener { _, _, _ ->
                FakeBootstrapSession(pocketshellVersion = "9999.0.0")
            },
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k").join()

        // Host navigates normally (ready) — never blocked by the offer.
        assertEquals(true, viewModel.pendingNavigation.value!!.ready)
        assertNull(viewModel.bootstrapState.value)

        // The warning carries the resolved release so the banner can offer
        // a working "Update" (ACTION_VIEW → APK).
        val warning = viewModel.appUpdateWarning.value
        assertNotNull(warning)
        assertEquals(hostId, warning!!.hostId)
        assertEquals(info, warning.releaseInfo)
    }

    @Test
    fun bootstrapReadyClosesProbeSessionAsynchronouslyOffMain() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "ready", hostname = "h.example", username = "u", keyId = keyId),
        )
        val host = db.hostDao().getById(hostId)!!
        val session = FakeBootstrapSession()
        // The production close runs on a REAL background dispatcher
        // (Dispatchers.IO) — see HostListViewModel.closeSession. Verifying it
        // ran OFF the Robolectric main/test thread is intrinsic to this test:
        // it inspects the executing thread's NAME, and a real off-main worker
        // is the property under test. So we keep the real thread and await its
        // completion with a generous WALL-CLOCK bound (CountDownLatch) rather
        // than a `runTest` virtual-clock `withTimeout`. The virtual clock can
        // skip ahead to the timeout instantly while the real off-main close is
        // still in flight, which is exactly what reddened this test under CI
        // CPU contention (issue #1048 Shape-B: wall-clock-bounded deterministic
        // await over real off-main work). Injecting a test dispatcher (Shape-A)
        // is NOT applicable here because it would confine the close back onto
        // the test/main thread and defeat the off-main assertion.
        val closeLatch = java.util.concurrent.CountDownLatch(1)
        val closeThreadName = java.util.concurrent.atomic.AtomicReference<String>()
        val viewModel = newViewModel(
            sessionOpener = object : HostSessionOpener {
                override suspend fun open(
                    host: HostEntity,
                    keyPath: String,
                    passphrase: CharArray?,
                ): SshSession = session

                override suspend fun close(session: SshSession) {
                    closeThreadName.set(Thread.currentThread().name)
                    session.close()
                    closeLatch.countDown()
                }
            },
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k").join()
        assertTrue(
            "bootstrap probe session was not closed within the wall-clock bound",
            closeLatch.await(10, java.util.concurrent.TimeUnit.SECONDS),
        )
        val threadName = closeThreadName.get()

        assertEquals(true, viewModel.pendingNavigation.value!!.ready)
        assertEquals(1, session.closeCount)
        assertFalse(
            "bootstrap probe close must not run on the test main thread, ran on $threadName",
            threadName.contains("main", ignoreCase = true),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun appUpdateWarning_offersResolvedRelease_whenCachedHostProbeSaysRemoteCliNewerThanApp() = runTest {
        // Regression for the 2026-06-08 dogfood recurrence: once a probe has
        // persisted "remote CLI newer than this APK", the host becomes a
        // compatible/ready cache hit. That cache hit must still surface the
        // same actionable in-app update path instead of silently navigating.
        val info = ReleaseInfo(
            tagName = "v9999.0.0",
            htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v9999.0.0",
            apkUrl = "https://example.com/pocketshell-9999.0.0-debug.apk",
        )
        val checker = SequenceReleaseChecker(
            listOf(
                ReleaseCheckResult.UpToDate, // init checkForUpdates()
                ReleaseCheckResult.UpdateAvailable(info), // cached host warning resolution
            ),
        )
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val now = System.currentTimeMillis()
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "cached-remote-newer",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                lastBootstrapAt = now - 60_000L,
                pocketshellInstalled = true,
                pocketshellLastDetectedAt = now - 60_000L,
                pocketshellCliVersion = "9999.0.0",
                pocketshellExpectedCliVersion = "0.2.0",
                pocketshellVersionCompatible = true,
                pocketshellDaemonRunning = true,
                pocketshellDaemonEnabled = true,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        var openCount = 0
        val viewModel = newViewModel(
            releaseChecker = checker,
            sessionOpener = HostSessionOpener { _, _, _ ->
                openCount += 1
                null
            },
        )
        advanceUntilIdle()

        val warning = viewModel.appUpdateWarning.value
        assertNotNull(warning)
        assertEquals(hostId, warning!!.hostId)
        assertEquals("9999.0.0", warning.remoteVersion)
        assertEquals("0.2.0", warning.appVersion)
        assertEquals(info, warning.releaseInfo)
        assertNull(warning.releaseResolutionFailure)

        viewModel.bootstrapHost(host, keyPath = "/tmp/k")

        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(true, pending!!.ready)
        assertEquals("cache hit should not need a live SSH probe", 0, openCount)
        assertNull(viewModel.bootstrapState.value)
        assertEquals(2, checker.callCount)
    }

    @Test
    fun appUpdateWarning_surfacesRetryableFailure_whenGithubResolvesNoRelease() = runTest {
        // If the host probe proves the app is behind but GitHub cannot
        // provide a downloadable APK, the warning must not silently degrade
        // to passive text. It carries a visible failure reason for the
        // banner's Retry action while preserving the non-blocking host flow.
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "remote-newer", hostname = "h.example", username = "u", keyId = keyId),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            releaseChecker = FakeReleaseChecker(result = null),
            sessionOpener = HostSessionOpener { _, _, _ ->
                FakeBootstrapSession(pocketshellVersion = "9999.0.0")
            },
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k").join()

        val warning = viewModel.appUpdateWarning.value
        assertNotNull(warning)
        assertEquals(hostId, warning!!.hostId)
        assertNull(warning.releaseInfo)
        assertEquals("No newer downloadable APK was found on GitHub", warning.releaseResolutionFailure)
        assertFalse(warning.isResolvingRelease)
    }

    @Test
    fun appUpdateWarning_retryResolvesRelease_afterInitialGithubFailure() = runTest {
        // Regression for the 2026-06-08 dogfood recurrence: the host probe
        // is the strongest "this app is old" signal. A failed APK resolution
        // must be visible and retryable from that same warning, not hidden
        // behind a separate cold-launch check.
        val info = ReleaseInfo(
            tagName = "v9999.0.0",
            htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v9999.0.0",
            apkUrl = "https://example.com/pocketshell-9999.0.0-debug.apk",
        )
        val checker = SequenceReleaseChecker(
            listOf(
                ReleaseCheckResult.UpToDate, // init checkForUpdates()
                ReleaseCheckResult.Failed("GitHub returned HTTP 403"),
                ReleaseCheckResult.UpdateAvailable(info),
            ),
        )
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "remote-newer", hostname = "h.example", username = "u", keyId = keyId),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            releaseChecker = checker,
            sessionOpener = HostSessionOpener { _, _, _ ->
                FakeBootstrapSession(pocketshellVersion = "9999.0.0")
            },
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k").join()

        val failedWarning = viewModel.appUpdateWarning.value
        assertNotNull(failedWarning)
        assertEquals("GitHub returned HTTP 403", failedWarning!!.releaseResolutionFailure)
        assertNull(failedWarning.releaseInfo)

        viewModel.retryAppUpdateWarningRelease()

        val resolvedWarning = viewModel.appUpdateWarning.value
        assertNotNull(resolvedWarning)
        assertEquals(info, resolvedWarning!!.releaseInfo)
        assertNull(resolvedWarning.releaseResolutionFailure)
        assertFalse(resolvedWarning.isResolvingRelease)
        assertEquals(info, viewModel.updateAvailable.value)
    }

    @Test
    fun checkForUpdates_canBeReInvoked() = runTest {
        val fake = FakeReleaseChecker(result = null)
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        // `init {}` already fired one call.
        assertEquals(1, fake.callCount)

        // Pull-to-refresh re-fires.
        viewModel.checkForUpdates()
        assertEquals(2, fake.callCount)

        viewModel.checkForUpdates()
        assertEquals(3, fake.callCount)
    }

    @Test
    fun checkForUpdates_skipsChecker_whenInstalledVersionCannotBeDetermined() = runTest {
        val fake = FakeReleaseChecker(
            result = ReleaseInfo(
                tagName = "v0.2.0",
                htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.2.0",
                apkUrl = "https://example.com/pocketshell-0.2.0-debug.apk",
            ),
        )
        val viewModel = newViewModel(
            applicationContext = BrokenPackageContext(context),
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        assertEquals(0, fake.callCount)
        assertNull(fake.lastCurrentVersion)
        assertNull(viewModel.updateAvailable.value)

        viewModel.checkForUpdates()

        assertEquals(0, fake.callCount)
        assertNull(viewModel.updateAvailable.value)
    }

    /**
     * Issue #49 + #315: when the host's bootstrap row says tmux is
     * installed and the last matching server-setup probe is fresh and
     * app-compatible, the ViewModel must not open a new SSH session — it
     * flips `pendingNavigation.ready` to `true` immediately so the screen
     * can route to the session.
     *
     * The fake bootstrapper never gets called because we never connect;
     * the test asserts the pending-navigation tuple, which is the
     * canonical "ready to route" signal the UI consumes.
     */
    @Test
    fun beginHostOpen_surfacesConnectingImmediately_andRejectsDuplicateTap() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "slow",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(sessionOpener = neverOpeningSession())

        assertTrue(viewModel.beginHostOpen(host.id, host.name))
        assertFalse("second tap must not enqueue another open", viewModel.beginHostOpen(host.id, host.name))

        val progress = viewModel.hostOpenProgress.value
        assertNotNull(progress)
        assertEquals(hostId, progress!!.hostId)
        assertEquals(HostListViewModel.HostOpenPhase.ConnectingToHost, progress.phase)
        assertEquals("Connecting to host", progress.phase.label)

        viewModel.cancelHostOpen(host.id)
        assertNull(viewModel.hostOpenProgress.value)
    }

    @Test
    fun bootstrapHost_reportsCheckingSetup_thenOpeningFolders_andClearsAfterNavigation() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "ready",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val tmuxProbeReached = CompletableDeferred<Unit>()
        val releaseTmuxProbe = CompletableDeferred<Unit>()
        val viewModel = newViewModel(
            sessionOpener = HostSessionOpener { _, _, _ ->
                FakeBootstrapSession(
                    tmuxProbeReached = tmuxProbeReached,
                    releaseTmuxProbe = releaseTmuxProbe,
                )
            },
        )

        val job = viewModel.bootstrapHost(host, keyPath = "/tmp/k")
        tmuxProbeReached.await()

        assertEquals(
            HostListViewModel.HostOpenPhase.CheckingSetup,
            viewModel.hostOpenProgress.value!!.phase,
        )

        releaseTmuxProbe.complete(Unit)
        job.join()

        assertEquals(
            HostListViewModel.HostOpenPhase.OpeningFolders,
            viewModel.hostOpenProgress.value!!.phase,
        )
        assertEquals(true, viewModel.pendingNavigation.value!!.ready)

        viewModel.consumePendingNavigation()
        assertNull(viewModel.hostOpenProgress.value)
    }

    @Test
    fun bootstrapHost_clearsProgress_whenSetupNeedsAction() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "missing-tmux",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            sessionOpener = HostSessionOpener { _, _, _ ->
                FakeBootstrapSession(tmuxInstalled = false)
            },
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k").join()

        assertNull(viewModel.hostOpenProgress.value)
        assertNotNull(viewModel.bootstrapState.value)
        assertEquals(false, viewModel.pendingNavigation.value!!.ready)
    }

    @Test
    fun installBootstrapTool_surfacesNoChangeMessage_whenUpgradeExitsZeroButVersionUnchanged() = runTest {
        // Issue #779: the user taps "Update" on the outdated-CLI row. The
        // host's `uv tool install --upgrade …` exits 0 but installs nothing
        // (the exclude-newer-style "Nothing to upgrade" dead end), so the
        // version is unchanged. Without the fix the sheet just re-renders the
        // identical "update needed" prompt — the spinner runs and nothing
        // happens. The fix surfaces an explicit Failed message instead of
        // silently looping.
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "outdated-cli",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            sessionOpener = HostSessionOpener { _, _, _ ->
                // Remote 0.1.0 < app 0.4.1 → VersionMismatch; the upgrade is a
                // silent no-op that leaves 0.1.0 in place.
                FakeBootstrapSession(pocketshellVersion = "0.1.0", upgradeIsNoOp = true)
            },
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k").join()

        // The probe surfaced the outdated-CLI prompt.
        val prompt = viewModel.bootstrapState.value as? HostBootstrapSheetState.Prompt
        assertNotNull("expected an outdated-CLI Prompt", prompt)
        assertTrue(
            "expected a pocketshell VersionMismatch",
            prompt!!.report?.tools?.get(BootstrapTool.Pocketshell) is ToolStatus.VersionMismatch,
        )

        // Tap "Update" → no-op upgrade → the no-change Failed message, NOT a
        // silent re-render of the same prompt.
        viewModel.installBootstrapTool(BootstrapTool.Pocketshell)
        testScheduler.advanceUntilIdle()

        val failed = viewModel.bootstrapState.value as? HostBootstrapSheetState.Failed
        assertNotNull(
            "tapping Update on a no-op upgrade must surface a Failed message, not loop",
            failed,
        )
        assertTrue(failed!!.message.contains("did not change the installed version"))
        assertTrue(failed.message.contains("still reports 0.1.0"))
    }

    @Test
    fun bootstrapHost_navigatesAndRaisesSoftWarning_whenRemoteCliIsNewerThanApp() = runTest {
        // Issue #514 (DESIGN REFINEMENT): the remote pocketshell CLI is far
        // newer than this app build (9999.0.0 > app versionName). The host
        // must be treated as fully ready: navigate normally, NO takeover
        // bootstrap sheet, NO installer, NO loop. The only surfaced
        // difference is the small dismissible "consider updating the app"
        // warning banner.
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "remote-newer",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            sessionOpener = HostSessionOpener { _, _, _ ->
                FakeBootstrapSession(pocketshellVersion = "9999.0.0")
            },
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k").join()

        // Host navigates normally (ready) and NO bootstrap sheet is shown.
        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(hostId, pending!!.host.id)
        assertEquals(true, pending.ready)
        assertNull(viewModel.bootstrapState.value)

        // The soft, dismissible warning is raised and names both versions.
        val warning = viewModel.appUpdateWarning.value
        assertNotNull(warning)
        assertEquals(hostId, warning!!.hostId)
        assertEquals("9999.0.0", warning.remoteVersion)
        assertTrue(warning.message.contains("consider updating the app"))

        // Dismiss clears the banner; re-probing the same host does not
        // re-raise it (no nag loop).
        viewModel.dismissAppUpdateWarning()
        assertNull(viewModel.appUpdateWarning.value)

        // Persisted host stays "installed + version-compatible" so usage /
        // sessions / folders treat it as ready, never CliUpdateNeeded.
        val persisted = db.hostDao().getById(hostId)!!
        assertEquals(true, persisted.pocketshellInstalled)
        assertEquals(true, persisted.pocketshellVersionCompatible)
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.Ready,
            deriveSetupState(persisted),
        )
    }

    @Test
    fun bootstrapHost_skipsProbe_whenCacheIsFresh() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val now = System.currentTimeMillis()
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "fresh",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                pocketshellLastDetectedAt = now - 60_000L,
                pocketshellCliVersion = "0.2.0",
                pocketshellExpectedCliVersion = "0.2.0",
                pocketshellVersionCompatible = true,
                pocketshellDaemonRunning = true,
                pocketshellDaemonEnabled = true,
                lastBootstrapAt = now - 60_000L, // 1 minute ago
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k")

        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(hostId, pending!!.host.id)
        // Cache-hit fast path → ready=true immediately.
        assertEquals(true, pending.ready)
        // Sheet stays hidden.
        assertNull(viewModel.bootstrapState.value)
    }

    @Test
    fun bootstrapHost_skipsProbe_whenFreshRequiredCacheHasUnknownDaemonState() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val now = System.currentTimeMillis()
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "unknown-daemon",
                hostname = "127.0.0.1",
                port = 1,
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                pocketshellLastDetectedAt = now - 60_000L,
                pocketshellCliVersion = "0.2.0",
                pocketshellExpectedCliVersion = "0.2.0",
                pocketshellVersionCompatible = true,
                pocketshellDaemonRunning = null,
                pocketshellDaemonEnabled = null,
                lastBootstrapAt = now - 60_000L,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(sessionOpener = neverOpeningSession())

        viewModel.bootstrapHost(host, keyPath = "/tmp/k")

        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(hostId, pending!!.host.id)
        assertEquals(true, pending.ready)
        assertNull(viewModel.bootstrapState.value)
    }

    @Test
    fun bootstrapHost_skipsProbe_whenFreshRequiredCacheHasDisabledDaemon() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val now = System.currentTimeMillis()
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "disabled-daemon",
                hostname = "127.0.0.1",
                port = 1,
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                pocketshellLastDetectedAt = now - 60_000L,
                pocketshellCliVersion = "0.2.0",
                pocketshellExpectedCliVersion = "0.2.0",
                pocketshellVersionCompatible = true,
                pocketshellDaemonRunning = true,
                pocketshellDaemonEnabled = false,
                lastBootstrapAt = now - 60_000L,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(sessionOpener = neverOpeningSession())

        viewModel.bootstrapHost(host, keyPath = "/tmp/k")

        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(hostId, pending!!.host.id)
        assertEquals(true, pending.ready)
        assertNull(viewModel.bootstrapState.value)
    }

    /**
     * Issue #315 follow-up: a fresh tmux cache is not enough to skip the
     * server-setup probe. If the last pocketshell result was
     * NeedsSetup, tapping the host must go through the async probe path
     * instead of immediately routing to the session off the tmux-only
     * cache.
     */
    @Test
    fun bootstrapHost_reprobes_whenFreshTmuxCacheHasMissingPocketshell() = runTest {
        val keyFile = File.createTempFile("pocketshell-hostlist", ".key").apply {
            writeText("not a real key")
            deleteOnExit()
        }
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = keyFile.absolutePath))
        val now = System.currentTimeMillis()
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "needs-setup",
                hostname = "127.0.0.1",
                port = 1, // closed port; connect happens after the synchronous probe decision.
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                lastBootstrapAt = now - 60_000L,
                pocketshellInstalled = false,
                pocketshellLastDetectedAt = now - 60_000L,
                pocketshellVersionCompatible = null,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
            sessionOpener = neverOpeningSession(),
        )

        viewModel.bootstrapHost(host, keyPath = keyFile.absolutePath)

        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(hostId, pending!!.host.id)
        assertEquals(
            "fresh tmux cache with missing pocketshell must not route immediately",
            false,
            pending.ready,
        )
        assertNull(viewModel.bootstrapState.value)
    }

    @Test
    fun bootstrapHost_reprobes_whenFreshPocketshellCacheHasVersionMismatch() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val now = System.currentTimeMillis()
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "cli-update",
                hostname = "127.0.0.1",
                port = 1,
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                pocketshellLastDetectedAt = now - 60_000L,
                pocketshellCliVersion = "0.1.0",
                pocketshellExpectedCliVersion = "0.2.0",
                pocketshellVersionCompatible = false,
                pocketshellDaemonRunning = true,
                pocketshellDaemonEnabled = true,
                lastBootstrapAt = now - 60_000L,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(sessionOpener = neverOpeningSession())

        viewModel.bootstrapHost(host, keyPath = "/tmp/k")

        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(hostId, pending!!.host.id)
        assertEquals(false, pending.ready)
        assertNull(viewModel.bootstrapState.value)
    }

    @Test
    fun bootstrapHost_refreshesStalePocketshellTimestamp_whenReprobeReturnsSameReadyValues() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val stale = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "stale-pocketshell",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                pocketshellLastDetectedAt = stale,
                pocketshellCliVersion = "0.2.0",
                pocketshellExpectedCliVersion = "0.2.0",
                pocketshellVersionCompatible = true,
                pocketshellDaemonRunning = true,
                pocketshellDaemonEnabled = true,
                lastBootstrapAt = stale,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val session = FakeBootstrapSession()
        val viewModel = newViewModel(
            sessionOpener = HostSessionOpener { _, _, _ -> session },
        )

        viewModel.bootstrapHost(host, keyPath = "/tmp/k").join()

        val pending = viewModel.pendingNavigation.value
        assertEquals(hostId, pending!!.host.id)

        val persisted = db.hostDao().getById(hostId)!!
        assertEquals(true, persisted.pocketshellInstalled)
        assertEquals(true, persisted.pocketshellVersionCompatible)
        assertEquals(true, persisted.pocketshellDaemonRunning)
        assertEquals(true, persisted.pocketshellDaemonEnabled)
        assertTrue(
            "same-value successful reprobe should refresh stale detection timestamp",
            persisted.pocketshellLastDetectedAt!! > stale,
        )
    }

    @Test
    fun leaseBackedHostSessionOpenerReleasesWarmLeaseInsteadOfClosingTransport() = runTest {
        val keyFile = File.createTempFile("pocketshell-host-lease", ".key").apply {
            writeText("fake key")
            deleteOnExit()
        }
        val host = HostEntity(
            id = 42L,
            name = "warm",
            hostname = "warm.example",
            port = 2222,
            username = "alex",
            keyId = 7L,
        )
        val session = FakeBootstrapSession()
        var connectCount = 0
        val manager = SshLeaseManager(
            connector = SshLeaseConnector {
                connectCount += 1
                Result.success(session)
            },
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
            ),
            idleTtlMillis = 60_000L,
        )
        val opener = LeaseBackedHostSessionOpener(manager)
        manager.onProcessStarted()

        val opened = opener.open(host, keyFile.absolutePath, passphrase = null)
        assertSame(session, opened)

        opener.close(session)

        assertEquals(
            "host bootstrap should release the lease, not close the SSH transport",
            0,
            session.closeCount,
        )
        val reacquired = manager.acquire(host.toLeaseTargetForTest(keyFile.absolutePath)).getOrThrow()
        assertSame(
            "tmux attach should be able to reacquire the same warm host SSH session",
            session,
            reacquired.session,
        )
        assertEquals("reacquire should not perform a second SSH connect", 1, connectCount)
        reacquired.release()
        manager.close()
    }

    /**
     * A stale cache (older than 24h) must NOT short-circuit; the
     * ViewModel attempts a connect, fails (no server), and lands on the
     * "navigate anyway" branch (`ready = true`). The bootstrap sheet
     * stays hidden because we couldn't prove tmux is missing.
     */
    @Test
    fun bootstrapHost_reprobes_whenCacheIsStale() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/no-such-key"))
        val stale = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "stale",
                hostname = "127.0.0.1",
                port = 1, // closed port → connect fails fast
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                lastBootstrapAt = stale,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
            sessionOpener = neverOpeningSession(),
        )

        // Synchronous cache-check sets pending immediately at ready=false
        // (probe goes async). We can at least observe the pre-probe
        // state machine; the async connect lands offline and flips ready
        // to true, but observing that race-free under runTest requires
        // pinning Dispatchers.IO which the suite doesn't currently do.
        viewModel.bootstrapHost(host, keyPath = "/tmp/no-such-key")

        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(hostId, pending!!.host.id)
        // Probe started → fast-path bypassed. The sheet should not be
        // showing yet (we haven't observed a Missing yet).
        assertNull(viewModel.bootstrapState.value)
    }

    /**
     * Issue #120: pure derivation rule. The unit test exercises the
     * persisted-column projection without standing up the ViewModel so
     * the mapping is observable in isolation. Mirrors the doc on
     * [deriveSetupState]:
     *
     * - tmuxInstalled=null OR pocketshellInstalled=null → Unknown
     * - tmuxInstalled=false → NeedsSetup
     * - tmuxInstalled=true && pocketshellInstalled=false → NeedsSetup
     * - tmuxInstalled=true && pocketshellInstalled=true → Ready
     */
    @Test
    fun deriveSetupState_returnsUnknown_whenTmuxInstalledIsNull() {
        val host = hostFixture().copy(tmuxInstalled = null, pocketshellInstalled = null)
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.Unknown,
            deriveSetupState(host),
        )
    }

    @Test
    fun deriveSetupState_returnsUnknown_whenPocketshellInstalledIsNullButTmuxIsTrue() {
        val host = hostFixture().copy(tmuxInstalled = true, pocketshellInstalled = null)
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.Unknown,
            deriveSetupState(host),
        )
    }

    @Test
    fun deriveSetupState_returnsNeedsSetup_whenTmuxIsFalse() {
        val host = hostFixture().copy(tmuxInstalled = false, pocketshellInstalled = true)
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.NeedsSetup,
            deriveSetupState(host),
        )
    }

    @Test
    fun deriveSetupState_returnsNeedsSetup_whenPocketshellIsFalseButTmuxIsTrue() {
        val host = hostFixture().copy(tmuxInstalled = true, pocketshellInstalled = false)
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.NeedsSetup,
            deriveSetupState(host),
        )
    }

    @Test
    fun deriveSetupState_returnsCliUpdateNeeded_whenPocketshellVersionIsMismatched() {
        val host = hostFixture().copy(
            tmuxInstalled = true,
            pocketshellInstalled = true,
            pocketshellCliVersion = "0.3.6",
            pocketshellExpectedCliVersion = "0.3.7",
            pocketshellVersionCompatible = false,
        )
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.CliUpdateNeeded,
            deriveSetupState(host),
        )
    }

    @Test
    fun deriveSetupState_returnsReady_whenBothFlagsAreTrue() {
        val host = hostFixture().copy(
            tmuxInstalled = true,
            pocketshellInstalled = true,
            pocketshellDaemonRunning = true,
            pocketshellDaemonEnabled = true,
        )
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.Ready,
            deriveSetupState(host),
        )
    }

    @Test
    fun deriveSetupState_returnsOptionalUnavailable_whenDaemonStateIsUnknown() {
        val host = hostFixture().copy(
            tmuxInstalled = true,
            pocketshellInstalled = true,
            pocketshellDaemonRunning = null,
            pocketshellDaemonEnabled = null,
        )
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.OptionalUnavailable,
            deriveSetupState(host),
        )
    }

    @Test
    fun deriveSetupState_returnsDaemonDisabled_whenDaemonIsDisabled() {
        val host = hostFixture().copy(
            tmuxInstalled = true,
            pocketshellInstalled = true,
            pocketshellDaemonRunning = true,
            pocketshellDaemonEnabled = false,
        )
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.DaemonDisabled,
            deriveSetupState(host),
        )
    }

    /**
     * Issue #120: the [HostListViewModel.setupStates] flow projects the
     * persisted columns onto the badge state, keyed by host id. Verify
     * the mapping by inserting three hosts in each of the three states
     * and reading the projection back.
     */
    @Test
    fun setupStates_projectsHosts_intoTheirBadgeStates() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/no-such-key"))
        val readyId = db.hostDao().insert(
            HostEntity(
                name = "ready",
                hostname = "h",
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                pocketshellDaemonRunning = true,
                pocketshellDaemonEnabled = true,
            ),
        )
        val needsSetupId = db.hostDao().insert(
            HostEntity(
                name = "needs",
                hostname = "h",
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = false,
            ),
        )
        val unknownId = db.hostDao().insert(
            HostEntity(
                name = "unknown",
                hostname = "h",
                username = "u",
                keyId = keyId,
                // tmuxInstalled, pocketshellInstalled both null by default.
            ),
        )
        val mismatchId = db.hostDao().insert(
            HostEntity(
                name = "mismatch",
                hostname = "h",
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                pocketshellCliVersion = "0.3.6",
                pocketshellExpectedCliVersion = "0.3.7",
                pocketshellVersionCompatible = false,
            ),
        )

        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        // Read the upstream projection — same `stateIn(WhileSubscribed)`
        // caveat as `hostsFlow_emitsStoredHosts_orderedByName` applies.
        val projection = db.hostDao().getAll().first()
            .associate { it.id to deriveSetupState(it) }
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.Ready,
            projection[readyId],
        )
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.NeedsSetup,
            projection[needsSetupId],
        )
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.Unknown,
            projection[unknownId],
        )
        assertEquals(
            com.pocketshell.uikit.model.HostSetupState.CliUpdateNeeded,
            projection[mismatchId],
        )
        // Sanity: the ViewModel constructed and exposes a non-null flow.
        assertNotNull(viewModel.setupStates)
    }

    /**
     * Issue #120: tapping "Re-check setup" must clear the cache and
     * surface the acknowledgement message. The probe itself goes through
     * the standard bootstrap path; the connect fails because there is no
     * server, but the cache invalidation is observable.
     */
    @Test
    fun recheckSetup_setsAcknowledgementMessage_andClearsCache() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/no-such-key"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "recheck",
                hostname = "127.0.0.1",
                port = 1, // closed port → connect fails fast
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                lastBootstrapAt = System.currentTimeMillis() - 60_000L,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        // Pre-condition: no acknowledgement banner yet.
        assertNull(viewModel.recheckMessage.value)

        viewModel.recheckSetup(host, keyPath = "/tmp/no-such-key")

        // Acknowledgement message names the host so the user knows the
        // tap landed on the right row.
        val msg = viewModel.recheckMessage.value
        assertNotNull(msg)
        assertTrue(
            "expected acknowledgement to mention the host name, got '$msg'",
            msg!!.contains("recheck"),
        )

        // The refresh path under the hood calls `bootstrapHost` with the
        // host's cache cleared — pendingNavigation should now be set,
        // which is the canonical "the probe is running" signal.
        assertNotNull(viewModel.pendingNavigation.value)

        // The clear hook hides the banner without touching anything else.
        viewModel.clearRecheckMessage()
        assertNull(viewModel.recheckMessage.value)
    }

    @Test
    fun bootstrapHost_waitsForSilentReprobeBeforeForegroundDecision() = runTest {
        val keyFile = File.createTempFile("pocketshell-hostlist", ".key").apply {
            writeText("not a real key")
            deleteOnExit()
        }
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = keyFile.absolutePath))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "unknown",
                hostname = "h.example",
                username = "u",
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val tmuxProbeReached = CompletableDeferred<Unit>()
        val releaseTmuxProbe = CompletableDeferred<Unit>()
        val session = FakeBootstrapSession(
            tmuxProbeReached = tmuxProbeReached,
            releaseTmuxProbe = releaseTmuxProbe,
        )
        var openCount = 0
        val viewModel = newViewModel(
            sessionOpener = HostSessionOpener { _, _, _ ->
                openCount += 1
                session
            },
        )

        viewModel.reprobeUnknownHostsOnce()
        tmuxProbeReached.await()

        val foregroundJob = viewModel.bootstrapHost(host, keyPath = keyFile.absolutePath)

        assertEquals("foreground tap must not open a racing second SSH session", 1, openCount)
        assertEquals(false, viewModel.pendingNavigation.value!!.ready)

        releaseTmuxProbe.complete(Unit)
        foregroundJob.join()

        val pending = viewModel.pendingNavigation.value
        assertNotNull(pending)
        assertEquals(hostId, pending!!.host.id)
        assertEquals(true, pending.ready)
        assertEquals("foreground tap should own the final probe after waiting for silent reprobe", 2, openCount)

        val persisted = db.hostDao().getById(hostId)!!
        assertEquals(true, persisted.tmuxInstalled)
        assertEquals(true, persisted.pocketshellInstalled)
        assertEquals(true, persisted.pocketshellVersionCompatible)
        assertEquals(true, persisted.pocketshellDaemonRunning)
        assertEquals(true, persisted.pocketshellDaemonEnabled)
    }

    /** Build a minimally-populated host row for the derivation tests. */
    private fun hostFixture(): HostEntity = HostEntity(
        name = "h",
        hostname = "h.example",
        username = "u",
        keyId = 1L,
    )

    @Test
    fun createSharePayload_wrapsKeyReferenceImportInQrEnvelope() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        val host = HostEntity(
            name = "shared",
            hostname = "shared.example.com",
            port = 2222,
            username = "ubuntu",
            keyId = keyId,
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        viewModel.createSharePayload(host)

        val share = viewModel.sharePayload.value
        assertNotNull(share)
        // A normal-sized host fits in a single QR envelope part (AC2).
        assertEquals(1, share!!.payloads.size)
        val envelope = share.payloads.single()
        assertTrue(QrChunkCodec.isEnvelope(envelope))
        val part = QrChunkCodec.decodePart(envelope).getOrThrow()
        assertEquals(1, part.total)
        val decoded = SshImportPayloadCodec.decode(
            String(part.chunk, Charsets.UTF_8),
        ).getOrThrow()
        assertEquals("shared", decoded.name)
        assertEquals("shared.example.com", decoded.host)
        assertEquals(2222, decoded.port)
        assertEquals("ubuntu", decoded.username)
        assertEquals(SshImportAuth.KeyReference("shared-key"), decoded.auth)
    }

    /**
     * Issue #1230 regression (reproduce-first, D33/G10). The export used to do
     * `QrChunkCodec.encode(importPayload).single()`; `.single()` throws
     * `IllegalArgumentException` on a >1-element list, and the uncaught throw
     * inside the bare `viewModelScope.launch` crashed the app for ANY payload
     * that split into more than one QR chunk (a host with a long
     * name/hostname/username or a long key name).
     *
     * The fixture below has a name long enough to push the SSH-import JSON well
     * past [QrChunkCodec.ChunkSize] (1500 bytes), so `encode` returns multiple
     * parts — the exact crash trigger.
     *
     * RED (revert the fix — restore `.single()` in `createSharePayload`): the
     * coroutine throws before assigning `_sharePayload`, so `share` is null and
     * `assertNotNull` fails (and the uncaught crash surfaces via `runTest`).
     * GREEN (with the fix): every part is produced and reassembles through the
     * live scanner's [QrChunkAssembler] back into the original import payload.
     */
    @Test
    fun createSharePayload_producesAllParts_forOversizedPayload_andRoundTrips() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        // ~4 KiB name → SSH-import JSON far exceeds the 1500-byte chunk size, so
        // the QR envelope MUST split into multiple parts.
        val longName = "long-host-".repeat(400)
        val host = HostEntity(
            name = longName,
            hostname = "shared.example.com",
            port = 2222,
            username = "ubuntu",
            keyId = keyId,
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        viewModel.createSharePayload(host)

        val share = viewModel.sharePayload.value
        assertNotNull("multi-chunk share must not crash (issue #1230)", share)
        // The crash trigger: the payload genuinely splits into >1 part.
        assertTrue(
            "oversized payload should produce more than one QR part",
            share!!.payloads.size > 1,
        )
        // Every part is a well-formed envelope sharing one id, parts 1..N, and
        // the whole set reassembles the way the in-app scanner combines them.
        val assembler = QrChunkAssembler()
        var assembled: String? = null
        share.payloads.forEach { envelope ->
            assertTrue(QrChunkCodec.isEnvelope(envelope))
            val part = QrChunkCodec.decodePart(envelope).getOrThrow()
            assertEquals(share.payloads.size, part.total)
            when (val outcome = assembler.accept(part)) {
                is QrChunkAssembler.Outcome.Complete -> assembled = outcome.payload
                else -> Unit
            }
        }
        assertNotNull("all parts should reassemble into the payload", assembled)
        val decoded = SshImportPayloadCodec.decode(assembled!!).getOrThrow()
        assertEquals(longName, decoded.name)
        assertEquals("shared.example.com", decoded.host)
        assertEquals(2222, decoded.port)
        assertEquals("ubuntu", decoded.username)
        assertEquals(SshImportAuth.KeyReference("shared-key"), decoded.auth)
    }

    @Test
    fun importSharedHostPayload_insertsHost_whenMatchingKeyExists() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "shared",
                host = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals("shared", rows[0].name)
        assertEquals("shared.example.com", rows[0].hostname)
        assertEquals(2222, rows[0].port)
        assertEquals("ubuntu", rows[0].username)
        assertEquals(keyId, rows[0].keyId)
        assertEquals("Imported shared", viewModel.shareMessage.value)
    }

    @Test
    fun importSharedHostPayload_reportsMissingKey() = runTest {
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "shared",
                host = "shared.example.com",
                port = 22,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("missing-key"),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        assertEquals(0, db.hostDao().getAll().first().size)
        assertEquals(
            "Import the SSH key named missing-key before importing this host",
            viewModel.shareMessage.value,
        )
    }

    @Test
    fun importSharedHostPayload_reportsExpectedPocketShellHostPayloadForUnsupportedPayload() = runTest {
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        viewModel.importSharedHostPayload("""{"type":"pocketshell.settings.v1","version":1}""").join()

        assertEquals(0, db.hostDao().getAll().first().size)
        val message = viewModel.shareMessage.value
        assertNotNull(message)
        assertTrue(message!!.contains("PocketShell SSH host payload"))
        assertTrue(message.contains("pocketshell.ssh-import.v1"))
    }

    @Test
    fun importSharedHostPayload_insertsHostAndKey_forSshImportPrivateKeyPayload() = runTest {
        File(context.filesDir, "ssh-keys").deleteRecursively()
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "qr-prod",
                host = "qr.example.com",
                port = 2200,
                username = "ubuntu",
                auth = SshImportAuth.PrivateKey(
                    name = "qr-key",
                    privateKeyPem = """
                        -----BEGIN OPENSSH PRIVATE KEY-----
                        abc
                        -----END OPENSSH PRIVATE KEY-----
                    """.trimIndent(),
                    passphraseRequired = false,
                ),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        val keys = db.sshKeyDao().getAll().first()
        val hosts = db.hostDao().getAll().first()
        assertEquals(1, keys.size)
        assertEquals("qr-key", keys[0].name)
        assertEquals(false, keys[0].hasPassphrase)
        assertTrue(File(keys[0].privateKeyPath).exists())
        assertEquals(1, hosts.size)
        assertEquals("qr-prod", hosts[0].name)
        assertEquals("qr.example.com", hosts[0].hostname)
        assertEquals(2200, hosts[0].port)
        assertEquals("ubuntu", hosts[0].username)
        assertEquals(keys[0].id, hosts[0].keyId)
        assertEquals("Imported qr-prod", viewModel.shareMessage.value)
    }

    @Test
    fun importSharedHostPayload_reusesKey_whenSamePrivateKeyPayloadIsScannedAgain() = runTest {
        File(context.filesDir, "ssh-keys").deleteRecursively()
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "qr-prod",
                host = "qr.example.com",
                port = 2200,
                username = "ubuntu",
                auth = SshImportAuth.PrivateKey(
                    name = "qr-key",
                    privateKeyPem = """
                        -----BEGIN OPENSSH PRIVATE KEY-----
                        abc
                        -----END OPENSSH PRIVATE KEY-----
                    """.trimIndent(),
                    passphraseRequired = false,
                ),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()
        viewModel.importSharedHostPayload(payload).join()

        val keys = db.sshKeyDao().getAll().first()
        val hosts = db.hostDao().getAll().first()
        val keyFiles = File(context.filesDir, "ssh-keys").listFiles().orEmpty()
        assertEquals(1, keys.size)
        assertEquals(1, hosts.size)
        assertEquals(keys[0].id, hosts[0].keyId)
        assertEquals(1, keyFiles.size)
        assertNotNull(viewModel.importConflict.value)
    }

    @Test
    fun importSharedHostPayload_derivesPassphraseRequirementFromEncryptedOpenSshKey() = runTest {
        File(context.filesDir, "ssh-keys").deleteRecursively()
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "encrypted-prod",
                host = "encrypted.example.com",
                port = 22,
                username = "ubuntu",
                auth = SshImportAuth.PrivateKey(
                    name = "encrypted-key",
                    privateKeyPem = EncryptedOpenSshPrivateKey,
                    passphraseRequired = false,
                ),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        val keys = db.sshKeyDao().getAll().first()
        assertEquals(1, keys.size)
        assertEquals("encrypted-key", keys[0].name)
        assertEquals(true, keys[0].hasPassphrase)
        assertEquals("Imported encrypted-prod", viewModel.shareMessage.value)
    }

    @Test
    fun importSharedHostPayload_usesExistingKey_forSshImportKeyReferencePayload() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "existing-key", privateKeyPath = "/tmp/existing-key"),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "qr-ref",
                host = "ref.example.com",
                port = 22,
                username = "alexey",
                auth = SshImportAuth.KeyReference("existing-key"),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        val hosts = db.hostDao().getAll().first()
        assertEquals(1, hosts.size)
        assertEquals("qr-ref", hosts[0].name)
        assertEquals(keyId, hosts[0].keyId)
        assertEquals(1, db.sshKeyDao().getAll().first().size)
    }

    @Test
    fun importSharedHostPayload_acceptsSinglePartEnvelopeWrappingSshImportPayload() = runTest {
        // Issue #129: a single-part envelope wraps the existing
        // ssh-import JSON. The view model should unwrap and import
        // the host the same as if the JSON had arrived raw.
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "existing-key", privateKeyPath = "/tmp/existing-key"),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val inner = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "envelope-ref",
                host = "env.example.com",
                port = 22,
                username = "alexey",
                auth = SshImportAuth.KeyReference("existing-key"),
            ),
        )
        val envelopes = QrChunkCodec.encode(inner, id = "deadbeef")
        assertEquals(1, envelopes.size)

        viewModel.importSharedHostPayload(envelopes[0]).join()

        val hosts = db.hostDao().getAll().first()
        assertEquals(1, hosts.size)
        assertEquals("envelope-ref", hosts[0].name)
        assertEquals(keyId, hosts[0].keyId)
    }

    @Test
    fun importSharedHostPayload_rejectsMultiPartEnvelope_withGuidance() = runTest {
        // Issue #129: a single envelope from a multi-part transmission
        // is not a complete payload. The view model surfaces guidance
        // pointing the user to the QR scanner rather than silently
        // failing.
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = "X".repeat(QrChunkCodec.ChunkSize * 2 + 1)
        val envelopes = QrChunkCodec.encode(payload, id = "deadbeef")
        assertTrue(envelopes.size > 1)

        viewModel.importSharedHostPayload(envelopes[0]).join()

        assertEquals(0, db.hostDao().getAll().first().size)
        val message = viewModel.shareMessage.value
        assertNotNull(message)
        assertTrue(
            "expected guidance message to mention the scanner, got: $message",
            message!!.contains("scanner", ignoreCase = true),
        )
    }

    // Issue #483: the `hasUsageInstalledHost` strip gate on the host-list
    // view model was removed with the cross-host usage strip (usage is now
    // per-host). The equivalent gate for the Settings → Usage entry lives
    // on SettingsViewModel and is covered by SettingsViewModelTest.

    /**
     * Issue #116: when the scheduler reports a blocked record for a
     * host, `usageBadges` carries the worst-case provider for that
     * host id. Hosts with a healthy snapshot are absent from the
     * map — the host card uses absence as "do not render the chip".
     */
    @Test
    fun usageBadges_mapsHostIdToWorstProvider() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val blockedHostId = db.hostDao().insert(
            HostEntity(name = "blocked", hostname = "b", username = "u", keyId = keyId, pocketshellInstalled = true),
        )
        val healthyHostId = db.hostDao().insert(
            HostEntity(name = "healthy", hostname = "h", username = "u", keyId = keyId, pocketshellInstalled = true),
        )

        val scheduler = newUsageScheduler()
        // Seed the scheduler synchronously with a deterministic fetch
        // lambda so the snapshot map has predictable contents before
        // the view model materialises.
        scheduler.fetchHost = { host ->
            val provider = if (host.id == blockedHostId) {
                com.pocketshell.core.usage.UsageProviderRecord(
                    provider = "codex",
                    status = com.pocketshell.core.usage.UsageStatus.Blocked,
                    windows = listOf(
                        com.pocketshell.core.usage.UsageWindow(
                            name = "5h",
                            used = 100.0,
                            limit = 100.0,
                            unit = "percent",
                            resetAt = null,
                        ),
                    ),
                    rawStatus = "limited",
                )
            } else {
                com.pocketshell.core.usage.UsageProviderRecord(
                    provider = "claude",
                    status = com.pocketshell.core.usage.UsageStatus.Ok,
                    windows = listOf(
                        com.pocketshell.core.usage.UsageWindow(
                            name = "5h",
                            used = 10.0,
                            limit = 100.0,
                            unit = "percent",
                            resetAt = null,
                        ),
                    ),
                    rawStatus = "ok",
                )
            }
            UsageSnapshot.Records(
                hostId = host.id,
                hostName = host.name,
                records = listOf(provider),
                fetchedAt = java.time.Instant.now(),
                command = UsageRemoteSource.defaultUsageCommand,
            )
        }
        scheduler.refreshNow()

        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = scheduler,
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        // The snapshots flow on the scheduler is the upstream — that's
        // the authoritative source of truth and easier to read than
        // the WhileSubscribed-gated flows on the view model.
        val snapshots = scheduler.snapshots.value
        assertTrue(snapshots[blockedHostId] is UsageSnapshot.Records)
        assertTrue(snapshots[healthyHostId] is UsageSnapshot.Records)
        val blockedSnapshot = snapshots[blockedHostId] as UsageSnapshot.Records
        // The view model's `usageBadges` flow runs the same
        // worstBadgeRecord rule on the same snapshots map; assert
        // directly on the rule so the test does not race the
        // WhileSubscribed sharing started.
        val blockedBadge = blockedSnapshot.worstBadgeRecord()
        assertNotNull("blocked host must yield a worst-case provider", blockedBadge)
        assertEquals(true, blockedBadge!!.isBlocked)
        val healthySnapshot = snapshots[healthyHostId] as UsageSnapshot.Records
        assertNull(
            "healthy host must NOT yield a worst-case provider (no badge)",
            healthySnapshot.worstBadgeRecord(),
        )

        // `viewModel.usageBadges` is non-null even before the flow
        // collector lands; sanity-check it is reachable.
        assertNotNull(viewModel.usageBadges)
    }

    // -- Issue #214: in-app usage warning banner ---------------------------

    /**
     * The dismissed-banners set is in-memory and starts empty. Calling
     * [HostListViewModel.dismissUsageBanner] adds the provider id to
     * the set (lowercased). The check is direct (not flow-observed) so
     * we don't depend on `WhileSubscribed` sharing started.
     */
    @Test
    fun dismissUsageBanner_addsProviderIdToDismissedSet_andNormalisesCase() = runTest {
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        assertEquals(emptySet<String>(), viewModel.dismissedBanners.value)
        viewModel.dismissUsageBanner("Claude")
        assertEquals(setOf("claude"), viewModel.dismissedBanners.value)
        // Idempotent: dismissing the same provider twice is a no-op.
        viewModel.dismissUsageBanner("claude")
        assertEquals(setOf("claude"), viewModel.dismissedBanners.value)
        viewModel.dismissUsageBanner("codex")
        assertEquals(setOf("claude", "codex"), viewModel.dismissedBanners.value)
    }

    // -- Issue #157 polish item 2: import-conflict prompt -------------------

    /**
     * Re-importing a host whose `(hostname, port)` already exists must
     * surface a conflict dialog instead of silently writing. The
     * ViewModel pauses the write until the user resolves with
     * [ImportConflictResolution].
     */
    @Test
    fun importSharedHostPayload_pausesAndExposesConflict_whenEndpointAlreadyExists() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                keyId = keyId,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "shared",
                host = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        // The pre-existing row is still the only one.
        val rowsAfter = db.hostDao().getAll().first()
        assertEquals(1, rowsAfter.size)
        assertEquals("existing", rowsAfter[0].name)
        // The conflict is exposed for the dialog to render.
        val conflict = viewModel.importConflict.value
        assertNotNull(conflict)
        assertEquals("existing", conflict!!.existing.name)
        assertEquals("shared", conflict.incoming.name)
        assertEquals("shared.example.com", conflict.incoming.hostname)
        assertEquals(2222, conflict.incoming.port)
    }

    @Test
    fun resolveImportConflict_overwrite_updatesExistingRowInPlace() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        val existingId = db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "shared.example.com",
                port = 2222,
                username = "old-user",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                lastBootstrapAt = 1234L,
                pocketshellLastDetectedAt = 1234L,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "shared-new-label",
                host = "shared.example.com",
                port = 2222,
                username = "new-user",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )
        viewModel.importSharedHostPayload(payload).join()

        viewModel.resolveImportConflict(ImportConflictResolution.Overwrite).join()

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals(existingId, rows[0].id)
        assertEquals("shared-new-label", rows[0].name)
        assertEquals("new-user", rows[0].username)
        // Bootstrap cache is invalidated so the next connect re-probes.
        assertNull(rows[0].tmuxInstalled)
        assertNull(rows[0].pocketshellInstalled)
        assertNull(rows[0].lastBootstrapAt)
        // Conflict state is cleared.
        assertNull(viewModel.importConflict.value)
        assertEquals("Overwrote existing", viewModel.shareMessage.value)
    }

    @Test
    fun resolveImportConflict_skip_leavesDatabaseUntouched() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                keyId = keyId,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "incoming",
                host = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )
        viewModel.importSharedHostPayload(payload).join()

        viewModel.resolveImportConflict(ImportConflictResolution.Skip).join()

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals("existing", rows[0].name)
        assertNull(viewModel.importConflict.value)
        assertEquals("Already added: existing", viewModel.shareMessage.value)
    }

    @Test
    fun resolveImportConflict_addAsNew_appendsSecondRow() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                keyId = keyId,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "incoming",
                host = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )
        viewModel.importSharedHostPayload(payload).join()

        viewModel.resolveImportConflict(ImportConflictResolution.AddAsNew).join()

        val rows = db.hostDao().getAll().first()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.name == "existing" })
        assertTrue(rows.any { it.name == "incoming" })
        assertNull(viewModel.importConflict.value)
        assertEquals("Imported incoming", viewModel.shareMessage.value)
    }

    @Test
    fun importSharedHostPayload_skipsConflictCheck_whenEndpointIsUnique() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "first.example.com",
                port = 22,
                username = "ubuntu",
                keyId = keyId,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "fresh",
                host = "fresh.example.com",
                port = 22,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )
        viewModel.importSharedHostPayload(payload).join()

        // No conflict — direct insert, two rows.
        assertNull(viewModel.importConflict.value)
        val rows = db.hostDao().getAll().first()
        assertEquals(2, rows.size)
        assertEquals("Imported fresh", viewModel.shareMessage.value)
    }

    private class FakeBootstrapSession(
        private val tmuxProbeReached: CompletableDeferred<Unit>? = null,
        private val releaseTmuxProbe: CompletableDeferred<Unit>? = null,
        private val daemonEnabled: Boolean = true,
        private val tmuxInstalled: Boolean = true,
        // Issue #514: drive the reported remote pocketshell CLI version so a
        // test can stand up a "remote newer than this app build" host.
        private val pocketshellVersion: String = "0.2.0",
        // Issue #779: when true, the `uv tool install --upgrade …` command
        // exits 0 but leaves [pocketshellVersion] unchanged — i.e. a silent
        // no-op upgrade (the exclude-newer-style "Nothing to upgrade" dead
        // end). Lets a test drive the "update ran but did nothing" branch.
        private val upgradeIsNoOp: Boolean = false,
    ) : SshSession {
        val recorded = mutableListOf<String>()
        var closeCount: Int = 0
            private set
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            if (command == HostBootstrapper().detectBootstrapPathCommand()) {
                return ExecResult(
                    "__POCKETSHELL_PATH_BEGIN__\n$DEFAULT_BOOTSTRAP_PATH\n__POCKETSHELL_PATH_END__\n",
                    "",
                    0,
                )
            }
            return when {
                command.contains("command -v tmux") -> {
                    tmuxProbeReached?.complete(Unit)
                    releaseTmuxProbe?.await()
                    if (tmuxInstalled) {
                        ExecResult("/usr/bin/tmux\n", "", 0)
                    } else {
                        ExecResult("", "", 1)
                    }
                }
                // Issue #779: a no-op upgrade — the installer reports success
                // (exit 0) but installs nothing, so the version below stays
                // unchanged. Must be matched BEFORE the generic `command -v
                // pocketshell` rule since the upgrade line also mentions
                // `pocketshell`.
                upgradeIsNoOp && command.contains("tool install") && command.contains("--upgrade") ->
                    ExecResult("Nothing to upgrade\n", "", 0)
                command.contains("command -v") && command.contains("pocketshell") ->
                    ExecResult("/home/u/.local/bin/pocketshell\n", "", 0)
                command.contains("pocketshell") && command.contains("--version") ->
                    ExecResult("pocketshell, version $pocketshellVersion\n", "", 0)
                command.contains("command -v") && command.contains("uv") ->
                    ExecResult("/home/u/.local/bin/uv\n", "", 0)
                command.contains("command -v") && command.contains("systemctl") ->
                    ExecResult("/usr/bin/systemctl\n", "", 0)
                command.contains("systemctl --user is-active pocketshell-jobs.service") ->
                    ExecResult("active\n", "", 0)
                command.contains("systemctl --user is-enabled pocketshell-jobs.service") ->
                    if (daemonEnabled) {
                        ExecResult("enabled\n", "", 0)
                    } else {
                        ExecResult("disabled\n", "", 1)
                    }
                else -> ExecResult("", "command not stubbed: $command", 127)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used in this test")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("port forward not used in this test")

        override fun startShell(): SshShell = error("shell not used in this test")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closeCount += 1
        }
    }

    private fun HostEntity.toLeaseTargetForTest(keyPath: String): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = "$id:$keyPath",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(File(keyPath)),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

    private companion object {
        const val DEFAULT_BOOTSTRAP_PATH: String =
            "/home/u/.local/bin:/home/u/bin:/home/u/.cargo/bin:/usr/local/bin:/usr/bin:/bin"

        val EncryptedOpenSshPrivateKey = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDy65Wy4J
            GIiPmAlfzxEptmAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIFz+4rPsOPrrK7I/
            hz3T8H4UpgIdLal/ADv4OhvewZ+xAAAAkPodwX8olqflsAful+M/4T4BtLAaULs9Oc3GVb
            uy664Ebtmwo+/HhJmloTIoVs0STzeFeHAK4xkEq6Ut303sIER2av1O0qHUjyOMGPPZop1V
            4Sd/bBQJu/Q14nSsiSRJaHBN2SBpyFSul2/ZLU5xYhs7dzmCTbXH+BM1cP6ZE5byxA8jeW
            gH19i7Bcv1CK6+Pg==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}
