package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.proof.DEFAULT_HOST
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
 * Issue #847 (P0, shipped v0.4.10 regression) — connected (emulator + Docker)
 * proof that the app CONNECTS and renders the LIVE tree when the host
 * `pocketshell` CLI is OLDER than the client (no `tree` subcommand).
 *
 * ## The regression
 *
 * #837 added a cold-start tree HYDRATE: `FolderListViewModel` execs
 * `pocketshell tree get` over the warm session BEFORE the live reconcile.
 * `tree` is new in 0.4.10; on the maintainer's 0.4.9 host it errors
 * (`No such command 'tree'`). v0.4.10 chained the freshening reconcile AFTER
 * the hydrate, so the failed `tree get` left the reconcile un-run and the app
 * sat on "loading tree" forever — it would not connect.
 *
 * ## Why this fixture is the missing one (#838-gate gap)
 *
 * The normal `agents` fixture (port 2222) always carries the matching 0.4.10
 * CLI, so the old-CLI / command-missing path was never exercised in review.
 * This test drives the PRODUCTION [FolderListViewModel] + [SshFolderListGateway]
 * + a real [TreeRemoteSource] against the dedicated `agents-old-cli` fixture
 * (port 2238, `tests/docker/Dockerfile.agents-old-cli`) whose `pocketshell`
 * rejects `tree` / `agents kind` exactly like an old Click CLI. On the un-fixed
 * code the VM stays `Loading` (the reconcile never fires after the hydrate's
 * `tree get` errors); after the fix the hydrate degrades gracefully and the
 * freshening reconcile runs in a `finally`, so the live tree renders.
 *
 * ## CI gating (issue #849 — this test now RUNS on CI)
 *
 * Previously this test self-skipped with `assumeFalse(isRunningOnCi())` because
 * the GitHub `emulator-journey` workflow started only the `agents` fixture on
 * 2222. As of #849 that workflow ALSO brings up `agents-old-cli` on 2238 and
 * adds this class to `scripts/ci-journey-suite.sh::JOURNEY_CLASSES`, so the
 * self-skip is GONE — this connected test IS the per-PR + pre-release regression
 * net for the v0.4.10 connect-break (old host CLI → "loading tree" hang). The
 * pre-release confidence gate (`scripts/pre-release-confidence-gate.sh`) also
 * brings up 2238. The JVM `FolderListViewModelOldCliHydrateTest` (per-push Unit
 * job) remains the always-runnable backstop.
 *
 * Docker service: `agents-old-cli` on host port `2238`.
 */
@RunWith(AndroidJUnit4::class)
class FolderListOldCliHydrateDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var db: AppDatabase
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0
    private val createdSessions = mutableListOf<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        // Issue #849: NO assumeFalse(isRunningOnCi()) self-skip. The
        // emulator-journey workflow (and the pre-release gate) now start the
        // agents-old-cli fixture on 2238, so this connect-break regression
        // RUNS on CI. waitForSshFixtureReady below HARD-fails fast if 2238 is
        // unreachable, so a missing fixture surfaces loudly instead of a vacuous
        // skip — process.md G3/G10.
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue847-old-cli-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        waitForSshFixtureReady(sshKey, port = OLD_CLI_PORT)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        viewModelStore.clear()
        if (createdSessions.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = OLD_CLI_PORT,
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

    /**
     * RED on v0.4.10: with a host whose CLI lacks `tree`, the cold-start hydrate
     * pinned `Loading`. GREEN after the fix: the VM connects and renders the
     * LIVE tree (the seeded session appears) within the connect window.
     */
    @Test
    fun connectsToLiveTree_onHostWithOldCliLackingTreeCommand(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folder = "/tmp/issue847-old-cli-$suffix"
        val sessionName = "issue847-old-cli-$suffix"

        // Seed a real tmux session so the LIVE tree has content to render.
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = OLD_CLI_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                // Confirm the fixture really is an OLD CLI: `tree get` must error
                // with the non-zero `No such command` shape (the bug trigger).
                val treeProbe = session.exec("printf '%s' '{\"host\":\"h\"}' | pocketshell tree get")
                assertTrue(
                    "the agents-old-cli fixture must reject `tree` like an old CLI " +
                        "(exit=${treeProbe.exitCode} stderr=${treeProbe.stderr}) — otherwise the " +
                        "test would not exercise the #847 mismatch path",
                    treeProbe.exitCode != 0,
                )
                session.exec("mkdir -p $folder")
                session.exec("tmux new-session -d -s $sessionName -c $folder")
                createdSessions += sessionName
            }
        }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue847-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue847-old-cli-host",
                hostname = DEFAULT_HOST,
                port = OLD_CLI_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        // Drive the PRODUCTION view model with a REAL TreeRemoteSource so the
        // cold-start hydrate runs against the old CLI — the exact bug path.
        val vm = newViewModel()
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

        // The load-bearing assertion: the app must leave Loading and render the
        // live tree (the seeded session) — NOT hang on "loading tree".
        withTimeout(30_000L) {
            while (true) {
                val state = vm.state.value
                if (state is FolderListUiState.Ready &&
                    state.flatSessions.any { it.sessionName == sessionName }
                ) {
                    break
                }
                delay(250L)
            }
        }
        val finalState = vm.state.value
        assertTrue(
            "with an OLD host CLI (no `tree`) the app must CONNECT and render the live " +
                "tree, not hang on Loading — state=$finalState",
            finalState is FolderListUiState.Ready &&
                (finalState as FolderListUiState.Ready).flatSessions
                    .any { it.sessionName == sessionName },
        )
    }

    private fun newViewModel(): FolderListViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FolderListViewModel(
            gateway = SshFolderListGateway(),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(context),
            // A REAL tree source so the cold-start hydrate exercises the old
            // CLI's failing `tree get` — without it the bug path is skipped.
            treeRemoteSource = TreeRemoteSource(),
            attachLifecycle = false,
        ).also { vm ->
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", vm)
        }
    }

    private companion object {
        const val OLD_CLI_PORT: Int = 2238
    }
}
