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
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.SessionLifecycleSignals
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #883 — "Stop session on a tmux WINDOW row kills the WHOLE session".
 *
 * The folder/session tree models each tmux WINDOW as its own `[wN]` row, but
 * "Stop session" used to always run `kill-session`, taking the entire session
 * (every window) down. This drives the **production** view models against the
 * Docker `agents` host (port 2222) end-to-end to prove the window-aware fix:
 *
 *  1. Create a real tmux session with TWO windows on the remote.
 *  2. Bind [FolderListViewModel] (foregrounded) → the session lists with both
 *     windows on the tree.
 *  3. Attach a real [TmuxSessionViewModel] to that session and confirm a Stop
 *     on window 0 ([TmuxSessionViewModel.killCurrentSession] with
 *     `windowIndex = 0`). The two view models share one
 *     [SessionLifecycleSignals] — the same singleton Hilt injects in
 *     production.
 *  4. ONLY window 0 must be gone; window 1 AND the underlying tmux session must
 *     survive (verified directly with `tmux list-windows` over a FRESH
 *     SSH-exec). The tree must drop the killed window row while keeping the
 *     sibling window row + the session.
 *
 * Pre-fix the in-session Stop ran `kill-session`, so BOTH windows + the whole
 * session would be destroyed — `list-windows` would fail (session gone). This
 * test FAILS on base for exactly that reason (G10 reproduce-first end-to-end).
 *
 * Docker service: `agents` on host port `2222` (the deterministic default
 * fixture; no extra compose service required).
 */
@RunWith(AndroidJUnit4::class)
class FolderListKillWindowDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue883-killwindow-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        waitForSshFixtureReady(sshKey)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        viewModelStore.clear()
        factoryScope.cancel()
        if (createdSessions.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
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
    }

    @Test
    fun stopOnWindowRowKillsOnlyThatWindowAndKeepsTheSession(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folder = "/tmp/issue883-killwindow-$suffix"
        val multi = "issue883-multi-$suffix"

        // 1. Seed a real tmux session with TWO windows on the remote.
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                session.exec("mkdir -p $folder")
                session.exec("tmux new-session -d -s $multi -c $folder")
                // A second window — window index 1.
                session.exec("tmux new-window -t $multi -c $folder")
                createdSessions += multi

                val windows = session.exec(
                    "tmux list-windows -t $multi -F '#{window_index}'",
                ).stdout.trim()
                assertTrue(
                    "fixture must have created a 2-window session; list-windows=$windows",
                    windows.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        == setOf("0", "1"),
                )
            }
        }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue883-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue883-host",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        val signals = SessionLifecycleSignals()

        // 2. Folder tree lists the multi-window session with both window rows.
        val folderVm = FolderListViewModel(
            gateway = SshFolderListGateway(),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
            sessionLifecycleSignals = signals,
            attachLifecycle = false,
        ).also { viewModelStore.put("FolderListViewModel", it) }
        folderVm.setProcessStartedForTest(true)
        folderVm.bind(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = keyFile.absolutePath,
            passphrase = null,
        )
        awaitWindowCount(folderVm, multi, expected = 2)

        // 3. Attach a real session view model and confirm a Stop on window 0.
        val tmuxVm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
            hostDao = db.hostDao(),
            folderListGateway = SshFolderListGateway(),
            sessionLifecycleSignals = signals,
        )
        try {
            tmuxVm.connect(
                hostId = host.id,
                hostName = host.name,
                host = host.hostname,
                port = host.port,
                user = host.username,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                sessionName = multi,
            )
            waitForTmuxConnected(tmuxVm)
            // Wait for the live attach to enumerate BOTH windows' panes so the
            // VM can resolve window 0's stable id.
            awaitVmWindowIndices(tmuxVm, expected = setOf(0, 1))

            // The Stop the user confirms while viewing window 0 (`[w0]`).
            tmuxVm.killCurrentSession(windowIndex = 0)

            // 4a. Authoritative remote check: ONLY window 0 is gone; window 1
            //     AND the session survive.
            val (sessionAlive, remainingWindows) = withTimeout(20_000) {
                awaitRemoteWindows(multi) { remaining ->
                    remaining == setOf("1")
                }
            }
            assertTrue(
                "the tmux session must SURVIVE a window-row Stop (kill-window, " +
                    "not kill-session)",
                sessionAlive,
            )
            assertEquals(
                "ONLY window 0 must be killed; window 1 must survive on the remote",
                setOf("1"),
                remainingWindows,
            )

            // 4b. The tree drops the killed window row but keeps the sibling
            //     window row + the session.
            awaitWindowCount(folderVm, multi, expected = 1)
            assertTrue(
                "the multi-window session row must remain on the tree; " +
                    "state=${folderVm.state.value}",
                hasSession(folderVm, multi),
            )
            assertEquals(
                "the sibling window row (index 1) must survive on the tree",
                listOf(1),
                windowIndices(folderVm, multi),
            )
        } finally {
            tmuxVm.clearForTest()
            folderVm.stopPolling()
        }
    }

    private fun hasSession(vm: FolderListViewModel, sessionName: String): Boolean {
        val state = vm.state.value as? FolderListUiState.Ready ?: return false
        return state.flatSessions.any { it.sessionName == sessionName }
    }

    private fun windowIndices(vm: FolderListViewModel, sessionName: String): List<Int?> {
        val state = vm.state.value as? FolderListUiState.Ready ?: return emptyList()
        return state.flatSessions
            .firstOrNull { it.sessionName == sessionName }
            ?.windows
            ?.map { it.index }
            ?: emptyList()
    }

    private suspend fun awaitWindowCount(
        vm: FolderListViewModel,
        sessionName: String,
        expected: Int,
    ) {
        withTimeout(25_000L) {
            while (windowIndices(vm, sessionName).size != expected) {
                delay(200L)
            }
        }
    }

    private suspend fun awaitVmWindowIndices(
        vm: TmuxSessionViewModel,
        expected: Set<Int>,
    ) {
        withTimeout(30_000L) {
            while (vm.panes.value.mapNotNull { it.windowIndex }.toSet() != expected) {
                delay(150L)
            }
        }
    }

    /**
     * Poll the remote over fresh SSH-exec connections until [predicate] holds
     * for the live `tmux list-windows` index set, or time out. Returns
     * `(sessionAlive, windowIndexSet)` for the final observation.
     */
    private suspend fun awaitRemoteWindows(
        sessionName: String,
        predicate: (Set<String>) -> Boolean,
    ): Pair<Boolean, Set<String>> {
        var lastAlive = true
        var lastWindows = emptySet<String>()
        while (true) {
            val (alive, windows) = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                val has = session.exec("tmux has-session -t $sessionName 2>/dev/null").exitCode == 0
                val list = session.exec(
                    "tmux list-windows -t $sessionName -F '#{window_index}' 2>/dev/null",
                ).stdout
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                has to list
            }
            lastAlive = alive
            lastWindows = windows
            if (alive && predicate(windows)) return alive to windows
            delay(300L)
        }
        @Suppress("UNREACHABLE_CODE")
        return lastAlive to lastWindows
    }

    private suspend fun waitForTmuxConnected(vm: TmuxSessionViewModel) {
        withTimeout(30_000L) {
            while (vm.connectionStatus.value !is TmuxSessionViewModel.ConnectionStatus.Connected) {
                delay(100L)
            }
        }
    }
}
