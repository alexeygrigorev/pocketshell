package com.pocketshell.app.tmux

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.proof.ToxiproxyControl
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #817 (Rank-1 measurement): the FIRST network-realistic measurement of
 * the Conversation-view open + warm-switch latency, against the <0.3s gate.
 *
 * ### Why this test exists
 *
 * Every recorded `conversation_open_ms` so far (13–18 ms) was measured on the
 * direct `agents:2222` fixture — **localhost, zero-RTT** — which is exactly the
 * case the #817 spike said hides the real bottleneck. The spike found the open
 * path is gated on **serial SSH round-trips** (detection execs + the first
 * window read), not parse/render, so a zero-RTT number is meaningless for the
 * "phone on mobile/wifi to a remote host" reality the maintainer cares about.
 *
 * This test routes the production [TmuxSessionViewModel] through the Docker
 * `network-fault-proxy` (toxiproxy on host port 2228, upstream `agents:22` —
 * the SAME fixture as 2222) with a **symmetric latency toxic** injected, so each
 * SSH round-trip pays a realistic RTT. It then measures, against a RECORDED
 * `@ps_agent_kind` session (the #825 path that #818 will default to):
 *
 *  - **Cold open** (`conversation_open_full`): from "tap into the recorded agent
 *    session → first parsed transcript content live in UI state", end-to-end,
 *    including the detection chain. This is the user-visible open the issue asks
 *    about. The window-read-only leg (`conversation_open`) is recorded alongside
 *    it so the detection-chain cost is `full - window`.
 *  - **Warm switch** (`conversation_switch`): Terminal → Conversation when the
 *    transcript is already loaded — a pure StateFlow read with no SSH.
 *
 * Run at two RTTs (~150 ms and ~80 ms) so the number reflects a typical and a
 * good mobile link.
 *
 * ### Recorded session
 *
 * The session carries `@ps_agent_kind = claude` (set with `tmux set-option`),
 * so the open takes the #825 recorded-identity path: `readRecordedAgentKind`
 * (1 exec) → `detectRecordedSessionForPane` (candidate enumeration + process
 * scan) → `readEventsWindow` (1 exec). No fixture-image change is needed — real
 * tmux honours the user option and the seeding sets it directly.
 *
 * ### Gating
 *
 * The `network-fault-proxy` service is opt-in (the default CI workflow does not
 * start it), so this test is gated off CI (`isRunningOnCi()`) AND behind the
 * `pocketshellNetworkFaultProofs=true` instrumentation arg, exactly like the
 * other [com.pocketshell.app.proof.NetworkFaultProofBase] proofs. It is local
 * measurement evidence; CI coverage of the open-path correctness stays in the
 * unit + the deterministic `agentOpensOnDefaultViewAndIsNotYankedMidSessionShellGetsNoConversationRow`
 * connected test.
 */
@RunWith(AndroidJUnit4::class)
class ConversationOpenLatencyRttDockerTest {

    @get:Rule
    val preGrantPermissions = PreGrantPermissionsRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupCommands = mutableListOf<String>()
    private val measurements = mutableListOf<String>()
    private var proxyTouched = false

    @After
    fun tearDown() {
        if (cleanupCommands.isNotEmpty()) {
            runBlocking {
                runCatching { execRemote(readFixtureKey(), cleanupCommands.joinToString("\n")) }
            }
        }
        if (proxyTouched) runCatching { toxiproxy().reset() }
        factoryScope.cancel()
        writeSummary()
    }

    @Test
    fun coldOpenAndWarmSwitchUnderRealisticRtt(): Unit = runBlocking {
        // Opt-in, local-only: the network-fault-proxy is not started by CI.
        Assume.assumeFalse(
            "network-fault-proxy is an opt-in Docker fixture; tests.yml does not start it",
            TerminalTestTimeouts.isRunningOnCi(),
        )
        val enabled = InstrumentationRegistry.getArguments()
            .getString("pocketshellNetworkFaultProofs")
            ?.toBooleanStrictOrNull() == true
        Assume.assumeTrue(
            "Enable with -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true " +
                "after `docker compose -f tests/docker/docker-compose.yml up -d --build agents network-fault-proxy`",
            enabled,
        )

        val key = readFixtureKey()
        // Both the direct fixture (for seeding) and the proxy must be reachable.
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        toxiproxy().reset()
        proxyTouched = true
        waitForSshFixtureReady(SshKey.Pem(key), port = NETWORK_FAULT_SSH_PORT)

        val keyPath = writeKeyFile(key)

        // Two RTTs: ~150 ms (typical mobile/wifi) and ~80 ms (a good link).
        // One-way latencies of 75 / 40 ms; toxiproxy delays each direction.
        measureAtRtt(key, keyPath, oneWayMs = 75)
        measureAtRtt(key, keyPath, oneWayMs = 40)
    }

    private suspend fun measureAtRtt(key: String, keyPath: String, oneWayMs: Int) {
        val rttMs = oneWayMs * 2
        val suffix = "${System.currentTimeMillis().toString().takeLast(8)}-rtt$rttMs"
        val sessionName = "issue817-rtt-$suffix"
        val processDir = "/tmp/issue817-claude-${System.nanoTime()}"
        val wrapperPath = "$processDir/claude"
        val homeDir = "/home/$DEFAULT_USER"
        val agentCwd = "$homeDir/issue817-pocketshell-$suffix"
        val encodedClaudeCwd = agentCwd.replace('/', '-')
        val claudeProjectDir = "$homeDir/.claude/projects/$encodedClaudeCwd"
        // #825 recorded-identity path: the Claude source is
        // ~/.claude/projects/<encodeClaudeCwd(cwd)>/<sessionId>.jsonl. We don't
        // know the tmux session-id token used by the resolver here, so name the
        // jsonl deterministically and let the most-recent-candidate selection
        // (scoped to the recorded Claude kind) bind to it.
        val claudeJsonl = "$claudeProjectDir/issue817-rtt.jsonl"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        cleanupCommands += "pkill -f ${shellQuote(wrapperPath)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(processDir)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(agentCwd)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(claudeProjectDir)} 2>/dev/null || true"

        // Seed: a `claude`-named foreground process in the project cwd, a fresh
        // Claude JSONL, and crucially the RECORDED @ps_agent_kind on the session
        // so the open takes the #825 recorded-identity path (not foreign
        // detection). All seeding goes through the DIRECT port (no latency) so
        // only the app's open path pays the injected RTT.
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("mkdir -p ${shellQuote(agentCwd)}")
                appendLine("mkdir -p ${shellQuote(claudeProjectDir)}")
                appendLine("cat > ${shellQuote(claudeJsonl)} <<'JSONL_EOF'")
                appendLine(
                    """{"uuid":"u817-1","timestamp":"2026-06-18T10:00:00Z",""" +
                        """"message":{"role":"user","content":"hello agent"}}""",
                )
                appendLine(
                    """{"uuid":"a817-1","timestamp":"2026-06-18T10:00:01Z",""" +
                        """"message":{"role":"assistant","content":[{"type":"text","text":"hi back"}]}}""",
                )
                appendLine("JSONL_EOF")
                appendLine("mkdir -p ${shellQuote(processDir)}")
                appendLine("cat > ${shellQuote(wrapperPath)} <<'WRAPPER_EOF'")
                appendLine("#!/bin/sh")
                appendLine("while true; do sleep 5; done")
                appendLine("WRAPPER_EOF")
                appendLine("chmod +x ${shellQuote(wrapperPath)}")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} " +
                        "-c ${shellQuote(agentCwd)} ${shellQuote(wrapperPath)}",
                )
                // Record the agent kind on the session (the #825 launch wrapper
                // does this for PocketShell-launched sessions).
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind claude")
                appendLine("sleep 1")
                // Sanity: the option is readable exactly as the app reads it.
                appendLine("tmux show-options -v -t ${shellQuote(sessionName)} @ps_agent_kind")
            },
        )

        // Inject the symmetric latency on the proxy BEFORE the VM connects, so
        // every SSH round-trip the open path makes pays the RTT.
        val proxy = toxiproxy()
        proxy.reset()
        proxy.addSymmetricLatency(oneWayMs)

        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
            runtimeCache = TmuxSessionRuntimeCache(maxEntries = 0),
        )
        // Issue #818: this is a warm-SWITCH latency test (Terminal -> Conversation
        // on an already-loaded row). The production open-time default is now
        // Conversation (#818), which would open this session straight onto
        // Conversation and make the "tap to switch" a no-op. Pin the open-time
        // default to Terminal so the row lands on Terminal and the warm switch is
        // the scenario under measurement. (The open-on-Conversation default itself
        // is covered by the unit + the deterministic connected default tests.)
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        try {
            vm.connect(
                hostId = 817L,
                hostName = "Issue817 RTT$rttMs Docker",
                host = DEFAULT_HOST,
                port = NETWORK_FAULT_SSH_PORT,
                user = DEFAULT_USER,
                keyPath = keyPath,
                passphrase = null,
                sessionName = sessionName,
            )
            waitForStatus<TmuxSessionViewModel.ConnectionStatus.Connected>(vm, "rtt$rttMs connect")
            val panes = waitForPanes(vm, "rtt$rttMs panes")
            val windowId = panes.first().windowId
            val paneId = panes.first { it.windowId == windowId }.paneId

            // The open path runs automatically once the pane is discovered:
            // recorded-kind read → recorded-source resolution → window read →
            // markAgentTailLive. Wait for the conversation row to go live with
            // the seeded transcript.
            waitForCondition(
                label = "rtt$rttMs conversation row live with seeded transcript",
                timeoutMs = 60_000,
                describe = {
                    "conversations=${vm.agentConversations.value.keys} " +
                        "events=${vm.agentConversations.value[paneId]?.events?.size}"
                },
                predicate = {
                    val row = vm.agentConversations.value[paneId]
                    row != null &&
                        row.events.filterIsInstance<ConversationEvent.Message>().size >= 2
                },
            )
            assertEquals(
                "recorded session must resolve to Claude on the seeded window",
                AgentKind.ClaudeCode,
                vm.agentForWindow(windowId),
            )
            val seededEvents = vm.agentConversations.value[paneId]!!.events
            assertEquals(
                "cold open under RTT must load the 2 seeded turns",
                listOf("hello agent", "hi back"),
                seededEvents.filterIsInstance<ConversationEvent.Message>().map { it.text },
            )

            // Read the authoritative spans for this RTT run.
            val fullOpen = waitForSpan(CONVERSATION_OPEN_FULL_LATENCY_OPERATION, "rtt$rttMs full open")
            val windowOpen = waitForSpan(CONVERSATION_OPEN_LATENCY_OPERATION, "rtt$rttMs window open")
            val detectionChainMs = fullOpen.durationMs - windowOpen.durationMs
            record("rtt${rttMs}_conversation_open_full_ms", fullOpen.durationMs)
            record("rtt${rttMs}_conversation_open_window_read_ms", windowOpen.durationMs)
            record("rtt${rttMs}_conversation_open_detection_chain_ms", detectionChainMs)
            measurements += "  full=${fullOpen.toArtifactLine()}"
            measurements += "  window=${windowOpen.toArtifactLine()}"

            // Issue #828: the recorded-Claude cold open must fit the <0.3s gate at
            // the realistic-good 80 ms RTT. This is the HARD regression assertion —
            // NOT behind any assumeTrue (the opt-in proxy gate at the top of the
            // @Test is the infra precondition; once the test runs, this bound is
            // load-bearing, per #657/F3). At 80 ms the recorded-Claude open path
            // after #828 is candidate-enum + window-read (the standalone
            // readRecordedAgentKind exec is cached away and the host-wide ps scan
            // is skipped for Claude/OpenCode), so the full open must clear 300 ms.
            // 150 ms RTT is reported for the record but not gated: with the two
            // mandatory serial round-trips it physically cannot fit 300 ms, and
            // 80 ms is the phone-to-remote target the issue asks us to certify.
            if (rttMs == GATE_RTT_MS) {
                assertTrue(
                    "recorded Claude cold conversation_open_full at ${rttMs}ms RTT must be " +
                        "< ${COLD_OPEN_GATE_MS}ms (the <0.3s gate); was ${fullOpen.durationMs}ms " +
                        "(window_read=${windowOpen.durationMs}ms detection_chain=${detectionChainMs}ms). " +
                        "A regression here means the open path grew an extra serial SSH round-trip — " +
                        "check the recorded-kind cache (no readRecordedAgentKind re-exec), that the " +
                        "Claude/OpenCode recorded path skips the host-wide ps scan, and that the " +
                        "recorded-Claude first window is prefetched in the resolve exec (not a " +
                        "separate window-read round-trip).",
                    fullOpen.durationMs < COLD_OPEN_GATE_MS,
                )
                // After #828 the recorded-Claude cold open is ONE SSH round-trip:
                // kind + source + first window folded into the resolve exec. So the
                // window-read leg (conversation_open) collapses to ~0 (the window
                // was prefetched, no separate read) — the proof that the fold took
                // effect. A non-trivial window-read here means the prefetch was
                // dropped and the path fell back to a second round-trip (the #817
                // baseline shape). Allow a small margin for the StateFlow push /
                // parse the span still spans.
                assertTrue(
                    "recorded Claude window-read leg at ${rttMs}ms RTT must collapse to ~0 — the " +
                        "first window is prefetched in the resolve exec, so no separate window-read " +
                        "round-trip should run; was ${windowOpen.durationMs}ms. A larger value means " +
                        "the prefetch was dropped and a second SSH round-trip came back.",
                    windowOpen.durationMs < PREFETCHED_WINDOW_READ_GATE_MS,
                )
            }

            // ---- Warm switch: Terminal -> Conversation on an already-loaded row.
            // The open-time default is pinned to Terminal for this latency test
            // (see setDefaultAgentSessionViewForTest above), so the transcript is
            // already loaded and the switch is the pure-state-read warm case. Tap
            // Conversation and snapshot the conversation_switch span.
            assertEquals(
                "the row opened on the pinned Terminal default (warm-switch scenario)",
                SessionTab.Terminal,
                vm.agentConversations.value[paneId]!!.selectedTab,
            )
            vm.selectSessionTab(paneId, SessionTab.Conversation)
            waitForCondition(
                label = "rtt$rttMs switched to Conversation",
                timeoutMs = 5_000,
                describe = { "tab=${vm.agentConversations.value[paneId]?.selectedTab}" },
                predicate = {
                    vm.agentConversations.value[paneId]?.selectedTab == SessionTab.Conversation
                },
            )
            val switchSpan = waitForSpan(CONVERSATION_SWITCH_LATENCY_OPERATION, "rtt$rttMs switch")
            record("rtt${rttMs}_conversation_switch_ms", switchSpan.durationMs)
            measurements += "  switch=${switchSpan.toArtifactLine()}"
        } finally {
            vm.clearForTest()
        }

        // Clear the latency for the next RTT pass so seeding stays fast.
        proxy.clearToxics()
    }

    private suspend fun waitForSpan(
        operation: String,
        label: String,
        timeoutMs: Long = 60_000,
    ): TmuxSessionLatencyTelemetry.Event {
        waitForCondition(
            label = "$label span ($operation)",
            timeoutMs = timeoutMs,
            describe = { "spans=${TmuxSessionLatencyTelemetry.snapshot().map { it.name }}" },
            predicate = { TmuxSessionLatencyTelemetry.snapshot().any { it.name == operation } },
        )
        return TmuxSessionLatencyTelemetry.snapshot().last { it.name == operation }
    }

    private suspend inline fun <reified T : TmuxSessionViewModel.ConnectionStatus> waitForStatus(
        vm: TmuxSessionViewModel,
        label: String,
        timeoutMs: Long = 60_000,
    ): T {
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    val status = vm.connectionStatus.value
                    if (status is T) return@withTimeout status
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "[$label] timed out after ${timeoutMs}ms waiting for ${T::class.simpleName}; " +
                    "last status was ${vm.connectionStatus.value::class.simpleName}",
                e,
            )
        }
    }

    private suspend fun waitForPanes(
        vm: TmuxSessionViewModel,
        label: String,
        timeoutMs: Long = 40_000,
    ): List<TmuxPaneState> {
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    val panes = vm.panes.value
                    if (panes.isNotEmpty()) return@withTimeout panes
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("[$label] timed out after ${timeoutMs}ms waiting for panes", e)
        }
    }

    private suspend fun waitForCondition(
        label: String,
        timeoutMs: Long,
        describe: () -> String,
        predicate: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (!predicate()) {
                    delay(50)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("[$label] timed out after ${timeoutMs}ms: ${describe()}", e)
        }
    }

    private suspend fun execRemote(key: String, command: String) {
        val result = withTimeout(30_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session -> session.use { it.exec(command) } }
        }
        val exec = result.getOrNull()
        assertTrue(
            "remote command failed: ${result.exceptionOrNull()} exit=${exec?.exitCode} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private fun toxiproxy(): ToxiproxyControl =
        ToxiproxyControl(baseUrl = "http://$DEFAULT_HOST:$TOXIPROXY_API_PORT")

    private fun writeKeyFile(key: String): String {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        return File(targetContext.filesDir, "issue817_rtt_test_key.pem").apply {
            writeText(key)
            setReadable(false, false)
            setWritable(false, false)
            setReadable(true, true)
            setWritable(true, true)
        }.absolutePath
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun record(name: String, value: Long) {
        val line = "$name=$value"
        measurements += line
        println("ISSUE817_TIMING $line")
    }

    private fun writeSummary() {
        if (measurements.isEmpty()) return
        val text = buildString {
            appendLine("scenario=conversation-open+switch-under-realistic-rtt (#817 Rank-1 measurement)")
            appendLine("recorded_session=@ps_agent_kind=claude (the #825 path #818 will default to)")
            appendLine("proxy=network-fault-proxy toxiproxy symmetric latency, upstream agents:22")
            appendLine("measurements:")
            measurements.forEach { appendLine(it) }
            appendLine(
                "note: conversation_open_full = detection_chain + window_read; " +
                    "warm switch is a pure StateFlow read (no SSH).",
            )
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/conversation-open-rtt")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact dir ${dir.absolutePath}" }
        val file = File(dir, "issue817-conversation-open-rtt-timing.txt")
        file.writeText(text)
        println("ISSUE817_TEXT ${file.absolutePath}")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val NETWORK_FAULT_SSH_PORT: Int = 2228
        const val TOXIPROXY_API_PORT: Int = 8474

        // Issue #828: the RTT the <0.3s gate is asserted at — the realistic-good
        // 80 ms phone-to-remote link. 150 ms is measured + reported but not gated
        // (two mandatory serial round-trips cannot fit 300 ms there).
        const val GATE_RTT_MS: Int = 80
        // The release gate: recorded Claude cold Conversation open must be < 0.3s.
        const val COLD_OPEN_GATE_MS: Long = 300L
        // After #828 the first window is prefetched in the resolve exec, so the
        // window-read leg must collapse to ~0 (no separate round-trip). 60 ms is
        // generous headroom for the StateFlow push / parse the span still covers;
        // any real second SSH round-trip at 80 ms RTT would be ~150 ms+.
        const val PREFETCHED_WINDOW_READ_GATE_MS: Long = 60L
    }
}
