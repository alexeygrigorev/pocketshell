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
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #783: a window closed ON THE HOST while the user is NOT on the tree
 * screen (e.g. they navigated into the session screen) must still prune the
 * `[wN]` node from the maintained project tree — WITHOUT a manual pull-to-refresh
 * — the instant the `%window-close` event arrives on the already-open `-CC`
 * channel.
 *
 * On base this FAILED: `FolderListScreen`'s dispose calls `stopPolling()`, which
 * cancelled the `%window-close` subscription, so the event (hot SharedFlow,
 * replay=0) landed on a dead collector and was dropped; the stale window lingered
 * up to the ~15-min staleness gate. The fix ties the subscription to the
 * bound-host warm-lease lifetime, NOT screen composition, so the prune survives
 * `stopPolling`.
 *
 * This connected test drives the PRODUCTION view model + gateway + a REAL
 * `tmux -CC` control client against the Docker `agents` host (port 2222) — the
 * SAME warm `-CC` client throughout, so NO second SSH/`-CC` connection is opened
 * for the prune (D21):
 *
 *  1. Seed a real session with TWO windows on the host.
 *  2. Bring up a real `-CC` client, register it in [ActiveTmuxClients] (what
 *     `TmuxSessionViewModel` does for a live session), and bind the production
 *     [FolderListViewModel] so the tree shows both windows.
 *  3. `stopPolling()` — the exact lifecycle event when the user leaves the tree
 *     screen for the session screen (on base this killed the subscription).
 *  4. Close ONE window on the host via `tmux kill-window` from a SEPARATE plain
 *     SSH connection (NOT routed through the app). tmux emits `%window-close`.
 *  5. Assert the closed window's node disappears from the tree within seconds —
 *     NO manual refresh, far inside the 15-min staleness gate — while its
 *     sibling window survives.
 *
 * Docker service: `agents` on host port `2222` (the deterministic default
 * fixture in the CI workflow; no extra compose service required).
 */
@RunWith(AndroidJUnit4::class)
class FolderListWindowCloseAfterStopPollingDockerTest {
    private lateinit var trustedHostKeySha256: String

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private val tmuxClientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ccSession: SshSession? = null
    private var ccClient: TmuxClient? = null
    private val createdSessions = mutableListOf<String>()

    @Before
    fun setUp(): Unit { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue783-window-close-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        trustedHostKeySha256 = waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        viewModelStore.clear()
        runCatching { ccClient?.close() }
        runCatching { ccSession?.close() }
        runCatching { tmuxClientScope.cancel() }
        if (createdSessions.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = sshKey,
                        knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                        timeoutMs = 10_000,
                    ).getOrNull()?.use { session ->
                        for (name in createdSessions) {
                            runCatching { session.exec("tmux kill-session -t $name 2>/dev/null || true") }
                        }
                    }
                }
            }
        }
        runCatching { db.close() }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun windowClosedOnHostWhileOffTreeScreenIsPrunedWithoutRefresh(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folder = "/tmp/issue783-$suffix"
        val sessionName = "issue783-$suffix"

        // 0. Seed a real session with TWO windows on the host.
        stage("seed-connect") { withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 10_000,
            ).getOrThrow().use { s ->
                s.exec("mkdir -p $folder")
                s.exec("tmux new-session -d -s $sessionName -c $folder")
                s.exec("tmux new-window -t $sessionName -c $folder")
                createdSessions += sessionName
            }
        } }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue783-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue783-host",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
                trustedHostKeySha256 = trustedHostKeySha256,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        // 1. Bring up a REAL `tmux -CC` control client over its own SSH session
        //    (the same wiring TmuxSessionViewModel uses) and register it. This is
        //    the SOLE `-CC` connection — the prune below reuses it (D21).
        val registry = ActiveTmuxClients()
        val session = stage("cc-session-connect") { withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 15_000,
            ).getOrThrow()
        } }
        ccSession = session
        val client = TmuxClientFactory(tmuxClientScope).create(
            session = session,
            sessionName = sessionName,
        )
        ccClient = client
        stage("cc-client-connect") { withTimeout(20_000) { client.connect() } }
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = keyFile.absolutePath,
            client = client,
        )

        // 2. Bind the production view model with the live client + registry.
        val vm = FolderListViewModel(
            gateway = SshFolderListGateway(
                reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
                activeTmuxClients = registry,
                sessionListParser = HostTmuxSessionListParser(),
            ),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
            activeTmuxClients = registry,
            attachLifecycle = false,
        ).also { viewModelStore.put("FolderListViewModel", it) }
        vm.setProcessStartedForTest(true)
        // Issue #1829: FolderListViewModel is Main-confined and now ENFORCES it.
        // Drive the REAL bind() on Main, exactly as FolderListScreen's
        // LaunchedEffect does — off Main, bind()'s synchronous cold seed races
        // the view model's own Main-dispatched emitReady and is dropped as
        // stale (#1823: 23-25% of binds), leaving the #867/#1109 Loading flash.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
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

        // Cold-open reconcile settles: the session shows both windows.
        //
        // Issue #1810: this is the bound the wedge expired. It is the FIRST
        // consumer of the live `-CC` client, so it is what a mis-correlated
        // attach block silently broke — the enumeration resolved with an EMPTY,
        // non-error `list-sessions`, the tree went `Ready` with ZERO sessions,
        // and nothing else ever happened on the wire (the "20 seconds of
        // complete silence"). The failure message carries the observed state so
        // "wedged in setup" is never again an unattributed `DefaultExecutor`
        // frame.
        stage("await-window-count-2") {
            awaitWindowCount(vm, sessionName, expected = 2)
        }
        val windowIdsBefore = windowIds(vm, sessionName)
        assertEquals(
            "the seeded session must have two windows before the close; ids=$windowIdsBefore",
            2,
            windowIdsBefore.size,
        )
        val targetWindowId = windowIdsBefore.last()
        assertNotNull("the target window must carry a tmux window id", targetWindowId)

        // 3. The user navigates into the session screen → FolderListScreen
        //    disposes → stopPolling(). On BASE this cancelled the %window-close
        //    subscription; with the fix it survives (bound-host lifetime).
        vm.stopPolling()
        delay(300L)

        // 4. Close ONE window ON THE HOST from a SEPARATE plain SSH connection
        //    (NOT the app). tmux emits %window-close on the live `-CC` client.
        stage("kill-window-connect") { withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 10_000,
            ).getOrThrow().use { s ->
                s.exec("tmux kill-window -t $targetWindowId")
            }
        } }

        // 5. The closed window's node must disappear from the tree within
        //    seconds — NO manual refresh, NO resume — while its sibling survives.
        val startedAt = System.currentTimeMillis()
        stage("await-window-gone") {
            awaitWindowGone(vm, sessionName, targetWindowId!!, timeoutMs = 25_000L)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        assertTrue(
            "the host-closed window must be pruned within seconds (event-driven), not the " +
                "15-min staleness gate; took ${elapsedMs}ms",
            elapsedMs < 25_000L,
        )
        val windowIdsAfter = windowIds(vm, sessionName)
        assertTrue(
            "the closed window must be gone; remaining ids=$windowIdsAfter",
            targetWindowId !in windowIdsAfter,
        )
        assertEquals(
            "exactly one (sibling) window must remain; ids=$windowIdsAfter",
            1,
            windowIdsAfter.size,
        )
    } }

    /**
     * Issue #1810: run one bounded step under a LABEL.
     *
     * This test has five 20 s bounds. When one expired, JUnit reported a bare
     * `TimeoutCancellationException: Timed out waiting for 20000 ms` on a
     * `DefaultExecutor` frame that named none of them, so "it wedges in setup"
     * could not be attributed without re-instrumenting. Re-throwing with the
     * stage name (and, for the tree waits, the observed UI state) makes the
     * report self-describing.
     *
     * This changes NO bound and weakens NO assertion — it only renames the
     * failure.
     */
    private suspend fun <T> stage(name: String, block: suspend () -> T): T = try {
        block()
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("stage `$name` timed out; ${e.message}", e)
    }

    private fun windowIds(vm: FolderListViewModel, sessionName: String): List<String?> {
        val state = vm.state.value as? FolderListUiState.Ready ?: return emptyList()
        return state.flatSessions
            .firstOrNull { it.sessionName == sessionName }
            ?.windows
            ?.map { it.windowId }
            ?: emptyList()
    }

    private suspend fun awaitWindowCount(
        vm: FolderListViewModel,
        sessionName: String,
        expected: Int,
        timeoutMs: Long = 20_000L,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (windowIds(vm, sessionName).size != expected) {
                    delay(200L)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Issue #1810: report WHAT the tree actually held. An empty
            // `Ready` here means the enumeration answered (wrongly) with zero
            // sessions rather than that the host never came up.
            throw AssertionError(
                "session `$sessionName` never showed $expected windows within ${timeoutMs}ms; " +
                    "observed ${describeState(vm)}",
                e,
            )
        }
    }

    private fun describeState(vm: FolderListViewModel): String =
        when (val state = vm.state.value) {
            is FolderListUiState.Ready ->
                "Ready(sessions=[" +
                    state.flatSessions.joinToString { "${it.sessionName}:${it.windows.size}w" } +
                    "] refreshing=${state.isRefreshing})"
            else -> state.toString().take(240)
        }

    private suspend fun awaitWindowGone(
        vm: FolderListViewModel,
        sessionName: String,
        windowId: String,
        timeoutMs: Long = 20_000L,
    ) {
        withTimeout(timeoutMs) {
            while (windowId in windowIds(vm, sessionName)) {
                delay(200L)
            }
        }
    }
}
