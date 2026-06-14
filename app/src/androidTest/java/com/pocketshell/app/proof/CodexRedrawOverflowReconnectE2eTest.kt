package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.tmux.TmuxClientDiagnostics
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #576 / J4 — deterministic RED harness for the Codex alt-screen-redraw
 * -> tmux `-CC` overflow -> command-timeout(fatal) -> reader EOF -> reconnect
 * regression the maintainer reproduced live on v0.4.0.
 *
 * ## The production flaw this reproduces (code-traced in the J4 spike)
 *
 * The `tmux -CC` control-mode reader is a single serial byte stream. A command's
 * `%begin`/`%end` response arrives **in-band, after** every `%output` byte tmux
 * already queued ahead of it. `TmuxClient.sendCommandInternal` awaits that
 * response inside a `commandTimeoutGate.run(commandTimeoutMs)` with
 * `commandTimeoutMs = 10_000`. `timeoutModeForCommand` maps **every non-`send-keys`
 * command** (`capture-pane`, `display-message`, `refresh-client`, `list-*`) to
 * `CommandTimeoutMode.FatalClose`. So when a heavy Codex full-screen alt-screen
 * redraw produces a `%output` backlog that takes **>10 s to drain**, an in-flight
 * control command times out, is classified `FatalClose`, calls
 * `closeInternal(CommandTimeout)` -> the reader loop exits -> `_disconnected = true`
 * -> the inline `TmuxSessionViewModel` reconnect path fires. **Nothing actually
 * disconnected** — a busy-but-alive link tore down a healthy control channel.
 *
 * The existing [CodexOverflowNoReconnectE2eTest] PASSES precisely because its
 * flood is **rate-limited** (`sleep 0.05` every 20 lines), so a command response
 * never sits behind >10 s of backlog. This proof removes that throttle AND caps
 * the link bandwidth so the relationship `redraw_bytes / rate > 10 s` is pinned
 * deterministically instead of racing a fast clean emulator<->Docker link.
 *
 * ## The deterministic lever
 *
 *  1. Seed an **unthrottled** alt-screen full-grid repaint on the `agents`
 *     fixture (the unthrottled cousin of the existing rate-limited seed).
 *  2. Route a production `TmuxClient` through the #552 toxiproxy proxy
 *     (`:2228`) and cap the **downstream** bandwidth with a stock `bandwidth`
 *     toxic ([ToxiproxyControl.addBandwidthLimit]) sized so one repaint's bytes
 *     cannot drain within the 10 s command-timeout window.
 *  3. Issue an ordinary non-`send-keys` control command (`capture-pane -p`) —
 *     the exact shape of the VM's reseed/probe traffic — while the backlog is
 *     building.
 *
 * ## Assertions = the CORRECT behaviour (so RED now, GREEN after the fix)
 *
 * The test asserts that a busy-but-alive redraw must NOT self-inflict a teardown:
 *
 *  - NO `tmux_client_command_timeout` with `timeoutMode=fatal`
 *  - NO `tmux_client_reader_exit` with `disconnectCause=command_timeout`
 *  - the production client stays `disconnected = false`
 *  - NO visible "Reconnecting" band on the app journey
 *
 * On current `main` the overflow trips ALL of these (the bug), so the test FAILS
 * RED today — that RED is the deliverable, proving #576 reproduces. After the
 * separate P4 connection-core production fix (command-deadline accounting /
 * `FatalClose` policy), the same stimulus produces none of them and the redraw
 * keeps draining, flipping this proof GREEN. It is the inverse of
 * [CodexOverflowNoReconnectE2eTest] (same diagnostics, opposite stimulus: this one
 * is unthrottled + bandwidth-capped so the backlog actually exceeds 10 s), so the
 * two tests bracket the fix.
 *
 * ## CI placement
 *
 * This subclasses [NetworkFaultProofBase], so it self-skips on per-PR CI
 * ([assumeNetworkFaultProofsEnabled] / `Assume.assumeFalse(isRunningOnCi())`):
 * the toxiproxy `network-fault-proxy` fixture is only started by the
 * nightly-extensive lane, never the per-PR `emulator-journey` job. A RED proof
 * must never run per-PR. It is also intentionally NOT added to
 * `scripts/ci-journey-suite.sh`.
 *
 * Artifact contract (process.md "Terminal Artifact Review"):
 *  - `issue576-redraw-01-attached-viewport.png` + `-visible-terminal.txt`
 *  - `issue576-redraw-02-after-overflow-viewport.png` + `-visible-terminal.txt`
 *  - `CodexRedrawOverflowReconnectE2eTest-summary.txt` + `timings`
 */
@RunWith(AndroidJUnit4::class)
class CodexRedrawOverflowReconnectE2eTest : NetworkFaultProofBase() {

    private val tmuxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeConnection: DirectTmuxConnection? = null
    private var redrawDiagnostics: RecordingTmuxDiagnosticSink? = null
    private var seededKey: String? = null

    @Before
    fun installRedrawDiagnostics() {
        redrawDiagnostics = RecordingTmuxDiagnosticSink().also { TmuxClientDiagnostics.install(it) }
    }

    @After
    fun closeRedrawHarness() {
        runCatching { activeConnection?.close() }
        activeConnection = null
        redrawDiagnostics?.close()
        redrawDiagnostics = null
        seededKey?.let { key -> runBlocking { runCatching { cleanupRedrawSession(key) } } }
        runCatching { tmuxScope.cancel() }
    }

    @Test
    fun codexAltScreenRedrawOverflowTimesOutFatalAndReconnects() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        seededKey = key
        val marker = System.currentTimeMillis().toString(36).takeLast(5)
        val sessionName = "issue576-redraw-$marker"

        // Reset proxy + ensure fixture reachable both directly and via the proxy.
        toxiproxy().reset()
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        // Seed a TRIGGER-GATED redraw: the pane prints REDRAW_MARKER then waits on
        // a trigger file before starting the unthrottled flood. This lets the app
        // (and the direct client) attach to a CALM pane first — an
        // attach-into-full-flood saturates the app's terminal pipeline and never
        // reaches the overflow stimulus.
        seedTriggerGatedAltScreenRedraw(key, sessionName)
        waitForSshFixtureReady(SshKey.Pem(key), port = NETWORK_FAULT_SSH_PORT)

        // ---- (1) Attach the full app journey through the proxy so the visible
        // "Reconnecting" band is observable when the inline VM path reacts to the
        // production client's disconnect. The app attaches through the SAME proxy
        // and SAME bandwidth cap, so its production client hits the same wall.
        val hostRowTag = seedNetworkFaultHost(key, hostName = HOST_NAME)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachToSession(hostRowTag, HOST_NAME, sessionName)
        waitForVisibleTerminalText("redraw marker") { it.contains(REDRAW_MARKER) }
        captureViewport("issue576-redraw-01-attached")
        assertTrue(
            "expected no reconnect band before the overflow",
            visibleReconnectBandCount() == 0,
        )

        // ---- (2) Open a production TmuxClient through the proxy while the pane is
        // still calm (fast handshake), THEN cap downstream bandwidth, THEN trigger
        // the unthrottled redraw. With the alt-screen repaint producing far faster
        // than RATE_KBPS can drain, the %output backlog grows without bound, so any
        // control command's %begin/%end response is perpetually queued behind >10 s
        // of backlog.
        activeConnection = openDirectTmuxConnection(key, sessionName, tmuxScope)
        val client = requireNotNull(activeConnection).client

        val proxy = toxiproxy()
        proxy.addBandwidthLimit(RATE_KBPS)
        recordTiming("bandwidth_rate_kbps", RATE_KBPS.toLong())
        triggerRedraw(key, sessionName)

        // Let the flood RUN under the cap so a deep %output backlog accumulates in
        // the pipe ahead of any later command response. The flood produces ~12 KB
        // per repaint cycle continuously while the link drains only RATE_KBPS, so
        // after BACKLOG_BUILD_MS the in-pipe backlog is many seconds deep — a
        // command issued now cannot be answered for >10 s. (Issuing capture-pane
        // the instant the trigger fires lets the response race AHEAD of the flood.)
        SystemClock.sleep(BACKLOG_BUILD_MS)

        // ---- (3) Issue a non-`send-keys` control command (up to MAX_CAPTURE_TRIES
        // times, in case the very first lands before the backlog is deep enough).
        // This deterministically drives the FatalClose path: the capture-pane
        // %begin/%end response sits behind the capped redraw backlog and trips
        // commandTimeoutMs (10 s).
        val overflowStart = SystemClock.elapsedRealtime()
        var captureThrew = false
        var tries = 0
        while (tries < MAX_CAPTURE_TRIES && !client.disconnected.value) {
            tries += 1
            captureThrew = runCatching {
                // capture-pane is a non-`send-keys` command => FatalClose on timeout.
                client.sendCommand("capture-pane -p")
            }.isFailure
            if (captureThrew || client.disconnected.value) break
        }
        recordTiming("capture_pane_tries", tries.toLong())
        recordTiming("capture_pane_outcome_ms", SystemClock.elapsedRealtime() - overflowStart)

        // Collect the failure signature. This test asserts the CORRECT behaviour
        // (a busy-but-alive redraw must NOT self-inflict a reconnect), so it FAILS
        // RED on current `main` — where the overflow trips the FatalClose path —
        // and turns GREEN once the P4 connection-core fix lands. It is the inverse
        // of [CodexOverflowNoReconnectE2eTest]: same diagnostics, opposite stimulus
        // (unthrottled + bandwidth-capped so the backlog actually exceeds 10 s).
        val tmux = requireNotNull(redrawDiagnostics)

        val fatalTimeouts = tmux.eventsNamed("tmux_client_command_timeout")
            .filter { it.fields["timeoutMode"] == "fatal" }
        val commandTimeoutReaderExits = tmux.eventsNamed("tmux_client_reader_exit")
            .filter { it.fields["disconnectCause"] == "command_timeout" }
        val clientDisconnected = client.disconnected.value

        // ---- (4) The user-visible reconnect band the maintainer reported. The
        // inline VM path observes the same overflow on its own production client
        // and surfaces "Reconnecting". On `main` this band appears (the symptom);
        // after the fix it must not.
        val sawReconnectBand = waitForVisibleReconnectBand(RECONNECT_BAND_TIMEOUT_MS)
        captureViewport("issue576-redraw-02-after-overflow")

        writeSummary(
            testName = "CodexRedrawOverflowReconnectE2eTest",
            lines = listOf(
                "scenario=codex-altscreen-redraw-overflow->command-timeout-fatal->reader-eof->reconnect",
                "issue=576",
                "session=$sessionName",
                "marker=$marker",
                "redraw=trigger-gated unthrottled alt-screen full-grid repaint (no inter-line sleep)",
                "toxic=bandwidth downstream rate=${RATE_KBPS}KB/s",
                "command_under_backlog=capture-pane -p (non-send-keys => FatalClose)",
                "capture_threw=$captureThrew",
                "fatal_command_timeouts=${fatalTimeouts.size}",
                "command_timeout_reader_exits=${commandTimeoutReaderExits.size}",
                "client_disconnected=$clientDisconnected",
                "visible_reconnect_band=$sawReconnectBand",
                "expectation=RED on current main (bug reproduces); GREEN after the P4 production fix",
            ),
        )

        // ---- Acceptance assertions (the CORRECT behaviour). RED on `main`: the
        // overflow currently DOES trip a fatal command timeout, reader EOF, client
        // disconnect, and a visible reconnect band — so these all FAIL today and
        // turn GREEN when the P4 backpressure/command-deadline fix stops a
        // busy-but-alive redraw from tearing down a healthy control channel.
        assertTrue(
            "#576: a busy-but-alive redraw must NOT trip a FatalClose command timeout " +
                "(this FAILS on main = the bug; GREEN after P4). fatal command_timeout " +
                "events=$fatalTimeouts",
            fatalTimeouts.isEmpty(),
        )
        assertTrue(
            "#576: the reader must NOT exit with a CommandTimeout cause from a redraw " +
                "backlog (FAILS on main; GREEN after P4). reader_exit events=" +
                "$commandTimeoutReaderExits",
            commandTimeoutReaderExits.isEmpty(),
        )
        assertFalse(
            "#576: the production client must STAY connected through the redraw — a " +
                "busy link is not a dead one (FAILS on main; GREEN after P4). " +
                "captureThrew=$captureThrew",
            clientDisconnected,
        )
        assertFalse(
            "#576: NO visible 'Reconnecting' band must surface — the maintainer's " +
                "exact symptom (Codex redraw -> session drops into Reconnecting). " +
                "This FAILS on main (the bug reproduces) and is GREEN after the P4 fix.",
            sawReconnectBand,
        )

        Log.i(
            LOG_TAG,
            "#576 RED reproduced: fatal=$fatalTimeouts readerExit=$commandTimeoutReaderExits band=$sawReconnectBand",
        )
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    /**
     * Seed a tmux session whose pane prints [REDRAW_MARKER], waits on a trigger
     * file, then enters the alternate screen and repaints the entire grid as fast
     * as the pipe allows — NO inter-line sleep. This is the unthrottled cousin of
     * [CodexOverflowNoReconnectE2eTest]'s rate-limited seed and the shape a heavy
     * Codex full-screen redraw produces. The trigger gate keeps the pane CALM
     * until the app + direct client have attached, so the attach never has to
     * fight a full-bandwidth flood. Each repaint row is a long full-width line of
     * churn, so per-repaint byte volume comfortably exceeds the capped link's 10 s
     * drain budget once triggered.
     */
    private suspend fun seedTriggerGatedAltScreenRedraw(key: String, sessionName: String) {
        val longLine = "X".repeat(200)
        // Remote shell script: print marker, wait for the trigger file, then flood.
        val paneScript = buildString {
            append("printf '$REDRAW_MARKER\\r\\n'; ")
            append("while [ ! -e ${shellQuote(REMOTE_TRIGGER)} ]; do sleep 0.1; done; ")
            append("printf '\\033[?1049h'; ") // enter alternate screen (the TUI/Codex case)
            append("printf '\\033[2J\\033[H'; ")
            // Continuous full-grid repaint, no inter-line sleep: home, then repaint
            // every row of a 60-row grid with a 200-col churn line.
            append("while true; do printf '\\033[H'; i=0; ")
            append("while [ \$i -lt 60 ]; do printf '\\033[K$longLine\\r\\n'; i=\$((i+1)); done; ")
            append("done")
        }
        val script = buildString {
            appendLine("set -eu")
            appendLine("rm -f ${shellQuote(REMOTE_TRIGGER)}")
            appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(sessionName)} -x 200 -y 60 " +
                    shellQuote(paneScript),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected trigger-gated alt-screen redraw seed to succeed; " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded trigger-gated redraw: ${exec?.stdout?.trim()}")
    }

    /** Release the trigger gate so the seeded pane starts the unthrottled flood. */
    private suspend fun triggerRedraw(key: String, sessionName: String) {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec("touch ${shellQuote(REMOTE_TRIGGER)}") } }
        val exec = result.getOrNull()
        assertTrue(
            "expected redraw trigger to succeed for $sessionName; " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "triggered unthrottled redraw for $sessionName")
    }

    private suspend fun cleanupRedrawSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    // Kill any issue576-redraw-* session left behind by this run and
                    // remove the trigger file.
                    it.exec(
                        "for s in \$(tmux list-sessions -F '#{session_name}' 2>/dev/null | " +
                            "grep '^issue576-redraw'); do tmux kill-session -t \"\$s\" 2>/dev/null || true; done; " +
                            "rm -f ${shellQuote(REMOTE_TRIGGER)}",
                    )
                }
            }
        }
    }

    private fun visibleReconnectBandCount(): Int =
        compose
            .onAllNodesWithText("Reconnecting", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    private fun waitForVisibleReconnectBand(timeoutMillis: Long): Boolean =
        runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                visibleReconnectBandCount() > 0
            }
            true
        }.getOrDefault(false)

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
                ?: activity.findViewById<View>(android.R.id.content)
                ?: activity.window.decorView
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE576_REDRAW_VIEWPORT ${file.absolutePath}")
        return file
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val LOG_TAG: String = "Issue576CodexRedraw"
        const val HOST_NAME: String = "Issue576 Codex Redraw"
        const val REDRAW_MARKER: String = "ISSUE576-REDRAW-MARKER"
        const val REMOTE_TRIGGER: String = "/tmp/pocketshell-issue576-redraw.trigger"

        /**
         * Downstream cap. The unthrottled alt-screen redraw produces ~12 KB per
         * repaint cycle (60 rows x ~200 cols) continuously, so at 8 KB/s the
         * `%output` backlog grows without bound and a control command's response
         * is perpetually queued behind >10 s of it — `redraw_bytes / rate > 10 s`
         * is pinned, making the FatalClose deterministic rather than a clean-link
         * race (the Phase-0 @Ignore'd scaffold was racy precisely because it had
         * no bandwidth cap).
         */
        const val RATE_KBPS: Int = 8

        /**
         * How long to let the flood run under the cap before issuing the control
         * command, so a deep in-pipe `%output` backlog accumulates ahead of the
         * command response. At ~12 KB/repaint produced vs 8 KB/s drained, ~6 s of
         * flood queues well over 10 s of un-drained output, guaranteeing the
         * command response cannot arrive within commandTimeoutMs.
         */
        const val BACKLOG_BUILD_MS: Long = 6_000L

        /** Issue capture-pane a few times in case the first races ahead of the backlog. */
        const val MAX_CAPTURE_TRIES: Int = 3

        /**
         * The app's OWN inline VM client must also overflow (its periodic
         * reseed/probe control command times out FatalClose) before the visible
         * "Reconnecting" band surfaces, which lags the direct-client signature by a
         * full timeout + close + backoff cycle (observed ~60 s from trigger). Give
         * the band a generous window so the user-visible symptom is captured, not
         * raced against.
         */
        val RECONNECT_BAND_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 75_000L
    }
}
