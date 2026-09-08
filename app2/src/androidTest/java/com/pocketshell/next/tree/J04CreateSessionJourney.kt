package com.pocketshell.next.tree

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.openQuietHost
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.terminal.SESSION_TITLE_TAG
import com.pocketshell.next.workspaces.WORKSPACE_ERROR_TAG
import com.pocketshell.next.workspaces.WORKSPACE_CREATE_NOTICE_TAG
import com.pocketshell.next.workspaces.WORKSPACE_NEW_SESSION_TAG
import com.pocketshell.next.workspaces.WORKSPACE_SCREEN_TAG
import com.pocketshell.next.workspaces.workspaceRowTag
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

/**
 * Journey J04 — create a session from a workspace and land in it
 * (rewrite task U-6).
 *
 * ## Why this has to be a device journey
 *
 * `SessionTreeViewModelTest` drives the same create over a scripted connection
 * and cannot see any of what breaks here: `pocketshell sessions create --json`
 * behaving differently from its fixtures, a `--cwd` the host quotes into
 * something else, a 60s exec that never returns, a sheet whose text field the
 * IME cannot fill, a Create button under the keyboard, or a navigation that
 * fires before the session exists. Everything from the FAB tap to the session
 * route is production code against a real sshd here.
 *
 * ## The oracle is the host, not this file
 *
 * After the create, the assertions are made against an INDEPENDENT
 * `pocketshell sessions list --json` over the journey's own SSH connection
 * ([AgentsFixture.exec]) plus the aplexer row's own `workspace` — so
 * "the session exists" and "it was created in the folder that was typed" are
 * answered by the host, not by the screen that claimed it. A screen-only
 * assertion would pass just as happily against a client that navigated to a
 * session it never created.
 *
 * ## The idempotent case is half the journey
 *
 * The host CLI's create is idempotent: a name that already exists comes back
 * `created:false`, which is a SUCCESS ([com.pocketshell.core.hostapi.CreatedSession]).
 * [creatingTheSameNameTwiceOpensTheExistingSessionWithoutAnError] runs the
 * whole flow twice against the same name and asserts the second run still opens
 * the session, still leaves the host with ONE session by that name, and reports
 * it as a notice rather than a failure.
 *
 * ## Fixture
 *
 * The Docker `agents` fixture (see [AgentsFixture]) — its `pocketshell sessions
 * create` arm delegates to the repository's REAL host implementation and
 * creates a real detached aplexer session, so the same `sessions list --json`
 * the tree reads then enumerates it for real.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J04CreateSessionJourney {

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
        println("J04_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedHostState()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j04_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j04-${description.methodName}", privateKeyPath = keyPath),
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
     * Puts the host into the state this journey needs, over SSH: the target
     * folders exist, and NO session from a previous run is left behind (a
     * leftover would make the "created it" test assert an idempotent open
     * instead, silently testing the wrong half).
     */
    private fun seedHostState() {
        AgentsFixture.exec("rm -f $ERRORS_FILE")
        AgentsFixture.exec("mkdir -p $FOLDER_NEW $FOLDER_TWICE")
        killSession(SESSION_NEW)
        killSession(SESSION_TWICE)
        AgentsFixture.exec("pocketshell sessions kill -- '$CANNED_SESSION' >/dev/null 2>&1 || true")
        AgentsFixture.exec(
            "pocketshell sessions create --cwd /home/testuser/git/pocketshell " +
                "--mem none --json -- claude-main >/dev/null",
        )
    }

    /**
     * The headline journey: workspace → New session → folder → Create → land
     * in the new session, which the HOST agrees exists in the typed folder.
     */
    @Test
    fun creatingASessionFromTheTreeLandsOnItAndItAppearsOnTheTree() {
        openWorkspace()
        assertTrue(
            "the session under test must not exist before the journey creates it",
            SESSION_NEW !in hostSessionNames(),
        )

        compose.onNodeWithTag(WORKSPACE_NEW_SESSION_TAG).performClick()
        awaitTag(CREATE_SESSION_SHEET_TAG)
        JourneyScreenshots.capture("01-create-sheet", JOURNEY)

        compose.onNodeWithTag(CREATE_SESSION_FOLDER_TAG).performTextReplacement(FOLDER_NEW)
        compose.onNodeWithTag(CREATE_SESSION_NAME_TAG).performTextReplacement(SESSION_NEW_TAG)
        // The name follows the folder, so the common case is one tap. This is
        // the on-device half of `defaultSessionName` — an IME that delivered the
        // text differently would break it here and nowhere else.
        compose.onNodeWithTag(CREATE_SESSION_NAME_TAG).assertTextContains(SESSION_NEW_TAG)
        // Settle the IME animation before the evidence shot: the sheet's
        // `imePadding` lifts the action row over the keyboard, and a frame
        // grabbed mid-animation would show the Create button half-covered and
        // look like the bug it exists to prevent.
        SystemClock.sleep(IME_SETTLE_MS)
        compose.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).assertIsDisplayed()
        assertCreateButtonClearsTheKeyboard()
        JourneyScreenshots.capture("02-create-filled", JOURNEY)

        compose.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()

        // Landed IN the new session — on the REAL terminal screen (U-4), whose
        // header title is the session identity the route carried.
        awaitSessionScreen(SESSION_NEW)
        JourneyScreenshots.capture("03-session-opened", JOURNEY)

        // The HOST's own answer: the session is really there, and it was really
        // created in the folder that was typed.
        assertTrue(
            "the host must report the created session, got ${hostSessionNames()}",
            SESSION_NEW in hostSessionNames(),
        )
        assertEquals(
            "--cwd must reach the host, not just the screen",
            FOLDER_NEW,
            hostSessionPath(SESSION_NEW),
        )

        // ...and the tree the user comes back to lists it.
        pressBack()
        awaitTag(sessionRowTag(SESSION_NEW))
        compose.onNodeWithTag(sessionRowTag(SESSION_NEW)).assertIsDisplayed()
        compose.onNodeWithTag(WORKSPACE_ERROR_TAG).assertDoesNotExist()
        JourneyScreenshots.capture("04-tree-after-create", JOURNEY)
    }

    /**
     * The idempotency contract on a device: creating the SAME name twice is not
     * an error, it opens the session that is already there.
     */
    @Test
    fun creatingTheSameNameTwiceOpensTheExistingSessionWithoutAnError() {
        openWorkspace()

        createFromSheet(FOLDER_TWICE)
        awaitSessionScreen(SESSION_TWICE)
        pressBack()
        awaitTag(WORKSPACE_SCREEN_TAG)
        assertEquals(
            "the first create must have made exactly one session",
            1,
            hostSessionNames().count { it == SESSION_TWICE },
        )

        // Exactly the same folder, so exactly the same derived name.
        createFromSheet(FOLDER_TWICE)

        // The second create remains on the workspace. `created:false` must not
        // silently resume an existing session; the user chooses its row.
        awaitTag(WORKSPACE_SCREEN_TAG)
        awaitTag(WORKSPACE_CREATE_NOTICE_TAG)
        compose.onNodeWithTag(SESSION_SCREEN_TAG).assertDoesNotExist()
        assertEquals(
            "an idempotent create must not duplicate the session",
            1,
            hostSessionNames().count { it == SESSION_TWICE },
        )

        // ...and it is reported as a notice, never as a failure.
        compose.onNodeWithTag(WORKSPACE_CREATE_NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithText(
            "Session \"$SESSION_TWICE\" already exists — choose it from the list to open it.",
        ).assertIsDisplayed()
        compose.onNodeWithTag(sessionRowTag(SESSION_TWICE)).performClick()
        awaitSessionScreen(SESSION_TWICE)
        JourneyScreenshots.capture("05-existing-session-opened", JOURNEY)
        pressBack()
        awaitTag(WORKSPACE_SCREEN_TAG)
        compose.onNodeWithTag(WORKSPACE_ERROR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CREATE_SESSION_ERROR_TAG).assertDoesNotExist()
        JourneyScreenshots.capture("06-already-existed-notice", JOURNEY)
    }

    // --- helpers ----------------------------------------------------------

    /**
     * The Create button is fully ABOVE the on-screen keyboard.
     *
     * The sheet is two text fields, so the keyboard is up whenever a user is
     * looking at it — and a modal sheet opens at its PARTIAL detent by default,
     * which puts the action row under the IME with no way to reach it. That is
     * what `skipPartiallyExpanded` + `imePadding` fix, and it is invisible to
     * every JVM test (there is no IME there), so the guard lives here: the
     * button's bottom edge in window coordinates must clear the keyboard's top.
     */
    private fun assertCreateButtonClearsTheKeyboard() {
        val decorView = compose.activity.window.decorView
        val imeHeight = compose.runOnUiThread {
            ViewCompat.getRootWindowInsets(decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: 0
        }
        assertTrue(
            "the keyboard must be up, or this assertion proves nothing",
            imeHeight > 0,
        )
        val keyboardTop = decorView.height - imeHeight
        val button = compose.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG)
            .fetchSemanticsNode()
            .boundsInWindow
        assertTrue(
            "the Create button (bottom=${button.bottom}) is under the keyboard " +
                "(top=$keyboardTop) — the user cannot reach it",
            button.bottom <= keyboardTop.toFloat(),
        )
    }

    /** FAB → type the folder → Create. Leaves the screen mid-navigation. */
    private fun createFromSheet(folder: String) {
        compose.onNodeWithTag(WORKSPACE_NEW_SESSION_TAG).performClick()
        awaitTag(CREATE_SESSION_SHEET_TAG)
        compose.onNodeWithTag(CREATE_SESSION_FOLDER_TAG).performTextReplacement(folder)
        // Keep the idempotent path under the same device-level guarantees as
        // the first-create path. Replacing the folder opens the IME and the
        // sheet's action row moves with it; an immediate semantic click can
        // target a still-disabled/under-IME button without invoking the host
        // command. That leaves the sheet open and makes the failure look like
        // a broken aplexer navigation even though no create was sent.
        val expectedTag = if (folder == FOLDER_TWICE) {
            SESSION_TWICE_TAG
        } else {
            defaultSessionName(folder)
        }
        if (folder == FOLDER_TWICE) {
            compose.onNodeWithTag(CREATE_SESSION_NAME_TAG)
                .performTextReplacement(SESSION_TWICE_TAG)
        }
        compose.onNodeWithTag(CREATE_SESSION_NAME_TAG).assertTextContains(expectedTag)
        SystemClock.sleep(IME_SETTLE_MS)
        compose.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
        assertCreateButtonClearsTheKeyboard()
        compose.onNodeWithTag(CREATE_SESSION_SUBMIT_TAG).performClick()
    }

    /** Opens the seeded workspace and waits for its first real listing. */
    private fun openWorkspace() {
        compose.openQuietHost(hostId, TIMEOUT_MS)
        awaitTag(workspaceRowTag(WORKSPACE_MAIN))
        compose.onNodeWithTag(workspaceRowTag(WORKSPACE_MAIN)).performClick()
        awaitTag(WORKSPACE_SCREEN_TAG)
        awaitTag(sessionRowTag(CANNED_SESSION))
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    /**
     * The create landed on the SESSION route, and on the session it just made.
     *
     * The identity check is the header title, not merely "a session screen is
     * up": that is the assertion that fails if the create navigates to the
     * wrong session (a stale `openRequest`, the typed name instead of the
     * host's answer, a dedup that opens a neighbour). Waiting on
    * [SESSION_SCREEN_TAG] alone would pass for any of those.
     */
    private fun awaitSessionScreen(name: String) {
        try {
            awaitTag(SESSION_SCREEN_TAG)
        } catch (failure: Throwable) {
            // A timeout here used to leave only a generic Compose exception.
            // Preserve the actual route state and host answer while the app is
            // still on screen, so a real aplexer/create regression is distinct
            // from a navigation or test-identity mistake.
            listOf(
                CREATE_SESSION_SHEET_TAG,
                CREATE_SESSION_SUBMIT_TAG,
                CREATE_SESSION_ERROR_TAG,
                WORKSPACE_SCREEN_TAG,
                WORKSPACE_ERROR_TAG,
                WORKSPACE_CREATE_NOTICE_TAG,
                SESSION_SCREEN_TAG,
                SESSION_TITLE_TAG,
            ).forEach { tag ->
                val nodes = compose.onAllNodesWithTag(
                    tag,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                println("J04_SESSION_TIMEOUT_TAG $tag count=${nodes.size}")
                nodes.forEach { node ->
                    println(
                        "J04_SESSION_TIMEOUT_NODE $tag text=" +
                            node.config.getOrNull(SemanticsProperties.Text) +
                            " disabled=" +
                            node.config.contains(SemanticsProperties.Disabled) +
                            " contentDescription=" +
                            node.config.getOrNull(SemanticsProperties.ContentDescription),
                    )
                }
            }
            println("J04_SESSION_TIMEOUT_HOST ${AgentsFixture.exec("pocketshell sessions list --json")}")
            JourneyScreenshots.capture("failure-session-screen", JOURNEY)
            throw failure
        }
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(SESSION_TITLE_TAG)
                .fetchSemanticsNodes()
                .any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.any { it.text == name } == true
                }
        }
        compose.onNodeWithTag(SESSION_SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_TITLE_TAG).assertTextEquals(name)
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

    /** The aplexer row's workspace for [name], read on the host. */
    private fun hostSessionPath(name: String): String {
        val payload = JSONObject(AgentsFixture.exec("pocketshell sessions list --json"))
        val sessions = payload.getJSONArray("sessions")
        return (0 until sessions.length())
            .map { sessions.getJSONObject(it) }
            .first { it.getString("name") == name }
            .getString("workspace")
    }

    /** Removes a session left behind by an earlier run. */
    private fun killSession(name: String) {
        AgentsFixture.exec("pocketshell sessions kill -- '$name' >/dev/null 2>&1 || true")
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L

        /** Long enough for the IME show animation to finish before a screenshot. */
        const val IME_SETTLE_MS = 1_500L
        const val JOURNEY = "j04-create-session"

        /** A real aplexer session the image/journey reports, so the tree has landed. */
        const val CANNED_SESSION = "pocketshell:claude-main"
        const val WORKSPACE_MAIN = "/home/testuser/git/pocketshell"

        const val FOLDER_NEW = "/home/testuser/git/pocketshell"
        // The app submits the explicit tag after `--`; the host returns the
        // stable aplexer display identity `<workspace-basename>:<tag>`. Keep
        // both values explicit because the form shows the tag while the tree
        // and attach route use the host identity.
        const val SESSION_NEW_TAG = "j04-new"
        const val SESSION_NEW = "pocketshell:j04-new"
        const val FOLDER_TWICE = "/home/testuser/git/pocketshell"
        const val SESSION_TWICE_TAG = "j04-twice"
        const val SESSION_TWICE = "pocketshell:j04-twice"

        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"

        /** Per-test host ids, for the same reason J01/J02 use them. */
        val HOST_IDS: Map<String, Long> = mapOf(
            "creatingASessionFromTheTreeLandsOnItAndItAppearsOnTheTree" to 9_401L,
            "creatingTheSameNameTwiceOpensTheExistingSessionWithoutAnError" to 9_402L,
        )
    }
}
