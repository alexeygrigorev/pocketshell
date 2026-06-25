package com.pocketshell.app.projects

import android.os.StrictMode
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.StrictModeInstaller
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.proof.RecordingDiagnosticSink
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #965 (ANR on the project list at scale) — the LOAD-BEARING on-device
 * red→green for the dominant cold-start cause: a SYNCHRONOUS per-host tree-cache
 * disk read + full JSON parse on the MAIN thread inside [FolderListViewModel.bind].
 *
 * ## The reported symptom + why this fixture reproduces it (D33 / G10)
 *
 * The maintainer hit a full-app ANR ("PocketShell isn't responding") on the
 * folder list for a host at REAL scale — "git · 71 projects · 12 sessions". The
 * tree-cache read+parse ran on Main inside `bind()` (the class doc itself says
 * reads should be off a background dispatcher — the cold-start call site violated
 * it). At 71 projects / 12 sessions that parse — stacked with the projection and
 * the first composition — crossed the 5s ANR bar. A SMALL cache would not
 * reproduce a meaningful parse cost (the #847 lesson), so this fixture seeds the
 * tree cache at the maintainer's exact scale: **71 project folders under one
 * watched root + 12 sessions with mixed agent kinds** (Claude / Codex / OpenCode
 * / Shell).
 *
 * ## What it asserts (the StrictMode `disk_read` tripwire, #933)
 *
 * The process-wide StrictMode policy ([StrictModeInstaller]) records a
 * `strictmode.violation` with `kind=disk_read` for any Main-thread disk read. We
 * install that production policy on Main, drive the REAL `bind()` on Main against
 * the 71/12 cache, and HARD-assert ZERO violations whose top frame is in
 * [TreeClientCache] / `hydrateFromClientCache` over the open window.
 *
 *  - RED on base: `bind()` reads + parses the 71/12 cache file synchronously on
 *    Main → a `disk_read` violation rooted in `TreeClientCache.read` is recorded.
 *  - GREEN with the fix: the read + parse run OFF Main on [ioDispatcher] → no such
 *    violation.
 *
 * No Docker / SSH fixture is needed for the load-bearing assertion — the
 * Main-thread cache read happens during `bind()` before any network round-trip,
 * and the assertion is filtered to the cache call site, so unrelated reads cannot
 * pollute it. The VM is built with `attachLifecycle = false` and no live host so
 * the test is deterministic on the CI swiftshader AVD and slots into the per-push
 * journey gate with no workflow service change. It does NOT self-skip on CI (no
 * `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing assertion —
 * process.md F3 / D33).
 */
@RunWith(AndroidJUnit4::class)
class FolderListScaleAnrStrictModeDockerTest {

    private lateinit var db: AppDatabase
    private lateinit var cache: TreeClientCache
    private lateinit var keyFile: File
    private lateinit var diagnostics: RecordingDiagnosticSink
    private val viewModelStore = ViewModelStore()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = TreeClientCache(targetContext)
        keyFile = File(targetContext.cacheDir, "issue965-key").apply {
            parentFile?.mkdirs()
            if (!exists()) writeText("not-a-real-key")
        }
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
        viewModelStore.clear()
        runCatching { cache.write(HOST_NAME, TreeClientCache.CachedTree(nodes = emptyList())) }
        runCatching { db.close() }
        runCatching { keyFile.delete() }
    }

    @Test
    fun coldBindAtScaleDoesNotReadTreeCacheOnMainThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // ---- Seed the tree cache at the MAINTAINER'S SCALE: 71 projects under
        // one watched root + 12 sessions with mixed agent kinds. ----
        val gitRoot = FolderListViewModel.canonicalisePath("/home/alexey/git")
        val projectPaths = (0 until 71).map {
            FolderListViewModel.canonicalisePath("/home/alexey/git/project-$it")
        }
        val agentKinds = listOf("claude", "codex", "opencode", null)
        val nodes = (0 until 12).map { i ->
            TreeRemoteSource.TreeNode(
                session = "session-$i",
                order = i,
                folderPath = projectPaths[i],
                collapsed = false,
                foreignKind = agentKinds[i % agentKinds.size],
            )
        }
        cache.write(
            HOST_NAME,
            TreeClientCache.CachedTree(
                nodes = nodes,
                watchedFolders = listOf(ProjectRootEntity(hostId = 0L, label = "git", path = gitRoot)),
                resolvedWatchedRootPaths = mapOf(gitRoot to gitRoot),
                scannedProjectFoldersByRoot = mapOf(gitRoot to projectPaths),
                historyProjectFoldersByRoot = mapOf(gitRoot to projectPaths.takeLast(20)),
            ),
        )

        val keyId = runBlocking {
            db.sshKeyDao().insert(SshKeyEntity(name = "issue965-key", privateKeyPath = keyFile.absolutePath))
        }
        val hostId = runBlocking {
            db.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
                    // A host that won't connect — the load-bearing assertion is the
                    // Main-thread cache read during bind(), before any network.
                    hostname = "10.255.255.1",
                    port = 2222,
                    username = "nobody",
                    keyId = keyId,
                ),
            )
        }
        val host = runBlocking { db.hostDao().getById(hostId)!! }
        runBlocking {
            db.projectRootDao().insert(ProjectRootEntity(hostId = hostId, label = "git", path = gitRoot))
        }

        // Build the VM (off-Main construction; lifecycle detached for determinism).
        val vm = FolderListViewModel(
            gateway = SshFolderListGateway(),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(targetContext),
            treeRemoteSource = null,
            treeClientCache = cache,
            attachLifecycle = false,
        ).also {
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-0", it)
        }

        // Install the PRODUCTION Main-thread StrictMode policy (what App.onCreate
        // installs on a debuggable build — the androidTest APK is debuggable) and
        // drive the REAL bind() on Main, exactly as the screen's LaunchedEffect
        // does. Then restore the original policy.
        val original = arrayOfNulls<StrictMode.ThreadPolicy>(1)
        try {
            instrumentation.runOnMainSync {
                original[0] = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictModeInstaller.buildThreadPolicy { it.run() })
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
            // Let the (synchronous-executor) violation listener land any Main-thread
            // disk read that bind() performed.
            Thread.sleep(400)

            val diskReads = diagnostics
                .eventsNamed(StrictModeInstaller.DIAGNOSTIC_EVENT)
                .filter { it.fields["kind"] == "disk_read" }

            // LOAD-BEARING (G6): the cold bind() at 71/12 scale must perform NO
            // Main-thread disk read. With `attachLifecycle = false` and an
            // in-memory Room DB, the ONLY Main-thread disk read bind() did on base
            // was the synchronous tree-cache `read` + 71-project JSON parse inside
            // `hydrateFromClientCache` — the #965 ANR cause. RED on base (that read
            // trips `disk_read`); GREEN with the off-Main read (zero violations).
            assertTrue(
                "the cold bind() at 71 projects / 12 sessions must NOT read+parse " +
                    "the tree cache on the Main thread (the #965 ANR cause). " +
                    "Recorded Main-thread disk_read violations: " +
                    diskReads.map { it.fields["topFrame"] to it.fields["detail"] },
                diskReads.isEmpty(),
            )
        } finally {
            instrumentation.runOnMainSync {
                original[0]?.let { StrictMode.setThreadPolicy(it) }
            }
        }
    }

    private companion object {
        const val HOST_NAME: String = "issue965-hetzner"
    }
}
