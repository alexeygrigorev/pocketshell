package com.pocketshell.app.session

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.execRemoteSetupUntilReady
import com.pocketshell.app.proof.signals.FOREIGN_WINDOW_FOCUS_SIGNATURE
import com.pocketshell.app.proof.signals.awaitActivityWindowFocus
import com.pocketshell.app.proof.signals.captureImeServiceState
import com.pocketshell.app.proof.signals.describeActiveWindow
import com.pocketshell.app.proof.signals.describeActiveWindowCallCount
import com.pocketshell.app.proof.signals.resetDescribeActiveWindowCallCount
import com.pocketshell.app.proof.signals.InheritedJourneyFocus
import com.pocketshell.app.proof.signals.recordJourneyEntryFocus
import com.pocketshell.app.proof.signals.requireNoJourneyOwnedFocusRegression
import com.pocketshell.app.proof.signals.waitForActivityWindowFocusLost
import com.pocketshell.app.proof.signals.waitForActivityWindowFocused
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.testsupport.IME_SERVICE_UNAVAILABLE_SIGNATURE
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #131 / #1846 / #1879: connected validation for the show-keyboard chip on
 * the live tmux session screen.
 *
 * ## What this pins
 *
 * The bug the chip fixes is "no obvious way to bring up the soft keyboard from
 * the session screen" — phone users otherwise have to discover the
 * tap-on-viewport gesture on their own. The contract is therefore *causal*:
 * **with the keyboard down, one tap on the chip must raise the real soft
 * keyboard.** This test drives the real `MainActivity` over the Docker `agents`
 * fixture, attaches to a plain shell tmux session, and asserts that contract
 * against the system-level `WindowInsetsCompat.Type.ime()` signal.
 *
 * ## Why the test looks like this (issue #1846)
 *
 * The original body was gated behind an unconditional `Assume` self-skip, so
 * roughly 60 lines of it could never execute anywhere, under any
 * configuration. The stated justification was that the raw-SSH
 * "Continue with SSH" escape hatch it navigated through had been deleted by
 * #171 (D22) and that "`ShowKeyboardChipDockerTest` already covers the
 * tmux-attach chip behaviour". The navigation half of that was true; the
 * coverage half was not — `ShowKeyboardChipDockerTest` has never existed in
 * this repository (`git log --all -S ShowKeyboardChipDockerTest` returns only
 * the commit that wrote the comment). So the skip traded a real assertion for a
 * test that does not exist, and the chip's contract went unpinned.
 *
 * The behaviour still ships (`VoiceSessionSurface.kt` renders the chip;
 * `TmuxSessionScreen.kt` wires it to `showTerminalSoftKeyboard`), so per D22 the
 * answer is not to delete the method — it is to re-point it at the surviving
 * production surface (tmux attach via the folders-first host route) and make it
 * assert.
 *
 * ## Why it is not redundant with the neighbouring IME journeys
 *
 * `TmuxShellComposerOcclusionE2eTest` and `Issue887TerminalFixedUnderImeE2eTest`
 * both tap this chip, but only as the *mechanism* for raising the keyboard
 * before measuring geometry. Neither establishes that the IME was DOWN
 * beforehand, and the occlusion journey re-taps the chip on a retry loop — so
 * neither of them fails if the chip stops causing the keyboard to appear on a
 * clean first tap. This test closes that gap:
 *
 *  - the keyboard is **explicitly driven down and hard-asserted down** before
 *    each tap, so the post-tap assertion cannot pass on a keyboard that was
 *    already up (the vacuity the pre-#1846 body explicitly allowed: it accepted
 *    `ime_visible_before_tap` being either `false` *or* `true`);
 *  - the chip is tapped **exactly once** per cycle, with no re-tap loop, so a
 *    chip that only works on the third try is a failure;
 *  - the cycle runs **twice**, so a one-shot artefact of initial focus cannot
 *    masquerade as the chip working.
 *
 * ## Issue #1879 — the surviving vacuity, and what replaced it
 *
 * `ime_visible_after_tap=false` is emitted by three different worlds, and #1846
 * closed only one of them:
 *
 *  1. **the chip is broken** — window focused, IME service healthy, request
 *     accepted, keyboard still down. The contract failing. Must stay RED;
 *  2. **no app window holds input focus** — a foreign error dialog owns it, so
 *     the framework REFUSES `showSoftInput` outright
 *     (`Ignoring showSoftInput() as view=… is not served`) and the IME is
 *     unreachable in both directions. Nothing about the chip is observable;
 *  3. **the IME service itself never raises** — unbound, restarting, or no
 *     enabled/default IME on the AVD at all.
 *
 * On shard 2 of run 30454838433 this class failed BOTH attempts in world 2 — a
 * `com.google.android.apps.nexuslauncher` ANR dialog stood over the session
 * screen for the whole class — and reported world 1. So each cycle now
 * establishes app-window focus the same way it establishes keyboard-down, and
 * every failure names the state it actually observed. See
 * `proof/signals/WindowFocusSignals.kt` and
 * `shared/test-support/.../ImeServiceState.kt`.
 *
 * Per F3 there is no `Assume`-based self-skip anywhere in this test — not on
 * CI, not on a dev-box AVD: every assertion is a hard failure on every
 * device, and no pre-condition acts on the UI to make a failure go away.
 *
 * ## Artifacts
 *
 * Per run, under
 * `/sdcard/Android/media/com.pocketshell.app/additional_test_output/show-keyboard-chip/`:
 *
 *  - `01-before-tap-viewport.png` — full-device screenshot with the chip row
 *    visible and the IME hidden.
 *  - `02-after-tap-viewport.png`  — full-device screenshot with the IME raised
 *    after the first chip tap.
 *  - `03-second-cycle-after-tap-viewport.png` — the repeat cycle.
 *  - `summary.txt` — observed IME-visibility values per cycle, tap→visible
 *    latencies, and the chip-row tag.
 *  - `04-foreign-focus-owner-stolen-viewport.png` — the #1879 injected state,
 *    captured once it has settled (a clean "stolen" frame).
 *  - `05-…` / `06-…` and `summary-foreign-focus-owner.txt` — the recovered
 *    cycle of the #1879 reproduction. Each `@Test` writes its OWN summary; a
 *    shared `summary.txt` meant the second silently clobbered the first.
 */
@RunWith(AndroidJUnit4::class)
class ShowKeyboardChipE2eTest {
    private lateinit var trustedHostKeySha256: String

    val compose = createAndroidComposeRule<MainActivity>()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus from
    // the Compose hierarchy ("No compose hierarchies found").
    //
    // Issue #788: seed the remote tmux session + DB host row BEFORE
    // `createAndroidComposeRule<MainActivity>()` launches, so the Termux
    // `TerminalView` interop child is reliably placed into the window. The
    // chip's production handler resolves the TerminalView out of the compose
    // root view, so an unplaced interop child would make this test fail for the
    // wrong reason.
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedFixture() })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private val summaryLines = mutableListOf<String>()

    /** #1879: the synthetic focus stealer, torn down even when a cycle fails. */
    private var focusStealer: Dialog? = null

    /** Issue #2021: the focus reading this journey inherited at its entry boundary. */
    private var journeyEntryFocus: InheritedJourneyFocus? = null

    private suspend fun seedFixture() {
        clearLastSessionPrefs()
        val key = readFixtureKey()
        seededKey = key
        trustedHostKeySha256 = waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        seedTmuxSession(key)
        seededHostRowTag = seedDockerHost(key)
    }

    @Before
    fun resetSharedProbes() {
        journeyEntryFocus = recordJourneyEntryFocus(
            scenario = compose.activityRule.scenario,
            context = "before show-keyboard IME journey",
        )
        resetDescribeActiveWindowCallCount()
    }

    @After
    fun tearDown() {
        // #1879: never leave a focus-stealing window standing for the next
        // class on the shard — that is precisely the leak this issue is about.
        focusStealer?.let { dialog ->
            runCatching {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    if (dialog.isShowing) dialog.dismiss()
                }
            }
        }
        focusStealer = null
        requireNoJourneyOwnedFocusRegression(
            scenario = compose.activityRule.scenario,
            entry = journeyEntryFocus,
            context = "after show-keyboard IME journey cleanup",
        )
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun showKeyboardChipBringsUpSoftInput() {
        val hostRowTag = requireNotNull(seededHostRowTag) { "host row was not seeded" }
        summaryLines += "issue=131,1846 scenario=show-keyboard-chip"
        summaryLines += "chip_test_tag=$SHOW_KEYBOARD_CHIP_TAG"
        summaryLines += "host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER"

        // #1879 blocker 2: the accessibility service is PROCESS-wide shared
        // state. The first cut of the focus diagnosis OR-ed flags into it on
        // every cycle and never restored them, so every later class on the
        // shard inherited a reconfigured automation service. Snapshot it here
        // and prove below it came back untouched.
        val automationFlagsBefore = uiAutomationServiceFlags()
        summaryLines += "automation_service_flags_before=$automationFlagsBefore"

        attachSeededTmuxSession(hostRowTag)

        // The chip only renders once the session screen has reached the live
        // band; wait for the production node rather than a fixed sleep.
        awaitShowKeyboardChip()

        // Cycle 1 — the maintainer's reported scenario: keyboard down, one tap,
        // keyboard up.
        runShowKeyboardCycle(
            cycle = 1,
            beforeArtifact = "01-before-tap-viewport.png",
            afterArtifact = "02-after-tap-viewport.png",
        )

        // Cycle 2 — the same contract from a state where the IME has already
        // been raised and dismissed once. A chip that "works" only because the
        // terminal happened to gain focus on first attach fails here.
        runShowKeyboardCycle(
            cycle = 2,
            beforeArtifact = null,
            afterArtifact = "03-second-cycle-after-tap-viewport.png",
        )

        // #1879 blocker 2, part 1: the expensive active-window diagnosis is
        // computed ONLY on a failure path. Interpolating it into an
        // `assertTrue` message evaluates it eagerly on every cycle — an
        // accessibility tree dump per cycle of every test in this class.
        val describeCalls = describeActiveWindowCallCount()
        summaryLines += "describe_active_window_calls=$describeCalls"
        assertEquals(
            "the active-window diagnosis must not run on the happy path (it is an " +
                "accessibility tree fetch); observed $describeCalls call(s)",
            0,
            describeCalls,
        )

        // #1879 blocker 2, part 2: nothing here may reconfigure the shared
        // automation service for the rest of the instrumentation process.
        val automationFlagsAfter = uiAutomationServiceFlags()
        summaryLines += "automation_service_flags_after=$automationFlagsAfter"
        assertEquals(
            "this class must not mutate the process-wide UiAutomation service flags — " +
                "every later class on the shard inherits them. before=" +
                "$automationFlagsBefore after=$automationFlagsAfter",
            automationFlagsBefore,
            automationFlagsAfter,
        )

        writeSummary()
    }

    /**
     * Issue #1879: with a **focus-stealing window over the live session
     * screen**, the failure must name the window that owns input focus instead
     * of blaming the show-keyboard chip — and the chip must still work once
     * that window is gone.
     *
     * ## The state this reproduces
     *
     * On shard 2 of run 30454838433 this class failed both attempts with
     * `ime_visible_after_tap=false raisedMs=45052`. The captured artifacts show
     * why, and it is not the chip: a `com.google.android.apps.nexuslauncher`
     * **"Pixel Launcher isn't responding"** `AppNotRespondingDialog` stood over
     * the session screen for the whole class — visible in `01-before-tap` AND
     * `02-after-tap` of BOTH attempts, the same dialog instance
     * (`AppNotRespondingDialog@8fb399b`) in each `activity-processes.txt` dump.
     * With that dialog holding focus the framework refuses the request outright:
     *
     * ```
     * W InputMethodManager: Ignoring showSoftInput() as view=com.termux.view.TerminalView{…} is not served.
     * ```
     *
     * The IME was unreachable in BOTH directions — the cycle's own
     * `forceHideSoftInput()` also failed at `PHASE_CLIENT_VIEW_SERVED` — so the
     * keyboard-down pre-condition passed **vacuously** and the post-tap
     * assertion then blamed the chip for a state it was never given.
     *
     * ## Why the injection is synthetic, and why it is faithful
     *
     * A third-party launcher cannot be made to ANR on demand, so the state is
     * injected the #780 way: a focusable window is raised over the activity so
     * `Activity.hasWindowFocus()` goes false while the activity stays RESUMED
     * and visible. That geometry — not the dialog's identity — is what produces
     * the framework's "is not served" refusal, and the reproduction was
     * confirmed byte-identical to the CI failure (same message, same
     * `onFailed at PHASE_CLIENT_VIEW_SERVED`, same `is not served` line, same
     * `VFED.V... .F....ID` view flags, same burn-the-whole-budget shape).
     *
     * The stealer is deliberately **not cancelable**: nothing incidental — a
     * stray BACK, a global action, a swipe — can make it go away. If the
     * journey were ever to recover from it, that could only be a deliberate act
     * of the harness, and there is none.
     *
     * ## Why the app package is the STRICTER case (round-2 review)
     *
     * This stealer is drawn by the app under test, so the diagnosis reports
     * `active_window_pkg=<the app>`. That is the exact shape of a *product*
     * regression — a spurious modal over the live session, e.g. one of
     * `TmuxSessionAuxiliaryModals`' `AlertDialog`s. The harness must therefore
     * stay RED here and must never dismiss it; both are asserted below.
     *
     * No `active_window_pkg` reading gets the shard off RED: the signature is a
     * label, not an auto-INFRA key, because a framework error dialog's window
     * reads `android` whether PocketShell ANR'd (#796) or the launcher did. See
     * the `FOREIGN_WINDOW_FOCUS_SIGNATURE` KDoc; pinned by
     * `scripts/test-ci-journey-infra-signature.sh`.
     *
     * ## What it pins
     *
     * RED before the fix: the cycle burns the full show budget and reports
     * `ime_visible_after_tap=false`, i.e. the #1879 misattribution — the
     * signature assertion below fails. GREEN after: the cycle fails fast,
     * naming the focus owner and explicitly not blaming the chip; and once the
     * stealer is gone the chip raises the keyboard on ONE tap exactly as it
     * does with no dialog present. The post-tap assertion is untouched and
     * still hard; only the pre-condition got stricter.
     */
    @Test
    fun foreignFocusOwnerIsNamedAsTheCauseInsteadOfBlamingTheChip() {
        val hostRowTag = requireNotNull(seededHostRowTag) { "host row was not seeded" }
        summaryLines += "issue=1879 scenario=show-keyboard-chip-foreign-focus-owner"

        attachSeededTmuxSession(hostRowTag)
        awaitShowKeyboardChip()

        // Inject the reported state, and HARD-assert the injection landed. A
        // stealer that failed to take focus would make everything below pass
        // for the wrong reason (the #780 rule: never let the injection
        // self-skip).
        raiseSyntheticFocusStealingWindow()
        val stolen = waitForActivityWindowFocusLost(
            scenario = compose.activityRule.scenario,
            timeoutMs = FOCUS_STEAL_SETTLE_MS,
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val stolenWindow = describeActiveWindow()
        summaryLines += "focus_steal.app_window_focus_lost=$stolen"
        summaryLines += "focus_steal.active_window=$stolenWindow"
        captureFullDevice(
            File(ensureArtifactDir(), "04-foreign-focus-owner-stolen-viewport.png"),
        )
        if (!stolen) {
            writeSummary(name = "summary-foreign-focus-owner.txt")
            fail(
                "the synthetic focus stealer must actually take input focus away from " +
                    "the activity window, otherwise this test cannot reproduce #1879; " +
                    "observed app_window_focus_lost=false $stolenWindow",
            )
        }

        val startedAt = SystemClock.elapsedRealtime()
        val thrown = runCatching {
            runShowKeyboardCycle(cycle = 1, beforeArtifact = null, afterArtifact = null)
        }.exceptionOrNull()
        val failFastMs = SystemClock.elapsedRealtime() - startedAt
        summaryLines += "focus_steal.cycle_failed=${thrown != null}"
        summaryLines += "focus_steal.fail_fast_ms=$failFastMs"
        summaryLines += "focus_steal.failure_message=${thrown?.message?.replace('\n', ' ')}"

        val message = thrown?.message.orEmpty()
        if (thrown == null) {
            writeSummary(name = "summary-foreign-focus-owner.txt")
            fail(
                "a cycle run while another window owns input focus MUST fail: the chip " +
                    "cannot be measured in that state. It reported success instead, which " +
                    "would mean the pre-condition is vacuous again (#1879).",
            )
        }

        // THE load-bearing assertion (G6). Before the fix this message read
        // "expected the soft keyboard to be VISIBLE after ONE tap … raisedMs=30038"
        // — a chip accusation for a state the chip was never given.
        assertTrue(
            "the failure must name the window that owns input focus, not the chip. " +
                "Expected the #1879 signature; got: $message",
            message.contains(FOREIGN_WINDOW_FOCUS_SIGNATURE),
        )
        assertTrue(
            "the failure must name the window that owns focus so a human reading the red " +
                "shard can see who took it; got: $message",
            message.contains("active_window_pkg="),
        )
        assertTrue(
            "the failure must NOT blame the chip when the chip was never given the " +
                "state it is contracted to act on; got: $message",
            !message.contains(CHIP_ACCUSATION),
        )

        // Blocker 1 (round-2 review): the focus owner here is a window the APP
        // drew — the exact shape of a spurious-modal product regression. The
        // harness must not dismiss it, retry past it, or otherwise recover: it
        // must stay red and say whose window it is.
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertTrue(
            "an app-owned focus thief is a PRODUCT signal and must be reported as such, " +
                "never cleared by the harness; expected active_window_pkg=$targetPackage " +
                "in: $message",
            message.contains("active_window_pkg=$targetPackage"),
        )
        assertTrue(
            "the stealer must still be standing — nothing in the pre-condition may act " +
                "on the UI to make a failure go away (that is the masking channel this " +
                "round removed)",
            focusStealer?.isShowing == true,
        )

        // The #1879 failure MODE was "burned the whole show budget and then
        // blamed the chip". Failing on the pre-condition is strictly faster
        // than the show budget it no longer reaches.
        assertTrue(
            "the pre-condition must fail fast instead of burning the IME show budget " +
                "(that burn is what produced the misleading raisedMs=45052); " +
                "observed ${failFastMs}ms vs show budget ${IME_SHOW_TIMEOUT_MS}ms",
            failFastMs < IME_SHOW_TIMEOUT_MS,
        )

        // Now remove the injected state and prove the chip itself is fine — the
        // control that keeps this test from passing merely because everything
        // is broken (G6).
        dismissSyntheticFocusStealingWindow()
        runShowKeyboardCycle(
            cycle = 2,
            beforeArtifact = "05-focus-owner-cleared-before-tap-viewport.png",
            afterArtifact = "06-focus-owner-cleared-after-tap-viewport.png",
        )

        writeSummary(name = "summary-foreign-focus-owner.txt")
    }

    /**
     * One full down → tap → up cycle.
     *
     * The pre-condition assertion is what makes the post-condition
     * load-bearing: without "the IME is verifiably DOWN before the tap", an
     * already-raised keyboard would satisfy the post-tap check while the chip
     * did nothing at all (G6 — the load-bearing assertion must be the one that
     * can go red).
     */
    private fun runShowKeyboardCycle(
        cycle: Int,
        beforeArtifact: String?,
        afterArtifact: String?,
    ) {
        // Issue #1879 — pin the REPORTED state before anything else.
        //
        // "The keyboard is down" is satisfied by two different worlds: the
        // maintainer's (app window focused, keyboard simply down, so a tap MUST
        // raise it) and a degenerate one (no app window holds focus at all, so
        // the framework refuses every showSoftInput as "not served" and the IME
        // is unreachable in BOTH directions). Only the first is the contract
        // under test; in the second the keyboard-down check passes vacuously and
        // the post-tap assertion below would blame the chip for a state it was
        // never given — which is exactly how run 30454838433 reddened behind a
        // Pixel Launcher ANR dialog.
        //
        // So wait for focus the same way forceHideSoftInput() establishes
        // keyboard-down, and HARD-fail with the real cause when it does not
        // arrive. No `Assume` self-skip (F3), and no action on the UI: an unfocusable
        // window is reported, never cleared and never skipped.
        val focus = awaitActivityWindowFocus(
            scenario = compose.activityRule.scenario,
            timeoutMs = WINDOW_FOCUS_TIMEOUT_MS,
        )
        summaryLines += "cycle$cycle.app_window_focused_before_tap=${focus.focused}"
        summaryLines += "cycle$cycle.window_focus_diagnosis=${focus.diagnosis}"
        if (!focus.focused) {
            // Built only here — the diagnosis is an accessibility tree fetch.
            fail(
                "$FOREIGN_WINDOW_FOCUS_SIGNATURE The show-keyboard chip cannot be " +
                    "measured in that state, so this is NOT a chip failure " +
                    "(cycle $cycle): ${focus.diagnosis}.",
            )
        }

        forceHideSoftInput()
        val downObserved = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = false,
            timeoutMs = IME_HIDE_TIMEOUT_MS,
        )
        summaryLines += "cycle$cycle.ime_visible_before_tap=$downObserved"
        // HARD pre-condition — no assumeTrue skip. If the keyboard cannot be
        // driven down we have not reproduced the reported state, and the tap
        // assertion below would be vacuous.
        assertTrue(
            "expected the soft keyboard to be HIDDEN before tapping the show-keyboard " +
                "chip (cycle $cycle); observed ime_visible=$downObserved. Without this " +
                "pre-condition the post-tap assertion cannot fail and proves nothing.",
            !downObserved,
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        beforeArtifact?.let { captureFullDevice(File(ensureArtifactDir(), it)) }

        // Tap the chip EXACTLY once, via its stable test tag so the assertion
        // survives a caption rename ("keyboard" -> "show keyboard"). No retry
        // loop: "the user taps show-keyboard and the keyboard appears" is the
        // contract, and a chip that needs three taps has broken it.
        val tapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).performClick()

        val shown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = IME_SHOW_TIMEOUT_MS,
        )
        val raisedMs = SystemClock.elapsedRealtime() - tapAt
        summaryLines += "cycle$cycle.ime_visible_after_tap=$shown"
        summaryLines += "cycle$cycle.tap_to_ime_visible_ms=$raisedMs"

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        afterArtifact?.let { captureFullDevice(File(ensureArtifactDir(), it)) }

        // The #131 acceptance: the IME must be visible after a single tap.
        if (!shown) {
            fail(describePostTapFailure(cycle, downObserved, raisedMs))
        }
    }

    /**
     * Issue #1879 — the post-tap failure diagnosis, built ONLY when the tap did
     * not raise the keyboard.
     *
     * `ime_visible_after_tap=false` is not one state, it is three (see the class
     * KDoc). This re-reads the two facts that discriminate them at the moment of
     * failure, so a future CI red is readable from the message alone:
     *
     *  - **focus, re-read now.** The pre-condition checked it before the tap; a
     *    dialog arriving *after* that check (the organic timing) would otherwise
     *    still produce the old misleading wording. This closes that residue.
     *  - **the IME service state.** Window focused, request accepted, but the
     *    input-method service unbound / not ready / with no enabled IME is the
     *    third world. The decision is a pure function pinned by
     *    `ImeServiceStateSignatureTest` in the required Unit job, and it is
     *    deliberately conservative: unreadable evidence reports UNKNOWN and the
     *    ordinary chip wording stands, so no environmental excuse is ever
     *    invented for a genuine defect.
     *
     * The IME-service signature is a *label*, not an auto-INFRA net: it is not
     * registered in `scripts/ci-journey-infra-signature.py`, so it never
     * downgrades a shard.
     */
    private fun describePostTapFailure(cycle: Int, downObserved: Boolean, raisedMs: Long): String {
        val lateFocus = awaitActivityWindowFocus(
            scenario = compose.activityRule.scenario,
            timeoutMs = 0L,
        )
        val imeService = captureImeServiceState()
        summaryLines += "cycle$cycle.window_focus_after_tap=${lateFocus.diagnosis}"
        summaryLines += "cycle$cycle.ime_service_state=${imeService.describe()}"

        val prefix = when {
            !lateFocus.focused ->
                "$FOREIGN_WINDOW_FOCUS_SIGNATURE Focus was lost AFTER the pre-condition " +
                    "check, so the chip was never given the state it acts on: " +
                    "${lateFocus.diagnosis}. "
            imeService.serviceUnavailable ->
                "$IME_SERVICE_UNAVAILABLE_SIGNATURE Observed ${imeService.describe()}. "
            else -> ""
        }
        return prefix + CHIP_ACCUSATION + " (cycle $cycle); observed " +
            "ime_visible_before_tap=$downObserved ime_visible_after_tap=false " +
            "raisedMs=$raisedMs ${imeService.describe()}"
    }

    // --- Navigation --------------------------------------------------------

    private fun awaitShowKeyboardChip() {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).assertExists()
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        waitForTerminalViewAttached()
    }

    /**
     * The production chip handler resolves the Termux [TerminalView] out of the
     * compose root and calls `imm.showSoftInput` on it. Waiting for a live
     * emulator here keeps a missing interop child from being reported as "the
     * chip does not raise the keyboard".
     */
    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    // --- #1879 synthetic focus stealer -------------------------------------

    /**
     * Raise a focusable window over the activity so `hasWindowFocus()` goes
     * false while the activity stays RESUMED and visible — the geometry that
     * makes the framework refuse `showSoftInput` with "is not served".
     *
     * Not cancelable on purpose: only [dismissSyntheticFocusStealingWindow] and
     * teardown can remove it, so "the journey recovered from it" can never
     * happen by accident.
     */
    private fun raiseSyntheticFocusStealingWindow() {
        compose.activityRule.scenario.onActivity { activity ->
            val dialog = Dialog(activity)
            val label = TextView(activity).apply {
                text = "issue-1879 synthetic focus owner"
                // Focusable content, so the stealer's window is the natural
                // input-focus target the moment it attaches.
                isFocusableInTouchMode = true
                isFocusable = true
            }
            dialog.setContentView(label)
            dialog.setCancelable(false)
            dialog.setOnDismissListener { focusStealer = null }
            dialog.show()
            focusStealer = dialog
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun dismissSyntheticFocusStealingWindow() {
        val dialog = focusStealer
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (dialog?.isShowing == true) dialog.dismiss()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val regained = waitForActivityWindowFocused(
            scenario = compose.activityRule.scenario,
            timeoutMs = WINDOW_FOCUS_TIMEOUT_MS,
        )
        summaryLines += "focus_steal.app_window_focus_regained=$regained"
        if (!regained) {
            fail(
                "the activity window must regain input focus once the synthetic stealer " +
                    "is dismissed, otherwise the control cycle below would not be a " +
                    "control at all; observed ${describeActiveWindow()}",
            )
        }
    }

    // --- IME control -------------------------------------------------------

    /**
     * Drive the soft keyboard down from the test side. Both routes are used:
     * `WindowInsetsControllerCompat.hide(ime())` is the modern signal-accurate
     * path, and `hideSoftInputFromWindow` covers the case where no view holds
     * an active connection yet.
     */
    private fun forceHideSoftInput() {
        compose.activityRule.scenario.onActivity { activity ->
            val window = activity.window ?: return@onActivity
            val decor = window.decorView
            runCatching {
                WindowInsetsControllerCompat(window, decor)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            runCatching {
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                imm?.hideSoftInputFromWindow(decor.windowToken, 0)
            }
        }
    }

    private fun uiAutomationServiceFlags(): Int =
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.serviceInfo?.flags
        }.getOrNull() ?: -1

    // --- Host / session seeding -------------------------------------------

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

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
                name = "show-keyboard-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "ShowKeyboard Chip",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    // Bootstrap already done so the host tap goes straight to
                    // the folders/session surface.
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
                    shellQuote("printf '$READY_MARKER\\n'; exec sleep 600"),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "show-keyboard chip tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true",
            description = "show-keyboard chip tmux cleanup",
        )
    }

    // --- Artifact helpers --------------------------------------------------

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create show-keyboard-chip artifact directory: ${dir.absolutePath}"
        }
        return dir
    }

    /**
     * Each `@Test` writes its OWN summary file. The class has two tests that
     * both produce a summary, and a shared `summary.txt` meant the second run
     * silently clobbered the first — the artifact review would then be reading
     * a different test's numbers than it thought (#1879).
     */
    private fun writeSummary(name: String = "summary.txt") {
        val file = File(ensureArtifactDir(), name)
        file.writeText(summaryLines.joinToString(separator = "\n", postfix = "\n"))
        println("SHOW_KEYBOARD_CHIP_SUMMARY ${file.absolutePath}")
        summaryLines.forEach { println("SHOW_KEYBOARD_CHIP_SUMMARY_LINE $it") }
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write show-keyboard screenshot: ${file.absolutePath}"
                }
            }
            println("SHOW_KEYBOARD_CHIP_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
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
        const val DEVICE_DIR_NAME: String = "show-keyboard-chip"
        const val SESSION_NAME: String = "issue131-show-keyboard"
        const val READY_MARKER: String = "ISSUE131-SHOW-KEYBOARD-READY"

        /**
         * The #131 acceptance wording. Held as a constant because #1879's
         * reproduction asserts it is ABSENT when the chip was never given the
         * state it acts on — the exact misattribution that reddened shard 2.
         */
        const val CHIP_ACCUSATION: String =
            "expected the soft keyboard to be VISIBLE after ONE tap on the show-keyboard chip"

        /**
         * Budget for the keyboard to go DOWN. Generous enough that a slow
         * swiftshader emulator never trips it, tight enough that a keyboard
         * that will not dismiss is reported as a failure rather than absorbed.
         */
        val IME_HIDE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L

        /**
         * Budget for the keyboard to come UP after the single chip tap. The CI
         * swiftshader emulator commonly lags 5–15 s and can spike to ~25 s
         * under load (see `ImeSignals.IME_VISIBILITY_DEFAULT_TIMEOUT_MS`), so
         * 45 s on CI is a "this is broken, not slow" ceiling.
         */
        val IME_SHOW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L

        /**
         * Issue #1879: budget for the app window to (re)gain input focus.
         * Generous enough to cover a slow swiftshader window transition, short
         * enough that a standing foreign error dialog is reported as such
         * rather than eating the IME budget and being misread as a chip
         * failure.
         */
        val WINDOW_FOCUS_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 15_000L else 10_000L

        /** Settle budget for the synthetic stealer to take focus. */
        const val FOCUS_STEAL_SETTLE_MS: Long = 5_000L
    }
}
