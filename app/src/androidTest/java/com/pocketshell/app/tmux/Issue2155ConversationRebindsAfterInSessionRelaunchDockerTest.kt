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
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #2155 — REAL-PATH (G10/D33) reproduction of the maintainer's report:
 *
 * > "The conversation tab often gets stale. Let's say I start a new session, or I
 * > start a new agent, or I do something — and it still points to the old
 * > conversation. So the conversation tab is not up to date with the actual state
 * > of the tmux panel."
 *
 * ### What this journey does
 *
 * It attaches to a real tmux session on the Docker `agents` fixture that carries
 * the FULL host-side launch record for agent #1 (`@ps_agent_kind=claude`,
 * `@ps_agent_source_generation=<gen-1>`, `@ps_agent_source="<gen-1>\t<first>"`),
 * opens the Conversation and confirms the FIRST agent's transcript is on screen.
 *
 * It then performs the **exact option sequence the host CLI's `record_agent_source`
 * performs on a relaunch** — mint a fresh generation, UNSET the stale
 * `@ps_agent_source`, then (as the watcher) write `"<gen-2>\t<second>"` — and
 * starts a genuinely new agent process in the SAME pane. PocketShell never sees a
 * launch event for this. The load-bearing signal is the generation bump riding
 * the existing `list-panes` reconcile (`#{@ps_agent_source_generation}`): a
 * same-kind `/new` or typed `claude` often leaves `(cwd, command, tty)`
 * unchanged, so that triple alone never retriggers detection.
 *
 * ### The fixture is what makes it a reproduction (G10)
 *
 * A happy fixture cannot enter the failing state, so this one is built to:
 *  * keep the FIRST agent's transcript FRESH on disk (inside the detector's 2h
 *    `-mmin -120` window), so the stale path is still enumerable and the bug can
 *    actually manifest; and
 *  * make the SECOND agent's own transcript **NOT the newest file in the cwd** — a
 *    busier same-kind sibling flushed more recently. Without that sibling, "bind
 *    the newest same-cwd transcript" would satisfy the assertion, so a change that
 *    merely stopped resolving the recorded source at all would pass. With it, only
 *    honouring the LIVE `@ps_agent_source` binds the right transcript (G6).
 *
 * RED on current main (`87d523fc`): detection only re-runs when
 * `(cwd, command, tty)` changes, so after the in-session relaunch the
 * Conversation stays bound to agent #1 even though the host `@ps_agent_source`
 * is already correct (STEP 3, 2/2 on the #2160-unblocked fixture). GREEN: the
 * generation rides the existing list-panes reconcile, detection re-runs, and
 * the Conversation re-anchors to agent #2. #2160 (`tmux -u`) is already on
 * main, so STEP 1 (exact-source bind of the older first transcript vs the
 * newer decoy) is a real precondition, not a locale-vacuous skip.
 *
 * The load-bearing assertion is the **bound transcript identity plus the text the
 * user actually sees** — not that some read happened.
 *
 * Harness: the #788 interop-placement cure — `createAndroidComposeRule<MainActivity>()`
 * with seed-before-launch via `RuleChain(grant -> seed -> compose)`.
 */
@RunWith(AndroidJUnit4::class)
class Issue2155ConversationRebindsAfterInSessionRelaunchDockerTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(SeedBeforeLaunchRule { seedFixture() })
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
                        withTimeout(30_000) { execRemote(key, cleanupCommands.joinToString("\n")) }
                    }
                }
            }
        }
        if (stamps.isNotEmpty()) {
            writeText("issue2155-stamps.txt", stamps.joinToString("\n", postfix = "\n"))
        }
    }

    @Test
    fun conversationRebindsToTheNewAgentTranscriptAfterAnInSessionRelaunch() { runBlocking {
        val key = requireNotNull(seededKey) { "seed-before-launch key missing" }
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue2155-ready") { "issue2155-ready" in it }
        stamp("attached session=$sessionName")

        val vm = currentViewModel()

        // ---------------------------------------------------------- STEP 1
        // The FIRST agent's recorded transcript binds. This is the precondition:
        // without it the relaunch assertion below would be vacuous (there would be
        // no stale binding to carry forward).
        val boundFirst = waitForBoundSource(vm) { it == FIRST_TRANSCRIPT }
        if (!boundFirst) {
            // Record what the host actually stored + returns, so a precondition
            // failure names its cause instead of leaving the next reader guessing.
            writeText("issue2155-recorded-option-bytes.txt", dumpRecordedOptionBytes(key))
        }
        assertTrue(
            "precondition: the Conversation must bind agent #1's RECORDED transcript.\n" +
                "  expected: $FIRST_TRANSCRIPT\n" +
                "  bound:    ${boundSources(vm)}\n" +
                "If the DECOY ($SEED_DECOY) is bound instead, the exact-recorded-source " +
                "mechanism did not resolve at all and the mtime selector won: the " +
                "`<generation>\\t<path>` value in @ps_agent_source did not survive " +
                "round-tripping through this host's tmux (`show-options -v` sanitising the " +
                "TAB is the known cause). That is NOT this issue's bug — it disables the " +
                "exact-source binding for EVERY session on such a host — but it also means " +
                "this fixture cannot reproduce #2155, so treat a failure here as a fixture/" +
                "host-protocol finding and see issue2155-recorded-option-bytes.txt.",
            boundFirst,
        )
        openConversationTab()
        val sawFirstText = waitForConversationText(FIRST_MARKER)
        assertTrue(
            "precondition: agent #1's transcript text must be on screen so a stale " +
                "binding after the relaunch is genuinely user-visible",
            sawFirstText,
        )
        captureFullFrame("issue2155-1-first-agent-conversation")
        stamp("first_agent_bound_and_rendered")

        // ---------------------------------------------------------- STEP 2
        // Start a NEW agent inside the SAME tmux session, exactly as the host CLI
        // does. PocketShell observes no launch event — only the pane's detection
        // input changing on the next reconcile.
        relaunchAgentInSameSession(key)
        stamp("relaunch_performed generation=$SECOND_GENERATION")

        // ---------------------------------------------------------- STEP 3
        // LOAD-BEARING (G6): the Conversation must re-anchor to the NEW agent's
        // transcript. Binding $FIRST_TRANSCRIPT is the reported symptom (the stale
        // cached source); binding $BUSIER_SIBLING would mean the live
        // @ps_agent_source was not honoured at all. Only $SECOND_TRANSCRIPT is
        // correct, and only a re-read of @ps_agent_source_generation can pick it.
        val reboundSecond = waitForBoundSource(vm) { it == SECOND_TRANSCRIPT }
        assertEquals(
            "#2155: after a new agent starts in the SAME tmux session the " +
                "Conversation must bind the NEW transcript. On base the " +
                "generation-scoped cached source short-circuits every resolve, so " +
                "it stays on agent #1's transcript. bound=${boundSources(vm)}",
            SECOND_TRANSCRIPT,
            boundSources(vm).firstOrNull { it == SECOND_TRANSCRIPT }
                ?: boundSources(vm).firstOrNull(),
        )
        assertTrue("re-anchor must land within the journey budget", reboundSecond)
        stamp("rebound_to_second_transcript")

        // ---------------------------------------------------------- STEP 4
        // USER-VISIBLE: the rendered Conversation shows the NEW agent's turn and no
        // longer the previous agent's — "the conversation tab is up to date with
        // the actual state of the tmux panel".
        openConversationTab()
        val sawSecondText = waitForConversationText(SECOND_MARKER)
        captureFullFrame("issue2155-2-second-agent-conversation")
        assertTrue(
            "#2155: the rendered Conversation must show the NEW agent's transcript " +
                "text ($SECOND_MARKER)",
            sawSecondText,
        )
        assertTrue(
            "#2155: the PREVIOUS agent's transcript text ($FIRST_MARKER) must no " +
                "longer be rendered — that leftover IS the maintainer's symptom",
            compose.onAllNodesWithText(FIRST_MARKER, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        stamp("second_agent_rendered_first_gone")
        Unit
    } }

    // -------------------------------------------------------------- seed

    private suspend fun seedFixture() {
        val key = readFixtureKey()
        seededKey = key
        clearLastSessionPrefs()
        waitForSshFixtureReady(SshKey.Pem(key))
        seededSessionName = seedFirstAgentSession(key)
        seededHostRowTag = persistHost(key)
    }

    /**
     * A tmux session carrying the FULL host-side launch record for agent #1 — the
     * state the host CLI's `record_agent_source` leaves behind after a successful
     * launch: a recorded kind, a launch generation, and the watcher-written
     * `"<generation>\t<source>"` pair.
     */
    private suspend fun seedFirstAgentSession(key: String): String {
        val sessionName = "issue2155-relaunch-${unique()}"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(PROJECT_DIR)} ${shellQuote(CWD)} 2>/dev/null || true"
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine("rm -rf ${shellQuote(PROJECT_DIR)}")
                appendLine("mkdir -p ${shellQuote(PROJECT_DIR)} ${shellQuote(CWD)}")
                appendLine(transcriptWriteCommand(FIRST_TRANSCRIPT, "first", FIRST_MARKER))
                appendLine(transcriptWriteCommand(SEED_DECOY, "seeddecoy", SEED_DECOY_MARKER))
                // Agent #1's transcript stays FRESH (well inside the detector's 2h
                // `-mmin -120` window) so the stale path remains enumerable after
                // the relaunch — otherwise the bug could not manifest at all. The
                // decoy is NEWER, so only an exact `@ps_agent_source` match can
                // bind agent #1 (see [SEED_DECOY]).
                appendLine("touch -d \"@\$(( \$(date +%s) - 60 ))\" ${shellQuote(FIRST_TRANSCRIPT)}")
                appendLine("touch ${shellQuote(SEED_DECOY)}")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} " +
                        "-c ${shellQuote(CWD)} " +
                        "\"printf 'issue2155-ready\\r\\n'; exec sh\"",
                )
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind claude")
                appendLine(
                    "tmux set-option -t ${shellQuote(sessionName)} " +
                        "@ps_agent_source_generation ${shellQuote(FIRST_GENERATION)}",
                )
                appendLine(
                    "tmux set-option -t ${shellQuote(sessionName)} @ps_agent_source " +
                        shellQuote("$FIRST_GENERATION\t$FIRST_TRANSCRIPT"),
                )
                // HARD precondition: a mis-seeded fixture must fail here rather
                // than vacuously "pass" the journey with nothing to re-anchor.
                // Matched on the PATH only, never on the `<generation>\t<path>`
                // pair: whether the TAB survives `show-options -v` is precisely
                // what STEP 1 is here to observe, so the seed must not pre-judge
                // it (and a tmux that sanitises it would fail the seed for a
                // reason the journey should be reporting, not hiding).
                appendLine(
                    "tmux show-options -v -t ${shellQuote(sessionName)} @ps_agent_source | " +
                        "grep -Fq ${shellQuote(FIRST_TRANSCRIPT)}",
                )
                appendLine("test -s ${shellQuote(FIRST_TRANSCRIPT)}")
                appendLine("test -s ${shellQuote(SEED_DECOY)}")
                appendLine("sleep 1")
            },
        )
        return sessionName
    }

    /**
     * The relaunch, performed EXACTLY as the host CLI's `record_agent_source` does
     * it (`tools/pocketshell/src/pocketshell/agents.py`):
     *  1. mint a fresh generation and write `@ps_agent_source_generation`,
     *  2. UNSET the stale `@ps_agent_source`,
     *  3. (watcher) write `"<generation>\t<source>"` once the new transcript exists.
     *
     * Plus a genuinely new agent process in the SAME pane, which is the only thing
     * PocketShell can observe (the pane's `(cwd, command, tty)` detection input
     * changes on the next `list-panes` reconcile — there is no launch event).
     *
     * The busier sibling is written LAST and is the newest file in the cwd, so the
     * mtime selector would pick IT, never the new agent's own transcript — that is
     * what makes STEP 3 discriminating.
     */
    private suspend fun relaunchAgentInSameSession(key: String) {
        val sessionName = requireNotNull(seededSessionName)
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine(transcriptWriteCommand(SECOND_TRANSCRIPT, "second", SECOND_MARKER))
                appendLine(transcriptWriteCommand(BUSIER_SIBLING, "busier", BUSIER_MARKER))
                // The NEW agent's own transcript is deliberately NOT the newest.
                appendLine("touch -d \"@\$(( \$(date +%s) - 30 ))\" ${shellQuote(SECOND_TRANSCRIPT)}")
                appendLine("touch ${shellQuote(BUSIER_SIBLING)}")
                // --- record_agent_source, verbatim protocol ---
                appendLine(
                    "tmux set-option -t ${shellQuote(sessionName)} " +
                        "@ps_agent_source_generation ${shellQuote(SECOND_GENERATION)}",
                )
                appendLine(
                    "tmux set-option -u -t ${shellQuote(sessionName)} @ps_agent_source " +
                        "2>/dev/null || true",
                )
                appendLine(
                    "tmux set-option -t ${shellQuote(sessionName)} @ps_agent_source " +
                        shellQuote("$SECOND_GENERATION\t$SECOND_TRANSCRIPT"),
                )
                // --- the new agent process, in the SAME pane ---
                appendLine(
                    "tmux send-keys -t ${shellQuote(sessionName)} " +
                        shellQuote(
                            "exec env POCKETSHELL_FAKE_AGENT_TRANSCRIPT=" +
                                SECOND_TRANSCRIPT + " /usr/local/bin/pocketshell-fake-agent",
                        ) + " Enter",
                )
                appendLine("sleep 1")
                // HARD precondition: the relaunch record must really be in place.
                appendLine(
                    "tmux show-options -v -t ${shellQuote(sessionName)} @ps_agent_source | " +
                        "grep -Fq ${shellQuote(SECOND_TRANSCRIPT)}",
                )
                appendLine("test -s ${shellQuote(SECOND_TRANSCRIPT)}")
                appendLine("test -s ${shellQuote(BUSIER_SIBLING)}")
            },
        )
    }

    /**
     * One assistant turn carrying [marker] as its visible text, in the Claude JSONL
     * shape the app parses. Written with `printf` so the Alpine-minimal fixture
     * needs no jq/python.
     */
    private fun transcriptWriteCommand(path: String, id: String, marker: String): String {
        val json = """{"uuid":"issue2155-$id","timestamp":"2026-08-15T10:00:00Z",""" +
            """"message":{"role":"assistant","content":[{"type":"text","text":"$marker"}]}}"""
        return "printf '%s\\n' " + shellQuote(json) + " > " + shellQuote(path)
    }

    // ----------------------------------------------------------- helpers

    /**
     * Dump what the host stores for `@ps_agent_source` and what the product's own
     * read shape returns, byte for byte. `show-options` (listing form) escapes a
     * stored control byte visibly, while `show-options -v` returns it raw — so
     * comparing the two says whether a TAB was lost at STORE time or at READ time.
     */
    private fun dumpRecordedOptionBytes(key: String): String {
        val sessionName = seededSessionName ?: return "no seeded session"
        val q = shellQuote(sessionName)
        return runBlocking {
            runCatching {
                withTimeout(30_000) {
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = SshKey.Pem(key),
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 15_000,
                    ).mapCatching { session ->
                        session.use {
                            it.exec(
                                "tmux -V\n" +
                                    "echo '--- stored (listing form, escapes control bytes) ---'\n" +
                                    "tmux show-options -t $q @ps_agent_source\n" +
                                    "echo '--- read back (-v, the form the app uses) ---'\n" +
                                    "tmux show-options -v -t $q @ps_agent_source | od -c\n" +
                                    "echo '--- transcripts present ---'\n" +
                                    "ls -la --time-style=+%s ${shellQuote(PROJECT_DIR)}\n",
                            ).stdout
                        }
                    }.getOrElse { "diagnostic exec failed: $it" }
                }
            }.getOrElse { "diagnostic failed: $it" }
        }
    }

    private fun boundSources(vm: TmuxSessionViewModel): List<String> =
        vm.agentConversations.value.values.mapNotNull { it.detection?.sourcePath }

    private fun waitForBoundSource(
        vm: TmuxSessionViewModel,
        predicate: (String) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + REBIND_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (boundSources(vm).any(predicate)) return true
            SystemClock.sleep(200)
        }
        return boundSources(vm).any(predicate)
    }

    private fun waitForConversationText(marker: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + REBIND_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val found = runCatching {
                compose.onAllNodesWithText(marker, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
            if (found) return true
            SystemClock.sleep(250)
        }
        return false
    }

    private fun openConversationTab() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        compose.waitForIdle()
        runCatching {
            compose.onAllNodesWithText("Conversation", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false).let { present ->
            if (present) {
                runCatching {
                    compose.onAllNodesWithText("Conversation", useUnmergedTree = true)[0]
                        .performClick()
                }
            }
        }
        compose.waitForIdle()
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
                name = "issue2155-key-${System.currentTimeMillis()}",
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
                compose.onAllNodesWithText(sessionName, useUnmergedTree = true)[0].performClick()
            }.onFailure { lastError = it }
            val shown = compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (shown) return
            SystemClock.sleep(400)
        }
        throw AssertionError(
            "session screen ($TMUX_SESSION_SCREEN_TAG) never mounted after tapping " +
                "'$sessionName' within ${ATTACH_TIMEOUT_MS}ms; lastTapError=$lastError",
        )
    }

    private fun waitForTerminalSessionAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                attached = activity.window.decorView.findTerminalView()?.currentSession?.emulator != null
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

    private fun waitForVisibleTerminalText(label: String, predicate: (String) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + VISIBLE_TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = visibleTerminalText()
            if (predicate(last)) return
            SystemClock.sleep(50)
        }
        writeText("issue2155-failure-$label-visible-terminal.txt", last)
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
        println("ISSUE2155_FULLFRAME ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE2155_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue2155-stale-conversation")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact dir ${dir.absolutePath}" }
        return File(dir, name)
    }

    private suspend fun execRemote(key: String, command: String) {
        val result = withTimeout(45_000) {
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
        stamps += "[issue2155] $name at ${SystemClock.elapsedRealtime()}"
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val HOST_NAME: String = "Issue2155 Agents"

        const val CWD: String = "/home/testuser/issue2155"
        const val PROJECT_DIR: String =
            "/home/testuser/.claude/projects/-home-testuser-issue2155"
        const val FIRST_TRANSCRIPT: String = "$PROJECT_DIR/first-agent.jsonl"

        /**
         * A transcript in the SAME cwd that is NEWER than agent #1's own, seeded
         * before the app ever attaches. It makes STEP 1 a genuine probe of the
         * recorded-source mechanism rather than a formality: if
         * `@ps_agent_source` resolves, the app binds the OLDER [FIRST_TRANSCRIPT]
         * because the option names it exactly; if the recorded source does NOT
         * resolve for any reason, the mtime selector binds THIS decoy instead and
         * STEP 1 fails loudly. Without it, both outcomes would bind
         * [FIRST_TRANSCRIPT] and the precondition would pass vacuously.
         */
        const val SEED_DECOY: String = "$PROJECT_DIR/seed-decoy.jsonl"
        const val SECOND_TRANSCRIPT: String = "$PROJECT_DIR/second-agent.jsonl"
        const val BUSIER_SIBLING: String = "$PROJECT_DIR/busier-sibling.jsonl"

        const val FIRST_GENERATION: String = "issue2155-launch-1"
        const val SECOND_GENERATION: String = "issue2155-launch-2"

        const val FIRST_MARKER: String = "ISSUE2155-FIRST-AGENT-TURN"
        const val SECOND_MARKER: String = "ISSUE2155-SECOND-AGENT-TURN"
        const val BUSIER_MARKER: String = "ISSUE2155-BUSIER-SIBLING-TURN"
        const val SEED_DECOY_MARKER: String = "ISSUE2155-SEED-DECOY-TURN"

        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val HOST_ROW_TIMEOUT_MS: Long = 60_000
        const val VISIBLE_TIMEOUT_MS: Long = 20_000

        // The re-anchor rides the periodic `list-panes` reconcile (5-30s, D21 — no
        // polling is added), so allow a generous window on the swiftshader AVD.
        const val REBIND_TIMEOUT_MS: Long = 60_000
    }
}
