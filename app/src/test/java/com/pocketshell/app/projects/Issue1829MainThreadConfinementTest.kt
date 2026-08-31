package com.pocketshell.app.projects

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.MainThreadConfinementViolation
import com.pocketshell.app.env.EnvFileTarget
import com.pocketshell.app.env.EnvGateway
import com.pocketshell.app.env.EnvListResult
import com.pocketshell.app.env.EnvOpResult
import com.pocketshell.app.env.EnvViewModel
import com.pocketshell.app.fileexplorer.FileExplorerViewModel
import com.pocketshell.app.fileviewer.FileViewerViewModel
import com.pocketshell.app.git.GitHistoryViewModel
import com.pocketshell.app.hosts.AddEditHostViewModel
import com.pocketshell.app.hosts.FirstHostConnectionTester
import com.pocketshell.app.hosts.FirstHostTestConnectViewModel
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.jobs.PocketshellJobsRemoteSource
import com.pocketshell.app.jobs.RecurringJobsParser
import com.pocketshell.app.jobs.RecurringJobsViewModel
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.HostTmuxSessionListResult
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.HostTmuxSessionsGateway
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #1829 — [FolderListViewModel]'s Main-thread confinement must be
 * ENFORCED, not merely conventional.
 *
 * ## What this class pins
 *
 * `FolderListViewModel` is Main-confined by construction: `viewModelScope` is
 * `Dispatchers.Main.immediate`, `bind` is non-suspend straight-line code, and
 * `emitReady(synchronous = true)` performs a **check-then-act** sequence
 * (`++emitGeneration` → `HostTreeModel.buildProjection` → re-check the counter)
 * over a plain, non-`@Volatile` `Long`. On Main that sequence is atomic —
 * there is no suspension point, so nothing else can run in between. Off Main it
 * is not: a concurrent Main-dispatched `emitReady` bumps the counter inside the
 * window, the synchronous cold seed concludes it is stale, `applyReadyProjection`
 * never runs, and `_state` stays `Loading` — the user-visible #867 / #1109
 * rebuild flash. #1823 measured that at **23–25%** of off-Main binds (10/40 and
 * 28/120) versus **0/120** on Main.
 *
 * Until #1829 that confinement was enforced by **nothing**: no `@MainThread`,
 * no assertion, no barrier. Production could not reach it (both call sites are
 * Compose `LaunchedEffect` bodies — `FolderListScreen.kt:209` and
 * `RepoBrowserScreen.kt:121` — which run on `AndroidUiDispatcher.Main`), but
 * nine `androidTest` sites did, and the failure mode is silent. This suite
 * makes the violation LOUD and deterministic.
 *
 * ## Why the assertions are shaped this way
 *
 * The 23–25% race is a poor thing to assert on — a test that samples it is
 * flaky by construction. The *confinement violation underneath it* is 100%
 * deterministic: an off-Main `bind` on a warm cache mutates `emitGeneration`
 * and writes `_state` from the foreign thread on **every** call. So the
 * load-bearing assertion is that the off-Main call fails loudly and leaves the
 * confined state untouched — which is exactly the property that makes the race
 * unreachable, asserted without sampling it.
 *
 * These are Robolectric tests deliberately: [requireMainThread] compares
 * against a real `Looper.getMainLooper()`, and a plain JVM unit test would get
 * the `isReturnDefaultValues` stub (`null`), where the guard is a documented
 * no-op and would prove nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1829MainThreadConfinementTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0
    private lateinit var testCacheFilesDir: File

    @Before
    fun setUpIsolatedCacheDir() {
        testCacheFilesDir =
            java.nio.file.Files.createTempDirectory("issue1829-tree-cache").toFile()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        testCacheFilesDir.deleteRecursively()
    }

    // ---- the reported defect: an off-Main bind must fail LOUDLY --------------

    /**
     * Issue #1829 (reproduce-first, G10). RED before the guard: the off-Main
     * `bind` returns normally — no exception, no log — while having hydrated the
     * client cache, bumped `emitGeneration` and written `_state` to `Ready` from
     * a foreign thread. That silent success IS the defect: nothing prevents a
     * future caller from binding off Main, and when one appears the #867/#1109
     * Loading flash comes back with no signal at all.
     *
     * GREEN with the guard: the call throws [MainThreadConfinementViolation]
     * BEFORE touching any confined state, so the violation is impossible to
     * miss and impossible to half-apply.
     */
    @Test
    fun bindFromOffMainFailsLoudlyInsteadOfSilentlyMutatingTheConfinedColdSeed() = runTest {
        val cache = newCache()
        // The #867/#1109 cold-start shape: a warm client cache, so `bind` takes
        // the SYNCHRONOUS seed path (`hydrateFromClientCache` ->
        // `emitReady(synchronous = true)`) — the exact check-then-act sequence
        // whose atomicity depends on Main confinement.
        writeNodes(cache, node("alpha", 0, folderPath("alpha")))
        val vm = newViewModel(StubGateway(listOf(sessionRow("alpha"))), cache)

        assertTrue(
            "precondition: a fresh view model starts at Loading, so any later Ready " +
                "observed from the foreign thread was written BY that thread",
            vm.state.value is FolderListUiState.Loading,
        )

        // Read back inside the foreign thread. `Dispatchers.Main` is a
        // StandardTestDispatcher and the test thread does not pump between
        // start and join, so NOTHING on Main can run during the call — any
        // state change observed here was written by this thread.
        val observedOffMain = AtomicReference<FolderListUiState?>(null)
        val failure = runOffMainAndJoin("FolderListViewModel.bind") {
            bind(vm)
            observedOffMain.set(vm.state.value)
        }

        assertTrue(
            "issue #1829: an off-Main bind() must fail LOUDLY. Instead it " +
                "${if (failure == null) "returned normally" else "threw $failure"} and the " +
                "confined state it wrote off Main was ${observedOffMain.get()}. With no " +
                "enforcement, the #867/#1109 Loading-flash race is one careless caller away " +
                "and arrives silently.",
            failure is MainThreadConfinementViolation,
        )
        assertNull(
            "issue #1829: the guard must fire BEFORE bind mutates emitGeneration / _state — " +
                "a half-applied bind off Main is exactly the corrupted state the confinement " +
                "exists to prevent",
            observedOffMain.get(),
        )
        assertTrue(
            "issue #1829: after a rejected off-Main bind the view model must still hold its " +
                "untouched Loading seed — got ${vm.state.value}",
            vm.state.value is FolderListUiState.Loading,
        )
    }

    /**
     * Issue #1829 class coverage inside [FolderListViewModel]: the confinement
     * belongs to the EMIT seam, not just to `bind`. `emitReady` is reached from
     * 21 call sites; guarding only `bind` would leave every one of them able to
     * bump `emitGeneration` off Main. Drive a public emit-producing entry point
     * (`toggleProjectExpanded`, the user's folder collapse tap) from a foreign
     * thread after a legitimate on-Main bind and require the same loud failure.
     */
    @Test
    fun emitProducingEntryPointFromOffMainFailsLoudly() = runTest {
        val cache = newCache()
        writeNodes(cache, node("alpha", 0, folderPath("alpha")))
        val vm = newViewModel(StubGateway(listOf(sessionRow("alpha"))), cache)

        // The production shape: bind on Main (this IS the Robolectric main thread).
        bind(vm)
        runCurrent()
        assertTrue(
            "precondition: the on-Main bind must have produced a Ready projection, so the " +
                "off-Main toggle below really reaches emitReady",
            vm.state.value is FolderListUiState.Ready,
        )

        val failure = runOffMainAndJoin("FolderListViewModel.toggleProjectExpanded") {
            vm.toggleProjectExpanded(folderPath("alpha"))
        }

        assertTrue(
            "issue #1829: every emit-producing entry point is Main-confined, not just bind() " +
                "— an off-Main toggle bumps the same non-@Volatile emitGeneration counter. " +
                "Got ${failure ?: "no exception at all"}",
            failure is MainThreadConfinementViolation,
        )
    }

    /**
     * Issue #1829 acceptance criterion 2: the on-Main path must be unchanged in
     * every observable way. This is the #867/#1109 instant-render promise —
     * a warm cache paints `Ready` SYNCHRONOUSLY inside `bind`, before any
     * coroutine runs. If the guard were misplaced (or threw on Main) this is
     * what would break.
     */
    @Test
    fun onMainBindStillRendersTheCachedTreeInstantlyWithNoLoadingFlash() = runTest {
        val cache = newCache()
        writeNodes(
            cache,
            node("alpha", 0, folderPath("alpha")),
            node("beta", 1, folderPath("beta")),
        )
        val vm = newViewModel(StubGateway(listOf(sessionRow("alpha"), sessionRow("beta"))), cache)

        bind(vm)

        val first = vm.state.value
        assertTrue(
            "issue #1829 must not change the on-Main path: a warm cache still paints Ready " +
                "synchronously inside bind (no Loading flash) — got $first",
            first is FolderListUiState.Ready,
        )
        assertEquals(
            listOf("alpha", "beta"),
            (first as FolderListUiState.Ready).flatSessions.map { it.sessionName },
        )
    }

    // ---- G2: the sibling view models that share the shape --------------------

    /**
     * Issue #1829 acceptance criterion 4 (G2) — the sibling table, enumerated
     * **BY SHAPE, NOT BY NAME**.
     *
     * ## How the enumeration was done (so the next reader can redo it)
     *
     * Round 1 of this issue defined the class correctly and then enumerated it
     * by grepping the method names `bind` and `load` — so three members called
     * `start` fell straight out. The enumeration is now three mechanical
     * properties checked against the source rather than against a name:
     *
     *  - **S1** the declaring type is a screen-scoped state holder (a
     *    `ViewModel` supertype, or it owns a `MutableStateFlow` a composable
     *    collects).
     *  - **S2** the member is non-suspend, non-private, and is not itself a
     *    whole-body `viewModelScope.launch { }` (which is Main by construction).
     *  - **S3** the body does check-then-act over plain, non-`@Volatile`,
     *    non-`Atomic` state in ONE of four sub-shapes: **(a)** a guard-return
     *    that reads a plain `var` followed by a write to one; **(b)** a
     *    read-modify-write on a `MutableStateFlow` (`_x.value = current.copy(…)`,
     *    not the atomic `.update {}`); **(c)** a generation-counter bump
     *    (`++f` / `f += 1`); **(d)** a **job swap** (`job?.cancel()` … `job =
     *    launch{}`) — which has no `if` at all, and is exactly why
     *    `HostTmuxSessionPickerViewModel.load` is invisible to a guard-shaped
     *    scan.
     *
     * That yields 95 candidates, dominated by gesture callbacks. Two further
     * splits, also mechanical:
     *
     *  - **call-site shape** — a candidate is a *binding seam* when at least one
     *    call site sits inside a Compose `LaunchedEffect(…) { }` block, i.e. the
     *    screen drives it with its route parameters and the check-then-act
     *    decides *whether the load happens at all*. Losing that race drops the
     *    screen's content (the reported #867/#1109 symptom). The remaining 56
     *    are gesture callbacks: same field shape, but their only possible
     *    invoker is Compose input dispatch, and losing the race drops one
     *    gesture, not the first frame.
     *  - **already-solved** — an entry point whose body hops to Main itself
     *    (`withContext(Dispatchers.Main)`) or serialises under `synchronized` is
     *    out of the class by construction. The four `observeProcessLifecycle`
     *    members are the former and their KDoc states that contract in words
     *    ("safe … from a coroutine on `Dispatchers.IO`") — guarding them would
     *    contradict a deliberate, documented design.
     *
     * ## The class, in full
     *
     * | entry point | check-then-act sub-shape | consequence off Main |
     * |---|---|---|
     * | `FolderListViewModel.bind` / `emitReady` / `applyReadyProjection` | (a)+(c)+(d) `++emitGeneration` → `buildProjection` → re-check | **the reported one** — dropped cold seed, #867/#1109 Loading flash |
     * | `EnvViewModel.bind` | (a) `if (params == next && …) return` then assign + `_state.value = …` | a duplicated or dropped `refresh()` and a lost `_state` write |
     * | `FileViewerViewModel.bind` | (a) `lastRequest` read/write + `loadJob?.isActive` | two concurrent loads, or a dropped one |
     * | `RepoBrowserViewModel.bind` | (a) `if (credentials == …) return` then assign + `refresh()` | duplicate/dropped enumeration |
     * | `WatchedFoldersChipsViewModel.bind` | (a)+(d) `if (hostId == …) return` then assign + job swap | an orphaned collector on the old host |
     * | `WatchedFoldersViewModel.bind` | (b)+(d) plus `_state.value = _state.value.copy(…)` | a lost-update read-modify-write on the StateFlow |
     * | `PortForwardPanelViewModel.load` | (a)+(c)+(d) `if (currentHostId == …) return` then `++loadGeneration` | a stale load generation, so a superseded result is applied |
     * | `RecurringJobsViewModel.load` | (a)+(c) `if (next == target) return` then `targetGeneration += 1` | the same stale-generation drop, on the jobs list |
     * | `FileExplorerViewModel.start` | (a) `if (request == …) return` then assign + a plain `LinkedHashMap.clear()` | a dropped navigation, plus a non-thread-safe map cleared mid-read |
     * | `GitHistoryViewModel.start` | (a) `if (request == …) return` then assign + `load()` | structurally identical to `RepoBrowserViewModel.bind` |
     * | `FirstHostTestConnectViewModel.start` | (a)+(b) guard on `testingHostId` + `_state.value`, then `current.copy(…)` | a lost first-run connect result |
     * | `AddEditHostViewModel.loadHost` | (a) `if (editingHostId == …) return` then assign | a double-load that clobbers the dirty-state `baseline` |
     * | `AddEditHostViewModel.consumeFirstInvalidField` | (b) read `_state.value` then write `s.copy(…)` | a concurrent form edit lost between read and write |
     * | `AddEditHostViewModel.consumeSaved` | (b) same, on the one-shot save/navigate signal | a re-firing navigation signal (the #1243 dead end) |
     * | `HostListViewModel.reprobeUnknownHostsOnce` | (a) plain `autoReprobeKicked` once-latch | two startup probe sweeps instead of one |
     * | `HostTmuxSessionPickerViewModel.load` | (d) `loadJob?.cancel()` … `loadJob = launch{}` | two loads race `_state`; the picker shows the wrong host's sessions |
     * | `HostTmuxSessionPickerViewModel.refreshProjectSiblings` | (b)+(d) same job swap on the sibling list | a stale project-switcher dropdown |
     *
     * ## Out of the class, with the reason (not silence)
     *
     *  - **56 gesture callbacks** (`toggleWordWrap`, `addFolder`,
     *    `dismissBanner`, `setSort`, …): the shape without the binding seam —
     *    see the call-site split above.
     *  - **`PortForwardPanelViewModel` / `SessionsDashboardViewModel` /
     *    `UpdateCheckScheduler` / `UsageScheduler` `.observeProcessLifecycle`,
     *    and `UsageScheduler.start` / `.cancel`**: self-dispatched or
     *    `synchronized` — already solved, see above.
     *  - **`TerminalLabActivity`'s `TerminalLabController.connect`**: not a
     *    screen view model and not `LaunchedEffect`-driven — it is called once
     *    from the debug lab activity's `onCreate` with an explicit
     *    `CoroutineScope` argument.
     *  - **`TmuxSessionViewModel`** (`connect`, `reconnect`,
     *    `prewarmLikelySwitchTargets`, `cancelTmuxSessionPrewarm`,
     *    `refreshActiveSessionCards`): genuinely in the shape, but it is the
     *    D28 connection-core serial lane and is deliberately untouched here.
     *    Recorded so the omission is a decision, not an oversight.
     *
     * This test proves each guard is LIVE, and names **every** entry point that
     * fails to enforce rather than stopping at the first.
     * `HostListViewModel.reprobeUnknownHostsOnce` is proven in
     * `HostListViewModelTest` and `PortForwardPanelViewModel.load` in
     * `PortForwardPanelViewModelTest`, where each already has a harness.
     */
    @Test
    fun everySiblingViewModelWithABindEntryPointAlsoEnforcesMainConfinement() = runTest {
        val leaseManager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(NoopTreeSshSession()) },
            scope = this,
            idleTtlMillis = 0L,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )
        val context: Context = ApplicationProvider.getApplicationContext()

        val envViewModel = EnvViewModel(gateway = NoopEnvGateway, hostDao = FakeHostDao(HOST))
        val fileViewerViewModel = FileViewerViewModel(
            appContext = context,
            sshLeaseManager = leaseManager,
        )
        val repoBrowserViewModel = RepoBrowserViewModel(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            sshLeaseManager = leaseManager,
        )
        val chipsViewModel = WatchedFoldersChipsViewModel(FakeProjectRootDao())
        val watchedFoldersViewModel = WatchedFoldersViewModel(
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = leaseManager,
        )
        val recurringJobsViewModel = RecurringJobsViewModel(
            remoteSource = PocketshellJobsRemoteSource(RecurringJobsParser()),
            connector = object : RecurringJobsViewModel.RecurringJobsSshConnector {
                override suspend fun acquire(
                    target: RecurringJobsViewModel.Target,
                ): Result<SshLease> = Result.failure(IllegalStateException("no lease in this test"))
            },
        )
        val fileExplorerViewModel = FileExplorerViewModel(leaseManager)
        val gitHistoryViewModel = GitHistoryViewModel(leaseManager)
        val addEditHostViewModel = AddEditHostViewModel(
            hostDao = FakeHostDao(HOST),
            sshKeyDao = FakeSshKeyDao,
        )
        val firstHostTestConnectViewModel = FirstHostTestConnectViewModel(
            hostDao = FakeHostDao(HOST),
            sshKeyDao = FakeSshKeyDao,
            // Never invoked: every guard fires before any work runs, which is
            // itself part of what the assertions below pin.
            tester = FirstHostConnectionTester(),
        )
        val sessionPickerViewModel = HostTmuxSessionPickerViewModel(NoopSessionsGateway)
        val pickerRequest = HostTmuxSessionPickerRequest(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
        )

        val siblings: List<Pair<String, () -> Unit>> = listOf(
            "EnvViewModel.bind" to {
                envViewModel.bind(
                    hostId = HOST.id,
                    keyPath = KEY_PATH,
                    passphrase = null,
                    directory = "/home/alexey/git/alpha",
                    folderLabel = "alpha",
                    copySources = emptyList(),
                )
            },
            "FileViewerViewModel.bind" to {
                fileViewerViewModel.bind(
                    FileViewerViewModel.Request(
                        hostId = HOST.id,
                        hostname = HOST.hostname,
                        port = HOST.port,
                        username = HOST.username,
                        keyPath = KEY_PATH,
                        passphrase = null,
                        path = "README.md",
                        cwd = null,
                    ),
                )
            },
            "RepoBrowserViewModel.bind" to {
                repoBrowserViewModel.bind(
                    RepoBrowserViewModel.SshCredentials(
                        hostId = HOST.id,
                        hostname = HOST.hostname,
                        port = HOST.port,
                        username = HOST.username,
                        keyPath = KEY_PATH,
                        passphrase = null,
                    ),
                )
            },
            "WatchedFoldersChipsViewModel.bind" to { chipsViewModel.bind(HOST.id) },
            "WatchedFoldersViewModel.bind" to {
                watchedFoldersViewModel.bind(hostId = HOST.id, hostName = HOST.name)
            },
            // The closest analogue of the reported defect: like
            // FolderListViewModel.emitReady this bumps a plain generation
            // counter and re-checks it later, so off Main the counter is
            // exactly the value that can be lost.
            "RecurringJobsViewModel.load" to {
                recurringJobsViewModel.load(
                    hostId = HOST.id,
                    hostName = HOST.name,
                    hostname = HOST.hostname,
                    port = HOST.port,
                    username = HOST.username,
                    keyPath = KEY_PATH,
                    passphrase = null,
                    sessionName = "alpha",
                )
            },
            // ---- round 2: the members the by-NAME sweep missed --------------
            "FileExplorerViewModel.start" to {
                fileExplorerViewModel.start(
                    FileExplorerViewModel.Request(
                        hostId = HOST.id,
                        hostname = HOST.hostname,
                        port = HOST.port,
                        username = HOST.username,
                        keyPath = KEY_PATH,
                        passphrase = null,
                        startDir = "/home/alexey/git/alpha",
                    ),
                )
            },
            "GitHistoryViewModel.start" to {
                gitHistoryViewModel.start(
                    GitHistoryViewModel.Request(
                        hostId = HOST.id,
                        hostname = HOST.hostname,
                        port = HOST.port,
                        username = HOST.username,
                        keyPath = KEY_PATH,
                        passphrase = null,
                        dir = "/home/alexey/git/alpha",
                    ),
                )
            },
            "FirstHostTestConnectViewModel.start" to {
                firstHostTestConnectViewModel.start(HOST.id)
            },
            "AddEditHostViewModel.bind" to { addEditHostViewModel.bind(HOST.id) },
            "AddEditHostViewModel.consumeFirstInvalidField" to {
                addEditHostViewModel.consumeFirstInvalidField()
            },
            "AddEditHostViewModel.consumeSaved" to { addEditHostViewModel.consumeSaved() },
            // The job-swap sub-shape: no `if` at all, which is exactly why a
            // guard-shaped scan cannot see it.
            "HostTmuxSessionPickerViewModel.load" to {
                sessionPickerViewModel.load(pickerRequest)
            },
            "HostTmuxSessionPickerViewModel.refreshProjectSiblings" to {
                sessionPickerViewModel.refreshProjectSiblings(
                    host = HOST,
                    keyPath = KEY_PATH,
                    currentSessionName = "alpha",
                    projectPath = "/home/alexey/git/alpha",
                )
            },
        )

        val unenforced = siblings.mapNotNull { (label, invoke) ->
            val failure = runOffMainAndJoin(label, invoke)
            if (failure is MainThreadConfinementViolation) {
                null
            } else {
                "$label -> ${failure ?: "no exception at all"}"
            }
        }

        assertEquals(
            "issue #1829 (G2): every screen-scoped view model with a non-suspend bind() " +
                "check-then-act must enforce Main confinement the same way FolderListViewModel " +
                "does. These did not fail loudly off Main: $unenforced",
            emptyList<String>(),
            unenforced,
        )
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Runs [block] on a real foreign thread and waits for it with a bounded,
     * HARD-failing join (the `process.md` `runTest` convention, Shape B): the
     * join's exit condition is asserted, never assumed, so a wedged call can
     * never be mistaken for "the guard did not throw". Returns whatever the
     * block threw, or `null` when it completed normally.
     */
    private fun runOffMainAndJoin(label: String, block: () -> Unit): Throwable? {
        val captured = AtomicReference<Throwable?>(null)
        val worker = Thread({
            try {
                block()
            } catch (t: Throwable) {
                captured.set(t)
            }
        }, "issue1829-off-main")
        worker.start()
        worker.join(OFF_MAIN_JOIN_TIMEOUT_MS)
        assertFalse(
            "issue #1829: the off-Main $label call must finish within " +
                "${OFF_MAIN_JOIN_TIMEOUT_MS}ms — it is still running, so nothing asserted " +
                "below would mean anything",
            worker.isAlive,
        )
        return captured.get()
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

    private fun folderPath(name: String): String =
        FolderListViewModel.canonicalisePath("/home/alexey/git/$name")

    private fun node(name: String, order: Int, path: String): TreeRemoteSource.TreeNode =
        TreeRemoteSource.TreeNode(
            session = name,
            order = order,
            folderPath = path,
            collapsed = false,
        )

    private fun writeNodes(cache: TreeClientCache, vararg nodes: TreeRemoteSource.TreeNode) {
        cache.write(HOST.id, 1L, TreeClientCache.CachedTree(nodes = nodes.toList()))
    }

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = true,
            cwd = "/home/alexey/git/$name",
            agentKind = SessionAgentKind.Shell,
        )

    private fun newCache(): TreeClientCache =
        TreeClientCache(
            object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
                override fun getFilesDir(): File = testCacheFilesDir
            },
        )

    private fun TestScope.newViewModel(
        gateway: FolderListGateway,
        cache: TreeClientCache,
    ): FolderListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(NoopTreeSshSession()) },
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
            treeRemoteSource = null,
            treeClientCache = cache,
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.treeDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    private class NoopTreeSshSession : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = ExecResult("", "no daemon", 127)
        override fun tail(path: String, onLine: (String) -> Unit) = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("unused")
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
        ): FolderListResult = FolderListResult.Sessions(
            rows = rows ?: error("gateway probe must not be called"),
            projectFoldersByRoot = emptyMap(),
            resolvedWatchedRootPaths = emptyMap(),
        )

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
            namePolicy: SessionNamePolicy,
        ): Result<SessionCreateOutcome> = error("not used")

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

    private object NoopEnvGateway : EnvGateway {
        override suspend fun listKeys(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            directory: String,
        ): EnvListResult = EnvListResult.Keys(emptyList())

        override suspend fun getValue(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            directory: String,
            key: String,
        ): EnvOpResult = error("not used")

        override suspend fun setKeys(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            directory: String,
            file: EnvFileTarget,
            updates: Map<String, String>,
        ): EnvOpResult = error("not used")

        override suspend fun copyKeys(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sourceDirectory: String,
            destinationDirectory: String,
            file: EnvFileTarget,
            keys: List<String>,
        ): EnvOpResult = error("not used")
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

    /**
     * Every method errors on purpose: the Main guard must fire BEFORE any of
     * these entry points touches a DAO, so a call reaching one is itself a
     * failure the test should surface rather than absorb.
     */
    private object FakeSshKeyDao : SshKeyDao {
        override fun getAll(): Flow<List<SshKeyEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): SshKeyEntity? = null
        override suspend fun getByName(name: String): SshKeyEntity? = null
        override suspend fun getByFingerprint(fingerprint: String): SshKeyEntity? = null
        override suspend fun insert(key: SshKeyEntity): Long = error("not used")
        override suspend fun delete(key: SshKeyEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    private object NoopSessionsGateway : HostTmuxSessionsGateway {
        override suspend fun listSessions(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): HostTmuxSessionListResult = HostTmuxSessionListResult.Sessions(emptyList())

        override suspend fun listSessionsFromLiveClient(
            host: HostEntity,
            keyPath: String,
        ): HostTmuxSessionListResult? = null
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

        /**
         * Generous relative to the work being waited on (a `bind` that either
         * throws immediately or completes a purely in-memory hydrate), so a
         * contended CI box cannot turn a correct run red — while still hard
         * failing rather than hanging if the call genuinely wedges.
         */
        const val OFF_MAIN_JOIN_TIMEOUT_MS: Long = 30_000L

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
