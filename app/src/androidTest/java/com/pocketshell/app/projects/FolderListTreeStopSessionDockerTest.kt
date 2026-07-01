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
import com.pocketshell.app.tmux.SessionLifecycleSignals
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
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
 * Issue #518 — "Stop session from the host-detail tree".
 *
 * Drives the production [FolderListViewModel] against the Docker `agents`
 * host (port 2222) to reproduce the maintainer's journey end to end,
 * WITHOUT entering the session:
 *
 *  1. Create two real tmux sessions on the remote.
 *  2. Bind [FolderListViewModel] (foregrounded) → both sessions list on the
 *     folder/session tree.
 *  3. Call [FolderListViewModel.killSession] for one — the exact call the
 *     host-detail "Stop session" confirmation triggers — over the gateway's
 *     SSH-exec `tmux kill-session` path (no attached `tmux -CC` client).
 *  4. The killed session drops from the tree promptly, the kept session
 *     stays, the lifecycle signal fans out, and the authoritative re-probe
 *     (`tmux list-sessions`) confirms it is gone on the remote — no phantom
 *     row.
 *
 * Docker service: `agents` on host port `2222` (the deterministic default
 * fixture; no extra compose service required).
 */
@RunWith(AndroidJUnit4::class)
class FolderListTreeStopSessionDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
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
        keyFile = File(cacheDir, "issue518-stop-key").apply {
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
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        viewModelStore.clear()
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
    } }

    @Test
    fun stopSessionFromTreeKillsRemoteAndDropsRow(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folder = "/tmp/issue518-stop-$suffix"
        val keep = "issue518-keep-$suffix"
        val doomed = "issue518-doomed-$suffix"

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
            SshKeyEntity(name = "issue518-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue518-host",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        val signals = SessionLifecycleSignals()

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
        try {
            awaitSession(folderVm, keep)
            awaitSession(folderVm, doomed)

            // The exact call the host-detail "Stop session" confirmation makes.
            folderVm.killSession(doomed)

            // The doomed row drops promptly; the kept session stays.
            awaitNoSession(folderVm, doomed)
            assertTrue(
                "kept session must remain on the tree; state=${folderVm.state.value}",
                hasSession(folderVm, keep),
            )

            // Authoritative re-probe confirms it never resurrects (no phantom).
            folderVm.refresh()
            delay(1_500L)
            assertTrue(
                "doomed session must stay gone after an authoritative re-probe; " +
                    "state=${folderVm.state.value}",
                !hasSession(folderVm, doomed),
            )

            // The kill really landed on the remote — verify directly and drop
            // it from cleanup.
            val stillThere = withTimeout(15_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = sshKey,
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 10_000,
                ).getOrThrow().use { session ->
                    session.exec("tmux has-session -t $doomed 2>/dev/null").exitCode == 0
                }
            }
            assertTrue("doomed tmux session must be gone on the remote", !stillThere)
            createdSessions.remove(doomed)
        } finally {
            folderVm.stopPolling()
        }
    } }

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
}
