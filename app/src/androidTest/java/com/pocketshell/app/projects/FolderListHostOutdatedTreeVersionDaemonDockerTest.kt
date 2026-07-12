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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1509 (G10 — end-to-end on the real path) — the host-version-mismatch
 * relocation, proven against a host whose `pocketshell tree` payload carries a
 * GENUINE (not synthetic) `cli_version` OLDER than the app expects.
 *
 * ## Why this fixture / this shape
 *
 * The reported symptom pairs the notifications prompt with the host-version
 * **Update PocketShell** banner. #1509 relocates that banner check to a single,
 * lazy, background session-tree pass that runs EXACTLY ONCE per open — never as
 * an app-open trigger, never re-raised by later reconcile polls. The reviewer's
 * G10 gap: no available Docker fixture reports a host-outdated CLI, so the
 * relocation had no real-path proof (`agents:2222` has no `tree` subcommand;
 * `agents-old-cli:2238` rejects `tree` entirely — neither yields a versioned
 * envelope).
 *
 * The `agents-daemon` fixture (port 2239) runs the REAL Python `pocketshell`
 * package, so `pocketshell tree get|reconcile` emit a GENUINE `cli_version`
 * through the real `tree.py` envelope (`tools/pocketshell/src/pocketshell/tree.py`
 * → `_cli_version()`), parsed by the REAL production [TreeRemoteSource]. That
 * installed version ships in lockstep with the app, so to reproduce a
 * host-OUTDATED mismatch on this real envelope we pin the app's EXPECTED version
 * ABOVE it (`expectedPocketshellVersionProvider = { "99.0.0" }`). This is the
 * app's own build-version seam (the same one the JVM check uses); the host's
 * older real `cli_version` vs a higher app expected version is EXACTLY the
 * production host-outdated condition — the mismatch is genuine, driven by a real
 * tree envelope over a real SSH session, not a synthetic in-memory payload.
 *
 * ## What it proves (acceptance)
 *
 *  1. Opening the session tree is IMMEDIATE — the VM reaches `Ready` with the
 *     live session; it does NOT block on the version check.
 *  2. The **update banner** (`cliVersionMismatch`) appears via the BACKGROUND
 *     check AFTER the tree is shown — driven by the real payload `cli_version`,
 *     not an app-open trigger.
 *  3. It fires EXACTLY ONCE: after the user dismisses the banner, a
 *     foreground/resume reconcile (which also carries `cli_version`) must NOT
 *     re-raise it (the one-shot session-tree setup guard on the real path).
 *
 * ## RED / GREEN
 *
 * GREEN with the fix: banner appears once, background, after Ready, and stays
 * dismissed across a reconcile. RED if the check regressed to firing on every
 * reconcile (the pre-#1509 duplicate trigger): the dismissed banner would
 * re-raise on the resume reconcile and phase 3 fails. The JVM sibling
 * `FolderListViewModelSessionTreeSetupTest` pins the same guard red→green off a
 * synthetic session; this is its on-device real-envelope counterpart.
 *
 * ## CI gating
 *
 * The `emulator-journey` workflow already brings up `agents-daemon` on 2239 (for
 * `FolderListDurableTreeDaemonDockerTest`) with a sanity check that its real
 * `pocketshell tree` persists, so NO new fixture/port/workflow wiring is needed.
 * This class is wired into `scripts/ci-journey-suite.sh::JOURNEY_CLASSES`, so the
 * load-bearing assertion RUNS on per-push CI (no `assumeFalse(isRunningOnCi())`
 * self-skip — that would leave it with zero protection). `waitForSshFixtureReady`
 * HARD-fails fast if 2239 is unreachable, so a missing fixture surfaces loudly
 * rather than a vacuous skip (G3/G10). The always-runnable JVM backstop is
 * `FolderListViewModelSessionTreeSetupTest` (Unit job).
 *
 * Docker service: `agents-daemon` on host port `2239`.
 */
@RunWith(AndroidJUnit4::class)
class FolderListHostOutdatedTreeVersionDaemonDockerTest {

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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keyFile = File(context.cacheDir, "issue1509-daemon-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        waitForSshFixtureReady(sshKey, port = DAEMON_PORT)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        viewModelStore.clear()
        runCatching {
            withTimeout(20_000) {
                connect().use { session ->
                    for (name in createdSessions) {
                        runCatching { session.exec("tmux kill-session -t $name 2>/dev/null || true") }
                    }
                    runCatching {
                        session.exec(
                            "rm -f \"\${XDG_STATE_HOME:-\$HOME/.local/state}\"" +
                                "/pocketshell/tree/registry.json 2>/dev/null || true",
                        )
                    }
                }
            }
        }
        runCatching { db.close() }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun hostOutdatedTreeVersion_bannerRaisedOnceInBackground_afterTreeShown(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val dir = "/tmp/issue1509-$suffix"
        val sessionName = "issue1509-$suffix"

        // --- Pre-condition: seed a real session AND prove the daemon fixture
        // emits a GENUINE, older-than-99.0.0 `cli_version` through the real tree
        // envelope — otherwise the mismatch proof would be vacuous. ---
        withTimeout(30_000) {
            connect().use { session ->
                session.exec("mkdir -p $dir")
                session.exec("tmux new-session -d -s $sessionName -c $dir")
                createdSessions += sessionName
                val treeGet = session.exec("printf '%s' '{\"host\":\"h\"}' | pocketshell tree get")
                assertTrue(
                    "the agents-daemon fixture must emit a real `cli_version` through the " +
                        "tree envelope (get exit=${treeGet.exitCode}, stdout=${treeGet.stdout}) — " +
                        "otherwise the host-outdated mismatch proof is vacuous",
                    treeGet.exitCode == 0 &&
                        treeGet.stdout.contains("\"cli_version\"") &&
                        parsedCliVersionIsBelow(treeGet.stdout, EXPECTED_APP_VERSION),
                )
            }
        }

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue1509-key", privateKeyPath = keyFile.absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue1509-host-$suffix",
                hostname = DEFAULT_HOST,
                port = DAEMON_PORT,
                username = DEFAULT_USER,
                keyId = keyId,
            ),
        )
        val host = db.hostDao().getById(hostId)!!

        // Drive the PRODUCTION VM + REAL TreeRemoteSource. Pin the EXPECTED app
        // version ABOVE the fixture's real installed version so the real payload
        // `cli_version` is host-OUTDATED (the production condition).
        val vm = newViewModel()
        vm.setProcessStartedForTest(true)
        bind(vm, host)

        // (1) Opening the tree is IMMEDIATE — Ready with the live session. The
        // version check must NOT block this.
        withTimeout(40_000) {
            while (true) {
                val s = vm.state.value
                if (s is FolderListUiState.Ready &&
                    s.flatSessions.any { it.sessionName == sessionName }
                ) {
                    break
                }
                delay(250L)
            }
        }

        // (2) The update banner appears via the BACKGROUND check, off the real
        // payload `cli_version` — after the tree is shown, not on app open.
        withTimeout(30_000) {
            while (vm.cliVersionMismatch.value == null) delay(250L)
        }
        val mismatch = vm.cliVersionMismatch.value
        assertTrue(
            "the real host `cli_version` (< $EXPECTED_APP_VERSION) must raise the " +
                "host-outdated update banner via the background session-tree check — " +
                "mismatch=$mismatch",
            mismatch != null && mismatch.expectedVersion == EXPECTED_APP_VERSION,
        )

        // (3) EXACTLY ONCE: dismiss the banner, then drive a foreground/resume
        // reconcile (which also carries `cli_version`). The one-shot guard must
        // NOT re-raise the dismissed banner (the pre-#1509 duplicate-trigger bug).
        vm.dismissCliVersionMismatch()
        assertNull(vm.cliVersionMismatch.value)
        vm.forceTreeStaleForTest()
        vm.setProcessStartedForTest(false)
        delay(200L)
        vm.setProcessStartedForTest(true)

        // Give the resume reconcile ample time to run its `tree reconcile` (which
        // carries `cli_version`); the dismissed banner must stay dismissed.
        withTimeout(30_000) {
            // Confirm a reconcile actually happened by waiting for the live tree
            // to still hold the session after the resume, then assert no re-raise.
            while (true) {
                val s = vm.state.value
                if (s is FolderListUiState.Ready &&
                    s.flatSessions.any { it.sessionName == sessionName }
                ) {
                    break
                }
                delay(250L)
            }
        }
        // Settle past any in-flight reconcile so a re-raise would have landed.
        delay(3_000L)
        assertNull(
            "the version-mismatch banner must fire EXACTLY ONCE per session-tree " +
                "open — a resume reconcile must NOT re-raise a dismissed banner " +
                "(the pre-#1509 duplicate trigger)",
            vm.cliVersionMismatch.value,
        )
    } }

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

    /** True when the tree envelope's `cli_version` parses and is below [ceiling]. */
    private fun parsedCliVersionIsBelow(stdout: String, ceiling: String): Boolean {
        val root = runCatching { org.json.JSONObject(stdout.trim()) }.getOrNull() ?: return false
        val cli = root.optString("cli_version", "").takeIf { it.isNotBlank() } ?: return false
        val cmp = PayloadVersionCheck.compareDottedVersions(cli, ceiling) ?: return false
        return cmp < 0
    }

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DAEMON_PORT,
        user = DEFAULT_USER,
        key = sshKey,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 10_000,
    ).getOrThrow()

    private fun newViewModel(): FolderListViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FolderListViewModel(
            gateway = SshFolderListGateway(),
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(context),
            // A REAL TreeRemoteSource so the cold-start hydrate + resume reconcile
            // exercise the genuine `pocketshell tree` daemon payload (real
            // `cli_version`) on the host.
            treeRemoteSource = TreeRemoteSource(),
            // Pin the app's EXPECTED version above the fixture's real installed
            // version → the real payload `cli_version` is host-outdated.
            expectedPocketshellVersionProvider = { EXPECTED_APP_VERSION },
            attachLifecycle = false,
        ).also { vm ->
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", vm)
        }
    }

    private companion object {
        const val DAEMON_PORT: Int = 2239
        // Deliberately far above any real release version so the fixture's genuine
        // installed `cli_version` is unambiguously host-outdated.
        const val EXPECTED_APP_VERSION: String = "99.0.0"
    }
}
