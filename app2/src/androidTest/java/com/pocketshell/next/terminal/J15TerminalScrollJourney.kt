package com.pocketshell.next.terminal

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.connect.idleWedgeNote
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.tree.SESSION_TREE_TAG
import com.pocketshell.next.tree.sessionRowTag
import com.termux.view.TerminalView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

/**
 * Journey J15 — a finger drag on the terminal scrolls; it never types (#2555).
 *
 * ## The report
 *
 * From the phone: *"when I try to scroll up or down with my finger it gives me
 * up/down. I want to scroll the screen not see previous commands in agents."*
 * Upstream Termux's `TerminalView.doScroll` answered a drag with synthesised
 * `KEYCODE_DPAD_UP`/`DOWN` whenever the ALTERNATE screen was active and mouse
 * tracking was not. In an agent TUI or a shell, arrow-up is prompt/command
 * history — so scrolling back through an agent's output rewrote what the
 * maintainer was typing.
 *
 * ## Why this has to be a device journey
 *
 * `com.termux.view.TerminalScrollGestureTest` pins the branch on the JVM
 * against real captured attach prologues. It cannot see the half that made
 * this a phone bug: a real finger crossing a real touch slop, dispatched by
 * the platform into the vendored view's gesture recogniser, on a session
 * whose emulator got its modes from a real host over a real PTY. Everything
 * from the injected `MotionEvent` to the bytes the remote receives is
 * production code here.
 *
 * ## The oracle is the HOST's key log, not the phone's screen
 *
 * Both defect tests run `pocketshell-altscreen-tui`, a fixture program that
 * takes the alternate screen, never enables mouse tracking, and appends every
 * key it receives to a file. The assertions read that file over an INDEPENDENT
 * SSH connection: "the phone did not draw anything different" is a weak claim
 * (a dropped repaint looks the same), while "the remote process received no
 * keystrokes" is the exact claim the report is about.
 *
 * Each drag test carries its own positive control — a REAL arrow key, pressed
 * after the drag, must land in that same log — so a swipe that silently sent
 * nothing cannot be confused with an oracle that can never see anything.
 * [aDragWithMouseTrackingOnStillReachesTheHostAsAWheelEvent] is the other half
 * of that: it proves the injected drag really is delivered to the vendored
 * view, by taking the one branch that still has a remote effect.
 *
 * ## The two backends, and why both
 *
 * The reproducing state is "alternate buffer active, mouse tracking inactive",
 * and PocketShell reaches it two different ways, measured on the dev box
 * (2026-09-06):
 *
 *  - **tmux**, whose client terminal is on the alternate screen for the whole
 *    of an attach, and whose `mouse` option is **off** by default — so a stock
 *    host is in this state permanently;
 *  - **aplexer**, which forwards an alt-screen workload's `CSI ? 1049 h` and
 *    never enables mouse tracking of its own.
 *
 * A fix validated on only one of them would look green and change nothing on
 * the other, so both are here.
 *
 * ## Fixture
 *
 * The Docker `agents` fixture (see
 * [com.pocketshell.next.connect.AgentsFixture]). Bring it up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J15TerminalScrollJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    /** Which session row this test taps — tmux-backed or aplexer-backed. */
    private var sessionName: String = TMUX_SESSION

    /** The REAL aplexer session id [seedAplexerSession] created, when it ran. */
    private var aplexerId: String? = null

    /**
     * Leaves no live aplexer session behind.
     *
     * The `agents` fixture is shared across the whole journey lane, and an
     * abandoned `<workspace>:<tag>` would both show up in the NEXT journey's
     * session listing and make this class's own next `a start` refuse the
     * duplicate. Best effort by design: a test that failed before the session
     * existed has nothing to clean up.
     */
    @After
    fun stopTheAplexerSession() {
        if (aplexerId != null) {
            runCatching { killAplexerSession() }
            aplexerId = null
        }
    }

    // --- the reported defect, on both backends -------------------------------

    @Test
    fun aDragOnATmuxBackedSessionSendsNoArrowKeys() {
        openSession()
        awaitTui()
        assertReproducingHostState()

        shot("01-tmux-before-drag", JOURNEY)
        dragUp()
        dragDown()
        shot("02-tmux-after-drag", JOURNEY)

        assertNothingWasTyped()
        assertARealArrowKeyStillArrives()
    }

    @Test
    fun aDragOnAnAplexerBackedSessionSendsNoArrowKeys() {
        openSession()
        awaitTui()
        assertReproducingHostState()

        shot("03-aplexer-before-drag", JOURNEY)
        dragUp()
        dragDown()
        shot("04-aplexer-after-drag", JOURNEY)

        assertNothingWasTyped()
        assertARealArrowKeyStillArrives()
    }

    // --- the path that must NOT change, and the delivery control -------------

    /**
     * A host that DOES track the mouse must still get wheel events — and the
     * proof that the injected drag is a real drag.
     *
     * The seed turns `mouse on` on this session's tmux server, which is what
     * the maintainer's own `~/.tmux.conf` does. tmux then enables SGR mouse
     * reporting on the app's emulator, `doScroll` takes its FIRST branch, and
     * the wheel event makes tmux enter copy mode — read here from the host's
     * own `#{pane_in_mode}`, over an independent connection.
     *
     * If the injected gesture never reached the vendored view, this test fails,
     * which is what stops the two tests above from passing vacuously.
     */
    @Test
    fun aDragWithMouseTrackingOnStillReachesTheHostAsAWheelEvent() {
        openSession()
        awaitRendered(SHELL_BANNER)

        val emulator = emulatorState()
        // Both flags at once, which is the whole reason the deleted branch was
        // reachable so often: a tmux attach is ALWAYS on the alternate screen,
        // and only `mouse on` decides which branch that state takes.
        assertTrue(
            "a tmux attach must put the app's emulator on the alternate screen, got $emulator",
            emulator.alternateBuffer,
        )
        assertTrue(
            "the mouse-on fixture must put the app's emulator in mouse tracking, got $emulator",
            emulator.mouseTracking,
        )
        assertEquals(
            "the pane must not be in copy mode before the drag",
            "0",
            paneInMode(),
        )

        shot("05-mouse-on-before-drag", JOURNEY)
        // Downward: dragging the content down is "show me what came before",
        // which reaches the host as wheel-UP. Dragging the other way is
        // wheel-DOWN, and a pane already at the bottom of its scrollback has
        // nowhere to go — a green-looking no-op, not a wheel that failed.
        dragDown()

        val entered = awaitHost("tmux to enter copy mode") { paneInMode() == "1" }
        shot("06-mouse-on-after-drag", JOURNEY)
        assertTrue(
            "the drag must have reached the host as a wheel event (pane_in_mode=${paneInMode()})",
            entered,
        )
        // Copy mode alone would also be reached by a stray keystroke, so pin
        // the thing only a wheel does: the pane scrolled back into its own
        // scrollback.
        val scrolled = scrollPosition()
        assertTrue(
            "the wheel must have scrolled the pane's scrollback, got scroll_position=$scrolled",
            (scrolled.toIntOrNull() ?: 0) > 0,
        )
    }

    // --- assertions ----------------------------------------------------------

    /**
     * The fixture really is the non-happy host this issue is about.
     *
     * Without this the drag assertions would be satisfied by any session that
     * happened to be on the main buffer, which is not the state that broke.
     */
    private fun assertReproducingHostState() {
        val state = emulatorState()
        assertTrue(
            "the fixture must reproduce the reported host state: alternate buffer " +
                "active, got $state",
            state.alternateBuffer,
        )
        assertFalse(
            "the fixture must reproduce the reported host state: mouse tracking " +
                "inactive, got $state",
            state.mouseTracking,
        )
    }

    private fun assertNothingWasTyped() {
        val log = keyLog()
        assertEquals(
            "a finger drag must not send keystrokes to the session. The host's " +
                "program logged: [$log]. Rendered viewport:\n" + renderedTranscript(),
            "",
            log,
        )
        val rendered = squashed(renderedTranscript())
        assertTrue(
            "the session must still show its untouched state (KEYS=0/CURSOR=0), got:\n" +
                renderedTranscript(),
            rendered.contains("KEYS=0") && rendered.contains("CURSOR=0"),
        )
    }

    /**
     * The positive control: a REAL arrow key still reaches the session.
     *
     * This is what keeps [assertNothingWasTyped] honest. An empty key log
     * proves the drag sent nothing only if a keystroke that WAS pressed shows
     * up in the same log through the same path.
     */
    private fun assertARealArrowKeyStillArrives() {
        focusTerminal()
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val arrived = awaitHost("the pressed arrow key to reach the host") {
            keyLog().lines().any { it.trim() == "UP" }
        }
        assertTrue(
            "a real arrow key must still reach the session, or the empty log above " +
                "proves nothing. Host log: [${keyLog()}]",
            arrived,
        )
        shot("07-${sessionName.replace(':', '-')}-real-arrow", JOURNEY)
    }

    // --- the gesture ---------------------------------------------------------

    /** A finger drag upward across the terminal — content scrolls down. */
    private fun dragUp() = drag(fromBottom = true)

    /** A finger drag downward across the terminal — content scrolls up. */
    private fun dragDown() = drag(fromBottom = false)

    /**
     * Injects a real touch drag over the terminal view, in screen coordinates.
     *
     * `UiAutomation.injectInputEvent` rather than a Compose gesture: the
     * terminal is an `AndroidView` and the branch under test lives in the
     * vendored `View`'s own `GestureDetector`, so the events have to travel the
     * platform's dispatch path to prove anything. The travel is deliberately
     * long (most of the view's height) and slow enough to be a scroll rather
     * than a fling, so the recogniser reports `onScroll` — the entry point
     * `doScroll` is reached from.
     */
    private fun drag(fromBottom: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.awaitIdle("before the drag")

        val bounds = IntArray(4)
        instrumentation.runOnMainSync {
            val view = checkNotNull(terminalView()) { "no TerminalView on screen to drag on" }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            bounds[0] = location[0]
            bounds[1] = location[1]
            bounds[2] = view.width
            bounds[3] = view.height
        }
        check(bounds[2] > 0 && bounds[3] > 0) {
            "the TerminalView has no size to drag across (${bounds[2]}x${bounds[3]})"
        }

        val x = (bounds[0] + bounds[2] / 2).toFloat()
        val top = (bounds[1] + bounds[3] * 0.2f)
        val bottom = (bounds[1] + bounds[3] * 0.8f)
        val startY = if (fromBottom) bottom else top
        val endY = if (fromBottom) top else bottom

        val downTime = SystemClock.uptimeMillis()
        inject(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY)
        for (step in 1..DRAG_STEPS) {
            val fraction = step.toFloat() / DRAG_STEPS
            val y = startY + (endY - startY) * fraction
            inject(
                downTime,
                downTime + step * DRAG_STEP_MS,
                MotionEvent.ACTION_MOVE,
                x,
                y,
            )
            SystemClock.sleep(DRAG_STEP_MS)
        }
        inject(
            downTime,
            downTime + (DRAG_STEPS + 1) * DRAG_STEP_MS,
            MotionEvent.ACTION_UP,
            x,
            endY,
        )
        instrumentation.waitForIdleSync()
        compose.awaitIdle("after the drag")
        // The vendored view writes into the session's queue and the bridge
        // forwards it on an IO dispatcher, so an assertion racing that would
        // read an empty log for the wrong reason.
        SystemClock.sleep(SETTLE_MS)
    }

    private fun inject(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    // --- host oracles --------------------------------------------------------

    /** Everything the session's program has been sent, over an independent SSH. */
    private fun keyLog(): String =
        AgentsFixture.exec("cat $KEY_LOG 2>/dev/null || true").trim()

    /** `1` while the tmux pane is in copy mode. Empty for a non-tmux session. */
    private fun paneInMode(): String = paneFormat("#{pane_in_mode}")

    /** How far back into its own scrollback the pane is scrolled. */
    private fun scrollPosition(): String = paneFormat("#{scroll_position}")

    private fun paneFormat(format: String): String =
        AgentsFixture.exec(
            "tmux -S $TMUX_SOCKET display-message -p -t '=$TMUX_SESSION:' " +
                "'$format' 2>/dev/null || true",
        ).trim()

    private fun awaitHost(what: String, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(POLL_MS)
        }
        println("J15_TIMEOUT waiting for $what")
        return false
    }

    // --- device state --------------------------------------------------------

    private data class EmulatorState(
        val alternateBuffer: Boolean,
        val mouseTracking: Boolean,
    ) {
        override fun toString(): String =
            "alternateBuffer=$alternateBuffer mouseTracking=$mouseTracking"
    }

    /**
     * The vendored emulator's own view of the two flags `doScroll` branches on.
     *
     * Read on the main thread, where the emulator parses — the same reason
     * J03's transcript reads are marshalled there.
     */
    private fun emulatorState(): EmulatorState {
        var state = EmulatorState(alternateBuffer = false, mouseTracking = false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val emulator = checkNotNull(terminalView()?.mEmulator) {
                "no live emulator on screen to read"
            }
            state = EmulatorState(
                alternateBuffer = emulator.isAlternateBufferActive,
                mouseTracking = emulator.isMouseTrackingActive,
            )
        }
        return state
    }

    private fun renderedTranscript(): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = terminalView()?.mEmulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private fun terminalView(): TerminalView? =
        findTerminalView(compose.activity.window.decorView)

    private fun findTerminalView(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun focusTerminal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.awaitIdle("before taking terminal focus")
        instrumentation.runOnMainSync {
            checkNotNull(terminalView()) { "no TerminalView on screen to type into" }
                .requestFocus()
        }
        instrumentation.waitForIdleSync()
    }

    private fun squashed(text: String): String = text.filterNot { it.isWhitespace() }

    /**
     * [JourneyScreenshots.capture], mirrored into AGP's additional-test-output
     * directory so the PNG survives the run.
     *
     * `JourneyScreenshots` writes into the app-under-test's external files dir,
     * which AGP deletes along with the app when it uninstalls after the task —
     * so a reviewer asking for this journey's screenshots gets nothing. AGP
     * DOES pull `additionalTestOutputDir` into
     * `app2/build/outputs/connected_android_test_additional_output/`, and it
     * passes that path as an instrumentation argument, so a copy there is the
     * one place a journey screenshot outlives its own run. Best effort: a
     * missing argument (a runner invoked by hand) must not fail the assertion
     * the screenshot merely illustrates.
     */
    private fun shot(name: String, journey: String = JOURNEY): File {
        val file = JourneyScreenshots.capture(name, journey)
        val outputDir = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }
            ?: return file
        runCatching {
            val target = File(File(outputDir, journey).apply { mkdirs() }, file.name)
            file.copyTo(target, overwrite = true)
            println("J15_SCREENSHOT ${target.absolutePath}")
        }
        return file
    }

    // --- navigation ----------------------------------------------------------

    private fun openSession() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)
        awaitTag(sessionRowTag(sessionName))
        compose.onNodeWithTag(sessionRowTag(sessionName)).performClick()
        awaitTag(SESSION_SCREEN_TAG)
    }

    /** Waits until the fixture TUI has painted its banner into the live grid. */
    private fun awaitTui() = awaitRendered(TUI_BANNER)

    /**
     * Waits until [marker] is in the live grid the renderer paints.
     *
     * Whitespace-squashed, for the reason J03 squashes: the phone's column
     * count comes from its own font metrics, so a marker can wrap at a
     * different place than it does on the host.
     */
    private fun awaitRendered(marker: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("waiting for \"$marker\" to render")
            last = renderedTranscript()
            if (squashed(last).contains(marker)) return
            SystemClock.sleep(POLL_MS)
        }
        val failureShot = shot("failure-no-render-$sessionName", JOURNEY)
        throw AssertionError(
            "the terminal never rendered \"$marker\" within ${TIMEOUT_MS}ms.\n" +
                "Rendered viewport was:\n$last\n" +
                "Screenshot: ${failureShot.absolutePath}" + compose.idleWedgeNote(),
        )
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- seed ----------------------------------------------------------------

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J15_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        val aplexerBacked = description.methodName.contains("Aplexer", ignoreCase = true)
        val mouseOn = description.methodName.contains("MouseTrackingOn", ignoreCase = true)
        sessionName = if (aplexerBacked) APLEXER_SESSION else TMUX_SESSION

        // A stale key log from a previous run would fail the NEXT test for the
        // wrong reason; the TUI truncates it on start too, and both matter
        // because a session that never starts must not look like a clean one.
        AgentsFixture.exec("rm -f $KEY_LOG")

        if (aplexerBacked) {
            seedAplexerSession()
        } else {
            seedTmuxSession(mouseOn = mouseOn)
        }

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j15_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j15-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
    }

    /**
     * A real tmux session on its own `tmuxctl-<name>` socket, running the
     * alt-screen fixture TUI as its pane command.
     *
     * [mouseOn] is the difference between a stock host (tmux's own default,
     * where the bug lived) and the maintainer's own `~/.tmux.conf`, which sets
     * `mouse on` — the option that makes tmux enable SGR mouse reporting on the
     * app's emulator and therefore selects `doScroll`'s wheel branch. Setting
     * it AFTER the session exists is deliberate: the fixture image ships no
     * `~/.tmux.conf`, so the default really is off unless a test asks.
     */
    private fun seedTmuxSession(mouseOn: Boolean) {
        AgentsFixture.exec("tmux -S $TMUX_SOCKET kill-session -t '=$TMUX_SESSION' 2>/dev/null || true")
        AgentsFixture.exec("mkdir -p $TMUX_SOCKET_DIR && chmod 700 $TMUX_SOCKET_DIR")
        val marker: String
        if (mouseOn) {
            // A NORMAL-screen pane with scrollback, because that is what a
            // wheel event has a defined meaning for: tmux 3.7 drops a wheel on
            // an alternate-screen pane (it has no history to scroll), so an
            // alt-screen pane here would make the wheel branch unobservable and
            // the delivery control vacuous. Verified against the fixture image.
            AgentsFixture.exec(
                "tmux -S $TMUX_SOCKET new-session -d -s $TMUX_SESSION " +
                    "-c /home/testuser -x 80 -y 24",
            )
            AgentsFixture.exec(
                "tmux -S $TMUX_SOCKET send-keys -t '=$TMUX_SESSION:' 'PS1=\"$PROMPT \"' Enter",
            )
            AgentsFixture.exec(
                "tmux -S $TMUX_SOCKET send-keys -t '=$TMUX_SESSION:' " +
                    "'clear; seq 1 200; echo $SHELL_BANNER' Enter",
            )
            marker = SHELL_BANNER
        } else {
            AgentsFixture.exec(
                "tmux -S $TMUX_SOCKET new-session -d -s $TMUX_SESSION -c /home/testuser " +
                    "-x 80 -y 24 /usr/local/bin/pocketshell-altscreen-tui",
            )
            marker = TUI_BANNER
        }
        // Set explicitly either way: the fixture image ships no `~/.tmux.conf`,
        // so `off` is tmux's own default AND the state the maintainer's stock
        // hosts are in — writing it down is what stops a future image growing a
        // config and silently retiring the reproduction.
        AgentsFixture.exec(
            "tmux -S $TMUX_SOCKET set-option -g mouse ${if (mouseOn) "on" else "off"}",
        )
        SystemClock.sleep(FIXTURE_SETTLE_MS)
        val pane = AgentsFixture.exec(
            "tmux -S $TMUX_SOCKET capture-pane -p -t '=$TMUX_SESSION:' 2>/dev/null || true",
        )
        check(pane.filterNot { it.isWhitespace() }.contains(marker)) {
            "the fixture tmux session did not come up: capture-pane says\n$pane"
        }
    }

    /**
     * A REAL aplexer session running the alt-screen fixture TUI, plus the
     * listing row that points the app at it.
     *
     * Nothing here fakes an attach. #2563 put the pinned production `a` (and
     * its sibling worker) in the `agents` image next to `/usr/bin/python3`, so
     * `a start` makes a genuine durable PTY session and `a attach` — which the
     * fixture's `pocketshell sessions attach` `execvp`s into — hands the app
     * that session's real stream. The id is read back from `a start --json`
     * rather than invented: the attach arm resolves the row's `id`, and a made
     * up one would attach to nothing.
     *
     * The listing row still comes from `POCKETSHELL_FIXTURE_APLEXER_FILE`,
     * which is where `pocketshell-fixture-sessions` reads aplexer rows from
     * (#2563 moved that read out of the deleted `a` stub). Its `name` follows
     * the real CLI's `<workspace-basename>:<tag>` convention
     * (`session_enum.aplexer_display_name`) so the row is the shape the app
     * would see from a real enumeration.
     */
    private fun seedAplexerSession() {
        // $LIVE_MARKER: issue #2586. A journey that opted into the fixture's
        // LIVE aplexer arm leaves that marker in the shared container (#2474
        // runs the suite unfiltered, one process, one fixture), and marker +
        // the seed written below is a deliberate rc-78 mode conflict — the app
        // would receive no listing at all and this journey would fail as a bare
        // 60-second Compose timeout. Cleared with the errors file it already
        // clears, so the deterministic arm defends itself.
        AgentsFixture.exec("rm -f $ERRORS_FILE $LIVE_MARKER")
        // One live session per run: a leftover from a previous run would be a
        // second `<workspace>:<tag>` and `a start` refuses the duplicate.
        killAplexerSession()
        AgentsFixture.exec("mkdir -p $APLEXER_WORKSPACE")
        val started = AgentsFixture.exec(
            "$APLEXER_BIN start --json --workspace $APLEXER_WORKSPACE " +
                "--tag $APLEXER_TAG --cwd $APLEXER_WORKSPACE " +
                "-- /usr/local/bin/pocketshell-altscreen-tui 2>&1",
        )
        val id = APLEXER_ID_JSON.find(started)?.groupValues?.get(1)
        checkNotNull(id) {
            "`a start` did not report a session id — the fixture has no real " +
                "aplexer session to attach to. It said:\n$started"
        }
        aplexerId = id
        println("J15_APLEXER_SESSION id=$id workspace=$APLEXER_WORKSPACE tag=$APLEXER_TAG")

        val now = System.currentTimeMillis() / 1000
        AgentsFixture.writeFile(
            APLEXER_FILE,
            """
            {
              "sessions": [
                {
                  "name": "$APLEXER_SESSION",
                  "id": "$id",
                  "workspace": "$APLEXER_WORKSPACE",
                  "tag": "$APLEXER_TAG",
                  "engine": "shell",
                  "profile": null,
                  "agent_state": "waiting",
                  "agent_state_source": "heuristic",
                  "attached": false,
                  "created_epoch": ${now - 60},
                  "activity_epoch": ${now - 5}
                }
              ]
            }
            """.trimIndent(),
        )

        // The session's own screen, straight from aplexer — the fixture must be
        // painting the alt-screen TUI before the app ever attaches, or a green
        // "nothing was typed" later would be green over a dead session.
        val screen = awaitAplexerScreen()
        check(screen.filterNot { it.isWhitespace() }.contains(TUI_BANNER)) {
            "the aplexer session did not come up: `a capture` says\n$screen"
        }

        // ...and the HOST enumerates it. `pocketshell-fixture-sessions` swallows
        // an unreadable or malformed seed and returns no aplexer rows at all, so
        // a broken seed reads to the app as "aplexer has nothing" — exit 0,
        // managers: ["tmux"], and a 60 s Compose timeout on a row that was never
        // going to appear. Asserting the listing here turns that silence into a
        // named seed failure. (It caught a real one: a Kotlin template escaped
        // one `$` too many wrote the template text into the JSON verbatim.)
        val listed = AgentsFixture.exec("pocketshell sessions list --json 2>&1")
        check(listed.contains("\"$APLEXER_SESSION\"")) {
            "the host does not enumerate the seeded aplexer row " +
                "\"$APLEXER_SESSION\". `pocketshell sessions list --json` said:\n$listed"
        }
    }

    /** `a capture --screen`, polled until the workload has painted. */
    private fun awaitAplexerScreen(): String {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = AgentsFixture.exec(
                "$APLEXER_BIN capture --screen --plain --workspace $APLEXER_WORKSPACE " +
                    "--tag $APLEXER_TAG 2>&1 || true",
            )
            if (last.filterNot { it.isWhitespace() }.contains(TUI_BANNER)) return last
            SystemClock.sleep(POLL_MS)
        }
        return last
    }

    /** Best effort: leave no live aplexer session behind for the next run. */
    private fun killAplexerSession() {
        AgentsFixture.exec(
            "$APLEXER_BIN kill --workspace $APLEXER_WORKSPACE --tag $APLEXER_TAG " +
                ">/dev/null 2>&1 || true",
        )
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L

        /** Let the fixture session paint its first frame before it is asserted on. */
        const val FIXTURE_SETTLE_MS = 1_000L

        /**
         * How long the drag's effect is given to cross the wire before the key
         * log is read. The view writes into the vendored session queue and the
         * bridge forwards it from an IO dispatcher, so reading immediately
         * would report "nothing was sent" for a bug that had just sent
         * something.
         */
        const val SETTLE_MS = 1_500L

        /** A long, slow travel: a scroll gesture, not a fling. */
        const val DRAG_STEPS = 12
        const val DRAG_STEP_MS = 30L

        const val JOURNEY = "j15-terminal-scroll"

        const val TMUX_SESSION = "j15-scroll"

        /**
         * The real pinned `a`, spelled out for the reason `Dockerfile.agents`
         * gives: exactly ONE copy exists, next to `/usr/bin/python3`, because
         * `pocketshell.aplexer` resolves it there and nowhere else (D22,
         * #2543). A PATH lookup here would be the shape that made the fixture
         * green-and-empty before #2563.
         */
        const val APLEXER_BIN = "/usr/bin/a"

        const val APLEXER_WORKSPACE = "/home/testuser/j15-aplexer"
        const val APLEXER_TAG = "scroll"

        /**
         * `<workspace-basename>:<tag>` — the real CLI's listing name
         * (`session_enum.aplexer_display_name`), so the seeded row is the shape
         * a real enumeration would produce.
         */
        const val APLEXER_SESSION = "j15-aplexer:$APLEXER_TAG"

        /** The `"id": "<uuid>"` `a start --json` reports. */
        val APLEXER_ID_JSON = Regex("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")

        const val TUI_BANNER = "POCKETSHELL-ALTSCREEN-TUI"

        /** The mouse-on variant runs a plain shell; these pin its screen. */
        const val PROMPT = "J15READY\$"
        const val SHELL_BANNER = "J15-SCROLL-BANNER"

        /** Where `pocketshell-altscreen-tui` records the keys it is sent. */
        const val KEY_LOG = "\$HOME/.pocketshell-fixture-altscreen-keys.log"

        const val APLEXER_FILE = "\$HOME/.pocketshell-fixture-aplexer.json"
        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"

        /** The fixture's LIVE-aplexer opt-in marker (issue #2586); cleared, never set. */
        const val LIVE_MARKER = "\$HOME/.pocketshell-fixture-aplexer-live"

        const val TMUX_SOCKET_DIR = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""
        const val TMUX_SOCKET =
            "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$TMUX_SESSION\""

        val HOST_IDS: Map<String, Long> = mapOf(
            "aDragOnATmuxBackedSessionSendsNoArrowKeys" to 9_501L,
            "aDragOnAnAplexerBackedSessionSendsNoArrowKeys" to 9_502L,
            "aDragWithMouseTrackingOnStillReachesTheHostAsAWheelEvent" to 9_503L,
        )
    }
}
