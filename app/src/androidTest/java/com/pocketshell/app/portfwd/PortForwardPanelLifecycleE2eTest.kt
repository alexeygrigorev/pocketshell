package com.pocketshell.app.portfwd

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #203 — connected E2E that proves the D21 lifecycle hook in
 * [PortForwardPanelViewModel] actually fires on real Android process
 * lifecycle transitions (not just on the [androidx.lifecycle.LifecycleRegistry]
 * shim used by the Robolectric unit tests).
 *
 * Strategy mirrors [com.pocketshell.app.proof.NoBackgroundWorkE2eTest]:
 *
 *  1. Launch [MainActivity] so the process has a foreground activity
 *     and [ProcessLifecycleOwner] enters `STARTED`.
 *  2. Construct a [PortForwardPanelViewModel] on the main thread with
 *     deterministic test doubles (in-memory Room database + a
 *     [PortForwardConnector] that returns a [FakeLifecycleE2eSshSession]
 *     so the test never touches a real SSH transport).
 *  3. Attach `ProcessLifecycleOwner.get()` to the ViewModel.
 *  4. Drive the user toggle on, wait for the panel to report
 *     `Connected`, assert the tunnel is open.
 *  5. `moveToState(Lifecycle.State.CREATED)` — backgrounds the process,
 *     `ProcessLifecycleOwner` flips to `CREATED`, the panel's observer
 *     dispatches `ON_STOP`, and the foreground-service carve-out keeps
 *     the active tunnel alive.
 *  6. `moveToState(Lifecycle.State.RESUMED)` — process returns to
 *     `STARTED`. `ON_START` must not reconnect an already-supervised
 *     tunnel.
 *
 * The test deliberately avoids navigating through the chooser → panel
 * Compose UI flow because the panel is only mounted inside a sub-graph
 * that requires either an `Intent` extra or a user tap to reach. The
 * lifecycle behaviour we care about lives in the ViewModel, not the
 * navigation chain, so a direct ViewModel exercise pinned to a real
 * `ProcessLifecycleOwner` gives the cleanest signal for the D21 hook.
 */
@RunWith(AndroidJUnit4::class)
class PortForwardPanelLifecycleE2eTest {

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from MainActivity at launch.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        db?.close()
        db = null
    }

    @Test
    fun lifecycleStopKeepsTunnels_lifecycleStartDoesNotReconnectThem() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext

        // 1. Stand up an in-memory Room DB on the main thread so the
        //    panel's autoForward + persist paths have a real DAO.
        val database = withContext(Dispatchers.Main) {
            Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        }
        db = database

        // 2. Seed one host with a paired SSH key entry.
        val keyId = database.sshKeyDao()
            .insert(SshKeyEntity(name = "e2e-lifecycle-key", privateKeyPath = "/tmp/e2e-key"))
        val hostId = database.hostDao().insert(
            HostEntity(
                name = "lifecycle-e2e-host",
                hostname = "lifecycle.example",
                username = "alexey",
                keyId = keyId,
                maxAutoPort = 10_000,
                skipPortsBelow = 1000,
                scanIntervalSec = 1,
                enabled = false,
            ),
        )

        // 3. Launch MainActivity to get a real process lifecycle.
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        launchedActivity!!.moveToState(Lifecycle.State.RESUMED)

        val sessionFactory = SequentialFakeSessionFactory()
        val connector = FakeConnector(sessionFactory)

        // 4. Build the ViewModel on the main thread (Lifecycle.addObserver
        //    requirement) and attach ProcessLifecycleOwner.
        val forwardingController = ForwardingController(targetContext)
        val viewModel = withContext(Dispatchers.Main) {
            PortForwardPanelViewModel(
                hostDao = database.hostDao(),
                sshKeyDao = database.sshKeyDao(),
                connector = connector,
                portRemappingDao = database.portRemappingDao(),
                forwardingController = forwardingController,
                showAllPortsStore = ShowAllPortsStore(targetContext),
            ).also { it.observeProcessLifecycle(ProcessLifecycleOwner.get()) }
        }

        viewModel.load(hostId, "/tmp/e2e-key")

        withTimeout(STATE_TIMEOUT_MS) {
            waitFor("panel host loaded") { viewModel.state.value.host?.id == hostId }
        }

        viewModel.setAutoForwardEnabled(true)

        withTimeout(STATE_TIMEOUT_MS) {
            waitFor("panel connected with tunnel") {
                val state = viewModel.state.value
                state.connectionState == PortForwardConnectionState.Connected &&
                    state.tunnels.any { it.status == TunnelInfo.Status.FORWARDING }
            }
        }
        val firstSession = requireNotNull(sessionFactory.lastSession()) {
            "fake session factory did not produce a session for the initial connect"
        }
        assertEquals(1, firstSession.openedForwards.size)
        assertTrue(firstSession.openedForwards.single().isActive)

        // 5. Background the app. ProcessLifecycleOwner flips to STOPPED;
        //    the foreground-service carve-out keeps the supervisor and
        //    tunnel alive.
        launchedActivity!!.moveToState(Lifecycle.State.CREATED)

        withTimeout(STATE_TIMEOUT_MS) {
            waitFor("panel kept tunnel active on ON_STOP") {
                val state = viewModel.state.value
                state.autoForwardEnabled &&
                    state.tunnels.any { it.status == TunnelInfo.Status.FORWARDING } &&
                    !firstSession.closed &&
                    firstSession.openedForwards.single().isActive
            }
        }

        // 6. Resume the app. ON_START must not open a second SSH session
        //    when the first one stayed supervised while backgrounded.
        launchedActivity!!.moveToState(Lifecycle.State.RESUMED)

        withTimeout(STATE_TIMEOUT_MS) {
            waitFor("panel still connected on ON_START") {
                val state = viewModel.state.value
                state.connectionState == PortForwardConnectionState.Connected &&
                    state.tunnels.any { it.status == TunnelInfo.Status.FORWARDING }
            }
        }
        assertFalse(
            "initial session must still be open after ON_START",
            firstSession.closed,
        )
        assertEquals(1, connector.connectCount)

        // Cleanup so the next test starts from a clean lifecycle.
        withContext(Dispatchers.Main) {
            viewModel.leavePanel()
        }
    } }

    private suspend fun waitFor(label: String, predicate: () -> Boolean) {
        while (!predicate()) {
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        /**
         * Max time we wait for any single state transition (load,
         * connect, ON_STOP teardown, ON_START reconnect). A 1s scan
         * interval combined with the test's `Dispatchers.IO` work means
         * connects normally complete in well under a second, but the
         * emulator under sibling-agent contention (#182) can spike.
         */
        const val STATE_TIMEOUT_MS: Long = 15_000L
        const val POLL_INTERVAL_MS: Long = 50L
    }
}

/** Hands out a fresh [FakeLifecycleE2eSshSession] on each `connect`. */
private class SequentialFakeSessionFactory {
    private val sessions = mutableListOf<FakeLifecycleE2eSshSession>()

    fun next(): FakeLifecycleE2eSshSession {
        val session = FakeLifecycleE2eSshSession()
        sessions += session
        return session
    }

    fun lastSession(): FakeLifecycleE2eSshSession? = sessions.lastOrNull()
}

private class FakeConnector(
    private val factory: SequentialFakeSessionFactory,
) : PortForwardConnector {
    @Volatile
    var connectCount: Int = 0
        private set

    override suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession> {
        connectCount += 1
        return Result.success(factory.next())
    }
}

/**
 * Minimal [SshSession] stub: `exec` returns canned `ss -tlnp` output
 * that pins port 3000 to a "node" process so the AutoForwarder opens
 * exactly one forward; `openLocalPortForward` returns a controllable
 * [FakeLifecycleE2eForward] so the test can assert open/closed
 * transitions per session.
 */
private class FakeLifecycleE2eSshSession : SshSession {
    val openedForwards = mutableListOf<FakeLifecycleE2eForward>()

    @Volatile
    var closed = false
        private set

    override val isConnected: Boolean
        get() = !closed

    override suspend fun exec(command: String): ExecResult = ExecResult(
        stdout = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        stderr = "",
        exitCode = 0,
    )

    override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward {
        val forward = FakeLifecycleE2eForward(remoteHost, remotePort, localPort)
        openedForwards += forward
        return forward
    }

    override fun startShell(): SshShell = error("shell not used in lifecycle e2e")

    override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
        error("uploadFile not used in lifecycle e2e")

    override suspend fun uploadStream(
        input: java.io.InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String = error("uploadStream not used in lifecycle e2e")

    override fun close() {
        closed = true
        openedForwards.forEach { it.close() }
    }
}

private class FakeLifecycleE2eForward(
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
