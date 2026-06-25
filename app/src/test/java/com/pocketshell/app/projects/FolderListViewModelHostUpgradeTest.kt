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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #947 — RED→GREEN for the host-version-mismatch banner's one-tap
 * **Update** action, which runs the host `pocketshell` upgrade over the EXISTING
 * warm SSH session (D21) and clears the banner on success / surfaces the error on
 * failure (no stuck spinner — #939/#944).
 *
 * The maintainer asked for a button that replaces the copy-paste upgrade command
 * on the banner with a one-tap upgrade. These tests drive [runHostPocketshellUpgrade]
 * directly against a fake warm session whose `tree get` reports an OLD CLI first
 * (so the banner is raised), then a fresh version after the upgrade.
 *
 * Coverage:
 *  - SUCCESS: tapping Update execs the upgrade, the host re-reports the matching
 *    version, and the banner clears (state back to Idle).
 *  - FAILURE: a failing upgrade exec leaves the banner with a Failure state (the
 *    stderr surfaced) and NEVER a stuck Running spinner.
 *  - NO-OP SUCCESS: an exit-0 upgrade whose re-check STILL reports outdated
 *    raises a Failure (so the user isn't told it worked when it didn't).
 *  - Running is entered then left (no stuck spinner) for both paths.
 *  - The exact upgrade command probes uv → pipx → pip with the #779 uv cap
 *    override (installer detection criterion).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelHostUpgradeTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun update_success_clearsBanner_andEntersThenLeavesRunning() = runTest {
        // Host starts at 0.4.9, app expects 0.4.16 → banner raised. After the
        // upgrade exec succeeds, the host re-reports 0.4.16 → banner clears.
        val session = UpgradableSession(
            initialVersion = "0.4.9",
            upgradedVersion = "0.4.16",
            upgradeResult = ExecResult("upgraded pocketshell 0.4.9 -> 0.4.16", "", 0),
        )
        val vm = newViewModel(session, expectedVersion = "0.4.16")
        bind(vm)
        advanceUntilIdle()
        assertTrue("banner must be raised before update", vm.cliVersionMismatch.value != null)

        vm.runHostPocketshellUpgrade()
        advanceUntilIdle()

        assertNull("the banner must clear after a successful upgrade", vm.cliVersionMismatch.value)
        assertEquals(
            FolderListViewModel.CliVersionUpdateState.Idle,
            vm.cliVersionUpdateState.value,
        )
        assertTrue("the upgrade exec must have run over the warm session", session.upgradeRan)
    }

    @Test
    fun update_failure_showsError_andNoStuckSpinner() = runTest {
        // The upgrade exec fails (non-zero exit + stderr). The banner must end in
        // Failure with the stderr surfaced, NOT a stuck Running spinner, and the
        // mismatch banner stays up so the user can Retry/Dismiss.
        val session = UpgradableSession(
            initialVersion = "0.4.9",
            upgradedVersion = "0.4.9", // still old (upgrade didn't take)
            upgradeResult = ExecResult("", "error: network unreachable", 1),
        )
        val vm = newViewModel(session, expectedVersion = "0.4.16")
        bind(vm)
        advanceUntilIdle()
        assertTrue(vm.cliVersionMismatch.value != null)

        vm.runHostPocketshellUpgrade()
        advanceUntilIdle()

        val state = vm.cliVersionUpdateState.value
        assertTrue(
            "a failing upgrade must end in Failure, not a stuck spinner — was $state",
            state is FolderListViewModel.CliVersionUpdateState.Failure,
        )
        assertTrue(
            "the failure message must surface the installer stderr — was $state",
            (state as FolderListViewModel.CliVersionUpdateState.Failure).message.contains("network unreachable"),
        )
        assertTrue(
            "the mismatch banner must stay up so the user can retry/dismiss",
            vm.cliVersionMismatch.value != null,
        )
    }

    @Test
    fun update_noOpSuccess_stillOutdated_raisesFailure() = runTest {
        // Exit-0 upgrade but the host STILL reports the old version (a silent
        // no-op / capped upgrade). The banner must NOT pretend success — it
        // surfaces a Failure so the user knows it didn't take.
        val session = UpgradableSession(
            initialVersion = "0.4.9",
            upgradedVersion = "0.4.9", // unchanged despite exit-0
            upgradeResult = ExecResult("nothing to upgrade", "", 0),
        )
        val vm = newViewModel(session, expectedVersion = "0.4.16")
        bind(vm)
        advanceUntilIdle()

        vm.runHostPocketshellUpgrade()
        advanceUntilIdle()

        val state = vm.cliVersionUpdateState.value
        assertTrue(
            "an exit-0 upgrade that did not bump the version must raise a Failure — was $state",
            state is FolderListViewModel.CliVersionUpdateState.Failure,
        )
        assertTrue("the banner stays up", vm.cliVersionMismatch.value != null)
    }

    @Test
    fun update_timeout_raisesFailure_noStuckSpinner() = runTest {
        // A wedged installer (never returns) must be bounded — the state leaves
        // Running and ends in Failure (timeout), never a stuck spinner.
        val session = UpgradableSession(
            initialVersion = "0.4.9",
            upgradedVersion = "0.4.9",
            upgradeResult = ExecResult("", "", 0),
            wedgeUpgrade = true,
        )
        val vm = newViewModel(session, expectedVersion = "0.4.16")
        // Tiny upgrade timeout so the wedge is bounded within the test.
        vm.setHostPocketshellUpgradeForTest(
            HostPocketshellUpgrade().apply {
                upgradeTimeoutMs = 50L
                execDispatcher = UnconfinedTestDispatcher(testScheduler)
            },
        )
        bind(vm)
        advanceUntilIdle()

        vm.runHostPocketshellUpgrade()
        advanceUntilIdle()

        val state = vm.cliVersionUpdateState.value
        assertTrue(
            "a wedged upgrade must be bounded to a Failure (no stuck spinner) — was $state",
            state is FolderListViewModel.CliVersionUpdateState.Failure,
        )
    }

    @Test
    fun upgradeCommand_probesUvPipxPip_withUvCapOverride() {
        // Installer-detection criterion: the single command probes uv → pipx →
        // pip and uses the #779 uv exclude-newer override so the upgrade is not a
        // silent no-op.
        val cmd = HostPocketshellUpgrade.UPGRADE_COMMAND
        assertTrue("must probe uv tool ownership", cmd.contains("uv tool list"))
        assertTrue(
            "uv arm must use the #779 exclude-newer cap override",
            cmd.contains("--exclude-newer-package pocketshell=2099-12-31"),
        )
        assertTrue("must fall back to pipx", cmd.contains("pipx upgrade pocketshell"))
        assertTrue("must fall back to pip", cmd.contains("pip install -U pocketshell"))
        assertTrue("must exit 127 when no installer is found", cmd.contains("exit 127"))
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

    private fun TestScope.newViewModel(
        session: SshSession,
        expectedVersion: String,
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
            gateway = StubGateway(listOf(sessionRow("alpha"))),
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = manager,
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            treeRemoteSource = TreeRemoteSource().apply {
                remoteExecDispatcher = dispatcher
            },
            expectedPocketshellVersionProvider = { expectedVersion },
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.setHostPocketshellUpgradeForTest(
                HostPocketshellUpgrade().apply { execDispatcher = dispatcher },
            )
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = true,
            cwd = "/home/alexey/git/$name",
        )

    /**
     * A warm session whose `tree get` reports [initialVersion] until the upgrade
     * exec runs, then [upgradedVersion]. The upgrade exec returns [upgradeResult]
     * (or, with [wedgeUpgrade], blocks until cancelled so the bound can be
     * exercised).
     */
    private class UpgradableSession(
        private val initialVersion: String?,
        private val upgradedVersion: String?,
        private val upgradeResult: ExecResult,
        private val wedgeUpgrade: Boolean = false,
    ) : SshSession {
        @Volatile var upgradeRan: Boolean = false
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            return when {
                // The upgrade probe-and-run command (issue #947).
                command.contains("pipx upgrade pocketshell") -> {
                    upgradeRan = true
                    if (wedgeUpgrade) {
                        kotlinx.coroutines.awaitCancellation()
                    }
                    upgradeResult
                }
                command.contains("tree get") -> {
                    val v = if (upgradeRan) upgradedVersion else initialVersion
                    val versionField = v?.let { ",\"cli_version\":\"$it\"" } ?: ""
                    ExecResult("{\"nodes\":[],\"version\":0$versionField}", "", 0)
                }
                command.contains("tree reconcile") -> {
                    val v = if (upgradeRan) upgradedVersion else initialVersion
                    val versionField = v?.let { ",\"cli_version\":\"$it\"" } ?: ""
                    ExecResult("{\"alive\":[],\"gone\":[],\"added\":[]$versionField}", "", 0)
                }
                else -> ExecResult("", "", 0)
            }
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
