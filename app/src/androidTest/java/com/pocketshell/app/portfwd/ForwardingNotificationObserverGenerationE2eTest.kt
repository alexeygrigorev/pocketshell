package com.pocketshell.app.portfwd

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.service.ForwardingNotificationCloseBarrier
import com.pocketshell.app.portfwd.service.ForwardingService
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #2006 — connected (emulator + Docker) regression for the ongoing
 * port-forward notification that stopped describing the live tunnel.
 *
 * ## The reported failure
 *
 * An exact-order connected recurrence hit a real product assertion in
 * [ForwardingNotificationE2eTest]:
 *
 * ```
 * expected notification body containing the forwarding host + port
 * actual: `Running in the background · Connecting…`
 * ```
 *
 * That test's `@Before` unregisters every active host and the test body then
 * registers one — i.e. the last forward goes away and a new one starts
 * immediately. `ForwardingService`'s teardown is driven by its notification
 * OBSERVER on the observe dispatcher, and it is not atomic against the
 * `ACTION_START` the controller's empty→1 transition fires, so that sequence
 * lands in one of two windows and leaves the tray describing nothing real:
 *
 *  1. **Start inside the close window** — the observer's "no hosts left"
 *     conclusion is already false by the time it reaches the close monitor, but
 *     the teardown ran anyway: the live forward's ongoing notification was
 *     removed and the service stopped. Production sends NO follow-up start
 *     (`ForwardingService.start()` fires only on empty→1, which already
 *     happened), so nothing recovers.
 *  2. **Start after the close, before the observer is cleared** — the fresh
 *     generation posts the bootstrap `Connecting…` body and arms its own
 *     observer, which the old teardown's generation-blind clear then cancelled
 *     (and which, before that, `armObserver` would REUSE while it was already
 *     invalidated). Nothing re-arms, so the tray stays on `Connecting…` — the
 *     exact reported body.
 *
 * See `ForwardingServiceObserverGenerationTest` for the deterministic JVM-level
 * red→green of the same two windows.
 *
 * ## What these tests prove on the real path
 *
 * A REAL Docker-backed SSH tunnel (adopted into the production
 * [ForwardingController] supervisor, bytes round-tripped through the real local
 * forward socket), the REAL system-started [ForwardingService], and the REAL
 * platform notification tray — driven through each window and then asserted to
 * describe the live host + forwarded port, with the tunnel still usable and Stop
 * still tearing everything down. **The only `ACTION_START` in either journey is
 * the one the production controller itself fires**; the tests never inject a
 * start of their own.
 *
 * Two pieces of state are injected synthetically, because a loaded emulator
 * cannot be asked to reproduce a microsecond-wide thread interleaving on demand
 * (D33: inject the failing state, hard-fail otherwise — the #780 model):
 *
 *  - [ForwardingNotificationCloseBarrier] holds ONE side of the
 *    notification-generation close open. Reaching it is HARD-asserted; the test
 *    fails rather than skips if the window never opens.
 *  - The tests hold a `BIND_AUTO_CREATE` binding on [ForwardingService] for the
 *    whole journey, so an observer-driven `stopSelf()` cannot silently give the
 *    next start a brand-new service instance (which would paper over the
 *    stranded observer). This is exactly the on-device shape while the app is
 *    alive and something still holds the service.
 *
 * Everything else — the intents, the foreground promotion, the SSH transport,
 * the notification content, the Stop `PendingIntent` — is production.
 *
 * Uses only the default `agents` fixture (`10.0.2.2:2222`), so no
 * `.github/workflows/tests.yml` service change is needed. There is no
 * `assumeTrue` / `isRunningOnCi` self-skip on any load-bearing assertion.
 */
@RunWith(AndroidJUnit4::class)
class ForwardingNotificationObserverGenerationE2eTest {
    private lateinit var trustedHostKeySha256: String

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val grantPermissions = PreGrantPermissionsRule()

    private val releaseBarrier = CountDownLatch(1)
    private val barrierExited = CountDownLatch(1)
    private var pinnedServiceConnection: ServiceConnection? = null
    private val openedSessions = mutableListOf<SshSession>()

    /** `notification-op=invalidate` count sampled just before the race is driven. */
    private var invalidateOpsAtRaceStart = 0

    /** Tray body observed right after the raced start, recorded as evidence. */
    private var bootstrapBodySeen: String? = null

    private val forwardingIsolation = PortForwardingTestIsolationRule(
        // Everything this test pins must be released BEFORE the shared hard
        // isolation asserts zero: a held barrier would wedge the teardown and
        // the BIND_AUTO_CREATE pin deliberately keeps the service alive.
        beforeStop = {
            releaseBarrier.countDown()
            ForwardingNotificationCloseBarrier.setForTest(null)
            pinnedServiceConnection?.let { connection ->
                runCatching { context.unbindService(connection) }
            }
            pinnedServiceConnection = null
            openedSessions.forEach { session -> runCatching { session.close() } }
            openedSessions.clear()
        },
        afterStop = {
            runCatching { notificationManager.cancelAll() }
        },
    )

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(forwardingIsolation)
        .around(grantPermissions)

    private fun entryPoint(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, TestAccessEntryPoint::class.java)

    private fun controller(): ForwardingController = entryPoint().forwardingController()

    /**
     * LOAD-BEARING (#2006), window 1. A real forward starts while the observer's
     * teardown is still in flight. The teardown's premise is now false, and
     * there is NO second start to recover with, so the ongoing notification must
     * survive and converge on the live host + forwarded port.
     */
    @Test
    fun startInsideCloseWindowKeepsRealForwardNotification_withNoFollowUpStart() {
        runJourney(ForwardingNotificationCloseBarrier.Phase.BEFORE_CLOSE) { secondLocalPort ->
            // The held teardown is released while the new forward is live. It
            // must be rejected: on the unfixed service it removed this live
            // forward's notification and stopped the service, leaving an active
            // tunnel with nothing in the tray at all.
            val dropsBefore = serviceOperationCount(LIVE_FORWARD_DROP)
            releaseBarrier.countDown()
            assertTrue(
                "the held notification-generation close must run to completion",
                barrierExited.await(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            // The USER-VISIBLE symptom is asserted first, so it is the assertion
            // that goes red on the unfixed service (G6); the mechanism marker
            // below is corroboration, never the load-bearing check.
            assertEquals(
                "the ongoing port-forward notification must keep describing the live " +
                    "host + forwarded port after a start raced the observer's teardown " +
                    "(issue #2006); production sends no second start, so a teardown that " +
                    "runs anyway leaves the real tunnel on port $secondLocalPort with no " +
                    "ongoing notification. active titles=" + activeTitles(),
                settledText(SECOND_HOST_NAME),
                awaitForwardingNotificationText(settledText(SECOND_HOST_NAME)),
            )
            assertTrue(
                "the observer's teardown must be rejected because the controller is no " +
                    "longer empty (logcat notification-op=drop_stale … " +
                    "reason=zero_snapshot:live_forward)",
                awaitServiceOperationCount(LIVE_FORWARD_DROP, dropsBefore + 1),
            )
        }
    }

    /**
     * LOAD-BEARING (#2006), window 2 — the reported BODY. The teardown completes
     * and a real forward starts before the torn-down observer is cleared, so the
     * service re-promotes and posts the bootstrap `Connecting…`. The fresh
     * generation must keep its own observer and converge on the live detail
     * instead of staying stranded on `Connecting…`.
     */
    @Test
    fun startAfterCloseIsNotStrandedOnConnectingBody() {
        runJourney(ForwardingNotificationCloseBarrier.Phase.AFTER_CLOSE) { secondLocalPort ->
            // The teardown COMPLETED before the start: the notification was
            // removed and the foreground dropped, so the start had to
            // re-promote and post the bootstrap `Connecting…` placeholder. That
            // is the exact reported precondition, and this asserts the journey
            // really entered it.
            assertTrue(
                "the observer's teardown must have completed (logcat " +
                    "notification-op=invalidate) before the new forward's start, " +
                    "otherwise the re-promotion that posts 'Connecting…' never happened " +
                    "and the reported state was not entered",
                awaitServiceOperationCount(INVALIDATE_OP, invalidateOpsAtRaceStart + 1),
            )
            bootstrapBodySeen = forwardingNotificationText()

            releaseBarrier.countDown()
            assertTrue(
                "the held clear-observer boundary must run to completion",
                barrierExited.await(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            assertEquals(
                "the ongoing port-forward notification must converge on the live host + " +
                    "forwarded-port detail — it must NOT stay stranded on the bootstrap " +
                    "'Connecting…' body (issue #2006); the real tunnel is up on port " +
                    "$secondLocalPort. bootstrap body seen=" + bootstrapBodySeen +
                    " active titles=" + activeTitles(),
                settledText(SECOND_HOST_NAME),
                awaitForwardingNotificationText(settledText(SECOND_HOST_NAME)),
            )
        }
    }

    // ------------------------------------------------------------- the journey

    /**
     * The shared real-path journey. [heldPhase] selects which side of the
     * notification-generation close is held open; [resolveRace] is the
     * per-window release + load-bearing assertion.
     */
    private fun runJourney(
        heldPhase: ForwardingNotificationCloseBarrier.Phase,
        resolveRace: (secondLocalPort: Int) -> Unit,
    ) = runBlocking {
        grantNotifications()
        controller().activeHostIdsSnapshot().forEach { controller().unregisterActiveHost(it) }
        notificationManager.cancelAll()

        val fixtureKey = SshKey.Pem(
            instrumentation.context.assets.open("test_key").bufferedReader().use { it.readText() },
        )
        trustedHostKeySha256 = waitForSshFixtureReady(fixtureKey, port = DEFAULT_PORT)
        startRemoteEchoFixture(fixtureKey)

        // Pin the service instance for the whole journey (see the class KDoc):
        // an observer-driven stopSelf() must not be allowed to hand the next
        // start a fresh instance.
        pinService()

        try {
            // 1. A REAL forward: adopt a live SSH session into the production
            // supervisor and prove bytes actually traverse the tunnel.
            val firstLocalPort = freeLocalPort()
            adoptRealForward(fixtureKey, FIRST_HOST_ID, FIRST_HOST_NAME, firstLocalPort)
            assertEquals(
                "the production supervisor must expose the Docker-backed forward",
                firstLocalPort,
                awaitForwardedLocalPort(FIRST_HOST_ID, firstLocalPort),
            )
            assertEquals(
                "the real forwarded socket must round-trip bytes through Docker",
                "issue2006-first",
                roundTripThroughForward(firstLocalPort, "issue2006-first"),
            )
            assertEquals(
                "the ongoing notification must describe the live forward before the " +
                    "race is driven; active titles=" + activeTitles(),
                settledText(FIRST_HOST_NAME),
                awaitForwardingNotificationText(settledText(FIRST_HOST_NAME)),
            )

            // 2. Open the chosen side of the notification-generation close. The
            // observer reaches it on its own dispatcher when the last host goes
            // away.
            //
            // `stopAllForwarding(requestServiceStop = false)` drives the
            // production runtime-zero shape without injecting ACTION_START:
            // the controller reaches zero and the OBSERVER's zero snapshot is
            // the only teardown path. The aggregate notification ACTION_STOP
            // now persists its global disable before reaching this same runtime
            // transition (#1202); this #2006 journey deliberately isolates the
            // later notification-generation race.
            val reachedWindow = holdFirstOffMainThreadClose(heldPhase)
            invalidateOpsAtRaceStart = serviceOperationCount(INVALIDATE_OP)
            controller().stopAllForwarding(requestServiceStop = false)
            assertTrue(
                "the ForwardingService notification observer must reach the " +
                    "$heldPhase boundary after the last forward is removed; without that " +
                    "window the #2006 race is not being exercised",
                reachedWindow.await(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )

            // 3. A new REAL forward starts inside that window. This is the ONLY
            // ACTION_START in the journey and PRODUCTION is what fires it:
            // adoptForwardingSession sees the controller was empty and calls
            // ForwardingService.start(). We assert the service actually
            // processed it (logcat notification-op=open) — the test itself never
            // sends a start.
            val openOpsBefore = serviceOperationCount(OPEN_OP)
            val secondLocalPort = freeLocalPort()
            adoptRealForward(fixtureKey, SECOND_HOST_ID, SECOND_HOST_NAME, secondLocalPort)
            assertTrue(
                "the ACTION_START that ForwardingController fires on its empty→1 " +
                    "transition must reach the service inside the close window (logcat " +
                    "notification-op=open); otherwise the race window was not entered",
                awaitServiceOperationCount(OPEN_OP, openOpsBefore + 1),
            )

            // 4. Window-specific release + LOAD-BEARING assertion.
            resolveRace(secondLocalPort)

            // 5. The surviving observer must be LIVE, not merely to have got one
            // publish in before something cancelled it: a later REAL topology
            // change must still reach the tray. On the production
            // multi-threaded observe dispatcher that is the only thing
            // separating "the observer survived the race" from "the observer
            // published once and was then killed" — and a frozen notification
            // is exactly what the user sees in the second case.
            val thirdLocalPort = freeLocalPort()
            adoptRealForward(fixtureKey, THIRD_HOST_ID, THIRD_HOST_NAME, thirdLocalPort)
            assertEquals(
                "a second real forward must reach the tray after the race; a notification " +
                    "whose observer was cancelled freezes on whatever it last showed " +
                    "(issue #2006). active titles=" + activeTitles(),
                twoForwardText(SECOND_HOST_NAME),
                awaitForwardingNotificationText(twoForwardText(SECOND_HOST_NAME)),
            )
            controller().unregisterActiveHost(THIRD_HOST_ID)
            assertEquals(
                "removing that forward must reach the tray too; active titles=" +
                    activeTitles(),
                settledText(SECOND_HOST_NAME),
                awaitForwardingNotificationText(settledText(SECOND_HOST_NAME)),
            )

            // 6. The settled notification must describe this forward exactly.
            val posted = requireNotNull(forwardingNotification()) {
                "the ongoing forwarding notification must be present; titles=" + activeTitles()
            }
            assertFalse(
                "the settled notification body must not still carry the bootstrap " +
                    "Connecting… placeholder",
                forwardingNotificationText().orEmpty().contains("Connecting"),
            )
            assertEquals(
                "the settled notification must badge the forwarded port count",
                1,
                posted.notification.number,
            )
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                assertEquals(
                    "the Android 16 Live Update chip must name the real remote port",
                    ":$REMOTE_PORT",
                    posted.notification.extras.getString("android.shortCriticalText"),
                )
            }

            // 7. The described tunnel is genuinely usable, not just labelled.
            assertEquals(
                "the forward the settled notification describes must still carry bytes",
                "issue2006-after-race",
                roundTripThroughForward(secondLocalPort, "issue2006-after-race"),
            )
            captureEvidence("settled-after-race-$heldPhase", secondLocalPort)

            // 8. Teardown/cleanup: the notification's own Stop action tears the
            // real tunnel down and clears the tray.
            requireNotNull(
                posted.notification.actions?.firstOrNull { it.title?.toString() == "Stop" },
            ) { "the forwarding notification must carry a Stop action" }.actionIntent.send()
            val stopDeadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
            while (
                (
                    controller().activeHostIdsSnapshot().isNotEmpty() ||
                        forwardingNotification() != null ||
                        canConnectToLocalPort(secondLocalPort)
                    ) &&
                SystemClock.elapsedRealtime() < stopDeadline
            ) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            assertTrue(
                "Stop must clear the controller state",
                controller().activeHostIdsSnapshot().isEmpty(),
            )
            assertFalse(
                "Stop must close the real local-forward socket on port $secondLocalPort",
                canConnectToLocalPort(secondLocalPort),
            )
            assertNull(
                "Stop must remove the ongoing forwarding notification; titles=" +
                    activeTitles(),
                forwardingNotification(),
            )
            captureEvidence("stopped-zero-$heldPhase", secondLocalPort)
        } finally {
            releaseBarrier.countDown()
            ForwardingNotificationCloseBarrier.setForTest(null)
            cleanupRemoteEchoFixture(fixtureKey)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun grantNotifications() {
        runShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val deadline = SystemClock.elapsedRealtime() + GRANT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (
                context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                PackageManager.PERMISSION_GRANTED &&
                notificationManager.areNotificationsEnabled()
            ) {
                break
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertEquals(
            "POST_NOTIFICATIONS must be granted before forwarding starts",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS"),
        )
        assertTrue(
            "notifications must be enabled before forwarding starts",
            notificationManager.areNotificationsEnabled(),
        )
    }

    /**
     * Hold a `BIND_AUTO_CREATE` binding so an observer-driven `stopSelf()`
     * cannot destroy the instance. `onBind` returns null by design, so the
     * platform reports the null binding; the connection itself is what pins the
     * service.
     */
    private fun pinService() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onNullBinding(name: ComponentName?) = Unit
        }
        assertTrue(
            "the test must be able to pin the ForwardingService instance for the journey",
            context.bindService(
                Intent(context, ForwardingService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        pinnedServiceConnection = connection
    }

    /**
     * Install a ONE-SHOT barrier that holds only [phase] when it arrives off the
     * main (service) thread — the notification observer's own teardown.
     * Lifecycle commands on the service thread run through untouched.
     */
    private fun holdFirstOffMainThreadClose(
        phase: ForwardingNotificationCloseBarrier.Phase,
    ): CountDownLatch {
        val reached = CountDownLatch(1)
        val held = AtomicBoolean(false)
        ForwardingNotificationCloseBarrier.setForTest { reachedPhase ->
            if (
                reachedPhase == phase &&
                Looper.myLooper() != Looper.getMainLooper() &&
                held.compareAndSet(false, true)
            ) {
                reached.countDown()
                releaseBarrier.await(BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                barrierExited.countDown()
            }
        }
        return reached
    }

    private suspend fun adoptRealForward(
        key: SshKey.Pem,
        hostId: Long,
        hostName: String,
        localPort: Int,
    ) {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = key,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = SSH_TIMEOUT_MS,
        ).getOrThrow()
        openedSessions += session
        controller().adoptForwardingSession(
            host = HostEntity(
                id = hostId,
                name = hostName,
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyId = 0,
                maxAutoPort = REMOTE_PORT,
                skipPortsBelow = REMOTE_PORT - 1,
                scanIntervalSec = 1,
                trustedHostKeySha256 = trustedHostKeySha256,
            ),
            keyPath = "",
            passphrase = null,
            firstSession = session,
            initialRemappings = mapOf(REMOTE_PORT to localPort),
        )
    }

    private suspend fun startRemoteEchoFixture(key: SshKey.Pem) {
        cleanupRemoteEchoFixture(key)
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = key,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = SSH_TIMEOUT_MS,
        ).getOrThrow().use { session ->
            val result = session.exec(
                "tmux new-session -d -s $FIXTURE_SESSION " +
                    "\"while true; do nc -l -p $REMOTE_PORT -e cat; done\"",
            )
            assertEquals("the Docker echo fixture must start", 0, result.exitCode)
        }
    }

    private suspend fun cleanupRemoteEchoFixture(key: SshKey.Pem) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = key,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = SSH_TIMEOUT_MS,
        ).getOrNull()?.use { session ->
            session.exec("tmux kill-session -t $FIXTURE_SESSION 2>/dev/null || true")
        }
    }

    private fun freeLocalPort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitForwardedLocalPort(hostId: Long, expected: Int): Int? {
        val deadline = SystemClock.elapsedRealtime() + FORWARD_TIMEOUT_MS
        var actual = forwardedLocalPort(hostId)
        while (actual != expected && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            actual = forwardedLocalPort(hostId)
        }
        return actual
    }

    private fun forwardedLocalPort(hostId: Long): Int? =
        controller().flowOfHostSnapshots().value[hostId]?.forwardedPortMap?.get(REMOTE_PORT)

    private fun roundTripThroughForward(localPort: Int, payload: String): String =
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", localPort), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val writer = socket.getOutputStream().bufferedWriter()
            writer.appendLine(payload)
            writer.flush()
            socket.getInputStream().bufferedReader().readLine().orEmpty()
        }

    private fun canConnectToLocalPort(localPort: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", localPort), 500) }
        }.isSuccess

    private fun settledText(hostName: String): String =
        "Running in the background · $hostName · 1 port forwarded"

    private fun twoForwardText(primaryHostName: String): String =
        "Running in the background · $primaryHostName + 1 more · 2 ports forwarded"

    private fun forwardingNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras.getCharSequence("android.title")?.toString()
                ?.contains("Port forwarding running") == true
        }

    private fun forwardingNotificationText(): String? =
        forwardingNotification()?.notification?.extras
            ?.getCharSequence("android.text")?.toString()

    private fun activeTitles(): List<String?> =
        notificationManager.activeNotifications.map {
            it.notification.extras.getCharSequence("android.title")?.toString() +
                " :: " + it.notification.extras.getCharSequence("android.text")?.toString()
        }

    private fun awaitForwardingNotificationText(expected: String): String? {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        var text = forwardingNotificationText()
        while (text != expected && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            text = forwardingNotificationText()
        }
        return text
    }

    /** Count of a `PsForwardingService` notification-op marker in logcat. */
    private fun serviceOperationCount(marker: String): Int =
        runShellCommand("logcat -d -s $SERVICE_TAG:I *:S")
            .lineSequence()
            .count { it.contains(marker) }

    private fun awaitServiceOperationCount(marker: String, atLeast: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + BOUNDARY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (serviceOperationCount(marker) >= atLeast) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun captureEvidence(phase: String, localPort: Int) {
        val artifactsDir = File(
            com.pocketshell.app.test.testArtifactsRoot(context),
            "additional_test_output/forwarding-notification-2006",
        )
        assertTrue(
            "could not create artifact dir ${artifactsDir.absolutePath}",
            artifactsDir.exists() || artifactsDir.mkdirs(),
        )
        val summary = buildString {
            appendLine("phase=$phase")
            appendLine("elapsed_realtime_ms=${SystemClock.elapsedRealtime()}")
            appendLine("controller_host_ids=${controller().activeHostIdsSnapshot()}")
            appendLine("controller_tunnel_count=${controller().flowOfTotalTunnelCount().value}")
            appendLine("controller_snapshots=${controller().flowOfHostSnapshots().value}")
            appendLine("local_forward_port=$localPort")
            appendLine("local_forward_reachable=${canConnectToLocalPort(localPort)}")
            appendLine("notification_body=${forwardingNotificationText()}")
            appendLine("active_notifications=${activeTitles()}")
        }
        File(artifactsDir, "$phase-summary.txt").writeText(summary)
        // Also emit the summary to logcat under a dedicated tag. The
        // media-dir artifacts live under the (suffixed) package and disappear
        // with it when a sibling connected lane's pre-run
        // `--include-base` sweep uninstalls it seconds after the run; the
        // logcat ring survives that, so the evidence is still recoverable from
        // the host with `adb logcat -d -s $EVIDENCE_TAG:I`.
        summary.lineSequence().filter { it.isNotBlank() }.forEach {
            android.util.Log.i(EVIDENCE_TAG, "$phase $it")
        }
        File(artifactsDir, "$phase-service-logcat.txt").writeText(
            runShellCommand("logcat -d -s $SERVICE_TAG:I *:S"),
        )
        File(artifactsDir, "$phase-services.txt").writeText(
            runShellCommand("dumpsys activity services ${context.packageName}"),
        )
        println("FORWARDING_NOTIFICATION_2006_PHASE $phase dir=${artifactsDir.absolutePath}")
    }

    private fun runShellCommand(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { it.readText() }
        }.also { pfd.close() }
    }

    private companion object {
        const val FIRST_HOST_ID = 2_006_101L
        const val SECOND_HOST_ID = 2_006_102L
        const val THIRD_HOST_ID = 2_006_103L
        const val FIRST_HOST_NAME = "Issue 2006 first forward"
        const val SECOND_HOST_NAME = "Issue 2006 raced forward"
        const val THIRD_HOST_NAME = "Issue 2006 liveness forward"
        const val REMOTE_PORT = 8_766
        const val FIXTURE_SESSION = "issue2006-forward"
        const val SERVICE_TAG = "PsForwardingService"
        const val EVIDENCE_TAG = "PsIssue2006Evidence"
        const val OPEN_OP = "notification-op=open"
        const val INVALIDATE_OP = "notification-op=invalidate"
        const val LIVE_FORWARD_DROP = "reason=zero_snapshot:live_forward"
        const val POLL_INTERVAL_MS = 100L
        const val GRANT_TIMEOUT_MS = 5_000L
        const val BOUNDARY_TIMEOUT_MS = 30_000L
        const val SETTLE_TIMEOUT_MS = 20_000L
        const val FORWARD_TIMEOUT_MS = 30_000L
        const val STOP_TIMEOUT_MS = 15_000L
        const val SSH_TIMEOUT_MS = 15_000
        const val SOCKET_TIMEOUT_MS = 5_000
    }
}
