package com.pocketshell.next.ports

import android.app.ActivityManager
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.connect.openQuietHost
import com.pocketshell.next.tree.SESSION_TREE_PORTS_TAG
import com.pocketshell.next.tree.SESSION_TREE_USAGE_TAG
import com.pocketshell.next.usage.USAGE_PROVIDER_LIST_TAG
import com.pocketshell.next.usage.USAGE_SCREEN_TAG
import com.pocketshell.next.usage.usageProviderRowTag
import kotlinx.coroutines.runBlocking
import com.pocketshell.next.workspaces.HOST_WORKSPACES_ACTIONS_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J13 — open the Services & tunnels screen from the session-tree header
 * (issue #2611).
 *
 * ## Why this has to be a device journey
 *
 * `AppNavHostTest` already proves `Destination.Ports.route` resolves when a
 * test navigates programmatically. This connected path proves the tap reaches
 * the host-scoped production screen inside the real Hilt graph against a real
 * sshd.
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
        val phase = phase()
        if (phase == PHASE_RESUME || phase == PHASE_REMOVED) {
            check(graph.hostDao().getById(HOST_ID) != null) {
                "J13 $phase phase requires the persisted host from the setup phase"
            }
        } else {
            graph.hostDao().getAll().first().forEach {
                graph.portRemappingDao().deleteByHostId(it.id)
                graph.hostDao().deleteById(it.id)
            }
            graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }
        }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J13_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")
        AgentsFixture.exec(
            "printf 'POCKETSHELL_J13_HTTP_2611\\n' > /tmp/j13-body.txt",
        )
        AgentsFixture.exec(
            "nohup python3 -m http.server $DISCOVERED_PORT --directory /tmp " +
                ">/tmp/pocketshell-j13-http.log 2>&1 </dev/null &",
        )

        hostId = HOST_ID
        if (phase != PHASE_RESUME && phase != PHASE_REMOVED) {
            val keyPath = AgentsFixture.installPrivateKey(fileName = "j13_fixture_key")
            val keyId = graph.sshKeyDao().insert(
                SshKeyEntity(name = "j13-${description.methodName}", privateKeyPath = keyPath),
            )
            graph.hostDao().insert(
                HostEntity(
                    id = hostId,
                    name = "docker-fixture",
                    hostname = AgentsFixture.host,
                    port = AgentsFixture.port,
                    username = AgentsFixture.USER,
                    keyId = keyId,
                    maxAutoPort = 20_000,
                    skipPortsBelow = 6_000,
                    trustedHostKeyAlgorithm = "SHA256",
                    trustedHostKeySha256 = fingerprint,
                ),
            )
        }
    }

    /**
     * Tapping Ports on the session tree opens the production Services & tunnels
     * screen (issue #2611 acceptance: a real navigation path is exercised).
     */
    @Test
    fun tappingPortsOnTheSessionTreeOpensThePortForwardScreen() {
        when (phase()) {
            PHASE_SETUP -> runSetupPhase()
            PHASE_RESUME -> runResumePhase()
            PHASE_REMOVED -> runRemovedPhase()
            else -> runFullPhase()
        }
    }

    /** The first phase leaves the durable manual mapping for the host script. */
    private fun runSetupPhase() {
        openServices()
        awaitText("No active tunnels")
        compose.onNodeWithText("No active tunnels").assertIsDisplayed()
        JourneyScreenshots.capture("02-services-off", JOURNEY)
        enableDiscoveryAndOpenAddTunnel()
        addManualTunnel()
        awaitForwardingAndHttp("05-tunnel-active-before-process-death")
    }

    /** The second phase starts only after the host script force-stops/relaunches the app. */
    private fun runResumePhase() {
        openServices()
        awaitMappingPresent(DISCOVERED_PORT, LOCAL_PORT)
        awaitForwardingAndHttp("06-process-death-remounted")
        removeManualTunnel()
        awaitMappingAbsent(DISCOVERED_PORT)
        JourneyScreenshots.capture("07-tunnel-removed", JOURNEY)
    }

    /** The third phase starts after a second external force-stop/relaunch. */
    private fun runRemovedPhase() {
        openServices()
        awaitMappingAbsent(DISCOVERED_PORT)
        awaitForwardService()
        awaitTag("$SERVICES_DISCOVERY_TAG-off", "the enabled discovery control after restart")
        compose.onNodeWithTag("$SERVICES_DISCOVERY_TAG-off").performClick()
        awaitText("No active tunnels")
        compose.onNodeWithTag("$SERVICES_DISCOVERY_TAG-on").performClick()
        awaitTag(servicesRowTag(DISCOVERED_PORT), "the available tunnel after supervisor restart")
        awaitMappingAbsent(DISCOVERED_PORT)
        compose.onNodeWithTag(servicesRowTag(DISCOVERED_PORT))
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Not forwarded", substring = true)
        JourneyScreenshots.capture("08-reopened-available", JOURNEY)
        openUsageFromServices()
    }

    /** The ordinary connected-suite path remains self-contained. */
    private fun runFullPhase() {
        openServices()
        awaitText("No active tunnels")
        compose.onNodeWithText("No active tunnels").assertIsDisplayed()
        JourneyScreenshots.capture("02-services-off", JOURNEY)
        enableDiscoveryAndOpenAddTunnel()
        addManualTunnel()
        awaitForwardingAndHttp("05-tunnel-active")
        removeManualTunnel()
        awaitMappingAbsent(DISCOVERED_PORT)
        JourneyScreenshots.capture("07-tunnel-removed", JOURNEY)

        // The mapping is gone before the screen is mounted again, so the
        // supervisor must expose the discovered service as available rather
        // than silently recreating the user's removed manual tunnel.
        awaitTag("$SERVICES_DISCOVERY_TAG-off", "the enabled discovery control")
        compose.onNodeWithTag("$SERVICES_DISCOVERY_TAG-off").performClick()
        awaitText("No active tunnels")
        compose.onNodeWithTag("$SERVICES_DISCOVERY_TAG-on").performClick()
        awaitTag(servicesRowTag(DISCOVERED_PORT), "the available tunnel after removal")
        awaitMappingAbsent(DISCOVERED_PORT)
        compose.onNodeWithTag(servicesRowTag(DISCOVERED_PORT))
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Not forwarded", substring = true)
        JourneyScreenshots.capture("08-reopened-available", JOURNEY)
        openUsageFromServices()
    }

    private fun openServices() {
        compose.openQuietHost(hostId, TIMEOUT_MS)
        JourneyScreenshots.capture("01-workspaces", JOURNEY)

        awaitTag(HOST_WORKSPACES_ACTIONS_TAG, "the host actions menu")
        compose.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        awaitTag(SESSION_TREE_PORTS_TAG, "the Ports header action")
        compose.onNodeWithTag(SESSION_TREE_PORTS_TAG).performClick()

        awaitTag(SERVICES_SCREEN_TAG, "the Services & tunnels screen")
        awaitTag(SERVICES_DISCOVERY_TAG, "the service discovery control")
        compose.onNodeWithTag(SERVICES_DISCOVERY_TAG).assertIsDisplayed()
    }

    private fun enableDiscoveryAndOpenAddTunnel() {
        compose.onNodeWithTag("$SERVICES_DISCOVERY_TAG-on").performClick()
        awaitTag(servicesRowTag(DISCOVERED_PORT), "the discovered service")
        JourneyScreenshots.capture("03-services-discovered", JOURNEY)
        compose.onNodeWithTag(servicesRowTag(DISCOVERED_PORT)).performClick()
        awaitTag(ADD_TUNNEL_SCREEN_TAG, "the add-tunnel form")
    }

    private fun addManualTunnel() {
        compose.onNodeWithTag(ADD_TUNNEL_NAME_TAG).performTextReplacement("Fixture HTTP")
        compose.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ADD_TUNNEL_REMOTE_TAG).performTextReplacement("0")
        compose.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).assertIsNotEnabled()
        JourneyScreenshots.capture("04-add-tunnel-invalid", JOURNEY)
        compose.onNodeWithTag(ADD_TUNNEL_REMOTE_TAG).performTextReplacement(DISCOVERED_PORT.toString())
        compose.onNodeWithTag(ADD_TUNNEL_LOCAL_TAG).performTextReplacement(LOCAL_PORT.toString())
        compose.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).performScrollTo().assertIsEnabled()
        JourneyScreenshots.capture("04-add-tunnel", JOURNEY)
        compose.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).performClick()

        awaitTag(SERVICES_SCREEN_TAG, "the Services & tunnels screen after submit")
        awaitTag(servicesRowTag(DISCOVERED_PORT), "the submitted manual tunnel")
        awaitMappingPresent(DISCOVERED_PORT, LOCAL_PORT)
    }

    private fun awaitForwardingAndHttp(screenshot: String) {
        awaitTextContaining("Forwarding")
        awaitForwardService()
        reportForwardingSnapshot("before-http-$screenshot")
        awaitForwardedHttpBody()
        JourneyScreenshots.capture(screenshot, JOURNEY)
    }

    private fun reportForwardingSnapshot(label: String) {
        val snapshot = appGraph().forwardingController().snapshot.value
        println(
            "J13_FORWARDING_SNAPSHOT label=$label " +
                snapshot.joinToString { host ->
                    "host=${host.hostId}/${host.connection} " +
                        "tunnels=${host.tunnels.joinToString { tunnel ->
                            "${tunnel.remotePort}->${tunnel.localPort}:${tunnel.status}"
                        }}"
                },
        )
    }

    private fun removeManualTunnel() {
        compose.onNodeWithTag(servicesRowTag(DISCOVERED_PORT)).performClick()
        awaitTag(TUNNEL_DETAIL_TAG, "the tunnel detail screen")
        compose.onNodeWithText("Manual tunnel").assertIsDisplayed()
        compose.onNodeWithTag(TUNNEL_STOP_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove tunnel").assertIsDisplayed()
        JourneyScreenshots.capture("06-tunnel-detail", JOURNEY)
        compose.onNodeWithTag(TUNNEL_STOP_TAG).performClick()

        awaitTag(SERVICES_SCREEN_TAG, "Services after manual removal")
    }

    private fun openUsageFromServices() {
        compose.onNodeWithTag(SERVICES_BACK_TAG).performClick()
        awaitTag(HOST_WORKSPACES_ACTIONS_TAG, "the host actions menu after Services")
        compose.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        awaitTag(SESSION_TREE_USAGE_TAG, "the Usage header action after Services")
        compose.onNodeWithTag(SESSION_TREE_USAGE_TAG).performClick()
        awaitTag(USAGE_SCREEN_TAG, "host-scoped Usage")
        awaitTag(USAGE_PROVIDER_LIST_TAG, "host-scoped Usage providers")
        awaitTag(usageProviderRowTag("codex"), "the host Usage Codex row")
        compose.onNodeWithText("docker-fixture").assertIsDisplayed()
        JourneyScreenshots.capture("09-host-usage", JOURNEY)
    }

    private fun awaitMappingPresent(remotePort: Int, localPort: Int) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val mapping = runBlocking {
                appGraph().portRemappingDao().getByRemotePort(hostId, remotePort)
            }
            if (mapping?.localPort == localPort) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("manual mapping for $remotePort never appeared")
    }

    private fun awaitMappingAbsent(remotePort: Int) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val mapping = runBlocking {
                appGraph().portRemappingDao().getByRemotePort(hostId, remotePort)
            }
            if (mapping == null) return
            SystemClock.sleep(POLL_MS)
        }
        assertNull(
            "manual mapping for $remotePort remained after removal/reopen",
            runBlocking { appGraph().portRemappingDao().getByRemotePort(hostId, remotePort) },
        )
    }

    private fun awaitForwardService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            @Suppress("DEPRECATION")
            val running = context.getSystemService(ActivityManager::class.java)
                ?.getRunningServices(100)
                ?.any { it.service.className == ForwardService::class.java.name } == true
            if (running) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-foreground-service", JOURNEY)
        throw AssertionError("ForwardService was not running. Screenshot: ${shot.absolutePath}")
    }

    /**
     * Proves the usable data path: the emulator connects to the local end of
     * the SSH forward and reads a body served by the fixture on remote 5173.
     */
    private fun awaitForwardedHttpBody() {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var lastBody = ""
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val connection = runCatching {
                (java.net.URL("http://127.0.0.1:$LOCAL_PORT/j13-body.txt")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    requestMethod = "GET"
                }
            }.getOrNull()
            if (connection != null) {
                try {
                    val responseCode = connection.responseCode
                    lastBody = connection.inputStream.bufferedReader().use { it.readText() }
                    assertTrue("forwarded HTTP response must be 200", responseCode == 200)
                    if (lastBody.contains(HTTP_BODY_TOKEN)) return
                    lastFailure = AssertionError(
                        "HTTP $responseCode from forwarded port contained: $lastBody",
                    )
                } catch (failure: Throwable) {
                    lastFailure = failure
                } finally {
                    connection.disconnect()
                }
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "GET http://127.0.0.1:$LOCAL_PORT/j13-body.txt did not return " +
                "the fixture body '$HTTP_BODY_TOKEN'; lastBody='$lastBody'",
            lastFailure,
        )
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

    private fun awaitTextContaining(text: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("text poll: $text")
            if (compose.onAllNodesWithText(text, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-text", JOURNEY)
        throw AssertionError(
            "text containing '$text' never appeared within ${TIMEOUT_MS}ms.\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun phase(): String = InstrumentationRegistry.getArguments()
        .getString(J13_PHASE_ARGUMENT)
        ?.trim()
        ?.lowercase()
        .orEmpty()

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val HTTP_TIMEOUT_MS = 1_000
        const val HTTP_BODY_TOKEN = "POCKETSHELL_J13_HTTP_2611"
        const val DISCOVERED_PORT = 5_173
        const val LOCAL_PORT = 7_432
        const val JOURNEY = "j13-port-forward-open"
        const val HOST_ID = 9_901L
        const val J13_PHASE_ARGUMENT = "j13Phase"
        const val PHASE_SETUP = "setup"
        const val PHASE_RESUME = "resume"
        const val PHASE_REMOVED = "removed"
    }
}
