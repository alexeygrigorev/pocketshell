package com.pocketshell.next.tree

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.pocketshell.next.terminal.SESSION_HEADER_KEBAB_TAG
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.terminal.SESSION_TITLE_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first

/**
 * Journey J14 — stop a throwaway session from the tree (and from the session
 * screen) and prove the HOST no longer lists it (issue #2535).
 *
 * ## Why this has to be a device journey
 *
 * The Stop path is kebab → confirm → `pocketshell sessions kill` → refresh.
 * A ViewModel over a scripted connection cannot see a fixture CLI that does
 * not have the verb, a kill that hits `claude-main` because the name was a
 * prefix, or a session screen that stays on a dead PTY. The oracle after
 * Stop is an independent `pocketshell sessions list --json` over SSH.
 *
 * ## Do not kill fixture sessions you did not create
 *
 * `claude-main` is the canned session every tree journey lands on. This
 * class creates a throwaway name, stops THAT, and asserts `claude-main` is
 * still on the host.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J14StopSessionJourney {

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
        println("J14_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedHostState(description)

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j14_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j14-${description.methodName}", privateKeyPath = keyPath),
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

    private fun seedHostState(description: Description) {
        AgentsFixture.exec("rm -f $ERRORS_FILE $APLEXER_FILE $DETAIL_FILE")
        val name = THROWAWAY_BY_TEST.getValue(description.methodName)
        cleanupThrowaway(name)
        AgentsFixture.exec("pocketshell sessions create --json -- '$name'")
    }

    @Test
    fun stoppingAThrowawaySessionFromTheTreeRemovesItFromTheHost() {
        openTree()
        assertTrue(
            "the throwaway must exist before Stop",
            SESSION_TREE in hostSessionNames(),
        )
        assertTrue(
            "claude-main is a fixture session this journey must not kill",
            CANNED_SESSION in hostSessionNames(),
        )
        awaitTag(sessionRowTag(SESSION_TREE))

        compose.onNodeWithTag(sessionRowMenuTag(SESSION_TREE)).performClick()
        compose.onNodeWithTag(STOP_SESSION_ITEM_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(STOP_SESSION_ITEM_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithText(STOP_SESSION_TITLE).assertIsDisplayed()
        compose.onNodeWithText(stopSessionMessage(SESSION_TREE)).assertIsDisplayed()
        JourneyScreenshots.capture("01-stop-confirm", JOURNEY)

        compose.onNodeWithTag(STOP_SESSION_CONFIRM_TAG).performClick()

        awaitGone(sessionRowTag(SESSION_TREE))
        compose.onNodeWithTag(sessionRowTag(CANNED_SESSION)).assertIsDisplayed()
        JourneyScreenshots.capture("02-tree-after-stop", JOURNEY)

        val names = hostSessionNames()
        assertFalse("the host must no longer list the stopped session, got $names", SESSION_TREE in names)
        assertTrue("stopping the throwaway must not kill $CANNED_SESSION, got $names", CANNED_SESSION in names)
    }

    @Test
    fun cancellingStopLeavesTheSessionAlive() {
        openTree()
        awaitTag(sessionRowTag(SESSION_CANCEL))

        compose.onNodeWithTag(sessionRowMenuTag(SESSION_CANCEL)).performClick()
        compose.onNodeWithTag(STOP_SESSION_ITEM_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(STOP_SESSION_CANCEL_TAG).performClick()

        compose.onNodeWithTag(sessionRowTag(SESSION_CANCEL)).assertIsDisplayed()
        compose.onNodeWithText(STOP_SESSION_TITLE).assertDoesNotExist()
        JourneyScreenshots.capture("03-cancel-alive", JOURNEY)

        assertTrue(
            "Cancel must leave the session on the host",
            SESSION_CANCEL in hostSessionNames(),
        )
        assertTrue(CANNED_SESSION in hostSessionNames())
    }

    @Test
    fun stoppingTheAttachedSessionReturnsToTheTree() {
        openTree()
        awaitTag(sessionRowTag(SESSION_ATTACHED))
        compose.onNodeWithTag(sessionRowTag(SESSION_ATTACHED)).performClick()
        awaitSessionScreen(SESSION_ATTACHED)

        compose.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).performClick()
        compose.onNodeWithTag(STOP_SESSION_ITEM_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithText(STOP_SESSION_TITLE).assertIsDisplayed()
        compose.onNodeWithTag(STOP_SESSION_CONFIRM_TAG).performClick()

        awaitTag(SESSION_TREE_TAG)
        awaitGone(SESSION_SCREEN_TAG)
        awaitGone(sessionRowTag(SESSION_ATTACHED))
        compose.onNodeWithTag(sessionRowTag(CANNED_SESSION)).assertIsDisplayed()
        JourneyScreenshots.capture("04-popped-after-stop", JOURNEY)

        val names = hostSessionNames()
        assertFalse(SESSION_ATTACHED in names)
        assertTrue(CANNED_SESSION in names)
    }

    private fun openTree() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)
        awaitTag(sessionRowTag(CANNED_SESSION))
    }

    private fun hostSessionNames(): List<String> {
        val payload = JSONObject(AgentsFixture.exec("pocketshell sessions list --json"))
        val sessions = payload.getJSONArray("sessions")
        return (0 until sessions.length()).map { index ->
            sessions.getJSONObject(index).getString("name")
        }
    }

    private fun cleanupThrowaway(name: String) {
        AgentsFixture.exec(
            "socket=\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$name\"; " +
                "tmux -S \"\$socket\" kill-session -t \"=$name\" >/dev/null 2>&1; " +
                "rm -f \"\$socket\"; true",
        )
    }

    private fun awaitSessionScreen(name: String) {
        awaitTag(SESSION_SCREEN_TAG)
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

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitGone(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val JOURNEY = "j14-stop-session"

        const val CANNED_SESSION = "claude-main"
        const val SESSION_TREE = "j14-stop-tree"
        const val SESSION_CANCEL = "j14-stop-cancel"
        const val SESSION_ATTACHED = "j14-stop-attached"

        const val DETAIL_FILE = "\$HOME/.pocketshell-fixture-session-detail.json"
        const val APLEXER_FILE = "\$HOME/.pocketshell-fixture-aplexer.json"
        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"

        val HOST_IDS: Map<String, Long> = mapOf(
            "stoppingAThrowawaySessionFromTheTreeRemovesItFromTheHost" to 9_141L,
            "cancellingStopLeavesTheSessionAlive" to 9_142L,
            "stoppingTheAttachedSessionReturnsToTheTree" to 9_143L,
        )

        val THROWAWAY_BY_TEST: Map<String, String> = mapOf(
            "stoppingAThrowawaySessionFromTheTreeRemovesItFromTheHost" to SESSION_TREE,
            "cancellingStopLeavesTheSessionAlive" to SESSION_CANCEL,
            "stoppingTheAttachedSessionReturnsToTheTree" to SESSION_ATTACHED,
        )
    }
}
