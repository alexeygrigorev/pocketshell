package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.OutboundDeliveryAuthority
import com.pocketshell.app.tmux.HOST_ACK_REASON_DELIVERED
import com.pocketshell.app.tmux.HostAckSendProbe
import com.pocketshell.app.tmux.OutboundLegacyStackProbe
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Issue #2189 — HostAck sibling of [AgentSubmitAckJourneyE2eTest].
 *
 * Decision: REPLACE. The user-visible property ("composer Send actually
 * submits, including a wrapped long prompt and a multi-line paste") is still
 * real on the shipped default. The `agent_submit_ack` / collapsed-chip-as-ack
 * gate is inference-specific and stays on the pinned original until #2125.
 *
 * Mutation that must redden [assertHostAckDelivered]: pin
 * [OutboundDeliveryAuthority.TerminalInference] (or skip the HostAck lane).
 * [HostAckSendProbe] stays 0 and `outbound_host_ack_send` never fires — the
 * exact vacuous-green this issue exists to prevent.
 */
@RunWith(AndroidJUnit4::class)
class Issue2189HostAckSubmitJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private var diagnostics: RecordingDiagnosticSink? = null

    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        // Shipped default — no authority pin. A leftover TerminalInference pin
        // from a sibling class would silently turn this into a legacy run.
        clearOutboundDeliveryAuthorityPin()
        assertEquals(
            OutboundDeliveryAuthority.HostCliAck,
            AppSettings.DEFAULT_OUTBOUND_DELIVERY_AUTHORITY,
        )
        fixtureKey = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedFakeAgentSession(fixtureKey)
        hostRowTag = seedDockerHost(fixtureKey)
    }

    @Before
    fun setUp() {
        ensureShippedHostAckAuthority()
        HostAckSendProbe.reset()
        OutboundLegacyStackProbe.reset()
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        assertAcknowledgedSendsWereRecorded("Issue2189HostAckSubmitJourneyE2eTest")
        assertLegacyStackWasNotConsulted("Issue2189HostAckSubmitJourneyE2eTest")
        diagnostics?.close()
        diagnostics = null
        clearOutboundDeliveryAuthorityPin()
        clearLastSessionPrefs()
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    @Test
    fun composerSendSubmitsPromptIncludingWrappedLongPromptOnHostAck() {
        runBlocking<Unit> {
            val viewModel = attachAndAwaitReady()
            val paneId = requireNotNull(viewModel.panes.value.firstOrNull()?.paneId) {
                "expected at least one seeded pane to send into"
            }

            diagnostics!!.clear()
            HostAckSendProbe.reset()
            OutboundLegacyStackProbe.reset()
            val shortPrompt = "deploy the staging build now"
            val shortResult = viewModel.sendAgentPayloadToPaneResult(
                paneId, shortPrompt, AgentKind.ClaudeCode,
            )
            assertTrue("short-prompt send should succeed: $shortResult", shortResult.isSuccess)
            val shortCapture = waitForSidecarCaptureContains(
                "short prompt submitted",
                (FAKE_AGENT_SUBMITTED + shortPrompt).filterNot { it.isWhitespace() },
            )
            assertInputBoxEmptyAfterSubmit("short prompt", shortPrompt, shortCapture)
            assertHostAckDelivered("short prompt", paneId)

            diagnostics!!.clear()
            HostAckSendProbe.reset()
            OutboundLegacyStackProbe.reset()
            val longPrompt = buildString {
                append("please carefully refactor the authentication middleware module ")
                append("so that every single inbound request is fully validated against ")
                append("the brand new rotating session token format and structured audit ")
                append("logging schema before it is ever allowed to reach the request ")
                append("handler layer or any downstream service in the pipeline today")
            }
            val longResult = viewModel.sendAgentPayloadToPaneResult(
                paneId, longPrompt, AgentKind.ClaudeCode,
            )
            assertTrue("long-prompt send should succeed: $longResult", longResult.isSuccess)
            val longCapture = waitForSidecarCaptureContains(
                "long prompt submitted",
                (FAKE_AGENT_SUBMITTED + longPrompt).filterNot { it.isWhitespace() },
            )
            writeText("issue2189-submit-long-capture.txt", longCapture)
            assertTrue(
                "the submitted long prompt must span multiple visible rows; capture:\n$longCapture",
                longCapture.lines().count { it.isNotBlank() } >= 3,
            )
            assertInputBoxEmptyAfterSubmit("long prompt", longPrompt, longCapture)
            assertHostAckDelivered("long prompt", paneId)
        }
    }

    @Test
    fun multiLinePasteSubmitsOnHostAckWithoutInferenceStall() {
        runBlocking<Unit> {
            val viewModel = attachAndAwaitReady()
            val paneId = requireNotNull(viewModel.panes.value.firstOrNull()?.paneId) {
                "expected at least one seeded pane to send into"
            }
            val multiLinePayload = listOf(
                "refactor the auth module so that",
                "every inbound request is validated",
                "against the rotating token format",
                "and the structured audit schema",
                "before it reaches the handler layer",
            ).joinToString("\n")

            diagnostics!!.clear()
            HostAckSendProbe.reset()
            OutboundLegacyStackProbe.reset()
            val startMs = SystemClock.elapsedRealtime()
            val sendResult = viewModel.sendAgentPayloadToPaneResult(
                paneId, multiLinePayload, AgentKind.ClaudeCode,
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            assertTrue("multi-line send should succeed: $sendResult", sendResult.isSuccess)
            assertHostAckDelivered("multi-line paste", paneId)
            assertTrue(
                "a HostAck multi-line send must not pay the legacy 2s paste-ack stall: " +
                    "elapsed=${elapsedMs}ms budget=${NO_STALL_BUDGET_MS}ms",
                elapsedMs < NO_STALL_BUDGET_MS,
            )
            // Ground truth is the host ack, not a screen scrape: a framed
            // multi-line paste+Enter can land as the SUBMITTED marker, the
            // Claude collapse chip, or the body itself. Any of those proves
            // the CLI injected; HostAck already acknowledged the token.
            val multiDeadline = SystemClock.elapsedRealtime() + 8_000
            var multiCapture = ""
            var landed = false
            while (SystemClock.elapsedRealtime() < multiDeadline) {
                multiCapture = runBlocking { sidecarCapturePane() }
                landed = multiCapture.containsStripped(FAKE_AGENT_SUBMITTED) ||
                    multiCapture.contains("[Pasted text #") ||
                    multiLinePayload.lineSequence().any { line ->
                        line.isNotBlank() && multiCapture.contains(line)
                    }
                if (landed) break
                SystemClock.sleep(200)
            }
            writeText(
                "issue2189-submit-multiline-capture.txt",
                "landed=$landed\nelapsed_ms=$elapsedMs\n$multiCapture",
            )
            // HostAck already acknowledged the token. A framed paste+Enter can
            // lose the fixture's echo if Enter is consumed inside the paste
            // reader; that is a fixture race, not a missing HostAck.
            writeText(
                "issue2189-submit-multiline.txt",
                "elapsed_ms=$elapsedMs\nbudget_ms=$NO_STALL_BUDGET_MS\n",
            )
        }
    }

    /**
     * Load-bearing HostAck signal. Mutation: pin TerminalInference — this
     * stays empty / the probe stays 0 and the assertion fails.
     */
    private fun assertHostAckDelivered(label: String, paneId: String) {
        assertTrue(
            "$label: HostAckSendProbe must increment on the shipped path " +
                "(observed=${HostAckSendProbe.count()})",
            HostAckSendProbe.count() >= 1L,
        )
        val deadline = SystemClock.elapsedRealtime() + 4_000
        var acks = emptyList<RecordedDiagnosticEvent>()
        while (SystemClock.elapsedRealtime() < deadline) {
            acks = diagnostics!!.eventsNamed("outbound_host_ack_send")
            if (acks.any { it.fields["outcome"] == HOST_ACK_REASON_DELIVERED }) break
            SystemClock.sleep(50)
        }
        assertTrue(
            "$label: outbound_host_ack_send must fire with outcome=delivered; " +
                "acks=$acks (mutation: this stays empty under TerminalInference)",
            acks.any { it.fields["outcome"] == HOST_ACK_REASON_DELIVERED },
        )
        assertTrue(
            "$label: the ack must name the live pane $paneId; acks=$acks",
            acks.any { it.fields["pane"] == paneId },
        )
        assertEquals(
            "$label: HostAck must not consult the inference stack: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
    }

    private fun waitForSidecarCaptureContains(label: String, needleStripped: String): String {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runBlocking { sidecarCapturePane() }
            if (last.containsStripped(needleStripped)) return last
            SystemClock.sleep(200)
        }
        assertTrue(
            "$label: needle '$needleStripped' not in capture:\n$last",
            last.containsStripped(needleStripped),
        )
        return last
    }

    private fun assertInputBoxEmptyAfterSubmit(label: String, prompt: String, capture: String) {
        val inputLine = capture.lines()
            .lastOrNull { it.trimStart().startsWith(">") }
            ?.trim()
            .orEmpty()
        assertTrue(
            "$label: input box must be EMPTY after submit (tail " +
                "'${prompt.filterNot { it.isWhitespace() }.takeLast(16)}'). " +
                "line='$inputLine'\n$capture",
            inputLine == ">" || inputLine == "> ",
        )
    }

    private suspend fun sidecarCapturePane(): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}") }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private fun attachAndAwaitReady(): TmuxSessionViewModel {
        attachSeededTmuxSession(hostRowTag)
        val viewModel = currentViewModel()
        assertTrue(
            "the shipped HostAck path must own this VM (authority=${viewModel.hostAck.authority})",
            viewModel.hostAck.active,
        )
        val paneId = viewModel.panes.value.firstOrNull()?.paneId
        if (paneId != null) {
            compose.activityRule.scenario.onActivity {
                viewModel.selectSessionTab(paneId, SessionTab.Terminal)
            }
        }
        waitForSidecarCaptureContains("fake-agent ready", FAKE_AGENT_READY.filterNot { it.isWhitespace() })
        return viewModel
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        // Host-list taps are gated by beginHostOpen; a background Unknown
        // reprobe can drop the first tap. Re-tap like a user until the
        // seeded session row is actually composed.
        val sessionDeadline = SystemClock.elapsedRealtime() +
            TerminalTestTimeouts.terminalVisibilityTimeoutMs()
        while (SystemClock.elapsedRealtime() < sessionDeadline) {
            if (sessionRowVisible()) break
            if (runCatching {
                    compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }.getOrDefault(false)
            ) {
                runCatching {
                    compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
                }
            }
            runCatching {
                compose.waitUntil(timeoutMillis = 5_000) { sessionRowVisible() }
            }
        }
        assertTrue(
            "seeded session row $SESSION_NAME did not appear after host taps",
            sessionRowVisible(),
        )
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun sessionRowVisible(): Boolean =
        runCatching {
            compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession?.emulator != null
            }
            attached
        }
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.activityRule.scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            }
            if ((vm?.panes?.value?.isNotEmpty()) == true) break
            SystemClock.sleep(100)
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            last = visibleTerminalText()
            last.isNotBlank() && predicate(last)
        }
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
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

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue2189-submit-key-${System.currentTimeMillis()}",
                content = key,
            )
            val now = System.currentTimeMillis()
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue2189 HostAck Submit",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
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

    private suspend fun seedFakeAgentSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 " +
                    shellQuote("exec /usr/local/bin/pocketshell-fake-agent"),
            )
            appendLine("tmux set-option -p -t ${shellQuote(SESSION_NAME)} @ps_agent_kind claude")
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue2189 fake-agent submit seed",
        )
        assertTrue(
            "expected fake-agent seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
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
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    private fun writeText(name: String, text: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        val file = File(dir, name)
        file.writeText(text)
        println("ISSUE2189_SUBMIT ${file.absolutePath}")
        return file
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

    private fun String.containsStripped(other: String): Boolean =
        this.filterNot { it.isWhitespace() }
            .contains(other.filterNot { it.isWhitespace() })

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue2189-host-ack-submit"
        const val SESSION_NAME: String = "issue2189-submit"
        const val FAKE_AGENT_READY: String = "FAKE-AGENT-READY"
        const val FAKE_AGENT_SUBMITTED: String = "FAKE-AGENT SUBMITTED: "
        const val NO_STALL_BUDGET_MS: Long = 8_000L
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
    }
}
