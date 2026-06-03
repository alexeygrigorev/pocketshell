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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #464 — "Killed session still shows in the folder tree".
 *
 * Drives the **production** view models against the Docker `agents` host
 * (port 2222) to reproduce the maintainer's journey end to end:
 *
 *  1. Create a real tmux session on the remote.
 *  2. Bind [FolderListViewModel] (foregrounded) → the session lists on the
 *     folder/session tree.
 *  3. Attach a real [TmuxSessionViewModel] to that session and confirm a
 *     Kill session ([TmuxSessionViewModel.killCurrentSession]). The two
 *     view models share one [SessionLifecycleSignals] — the same singleton
 *     Hilt injects in production.
 *  4. The folder tree must drop the killed session promptly without a
 *     manual refresh, and the authoritative re-probe (`tmux list-sessions`)
 *     must confirm it is gone — no phantom row.
 *
 * Pre-fix the folder tree kept the dead row until something else happened
 * to re-probe. Post-fix the confirmed kill broadcasts a lifecycle signal
 * the tree consumes (optimistic drop + authoritative reconcile).
 *
 * Docker service: `agents` on host port `2222` (the deterministic default
 * fixture; no extra compose service required).
 */
@RunWith(AndroidJUnit4::class)
class FolderListKillSessionDockerTest {

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
        keyFile = File(cacheDir, "issue464-kill-key").apply {
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
    fun killSessionDropsRowFromFolderTreePromptly(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folder = "/tmp/issue464-kill-$suffix"
        val keep = "issue464-keep-$suffix"
        val doomed = "issue464-doomed-$suffix"

        // 1. Seed two real tmux sessions on the remote so we can prove the
        //    kill drops only the targeted row.
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
                session.exec("tmux new-session -d -s $keep -c $folder")
                session.exec("tmux new-session -d -s $doomed -c $folder")
                createdSessions += keep
                createdSessions += doomed
            }
        }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue464-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue464-host",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        val signals = SessionLifecycleSignals()

        // 2. Folder tree lists both sessions.
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
        awaitSession(folderVm, keep)
        awaitSession(folderVm, doomed)

        // 3. Attach a real session view model to the doomed session and
        //    confirm a Kill session. Both view models share the singleton
        //    Hilt wires in production.
        val tmuxVm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
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
                sessionName = doomed,
            )
            waitForTmuxConnected(tmuxVm)

            // The kill the user confirms from the session dropdown.
            tmuxVm.killCurrentSession()

            // 4. The folder tree drops the doomed row promptly, the kept
            //    session stays, and the authoritative re-probe agrees.
            awaitNoSession(folderVm, doomed)
            assertTrue(
                "kept session must remain on the tree; state=${folderVm.state.value}",
                hasSession(folderVm, keep),
            )

            // Force a fresh authoritative probe and confirm the doomed
            // session never resurrects (no phantom row).
            folderVm.refresh()
            delay(1_500L)
            assertTrue(
                "doomed session must stay gone after an authoritative re-probe; " +
                    "state=${folderVm.state.value}",
                !hasSession(folderVm, doomed),
            )
            // The kill really landed on the remote — drop it from cleanup.
            createdSessions.remove(doomed)
        } finally {
            tmuxVm.clearForTest()
            folderVm.stopPolling()
        }
    }

    private fun hasSession(vm: FolderListViewModel, sessionName: String): Boolean {
        val state = vm.state.value as? FolderListUiState.Ready ?: return false
        return state.flatSessions.any { it.sessionName == sessionName }
    }

    private suspend fun awaitSession(vm: FolderListViewModel, sessionName: String) {
        withTimeout(20_000L) {
            while (!hasSession(vm, sessionName)) {
                delay(200L)
            }
        }
    }

    private suspend fun awaitNoSession(vm: FolderListViewModel, sessionName: String) {
        withTimeout(15_000L) {
            while (hasSession(vm, sessionName)) {
                delay(200L)
            }
        }
    }

    private suspend fun waitForTmuxConnected(vm: TmuxSessionViewModel) {
        withTimeout(30_000L) {
            while (vm.connectionStatus.value !is TmuxSessionViewModel.ConnectionStatus.Connected) {
                delay(100L)
            }
        }
    }
}
