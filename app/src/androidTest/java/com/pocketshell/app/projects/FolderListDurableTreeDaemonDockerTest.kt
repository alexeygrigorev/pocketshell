package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.proof.DEFAULT_HOST
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
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #839 (epic #821 workstream C, #837 follow-up) — the END-TO-END
 * durable-tree journey on a REAL device + REAL daemon. #837 was approved on
 * JVM-level proofs (an in-memory `FakeTreeDaemon` standing in for the host-side
 * `pocketshell tree` registry); the reviewer flagged the non-blocking gap that
 * the daemon was never installed on a Docker fixture, so the `tree.*` RPCs could
 * not be exercised end-to-end on-device. This is that missing journey.
 *
 * ## What it proves on the real path (no proxy)
 *
 * The PRODUCTION [FolderListViewModel] + a REAL [TreeRemoteSource] drive
 * `pocketshell tree get|upsert|reconcile` over a warm SSH session against the
 * `agents-daemon` Docker fixture (port 2239,
 * `tests/docker/Dockerfile.agents-daemon`) whose `pocketshell` is the REAL Python
 * package — the genuine `tree.py` daemon code persisting a host-keyed JSON
 * registry under `$XDG_STATE_HOME/pocketshell/tree/registry.json`. That registry
 * is HOST-SIDE state, so it survives an Android app kill + relaunch (the #837
 * durable property the JVM proof could only model).
 *
 * The journey, in the issue's words ("collapse a folder + establish an order,
 * kill + relaunch the app, assert the tree hydrates with the held order/collapse
 * INSTANTLY, then a refresh reconciles gone/added as deltas"):
 *
 * 1. **First app session** binds the daemon host. Two real tmux sessions seeded
 *    under a watched root render in the live tree. The user COLLAPSES that
 *    folder — `persistTree` fire-and-forgets a `tree.upsert` to the daemon.
 * 2. The collapse + order is confirmed PERSISTED host-side via a direct
 *    out-of-band `pocketshell tree get` over a fresh SSH session (proving the
 *    durability lives in the host JSON registry, not any client cache — D22).
 * 3. **App kill + relaunch**: the first VM's store is cleared and a BRAND-NEW
 *    [FolderListViewModel] + a NEW [TreeRemoteSource] bind the same host over a
 *    NEW SSH session. The cold-start hydrate reads the held order/collapse back
 *    and renders it INSTANTLY — the collapsed folder STAYS collapsed across the
 *    process death (the load-bearing daemon-hydrate assertion).
 * 4. **Refresh reconciles deltas**: a session is killed out-of-band on the host,
 *    then a resume-when-stale fires the daemon `tree.reconcile`, which diffs the
 *    registry against the LIVE `tmuxctl list` and PRUNES the gone session as a
 *    DELTA (no full reload). A newly-added session is then picked up too.
 *
 * ## RED / GREEN
 *
 * RED (if the daemon durability were broken — e.g. `tree.upsert` not persisting,
 * or the registry NOT surviving the new SSH session): the relaunched VM2 would
 * NOT see the held collapse — the folder would auto-EXPAND on first ready (its
 * default), failing the "stays collapsed across relaunch" assertion. GREEN: the
 * host-side registry hydrates the collapse instantly. The two pre-conditions
 * (the fixture really persists; the registry shows the collapse over a fresh SSH
 * session) are asserted explicitly before the VM journey so the proof can never
 * pass vacuously against a daemon that silently dropped the write.
 *
 * ## CI gating (#839 — this test RUNS on per-push CI)
 *
 * The `emulator-journey` workflow now brings up the `agents-daemon` fixture on
 * host port 2239 (and a sanity step verifies its real `pocketshell tree`
 * persists), AND this class is wired into
 * `scripts/ci-journey-suite.sh::JOURNEY_CLASSES`, mirroring the `agents-old-cli`
 * (#849) promotion precedent. So there is NO `assumeFalse(isRunningOnCi())`
 * self-skip — the load-bearing durable-tree assertion runs at PR time (D32/G4/F3;
 * a self-skip would leave it with zero protection). `waitForSshFixtureReady`
 * HARD-fails fast if 2239 is unreachable, so a missing fixture surfaces loudly
 * rather than skipping. The always-runnable JVM backstop is
 * `FolderListViewModelTreeDurabilityTest` (per-push Unit job). Locally the fixture
 * is brought up with
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents-daemon`
 * and the test runs via `scripts/connected-test.sh --suffix i839`.
 *
 * Docker service: `agents-daemon` on host port `2239`.
 */
@RunWith(AndroidJUnit4::class)
class FolderListDurableTreeDaemonDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0
    private val createdSessions = mutableListOf<String>()
    private var hostName: String = "issue839-host"

    @Before
    fun setUp(): Unit = runBlocking {
        // Issue #839: NO assumeFalse(isRunningOnCi()) self-skip. The
        // emulator-journey workflow now brings up the `agents-daemon` fixture on
        // port 2239 (and verifies its real `pocketshell tree` persists) AND this
        // class is wired into scripts/ci-journey-suite.sh::JOURNEY_CLASSES, so the
        // load-bearing durable-tree assertion RUNS on per-push CI (D32/G4/F3 — a
        // self-skip would leave it with zero protection). waitForSshFixtureReady
        // below HARD-fails fast if 2239 is unreachable, so a missing fixture
        // surfaces loudly instead of a vacuous skip (G3/G10). The always-runnable
        // backstop is the JVM FolderListViewModelTreeDurabilityTest (Unit job).
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keyFile = File(context.cacheDir, "issue839-daemon-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        waitForSshFixtureReady(sshKey, port = DAEMON_PORT)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        viewModelStore.clear()
        runCatching {
            withTimeout(20_000) {
                connect().use { session ->
                    for (name in createdSessions) {
                        runCatching {
                            session.exec("tmux kill-session -t $name 2>/dev/null || true")
                        }
                    }
                    // Reset the durable registry for this host so a re-run starts
                    // clean (the host-side state is, by design, persistent).
                    runCatching {
                        session.exec(
                            "rm -f \"\${XDG_STATE_HOME:-\$HOME/.local/state}\"" +
                                "/pocketshell/tree/registry.json 2>/dev/null || true",
                        )
                    }
                }
            }
        }
        runCatching { db.close() }
        runCatching { keyFile.delete() }
    }

    /**
     * The load-bearing journey: collapse a folder + hold an order, KILL +
     * RELAUNCH the app, and assert the daemon-hydrated tree renders the held
     * order/collapse INSTANTLY, then a resume reconcile prunes a gone session as
     * a delta and a refresh picks up an added session.
     */
    @Test
    fun durableTreeSurvivesAppKillAndRelaunch_thenReconcilesDeltas(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        hostName = "issue839-host-$suffix"
        val rootDir = "/tmp/issue839-$suffix"
        val alphaDir = "$rootDir/alpha"
        val betaDir = "$rootDir/beta"
        val alphaSession = "issue839-alpha-$suffix"
        val betaSession = "issue839-beta-$suffix"
        val alphaFolder = FolderListViewModel.canonicalisePath(alphaDir)
        val watchRoot = FolderListViewModel.canonicalisePath(rootDir)

        // --- Pre-condition A: a CLEAN registry for this fresh host name, and a
        // fixture whose `pocketshell tree` REALLY persists (not a no-op). Seed two
        // real tmux sessions, one per project folder under the watched root, so
        // the live tree groups them under their folders. ---
        withTimeout(30_000) {
            connect().use { session ->
                session.exec("mkdir -p $alphaDir $betaDir")
                session.exec("tmux new-session -d -s $alphaSession -c $alphaDir")
                session.exec("tmux new-session -d -s $betaSession -c $betaDir")
                createdSessions += alphaSession
                createdSessions += betaSession
                // Prove this fixture's `tree` actually persists: upsert a probe,
                // get it back over the SAME session. If the daemon were a no-op
                // the get would be empty and the durability journey would be
                // vacuous, so fail loud here.
                val probeHost = "issue839-probe-$suffix"
                session.exec(
                    "printf '%s' '{\"host\":\"$probeHost\",\"nodes\":" +
                        "[{\"session\":\"p\",\"order\":0,\"folder_path\":\"/x\",\"collapsed\":true}]}' " +
                        "| pocketshell tree upsert",
                )
                val probeGet = session.exec(
                    "printf '%s' '{\"host\":\"$probeHost\"}' | pocketshell tree get",
                )
                assertTrue(
                    "the agents-daemon fixture's `pocketshell tree` must REALLY persist " +
                        "(get returned: ${probeGet.stdout} / exit=${probeGet.exitCode}) — " +
                        "otherwise the durability journey is vacuous",
                    probeGet.exitCode == 0 &&
                        probeGet.stdout.contains("\"session\": \"p\"") &&
                        probeGet.stdout.contains("\"collapsed\": true"),
                )
            }
        }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue839-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = hostName,
                hostname = DEFAULT_HOST,
                port = DAEMON_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        // Persist the watched root so the live tree buckets the two sessions under
        // their project folders (the grouping the collapse acts on).
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "issue839", path = watchRoot),
        )
        val host = db.hostDao().getById(hostId)!!

        // --- Phase 1: first app session — bind, render the live tree, collapse
        // the alpha folder (which fire-and-forget upserts to the daemon). ---
        val vm1 = newViewModel()
        vm1.setProcessStartedForTest(true)
        bind(vm1, host)

        val ready1 = awaitReadyWithSessions(vm1, setOf(alphaSession, betaSession))
        assertTrue(
            "alpha must auto-expand before the user collapses it — expanded=" +
                "${ready1.expandedProjectPaths}",
            alphaFolder in ready1.expandedProjectPaths,
        )

        // The user collapses the alpha folder.
        vm1.toggleProjectExpanded(alphaFolder)
        // The collapse is reflected locally immediately.
        withTimeout(10_000) {
            while (alphaFolder in readyExpanded(vm1)) delay(100L)
        }

        // --- Phase 2: confirm the collapse PERSISTED host-side over a FRESH SSH
        // session (the app-kill-equivalent: the registry is host state, read with
        // a brand-new connection). ---
        withTimeout(20_000) {
            // The upsert is fire-and-forget; poll the host registry until it
            // reflects the collapse (or fail loud after the window).
            var sawCollapse = false
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val got = connect().use { session ->
                    session.exec(
                        "printf '%s' '{\"host\":\"$hostName\"}' | pocketshell tree get",
                    )
                }
                if (got.exitCode == 0 &&
                    got.stdout.contains("\"session\": \"$alphaSession\"") &&
                    registryMarksFolderCollapsed(got.stdout, alphaSession)
                ) {
                    sawCollapse = true
                    break
                }
                delay(500L)
            }
            assertTrue(
                "the collapsed alpha folder must be PERSISTED to the host-side " +
                    "registry (durable across a fresh SSH session)",
                sawCollapse,
            )
        }

        // --- Phase 3: app kill + relaunch — a BRAND-NEW VM + a NEW TreeRemoteSource
        // bind the same host. The cold-start hydrate reads the held order/collapse
        // from the daemon and renders it; the collapsed folder STAYS collapsed. ---
        viewModelStore.clear() // simulate the process death tearing down vm1
        val vm2 = newViewModel()
        vm2.setProcessStartedForTest(true)
        bind(vm2, host)

        val ready2 = awaitReadyWithSessions(vm2, setOf(alphaSession, betaSession))
        // The LOAD-BEARING daemon-hydrate assertion: a folder collapsed before the
        // kill must stay collapsed after the relaunch — restored from the durable
        // host registry, NOT any client cache (this VM has none).
        assertFalse(
            "a folder collapsed before the app kill MUST stay collapsed after " +
                "relaunch (daemon-hydrated) — expanded=${ready2.expandedProjectPaths}",
            alphaFolder in ready2.expandedProjectPaths,
        )

        // --- Phase 4: a refresh reconciles gone/added as DELTAS. Kill beta
        // out-of-band; a resume-when-stale fires the daemon `tree.reconcile`,
        // which diffs the registry against the LIVE `tmuxctl list` and prunes beta
        // as a gone DELTA (no full reload). ---
        withTimeout(20_000) {
            connect().use { session ->
                session.exec("tmux kill-session -t $betaSession")
            }
        }
        // Drive a resume-when-stale: background → foreground past the freshen
        // window so maybeReconcileOnResume takes the daemon delta path.
        vm2.forceTreeStaleForTest()
        vm2.setProcessStartedForTest(false)
        delay(200L)
        vm2.setProcessStartedForTest(true)

        withTimeout(30_000) {
            while (true) {
                val s = vm2.state.value
                if (s is FolderListUiState.Ready &&
                    s.flatSessions.none { it.sessionName == betaSession } &&
                    s.flatSessions.any { it.sessionName == alphaSession }
                ) {
                    break
                }
                delay(250L)
            }
        }
        val afterPrune = vm2.state.value as FolderListUiState.Ready
        assertTrue(
            "the killed beta session must be PRUNED by the daemon reconcile delta — " +
                "sessions=${afterPrune.flatSessions.map { it.sessionName }}",
            afterPrune.flatSessions.none { it.sessionName == betaSession } &&
                afterPrune.flatSessions.any { it.sessionName == alphaSession },
        )
        // The collapse survives the reconcile too (still collapsed).
        assertFalse(
            "the collapse must survive the reconcile — expanded=" +
                "${afterPrune.expandedProjectPaths}",
            alphaFolder in afterPrune.expandedProjectPaths,
        )

        // --- Phase 5: a newly-ADDED session is picked up on refresh (the added
        // side of the delta). Create a fresh session, then refresh. ---
        val gammaDir = "$rootDir/gamma"
        val gammaSession = "issue839-gamma-$suffix"
        withTimeout(20_000) {
            connect().use { session ->
                session.exec("mkdir -p $gammaDir")
                session.exec("tmux new-session -d -s $gammaSession -c $gammaDir")
                createdSessions += gammaSession
            }
        }
        vm2.refreshSessions()
        withTimeout(30_000) {
            while (true) {
                val s = vm2.state.value
                if (s is FolderListUiState.Ready &&
                    s.flatSessions.any { it.sessionName == gammaSession } &&
                    !s.isRefreshing
                ) {
                    break
                }
                delay(250L)
            }
        }
        val afterAdd = vm2.state.value as FolderListUiState.Ready
        assertTrue(
            "a newly-added session must be reconciled in — sessions=" +
                "${afterAdd.flatSessions.map { it.sessionName }}",
            afterAdd.flatSessions.any { it.sessionName == gammaSession } &&
                afterAdd.flatSessions.any { it.sessionName == alphaSession },
        )
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

    private fun readyExpanded(vm: FolderListViewModel): Set<String> =
        (vm.state.value as? FolderListUiState.Ready)?.expandedProjectPaths ?: emptySet()

    private suspend fun awaitReadyWithSessions(
        vm: FolderListViewModel,
        expected: Set<String>,
    ): FolderListUiState.Ready {
        withTimeout(40_000) {
            while (true) {
                val s = vm.state.value
                if (s is FolderListUiState.Ready &&
                    expected.all { name -> s.flatSessions.any { it.sessionName == name } }
                ) {
                    return@withTimeout
                }
                delay(250L)
            }
        }
        return vm.state.value as FolderListUiState.Ready
    }

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DAEMON_PORT,
        user = DEFAULT_USER,
        key = sshKey,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 10_000,
    ).getOrThrow()

    /**
     * Parse the `pocketshell tree get` JSON envelope and report whether [session]'s
     * node carries `"collapsed": true`. A small string scan (no JSON dep here)
     * keyed off the session name so it is robust to whitespace / key order.
     */
    private fun registryMarksFolderCollapsed(stdout: String, session: String): Boolean {
        val root = runCatching { org.json.JSONObject(stdout.trim()) }.getOrNull() ?: return false
        val nodes = root.optJSONArray("nodes") ?: return false
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (node.optString("session") == session) {
                return node.optBoolean("collapsed", false)
            }
        }
        return false
    }

    private fun newViewModel(): FolderListViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FolderListViewModel(
            gateway = SshFolderListGateway(),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(context),
            // A REAL TreeRemoteSource so the cold-start hydrate + persist +
            // resume-reconcile all exercise the genuine `pocketshell tree` daemon
            // on the host. NO treeClientCache: this isolates the DAEMON durable
            // path (#837/#839) from the client-side instant-render cache (#867),
            // so the relaunch's instant collapse can only come from the host.
            treeRemoteSource = TreeRemoteSource(),
            attachLifecycle = false,
        ).also { vm ->
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", vm)
        }
    }

    private companion object {
        const val DAEMON_PORT: Int = 2239
    }
}
