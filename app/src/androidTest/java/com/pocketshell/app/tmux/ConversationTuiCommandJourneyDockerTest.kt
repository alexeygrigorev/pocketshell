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
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_SEND_ENTER_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.SeedBeforeLaunchRule
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1207 (reviewer BLOCKED-G4 follow-up) — the REAL-PATH, end-to-end proof
 * of the Conversation-view TUI-only slash-command UX INTEGRATION that the
 * component/JVM proofs could not cover: a `/model` sent from the Conversation
 * composer on the REAL production [TmuxSessionScreen] takes the no-echo path,
 * raises the Open-in-Terminal notice, and one tap lands the user on the Terminal
 * tab. This is a hot reopen area (#818/#878/#815/#894/#962/#1057/#1158) so a
 * component render is explicitly NOT the acceptance — the maintainer's actual
 * on-screen journey is.
 *
 * ### Why this exists (the reviewer's residual — G4/G10/D33)
 *
 * The decision logic is proven by [TmuxSessionScreenTest] (JVM red→green for
 * `tmuxAgentConversationSend` / `tmuxConversationPlaceholderLoadState`) and the
 * notice/empty composables render on-device via
 * [com.pocketshell.app.conversation.ConversationTuiCommandNoticeRenderTest]. But
 * NEITHER exercises the SCREEN-LEVEL wiring: that a composer `/model` submit on
 * the live `TmuxSessionScreen` actually routes through the AgentConversation
 * branch, suppresses the optimistic bubble, raises the notice, and switches tabs
 * on tap. This test drives that integration on the real app + real Docker agent.
 *
 * ### Real-path red→green (D33/G10)
 *
 * [modelCommandFromConversationShowsNoticeNoEchoBubbleThenTapOpensTerminal]:
 *  1. Attach to a masked-live-claude session (the #975/#1057 fixture shape: a
 *     recorded-shell whose cwd holds a fresh Claude transcript; the #975
 *     transcript-evidence fallback binds live detection and the transcript tails
 *     into `events`). The composer route is AgentConversation only when
 *     `currentDetection != null` on the Conversation tab, so a REAL bound
 *     detection is required — a presumed-only pane routes AgentPayload and never
 *     reaches the notice path.
 *  2. Tap Conversation → the transcript renders. Snapshot the conversation events.
 *  3. Type `/model` into the REAL composer and Send.
 *  4. ASSERT (RED on base echo-always / GREEN with the #1207 fix):
 *      - NO optimistic `/model` User bubble was inserted into the transcript
 *        (on base `sendToAgentPaneResult` inserts one — the misleading echo).
 *      - The [TMUX_CONVERSATION_TUI_NOTICE_TAG] Open-in-Terminal notice IS shown
 *        on the Conversation tab (on base the notice never appears).
 *      - One tap on [TMUX_CONVERSATION_TUI_NOTICE_OPEN_TAG] lands the user on
 *        [SessionTab.Terminal] (the only surface that can drive the picker), and
 *        the notice self-clears.
 *
 * [confirmedShellShowsNoConversationNoticeOrPlaceholder] is the #894 no-flap
 * adjacency control: a genuine no-agent recorded shell shows NO Conversation tab
 * at all, so neither the notice nor the Conversation placeholder can appear on a
 * confirmed shell — the #1207 change must not resurrect a Conversation surface
 * for a conversation-less shell.
 *
 * Uses ONLY the deterministic `agents:2222` fixture that `tests.yml` already
 * brings up (no new Docker service/port), and the load-bearing assertions do NOT
 * self-skip on CI. Wired into `scripts/ci-journey-suite.sh` so the composer-send
 * integration stays durably guarded per-push. Harness mirrors
 * [ConversationStaysReachableAfterDetectionDropsDockerTest].
 */
@RunWith(AndroidJUnit4::class)
class ConversationTuiCommandJourneyDockerTest {

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
            writeText("issue1207-stamps.txt", stamps.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Issue #1207 LOAD-BEARING real-path red→green: a `/model` sent from the
     * Conversation composer shows the Open-in-Terminal notice (no misleading
     * echo bubble) and one tap lands the user on the Terminal.
     */
    @Test
    fun modelCommandFromConversationShowsNoticeNoEchoBubbleThenTapOpensTerminal() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue1207-ready", VISIBLE_TIMEOUT_MS) { "issue1207-ready" in it }
        stamp("attached session=$sessionName")

        val vm = currentViewModel()

        // STEP 1 — REAL detection binds (the #975 transcript-evidence fallback)
        // AND the transcript tails into `events`. A bound detection is what makes
        // the composer route AgentConversation (the notice path); a presumed-only
        // pane routes AgentPayload and never reaches it.
        val boundPaneId = requireNotNull(waitForDetectionBoundConversationPane(vm)) {
            "#1207 precondition: a live conversation with a bound detection must " +
                "bind on the real path so the composer routes AgentConversation. " +
                "agentConversations=${describeConversations(vm)}"
        }
        stamp("conversation_bound pane=$boundPaneId events=${vm.agentConversations.value[boundPaneId]?.events?.size}")

        // STEP 2 — open the Conversation tab; the real transcript renders.
        tapConversationSegment()
        compose.waitUntil(timeoutMillis = SURFACE_TIMEOUT_MS) { conversationPaneShown() }
        compose.onNodeWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true).assertExists()
        captureFullFrame("issue1207-01-conversation-open")

        // Snapshot the transcript BEFORE the `/model` send so we can prove no
        // optimistic `/model` User bubble was inserted by the send.
        val userTurnsBefore = userMessageTexts(vm, boundPaneId)
        assertFalse(
            "precondition: the transcript must not already contain a /model User turn",
            userTurnsBefore.any { it.trim() == MODEL_COMMAND },
        )
        stamp("pre_send user_turns=${userTurnsBefore.size}")

        // STEP 3 — type `/model` into the REAL composer and Send. This drives the
        // production composerSendHandler AgentConversation branch on the live
        // screen (NOT a proxy): `tmuxAgentConversationSend("/model")` =
        // TuiCommandNoEcho → sendAgentPayloadToPaneResult (the #1577b GATED submit:
        // floor + count-baseline ack gate + Enter; no optimistic echo) → the
        // Open-in-Terminal notice is raised. This test is the real-path guard for
        // the #1577b Route B routing change (Conversation slash → gated submit).
        sendFromComposer(MODEL_COMMAND)
        stamp("composer_sent text=$MODEL_COMMAND")

        // STEP 4a — LOAD-BEARING (RED on base echo-always / GREEN with the fix):
        // the Open-in-Terminal notice IS shown on the Conversation tab. On base
        // the send takes the sendToAgentPaneResult echo path and the notice never
        // appears — this waitUntil times out → RED.
        compose.waitUntil(timeoutMillis = NOTICE_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_TUI_NOTICE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_CONVERSATION_TUI_NOTICE_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TMUX_CONVERSATION_TUI_NOTICE_OPEN_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        captureFullFrame("issue1207-02-notice-shown")
        stamp("notice_shown")

        // STEP 4b — LOAD-BEARING (RED on base / GREEN with the fix): NO optimistic
        // `/model` User bubble was inserted into the transcript. On base
        // sendToAgentPaneResult inserts exactly this misleading echo turn.
        val userTurnsAfter = userMessageTexts(vm, boundPaneId)
        assertFalse(
            "#1207: a /model send must NOT insert an optimistic User bubble into " +
                "the Conversation transcript (the misleading echo). userTurns=$userTurnsAfter",
            userTurnsAfter.any { it.trim() == MODEL_COMMAND },
        )
        stamp("no_optimistic_model_bubble user_turns=${userTurnsAfter.size}")

        // STEP 5 — LOAD-BEARING: one tap on the notice's Open-in-Terminal lands
        // the user on the Terminal tab (the only surface that can drive the
        // picker) and the notice self-clears.
        compose.onNodeWithTag(TMUX_CONVERSATION_TUI_NOTICE_OPEN_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = SURFACE_TIMEOUT_MS) {
            vm.agentConversations.value[boundPaneId]?.selectedTab == SessionTab.Terminal
        }
        assertEquals(
            "#1207: one tap on Open-in-Terminal must select the Terminal tab",
            SessionTab.Terminal,
            vm.agentConversations.value[boundPaneId]?.selectedTab,
        )
        // The notice self-clears on the jump.
        compose.waitUntil(timeoutMillis = SURFACE_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_TUI_NOTICE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.onNodeWithTag(TMUX_CONVERSATION_TUI_NOTICE_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        captureFullFrame("issue1207-03-terminal-after-tap")
        stamp("tap_opened_terminal_and_notice_cleared")

        writeText("issue1207-acceptance.txt", buildString {
            appendLine("issue=1207")
            appendLine("no_optimistic_model_bubble=true")
            appendLine("open_in_terminal_notice_shown=true")
            appendLine("one_tap_lands_on_terminal=true")
            appendLine("notice_self_cleared_on_jump=true")
            appendLine("bound_pane=$boundPaneId")
            appendLine("user_turns_before=${userTurnsBefore.size}")
            appendLine("user_turns_after=${userTurnsAfter.size}")
        })
        Unit
    } }

    /**
     * #894 no-flap adjacency control: a genuine no-agent recorded shell shows NO
     * Conversation tab, so the #1207 notice and the Conversation placeholder can
     * NEVER appear on a confirmed shell. The #1207 change (which only touches the
     * Conversation composer send path + the placeholder load-state resolution)
     * must not resurrect a Conversation surface for a conversation-less shell.
     */
    @Test
    fun confirmedShellShowsNoConversationNoticeOrPlaceholder() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue1207-plain-ready", VISIBLE_TIMEOUT_MS) {
            "issue1207-plain-ready" in it
        }
        stamp("plain_shell_attached session=$sessionName")

        // Give live detection a generous window to (not) fire.
        SystemClock.sleep(SHELL_NO_TOGGLE_SETTLE_MS)

        // No Conversation tab → no notice, no Conversation placeholder possible.
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(TMUX_CONVERSATION_TUI_NOTICE_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithTag(TMUX_CONVERSATION_DETECTING_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        captureFullFrame("issue1207-plain-shell-no-conversation")
        stamp("plain_shell_no_conversation_surface_ok")
        Unit
    } }

    // -------------------------------------------------------------- seed-before-launch

    private suspend fun seedForMethod(methodName: String) {
        val key = readFixtureKey()
        seededKey = key
        clearLastSessionPrefs()
        waitForSshFixtureReady(SshKey.Pem(key))
        val sessionName = when (methodName) {
            "modelCommandFromConversationShowsNoticeNoEchoBubbleThenTapOpensTerminal" ->
                seedMaskedLiveClaudeSession(key)
            "confirmedShellShowsNoConversationNoticeOrPlaceholder" ->
                seedPlainShellSession(key)
            else -> error("unexpected test method $methodName")
        }
        seededSessionName = sessionName
        seededHostRowTag = persistHost(key)
    }

    private suspend fun seedPlainShellSession(key: String): String {
        val suffix = unique()
        val sessionName = "issue1207-plain-shell-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} -c /tmp " +
                        "\"printf 'issue1207-plain-ready\\r\\n'; exec sh\"",
                )
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind shell")
                appendLine("sleep 1")
            },
        )
        return sessionName
    }

    /**
     * The #975/#1057 masked-live-claude fixture shape: a session recorded
     * `@ps_agent_kind=shell` whose cwd holds a FRESH Claude transcript (copied
     * from the fixture seed). The daemon classify returns `unknown`, so the ONLY
     * way detection binds is the #975 transcript-evidence fallback — which is what
     * we need: a bound live detection whose transcript tails into events so the
     * composer routes AgentConversation.
     */
    private suspend fun seedMaskedLiveClaudeSession(key: String): String {
        val suffix = unique()
        val sessionName = "issue1207-masked-claude-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        cleanupCommands +=
            "rm -f /home/testuser/.claude/projects/-home-testuser/" +
                "issue1207-live-claude.jsonl 2>/dev/null || true"
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine("mkdir -p /home/testuser/.claude/projects/-home-testuser")
                appendLine(
                    "cp /home/testuser/.claude/projects/-workspace-pocketshell/" +
                        "pocketshell-claude.jsonl " +
                        "/home/testuser/.claude/projects/-home-testuser/" +
                        "issue1207-live-claude.jsonl",
                )
                appendLine(
                    "touch /home/testuser/.claude/projects/-home-testuser/" +
                        "issue1207-live-claude.jsonl",
                )
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} " +
                        "-c /home/testuser " +
                        "\"printf 'issue1207-ready\\r\\n'; exec sh\"",
                )
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind shell")
                appendLine("sleep 1")
            },
        )
        return sessionName
    }

    // ------------------------------------------------------------------ composer

    private fun sendFromComposer(text: String) {
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        clickRobustly {
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        }
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true).performTextInput(text)
        compose.waitForIdle()
        clickRobustly {
            compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true).performClick()
        }
        // The composer dismisses on a successful send (draft cleared).
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    // ----------------------------------------------------------------- helpers

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
                name = "issue1207-key-${System.currentTimeMillis()}",
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
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        clickRobustly { compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick() }
        compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
            compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        tapUntilSessionScreenShown(sessionName)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

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

    private fun tapUntilSessionScreenShown(sessionName: String) {
        val deadline = SystemClock.elapsedRealtime() + ATTACH_TIMEOUT_MS
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
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
     * Poll until a pane has a bound live detection (`detection != null`) — the
     * state the composer needs to route AgentConversation. Returns the pane id,
     * or null if it never bound within the budget.
     */
    private fun waitForDetectionBoundConversationPane(vm: TmuxSessionViewModel): String? {
        val deadline = SystemClock.elapsedRealtime() + CONVERSATION_LOAD_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val match = vm.agentConversations.value.entries.firstOrNull {
                it.value.detection != null
            }
            if (match != null) return match.key
            SystemClock.sleep(150)
        }
        return vm.agentConversations.value.entries.firstOrNull { it.value.detection != null }?.key
    }

    private fun userMessageTexts(vm: TmuxSessionViewModel, paneId: String): List<String> =
        vm.agentConversations.value[paneId]?.events.orEmpty()
            .filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User }
            .map { it.text }

    private fun describeConversations(vm: TmuxSessionViewModel): String =
        vm.agentConversations.value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "$k=(agent=${v.detection?.agent}, events=${v.events.size}, tab=${v.selectedTab})"
        }

    private fun tapConversationSegment() {
        clickRobustly {
            compose.onNodeWithTag(CONVERSATION_SEGMENT_TAG, useUnmergedTree = true).performClick()
        }
        compose.waitForIdle()
    }

    private fun conversationPaneShown(): Boolean =
        compose.onAllNodesWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

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
        writeText("issue1207-failure-$label-visible-terminal.txt", last)
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
        println("ISSUE1207_FULLFRAME ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1207_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/conversation-tui-command-1207")
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
        stamps += "[issue1207] $name at ${SystemClock.elapsedRealtime()}"
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val HOST_NAME: String = "Issue1207 Agents"
        const val MODEL_COMMAND: String = "/model"
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val HOST_ROW_TIMEOUT_MS: Long = 60_000
        const val VISIBLE_TIMEOUT_MS: Long = 20_000
        const val SHELL_NO_TOGGLE_SETTLE_MS: Long = 8_000
        const val CONVERSATION_LOAD_TIMEOUT_MS: Long = 30_000
        const val SURFACE_TIMEOUT_MS: Long = 20_000
        const val NOTICE_TIMEOUT_MS: Long = 20_000
        const val COMPOSER_TIMEOUT_MS: Long = 15_000

        // The Conversation segment is index 1 in the Terminal|Conversation pill
        // (index 0 is TMUX_TERMINAL_TAB_TAG; see ConsolidatedTabPill.segmentTag).
        const val CONVERSATION_SEGMENT_TAG: String = TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + "1"
    }
}
