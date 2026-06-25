package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Epic #821 slice C (issue #837): the durable daemon-backed project tree — the
 * acceptance journey at the ViewModel layer.
 *
 * The added maintainer-wording criterion: the tree's order + expand/collapse
 * survives BOTH
 *  (a) a same-host RECONNECT (the regression guard — must stay green), AND
 *  (b) an app process KILL + relaunch (the new daemon-hydrate path),
 * and a refresh reconciles gone/added as DELTAS only.
 *
 * Modelled with an in-memory [FakeTreeDaemon] standing in for the host-side
 * `pocketshell tree` registry: a [RoutingTreeSshSession] serves `tree get` /
 * `tree upsert` / `tree reconcile` from it (the REAL [TreeRemoteSource] parses
 * those envelopes). A "restart" is a brand-new [FolderListViewModel] bound to
 * the same host with the SAME daemon registry — proving the durability is in the
 * host store, NOT in any client-side cache (D22).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelTreeDurabilityTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    // ---- (b) RESTART: collapse + order survive an app kill + relaunch -------

    @Test
    fun collapseAndOrderSurviveAppRestart() = runTest {
        val daemon = FakeTreeDaemon()
        val gateway = StubGateway(listOf(sessionRow("beta"), sessionRow("alpha")))

        // ---- First app session: bind, collapse a folder, persist. ----
        val vm1 = newViewModel(gateway, daemon)
        bind(vm1)
        awaitReady(vm1)
        val alphaFolder = folderPath("alpha")
        assertTrue("alpha auto-expands on first ready", alphaFolder in expandedPaths(vm1))

        // User collapses alpha — this fire-and-forget upserts to the daemon.
        vm1.toggleProjectExpanded(alphaFolder)
        advanceUntilIdle()
        assertFalse("alpha collapses on tap", alphaFolder in expandedPaths(vm1))
        // The collapse was persisted host-side.
        assertTrue("collapse persisted to the daemon registry", daemon.hasHost(HOST.name))
        assertTrue(daemon.collapsedFolders(HOST.name).contains(alphaFolder))

        // ---- "App restart": a brand-new VM, SAME daemon registry. ----
        val vm2 = newViewModel(gateway, daemon)
        bind(vm2)
        awaitReady(vm2)

        // The held order is restored (beta before alpha, as persisted) and the
        // collapsed folder STAYS collapsed across the process death — proven from
        // the durable host registry, with NO client-side cache (D22).
        val order = readySessions(vm2)
        assertEquals(listOf("beta", "alpha"), order)
        assertFalse(
            "a folder collapsed before the restart must stay collapsed after relaunch",
            alphaFolder in expandedPaths(vm2),
        )
    }

    // ---- (a) RECONNECT regression guard: same-host re-bind keeps state -----

    @Test
    fun collapseAndOrderSurviveSameHostReconnect() = runTest {
        val daemon = FakeTreeDaemon()
        val gateway = StubGateway(listOf(sessionRow("beta"), sessionRow("alpha")))
        val vm = newViewModel(gateway, daemon)

        bind(vm)
        awaitReady(vm)
        val alphaFolder = folderPath("alpha")
        vm.toggleProjectExpanded(alphaFolder)
        advanceUntilIdle()
        assertFalse(alphaFolder in expandedPaths(vm))

        // A same-host RECONNECT is a re-bind with identical params (the VM is NOT
        // recreated — only a host CHANGE resets the held tree). The held order +
        // collapse must be preserved in place, with no Loading flash.
        bind(vm)
        awaitReady(vm)
        assertEquals(listOf("beta", "alpha"), readySessions(vm))
        assertFalse(
            "same-host reconnect must NOT re-open the collapsed folder",
            alphaFolder in expandedPaths(vm),
        )
    }

    // ---- refresh reconciles gone/added as DELTAS only ----------------------

    @Test
    fun resumeReconcilePrunesGoneSessionAsDelta() = runTest {
        val daemon = FakeTreeDaemon()
        // Seed the daemon registry as if a prior session persisted two sessions.
        daemon.seed(
            HOST.name,
            listOf(
                FakeTreeDaemon.Node("beta", 0, folderPath("beta"), false),
                FakeTreeDaemon.Node("alpha", 1, folderPath("alpha"), false),
            ),
        )
        // The gateway probe (only fired on the cold-start reconcile) returns both.
        val gateway = StubGateway(listOf(sessionRow("beta"), sessionRow("alpha")))
        val vm = newViewModel(gateway, daemon)

        bind(vm)
        awaitReady(vm)
        assertEquals(listOf("beta", "alpha"), readySessions(vm))

        // `alpha` is killed out-of-band: the daemon's live enumeration now omits
        // it. A resume-when-stale should reconcile this as a DELTA (prune alpha)
        // WITHOUT a full gateway reload — the gateway is told to fail loudly if
        // it is probed, proving the delta path was used.
        daemon.setLive(HOST.name, setOf("beta"))
        gateway.rows = null // a full re-probe would now blow up

        // Drive a resume past the freshen window.
        vm.forceTreeStaleForTest()
        vm.setProcessStartedForTest(false)
        advanceUntilIdle()
        vm.setProcessStartedForTest(true)
        awaitReady(vm)

        assertEquals(
            "the gone session is pruned via the delta path, no full reload",
            listOf("beta"),
            readySessions(vm),
        )
    }

    @Test
    fun nonZeroTreeGetFallsBackToGatewayReadyWithoutLoadingHang() = runTest {
        val daemon = FakeTreeDaemon()
        val gateway = StubGateway(listOf(sessionRow("alpha")))
        val vm = newViewModel(
            gateway = gateway,
            daemon = daemon,
            treeGetResult = ExecResult("", "Error: No such command 'tree'.", 2),
        )

        bind(vm)
        awaitReady(vm)

        assertEquals(
            "a missing/old tree CLI must not pin the cold-start screen behind the durable hydrate",
            listOf("alpha"),
            readySessions(vm),
        )
    }

    @Test
    fun wedgedResumeTreeReconcileFallsBackToFullGatewayProbe() = runTest {
        val daemon = FakeTreeDaemon()
        daemon.seed(
            HOST.name,
            listOf(
                FakeTreeDaemon.Node("beta", 0, folderPath("beta"), false),
                FakeTreeDaemon.Node("alpha", 1, folderPath("alpha"), false),
            ),
        )
        val gateway = StubGateway(listOf(sessionRow("beta"), sessionRow("alpha")))
        val vm = newViewModel(
            gateway = gateway,
            daemon = daemon,
            treeReconcileWedges = true,
            treeRemoteExecTimeoutMs = Long.MAX_VALUE,
        ).apply {
            reconcileTimeoutMs = 100L
        }

        bind(vm)
        awaitReady(vm)
        assertEquals(listOf("beta", "alpha"), readySessions(vm))

        gateway.rows = listOf(sessionRow("beta"))
        vm.forceTreeStaleForTest()
        vm.setProcessStartedForTest(false)
        advanceUntilIdle()
        vm.setProcessStartedForTest(true)

        awaitSessionOrder(vm, listOf("beta"))
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

    private fun expandedPaths(vm: FolderListViewModel): Set<String> =
        (vm.state.value as FolderListUiState.Ready).expandedProjectPaths

    private fun readySessions(vm: FolderListViewModel): List<String> =
        (vm.state.value as FolderListUiState.Ready).flatSessions.map { it.sessionName }

    private suspend fun TestScope.awaitReady(vm: FolderListViewModel): FolderListUiState.Ready {
        repeat(200) {
            advanceUntilIdle()
            val state = vm.state.value
            if (state is FolderListUiState.Ready) return state
            delay(10L)
        }
        throw AssertionError("expected Ready, was ${vm.state.value}")
    }

    private suspend fun TestScope.awaitSessionOrder(
        vm: FolderListViewModel,
        expected: List<String>,
    ) {
        repeat(200) {
            advanceUntilIdle()
            if (vm.state.value is FolderListUiState.Ready && readySessions(vm) == expected) return
            delay(10L)
        }
        throw AssertionError("expected sessions $expected, was ${vm.state.value}")
    }

    private fun folderPath(name: String): String =
        FolderListViewModel.canonicalisePath("/home/alexey/git/$name")

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = true,
            cwd = "/home/alexey/git/$name",
        )

    private fun TestScope.newViewModel(
        gateway: FolderListGateway,
        daemon: FakeTreeDaemon,
        treeGetResult: ExecResult? = null,
        treeReconcileWedges: Boolean = false,
        treeRemoteExecTimeoutMs: Long = 12_000L,
    ): FolderListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val session = RoutingTreeSshSession(
            daemon = daemon,
            treeGetResult = treeGetResult,
            treeReconcileWedges = treeReconcileWedges,
        )
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
            treeRemoteSource = TreeRemoteSource().apply {
                remoteExecTimeoutMs = treeRemoteExecTimeoutMs
                remoteExecDispatcher = dispatcher
            },
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.treeDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    /** In-memory stand-in for the host-side `pocketshell tree` registry. */
    private class FakeTreeDaemon {
        data class Node(
            val session: String,
            val order: Int,
            val folderPath: String,
            val collapsed: Boolean,
            val foreignKind: String? = null,
        )

        private val nodesByHost = LinkedHashMap<String, MutableList<Node>>()
        private val liveByHost = LinkedHashMap<String, Set<String>>()

        fun hasHost(host: String): Boolean = nodesByHost.containsKey(host)

        fun collapsedFolders(host: String): Set<String> =
            nodesByHost[host].orEmpty().filter { it.collapsed }.map { it.folderPath }.toSet()

        fun seed(host: String, nodes: List<Node>) {
            nodesByHost[host] = nodes.toMutableList()
        }

        fun setLive(host: String, names: Set<String>) {
            liveByHost[host] = names
        }

        @Synchronized
        fun get(host: String): JSONObject {
            val nodes = nodesByHost[host].orEmpty()
            val arr = JSONArray()
            nodes.forEach { n ->
                val o = JSONObject()
                    .put("session", n.session)
                    .put("order", n.order)
                    .put("folder_path", n.folderPath)
                    .put("collapsed", n.collapsed)
                if (n.foreignKind != null) o.put("foreign_kind", n.foreignKind)
                arr.put(o)
            }
            return JSONObject().put("nodes", arr).put("version", nodes.size)
        }

        @Synchronized
        fun upsert(host: String, request: JSONObject): JSONObject {
            val arr = request.optJSONArray("nodes") ?: JSONArray()
            val parsed = ArrayList<Node>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("session").takeIf { it.isNotBlank() } ?: continue
                parsed.add(
                    Node(
                        session = name,
                        order = o.optInt("order", i),
                        folderPath = o.optString("folder_path", ""),
                        collapsed = o.optBoolean("collapsed", false),
                        foreignKind = o.optString("foreign_kind", "").takeIf { it.isNotBlank() },
                    ),
                )
            }
            nodesByHost[host] = parsed
            return JSONObject().put("status", "ok").put("version", parsed.size)
        }

        @Synchronized
        fun reconcile(host: String): JSONObject {
            val nodes = nodesByHost[host].orEmpty()
            val registryNames = nodes.map { it.session }
            val live = liveByHost[host]
            if (live == null) {
                // Enumeration unavailable — nothing pruned.
                return JSONObject()
                    .put("alive", JSONArray(registryNames))
                    .put("gone", JSONArray())
                    .put("added", JSONArray())
            }
            val alive = registryNames.filter { it in live }
            val gone = registryNames.filter { it !in live }
            val added = live.filter { it !in registryNames }
            // Prune gone from the registry (deltas only).
            nodesByHost[host] = nodes.filter { it.session in live }.toMutableList()
            return JSONObject()
                .put("alive", JSONArray(alive))
                .put("gone", JSONArray(gone))
                .put("added", JSONArray(added))
        }
    }

    /** Serves `pocketshell tree <verb>` from the [FakeTreeDaemon]. */
    private class RoutingTreeSshSession(
        private val daemon: FakeTreeDaemon,
        private val treeGetResult: ExecResult? = null,
        private val treeReconcileWedges: Boolean = false,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            val request = extractRequestJson(command)
            val host = request.optString("host")
            val envelope = when {
                command.contains("tree get") -> return treeGetResult ?: ExecResult(daemon.get(host).toString(), "", 0)
                command.contains("tree upsert") -> daemon.upsert(host, request)
                command.contains("tree reconcile") -> {
                    if (treeReconcileWedges) delay(Long.MAX_VALUE)
                    daemon.reconcile(host)
                }
                else -> return ExecResult("", "no route", 127)
            }
            return ExecResult(envelope.toString(), "", 0)
        }

        /**
         * The wrapped command is `printf %s '<json>' | <pocketshell wrap>`. Pull
         * the single-quoted JSON payload back out (undoing the `'\''` escape).
         */
        private fun extractRequestJson(command: String): JSONObject {
            val start = command.indexOf('\'')
            val end = command.indexOf("' |")
            if (start < 0 || end <= start) return JSONObject()
            val quoted = command.substring(start + 1, end)
            val unescaped = quoted.replace("'\"'\"'", "'")
            return runCatching { JSONObject(unescaped) }.getOrDefault(JSONObject())
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
        ): FolderListResult {
            val r = rows ?: error("gateway probe must not be called on the delta path")
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
