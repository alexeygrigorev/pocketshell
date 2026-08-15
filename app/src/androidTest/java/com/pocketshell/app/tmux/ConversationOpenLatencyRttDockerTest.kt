package com.pocketshell.app.tmux

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
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
 * ### Where it runs (issue #2111)
 *
 * It used to run on **no lane at all**. Its `assumeFalse(isRunningOnCi())`
 * skipped it per-push, and nightly's `NETWORK_FAULT_CLASSES` list was
 * `com.pocketshell.app.proof.*` only — so nightly phase 1 selected it WITHOUT
 * the `pocketshellNetworkFaultProofs` opt-in and the `assumeTrue` skipped it
 * there too. The #828 gates below were therefore unprotected: reintroducing the
 * serial open-path detection execs left every lane green.
 *
 * It is now enrolled in nightly's fault run — specifically the NON-GATING
 * **phase 2b** (`scripts/nightly-extensive-suite.sh` `EXPECTED_FAIL_CLASSES`),
 * which runs WITH `pocketshellNetworkFaultProofs=true` against the
 * `network-fault-proxy:2228` + toxiproxy API:8474 fixtures the nightly workflow
 * already starts. Being in that list also excludes it from phase 1, so it is
 * never selected without the opt-in on CI.
 *
 * The opt-in is therefore a HARD assertion, not an `assumeTrue` — mirroring the
 * sibling toxiproxy fixture user `OutboundAttachmentOffsetResumeJourneyE2eTest`
 * (#1733/#1866). A lost opt-in must be a loud red, never a silent skip that
 * reads as coverage; that silent skip is exactly the defect #2111 removes.
 *
 * ### Why TWO `@Test` methods, and not one (issue #2111 round 2)
 *
 * This class asserts two DIFFERENT properties with two DIFFERENT current fates,
 * and round 1 shipped them as two sequential `assertTrue` calls inside one
 * `@Test`. That was a real defect, not a style nit:
 *
 *  - [recordedClaudeFirstWindowIsPrefetchedUnderRealisticRtt] is the STRUCTURAL
 *    #828 guard — the window-read leg must collapse to ~0 because the first
 *    window is folded into the resolve exec. It **passes today**.
 *  - [recordedClaudeColdOpenMeetsPhoneBudgetAtGoodRtt] is the <0.3 s PHONE-class
 *    budget. It **fails on an emulator at any RTT** (numbers below), tracked as
 *    a follow-up.
 *
 * With both in one method the budget assertion threw first and the structural
 * one was **never evaluated**, so the #828 mutation ("drop the prefetch fold")
 * produced red-before and red-after — indistinguishable, i.e. zero protection.
 * Splitting them means a #828 regression changes the run's FAILURE SET (one
 * failing test becomes two) on a lane whose overall exit code is informational.
 * Neither assertion was weakened to achieve this (a #2111 non-goal); both stay
 * hard, they just no longer mask each other.
 *
 * ### Running it locally — pass `--no-pool` (issue #2111 round 2)
 *
 * This class seeds its tmux session over the DIRECT fixture port ([DEFAULT_PORT])
 * but drives the app through the proxy on [NETWORK_FAULT_SSH_PORT], whose
 * upstream is the SHARED `agents` container. `scripts/connected-test.sh`
 * auto-enables `--pool` for a `*DockerTest*` class when more than one emulator is
 * online, and pool mode relocates `DEFAULT_PORT` to a per-lane agents container —
 * so the session would be seeded in one container while the app reads a different
 * one, and the test fails for a reason that has nothing to do with the code. The
 * fault proxy itself is a singleton and is not pool-isolated either. So always:
 *
 * ```
 * scripts/connected-test.sh --no-pool --suffix i<issue> :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.tmux.ConversationOpenLatencyRttDockerTest
 * ```
 *
 * Nightly is unaffected: `scripts/nightly-extensive-suite.sh` invokes Gradle
 * directly and never takes the pool path.
 *
 * If every SSH attempt fails with `Server closed connection during identification
 * exchange` while `docker ps` still reports the fixture healthy, it is not this
 * test: OpenSSH 9.8+ `PerSourcePenalties` has penalised the docker gateway
 * (`srclimit_penalise ... penalty: failed authentication` in `docker logs`). All
 * emulator/host traffic NATs through that one source address, while the
 * container's own healthcheck comes over `::1` and keeps passing — so the fixture
 * looks fine and every lane fails. See the issue #2111 thread.
 *
 * ### What its first-ever execution measured (2026-08-13)
 *
 *  - [PREFETCHED_WINDOW_READ_GATE_MS] — **satisfied**: the window-read leg is
 *    0 ms at both 150 ms and 80 ms RTT, i.e. #828's fold of the first window
 *    into the resolve exec is intact. Removing that fold pushes it to 184 ms
 *    (80 ms RTT) / 784 ms (150 ms RTT) and reddens this assertion. It is
 *    asserted at BOTH measured RTTs: a dropped fold costs one extra serial
 *    round-trip, so the higher RTT is the louder detector.
 *
 *    Re-confirmed by mutation on the SHIPPED tree (2026-08-14, round 2), with
 *    [COLD_OPEN_GATE_MS] left untouched at 300 ms. Dropping the fold — deleting
 *    the `prefetchedWindow ?:` in `TmuxSessionViewModel.startAgentConversation`
 *    so the separate window read always runs — moves this method PASS -> FAIL
 *    (window-read leg 0 ms -> 818 ms at 150 ms RTT) while the budget method
 *    fails either way. The run's FAILURE SET therefore changes, 1 -> 2, which is
 *    the signal this split exists to produce: before the split the budget
 *    assertion threw first and this one never ran, so the same mutation gave red
 *    before and red after — indistinguishable.
 *  - [COLD_OPEN_GATE_MS] — **not met on an emulator at any RTT**: measured
 *    `conversation_open_full` was 840 / 429 / 753 / 842 ms at 80 ms RTT across
 *    four runs, and a control pass at 10 ms RTT still measured 406 ms; a fifth
 *    run (round 2) measured 357 ms. The device-side fixed cost on this AVD is
 *    ~400 ms, so a <0.3 s PHONE-class budget cannot be met there no matter how
 *    fast the link. Tracked as a follow-up; #2111 only makes the test run.
 *
 * That is why the enrolment is the NON-GATING phase 2b and not the
 * release-GATING phase 2: gating on a budget the runner's hardware cannot meet
 * would turn the nightly fault verdict permanently red for an environmental
 * reason. Promote this class into `NETWORK_FAULT_CLASSES` once the <0.3 s
 * question is resolved (a device-class-aware budget, or cutting the ~400 ms of
 * device-side open cost).
 */
@RunWith(AndroidJUnit4::class)
class ConversationOpenLatencyRttDockerTest {

    @get:Rule
    val preGrantPermissions = PreGrantPermissionsRule()

    /** Issue #2111: keeps the two methods' timing artifacts from overwriting each other. */
    @get:Rule
    val testName = TestName()

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

    /**
     * Issue #828 STRUCTURAL guard, and the load-bearing test of this class.
     *
     * The recorded-Claude cold open must be ONE SSH round-trip: kind + source +
     * the first transcript window all folded into the resolve exec. So the
     * window-read leg (`conversation_open`) must collapse to ~0 at every RTT.
     * Reintroducing the separate window-read round-trip (the #817 baseline
     * shape) reddens this and ONLY this.
     *
     * Issue #2111 round 2: this is deliberately its own `@Test`, separate from
     * the <0.3 s budget below, so a budget failure cannot abort the method
     * before this assertion runs. See the class KDoc.
     */
    @Test
    fun recordedClaudeFirstWindowIsPrefetchedUnderRealisticRtt(): Unit { runBlocking {
        val (key, keyPath) = prepareFixtures()
        // Two RTTs: ~150 ms (typical mobile/wifi) and ~80 ms (a good link).
        // One-way latencies of 75 / 40 ms; toxiproxy delays each direction.
        // The fold is asserted at BOTH: a dropped fold costs one extra serial
        // round-trip, so the higher RTT is the louder detector (784 ms vs 184 ms).
        measureAtRtt(key, keyPath, oneWayMs = 75, gate = LatencyGate.PrefetchedWindowRead)
        measureAtRtt(key, keyPath, oneWayMs = 40, gate = LatencyGate.PrefetchedWindowRead)
    } }

    /**
     * Issue #828 BUDGET gate: the recorded-Claude cold Conversation open must fit
     * the <0.3 s target at the realistic-good 80 ms RTT.
     *
     * Issue #2111 round 2: known-red on an emulator (~400 ms of device-side fixed
     * cost — see the class KDoc for the measurements), which is why this class
     * sits in nightly's NON-GATING phase 2b. The assertion is NOT weakened and
     * NOT skipped; it is isolated in its own `@Test` so its environmental failure
     * cannot mask the structural guard above.
     */
    @Test
    fun recordedClaudeColdOpenMeetsPhoneBudgetAtGoodRtt(): Unit { runBlocking {
        val (key, keyPath) = prepareFixtures()
        measureAtRtt(key, keyPath, oneWayMs = GATE_RTT_MS / 2, gate = LatencyGate.ColdOpenBudget)
    } }

    /** Which hard gate [measureAtRtt] applies (issue #2111 round 2). */
    private enum class LatencyGate {
        /** #828 structural: the first window is prefetched, so no second round-trip. */
        PrefetchedWindowRead,

        /** #828 budget: cold `conversation_open_full` < [COLD_OPEN_GATE_MS]. */
        ColdOpenBudget,
    }

    /** Hard-asserts the toxiproxy opt-in and readies both the direct and proxied fixtures. */
    private suspend fun prepareFixtures(): Pair<String, String> {
        // Issue #2111: HARD-assert the toxiproxy opt-in — never `assumeTrue`.
        // This class is enrolled in nightly fault phase 2b, which passes the flag
        // and is therefore excluded from phase 1; anything else selecting it
        // without the fixture must be a loud red, not a silent skip that reads
        // as coverage. Mirrors OutboundAttachmentOffsetResumeJourneyE2eTest.
        assertTrue(
            "issue #2111: this proof requires the explicitly opted-in Toxiproxy fixture. " +
                "Run it via nightly fault phase 2b, or locally with " +
                "-Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true " +
                "after `docker compose -f tests/docker/docker-compose.yml up -d --build agents network-fault-proxy`",
            InstrumentationRegistry.getArguments()
                .getString("pocketshellNetworkFaultProofs")
                ?.toBooleanStrictOrNull() == true,
        )

        val key = readFixtureKey()
        // Both the direct fixture (for seeding) and the proxy must be reachable.
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        toxiproxy().reset()
        proxyTouched = true
        waitForSshFixtureReady(SshKey.Pem(key), port = NETWORK_FAULT_SSH_PORT)

        return key to writeKeyFile(key)
    }

    private suspend fun measureAtRtt(
        key: String,
        keyPath: String,
        oneWayMs: Int,
        gate: LatencyGate,
    ) {
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

            // Issue #2111 round 2: exactly ONE hard gate per measurement pass, and
            // which one is decided by the calling @Test. Both are HARD assertions —
            // NOT behind any assumeTrue (the opt-in proxy gate in prepareFixtures is
            // the infra precondition; once the test runs, these bounds are
            // load-bearing, per #657/F3). They live in separate @Test methods so
            // the known-red budget cannot abort the method before the structural
            // guard is evaluated; see the class KDoc. The gates run LAST so every
            // measurement above reaches the timing artifact even when one fails.
            when (gate) {
                // After #828 the recorded-Claude cold open is ONE SSH round-trip:
                // kind + source + first window folded into the resolve exec. So the
                // window-read leg (conversation_open) collapses to ~0 (the window
                // was prefetched, no separate read) — the proof that the fold took
                // effect. A non-trivial window-read here means the prefetch was
                // dropped and the path fell back to a second round-trip (the #817
                // baseline shape). Allow a small margin for the StateFlow push /
                // parse the span still spans.
                LatencyGate.PrefetchedWindowRead -> assertTrue(
                    "recorded Claude window-read leg at ${rttMs}ms RTT must collapse to ~0 — the " +
                        "first window is prefetched in the resolve exec, so no separate window-read " +
                        "round-trip should run; was ${windowOpen.durationMs}ms " +
                        "(full_open=${fullOpen.durationMs}ms detection_chain=${detectionChainMs}ms). " +
                        "A larger value means the prefetch was dropped and a second SSH round-trip " +
                        "came back.",
                    windowOpen.durationMs < PREFETCHED_WINDOW_READ_GATE_MS,
                )
                // The <0.3s gate is asserted at the realistic-good 80 ms RTT only:
                // 150 ms is measured for the record but cannot fit 300 ms with the
                // mandatory serial round-trips, and 80 ms is the phone-to-remote
                // target the issue asks us to certify. `check` rather than an `if`
                // so a future caller at the wrong RTT is a loud error, not a
                // silently-skipped gate.
                LatencyGate.ColdOpenBudget -> {
                    check(rttMs == GATE_RTT_MS) {
                        "the <0.3s budget is only asserted at the ${GATE_RTT_MS}ms gate RTT; " +
                            "this pass ran at ${rttMs}ms"
                    }
                    assertTrue(
                        "recorded Claude cold conversation_open_full at ${rttMs}ms RTT must be " +
                            "< ${COLD_OPEN_GATE_MS}ms (the <0.3s gate); was ${fullOpen.durationMs}ms " +
                            "(window_read=${windowOpen.durationMs}ms " +
                            "detection_chain=${detectionChainMs}ms). " +
                            "A regression here means the open path grew an extra serial SSH " +
                            "round-trip — check the recorded-kind cache (no readRecordedAgentKind " +
                            "re-exec), that the Claude/OpenCode recorded path skips the host-wide " +
                            "ps scan, and that the recorded-Claude first window is prefetched in " +
                            "the resolve exec (not a separate window-read round-trip).",
                        fullOpen.durationMs < COLD_OPEN_GATE_MS,
                    )
                }
            }
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
        val method = testName.methodName ?: "unknown"
        val text = buildString {
            appendLine("scenario=conversation-open+switch-under-realistic-rtt (#817 Rank-1 measurement)")
            appendLine("test_method=$method")
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
        // Issue #2111 round 2: one file per @Test method — the two methods run in
        // the same instrumentation process, so a fixed name would let whichever
        // ran last silently overwrite the other's timings.
        val file = File(dir, "issue817-conversation-open-rtt-timing-$method.txt")
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
