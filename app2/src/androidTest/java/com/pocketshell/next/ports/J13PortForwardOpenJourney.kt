package com.pocketshell.next.ports

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.tree.SESSION_TREE_PORTS_TAG
import com.pocketshell.next.tree.SESSION_TREE_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J13 — open the port-forward panel from the session-tree header
 * (rewrite task P-4 / issue #2505).
 *
 * ## Why this has to be a device journey
 *
 * `AppNavHostTest` already proves `Destination.Ports.route` resolves when a
 * test navigates programmatically. That is not the missing coverage: until this
 * journey, nothing in the shipping UI called `navController.navigate` for
 * Ports, so a user could not reach the fully-built `PortForwardScreen` at all.
 * A Robolectric screen test can prove the header action fires a callback; only
 * this connected path proves the tap actually lands on the production screen
 * inside the real Hilt graph against a real sshd.
 *
 * ## Fixture
 *
 * The Docker `agents` fixture on `10.0.2.2:2222` (see [AgentsFixture]). Bring
 * it up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 *
 * The host row is seeded with the fingerprint the fixture actually presents
 * (read live in [seed]), so the dial connects without a prompt — the trust
 * sheet is `J01ConnectAndTrustJourney`'s subject.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J13PortForwardOpenJourney {

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
        println("J13_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j13_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j13-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_ID
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
     * Tapping Ports on the session tree opens the production port-forward
     * screen (task P-4 accept: a real navigation path exists and is exercised).
     */
    @Test
    fun tappingPortsOnTheSessionTreeOpensThePortForwardScreen() {
        awaitTag(hostRowTag(hostId), "the seeded host row")
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG, "the session tree")
        JourneyScreenshots.capture("01-tree", JOURNEY)

        awaitTag(SESSION_TREE_PORTS_TAG, "the Ports header action")
        compose.onNodeWithTag(SESSION_TREE_PORTS_TAG).performClick()

        awaitTag(FORWARDING_TOGGLE_TAG, "the port-forward Off/On toggle")
        compose.onNodeWithTag(FORWARDING_TOGGLE_TAG).assertIsDisplayed()
        awaitText("Forwarding is off. Turn it on to discover listening ports.")
        compose.onNodeWithText("Forwarding is off. Turn it on to discover listening ports.")
            .assertIsDisplayed()
        JourneyScreenshots.capture("02-ports", JOURNEY)
    }

    private fun awaitTag(tag: String, what: String = tag) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("tag poll: $what")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "$what never appeared within ${TIMEOUT_MS}ms.\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun awaitText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("text poll: $text")
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-text", JOURNEY)
        throw AssertionError(
            "text '$text' never appeared within ${TIMEOUT_MS}ms.\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val JOURNEY = "j13-port-forward-open"
        const val HOST_ID = 9_901L
    }
}
