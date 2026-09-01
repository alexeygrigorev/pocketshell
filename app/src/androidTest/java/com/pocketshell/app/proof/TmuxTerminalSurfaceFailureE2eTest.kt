package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.insets.SyntheticImeStage
import com.pocketshell.app.proof.signals.assertNodeFullyAboveImeOrKeyboard
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.tmux.TERMINAL_HOTKEYS_LAUNCHER_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_BREADCRUMB_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TMUX_FULL_BREADCRUMB_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_RECREATE_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import com.pocketshell.app.proof.signals.captureViewToBitmap

/**
 * Issue #423 — terminal-surface failures must be distinguished from SSH
 * disconnects.
 *
 * The bug (re-confirmed by the maintainer 2026-06-03 inside a Codex
 * session): after a long dictated prompt, opening the soft keyboard makes
 * the terminal start redrawing, then it freezes / jumps to the start of
 * the message and never returns, then the app shows "reconnecting" and is
 * unusable until force-restart. Crucially the SSH/tmux transport is still
 * alive — a local terminal-surface / IME / render failure is being
 * misclassified as a connection failure.
 *
 * This connected E2E class drives two independently seeded production
 * `MainActivity` host-tap → tmux journeys to a live session on the
 * deterministic Docker `agents` fixture (port 2222, already brought up by the
 * nightly-extensive workflow). Each journey then:
 *
 *  1. Types a large multi-line prompt into the pane (the dictation-sized
 *     input that precedes the failure in the field report).
 *  2. Opens the soft keyboard via the production show-keyboard chip.
 *  3. Drives a burst of terminal-surface failures through the SAME
 *     production seam the screen wires up
 *     ([TmuxSessionViewModel.reportTerminalSurfaceFailure]) — i.e. the
 *     callback `TerminalSurface(onLocalTerminalError = ...)` invokes when
 *     the embedded Termux view throws during IME/resize/render. We cannot
 *     force a real native render crash deterministically across emulator
 *     images, so we exercise the exact recovery/classification path the
 *     real exception would take.
 *
 * Acceptance assertions (the regression this test pins):
 *
 *  - The SSH/tmux transport stays Connected the entire time — the failure
 *    burst must NOT flip [TmuxSessionViewModel.connectionStatus] to
 *    Reconnecting/Failed and must NOT show the in-session "Disconnected …
 *    Tap Reconnect" band.
 *  - The recovery storm stops at an actionable terminal-surface error
 *    state with a "Recreate terminal" control instead of thrashing forever
 *    or entering an indefinite reconnect loop.
 *  - The navigation journey uses the production Back control while that
 *    actionable error is still active, reaches the host session list, and
 *    proves its seeded session row is fully visible and actionable. It ends
 *    there, before any Recreate/recovery can make the navigation oracle
 *    vacuous.
 *  - The recovery journey separately taps "Recreate terminal", rebuilds the
 *    surface, and re-attaches to the still-live tmux pane, restoring a usable
 *    terminal — no SSH reconnect, no force-restart.
 *
 * # CI compatibility
 *
 * Uses the default `agents` Docker service on port 2222, brought up by the
 * nightly-extensive workflow for sibling connected tests
 * (`TmuxBracketedPasteDictationE2eTest`, `TmuxSessionWindowNavigationE2eTest`).
 * No extra fixture or port is required.
 *
 * # Issue #2126 — the two environment races this class used to lose
 *
 * This class sat in the **gating** shard-0 journey suite and failed at roughly a
 * 17% per-run rate, with two signatures. Both are fixed here; neither was a
 * product regression in the code under test, and neither was fixed by widening a
 * timeout or a retry budget (#2139 rules both out explicitly).
 *
 * ## Signature 1 — `expected visible terminal text for large prompt echo within
 * 180000ms`. NOT a slow device: BYTES WERE LOST.
 *
 * The captured failure (run 31725258187, shard 0, cold boot 2, attempt 1) is not
 * an empty transcript. It is the whole typed prompt, shifted left by exactly two
 * columns:
 *
 * ```
 * ISSUE423-READY
 * ~ $ SUE423-PROMPT-HEAD detail line 0 about the cable world s   <- 60 cols
 * ession and the long dictated codex prompt that precedes the ke <- 62 cols
 * ```
 *
 * The pane is 62 columns (`tmux-client-size-known cols=62 rows=58`); every
 * continuation row is 61–62 and only the first is 60. So `ISSUE423-PROMPT-HEAD`
 * arrived as `SUE423-PROMPT-HEAD` — the leading `IS` never reached the screen —
 * and `contains(PROMPT_HEAD)` was therefore **unsatisfiable from ~200 ms in**.
 * The 180 s was spent waiting for text that could never appear.
 *
 * The device log names the race. In EVERY run of this class, passing or failing,
 * the ordering is the same and the test loses a coin flip inside a ~250–460 ms
 * window:
 *
 * ```
 * 19:46:03.074  Issue423SurfaceFail: timing session-row-tap->terminal-visible   <- test resumes
 * 19:46:03.355  PsTmuxReconnect: tmux-refresh-client-size-ok  cols=62 rows=58
 * 19:46:03.356  PsTmuxReconnect: tmux-reattach-full-viewport-reseed pane=%1 partialBlank=true
 * ```
 *
 * The reveal gate paints a healed capture (which already contains the seed
 * marker) BEFORE the attach finishes, so `waitForVisibleTerminal("initial
 * marker")` returns while the production attach is still mid-flight. The attach
 * then completes and issues `reseedActivePaneForReattach`, whose `capture-pane`
 * round-trip snapshots the server grid and REPLACES the local screen with it.
 * Any byte echoed locally after that snapshot was taken, but before it is
 * applied, is discarded. Typing into that window is a coin flip on the SSH
 * round-trip; two characters lost is what a lost flip looks like.
 *
 * The corrective is a real precondition, not a sleep:
 * [awaitTerminalDurablyRendersTypedInput] types a uniquely-numbered probe through
 * the SAME production `InputConnection` and requires the transcript to contain it
 * AND to be byte-identical for a settle window before the journey types anything
 * load-bearing. That condition is exactly the absence of the defect: a reseed can
 * only drop characters when its snapshot DIFFERS from the local screen, and a
 * differing snapshot necessarily mutates `transcriptText`. A clobbered probe is
 * retyped, and the surviving-probe count is written to the artifact so a CI run
 * says how many reseeds it absorbed. It hard-fails if the pane never becomes
 * durably writable — there is no skip.
 *
 * `waitForVisibleTerminal` additionally aborts the instant the wait becomes
 * *unsatisfiable* (the prompt's tail is on screen but its head is not), so a
 * residual occurrence costs ~seconds and reports "the echo lost its leading
 * characters" instead of burning 180 s and reporting a timeout that misdescribes
 * its own cause.
 *
 * **The underlying reseed-vs-echo race is a real, separate product defect** (a
 * user typing inside the first second of an attach can lose the leading
 * characters). It lives in `TmuxSessionViewModel.reseedActivePaneForReattach`,
 * which is owned by another lane, and is reported separately rather than patched
 * from here. Nothing about #423's regression requires typing before the attach
 * has settled.
 *
 * ## Signature 2 — `one production Show keyboard tap must make the real IME
 * visible`. Converted to the #780 synthetic-inset model.
 *
 * The keyboard is CONTEXT for this journey, not its subject: the surface failure
 * is injected through [TmuxSessionViewModel.reportTerminalSurfaceFailure], so
 * what this class needs is the keyboard-UP *chrome and geometry*, not proof that
 * the CI swiftshader AVD can raise a real system IME. Depending on the real IME
 * made this class a second copy of a contract that is already owned, per-push, by
 * `ShowKeyboardChipE2eTest` (registered in `scripts/ci-journey-suite.sh`), which
 * establishes keyboard-down, taps exactly once, and hard-names the observed state
 * on failure.
 *
 * So per F3 and #2139, the real-IME dependence is DELETED (D22 hard cut — no
 * flag, no fallback) and replaced by [enterKeyboardUpChromeWithSyntheticIme],
 * which dispatches a synthetic `Type.ime()` inset through the audited
 * [SyntheticImeStage] and then HARD-asserts the PRODUCTION screen actually
 * entered keyboard-up chrome. The read-back is not "an inset is non-zero": it is
 * the observable behaviour change — the #184 chrome compaction
 * (`chromeCompressed = isImeVisible` swaps the full breadcrumb for the compact
 * one), plus the IME-only `TERMINAL_HOTKEYS_LAUNCHER_TAG` overlay being present,
 * contained, and fully above the synthetic keyboard top.
 *
 * This is strictly MORE than the deleted code checked — the old version measured
 * the chip's containment with the keyboard DOWN and never measured any control in
 * the keyboard-up layout at all.
 */
@RunWith(AndroidJUnit4::class)
class TmuxTerminalSurfaceFailureE2eTest {
    private lateinit var trustedHostKeySha256: String

    // Issue #788/#848: launch-owned rule so the Compose clock and the real
    // TerminalView interop child share one MainActivity. Seed the Docker tmux
    // session and Room host before Compose launches that activity.
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private lateinit var artifactScenario: String

    /** Issue #2126: the audited #780/#1821 synthetic-IME harness. */
    private val imeStage by lazy { SyntheticImeStage(compose) }

    /** Issue #2126 evidence: probes typed before the pane held one durably. */
    private var inputReadinessProbes: Int = 0

    /** Issue #2126 evidence: probes an in-flight reattach reseed clobbered. */
    private var inputReadinessProbesDiscarded: Int = 0

    private suspend fun seedBeforeLaunch() {
        fixtureKey = readFixtureKey()
        trustedHostKeySha256 = waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedTmuxSession(fixtureKey)
        hostRowTag = seedDockerHost(fixtureKey, "Issue423 Surface")
        forceFlatHostDetailViewMode()
    }

    @After
    fun teardown() {
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupSeededSession(fixtureKey) } }
        }
    }

    @Test
    fun keyboardSurfaceFailureAfterLargePromptRecoversWithoutSshReconnect() { runBlocking<Unit> {
        artifactScenario = RECOVERY_ARTIFACT_SCENARIO
        driveToActionableSurfaceError()

        // ===== Recovery — Recreate the terminal surface =====
        val recreateStart = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TMUX_TERMINAL_SURFACE_RECREATE_TAG, useUnmergedTree = true)
            .performClick()
        // The error state clears and a fresh TerminalView re-attaches to the
        // still-live tmux pane.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(TMUX_TERMINAL_SURFACE_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        waitForTerminalViewAttached()
        // The recreated surface replays the live pane buffer and reattaches to
        // the still-live tmux pane. The most recent live content is the large
        // prompt typed before the failure (the initial ISSUE423-READY marker
        // has scrolled out of the visible viewport behind that prompt), so the
        // proof that we reattached to the SAME live pane is that the prompt
        // head reappears in the recreated surface.
        waitForVisibleTerminal("recovered live pane") { transcriptContains(it, PROMPT_HEAD) }
        recordTiming("recreate-tap->terminal-reattached", recreateStart)
        captureViewport("issue423-05-recovered")

        assertTrue(
            "recreate must not reconnect SSH — transport stays Connected " +
                "(observed ${currentConnectionStatus()})",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        writeTimings()
    } }

    @Test
    fun actionableSurfaceErrorKeepsProductionBackNavigationUsable() { runBlocking<Unit> {
        artifactScenario = NAVIGATION_ARTIFACT_SCENARIO
        driveToActionableSurfaceError()

        // This is intentionally the terminal step: the Back action and real
        // row assertion happen while the actionable error is still active,
        // before any Recreate/recovery can clear the reported state.
        navigateFromActiveSurfaceErrorToHostDetail()
        writeTimings()
    } }

    private fun driveToActionableSurfaceError() {
        // ===== Step 1 — Attach to the seeded session =====
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // The host tap lands on the per-host FolderList screen; the seeded
        // tmux session appears in its session list. Wait for the screen and
        // the session row together (same pattern as TmuxSessionSwitchE2eTest)
        // before tapping to attach.
        waitForSessionRowVisible()
        val sessionRowTapStart = SystemClock.elapsedRealtime()
        compose.onNodeWithText(SESSION_NAME).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        selectTerminalTabForJourney()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("initial marker") { it.contains(INITIAL_MARKER) }
        recordTiming("session-row-tap->terminal-visible", sessionRowTapStart)

        // ===== Step 2 — Prove the pane DURABLY renders typed input =====
        // Issue #2126: the reveal gate paints a healed capture before the
        // production attach finishes, so the marker being visible does NOT mean
        // the terminal is ready to be typed into — the attach's
        // `tmux-reattach-full-viewport-reseed` is still in flight and, when it
        // lands, replaces the local screen with a server snapshot taken before
        // the freshly-echoed bytes. This gate is what stops the load-bearing
        // prompt below from being typed into that window. See the class KDoc.
        awaitTerminalDurablyRendersTypedInput()
        captureViewport("issue423-01-attached")

        // ===== Step 3 — Type a large multi-line prompt =====
        // The field report precedes the failure with a long dictated
        // prompt; reproduce the size so the resize/redraw path is under
        // load when the surface fails.
        sendLargePromptThroughTerminalInput()
        waitForVisibleTerminal(
            label = "large prompt echo",
            // Issue #2126: the echo completing WITHOUT its head is a lost-byte
            // failure, not a slow one, and no further waiting can satisfy the
            // predicate. Abort at once and say so, instead of spending the whole
            // 180 s budget and reporting a timeout that misdescribes its cause.
            //
            // BOTH sides use the audited wrap-tolerant matcher against the pane's
            // REAL column count. The prompt is far longer than the ~62-column
            // grid, so `TerminalEmulator` inserts soft-wrap newlines through the
            // middle of the tail phrase; a raw `contains` would never see it and
            // the abort would be dead code. (The head is matched the same way for
            // the same reason — at a different prompt column it can straddle the
            // margin too, and a raw `contains` would then spend the whole budget
            // on text that IS on screen.)
            unsatisfiableWhen = { transcript ->
                transcriptContains(transcript, PROMPT_TAIL) &&
                    !transcriptContains(transcript, PROMPT_HEAD)
            },
            unsatisfiableReason = "the pane echoed the prompt's tail but its " +
                "leading characters never reached the screen (issue #2126 " +
                "reseed-vs-echo byte loss); waiting longer cannot satisfy this",
        ) { transcript ->
            transcriptContains(transcript, PROMPT_HEAD)
        }
        captureViewport("issue423-02-large-prompt")

        // ===== Step 4 — Put the screen in the keyboard-UP state =====
        // Hard-prove the causal field state before touching the failure seam.
        // Issue #2126: via the #780 synthetic-inset model rather than the real
        // system IME — see the class KDoc for why, and for the read-back that
        // makes it non-vacuous.
        enterKeyboardUpChromeWithSyntheticIme()

        // Sanity: transport is Connected before we fail the surface.
        assertTrue(
            "expected the transport to be Connected before forcing a surface failure",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // ===== Step 5 — Drive a terminal-surface recovery storm =====
        // Same seam the screen's `onLocalTerminalError` callback uses when
        // the embedded Termux view throws during IME/resize/render. A
        // burst past the recovery-storm threshold must trip the actionable
        // error state without ever touching SSH/tmux.
        val paneId = currentPaneId()
        val failureBurstStart = SystemClock.elapsedRealtime()
        repeat(SURFACE_FAILURE_BURST) {
            invokeOnTmuxViewModel { vm ->
                vm.reportTerminalSurfaceFailure(
                    paneId,
                    RuntimeException("issue423 simulated IME/redraw storm"),
                )
            }
            SystemClock.sleep(30)
        }

        // The actionable error state must be visible — NOT a reconnect band.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(TMUX_TERMINAL_SURFACE_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Issue #2126: measure the actionable error in the state the field
        // report describes — keyboard UP. The surface-failure burst tears the
        // TerminalView out and rebuilds the column, which can trigger a window
        // traversal that re-applies the REAL (keyboard-down) insets, so
        // re-establish and re-assert the keyboard-up chrome rather than assuming
        // it survived. Before #2126 these two containment checks were measured
        // in whatever keyboard state the real IME happened to be in.
        reassertKeyboardUpChrome("after the surface-failure burst")
        compose.assertNodeFullyWithinRoot(
            TMUX_TERMINAL_SURFACE_ERROR_TAG,
            useUnmergedTree = true,
        )
        compose.assertNodeFullyWithinRoot(
            TMUX_TERMINAL_SURFACE_RECREATE_TAG,
            useUnmergedTree = true,
        )
        recordTiming("failure-burst->surfaceError-visible", failureBurstStart)
        captureViewport("issue423-04-surface-error")

        assertFalse(
            "a local surface failure must NOT show the SSH disconnect/reconnect band",
            compose.onAllNodesWithText("Tap Reconnect", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                compose.onAllNodesWithText("Disconnected from", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
        )
        assertTrue(
            "a local surface failure must keep the SSH/tmux transport Connected " +
                "(observed ${currentConnectionStatus()})",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    // ----------------------------------------------------------------
    // ViewModel access (production seam used by the screen)
    // ----------------------------------------------------------------

    private fun tmuxViewModel(activity: MainActivity): TmuxSessionViewModel {
        // MainActivity holds the VM via `by viewModels()`, whose backing field
        // is a `Lazy` named `tmuxSessionViewModel$delegate`, so reflecting the
        // property name directly fails. Resolve the identical instance through
        // the activity's own ViewModelStore instead — `by viewModels()` and
        // `ViewModelProvider(activity)` share that store and default factory,
        // so they return the same cached TmuxSessionViewModel.
        return ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
    }

    private fun invokeOnTmuxViewModel(block: (TmuxSessionViewModel) -> Unit) {
        compose.activityRule.scenario.onActivity { activity -> block(tmuxViewModel(activity)) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = tmuxViewModel(activity).connectionStatus.value
        }
        return status
    }

    private fun currentPaneId(): String {
        var paneId = ""
        compose.activityRule.scenario.onActivity { activity ->
            paneId = tmuxViewModel(activity).panes.value.firstOrNull()?.paneId.orEmpty()
        }
        check(paneId.isNotBlank()) { "expected at least one tmux pane to be attached" }
        return paneId
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
                name = "issue423-key-${System.currentTimeMillis()}",
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
                    trustedHostKeySha256 = trustedHostKeySha256,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$INITIAL_MARKER\\n'; exec sh"),
            )
            appendLine("tmux set-option -t ${shellQuote(SESSION_NAME)} @ps_agent_kind codex")
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
            "expected tmux session seeding to succeed for #423, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSession(key: String) {
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

    // ----------------------------------------------------------------
    // Terminal / IME helpers
    // ----------------------------------------------------------------

    private fun waitForSessionRowVisible() {
        // The host tap lands on the per-host detail screen (FolderList) which
        // lists discovered tmux sessions by name. With the flat view forced
        // before launch (forceFlatHostDetailViewMode), the seeded session
        // renders as a top-level tappable row rather than nested under a
        // collapsed folder group, so polling for the session-name node directly
        // matches. Same wait pattern as the passing TmuxSessionSwitchE2eTest.
        val ready = runCatching {
            compose.waitUntil(timeoutMillis = 40_000) {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            true
        }.getOrDefault(false)
        if (!ready) {
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                .getOrDefault("<unavailable>")
            writeText("diag-folderlist.txt", tree)
            // Mirror the node tree to logcat so the diagnostic survives an
            // APK reinstall wiping external files (parallel AVD runs).
            Log.w(LOG_TAG, "session '$SESSION_NAME' not visible; node tree follows")
            tree.lineSequence().forEach { line -> Log.w(LOG_TAG, "NODE| $line") }
        }
        assertTrue("expected host detail to show session '$SESSION_NAME'", ready)
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

    /**
     * The recorded Codex kind defaults to Conversation. The surface-failure
     * oracle and viewport must exercise the selected Terminal page itself;
     * finding an offscreen pager node while Conversation is visible is a
     * vacuous green.
     */
    private fun selectTerminalTabForJourney() {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Waits until [predicate] holds over the visible terminal transcript.
     *
     * Issue #2126 adds [unsatisfiableWhen]: a predicate over the same transcript
     * that identifies a state from which [predicate] can NEVER become true. When
     * it holds, the wait fails immediately and reports [unsatisfiableReason]
     * instead of running out the (deliberately generous, 180 s on CI) budget and
     * reporting a timeout. That distinction is the whole point — the observed
     * shard-0 failure was byte LOSS being reported as slowness, which sent triage
     * to the emulator's frame rate rather than to the attach race that caused it.
     */
    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        unsatisfiableWhen: (String) -> Boolean = { false },
        unsatisfiableReason: String = "",
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        var unsatisfiable = false
        val startedAt = SystemClock.elapsedRealtime()
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                if (predicate(last)) return@waitUntil true
                unsatisfiable = unsatisfiableWhen(last)
                unsatisfiable
            }
            true
        }.getOrDefault(false)
        if (unsatisfiable && !predicate(last)) {
            throw AssertionError(
                "visible terminal text for $label can no longer become correct: " +
                    "$unsatisfiableReason. Aborted after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms of a " +
                    "${timeoutMillis}ms budget; got:\n$last",
            )
        }
        assertTrue(
            "expected visible terminal text for $label within ${timeoutMillis}ms; got:\n$last",
            satisfied && predicate(last),
        )
    }

    /**
     * Issue #2126 — the precondition the large-prompt step needs and never had.
     *
     * The reveal gate paints a healed `capture-pane` (which already carries the
     * seed marker) BEFORE the production attach finishes, so
     * `waitForVisibleTerminal("initial marker")` returns while
     * `reseedActivePaneForReattach` is still in flight. When that reseed's own
     * `capture-pane` result lands it REPLACES the local screen with a server
     * snapshot taken earlier, silently discarding any byte echoed in between.
     *
     * This gate proves the pane is past that, using only the production surface —
     * text typed through the real `InputConnection`, read back off the real
     * `TerminalEmulator` transcript:
     *
     *  1. type a uniquely-numbered probe;
     *  2. require the transcript to CONTAIN it and to be BYTE-IDENTICAL for
     *     [INPUT_SETTLE_MS].
     *
     * Step 2 is exactly the absence of the defect, not a proxy for it: a reseed
     * can only drop characters when its snapshot DIFFERS from the local screen,
     * and a differing snapshot necessarily mutates `transcriptText`. A probe that
     * is clobbered (its leading characters eaten, so `contains` goes false) is
     * simply retyped with a fresh number, and the discard count is recorded for
     * the artifact so a run reports how many reseeds it absorbed.
     *
     * Hard-fails when the pane never becomes durably writable. There is no
     * `assumeTrue` and no silent degradation (F3).
     */
    private fun awaitTerminalDurablyRendersTypedInput() {
        val deadline = SystemClock.elapsedRealtime() + INPUT_READINESS_TIMEOUT_MS
        var attempts = 0
        var lastTranscript = visibleTerminalText()
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts += 1
            val probe = "$INPUT_PROBE_PREFIX$attempts]"
            typeThroughTerminalInput(probe)

            var stableSince = SystemClock.elapsedRealtime()
            var previous: String? = null
            val cycleDeadline = minOf(
                SystemClock.elapsedRealtime() + INPUT_PROBE_CYCLE_MS,
                deadline,
            )
            while (SystemClock.elapsedRealtime() < cycleDeadline) {
                val now = SystemClock.elapsedRealtime()
                val transcript = visibleTerminalText()
                lastTranscript = transcript
                if (transcript != previous) {
                    previous = transcript
                    stableSince = now
                } else if (now - stableSince >= INPUT_SETTLE_MS) {
                    // Wrap-tolerant: a retyped probe lands further along the
                    // shell line, so probe N can straddle the right margin. A raw
                    // `contains` would read that as "clobbered" and retype
                    // forever, inflating the discard count with a fixture
                    // artefact. The cheap raw check short-circuits the common
                    // case so the extra main-thread hop is not paid per sample.
                    if (transcript.contains(probe) || transcriptContains(transcript, probe)) {
                        inputReadinessProbes = attempts
                        inputReadinessProbesDiscarded = attempts - 1
                        Log.i(
                            LOG_TAG,
                            "issue2126 terminal-input-readiness probes=$attempts " +
                                "discarded=${attempts - 1}",
                        )
                        return
                    }
                    // Settled WITHOUT the probe: a reseed replaced the screen and
                    // took the probe with it. Retype under a fresh number.
                    break
                }
                SystemClock.sleep(INPUT_SAMPLE_INTERVAL_MS)
            }
        }
        throw AssertionError(
            "the tmux pane never durably rendered typed input within " +
                "${INPUT_READINESS_TIMEOUT_MS}ms (issue #2126): $attempts probe(s) " +
                "typed through the production InputConnection, none of which both " +
                "appeared in the transcript and survived a ${INPUT_SETTLE_MS}ms " +
                "byte-stable window. Last transcript:\n$lastTranscript",
        )
    }

    private fun sendLargePromptThroughTerminalInput() {
        // A multi-line, dictation-sized prompt typed into the pane. The
        // exact content does not matter — what matters is that the surface
        // is under a large redraw load when the failure burst lands.
        val prompt = buildString {
            append(PROMPT_HEAD)
            repeat(12) { line ->
                append(" $PROMPT_DETAIL_PREFIX $line about the cable world session and ")
                append("the long dictated codex prompt that precedes the keyboard tap")
            }
        }
        typeThroughTerminalInput(prompt)
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

    /**
     * Issue #2126 — puts the production session screen into the keyboard-UP
     * state the field report describes, WITHOUT depending on the CI swiftshader
     * AVD's ability to raise a real system input-method window.
     *
     * The old body tapped the production Show-keyboard chip once and required the
     * real IME to appear and hold a non-zero inset. That is a genuine contract,
     * but it is `ShowKeyboardChipE2eTest`'s contract (also gate-wired per push),
     * not this journey's: here the surface failure is injected through
     * [TmuxSessionViewModel.reportTerminalSurfaceFailure], so all this journey
     * needs from the keyboard is the keyboard-up chrome and geometry. Depending
     * on the real IME made this class fail with
     * `Ignoring showSoftInput() as view=…TerminalView… is not served` whenever the
     * input connection had not been established by the time the single tap fired.
     * D22 hard cut: the real-IME path is deleted, not flagged off.
     *
     * The read-back is deliberately a PRODUCTION BEHAVIOUR change, not "the inset
     * we dispatched reads back non-zero":
     *
     *  - keyboard DOWN, `SHOW_KEYBOARD_CHIP_TAG` is present and contained;
     *  - after the dispatch, the #184 chrome compaction must have happened —
     *    `TmuxSessionScreen` derives `chromeCompressed = isImeVisible` and swaps
     *    the full breadcrumb for the compact one — and the IME-only
     *    `TERMINAL_HOTKEYS_LAUNCHER_TAG` overlay must be present, contained, and
     *    fully above the synthetic keyboard top.
     *
     * The production screen reads the inset through `rememberHostImeBottomPx`, an
     * `OnApplyWindowInsetsListener` on the host root, which is exactly what
     * [SyntheticImeStage.dispatch] drives; the breadcrumb swap is the same signal
     * `Issue796ImeRecompositionProofTest` uses to prove a synthetic inset reached
     * THIS screen.
     *
     * Named mutation, RUN not argued: deleting the `imeStage.dispatch(...)` line
     * turns both `@Test`s red here (2/2), with the breadcrumb assertion naming
     * `compact=0 full=1`.
     *
     * A first draft of this used "the Show-keyboard chip must VANISH" as the
     * read-back, reasoning that `ReservedTmuxTerminalBottomBand` wraps it in
     * `clearAndSetSemantics {}` and declines to place it when `isImeVisible`. The
     * emulator falsified that: with the compaction demonstrably applied
     * (`compact_breadcrumb_nodes=1 full_breadcrumb_nodes=0`) the chip's semantics
     * node is still present (`show_keyboard_chip_nodes=1`). Both counts are kept
     * in the artifact as diagnostics, and neither the chip count nor the launcher
     * count is used as the keyboard-state oracle.
     */
    private fun enterKeyboardUpChromeWithSyntheticIme() {
        compose.waitUntil(timeoutMillis = KEYBOARD_CHROME_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.assertNodeFullyWithinRoot(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true)

        imeStage.startEdgeToEdge()
        // A synthetic boundary must never be mixed with a real keyboard, and this
        // is a hard assertion, not a skip.
        imeStage.hideRealImeAndAssertHidden(
            "Issue #2126: the keyboard-up state for the #423 surface-failure " +
                "journey is synthetic (#780 model).",
        )

        val keyboardUpStart = SystemClock.elapsedRealtime()
        applySyntheticKeyboardUp()
        recordTiming("synthetic-ime-dispatch->keyboard-up-chrome", keyboardUpStart)

        writeText(
            "issue423-03-keyboard-up-ime.txt",
            buildString {
                appendLine("keyboard_up_model=synthetic-inset (issue #780 / #2126)")
                appendLine("real_system_ime_used=false")
                appendLine("synthetic_ime_bottom_px=${imeStage.imeBottomPx}")
                appendLine("synthetic_nav_bar_bottom_px=${imeStage.navBarBottomPx}")
                appendLine("synthetic_keyboard_top_px=${syntheticKeyboardTopPx()}")
                appendLine("compact_breadcrumb_nodes=${nodeCount(TMUX_COMPACT_BREADCRUMB_TAG)}")
                appendLine("full_breadcrumb_nodes=${nodeCount(TMUX_FULL_BREADCRUMB_TAG)}")
                appendLine("show_keyboard_chip_nodes=${nodeCount(SHOW_KEYBOARD_CHIP_TAG)}")
                appendLine("ime_hotkeys_launcher_nodes=${nodeCount(TERMINAL_HOTKEYS_LAUNCHER_TAG)}")
                appendLine("input_readiness_probes=$inputReadinessProbes")
                appendLine("input_readiness_probes_discarded=$inputReadinessProbesDiscarded")
            },
        )
        captureFullDevice("issue423-03-keyboard-up")
    }

    /**
     * Re-dispatches the synthetic keyboard-up inset and re-asserts the production
     * chrome, so a later measurement is taken in the reported state rather than
     * in whatever state a window traversal left behind.
     */
    private fun reassertKeyboardUpChrome(stage: String) {
        imeStage.hideRealImeAndAssertHidden(
            "Issue #2126: the synthetic keyboard-up state $stage must not be " +
                "mixed with a real IME window.",
        )
        applySyntheticKeyboardUp(stage)
    }

    private fun applySyntheticKeyboardUp(stage: String = "before the surface-failure burst") {
        imeStage.dispatch(imeBottomPx = imeStage.imeBottomPx, requireExtraWindowRoot = false)

        // Read-back 1 — the #184 chrome compaction. This is the assertion that
        // fails if the dispatch never reached the production composition, and it
        // is what makes everything measured afterwards non-vacuous.
        //
        // `TmuxSessionScreen` derives `chromeCompressed = isImeVisible` (which
        // comes from the real `rememberHostImeBottomPx` decor listener that
        // `dispatch` drives) and swaps the FULL breadcrumb for the COMPACT one.
        // A test cannot fake that swap: it is production state, not a value we
        // wrote. Same signal `Issue796ImeRecompositionProofTest` uses to prove a
        // synthetic inset reached this exact screen.
        val compacted = waitForCondition(KEYBOARD_CHROME_TIMEOUT_MS) {
            nodeCount(TMUX_COMPACT_BREADCRUMB_TAG) > 0 && nodeCount(TMUX_FULL_BREADCRUMB_TAG) == 0
        }
        assertTrue(
            "the synthetic ime() inset must drive the PRODUCTION keyboard-up " +
                "state $stage: TmuxSessionScreen sets chromeCompressed = " +
                "isImeVisible and swaps the full breadcrumb for the #184 compact " +
                "one, so '$TMUX_COMPACT_BREADCRUMB_TAG' must be present and " +
                "'$TMUX_FULL_BREADCRUMB_TAG' gone; observed compact=" +
                "${nodeCount(TMUX_COMPACT_BREADCRUMB_TAG)} full=" +
                "${nodeCount(TMUX_FULL_BREADCRUMB_TAG)}. " +
                "syntheticImeBottomPx=${imeStage.imeBottomPx}",
            compacted,
        )

        // Read-back 2 — the IME-only launcher overlay is present, contained, and
        // reachable ABOVE the synthetic keyboard (F2/F3 containment, never a bare
        // assertIsDisplayed()).
        val launcherPresent = waitForCondition(KEYBOARD_CHROME_TIMEOUT_MS) {
            nodeCount(TERMINAL_HOTKEYS_LAUNCHER_TAG) > 0
        }
        assertTrue(
            "the IME-only terminal hotkeys launcher must be composed in the " +
                "keyboard-up state $stage; observed " +
                "${nodeCount(TERMINAL_HOTKEYS_LAUNCHER_TAG)} node(s)",
            launcherPresent,
        )
        compose.assertNodeFullyWithinRoot(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
        compose.assertNodeFullyAboveImeOrKeyboard(
            tag = TERMINAL_HOTKEYS_LAUNCHER_TAG,
            keyboardTopPx = syntheticKeyboardTopPx(),
            useUnmergedTree = true,
        )
    }

    /**
     * Top edge of the synthetic keyboard in `boundsInRoot` coordinates. The
     * window is not resized by the keyboard (#887 `SOFT_INPUT_ADJUST_NOTHING`),
     * so the keyboard intrudes into the root by `ime - navBars`.
     */
    private fun syntheticKeyboardTopPx(): Float {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val intrusion = (imeStage.imeBottomPx - imeStage.navBarBottomPx).coerceAtLeast(0)
        return root.bottom - intrusion
    }

    private fun nodeCount(tag: String): Int =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /** Bounded poll; returns `false` on timeout so every caller can hard-assert. */
    private fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(INPUT_SAMPLE_INTERVAL_MS)
        }
        return runCatching(condition).getOrDefault(false)
    }

    private fun navigateFromActiveSurfaceErrorToHostDetail() {
        // These are the load-bearing preconditions: Back is clicked while the
        // actionable error is visibly selected, and no recovery action has run.
        compose.assertNodeFullyWithinRoot(
            TMUX_TERMINAL_SURFACE_ERROR_TAG,
            useUnmergedTree = true,
        )
        compose.assertNodeFullyWithinRoot(
            TMUX_TERMINAL_SURFACE_RECREATE_TAG,
            useUnmergedTree = true,
        )
        val backTag = listOf(
            TMUX_COMPACT_CHROME_BACK_BUTTON_TAG,
            TMUX_FULL_CHROME_BACK_BUTTON_TAG,
        ).firstOrNull { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        } ?: error("expected a production tmux Back control during the active surface error")

        compose.assertNodeFullyWithinRoot(backTag, useUnmergedTree = true)
        val backStart = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(backTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        waitForSessionRowVisible()
        assertNamedSessionRowFullyWithinRootAndActionable()
        recordTiming("surface-error-back-tap->host-session-list", backStart)
        captureFullDevice("issue423-05-navigated-from-active-error")
        assertTrue(
            "error-state navigation must retain the connected transport " +
                "(observed ${currentConnectionStatus()})",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun assertNamedSessionRowFullyWithinRootAndActionable() {
        // FolderList may render the named session as either a flat row or a
        // revealed tree child. The user-facing contract is the merged row with
        // this exact session name and click action — the same production node
        // used to enter the session at the start of the journey.
        val row = compose.onNodeWithText(SESSION_NAME)
            .assertHasClickAction()
            .fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode()
        val rowBounds = row.boundsInRoot
        val rootBounds = root.boundsInRoot
        assertTrue(
            "named production session row must be fully within the host-list viewport; " +
                "rowBounds=$rowBounds rootBounds=$rootBounds",
            rowBounds.left >= rootBounds.left &&
                rowBounds.top >= rootBounds.top &&
                rowBounds.right <= rootBounds.right &&
                rowBounds.bottom <= rootBounds.bottom,
        )
    }

    /**
     * Wrap-tolerant `contains` over the visible transcript.
     *
     * Issue #2126: the pane is ~62 columns and the journey's prompt is far
     * longer, so `TerminalEmulator` inserts soft-wrap newlines mid-phrase. A raw
     * `String.contains` therefore mis-reports text that IS on screen, which is
     * why this goes through the audited [TerminalTextMatcher] with the pane's
     * REAL column count rather than a hardcoded width.
     */
    private fun transcriptContains(transcript: String, substring: String): Boolean =
        TerminalTextMatcher.containsWrapTolerant(
            transcript = transcript,
            substring = substring,
            terminalCols = terminalColumns(),
        )

    /** The attached pane's live grid width; 0 disables wrap collapsing. */
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
            // Prefer the embedded TerminalView so the viewport screenshot is the
            // authoritative terminal render. In the surfaceError state there is
            // no attached TerminalView (the broken surface is replaced by the
            // error composable), so fall back to the activity's content view so
            // the error UI still produces a `*-viewport.png`.
            bitmap = captureViewToBitmap(
                activity.window.decorView.findTerminalView()
                    ?: activity.findViewById<View>(android.R.id.content)
                    ?: activity.window.decorView,
                name,
            )
        }
        val captured = checkNotNull(bitmap) {
            "activity was not available to capture viewport '$name' (#2135)"
        }
        writeBitmap("$name-viewport", captured)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        captured.recycle()
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "system screenshot was unavailable for $name"
        }
        writeBitmap("$name-full-device", bitmap)
        bitmap.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE423_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE423_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE423_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        check(::artifactScenario.isInitialized) {
            "artifact scenario must be selected before writing #423 evidence"
        }
        val dir = File(
            mediaRoot,
            "additional_test_output/$DEVICE_DIR_NAME/$artifactScenario",
        )
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
        const val LOG_TAG: String = "Issue423SurfaceFail"
        const val DEVICE_DIR_NAME: String = "issue423-terminal-surface-failure"
        const val RECOVERY_ARTIFACT_SCENARIO: String = "recovery"
        const val NAVIGATION_ARTIFACT_SCENARIO: String = "navigation-active-error"
        const val SESSION_NAME: String = "issue423-surface"
        const val INITIAL_MARKER: String = "ISSUE423-READY"
        const val PROMPT_HEAD: String = "ISSUE423-PROMPT-HEAD"

        /**
         * Issue #2126: a distinctive fragment of the prompt's TAIL. Its presence
         * without [PROMPT_HEAD] means the echo completed having lost its leading
         * characters, so the head wait is unsatisfiable and must abort at once
         * rather than run out a 180 s budget.
         */
        const val PROMPT_DETAIL_PREFIX: String = "detail line"
        const val PROMPT_TAIL: String = "$PROMPT_DETAIL_PREFIX 11 about the cable world"

        /** Issue #2126: chunk size / pacing of every production commitText. */
        const val TYPING_CHUNK_CHARS: Int = 8
        const val TYPING_CHUNK_PAUSE_MS: Long = 20

        /**
         * Issue #2126 input-readiness gate. The observed attach tail between
         * "seed marker visible" and the reattach reseed landing was 245–460 ms
         * locally and ~280 ms on the CI shard, so a 1.5 s byte-stable window is
         * several times the race it has to outlast, while the outer budget is a
         * "this is hung, not slow" ceiling that stays far under the 300 s
         * per-test `ci-journey-suite.sh` watchdog.
         */
        const val INPUT_READINESS_TIMEOUT_MS: Long = 90_000
        const val INPUT_PROBE_CYCLE_MS: Long = 20_000
        const val INPUT_SETTLE_MS: Long = 1_500
        const val INPUT_SAMPLE_INTERVAL_MS: Long = 50
        const val INPUT_PROBE_PREFIX: String = "[PS2126-PROBE-"

        /** Issue #2126: presence/settle ceiling for the keyboard-up chrome. */
        const val KEYBOARD_CHROME_TIMEOUT_MS: Long = 15_000

        // One past the in-app storm threshold so the error state trips
        // deterministically even if a stray transparent recovery is
        // absorbed first.
        const val SURFACE_FAILURE_BURST: Int = 6
    }
}
