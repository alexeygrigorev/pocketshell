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
import androidx.lifecycle.ViewModelProvider
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
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_RECONNECTING_RETRY_NOW_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
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
        // Issue #1676 — a benign foreground reconnect (transient transport churn, or a
        // lifecycle detach/reattach) briefly HOLDS the terminal: the surface goes
        // Reattaching/Reconnecting and the TerminalView drops out of the view tree. A
        // send that raced that window used to fail immediately with "TerminalView was
        // not found". Wait (bounded) for the terminal to be attached again before
        // typing — the app auto-recovers, so it returns within the budget. This makes
        // the shared send helper resilient to transient holds without changing what any
        // test asserts.
        waitForTerminalAttachedForInput(label)
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input commit for $label chunk `$chunk`", committed)
            SystemClock.sleep(35)
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input submit for $label", enterCommitted)
    }

    private fun waitForTerminalAttachedForInput(label: String) {
        val ready = runCatching {
            compose.waitUntil(timeoutMillis = TERMINAL_INPUT_READY_TIMEOUT_MS) {
                terminalViewAttached()
            }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected an attached terminal to type $label into within " +
                "${TERMINAL_INPUT_READY_TIMEOUT_MS}ms (the terminal stayed held/detached — " +
                "a reconnect that never settled)",
            ready,
        )
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
     * Issue #1676 — a single sampled snapshot of every recovery-relevant RENDERED
     * signal plus the VM connection status, used by [observeRecoveryTimeline] and the
     * ride-through assertions. These are the ACTUAL on-screen signals a user sees
     * during the deliberate auto-reconnect episode (the current ride-through
     * contract), not a seam/lambda proxy (the #1693 trap).
     */
    protected data class RecoverySnapshot(
        val elapsedMs: Long,
        val statusName: String,
        /** Top-chrome "Reconnecting" (amber) pill — [TMUX_CONNECTION_STATUS_PILL_TAG]. */
        val reconnectingPill: Boolean,
        /** Centered "Attaching…" reconnect hold — [TMUX_SWITCHING_LOADING_TAG]. */
        val attachingHold: Boolean,
        /** The (architecturally live-frame-only) Reconnecting band "Retry now". */
        val reconnectBandRetryNow: Boolean,
        /** The Connecting/Reconnecting progress row root — [TMUX_CONNECTING_PROGRESS_TAG]. */
        val connectingProgressRow: Boolean,
        /** The SETTLED Failed band — [TMUX_SESSION_ERROR_TAG] (give-up only). */
        val settledFailedBand: Boolean,
    ) {
        /**
         * True while an HONEST, escapable recovery indicator is on screen (the
         * current ride-through contract's fast signal) and the SETTLED Failed band
         * is NOT — i.e. the app is patiently auto-reconnecting, not surrendered.
         */
        val showsRecoveryInProgress: Boolean
            get() = !settledFailedBand &&
                (reconnectingPill || attachingHold || reconnectBandRetryNow ||
                    connectingProgressRow || statusName == "Reconnecting")

        fun asLine(): String =
            "t=${elapsedMs}ms status=$statusName pill=$reconnectingPill " +
                "attachingHold=$attachingHold retryNow=$reconnectBandRetryNow " +
                "progressRow=$connectingProgressRow settledFailed=$settledFailedBand " +
                "recoveryInProgress=$showsRecoveryInProgress"
    }

    /** The current [TmuxSessionViewModel.ConnectionStatus] simple name (VM real state). */
    protected fun currentConnectionStatusName(): String {
        var name = "unknown"
        launchedActivity?.onActivity { activity ->
            name = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value::class
                .simpleName
                ?: "null"
        }
        return name
    }

    protected fun sampleRecovery(startedAt: Long): RecoverySnapshot = RecoverySnapshot(
        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        statusName = currentConnectionStatusName(),
        reconnectingPill = hasReconnectingPill(),
        attachingHold = hasTag(TMUX_SWITCHING_LOADING_TAG),
        reconnectBandRetryNow = hasTag(TMUX_RECONNECTING_RETRY_NOW_TAG),
        connectingProgressRow = hasTag(TMUX_CONNECTING_PROGRESS_TAG),
        settledFailedBand = hasTag(TMUX_SESSION_ERROR_TAG),
    )

    /**
     * The top-chrome "Reconnecting" pill reads its label from the fused surface
     * status, so the tag alone is present for both "Reconnecting" and "Disconnected".
     * We treat it as the reconnecting signal only when its visible text is
     * "Reconnecting" (the amber, in-progress label — not the red "Disconnected").
     */
    private fun hasReconnectingPill(): Boolean {
        if (!hasTag(TMUX_CONNECTION_STATUS_PILL_TAG)) return false
        return runCatching {
            compose.onAllNodesWithText("Reconnecting", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * Issue #1676 — record a per-tick timeline of the RENDERED recovery signals for
     * [windowMs] after a link cut, to a `recovery-timeline-<label>.txt` artifact.
     * Diagnostic + evidence: it captures exactly which honest recovery indicator the
     * user sees during the deliberate auto-reconnect ladder, and whether the settled
     * Failed band ever appears (it must not, until give-up).
     */
    protected fun observeRecoveryTimeline(
        label: String,
        windowMs: Long,
        pollMs: Long = 1_000L,
    ): List<RecoverySnapshot> {
        val start = SystemClock.elapsedRealtime()
        val samples = mutableListOf<RecoverySnapshot>()
        while (SystemClock.elapsedRealtime() - start < windowMs) {
            samples += sampleRecovery(start)
            SystemClock.sleep(pollMs)
        }
        artifactFile("recovery-timeline-$label.txt").writeText(
            buildString {
                appendLine("label=$label window_ms=$windowMs poll_ms=$pollMs")
                samples.forEach { appendLine(it.asLine()) }
            },
        )
        return samples
    }

    /**
     * Issue #1676 — the CURRENT ride-through contract's load-bearing recovery
     * assertion. During a link outage the app AUTO-reconnects patiently through the
     * bounded 8-rung ladder (it does NOT wait for a manual reconnect — the superseded
     * #342/#552 contract). The user's fast, honest, escapable signal during that
     * window is a recovery-in-progress indicator (the top-chrome "Reconnecting" pill,
     * the centered "Attaching…" reconnect hold, and/or the Reconnecting progress row),
     * while the VM sits in [TmuxSessionViewModel.ConnectionStatus.Reconnecting]. The
     * SETTLED Failed band ([TMUX_SESSION_ERROR_TAG]) must NOT appear during this
     * window — that renders ONLY at give-up (`Unreachable`/`Gone`), which the
     * ride-through is deliberately built to avoid.
     *
     * Polls up to [budgetMs] for the first tick that [RecoverySnapshot.showsRecoveryInProgress],
     * hard-asserting that a settled Failed band never pre-empted it. Writes the full
     * observation timeline to a `recovery-band-<label>.txt` artifact (the authoritative
     * evidence). This is the symptom-defining RENDERED signal on the real transport,
     * not a seam having fired.
     */
    protected fun waitForReconnectingRecoveryBand(
        label: String,
        budgetMs: Long = RECONNECTING_BAND_BUDGET_MS,
    ): RecoverySnapshot {
        val start = SystemClock.elapsedRealtime()
        val timeline = mutableListOf<RecoverySnapshot>()
        var recovered: RecoverySnapshot? = null
        var settledFailedFirst: RecoverySnapshot? = null
        while (SystemClock.elapsedRealtime() - start < budgetMs) {
            val snap = sampleRecovery(start)
            timeline += snap
            if (snap.settledFailedBand && recovered == null && settledFailedFirst == null) {
                settledFailedFirst = snap
            }
            if (snap.showsRecoveryInProgress) {
                recovered = snap
                break
            }
            SystemClock.sleep(RECONNECTING_BAND_POLL_MS)
        }
        val appeared = recovered != null
        val detectMs = (recovered ?: timeline.lastOrNull())?.elapsedMs ?: 0L
        recordTiming("${label}_reconnecting_band_ms", detectMs)
        recordTiming("${label}_reconnecting_band_appeared", if (appeared) 1L else 0L)
        artifactFile("recovery-band-$label.txt").writeText(
            buildString {
                appendLine("label=$label budget_ms=$budgetMs")
                appendLine("reconnecting_band_appeared=$appeared")
                appendLine("reconnecting_band_ms=$detectMs")
                appendLine("settled_failed_pre_empted=${settledFailedFirst != null}")
                appendLine("timeline:")
                timeline.forEach { appendLine("  ${it.asLine()}") }
            },
        )
        assertTrue(
            "expected the CURRENT ride-through recovery indicator (Reconnecting pill / " +
                "Attaching hold / progress row, VM=Reconnecting) for $label to appear within " +
                "${budgetMs}ms WITHOUT the settled Failed band pre-empting it, but a settled " +
                "Failed band (give-up) appeared first at ${settledFailedFirst?.elapsedMs}ms — " +
                "the app surrendered instead of riding through (see recovery-band-$label.txt)",
            settledFailedFirst == null,
        )
        assertTrue(
            "expected the CURRENT ride-through recovery indicator for $label to appear within " +
                "${budgetMs}ms (top-chrome Reconnecting pill / centered Attaching hold / " +
                "Reconnecting progress row / VM Reconnecting); none appeared " +
                "(see recovery-band-$label.txt)",
            appeared,
        )
        return recovered!!
    }

    /**
     * Issue #1676 — wait for the session to AUTO-recover to Connected (Live) after
     * the link is restored within the episode budget — WITHOUT any manual reconnect
     * tap (the CURRENT ride-through contract; the superseded #342/#552 contract
     * required a manual Reconnect). Reads the real VM
     * [TmuxSessionViewModel.ConnectionStatus] and hard-asserts it settled to
     * `Connected`.
     */
    protected fun waitForConnectedStatus(
        label: String,
        timeoutMs: Long = AUTO_RECOVERY_CONNECTED_TIMEOUT_MS,
    ) {
        val start = SystemClock.elapsedRealtime()
        val settled = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMs) {
                currentConnectionStatusName() == "Connected"
            }
            true
        }.getOrDefault(false)
        recordTiming("${label}_auto_recovered_ms", SystemClock.elapsedRealtime() - start)
        assertTrue(
            "expected the session for $label to AUTO-recover to Connected within ${timeoutMs}ms " +
                "after the link restored (no manual reconnect tap — the current ride-through " +
                "contract), observed=${currentConnectionStatusName()}",
            settled,
        )
    }

    /**
     * Issue #1676 — assert the VM is Connected RIGHT NOW (a synchronous check, e.g.
     * to prove a half-open wedge was ridden through WITHOUT tearing the session down).
     */
    protected fun assertConnectedStatus(label: String) {
        val status = currentConnectionStatusName()
        assertTrue(
            "expected the session to stay Connected $label (ride-through, no teardown), " +
                "observed=$status",
            status == "Connected",
        )
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
        compose.waitUntil(timeoutMillis = 30_000) { terminalViewAttached() }
    }

    private fun terminalViewAttached(): Boolean {
        var attached = false
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            attached = view?.currentSession != null && view.mEmulator != null
        }
        return attached
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
        // Issue #1676 — tolerate a transient hold between chunks: poll (bounded) for the
        // TerminalView to be present rather than throwing on the first miss.
        if (!terminalViewAttached()) {
            runCatching {
                compose.waitUntil(timeoutMillis = TERMINAL_INPUT_READY_TIMEOUT_MS) {
                    terminalViewAttached()
                }
            }
        }
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
         * Issue #1676 — the generous slow-link positive-visibility budget for the
         * network-fault proofs (the CI terminal-visibility value, 180s). See
         * [faultProofVisibilityBudgetMs].
         */
        const val FAULT_PROOF_VISIBILITY_BUDGET_MS: Long = 180_000L

        /**
         * Issue #1676 — the budget for the CURRENT ride-through contract's recovery
         * indicator (top-chrome "Reconnecting" pill / centered "Attaching…" hold /
         * VM Reconnecting) to appear after a link cut. The reader-EOF that flips the
         * live transport into the reconnect ladder is bounded by the app-level
         * half-open detection (`LivenessProbe` ~48s worst case) plus the reveal
         * transition off the within-grace live-frame hold; 90s covers that on the
         * slow swiftshader path with headroom while staying well under the 300s
         * per-test ci-journey watchdog. Unlike the superseded settled-Failed-band
         * wait, this fast indicator surfaces EARLY in the episode — long before the
         * ~119–270s give-up the old tests waited for.
         */
        const val RECONNECTING_BAND_BUDGET_MS: Long = 90_000L

        /** Issue #1676 — poll cadence for [waitForReconnectingRecoveryBand]. */
        const val RECONNECTING_BAND_POLL_MS: Long = 500L

        /**
         * Issue #1676 — budget for the session to AUTO-recover to Connected after the
         * link is restored within the episode budget. The 8-rung reconnect ladder
         * (`DEFAULT_RECONNECT_LADDER_MS = [0,1,2,5,10,20,30,30]s`) plus a fresh dial
         * can span most of a rung interval on the slow emulator; 90s covers a restore
         * landing on the far rungs with headroom, well under the 300s watchdog.
         */
        const val AUTO_RECOVERY_CONNECTED_TIMEOUT_MS: Long = 90_000L

        /**
         * Issue #1676 — bounded wait for the terminal to (re-)attach before a
         * terminal-input send, so a benign transient reconnect-hold doesn't fail the
         * send with "TerminalView was not found". Generous enough to cover a full
         * ladder auto-recovery on the slow emulator, under the 300s watchdog.
         */
        const val TERMINAL_INPUT_READY_TIMEOUT_MS: Long = 90_000L

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
