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
 * Issue #430 regression — "sessions disappear from the folder tree after
 * app or agent restart".
 *
 * The visible session list the maintainer browses lives on the folder
 * tree ([FolderListScreen] backed by [FolderListViewModel] via
 * [SshFolderListGateway.listSessionsWithFolder] — `tmux list-sessions` +
 * `list-panes -a`). Before this fix the view model's poll loop kept
 * running on a dead SSH lease while the app was backgrounded and did
 * **not** re-probe on the foreground/resume transition, so after the
 * user backgrounded + re-foregrounded the app (or relaunched it cold)
 * the host's still-alive remote tmux session no longer reappeared on the
 * tree until a manual re-attach.
 *
 * This connected test drives the **production** view model + gateway
 * against the Docker `agents` host (port 2222) and the
 * [FolderListViewModel.setProcessStartedForTest] foreground seam to
 * reproduce the journey:
 *
 *  1. Create a real tmux session on the remote.
 *  2. Bind the view model with the app foregrounded → the session is
 *     listed (baseline probe works).
 *  3. Drive the whole-process foreground signal to `false` (app
 *     backgrounded). No probing happens while backgrounded.
 *  4. Build a **fresh** view model with the gate initially `false` — the
 *     exact post-relaunch state where no live `-CC` client is registered
 *     and the previous poll loop is gone. With the app still
 *     backgrounded it must NOT have surfaced the session yet.
 *  5. Flip the gate to `true` (app foreground / resume). The loop wakes,
 *     runs an immediate probe, and the still-alive remote session
 *     reappears on the tree — matched purely by tmux session name, no
 *     live agent process or `-CC` client required.
 *
 * Pre-fix the loop in step 5 had no foreground gate to release on, so
 * the fresh view model never re-probed after relaunch and the session
 * stayed invisible — the test fails. Post-fix the foreground transition
 * drives the immediate probe and the session is listed.
 *
 * Docker service: `agents` on host port `2222` (the deterministic
 * default fixture; no extra compose service required).
 */
@RunWith(AndroidJUnit4::class)
class FolderListSessionResumeDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0
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
        val cacheDir = InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir
        keyFile = File(cacheDir, "issue430-folder-resume-key").apply {
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
    fun sessionReappearsOnFolderTreeAfterBackgroundResume(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folder = "/tmp/issue430-resume-$suffix"
        val sessionName = "issue430-resume-$suffix"

        // 1. Seed a real tmux session on the remote.
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
                session.exec("tmux new-session -d -s $sessionName -c $folder")
                createdSessions += sessionName
            }
        }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue430-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue430-host",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        // 2. Foregrounded baseline: bind and confirm the session lists.
        val firstVm = newViewModel()
        firstVm.setProcessStartedForTest(true)
        bind(firstVm, host)
        awaitSession(firstVm, sessionName)

        // 3. Background the app. The poll loop parks (no background SSH).
        firstVm.setProcessStartedForTest(false)
        delay(500L)

        // 4. Cold-relaunch state: a brand-new view model with the
        //    foreground gate still `false`. No live `-CC` client, no
        //    prior poll loop. While backgrounded it must NOT have
        //    surfaced the session yet.
        val relaunchVm = newViewModel()
        relaunchVm.setProcessStartedForTest(false)
        bind(relaunchVm, host)
        delay(2_000L)
        assertTrue(
            "while backgrounded the relaunched view model must not have " +
                "listed the session yet; state=${relaunchVm.state.value}",
            !hasSession(relaunchVm, sessionName),
        )

        // 5. Foreground / resume → immediate probe → session reappears.
        relaunchVm.setProcessStartedForTest(true)
        awaitSession(relaunchVm, sessionName)
    } }

    private fun newViewModel(): FolderListViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FolderListViewModel(
            gateway = SshFolderListGateway(),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(context),
            // Drive the foreground gate directly via the test seam; do
            // not touch the main-thread-affine ProcessLifecycleOwner
            // registry from this off-main-thread test.
            attachLifecycle = false,
        ).also { vm ->
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", vm)
        }
    }

    private fun bind(vm: FolderListViewModel, host: HostEntity) {
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
}
