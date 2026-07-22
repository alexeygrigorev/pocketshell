package com.pocketshell.app.tmux

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE
import com.pocketshell.app.composer.OutboundQueueStore
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.voice.WhisperClient
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #1686 (Track C) — the ON-DEVICE queue-drain journey (reviewer G4 / D33
 * gate). The maintainer's daily blocker: "the composer queue gets clogged
 * because it thinks the connection is not there." The #1680 reconnect storm
 * produces a FALSE / flapping `ConnectionStatus` (not-Connected) while the `-CC`
 * transport is perfectly writable, and both enum-trusting layers shut the drain:
 *
 *  - **Admission** ([TmuxSessionViewModel.liveTmuxClientForSendOrNull]) refused a
 *    live `clientRef` unless the enum said `Connected`, so a dispatched send
 *    failed ("Session is disconnected") even though the wire would accept it.
 *  - **The drain gate** ([runOutboundQueueAutoFlush]) was hard-gated on the enum
 *    (`sessionLive`), so during a false-disconnect NOTHING even tried the wire.
 *
 * The JVM proofs ([Issue1686WireOracleSendTest], [PromptComposerWireOracleClogTest])
 * pin the seams, but the reviewer BLOCKED (G4) because a JVM proxy is not the
 * acceptance for a user-facing composer×connection fix — the acceptance is a
 * connected (emulator + Docker) journey proving the clog is GONE on the REAL wire.
 *
 * This test is that journey. It:
 *  1. attaches to a REAL `opencode-lab` tmux session over a REAL SSH `-CC`
 *     transport on the Docker `agents` fixture (the live activity-scoped
 *     [TmuxSessionViewModel] with a genuinely-writable `clientRef`),
 *  2. injects the EXACT false-disconnect the storm produces — the #780
 *     synthetic-injection model, deterministic, no self-skip:
 *     [TmuxSessionViewModel.forceInlineReconnectingStatusKeepingClientForTest]
 *     flips the inline enum to `Reconnecting` (the admission gate's oracle) while
 *     the real `clientRef` stays live, and the drain runs with `sessionLive=false`
 *     (the drain gate's enum oracle). BOTH enum labels are false; the wire is
 *     genuinely alive,
 *  3. enqueues a composer prompt and drives the PRODUCTION drain machinery — the
 *     real [runOutboundQueueAutoFlush] + [PromptComposerViewModel.retryNextOutboundItem]
 *     + the `PromptComposerSendDispatcher`-shaped collector whose `onSend` is the
 *     production [TmuxSessionViewModel.writeInputToPaneResult] — and asserts the
 *     prompt DRAINS over the writable wire: the marker appears in the real tmux
 *     pane (authoritative `capture-pane` artifact),
 *  4. covers the transport-alive-edge self-heal
 *     ([PromptComposerViewModel.unparkTransportFailedRows]): a storm-stranded
 *     auto-parked `Failed` backlog un-parks on the connected edge and then drains
 *     to the real pane.
 *
 * RED reproduction (reviewer, this run):
 *  - revert the admission hunk (restore `if (inlineConnectionStatus !is Connected)
 *    return null` in [TmuxSessionViewModel.liveTmuxClientForSendOrNull]) → the send
 *    is refused on the false `Reconnecting` label → the marker NEVER reaches the
 *    pane (the clog) → this test times out red.
 *  - OR revert the drain-gate hunk (`drainGateOpen() = sessionLive`) → the drain
 *    never attempts the wire with `sessionLive=false` → the marker never reaches
 *    the pane → red.
 *
 * Uses the plain deterministic `agents:2222` fixture (no toxiproxy) — the false-
 * disconnect is injected at the two exact seams the fix touches, so the wire is a
 * genuinely healthy SSH connection whose writability is the strongest possible
 * proof that the enum label was false. Wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class Issue1686QueueDrainWireOracleDockerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Grant runtime permissions before MainActivity launches (issue #470 blocker
    // #1) so the system GrantPermissionsActivity never steals Compose focus.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val harnessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val createdComposerVms = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDown() {
        harnessScope.cancel()
        compose.runOnUiThread {
            createdComposerVms.forEach { runCatching { it.clearForTest() } }
        }
        createdComposerVms.clear()
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        runBlocking { runCatching { cleanupSeededSession(readFixtureKey()) } }
    }

    // ---------------------------------------------------------------- Tests

    @Test
    fun queuedComposerSendDrainsOverWritableWireWhileConnectionStatusFalselyNotConnected() {
        runBlocking {
            val key = readFixtureKey()
            waitForSshFixtureReady(SshKey.Pem(key))
            seedInteractiveSession(key)
            val hostRowTag = seedDockerHost(key, "Issue1686 Drain")

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            attachToOpencodeLab(hostRowTag)

            val liveVm = liveTmuxViewModel()
            val paneId = awaitAttachedPaneId(liveVm)

            // --- Inject the EXACT false-disconnect (the #1680 storm): the inline
            //     enum flips to `Reconnecting` (admission oracle) while the real
            //     `clientRef` stays live + writable.
            onMainUnit { liveVm.forceInlineReconnectingStatusKeepingClientForTest() }

            // The WIRE is the oracle: it must report the transport truth over the
            // real socket even though the ConnectionStatus enum now says not-Connected.
            val writable = liveVm.isSendTransportWritable()
            assertTrue(
                "the wire-oracle must see the writable `-CC` transport over the REAL socket " +
                    "even though the inline ConnectionStatus enum falsely reports Reconnecting (#1686)",
                writable,
            )

            // --- Build the REAL composer + queue, target the drain session, and
            //     wire the WIRE-oracle probe to the live TmuxSessionViewModel exactly
            //     as TmuxSessionScreenEffects does in production.
            val queue = InMemoryOutboundQueueStore()
            val composer = newComposerVm(queue)
            val target = "issue1686/drain"
            onMainUnit {
                composer.onComposerTargetChanged(target)
                composer.setTransportWritableProbe { liveVm.isSendTransportWritable() }
                composer.setSendWatchdogTimeoutForTest(null)
            }

            startProductionDrain(composer, queue, liveVm, paneId, target)

            // --- Enqueue a prompt while the status FALSELY says not-Connected.
            val marker = "PSDRAIN${System.currentTimeMillis().toString(36).takeLast(6)}"
            onMainUnit {
                queue.enqueue(
                    sessionKey = target,
                    cleanText = "# $marker",
                    createdAtMs = System.currentTimeMillis(),
                )
                composer.refreshOutboundQueueItemsFor(target)
            }

            // --- The prompt must DRAIN over the writable wire and reach the REAL
            //     tmux pane (authoritative capture-pane), and the queue must empty.
            val pane = waitForPaneContains(key, marker, label = "drain")
            awaitTrue("queue drains to empty after delivery") {
                composer.outboundQueueItems.value.isEmpty()
            }

            captureViewport("01-drained-over-writable-wire")
            writeSummary(
                testName = "queuedComposerSendDrainsOverWritableWire",
                lines = listOf(
                    "target=$target",
                    "pane_id=$paneId",
                    "marker=$marker",
                    "inline_enum=Reconnecting (false-disconnect, injected)",
                    "wire_writable=$writable",
                    "drain_gate_sessionLive=false",
                    "captured_pane_contains_marker=${marker in pane}",
                    "queue_empty_after_delivery=true",
                ),
            )
        }
    }

    @Test
    fun transportAliveEdgeUnparksAutoFailedRowThenDrainsOverWire() {
        runBlocking {
            val key = readFixtureKey()
            waitForSshFixtureReady(SshKey.Pem(key))
            seedInteractiveSession(key)
            val hostRowTag = seedDockerHost(key, "Issue1686 Unpark")

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            attachToOpencodeLab(hostRowTag)

            val liveVm = liveTmuxViewModel()
            val paneId = awaitAttachedPaneId(liveVm)
            onMainUnit { liveVm.forceInlineReconnectingStatusKeepingClientForTest() }

            val queue = InMemoryOutboundQueueStore()
            val composer = newComposerVm(queue)
            val target = "issue1686/unpark"
            onMainUnit {
                composer.onComposerTargetChanged(target)
                composer.setTransportWritableProbe { liveVm.isSendTransportWritable() }
                composer.setSendWatchdogTimeoutForTest(null)
            }

            // --- Model the storm-stranded backlog: an AUTO-parked Failed row
            //     (budget exhausted).
            val marker = "PSUNPARK${System.currentTimeMillis().toString(36).takeLast(6)}"
            val rowId = onMain {
                val row = queue.enqueue(
                    sessionKey = target,
                    cleanText = "# $marker",
                    createdAtMs = System.currentTimeMillis(),
                )
                queue.markInFlight(row.id)
                queue.markFailed(row.id, lastError = OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE)
                composer.refreshOutboundQueueItemsFor(target)
                row.id
            }
            assertEquals(
                "precondition: the row is auto-parked Failed (a storm-stranded backlog)",
                OutboundState.Failed,
                queue.item(rowId)?.state,
            )

            // --- The transport-alive edge self-heal (production wires this to
            //     onConnectionWindowChanged's connected edge in TmuxSessionScreenEffects).
            val unparked = onMain { composer.unparkTransportFailedRows() }
            assertTrue(
                "the transport-alive edge must un-park the auto-parked backlog (#1686 self-heal)",
                rowId in unparked,
            )
            assertEquals(
                "the un-parked row re-arms to Queued so the drain can re-claim it",
                OutboundState.Queued,
                queue.item(rowId)?.state,
            )

            // --- The re-armed row must then DRAIN over the writable wire to the
            //     REAL pane.
            startProductionDrain(composer, queue, liveVm, paneId, target)
            val pane = waitForPaneContains(key, marker, label = "unpark-drain")
            awaitTrue("un-parked backlog drains to empty after delivery") {
                composer.outboundQueueItems.value.isEmpty()
            }

            captureViewport("02-unparked-then-drained")
            writeSummary(
                testName = "transportAliveEdgeUnparksAutoFailedRowThenDrains",
                lines = listOf(
                    "target=$target",
                    "pane_id=$paneId",
                    "marker=$marker",
                    "row_state_before_unpark=Failed(auto-exhausted)",
                    "unparked_ids=$unparked",
                    "row_state_after_unpark=Queued",
                    "captured_pane_contains_marker=${marker in pane}",
                    "queue_empty_after_delivery=true",
                ),
            )
        }
    }

    // ------------------------------------------------------- Drain machinery

    /**
     * Start the PRODUCTION drain machinery, wired to the live [TmuxSessionViewModel]
     * over the real wire:
     *  - a `PromptComposerSendDispatcher`-shaped collector whose `onSend` is the
     *    production [TmuxSessionViewModel.writeInputToPaneResult] (so reverting the
     *    admission hunk reds this journey), and whose failure classification is the
     *    production `resetAttemptBudget = !isSendTransportWritable()` taxonomy;
     *  - the real [runOutboundQueueAutoFlush] with `sessionLive=false` and the
     *    wire-oracle `transportWritable` probe (so reverting the drain-gate hunk
     *    reds this journey).
     * Both run on the Main dispatcher on [harnessScope] and are cancelled in @After.
     */
    private fun startProductionDrain(
        composer: PromptComposerViewModel,
        queue: OutboundQueueStore,
        liveVm: TmuxSessionViewModel,
        paneId: String,
        target: String,
    ) {
        // The dispatcher: mirrors PromptComposerSendDispatcher's #745 bounded send
        // + #1686 failure taxonomy, but its onSend is the PRODUCTION send path.
        harnessScope.launch {
            composer.sendRequests.collect { request ->
                val delivered = runCatching {
                    withTimeoutOrNull(PromptComposerViewModel.SEND_TIMEOUT_MS) {
                        liveVm.writeInputToPaneResult(
                            paneId,
                            (request.text + "\r").toByteArray(Charsets.UTF_8),
                        ).isSuccess
                    } == true
                }.getOrDefault(false)
                if (delivered) {
                    composer.markSendDelivered(request)
                } else {
                    composer.markOutboundSendDeferred(
                        request,
                        resetAttemptBudget = !liveVm.isSendTransportWritable(),
                    )
                }
            }
        }
        // The real production drain: the WIRE-oracle gate opens on
        // `sessionLive || transportWritable()`. sessionLive=false models the enum
        // false-disconnect; the probe reads the live wire.
        val controller =
            OutboundQueueAutoFlushController.boundTo(composer, clock = { SystemClock.elapsedRealtime() })
        harnessScope.launch {
            controller.onConnectionWindowChanged(sessionLive = false, targetSessionId = target) {}
            runOutboundQueueAutoFlush(
                sessionLive = false,
                outboundQueueItems = composer.outboundQueueItems,
                controller = controller,
                retryNext = { excludingIds -> composer.retryNextOutboundItem(excludingIds) },
                transportWritable = { liveVm.isSendTransportWritable() },
            )
        }
    }

    // ------------------------------------------------------------ UI journey

    private fun attachToOpencodeLab(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 45_000) {
            compose.onAllNodesWithText(SESSION_LAB, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_LAB).performClick()
        compose.waitUntil(timeoutMillis = 45_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Wait for the real terminal + `-CC` client to attach (the live clientRef).
        compose.waitUntil(timeoutMillis = 45_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    /**
     * Wait for the live VM to register the attached pane (so
     * [TmuxSessionViewModel.writeInputToPaneResult] has a real pane id to target),
     * then return it. `panes` is a StateFlow — a thread-safe read off the test
     * thread; do NOT wrap in [onMain] (that would run on Main and any nested
     * instrumentation call there throws "can not be called from the main thread").
     */
    private fun awaitAttachedPaneId(liveVm: TmuxSessionViewModel): String {
        compose.waitUntil(timeoutMillis = 30_000) {
            (liveVm.panes.value.firstOrNull()?.paneId ?: "").isNotBlank()
        }
        return liveVm.panes.value.firstOrNull()?.paneId
            ?: error("no attached pane id from the live TmuxSessionViewModel")
    }

    private fun liveTmuxViewModel(): TmuxSessionViewModel {
        val scenario = launchedActivity ?: error("activity not launched")
        var vm: TmuxSessionViewModel? = null
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(vm) { "could not resolve the activity-scoped TmuxSessionViewModel" }
    }

    /** Run [block] synchronously on the activity's Main thread and return its value. */
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        var captured = false
        compose.runOnUiThread {
            result = block()
            captured = true
        }
        check(captured) { "onMain block did not run" }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun onMainUnit(block: () -> Unit) {
        compose.runOnUiThread { block() }
    }

    // ------------------------------------------------------------- Fixtures

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun newComposerVm(store: OutboundQueueStore): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = NoopMicCapture(),
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                        Result.success("")
                }
            },
            apiKeyStorage = NoopVault(),
            voiceSettings = NoopVoiceSettings(),
            outboundQueueStore = store,
            savedStateHandle = SavedStateHandle(),
        )
        createdComposerVms += vm
        return vm
    }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1686-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
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

    /**
     * Seed `opencode-lab` with an INTERACTIVE shell (`sh -i`) so a `send-keys`
     * prompt is echoed into the pane and shows up in `capture-pane` — the marker
     * the drain assertion looks for. A `# <marker>` comment line echoes the token
     * without any command-not-found noise.
     */
    private suspend fun seedInteractiveSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_LAB)} " +
                    shellQuote("printf 'ISSUE1686-READY\\n'; exec sh -i"),
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
            "expected interactive session seed to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun capturePane(key: String): String {
        val script = "tmux capture-pane -p -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true"
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
        return result.getOrNull()?.stdout.orEmpty()
    }

    private suspend fun waitForPaneContains(key: String, marker: String, label: String): String {
        var last = ""
        val deadline = SystemClock.elapsedRealtime() + PANE_DRAIN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            last = capturePane(key)
            if (marker in last) {
                artifactFile("$label-visible-terminal.txt").writeText(last)
                return last
            }
            SystemClock.sleep(300)
        }
        artifactFile("failure-$label-visible-terminal.txt").writeText(last)
        assertTrue(
            "expected the queued prompt to DRAIN over the writable wire and appear in the real " +
                "tmux pane for $label; marker '$marker' never landed. Captured pane:\n$last",
            marker in last,
        )
        return last
    }

    private suspend fun cleanupSeededSession(key: String) {
        runCatching {
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).mapCatching { session ->
                    session.use {
                        it.exec("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
                    }
                }
            }
        }
    }

    private fun awaitTrue(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + PANE_DRAIN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(150)
        }
        assertTrue("timed out waiting for: $label", predicate())
    }

    // ------------------------------------------------------------ Artifacts

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name-viewport.png")
        try {
            java.io.FileOutputStream(file).use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            println("ISSUE1686_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeSummary(testName: String, lines: List<String>) {
        artifactFile("$testName-summary.txt").writeText(
            buildString {
                appendLine("test=$testName")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                appendLine("details:")
                lines.forEach { appendLine("  $it") }
            },
        )
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create issue #1686 artifact directory ${dir.absolutePath}"
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

    // ----------------------------------------------------------- VM fakes

    private class NoopMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class NoopVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class NoopVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1686-queue-drain-wire-oracle"

        /**
         * The session name the `agents` Docker fixture's picker stub recognises;
         * `opencode-lab` is the canonical choice shared with the other tmux E2Es.
         */
        const val SESSION_LAB: String = "opencode-lab"

        const val PANE_DRAIN_TIMEOUT_MS: Long = 30_000L
    }
}
