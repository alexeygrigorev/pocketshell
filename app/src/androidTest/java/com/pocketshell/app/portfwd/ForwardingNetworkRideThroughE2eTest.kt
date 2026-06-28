package com.pocketshell.app.portfwd

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.connectivity.TerminalNetworkObserver
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.FORWARDING_INDICATOR_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.RecordingDiagnosticSink
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISSUE #1058 (#843 audit R1, trigger T11 / coverage gap C1) — the port-forward
 * tunnel must ride through a cellular network handoff / bare loss / restore the
 * SAME liveness-first way the terminal transport already does
 * (#981/#997/#1042/#1045), instead of calling `forceReconnectNow()` on EVERY
 * emitted network change. On cellular the device dips into no-validated-network
 * constantly (tunnel, elevator, RAT handover, congestion re-validation) WITHOUT
 * the socket dying, so the old unconditional redial churned the user's forwards
 * "restoring…" on every blip while the terminal stayed Live.
 *
 * This is the D33/G10 reproduce-first END-TO-END proof: it stands up a REAL
 * forward against the deterministic `agents:2222` fixture (real [SshSession]
 * with the always-on #945 transport keepalive running), then injects the device
 * network event SYNTHETICALLY through the PRODUCTION [TerminalNetworkObserver]
 * detector + emit pipeline — the SAME `changes` stream the controller subscribes
 * to (the AVD can't mint a new `networkHandle` / enter airplane mode on demand;
 * the #780 hard-inject model, NO self-skip). It asserts from the controller's
 * `portforward` diagnostics (recorded on the same run), covering all three arms
 * the audit names (D32 G2 class coverage):
 *
 *   (a) NetworkLost  → HOLD: the tunnels are held, ZERO redial.
 *   (b) handoff/restore on a PROVEN-ALIVE tunnel → RIDE THROUGH: ZERO redial,
 *       no "restoring…" churn.
 *   (c) handoff/restore on a GENUINELY-DEAD tunnel → REDIAL (preserves the
 *       #329/#439 re-establish contract).
 *
 * RED on base: the old controller recorded NO `network_loss_hold` /
 * `network_ride_through` and force-redialled on the loss + the proven-alive
 * restore → arms (a) and (b) FAIL. GREEN with #1058.
 *
 * No Docker fixture beyond the default `agents:2222` is used, so no
 * `.github/workflows/tests.yml` change is needed.
 */
@RunWith(AndroidJUnit4::class)
class ForwardingNetworkRideThroughE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var seededHostId: Long? = null
    private var diagnostics: RecordingDiagnosticSink? = null

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun entryPoint(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(appContext(), TestAccessEntryPoint::class.java)

    private fun controller(): ForwardingController = entryPoint().forwardingController()

    private fun observer(): TerminalNetworkObserver = entryPoint().terminalNetworkObserver()

    @After
    fun teardown() {
        runCatching { controller().forceTransportProvenAliveForTest = null }
        runCatching { controller().stopAllForwarding() }
        diagnostics?.close()
        diagnostics = null
        seededHostId = null
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun cellularHandoffLossRestoreRidesThroughWhenTunnelSurvived_redialsWhenDead() {
        val key = readFixtureKey()
        runBlocking { waitForSshFixtureReady(SshKey.Pem(key)) }

        // 1. Seed an ENABLED host pointing at the agents fixture into the real
        //    singleton DB (the same instance the running app flows observe),
        //    BEFORE launching MainActivity — the persisted "auto-forward on"
        //    state, so the production resume hook stands up a REAL forward.
        val db = entryPoint().appDatabase()
        runBlocking {
            db.clearAllTables()
            controller().activeHostIdsSnapshot().forEach { controller().stopForwarding(it) }
            val storedKey = SshKeyStorage.persistKey(
                context = appContext(),
                sshKeyDao = db.sshKeyDao(),
                name = "issue1058-key-${System.currentTimeMillis()}",
                content = key,
            )
            seededHostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1058 Ride-Through Host",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    enabled = true,
                ),
            )
        }
        val hostId = requireNotNull(seededHostId)

        // 2. Launch + force a real STOP→START so the production resume connects to
        //    the fixture and adopts the host — a live tunnel transport (real
        //    SshSession + always-on keepalive).
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MS) {
            compose.onAllNodesWithText("Hosts").fetchSemanticsNodes().isNotEmpty()
        }
        launchedActivity!!.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(300)
        launchedActivity!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(timeoutMillis = FORWARD_TIMEOUT_MS) {
            controller().isHostActive(hostId)
        }
        compose.waitUntil(timeoutMillis = FORWARD_TIMEOUT_MS) {
            compose.onAllNodesWithTag(FORWARDING_INDICATOR_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "ForwardingController must report the host active before the network drive",
            controller().isHostActive(hostId),
        )

        // 3. Record the controller's portforward diagnostics on this run.
        val sink = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        diagnostics = sink

        val observer = observer()
        // Seed a known validated baseline so the subsequent loss reliably emits a
        // NetworkLost (the detector's loss arm is idempotent if already lost).
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "net-A", transports = setOf("WIFI")),
            reason = "issue1058-baseline",
        )
        SystemClock.sleep(200)
        sink.clear()

        // ---- ARM (a): a bare NetworkLost is HELD — ZERO redial. ----
        controller().forceTransportProvenAliveForTest = false // redial-eligible if it were a handoff
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.NoValidatedNetwork,
            reason = "issue1058-bare-loss",
        )
        waitForDiagnostic("network_loss_hold")
        watchNoRedial("across the bare network loss")
        assertEquals(
            "the host must stay active (held, not removed) across a bare loss",
            true,
            controller().isHostActive(hostId),
        )
        Log.i(LOG_TAG, "arm (a) loss-hold verified: ${sink.events.map { it.name }}")

        // ---- ARM (b): the tunnel SURVIVED the dip → restore RIDES THROUGH. ----
        sink.clear()
        controller().forceTransportProvenAliveForTest = true
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "net-A", transports = setOf("WIFI")),
            reason = "issue1058-restore-survived",
        )
        waitForDiagnostic("network_ride_through")
        watchNoRedial("across the survivable restore (proven alive)")
        val rideThrough = sink.eventsNamed("network_ride_through")
        assertTrue(
            "expected a network_ride_through (proves the ride-through arm fired, not a " +
                "vacuous pass); events=${sink.events.map { it.name }}",
            rideThrough.isNotEmpty(),
        )
        assertEquals(
            "ride-through must be attributed to the proven-alive keepalive",
            "transport_proven_alive",
            rideThrough.first().fields["cause"],
        )
        assertEquals(
            "the surviving tunnel must NOT enter 'restoring…' churn on ride-through",
            0,
            controller().flowOfRestoringHostCount().value,
        )
        Log.i(LOG_TAG, "arm (b) ride-through verified: ${sink.events.map { it.name }}")

        // ---- ARM (c): a GENUINELY-DEAD tunnel on a handoff → REDIAL. ----
        sink.clear()
        controller().forceTransportProvenAliveForTest = false
        // A cross-transport handoff (WIFI→CELLULAR) on a not-proven-alive tunnel —
        // a real handoff the dead socket cannot survive — must redial.
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "net-B", transports = setOf("CELLULAR")),
            reason = "issue1058-handoff-dead",
        )
        waitForDiagnostic("network_redial")
        val redial = sink.eventsNamed("network_redial")
        assertTrue(
            "expected a network_redial for the genuinely-dead handoff (preserves the " +
                "#329/#439 re-establish contract); events=${sink.events.map { it.name }}",
            redial.isNotEmpty(),
        )
        Log.i(LOG_TAG, "arm (c) dead-redial verified: ${sink.events.map { it.name }}")
    }

    private fun waitForDiagnostic(name: String) {
        val sink = requireNotNull(diagnostics)
        compose.waitUntil(timeoutMillis = DIAGNOSTIC_TIMEOUT_MS) {
            sink.eventsNamed(name).isNotEmpty()
        }
        assertTrue(
            "expected a $name diagnostic; events=${sink.events.map { it.name }}",
            sink.eventsNamed(name).isNotEmpty(),
        )
    }

    /**
     * Forbid the redial signal across a window — proves the tunnel was held / rode
     * through with NO churn (the whole point of #1058).
     */
    private fun watchNoRedial(label: String) {
        val sink = requireNotNull(diagnostics)
        val deadline = SystemClock.elapsedRealtime() + WATCH_NO_REDIAL_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val redials = sink.eventsNamed("network_redial")
            assertTrue(
                "expected NO network_redial for $label (the tunnel survived; no churn); " +
                    "events=${sink.events.map { it.name }}",
                redials.isEmpty(),
            )
            SystemClock.sleep(100)
        }
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private companion object {
        const val LOG_TAG = "Issue1058RideThrough"
        const val WATCH_NO_REDIAL_MS = 2_000L
        val LAUNCH_TIMEOUT_MS: Long =
            if (com.pocketshell.app.proof.TerminalTestTimeouts.isRunningOnCi()) 40_000L else 20_000L
        val FORWARD_TIMEOUT_MS: Long =
            if (com.pocketshell.app.proof.TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L
        val DIAGNOSTIC_TIMEOUT_MS: Long =
            if (com.pocketshell.app.proof.TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
