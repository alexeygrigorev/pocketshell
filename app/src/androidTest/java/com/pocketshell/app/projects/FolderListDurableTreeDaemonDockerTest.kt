package com.pocketshell.app.projects

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.tmux.SessionLifecycleSignals
import com.pocketshell.app.tmux.TmuxSessionGeneration
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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

    @get:Rule
    val compose = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0
    private val createdSessions = mutableListOf<String>()
    private var hostName: String = "issue839-host"

    @Before
    fun setUp(): Unit { runBlocking {
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
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        // FolderListScreen owns the production polling lifecycle through its
        // DisposableEffect. Unmount it before clearing the ViewModelStore so a
        // failed journey cannot leave the screen's collector/polling job alive
        // while the next test tears down the SSH fixture.
        runCatching {
            compose.setContent { }
            compose.waitForIdle()
        }
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
    } }

    /**
     * The load-bearing journey: collapse a folder + hold an order, KILL +
     * RELAUNCH the app, and assert the daemon-hydrated tree renders the held
     * order/collapse INSTANTLY, then a resume reconcile prunes a gone session as
     * a delta and a refresh picks up an added session.
     */
    @Test
    fun durableTreeSurvivesAppKillAndRelaunch_thenReconcilesDeltas(): Unit { runBlocking {
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

        // The user collapses the alpha folder. #1829: `toggleProjectExpanded`
        // calls `emitReady()` SYNCHRONOUSLY, and `emitReady` is the Main-confined
        // check-then-act seam over `emitGeneration` — so this needs the same Main
        // hop the `bind` helper below uses. In production this is a Compose tap
        // callback (`FolderListScreen.kt` `onToggleProjectExpanded`), i.e. Main;
        // running it on the instrumentation thread was never the real path.
        onMain { vm1.toggleProjectExpanded(alphaFolder) }
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
    } }

    /**
     * Issue #2239: a lifecycle event emitted for a dead tmux generation must
     * never remove a live same-name successor. This is deliberately in the
     * already-gated daemon journey class so it exercises the production
     * FolderListViewModel + SshFolderListGateway + SessionLifecycleSignals path
     * on an emulator against a real tmux server.
     *
     * The predecessor event is held until AFTER a fresh authoritative probe has
     * painted the successor's exact `$N` + `session_created` identity. Stopping
     * polling before delivery is load-bearing: no follow-up probe can rescue a
     * wrongly name-keyed removal, so that mutation makes the screen-facing Ready
     * state lose its only row and fails the stability observation below.
     */
    @Test
    fun delayedPredecessorKillDoesNotDeleteSameNameSuccessorGeneration(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        hostName = "issue2239-host-$suffix"
        val rootDir = "/tmp/issue2239-$suffix"
        val sessionName = "issue2239-work-$suffix"
        val watchRoot = FolderListViewModel.canonicalisePath(rootDir)
        val evidence = mutableListOf<String>()

        val predecessor = withTimeout(20_000) {
            connect().use { session ->
                assertTrue(
                    "predecessor folder creation must succeed",
                    session.exec("mkdir -p ${shellQuote(rootDir)}").exitCode == 0,
                )
                val created = session.exec(
                    "tmux new-session -d -s ${shellQuote(sessionName)} " +
                        "-c ${shellQuote(rootDir)}",
                )
                assertTrue(
                    "predecessor tmux session/window must be created; " +
                        "exit=${created.exitCode} stderr=${created.stderr}",
                    created.exitCode == 0,
                )
                createdSessions += sessionName
                readRemoteIdentity(session, sessionName)
            }
        }
        evidence += "predecessor_remote=${predecessor.describe()}"

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue2239-key", privateKeyPath = keyFile.absolutePath),
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
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "issue2239", path = watchRoot),
        )
        val host = db.hostDao().getById(hostId)!!
        val signals = SessionLifecycleSignals()
        val vm = newViewModel(signals)
        vm.setProcessStartedForTest(true)

        // Mount the real production screen around this same production
        // FolderListViewModel + SshFolderListGateway. FolderListScreen's own
        // LaunchedEffect performs the bind, so this proof does not inject a
        // test-only bind path that production never uses.
        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = host.id,
                    hostName = host.name,
                    hostname = host.hostname,
                    port = host.port,
                    username = host.username,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _, _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = vm,
                )
            }
        }

        val predecessorReady = awaitExactSession(vm, sessionName, predecessor.generation)
        evidence += "predecessor_ui=${predecessorReady.describeIdentityRows(sessionName)}"
        val predecessorFolderPath = predecessorReady.folderPathForGeneration(
            sessionName = sessionName,
            expected = predecessor.generation,
        )
        ensureTreeSessionChildVisible(predecessorFolderPath, sessionName)

        // Kill the predecessor out-of-band, then wait for the host's epoch clock
        // to advance before recreating the exact SAME name. tmux may reuse the
        // freed `$N` and `%N` identifiers when this was the server's only
        // session/window, so the creation timestamp is the load-bearing fence.
        val successor = withTimeout(25_000) {
            connect().use { session ->
                val recreated = session.exec(
                    "set -eu; " +
                        "tmux kill-session -t ${shellQuote(sessionName)}; " +
                        "while [ \"\$(date +%s)\" -le " +
                        "${predecessor.generation.createdEpochSeconds} ]; do sleep 0.1; done; " +
                        "tmux new-session -d -s ${shellQuote(sessionName)} " +
                        "-c ${shellQuote(rootDir)}",
                )
                assertTrue(
                    "same-name successor must be recreated; exit=${recreated.exitCode} " +
                        "stderr=${recreated.stderr}",
                    recreated.exitCode == 0,
                )
                readRemoteIdentity(session, sessionName)
            }
        }
        assertNotEquals(
            "the successor creation timestamp must differ from the predecessor",
            predecessor.generation.createdEpochSeconds,
            successor.generation.createdEpochSeconds,
        )
        assertNotEquals(
            "the successor generation must differ from the predecessor",
            predecessor.generation,
            successor.generation,
        )
        evidence += "successor_remote=${successor.describe()}"

        // Real pull-to-refresh -> real SSH enumeration -> production tree
        // replacement. Do not deliver the delayed event until the screen-facing
        // state proves the successor generation is the sole visible row.
        vm.refreshSessions()
        val successorBeforeDelayedKill =
            awaitExactSession(vm, sessionName, successor.generation)
        assertSingleExactRow(
            state = successorBeforeDelayedKill,
            sessionName = sessionName,
            expected = successor.generation,
            phase = "before delayed predecessor kill",
        )
        val successorFolderPath = successorBeforeDelayedKill.folderPathForGeneration(
            sessionName = sessionName,
            expected = successor.generation,
        )
        assertEquals(
            "same-name successor must stay under the predecessor's watched folder",
            predecessorFolderPath,
            successorFolderPath,
        )
        val successorTreeRowTag = folderDetailRowTestTag(successorFolderPath, sessionName)
        assertComposeTreeSessionChildExactlyOnce(
            rowTag = successorTreeRowTag,
            phase = "before delayed predecessor kill",
        )
        val beforeScreenshot = captureIssue2239Viewport(
            name = "successor-before-delayed-predecessor.png",
            rowTag = successorTreeRowTag,
        )
        evidence += "successor_ui_before_event=" +
            successorBeforeDelayedKill.describeIdentityRows(sessionName)
        evidence += "successor_compose_before=${beforeScreenshot.file.absolutePath}" +
            " row_tag=$successorTreeRowTag" +
            " row_nonblank_pixels=${beforeScreenshot.rowNonBlankPixels}"

        // Freeze the displayed tree. A buggy name-keyed reducer now has no
        // authoritative refresh available to re-add the successor and hide the
        // regression. emitKilled is the same process-scoped production entry
        // point used by session Stop; the ViewModel's real collector/reducer
        // receives the predecessor's exact identity.
        vm.stopPolling()
        signals.emitKilled(
            hostId = hostId,
            generation = predecessor.generation,
            lastKnownName = sessionName,
        )
        // Drain the Main queue after SharedFlow delivery, then observe multiple
        // frames. This is intentionally not a refresh/reconcile rescue step.
        onMain { }
        val successorAfterDelayedKill = assertExactRowStable(
            vm = vm,
            sessionName = sessionName,
            expected = successor.generation,
            observationMs = 2_000L,
        )
        evidence += "delayed_event=KilledSession(hostId=$hostId," +
            "generation=${predecessor.generation},lastKnownName=$sessionName)"
        evidence += "successor_ui_after_event=" +
            successorAfterDelayedKill.describeIdentityRows(sessionName)
        assertComposeTreeSessionChildExactlyOnce(
            rowTag = successorTreeRowTag,
            phase = "after delayed predecessor kill",
        )
        val afterScreenshot = captureIssue2239Viewport(
            name = "successor-after-delayed-predecessor.png",
            rowTag = successorTreeRowTag,
        )
        evidence += "successor_compose_after=${afterScreenshot.file.absolutePath}" +
            " row_tag=$successorTreeRowTag" +
            " row_nonblank_pixels=${afterScreenshot.rowNonBlankPixels}"

        // Final out-of-band SSH/tmux oracle: exactly one live row has this name,
        // and its id + creation timestamp + window are still the successor's.
        val finalRemote = withTimeout(15_000) {
            connect().use { session ->
                val matching = session.exec(
                    "tmux list-sessions -F " +
                        "'#{session_id}::#{session_created}::#{session_name}'",
                )
                assertTrue(
                    "direct tmux list-sessions must succeed; exit=${matching.exitCode} " +
                        "stderr=${matching.stderr}",
                    matching.exitCode == 0,
                )
                val exactNameRows = matching.stdout.lineSequence()
                    .filter { it.substringAfterLast("::", missingDelimiterValue = "") == sessionName }
                    .toList()
                assertEquals(
                    "the host must contain exactly one same-name live session; " +
                        "all=${matching.stdout}",
                    1,
                    exactNameRows.size,
                )
                readRemoteIdentity(session, sessionName)
            }
        }
        assertEquals(
            "the delayed predecessor event must not kill or replace the successor remotely",
            successor,
            finalRemote,
        )
        evidence += "final_ssh_tmux=${finalRemote.describe()} live=true same_name_rows=1"
        writeIssue2239Evidence(evidence)
    } }

    /**
     * Issue #2239's connected A→B→A navigation contract. The rows and the
     * navigation callback both have to carry the exact tmux generation; a
     * session name is only display text and is not an identity oracle.
     *
     * The screen is the real production [FolderListScreen]. Its bind and
     * refresh paths use the production [FolderListViewModel],
     * [SshFolderListGateway], and [TreeRemoteSource] against the live Docker
     * tmux server. Tapping a row observes the real production navigation
     * payload; the test then uses the screen's production refresh entry point
     * before selecting the next row.
     */
    @Test
    fun exactGenerationSurvivesAtoBtoASwitchAndRefresh(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        hostName = "issue2239-aba-host-$suffix"
        val rootDir = "/tmp/issue2239-aba-$suffix"
        val aDir = "$rootDir/a"
        val bDir = "$rootDir/b"
        val aName = "issue2239-a-$suffix"
        val bName = "issue2239-b-$suffix"
        val watchRoot = FolderListViewModel.canonicalisePath(rootDir)
        val evidence = mutableListOf<String>()

        val seeded = withTimeout(30_000) {
            connect().use { session ->
                val folders = session.exec(
                    "mkdir -p ${shellQuote(aDir)} ${shellQuote(bDir)}",
                )
                assertTrue(
                    "A/B folder creation must succeed; exit=${folders.exitCode} " +
                        "stderr=${folders.stderr}",
                    folders.exitCode == 0,
                )
                val createdA = session.exec(
                    "tmux new-session -d -s ${shellQuote(aName)} -c ${shellQuote(aDir)}",
                )
                assertTrue(
                    "live tmux session A must be created; exit=${createdA.exitCode} " +
                        "stderr=${createdA.stderr}",
                    createdA.exitCode == 0,
                )
                val createdB = session.exec(
                    "tmux new-session -d -s ${shellQuote(bName)} -c ${shellQuote(bDir)}",
                )
                assertTrue(
                    "live tmux session B must be created; exit=${createdB.exitCode} " +
                        "stderr=${createdB.stderr}",
                    createdB.exitCode == 0,
                )
                createdSessions += aName
                createdSessions += bName
                val a = readRemoteIdentity(session, aName)
                val b = readRemoteIdentity(session, bName)
                assertNotEquals(
                    "A and B must be distinct live tmux generations",
                    a.generation,
                    b.generation,
                )
                a to b
            }
        }
        val remoteA = seeded.first
        val remoteB = seeded.second
        val expected = linkedMapOf(
            remoteA.sessionName to remoteA.generation,
            remoteB.sessionName to remoteB.generation,
        )
        evidence += "seed_A=${remoteA.describe()}"
        evidence += "seed_B=${remoteB.describe()}"

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue2239-aba-key", privateKeyPath = keyFile.absolutePath),
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
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "issue2239-aba", path = watchRoot),
        )
        val host = db.hostDao().getById(hostId)!!
        val navigation = mutableListOf<ProductionNavigationSelection>()
        val vm = newViewModel()
        vm.setProcessStartedForTest(true)

        // FolderListScreen's LaunchedEffect performs the production bind. The
        // callback below only records the real route payload; it does not seed
        // or replace any view-model state.
        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = host.id,
                    hostName = host.name,
                    hostname = host.hostname,
                    port = host.port,
                    username = host.username,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _, _, _ -> },
                    onOpenSessionWindow = { sessionName, _, _, tmuxSessionId, sessionCreated ->
                        navigation += ProductionNavigationSelection(
                            sessionName = sessionName,
                            generation = if (tmuxSessionId != null && sessionCreated != null) {
                                TmuxSessionGeneration(
                                    sessionId = tmuxSessionId,
                                    createdEpochSeconds = sessionCreated,
                                )
                            } else {
                                null
                            },
                        )
                    },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = vm,
                )
            }
        }

        // Render A, then observe the exact production navigation payload for A.
        val aBefore = awaitExactGenerations(vm, expected, "A initial")
        assertExactGenerationsInProductionTree(aBefore, expected, "A initial")
        val aFolder = aBefore.folderPathForGeneration(remoteA.sessionName, remoteA.generation)
        ensureTreeSessionChildVisible(aFolder, remoteA.sessionName)
        val aInitialScreenshot = captureIssue2239Viewport(
            name = "a-initial.png",
            rowTag = folderDetailRowTestTag(aFolder, remoteA.sessionName),
        )
        val aNavigationIndex = navigation.size
        compose.onNodeWithTag(
            folderDetailRowTestTag(aFolder, remoteA.sessionName),
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { navigation.size > aNavigationIndex }
        val selectedA = navigation[aNavigationIndex]
        assertEquals(remoteA.sessionName, selectedA.sessionName)
        assertEquals(remoteA.generation, selectedA.generation)
        evidence += "phase=A_INITIAL rendered=true " +
            "tree=${aBefore.describeExactGenerationRows(expected)} " +
            "navigation=${selectedA.describe()} " +
            "screenshot=${aInitialScreenshot.file.absolutePath} " +
            "row_nonblank_pixels=${aInitialScreenshot.rowNonBlankPixels}"

        // Refresh through the production screen's real ViewModel path, then
        // switch to B through the rendered production row.
        vm.refreshSessions()
        val bReady = awaitExactGenerations(vm, expected, "B after refresh")
        assertExactGenerationsInProductionTree(bReady, expected, "B after refresh")
        val bFolder = bReady.folderPathForGeneration(remoteB.sessionName, remoteB.generation)
        ensureTreeSessionChildVisible(bFolder, remoteB.sessionName)
        val bScreenshot = captureIssue2239Viewport(
            name = "b-after-refresh.png",
            rowTag = folderDetailRowTestTag(bFolder, remoteB.sessionName),
        )
        val bNavigationIndex = navigation.size
        compose.onNodeWithTag(
            folderDetailRowTestTag(bFolder, remoteB.sessionName),
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { navigation.size > bNavigationIndex }
        val selectedB = navigation[bNavigationIndex]
        assertEquals(remoteB.sessionName, selectedB.sessionName)
        assertEquals(remoteB.generation, selectedB.generation)
        evidence += "phase=B_AFTER_REFRESH rendered=true " +
            "tree=${bReady.describeExactGenerationRows(expected)} " +
            "navigation=${selectedB.describe()} " +
            "screenshot=${bScreenshot.file.absolutePath} " +
            "row_nonblank_pixels=${bScreenshot.rowNonBlankPixels}"

        // Refresh again through the production path and return to A. This is
        // the load-bearing A→B→A assertion: A's exact pair must be byte-for-
        // byte the pair observed before B was selected.
        vm.refreshSessions()
        val aAfter = awaitExactGenerations(vm, expected, "A after refresh")
        assertExactGenerationsInProductionTree(aAfter, expected, "A after refresh")
        val aAfterFolder = aAfter.folderPathForGeneration(remoteA.sessionName, remoteA.generation)
        assertEquals(
            "A's folder must remain stable across the B round trip",
            aFolder,
            aAfterFolder,
        )
        ensureTreeSessionChildVisible(aAfterFolder, remoteA.sessionName)
        val aAfterScreenshot = captureIssue2239Viewport(
            name = "a-after-refresh.png",
            rowTag = folderDetailRowTestTag(aAfterFolder, remoteA.sessionName),
        )
        val aAfterNavigationIndex = navigation.size
        compose.onNodeWithTag(
            folderDetailRowTestTag(aAfterFolder, remoteA.sessionName),
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { navigation.size > aAfterNavigationIndex }
        val selectedAAfter = navigation[aAfterNavigationIndex]
        assertEquals(remoteA.sessionName, selectedAAfter.sessionName)
        assertEquals(remoteA.generation, selectedAAfter.generation)

        val aBeforeGeneration = aBefore.exactGenerationFor(remoteA.sessionName)
        val aAfterGeneration = aAfter.exactGenerationFor(remoteA.sessionName)
        assertEquals(
            "A's exact tmuxSessionId + sessionCreated pair must survive A→B→A",
            aBeforeGeneration,
            aAfterGeneration,
        )
        assertEquals(remoteA.generation, aBeforeGeneration)
        assertEquals(remoteB.generation, bReady.exactGenerationFor(remoteB.sessionName))
        assertEquals(
            "the three production navigation events must be A, B, A",
            listOf(remoteA.sessionName, remoteB.sessionName, remoteA.sessionName),
            navigation.map { it.sessionName })
        assertEquals(
            "the production navigation payloads must preserve exact generations",
            listOf(remoteA.generation, remoteB.generation, remoteA.generation),
            navigation.map { it.generation },
        )
        evidence += "phase=A_AFTER_REFRESH rendered=true " +
            "tree=${aAfter.describeExactGenerationRows(expected)} " +
            "navigation=${selectedAAfter.describe()} " +
            "screenshot=${aAfterScreenshot.file.absolutePath} " +
            "row_nonblank_pixels=${aAfterScreenshot.rowNonBlankPixels}"

        // Final direct SSH/tmux oracle: the app did not merely echo cached
        // fields; both distinct live sessions still resolve to the seeded pairs.
        val finalRemote = withTimeout(20_000) {
            connect().use { session ->
                readRemoteIdentity(session, remoteA.sessionName) to
                    readRemoteIdentity(session, remoteB.sessionName)
            }
        }
        assertEquals(remoteA, finalRemote.first)
        assertEquals(remoteB, finalRemote.second)
        evidence += "final_ssh_tmux_A=${finalRemote.first.describe()}"
        evidence += "final_ssh_tmux_B=${finalRemote.second.describe()}"
        evidence += "a_exact_pair_unchanged=true b_exact_pair_own=true " +
            "tree_one_row_per_expected_generation=true name_only_aliases=0 " +
            "navigation_sequence=A,B,A"
        writeIssue2239AbaEvidence(evidence)
    } }

    /**
     * Issue #1829: run a Main-confined view-model entry point on Main, the way
     * every production caller does. Used for `bind` AND for the non-suspend
     * members that reach `emitReady()` synchronously (`toggleProjectExpanded`).
     */
    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun bind(vm: FolderListViewModel, host: HostEntity) {
        // Issue #1829: FolderListViewModel is Main-confined and now ENFORCES it.
        // Drive the REAL bind() on Main, exactly as FolderListScreen's
        // LaunchedEffect does — off Main, bind()'s synchronous cold seed races
        // the view model's own Main-dispatched emitReady and is dropped as
        // stale (#1823: 23-25% of binds), leaving the #867/#1109 Loading flash.
        onMain {
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

    private suspend fun awaitExactSession(
        vm: FolderListViewModel,
        sessionName: String,
        expected: TmuxSessionGeneration,
    ): FolderListUiState.Ready {
        withTimeout(40_000) {
            while (true) {
                val ready = vm.state.value as? FolderListUiState.Ready
                val rows = ready?.flatSessions?.filter { it.sessionName == sessionName }.orEmpty()
                if (rows.size == 1 &&
                    rows.single().tmuxSessionId == expected.sessionId &&
                    rows.single().sessionCreated == expected.createdEpochSeconds &&
                    ready?.isRefreshing == false
                ) {
                    return@withTimeout
                }
                delay(100L)
            }
        }
        return vm.state.value as FolderListUiState.Ready
    }

    private suspend fun awaitExactGenerations(
        vm: FolderListViewModel,
        expected: Map<String, TmuxSessionGeneration>,
        phase: String,
    ): FolderListUiState.Ready {
        withTimeout(40_000) {
            while (true) {
                val ready = vm.state.value as? FolderListUiState.Ready
                if (ready != null &&
                    !ready.isRefreshing &&
                    ready.hasExactGenerationRows(expected)
                ) {
                    return@withTimeout
                }
                delay(100L)
            }
        }
        return (vm.state.value as? FolderListUiState.Ready)
            ?: error("$phase did not reach FolderListUiState.Ready")
    }

    private suspend fun assertExactRowStable(
        vm: FolderListViewModel,
        sessionName: String,
        expected: TmuxSessionGeneration,
        observationMs: Long,
    ): FolderListUiState.Ready {
        val deadline = System.currentTimeMillis() + observationMs
        var latest = vm.state.value as FolderListUiState.Ready
        while (System.currentTimeMillis() < deadline) {
            latest = vm.state.value as FolderListUiState.Ready
            assertSingleExactRow(latest, sessionName, expected, "after delayed predecessor kill")
            delay(50L)
        }
        return latest
    }

    private fun ensureTreeSessionChildVisible(folderPath: String, sessionName: String) {
        val headerTag = folderHeaderClickTestTag(folderPath)
        val rowTag = folderDetailRowTestTag(folderPath, sessionName)
        compose.waitUntil(timeoutMillis = 40_000) {
            compose.onAllNodesWithTag(headerTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(headerTag))
        if (compose.onAllNodesWithTag(rowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        ) {
            // This is the real user's expand gesture on the production tree;
            // it happens before the delayed event, never as a post-event rescue.
            compose.onNodeWithTag(headerTag, useUnmergedTree = true).performClick()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(rowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size == 1
        }
        assertComposeTreeSessionChildExactlyOnce(rowTag, "tree expansion")
    }

    private fun assertComposeTreeSessionChildExactlyOnce(rowTag: String, phase: String) {
        val nodes = compose.onAllNodesWithTag(rowTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(
            "$phase: the production tree must expose exactly one same-name successor " +
                "child node; tag=$rowTag",
            1,
            nodes.size,
        )
        compose.onNodeWithTag(rowTag, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun captureIssue2239Viewport(
        name: String,
        rowTag: String,
    ): Issue2239ScreenshotEvidence {
        compose.waitForIdle()
        assertComposeTreeSessionChildExactlyOnce("$rowTag", "screenshot $name")

        // The tree row is the load-bearing screenshot oracle: count pixels from
        // the actual displayed child before saving the full production viewport.
        // A successful file write alone would be a vacuous artifact if the row
        // were blank or off-screen.
        val rowImage = compose.onNodeWithTag(rowTag, useUnmergedTree = true).captureToImage()
        assertTrue(
            "$name: the displayed tree child must have non-zero screenshot bounds",
            rowImage.width > 0 && rowImage.height > 0,
        )
        val rowPixels = IntArray(rowImage.width * rowImage.height)
        rowImage.readPixels(rowPixels)
        val rowBackground = rowPixels.firstOrNull()
        val rowNonBlankPixels = rowPixels.count { pixel ->
            pixel != rowBackground && ((pixel ushr 24) and 0xFF) != 0
        }
        assertTrue(
            "$name: the displayed tree child screenshot must be nonblank; " +
                "rowNonBlankPixels=$rowNonBlankPixels size=${rowImage.width}x${rowImage.height}",
            rowNonBlankPixels >= MIN_ISSUE2239_NONBLANK_PIXELS,
        )

        val image = compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
            .captureToImage()
        assertTrue(
            "$name: the production folder-list viewport must have non-zero bounds",
            image.width > 0 && image.height > 0,
        )
        val bitmap: Bitmap = createBitmap(image.width, image.height)
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = com.pocketshell.app.test.testArtifactsRoot(context)
        val dir = File(root, "additional_test_output/issue2239-generation-fence")
        check(dir.exists() || dir.mkdirs()) {
            "could not create issue #2239 screenshot directory ${dir.absolutePath}"
        }
        val file = File(dir, name)
        FileOutputStream(file).use { output ->
            assertTrue(
                "$name: could not encode Compose screenshot ${file.absolutePath}",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        bitmap.recycle()
        assertTrue(
            "$name: Compose screenshot must be a non-empty artifact ${file.absolutePath}",
            file.isFile && file.length() > 0L,
        )
        println(
            "ISSUE2239_COMPOSE_SCREENSHOT ${file.absolutePath} " +
                "row_nonblank_pixels=$rowNonBlankPixels",
        )
        return Issue2239ScreenshotEvidence(file, rowNonBlankPixels)
    }

    private fun FolderListUiState.Ready.folderPathForGeneration(
        sessionName: String,
        expected: TmuxSessionGeneration,
    ): String = treeRoots
        .flatMap { it.folders }
        .first { folder ->
            folder.sessions.any { session ->
                session.sessionName == sessionName &&
                    session.tmuxSessionId == expected.sessionId &&
                    session.sessionCreated == expected.createdEpochSeconds
            }
        }
        .path

    private fun assertSingleExactRow(
        state: FolderListUiState.Ready,
        sessionName: String,
        expected: TmuxSessionGeneration,
        phase: String,
    ) {
        val sameNameRows = state.flatSessions.filter { it.sessionName == sessionName }
        assertEquals(
            "$phase: exactly one visible same-name row is required; " +
                "rows=${state.describeIdentityRows(sessionName)}",
            1,
            sameNameRows.size,
        )
        val row = sameNameRows.single()
        assertEquals(
            "$phase: stale predecessor id must not be shown",
            expected.sessionId,
            row.tmuxSessionId,
        )
        assertEquals(
            "$phase: stale predecessor creation timestamp must not be shown",
            expected.createdEpochSeconds,
            row.sessionCreated,
        )
    }

    private fun FolderListUiState.Ready.describeIdentityRows(sessionName: String): String =
        flatSessions
            .filter { it.sessionName == sessionName }
            .joinToString(prefix = "[", postfix = "]") { row ->
                "${row.sessionName}/${row.tmuxSessionId}/${row.sessionCreated}"
            }

    private fun FolderListUiState.Ready.hasExactGenerationRows(
        expected: Map<String, TmuxSessionGeneration>,
    ): Boolean {
        val flatTargets = flatSessions.filter { it.sessionName in expected.keys }
        val treeTargets = treeRoots
            .flatMap { it.folders }
            .flatMap { it.sessions }
            .filter { it.sessionName in expected.keys }
        fun rowsMatch(rows: List<FolderSessionEntry>): Boolean {
            if (rows.size != expected.size) return false
            if (rows.map { it.sessionName }.toSet() != expected.keys) return false
            if (rows.any { it.tmuxSessionId.isNullOrBlank() || it.sessionCreated == null }) {
                return false
            }
            val actual = rows.associate { row ->
                row.sessionName to TmuxSessionGeneration(
                    sessionId = checkNotNull(row.tmuxSessionId),
                    createdEpochSeconds = checkNotNull(row.sessionCreated),
                )
            }
            return actual == expected
        }
        return rowsMatch(flatTargets) && rowsMatch(treeTargets)
    }

    private fun assertExactGenerationsInProductionTree(
        state: FolderListUiState.Ready,
        expected: Map<String, TmuxSessionGeneration>,
        phase: String,
    ) {
        val flatTargets = state.flatSessions.filter { it.sessionName in expected.keys }
        val treeTargets = state.treeRoots
            .flatMap { it.folders }
            .flatMap { it.sessions }
            .filter { it.sessionName in expected.keys }
        assertEquals(
            "$phase: one flat production row per expected session name",
            expected.size,
            flatTargets.size,
        )
        assertEquals(
            "$phase: one tree production row per expected session name",
            expected.size,
            treeTargets.size,
        )
        assertEquals(
            "$phase: flat production rows must carry exact generations",
            expected,
            flatTargets.associate { row ->
                row.sessionName to TmuxSessionGeneration(
                    sessionId = checkNotNull(row.tmuxSessionId),
                    createdEpochSeconds = checkNotNull(row.sessionCreated),
                )
            },
        )
        assertEquals(
            "$phase: tree production rows must carry exact generations; no name-only alias",
            expected,
            treeTargets.associate { row ->
                row.sessionName to TmuxSessionGeneration(
                    sessionId = checkNotNull(row.tmuxSessionId),
                    createdEpochSeconds = checkNotNull(row.sessionCreated),
                )
            },
        )
    }

    private fun FolderListUiState.Ready.exactGenerationFor(sessionName: String): TmuxSessionGeneration {
        val row = flatSessions.single { it.sessionName == sessionName }
        return TmuxSessionGeneration(
            sessionId = checkNotNull(row.tmuxSessionId),
            createdEpochSeconds = checkNotNull(row.sessionCreated),
        )
    }

    private fun FolderListUiState.Ready.describeExactGenerationRows(
        expected: Map<String, TmuxSessionGeneration>,
    ): String = expected.keys.sorted().joinToString(prefix = "[", postfix = "]") { name ->
        val row = flatSessions.singleOrNull { it.sessionName == name }
        "$name/${row?.tmuxSessionId ?: "<missing>"}/${row?.sessionCreated ?: "<missing>"}"
    }

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DAEMON_PORT,
        user = DEFAULT_USER,
        key = sshKey,
        knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
        timeoutMs = 10_000,
    ).getOrThrow()

    private suspend fun readRemoteIdentity(
        session: SshSession,
        sessionName: String,
    ): RemoteTmuxIdentity {
        val result = session.exec(
            "tmux display-message -p -t ${shellQuote(sessionName)} " +
                "'#{session_id}::#{session_created}::#{window_id}::#{session_name}'",
        )
        check(result.exitCode == 0) {
            "could not read exact tmux identity for $sessionName: " +
                "exit=${result.exitCode} stderr=${result.stderr}"
        }
        val fields = result.stdout.trim().split("::", limit = 4)
        check(fields.size == 4 && fields[0].startsWith("$") && fields[2].startsWith("@")) {
            "malformed exact tmux identity for $sessionName: '${result.stdout}'"
        }
        check(fields[3] == sessionName) {
            "tmux target resolved to '${fields[3]}', expected '$sessionName'"
        }
        return RemoteTmuxIdentity(
            generation = TmuxSessionGeneration(
                sessionId = fields[0],
                createdEpochSeconds = checkNotNull(fields[1].toLongOrNull()) {
                    "malformed session_created in '${result.stdout}'"
                },
            ),
            windowId = fields[2],
            sessionName = fields[3],
        )
    }

    private fun writeIssue2239Evidence(lines: List<String>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = com.pocketshell.app.test.testArtifactsRoot(context)
        val dir = File(root, "additional_test_output/issue2239-generation-fence")
        check(dir.exists() || dir.mkdirs()) {
            "could not create issue #2239 evidence directory ${dir.absolutePath}"
        }
        val file = File(dir, "same-name-generation-fence.txt")
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE2239_GENERATION_FENCE ${file.absolutePath}")
        lines.forEach { println("ISSUE2239_EVIDENCE $it") }
    }

    private fun writeIssue2239AbaEvidence(lines: List<String>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = com.pocketshell.app.test.testArtifactsRoot(context)
        val dir = File(root, "additional_test_output/issue2239-generation-fence")
        check(dir.exists() || dir.mkdirs()) {
            "could not create issue #2239 A→B→A evidence directory ${dir.absolutePath}"
        }
        val file = File(dir, "a-b-a-exact-generation.txt")
        file.writeText(
            buildString {
                appendLine("format=issue2239-exact-generation-v1")
                appendLine("journey=A->B->A")
                appendLine("test=exactGenerationSurvivesAtoBtoASwitchAndRefresh")
                lines.forEach(::appendLine)
            },
        )
        assertTrue(
            "A→B→A exact-generation artifact must be non-empty: ${file.absolutePath}",
            file.isFile && file.length() > 0L,
        )
        println("ISSUE2239_ABA_EXACT_GENERATION ${file.absolutePath}")
        lines.forEach { println("ISSUE2239_ABA_EVIDENCE $it") }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

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

    private fun newViewModel(
        sessionLifecycleSignals: SessionLifecycleSignals? = null,
    ): FolderListViewModel {
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
            sessionLifecycleSignals = sessionLifecycleSignals,
            attachLifecycle = false,
        ).also { vm ->
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", vm)
        }
    }

    private data class RemoteTmuxIdentity(
        val generation: TmuxSessionGeneration,
        val windowId: String,
        val sessionName: String,
    ) {
        fun describe(): String =
            "name=$sessionName session_id=${generation.sessionId} " +
                "session_created=${generation.createdEpochSeconds} window_id=$windowId"
    }

    private data class Issue2239ScreenshotEvidence(
        val file: File,
        val rowNonBlankPixels: Int,
    )

    private data class ProductionNavigationSelection(
        val sessionName: String,
        val generation: TmuxSessionGeneration?,
    ) {
        fun describe(): String =
            "$sessionName/${generation?.sessionId ?: "<missing>"}/" +
                "${generation?.createdEpochSeconds ?: "<missing>"}"
    }

    private companion object {
        const val DAEMON_PORT: Int = 2239
        const val MIN_ISSUE2239_NONBLANK_PIXELS: Int = 16
    }
}
