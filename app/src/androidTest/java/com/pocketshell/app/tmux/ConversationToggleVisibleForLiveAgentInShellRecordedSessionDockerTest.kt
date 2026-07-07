package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
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
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.SeedBeforeLaunchRule
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #975 (#962 recurrence) — a recorded `@ps_agent_kind=shell` session with a
 * LIVE agent (a `claude` transcript present in the cwd) MUST regain its
 * Terminal/Conversation toggle on the REAL path, EVEN when the host agent-kind
 * daemon's cgroup/`/proc` classify returns `unknown` (the masked node-wrapped /
 * quiet `claude`). And the no-agent control must STILL show NO toggle (#894
 * no-flap adjacency).
 *
 * The maintainer's dogfood report (v0.4.18): in an active `claude` session the top
 * chrome shows only a single "Terminal" pill — no "Conversation" toggle. Root
 * cause (research, cited): the session was recorded `@ps_agent_kind=shell` (a
 * plain shell the user/kind-picker classified as shell) with `claude` started
 * INSIDE it. The recorded-shell verdict (#894) publishes the pane confirmed-shell,
 * which collapses `presumedAgent`; and because the daemon's classify returns
 * `unknown` for the masked `claude`, the #962 fix never bound a live source — so
 * BOTH inputs to
 * `tmuxSessionTabState.showsConversationTab = hasLiveDetection || presumedAgent`
 * stayed false and the toggle was gone for the life of the session.
 *
 * ### Real-path B1 reproduction (G10/D33 — the gap #962 left open)
 *
 * #962's connected test claimed the Docker `agents` fixture "cannot bind a live
 * detection in-fixture" because the non-systemd container's daemon returns
 * `unknown`/`scope=null`. That is EXACTLY the B1 classify-miss the maintainer hit
 * on-device — and the #975 fix turns it into the load-bearing real-path test: the
 * fixture's `agents` container DOES ship a live Claude transcript at
 * `~/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl`, and the
 * daemon DOES return `unknown` for the pane. So a recorded-shell session whose cwd
 * has a fresh Claude transcript reproduces the masked-live-agent state on the REAL
 * path: on base (no #975 fix) the foreign resolver returns null on the `unknown`
 * classify and NO toggle appears (RED); with the fix the transcript-evidence
 * fallback binds the agent → markAgentTailLive clears the verdict → the toggle
 * returns (GREEN). [conversationToggleReturnsForMaskedLiveClaudeInRecordedShellSession]
 * is that real-path proof; the deterministic JVM red→green
 * (`TmuxSessionViewModelTest.b1MaskedLiveClaudeInRecordedShellBindsDetectionViaTranscriptDespiteUnknownClassify`)
 * is the fast sibling.
 *
 * [plainShellRecordedSessionShowsNoConversationToggle] is the **no-agent control /
 * #894 no-flap invariant**: a session recorded `@ps_agent_kind=shell` with NO
 * agent transcript must show NO toggle — the active-rework adjacency the fix must
 * not resurrect.
 *
 * ### Harness — #788 interop-placement cure (this round)
 *
 * The previous round of this class used `createEmptyComposeRule()` +
 * `ActivityScenario.launch(MainActivity)` inside the `@Test` body — the exact #788
 * interop-placement stall: under the swiftshader AVD the Termux `TerminalView`
 * `AndroidView` interop child is never placed, MainActivity's compose tree never
 * registers ("No compose hierarchies found in the app"), and the hand-rolled
 * `ActivityScenario` lands in a bad lifecycle so `tearDown` NPEs — so the test
 * never reached its toggle assertion. This round migrates to
 * `createAndroidComposeRule<MainActivity>()` (the rule owns the activity
 * lifecycle, the test clock drives the SAME foreground activity the interop child
 * is placed into) wired through a [RuleChain] in the #788 cure order:
 * grant → seed-remote+DB → launch MainActivity. Each method's per-test remote
 * tmux session + DB host row is seeded in the rule's `before()` phase (branched on
 * the JUnit method name), so MainActivity reads a populated DB on cold start.
 * See `PreExistingMultiWindowSeedE2eTest` / `Issue895SwitchWhileBlackBandJourneyE2eTest`.
 */
@RunWith(AndroidJUnit4::class)
class ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(SeedBeforeLaunchRule { description -> seedForMethod(description.methodName) })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private var seededSessionName: String? = null
    private val cleanupCommands = mutableListOf<String>()
    private val stamps = mutableListOf<String>()

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        clearLastSessionPrefs()
        seededKey?.let { key ->
            if (cleanupCommands.isNotEmpty()) {
                runBlocking {
                    runCatching {
                        withTimeout(20_000) { execRemote(key, cleanupCommands.joinToString("\n")) }
                    }
                }
            }
        }
        if (stamps.isNotEmpty()) {
            writeText("conversation-toggle-962-stamps.txt", stamps.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Adjacency / #894 no-flap invariant: a session recorded `@ps_agent_kind=shell`
     * with NO agent process must show NO toggle. This is the regression the #962
     * override must NOT resurrect (a recorded shell flashing the Conversation tab).
     * Runs unchanged in-fixture (the absence assertion needs no daemon classify).
     */
    @Test
    fun plainShellRecordedSessionShowsNoConversationToggle() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue962-plain-ready", VISIBLE_TIMEOUT_MS) {
            "issue962-plain-ready" in it
        }
        stamp("plain_shell_attached session=$sessionName")

        // Settle: give live detection a generous window to (not) fire, so the
        // "no toggle" assertion is meaningful and not racing the detector.
        SystemClock.sleep(SHELL_NO_TOGGLE_SETTLE_MS)

        // CONTROL ASSERTION (#894): a genuine recorded shell with NO agent shows
        // NO toggle. No live detection ever binds → confirmedShell is never
        // cleared → presumedAgent false → no toggle. This is the no-flap invariant
        // the #962 override must not resurrect.
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertDoesNotExist()
        captureFullFrame("issue962-plain-shell-no-toggle")
        stamp("plain_shell_no_toggle_ok")
        Unit
    } }

    /**
     * Issue #975 (B1, classify-miss) — the REAL-PATH reproduction of the
     * maintainer's bug. A session recorded `@ps_agent_kind=shell` whose cwd holds a
     * fresh live Claude transcript (copied from the fixture's seed at
     * `~/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl`), while
     * the host agent-kind daemon's classify returns `unknown` for the pane (the
     * non-systemd container has no readable cgroup scope — exactly the masked
     * node-wrapped `claude` on-device). On base (no #975 fix) the foreign resolver
     * returns null on the `unknown` classify so NO Conversation toggle appears
     * (the user is stranded on the raw black agent Terminal). With the fix the
     * transcript-evidence fallback binds the live agent → markAgentTailLive clears
     * the recorded-shell verdict → the [TMUX_TABS_TAG] toggle returns with a
     * "Conversation" segment.
     */
    @Test
    fun conversationToggleReturnsForMaskedLiveClaudeInRecordedShellSession() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue975-masked-ready", VISIBLE_TIMEOUT_MS) {
            "issue975-masked-ready" in it
        }
        stamp("masked_claude_attached session=$sessionName")

        // STEP 1 — observe the recorded-shell verdict APPLYING (the collapse
        // point), recorded as evidence the optimistic window genuinely ends. The
        // fresh pane shows the toggle optimistically (#878 black-screen cure)
        // BEFORE the recorded `@ps_agent_kind=shell` verdict is read+applied; a
        // test that merely waited for the toggle to appear would catch that
        // optimistic window and pass even on base. The hard RED/GREEN gate is
        // STEP 2 (a bound DETECTION, which the optimistic toggle never sets), so
        // this observation is recorded but not fatal — with the fix B1 clears the
        // verdict again so quickly that the confirmed-shell set may already be
        // empty by the time the poll ticks. STEP 2 is the immune signal.
        val vm = currentViewModel()
        val verdictObserved = waitForConfirmedShellVerdict(vm)
        stamp("masked_claude_confirmed_shell_verdict_observed=$verdictObserved")

        // STEP 2 — LOAD-BEARING B1 SIGNAL: a LIVE transcript detection must BIND
        // for the pane. This is the exact signal the JVM red→green asserts
        // (`assertNotNull(detection)`): in this fixture the daemon classify returns
        // `unknown` ({"results":[]}), so the ONLY way `detection != null` can ever
        // be true is the #975 B1 transcript-evidence fallback. On base (no fix) the
        // foreign resolver returns null and detection stays null for the life of
        // the session → RED here, BEFORE the UI assertions. With the fix the
        // fallback binds the live Claude transcript → detection non-null → GREEN.
        // Asserting the bound DETECTION (not just the toggle UI) is what makes this
        // immune to the optimistic-presumedAgent confound: the optimistic toggle
        // never sets a non-null detection.
        val detectionBound = waitForLiveDetectionBound(vm)
        assertTrue(
            "#975 B1 (real path): a LIVE transcript detection must bind for the " +
                "masked-claude recorded-shell pane within ${MASKED_TOGGLE_TIMEOUT_MS}ms " +
                "(the daemon classify is `unknown`, so only the transcript-evidence " +
                "fallback can bind it). On base (no fix) detection stays null. " +
                "agentConversations=${vm.agentConversations.value.mapValues { entry -> entry.value.detection?.agent }}",
            detectionBound,
        )
        stamp("masked_claude_live_detection_bound")

        // STEP 3 — DURABLE UI: with the live detection bound, the
        // Terminal/Conversation toggle must (re)appear and stay. On base the
        // confirmed-shell verdict permanently suppresses presumedAgent and the
        // toggle is gone; with the fix markAgentTailLive →
        // clearConfirmedShellOnLiveAgentDetection clears the verdict → the toggle
        // returns DURABLY.
        compose.waitUntil(timeoutMillis = MASKED_TOGGLE_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_TABS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertExists()
        // The toggle carries a "Conversation" segment (the agent surface the user
        // was stranded away from), not just a lone "Terminal" pill.
        compose.waitUntil(timeoutMillis = MASKED_TOGGLE_TIMEOUT_MS) {
            compose.onAllNodesWithText("Conversation", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Conversation", useUnmergedTree = true).assertExists()
        captureFullFrame("issue975-masked-claude-toggle-returns")
        stamp("masked_claude_toggle_returned_ok")
        Unit
    } }

    /**
     * Issue #1158 (recurrence of #962/#1057) — a session RECORDED as a non-shell
     * agent kind (`@ps_agent_kind=codex`) whose conversation SOURCE can't bind
     * (no cwd-enumerable transcript; Codex needs the `/proc/<pid>/fd` owned-rollout
     * match the non-systemd fixture can't supply) MUST still show a present +
     * tappable Terminal/Conversation toggle, and tapping it must route to the
     * Conversation surface (the loading/placeholder state), NEVER collapse the
     * whole tab and strand the user on the Terminal.
     *
     * This is the SECOND kind in the class (Codex; the masked-Claude sibling above
     * is the first) and the "source-binding-fails-but-tab-still-present" case. Tab
     * PRESENCE now follows the RECORDED agent kind
     * (`tmuxSessionRecordedAgentKind(currentSessionRecordedKind)` →
     * `tmuxSessionTabState.showsConversationTab`), independent of the fragile live
     * detection / transcript-source binding — the durable #1158 fix. The
     * deterministic per-kind red→green (Claude / Codex / glm-Z.AI) lives in the
     * fast JVM sibling `TmuxSessionScreenTest.tmuxSessionTabStateShowsConversationForRecorded*`;
     * this is the REAL-path acceptance that the toggle is present, tappable, and
     * degrades to the Conversation placeholder — not a dead Terminal — when source
     * binding fails on the maintainer's fleet.
     *
     * NOTE (loading state, not loaded content): actually LOADING the Codex/Z.AI
     * transcript into the surface is the explicit #1158 non-goal (the #820
     * content-loading class, a separate follow-up). Here the acceptance is the tab
     * being reachable and reaching the Placeholder/loading surface.
     */
    @Test
    fun conversationTogglePresentForRecordedCodexAgentWhenSourceBindingFails() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue1158-codex-ready", VISIBLE_TIMEOUT_MS) {
            "issue1158-codex-ready" in it
        }
        stamp("recorded_codex_attached session=$sessionName")

        // STEP 1 — LOAD-BEARING recorded-agent signal: the tree read back
        // `@ps_agent_kind=codex` for the active session. This is the exact input
        // the #1158 fix keys on (currentSessionRecordedKind → recordedAgentKind →
        // showsConversationTab). If this never resolves, the recorded-kind path
        // isn't exercised and the test would be vacuous — so hard-assert it.
        val vm = currentViewModel()
        val recordedCodex = waitForRecordedKind(vm, SessionAgentKind.Codex)
        assertTrue(
            "#1158: the active session must read back `@ps_agent_kind=codex` " +
                "(the recorded-agent signal that drives tab presence). " +
                "currentSessionRecordedKind=${vm.currentSessionRecordedKind.value}",
            recordedCodex,
        )
        stamp("recorded_codex_kind_resolved")

        // STEP 2 — NO live source binds: the fixture cannot bind a Codex source
        // (no `/proc` match, no cwd transcript). Detection stays null — proving the
        // toggle below is NOT coming from live detection but from the recorded
        // kind. (Best-effort observation; the load-bearing gate is STEP 3.)
        SystemClock.sleep(SHELL_NO_TOGGLE_SETTLE_MS)
        val detectionBound = vm.agentConversations.value.values.any { it.detection != null }
        stamp("recorded_codex_detection_bound=$detectionBound")

        // STEP 3 — DURABLE UI: the Terminal/Conversation toggle is present with a
        // "Conversation" segment, driven purely by the recorded Codex kind.
        compose.waitUntil(timeoutMillis = MASKED_TOGGLE_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_TABS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = MASKED_TOGGLE_TIMEOUT_MS) {
            compose.onAllNodesWithText("Conversation", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Conversation", useUnmergedTree = true).assertExists()
        captureFullFrame("issue1158-recorded-codex-toggle-present")
        stamp("recorded_codex_toggle_present_ok")

        // STEP 4 — POINT 2 (never collapse the tab on a source-binding hiccup):
        // tapping Conversation routes to the Conversation surface (the loading /
        // placeholder state), NOT stranding the user on the Terminal. Assert the
        // production placeholder [TMUX_CONVERSATION_DETECTING_TAG] appears.
        clickRobustly {
            compose.onNodeWithText("Conversation", useUnmergedTree = true).performClick()
        }
        compose.waitUntil(timeoutMillis = MASKED_TOGGLE_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_DETECTING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_CONVERSATION_DETECTING_TAG, useUnmergedTree = true)
            .assertExists()
        captureFullFrame("issue1158-recorded-codex-conversation-placeholder")
        stamp("recorded_codex_conversation_placeholder_ok")
        Unit
    } }

    /**
     * Issue #1158 (REOPENED — maintainer's EXACT dogfood path, 2026-07-01) — an
     * agent launched DIRECTLY inside an existing shell tmux session (NOT via the
     * `pocketshell agent` wrapper). Result on-device: the Conversation tab is NEVER
     * shown — the user is stranded on the raw (often black) Terminal for the whole
     * session. Root cause: the session recorded `@ps_agent_kind=shell` (so
     * `recordedAgentKind == false`), the confirmed-shell verdict is never cleared,
     * and live detection never binds for the node-wrapped-Claude / Codex-`/proc` /
     * Z.AI fleet — so every prior tab signal is false and the toggle is gone for the
     * session's life.
     *
     * The durable fix is the detection-INDEPENDENT alt-buffer positive signal: a
     * full-screen agent TUI holds the ALTERNATE screen buffer, which the tmux
     * ViewModel latches STICKY and OR's into `showsConversationTab`.
     *
     * ### #780 synthetic-state model (no self-skip — hard-fail)
     *
     * The tmux `-CC` control path does not reliably mirror a REMOTE pane's alternate
     * screen buffer into the CLIENT emulator (the capture-pane seed replays the
     * screen TEXT onto the main buffer, and an idle full-screen agent emits no fresh
     * `%output` carrying the `?1049h` toggle), so a connected journey cannot enter
     * the on-device alt-buffer state on its own. Per D33/#780 the failing state is
     * injected SYNTHETICALLY on the REAL connected pane
     * ([TmuxSessionViewModel.forceActivePaneAltBufferForTest]) — the ONLY synthetic
     * part; the recorded-shell verdict, the detection-never-binds state, the real
     * production tab gate and the real chrome rendering are all live. It HARD-fails
     * (no `assumeTrue`) so CI carries real protection.
     *
     * RED on base: with no `|| altBufferAgent` term the toggle stays absent even
     * with the alt-buffer injected (the maintainer's symptom). GREEN with the fix:
     * the latch shows the toggle, and it STAYS after the buffer leaves alt-mode
     * (sticky).
     */
    @Test
    fun conversationToggleAppearsForAltBufferAgentDirectlyLaunchedInShellSession() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue962-plain-ready", VISIBLE_TIMEOUT_MS) {
            "issue962-plain-ready" in it
        }
        stamp("altbuf_shell_attached session=$sessionName")

        // Settle so the recorded `@ps_agent_kind=shell` verdict applies and live
        // detection has a generous window to (not) fire — the exact base state where
        // the toggle is absent for the maintainer.
        val vm = currentViewModel()
        val verdictObserved = waitForConfirmedShellVerdict(vm)
        stamp("altbuf_confirmed_shell_verdict_observed=$verdictObserved")
        SystemClock.sleep(SHELL_NO_TOGGLE_SETTLE_MS)
        val detectionBound = vm.agentConversations.value.values.any { it.detection != null }
        stamp("altbuf_detection_bound=$detectionBound")

        // Precondition (the maintainer's symptom, base state): NO Conversation toggle
        // — the recorded shell + no detection leaves only the "Terminal" pill.
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertDoesNotExist()
        captureFullFrame("issue1158-altbuf-before-no-toggle")
        stamp("altbuf_no_toggle_before_ok")

        // The agent goes full-screen: the pane's emulator switches to the ALTERNATE
        // screen buffer. Injected synthetically on the REAL connected pane (#780) —
        // the on-device signal a full-screen Claude/Codex TUI produces.
        compose.activityRule.scenario.onActivity { vm.forceActivePaneAltBufferForTest(true) }
        compose.waitUntil(timeoutMillis = MASKED_TOGGLE_TIMEOUT_MS) {
            vm.altBufferAgentPaneIds.value.isNotEmpty()
        }
        stamp("altbuf_latched=${vm.altBufferAgentPaneIds.value.isNotEmpty()}")

        // DURABLE UI (load-bearing): the Terminal/Conversation toggle now appears,
        // driven purely by the alt-buffer signal (detection is still null). RED on
        // base (no `|| altBufferAgent`), GREEN with the fix.
        compose.waitUntil(timeoutMillis = MASKED_TOGGLE_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_TABS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = MASKED_TOGGLE_TIMEOUT_MS) {
            compose.onAllNodesWithText("Conversation", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Conversation", useUnmergedTree = true).assertExists()
        captureFullFrame("issue1158-altbuf-toggle-appears")
        stamp("altbuf_toggle_appears_ok")

        // STICKY: the agent leaves the alt-buffer (exits its full-screen view /
        // detection stays null). The toggle MUST remain for the session's life.
        compose.activityRule.scenario.onActivity { vm.forceActivePaneAltBufferForTest(false) }
        SystemClock.sleep(SHELL_NO_TOGGLE_SETTLE_MS)
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Conversation", useUnmergedTree = true).assertExists()
        captureFullFrame("issue1158-altbuf-toggle-sticky")
        stamp("altbuf_toggle_sticky_ok")
        Unit
    } }

    // -------------------------------------------------------------- seed-before-launch

    /**
     * #788 cure: seed the per-test remote tmux session + DB host row in the
     * RuleChain's `before()` phase (BEFORE `createAndroidComposeRule` launches
     * MainActivity), branched on the JUnit method name so each test gets its own
     * fixture. The host row tag + session name are stashed for the `@Test` body.
     */
    private suspend fun seedForMethod(methodName: String) {
        val key = readFixtureKey()
        seededKey = key
        clearLastSessionPrefs()
        waitForSshFixtureReady(SshKey.Pem(key))
        val sessionName = when (methodName) {
            "conversationToggleReturnsForMaskedLiveClaudeInRecordedShellSession" ->
                seedMaskedLiveClaudeSession(key)
            "plainShellRecordedSessionShowsNoConversationToggle" ->
                seedPlainShellSession(key)
            "conversationTogglePresentForRecordedCodexAgentWhenSourceBindingFails" ->
                seedRecordedCodexNoTranscriptSession(key)
            "conversationToggleAppearsForAltBufferAgentDirectlyLaunchedInShellSession" ->
                seedPlainShellSession(key)
            else -> error("unexpected test method $methodName")
        }
        seededSessionName = sessionName
        seededHostRowTag = persistHost(key)
    }

    private suspend fun seedPlainShellSession(key: String): String {
        val suffix = unique()
        val sessionName = "issue962-plain-shell-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} -c /tmp " +
                        "\"printf 'issue962-plain-ready\\r\\n'; exec sh\"",
                )
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind shell")
                appendLine("sleep 1")
            },
        )
        return sessionName
    }

    private suspend fun seedMaskedLiveClaudeSession(key: String): String {
        val suffix = unique()
        val sessionName = "issue975-masked-claude-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        cleanupCommands +=
            "rm -f /home/testuser/.claude/projects/-home-testuser/" +
                "issue975-live-claude.jsonl 2>/dev/null || true"
        // Seed a FRESH live Claude transcript in the cwd-encoded Claude project
        // dir for a writable cwd (`/home/testuser` → encoded `-home-testuser`), and
        // attach the tmux session to that SAME cwd. (`/workspace` is not writable by
        // testuser in the fixture; the encoded transcript dir is what the detector
        // enumerates, and the cwd must be a real accessible dir for `pane.cwd` to
        // match.) Record it `@ps_agent_kind=shell` (the durable verdict that hides
        // the toggle). The daemon's classify returns `unknown` for this pane (no
        // cgroup scope in the non-systemd container) — the masked-live-agent state.
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine("mkdir -p /home/testuser/.claude/projects/-home-testuser")
                // A fresh Claude transcript (copied from the fixture's seed) inside
                // the detector's 2h `-mmin -120` freshness window.
                appendLine(
                    "cp /home/testuser/.claude/projects/-workspace-pocketshell/" +
                        "pocketshell-claude.jsonl " +
                        "/home/testuser/.claude/projects/-home-testuser/" +
                        "issue975-live-claude.jsonl",
                )
                appendLine(
                    "touch /home/testuser/.claude/projects/-home-testuser/" +
                        "issue975-live-claude.jsonl",
                )
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} " +
                        "-c /home/testuser " +
                        "\"printf 'issue975-masked-ready\\r\\n'; exec sh\"",
                )
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind shell")
                appendLine("sleep 1")
            },
        )
        return sessionName
    }

    /**
     * Issue #1158: a session RECORDED as an agent kind (`@ps_agent_kind=codex`)
     * with NO bindable conversation source — no cwd-enumerable transcript, and the
     * fixture cannot supply the Codex `/proc/<pid>/fd` owned-rollout match — so live
     * detection / transcript-source binding fails for the life of the session,
     * exactly the maintainer's Codex fleet. The recorded Codex kind is the durable
     * signal that keeps the Conversation toggle present regardless.
     */
    private suspend fun seedRecordedCodexNoTranscriptSession(key: String): String {
        val suffix = unique()
        val sessionName = "issue1158-codex-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} -c /tmp " +
                        "\"printf 'issue1158-codex-ready\\r\\n'; exec sh\"",
                )
                // Record it as a Codex agent (the durable `@ps_agent_kind` the
                // `pocketshell agent codex` wrapper writes). NO transcript is seeded,
                // so the conversation source cannot bind.
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind codex")
                appendLine("sleep 1")
            },
        )
        return sessionName
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Issue #1158: poll until the active session's recorded `@ps_agent_kind`
     * resolves to [expected] (read over the warm session by
     * `refreshCurrentSessionRecordedKind`, which the screen fires on connect). This
     * is the load-bearing recorded-agent signal the tab-presence fix keys on.
     */
    private fun waitForRecordedKind(vm: TmuxSessionViewModel, expected: SessionAgentKind): Boolean {
        val deadline = SystemClock.elapsedRealtime() + VERDICT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (vm.currentSessionRecordedKind.value == expected) return true
            SystemClock.sleep(100)
        }
        return vm.currentSessionRecordedKind.value == expected
    }

    private suspend fun persistHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue962-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            hostRowTag = HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
        return hostRowTag
    }

    private fun attachToSeededSession(hostRowTag: String, sessionName: String) {
        // Under createAndroidComposeRule, MainActivity cold compose can briefly
        // expose no registered hierarchy, so probe defensively until the
        // pre-launch seeded host row exists.
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        clickRobustly { compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick() }
        // Tap the session by its NAME text — it matches whether the folder list
        // renders the session as a flat row (cwd at the host root) or a nested
        // folder-detail row (a nested cwd), so this works for both fixtures. The
        // swiftshader AVD intermittently rejects a tap's touch injection while the
        // freshly-populated list is still settling, so retry until the session
        // screen mounts.
        compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
            compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        tapUntilSessionScreenShown(sessionName)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * Perform a Compose click, retrying once on a transient "Failed to inject
     * touch input" — the host/session list can shift a row by a pixel during a
     * still-settling recomposition between the framework reading the node bounds
     * and dispatching the gesture. Settle to idle and retry.
     */
    private fun clickRobustly(click: () -> Unit) {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        compose.waitForIdle()
        try {
            click()
        } catch (e: AssertionError) {
            if (e.message?.contains("Failed to inject touch input") != true) throw e
            runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
            compose.waitForIdle()
            SystemClock.sleep(300)
            click()
        }
    }

    /**
     * Tap the session row until the [TMUX_SESSION_SCREEN_TAG] mounts. The
     * swiftshader AVD intermittently rejects a tap's touch injection while the
     * freshly-populated folder list is still settling; we retry the tap (settling
     * to idle + a short pause between attempts) until the session screen appears
     * or the budget is exhausted, so a transient injection rejection does not fail
     * the run.
     */
    private fun tapUntilSessionScreenShown(sessionName: String) {
        val deadline = SystemClock.elapsedRealtime() + ATTACH_TIMEOUT_MS
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            // Under memory pressure the AVD can send the app to the background
            // (focus moves to the launcher), which makes Compose touch injection
            // fail ("Failed to inject touch input"). Bring the Activity back to
            // RESUMED before each tap so the app window is foreground+touchable.
            runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
            compose.waitForIdle()
            runCatching {
                compose.onNodeWithText(sessionName, useUnmergedTree = true).performClick()
            }.onFailure { lastError = it }
            val shown = compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (shown) return
            SystemClock.sleep(400)
        }
        throw AssertionError(
            "session screen ($TMUX_SESSION_SCREEN_TAG) never mounted after tapping session " +
                "'$sessionName' within ${ATTACH_TIMEOUT_MS}ms; lastTapError=$lastError",
        )
    }

    private fun waitForTerminalSessionAttached() {
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
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    /**
     * Poll until the recorded `@ps_agent_kind=shell` verdict has applied (the
     * pane's confirmed-shell set became non-empty at least once) — the moment the
     * optimistic presumed-agent toggle collapses on base. Best-effort: returns
     * false if never observed within the budget (with the fix, B1 may clear it
     * before a poll tick lands; STEP 2 is the immune load-bearing signal).
     */
    private fun waitForConfirmedShellVerdict(vm: TmuxSessionViewModel): Boolean {
        val deadline = SystemClock.elapsedRealtime() + VERDICT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (vm.confirmedShellPaneIds.value.isNotEmpty()) return true
            SystemClock.sleep(80)
        }
        return vm.confirmedShellPaneIds.value.isNotEmpty()
    }

    /**
     * Poll until a LIVE transcript detection binds for any pane — the #975 B1
     * signal. In this fixture the daemon classify is `unknown`, so a non-null
     * `detection` can ONLY come from the transcript-evidence fallback; on base it
     * stays null. This is the same property the JVM red→green asserts
     * (`assertNotNull(detection)`), driven here on the real connected path.
     */
    private fun waitForLiveDetectionBound(vm: TmuxSessionViewModel): Boolean {
        val deadline = SystemClock.elapsedRealtime() + MASKED_TOGGLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (vm.agentConversations.value.values.any { it.detection != null }) return true
            SystemClock.sleep(100)
        }
        return vm.agentConversations.value.values.any { it.detection != null }
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

    private fun waitForVisibleTerminalText(
        label: String,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = visibleTerminalText()
            if (predicate(last)) return
            SystemClock.sleep(50)
        }
        writeText("issue962-failure-$label-visible-terminal.txt", last)
        assertTrue("predicate $label timed out; visible terminal:\n$last", false)
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

    private fun captureFullFrame(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write full-frame screenshot to ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE962_FULLFRAME ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE962_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/conversation-toggle-962")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact dir ${dir.absolutePath}" }
        return File(dir, name)
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

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun unique(): String =
        "${System.currentTimeMillis().toString().takeLast(6)}${System.nanoTime().toString().takeLast(4)}"

    private fun stamp(name: String) {
        stamps += "[issue962] $name at ${SystemClock.elapsedRealtime()}"
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val HOST_NAME: String = "Issue962 Agents"
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val HOST_ROW_TIMEOUT_MS: Long = 60_000
        const val VISIBLE_TIMEOUT_MS: Long = 20_000
        const val SHELL_NO_TOGGLE_SETTLE_MS: Long = 8_000
        const val VERDICT_TIMEOUT_MS: Long = 20_000

        // Issue #975: the masked-live-agent transcript fallback binds on the first
        // foreign reconcile + the confirmed-shell cache-bust re-probe; allow a
        // generous window for the toggle to return on the swiftshader AVD.
        const val MASKED_TOGGLE_TIMEOUT_MS: Long = 25_000
    }
}
