package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
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
import org.junit.Assert.assertFalse
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
    fun upgradeCommand_probesUvPipxPip_withGlobalUvCapOverride() {
        // Installer-detection criterion: the single command probes uv → pipx →
        // pip and uses the #779/#1492 uv exclude-newer override so the upgrade is
        // not a silent no-op.
        val cmd = HostPocketshellUpgrade.UPGRADE_COMMAND
        assertTrue("must probe uv tool ownership", cmd.contains("uv tool list"))
        assertTrue(
            "uv arm must lift the exclude-newer cap for the WHOLE resolution (#1492)",
            cmd.contains("--exclude-newer 2099-12-31"),
        )
        assertFalse(
            "uv arm must NOT use the narrow per-package override that broke on sibling pins (#1492)",
            cmd.contains("--exclude-newer-package"),
        )
        assertTrue("must fall back to pipx", cmd.contains("pipx upgrade pocketshell"))
        assertTrue("must fall back to pip", cmd.contains("pip install -U pocketshell"))
        assertTrue("must exit 127 when no installer is found", cmd.contains("exit 127"))
    }

    // --- Issue #1157: no false "Not connected" while the host is connected ---
    //
    // The upgrade runs over the FolderList's WARM-LEASE session, which is SEPARATE
    // from the live terminal sessions. When the warm lease is absent/expired but
    // the host is genuinely connected (13 live terminal sessions in the report),
    // the old path dead-ended at `awaitWarmSession() == null` and surfaced a FALSE
    // "Not connected to the host — reconnect and try again." that contradicted the
    // tree ("13 active") and the tray. These reproduce that class red→green: the
    // upgrade must (re)acquire a live session robustly and never falsely claim the
    // host is disconnected.

    @Test
    fun update_warmLeaseReleased_whileConnected_reacquires_noFalseNotConnected() = runTest {
        // Bind → banner raised (host connected, warm lease held).
        val session = UpgradableSession(
            initialVersion = "0.4.19",
            upgradedVersion = "0.4.20",
            upgradeResult = ExecResult("upgraded pocketshell 0.4.19 -> 0.4.20", "", 0),
        )
        val rig = newViewModelRig({ session }, expectedVersion = "0.4.20")
        bind(rig.vm)
        advanceUntilIdle()
        assertTrue("banner must be raised before update", rig.vm.cliVersionMismatch.value != null)

        // Leave the FolderList screen: the warm lease is scheduled for release
        // (stopPolling → scheduleWarmRelease). The host stays genuinely connected
        // (its live terminal sessions keep the pooled transport open on device),
        // but this screen's warm lease is now gone — the exact #1157 state.
        rig.vm.stopPolling()
        advanceUntilIdle()

        rig.vm.runHostPocketshellUpgrade()
        advanceUntilIdle()

        val state = rig.vm.cliVersionUpdateState.value
        assertFalse(
            "the banner must NOT surface a false 'Not connected' when the host is " +
                "genuinely connected — was $state",
            state is FolderListViewModel.CliVersionUpdateState.Failure &&
                (state as FolderListViewModel.CliVersionUpdateState.Failure).message.contains("Not connected"),
        )
        assertTrue("the upgrade must actually run over the re-acquired session", session.upgradeRan)
        assertNull("a successful re-acquired upgrade clears the banner", rig.vm.cliVersionMismatch.value)
        assertEquals(
            FolderListViewModel.CliVersionUpdateState.Idle,
            rig.vm.cliVersionUpdateState.value,
        )
    }

    @Test
    fun update_warmLeaseExpired_whileConnected_reacquiresLiveSession() = runTest {
        // The warm lease is PRESENT but its transport went stale (a network
        // handoff): `awaitWarmSession()` returns a DISCONNECTED session. The host
        // is still reachable, so a robust re-acquire must yield a LIVE session and
        // succeed — not run the upgrade over the dead transport.
        val expiredWarmSession = UpgradableSession(
            initialVersion = "0.4.19",
            upgradedVersion = "0.4.19",
            upgradeResult = ExecResult("", "broken pipe", 1),
            connectedOverride = true, // flipped to false below to simulate expiry
        )
        val liveReacquiredSession = UpgradableSession(
            initialVersion = "0.4.19",
            upgradedVersion = "0.4.20",
            upgradeResult = ExecResult("upgraded pocketshell 0.4.19 -> 0.4.20", "", 0),
        )
        // Supplier hands out the warm session first (bind), then the fresh live
        // session once the warm one has "expired" (isConnected flipped false).
        val rig = newViewModelRig(
            sessionSupplier = { if (expiredWarmSession.isConnected) expiredWarmSession else liveReacquiredSession },
            expectedVersion = "0.4.20",
        )
        bind(rig.vm)
        advanceUntilIdle()
        assertTrue("banner must be raised before update", rig.vm.cliVersionMismatch.value != null)

        // The warm lease's transport goes stale (still leased, now disconnected).
        expiredWarmSession.isConnected = false

        rig.vm.runHostPocketshellUpgrade()
        advanceUntilIdle()

        assertTrue(
            "the upgrade must run over a freshly re-acquired LIVE session, not the " +
                "expired warm one",
            liveReacquiredSession.upgradeRan,
        )
        assertNull("the re-acquired upgrade clears the banner", rig.vm.cliVersionMismatch.value)
        val state = rig.vm.cliVersionUpdateState.value
        assertFalse(
            "the banner must NOT falsely claim 'Not connected' — was $state",
            state is FolderListViewModel.CliVersionUpdateState.Failure &&
                (state as FolderListViewModel.CliVersionUpdateState.Failure).message.contains("Not connected"),
        )
    }

    @Test
    fun update_warmLeaseAbsent_reusesLivePooledTransport_noNewConnect() = runTest {
        // Class coverage + D21 proof: with a live pooled transport (the live
        // terminal sessions hold a ref on the SAME lease manager), the re-acquire
        // must REUSE it — no new SSH connect — rather than dial a second one.
        val session = UpgradableSession(
            initialVersion = "0.4.19",
            upgradedVersion = "0.4.20",
            upgradeResult = ExecResult("upgraded pocketshell 0.4.19 -> 0.4.20", "", 0),
        )
        val rig = newViewModelRig({ session }, expectedVersion = "0.4.20")
        bind(rig.vm)
        advanceUntilIdle()
        assertTrue("banner must be raised before update", rig.vm.cliVersionMismatch.value != null)

        // Simulate the live terminal sessions holding the pooled transport: take a
        // second lease on the shared manager and keep it (never released).
        val liveTerminalLease = rig.manager.acquire(leaseTarget()).getOrNull()
        assertTrue("a live pooled lease must be held", liveTerminalLease != null)
        val connectsAfterBind = rig.connectCount

        // Leave the screen → warm lease released, but the pooled transport stays
        // OPEN (refCount > 0 via the live terminal lease).
        rig.vm.stopPolling()
        advanceUntilIdle()

        rig.vm.runHostPocketshellUpgrade()
        advanceUntilIdle()

        assertTrue("the upgrade must run over the reused pooled session", session.upgradeRan)
        assertNull("the reused-session upgrade clears the banner", rig.vm.cliVersionMismatch.value)
        assertEquals(
            "the upgrade must REUSE the live pooled transport — NO new SSH connect (D21)",
            connectsAfterBind,
            rig.connectCount,
        )
    }

    @Test
    fun update_noBoundHost_doesNotFalselySayNotConnected() = runTest {
        // Class coverage: bound == null. The mismatch banner is only ever raised
        // for a bound host, so this is a defensive edge — but it must NEVER claim
        // a false "Not connected" (which contradicts a connected tray/tree).
        val session = UpgradableSession(
            initialVersion = "0.4.19",
            upgradedVersion = "0.4.20",
            upgradeResult = ExecResult("ok", "", 0),
        )
        val rig = newViewModelRig({ session }, expectedVersion = "0.4.20")
        // Deliberately do NOT bind — bound stays null.

        rig.vm.runHostPocketshellUpgrade()
        advanceUntilIdle()

        val state = rig.vm.cliVersionUpdateState.value
        assertTrue(
            "an unbound upgrade must end in a Failure state — was $state",
            state is FolderListViewModel.CliVersionUpdateState.Failure,
        )
        val message = (state as FolderListViewModel.CliVersionUpdateState.Failure).message
        assertFalse(
            "must NOT falsely claim 'Not connected to the host — reconnect' — was: $message",
            message.contains("Not connected"),
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun leaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = HOST.hostname,
                port = HOST.port,
                user = HOST.username,
                credentialId = "${HOST.id}:$KEY_PATH",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(java.io.File(KEY_PATH)),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

    private class Rig(
        val vm: FolderListViewModel,
        val manager: SshLeaseManager,
        val connectCounter: java.util.concurrent.atomic.AtomicInteger,
    ) {
        val connectCount: Int get() = connectCounter.get()
    }

    private fun TestScope.newViewModelRig(
        sessionSupplier: () -> SshSession,
        expectedVersion: String,
    ): Rig {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val connectCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val manager = SshLeaseManager(
            connector = SshLeaseConnector {
                connectCounter.incrementAndGet()
                Result.success(sessionSupplier())
            },
            scope = this,
            idleTtlMillis = 0L,
            connectTimeoutContext = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
        val vm = FolderListViewModel(
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
            it.treeDispatcher = dispatcher
            it.setHostPocketshellUpgradeForTest(
                HostPocketshellUpgrade().apply { execDispatcher = dispatcher },
            )
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
        return Rig(vm, manager, connectCounter)
    }

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
            it.treeDispatcher = dispatcher
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
        connectedOverride: Boolean = true,
    ) : SshSession {
        @Volatile var upgradeRan: Boolean = false

        // Issue #1157: mutable so a test can flip a warm session to "expired"
        // (transport went stale after a network handoff) mid-scenario.
        @Volatile override var isConnected: Boolean = connectedOverride

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
