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
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.OutboundDeliveryAuthority
import com.pocketshell.app.tmux.DurableOutboundRowIdentity
import com.pocketshell.app.tmux.HOST_ACK_REASON_ALREADY_DELIVERED
import com.pocketshell.app.tmux.HOST_ACK_REASON_DELIVERED
import com.pocketshell.app.tmux.HostAckSendProbe
import com.pocketshell.app.tmux.OutboundLegacyStackProbe
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Issue #2189 — HostAck sibling of [OutboundExactlyOnceAcrossFlapE2eTest].
 *
 * Decision: REPLACE the exactly-once-across-flap property. The late-authority /
 * unconfirmed / paste-ack-timeout / turnover oracles stay on the pinned
 * original and drop with #2125 — they assert inference semantics the host
 * acknowledgement deleted.
 *
 * On HostAck, exactly-once is the durable token: a send that raced a drop is
 * retried with the SAME token, and the host either delivers once or answers
 * `already-delivered`. The legacy `failSendResultLostBeforeSubmitEnter` seam
 * is not used — paste+Enter is one exec, so that cut does not exist.
 *
 * Mutation that must redden the HostAck assertions: pin
 * [OutboundDeliveryAuthority.TerminalInference]. [HostAckSendProbe] stays 0
 * and `outbound_host_ack_send` never fires.
 */
@RunWith(AndroidJUnit4::class)
class Issue2189HostAckExactlyOnceAcrossFlapE2eTest {

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
        assertAcknowledgedSendsWereRecorded("Issue2189HostAckExactlyOnceAcrossFlapE2eTest")
        assertLegacyStackWasNotConsulted("Issue2189HostAckExactlyOnceAcrossFlapE2eTest")
        diagnostics?.close()
        diagnostics = null
        clearOutboundDeliveryAuthorityPin()
        clearLastSessionPrefs()
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
        runCatching {
            SharedPrefsOutboundQueueStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).clearSession(QUEUE_SESSION_KEY)
        }
    }

    @Test
    fun promptSentAcrossAFlapIsDeliveredExactlyOnceOnHostAck() {
        runBlocking<Unit> {
            attachSeededTmuxSession(hostRowTag)
            waitForConnected("initial attach")
            val vm = currentViewModel()
            assertTrue(
                "the shipped HostAck path must own this VM (authority=${vm.hostAck.authority})",
                vm.hostAck.active,
            )
            waitForSidecarCaptureContains(FAKE_AGENT_READY.filterNot { it.isWhitespace() })
            val paneId = requireNotNull(vm.panes.value.firstOrNull()?.paneId) {
                "expected a live pane to send into"
            }
            val clientBefore = vm.currentClientIdentityForTest()
            val store = SharedPrefsOutboundQueueStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
            store.clearSession(QUEUE_SESSION_KEY)
            val nonce = SystemClock.elapsedRealtime().toString().takeLast(6)
            val payload = "exactly once across the flap $nonce"
            val payloadStripped = payload.filterNot { it.isWhitespace() }
            val row = store.enqueue(
                sessionKey = QUEUE_SESSION_KEY,
                cleanText = payload,
                paneId = paneId,
                route = OutboundRoute.AgentPayload,
                agentKind = "claude",
                sendKey = "sk-2189-$nonce",
            )
            val identity = DurableOutboundRowIdentity(QUEUE_SESSION_KEY, row.id)

            diagnostics!!.clear()
            HostAckSendProbe.reset()
            OutboundLegacyStackProbe.reset()

            assertTrue(
                "precondition: the live transport must drop so the send races a flap",
                vm.triggerCleanPassiveDropForTest(),
            )
            waitUntilNotWritable("after injected drop")

            val first = vm.sendAgentPayloadToPaneResult(
                paneId,
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = identity,
            )
            assertTrue(
                "the in-flight send must not invent success on a dead transport; " +
                    "result=$first",
                first.isFailure,
            )

            if (currentConnectionStatus() !is TmuxSessionViewModel.ConnectionStatus.Connected) {
                var accepted = false
                compose.activityRule.scenario.onActivity { accepted = vm.reconnect() }
                assertTrue("reconnect after the flap must be accepted", accepted)
            }
            waitForConnected("post-flap reconnect")
            val clientAfter = currentViewModel().currentClientIdentityForTest()
            assertTrue(
                "the flap must have been REAL (fresh tmux client); " +
                    "before=$clientBefore after=$clientAfter",
                clientAfter != null && clientAfter != clientBefore,
            )

            val retry = currentViewModel().sendAgentPayloadToPaneResult(
                paneId,
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = identity,
            )
            assertTrue(
                "the same-token retry after the flap must be acknowledged; " +
                    "failure=${retry.exceptionOrNull()}",
                retry.isSuccess,
            )

            assertTrue(
                "the retry must have entered the HostAck lane " +
                    "(observed=${HostAckSendProbe.count()})",
                HostAckSendProbe.count() >= 1L,
            )
            val acks = waitForHostAckEvents()
            assertTrue(
                "outbound_host_ack_send must fire delivered or already-delivered; acks=$acks",
                acks.any {
                    it.fields["outcome"] == HOST_ACK_REASON_DELIVERED ||
                        it.fields["outcome"] == HOST_ACK_REASON_ALREADY_DELIVERED
                },
            )
            assertEquals(
                "HostAck must not consult the inference stack: " +
                    OutboundLegacyStackProbe.snapshot(),
                0L,
                OutboundLegacyStackProbe.total(),
            )

            val capture = waitForSidecarCaptureContains(payloadStripped)
            writeText("issue2189-flap-final-capture.txt", capture)
            val captureStripped = capture.filterNot { it.isWhitespace() }
            assertFalse(
                "payload must not be duplicated across the flap:\n$capture",
                captureStripped.contains(payloadStripped + payloadStripped),
            )
            assertEquals(
                "payload must occur EXACTLY ONCE after the flap retry:\n$capture",
                1,
                countOccurrences(captureStripped, payloadStripped),
            )
            assertEquals(
                "submitted marker must occur EXACTLY ONCE:\n$capture",
                1,
                countOccurrences(captureStripped, FAKE_AGENT_SUBMITTED_STRIPPED + payloadStripped),
            )
            writeText(
                "issue2189-flap-summary.txt",
                buildString {
                    appendLine("issue=2189")
                    appendLine("property=exactly-once-across-flap")
                    appendLine("authority=HostCliAck")
                    appendLine("host_ack_sends=${HostAckSendProbe.count()}")
                    appendLine("legacy_stack=${OutboundLegacyStackProbe.snapshot()}")
                    appendLine("client_before=$clientBefore")
                    appendLine("client_after=$clientAfter")
                    appendLine("payload=$payload")
                },
            )
        }
    }

    private fun waitForHostAckEvents(): List<RecordedDiagnosticEvent> {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var acks = emptyList<RecordedDiagnosticEvent>()
        while (SystemClock.elapsedRealtime() < deadline) {
            acks = diagnostics!!.eventsNamed("outbound_host_ack_send")
            if (acks.isNotEmpty()) return acks
            SystemClock.sleep(50)
        }
        return acks
    }

    private fun waitUntilNotWritable(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() !is TmuxSessionViewModel.ConnectionStatus.Connected ||
                !currentViewModel().isSendTransportWritable()
        }
        assertTrue(
            "$label: transport must be down before the raced send, " +
                "status=${currentConnectionStatus()} writable=${currentViewModel().isSendTransportWritable()}",
            currentConnectionStatus() !is TmuxSessionViewModel.ConnectionStatus.Connected ||
                !currentViewModel().isSendTransportWritable(),
        )
    }

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus.value
        }
        return status
    }

    private suspend fun waitForSidecarCaptureContains(needleStripped: String): String {
        val deadline = SystemClock.elapsedRealtime() + SUBMIT_TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = sidecarCapturePane()
            if (last.filterNot { it.isWhitespace() }.contains(needleStripped)) return last
            SystemClock.sleep(200)
        }
        assertTrue(
            "payload '$needleStripped' never appeared in capture-pane:\n$last",
            last.filterNot { it.isWhitespace() }.contains(needleStripped),
        )
        return last
    }

    private suspend fun sidecarCapturePane(): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}") }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                attached = activity.window.decorView.findTerminalView()
                    ?.currentSession?.emulator != null
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
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
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
                name = "issue2189-flap-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue2189 HostAck Flap",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
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
            description = "issue2189 fake-agent flap seed",
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
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
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
        println("ISSUE2189_FLAP ${file.absolutePath}")
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

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue2189-host-ack-flap"
        const val SESSION_NAME: String = "issue2189-exactly-once"
        const val QUEUE_SESSION_KEY: String = "issue2189/flap"
        const val FAKE_AGENT_READY: String = "FAKE-AGENT-READY"
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
        val SUBMIT_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
    }
}
