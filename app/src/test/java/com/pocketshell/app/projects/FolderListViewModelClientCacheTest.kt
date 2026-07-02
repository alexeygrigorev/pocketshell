package com.pocketshell.app.projects

import android.content.Context
import android.content.ContextWrapper
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
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Issue #867: session-tree stale-while-revalidate — render the last-known tree
 * INSTANTLY from the per-host CLIENT cache, reconcile SILENTLY in place, no
 * rebuild churn on connect, no churn on session switch, and the cache stays
 * ADVISORY (the reconcile is authoritative).
 *
 * The reproduction ([coldStartWithEmptyCacheFlashesLoading] = RED on the
 * unfixed code's behaviour, [coldStartWithCacheRendersInstantlyNoLoadingFlash] =
 * GREEN with the fix) records EVERY [FolderListUiState] emitted during the
 * cold-start cycle. The bug is the visible Loading flash ("No folders yet / 0
 * projects" + spinner) that paints during the daemon round-trip + first probe;
 * the fix is the client cache seeding the held tree so the FIRST state after
 * bind is already Ready with the last-known sessions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelClientCacheTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    // Issue #1155 (blocker 1): a PER-TEST isolated on-disk cache directory so the
    // client-cache tests never share `filesDir`/`tree-cache` state across methods.
    // The prior suite read + wrote one shared `ApplicationProvider` filesDir, which
    // (together with the now-removed per-VM real-`Dispatchers.IO` warm) let one
    // test's on-disk snapshot + a racing warm flip a sibling's cold-miss assertion.
    // Every `newCache()` in a test targets this dir; `@After` wipes it.
    private lateinit var testCacheFilesDir: File

    @Before
    fun setUpIsolatedCacheDir() {
        testCacheFilesDir =
            java.nio.file.Files.createTempDirectory("tree-client-cache-test").toFile()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        testCacheFilesDir.deleteRecursively()
    }

    // ---- RED reproduction: with NO cache, cold start flashes Loading ---------

    @Test
    fun coldStartWithEmptyCacheFlashesLoading() = runTest {
        val cache = newCache()
        // No cache seeded for this host (a genuinely first-ever open).
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway, cache)

        bind(vm)
        // SYNCHRONOUSLY after bind (before any coroutine runs): the first-ever
        // open has nothing to paint instantly, so the screen shows the brief
        // Loading before the reconcile produces Ready — exactly the pre-#867
        // behaviour the maintainer saw (the empty 'No folders yet / 0 projects'
        // rebuild flash). This pins WHY the client cache is needed: WITHOUT a
        // cache to seed, the very first state IS Loading.
        assertTrue(
            "first-ever open (no cache) shows the brief Loading before Ready",
            vm.state.value is FolderListUiState.Loading,
        )

        runCurrent()
        assertTrue(
            "the reconcile eventually produces Ready",
            vm.state.value is FolderListUiState.Ready,
        )
    }

    // ---- GREEN: cold start WITH a populated cache renders the held tree -------

    @Test
    fun coldStartWithCacheRendersHeldTreeInstantlyNoLoadingFlash() = runTest {
        val cache = newCache()
        // The PREVIOUS app session left a settled tree in the client cache.
        writeNodes(
            cache,
            node("alpha", 0, folderPath("alpha")),
            node("beta", 1, folderPath("beta")),
        )
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway, cache)

        bind(vm)

        // Issue #1109 (regression of the #867 instant-render promise): with a
        // populated client cache the cold connect paints the held tree
        // SYNCHRONOUSLY inside bind() — the FIRST state read (before any coroutine
        // runs / before runCurrent) is already Ready, so the screen never composes
        // the empty Loading frame. #965 had moved the read + projection OFF Main,
        // which made the cold connect always flash Loading first; this is the
        // load-bearing fast-gate proof that the flash is gone. RED on #965's
        // off-Main code (this is Loading until runCurrent), GREEN with the
        // synchronous seed.
        val first = vm.state.value
        assertTrue(
            "cold connect must render the cached tree INSTANTLY (Ready, not the " +
                "Loading rebuild flash) — got $first",
            first is FolderListUiState.Ready,
        )
        assertEquals(
            "the cached sessions paint in order on the FIRST frame",
            listOf("alpha", "beta"),
            (first as FolderListUiState.Ready).flatSessions.map { it.sessionName },
        )

        // The silent reconcile then keeps the instantly-rendered tree authoritative
        // against the live probe (advisory cache) — still the same sessions here.
        runCurrent()
        val reconciled = vm.state.value
        assertTrue(reconciled is FolderListUiState.Ready)
        assertEquals(
            listOf("alpha", "beta"),
            (reconciled as FolderListUiState.Ready).flatSessions.map { it.sessionName },
        )
    }

    // ---- Issue #1109: the parse is DECOUPLED from the synchronous hydrate --------

    /**
     * Issue #1109 (the #965-regression guard): [TreeClientCache.peek] — the lookup
     * the SYNCHRONOUS cold-connect seed uses inside `bind` — is a pure IN-MEMORY
     * read that does NO file read + JSON parse. A FRESH cache instance (the cold
     * app-restart case: the file is on disk but memory is cold) MISSES on `peek`
     * until [TreeClientCache.warmAll] has parsed the file OFF Main; only then does
     * `peek` hit. This is what lets `bind` paint synchronously without the
     * Main-thread `disk_read` that produced the #965 ANR — proven directly at the
     * cache seam, independent of the VM.
     */
    @Test
    fun peekIsInMemoryOnly_missesUntilWarmedThenHitsFromDisk() {
        val writer = newCache()
        writer.write(
            HOST.name,
            TreeClientCache.CachedTree(
                nodes = listOf(node("alpha", 0, folderPath("alpha"))),
            ),
        )

        // A fresh instance = a new process: the snapshot is on DISK but its parsed
        // in-memory map is cold. `peek` does NOT read the file, so it misses.
        val reader = newCache()
        assertEquals(
            "peek must be in-memory only — a fresh (un-warmed) instance has no Main-" +
                "thread read, so it MISSES the on-disk snapshot",
            null,
            reader.peek(HOST.name),
        )

        // The OFF-Main warm parses the file into memory; now (and only now) peek hits.
        reader.warmAll()
        val warmed = reader.peek(HOST.name)
        assertTrue("warmAll must parse the on-disk snapshot into memory", warmed != null)
        assertEquals(
            listOf("alpha"),
            warmed!!.nodes.map { it.session },
        )
    }

    /**
     * Issue #1109 cold-MISS fallback: when the in-memory snapshot was NOT warmed
     * before `bind` (a brand-new cache instance, file on disk, cold memory), the
     * synchronous seed MISSES, so the first frame is the brief Loading and the cache
     * is read OFF Main — NOT on the Main thread inside `bind`. After the off-Main
     * read drains, the held tree paints. This pins the graceful fallback the
     * reviewer required (the seed never blocks Main on a miss).
     */
    @Test
    fun coldStartCacheMiss_readsOffMainThenPaintsHeldTree() = runTest {
        // Write via one instance (disk + that instance's memory), then bind a VM with
        // a DIFFERENT, cold-memory instance — the file is on disk but un-warmed.
        newCache().write(
            HOST.name,
            TreeClientCache.CachedTree(
                nodes = listOf(node("gamma", 0, folderPath("gamma"))),
            ),
        )
        val coldCache = newCache()
        val gateway = StubGateway(listOf(sessionRow("gamma")))
        val vm = newViewModel(gateway, coldCache)

        bind(vm)
        // SYNCHRONOUSLY after bind (before any coroutine runs): the cold-memory
        // instance misses the in-memory peek, so the seed does NOT read the file on
        // Main — the first frame is the brief Loading.
        assertTrue(
            "a cold-memory cache miss must fall back to Loading (no Main-thread " +
                "file read in bind) — got ${vm.state.value}",
            vm.state.value is FolderListUiState.Loading,
        )

        // The OFF-Main warm + read then paint the held tree.
        runCurrent()
        val ready = vm.state.value
        assertTrue(
            "the off-Main read paints the held tree — got $ready",
            ready is FolderListUiState.Ready,
        )
        assertEquals(
            listOf("gamma"),
            (ready as FolderListUiState.Ready).flatSessions.map { it.sessionName },
        )
    }

    // ---- Issue #1155 (Part A): the PROCESS-START warm makes cold connect instant ----
    //
    // The maintainer's recurring #867/#1109 symptom: a cold process (the parsed
    // map is empty; the tree file is on disk from the previous run) where they
    // deep-link into a session and then go BACK to the tree. The VM is constructed
    // and `bind` runs in the SAME instant, so a per-VM warm (removed in this
    // change) loses the race and [TreeClientCache.peek] MISSES → the "Loading
    // workspace tree" spinner flashes. The fix warms the parsed snapshot ONCE at
    // process startup ([com.pocketshell.app.App.onCreate] → [TreeClientCache.warmAll],
    // MANY frames ahead of any navigation) — the SINGLE warm path — so `peek` HITS
    // synchronously inside `bind` and the FIRST painted frame is the persisted
    // Ready tree.
    //
    // These two tests pivot on that process-start warm: the ONLY difference is
    // whether [TreeClientCache.warmAll] (exactly what App.onCreate calls) ran
    // before `bind`. With the per-VM warm GONE, warmAll is the load-bearing
    // mechanism, and both assertions are deterministic (no real-`Dispatchers.IO`
    // warm racing the synchronous peek). Reverting the App.onCreate warm turns
    // every first cold open into the RED case below — which the CI-wired on-device
    // `ColdRestoreGoneSessionNoResurrectE2eTest` deep-link-back journey catches.

    /**
     * Issue #1155 (Part A) RED — the "App.onCreate warm reverted / not yet run"
     * state. A cold-memory cache (file on disk, parsed map cold) with NO
     * process-start warm: `bind` MISSES [TreeClientCache.peek], so the FIRST frame
     * is the "Loading workspace tree" flash (then the OFF-Main read paints the held
     * tree — never a Main-thread read). Deterministic now that the per-VM
     * real-`Dispatchers.IO` warm is removed.
     */
    @Test
    fun coldStartWithoutProcessStartWarm_flashesLoadingThenPaintsOffMain_RED() = runTest {
        newCache().write(
            HOST.name,
            TreeClientCache.CachedTree(nodes = listOf(node("alpha", 0, folderPath("alpha")))),
        )
        val coldCache = newCache() // fresh process: file on disk, parsed map cold.
        // NB: no `coldCache.warmAll()` — this is the App.onCreate-warm-absent case.
        val gateway = StubGateway(listOf(sessionRow("alpha")))
        val vm = newViewModel(gateway, coldCache)

        bind(vm)
        assertTrue(
            "without the process-start warm the cold `bind` MISSES peek and flashes " +
                "Loading — got ${vm.state.value}",
            vm.state.value is FolderListUiState.Loading,
        )
        // The off-Main fallback still paints the held tree (no Main-thread read).
        runCurrent()
        val painted = vm.state.value
        assertTrue(
            "the OFF-Main cold-miss read must still paint the held tree — got $painted",
            painted is FolderListUiState.Ready,
        )
        assertEquals(
            listOf("alpha"),
            (painted as FolderListUiState.Ready).flatSessions.map { it.sessionName },
        )
    }

    /**
     * Issue #1155 (Part A) GREEN — the fix. The process-start warm
     * ([TreeClientCache.warmAll], exactly what [com.pocketshell.app.App.onCreate]
     * runs off Main, many frames ahead of navigation) parses the on-disk snapshot
     * into the process-wide in-memory map BEFORE the user navigates in. So the SAME
     * cold-memory cache, once warmed, makes the first `bind` [peek] HIT — the FIRST
     * state read (before any coroutine runs) is already the persisted Ready tree,
     * NO Loading spinner flash. Load-bearing proof that the startup warm (the ONLY
     * warm path now the per-VM warm is removed) kills the disruptive loading on the
     * maintainer's deep-link-then-back path.
     */
    @Test
    fun coldStartAfterProcessStartWarm_rendersInstantlyNoLoadingFlash_GREEN() = runTest {
        newCache().write(
            HOST.name,
            TreeClientCache.CachedTree(nodes = listOf(node("alpha", 0, folderPath("alpha")))),
        )
        val coldCache = newCache() // fresh process: file on disk, parsed map cold.
        // The App.onCreate process-start warm (issue #1155 Part A): parse the
        // on-disk snapshot into memory BEFORE navigation. This one call is the
        // WHOLE difference from the RED test above.
        coldCache.warmAll()

        val gateway = StubGateway(listOf(sessionRow("alpha")))
        val vm = newViewModel(gateway, coldCache)

        bind(vm)
        val first = vm.state.value
        assertTrue(
            "after the process-start warm the cold connect must render the persisted tree " +
                "INSTANTLY (Ready, no Loading flash) — got $first",
            first is FolderListUiState.Ready,
        )
        assertEquals(
            listOf("alpha"),
            (first as FolderListUiState.Ready).flatSessions.map { it.sessionName },
        )
    }

    // ---- silent reconcile: changing one node does NOT rebuild/reorder --------

    @Test
    fun silentReconcileKeepsNodeIdentityAndOrderStable() = runTest {
        val cache = newCache()
        writeNodes(
            cache,
            node("alpha", 0, folderPath("alpha")),
            node("beta", 1, folderPath("beta")),
        )
        // The probe reports the SAME sessions in the SAME order, but flips beta's
        // attached flag (the "only one node changed" case).
        val gateway = StubGateway(
            listOf(
                sessionRow("alpha", attached = false),
                sessionRow("beta", attached = true),
            ),
        )
        val vm = newViewModel(gateway, cache)

        bind(vm)
        runCurrent()
        val orderBefore = readySessions(vm)
        assertEquals(listOf("alpha", "beta"), orderBefore)

        // Drive the reconcile to completion. The node identity + order must be
        // STABLE across it — no rebuild, no reorder, no empty flash.
        runCurrent()
        val orderAfter = readySessions(vm)
        assertEquals(
            "reconcile that changes only one field keeps node order stable",
            orderBefore,
            orderAfter,
        )
        // The changed field IS reflected (the reconcile is authoritative), proving
        // the update was in place, not a no-op.
        val ready = vm.state.value as FolderListUiState.Ready
        assertTrue(
            "the in-place reconcile applied beta's attached=true",
            ready.flatSessions.first { it.sessionName == "beta" }.attached,
        )
    }

    // ---- session switch causes NO tree churn / reorder ----------------------

    @Test
    fun sessionSwitchDoesNotReorderOrReloadTree() = runTest {
        val cache = newCache()
        writeNodes(
            cache,
            node("alpha", 0, folderPath("alpha")),
            node("beta", 1, folderPath("beta")),
        )
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway, cache)

        bind(vm)
        runCurrent()
        val orderBefore = readySessions(vm)

        // Simulate a session switch: the screen leaves (stopPolling) and returns
        // (same-host re-bind). After the first reconcile the gateway is told to
        // fail loudly if probed again — proving the re-bind reuses the held tree
        // and does NOT churn / reload it.
        gateway.rows = null
        vm.stopPolling()
        runCurrent()
        // A same-host re-bind must NOT show Loading and must NOT reorder.
        val states = collectStates(vm)
        bind(vm)
        runCurrent()

        assertFalse(
            "a session switch (same-host re-bind) must not flash Loading",
            states.any { it is FolderListUiState.Loading },
        )
        assertEquals(
            "a session switch must not reorder the tree",
            orderBefore,
            readySessions(vm),
        )
    }

    // ---- cache is ADVISORY, not authoritative -------------------------------

    @Test
    fun cacheIsAdvisoryReconcilePrunesStaleCachedSession() = runTest {
        val cache = newCache()
        // The cache holds a STALE session ('ghost') that no longer exists, plus a
        // real one ('alpha').
        writeNodes(
            cache,
            node("ghost", 0, folderPath("ghost")),
            node("alpha", 1, folderPath("alpha")),
        )
        // The authoritative probe reports ONLY 'alpha'. HOLD the reconcile behind
        // a gate so the off-Main cache paint is observable before it lands (issue
        // #965 — otherwise StateFlow conflates the transient seed paint away).
        val reconcileGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway = StubGateway(listOf(sessionRow("alpha")), reconcileGate = reconcileGate)
        val vm = newViewModel(gateway, cache)

        val states = collectStates(vm)
        bind(vm)
        // Drain the OFF-Main cache read + advisory paint (the reconcile is still
        // gated, so it cannot run yet and conflate the seed away).
        runCurrent()

        // The seeded advisory render carries BOTH sessions — the stale 'ghost'
        // too — before the authoritative reconcile prunes it.
        val firstReady = states.filterIsInstance<FolderListUiState.Ready>().first()
        assertTrue(
            "the off-Main cache read seeds the stale 'ghost' too (advisory render)",
            firstReady.flatSessions.map { it.sessionName }.contains("ghost"),
        )
        // Now release the reconcile: the authoritative probe is the source of
        // truth — 'ghost' is pruned in place once it lands, so a stale cache entry
        // can never survive past the first refresh (D22 / #679 stale-type guard).
        reconcileGate.complete(Unit)
        runCurrent()
        assertEquals(
            "the reconcile is authoritative: the stale cached session is pruned",
            listOf("alpha"),
            readySessions(vm),
        )
    }

    // ---- pull-to-refresh persists the freshened tree back to the cache ------

    @Test
    fun reconcilePersistsFreshenedTreeToClientCache() = runTest {
        val cache = newCache()
        // No cache yet (first-ever open).
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway, cache)

        bind(vm)
        runCurrent()
        assertEquals(listOf("alpha", "beta"), readySessions(vm))

        // The first reconcile must have written the settled tree to the cache so
        // the NEXT cold start renders instantly (the local-first SWR half).
        val cached = cache.read(HOST.name).nodes.map { it.session }
        assertEquals(
            "the reconcile mirrors the settled tree into the client cache",
            listOf("alpha", "beta"),
            cached,
        )
    }

    // ---- REOPEN (v0.4.14): cold start renders the GROUPED tree, not a flat ---
    // ---- list dumped into 'Other folders' with '0 projects' -----------------

    /**
     * Issue #867 REOPEN regression. The shipped fix cached ONLY the per-session
     * nodes, so a cold-start render had no watched-root resolution / scanned
     * project folders → every session fell into "Other folders" and each watched
     * root showed "0 projects" (the spinner / empty-rebuild state the maintainer
     * re-reported on v0.4.14). This is the load-bearing assertion the original
     * test missed: it only checked the FLAT session list, which renders fine
     * regardless of grouping (the G6/F2 proxy gap).
     *
     * RED on the shipped code: `treeRoots` is empty OR the "git" root has 0
     * project subfolders and the sessions sit in "Other folders".
     * GREEN with the structural cache: the cached "git" root paints WITH its
     * project subfolders and the sessions bucketed under it, instantly.
     */
    @Test
    fun coldStartRendersGroupedTreeFromCacheNotOtherFoldersFlash() = runTest {
        val cache = newCache()
        val gitRoot = canonical("/home/alexey/git")
        // A previous app session left a SETTLED tree: two sessions under
        // ~/git/<project>, the watched root resolved, and the scanned project
        // subfolders — the full grouped shape, persisted to the client cache.
        cache.write(
            HOST.name,
            TreeClientCache.CachedTree(
                nodes = listOf(
                    node("alpha", 0, folderPath("alpha")),
                    node("beta", 1, folderPath("beta")),
                ),
                watchedFolders = listOf(watchedRoot("git", gitRoot)),
                resolvedWatchedRootPaths = mapOf(gitRoot to gitRoot),
                scannedProjectFoldersByRoot = mapOf(
                    gitRoot to listOf(folderPath("alpha"), folderPath("beta"), folderPath("gamma")),
                ),
            ),
        )
        // The gateway never gets to probe in this test — we assert the
        // CACHE-seeded grouping, captured as the first Ready, before any
        // authoritative reconcile lands.
        val gateway = StubGateway(rows = null)
        val vm = newViewModel(gateway, cache, watchedRoots = listOf(watchedRoot("git", gitRoot)))

        bind(vm)

        // Issue #1109: the cache seed is SYNCHRONOUS, so the FIRST state read
        // (before any coroutine runs) is already the cache-seeded Ready — the
        // GROUPED tree, no Loading flash. The "git" watched root paints with its
        // project subfolders and the two sessions bucketed UNDER it, NOT a flat
        // list dumped into "Other folders" with "0 projects".
        val ready = vm.state.value as? FolderListUiState.Ready
            ?: error("cold start must paint the cached grouped tree INSTANTLY, got ${vm.state.value}")
        val gitTreeRoot = ready.treeRoots.firstOrNull { it.path == gitRoot }
            ?: error(
                "the cached 'git' watched root must paint instantly with its grouping; " +
                    "treeRoots=${ready.treeRoots.map { it.path }}",
            )
        // The 'git' root carries its project subfolders (the "N projects" count) —
        // RED before the fix, where the structure was not cached so this was empty.
        val gitProjectPaths = gitTreeRoot.folders.map { it.path }
        assertTrue(
            "the cached 'git' root must show its project subfolders instantly " +
                "(not '0 projects'); folders=$gitProjectPaths",
            gitProjectPaths.contains(folderPath("alpha")) &&
                gitProjectPaths.contains(folderPath("beta")),
        )
        // And the sessions are bucketed UNDER the git root, not in "Other folders".
        val sessionsUnderGit = gitTreeRoot.folders.flatMap { it.sessions }.map { it.sessionName }
        assertEquals(
            "the cached sessions must render UNDER their watched root, not 'Other folders'",
            listOf("alpha", "beta").sorted(),
            sessionsUnderGit.sorted(),
        )
    }

    /**
     * Issue #867 REOPEN: the freshened tree's STRUCTURE (not just the sessions)
     * round-trips through the client cache, so the NEXT cold start (the test
     * above) has the grouping to render. RED before the fix: the cache stored no
     * structure, so `read(...).resolvedWatchedRootPaths` was empty.
     */
    @Test
    fun reconcilePersistsTreeStructureToClientCache() = runTest {
        val cache = newCache()
        val gitRoot = canonical("/home/alexey/git")
        val gateway = StubGateway(
            rows = listOf(sessionRow("alpha"), sessionRow("beta")),
            projectFoldersByRoot = mapOf(
                gitRoot to listOf(folderPath("alpha"), folderPath("beta")),
            ),
            resolvedWatchedRootPaths = mapOf(gitRoot to gitRoot),
        )
        val vm = newViewModel(gateway, cache, watchedRoots = listOf(watchedRoot("git", gitRoot)))

        bind(vm)
        runCurrent()
        assertEquals(listOf("alpha", "beta"), readySessions(vm))

        val cached = cache.read(HOST.name)
        assertEquals(
            "the reconcile must persist the resolved watched-root paths to the cache",
            mapOf(gitRoot to gitRoot),
            cached.resolvedWatchedRootPaths,
        )
        assertEquals(
            "the reconcile must persist the scanned project folders to the cache",
            mapOf(gitRoot to listOf(folderPath("alpha"), folderPath("beta"))),
            cached.scannedProjectFoldersByRoot,
        )
        assertTrue(
            "the reconcile must persist the watched-root overlay to the cache",
            cached.watchedFolders.any { canonical(it.path) == gitRoot },
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun TestScope.collectStates(vm: FolderListViewModel): MutableList<FolderListUiState> {
        val states = mutableListOf<FolderListUiState>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            vm.state.collect { states.add(it) }
        }
        runCurrent()
        // Drop the seed Loading state collected before bind so the assertions read
        // only the cold-start cycle.
        states.clear()
        return states
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

    private fun readySessions(vm: FolderListViewModel): List<String> =
        (vm.state.value as FolderListUiState.Ready).flatSessions.map { it.sessionName }

    private fun folderPath(name: String): String =
        FolderListViewModel.canonicalisePath("/home/alexey/git/$name")

    private fun canonical(path: String): String = FolderListViewModel.canonicalisePath(path)

    private fun watchedRoot(label: String, path: String): ProjectRootEntity =
        ProjectRootEntity(hostId = HOST.id, label = label, path = path)

    private fun node(name: String, order: Int, path: String): TreeRemoteSource.TreeNode =
        TreeRemoteSource.TreeNode(
            session = name,
            order = order,
            folderPath = path,
            collapsed = false,
        )

    private fun writeNodes(cache: TreeClientCache, vararg nodes: TreeRemoteSource.TreeNode) {
        cache.write(HOST.name, TreeClientCache.CachedTree(nodes = nodes.toList()))
    }

    private fun sessionRow(name: String, attached: Boolean = true): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = attached,
            cwd = "/home/alexey/git/$name",
            agentKind = SessionAgentKind.Shell,
        )

    private fun newCache(): TreeClientCache =
        TreeClientCache(isolatedCacheContext())

    /**
     * A [Context] whose `filesDir` is this test's own temp dir, so the
     * [TreeClientCache] writes/reads under an isolated `tree-cache/` folder and no
     * on-disk snapshot leaks across test methods (blocker 1 hermeticity).
     */
    private fun isolatedCacheContext(): Context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = testCacheFilesDir
        }

    private fun TestScope.newViewModel(
        gateway: FolderListGateway,
        cache: TreeClientCache,
        watchedRoots: List<ProjectRootEntity> = emptyList(),
    ): FolderListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val session = NoopTreeSshSession()
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
            projectRootDao = FakeProjectRootDao(watchedRoots),
            sshLeaseManager = manager,
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            // No treeRemoteSource: this suite exercises the CLIENT cache path in
            // isolation (the daemon registry is covered by the durability suite).
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

    /** A connected session that has no `tree` daemon (advisory-cache path only). */
    private class NoopTreeSshSession : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = ExecResult("", "no daemon", 127)
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
        // Issue #867 (REOPEN): optional structural result so a test can assert
        // the GROUPED tree (sessions under their watched root) on reconcile, not
        // just the flat session list.
        @Volatile var projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        @Volatile var resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
        // Issue #965: an optional gate the test completes to RELEASE the
        // reconcile. With the off-Main cache read, the advisory cache paint and
        // the reconcile are both coroutines; holding the reconcile here lets a
        // test deterministically observe the cache-seeded paint BEFORE the
        // authoritative reconcile prunes it (StateFlow would otherwise conflate
        // the transient seed paint away under virtual time).
        private val reconcileGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : FolderListGateway {
        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            reconcileGate?.await()
            val r = rows ?: error("gateway probe must not be called (tree should be reused, not reloaded)")
            return FolderListResult.Sessions(
                rows = r,
                projectFoldersByRoot = projectFoldersByRoot,
                resolvedWatchedRootPaths = resolvedWatchedRootPaths,
            )
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

    private class FakeProjectRootDao(
        initial: List<ProjectRootEntity> = emptyList(),
    ) : ProjectRootDao {
        private val roots = MutableStateFlow(initial)
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
