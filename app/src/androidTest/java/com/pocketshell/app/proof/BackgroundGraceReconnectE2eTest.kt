package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
import com.pocketshell.app.BACKGROUND_GRACE_MILLIS
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.sessions.service.SessionConnectionService
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxClientDiagnostics
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #548 / #450: real MainActivity -> host picker -> tmux attach proof
 * for a quick app-switch returning inside the background grace window.
 *
 * The within-grace half is the regression guard: after ProcessLifecycle
 * `ON_STOP` has started the bounded grace timer, foregrounding before that
 * timer elapses must keep the live SSH/tmux control client intact. The user
 * must not see Connecting/Reconnecting/Disconnected/Tap Reconnect, and the
 * app must not record reconnect/reattach diagnostics.
 *
 * The same run also drives a short post-grace cycle using the test override
 * seam so reviewers can see the opposite branch without a 30s connected-test
 * sleep: once grace elapses, the app detaches and later foregrounds through
 * the normal lifecycle reattach path.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundGraceReconnectE2eTest {

    // Issue #788: createAndroidComposeRule<MainActivity>() so the Compose test
    // clock drives the SAME foreground activity the Termux TerminalView interop
    // child is placed into — fixing the #470 swiftshader interop-placement /
    // enumeration stall. The rule launches MainActivity in its `before()`, so the
    // remote tmux session + DB host row are seeded BEFORE launch by the chain.
    val compose = createAndroidComposeRule<MainActivity>()

    // Issue #470 blocker #1 (grant) + #788 seed-before-launch ordering
    // (deterministic via RuleChain — outer `before()` first):
    //   grant perms -> seed remote session + DB host row -> launch MainActivity.
    // NOTE: the @Before setUp() (clear prefs, install diagnostics, grace
    // override) still runs INSIDE all rule statements, i.e. AFTER MainActivity
    // launches. The pieces that MUST precede launch (remote seed + DB row) live
    // in [seedBeforeLaunch]; the @Before-only state (diagnostics sink, grace
    // override) is read lazily by the lifecycle path and is fine post-launch.
    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private var diagnostics: RecordingDiagnosticSink? = null

    // Issue #743 de-flake: observe the LOCAL `-CC` control-client reader exit so
    // the post-grace foreground can be gated on the local detach genuinely
    // completing (see [waitForPostGraceLocalDetachSettled]). `tmux_client_reader_exit`
    // is emitted by the core TmuxClient when its reader thread exits — a stronger
    // "local detach settled" signal than the REMOTE client-count poll alone, which
    // can read 0 before the local VM coroutine + the conflating-StateFlow driver
    // collector have drained `Backgrounded`.
    private var tmuxDiagnostics: RecordingTmuxDiagnosticSink? = null
    private val timings = mutableListOf<String>()

    @Before
    fun setUp() {
        // Issue #788: the diagnostics sink is installed in @Before (which runs
        // AFTER MainActivity launches under createAndroidComposeRule). That is
        // fine — the events the assertions read are emitted during the bg->fg
        // cycle the body drives later, and the body clears the sink before each
        // measured cycle. The pref/override clears that the app reads AT LAUNCH
        // moved into [seedBeforeLaunch] so they precede the rule's launch.
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        tmuxDiagnostics = RecordingTmuxDiagnosticSink().also { TmuxClientDiagnostics.install(it) }
    }

    /**
     * Issue #788: establish all LAUNCH-time state BEFORE MainActivity launches
     * (run by [SeedBeforeLaunchRule], which evaluates before the compose rule's
     * `before()`):
     *  - clear last-session + background-grace prefs and the grace override, so
     *    MainActivity reads a clean baseline on its first composition (these are
     *    read at launch — clearing them in @Before, post-launch, would be too
     *    late);
     *  - seed the remote tmux session + DB host row the app will attach to.
     */
    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        clearBackgroundGraceSetting()
        BackgroundGraceTestOverride.setForTest(null)
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)
        hostRowTag = seedDockerHost(key)
    }

    @After
    fun tearDown() {
        // Issue #788: the body cycles the rule-owned scenario to CREATED during
        // the bg->fg grace cycle; restore RESUMED before the compose rule's own
        // `after()` -> ActivityScenario.close() runs so close() does not crash
        // with "Current state was null … Last stage = STARTED" (the FATAL
        // AndroidRuntime "Process crashed" the reviewer hit). @After runs INSIDE
        // the rule statement, BEFORE the rule's after(). Best-effort.
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics?.close()
        diagnostics = null
        tmuxDiagnostics?.close()
        tmuxDiagnostics = null
        clearLastSessionPrefs()
        clearBackgroundGraceSetting()
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    @Test
    fun quickAppSwitchWithinBackgroundGraceDoesNotShowOrRecordReconnect() = runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForClientCountAtLeast(1, "initial attach")
        captureViewport("issue548-01-attached")
        diagnostics!!.clear()

        // Within-grace branch: ProcessLifecycle ON_STOP starts the timer,
        // but foreground arrives before the short injected window elapses.
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
        val withinStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "within-grace background")
        waitForClientCountAtLeast(1, "inside grace before foreground")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "within-grace foreground") {
            it.fields["withinGrace"] == true
        }
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - withinStart)

        waitForConnected("within-grace foreground")
        assertNoVisibleReconnect("within-grace foreground")
        watchNoVisibleReconnect("within-grace settle", WATCH_NO_RECONNECT_MS)
        waitForVisibleTerminal("within-grace terminal") { it.contains(READY_MARKER) }
        waitForClientCountAtLeast(1, "within-grace after foreground")
        assertNoReconnectOrReattachDiagnostics("within-grace foreground")
        assertWithinGraceReseedOnlyDriverOwned("within-grace foreground")
        captureViewport("issue548-02-within-grace-foreground")

        // Post-grace branch: with the same real UI path still open, use a
        // shorter override so the teardown branch is covered without waiting
        // for the user-facing 30s minimum.
        stopSessionHoldBeforeExpectedTeardown()
        diagnostics!!.clear()
        BackgroundGraceTestOverride.setForTest(POST_GRACE_MS)
        val postStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_elapsed", "post-grace elapsed") {
            it.fields["teardown"] == true
        }
        waitForDiagnostic("terminal_background_teardown", "post-grace teardown")
        waitForClientCountAtMost(0, "post-grace detached")
        // Issue #743 de-flake: the headline `quickAppSwitch…` flake on a contended
        // swiftshader box is a deterministic conflation race, NOT a slow render. The
        // post-grace foreground reattach (`foreground_reattach`) is fired by the
        // ConnectionEffectDriver ONLY on the controller edge
        // `current is Reconnecting && previous is Backgrounded`, and the driver collects
        // a CONFLATING StateFlow. If the test submits `Foreground` (moveToState RESUMED)
        // before the driver's collector has resumed past `Backgrounded`, the
        // `Backgrounded -> Reconnecting` edge is conflated away and `foreground_reattach`
        // NEVER fires -> the test wedges on `waitForDiagnostic("foreground_reattach")`.
        //
        // The REMOTE client-count poll above can read 0 before the LOCAL `-CC` reader
        // has exited and the driver collector has drained `Backgrounded`, so it is not a
        // sufficient gate. Wait here for the LOCAL detach to genuinely complete (the
        // core TmuxClient reader thread exiting) + an idle pump, restoring the
        // production-faithful ordering (real grace is 30-60s, so the controller/driver
        // is long-settled into `Backgrounded` before any real foreground). HARD-fails if
        // the local detach never lands — it does not mask a regression.
        waitForPostGraceLocalDetachSettled("post-grace detached")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "post-grace foreground") {
            it.fields["withinGrace"] == false
        }
        waitForDiagnostic("terminal_foreground_reattach", "post-grace lifecycle reattach")
        waitForDiagnostic("foreground_reattach", "post-grace vm reattach")
        waitForConnected("post-grace reattach")
        waitForVisibleTerminal("post-grace terminal") { it.contains(READY_MARKER) }
        recordTiming("post_grace_cycle_ms", SystemClock.elapsedRealtime() - postStart)
        captureViewport("issue548-03-post-grace-reattached")
        writeTimings()
    }

    /**
     * Issue #959 — DURABLE END-TO-END REGRESSION for the beyond-grace
     * background→foreground "TERMINAL frozen (no I/O) but app responsive" report.
     *
     * The post-grace `connect(LifecycleReattach)` reconnects against a FRESH SSH
     * lease + a brand-new `-CC` client, but tmux preserves pane ids so the
     * reconcile REUSES the pane. Before the fix, the reused pane's output producer
     * (subscribed to the DEAD client's `outputFor`) and input drain were never
     * re-bound to the new client: the stale buffer stayed painted, NEW output
     * never reached the emulator, and typed input went nowhere — a Connected-
     * looking, frozen terminal.
     *
     * The existing post-grace branch in
     * [quickAppSwitchWithinBackgroundGraceDoesNotShowOrRecordReconnect] only
     * asserts `waitForConnected` + `READY_MARKER` present — content that was
     * already on screen BEFORE backgrounding, so a frozen terminal PASSES it (the
     * wrong-cost gap, D32-G6). This test closes that gap: after the reattach it
     * TYPES a unique token through the real input path and asserts the shell's
     * FRESH ECHO of that token renders in the visible terminal — the live-terminal
     * property. RED on a frozen reattach (no echo ever appears → times out);
     * GREEN once the producer + input re-bind to the new client.
     */
    @Test
    fun postGraceReattachLeavesTerminalLiveWithFreshInputEcho() = runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForClientCountAtLeast(1, "initial attach")

        // Prove the terminal is LIVE before backgrounding: type a pre-background
        // token and confirm the shell echoes it. This anchors that the freeze the
        // test detects later is introduced by the reattach, not pre-existing.
        val preToken = "ISSUE959-PRE-${SystemClock.elapsedRealtime()}"
        sendLineToActivePane("echo $preToken")
        waitForVisibleTerminal("pre-background echo") { it.contains(preToken) }
        captureViewport("issue959-01-pre-background-live")
        diagnostics!!.clear()

        // #780 synthetic-injection: force the on-device RACE the freeze needs — a
        // pane that SURVIVES the background teardown's clear into the foreground
        // reattach reconcile (cache/park/race). The clean teardown clears paneRows
        // (so a plain background→foreground builds fresh panes and never freezes),
        // so the only way to drive the reported frozen state deterministically is
        // to inject the surviving-pane state. With it, the post-grace reattach
        // takes the REUSE branch against a fresh client; without the fix the
        // reused pane stays bound to the dead client -> frozen. HARD-fails if the
        // state is not entered (no self-skip).
        forcePreservePaneRuntimeOnBackgroundTeardown()

        // Beyond-grace background -> teardown -> foreground -> reattach. Mirrors
        // the post-grace branch of the within-grace test (same proven sequencing),
        // using the short override so the teardown actually fires without a 30s+
        // real-grace wait.
        stopSessionHoldBeforeExpectedTeardown()
        BackgroundGraceTestOverride.setForTest(POST_GRACE_MS)
        val postStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_elapsed", "post-grace elapsed") {
            it.fields["teardown"] == true
        }
        waitForDiagnostic("terminal_background_teardown", "post-grace teardown")
        waitForClientCountAtMost(0, "post-grace detached")
        waitForPostGraceLocalDetachSettled("post-grace detached")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "post-grace foreground") {
            it.fields["withinGrace"] == false
        }
        // Confirm a REAL reconnect actually fired (rules out the secondary
        // missing-arm race: if no reattach diagnostics appear, the bug is a
        // different one and this test would fail here, not on the echo).
        waitForDiagnostic("terminal_foreground_reattach", "post-grace lifecycle reattach")
        waitForDiagnostic("foreground_reattach", "post-grace vm reattach")
        waitForConnected("post-grace reattach")
        // NOTE: we intentionally do NOT hard-assert the stale READY_MARKER buffer
        // here — on a frozen reattach the reused pane may never even reveal its
        // stale content, and asserting it would short-circuit the test before the
        // LOAD-BEARING live-terminal check below. The single discriminator is the
        // fresh-input echo: a frozen reattach never produces it. (#641-class
        // wrong-cost avoidance: prove the user-visible LIVE property, not a
        // weaker buffer-present proxy.)
        recordTiming("post_grace_reattach_cycle_ms", SystemClock.elapsedRealtime() - postStart)
        captureViewport("issue959-02-post-grace-reattached")

        // LOAD-BEARING: type a NEW token AFTER the reattach and require the shell's
        // FRESH echo to render. On a frozen reattach (producer/input still bound to
        // the dead client) the keystrokes never reach the shell and/or the echo
        // never reaches the emulator, so this token NEVER appears -> the wait
        // HARD-fails. This is the user-visible "terminal usable again" property.
        val postToken = "ISSUE959-POST-${SystemClock.elapsedRealtime()}"
        sendLineToActivePane("echo $postToken")
        waitForVisibleTerminal("post-grace fresh input echo") { it.contains(postToken) }
        captureViewport("issue959-03-post-grace-fresh-echo")
        writeTimings()
    }

    /**
     * Issue #959: send a line of text + Enter to the active pane through the
     * REAL on-device input path: bytes are written to the live Termux
     * [TerminalView]'s session (`currentSession.write`) — EXACTLY what the Termux
     * key handler does for a typed character. That session is the bridge's
     * `TerminalSession`, whose output stream is the pane's `remoteStdin` (the tmux
     * input sink -> queue -> drain coroutine). This is the path the freeze kills:
     * after a beyond-grace reattach the reused pane's bridge/remoteStdin/drain are
     * still wired to the DEAD client (its queue was closed at teardown), so the
     * keystrokes never reach the shell and nothing echoes. Driving the VM's
     * `writeInputToPane` (which sends straight to `clientRef`) would BYPASS that
     * frozen queue and mask the bug — this MUST go through the terminal session.
     */
    /**
     * Issue #959 (#780 model): arm the synthetic injection so the next background
     * teardown PRESERVES the pane runtime (paneRows + producers + input + client
     * bindings) across into the foreground reattach reconcile — the surviving-pane
     * race the freeze requires. HARD-asserts the flag took (no silent skip).
     */
    private fun forcePreservePaneRuntimeOnBackgroundTeardown() {
        var armed = false
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            vm.preservePaneRuntimeOnBackgroundTeardownForTest = true
            armed = vm.preservePaneRuntimeOnBackgroundTeardownForTest
        }
        check(armed) { "failed to arm the #959 surviving-pane-runtime injection" }
    }

    private fun sendLineToActivePane(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        var wrote = false
        compose.activityRule.scenario.onActivity { activity ->
            val session = activity.window.decorView.findTerminalView()?.currentSession
                ?: return@onActivity
            session.write(bytes, 0, bytes.size)
            wrote = true
        }
        check(wrote) { "no live terminal session to send input to" }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun sixSecondAppSwitchWithProductionGraceDoesNotShowOrRecordReconnect() = runBlocking<Unit> {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForClientCountAtLeast(1, "initial attach")
        captureViewport("issue548-sixsec-01-attached")
        diagnostics!!.clear()

        // The production/default grace window is 60s. This holds the app
        // backgrounded for the reported short app-switch interval (~6s),
        // then foregrounds before grace can elapse. If #548/#450 regresses,
        // the assertions below catch the stuck "Tap Reconnect" state or any
        // reconnect/reattach diagnostic emitted during the cycle.
        BackgroundGraceTestOverride.setForTest(null)
        val switchStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "six-second production-grace background") {
            (it.fields["millis"] as? Number)?.toLong() == BACKGROUND_GRACE_MILLIS
        }
        SystemClock.sleep(SIX_SECOND_APP_SWITCH_MS)
        waitForClientCountAtLeast(1, "six-second production-grace background hold")
        assertTrue(
            "six-second production-grace cycle must not elapse before foreground; events=${diagnostics!!.events}",
            diagnostics!!.eventsNamed("background_grace_elapsed").isEmpty(),
        )

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNoVisibleReconnect("immediately after six-second production-grace foreground")
        waitForDiagnostic("background_grace_foreground", "six-second production-grace foreground") {
            it.fields["withinGrace"] == true
        }
        recordTiming(
            "six_second_production_grace_cycle_ms",
            SystemClock.elapsedRealtime() - switchStart,
        )

        waitForConnected("six-second production-grace foreground")
        assertNoVisibleReconnect("six-second production-grace foreground")
        watchNoVisibleReconnect("six-second production-grace settle", WATCH_NO_RECONNECT_MS)
        waitForVisibleTerminal("six-second production-grace terminal") { it.contains(READY_MARKER) }
        waitForClientCountAtLeast(1, "six-second production-grace after foreground")
        assertNoReconnectOrReattachDiagnostics("six-second production-grace foreground")
        assertWithinGraceReseedOnlyDriverOwned("six-second production-grace foreground")
        captureViewport("issue548-sixsec-02-foreground")
        writeTimings()
    }

    /**
     * Issue #743 de-flake: a plain WALL-CLOCK poll loop, DECOUPLED from the Compose
     * idling resource.
     *
     * `compose.waitUntil` only evaluates its predicate after Compose reaches idle
     * (it pumps the looper "until idle" between polls). Under heavy software-GL
     * contention on a loaded box + a continuously-recomposing app, Compose rarely
     * idles, so the predicate is checked only sporadically and the wait can burn its
     * entire budget WITHOUT ever observing a signal that already landed — exactly the
     * `ComposeTimeoutException after 90000ms` wedge reproduced on base at
     * `attachSeededTmuxSession` (the #470 family the issue predicted). This loop
     * evaluates `condition()` directly each 100ms iteration regardless of Compose
     * idle, so a render/state that DID land is seen the moment it lands.
     *
     * Still HARD-fails with the labelled stuck condition if the signal never arrives,
     * so a real regression surfaces (it does not weaken any assertion).
     */
    private fun pollUntil(
        timeoutMs: Long,
        label: String,
        // Issue #743: optionally drain queued main-thread work each iteration via the
        // BOUNDED `waitForIdleSync` (it returns once the queue drains, so it cannot
        // hang on a never-idle app the way Compose's unbounded `waitForIdle()` can).
        // This lets a state read via `onActivity` (VM connection status) observe a
        // value that landed on the main thread. NOTE: Compose-FRAME-dependent UI
        // signals — recomposition presence, the Termux `TerminalView` interop child
        // placement, the emulator transcript — use [waitForRender] instead, which
        // actually drives frames; this idle-decoupled loop is for the remote/sink/VM
        // reads that the never-idle-Compose wedge (round 2) would otherwise starve.
        pumpMainLooper: Boolean = true,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { condition() }.getOrDefault(false)) return
            if (pumpMainLooper) {
                runCatching { InstrumentationRegistry.getInstrumentation().waitForIdleSync() }
            }
            SystemClock.sleep(100)
        }
        // Final post-deadline evaluation so a signal that landed in the last sleep
        // window is not lost to an off-by-one.
        check(runCatching { condition() }.getOrDefault(false)) {
            "timed out after ${timeoutMs}ms waiting for: $label"
        }
    }

    /**
     * Issue #743: read a semantics node count tolerantly — return the TRUE count when
     * the tree is readable, and treat the transient "No compose hierarchies found" ISE
     * (thrown while the compose root is momentarily absent under cold compose / a
     * navigation transition — the #470 family) as 0. Returns the real count whenever a
     * root exists, so it never masks a genuinely-present node.
     */
    private fun tolerantNodeCountWithTag(tag: String): Int =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
        }.getOrDefault(0)

    private fun tolerantNodeCountWithText(text: String): Int =
        runCatching {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
        }.getOrDefault(0)

    /**
     * Issue #743: a UI-render wait that DRIVES Compose frames (via `compose.waitUntil`,
     * which pumps the looper to idle between polls) so a Compose-frame-dependent
     * signal — recomposition of a presence node, and crucially the Termux `TerminalView`
     * AndroidView interop child being PLACED + laid out into the resumed activity — can
     * actually advance. The reliably-attaching EmulatorDockerSshSmokeTest uses the same
     * frame-driving wait; a purely idle-DECOUPLED wall-clock poll never advances the
     * interop placement and wedges at "terminal view attached" even though tmux is ready.
     *
     * The `condition` is wrapped in `runCatching` so the transient "No compose
     * hierarchies found" ISE (the #470 family, thrown while the compose root is
     * momentarily absent under cold compose / a navigation transition) is treated as
     * "keep waiting" rather than escaping. On timeout it re-checks once and HARD-fails
     * with the labelled stuck condition, so a real regression still surfaces.
     */
    private fun waitForRender(timeoutMs: Long, label: String, condition: () -> Boolean) {
        runCatching {
            compose.waitUntil(timeoutMillis = timeoutMs) {
                runCatching { condition() }.getOrDefault(false)
            }
        }
        check(runCatching { condition() }.getOrDefault(false)) {
            "timed out after ${timeoutMs}ms waiting for: $label"
        }
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        // Issue #788 + #743: cold-compose-aware presence budget on a FRAME-DRIVING wait
        // ([waitForRender]) so the awaited node's recomposition actually advances. The
        // wait early-exits the instant the node appears and tolerates the transient
        // "No compose hierarchies found" ISE under cold compose (the #470 family).
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "host row '$hostRowTag' present") {
            tolerantNodeCountWithTag(hostRowTag) > 0
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "session row '$SESSION_NAME' present") {
            tolerantNodeCountWithText(SESSION_NAME) > 0
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "session screen present") {
            tolerantNodeCountWithTag(TMUX_SESSION_SCREEN_TAG) > 0
        }
        waitForTerminalViewAttached()
    }

    private fun waitForTerminalViewAttached() {
        // Issue #743 de-flake: the Termux TerminalView is an AndroidView interop child
        // whose PLACEMENT into the resumed activity REQUIRES Compose frames to run — so
        // this wait MUST drive frames ([waitForRender], not the idle-decoupled
        // [pollUntil]); an idle-decoupled poll observes tmux "panes ready" yet never
        // sees the interop child because no frame placed it (the round-4 / #470 stall).
        // The old hardcoded 30s was NOT CI/contention-aware — reuse the #788 cold-compose
        // presence budget (90s local / 150s CI), which EARLY-EXITS the instant the view
        // attaches, so a fast emulator pays nothing. Stays under the 300s
        // ci-journey-suite.sh watchdog.
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "terminal view attached") {
            terminalViewAttached()
        }
    }

    private fun terminalViewAttached(): Boolean {
        var attached = false
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            attached = view?.currentSession != null && view.mEmulator != null
        }
        return attached
    }

    private fun waitForConnected(label: String) {
        pollUntil(CONNECTED_TIMEOUT_MS, "Connected after $label") {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        // Issue #743 de-flake: the visible transcript comes from the Termux
        // TerminalView's emulator screen, which only advances as Compose frames drive
        // the interop child — so this MUST be a frame-driving wait ([waitForRender]),
        // not the idle-decoupled [pollUntil]. Early-exits the instant the predicate
        // matches; reads the live transcript via `onActivity` each iteration.
        var last = ""
        waitForRender(timeoutMillis, "visible terminal for $label") {
            last = visibleTerminalText()
            last.isNotBlank() && predicate(last)
        }
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
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

    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no Connecting overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            "expected no Reconnecting/Disconnected pill for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            "expected no disconnect band for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            "expected no Tap Reconnect button for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        // Issue #754: the "Attaching…" SwitchingLoadingPlaceholder overlay is the
        // EXACT surface the maintainer reported on a within-grace return. The old
        // inline foreground probe→connect raised `_switchHidesTerminal` (this tag) on
        // any confirmed-dead probe verdict even inside grace — the D21 violation #754
        // deletes. A within-grace foreground must NEVER paint it.
        assertEquals(
            "expected no 'Attaching…' switching-loading overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        listOf("Connecting", "Reconnecting", "Disconnected", "Tap Reconnect", "Attaching").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label",
                0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
        }
    }

    /**
     * Issue #754 (slice 1c-iv-c): the within-grace foreground reattach must be the
     * NEW driver/controller-owned RESEED-ONLY effect, NOT the deleted inline
     * `probeCurrentRuntimeOnForegroundIfNeeded → connect(LifecycleReattach)` path.
     *
     * This is the per-PR-CI deterministic regression catcher (no toxiproxy needed):
     *  - it asserts a `foreground_reattach outcome=reseed_only` cause-trail was
     *    recorded — the new path's signature; on `main` the within-grace foreground
     *    runs the inline probe, which records `tmux_probe_result` and NEVER
     *    `reseed_only`, so this assertion FAILS on `main`;
     *  - it asserts NO `tmux_probe_result` cause-trail and NO
     *    `foreground_runtime_probe_failed` diagnostic — the deleted probe must not run.
     * Combined with [assertNoVisibleReconnect]'s new `TMUX_SWITCHING_LOADING_TAG`
     * forbid, this pins both the visible behaviour and the owning effect.
     */
    private fun assertWithinGraceReseedOnlyDriverOwned(label: String) {
        val causeTrail = diagnostics!!.eventsNamed("cause_trail")
        val reseedOnly = causeTrail.filter {
            it.fields["stage"] == "foreground_reattach" && it.fields["outcome"] == "reseed_only"
        }
        assertTrue(
            "within-grace foreground for $label must record the driver-owned reseed_only " +
                "cause-trail (the new path); trail=$causeTrail",
            reseedOnly.isNotEmpty(),
        )
        val probeResults = causeTrail.filter { it.fields["stage"] == "tmux_probe_result" }
        assertTrue(
            "within-grace foreground for $label must NOT run the deleted inline probe " +
                "(tmux_probe_result); trail=$probeResults",
            probeResults.isEmpty(),
        )
        assertTrue(
            "within-grace foreground for $label must NOT emit foreground_runtime_probe_failed; " +
                "events=${diagnostics!!.events}",
            diagnostics!!.eventsNamed("foreground_runtime_probe_failed").isEmpty(),
        )
    }

    private fun watchNoVisibleReconnect(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect(label)
            SystemClock.sleep(100)
        }
    }

    private fun assertNoReconnectOrReattachDiagnostics(label: String) {
        val events = diagnostics!!.events
        // Forbid GENUINE reconnect signals only — a within-grace foreground must
        // reuse the live SSH/tmux control client with no new handshake:
        //   * reconnect_start / network_reconnect_start — a real reconnect kicked off.
        //   * foreground_reattach — the VM-level reattach `connect()` request
        //     (TmuxSessionViewModel.kt; category "connection"). NOT the benign
        //     "app"-category fan-out marker below.
        //   * foreground_runtime_probe_failed — the within-grace probe found the
        //     transport dead and triggered a reconnect (TmuxSessionViewModel.kt;
        //     the DiagnosticEvent equivalent of the `tmux_probe_result outcome=failed`
        //     cause-trail record). If this fires within grace, the grace fix has a
        //     residual and this test MUST fail.
        //   * terminal_background_teardown — the runtime was torn down on background.
        //
        // We deliberately DO NOT forbid the `terminal_foreground_reattach`
        // fan-out marker (App.kt). Since #548 / commit 1271a60e, App.kt calls
        // dispatchTmuxForeground() UNCONDITIONALLY on every foreground (even
        // within grace) so a stale transport is probed early. That call records
        // `terminal_foreground_reattach` as a plain dispatch label BEFORE and
        // REGARDLESS of what the hook does — for a healthy within-grace resume
        // the hook just runs a live-channel probe and rides through with zero
        // reconnect. The marker is a dispatch label, not a reconnect; the
        // genuine reconnect signals above (none of which fire within grace) still
        // gate this test.
        val forbidden = events.filter { event ->
            event.name in setOf(
                "reconnect_start",
                "network_reconnect_start",
                "foreground_reattach",
                "foreground_runtime_probe_failed",
                "terminal_background_teardown",
            )
        }
        assertTrue(
            "expected no reconnect/reattach diagnostics for $label; forbidden=$forbidden all=$events",
            forbidden.isEmpty(),
        )
        assertTrue(
            "within-grace cycle must not emit background_grace_elapsed; events=$events",
            diagnostics!!.eventsNamed("background_grace_elapsed").isEmpty(),
        )
    }

    private fun waitForDiagnostic(
        name: String,
        label: String,
        timeoutMs: Long = DIAGNOSTIC_TIMEOUT_MS,
        predicate: (RecordedDiagnosticEvent) -> Boolean = { true },
    ): RecordedDiagnosticEvent {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var matches = emptyList<RecordedDiagnosticEvent>()
        while (SystemClock.elapsedRealtime() < deadline) {
            matches = diagnostics!!.eventsNamed(name).filter(predicate)
            if (matches.isNotEmpty()) return matches.last()
            SystemClock.sleep(50)
        }
        error("timed out waiting for diagnostic '$name' during $label; events=${diagnostics!!.events}")
    }

    private suspend fun waitForClientCountAtLeast(min: Int, label: String) {
        val deadline = SystemClock.elapsedRealtime() + CLIENT_COUNT_TIMEOUT_MS
        var lastCount = -1
        var lastRaw = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastRaw = listClientsRaw()
            lastCount = lastRaw.lines().count { it.isNotBlank() }
            if (lastCount >= min) return
            SystemClock.sleep(100)
        }
        error("expected at least $min tmux clients for $label, got $lastCount; raw=`$lastRaw`")
    }

    /**
     * Issue #743 de-flake: gate the post-grace foreground on the LOCAL `-CC`
     * control-client detach genuinely completing, so the conflating-StateFlow
     * driver collector has drained `Backgrounded` before `Foreground` is
     * submitted (see the call site for the conflation explanation).
     *
     * The clean background teardown ([TmuxSessionViewModel.closeCurrentConnectionAndJoin],
     * launched by the driver's Backgrounded effect) closes the local `-CC` client,
     * whose reader thread then exits and emits `tmux_client_reader_exit` via
     * [TmuxClientDiagnostics]. Waiting for that event proves the LOCAL detach
     * actually finished — a stronger signal than the REMOTE client-count poll,
     * which can read 0 while the local coroutine + driver collector are still in
     * flight.
     *
     * After the local detach lands, pump both the instrumentation idle loop and a
     * short settle so any pending Main-loop turns (the driver collector resuming on
     * `Backgrounded`, the `previous = Backgrounded` assignment) have drained before
     * the foreground submit.
     *
     * HARD-fails if the local detach never lands — it does NOT mask a regression; a
     * foreground that genuinely fails to reattach still times out on the downstream
     * `waitForDiagnostic("foreground_reattach")`.
     */
    private fun waitForPostGraceLocalDetachSettled(label: String) {
        val sink = tmuxDiagnostics
            ?: error("tmux diagnostics sink not installed for $label")
        val deadline = SystemClock.elapsedRealtime() + LOCAL_DETACH_SETTLE_TIMEOUT_MS
        var sawReaderExit = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sink.eventsNamed("tmux_client_reader_exit").isNotEmpty()) {
                sawReaderExit = true
                break
            }
            SystemClock.sleep(50)
        }
        check(sawReaderExit) {
            "expected the local -CC reader to exit (tmux_client_reader_exit) during " +
                "$label before foregrounding; tmux events=${sink.events}"
        }
        // Drain pending Main-loop turns so the conflating driver collector has
        // resumed past `Backgrounded` before the foreground submit.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(LOCAL_DETACH_SETTLE_PUMP_MS)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private suspend fun waitForClientCountAtMost(max: Int, label: String) {
        val deadline = SystemClock.elapsedRealtime() + CLIENT_COUNT_TIMEOUT_MS
        var lastCount = -1
        var lastRaw = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastRaw = listClientsRaw()
            lastCount = lastRaw.lines().count { it.isNotBlank() }
            if (lastCount <= max) return
            SystemClock.sleep(100)
        }
        error("expected at most $max tmux clients for $label, got $lastCount; raw=`$lastRaw`")
    }

    private suspend fun listClientsRaw(): String {
        val key = fixtureKey
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux list-clients -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun clearBackgroundGraceSetting() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("background_grace_millis")
            .commit()
    }

    private fun stopSessionHoldBeforeExpectedTeardown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        SessionConnectionService.stop(ctx)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(250L)
    }

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
                name = "issue548-bg-grace-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue548 Background Grace",
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

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            // Issue #959: the pane must run an INTERACTIVE shell (not `exec sleep`)
            // so the post-grace reattach journey can type a command and assert the
            // shell's FRESH output echoes back — the live-terminal property that a
            // frozen (producer-still-bound-to-the-dead-client) reattach fails. A
            // bare interactive shell stays alive in a detached tmux session, so the
            // within-grace tests (which only check $READY_MARKER + connection) are
            // unaffected. PS1 is pinned so the prompt is deterministic.
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("PS1='$ ' exec bash --noprofile --norc -i"),
            )
            appendLine("sleep 1")
            appendLine(
                "tmux send-keys -t ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$READY_MARKER\\n'") + " Enter",
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue548 tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
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
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
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
        println("ISSUE548_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE548_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE548_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue548-background-grace-reconnect"
        const val SESSION_NAME: String = "issue548-bg-grace-proof"
        const val READY_MARKER: String = "ISSUE548-BG-GRACE-READY"

        const val WITHIN_GRACE_MS: Long = 3_000L
        const val POST_GRACE_MS: Long = 500L
        const val SIX_SECOND_APP_SWITCH_MS: Long = 6_000L
        const val WATCH_NO_RECONNECT_MS: Long = 1_200L

        // Issue #743 de-flake: the flat 8s budgets were a secondary contended-box
        // flake source. Each remote `tmux list-clients` probe opens a FRESH SSH
        // connection (see [listClientsRaw]); on a contended swiftshader box that
        // single connect can take several seconds, and the diagnostic round-trips
        // (e.g. the post-grace teardown chain) are equally slowed. These loops
        // EARLY-EXIT on the deterministic signal, so a fast box pays nothing — only
        // a contended/CI box consumes the headroom. They stay well under the 300s
        // per-test `ci-journey-suite.sh` watchdog and are sequential (the first
        // wedged step fails immediately, so they cannot stack toward 300s).
        val DIAGNOSTIC_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        val CLIENT_COUNT_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L

        // Issue #743 de-flake: budget for the local `-CC` reader exit to land after
        // the driver-owned background teardown is launched (see
        // [waitForPostGraceLocalDetachSettled]). Early-exits on the
        // `tmux_client_reader_exit` signal, so a fast box pays nothing.
        val LOCAL_DETACH_SETTLE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        const val LOCAL_DETACH_SETTLE_PUMP_MS: Long = 250L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
