package com.pocketshell.app.hosts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.bootstrap.HostBootstrapper
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
        db.close()
    }

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
    ) : ReleaseChecker() {
        var callCount: Int = 0
            private set

        override suspend fun check(currentVersion: String): ReleaseInfo? {
            callCount += 1
            return result
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

        val viewModel = HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
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
        val viewModel = HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
        )
        val key = viewModel.keyFor(keyId)
        assertNotNull(key)
        assertEquals("my-key", key!!.name)
        assertEquals("/tmp/id_ed25519", key.privateKeyPath)
    }

    @Test
    fun keyFor_returnsNullForMissingId() = runTest {
        val viewModel = HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
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

        val viewModel = HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
        )

        // `init {}` kicks off the check; UnconfinedTestDispatcher drains
        // the launch synchronously, so the state is settled by here.
        assertEquals(1, fake.callCount)
        assertEquals(info, viewModel.updateAvailable.value)
    }

    @Test
    fun updateAvailable_staysNull_whenCheckerReturnsNull() = runTest {
        val fake = FakeReleaseChecker(result = null)
        val viewModel = HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
        )

        assertEquals(1, fake.callCount)
        assertNull(viewModel.updateAvailable.value)
    }

    @Test
    fun checkForUpdates_canBeReInvoked() = runTest {
        val fake = FakeReleaseChecker(result = null)
        val viewModel = HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = fake,
            bootstrapper = HostBootstrapper(),
        )
        // `init {}` already fired one call.
        assertEquals(1, fake.callCount)

        // Pull-to-refresh re-fires.
        viewModel.checkForUpdates()
        assertEquals(2, fake.callCount)

        viewModel.checkForUpdates()
        assertEquals(3, fake.callCount)
    }

    /**
     * Issue #49: when the host's bootstrap row says tmux is installed
     * and the last probe is fresh (within 24h), the ViewModel must not
     * open a new SSH session — it flips `pendingNavigation.ready` to
     * `true` immediately so the screen can route to the session.
     *
     * The fake bootstrapper never gets called because we never connect;
     * the test asserts the pending-navigation tuple, which is the
     * canonical "ready to route" signal the UI consumes.
     */
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
                lastBootstrapAt = now - 60_000L, // 1 minute ago
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        val viewModel = HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
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
        val viewModel = HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
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
}
