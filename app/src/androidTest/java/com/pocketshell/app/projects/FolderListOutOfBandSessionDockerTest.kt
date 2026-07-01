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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #706: out-of-band session appears in the host-detail picker within
 * seconds (a real regression from EPIC #679).
 *
 * The #679 maintained [HostTreeModel] reconcile fires ONLY on cold-open, a
 * 15-min staleness gate, a pull-to-refresh, or an app-initiated create/kill —
 * it did NOT subscribe to the live `-CC` `%sessions-changed` event. So a session
 * created OUT-OF-BAND (another terminal, an agent spawning one) stayed invisible
 * in the picker for up to 15 minutes.
 *
 * The fix subscribes the held tree ([FolderListViewModel]) to the bound host's
 * live `tmux -CC` client's `%sessions-changed`
 * ([com.pocketshell.core.tmux.protocol.ControlEvent.SessionsChanged]) event as a
 * DEBOUNCED, foreground-only reconcile trigger.
 *
 * This connected test drives the PRODUCTION view model + gateway + a REAL
 * `tmux -CC` control client against the Docker `agents` host (port 2222):
 *
 *  1. Bring up a real `-CC` client and register it in [ActiveTmuxClients]
 *     (exactly what `TmuxSessionViewModel` does for a live session).
 *  2. Bind the production [FolderListViewModel] (app foregrounded) so it shows
 *     the host's held tree AND subscribes to the live client's
 *     `%sessions-changed`.
 *  3. After the cold-open reconcile settles, create a session OUT-OF-BAND from a
 *     SEPARATE plain SSH connection (`tmux new-session` — NOT routed through the
 *     app's `createSession`).
 *  4. Assert the new session appears in the picker within SECONDS — driven
 *     purely by the `%sessions-changed`-triggered reconcile, with NO manual
 *     refresh, no resume, and well inside the 15-min staleness gate.
 *
 * D21: no poll/Timer — the trigger rides the already-open `-CC` event bus and
 * the reconcile is foreground-gated.
 *
 * Docker service: `agents` on host port `2222` (the deterministic default
 * fixture in the CI workflow; no extra compose service required).
 */
@RunWith(AndroidJUnit4::class)
class FolderListOutOfBandSessionDockerTest {

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
        keyFile = File(cacheDir, "issue706-out-of-band-key").apply {
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
                        knownHosts = KnownHostsPolicy.AcceptAll,
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
    fun outOfBandSessionAppearsInPickerWithinSeconds(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val anchorFolder = "/tmp/issue706-anchor-$suffix"
        val anchorSession = "issue706-anchor-$suffix"
        val outOfBandFolder = "/tmp/issue706-oob-$suffix"
        val outOfBandSession = "issue706-oob-$suffix"

        // 0. Seed one anchor session the app is "attached" to, so the host has
        //    a live `-CC` client and a non-empty cold-open tree.
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { s ->
                s.exec("mkdir -p $anchorFolder")
                s.exec("tmux new-session -d -s $anchorSession -c $anchorFolder")
                createdSessions += anchorSession
            }
        }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue706-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue706-host",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        // 1. Bring up a REAL `tmux -CC` control client over its own SSH session
        //    (the same wiring TmuxSessionViewModel uses) and register it.
        val registry = ActiveTmuxClients()
        val session = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        ccSession = session
        val client = TmuxClientFactory(tmuxClientScope).create(
            session = session,
            sessionName = anchorSession,
        )
        ccClient = client
        withTimeout(20_000) { client.connect() }
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
        vm.bind(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = keyFile.absolutePath,
            passphrase = null,
        )

        // Cold-open reconcile settles: the anchor session is shown, the
        // out-of-band one is NOT yet present.
        awaitSession(vm, anchorSession)
        assertFalse(
            "the out-of-band session must not exist before it is created; state=${vm.state.value}",
            hasSession(vm, outOfBandSession),
        )

        // 3. Create the session OUT-OF-BAND from a SEPARATE plain SSH connection
        //    (NOT the app's createSession). This is what an agent or a second
        //    terminal does; tmux emits `%sessions-changed` on the live client.
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { s ->
                s.exec("mkdir -p $outOfBandFolder")
                s.exec("tmux new-session -d -s $outOfBandSession -c $outOfBandFolder")
                createdSessions += outOfBandSession
            }
        }

        // 4. The %sessions-changed-triggered reconcile must surface the new
        //    session within SECONDS — NO manual refresh, NO resume, far inside
        //    the 15-min staleness gate.
        val startedAt = System.currentTimeMillis()
        awaitSession(vm, outOfBandSession, timeoutMs = 25_000L)
        val elapsedMs = System.currentTimeMillis() - startedAt
        assertTrue(
            "the out-of-band session must appear within seconds (event-driven), not the " +
                "15-min staleness gate; took ${elapsedMs}ms",
            elapsedMs < 25_000L,
        )
        // Sanity: the anchor session is still listed too.
        assertTrue("anchor session must remain listed", hasSession(vm, anchorSession))
    } }

    private fun hasSession(vm: FolderListViewModel, sessionName: String): Boolean {
        val state = vm.state.value as? FolderListUiState.Ready ?: return false
        return state.flatSessions.any { it.sessionName == sessionName }
    }

    private suspend fun awaitSession(
        vm: FolderListViewModel,
        sessionName: String,
        timeoutMs: Long = 20_000L,
    ) {
        withTimeout(timeoutMs) {
            while (!hasSession(vm, sessionName)) {
                delay(200L)
            }
        }
    }
}
