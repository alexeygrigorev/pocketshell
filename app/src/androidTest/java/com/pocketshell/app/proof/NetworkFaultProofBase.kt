package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FolderListViewModel
import com.pocketshell.app.projects.folderDetailRowTestTag
import com.pocketshell.app.projects.folderHeaderClickTestTag
import com.pocketshell.app.projects.folderRowTestTag
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared harness for opt-in network-fault proof tests.
 *
 * The tests route PocketShell through the Docker `network-fault-proxy`
 * service on host port 2228. Control traffic uses Toxiproxy's HTTP API
 * on host port 8474. Toxiproxy documents `timeout=0` as a non-closing
 * data drop, which gives us a half-open/no-FIN failure instead of the
 * existing EOF/process-kill fixtures.
 */
abstract class NetworkFaultProofBase {

    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found"). Inherited
    // by every NetworkFaultProofBase subclass.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    protected var launchedActivity: ActivityScenario<MainActivity>? = null
    protected val timings: MutableList<String> = mutableListOf()
    private var networkFaultProofEnabled: Boolean = false

    @After
    fun closeNetworkFaultActivity() {
        // Issue #970: a test whose journey already drove the activity to FINISHED
        // (e.g. a sustained slow/reconnect window) leaves `ActivityScenario.close()`
        // to call `moveToState` on a null current state -> a teardown NPE that would
        // red an otherwise-PASSING body. The close is best-effort cleanup, never a
        // load-bearing assertion, so swallow it (the body's asserts already ran).
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        if (networkFaultProofEnabled) {
            runCatching { toxiproxy().reset() }
        }
    }

    protected fun assumeNetworkFaultProofsEnabled() {
        Assume.assumeFalse(
            "Network-fault proofs require opt-in Docker proxy fixtures; tests.yml does not start them.",
            TerminalTestTimeouts.isRunningOnCi(),
        )
        val enabled = InstrumentationRegistry.getArguments()
            .getString(NETWORK_FAULT_ARG)
            ?.toBooleanStrictOrNull() == true
        Assume.assumeTrue(
            "Enable with -Pandroid.testInstrumentationRunnerArguments.$NETWORK_FAULT_ARG=true " +
                "after starting `docker compose -f tests/docker/docker-compose.yml up -d --build " +
                "agents network-fault-proxy packet-loss-proxy`.",
            enabled,
        )
        networkFaultProofEnabled = true
    }

    protected fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    protected fun toxiproxy(): ToxiproxyControl =
        ToxiproxyControl(baseUrl = "http://$DEFAULT_HOST:$TOXIPROXY_API_PORT")

    /**
     * Issue #1681 — open the UN-PROXIED sentinel: a direct SSH connection to the
     * fixture SSH port (2222, no toxiproxy in the path) that exec-pings on a fixed
     * cadence for the whole storm window. It is the "everything else works fine"
     * control — because it never rides the delayed proxy, it hard-proves the host
     * + sshd + network path stayed perfectly healthy throughout, so ANY lease
     * death the app suffers over the merely-DELAYED (never-severed) proxy is
     * SELF-INFLICTED by construction, not a real remote drop. [SshSentinel.stop]
     * must be called in teardown.
     */
    protected suspend fun openUnProxiedSentinel(
        key: String,
        scope: CoroutineScope,
        intervalMs: Long = SENTINEL_INTERVAL_MS,
    ): SshSentinel = SshSentinel.open(key, scope, intervalMs)

    protected suspend fun prepareProxyAndRemoteSession(
        key: String,
        sessionName: String,
        readyText: String,
    ) {
        toxiproxy().reset()
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        seedTmuxSession(key, sessionName, readyText)
        waitForSshFixtureReady(SshKey.Pem(key), port = NETWORK_FAULT_SSH_PORT)
    }

    /**
     * Seed an ADDITIONAL tmux session on the same fixture without resetting the
     * proxy — used by multi-session switch proofs that need a second session to
     * switch to. Goes through the direct SSH port so it works regardless of the
     * proxy toxic state.
     */
    protected suspend fun seedExtraSession(
        key: String,
        sessionName: String,
        readyText: String,
    ) {
        seedTmuxSession(key, sessionName, readyText)
    }

    protected suspend fun preparePacketLossProxyAndRemoteSession(
        key: String,
        sessionName: String,
        readyText: String,
    ) {
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        seedTmuxSession(key, sessionName, readyText)
        waitForSshFixtureReady(SshKey.Pem(key), port = PACKET_LOSS_SSH_PORT)
    }

    protected suspend fun seedNetworkFaultHost(
        key: String,
        hostName: String,
        port: Int = NETWORK_FAULT_SSH_PORT,
    ): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "network-fault-key-${System.currentTimeMillis()}",
                content = key,
            )
            // Keep this aligned with HostListViewModel.canUseBootstrapCache:
            // the connected proof wants host-card tap -> folder/session list,
            // not setup/probe, so all cached tool/daemon checks must be fresh.
            val now = System.currentTimeMillis()
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = now,
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = now,
                    pocketshellVersionCompatible = true,
                    pocketshellDaemonRunning = true,
                    pocketshellDaemonEnabled = true,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    protected fun attachToSession(hostRowTag: String, hostName: String, sessionName: String) {
        val pickerTimeoutMs = TerminalTestTimeouts.terminalVisibilityTimeoutMs()
        waitUntilWithDiagnostics(
            label = "host row $hostName",
            timeoutMillis = pickerTimeoutMs,
            textProbes = listOf(hostName),
            tagProbes = listOf(hostRowTag),
        ) {
            hasTag(hostRowTag)
        }
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        openSessionFromList(hostName, sessionName)
    }

    /**
     * Tap a session row from the host-detail folder/session list (the screen
     * reached after a host-row tap, or after pressing Back out of the terminal).
     * Splits out of [attachToSession] so multi-session switch proofs can open a
     * second session without re-navigating from the host list.
     */
    protected fun openSessionFromList(hostName: String, sessionName: String) {
        val pickerTimeoutMs = TerminalTestTimeouts.terminalVisibilityTimeoutMs()
        var folderPath = FolderListViewModel.UNTRACKED_PATH
        waitUntilWithDiagnostics(
            label = "folder row for $sessionName",
            timeoutMillis = pickerTimeoutMs,
            textProbes = listOf(hostName, sessionName),
            tagProbes = NETWORK_FAULT_FOLDER_CANDIDATES.map(::folderRowTestTag),
        ) {
            val match = NETWORK_FAULT_FOLDER_CANDIDATES.firstOrNull { candidate ->
                hasTag(folderRowTestTag(candidate))
            }
            if (match != null) {
                folderPath = match
                true
            } else {
                false
            }
        }
        expandFolderUntilSessionRowVisible(folderPath, sessionName, pickerTimeoutMs)
        val sessionRowTag = folderDetailRowTestTag(folderPath, sessionName)
        compose.onNodeWithTag(sessionRowTag, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminalText("tmux pane ready") { it.isNotBlank() }
    }

    protected fun sendCommandThroughTerminalInput(command: String, label: String) {
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input commit for $label chunk `$chunk`", committed)
            SystemClock.sleep(35)
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input submit for $label", enterCommitted)
    }

    /**
     * Issue #1676 — the generous slow-link visibility budget every network-fault
     * proof deserves. These proofs ONLY ever run on the slow emulator + Docker /
     * toxiproxy path (opt-in gated; they self-skip otherwise), frequently behind a
     * deliberately-injected multi-second bufferbloat / latency toxic, so a positive
     * "text became visible" wait must allow the slow link its full drain time. It
     * early-exits the instant the text appears, so a fast pass pays nothing; the
     * generous ceiling only matters when a genuinely slow-but-progressing link is
     * draining. Keying the whole cohort's ceilings off `pocketshellCi` alone was the
     * root cause of the #1676 correlated cohort: the nightly runs these WITHOUT
     * `pocketshellCi=true`, so `terminalVisibilityTimeoutMs()` silently returned the
     * tight 60s dev-box LOCAL value on the SLOWEST swiftshader hardware — exactly the
     * ceiling `ColdDialUnderBandwidthLimitE2eTest`'s post-dial echo blew (~98.8s vs
     * 60s) on bad nights. This budget is the CI terminal-visibility value (180s),
     * applied unconditionally because there is no fast-hardware case for a toxiproxy
     * proof; it stays well under the 300s per-test ci-journey watchdog.
     */
    protected fun faultProofVisibilityBudgetMs(): Long = FAULT_PROOF_VISIBILITY_BUDGET_MS

    protected fun waitForVisibleTerminalText(
        label: String,
        timeoutMillis: Long = faultProofVisibilityBudgetMs(),
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            artifactFile("failure-$label-visible-terminal.txt").writeText(last.printableForFailure())
        }
        assertTrue(
            "expected visible terminal text for $label, got:\n${last.printableForFailure()}",
            predicate(last),
        )
    }

    /**
     * Issue #1676 — the load-bearing budget for the disconnect band to surface,
     * with a reproduce-first measure-PAST-budget capture (D33/G10).
     *
     * ## Why the budget is what it is (measured from production, NOT guessed)
     *
     * The band is surfaced by the production dead / half-open transport detectors,
     * whose worst-case detection latencies are DOCUMENTED PRODUCTION CONSTANTS:
     *
     *  - the app-level `com.pocketshell.core.connection.LivenessProbe` — the
     *    half-open / `-CC`-wedged detector for the blackhole + sustained-cut cohort —
     *    has a worst case of
     *    `failureThreshold × (intervalMs + perProbeTimeoutMs)` = `4 × (7s + 5s)` = **48s**;
     *  - the always-on `com.pocketshell.core.ssh.TransportKeepAlive` — the
     *    silent-peer detector (e.g. `NatIdleMappingSurvivalE2eTest`) — is
     *    `countMax × intervalMs` (prod 90s; NatIdle's override 3 × 3s = 9s).
     *
     * The pre-#1676 flat **35s** default was SMALLER than the LivenessProbe's own
     * **48s** worst case — so a perfectly-functioning half-open detection could hit
     * its documented budget and STILL time out the test. On the CI swiftshader runner
     * the `delay()`-driven detector ticks stretch further under load, so the 35s
     * ceiling blew on ~6/8 nights (the #1676 correlated cohort) even though detection
     * was working. That is a HARNESS under-budgeting (a wall-clock flake), NOT a
     * slow-detection `core-connection` regression:
     * `Issue1676DisconnectDetectionLatencyTest` (core-connection, per-push Unit gate)
     * proves the detection logic fires at exactly its deterministic budget on a
     * virtual clock, decoupled from wall-clock.
     *
     * [detectionBudgetMs] therefore defaults to [disconnectBandBudgetMs] — the
     * production detector worst case plus slow-emulator headroom. The LOAD-BEARING
     * assertion ("the band surfaces within [detectionBudgetMs]") is unchanged and NOT
     * widened into a meaningless band (G6): a genuinely never-surfacing or
     * far-over-budget regression STILL fails, and the TRUE latency is captured up to
     * [captureCeilingMs] (recorded even when the budget is blown) so the next nightly
     * emits the decisive flake-vs-regression number instead of just "did not appear".
     */
    protected fun waitForDisconnectBand(
        label: String,
        detectionBudgetMs: Long = disconnectBandBudgetMs(),
        captureCeilingMs: Long = DISCONNECT_BAND_CAPTURE_CEILING_MS,
    ) {
        val start = SystemClock.elapsedRealtime()
        // Poll PAST the load-bearing budget up to a generous capture ceiling so the
        // TRUE detection latency is recorded even on a blown budget (reproduce-first).
        val appeared = runCatching {
            compose.waitUntil(timeoutMillis = captureCeilingMs) {
                compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            true
        }.getOrDefault(false)
        val detectMs = SystemClock.elapsedRealtime() - start
        recordTiming("${label}_disconnect_visible_ms", detectMs)
        recordTiming("${label}_disconnect_appeared", if (appeared) 1L else 0L)
        recordTiming("${label}_disconnect_budget_ms", detectionBudgetMs)
        artifactFile("disconnect-band-latency-$label.txt").writeText(
            buildString {
                appendLine("label=$label")
                appendLine("appeared=$appeared")
                appendLine("disconnect_visible_ms=$detectMs")
                appendLine("detection_budget_ms=$detectionBudgetMs")
                appendLine("capture_ceiling_ms=$captureCeilingMs")
                appendLine("within_budget=${appeared && detectMs <= detectionBudgetMs}")
            },
        )
        val within = appeared && detectMs <= detectionBudgetMs
        assertTrue(
            if (appeared) {
                "expected the disconnect band for $label to surface within " +
                    "${detectionBudgetMs}ms (production half-open detection worst case " +
                    "~48s, LivenessProbe); it surfaced after ${detectMs}ms" +
                    if (within) "" else " — OVER budget (real slow-detection regression?)"
            } else {
                "expected the disconnect band for $label to surface within " +
                    "${detectionBudgetMs}ms; it NEVER surfaced within the ${captureCeilingMs}ms " +
                    "capture ceiling (real detection regression — band never appeared)"
            },
            within,
        )
        val reconnectActions = compose.onAllNodesWithTag(
            TMUX_SESSION_RECONNECT_TAG,
            useUnmergedTree = true,
        )
            .fetchSemanticsNodes()
        assertTrue(
            "expected reconnect affordance for $label; found ${reconnectActions.size}",
            reconnectActions.isNotEmpty(),
        )
    }

    /**
     * Issue #1676 — load-bearing budget for the disconnect band to surface. These
     * proofs ONLY ever run on the slow emulator + Docker / toxiproxy path (opt-in
     * gated; they self-skip otherwise), so they always deserve the generous
     * slow-hardware ceiling — there is no "fast hardware deserves a tight budget"
     * case for a toxiproxy fault proof. The budget covers the production
     * `LivenessProbe` half-open worst case (~48s) plus swiftshader tick-stretch
     * headroom, and stays well under the 300s per-test ci-journey watchdog. See the
     * [waitForDisconnectBand] KDoc for the full derivation.
     */
    protected fun disconnectBandBudgetMs(): Long = DISCONNECT_BAND_BUDGET_MS

    protected fun tapReconnectAndWait(label: String) {
        val start = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 45_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        waitForTerminalViewAttached()
        recordTiming("${label}_reconnect_ms", SystemClock.elapsedRealtime() - start)
    }

    protected fun disconnectBandCount(): Int =
        compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    protected fun assertNoDisconnectBand(label: String) {
        val count = disconnectBandCount()
        assertTrue("expected no disconnect band for $label, found $count", count == 0)
    }

    protected fun waitForNoDisconnectBandDuring(label: String, durationMillis: Long) {
        val start = SystemClock.elapsedRealtime()
        var maxCount = 0
        while (SystemClock.elapsedRealtime() - start < durationMillis) {
            val count = disconnectBandCount()
            maxCount = maxOf(maxCount, count)
            assertTrue("expected no disconnect band during $label, found $count", count == 0)
            SystemClock.sleep(250)
        }
        recordTiming("${label}_stable_no_disconnect_ms", SystemClock.elapsedRealtime() - start)
        recordTiming("${label}_max_disconnect_bands", maxCount.toLong())
    }

    protected suspend fun listClientsCount(key: String, sessionName: String): Int =
        listClientsRaw(key, sessionName).lines().count { it.isNotBlank() }

    protected suspend fun waitForClientCountAtMost(
        key: String,
        sessionName: String,
        max: Int,
        label: String,
        timeoutMs: Long = 6_000L,
    ) {
        var last = -1
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            last = listClientsCount(key, sessionName)
            if (last <= max) return
            SystemClock.sleep(150)
        }
        assertTrue("expected at most $max tmux client(s) for $label, got $last", last <= max)
    }

    protected suspend fun openDirectTmuxConnection(
        key: String,
        sessionName: String,
        scope: CoroutineScope,
    ): DirectTmuxConnection {
        val session = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = NETWORK_FAULT_SSH_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        val client = TmuxClientFactory(scope).create(
            session = session,
            sessionName = sessionName,
        )
        try {
            client.connect()
            return DirectTmuxConnection(session = session, client = client)
        } catch (t: Throwable) {
            runCatching { client.close() }
            runCatching { session.close() }
            throw t
        }
    }

    protected suspend fun sendShellMarkerViaTmux(
        client: TmuxClient,
        marker: String,
        label: String,
    ) {
        val response = client.sendCommand("send-keys ${tmuxSingleQuoted("printf '$marker\\n'")} Enter")
        assertTrue("expected send-keys to succeed for $label, got ${response.output}", !response.isError)
    }

    protected suspend fun waitForCapturedPaneText(
        client: TmuxClient,
        expected: String,
        label: String,
        timeoutMs: Long = 10_000L,
    ) {
        var last = ""
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            last = capturePane(client).output.joinToString("\n")
            if (expected in last) return
            SystemClock.sleep(150)
        }
        artifactFile("failure-$label-capture-pane.txt").writeText(last)
        assertTrue("expected captured pane text for $label to contain $expected, got:\n$last", expected in last)
    }

    /**
     * Half-open/no-FIN link starvation for short ride-through checks. Toxiproxy
     * keeps the socket established while dropping bytes for [downMillis], then
     * removes the toxic so the same SSH/tmux connection can make progress again.
     */
    protected fun starveLinkFor(label: String, downMillis: Long) {
        val proxy = toxiproxy()
        val cutStart = SystemClock.elapsedRealtime()
        proxy.addBlackhole()
        try {
            recordTiming("${label}_link_starved_ms", downMillis)
            waitForNoDisconnectBandDuring("${label}_while_starved", downMillis)
        } finally {
            proxy.clearToxics()
            recordTiming("${label}_link_starve_total_ms", SystemClock.elapsedRealtime() - cutStart)
        }
    }

    /**
     * Clean socket-drop outage for sustained reconnect checks: the reusable
     * "cut the link for N ms, then restore" primitive. Toxiproxy disables the
     * proxy, which drops active connections and refuses new ones, holds the
     * link down for at least [downMillis], then re-enables it.
     *
     * Pass [whileDown] to run assertions/waits *during* the outage (e.g. wait
     * for the disconnect band to surface on a sustained cut); whatever wall time
     * it consumes counts toward [downMillis], and any remaining hold is slept
     * out so the link stays down for the full window. The link is always
     * restored in `finally`, even if [whileDown] throws.
     */
    protected fun disableProxyFor(
        label: String,
        downMillis: Long,
        whileDown: () -> Unit = {},
    ) {
        val proxy = toxiproxy()
        val cutStart = SystemClock.elapsedRealtime()
        proxy.disable()
        try {
            recordTiming("${label}_proxy_disabled_ms", downMillis)
            whileDown()
            val remainingHold = downMillis - (SystemClock.elapsedRealtime() - cutStart)
            if (remainingHold > 0L) {
                SystemClock.sleep(remainingHold)
            }
        } finally {
            proxy.enable()
            recordTiming("${label}_proxy_disable_total_ms", SystemClock.elapsedRealtime() - cutStart)
        }
    }

    protected fun assertNoExtraConnectAttempts(
        before: Int,
        expectedDelta: Int,
        label: String,
    ) {
        val after = TMUX_CONNECT_ATTEMPTS.get()
        val delta = after - before
        assertTrue(
            "expected $expectedDelta tmux connect attempt(s) for $label, got $delta " +
                "(before=$before after=$after)",
            delta == expectedDelta,
        )
    }

    protected fun writeSummary(testName: String, lines: List<String>) {
        artifactFile("$testName-summary.txt").writeText(
            buildString {
                appendLine("test=$testName")
                appendLine("proxy_port=$NETWORK_FAULT_SSH_PORT")
                appendLine("packet_loss_proxy_port=$PACKET_LOSS_SSH_PORT")
                appendLine("toxiproxy_api_port=$TOXIPROXY_API_PORT")
                appendLine("timings:")
                timings.forEach { appendLine(it) }
                appendLine("details:")
                lines.forEach { appendLine(it) }
            },
        )
    }

    protected fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE342_TIMING $line")
    }

    protected fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create issue #342 artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private suspend fun seedTmuxSession(key: String, sessionName: String, readyText: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(sessionName)} " +
                    "${shellQuote("printf '${escapeSingleQuotedForPrintf(readyText)}\\n'; exec sh -i")}",
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
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun listClientsRaw(key: String, sessionName: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux list-clients -t ${shellQuote(sessionName)} 2>/dev/null || true")
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private suspend fun capturePane(client: TmuxClient): CommandResponse =
        client.sendCommand("capture-pane -p")

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun expandFolderUntilSessionRowVisible(
        folderPath: String,
        sessionName: String,
        timeoutMillis: Long,
    ) {
        val detailTag = folderDetailRowTestTag(folderPath, sessionName)
        val headerTag = folderHeaderClickTestTag(folderPath)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var taps = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            if (hasTag(detailTag)) {
                recordTiming("attach_folder_expand_taps", taps.toLong())
                return
            }
            if (hasTag(headerTag)) {
                compose.onNodeWithTag(headerTag, useUnmergedTree = true).performClick()
                taps += 1
            }
            SystemClock.sleep(250)
        }
        waitUntilWithDiagnostics(
            label = "expanded folder $folderPath showing session row $sessionName after $taps tap(s)",
            timeoutMillis = 5_000,
            textProbes = listOf(sessionName, folderPath.substringAfterLast('/')),
            tagProbes = listOf(
                folderRowTestTag(folderPath),
                folderHeaderClickTestTag(folderPath),
                folderDetailRowTestTag(folderPath, sessionName),
            ),
        ) {
            hasTag(detailTag)
        }
    }

    private fun waitUntilWithDiagnostics(
        label: String,
        timeoutMillis: Long,
        textProbes: List<String> = emptyList(),
        tagProbes: List<String> = emptyList(),
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (error: Throwable) {
            throw AssertionError(
                buildString {
                    appendLine("Timed out after ${timeoutMillis}ms waiting for $label.")
                    appendLine(screenDiagnostics(textProbes = textProbes, tagProbes = tagProbes))
                },
                error,
            )
        }
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun screenDiagnostics(textProbes: List<String>, tagProbes: List<String>): String = buildString {
        appendLine("Tag probe counts:")
        tagProbes.distinct().forEach { tag ->
            val count = runCatching {
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
            }.getOrDefault(-1)
            appendLine("  $tag=$count")
        }
        appendLine("Text probe counts:")
        textProbes.distinct().forEach { text ->
            val count = runCatching {
                compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
            }.getOrDefault(-1)
            appendLine("  \"$text\"=$count")
        }
        appendLine("Compose semantics tree:")
        appendLine(
            runCatching {
                compose.waitForIdle()
                compose.onRoot(useUnmergedTree = true).printToString()
            }.getOrElse { diagnosticsError ->
                "  <failed to capture semantics tree: ${diagnosticsError.javaClass.simpleName}: " +
                    "${diagnosticsError.message.orEmpty()}>"
            },
        )
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            requireNotNull(terminalView) { "TerminalView was not found" }
            terminalView.requestFocus()
            connection = terminalView.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
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

    private fun String.printableForFailure(): String =
        buildString(length) {
            for (ch in this@printableForFailure) {
                when {
                    ch == '\u001B' -> append("<ESC>")
                    ch == '\r' -> append("<CR>")
                    ch == '\u0000' -> append("<NUL>")
                    ch < ' ' && ch != '\n' && ch != '\t' -> append("<0x${ch.code.toString(16)}>")
                    else -> append(ch)
                }
            }
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun escapeSingleQuotedForPrintf(value: String): String =
        value.replace("'", "'\"'\"'")

    private fun tmuxSingleQuoted(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    protected companion object {
        const val NETWORK_FAULT_ARG: String = "pocketshellNetworkFaultProofs"
        const val NETWORK_FAULT_SSH_PORT: Int = 2228
        const val PACKET_LOSS_SSH_PORT: Int = 2229
        const val TOXIPROXY_API_PORT: Int = 8474
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue342-network-faults"

        /**
         * Issue #1676 — the load-bearing disconnect-band budget. Must be ≥ the
         * production `LivenessProbe` half-open detection worst case (~48s) plus
         * slow-swiftshader tick-stretch headroom. 90s ≈ 48s × ~1.9 and matches the
         * always-on keepalive's 90s prod death budget. Pinned against the production
         * detector budgets by `Issue1676DisconnectDetectionLatencyTest`.
         */
        const val DISCONNECT_BAND_BUDGET_MS: Long = 90_000L

        /**
         * Issue #1676 — poll the band this far PAST the load-bearing budget so the
         * TRUE detection latency is captured even when the budget is blown
         * (reproduce-first). > [DISCONNECT_BAND_BUDGET_MS] so an over-budget surface
         * is measured, not clipped; < the 300s per-test ci-journey watchdog.
         */
        const val DISCONNECT_BAND_CAPTURE_CEILING_MS: Long = 120_000L

        /**
         * Issue #1676 — the generous slow-link positive-visibility budget for the
         * network-fault proofs (the CI terminal-visibility value, 180s). See
         * [faultProofVisibilityBudgetMs].
         */
        const val FAULT_PROOF_VISIBILITY_BUDGET_MS: Long = 180_000L

        /** Issue #1681 — the un-proxied sentinel exec-ping cadence. */
        const val SENTINEL_INTERVAL_MS: Long = 3_000L

        val NETWORK_FAULT_FOLDER_CANDIDATES: List<String> = listOf(
            FolderListViewModel.UNTRACKED_PATH,
            "/home/testuser",
            "~",
        )
    }
}

/**
 * Issue #1681 — the un-proxied liveness sentinel (see
 * [NetworkFaultProofBase.openUnProxiedSentinel]). Holds a single direct SSH
 * session to the fixture SSH port (never through toxiproxy) and exec-pings it on
 * a fixed cadence in the background for the life of the storm window. Every
 * failed or timed-out ping is recorded; [isAlive] is true only when EVERY ping
 * round-tripped and the session is still connected. That is the constructive
 * proof the host + network stayed healthy, so a lease death over the delayed
 * proxy is attributable to the app, not the link.
 */
class SshSentinel private constructor(
    private val session: SshSession,
    scope: CoroutineScope,
    private val intervalMs: Long,
) {
    private val failures = CopyOnWriteArrayList<String>()

    @Volatile
    private var pings: Int = 0

    @Volatile
    private var attempts: Int = 0

    private val job: Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            attempts += 1
            val outcome = runCatching {
                withTimeout(SENTINEL_PING_TIMEOUT_MS) { session.exec("printf pong") }
            }.getOrNull()
            if (outcome != null && outcome.exitCode == 0 && outcome.stdout.contains("pong")) {
                pings += 1
            } else {
                failures += "ping #$attempts: exit=${outcome?.exitCode} " +
                    "stdout='${outcome?.stdout?.take(80)}' stderr='${outcome?.stderr?.take(80)}'"
            }
            delay(intervalMs)
        }
    }

    val pingCount: Int get() = pings
    val attemptCount: Int get() = attempts
    val failureLog: List<String> get() = failures.toList()

    /** True only when no ping ever failed AND the un-proxied session is still up. */
    val isAlive: Boolean get() = failures.isEmpty() && runCatching { session.isConnected }.getOrDefault(false)

    fun stop() {
        job.cancel()
        runCatching { runBlocking { session.close() } }
    }

    companion object {
        private const val SENTINEL_PING_TIMEOUT_MS: Long = 20_000L

        suspend fun open(key: String, scope: CoroutineScope, intervalMs: Long): SshSentinel {
            val session = withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).getOrThrow()
            }
            return SshSentinel(session, scope, intervalMs)
        }
    }
}

data class DirectTmuxConnection(
    val session: SshSession,
    val client: TmuxClient,
) {
    suspend fun detachAndClose(timeoutMs: Long = 2_000L) {
        runCatching { client.detachCleanly(timeoutMs) }
        runCatching { session.close() }
    }

    fun close() {
        runCatching { client.close() }
        runCatching { session.close() }
    }
}
