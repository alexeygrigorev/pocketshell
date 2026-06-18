package com.pocketshell.app.portfwd

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ForwardingResumeScheduler] (issue #752, REOPENED).
 *
 * Root cause being fixed: the persisted `host.enabled` flag was written by
 * the panel but never read back to auto-start forwarding ([HostDao.getEnabled]
 * had zero callers), so a host enabled in a previous app session forwarded
 * nothing and every port-forward indicator stayed hidden.
 *
 * The first test fails on `origin/main` (there is no caller of
 * `getEnabled()` that populates the controller) and passes once the
 * foreground `ON_START` resume is wired.
 *
 * The scheduler and the controller are driven on a real
 * [Dispatchers.Unconfined] scope (not a `runTest` test scheduler): both
 * register the resumed host **synchronously** under their internal locks
 * before any background launch, so the assertions can read the resulting
 * state immediately after [runBlocking] returns without owning a long-lived
 * collector in the test dispatcher (which would deadlock `runTest`'s
 * end-of-test drain on the controller's durable network-change collector).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardingResumeSchedulerTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var keyFile: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        // The scheduler skips a host whose key file is missing on disk, so
        // give the test a real (empty) key path that exists.
        keyFile = File.createTempFile("ps-resume-key", ".pem").apply { deleteOnExit() }
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
        keyFile.delete()
    }

    /**
     * RED→GREEN: an enabled host (persisted `host.enabled = true`) results
     * in forwarding being (re-)started on foreground `ON_START`, so
     * `activeHostCount > 0` and the indicators have something to show.
     *
     * On `origin/main` nothing reads `getEnabled()`, so the controller stays
     * empty and this assertion fails.
     */
    @Test
    fun enabledHost_isResumedOnForegroundStart() {
        val connector = CountingConnector(Result.success(FakeSshSession()))
        val controller = newController(connector)
        val hostId = runBlocking { insertEnabledHost() }

        val scheduler = newScheduler(connector, controller)
        val owner = ManualLifecycleOwner()
        scheduler.observeProcessLifecycle(owner)
        idleMainLooper() // run the main-thread addObserver hop
        // Cold launch already STARTED → seeds an immediate sweep.
        owner.moveTo(Lifecycle.State.STARTED)
        idleMainLooper()

        assertTrue(
            "enabled host must be forwarding after foreground resume",
            controller.isHostActive(hostId),
        )
        assertEquals(1, controller.flowOfActiveHostCount().value)
        assertEquals(1, connector.connectCount.get())
        assertEquals(1L, scheduler.resumedHostCount)
    }

    /**
     * Idempotency: a host that is already actively forwarding in this
     * process must NOT be re-connected on a subsequent foreground.
     */
    @Test
    fun alreadyActiveHost_isNotDoubleStarted() {
        val connector = CountingConnector(Result.success(FakeSshSession()))
        val controller = newController(connector)
        val hostId = runBlocking { insertEnabledHost() }

        val scheduler = newScheduler(connector, controller)
        val owner = ManualLifecycleOwner()
        scheduler.observeProcessLifecycle(owner)
        idleMainLooper()
        owner.moveTo(Lifecycle.State.STARTED)
        idleMainLooper()

        assertTrue(controller.isHostActive(hostId))
        assertEquals(1, connector.connectCount.get())

        // Background then foreground again → second ON_START sweep. The host
        // is already active so it must not be re-connected.
        owner.moveTo(Lifecycle.State.CREATED)
        idleMainLooper()
        owner.moveTo(Lifecycle.State.STARTED)
        idleMainLooper()

        assertEquals(
            "already-active host must not be re-connected on a second foreground",
            1,
            connector.connectCount.get(),
        )
        assertEquals(1, controller.flowOfActiveHostCount().value)
        assertEquals(1L, scheduler.resumedHostCount)
    }

    /**
     * A disabled host (persisted `host.enabled = false`) must never be
     * resumed.
     */
    @Test
    fun disabledHost_isNotResumed() {
        val connector = CountingConnector(Result.success(FakeSshSession()))
        val controller = newController(connector)
        runBlocking { insertEnabledHost(enabled = false) }

        val scheduler = newScheduler(connector, controller)
        runBlocking { scheduler.resumeEnabledHosts() }

        assertEquals(0, controller.flowOfActiveHostCount().value)
        assertEquals(0, connector.connectCount.get())
    }

    /**
     * A passphrase-protected key cannot be resumed at launch (no prompt
     * surface), so the host is skipped rather than crashing.
     */
    @Test
    fun passphraseProtectedKey_isSkipped() {
        val connector = CountingConnector(Result.success(FakeSshSession()))
        val controller = newController(connector)
        runBlocking { insertEnabledHost(hasPassphrase = true) }

        val scheduler = newScheduler(connector, controller)
        runBlocking { scheduler.resumeEnabledHosts() }

        assertEquals(0, controller.flowOfActiveHostCount().value)
        assertEquals(0, connector.connectCount.get())
    }

    /**
     * A connect failure leaves no state behind, and a later foreground
     * retries (no permanent skip).
     */
    @Test
    fun connectFailure_doesNotConsumeState_andRetriesNextForeground() {
        val connector = QueueConnector(
            listOf(
                Result.failure(IllegalStateException("refused")),
                Result.success(FakeSshSession()),
            ),
        )
        val controller = newController(connector)
        val hostId = runBlocking { insertEnabledHost() }

        val scheduler = newScheduler(connector, controller)
        runBlocking { scheduler.resumeEnabledHosts() }
        assertFalse("failed connect must not register the host", controller.isHostActive(hostId))

        // Second foreground → retries and succeeds.
        runBlocking { scheduler.resumeEnabledHosts() }
        assertTrue("a later foreground must retry the failed host", controller.isHostActive(hostId))
        assertEquals(2, connector.connectCount.get())
    }

    private fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun insertEnabledHost(
        enabled: Boolean = true,
        hasPassphrase: Boolean = false,
    ): Long {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(
                name = "k",
                privateKeyPath = keyFile.absolutePath,
                hasPassphrase = hasPassphrase,
            ),
        )
        return db.hostDao().insert(
            HostEntity(
                name = "dev",
                hostname = "dev.example",
                username = "alexey",
                keyId = keyId,
                enabled = enabled,
            ),
        )
    }

    private fun newController(connector: PortForwardConnector): ForwardingController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return ForwardingController(
            appContext = context,
            connector = connector,
            portRemappingDao = db.portRemappingDao(),
            scope = scope,
        )
    }

    private fun newScheduler(
        connector: PortForwardConnector,
        controller: ForwardingController,
    ): ForwardingResumeScheduler =
        ForwardingResumeScheduler(
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            connector = connector,
            portRemappingDao = db.portRemappingDao(),
            forwardingController = controller,
        ).also {
            // Run the resume sweep on a real Unconfined scope so a launched
            // sweep completes inline (the connect + adopt register the host
            // synchronously). `Unconfined` is NOT a test dispatcher, so it is
            // never owned by a `runTest` drain.
            it.scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { s -> scopes += s }
        }

    private class ManualLifecycleOwner : LifecycleOwner {
        private val registry =
            LifecycleRegistry(this).apply { currentState = Lifecycle.State.INITIALIZED }
        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private class CountingConnector(
        private val result: Result<SshSession>,
    ) : PortForwardConnector {
        val connectCount = AtomicInteger(0)

        override suspend fun connect(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): Result<SshSession> {
            connectCount.incrementAndGet()
            return result
        }
    }

    private class QueueConnector(
        results: List<Result<SshSession>>,
    ) : PortForwardConnector {
        private val queue = ArrayDeque(results)
        val connectCount = AtomicInteger(0)

        override suspend fun connect(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): Result<SshSession> {
            connectCount.incrementAndGet()
            return queue.removeFirstOrNull() ?: Result.failure(AssertionError("missing connector stub"))
        }
    }

    private class FakeSshSession : SshSession {
        private var closed = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            if (closed) throw RuntimeException("session closed")
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            if (closed) throw RuntimeException("session closed")
            return FakePortForward(remoteHost, remotePort, localPort)
        }

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")

        override fun close() {
            closed = true
        }
    }

    private class FakePortForward(
        override val remoteHost: String,
        override val remotePort: Int,
        override val localPort: Int,
    ) : SshPortForward {
        override var isActive: Boolean = true
            private set
        override val bytesForwarded: Long = 0
        override val bytesReceived: Long = 0

        override fun close() {
            isActive = false
        }
    }
}
