package com.pocketshell.app.projects

import android.os.Looper
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
 * Issue #867 (stale-while-revalidate) — connected (emulator + Docker) proof that
 * a cold connect paints the LAST-KNOWN tree INSTANTLY from the per-host CLIENT
 * cache, with NO empty rebuild flash ("No folders yet / 0 projects", everything
 * in "Other folders", a spinner), and that the silent reconcile then keeps it
 * authoritative against the REAL live tmux/SSH path.
 *
 * ## What it proves end-to-end (the real path, not a proxy)
 *
 * 1. A previous app session's settled tree lives in [TreeClientCache] (keyed by
 *    host). A FRESH [FolderListViewModel] (the cold-start / app-relaunch case)
 *    binds the same host: the FIRST state the screen sees is Ready with the
 *    cached session in its slot — captured SYNCHRONOUSLY after `bind`, before any
 *    SSH round-trip — so the maintainer's empty rebuild flash never paints.
 * 2. The silent reconcile against the LIVE Docker `agents` fixture then converges
 *    the tree on the authoritative session set (the cached session is confirmed),
 *    proving the cache is ADVISORY, not the source of truth.
 *
 * ## Issue #1823 — `bind` + the first-frame capture MUST run on the Main thread
 *
 * [FolderListViewModel] is Main-confined: its `viewModelScope` is
 * `Dispatchers.Main.immediate`, `applyReadyProjection` writes `_state` and is
 * documented "Must run on Main", and the ONE production caller is
 * `FolderListScreen`'s `LaunchedEffect { viewModel.bind(...) }` — the Main thread.
 * The instant render is only an invariant under that confinement, because the
 * synchronous cold seed's `++emitGeneration` … `buildProjection` … staleness-check
 * sequence is atomic only when nothing else can run on Main meanwhile.
 *
 * This test used to call `bind` from the INSTRUMENTATION thread, so the view
 * model's own Main-dispatched coroutines ran CONCURRENTLY with that sequence. When
 * the `init` block's `forwardingController.flowOfHostSnapshots()` collector landed
 * its `emitReady()` inside the ~1 ms window, it bumped `emitGeneration` first, the
 * synchronous seed then saw its own generation as stale and DISCARDED its
 * projection, and `_state` stayed `Loading` — a first frame the real screen can
 * never see. Measured over 120 consecutive cold binds: 28 `Loading` first frames
 * off Main (23%) versus 0 on Main; the client-cache `peek` HIT on every one of
 * them, so the failure was never a cold cache.
 *
 * So the bind + capture below runs inside `runOnMainSync`, exactly as the sibling
 * [FolderListScaleAnrStrictModeDockerTest] does and exactly as the screen does.
 * The assertion is unchanged and NOT weakened: it is still the FIRST state after
 * `bind`, with no wait, no retry and no widened bound — just observed on the
 * thread the screen actually composes on.
 *
 * Docker service: `agents` on host port `2222` (the standard journey fixture the
 * `emulator-journey` CI workflow already starts), so this runs on CI with no new
 * fixture. A pool lane overrides the port via `agentsPort`.
 */
@RunWith(AndroidJUnit4::class)
class FolderListClientCacheInstantRenderDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private lateinit var cache: TreeClientCache
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0
    private val createdSessions = mutableListOf<String>()
    private val fixturePort: Int get() = DEFAULT_PORT

    @Before
    fun setUp(): Unit { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keyFile = File(context.cacheDir, "issue867-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = TreeClientCache(context)
        waitForSshFixtureReady(sshKey, port = fixturePort)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        viewModelStore.clear()
        if (createdSessions.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = fixturePort,
                        user = DEFAULT_USER,
                        key = sshKey,
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 10_000,
                    ).getOrNull()?.use { session ->
                        for (name in createdSessions) {
                            runCatching {
                                session.exec("tmux kill-session -t $name 2>/dev/null || true")
                            }
                        }
                    }
                }
            }
        }
        runCatching { db.close() }
        runCatching { keyFile.delete() }
        runCatching { cache.write(HOST_NAME, TreeClientCache.CachedTree(nodes = emptyList())) }
    } }

    @Test
    fun coldConnectRendersCachedTreeInstantly_thenReconcilesAgainstLiveHost(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folder = "/tmp/issue867-$suffix"
        val sessionName = "issue867-$suffix"

        // Seed a real tmux session on the LIVE host so the silent reconcile has
        // an authoritative tree to converge on.
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = fixturePort,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                session.exec("mkdir -p $folder")
                session.exec("tmux new-session -d -s $sessionName -c $folder")
                createdSessions += sessionName
            }
        }

        // The PREVIOUS app session left this settled tree in the client cache —
        // the host name keys the entry. This is what must paint instantly.
        //
        // Issue #867 REOPEN: cache the FULL settled shape — the session node PLUS
        // the watched-root overlay + the resolved match path + the scanned project
        // folders — so the cold-start instant render reproduces the GROUPED tree
        // (the session bucketed UNDER the "/tmp" watched root), NOT a flat list
        // dumped into "Other folders" with "0 projects" (the v0.4.14 symptom).
        val watchRoot = FolderListViewModel.canonicalisePath("/tmp")
        val canonicalFolder = FolderListViewModel.canonicalisePath(folder)
        cache.write(
            HOST_NAME,
            TreeClientCache.CachedTree(
                nodes = listOf(
                    TreeRemoteSource.TreeNode(
                        session = sessionName,
                        order = 0,
                        folderPath = canonicalFolder,
                        collapsed = false,
                    ),
                ),
                watchedFolders = listOf(
                    ProjectRootEntity(hostId = 0L, label = "tmp", path = watchRoot),
                ),
                resolvedWatchedRootPaths = mapOf(watchRoot to watchRoot),
                scannedProjectFoldersByRoot = mapOf(watchRoot to listOf(canonicalFolder)),
            ),
        )
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue867-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = HOST_NAME,
                hostname = DEFAULT_HOST,
                port = fixturePort,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!
        // Persist the SAME watched root to Room so the authoritative overlay
        // matches the cached one (the Room Flow overwrites the cached watched
        // folders, advisory, the moment it emits).
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "tmp", path = watchRoot),
        )

        // A FRESH view model = a cold app start. Bind the cached host.
        val vm = newViewModel()
        vm.setProcessStartedForTest(true)

        // Issue #1823: drive `bind` and capture the FIRST state on the MAIN thread,
        // exactly as `FolderListScreen`'s `LaunchedEffect` does in production (and
        // as the sibling FolderListScaleAnrStrictModeDockerTest already does). Off
        // Main the view model's own Main-dispatched coroutines interleave with
        // bind's synchronous cold seed and can discard it as stale — a first frame
        // the real screen can never observe. See this class's KDoc.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val capturedOnMain = arrayOfNulls<FolderListUiState>(1)
        val captureThreadWasMain = booleanArrayOf(false)
        instrumentation.runOnMainSync {
            captureThreadWasMain[0] = Looper.myLooper() === Looper.getMainLooper()
            vm.bind(
                hostId = host.id,
                hostName = host.name,
                hostname = host.hostname,
                port = host.port,
                username = host.username,
                keyPath = keyFile.absolutePath,
                passphrase = null,
            )
            // LOAD-BEARING: the FIRST state after bind (before any SSH round-trip)
            // is already Ready with the cached session — the instant render. The
            // pre-#867 behaviour would be Loading here (the empty rebuild flash).
            // Captured INSIDE the same Main runnable as `bind`, so this really is
            // the first frame the screen would compose, not a later one.
            capturedOnMain[0] = vm.state.value
        }
        // HARD guard (never a skip): if this ever stops running on Main the
        // instant-render assertion below is no longer the invariant it claims to
        // test, so fail loudly instead of measuring a race (issue #1823).
        assertTrue(
            "the instant-render capture must run on the Main thread (production " +
                "binds from FolderListScreen's LaunchedEffect) — issue #1823",
            captureThreadWasMain[0],
        )
        val firstState = capturedOnMain[0]!!
        assertTrue(
            "cold connect must render the cached tree INSTANTLY (Ready, not the " +
                "Loading rebuild flash) — state=$firstState",
            firstState is FolderListUiState.Ready,
        )
        assertFalse(
            "the instant render must NOT be the empty 'No folders yet' tree",
            (firstState as FolderListUiState.Ready).flatSessions.isEmpty(),
        )
        assertEquals(
            "the cached session paints in its slot instantly",
            listOf(sessionName),
            firstState.flatSessions.map { it.sessionName },
        )
        // Issue #867 REOPEN load-bearing assertion (the v0.4.14 symptom): the
        // instant render shows the GROUPED tree — the cached session bucketed
        // UNDER the "/tmp" watched root with its project subfolder visible — NOT a
        // flat list dumped into "Other folders" with the watched root at "0
        // projects". RED before the structural cache (treeRoots had no folders).
        val tmpRoot = firstState.treeRoots.firstOrNull { it.path == watchRoot }
        assertTrue(
            "the cached '/tmp' watched root must paint instantly WITH its project " +
                "subfolder (not '0 projects') — treeRoots=" +
                firstState.treeRoots.map { r -> r.path to r.folders.map { it.path } },
            tmpRoot != null && tmpRoot.folders.any { it.path == canonicalFolder },
        )
        assertTrue(
            "the cached session must render UNDER its watched root, not in 'Other " +
                "folders' — tmpRoot folders=${tmpRoot?.folders?.map { it.path to it.sessions.map { s -> s.sessionName } }}",
            tmpRoot!!.folders.any { row ->
                row.path == canonicalFolder && row.sessions.any { it.sessionName == sessionName }
            },
        )

        // The silent reconcile then converges on the authoritative live tree —
        // the cached session is CONFIRMED by the real probe (advisory cache).
        withTimeout(30_000L) {
            while (true) {
                val s = vm.state.value
                if (s is FolderListUiState.Ready &&
                    s.flatSessions.any { it.sessionName == sessionName } &&
                    !s.isRefreshing
                ) {
                    break
                }
                delay(250L)
            }
        }
        val finalState = vm.state.value as FolderListUiState.Ready
        assertTrue(
            "after the silent reconcile the live session is still present " +
                "(authoritative confirm) — state=$finalState",
            finalState.flatSessions.any { it.sessionName == sessionName },
        )
    } }

    private fun newViewModel(): FolderListViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FolderListViewModel(
            gateway = SshFolderListGateway(),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(context),
            // No daemon registry: this exercises the CLIENT cache path against the
            // live host (the durable-daemon path is covered separately).
            treeRemoteSource = null,
            treeClientCache = cache,
            attachLifecycle = false,
        ).also { vm ->
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", vm)
        }
    }

    private companion object {
        const val HOST_NAME: String = "issue867-host"
    }
}
