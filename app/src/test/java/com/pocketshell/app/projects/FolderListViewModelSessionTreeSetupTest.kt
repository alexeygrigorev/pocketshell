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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1509 — the SINGLE session-tree setup coordinator (relocation + dedup).
 *
 * The maintainer's dogfood (v0.4.28): on app open the host-version-mismatch
 * **Update PocketShell** banner appeared AND, as a SECOND sequential prompt, the
 * **enable notifications** system dialog — two uncoordinated triggers (the
 * version banner from the folder/session tree, the notification prompt from
 * `MainActivity.onCreate` on app open).
 *
 * The intended design ([FolderListViewModel.runSessionTreeSetup]):
 *  - The version-mismatch check runs EXACTLY ONCE in the background when the
 *    session tree is shown — never re-raised by later reconcile polls, and never
 *    as an app-open trigger.
 *  - The notifications-permission request is FOLDED INTO that same one-shot setup
 *    pass (the `MainActivity.onCreate` app-open trigger is deleted, D22), so one
 *    code path drives both prompts.
 *
 * These tests drive the real cold-start hydrate + resume-reconcile over a fake
 * warm session whose `tree get` / `tree reconcile` envelopes carry a
 * `cli_version`, and assert the consolidated behaviour across the class:
 * both-conditions-true, mismatch-only, notifications-only, and neither.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelSessionTreeSetupTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    // ---- REPRODUCTION (RED→GREEN): the check runs ONCE, in the background -----

    /**
     * The reported "duplicated / uncoordinated check" symptom at the version-check
     * layer: on base the version check re-fired on EVERY reconcile poll, so a
     * dismissed banner re-appeared on the next foreground/resume. The one-shot
     * session-tree setup runs it EXACTLY ONCE — a dismissed banner stays gone.
     *
     * RED on base (reconcile re-raises the banner); GREEN with the one-shot guard.
     */
    @Test
    fun versionCheckRunsOnce_dismissedBannerStaysDismissedAcrossReconcile() = runTest {
        val session = VersionedTreeSession(cliVersion = "0.4.27")
        val vm = newViewModel(session, expectedVersion = "0.4.28")
        bind(vm)
        awaitReady(vm)
        assertTrue(
            "the one-shot session-tree check must raise the host-outdated banner on open",
            vm.cliVersionMismatch.value != null,
        )

        // User dismisses the update banner.
        vm.dismissCliVersionMismatch()
        assertNull(vm.cliVersionMismatch.value)

        // A foreground/resume drives a reconcile (which also carries cli_version).
        // The one-shot check must NOT re-raise the dismissed banner.
        vm.forceTreeStaleForTest()
        vm.setProcessStartedForTest(false)
        advanceUntilIdle()
        vm.setProcessStartedForTest(true)
        advanceUntilIdle()

        assertNull(
            "the version-mismatch check must run EXACTLY ONCE per session-tree open — " +
                "a reconcile poll must not re-raise a dismissed banner",
            vm.cliVersionMismatch.value,
        )
    }

    // ---- CLASS COVERAGE: one coordinator drives both prompts -----------------

    @Test
    fun bothConditions_singleSetupPass_raisesBannerAndRequestsNotification() = runTest {
        val session = VersionedTreeSession(cliVersion = "0.4.27")
        val vm = newViewModel(
            session,
            expectedVersion = "0.4.28",
            notificationNeeded = true,
        )
        bind(vm)
        awaitReady(vm)

        assertTrue(
            "mismatch + notifications-needed must both be driven by ONE setup pass: banner raised",
            vm.cliVersionMismatch.value != null,
        )
        assertTrue(
            "mismatch + notifications-needed must both be driven by ONE setup pass: notification requested",
            vm.notificationPermissionRequest.value,
        )
    }

    @Test
    fun mismatchOnly_raisesBanner_doesNotRequestNotification() = runTest {
        val session = VersionedTreeSession(cliVersion = "0.4.27")
        val vm = newViewModel(
            session,
            expectedVersion = "0.4.28",
            notificationNeeded = false,
        )
        bind(vm)
        awaitReady(vm)

        assertTrue(vm.cliVersionMismatch.value != null)
        assertFalse(
            "notifications already enabled — the setup must not request them",
            vm.notificationPermissionRequest.value,
        )
    }

    @Test
    fun notificationsOnly_requestsNotification_noBanner() = runTest {
        // Host matches the app version → no update banner, but notifications are
        // still needed → the single setup requests them.
        val session = VersionedTreeSession(cliVersion = "0.4.28")
        val vm = newViewModel(
            session,
            expectedVersion = "0.4.28",
            notificationNeeded = true,
        )
        bind(vm)
        awaitReady(vm)

        assertNull(vm.cliVersionMismatch.value)
        assertTrue(vm.notificationPermissionRequest.value)
    }

    @Test
    fun noMismatchNoNotification_noBanner_noRequest() = runTest {
        val session = VersionedTreeSession(cliVersion = "0.4.28")
        val vm = newViewModel(
            session,
            expectedVersion = "0.4.28",
            notificationNeeded = false,
        )
        bind(vm)
        awaitReady(vm)

        assertNull(vm.cliVersionMismatch.value)
        assertFalse(vm.notificationPermissionRequest.value)
    }

    @Test
    fun notificationRequest_firesOnce_notReRaisedAfterConsumeOnRebind() = runTest {
        val session = VersionedTreeSession(cliVersion = "0.4.28")
        val vm = newViewModel(
            session,
            expectedVersion = "0.4.28",
            notificationNeeded = true,
        )
        bind(vm)
        awaitReady(vm)
        assertTrue(vm.notificationPermissionRequest.value)

        // The screen launches the system prompt and consumes the signal.
        vm.onNotificationPermissionRequestConsumed()
        assertFalse(vm.notificationPermissionRequest.value)

        // Re-opening the SAME host's session tree must not re-request (no second
        // sequential prompt within the app session).
        bind(vm)
        awaitReady(vm)
        assertFalse(
            "the notifications request is a once-per-session setup step — a re-open must not re-prompt",
            vm.notificationPermissionRequest.value,
        )
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

    private suspend fun TestScope.awaitReady(vm: FolderListViewModel): FolderListUiState.Ready {
        repeat(200) {
            advanceUntilIdle()
            val state = vm.state.value
            if (state is FolderListUiState.Ready) return state
            delay(10L)
        }
        throw AssertionError("expected Ready, was ${vm.state.value}")
    }

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = true,
            cwd = "/home/alexey/git/$name",
        )

    private fun TestScope.newViewModel(
        session: SshSession,
        expectedVersion: String,
        notificationNeeded: Boolean = false,
    ): FolderListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(session) },
            scope = this,
            idleTtlMillis = 0L,
            connectTimeoutContext = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
        return FolderListViewModel(
            gateway = StubGateway(listOf(sessionRow("alpha"))),
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = manager,
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            treeRemoteSource = TreeRemoteSource().apply {
                remoteExecDispatcher = dispatcher
            },
            expectedPocketshellVersionProvider = { expectedVersion },
            notificationPermissionNeeded = { notificationNeeded },
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.treeDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    /**
     * A warm session whose `tree get` / `tree reconcile` envelopes carry a
     * [cliVersion]. `tree get` seeds a single non-empty node so the held tree has
     * a snapshot (→ the resume path takes the delta reconcile, which also carries
     * the version — the poll that base re-raised on).
     */
    private class VersionedTreeSession(
        private val cliVersion: String,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult = when {
            command.contains("tree get") -> ExecResult(
                JSONObject()
                    .put(
                        "nodes",
                        JSONArray().put(
                            JSONObject()
                                .put("session", "alpha")
                                .put("order", 0)
                                .put("folder_path", "/home/alexey/git/alpha")
                                .put("collapsed", false),
                        ),
                    )
                    .put("version", 1)
                    .put("cli_version", cliVersion)
                    .toString(),
                "",
                0,
            )

            command.contains("tree upsert") ->
                ExecResult(JSONObject().put("status", "ok").put("version", 1).toString(), "", 0)

            command.contains("tree reconcile") -> ExecResult(
                JSONObject()
                    .put("alive", JSONArray(listOf("alpha")))
                    .put("gone", JSONArray())
                    .put("added", JSONArray())
                    .put("cli_version", cliVersion)
                    .toString(),
                "",
                0,
            )

            else -> ExecResult("", "", 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit) = error("unused")
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
        ): FolderListResult = FolderListResult.Sessions(rows = rows ?: error("gateway probe must not be called"))

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
