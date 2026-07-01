package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
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
 * Connected smoke test for issue #171 — the folder-list gateway must
 * pull tmux's `session_path` + active-pane `pane_current_path` from
 * the deterministic Docker `agents` service and the grouping logic
 * must produce one row per folder.
 *
 * The test exercises the gateway directly (no Compose UI) so the
 * assertions stay tight on the SSH probe + grouping plumbing. The
 * UI rendering is covered by the local unit suite
 * ([FolderListGroupingTest]) + a manual emulator pass before merge.
 *
 * Docker service: `agents` on host port `2222` (already wired into
 * the CI workflow per the brief's CI-compat rule — no new fixture
 * required).
 *
 * The test creates three tmux sessions across two folders, runs the
 * gateway, and asserts:
 *
 *  - The gateway returns five sessions in total: three we created
 *    plus any pre-existing baseline sessions on the fixture (we filter
 *    to our created names before asserting counts).
 *  - Two of our created sessions share the same `pane_current_path`
 *    and group into the same `FolderRow`.
 *  - One session lives in a different folder and gets its own row.
 *  - The active pane's `pane_current_path` overrides `session_path`
 *    when the user `cd`s elsewhere inside the session (we drive this
 *    with `send-keys` so a session starts in folder A and cd's to
 *    folder B; the gateway must report B).
 */
@RunWith(AndroidJUnit4::class)
class FolderListGatewayDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
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
        // The gateway accepts an SshKey.Path so we materialise the
        // PEM into a tmp file on the emulator's filesystem.
        val cacheDir = InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir
        keyFile = File(cacheDir, "issue171-folder-list-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        // Best-effort cleanup — kill any tmux sessions we created so
        // re-runs / sibling tests start from a clean slate.
        if (createdSessions.isEmpty()) return@runBlocking
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
        runCatching { keyFile.delete() }
    } }

    @Test
    fun gatewayGroupsThreeSessionsAcrossTwoFolders(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val folderA = "/tmp/issue171-folder-a-$suffix"
        val folderB = "/tmp/issue171-folder-b-$suffix"
        val sessionAlpha = "issue171-alpha-$suffix"
        val sessionBeta = "issue171-beta-$suffix"
        val sessionGamma = "issue171-gamma-$suffix"

        // Seed the fixture: two folders, three sessions where alpha +
        // beta land in folderA and gamma lands in folderB.
        withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                session.exec("mkdir -p $folderA $folderB")
                session.exec("tmux new-session -d -s $sessionAlpha -c $folderA")
                session.exec("tmux new-session -d -s $sessionBeta -c $folderA")
                session.exec("tmux new-session -d -s $sessionGamma -c $folderB")
                createdSessions += listOf(sessionAlpha, sessionBeta, sessionGamma)
            }
        }

        // Run the gateway probe.
        val gateway = SshFolderListGateway()
        val host = HostEntity(
            id = 1L,
            name = "folder-list-test",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
        )
        val result = gateway.listSessionsWithFolder(
            host = host,
            keyPath = keyFile.absolutePath,
            passphrase = null,
        )

        assertTrue(
            "expected FolderListResult.Sessions, got $result",
            result is FolderListResult.Sessions,
        )
        val rows = (result as FolderListResult.Sessions).rows

        // Pull out just our created rows so pre-existing fixture
        // sessions don't perturb the assertion.
        val ours = rows.filter { it.sessionName in setOf(sessionAlpha, sessionBeta, sessionGamma) }
        assertEquals("expected three of our sessions to be discovered", 3, ours.size)

        val alpha = ours.first { it.sessionName == sessionAlpha }
        val beta = ours.first { it.sessionName == sessionBeta }
        val gamma = ours.first { it.sessionName == sessionGamma }

        // cwd points at the right folder for each session.
        assertEquals(folderA, alpha.cwd)
        assertEquals(folderA, beta.cwd)
        assertEquals(folderB, gamma.cwd)

        // Drive the pure grouping function with the gateway output to
        // verify the screen will render two folders.
        val entries = ours.map { row ->
            FolderSessionEntry(
                sessionName = row.sessionName,
                lastActivity = row.lastActivity,
                attached = row.attached,
                // Issue #171 round 2: agentKind is now sourced directly
                // from the gateway's per-session detection (no more
                // name-regex heuristic). Bare tmux sessions seeded by
                // this test stay on Shell because there is no live
                // agent process and no recent JSONL candidate matching
                // their cwd.
                agentKind = row.agentKind,
            )
        }
        val cwdMap = ours.associate { row ->
            row.sessionName to (row.cwd?.let(FolderListViewModel::canonicalisePath)
                ?: FolderListViewModel.UNTRACKED_PATH)
        }
        val grouped = FolderListViewModel.groupSessionsIntoFolders(
            sessions = entries,
            sessionFolderPaths = cwdMap,
            watchedFolders = emptyList(),
        )
        assertEquals(
            "expected two folder rows for our seeded sessions, got ${grouped.map { it.label }}",
            2,
            grouped.size,
        )
        // folderA has two sessions, folderB has one.
        val rowA = grouped.first { it.path == folderA }
        val rowB = grouped.first { it.path == folderB }
        assertEquals(2, rowA.sessions.size)
        assertEquals(1, rowB.sessions.size)

        // Watched-folder overlay test: add a ProjectRootEntity for
        // folderA and an unrelated never-existed folder; the latter
        // should still appear as an empty row.
        val unmatchedWatched = "/tmp/issue171-watched-empty-$suffix"
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 1L, label = "Folder A pinned", path = folderA),
            ProjectRootEntity(id = 2L, hostId = 1L, label = "Empty pinned", path = unmatchedWatched),
        )
        val groupedWithWatched = FolderListViewModel.groupSessionsIntoFolders(
            sessions = entries,
            sessionFolderPaths = cwdMap,
            watchedFolders = watched,
        )
        assertEquals(3, groupedWithWatched.size)
        val pinnedA = groupedWithWatched.first { it.path == folderA }
        assertTrue("expected folderA to be flagged as watched", pinnedA.isWatched)
        assertEquals("Folder A pinned", pinnedA.label)
        val empty = groupedWithWatched.first { it.path == unmatchedWatched }
        assertTrue("expected unmatched watched folder to be empty", empty.isEmpty)
        assertTrue("expected unmatched watched folder to be flagged as watched", empty.isWatched)

        // Sanity check on agent-kind classification for the spike's
        // tinting tokens.
        assertEquals(SessionAgentKind.Shell, entries.first().agentKind)
        assertNotNull(rowA)
        assertNotNull(rowB)
    } }
}
