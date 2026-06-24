package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #847 (P0, shipped regression in v0.4.10) — RED→GREEN at the ViewModel
 * layer: the app must CONNECT and render the LIVE tree even when the host
 * `pocketshell` CLI is OLDER than the client (no `tree` subcommand).
 *
 * ## The bug
 *
 * #837 added a cold-start tree HYDRATE that execs `pocketshell tree get` over
 * the warm session BEFORE the live reconcile. `tree` is new in 0.4.10; on a
 * 0.4.9 host it errors (`No such command 'tree'`). v0.4.10 chained the
 * freshening reconcile AFTER the hydrate, so when the hydrate's `tree get`
 * failed/wedged the reconcile never ran and the screen sat on "loading tree"
 * forever — the app would not connect.
 *
 * ## What these tests prove (and which one is the load-bearing RED→GREEN)
 *
 * The fix keeps the hydrate-BEFORE-reconcile ordering but makes the hydrate
 * FAIL-SAFE: the hydrate body (warm-session await + `tree get`) is bounded by
 * `HYDRATE_TIMEOUT_MS`, and the freshening reconcile runs in a `finally` so it
 * ALWAYS fires. This file covers the WHOLE cold-start hydrate fault class:
 *
 *  - non-zero `No such command` exit, garbage stdout, and a thrown transport
 *    error — these PASS on BOTH the un-fixed and fixed code, because `getTree`
 *    already swallows a PROMPT failure (exit-code-guard + catch-all) so the
 *    chained reconcile still ran. They are class-coverage regression guards
 *    proving the safe prompt-error paths stay safe; AND
 *  - a WEDGED / never-returning `tree get` — this is the LOAD-BEARING RED→GREEN.
 *    The genuine v0.4.10 hang is a `tree get` that does NOT return promptly:
 *    on the un-fixed VM the cold-start coroutine suspends inside `getTree`
 *    forever and the freshening reconcile (chained AFTER it) NEVER runs, pinning
 *    the screen on "loading tree" (RED — proven by stashing the VM fix). The
 *    fixed VM bounds the hydrate and runs the reconcile in `finally`, so it
 *    reaches the live tree (GREEN).
 *
 * The good-host path (a real `tree get` envelope seeding the held order /
 * collapse, which must survive an app restart) is covered by
 * [FolderListViewModelTreeDurabilityTest]; this file is the old/mismatched/
 * wedged-CLI fault class at the VM layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelOldCliHydrateTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    /** OLD CLI: `tree get` errors with the real Click `No such command` shape. */
    @Test
    fun connectsToLiveTree_whenHostCliLacksTreeCommand() = runTest {
        val session = OldCliTreeSshSession(
            treeGetResult = ExecResult("", "Error: No such command 'tree'.", 2),
        )
        assertConnectsToLiveTree(session)
    }

    /** A garbage / unparseable `tree get` stdout must not pin connect either. */
    @Test
    fun connectsToLiveTree_whenTreeGetReturnsGarbage() = runTest {
        val session = OldCliTreeSshSession(
            treeGetResult = ExecResult("not json at all", "", 0),
        )
        assertConnectsToLiveTree(session)
    }

    /** A transport error thrown straight out of `tree get` must not hang. */
    @Test
    fun connectsToLiveTree_whenTreeGetThrows() = runTest {
        val session = OldCliTreeSshSession(treeGetThrowable = RuntimeException("transport down"))
        assertConnectsToLiveTree(session)
    }

    /**
     * A `tree get` exec that NEVER returns (the #470 wedge — no exit, no
     * exception). Because the reconcile is now decoupled from the hydrate, even
     * a permanently-wedged `tree get` cannot pin connect: the reconcile reaches
     * the live tree while the bounded hydrate-seed coroutine simply times out.
     * (The wedge is a suspending `delay` so it is interruptible under the test
     * scheduler's virtual clock by the hydrate-seed [HYDRATE_TIMEOUT_MS] bound.)
     */
    @Test
    fun connectsToLiveTree_whenTreeGetWedgesForever() = runTest {
        val session = OldCliTreeSshSession(treeGetWedges = true)
        assertConnectsToLiveTree(session)
    }

    // --- shared assertion ---------------------------------------------------

    private suspend fun TestScope.assertConnectsToLiveTree(session: OldCliTreeSshSession) {
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val treeSource = TreeRemoteSource().apply {
            remoteExecTimeoutMs = 500L
            remoteExecDispatcher = UnconfinedTestDispatcher(testScheduler)
        }
        val vm = newViewModel(gateway, session, treeSource)

        bind(vm)
        // The source-level exec bound is production-IO by default; this test
        // routes it through the test dispatcher above, then waits on the visible
        // Ready state so the assertion follows the UI contract rather than an
        // implementation-specific launch order.
        advanceUntilIdle()

        val state = awaitReady(vm)
        assertEquals(
            "the live gateway sessions are shown",
            listOf("alpha", "beta"),
            (state as FolderListUiState.Ready).flatSessions.map { it.sessionName },
        )
    }

    private suspend fun TestScope.awaitReady(vm: FolderListViewModel): FolderListUiState {
        withTimeout(2_000L) {
            while (vm.state.value !is FolderListUiState.Ready) {
                advanceUntilIdle()
                delay(10L)
            }
        }
        return vm.state.value
    }

    // --- helpers ------------------------------------------------------------

    private fun bind(vm: FolderListViewModel) {
        vm.bind(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            passphrase = null,
        )
    }

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = true,
            cwd = "/home/alexey/git/$name",
        )

    private fun TestScope.newViewModel(
        gateway: FolderListGateway,
        session: SshSession,
        treeSource: TreeRemoteSource,
    ): FolderListViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(session) },
            scope = this,
            idleTtlMillis = 0L,
            connectTimeoutContext = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
        return FolderListViewModel(
            gateway = gateway,
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = manager,
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            treeRemoteSource = treeSource,
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    /**
     * Stands in for a host whose `pocketshell` CLI is OLDER than the client:
     * `tree get` (and the other `tree` verbs) fail the way an old CLI does —
     * non-zero `No such command` exit, garbage, a thrown transport error, or a
     * never-returning wedge — while the rest of the session is fine (the gateway
     * probe never touches THIS session; it runs through the StubGateway).
     */
    private class OldCliTreeSshSession(
        private val treeGetResult: ExecResult = ExecResult("", "Error: No such command 'tree'.", 2),
        private val treeGetThrowable: Throwable? = null,
        private val treeGetWedges: Boolean = false,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            if (command.contains("tree ")) {
                treeGetThrowable?.let { throw it }
                if (treeGetWedges) {
                    // A never-returning exec: await a Deferred that is NEVER
                    // completed, so `advanceUntilIdle` cannot fast-forward past it
                    // (unlike a time-based `delay`). On the un-fixed VM (no bound
                    // on the hydrate) the cold-start coroutine stays suspended here
                    // forever and the freshening reconcile never runs → Loading
                    // (the RED). The fixed VM's HYDRATE_TIMEOUT_MS bound fires
                    // under virtual time, cancels this await, and the `finally`
                    // reconcile reaches the live tree (GREEN).
                    kotlinx.coroutines.CompletableDeferred<Unit>().await()
                }
                return treeGetResult
            }
            // Any other exec (none expected on this path) is a benign no-op.
            return ExecResult("", "", 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() = Unit
    }

    private class StubGateway(
        @Volatile var rows: List<FolderSessionRow>?,
    ) : FolderListGateway {
        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            val r = rows ?: error("gateway probe must not be called")
            return FolderListResult.Sessions(rows = r)
        }

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
        ): Result<String> = error("not used")

        override suspend fun createEmptyProject(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = error("not used")

        override suspend fun importFile(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: FolderImportPayload,
        ): Result<String> = error("not used")

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> = error("not used")
    }

    private class FakeHostDao(private val host: HostEntity) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? = host.takeIf { it.id == id }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    private class FakeProjectRootDao : ProjectRootDao {
        private val roots = MutableStateFlow<List<ProjectRootEntity>>(emptyList())
        override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> = roots
        override suspend fun insert(root: ProjectRootEntity): Long = error("not used")
        override suspend fun update(root: ProjectRootEntity) = error("not used")
        override suspend fun delete(root: ProjectRootEntity) = error("not used")
    }

    private companion object {
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 42L,
            name = "hetzner",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
