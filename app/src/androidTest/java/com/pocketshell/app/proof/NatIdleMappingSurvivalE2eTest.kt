package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.tmux.LivenessProbeTestOverride
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KeepAliveTestOverride
import com.pocketshell.core.ssh.SshKey
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1063 (R3, from the #843 round-2 mobile audit / gap C2) — the REAL-WIRE
 * carrier-NAT idle-mapping RECOVERY proof (Arm 2): when the carrier NAT actually
 * reaps the idle TCP mapping mid-idle (modelled by the toxiproxy `timeout=0`
 * half-open toxic — no RST/FIN, packets silently dropped), the always-on
 * [com.pocketshell.core.ssh.TransportKeepAlive] (#945) must DETECT the now-dead
 * half-open transport within its `countMax × interval` budget and drive recovery,
 * after which the session returns to Connected and a post-recovery send round-trips.
 *
 * CI_JOURNEY_SUITE_JUSTIFIED: this is a toxiproxy `NetworkFaultProofBase` proof; it
 * needs the `network-fault-proxy` family that `tests.yml` deliberately leaves down,
 * so it self-skips on CI ([assumeNetworkFaultProofsEnabled]) — wiring it into
 * `scripts/ci-journey-suite.sh` would only ever ALL-SKIP at PR time (the G3 vacuous-
 * pass trap the round-1 reviewer flagged). It is the reviewer's real-wire RECOVERY
 * evidence, run with the proxy family up (the same pattern as the baselined
 * `PacketLossNetworkFaultE2eTest` / `DisconnectBlackholeE2eTest` siblings).
 *
 * The LOAD-BEARING per-push red→green for Arm 1 (idle-mapping SURVIVAL: a keepalive
 * interval shorter than the NAT idle window keeps the mapping warm; a retune past it
 * reaps it) lives at the layer where the keepalive IS the deciding factor —
 * `shared/core-ssh/src/test/.../NatIdleMappingSurvivalKeepAliveTest.kt` — and runs
 * in the per-push Unit gate. Round 1's connected-oracle survival pair could NOT
 * isolate the keepalive: the tmux `-CC` control channel itself refreshes inbound
 * activity (`RealSshSession`, `onInboundActivity = ::recordInboundActivity`), so the
 * oracle stayed proven-alive even with the keepalive effectively off — the reviewer
 * proved that pair vacuous on emulator + Docker. Hence the survival pin pivoted to
 * the keepalive layer; this connected file keeps only the genuinely non-vacuous
 * recovery proof (the half-open blackhole kills ALL inbound, `-CC` included, so the
 * keepalive's dead-detection budget is the real variable).
 */
@RunWith(AndroidJUnit4::class)
class NatIdleMappingSurvivalE2eTest : NetworkFaultProofBase() {

    private var diagnostics: RecordingDiagnosticSink? = null

    @Before
    fun installDiagnostics() {
        clearLastSessionPrefs()
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        // Disable the foreground `-CC` LivenessProbe so the transport keepalive is
        // the SOLE dead-transport detector for the recovery arm — i.e. the recovery
        // is genuinely driven by the keepalive's budget, not the probe's (which would
        // otherwise race it). The keepalive timing is set per-method below.
        LivenessProbeTestOverride.setForTest(
            intervalMs = PROBE_DISABLED_INTERVAL_MS,
            perProbeTimeoutMs = PROBE_DISABLED_TIMEOUT_MS,
            failureThreshold = PROBE_FAILURE_THRESHOLD,
        )
    }

    @After
    fun clearOverrides() {
        LivenessProbeTestOverride.clear()
        KeepAliveTestOverride.clear()
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
    }

    /**
     * ARM 2, REAL-WIRE recovery (toxiproxy `timeout=0` half-open). The carrier NAT
     * reaps the mapping MID-IDLE (half-open blackhole — no RST/FIN). The (short)
     * keepalive detects the dead transport within its `countMax × interval` budget
     * and drives recovery; once the link is restored the session returns to
     * Connected and a post-recovery send round-trips.
     */
    @Test
    fun severedIdleNatMappingRecoversWithinKeepAliveBudget() { runBlocking<Unit> {
        assumeNetworkFaultProofsEnabled()
        // SHORT keepalive so the dead-transport detection budget (interval × count)
        // is small and deterministic: 3s × 3 = ~9s.
        KeepAliveTestOverride.setForTest(
            intervalMs = RECOVERY_KEEPALIVE_INTERVAL_MS,
            countMax = KEEPALIVE_COUNT_MAX,
        )

        val key = readFixtureKey()
        val marker = "rc${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue1063-recover-$marker"
        val hostName = "Issue1063 NatRecover $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE1063-RECOVER-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachToSession(hostRowTag, hostName, sessionName)
        sendCommandThroughTerminalInput("printf 'LIVE-$marker\\n'", "pre-sever-live")
        waitForVisibleTerminalText("pre-sever-live") { "LIVE-$marker" in it }
        waitForConnected("initial attach")
        captureViewport("issue1063-recover-01-attached")

        // Idle briefly, then SEVER the mapping mid-idle (half-open blackhole).
        SystemClock.sleep(2_000)
        val severStart = SystemClock.elapsedRealtime()
        toxiproxy().addBlackhole()
        recordTiming("recover_severed_at_ms", severStart)

        // The keepalive must DETECT the dead half-open transport within its budget
        // and surface the connection-lost band (the user-visible recovery signal).
        waitForDisconnectBand("severed NAT mapping", detectionBudgetMs = KEEPALIVE_DEATH_DETECT_TIMEOUT_MS)
        val detectMs = SystemClock.elapsedRealtime() - severStart
        recordTiming("recover_detect_ms", detectMs)

        // Restore the link (the device gets a fresh path / the NAT remaps on the
        // reconnect) so recovery can complete.
        toxiproxy().clearToxics()

        // Recovery: the session returns to Connected and a post-recovery send
        // round-trips — recovery within the keepalive budget + a reconnect.
        waitForConnected("after severed-mapping recovery", timeoutMs = RECOVERY_CONNECTED_TIMEOUT_MS)
        sendCommandThroughTerminalInput("printf 'RECOV-$marker\\n'", "post-recovery")
        waitForVisibleTerminalText("post-recovery round-trip") { "RECOV-$marker" in it }
        captureViewport("issue1063-recover-02-recovered")

        assertTrue(
            "the keepalive must detect the severed half-open mapping within its budget " +
                "(${KEEPALIVE_DEATH_BUDGET_MS}ms = ${RECOVERY_KEEPALIVE_INTERVAL_MS}ms x " +
                "$KEEPALIVE_COUNT_MAX); detected after ${detectMs}ms",
            detectMs <= KEEPALIVE_DEATH_DETECT_TIMEOUT_MS,
        )

        writeSummary(
            testName = "NatIdleMappingSurvival-recover",
            lines = listOf(
                "session=$sessionName",
                "fixture=network-fault-proxy:$NETWORK_FAULT_SSH_PORT (toxiproxy half-open)",
                "keepalive_override=${RECOVERY_KEEPALIVE_INTERVAL_MS}ms x $KEEPALIVE_COUNT_MAX (SHORT)",
                "keepalive_death_budget_ms=$KEEPALIVE_DEATH_BUDGET_MS",
                "detect_ms=$detectMs",
                "expectation=keepalive detects half-open within budget => recovery + send",
            ),
        )
    } }

    // -- helpers ------------------------------------------------------------------

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        launchedActivity?.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun waitForConnected(label: String, timeoutMs: Long = CONNECTED_TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let {
            val file = artifactFile("$name-viewport.png")
            java.io.FileOutputStream(file).use { out ->
                check(it.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "failed to write bitmap to ${file.absolutePath}"
                }
            }
            println("ISSUE1063_VIEWPORT ${file.absolutePath}")
            it.recycle()
        }
        artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText())
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private companion object {
        // SHORT keepalive (Arm 2 recovery): a small, deterministic dead-transport
        // detection budget = interval × countMax = 3s × 3 = ~9s.
        const val RECOVERY_KEEPALIVE_INTERVAL_MS: Long = 3_000L
        const val KEEPALIVE_COUNT_MAX: Int = 3
        const val KEEPALIVE_DEATH_BUDGET_MS: Long =
            RECOVERY_KEEPALIVE_INTERVAL_MS * KEEPALIVE_COUNT_MAX // ~9s

        // Disable the foreground `-CC` LivenessProbe for the recovery arm so the
        // keepalive is the SOLE dead-transport detector (probe interval ≫ any hold).
        const val PROBE_DISABLED_INTERVAL_MS: Long = 3_600_000L
        const val PROBE_DISABLED_TIMEOUT_MS: Long = 5_000L
        const val PROBE_FAILURE_THRESHOLD: Int = 4

        // Issue #1676 — these budgets are slow-hardware UNCONDITIONALLY. This proof
        // only ever runs on the slow emulator + Docker / toxiproxy path (opt-in
        // gated; self-skips otherwise), and the nightly runs it WITHOUT
        // `pocketshellCi=true`, so keying the ceilings off `isRunningOnCi()` silently
        // used the tight dev-box LOCAL values on the SLOWEST swiftshader hardware —
        // the cohort's root cause: this test's `waitForDisconnectBand` blew its 30s
        // LOCAL budget on ~6/8 nights while passing on faster nights. The detection
        // itself is bounded by the SHORT keepalive death budget ([KEEPALIVE_DEATH_
        // BUDGET_MS] = 9s); the ceiling below covers that nominal 9s plus generous
        // swiftshader tick-stretch (~5×), so the load-bearing "detect within budget"
        // assertion still constrains a real slow-keepalive regression (which would
        // exceed even 50s) while absorbing runner jitter.
        const val CONNECTED_TIMEOUT_MS: Long = 40_000L

        const val KEEPALIVE_DEATH_DETECT_TIMEOUT_MS: Long = 50_000L
        const val RECOVERY_CONNECTED_TIMEOUT_MS: Long = 60_000L
    }
}
