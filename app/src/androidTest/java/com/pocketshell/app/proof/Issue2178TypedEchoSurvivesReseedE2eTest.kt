package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_REDRAW_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
import com.pocketshell.app.tmux.ReseedApplyRaceTestGate
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #2178 — END-TO-END (D33/G10) proof, on the real emulator + Docker tmux
 * fixture, that text typed while a reseed is in flight is still on screen after
 * that reseed lands.
 *
 * ## The reported defect, on the real path
 *
 * Typing within roughly 300 ms of a pane appearing silently loses the leading
 * characters. The reveal gate paints a healed `capture-pane` BEFORE the attach
 * finishes, so the pane is visible and typeable while
 * `TmuxSessionViewModel.reseedActivePaneForReattach` is still in flight; when its
 * own `capture-pane` result lands it REPLACES the local screen with a server
 * snapshot taken earlier, discarding everything echoed in between. The CI
 * artifact that established this shows the prompt arriving shifted left by
 * exactly two columns — `ISSUE423-PROMPT-HEAD` as `SUE423-PROMPT-HEAD` — i.e. the
 * two leading bytes deleted and the rest slid left.
 *
 * ## Why this journey is deterministic and not a 245–460 ms coin flip
 *
 * The window is REAL and present on every run, but whether a test lands inside it
 * is chance, and a proof that only sometimes enters the failing state is not a
 * proof (the #847 happy-fixture lesson). So this uses the #780 synthetic-injection
 * model: [ReseedApplyRaceTestGate] HOLDS a reseed between its `capture-pane`
 * round-trip and its apply — widening a window production genuinely has rather
 * than inventing a step production never performs — and the journey types inside
 * it through the production `InputConnection`, exactly as the soft keyboard does.
 *
 * Every wait is bounded and HARD-FAILS; there is no `assumeTrue` and no silent
 * degradation. In particular the journey fails loudly if the reseed never parks,
 * so "the window did not happen" can never read as a pass.
 *
 * ## The journey (a real session switch — the class the issue calls out)
 *
 *  1. attach to session A on the Docker `agents` fixture;
 *  2. switch to session B through the production Back → session-row tap, with the
 *     seam DISARMED so the switch's own attach runs exactly as in production;
 *  3. on that freshly-switched-to session, tap the production Redraw action — the
 *     same `reseedActivePaneForReattach` → `captureAndApplyPaneSnapshot`
 *     chokepoint the attach reflow uses — and catch it parked before its apply;
 *  4. type a unique marker through the production `InputConnection` and wait for
 *     the pane to echo it;
 *  5. release THAT apply while the seam stays ARMED, so it proceeds exactly as
 *     production would but every later capture→apply is frozen before painting;
 *  6. assert the typed marker is STILL on screen, byte-for-byte, read in that
 *     frozen state;
 *  7. unfreeze, let the refusal's re-capture land, and require both the typed
 *     marker and the session's own content on the settled screen.
 *
 * On base step 6 is RED: the snapshot predates the echo, so the repaint clears it
 * away. With the ordering guard the snapshot is refused and re-captured, so the
 * bytes survive AND tmux's authoritative content still lands.
 *
 * ## Why step 5 freezes the later painters (the round-2 oracle fix)
 *
 * Reading the SETTLED end state instead is NOT a sound oracle. The typed
 * characters are still held in the remote shell's line editor, so any subsequent
 * server repaint — most often the #966 stale-render heal reacting to the very
 * divergence the destructive apply created — restores them before the assertion
 * looks. Measured on a build with the guard fully removed: PASS / FAIL / FAIL.
 * Keeping the seam armed across the release turns "the bytes were destroyed" into
 * a permanent, observable state, so the assertion measures the apply under test
 * rather than a race between damage and repair.
 *
 * ## CI compatibility
 *
 * Uses the default `agents` Docker service on port 2222 — the same fixture the
 * sibling connected journeys in `scripts/ci-journey-suite.sh` use. No extra
 * service or port.
 */
@RunWith(AndroidJUnit4::class)
class Issue2178TypedEchoSurvivesReseedE2eTest {

    // Issue #788/#848: launch-owned rule so the Compose clock and the real
    // TerminalView interop child share one MainActivity, with the Docker tmux
    // sessions and the Room host seeded BEFORE that activity launches.
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String

    private suspend fun seedBeforeLaunch() {
        fixtureKey = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedTmuxSessions(fixtureKey)
        hostRowTag = seedDockerHost(fixtureKey, "Issue2178 Reseed")
        forceFlatHostDetailViewMode()
    }

    @After
    fun teardown() {
        // Never leave a production reseed parked on a global seam.
        ReseedApplyRaceTestGate.clearReseedApplyPark()
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupSeededSessions(fixtureKey) } }
        }
    }

    @Test
    fun typedTextSurvivesTheReseedThatFollowsASessionSwitch() { runBlocking<Unit> {
        // ===== Step 1 — attach to session A =====
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForSessionRowVisible(SESSION_A)
        compose.onNodeWithText(SESSION_A).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        selectTerminalTabForJourney()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("session A marker") { it.contains(MARKER_A) }
        captureViewport("issue2178-01-attached-a")

        // ===== Step 2 — a real session SWITCH, left completely unblocked =====
        // The seam is NOT armed here: the switch itself (its cold-open seed and
        // its post-`refresh-client -C` reflow reseed) must run exactly as in
        // production, so the pane under test is a genuinely freshly-switched-to
        // session rather than one held together by a test seam.
        val switchStart = SystemClock.elapsedRealtime()
        clickTmuxBack()
        waitForSessionRowVisible(SESSION_B)
        compose.onNodeWithText(SESSION_B).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        selectTerminalTabForJourney()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("session B marker") { it.contains(MARKER_B) }
        recordTiming("session-switch->terminal-visible", switchStart)
        captureViewport("issue2178-02-switched-to-b")

        // ===== Step 3 — drive a reseed and catch it mid-flight =====
        // Redraw is a production user action on the SAME chokepoint the attach
        // reflow uses ([TmuxSessionViewModel.reseedActivePaneForReattach] →
        // `captureAndApplyPaneSnapshot`), so tapping it reproduces the reported
        // ordering — revealed, typeable pane with a reseed in flight — WITHOUT
        // depending on winning a 245–460 ms race against the attach.
        ReseedApplyRaceTestGate.forceNextReseedApplyParkedForTest()
        openMoreMenu()
        compose.onNodeWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true).performClick()
        awaitReseedParked("the Redraw reseed on the switched-to session")

        // ===== Step 4 — type inside that window, through the real InputConnection
        val typingStart = SystemClock.elapsedRealtime()
        typeThroughTerminalInput(TYPED_MARKER)
        val echoed = waitForCondition(ECHO_TIMEOUT_MS) {
            transcriptContains(visibleTerminalText(), TYPED_MARKER)
        }
        assertTrue(
            "precondition: the pane must echo the typed marker WHILE the reseed is " +
                "parked — that is the state the defect needs, and without it the " +
                "survival assertion below would be vacuous. Visible terminal:\n" +
                visibleTerminalText(),
            echoed,
        )
        recordTiming("type->echo-visible", typingStart)
        captureViewport("issue2178-03-typed-inside-reseed-window")
        val transcriptAtType = visibleTerminalText()

        // ===== Step 5 — release THAT apply, with every later painter frozen =====
        // THE ORACLE FIX (round 2). Releasing and then reading the SETTLED end
        // state is not a sound oracle: the typed characters are still in the
        // remote shell's line editor, so any later server repaint — most often
        // the #966 stale-render heal reacting to the divergence the destructive
        // apply just created — puts them back. Measured: a build with the guard
        // fully removed passed 1 run in 3 that way. So the seam stays ARMED
        // across the release: the parked apply proceeds (exactly as production
        // would), and every capture→apply that follows it — the refusal's bounded
        // retry, a watchdog heal, a prewarm reseed — is held before painting.
        // Destruction therefore becomes a PERMANENT observable state instead of a
        // transient one that races the assertion.
        val releaseStart = SystemClock.elapsedRealtime()
        val parkedBeforeRelease = ReseedApplyRaceTestGate.parkedReseedApplyCount()
        ReseedApplyRaceTestGate.forceReleaseOfParkedReseedAppliesForTest()

        // The apply has COMPLETED when either outcome is observable:
        //  - it refused  -> the bounded retry re-captures and parks again, so the
        //                   parked count grows (the fixed build); or
        //  - it painted  -> the visible transcript changed (the broken build).
        // Neither happening means the run proved nothing, so this HARD-FAILS
        // rather than letting the assertion run too early and pass by arriving
        // before the damage.
        val appliedTranscript = awaitReseedApplyOutcome(parkedBeforeRelease, transcriptAtType)
        recordTiming("reseed-release->apply-outcome", releaseStart)
        captureViewport("issue2178-04-after-reseed")

        // ===== Step 6 — THE LOAD-BEARING ASSERTION, taken while frozen =====
        assertTrue(
            "REGRESSION (#2178): the reseed applied a `capture-pane` snapshot taken " +
                "BEFORE the typed bytes reached the screen, so its full-grid repaint " +
                "discarded them — the on-device symptom is losing the leading " +
                "characters of anything typed within ~300ms of a pane appearing. " +
                "Read with every later capture→apply held, so no heal could have " +
                "repaired it first. Visible terminal after the reseed:\n$appliedTranscript",
            transcriptContains(appliedTranscript, TYPED_MARKER),
        )

        // ===== Step 7 — unfreeze and confirm the pane is whole =====
        // Refusing a stale snapshot must not degrade into "never heal": stop
        // intercepting, let the retry's fresh capture land, and require BOTH the
        // typed bytes and tmux's own content on the settled screen.
        ReseedApplyRaceTestGate.clearReseedApplyPark()
        awaitTranscriptByteStable()
        val finalTranscript = visibleTerminalText()
        captureViewport("issue2178-05-after-unfreeze")
        assertTrue(
            "after the refusal the re-capture must still land the typed bytes " +
                "(visible terminal:\n$finalTranscript)",
            transcriptContains(finalTranscript, TYPED_MARKER),
        )
        assertTrue(
            "the switched-to session's own content must survive the reseed as well " +
                "(visible terminal:\n$finalTranscript)",
            transcriptContains(finalTranscript, MARKER_B),
        )

        writeText(
            "issue2178-summary.txt",
            buildString {
                appendLine("journey=attach($SESSION_A) -> switch($SESSION_B) -> type inside the reseed window")
                appendLine("typed_marker=$TYPED_MARKER")
                appendLine("parked_applies_before_release=$parkedBeforeRelease")
                appendLine("apply_outcome=$applyOutcomeDetail")
                appendLine("typed_marker_present_while_frozen=true")
                appendLine("typed_marker_present_after_unfreeze=true")
                appendLine("session_marker_present_after_reseed=true")
                appendLine("reseed_window_model=production apply parked by ReseedApplyRaceTestGate (#780 model)")
                appendLine("terminal_columns=${terminalColumns()}")
            },
        )
        writeTimings()
    } }

    // ----------------------------------------------------------------
    // The reseed seam
    // ----------------------------------------------------------------

    /**
     * Bounded, hard-failing wait for a production reseed to reach its apply with
     * the seam armed. A timeout means the window never happened, which must read
     * as a FAILURE, never as a pass.
     */
    private suspend fun awaitReseedParked(label: String) {
        val parked = withTimeoutOrNull(RESEED_PARK_TIMEOUT_MS) {
            ReseedApplyRaceTestGate.awaitReseedApplyParked(atLeast = 1L)
            true
        }
        assertTrue(
            "expected $label to reach its capture→apply within ${RESEED_PARK_TIMEOUT_MS}ms " +
                "with the #2178 seam armed; it never did, so this run proved nothing",
            parked == true,
        )
        Log.i(LOG_TAG, "reseed parked: $label")
    }

    /**
     * Wait — bounded, HARD-FAILING — until the released apply has actually
     * RESOLVED, then return the screen it left behind while every later
     * capture→apply is still held.
     *
     * Two mutually exclusive observable outcomes, one per world:
     *  - REFUSED (fixed): nothing paints and the bounded retry re-captures, so the
     *    seam's parked count grows past [parkedBeforeRelease];
     *  - PAINTED (broken): the full-grid repaint lands, so the visible transcript
     *    differs from [transcriptAtType].
     *
     * Timing out on BOTH means the apply never resolved — that is a failed run,
     * not a pass, because asserting survival before the paint would be green on a
     * broken build purely by looking too early.
     */
    private fun awaitReseedApplyOutcome(
        parkedBeforeRelease: Long,
        transcriptAtType: String,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + APPLY_OUTCOME_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val parkedNow = ReseedApplyRaceTestGate.parkedReseedApplyCount()
            val refused = parkedNow > parkedBeforeRelease
            val painted = visibleTerminalText() != transcriptAtType
            if (refused || painted) {
                applyOutcomeDetail =
                    "refused=$refused painted=$painted parked=$parkedNow " +
                        "(was $parkedBeforeRelease)"
                Log.i(LOG_TAG, "reseed apply resolved: $applyOutcomeDetail")
                // Let whatever landed finish draining. Nothing else can paint
                // through a capture→apply while the seam is still armed.
                awaitTranscriptByteStable()
                return visibleTerminalText()
            }
            SystemClock.sleep(SAMPLE_INTERVAL_MS)
        }
        throw AssertionError(
            "the released reseed apply never resolved within ${APPLY_OUTCOME_TIMEOUT_MS}ms " +
                "— it neither painted (the transcript is unchanged) nor refused (no retry " +
                "parked, parked=${ReseedApplyRaceTestGate.parkedReseedApplyCount()}, was " +
                "$parkedBeforeRelease). This run proved nothing. Visible terminal:\n" +
                visibleTerminalText(),
        )
    }

    /**
     * Wait until the visible transcript stops changing for [SETTLE_MS]. Both the
     * fixed and the broken build reach this: the broken one after the stale
     * snapshot repaints, the fixed one after the refusal + re-capture. Asserting
     * survival without it could pass simply by looking too early.
     */
    private fun awaitTranscriptByteStable() {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        var previous: String? = null
        var stableSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            val transcript = visibleTerminalText()
            if (transcript != previous) {
                previous = transcript
                stableSince = now
            } else if (now - stableSince >= SETTLE_MS) {
                return
            }
            SystemClock.sleep(SAMPLE_INTERVAL_MS)
        }
        throw AssertionError(
            "the terminal transcript never settled within ${SETTLE_TIMEOUT_MS}ms after " +
                "the reseed was released; last transcript:\n${visibleTerminalText()}",
        )
    }

    // ----------------------------------------------------------------
    // Fixture seeding
    // ----------------------------------------------------------------

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

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
                name = "issue2178-key-${System.currentTimeMillis()}",
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

    private suspend fun seedTmuxSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            listOf(SESSION_A to MARKER_A, SESSION_B to MARKER_B).forEach { (name, marker) ->
                appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -s ${shellQuote(name)} " +
                        shellQuote("printf '$marker\\n'; exec sh"),
                )
            }
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed for #2178, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSessions(key: String) {
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
                    it.exec(
                        listOf(SESSION_A, SESSION_B).joinToString("\n") { name ->
                            "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
                        },
                    )
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // UI / terminal helpers
    // ----------------------------------------------------------------

    private fun waitForSessionRowVisible(sessionName: String) {
        val ready = runCatching {
            compose.waitUntil(timeoutMillis = 40_000) {
                compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            true
        }.getOrDefault(false)
        assertTrue("expected host detail to show session '$sessionName'", ready)
    }

    private fun clickTmuxBack() {
        val tags = listOf(TMUX_COMPACT_CHROME_BACK_BUTTON_TAG, TMUX_FULL_CHROME_BACK_BUTTON_TAG)
        val tag = tags.firstOrNull { candidate ->
            compose.onAllNodesWithTag(candidate, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        } ?: error("expected a production tmux Back control")
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun openMoreMenu() {
        val tags = listOf(
            TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
            TMUX_FULL_CHROME_MORE_BUTTON_TAG,
        ).filter { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        for (tag in tags) {
            compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            val opened = waitForCondition(2_000) {
                compose.onAllNodesWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            if (opened) return
        }
        error("the production More menu never exposed the Redraw action")
    }

    /**
     * A recorded agent kind would open the session on Conversation; this journey
     * is about the terminal grid, so select the Terminal page explicitly (finding
     * an offscreen pager node while Conversation is visible is a vacuous green).
     */
    private fun selectTerminalTabForJourney() {
        val hasTab = waitForCondition(15_000) {
            compose.onAllNodesWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        if (!hasTab) return
        compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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

    private fun waitForVisibleTerminal(label: String, predicate: (String) -> Boolean) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        assertTrue("expected visible terminal text for $label; got:\n$last", satisfied)
    }

    /** Bounded poll; returns `false` on timeout so every caller can hard-assert. */
    private fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(SAMPLE_INTERVAL_MS)
        }
        return runCatching(condition).getOrDefault(false)
    }

    /**
     * Types [text] into the attached pane through the SAME production
     * [InputConnection] the soft keyboard commits into — no test-only input seam.
     */
    private fun typeThroughTerminalInput(text: String) {
        text.chunked(TYPING_CHUNK_CHARS).forEach { chunk ->
            terminalInputConnection().commitText(chunk, 1)
            SystemClock.sleep(TYPING_CHUNK_PAUSE_MS)
        }
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = requireNotNull(activity.window.decorView.findTerminalView()) {
                "TerminalView was not found"
            }
            view.requestFocus()
            connection = view.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
    }

    /** Wrap-tolerant `contains` over the visible transcript at the REAL width. */
    private fun transcriptContains(transcript: String, substring: String): Boolean =
        TerminalTextMatcher.containsWrapTolerant(
            transcript = transcript,
            substring = substring,
            terminalCols = terminalColumns(),
        )

    private fun terminalColumns(): Int {
        var columns = 0
        compose.activityRule.scenario.onActivity { activity ->
            columns = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.mColumns
                ?: 0
        }
        return columns
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

    // ----------------------------------------------------------------
    // Artifacts
    // ----------------------------------------------------------------

    private val timings = mutableListOf<String>()

    /** How the released apply resolved; written to the run's summary artifact. */
    private var applyOutcomeDetail: String = "unresolved"

    private fun recordTiming(label: String, startElapsedRealtimeMs: Long) {
        val elapsed = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        timings.add("$label: ${elapsed}ms")
        Log.i(LOG_TAG, "timing $label=${elapsed}ms")
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
                ?: activity.findViewById<View>(android.R.id.content)
                ?: activity.window.decorView
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE2178_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE2178_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE2178_TIMINGS ${file.absolutePath}")
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
        const val LOG_TAG: String = "Issue2178Reseed"
        const val DEVICE_DIR_NAME: String = "issue2178-typed-echo-survives-reseed"

        const val SESSION_A: String = "issue2178-a"
        const val SESSION_B: String = "issue2178-b"
        const val MARKER_A: String = "ISSUE2178-READY-A"
        const val MARKER_B: String = "ISSUE2178-READY-B"

        /**
         * The typed marker. Deliberately front-loaded with a distinctive head, so
         * the reported failure mode (the LEADING characters eaten) is what the
         * wrap-tolerant `contains` misses.
         */
        const val TYPED_MARKER: String = "ISSUE2178-TYPED-HEAD-SURVIVES"

        const val TYPING_CHUNK_CHARS: Int = 8
        const val TYPING_CHUNK_PAUSE_MS: Long = 20
        const val SAMPLE_INTERVAL_MS: Long = 50

        /** How long a production reseed may take to reach its apply. */
        const val RESEED_PARK_TIMEOUT_MS: Long = 60_000

        /** How long the pane may take to echo what was typed into it. */
        const val ECHO_TIMEOUT_MS: Long = 30_000

        /** Byte-stable settle after the reseed is released. */
        const val SETTLE_MS: Long = 1_500
        const val SETTLE_TIMEOUT_MS: Long = 30_000

        /**
         * How long the released apply may take to resolve into one of its two
         * observable outcomes (refused-and-re-parked, or painted).
         */
        const val APPLY_OUTCOME_TIMEOUT_MS: Long = 30_000
    }
}
