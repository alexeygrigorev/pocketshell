package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.OutboundDeliveryAuthority
import com.pocketshell.app.tmux.HOST_ACK_REASON_DELIVERED
import com.pocketshell.app.tmux.HostAckSendProbe
import com.pocketshell.app.tmux.OutboundLegacyStackProbe
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
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
import org.junit.runner.RunWith

/**
 * Issue #2189 — HostAck sibling of [SendWithAttachmentStaysVisibleE2eTest].
 *
 * Decision: REPLACE. The #1153 half-black send-heal is preserved verbatim on
 * the acknowledged path (`HostAckDeliveryPort.deliver` calls `onDelivered` →
 * `ReconcileReason.Send`). The original stays pinned to TerminalInference
 * because it asserts `agent_submit_ack`; this class proves the same heal on
 * the shipped default.
 *
 * Mutation that must redden [assertHostAckDelivered]: pin
 * [OutboundDeliveryAuthority.TerminalInference]. [HostAckSendProbe] stays 0
 * and `outbound_host_ack_send` never fires — the send-heal assertions would
 * then be proving a path users do not take.
 */
@RunWith(AndroidJUnit4::class)
class Issue2189HostAckSendHealJourneyE2eTest {
    private lateinit var trustedHostKeySha256: String

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private var diagnostics: RecordingDiagnosticSink? = null

    private suspend fun seedBeforeLaunch() {
        clearOutboundDeliveryAuthorityPin()
        assertEquals(
            OutboundDeliveryAuthority.HostCliAck,
            AppSettings.DEFAULT_OUTBOUND_DELIVERY_AUTHORITY,
        )
        BackgroundGraceTestOverride.setForTest(null)
        val key = readFixtureKey()
        fixtureKey = key
        trustedHostKeySha256 = waitForSshFixtureReady(SshKey.Pem(key))
        seedFullFrameSession(key)
        hostRowTag = seedDockerHost(key)
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
        assertAcknowledgedSendsWereRecorded("Issue2189HostAckSendHealJourneyE2eTest")
        assertLegacyStackWasNotConsulted("Issue2189HostAckSendHealJourneyE2eTest")
        diagnostics?.close()
        diagnostics = null
        clearOutboundDeliveryAuthorityPin()
        BackgroundGraceTestOverride.setForTest(null)
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    @Test
    fun sendWithAttachmentHealsHalfBlackActivePaneOnHostAck() {
        runBlocking {
            attachSeededTmuxSession(hostRowTag)
            val attachedVm = liveViewModel()
            assertTrue(
                "the shipped HostAck path must own this VM (authority=${attachedVm.hostAck.authority})",
                attachedVm.hostAck.active,
            )
            pauseAndProveStaleRenderWatchdogQuiescent()

            waitForVisibleTerminal("initial full frame") {
                frameRowCount(it) >= MIN_RESTORED_FRAME_ROWS
            }
            waitForConnected("initial attach")
            capturePaintedRows("issue2189-00-attached")

            val authoritativeCapture = captureRemoteTmuxPane()
            assertTrue(
                "sanity: tmux's authoritative capture must hold the full frame marker; " +
                    "capture:\n$authoritativeCapture",
                authoritativeCapture.contains(FRAME_MARKER),
            )

            runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }

            val paneBefore = snapshotPaneId()
            diagnostics!!.clear()
            HostAckSendProbe.reset()
            OutboundLegacyStackProbe.reset()

            val esc = "\u001B"
            val halfBlack = buildString {
                append("$esc[2J$esc[H")
                for (line in 0 until HALF_BLACK_LIVE_LINES) {
                    val row = 1 + line * HALF_BLACK_ROW_STRIDE
                    append("$esc[$row;1H$HALF_BLACK_MARKER live line $line after send overpaint")
                }
            }
            val injected = driveHostAckSendThenInject(
                paneId = paneBefore,
                halfBlack = halfBlack,
                authoritativeCapture = authoritativeCapture,
            )
            assertHostAckDelivered(paneBefore)

            val afterInject = injected.visibleText
            assertFalse(
                "the injected half-black must have WIPED the full frame; visible:\n$afterInject",
                afterInject.contains(FRAME_MARKER),
            )
            assertTrue(
                "the injected pane must keep MORE than 3 live lines, found " +
                    "${frameRowCount(afterInject)}",
                frameRowCount(afterInject) > 3,
            )
            assertFalse(
                "precondition: the pane is NOT the ≤3-line partial-black",
                injected.partiallyBlank,
            )
            assertTrue(
                "precondition: the send-heal pre-check must flag the half-black as sparse",
                injected.looksSparse,
            )
            assertTrue(
                "precondition: the half-black render must be a LOST FRAME vs tmux",
                injected.lostFrameVsCapture,
            )
            assertTrue(
                "the transport must stay Connected, observed=${injected.connectionStatus}",
                injected.connectionStatus is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertFalse("the tmux client must NOT be disconnected", injected.clientDisconnected)
            assertNoVisibleReconnect("half-black (no reconnect surface)")

            val visibleAfter = waitForVisibleTerminal(
                "send-heal full-frame restore",
                timeoutMillis = RESTORE_TIMEOUT_MS,
            ) { it.contains(FRAME_MARKER) && frameRowCount(it) >= MIN_RESTORED_FRAME_ROWS }
            assertTrue(
                "REGRESSION (#1153 on HostAck): the with-attachment send heal must restore " +
                    "the FULL frame. Found rows=${frameRowCount(visibleAfter)}.\n$visibleAfter",
                visibleAfter.contains(FRAME_MARKER) &&
                    frameRowCount(visibleAfter) >= MIN_RESTORED_FRAME_ROWS,
            )
            assertFalse(
                "REGRESSION (#1153 on HostAck): after the send heal the render must MATCH " +
                    "tmux's authoritative capture.\n$visibleAfter",
                activePaneLostFrameVsCapture(authoritativeCapture),
            )
            capturePaintedRows("issue2189-02-healed")
            assertNoVisibleReconnect("post-heal (no reconnect surface)")
            assertTrue(
                "session must stay Connected after the send heal, " +
                    "observed=${currentConnectionStatus()}",
                currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            writeText(
                "issue2189-heal-summary.txt",
                buildString {
                    appendLine("issue=2189")
                    appendLine("property=1153-send-heal")
                    appendLine("authority=HostCliAck")
                    appendLine("host_ack_sends=${HostAckSendProbe.count()}")
                    appendLine("legacy_stack=${OutboundLegacyStackProbe.snapshot()}")
                    appendLine("pane=$paneBefore")
                },
            )
        }
    }

    private suspend fun driveHostAckSendThenInject(
        paneId: String,
        halfBlack: String,
        authoritativeCapture: String,
    ): HalfBlackSnapshot {
        var sendVm: TmuxSessionViewModel? = null
        var sendView: TerminalView? = null
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            val livePane = vm.panes.value.firstOrNull()?.paneId
            check(livePane == paneId) {
                "active pane changed before send: expected=$paneId actual=$livePane"
            }
            sendVm = vm
            sendView = checkNotNull(activity.window.decorView.findTerminalView()) {
                "expected the live TerminalView before send"
            }
        }
        val vm = checkNotNull(sendVm) { "expected a live ViewModel before send" }
        val view = checkNotNull(sendView) { "expected a retained TerminalView before send" }

        val result = vm.sendAgentPayloadToPaneResult(
            paneId,
            WITH_ATTACHMENT_PAYLOAD,
            AgentKind.Codex,
        )
        assertTrue(
            "the with-attachment send itself must succeed through HostAck; " +
                "failure=${result.exceptionOrNull()}",
            result.isSuccess,
        )
        val postSendCapture = captureRemoteTmuxPane()
        assertTrue(
            "the opt-in fake agent must retain its one initial full frame after send.\n" +
                postSendCapture,
            postSendCapture.contains(INITIAL_FULL_RENDER_SENTINEL),
        )

        var injected: HalfBlackSnapshot? = null
        compose.activityRule.scenario.onActivity { activity ->
            val currentVm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            check(currentVm === vm) {
                "activity/ViewModel changed between HostAck send and injection"
            }
            injected = injectHalfBlackAndSnapshot(
                activity = activity,
                vm = vm,
                view = view,
                frame = halfBlack,
                authoritativeCapture = authoritativeCapture,
            )
        }
        val captured = checkNotNull(injected) {
            "expected to send, acknowledge, and inject on the live activity"
        }
        writeBitmap("issue2189-01-half-black-viewport", captured.bitmap)
        captured.bitmap.recycle()
        writeText("issue2189-01-half-black-visible-terminal.txt", captured.visibleText)
        return captured
    }

    private fun assertHostAckDelivered(paneId: String) {
        assertEquals(
            "exactly one HostAck attempt must have run on the shipped default",
            1L,
            HostAckSendProbe.count(),
        )
        val acks = diagnostics!!.eventsNamed("outbound_host_ack_send")
        assertTrue(
            "outbound_host_ack_send must fire with outcome=delivered; acks=$acks " +
                "(mutation: this stays empty under TerminalInference)",
            acks.any { it.fields["outcome"] == HOST_ACK_REASON_DELIVERED },
        )
        assertTrue(
            "the ack must name the live pane $paneId; acks=$acks",
            acks.any { it.fields["pane"] == paneId },
        )
        assertEquals(
            "HostAck must not consult the inference stack: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
    }

    private fun liveViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun snapshotPaneId(): String {
        var paneId: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            paneId = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .panes.value.firstOrNull()?.paneId
        }
        return requireNotNull(paneId) { "expected a live pane before send" }
    }

    private suspend fun pauseAndProveStaleRenderWatchdogQuiescent() {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        val currentVm = checkNotNull(vm) { "expected a live ViewModel before pausing watchdog" }
        currentVm.pauseActivePaneStaleRenderWatchdogForTest()
        val currentJob = currentVm.staleRenderWatchdogJobForTest()
        assertFalse(
            "the attach-owned stale-render watchdog must be inactive before the send",
            currentJob?.isActive == true,
        )
        assertTrue(
            "the attach-owned stale-render watchdog must be fully joined",
            currentJob == null || currentJob.isCompleted,
        )
    }

    private fun activePaneLostFrameVsCapture(capture: String): Boolean {
        var hit = false
        compose.activityRule.scenario.onActivity { activity ->
            hit = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .panes.value.firstOrNull()
                ?.terminalState
                ?.visibleRenderLostFrameVsCapture(capture) ?: false
        }
        return hit
    }

    private fun injectHalfBlackAndSnapshot(
        activity: MainActivity,
        vm: TmuxSessionViewModel,
        view: TerminalView,
        frame: String,
        authoritativeCapture: String,
    ): HalfBlackSnapshot {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        val pane = checkNotNull(vm.panes.value.firstOrNull()) {
            "expected a current pane for the half-black injection"
        }
        check(view.isAttachedToWindow && view.rootView === activity.window.decorView.rootView) {
            "expected the pre-send TerminalView to remain attached"
        }
        val emulator = checkNotNull(view.mEmulator) {
            "expected the retained live emulator for the half-black injection"
        }
        emulator.append(bytes, bytes.size)
        view.invalidate()
        val afterAppend = emulator.screen.visibleScreenText
        check(afterAppend.contains(HALF_BLACK_MARKER)) {
            "half-black bytes did not land in the retained emulator"
        }
        check(view.width > 0 && view.height > 0) {
            "expected non-zero TerminalView bounds"
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
        return HalfBlackSnapshot(
            visibleText = afterAppend,
            partiallyBlank = pane.terminalState.visibleScreenIsPartiallyBlank(),
            looksSparse = pane.terminalState.renderLooksSuspect(),
            lostFrameVsCapture =
                pane.terminalState.visibleRenderLostFrameVsCapture(authoritativeCapture),
            connectionStatus = vm.connectionStatus.value,
            clientDisconnected = vm.clientDisconnectedForTest(),
            bitmap = bitmap,
        )
    }

    private data class HalfBlackSnapshot(
        val visibleText: String,
        val partiallyBlank: Boolean,
        val looksSparse: Boolean,
        val lostFrameVsCapture: Boolean,
        val connectionStatus: TmuxSessionViewModel.ConnectionStatus,
        val clientDisconnected: Boolean,
        val bitmap: Bitmap,
    )

    private fun capturePaintedRows(name: String) {
        val bitmap = renderViewportBitmap() ?: return
        writeBitmap("$name-viewport", bitmap)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap.recycle()
    }

    private fun renderViewportBitmap(): Bitmap? {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        return bitmap
    }

    private fun frameRowCount(text: String): Int =
        text.split('\n').count { it.isNotBlank() }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
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
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
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

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) writeText("failure-$label-visible-terminal.txt", last)
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()?.currentSession?.emulator?.screen?.visibleScreenText.orEmpty()
        }
        return text
    }

    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no disconnect band for $label", 0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no Tap Reconnect button for $label", 0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Reconnecting", "Disconnected", "Tap Reconnect").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label", 0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
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
                name = "issue2189-heal-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue2189 HostAck Send Heal",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    trustedHostKeySha256 = trustedHostKeySha256,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedFullFrameSession(key: String) {
        val payload =
            "POCKETSHELL_FAKE_AGENT_RENDER_MODE=$FAKE_AGENT_RENDER_MODE " +
                "exec /usr/local/bin/pocketshell-fake-agent"
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 " +
                    shellQuote(payload),
            )
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded full-frame session: ${exec?.stdout?.trim()}")
    }

    private suspend fun captureRemoteTmuxPane(): String {
        val script = "tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}"
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected `capture-pane` to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        return exec?.stdout.orEmpty()
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

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE2189_HEAL_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE2189_HEAL ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
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
        const val LOG_TAG: String = "Issue2189HostAckSendHeal"
        const val DEVICE_DIR_NAME: String = "issue2189-host-ack-send-heal"
        const val SESSION_NAME: String = "issue2189-send-heal"
        const val FRAME_MARKER: String = "ISSUE1153-FRAME"
        const val INITIAL_FULL_RENDER_SENTINEL: String = "ISSUE1153-FRAME row 1 render 1"
        const val HALF_BLACK_MARKER: String = "ISSUE2189-HALFBLACK"
        const val FAKE_AGENT_RENDER_MODE: String = "issue1153-incremental"
        const val HALF_BLACK_LIVE_LINES: Int = 6
        const val HALF_BLACK_ROW_STRIDE: Int = 2
        const val MIN_RESTORED_FRAME_ROWS: Int = 10
        const val WITH_ATTACHMENT_PAYLOAD: String =
            "please review this\n\nAttached files:\n- /home/testuser/report.txt"
        val RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 20_000L else 12_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
