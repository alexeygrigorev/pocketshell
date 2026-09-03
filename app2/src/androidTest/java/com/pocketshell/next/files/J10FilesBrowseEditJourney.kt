package com.pocketshell.next.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.tree.SESSION_TREE_FILES_TAG
import com.pocketshell.next.tree.SESSION_TREE_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J10 — browse a host's files, open one, edit it, save it, and see the
 * edit survive (rewrite tasks P-3a + P-3b).
 *
 * ## Why this has to be a device journey
 *
 * `FileExplorerViewModelTest` and `ViewerViewModelTest` drive the same
 * ViewModels over `core-transport`'s in-memory SFTP fixture, and they cannot
 * see any of the things that break here: an SFTP subsystem the server refuses
 * to open, a `pwd` that answers something other than an absolute path over a
 * non-interactive exec channel, a listing whose rows render off-screen, a text
 * field that the emulator's IME cannot type into, or a write that lands with the
 * wrong bytes because of an encoding hop. Everything from the tap to the pixels
 * is production code against a real sshd here.
 *
 * ## The edit is checked against the host, not against the app
 *
 * The load-bearing oracle is an INDEPENDENT `cat` over the journey's own SSH
 * connection ([AgentsFixture.exec]) after the save. A screen that showed the new
 * text without ever writing it would pass an in-app assertion and fail this one.
 * The re-open assertion then closes the loop from the other side: a SECOND read
 * through the app returns what the host holds.
 *
 * ## Fixture
 *
 * The Docker `agents` fixture on `10.0.2.2:2222` (see [AgentsFixture]). The
 * files each test needs are SEEDED over SSH in [seed] rather than baked into the
 * image, and the whole directory is removed and recreated first so a rerun
 * cannot pass on the previous run's leftovers.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 *
 * ## Trust
 *
 * The host row is seeded with the fingerprint the fixture actually presents
 * (read live in [seed]), so the dial connects without a prompt — the trust sheet
 * is `J01ConnectAndTrustJourney`'s subject. Per-test host ids for the same
 * reason J01/J02 use them: SQLite reuses `max(id) + 1`, and a reused id plus the
 * registry's one-connection-per-host cache would let a later test ride on an
 * earlier test's connection.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J10FilesBrowseEditJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J10_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedHostFiles()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j10_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j10-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
    }

    /**
     * Recreates the journey's directory on the host.
     *
     * Removed first, deliberately: a rerun that found `notes.txt` already
     * carrying the edited text would pass its save assertion without saving
     * anything.
     */
    private fun seedHostFiles() {
        AgentsFixture.exec("rm -rf $DIR && mkdir -p $DIR/nested")
        AgentsFixture.writeFile("$DIR/$TEXT_FILE", ORIGINAL_TEXT)
        AgentsFixture.writeFile("$DIR/$MARKDOWN_FILE", MARKDOWN_SOURCE)
        AgentsFixture.writeFile("$DIR/nested/deep.txt", "you found me")
        // A file that is neither text nor an image: two NUL bytes make the
        // detector's UTF-8 sniff reject it, which is what the fallback exists
        // for. `printf` because a heredoc cannot carry a NUL.
        AgentsFixture.exec("printf 'PK\\003\\004\\000\\000binary' > $DIR/$BINARY_FILE")
    }

    /**
     * The headline journey: browse to a text file, edit it, save it, and prove
     * the host really holds the new content.
     */
    @Test
    fun browsingToATextFileEditingItAndSavingWritesTheHostFile() {
        openExplorerAt(DIR)
        JourneyScreenshots.capture("01-explorer", JOURNEY)

        // Everything the host has in this directory has a row on screen. The
        // oracle is the host's own `ls`, read over a separate connection — a
        // hard-coded expected list would pass just as happily against a stale
        // cache or a placeholder.
        val hostEntries = hostEntryNames(DIR)
        assertTrue(
            "the fixture must report the seeded files, got $hostEntries",
            hostEntries.containsAll(listOf("nested", TEXT_FILE, MARKDOWN_FILE, BINARY_FILE)),
        )
        hostEntries.forEach { name -> scrollTo(fileRowTag(name)) }

        // Browse into a subdirectory and back out, so "navigate in/out" is
        // exercised against real listings rather than asserted structurally.
        scrollTo(fileRowTag("nested"))
        compose.onNodeWithTag(fileRowTag("nested")).performClick()
        awaitTag(fileRowTag("deep.txt"))
        compose.onNodeWithTag(FILE_EXPLORER_UP_TAG).performClick()
        awaitTag(fileRowTag(TEXT_FILE))

        // Open the file: its real content is on screen.
        scrollTo(fileRowTag(TEXT_FILE))
        compose.onNodeWithTag(fileRowTag(TEXT_FILE)).performClick()
        awaitTag(VIEWER_TEXT_TAG)
        // `substring`: the fixture's heredoc write leaves the file
        // newline-terminated the way any editor would, so the rendered node
        // holds "the original line\n".
        compose.onNodeWithText(ORIGINAL_TEXT, substring = true).assertIsDisplayed()
        JourneyScreenshots.capture("02-viewer", JOURNEY)

        // Edit and save.
        compose.onNodeWithTag(VIEWER_EDIT_TAG).performClick()
        awaitTag(VIEWER_EDITOR_TAG)
        compose.onNodeWithTag(VIEWER_EDITOR_TAG).performTextReplacement(EDITED_TEXT)
        JourneyScreenshots.capture("03-editor", JOURNEY)
        compose.onNodeWithTag(VIEWER_SAVE_TAG).performClick()
        awaitTag(VIEWER_SAVED_TAG)
        JourneyScreenshots.capture("04-saved", JOURNEY)

        // THE load-bearing assertion: the host's own copy of the file changed.
        assertEquals(
            "the host must hold the edited text",
            EDITED_TEXT,
            AgentsFixture.exec("cat $DIR/$TEXT_FILE").trimEnd('\n'),
        )

        // And the app agrees on a fresh read: back to the explorer, re-open,
        // and the new text — not the old one — is what renders.
        compose.onNodeWithText("Back").performClick()
        awaitTag(fileRowTag(TEXT_FILE))
        scrollTo(fileRowTag(TEXT_FILE))
        compose.onNodeWithTag(fileRowTag(TEXT_FILE)).performClick()
        awaitTag(VIEWER_TEXT_TAG)
        awaitText(EDITED_TEXT)
        compose.onNodeWithText(EDITED_TEXT).assertIsDisplayed()
        compose.onNodeWithText(ORIGINAL_TEXT, substring = true).assertDoesNotExist()
        JourneyScreenshots.capture("05-reopened", JOURNEY)
    }

    /**
     * A Markdown file opens FORMATTED. The state is identical whether the
     * viewer renders or dumps source, so only a rendered-tree assertion can tell
     * the difference — which is why this lives here and not in a ViewModel test.
     */
    @Test
    fun aMarkdownFileOpensRenderedAndCanBeSwitchedToSource() {
        openExplorerAt(DIR)
        scrollTo(fileRowTag(MARKDOWN_FILE))
        compose.onNodeWithTag(fileRowTag(MARKDOWN_FILE)).performClick()

        awaitTag(MARKDOWN_VIEW_TAG)
        JourneyScreenshots.capture("06-markdown-rendered", JOURNEY)
        // The heading is on screen without its `#`, and the fence markers are
        // not painted at all.
        compose.onNodeWithText("Release notes").assertIsDisplayed()
        compose.onNodeWithText("# Release notes").assertDoesNotExist()
        compose.onNodeWithText("cargo build --release").assertIsDisplayed()
        compose.onNodeWithTag(VIEWER_TEXT_TAG).assertDoesNotExist()

        compose.onNodeWithTag(VIEWER_MARKDOWN_TOGGLE_TAG).performClick()

        awaitTag(VIEWER_TEXT_TAG)
        compose.onNodeWithTag(MARKDOWN_VIEW_TAG).assertDoesNotExist()
        JourneyScreenshots.capture("07-markdown-source", JOURNEY)
    }

    /**
     * A file that is neither text nor an image renders a hex preview instead of
     * crashing, blanking, or — worst — opening in the text editor and offering
     * to write a mangled version back.
     */
    @Test
    fun anUndecodableFileFallsBackToAHexPreviewAndCannotBeEdited() {
        openExplorerAt(DIR)
        scrollTo(fileRowTag(BINARY_FILE))
        compose.onNodeWithTag(fileRowTag(BINARY_FILE)).performClick()

        awaitTag(VIEWER_BINARY_TAG)
        compose.onNodeWithTag(VIEWER_BINARY_NOTE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(VIEWER_TEXT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(VIEWER_ERROR_TAG).assertDoesNotExist()
        JourneyScreenshots.capture("08-binary", JOURNEY)
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Taps the seeded host, opens the file explorer from the session tree, and
     * navigates to [path] through the breadcrumb the explorer built from the
     * home directory the HOST reported.
     */
    private fun openExplorerAt(path: String) {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)

        compose.onNodeWithTag(SESSION_TREE_FILES_TAG).performClick()
        awaitTag(FILE_EXPLORER_TAG)
        // The explorer opens with no path argument, so its first listing proves
        // the `pwd` home-directory resolution works over a real exec channel.
        awaitTag(crumbTag(HOME))

        path.removePrefix("$HOME/").split('/').fold(HOME) { parent, segment ->
            val child = "$parent/$segment"
            awaitTag(FILE_EXPLORER_LIST_TAG)
            scrollTo(fileRowTag(segment))
            compose.onNodeWithTag(fileRowTag(segment)).performClick()
            awaitTag(crumbTag(child))
            child
        }
    }

    /** The host's own answer for what is in [path], over an independent connection. */
    private fun hostEntryNames(path: String): List<String> =
        AgentsFixture.exec("ls -A $path")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(FILE_EXPLORER_LIST_TAG).performScrollToNode(hasTestTag(tag))
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val JOURNEY = "j10-files"

        const val HOME = "/home/testuser"
        const val DIR = "$HOME/j10"

        const val TEXT_FILE = "notes.txt"
        const val MARKDOWN_FILE = "release.md"
        const val BINARY_FILE = "blob.bin"

        const val ORIGINAL_TEXT = "the original line"
        const val EDITED_TEXT = "edited from the phone"

        val MARKDOWN_SOURCE = """
            # Release notes

            Build it with:

            ```sh
            cargo build --release
            ```
        """.trimIndent()

        val HOST_IDS: Map<String, Long> = mapOf(
            "browsingToATextFileEditingItAndSavingWritesTheHostFile" to 9_301L,
            "aMarkdownFileOpensRenderedAndCanBeSwitchedToSource" to 9_302L,
            "anUndecodableFileFallsBackToAHexPreviewAndCannotBeEdited" to 9_303L,
        )
    }
}
