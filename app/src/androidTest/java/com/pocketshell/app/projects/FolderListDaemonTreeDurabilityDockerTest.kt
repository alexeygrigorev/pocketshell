package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #839: connected emulator + Docker proof for the daemon-backed durable
 * project tree added in #837.
 *
 * The standard `agents` Docker fixture now exposes the `pocketshell tree`
 * seam with a daemon-ready marker. This journey uses the production
 * [TreeRemoteSource] and the real [SshFolderListGateway] against that fixture:
 *
 * 1. Open a host and let the live tmux probe seed the tree.
 * 2. Collapse a folder, which persists order/collapse to the host registry.
 * 3. Simulate app kill + relaunch with a fresh [FolderListViewModel] and no
 *    client cache; the daemon hydrate must render the held order/collapse before
 *    the first full gateway probe completes.
 * 4. Resume when stale: a gone-only delta prunes without a full probe, while an
 *    added-session delta escalates to the real full probe so the new row appears.
 */
@RunWith(AndroidJUnit4::class)
class FolderListDaemonTreeDurabilityDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0
    private val createdSessions = mutableListOf<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keyFile = File(context.cacheDir, "issue839-daemon-tree-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        waitForSshFixtureReady(sshKey)
        assertTreeDaemonReady()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        viewModelStore.clear()
        if (createdSessions.isNotEmpty()) {
            runCatching {
                withSshSession { session ->
                    createdSessions.forEach { name ->
                        runCatching { session.exec("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true") }
                    }
                }
            }
        }
        runCatching { db.close() }
        runCatching { keyFile.delete() }
    }

    @Test
    fun daemonTreeSurvivesAppRelaunchAndResumeDeltas(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val hostName = "issue839-host-$suffix"
        val baseDir = "/tmp/issue839-$suffix"
        val betaSession = "issue839-beta-$suffix"
        val alphaSession = "issue839-alpha-$suffix"
        val addedSession = "issue839-added-$suffix"
        val betaFolder = FolderListViewModel.canonicalisePath("$baseDir/beta")
        val alphaFolder = FolderListViewModel.canonicalisePath("$baseDir/alpha")

        seedTmuxSession(betaSession, betaFolder)
        seedTmuxSession(alphaSession, alphaFolder)
        upsertEmptyRemoteTree(hostName)

        val host = insertHost(hostName)

        val firstGateway = CountingGateway()
        val firstVm = newViewModel(firstGateway)
        firstVm.setProcessStartedForTest(true)
        bind(firstVm, host)

        val firstReady = awaitReadyContaining(firstVm, setOf(betaSession, alphaSession))
        val persistedOrder = firstReady.flatSessions.map { it.sessionName }
            .filter { it == betaSession || it == alphaSession }
        assertEquals("both seeded sessions must establish the persisted order", 2, persistedOrder.size)
        assertTrue("alpha starts expanded before the user collapse", alphaFolder in firstReady.expandedProjectPaths)

        firstVm.toggleProjectExpanded(alphaFolder)
        awaitCollapsed(firstVm, alphaFolder)
        awaitRemoteTree(hostName) { tree ->
            val nodes = tree.getJSONArray("nodes")
            (0 until nodes.length()).any { index ->
                val node = nodes.getJSONObject(index)
                node.getString("session") == alphaSession &&
                    node.getString("folder_path") == alphaFolder &&
                    node.getBoolean("collapsed")
            }
        }

        viewModelStore.clear()

        val relaunchGateway = CountingGateway(delayBeforeFirstListMs = 2_000L)
        val relaunchVm = newViewModel(relaunchGateway)
        relaunchVm.setProcessStartedForTest(true)
        bind(relaunchVm, host)

        val hydrated = awaitReadyOrder(relaunchVm, persistedOrder)
        assertFalse(
            "daemon hydrate must preserve the collapsed folder before the full probe completes",
            alphaFolder in hydrated.expandedProjectPaths,
        )
        assertEquals(
            "the durable daemon hydrate must render before the first full gateway probe completes",
            0,
            relaunchGateway.completedListCalls,
        )

        awaitGatewayCalls(relaunchGateway, completed = 1)
        awaitReadyOrder(relaunchVm, persistedOrder)

        killTmuxSession(betaSession)
        val beforeGoneDeltaProbeCount = relaunchGateway.completedListCalls
        relaunchVm.forceTreeStaleForTest()
        resumeFromBackground(relaunchVm)
        awaitSessionsAbsent(relaunchVm, setOf(betaSession))
        assertEquals(
            "gone-only daemon delta must prune without a full gateway reload",
            beforeGoneDeltaProbeCount,
            relaunchGateway.completedListCalls,
        )

        seedTmuxSession(addedSession, "$baseDir/added")
        relaunchVm.forceTreeStaleForTest()
        resumeFromBackground(relaunchVm)
        awaitReadyContaining(relaunchVm, setOf(alphaSession, addedSession))
        assertTrue(
            "an added-session daemon delta must escalate to the real full probe",
            relaunchGateway.completedListCalls > beforeGoneDeltaProbeCount,
        )
    }

    private suspend fun assertTreeDaemonReady() {
        val probe = runCatching {
            withSshSession { session ->
                val status = session.exec("pocketshell daemon status")
                val tree = session.exec(
                    "printf %s ${shellQuote("{\"host\":\"issue839-probe\"}")} | pocketshell tree get",
                )
                TreeDaemonProbe(
                    ready = status.exitCode == 0 && tree.exitCode == 0 && tree.stdout.contains("\"nodes\""),
                    statusExitCode = status.exitCode,
                    statusOutput = status.stdout.trim(),
                    treeExitCode = tree.exitCode,
                    treeOutput = tree.stdout.trim(),
                )
            }
        }.getOrElse { error ->
            TreeDaemonProbe(
                ready = false,
                failure = error.stackTraceToString(),
            )
        }
        assertTrue(
            "Docker agents fixture must expose the daemon-backed tree seam; probe=$probe",
            probe.ready,
        )
    }

    private suspend fun seedTmuxSession(sessionName: String, folder: String) {
        withSshSession { session ->
            session.exec("mkdir -p ${shellQuote(folder)}")
            session.exec("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
            session.exec("tmux new-session -d -s ${shellQuote(sessionName)} -c ${shellQuote(folder)}")
        }
        if (sessionName !in createdSessions) createdSessions += sessionName
    }

    private suspend fun killTmuxSession(sessionName: String) {
        withSshSession { session ->
            session.exec("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
        }
        createdSessions.remove(sessionName)
    }

    private suspend fun upsertEmptyRemoteTree(hostName: String) {
        withSshSession { session ->
            val json = "{\"host\":\"$hostName\",\"nodes\":[]}"
            session.exec("printf %s ${shellQuote(json)} | pocketshell tree upsert")
        }
    }

    private suspend fun awaitRemoteTree(hostName: String, predicate: (JSONObject) -> Boolean) {
        withSshSession { session ->
            withTimeout(20_000L) {
                while (true) {
                    val json = "{\"host\":\"$hostName\"}"
                    val result = session.exec("printf %s ${shellQuote(json)} | pocketshell tree get")
                    if (result.exitCode == 0) {
                        val tree = JSONObject(result.stdout.trim())
                        if (predicate(tree)) return@withTimeout
                    }
                    delay(250L)
                }
            }
        }
    }

    private suspend fun resumeFromBackground(vm: FolderListViewModel) {
        vm.setProcessStartedForTest(false)
        delay(250L)
        vm.setProcessStartedForTest(true)
    }

    private suspend fun <T> withSshSession(block: suspend (com.pocketshell.core.ssh.SshSession) -> T): T {
        return SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 10_000,
        ).getOrThrow().use { session -> block(session) }
    }

    private suspend fun insertHost(hostName: String): HostEntity {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue839-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = hostName,
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        return db.hostDao().getById(hostId)!!
    }

    private fun newViewModel(gateway: FolderListGateway): FolderListViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FolderListViewModel(
            gateway = gateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(context),
            treeRemoteSource = TreeRemoteSource(),
            treeClientCache = null,
            attachLifecycle = false,
        ).also { vm ->
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", vm)
        }
    }

    private fun bind(vm: FolderListViewModel, host: HostEntity) {
        vm.bind(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = keyFile.absolutePath,
            passphrase = null,
        )
    }

    private suspend fun awaitReadyContaining(
        vm: FolderListViewModel,
        expectedSessions: Set<String>,
    ): FolderListUiState.Ready {
        return awaitReadyMatching(vm, "expected sessions $expectedSessions") { state ->
            state.flatSessions.map { it.sessionName }.containsAll(expectedSessions)
        }
    }

    private suspend fun awaitReadyOrder(
        vm: FolderListViewModel,
        expectedOrder: List<String>,
    ): FolderListUiState.Ready {
        return awaitReadyMatching(vm, "expected order $expectedOrder") { state ->
            state.flatSessions.map { it.sessionName }.filter { it in expectedOrder } == expectedOrder
        }
    }

    private suspend fun awaitSessionsAbsent(vm: FolderListViewModel, absentSessions: Set<String>) {
        awaitReadyMatching(vm, "expected absent sessions $absentSessions") { state ->
            state.flatSessions.none { it.sessionName in absentSessions }
        }
    }

    private suspend fun awaitCollapsed(vm: FolderListViewModel, folder: String) {
        awaitReadyMatching(vm, "expected collapsed folder $folder") { state -> folder !in state.expandedProjectPaths }
    }

    private suspend fun awaitReadyMatching(
        vm: FolderListViewModel,
        label: String,
        predicate: (FolderListUiState.Ready) -> Boolean,
    ): FolderListUiState.Ready {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        var lastState: FolderListUiState = vm.state.value
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val state = vm.state.value
            lastState = state
            if (state is FolderListUiState.Ready && predicate(state)) {
                return state
            }
            delay(100L)
        }
        error("$label timed out; lastState=$lastState")
    }

    private suspend fun awaitGatewayCalls(gateway: CountingGateway, completed: Int) {
        withTimeout(30_000L) {
            while (gateway.completedListCalls < completed) delay(100L)
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private data class TreeDaemonProbe(
        val ready: Boolean,
        val statusExitCode: Int? = null,
        val statusOutput: String = "",
        val treeExitCode: Int? = null,
        val treeOutput: String = "",
        val failure: String? = null,
    )

    private class CountingGateway(
        private val delegate: SshFolderListGateway = SshFolderListGateway(),
        private val delayBeforeFirstListMs: Long = 0L,
    ) : FolderListGateway {
        @Volatile
        var startedListCalls: Int = 0
            private set

        @Volatile
        var completedListCalls: Int = 0
            private set

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            startedListCalls += 1
            if (startedListCalls == 1 && delayBeforeFirstListMs > 0L) {
                delay(delayBeforeFirstListMs)
            }
            return delegate.listSessionsWithFolder(host, keyPath, passphrase, watchedRoots)
                .also { completedListCalls += 1 }
        }

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
        ): Result<String> = delegate.createSession(host, keyPath, passphrase, sessionName, cwd, startCommand)

        override suspend fun createEmptyProject(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = delegate.createEmptyProject(host, keyPath, passphrase, parentPath, folderName)

        override suspend fun importFile(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: FolderImportPayload,
        ): Result<String> = delegate.importFile(host, keyPath, passphrase, folderPath, payload)

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> = delegate.killSession(host, keyPath, passphrase, sessionName)
    }
}
