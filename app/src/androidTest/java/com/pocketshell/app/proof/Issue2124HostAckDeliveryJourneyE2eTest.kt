package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SKIP_TAG
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_SEND_ENTER_TAG
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.composer.outboundQueueSummary
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.DurableOutboundRowIdentity
import com.pocketshell.app.tmux.HOST_CLI_UPGRADE_HINT
import com.pocketshell.app.tmux.HostAckSendFailedException
import com.pocketshell.app.tmux.HostAckSendProbe
import com.pocketshell.app.tmux.OutboundLegacyStackProbe
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Issue #2124 (step 1b of epic #2121) — the ON-DEVICE end-to-end proof that the
 * host-CLI acknowledgement is the sole delivery authority.
 *
 * ## The reported scenario, on a real wire
 *
 * The maintainer's photograph (#1602, comment 5283865370) is `2 queued · 2
 * unconfirmed` on a GREEN session whose terminal is visibly rendering the
 * submitted prompt as **Working**. That state is undecidable to the old oracle
 * *by design*: the framed agent surface (`┃`, no marker glyph) is stripped as
 * decoration and nothing behind it is a prompt marker, so the bounded turnover
 * proof times out and the row parks at `wireOutcomeUnknown` forever.
 *
 * This journey drives exactly that surface —
 * `pocketshell-fake-agent` in [Issue2056InducedSubmitAmbiguity.FRAMED_INPUT_RENDER_MODE],
 * the same fixture the late-authority journeys use precisely BECAUSE no oracle
 * can read it — over the real Docker `agents:2222` SSH transport, on the SHIPPED
 * default (no authority pin), through the production send entry point the
 * composer drain calls. The row must reach Delivered anyway, because the host
 * answered.
 *
 * ## The unhappy host (G10)
 *
 * [oldHostCliWithoutSendFailsClosedAndNeverStrandsTheRow] runs the SAME send
 * against `agents-old-cli:2238`, whose `pocketshell` rejects `send` with Click's
 * exact unknown-command shape. A happy fixture cannot enter that state, which is
 * why the fixture that can is part of this change
 * (`tests/docker/agent-bin-old-cli/pocketshell`). The row must become a plain
 * retryable `Failed` naming the upgrade — bounded, never a hang, and never a new
 * "unknown" state.
 *
 * Docker services: `agents` on 2222 and `agents-old-cli` on 2238 — BOTH already
 * started by the emulator job in `.github/workflows/tests.yml` (2238 for the
 * existing `FolderListOldCliHydrateDockerTest`), so this class adds no new
 * service or port. Wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class Issue2124HostAckDeliveryJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    val testName: TestName = TestName()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(testName)
        .around(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private var seededPort: Int = DEFAULT_PORT

    /**
     * The old-CLI arm needs its host seeded on 2238 BEFORE launch, so the port is
     * derived from the test name (the rule chain runs before the method body).
     */
    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        // No authority pin: this journey must run on the SHIPPED default, which
        // is the whole point of step 1b. `clearOutboundDeliveryAuthorityPin`
        // asserts the singleton really answers the default, so a leftover pin
        // from a sibling class cannot silently turn this into a legacy run.
        clearOutboundDeliveryAuthorityPin()
        OutboundLegacyStackProbe.reset()
        HostAckSendProbe.reset()
        seededPort = if (testName.methodName.contains("oldHostCli", ignoreCase = true)) {
            OLD_CLI_PORT
        } else {
            DEFAULT_PORT
        }
        fixtureKey = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey), port = seededPort)
        seedFramedFakeAgentSession(fixtureKey, seededPort)
        hostRowTag = seedDockerHost(fixtureKey, seededPort)
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { cleanupRemoteTmuxSession(fixtureKey, seededPort) } }
        runCatching {
            SharedPrefsOutboundQueueStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).clearSession(QUEUE_SESSION_KEY)
        }
    }

    /**
     * THE LOAD-BEARING JOURNEY: a prompt sent while the agent is Working reaches
     * Delivered, decided by the host CLI's exit status alone.
     */
    @Test
    fun workingAgentSendIsAcknowledgedAndTheRowClears() {
        val paneId = attachAndResolvePane()
        val vm = currentViewModel()
        val store = SharedPrefsOutboundQueueStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val payload = "PSACK${System.currentTimeMillis().toString(36).takeLast(6)} restart pool"
        val row = store.enqueue(
            sessionKey = QUEUE_SESSION_KEY,
            cleanText = payload,
            paneId = paneId,
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "sk-2124-${System.currentTimeMillis()}",
        )
        OutboundLegacyStackProbe.reset()
        HostAckSendProbe.reset()

        val sendStartedAt = SystemClock.elapsedRealtime()
        val result = runBlocking {
            vm.sendAgentPayloadToPaneResult(
                paneId,
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity(QUEUE_SESSION_KEY, row.id),
            )
        }
        val sendElapsedMs = SystemClock.elapsedRealtime() - sendStartedAt

        assertEquals(
            "on the SHIPPED default exactly one acknowledged exec must have run — a " +
                "zero here means the default is not the acknowledged path on device",
            1L,
            HostAckSendProbe.count(),
        )
        // Every row-owning send now costs an SSH exec + a Python CLI start
        // instead of a `-CC` command on the warm transport. Send latency is a
        // live complaint (#1687), so the number is recorded rather than assumed.
        writeText(
            "issue2124-04-send-latency.txt",
            "acknowledged_send_ms=$sendElapsedMs\n" +
                "bound_ms=$HOST_ACK_SEND_LATENCY_BOUND_MS\n",
        )
        assertTrue(
            "an acknowledged send must stay inside its transport bound " +
                "(took ${sendElapsedMs}ms)",
            sendElapsedMs < HOST_ACK_SEND_LATENCY_BOUND_MS,
        )

        assertTrue(
            "a send to a WORKING agent must be ACKNOWLEDGED by the host, not guessed " +
                "from the screen; failure was ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        assertEquals(
            "the acknowledged path must not consult the legacy inference stack at all: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
        assertTrue("the row must be prunable", store.markDelivered(row.id))
        assertNull("a delivered row is pruned", store.item(row.id))

        // GROUND TRUTH from the host, not from the app's belief: the payload is
        // in the pane exactly once, and the host journaled the token.
        val pane = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        writeText("issue2124-01-acknowledged-capture.txt", pane)
        assertEquals(
            "the token must keep the payload to exactly one occurrence in the pane:\n$pane",
            1,
            Regex(Regex.escape(payload.substringBefore(' '))).findAll(pane).count(),
        )
        val journal = runBlocking { remoteJournalListing(fixtureKey, seededPort) }
        writeText("issue2124-02-host-journal.txt", journal)
        assertTrue(
            "the host must have journaled the delivery token:\n$journal",
            journal.isNotBlank(),
        )
        assertTrue(
            "no row may be left unconfirmed",
            store.itemsFor(QUEUE_SESSION_KEY).none { it.wireOutcomeUnknown },
        )
    }

    /**
     * The SAME acknowledged delivery, driven the way the maintainer drives it:
     * typing into the production composer and tapping Send, on the shipped
     * default, against the Working agent surface.
     *
     * #1602 is a composer-queue symptom, so the composer drain — draft →
     * durable row → dispatch → prune — is the journey that matters. The
     * ViewModel-entry test above pins the delivery contract; this one pins that
     * the user-facing path reaches it and that the acknowledged lane is what ran.
     */
    @Test
    fun composerSendOnTheShippedDefaultIsAcknowledgedAndLeavesNoQueueRow() {
        val paneId = attachAndResolvePane()
        val store = SharedPrefsOutboundQueueStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val payload = "PSCMP${System.currentTimeMillis().toString(36).takeLast(6)} ship it"
        OutboundLegacyStackProbe.reset()
        HostAckSendProbe.reset()

        val queueKey = currentQueueSessionKey()
        openComposerAndSend(payload)

        // The row must clear on its own: the acknowledged answer prunes it. If the
        // Send never dispatched, or the send never resolved, this is where it
        // fails — the row is the oracle, not the sheet dismissing.
        compose.waitUntil(timeoutMillis = QUEUE_DRAIN_TIMEOUT_MS) {
            HostAckSendProbe.count() >= 1L &&
                store.itemsFor(queueKey).none { it.cleanText == payload }
        }

        assertTrue(
            "the composer Send must have travelled the ACKNOWLEDGED lane on the " +
                "shipped default, not the legacy inference stack",
            HostAckSendProbe.count() >= 1L,
        )
        assertEquals(
            "the acknowledged path must not consult the legacy inference stack: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
        assertTrue(
            "no row may be left unconfirmed after a composer send",
            store.itemsFor(queueKey).none { it.wireOutcomeUnknown },
        )

        val pane = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        writeText("issue2124-05-composer-capture.txt", pane)
        assertEquals(
            "the composer prompt must be in the pane exactly once:\n$pane",
            1,
            Regex(Regex.escape(payload.substringBefore(' '))).findAll(pane).count(),
        )
    }

    /**
     * G10 unhappy host: an OLD host CLI without `send` must fail CLOSED to a plain
     * retryable `Failed` naming the upgrade — bounded, never a hang, never a new
     * unknown state.
     */
    @Test
    fun oldHostCliWithoutSendFailsClosedAndNeverStrandsTheRow() {
        val paneId = attachAndResolvePane()
        val vm = currentViewModel()
        val store = SharedPrefsOutboundQueueStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val payload = "PSOLD${System.currentTimeMillis().toString(36).takeLast(6)} upgrade me"
        val row = store.enqueue(
            sessionKey = QUEUE_SESSION_KEY,
            cleanText = payload,
            paneId = paneId,
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "sk-2124-old-${System.currentTimeMillis()}",
        )
        OutboundLegacyStackProbe.reset()

        val startedAt = SystemClock.elapsedRealtime()
        val result = runBlocking {
            vm.sendAgentPayloadToPaneResult(
                paneId,
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity(QUEUE_SESSION_KEY, row.id),
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        assertTrue("an old host CLI must fail, not silently succeed", result.isFailure)
        assertTrue(
            "the send must be BOUNDED, never a hang (took ${elapsed}ms)",
            elapsed < OLD_CLI_BOUND_MS,
        )
        val failure = result.exceptionOrNull()
        assertTrue(
            "the failure must be the acknowledged path's own type, not the turnover-" +
                "not-proven type that becomes the absorbing 'unconfirmed' row; got $failure",
            failure is HostAckSendFailedException,
        )
        assertEquals(
            "the message must name the host-CLI upgrade",
            HOST_CLI_UPGRADE_HINT,
            failure?.message,
        )
        assertEquals(
            "an old host CLI must NOT silently fall back to the old stack: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
        val stored = requireNotNull(store.item(row.id)) { "the row must survive as retryable" }
        assertFalse("the row must never be marked unconfirmed", stored.wireOutcomeUnknown)
        assertFalse("no write-ahead barrier may strand it", stored.wireSubmitAttempted)
        assertNull(
            "no UI path may show an 'unconfirmed' count",
            outboundQueueSummary(listOf(stored), connectionDegraded = false).attentionSuffix,
        )
        val pane = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        writeText("issue2124-03-old-cli-capture.txt", pane)
        assertFalse(
            "a failed acknowledgement must not have injected anything:\n$pane",
            pane.contains(payload.substringBefore(' ')),
        )
    }

    // ------------------------------------------------------------- harness

    /** The durable-queue key the composer enqueues under for the live session. */
    private fun currentQueueSessionKey(): String =
        requireNotNull(currentViewModel().currentTargetSessionKeyForTest()) {
            "a live session target is required to read the composer queue"
        }

    private fun hasTag(tag: String): Boolean = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun sendEnabled(): Boolean = runCatching {
        val node = compose.onAllNodesWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull() ?: return false
        !node.config.contains(SemanticsProperties.Disabled)
    }.getOrDefault(false)

    /** Type into the production composer and tap its Send — no seam, no shortcut. */
    private fun openComposerAndSend(payload: String) {
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            hasTag(SESSION_COMPOSER_LAUNCHER_TAG) || hasTag(COMPOSER_DRAFT_TAG)
        }
        if (!hasTag(COMPOSER_DRAFT_TAG)) {
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) { hasTag(COMPOSER_DRAFT_TAG) }
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true).performTextInput(payload)
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) { sendEnabled() }
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true).performClick()
    }

    private fun attachAndResolvePane(): String {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // The `agents-old-cli` fixture reports an OLDER `pocketshell --version`
        // than the app, so connecting raises the "Host setup needed" bootstrap
        // sheet before the session list. Tap Skip exactly as
        // FolderListBootstrapSkipTreeLoadsDockerTest does. Bounded and
        // tolerant: the current-CLI fixture never shows the sheet.
        runCatching {
            compose.waitUntil(timeoutMillis = BOOTSTRAP_SHEET_PROBE_MS) {
                compose.onAllNodesWithTag(HOST_BOOTSTRAP_SKIP_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(HOST_BOOTSTRAP_SKIP_TAG, useUnmergedTree = true).performClick()
        }
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                attached = activity.window.decorView.findTerminalView()
                    ?.currentSession?.emulator != null
            }
            attached
        }
        val vm = currentViewModel()
        return requireNotNull(vm.panes.value.firstOrNull()?.paneId) {
            "expected at least one attached pane to send into"
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

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun seedDockerHost(key: String, port: Int): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue2124-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue2124 Host Ack",
                    hostname = DEFAULT_HOST,
                    port = port,
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
     * The UNDECIDABLE surface: the framed fake-agent box real Claude/Codex draw
     * while WORKING. [Issue2056InducedSubmitAmbiguity] documents why no oracle can
     * read it — which is exactly why the acknowledged path must not need to.
     */
    private suspend fun seedFramedFakeAgentSession(key: String, port: Int) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 " +
                    shellQuote(
                        "POCKETSHELL_FAKE_AGENT_RENDER_MODE=" +
                            Issue2056InducedSubmitAmbiguity.FRAMED_INPUT_RENDER_MODE +
                            " exec /usr/local/bin/pocketshell-fake-agent",
                    ),
            )
            appendLine("tmux set-option -p -t ${shellQuote(SESSION_NAME)} @ps_agent_kind claude")
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue2124 framed fake-agent seed",
            port = port,
        )
        assertTrue(
            "expected framed fake-agent seeding to succeed; exit=${result.exitCode} " +
                "stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun remoteExec(key: String, port: Int, command: String): String =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = port,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(command).stdout } }.getOrElse { "" }

    private suspend fun remoteCapture(key: String, port: Int, paneId: String): String =
        remoteExec(key, port, "tmux capture-pane -p -t ${shellQuote(paneId)} 2>&1 || true")

    private suspend fun remoteJournalListing(key: String, port: Int): String =
        remoteExec(
            key,
            port,
            "ls -1 \"\${XDG_STATE_HOME:-\$HOME/.local/state}\"/pocketshell/sends 2>/dev/null || true",
        )

    private suspend fun cleanupRemoteTmuxSession(key: String, port: Int) {
        remoteExec(key, port, "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
    }

    private fun writeText(name: String, text: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        val file = File(dir, name)
        file.writeText(text)
        println("ISSUE2124_TEXT ${file.absolutePath}")
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
        const val DEVICE_DIR_NAME: String = "issue2124-host-ack"
        const val SESSION_NAME: String = "issue2124-framed-agent"
        const val QUEUE_SESSION_KEY: String = "issue2124/hostack"

        /** `agents-old-cli` — the fixture whose `pocketshell` has no `send`. */
        const val OLD_CLI_PORT: Int = 2238

        /**
         * The old-CLI send must answer well inside the transport bound. Generous
         * enough for a contended emulator, tight enough that a HANG fails.
         */
        const val OLD_CLI_BOUND_MS: Long = 30_000L

        /**
         * The whole acknowledged round trip (SSH exec + Python CLI start + tmux
         * paste) must complete well inside the port's 15s transport bound. The
         * measured number is written to an artifact either way; this only fails
         * a send that has effectively hung.
         */
        const val HOST_ACK_SEND_LATENCY_BOUND_MS: Long = 20_000L

        const val HOST_ROW_TIMEOUT_MS: Long = 60_000L

        /**
         * How long to look for the old-CLI bootstrap sheet before concluding the
         * host does not raise one. Short: the sheet is decided at connect time.
         */
        const val BOOTSTRAP_SHEET_PROBE_MS: Long = 20_000L

        /** Composer launcher/draft/Send visibility on a contended emulator. */
        const val COMPOSER_TIMEOUT_MS: Long = 30_000L

        /**
         * How long the acknowledged answer has to prune the composer's durable
         * row. One SSH exec plus a CLI start; generous for a contended AVD, and a
         * genuine hang still fails.
         */
        const val QUEUE_DRAIN_TIMEOUT_MS: Long = 60_000L
    }
}
