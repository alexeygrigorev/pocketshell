package com.pocketshell.next.ports

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.hosts.HOST_LIST_TAG
import com.pocketshell.next.share.ShareActivity
import com.pocketshell.next.workspaces.HOST_WORKSPACES_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J18 — opening the app with `hosts.enabled = 1` must auto-forward
 * in-window ports without visiting Services / Ports / Add tunnel (issue #2654).
 *
 * Pre-0.5.0, ProcessLifecycleOwner ON_START remounted AutoForwarder for every
 * enabled host. The rewrite kept the engine but only called
 * [ForwardService.resume] from the Services screens, so a cold start never
 * forwarded anything.
 *
 * The wholesale `app2-journey` suite (#2474) runs J13 then J18 in one process.
 * J13 leaves ForwardService running and the process lifecycle observer
 * attached; ProcessLifecycleOwner stays STARTED, so J18's MainActivity never
 * sees another ON_START. `awaitForwardService()` must not treat that leftover
 * FGS as success — this seed unmounts it, re-attaches the leftover observer,
 * and asserts host 9918's in-window tunnel plus the HTTP body.
 *
 * Fixture: Docker `agents` on `10.0.2.2:2222`. Bring it up first:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J18AutoForwardResumeJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    /**
     * Holds ProcessLifecycleOwner at STARTED across MainActivity launch, like
     * J13's leftover activity in the wholesale suite. Closed in [tearDown].
     */
    private var leftoverStarted: ActivityScenario<out Activity>? = null

    @After
    fun tearDown() {
        leftoverStarted?.close()
        leftoverStarted = null
    }

    private suspend fun seed(description: Description) {
        grantNotificationPermission()
        val graph = appGraph()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        graph.connectionsRegistry().closeAll()

        // J13 (and any prior journey) may have left ForwardService running.
        // Tear it down so "some FGS is alive" cannot satisfy this test.
        graph.forwardingController().stopAll()
        context.stopService(Intent(context, ForwardService::class.java))
        awaitForwardServiceStopped()
        graph.hostDao().getAll().first().forEach {
            graph.portRemappingDao().deleteByHostId(it.id)
            graph.hostDao().deleteById(it.id)
        }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J18_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")
        AgentsFixture.exec(
            "printf 'POCKETSHELL_J18_HTTP_2654\\n' > /tmp/j18-body.txt",
        )
        AgentsFixture.exec(
            "nohup python3 -m http.server $DISCOVERED_PORT --directory /tmp " +
                ">/tmp/pocketshell-j18-http.log 2>&1 </dev/null &",
        )

        // Suite class: process already STARTED, observer already attached
        // (J13 leftover). Keep a leftover activity so ProcessLifecycleOwner
        // stays STARTED and MainActivity launch does not emit another ON_START.
        leftoverStarted = launchLeftoverStartedActivity()
        val sweepsBefore = graph.forwardingResume().resumeSweepCount.get()
        graph.forwardingResume().observeProcessLifecycle()
        awaitObserverAttached()
        awaitResumeSweep(sweepsBefore)

        hostId = HOST_ID
        val keyPath = AgentsFixture.installPrivateKey(fileName = "j18_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j18-${description.methodName}", privateKeyPath = keyPath),
        )
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                enabled = true,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
        unmountKeepingEnabled()
        val processState = ProcessLifecycleOwner.get().lifecycle.currentState
        val leftoverFgs = isForwardServiceRunning()
        println(
            "J18_LEFTOVER_CLASS process=$processState leftoverFgs=$leftoverFgs " +
                "host=$HOST_ID enabled=true observerAttached=" +
                graph.forwardingResume().lifecycleObserverAttached,
        )
        check(!leftoverFgs) {
            "leftover ForwardService still running after seed unmount"
        }
        check(graph.forwardingController().snapshot.value.none { it.hostId == HOST_ID }) {
            "host $HOST_ID must not already be mounted before MainActivity launch"
        }
        check(graph.hostDao().getById(HOST_ID)?.enabled == true) {
            "host $HOST_ID must stay enabled so onStart remounts it"
        }
    }

    @Test
    fun openingTheAppWithEnabledHostAutoForwardsWithoutOpeningServices() {
        awaitHostListOrTree()
        compose.onAllNodesWithTag(SERVICES_SCREEN_TAG).fetchSemanticsNodes().let { nodes ->
            assertTrue("must not open Services / Ports / Add tunnel", nodes.isEmpty())
        }
        JourneyScreenshots.capture("01-after-launch", JOURNEY)

        val localPort = awaitForwardedLocalPort()
        reportForwardingSnapshot("before-http-local=$localPort")
        awaitForwardedHttpBody(localPort)
        reportForwardingSnapshot("after-http-local=$localPort")
        JourneyScreenshots.capture("02-forward-live", JOURNEY)

        compose.onAllNodesWithTag(SERVICES_SCREEN_TAG).fetchSemanticsNodes().let { nodes ->
            assertTrue("Services must stay unopened after auto-forward", nodes.isEmpty())
        }
    }

    private fun awaitHostListOrTree() {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("host list or tree")
            val onHosts = compose.onAllNodesWithTag(HOST_LIST_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
            val onTree = compose.onAllNodesWithTag(HOST_WORKSPACES_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (onHosts || onTree) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-landing", JOURNEY)
        throw AssertionError(
            "host list or tree never appeared within ${TIMEOUT_MS}ms.\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun awaitForwardedLocalPort(): Int {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val tunnel = forwardedTunnel()
            if (tunnel != null) return tunnel.localPort
            SystemClock.sleep(POLL_MS)
        }
        reportForwardingSnapshot("timeout-waiting-for-tunnel")
        val shot = JourneyScreenshots.capture("failure-no-tunnel", JOURNEY)
        throw AssertionError(
            "in-window port $DISCOVERED_PORT was never forwarded for host $HOST_ID. " +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun forwardedTunnel(): TunnelInfo? =
        appGraph().forwardingController().snapshot.value
            .firstOrNull { it.hostId == HOST_ID }
            ?.tunnels
            ?.firstOrNull {
                it.remotePort == DISCOVERED_PORT && it.status == TunnelInfo.Status.FORWARDING
            }

    private fun reportForwardingSnapshot(label: String) {
        val snapshot = appGraph().forwardingController().snapshot.value
        val fgs = isForwardServiceRunning()
        println(
            "J18_FORWARDING_SNAPSHOT label=$label fgs=$fgs " +
                snapshot.joinToString { host ->
                    "host=${host.hostId}/${host.connection} " +
                        "tunnels=${host.tunnels.joinToString { tunnel ->
                            "${tunnel.remotePort}->${tunnel.localPort}:${tunnel.status}"
                        }}"
                },
        )
    }

    private fun awaitForwardedHttpBody(localPort: Int) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var lastBody = ""
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val connection = runCatching {
                (java.net.URL("http://127.0.0.1:$localPort/j18-body.txt")
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
                    if (lastBody.contains(HTTP_BODY_TOKEN)) {
                        println("J18_HTTP_BODY=${lastBody.trim()}")
                        return
                    }
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
            "GET http://127.0.0.1:$localPort/j18-body.txt did not return " +
                "the fixture body '$HTTP_BODY_TOKEN'; lastBody='$lastBody'",
            lastFailure,
        )
    }

    private fun unmountKeepingEnabled() {
        val graph = appGraph()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            if (graph.forwardingController().isRunning(HOST_ID)) {
                graph.forwardingController().stop(HOST_ID)
            }
            val host = graph.hostDao().getById(HOST_ID)
            if (host != null && !host.enabled) {
                graph.hostDao().update(host.copy(enabled = true))
            }
        }
        context.stopService(Intent(context, ForwardService::class.java))
        awaitForwardServiceStopped()
    }

    private fun launchLeftoverStartedActivity(): ActivityScenario<out Activity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "j18-leftover-keep-started")
        }
        val scenario = ActivityScenario.launch<ShareActivity>(intent)
        val deadline = SystemClock.elapsedRealtime() + SEED_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.STARTED,
                )
            ) {
                return scenario
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("leftover ShareActivity did not move ProcessLifecycleOwner to STARTED")
    }

    private fun awaitResumeSweep(sweepsBefore: Int) {
        val deadline = SystemClock.elapsedRealtime() + SEED_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (appGraph().forwardingResume().resumeSweepCount.get() > sweepsBefore) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("leftover attach ON_START sweep did not complete")
    }

    private fun awaitObserverAttached() {
        val deadline = SystemClock.elapsedRealtime() + SEED_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (appGraph().forwardingResume().lifecycleObserverAttached) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("leftover ProcessLifecycleOwner observer did not attach")
    }

    private fun awaitForwardServiceStopped() {
        val deadline = SystemClock.elapsedRealtime() + SEED_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isForwardServiceRunning()) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("leftover ForwardService did not stop within ${SEED_WAIT_MS}ms")
    }

    private fun isForwardServiceRunning(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        return context.getSystemService(ActivityManager::class.java)
            ?.getRunningServices(100)
            ?.any { it.service.className == ForwardService::class.java.name } == true
    }

    private fun grantNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val SEED_WAIT_MS = 5_000L
        const val POLL_MS = 250L
        const val HTTP_TIMEOUT_MS = 1_000
        const val HTTP_BODY_TOKEN = "POCKETSHELL_J18_HTTP_2654"
        const val DISCOVERED_PORT = 5_173
        const val JOURNEY = "j18-auto-forward-resume"
        const val HOST_ID = 9_918L
    }
}
