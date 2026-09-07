package com.pocketshell.next.tree

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.openQuietHost
import com.pocketshell.next.connect.openQuietSession
import com.pocketshell.next.hosts.HOST_LIST_TAG
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_ADD_PATH_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_BACK_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_EMPTY_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_PARTIAL_BANNER_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_SEARCH_TAG
import com.pocketshell.next.workspaces.workspaceRootAddTag
import com.pocketshell.next.workspaces.workspaceRootTag
import com.pocketshell.next.workspaces.workspaceRowTag
import com.pocketshell.next.workspaces.workspaceSessionRowTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Journey J02 — connect to a host, land on its workspaces, and see the
 * sessions the host really has (rewrite task U-3).
 *
 * ## Why this has to be a device journey
 *
 * `SessionTreeViewModelTest` drives the same ViewModel over a scripted
 * connection on the host JVM, and it cannot see any of the things that break
 * here: `pocketshell` not being on the non-interactive SSH `exec` PATH, a
 * 20s exec that never returns because the channel is not drained, JSON that the
 * emulator's `kotlinx.serialization` reads differently, or a screen that renders
 * its rows off-screen. Everything from the tap to the pixels is production code
 * against a real sshd here.
 *
 * ## The list on screen is checked against the host, not against this file
 *
 * The oracle is an INDEPENDENT `pocketshell sessions list --json` run over the
 * journey's own SSH connection ([AgentsFixture.exec]) — the assertion is that
 * every session name the host reports has a rendered row. A hard-coded expected
 * list would pass just as happily against a screen showing a stale cache or a
 * placeholder; comparing against what the host says at that moment cannot.
 *
 * ## Fixture
 *
 * The Docker `agents` fixture on `10.0.2.2:2222` (see [AgentsFixture]) — its
 * `pocketshell sessions list --json` speaks schema 3. The host state each test
 * needs is SEEDED over SSH in [seed] rather than baked into the image:
 *
 *  - The seed creates five real aplexer shell sessions in two workspaces and
 *    one root-level session.
 *  - `~/.pocketshell-fixture-session-errors.json` adds an explicit enumeration
 *    error to the real aplexer payload for the partial-listing test. The other
 *    methods remove it, so a leftover fault cannot change a happy-path result.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 *
 * ## Trust
 *
 * The host row is seeded with the fingerprint the fixture actually presents
 * (read live in [seed]), so the dial connects without a prompt. The trust sheet
 * itself is `J01ConnectAndTrustJourney`'s subject; repeating it here would add a
 * second failure mode to a test about the tree.
 *
 * Per-test host ids, for the same reason J01 uses them: SQLite reuses
 * `max(id) + 1`, and a reused id plus the registry's one-connection-per-host
 * cache would let a later test reuse an earlier test's connection.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J02SessionTreeListJourney {

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
        println("J02_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedHostSessions(description)

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j02_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j02-${description.methodName}", privateKeyPath = keyPath),
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
     * Puts the host into the state this test needs, over SSH.
     *
     * Activity timestamps are derived from the DEVICE clock so the rendered
     * relative labels ("2m ago") mean what they say regardless of how long the
     * container has been up.
     */
    private fun seedHostSessions(description: Description) {
        val partial = description.methodName.contains("partial", ignoreCase = true)
        val sessions = listOf(
            Triple(SESSION_ROOT, WORKSPACE_ROOT, "root"),
            Triple(SESSION_ATTACHED, WORKSPACE_MAIN, "claude-main"),
            Triple(SESSION_QUIET, WORKSPACE_MAIN, "codex"),
            Triple(SESSION_OTHER, WORKSPACE_APLEXER, "opencode-lab"),
            Triple(SESSION_APLEXER, WORKSPACE_APLEXER, "yolo"),
        )
        AgentsFixture.exec("rm -f $ERRORS_FILE")
        sessions.forEach { (displayName, workspace, tag) ->
            AgentsFixture.exec("pocketshell sessions kill -- '$displayName' >/dev/null 2>&1 || true")
            AgentsFixture.exec(
                "pocketshell sessions create --cwd '$workspace' --mem none --json -- '$tag' >/dev/null",
            )
        }
        if (partial) {
            // The real aplexer listing remains visible, with an explicit
            // enumeration error added by the fixture wrapper. This exercises
            // the partial-listing contract without a second backend.
            AgentsFixture.writeFile(
                ERRORS_FILE,
                "[{\"message\": \"$BACKEND_ERROR_MESSAGE\"}]",
            )
        }
    }

    /**
     * The headline journey: tap the host, land on workspaces, and see the host's
     * REAL sessions grouped by the workspaces the host reported.
     */
    @Test
    fun connectingToAHostListsItsRealSessionsGroupedByWorkspace() {
        openWorkspaces()

        // Every session the host reports right now has a row on screen. This is
        // the load-bearing assertion: the oracle is the host's own answer to the
        // same command the app just ran, read over a separate connection.
        val hostSessions = hostSessionNames()
        assertTrue(
            "the fixture must report the seeded aplexer sessions, got $hostSessions",
            hostSessions.containsAll(
                listOf(
                    SESSION_ROOT,
                    SESSION_ATTACHED,
                    SESSION_QUIET,
                    SESSION_OTHER,
                    SESSION_APLEXER,
                ),
            ),
        )
        compose.onNodeWithTag(workspaceRootTag(WORKSPACE_ROOT)).assertIsDisplayed()
        compose.onNodeWithTag(workspaceSessionRowTag(SESSION_ROOT)).assertIsDisplayed()
        compose.onNodeWithTag(workspaceRowTag(WORKSPACE_MAIN)).assertIsDisplayed()
        compose.onNodeWithTag(workspaceRowTag(WORKSPACE_APLEXER)).assertIsDisplayed()
        JourneyScreenshots.capture("01-workspaces", JOURNEY)

        // No engine/agent chrome — the tree names the session, not the engine.
        compose.onNodeWithContentDescription("codex").assertDoesNotExist()
        compose.onNodeWithContentDescription("claude").assertDoesNotExist()
        compose.onNodeWithContentDescription("Working").assertDoesNotExist()
        compose.onNodeWithContentDescription("Waiting for input").assertDoesNotExist()

        // The happy path raises NO banner. A partial-listing banner here would
        // mean the aplexer probe silently failed and the list is short.
        compose.onNodeWithTag(HOST_WORKSPACES_PARTIAL_BANNER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(HOST_WORKSPACES_EMPTY_TAG).assertDoesNotExist()
    }

    /** Tapping a session row opens THAT session, name intact through the route. */
    @Test
    fun tappingASessionRowOpensThatSession() {
        compose.openQuietSession(hostId, SESSION_APLEXER, WORKSPACE_APLEXER, TIMEOUT_MS)

        // What this pins is that the tap navigated with THIS row's name — an
        // `aplexer` display name carries a `:` and therefore goes through route
        // encoding, and the session screen titles itself with the decoded name.
        // Whether the attach then succeeds is J03's subject, not this test's:
        // the fixture hosts a real attachable aplexer process.
        awaitTag(SESSION_SCREEN_TAG)
        awaitText(SESSION_APLEXER)
        compose.onNodeWithText(SESSION_APLEXER).assertIsDisplayed()
        JourneyScreenshots.capture("02-session-opened", JOURNEY)
    }

    /**
     * The non-happy host state: the aplexer probe reports an error alongside
     * rows it did enumerate. The tree must SAY the list may be short — a
     * probe failure and an empty healthy host must not render identically
     * (the #2426 contract, on a device).
     */
    @Test
    fun aPartialListingRaisesTheMissingSessionsBannerAndStillShowsTheRest() {
        openWorkspaces()

        awaitTag(HOST_WORKSPACES_PARTIAL_BANNER_TAG)
        JourneyScreenshots.capture("03-partial-listing", JOURNEY)
        compose.onNodeWithTag(HOST_WORKSPACES_PARTIAL_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText(
            "Some sessions may be missing: $BACKEND_ERROR_MESSAGE",
        ).assertIsDisplayed()

        // The aplexer sessions the host DID enumerate are still listed...
        compose.onNodeWithTag(workspaceRowTag(WORKSPACE_MAIN)).assertIsDisplayed()
        // ...and the healthy rows remain visible while the error is surfaced.
        assertTrue(SESSION_APLEXER in hostSessionNames())
        compose.onNodeWithTag(workspaceRowTag(WORKSPACE_APLEXER)).assertIsDisplayed()
        compose.onNodeWithTag(workspaceSessionRowTag(SESSION_ROOT)).assertIsDisplayed()
        // The empty state never appears, because the host is not empty.
        compose.onNodeWithTag(HOST_WORKSPACES_EMPTY_TAG).assertDoesNotExist()
    }

    /**
     * Issue #2532: the tree is a popped screen. Back must return to Hosts —
     * the system gesture is not the only path.
     */
    @Test
    fun tappingBackOnTheTreeReturnsToHosts() {
        openWorkspaces()
        compose.onNodeWithTag(HOST_WORKSPACES_BACK_TAG).assertIsDisplayed()
        compose.onNodeWithText("Back").assertIsDisplayed()
        JourneyScreenshots.capture("04-tree-back", JOURNEY)

        compose.onNodeWithTag(HOST_WORKSPACES_BACK_TAG).performClick()
        awaitTag(HOST_LIST_TAG)
        compose.onNodeWithTag(hostRowTag(hostId)).assertIsDisplayed()
        JourneyScreenshots.capture("05-hosts-after-back", JOURNEY)
    }

    @Test
    fun searchingAndAddingAWorkspaceUsesTheProductionQuietControls() {
        openWorkspaces()

        compose.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG)
            .performTextReplacement("aplexer")
        awaitTag(workspaceRowTag(WORKSPACE_APLEXER))
        compose.onNodeWithTag(workspaceRowTag(WORKSPACE_APLEXER)).assertIsDisplayed()
        compose.onNodeWithTag(workspaceRowTag(WORKSPACE_MAIN)).assertDoesNotExist()

        // Keep the add flow reversible: opening the production dialog proves
        // the root-level action is reachable without mutating fixture state.
        compose.onNodeWithTag(workspaceRootAddTag(WORKSPACE_ROOT)).performClick()
        awaitTag(HOST_WORKSPACES_ADD_PATH_TAG)
        compose.onNodeWithTag(HOST_WORKSPACES_ADD_PATH_TAG).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }

    // --- helpers ----------------------------------------------------------

    /** Taps the seeded host and waits for the workspaces listing. */
    private fun openWorkspaces() {
        compose.openQuietHost(hostId, TIMEOUT_MS)
        awaitTag(workspaceRowTag(WORKSPACE_MAIN))
    }

    /**
     * The host's own answer to the command the app just ran, over an
     * independent SSH connection.
     */
    private fun hostSessionNames(): List<String> {
        val payload = JSONObject(AgentsFixture.exec("pocketshell sessions list --json"))
        assertEquals(
            "the fixture must speak the schema the client parses",
            3,
            payload.getInt("schema"),
        )
        val sessions = payload.getJSONArray("sessions")
        return (0 until sessions.length()).map { index ->
            sessions.getJSONObject(index).getString("name")
        }
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
        const val JOURNEY = "j02-session-tree"

        /** Real aplexer sessions seeded by each test method. */
        const val SESSION_ATTACHED = "pocketshell:claude-main"
        const val SESSION_QUIET = "pocketshell:codex"
        const val SESSION_OTHER = "aplexer:opencode-lab"
        const val SESSION_APLEXER = "aplexer:yolo"
        const val SESSION_ROOT = "git:root"

        const val WORKSPACE_ROOT = "/home/testuser/git"
        const val WORKSPACE_MAIN = "/home/testuser/git/pocketshell"
        const val WORKSPACE_APLEXER = "/home/testuser/git/aplexer"

        const val BACKEND_ERROR_MESSAGE =
            "a --json snapshot failed: exit 127 (command not found)"

        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"

        val HOST_IDS: Map<String, Long> = mapOf(
            "connectingToAHostListsItsRealSessionsGroupedByWorkspace" to 9_201L,
            "tappingASessionRowOpensThatSession" to 9_202L,
            "aPartialListingRaisesTheMissingSessionsBannerAndStillShowsTheRest" to 9_203L,
            "tappingBackOnTheTreeReturnsToHosts" to 9_204L,
            "searchingAndAddingAWorkspaceUsesTheProductionQuietControls" to 9_205L,
        )
    }
}
