package com.pocketshell.next.tree

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
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
 * Journey J02 — connect to a host, land on its session tree, and see the
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
 * `pocketshell sessions list --json` speaks schema 2. The host state each test
 * needs is SEEDED over SSH in [seed] rather than baked into the image:
 *
 *  - `~/.pocketshell-fixture-session-detail.json` gives the canned tmux
 *    sessions their workspace / attach / activity / agent-state fields. One
 *    session is deliberately left OUT of it, so the host reports a `null`
 *    workspace and the "other" bucket is exercised on a real device.
 *  - `~/.pocketshell-fixture-aplexer.json` gives the `a` fixture binary an
 *    aplexer session, so the tree lists BOTH managers — the shape the
 *    maintainer's own box has, and the one a tmux-only fixture could never
 *    reproduce.
 *  - `~/.pocketshell-fixture-session-errors.json` makes a backend report an
 *    enumeration failure. Only the partial-listing test seeds it; the others
 *    delete it, because a leftover file would make the happy-path
 *    "no banner" assertion pass or fail for the wrong reason.
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
        val now = System.currentTimeMillis() / 1000
        AgentsFixture.writeFile(
            DETAIL_FILE,
            """
            {
              "$SESSION_ATTACHED": {
                "workspace": "$WORKSPACE_MAIN",
                "attached": true,
                "engine": "claude",
                "agent_state": "working",
                "agent_state_source": "reported",
                "activity_epoch": ${now - 120}
              },
              "$SESSION_QUIET": {
                "workspace": "$WORKSPACE_MAIN",
                "attached": false,
                "activity_epoch": ${now - 7200}
              }
            }
            """.trimIndent(),
        )
        // NOTE: no entry for `opencode-lab` — the host therefore reports it with
        // a null workspace, which is the "other" bucket under test.

        if (partial) {
            // The REAL shape of a backend that failed to enumerate: it
            // contributes NO rows and reports why. Seeding the error while
            // leaving its sessions in place would have been the easy version and
            // a weaker test — the whole point is that "aplexer produced nothing"
            // must not read as "aplexer has nothing".
            AgentsFixture.exec("rm -f $APLEXER_FILE")
            AgentsFixture.writeFile(
                ERRORS_FILE,
                """[{"manager": "aplexer", "message": "$BACKEND_ERROR_MESSAGE"}]""",
            )
            return
        }

        AgentsFixture.exec("rm -f $ERRORS_FILE")
        AgentsFixture.writeFile(
            APLEXER_FILE,
            """
            {
              "sessions": [
                {
                  "name": "$SESSION_APLEXER",
                  "id": "52a2508e-c902-4bd6-9ea8-dd3668381749",
                  "workspace": "$WORKSPACE_APLEXER",
                  "tag": "yolo",
                  "engine": "codex",
                  "profile": null,
                  "agent_state": "waiting",
                  "agent_state_source": "heuristic",
                  "attached": false,
                  "created_epoch": ${now - 86400},
                  "activity_epoch": ${now - 60}
                }
              ]
            }
            """.trimIndent(),
        )
    }

    /**
     * The headline journey: tap the host, land on the tree, and see the host's
     * REAL sessions grouped by the workspaces the host reported.
     */
    @Test
    fun connectingToAHostListsItsRealSessionsGroupedByWorkspace() {
        openTree()

        // Every session the host reports right now has a row on screen. This is
        // the load-bearing assertion: the oracle is the host's own answer to the
        // same command the app just ran, read over a separate connection.
        val hostSessions = hostSessionNames()
        assertTrue(
            "the fixture must report at least the seeded tmux + aplexer sessions, got $hostSessions",
            hostSessions.containsAll(
                listOf(SESSION_ATTACHED, SESSION_QUIET, SESSION_OTHER, SESSION_APLEXER),
            ),
        )
        hostSessions.forEach { name ->
            compose.onNodeWithTag(sessionRowTag(name))
                .assertIsDisplayed()
        }
        JourneyScreenshots.capture("01-session-tree", JOURNEY)

        // Grouped by the workspace the host reported — including the "other"
        // bucket for the session it reported with none.
        compose.onNodeWithTag(workspaceHeaderTag(WORKSPACE_MAIN)).assertIsDisplayed()
        compose.onNodeWithTag(workspaceHeaderTag(WORKSPACE_APLEXER)).assertIsDisplayed()
        compose.onNodeWithTag(workspaceHeaderTag(OTHER_WORKSPACE_LABEL)).assertIsDisplayed()

        // The aplexer row carries its engine badge, so BOTH managers are really
        // rendered rather than the tmux half only.
        compose.onNodeWithContentDescription("codex").assertIsDisplayed()
        // And the reported agent state became a chip.
        compose.onNodeWithContentDescription("Working").assertIsDisplayed()

        // The happy path raises NO banner. A partial-listing banner here would
        // mean a backend silently failed and the list is short.
        compose.onNodeWithTag(SESSION_TREE_PARTIAL_BANNER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_TREE_ERROR_BANNER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_TREE_EMPTY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_TREE_LOADING_TAG).assertDoesNotExist()
    }

    /** Tapping a session row opens THAT session, name intact through the route. */
    @Test
    fun tappingASessionRowOpensThatSession() {
        openTree()
        awaitTag(sessionRowTag(SESSION_APLEXER))

        compose.onNodeWithTag(sessionRowTag(SESSION_APLEXER)).performClick()

        // What this pins is that the tap navigated with THIS row's name — an
        // `aplexer` display name carries a `:` and therefore goes through route
        // encoding, and the session screen titles itself with the decoded name.
        // Whether the attach then succeeds is J03's subject, not this test's:
        // the `a` fixture hosts no attachable process, so this screen is
        // expected to end up saying so.
        awaitTag(SESSION_SCREEN_TAG)
        awaitText(SESSION_APLEXER)
        compose.onNodeWithText(SESSION_APLEXER).assertIsDisplayed()
        JourneyScreenshots.capture("02-session-opened", JOURNEY)
    }

    /**
     * The non-happy host state: one backend fails to enumerate. The tree must
     * SAY the list is short and still show what did arrive — "aplexer is
     * broken" and "aplexer has no sessions" must not render identically
     * (the #2426 contract, on a device).
     */
    @Test
    fun aPartialListingRaisesTheMissingSessionsBannerAndStillShowsTheRest() {
        openTree()

        awaitTag(SESSION_TREE_PARTIAL_BANNER_TAG)
        JourneyScreenshots.capture("03-partial-listing", JOURNEY)
        compose.onNodeWithTag(SESSION_TREE_PARTIAL_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("Some sessions may be missing: aplexer").assertIsDisplayed()

        // The tmux sessions the host DID enumerate are still listed...
        compose.onNodeWithTag(sessionRowTag(SESSION_ATTACHED)).assertIsDisplayed()
        compose.onNodeWithTag(sessionRowTag(SESSION_QUIET)).assertIsDisplayed()
        // ...the failed backend really did contribute nothing (this is what the
        // banner exists to explain — without it this absence is invisible)...
        assertTrue(
            "the failed backend must contribute no rows",
            SESSION_APLEXER !in hostSessionNames(),
        )
        compose.onNodeWithTag(sessionRowTag(SESSION_APLEXER)).assertDoesNotExist()
        // ...and the empty state never appears, because the host is not empty.
        compose.onNodeWithTag(SESSION_TREE_EMPTY_TAG).assertDoesNotExist()
        // A partial listing is not a hard failure either.
        compose.onNodeWithTag(SESSION_TREE_ERROR_BANNER_TAG).assertDoesNotExist()
    }

    // --- helpers ----------------------------------------------------------

    /** Taps the seeded host and waits for the tree's first real listing. */
    private fun openTree() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()

        awaitTag(SESSION_TREE_TAG)
        // The screen exists immediately; wait for the listing to land before
        // asserting on rows, so a slow exec is a wait rather than a false red.
        awaitTag(sessionRowTag(SESSION_ATTACHED))
    }

    /**
     * The host's own answer to the command the app just ran, over an
     * independent SSH connection.
     */
    private fun hostSessionNames(): List<String> {
        val payload = JSONObject(AgentsFixture.exec("pocketshell sessions list --json"))
        assertEquals(
            "the fixture must speak the schema the client parses",
            2,
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

        /** Canned tmux sessions the `agents` image always reports. */
        const val SESSION_ATTACHED = "claude-main"
        const val SESSION_QUIET = "codex"
        const val SESSION_OTHER = "opencode-lab"
        const val SESSION_APLEXER = "aplexer-follow:yolo"

        const val WORKSPACE_MAIN = "/home/testuser/git/pocketshell"
        const val WORKSPACE_APLEXER = "/home/testuser/git/aplexer"

        const val BACKEND_ERROR_MESSAGE =
            "a --json snapshot failed: exit 127 (command not found)"

        const val DETAIL_FILE = "\$HOME/.pocketshell-fixture-session-detail.json"
        const val APLEXER_FILE = "\$HOME/.pocketshell-fixture-aplexer.json"
        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"

        val HOST_IDS: Map<String, Long> = mapOf(
            "connectingToAHostListsItsRealSessionsGroupedByWorkspace" to 9_201L,
            "tappingASessionRowOpensThatSession" to 9_202L,
            "aPartialListingRaisesTheMissingSessionsBannerAndStillShowsTheRest" to 9_203L,
        )
    }
}
