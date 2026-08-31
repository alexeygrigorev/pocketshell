package com.pocketshell.app.fileviewer

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.RemoteListing
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #1715 — ViewModel workspace hydrate / upsert / switch / close / dirty-work.
 *
 * G6 mutations:
 *  - skip hydrate-once → a second bind would increment [FakeWorkspaceSource.getCalls]
 *  - add a tab on CannotPreview without cacheFile → missing new files would appear
 *  - key the #697 cache by raw Request → relative then absolute A would miss
 *  - switch without the pending-work guard → review comments would ride onto B
 *
 * ## Issue #2339 — why there is not a single wall-clock wait in this class
 *
 * This class shipped flaky by construction and reddened the required `Unit
 * tests` check on `main`: a DIFFERENT member failed on each run of the SAME
 * tree (`closeActiveSelectsRightThenEmpty` on CI,
 * `relativeThenAbsoluteAToBToAHitsTheResolvedPathCache` and
 * `hydrateOnceThenSuccessfulOpenAddsTab` locally). It was the #708/#882/#1048
 * class exactly: a `runBlocking` body polled `state.value` behind hand-rolled
 * `System.currentTimeMillis()` deadlines (10 s, and a tighter 5 s inline) while
 * the ViewModel's owned work ran on a REAL `Dispatchers.IO` pool. Such a pump
 * returns the instant ONE observable lands, so every sibling effect of the same
 * turn — the fire-and-forget workspace upsert, the live re-fetch that must LOSE
 * the race against the cache paint — was sampled at an arbitrary moment.
 *
 * The cure is the convention's Shape A (pinned seam), not a bigger budget:
 * EVERY hop the code under test owns now resolves on ONE
 * [kotlinx.coroutines.test.TestCoroutineScheduler] —
 *  - `Dispatchers.Main` via [MainDispatcherRule] (so does `viewModelScope` and
 *    the test-owned `workspaceWriteScope`),
 *  - the ViewModel's blocking SSH hop via [FileViewerViewModel.ioDispatcher],
 *  - the lease manager's owned dial, its abort, and its idle-close scope.
 *
 * [settle] then drains that scheduler with `runCurrent()`, which runs every task
 * due at the current instant (including the ones those tasks schedule) and
 * NEVER advances virtual time. That last property is load-bearing: a deliberate
 * park in this class (a gated download, a serialized workspace write) must stay
 * parked, and advancing the clock would instead fire
 * `LeaseSessionExec`'s 45 s block bound and turn the park into a timeout.
 *
 * Consequence for anyone extending this class: there is nothing to wait for.
 * Call [settle] and assert. Do NOT reintroduce a `System.currentTimeMillis()`
 * deadline, a `Thread.sleep`, or a `delay` — with the seam pinned they would
 * measure nothing except how busy the box is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerWorkspaceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scheduler get() = mainDispatcherRule.dispatcher.scheduler

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * Runs every task the code under test has scheduled at the current instant,
     * transitively, on the ONE shared test scheduler. Never advances the virtual
     * clock — see the class KDoc.
     */
    private fun settle() {
        scheduler.runCurrent()
    }

    @Test
    fun hydrateOnceThenSuccessfulOpenAddsTab() {
        val source = FakeWorkspaceSource()
        val vm = viewModel(source)
        vm.bind(request("/srv/a.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }
        assertEquals("hydrate must run once on first bind", 1, source.getCalls)
        assertEquals(listOf("/srv/a.txt"), vm.workspace.value.orderedTabs.map { it.absolutePath })
        assertEquals("/srv/a.txt", vm.workspace.value.activePath)
        assertTrue("successful open must persist", source.upsertCalls >= 1)

        vm.bind(request("/srv/b.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }
        assertEquals("a second bind must not re-poll the host workspace", 1, source.getCalls)
        assertEquals(
            listOf("/srv/a.txt", "/srv/b.txt"),
            vm.workspace.value.orderedTabs.map { it.absolutePath },
        )
        vm.close()
    }

    @Test
    fun missingNewFileDoesNotAddATab() {
        val session = MutableFileSession(body = "unused")
        session.missing.add("/srv/gone.txt")
        val source = FakeWorkspaceSource()
        val vm = viewModel(source, session)
        vm.bind(request("/srv/gone.txt"))
        vm.state.awaitCannotPreview()
        assertTrue(
            "a reachability/missing failure must not create a tab",
            vm.workspace.value.orderedTabs.isEmpty(),
        )
        vm.close()
    }

    @Test
    fun relativeThenAbsoluteAToBToAHitsTheResolvedPathCache() {
        val session = MutableFileSession(body = "unused")
        session.pathBodies["/srv/a.txt"] = "A original"
        session.pathBodies["/srv/b.txt"] = "B body"
        val source = FakeWorkspaceSource()
        val vm = viewModel(source, session)

        vm.bind(request("a.txt", cwd = "/srv"))
        assertEquals("A original", vm.state.awaitText { it.displayPath.endsWith("/a.txt") }.content)

        vm.bind(request("/srv/b.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }

        session.pathBodies["/srv/a.txt"] = "A CHANGED"
        val downloadsBefore = session.downloads.get()
        // #2339: the property under test is an ORDER — the resolved-path cache
        // paints FIRST, the live re-fetch replaces it after. Sampling
        // `state.value` right after `bind` proved nothing: whether the cache or
        // the fetch was showing depended on which real thread got there first,
        // and the fetch won often enough to red the class. Gate the reopen's
        // download inside the fake session so the live body provably CANNOT
        // have landed at the moment we sample the paint.
        session.blockNextDownload = true
        vm.bind(request("/srv/a.txt"))
        settle()
        assertTrue(
            "the reopen's live fetch must be in flight and still gated before the paint is sampled",
            session.downloadStarted.isCompleted && !session.releaseDownload.isCompleted,
        )
        val gatedPaint = vm.state.value
        assertTrue(
            "absolute reopen of a relative-opened file must paint the resolved-path " +
                "cache while the live fetch is still gated, was $gatedPaint",
            gatedPaint is FileViewerUiState.TextContent &&
                gatedPaint.displayPath.endsWith("/a.txt") &&
                gatedPaint.content == "A original",
        )

        session.releaseDownload.complete(Unit)
        val fresh = vm.state.awaitText { it.displayPath.endsWith("/a.txt") && it.content == "A CHANGED" }
        assertEquals("A CHANGED", fresh.content)
        assertTrue(
            "reconcile must still issue a live fetch",
            session.downloads.get() > downloadsBefore,
        )
        vm.close()
    }

    @Test
    fun openFilesRestoresTabsAndActiveAfterVmRecreation() {
        val session = MutableFileSession(body = "unused")
        session.pathBodies["/srv/a.txt"] = "A"
        session.pathBodies["/srv/b.txt"] = "B"
        session.pathBodies["/srv/c.txt"] = "C"
        val source = FakeWorkspaceSource()
        val first = viewModel(source, session)
        first.bind(request("/srv/a.txt"))
        first.state.awaitText { it.displayPath.endsWith("/a.txt") }
        first.bind(request("/srv/b.txt"))
        first.state.awaitText { it.displayPath.endsWith("/b.txt") }
        first.bind(request("/srv/c.txt"))
        first.state.awaitText { it.displayPath.endsWith("/c.txt") }
        assertEquals(3, first.workspace.value.orderedTabs.size)
        assertEquals("/srv/c.txt", first.workspace.value.activePath)
        first.close()

        val restored = viewModel(source, session)
        restored.bind(request(path = null))
        restored.state.awaitText { it.displayPath.endsWith("/c.txt") }
        assertEquals(
            "Open files must restore the three previously-open tabs",
            listOf("/srv/a.txt", "/srv/b.txt", "/srv/c.txt"),
            restored.workspace.value.orderedTabs.map { it.absolutePath },
        )
        assertEquals("/srv/c.txt", restored.workspace.value.activePath)
        restored.close()
    }

    @Test
    fun closeActiveSelectsRightThenEmpty() {
        val source = FakeWorkspaceSource()
        val vm = viewModel(source)
        vm.bind(request("/srv/a.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }
        vm.bind(request("/srv/b.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }
        vm.bind(request("/srv/c.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/c.txt") }

        vm.switchToTab(vm.workspace.value.orderedTabs[1]) // b
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }
        vm.closeTab(vm.workspace.value.orderedTabs.first { it.absolutePath.endsWith("/b.txt") })
        vm.state.awaitText { it.displayPath.endsWith("/c.txt") }
        assertEquals(listOf("/srv/a.txt", "/srv/c.txt"), vm.workspace.value.orderedTabs.map { it.absolutePath })

        vm.closeTab(vm.workspace.value.orderedTabs.first { it.absolutePath.endsWith("/c.txt") })
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }

        vm.closeTab(vm.workspace.value.orderedTabs.single())
        settle()
        assertTrue(
            "closing the last tab must land on EmptyWorkspace; was ${vm.state.value}",
            vm.state.value is FileViewerUiState.EmptyWorkspace,
        )
        assertTrue(vm.workspace.value.orderedTabs.isEmpty())
        vm.close()
    }

    @Test
    fun missingRestoredFileKeepsTheTab() {
        val session = MutableFileSession(body = "unused")
        session.pathBodies["/srv/a.txt"] = "A"
        val source = FakeWorkspaceSource(
            stored = FileWorkspace(
                orderedTabs = listOf(OpenFileTab("/srv/a.txt", 1)),
                activePath = "/srv/a.txt",
            ),
        )
        session.missing.add("/srv/a.txt")
        val vm = viewModel(source, session)
        vm.bind(request(path = null))
        vm.state.awaitCannotPreview()
        assertEquals(
            "a restored tab that is now missing must stay in the strip",
            listOf("/srv/a.txt"),
            vm.workspace.value.orderedTabs.map { it.absolutePath },
        )
        vm.close()
    }

    @Test
    fun reusedViewModelRehydratesWhenHostChanges() {
        val source = SequencedWorkspaceSource(
            listOf(
                FileWorkspaceResult(
                    workspace = FileWorkspace(
                        orderedTabs = listOf(OpenFileTab("/host-a/restored.md", 1)),
                        activePath = "/host-a/restored.md",
                    ),
                    available = true,
                ),
                FileWorkspaceResult(
                    workspace = FileWorkspace(
                        orderedTabs = listOf(OpenFileTab("/host-b/restored.md", 2)),
                        activePath = "/host-b/restored.md",
                    ),
                    available = true,
                ),
            ),
        )
        val vm = viewModel(source)

        vm.bind(request("/srv/a.txt", hostId = 1L))
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }
        vm.bind(request("/srv/b.txt", hostId = 2L))
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }

        assertEquals("a reused VM must hydrate once for each host", 2, source.getCalls)
        assertTrue(
            "host B's durable workspace must replace host A's in-memory workspace",
            vm.workspace.value.orderedTabs.any { it.absolutePath == "/host-b/restored.md" },
        )
        assertTrue(
            "host A's tabs must not bleed into host B",
            vm.workspace.value.orderedTabs.none { it.absolutePath == "/host-a/restored.md" },
        )
        vm.close()
    }

    @Test
    fun editedHostProfileWithSameIdDoesNotReuseWorkspaceOrCachedContent() {
        val source = SequencedWorkspaceSource(
            listOf(
                FileWorkspaceResult(
                    workspace = FileWorkspace(
                        orderedTabs = listOf(OpenFileTab("/host-a/restored.md", 1)),
                        activePath = "/host-a/restored.md",
                    ),
                    available = true,
                ),
                FileWorkspaceResult(
                    workspace = FileWorkspace(
                        orderedTabs = listOf(OpenFileTab("/host-b/restored.md", 2)),
                        activePath = "/host-b/restored.md",
                    ),
                    available = true,
                ),
            ),
        )
        val session = MutableFileSession(body = "host A")
        val vm = viewModel(source, session)

        // Room host IDs are mutable: editing a saved host can point the same
        // row at a different endpoint/account while this Hilt VM survives.
        vm.bind(request("/srv/shared.txt", hostId = 7L, hostname = "host-a"))
        vm.state.awaitText { it.content == "host A" }
        session.body.set("host B")
        session.blockNextDownload = true
        vm.bind(request("/srv/shared.txt", hostId = 7L, hostname = "host-b"))
        settle()
        assertTrue(
            "the edited-host reopen must have entered the gated download",
            session.downloadStarted.isCompleted && !session.releaseDownload.isCompleted,
        )

        assertEquals("a changed endpoint must hydrate a fresh workspace", 2, source.getCalls)
        assertTrue(
            "the edited host must use host B's restored tab",
            vm.workspace.value.orderedTabs.any { it.absolutePath == "/host-b/restored.md" },
        )
        assertTrue(
            "host A's tabs must not bleed through a reused Room ID",
            vm.workspace.value.orderedTabs.none { it.absolutePath == "/host-a/restored.md" },
        )
        assertTrue(
            "a changed endpoint must not paint host A's cached content",
            vm.state.value is FileViewerUiState.Loading,
        )
        session.releaseDownload.complete(Unit)
        vm.state.awaitText { it.content == "host B" }
        vm.close()
    }

    @Test
    fun unavailableWorkspaceExplainsHostUpdateAndRetryRestoresTabs() {
        val session = MutableFileSession(body = "unused")
        session.pathBodies["/srv/restored.md"] = "restored"
        val source = FakeWorkspaceSource().apply { available = false }
        val vm = viewModel(source, session)

        vm.bind(request(path = null))
        val unavailable = vm.state.awaitWorkspaceUnavailable()
        assertTrue(unavailable.message.contains("update PocketShell on this host"))

        source.available = true
        source.stored = FileWorkspace(
            orderedTabs = listOf(OpenFileTab("/srv/restored.md", 1)),
            activePath = "/srv/restored.md",
        )
        vm.retry()
        vm.state.awaitText { it.displayPath.endsWith("/restored.md") }
        assertEquals("retry must ask the host for the workspace again", 2, source.getCalls)
        assertEquals("/srv/restored.md", vm.workspace.value.activePath)
        vm.close()
    }

    @Test
    fun relativeActiveTabStillBlocksCloseWithPendingReview() {
        val source = FakeWorkspaceSource()
        val vm = viewModel(source)
        vm.bind(request("a.txt", cwd = "/srv"))
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }
        vm.bind(request("/srv/b.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }
        // Reopen through the relative route so lastRequest.path is relative
        // while the durable active identity is the resolved absolute path.
        vm.bind(request("a.txt", cwd = "/srv"))
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }
        vm.setLineComment(1, "keep this on A")

        val active = vm.workspace.value.orderedTabs.first { it.absolutePath == "/srv/a.txt" }
        vm.closeTab(active)

        assertTrue(
            "closing the active tab must compare resolved identity, not raw request text",
            vm.pendingTabAction.value is PendingTabAction.Close,
        )
        assertEquals("/srv/a.txt", vm.workspace.value.activePath)
        vm.close()
    }

    @Test
    fun workspaceWritesAreSerializedSoOlderSnapshotCannotWin() {
        val source = OrderedWriteWorkspaceSource()
        val vm = viewModel(source)
        vm.bind(request("/srv/a.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }
        settle()
        assertTrue("A's workspace write must have started", source.firstWriteStarted.isCompleted)

        vm.bind(request("/srv/b.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }
        // A full drain of the shared scheduler: if B's write has not started
        // after it, nothing except A's still-parked write can be holding it.
        settle()
        assertFalse(
            "the second host write must wait for the first snapshot",
            source.secondWriteStarted.isCompleted,
        )

        source.releaseFirstWrite.complete(Unit)
        settle()
        assertTrue(
            "releasing the first write must let the queued second write run",
            source.secondWriteStarted.isCompleted,
        )
        assertEquals(
            "the latest workspace snapshot must be the durable winner",
            "/srv/b.txt",
            source.stored?.activePath,
        )
        vm.close()
    }

    @Test
    fun rejectedWorkspaceWriteIsObservableInsteadOfSilentlyIgnored() {
        val source = FakeWorkspaceSource().apply { upsertResult = false }
        val vm = viewModel(source)
        vm.bind(request("/srv/rejected.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/rejected.txt") }

        val failure = vm.workspaceWriteState.awaitState {
            it is FileWorkspaceWriteState.Failed
        } as FileWorkspaceWriteState.Failed
        assertTrue(failure.message.contains("rejected", ignoreCase = true))
        assertEquals("local tab state remains available after a failed host write", "/srv/rejected.txt", failure.workspace.activePath)
        vm.close()
    }

    @Test
    fun workspaceWriteSurvivesViewModelClearUntilProcessScopeFinishes() {
        val source = OrderedWriteWorkspaceSource()
        val pair = viewModel(source)
        pair.vm.bind(request("/srv/process-death.txt"))
        pair.vm.state.awaitText { it.displayPath.endsWith("/process-death.txt") }
        settle()
        assertTrue("the workspace write must be in flight", source.firstWriteStarted.isCompleted)

        // Activity/ViewModel recreation clears the VM, but must not cancel the
        // process-scoped write queue before its host acknowledgement lands.
        ViewModelStore().apply {
            put("file-viewer", pair.vm)
            clear()
        }
        source.releaseFirstWrite.complete(Unit)
        val saved = pair.vm.workspaceWriteState.awaitState {
            it is FileWorkspaceWriteState.Saved
        } as FileWorkspaceWriteState.Saved
        assertEquals("/srv/process-death.txt", saved.workspace.activePath)
        assertEquals("the write must reach the host after VM clear", "/srv/process-death.txt", source.stored?.activePath)
        pair.close()
    }

    @Test
    fun dirtyBackQueuesBackActionAndDiscardReturnsIt() {
        val source = FakeWorkspaceSource()
        val vm = viewModel(source)
        vm.bind(request("/srv/dirty-back.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/dirty-back.txt") }
        vm.setLineComment(1, "must guard app-bar Back")

        assertFalse(vm.vm.requestBack())
        assertTrue(vm.pendingTabAction.value is PendingTabAction.Back)
        assertTrue(vm.vm.discardPendingWorkAndProceed() is PendingTabAction.Back)
        assertFalse(vm.hasPendingWork())
        vm.close()
    }

    @Test
    fun pendingReviewBlocksSwitchUntilDiscard() {
        val source = FakeWorkspaceSource()
        val vm = viewModel(source)
        vm.bind(request("/srv/a.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }
        vm.bind(request("/srv/b.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }
        vm.switchToTab(vm.workspace.value.orderedTabs[0])
        vm.state.awaitText { it.displayPath.endsWith("/a.txt") }

        vm.setLineComment(1, "looks wrong")
        assertTrue(vm.hasPendingWork())
        val target = vm.workspace.value.orderedTabs[1]
        vm.switchToTab(target)
        assertTrue(
            "pending review must block the switch, not silently carry comments onto B",
            vm.pendingTabAction.value is PendingTabAction.Switch,
        )
        assertEquals("/srv/a.txt", vm.workspace.value.activePath)
        assertEquals("looks wrong", vm.reviewState.value.lineComments[1])

        vm.stayOnTab()
        assertNull(vm.pendingTabAction.value)

        vm.switchToTab(target)
        vm.discardPendingWorkAndProceed()
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }
        assertFalse(vm.hasPendingWork())
        assertTrue(vm.reviewState.value.lineComments.isEmpty())
        vm.close()
    }

    @Test
    fun capEvictsOldestInactiveOnThirteenthOpen() {
        val session = MutableFileSession(body = "unused")
        val source = FakeWorkspaceSource()
        val vm = viewModel(source, session)
        repeat(12) { i ->
            val path = "/srv/%02d.txt".format(i)
            session.pathBodies[path] = "body-$i"
            vm.bind(request(path))
            vm.state.awaitText { it.displayPath.endsWith("%02d.txt".format(i)) }
        }
        session.pathBodies["/srv/new.txt"] = "new"
        vm.bind(request("/srv/new.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/new.txt") }
        val paths = vm.workspace.value.orderedTabs.map { it.absolutePath }
        assertEquals(12, paths.size)
        assertTrue("/srv/new.txt" in paths)
        assertTrue("/srv/00.txt" !in paths)
        vm.close()
    }

    private fun viewModel(
        source: FileWorkspaceRemoteSource,
        session: MutableFileSession = MutableFileSession(body = "hello"),
    ): PairVm {
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            // #2339: the manager's OWN scopes are pinned to the shared test
            // scheduler too, so nothing this class exercises can land on a real
            // thread pool. The idle-close job (`delay(idleTtlMillis)`) therefore
            // never fires, because [settle] deliberately never advances virtual
            // time — which is what we want: a lease that closes itself
            // mid-class would be another source of run-to-run divergence.
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)),
            idleTtlMillis = 30_000L,
            // The owned dial and its abort must share the caller's clock: the
            // acquire wraps `dial.await()` in `withTimeoutOrNull`, so a dial on a
            // REAL dispatcher raced against a virtual-time bound (see the
            // SshLeaseManager constructor KDoc).
            connectTimeoutContext = StandardTestDispatcher(scheduler),
            abortTimeoutContext = StandardTestDispatcher(scheduler),
        )
        val vm = FileViewerViewModel(
            context,
            leaseManager,
            workspaceSource = source,
        ).also {
            it.nowMillis = { clock.getAndIncrement().toLong() }
            // Shape A: the ViewModel's blocking SSH hop resolves on the test
            // scheduler instead of Dispatchers.IO (#2339).
            it.ioDispatcher = StandardTestDispatcher(scheduler)
        }
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        vm.workspaceWriteScope = writeScope
        return PairVm(vm, leaseManager, writeScope)
    }

    private val clock = AtomicInteger(1)

    private data class PairVm(
        val vm: FileViewerViewModel,
        val leaseManager: SshLeaseManager,
        val writeScope: CoroutineScope,
    ) {
        val state get() = vm.state
        val workspace get() = vm.workspace
        val workspaceWriteState get() = vm.workspaceWriteState
        val pendingTabAction get() = vm.pendingTabAction
        val reviewState get() = vm.reviewState
        fun bind(request: FileViewerViewModel.Request) = vm.bind(request)
        fun switchToTab(tab: OpenFileTab) = vm.switchToTab(tab)
        fun closeTab(tab: OpenFileTab) = vm.closeTab(tab)
        fun retry() = vm.retry()
        fun stayOnTab() = vm.stayOnTab()
        fun discardPendingWorkAndProceed() = vm.discardPendingWorkAndProceed()
        fun hasPendingWork() = vm.hasPendingWork()
        fun setLineComment(line: Int, text: String) = vm.setLineComment(line, text)
        fun close() {
            writeScope.cancel()
            leaseManager.close()
        }
    }

    private fun StateFlow<FileWorkspaceWriteState>.awaitState(
        predicate: (FileWorkspaceWriteState) -> Boolean,
    ): FileWorkspaceWriteState {
        settle()
        val current = value
        assertTrue(
            "workspace write never reached the expected state; was $current",
            predicate(current),
        )
        return current
    }

    private fun StateFlow<FileViewerUiState>.awaitText(
        predicate: (FileViewerUiState.TextContent) -> Boolean = { true },
    ): FileViewerUiState.TextContent {
        settle()
        val current = value
        assertTrue(
            "viewer never reached the expected TextContent state; was $current",
            current is FileViewerUiState.TextContent && predicate(current),
        )
        return current as FileViewerUiState.TextContent
    }

    private fun StateFlow<FileViewerUiState>.awaitCannotPreview(): FileViewerUiState.CannotPreview {
        settle()
        val current = value
        assertTrue("viewer never reached CannotPreview; was $current", current is FileViewerUiState.CannotPreview)
        return current as FileViewerUiState.CannotPreview
    }

    private fun StateFlow<FileViewerUiState>.awaitWorkspaceUnavailable(): FileViewerUiState.WorkspaceUnavailable {
        settle()
        val current = value
        assertTrue(
            "viewer never reached WorkspaceUnavailable; was $current",
            current is FileViewerUiState.WorkspaceUnavailable,
        )
        return current as FileViewerUiState.WorkspaceUnavailable
    }

    private fun request(
        path: String?,
        cwd: String? = null,
        hostId: Long = 1L,
        hostname: String = "10.0.2.2",
        port: Int = 2222,
        username: String = "tester",
        keyPath: String = "/tmp/key",
    ) = FileViewerViewModel.Request(
        hostId = hostId,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
        trustedHostKeySha256 = "SHA256:test",
        passphrase = null,
        path = path,
        cwd = cwd,
    )

    private class FakeWorkspaceSource(
        stored: FileWorkspace = FileWorkspace.Empty,
    ) : FileWorkspaceRemoteSource() {
        var stored: FileWorkspace = stored
        var available: Boolean = true
        var getCalls: Int = 0
        var upsertCalls: Int = 0
        var upsertResult: Boolean = true

        override suspend fun getWorkspace(session: SshSession): FileWorkspaceResult {
            getCalls += 1
            return if (available) {
                FileWorkspaceResult(this.stored, available = true)
            } else {
                FileWorkspaceResult.Unavailable
            }
        }

        override suspend fun upsertWorkspace(
            session: SshSession,
            workspace: FileWorkspace,
        ): Boolean {
            upsertCalls += 1
            if (upsertResult) stored = workspace
            return upsertResult
        }
    }

    private class SequencedWorkspaceSource(
        private val results: List<FileWorkspaceResult>,
    ) : FileWorkspaceRemoteSource() {
        var getCalls: Int = 0

        override suspend fun getWorkspace(session: SshSession): FileWorkspaceResult {
            val index = getCalls++
            return results.getOrElse(index) { results.last() }
        }

        override suspend fun upsertWorkspace(
            session: SshSession,
            workspace: FileWorkspace,
        ): Boolean = true
    }

    private class OrderedWriteWorkspaceSource : FileWorkspaceRemoteSource() {
        var upsertCalls: Int = 0
        var stored: FileWorkspace? = null
        val firstWriteStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondWriteStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirstWrite = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun getWorkspace(session: SshSession): FileWorkspaceResult =
            FileWorkspaceResult.Empty

        override suspend fun upsertWorkspace(
            session: SshSession,
            workspace: FileWorkspace,
        ): Boolean {
            val call = synchronized(this) { ++upsertCalls }
            if (call == 1) {
                firstWriteStarted.complete(Unit)
                releaseFirstWrite.await()
            } else {
                secondWriteStarted.complete(Unit)
            }
            stored = workspace
            return true
        }
    }

    private class CountingConnector(
        private val session: MutableFileSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    private class MutableFileSession(
        body: String,
    ) : SshSession {
        var closed: Boolean = false
        val body = AtomicReference(body)
        val pathBodies = mutableMapOf<String, String>()
        val missing = mutableSetOf<String>()
        val downloads = AtomicInteger(0)

        /**
         * #2339 — the deterministic seam that makes "the cache paints before the
         * live fetch lands" an ORDER the test controls rather than a race it
         * hopes to win. Set it before the bind whose download must be observed
         * mid-flight; the download then parks on [releaseDownload] until the
         * test completes it.
         */
        var blockNextDownload: Boolean = false
        val downloadStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseDownload = kotlinx.coroutines.CompletableDeferred<Unit>()

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "/home/tester\n", stderr = "", exitCode = 0)

        override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray {
            downloads.incrementAndGet()
            if (blockNextDownload) {
                blockNextDownload = false
                downloadStarted.complete(Unit)
                releaseDownload.await()
            }
            if (remotePath in missing) {
                throw com.pocketshell.core.ssh.SshFileNotFoundException(remotePath)
            }
            val text = pathBodies[remotePath] ?: body.get()
            return text.toByteArray(Charsets.UTF_8)
        }

        override suspend fun listDirectory(remotePath: String, maxEntries: Int): RemoteListing =
            error("not used")

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }
}
