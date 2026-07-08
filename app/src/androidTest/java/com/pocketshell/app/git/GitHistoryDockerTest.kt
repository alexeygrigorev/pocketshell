package com.pocketshell.app.git

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connected Docker test for the Git history screen (issue #646).
 *
 * Seeds a small git repository on the deterministic `agents` fixture (host port
 * `2222`, already wired into the CI emulator job), drives [GitHistoryScreen]
 * against the live SSH session, and asserts the seeded commits render. A second
 * case points the screen at a non-git directory and asserts the NotARepo state.
 * Screenshots are captured under the walkthrough artifact directory for
 * reviewer inspection.
 */
@RunWith(AndroidJUnit4::class)
class GitHistoryDockerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private var seededRoot: String? = null

    @Before
    fun setUp(): Unit { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue646-git-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        seededRoot?.let { root ->
            withTimeout(15_000) {
                connect()?.use { session -> runCatching { session.exec("rm -rf '$root'") } }
            }
        }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun rendersSeededCommitHistory(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue646-$suffix"
        val repo = "$root/proj"
        seededRoot = root

        // Seed a git repo with two known commits. Configure identity locally so
        // the fixture image needs no global git config.
        withTimeout(30_000) {
            connect()?.use { session ->
                val script = listOf(
                    "mkdir -p '$repo'",
                    "cd '$repo'",
                    "git init -q",
                    "git config user.email tester@example.com",
                    "git config user.name 'PocketShell Tester'",
                    "printf 'one\\n' > a.txt",
                    "git add a.txt",
                    "git commit -q -m 'Add a.txt'",
                    "printf 'two\\n' > b.txt",
                    "git add b.txt",
                    "git commit -q -m 'Add b.txt'",
                    "echo ok",
                ).joinToString(" && ")
                val exit = session.exec(script)
                assertEquals("seed exit (stderr=${exit.stderr})", 0, exit.exitCode)
            } ?: error("could not connect to seed git repo")
        }

        // Issue #699: count the real SSH handshakes the warm-lease pool makes
        // while the whole screen (history + overview + github + gh probes) loads.
        val dialCount = AtomicInteger(0)
        composeRule.setContent {
            GitHistoryScreen(
                hostId = 646L,
                hostName = "proj",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                dir = repo,
                onBack = {},
                viewModel = newGitHistoryViewModel(dialCount),
            )
        }

        // Wait for the repo to load, then switch from the default Overview tab to
        // the History tab where the commits render.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_HISTORY_TAB_TAG)
        }
        composeRule.onNodeWithTag(GIT_HISTORY_TAB_TAG).performClick()

        // Both seeded commit subjects render (newest first).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTextExists("Add b.txt") &&
                composeRule.onAllNodesWithTextExists("Add a.txt")
        }
        // Issue #699: the entire screen rode ONE warm transport — a single SSH
        // handshake — instead of a fresh per-action dial. (Many git execs, one
        // lease.) The lease self-heals on a stale transport, so allow at most a
        // single heal re-dial; the per-action regression would be >>1.
        assertEquals(
            "git history should reuse ONE warm lease, not dial per action (#699)",
            1,
            dialCount.get(),
        )
        WalkthroughScreenshotArtifacts.capture("issue646-git-history")
    } }

    @Test
    fun rendersBranchesWorktreesAndStatusOverview(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue647-$suffix"
        val repo = "$root/proj"
        seededRoot = root

        // Seed a repo with two branches and a linked worktree, then leave the
        // working tree dirty so the Status row reports it.
        withTimeout(40_000) {
            connect()?.use { session ->
                val script = listOf(
                    "mkdir -p '$repo'",
                    "cd '$repo'",
                    "git init -q",
                    "git config user.email tester@example.com",
                    "git config user.name 'PocketShell Tester'",
                    "printf 'one\\n' > a.txt",
                    "git add a.txt",
                    "git commit -q -m 'Add a.txt'",
                    "git branch feature-x",
                    "git worktree add -q '$root/proj-feature' feature-x",
                    // dirty the main working tree
                    "printf 'dirty\\n' > dirty.txt",
                    "echo ok",
                ).joinToString(" && ")
                val exit = session.exec(script)
                assertEquals("seed exit (stderr=${exit.stderr})", 0, exit.exitCode)
            } ?: error("could not connect to seed multi-branch git repo")
        }

        composeRule.setContent {
            GitHistoryScreen(
                hostId = 647L,
                hostName = "proj",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                dir = repo,
                onBack = {},
                viewModel = newGitHistoryViewModel(),
            )
        }

        // Overview is the default tab: both branches and the worktree paths render.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_BRANCH_ROW_TAG_PREFIX + "master") ||
                composeRule.onAllNodesWithTagExists(GIT_BRANCH_ROW_TAG_PREFIX + "main")
        }
        composeRule.onNodeWithTag(GIT_OVERVIEW_STATUS_TAG).assertExists()
        composeRule.onNodeWithTag(GIT_BRANCH_ROW_TAG_PREFIX + "feature-x").assertExists()
        composeRule.onNodeWithTag(GIT_WORKTREE_ROW_TAG_PREFIX + repo).assertExists()
        composeRule.onNodeWithTag(GIT_WORKTREE_ROW_TAG_PREFIX + "$root/proj-feature").assertExists()
        WalkthroughScreenshotArtifacts.capture("issue647-git-overview")
    } }

    @Test
    fun gitHubOriginShowsOpenOnGitHubAction(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue648-$suffix"
        val repo = "$root/proj"
        seededRoot = root

        // Seed a repo whose origin points at GitHub over SSH, so the gateway's
        // `git remote get-url origin` returns a GitHub remote and the screen
        // surfaces "Open on GitHub". The remote URL is parsed, not fetched, so
        // the URL not resolving to a real repo is fine for this test.
        withTimeout(30_000) {
            connect()?.use { session ->
                val script = listOf(
                    "mkdir -p '$repo'",
                    "cd '$repo'",
                    "git init -q",
                    "git config user.email tester@example.com",
                    "git config user.name 'PocketShell Tester'",
                    "git remote add origin git@github.com:pocketshell/demo.git",
                    "printf 'one\\n' > a.txt",
                    "git add a.txt",
                    "git commit -q -m 'Add a.txt'",
                    "echo ok",
                ).joinToString(" && ")
                val exit = session.exec(script)
                assertEquals("seed exit (stderr=${exit.stderr})", 0, exit.exitCode)
            } ?: error("could not connect to seed github-origin repo")
        }

        composeRule.setContent {
            GitHistoryScreen(
                hostId = 648L,
                hostName = "proj",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                dir = repo,
                onBack = {},
                viewModel = newGitHistoryViewModel(),
            )
        }

        // Overview is the default tab; the "Open on GitHub" row appears with the
        // canonical web URL derived from the github.com SSH remote.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_OPEN_ON_GITHUB_TAG)
        }
        composeRule.onNodeWithTag(GIT_OPEN_ON_GITHUB_TAG).assertExists()
        composeRule.onNodeWithText("github.com/pocketshell/demo").assertExists()
        WalkthroughScreenshotArtifacts.capture("issue648-open-on-github")
    } }

    @Test
    fun issuesTabShowsConfigureGhHintWhenGhNotConfigured(): Unit { runBlocking {
        // The deterministic `agents` fixture has no authenticated `gh` (and most
        // likely no `pocketshell` either), so the Issues tab must degrade to the
        // "configure gh" hint rather than show a list — issue #649's gated path.
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue649-$suffix"
        val repo = "$root/proj"
        seededRoot = root

        withTimeout(30_000) {
            connect()?.use { session ->
                val script = listOf(
                    "mkdir -p '$repo'",
                    "cd '$repo'",
                    "git init -q",
                    "git config user.email tester@example.com",
                    "git config user.name 'PocketShell Tester'",
                    "git remote add origin git@github.com:pocketshell/demo.git",
                    "printf 'one\\n' > a.txt",
                    "git add a.txt",
                    "git commit -q -m 'Add a.txt'",
                    "echo ok",
                ).joinToString(" && ")
                val exit = session.exec(script)
                assertEquals("seed exit (stderr=${exit.stderr})", 0, exit.exitCode)
            } ?: error("could not connect to seed issue-649 repo")
        }

        composeRule.setContent {
            GitHistoryScreen(
                hostId = 649L,
                hostName = "proj",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                dir = repo,
                onBack = {},
                viewModel = newGitHistoryViewModel(),
            )
        }

        // Wait for load, switch to the Issues tab, and confirm the configure-gh
        // hint renders (gh is not authenticated on the fixture).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_ISSUES_TAB_TAG)
        }
        composeRule.onNodeWithTag(GIT_ISSUES_TAB_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_ISSUES_HINT_TAG)
        }
        composeRule.onNodeWithTag(GIT_ISSUES_HINT_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue649-configure-gh-hint")
    } }

    @Test
    fun tappingCommitShowsUnifiedDiffOverWarmSession(): Unit { runBlocking {
        // Issue #1242: tap a commit → its unified diff is fetched via `git show`
        // over the SAME warm lease the log rode (D21, no new connection) and
        // renders in-app with +/- gutters.
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue1242-$suffix"
        val repo = "$root/proj"
        seededRoot = root

        var shortHash = ""
        withTimeout(30_000) {
            connect()?.use { session ->
                val script = listOf(
                    "mkdir -p '$repo'",
                    "cd '$repo'",
                    "git init -q",
                    "git config user.email tester@example.com",
                    "git config user.name 'PocketShell Tester'",
                    "printf 'one\\ntwo\\n' > a.txt",
                    "git add a.txt",
                    "git commit -q -m 'Seed a.txt'",
                    "printf 'one\\nCHANGED\\nthree\\n' > a.txt",
                    "git add a.txt",
                    "git commit -q -m 'Change a.txt'",
                    "echo ok",
                ).joinToString(" && ")
                val exit = session.exec(script)
                assertEquals("seed exit (stderr=${exit.stderr})", 0, exit.exitCode)
                shortHash = session.exec("git -C '$repo' rev-parse --short HEAD").stdout.trim()
            } ?: error("could not connect to seed diff repo")
        }
        assertTrue("seed must yield a short hash", shortHash.isNotBlank())

        // Issue #699: still ONE warm lease for the log AND the git show.
        val dialCount = AtomicInteger(0)
        composeRule.setContent {
            GitHistoryScreen(
                hostId = 1242L,
                hostName = "proj",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                dir = repo,
                onBack = {},
                viewModel = newGitHistoryViewModel(dialCount),
            )
        }

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_HISTORY_TAB_TAG)
        }
        composeRule.onNodeWithTag(GIT_HISTORY_TAB_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_HISTORY_ROW_TAG_PREFIX + shortHash)
        }
        composeRule.onNodeWithTag(GIT_HISTORY_ROW_TAG_PREFIX + shortHash).performClick()

        // The diff renders: content view + the added/removed lines from the commit.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_DIFF_CONTENT_TAG)
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTextExists("CHANGED")
        }
        composeRule.onNodeWithTag(GIT_DIFF_CONTENT_TAG).assertExists()
        // Only one real SSH handshake for the whole log + show journey (#699/D21).
        assertEquals(
            "git show should reuse ONE warm lease, not dial per action (#699/D21)",
            1,
            dialCount.get(),
        )
        WalkthroughScreenshotArtifacts.capture("issue1242-git-diff")
    } }

    @Test
    fun largeCommitDiffIsTruncatedWithMarker(): Unit { runBlocking {
        // Issue #1242 (AC2): a diff larger than the server-side byte cap must
        // come back windowed with a visible truncation marker — never an
        // unbounded read.
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue1242-big-$suffix"
        val repo = "$root/proj"
        seededRoot = root

        var shortHash = ""
        withTimeout(40_000) {
            connect()?.use { session ->
                // seq 1..60000 is ~400 KiB of added lines — well over the 256 KiB
                // DEFAULT_DIFF_BYTE_CAP, so the diff is byte-capped + truncated.
                val script = listOf(
                    "mkdir -p '$repo'",
                    "cd '$repo'",
                    "git init -q",
                    "git config user.email tester@example.com",
                    "git config user.name 'PocketShell Tester'",
                    "seq 1 60000 > big.txt",
                    "git add big.txt",
                    "git commit -q -m 'Add big.txt'",
                    "echo ok",
                ).joinToString(" && ")
                val exit = session.exec(script)
                assertEquals("seed exit (stderr=${exit.stderr})", 0, exit.exitCode)
                shortHash = session.exec("git -C '$repo' rev-parse --short HEAD").stdout.trim()
            } ?: error("could not connect to seed big-diff repo")
        }
        assertTrue(shortHash.isNotBlank())

        composeRule.setContent {
            GitHistoryScreen(
                hostId = 12420L,
                hostName = "proj",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                dir = repo,
                onBack = {},
                viewModel = newGitHistoryViewModel(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_HISTORY_TAB_TAG)
        }
        composeRule.onNodeWithTag(GIT_HISTORY_TAB_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_HISTORY_ROW_TAG_PREFIX + shortHash)
        }
        composeRule.onNodeWithTag(GIT_HISTORY_ROW_TAG_PREFIX + shortHash).performClick()

        // The truncation marker appears (the read was bounded, not unbounded).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_DIFF_TRUNCATED_TAG)
        }
        composeRule.onNodeWithTag(GIT_DIFF_TRUNCATED_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue1242-git-diff-truncated")
    } }

    @Test
    fun nonGitDirectoryShowsNotARepoState(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue646-plain-$suffix"
        seededRoot = root

        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec("mkdir -p '$root' && printf 'x' > '$root/file.txt' && echo ok")
                assertEquals("seed exit", 0, exit.exitCode)
            } ?: error("could not connect to seed plain dir")
        }

        composeRule.setContent {
            GitHistoryScreen(
                hostId = 6460L,
                hostName = "plain",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                dir = root,
                onBack = {},
                viewModel = newGitHistoryViewModel(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(GIT_HISTORY_NOT_A_REPO_TAG)
        }
        composeRule.onNodeWithTag(GIT_HISTORY_NOT_A_REPO_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue646-git-not-a-repo")
    } }

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DEFAULT_PORT,
        user = DEFAULT_USER,
        key = sshKey,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 10_000,
    ).getOrNull()

    /**
     * Issue #699: build the screen's view-model over a REAL [SshLeaseManager]
     * (the same warm-lease pool the live session screens use) so the Docker
     * test exercises the shared transport, not a fresh per-screen dial. The
     * wrapping [DialCountingConnector] records how many real SSH handshakes the
     * pool performed; one screen render runs many git execs over ONE lease.
     */
    private fun newGitHistoryViewModel(
        dialCount: AtomicInteger = AtomicInteger(0),
    ): GitHistoryViewModel =
        GitHistoryViewModel(
            sshLeaseManager = SshLeaseManager(
                connector = DialCountingConnector(dialCount),
            ),
        )

    private class DialCountingConnector(
        private val dialCount: AtomicInteger,
    ) : SshLeaseConnector {
        private val real = DefaultSshLeaseConnector()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            dialCount.incrementAndGet()
            return real.connect(target)
        }
    }
}

private fun ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

private fun ComposeContentTestRule.onAllNodesWithTextExists(text: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
