package com.pocketshell.next.terminal

import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.tree.SESSION_TREE_TAG
import com.pocketshell.next.tree.sessionRowTag
import com.termux.view.TerminalView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J03 — attach to a real session on a real host, see it render, type
 * into it (rewrite task U-4). This is the point of the app.
 *
 * ## Why this has to be a device journey
 *
 * `SessionViewModelTest` drives the same ViewModel and the same
 * [TerminalPtyBridge] over a scripted connection on the host JVM, and it cannot
 * see any of the things that break here: `pocketshell sessions attach` not
 * resolving a session, an sshj `exec` channel that never allocates a PTY, a
 * vendored `TerminalSession` whose reflected fields moved, a `libtermux.so`
 * that fails to load, a `TerminalView` that lays out to 0x0 and therefore never
 * creates an emulator, or a keyboard path that reaches nothing. Everything from
 * the host tap to the pixels is production code against a real sshd here.
 *
 * ## The oracle is the EMULATOR'S OWN SCREEN BUFFER, cross-checked on the host
 *
 * Assertions read `TerminalBuffer.getTranscriptText()` off the live
 * `TerminalView` in the running Activity — the exact text the renderer paints —
 * never ViewModel state (the D29 lesson: internal state green while the screen
 * is broken is the failure this project has already paid for). Each assertion
 * is then cross-checked against `tmux capture-pane -p` run over an INDEPENDENT
 * SSH connection, so "the phone shows it" and "the host has it" have to agree:
 * a device-only assertion could pass on locally echoed bytes that never left,
 * and a host-only assertion could pass with a black screen.
 *
 * ## Fixture
 *
 * The Docker `agents` fixture (see [AgentsFixture]). [seed] creates a REAL tmux
 * session on a real `tmuxctl-<name>` socket — the shape `pocketshell sessions
 * list` enumerates and `pocketshell sessions attach` resolves — with a pinned
 * `PS1` so "the shell prompt is on screen" is an assertable string rather than
 * a guess about Alpine's ash defaults.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 *
 * Per-test host ids, for the reason J01/J02 use them: SQLite reuses
 * `max(id) + 1`, and a reused id plus the registry's one-connection-per-host
 * cache would let a later test pass on an earlier test's connection.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J03AttachAndTypeJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    /**
     * Puts the device back the way it was found.
     *
     * The rotation test is the only one here that touches orientation, and a
     * run that fails midway through it would otherwise leave the shared
     * emulator in landscape — where the NEXT test class, and every parallel
     * agent's run on the same AVD, starts from a screen shape it did not ask
     * for. Best effort: if the Activity is already gone there is nothing to
     * restore.
     */
    @After
    fun restoreOrientation() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J03_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedTmuxSession()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j03_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j03-${description.methodName}", privateKeyPath = keyPath),
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
     * Creates the session on its own `tmuxctl-<name>` socket — the per-session
     * socket convention `sessions attach` resolves against — and paints a known
     * prompt plus a marker line into it.
     *
     * Recreated per test rather than reused, so a test that types into the pane
     * cannot leave text behind that would make the NEXT test's assertion pass
     * for the wrong reason.
     */
    private fun seedTmuxSession() {
        AgentsFixture.exec("tmux -S $SOCKET kill-session -t '=$SESSION' 2>/dev/null || true")
        // `tmux -S <path>` binds the path as given and does NOT create its
        // parent, unlike `-L`. The enumerator scans this exact directory.
        AgentsFixture.exec("mkdir -p $SOCKET_DIR && chmod 700 $SOCKET_DIR")
        AgentsFixture.exec(
            "tmux -S $SOCKET new-session -d -s $SESSION -c /home/testuser -x 80 -y 24",
        )
        // A pinned prompt: Alpine's ash default PS1 is not something a test
        // should be guessing at, and this is still the shell's real prompt.
        AgentsFixture.exec("tmux -S $SOCKET send-keys -t '=$SESSION:' 'PS1=\"$PROMPT \"' Enter")
        AgentsFixture.exec("tmux -S $SOCKET send-keys -t '=$SESSION:' 'clear; echo $BANNER' Enter")
        SystemClock.sleep(500)
        val pane = capturePane()
        check(squashed(pane).contains(BANNER)) {
            "the fixture tmux session did not come up: capture-pane says\n$pane"
        }
    }

    /**
     * The headline journey: tap a host, tap a session, watch a real shell
     * render, type a command, watch its output come back.
     */
    @Test
    fun attachingToARealSessionRendersItAndAcceptsTyping() {
        openSession()

        // 1. The pane the host has is the pane the phone draws. Both sides are
        //    read fresh; neither is a constant this file made up.
        val rendered = awaitTranscript("the fixture's banner line") { it.contains(BANNER) }
        JourneyScreenshots.capture("01-attached", JOURNEY)
        assertTrue(
            "the live shell prompt must be on screen, got:\n$rendered",
            squashed(rendered).contains(PROMPT),
        )
        assertTrue(
            "the host's own view of the pane must contain what the phone drew",
            squashed(capturePane()).contains(BANNER),
        )

        // 2. Type. Real key events into the focused terminal view — the same
        //    path a hardware keyboard and the IME take.
        typeLine("echo $MARKER")

        // 3. The typed command AND its output came back from the host.
        val afterTyping = awaitTranscript("the echoed marker twice") {
            // Twice: once as the shell echoed the typed line back, once as the
            // command's own output. A single occurrence is what a screen that
            // merely rendered the keystrokes locally would show.
            squashed(it).split(MARKER).size >= 3
        }
        JourneyScreenshots.capture("02-typed", JOURNEY)
        assertTrue(
            "the rendered viewport must show the command's output, got:\n$afterTyping",
            squashed(afterTyping).contains(MARKER),
        )
        // And the host agrees: the bytes really crossed the wire.
        val pane = capturePane()
        assertTrue(
            "the host's pane must show the typed command, got:\n$pane",
            squashed(pane).contains("echo$MARKER"),
        )
    }

    /**
     * The non-happy host: the tree listed a session that has since died.
     *
     * `pocketshell sessions attach` exits 3, and the screen must SAY so rather
     * than sit on "Attaching…" forever or show an empty black terminal. There
     * is deliberately no retry (task U-7 owns reconnect) — the one affordance
     * is the way back.
     */
    @Test
    fun attachingToAVanishedSessionSaysSoInsteadOfHangingOnAttaching() {
        // The row has to EXIST to be tapped, and the session has to be GONE by
        // the time the attach runs — which is the real race the maintainer hits
        // (the tree is a snapshot, sessions end). Killing it here rather than in
        // `seed()` is what produces that ordering; killing it earlier just
        // removes the row and tests nothing.
        openTree()
        awaitTag(sessionRowTag(SESSION))
        AgentsFixture.exec("tmux -S $SOCKET kill-session -t '=$SESSION' 2>/dev/null || true")
        compose.onNodeWithTag(sessionRowTag(SESSION)).performClick()
        awaitTag(SESSION_SCREEN_TAG)

        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(SESSION_ERROR_BANNER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        JourneyScreenshots.capture("03-vanished-session", JOURNEY)
        compose.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertIsDisplayed()
        // WHY it failed has to survive to the screen, not just THAT it failed:
        // exit 3 is `pocketshell sessions attach`'s "no session named ...", and
        // a message that dropped it would read identically to a clean detach.
        compose.onNode(hasText("exit 3", substring = true)).assertIsDisplayed()
        // Not stuck on the attaching state, and no terminal pretending to work.
        compose.onNodeWithTag(SESSION_CONNECTING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_TERMINAL_TAG).assertDoesNotExist()

        // Back is the way out, and it works.
        compose.onNodeWithTag(SESSION_BACK_TAG).performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(SESSION_TREE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SESSION_TREE_TAG).assertIsDisplayed()
    }

    /**
     * Task U-5: the remote's idea of the terminal size follows the phone's.
     *
     * `stty size` is asked INSIDE the live session, so the number under test is
     * the one the shell will wrap at — not a field on a ViewModel and not the
     * emulator's own grid, either of which can be right while the remote is
     * still wrapping at 80x24. Every reading is cross-checked against the
     * host's own `#{pane_width}x#{pane_height}` over an independent SSH
     * connection, because a locally-echoed `stty` reply would look identical to
     * a resize that never crossed the wire.
     *
     * Two viewport changes, the two the maintainer actually does: raising the
     * keyboard (which must take rows and leave columns alone) and rotating
     * (which swaps them). Both must also come BACK — a resize path that only
     * ever shrinks leaves the session unusable after the keyboard closes.
     *
     * Then the awkward one: a viewport change that lands in the MIDDLE of a
     * measurement. Each of those gestures is a stream of sizes (an IME inset
     * animation reports a new one per frame), and the app must end up telling
     * the remote the size it settled at — not one from the middle of the
     * animation, which leaves the pane wrapping at a width the screen does not
     * have and no further layout change to correct it.
     */
    @Test
    fun theRemoteTerminalSizeTracksTheKeyboardAndRotation() {
        openSession()
        awaitTranscript("the fixture's banner line") { it.contains(BANNER) }

        val closed = remoteSize("keyboard down", keyboardUp = false)
        JourneyScreenshots.capture("04-keyboard-down", JOURNEY)

        showKeyboard()
        val opened = remoteSize("keyboard up", keyboardUp = true)
        JourneyScreenshots.capture("05-keyboard-up", JOURNEY)
        assertTrue(
            "the keyboard must cost the remote rows: closed=$closed opened=$opened",
            opened.rows < closed.rows,
        )
        assertEquals(
            "the keyboard takes height, not width: closed=$closed opened=$opened",
            closed.cols,
            opened.cols,
        )

        hideKeyboard()
        val reclosed = remoteSize("keyboard down again", keyboardUp = false)
        assertEquals(
            "closing the keyboard must give the rows back: was $closed, now $reclosed",
            closed,
            reclosed,
        )

        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        val landscape = remoteSize("landscape", keyboardUp = false)
        JourneyScreenshots.capture("06-landscape", JOURNEY)
        assertTrue(
            "landscape must widen the remote terminal: portrait=$closed landscape=$landscape",
            landscape.cols > closed.cols,
        )
        assertTrue(
            "landscape must shorten the remote terminal: portrait=$closed landscape=$landscape",
            landscape.rows < closed.rows,
        )

        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val backToPortrait = remoteSize("portrait again", keyboardUp = false)
        assertEquals(
            "rotating back must restore the size: was $closed, now $backToPortrait",
            closed,
            backToPortrait,
        )

        // A viewport change can land in the MIDDLE of a command, and on a
        // device with no hardware keyboard the framework raises the IME by
        // itself when the terminal takes focus — so this is not a contrived
        // race, it is the one that left the CI run of this journey waiting a
        // full minute for a `stty size` reply describing a size the remote no
        // longer had. Both ends must converge afterwards, on the size the
        // layout settled at.
        val afterMidFlightKeyboard =
            remoteSize("a keyboard arriving mid-measurement", keyboardUp = false) {
                showKeyboard()
            }
        assertEquals(
            "a viewport change during a measurement must still leave both ends " +
                "agreeing: was $closed, now $afterMidFlightKeyboard",
            closed,
            afterMidFlightKeyboard,
        )
    }

    /**
     * Task U-5: Ctrl on the key bar plus `c` on the keyboard is a real SIGINT.
     *
     * The oracle is the HOST's process table, not the screen. A terminal shows
     * `^C` for any number of reasons — a locally echoed control glyph, a shell
     * printing it on its own — and the only claim worth making is that the
     * running process died. So the journey starts a uniquely-named `sleep`,
     * waits until the host reports it running (otherwise the interrupt could be
     * "proven" against a command that never started), interrupts it, and waits
     * for it to be gone.
     *
     * Then it types a command and watches the output come back, because an
     * interrupt that also wedged the session would satisfy the first half.
     */
    @Test
    fun ctrlFromTheKeyBarInterruptsARunningCommand() {
        openSession()
        awaitTranscript("the fixture's banner line") { it.contains(BANNER) }

        typeLine("sleep $SLEEP_SECONDS")
        awaitHostSleep(running = true)
        JourneyScreenshots.capture("07-sleeping", JOURNEY)

        // The whole point of the key bar: the modifier comes from the bar, the
        // letter from the keyboard, because a phone keyboard has every letter
        // and no Ctrl.
        compose.onNodeWithText(KEY_LABEL_CTRL).assertIsDisplayed()
        compose.onNodeWithText(KEY_LABEL_CTRL).performClick()
        compose.waitForIdle()
        typeCharacter('c')

        awaitHostSleep(running = false)
        JourneyScreenshots.capture("08-interrupted", JOURNEY)

        // ...and the session is still usable afterwards. Twice, for the reason
        // the headline test explains: once as the shell echoes the typed line,
        // once as the command's own output.
        typeLine("echo $CTRL_MARKER")
        awaitTranscript("the post-interrupt marker twice") {
            it.split(CTRL_MARKER).size >= 3
        }
        assertTrue(
            "the host must show the post-interrupt command ran",
            squashed(capturePane()).contains(CTRL_MARKER),
        )
    }

    /**
     * Task U-5: Esc and Enter on the key bar reach the remote as real bytes.
     *
     * Enter is the load-bearing one — a bar that sent `\n` instead of `\r`
     * submits nothing to a line editor — and it is asserted the only way that
     * distinguishes them: by typing a command WITHOUT a newline and letting the
     * bar's Enter run it.
     */
    @Test
    fun theKeyBarsEnterSubmitsATypedCommand() {
        openSession()
        awaitTranscript("the fixture's banner line") { it.contains(BANNER) }

        // Typed WITHOUT the Enter key event `typeLine` appends.
        typeCharacters("echo $ENTER_MARKER")
        val beforeEnter = awaitTranscript("the un-submitted command echoed back") {
            it.contains(ENTER_MARKER)
        }
        assertEquals(
            "the command must NOT have run yet, or the key bar's Enter proves nothing:\n" +
                beforeEnter,
            2,
            squashed(beforeEnter).split(ENTER_MARKER).size,
        )

        compose.onNodeWithText(KEY_LABEL_ENTER).performClick()

        awaitTranscript("the marker echoed and run") { it.split(ENTER_MARKER).size >= 3 }
        JourneyScreenshots.capture("09-key-bar-enter", JOURNEY)
        assertTrue(
            "the host must show the command the key bar's Enter submitted",
            squashed(capturePane()).contains(ENTER_MARKER),
        )
    }

    // --- helpers ----------------------------------------------------------

    /** Host tap → the session tree for that host. */
    private fun openTree() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)
    }

    /** ...and on into the fixture session's terminal. */
    private fun openSession() {
        openTree()
        awaitTag(sessionRowTag(SESSION))
        compose.onNodeWithTag(sessionRowTag(SESSION)).performClick()
        awaitTag(SESSION_SCREEN_TAG)
    }

    /**
     * Polls the LIVE emulator's screen buffer until [predicate] holds.
     *
     * Two things here are load bearing and were each a red run first:
     *
     *  - [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForIdle] on
     *    every turn. Under a Compose test rule the app's frame clock is DRIVEN BY
     *    THE TEST, so a plain sleep-poll loop starves recomposition: the
     *    `Connecting -> Live` state change never gets a frame, the `AndroidView`
     *    hosting the terminal is never created, and the transcript stays empty
     *    forever while every ViewModel-level thing is perfectly fine. (That is
     *    the D29 failure mode with the polarity flipped, and it is exactly why
     *    the assertion reads the rendered grid rather than the ViewModel.)
     *  - Reading the grid on the MAIN thread, because that is where the vendored
     *    emulator parses and renders; reading it from the instrumentation thread
     *    is a data race whose failures look like flakes.
     */
    private fun awaitTranscript(what: String, predicate: (String) -> Boolean): String =
        awaitRenderedTranscript(what) { predicate(squashed(it)) }

    /**
     * The same poll over the RAW transcript, newlines intact.
     *
     * Line boundaries matter to anything reading `stty size`, which prints
     * `rows cols` on a line of its own: squashing them would turn `64 90`
     * followed by the next prompt into one run of digits and letters. The U-5
     * resize measurements read the same raw text through [sizeLines], on their
     * own retry loop ([remoteSize]).
     */
    private fun awaitRenderedTranscript(what: String, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            last = renderedTranscript()
            if (predicate(last)) return last
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "the terminal never rendered $what within ${TIMEOUT_MS}ms.\n" +
                "Screen state: ${screenDiagnosis()}\n" +
                "Rendered viewport was:\n$last\n" +
                "The host's own capture-pane says:\n" + capturePane() + "\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    /**
     * Which of the session screen's three states is on screen, and whether the
     * terminal view exists and has a grid.
     *
     * Without this, "the transcript is empty" is ambiguous between four very
     * different bugs — never attached, attached but not pumping, attached but
     * the view never laid out, and attached but the view is not in the
     * hierarchy — and each failed run would cost another round trip to tell
     * them apart.
     */
    private fun screenDiagnosis(): String {
        fun present(tag: String) =
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

        // Sampled BEFORE the view dump: reading the semantics tree also syncs
        // the composition, and a dump taken first would describe a hierarchy
        // one frame older than the state it is reported next to.
        val chrome = "connecting=${present(SESSION_CONNECTING_TAG)} " +
            "error=${present(SESSION_ERROR_BANNER_TAG)} " +
            "terminalComposed=${present(SESSION_TERMINAL_TAG)}"

        var view = "no TerminalView in the hierarchy"
        var tree = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            terminalView()?.let {
                view = "TerminalView ${it.width}x${it.height}, focused=${it.isFocused}, " +
                    "emulator=" + (it.mEmulator?.let { e -> "${e.mColumns}x${e.mRows}" } ?: "null")
            }
            tree = dumpHierarchy(compose.activity.window.decorView, 0)
        }
        return "$chrome; $view\nview tree:\n$tree"
    }

    private fun dumpHierarchy(view: View, depth: Int): String {
        val head = "  ".repeat(depth) + view.javaClass.name +
            " ${view.width}x${view.height}\n"
        if (view !is ViewGroup) return head
        return head + (0 until view.childCount).joinToString("") {
            dumpHierarchy(view.getChildAt(it), depth + 1)
        }
    }

    /**
     * The text the terminal is actually showing, straight out of the vendored
     * `TerminalBuffer`. Empty string while the view has not created its
     * emulator yet (it needs one non-zero layout pass first).
     */
    private fun renderedTranscript(): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = terminalView()?.mEmulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    /** Finds the hosted [TerminalView] in the running Activity, or null. */
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

    /**
     * Types [line] followed by Enter as real key events.
     *
     * `sendStringSync` injects the same `KeyEvent`s a keyboard produces, so this
     * exercises `TerminalView.onKeyDown` → `inputCodePoint` →
     * `TerminalSession.write` → the bridge → the PTY. Writing to the session
     * directly would skip every one of those.
     */
    private fun typeLine(line: String) {
        typeCharacters(line)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        instrumentation.waitForIdleSync()
    }

    /**
     * Types [text] with NO trailing Enter.
     *
     * Split out for U-5: proving the key bar's Enter really submits a command
     * requires a command sitting there unsubmitted first, and a helper that
     * always appended Enter could never produce one.
     */
    private fun typeCharacters(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        focusTerminal()
        instrumentation.sendStringSync(text)
        instrumentation.waitForIdleSync()
    }

    /** One character, the same real key-event path. */
    private fun typeCharacter(character: Char) = typeCharacters(character.toString())

    /**
     * Puts the keyboard focus back on the terminal.
     *
     * Called before every injection because a tap on a Compose key bar slot
     * takes focus with it — and an injected key event goes to whatever the
     * window says is focused, so without this the letter after a Ctrl tap would
     * be delivered to the bar and silently vanish.
     */
    private fun focusTerminal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        instrumentation.runOnMainSync {
            val view = terminalView()
            checkNotNull(view) { "no TerminalView on screen to type into" }
            view.requestFocus()
        }
        instrumentation.waitForIdleSync()
    }

    // --- U-5: size, keyboard, rotation ------------------------------------

    /** A terminal size as both ends of the wire report it. */
    private data class RemoteSize(val cols: Int, val rows: Int) {
        override fun toString(): String = "${cols}x$rows"
    }

    /**
     * The size the live session believes it has, agreed by both ends.
     *
     * The host's own `#{pane_width}`/`#{pane_height}` is read first, over an
     * INDEPENDENT SSH connection, and polled until it stops moving — a resize
     * crosses the wire asynchronously and asserting mid-flight would be a
     * flake generator. Then `stty size` is typed INTO the session and required
     * to report that exact size: the pane geometry proves the resize reached
     * tmux, and `stty` proves it reached the process that actually wraps text.
     *
     * ## Why the whole thing is a RETRY loop, and why it pins the keyboard
     *
     * Both readings describe a live phone, and a phone's viewport moves on its
     * own. Two things do it here: the framework raises the IME by itself when
     * a text-editor view takes focus on a device with no hardware keyboard
     * (which `typeLine` triggers), and an inset animation is still settling
     * for a few frames after the test thinks it is done. A measurement that
     * read the host once and then waited for the shell to confirm THAT number
     * can never recover from either — the remote has legitimately moved on,
     * and the reply describing the new size is not the one being waited for.
     * (That is exactly how this journey failed in CI: a 60 s wait for
     * `49 63` from a session that had already been resized to 24 rows.)
     *
     * So each attempt re-asserts the keyboard state it is measuring under,
     * re-reads the host, and asks again; an attempt whose host size moves
     * underneath it is abandoned early rather than waited out. What is being
     * asserted is unchanged and no weaker: both ends must agree on one size,
     * measured with the keyboard where the caller says it is.
     *
     * @param keyboardUp the IME state this measurement is about. Re-asserted
     *   (not assumed) on every attempt, because the framework can raise the
     *   keyboard between two of them.
     * @param viewportMoves runs after the host has been read and before the
     *   command is typed — the seam a real mid-measurement viewport change
     *   arrives through, and the only way to pin that the loop survives one.
     */
    private fun remoteSize(
        what: String,
        keyboardUp: Boolean,
        viewportMoves: () -> Unit = {},
    ): RemoteSize {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var host: RemoteSize? = null
        // Once, on the first attempt: it stands for a one-off event (the IME
        // arriving), and re-running it every time would be a viewport that
        // never stops moving, which is a different — and unmeasurable — thing.
        var pendingMove = viewportMoves
        while (SystemClock.elapsedRealtime() < deadline) {
            ensureKeyboard(visible = keyboardUp)
            val current = settledHostPaneSize(what)
            host = current
            pendingMove()
            pendingMove = {}
            // Counted BEFORE typing so the assertion needs a FRESH reply: two
            // consecutive measurements can legitimately expect the same
            // numbers (closing the keyboard restores the size it opened on),
            // and matching the previous measurement's line would be a green
            // assertion for a command that never ran.
            val repliesBefore = sizeLines(renderedTranscript()).size
            typeLine("stty size")
            if (awaitSizeReply(current, repliesBefore)) {
                // Printed so a run's own log carries the evidence: a green
                // assertion that both ends agree says nothing about whether the
                // number CHANGED.
                println("J03_REMOTE_SIZE $what stty=${current.rows} ${current.cols} pane=$current")
                return current
            }
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "$what: the phone and the host never agreed on a terminal size within " +
                "${TIMEOUT_MS}ms (last host pane=$host).\n" +
                "Screen state: ${screenDiagnosis()}\n" +
                "Rendered viewport was:\n" + renderedTranscript() + "\n" +
                "The host's own capture-pane says:\n" + capturePane() + "\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    /**
     * Waits for a FRESH `stty size` reply — one beyond the [repliesBefore]
     * already on screen — to say [host], giving up as soon as the host's own
     * pane size stops being [host].
     *
     * The early give-up is the point: once the pane has moved, no reply to the
     * command just typed can ever match, so waiting out the clock only turns a
     * recoverable situation into a timeout.
     */
    private fun awaitSizeReply(host: RemoteSize, repliesBefore: Int): Boolean {
        val expected = "${host.rows} ${host.cols}"
        val deadline = SystemClock.elapsedRealtime() + SIZE_REPLY_TIMEOUT_MS
        var nextHostCheck = SystemClock.elapsedRealtime() + SIZE_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            val replies = sizeLines(renderedTranscript())
            if (replies.size > repliesBefore && replies.last() == expected) return true
            if (SystemClock.elapsedRealtime() >= nextHostCheck) {
                if (hostPaneSize() != host) return false
                nextHostCheck = SystemClock.elapsedRealtime() + SIZE_SETTLE_MS
            }
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    /**
     * Puts the keyboard where a measurement says it is, and does nothing when
     * it is already there.
     *
     * Cheap on purpose: [showKeyboard]/[hideKeyboard] each wait out an inset
     * animation, which is the right price for a state CHANGE and the wrong one
     * for the common case of "still where we left it".
     */
    private fun ensureKeyboard(visible: Boolean) {
        if (imeInsetBottom() > 0 == visible) return
        if (visible) showKeyboard() else hideKeyboard()
    }

    /**
     * The host's pane size once two consecutive reads agree.
     *
     * A `SIGWINCH` and tmux's own redraw are not instantaneous, and the phone's
     * inset animation produces several intermediate sizes on the way, so a
     * single read right after a viewport change can catch any of them.
     */
    private fun settledHostPaneSize(what: String): RemoteSize {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var previous: RemoteSize? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            val current = hostPaneSize()
            if (current != null && current == previous) return current
            previous = current
            SystemClock.sleep(SIZE_SETTLE_MS)
        }
        throw AssertionError("$what: the host's pane size never settled (last=$previous)")
    }

    /** `tmux display-message`, over the fixture's own connection, never the app's. */
    private fun hostPaneSize(): RemoteSize? {
        val raw = AgentsFixture.exec(
            "tmux -S $SOCKET display-message -p -t '=$SESSION:' " +
                "'#{pane_width} #{pane_height}' 2>/dev/null || true",
        ).trim()
        val match = Regex("""^(\d+) (\d+)$""").find(raw) ?: return null
        return RemoteSize(
            cols = match.groupValues[1].toInt(),
            rows = match.groupValues[2].toInt(),
        )
    }

    /**
     * The `rows cols` lines `stty size` prints, in transcript order.
     *
     * Matched on the RAW transcript: the shell prints the pair on a line of its
     * own, and the whitespace-squashing the other assertions use would run it
     * into the next prompt.
     */
    private fun sizeLines(text: String): List<String> =
        text.lines().map { it.trim() }.filter { STTY_SIZE_LINE.matches(it) }

    /** Raises the soft keyboard on the terminal and waits for the inset to appear. */
    private fun showKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        focusTerminal()
        instrumentation.runOnMainSync {
            val view = checkNotNull(terminalView()) { "no TerminalView to raise a keyboard on" }
            val manager = view.context.getSystemService(InputMethodManager::class.java)
            manager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        awaitImeInset(visible = true)
    }

    private fun hideKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = checkNotNull(terminalView()) { "no TerminalView to lower a keyboard on" }
            val manager = view.context.getSystemService(InputMethodManager::class.java)
            manager?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        awaitImeInset(visible = false)
    }

    /**
     * Waits for the framework's own IME inset to reach the expected state.
     *
     * The inset — not a screenshot, not `isActive()` — because it is the exact
     * quantity `Modifier.imePadding()` consumes, so it is the thing that must
     * change for the terminal to shrink. If it never does, the test says so
     * instead of quietly asserting a resize that had no cause.
     */
    /** The framework's own IME inset, in pixels. 0 when the keyboard is down. */
    private fun imeInsetBottom(): Int {
        compose.waitForIdle()
        return compose.runOnUiThread {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: 0
        }
    }

    private fun awaitImeInset(visible: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var bottom = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            bottom = imeInsetBottom()
            if ((bottom > 0) == visible) {
                // Let the inset animation finish before anything measures.
                SystemClock.sleep(IME_SETTLE_MS)
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-ime-${visible}", JOURNEY)
        throw AssertionError(
            "the keyboard never became ${if (visible) "visible" else "hidden"} " +
                "(ime inset bottom=$bottom). Screenshot: ${shot.absolutePath}",
        )
    }

    /**
     * Rotates the device and waits until the terminal is laid out the new way.
     *
     * The Activity is recreated, so the vendored view is rebuilt and re-attached
     * to the SAME `TerminalSession` the ViewModel still owns — which is the U-4
     * design this exercises for the first time.
     */
    private fun rotate(orientation: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            compose.activity.requestedOrientation = orientation
        }
        instrumentation.waitForIdleSync()

        val wantWide = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var seen = "none"
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            var laidOut = false
            instrumentation.runOnMainSync {
                val view = terminalView()
                if (view != null && view.width > 0 && view.height > 0) {
                    seen = "${view.width}x${view.height}"
                    laidOut = (view.width > view.height) == wantWide
                }
            }
            if (laidOut) {
                SystemClock.sleep(IME_SETTLE_MS)
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "the terminal never laid out ${if (wantWide) "landscape" else "portrait"} " +
                "(last seen $seen)",
        )
    }

    /**
     * Waits until the host's process table does (or does not) carry the
     * journey's `sleep`.
     *
     * The anchored match matters: the fixture image runs three idle
     * `sleep 3600` agents of its own, and the SSH command carrying this very
     * `grep` has the pattern in its own arguments.
     *
     * `-e` matters just as much, and cost a red run to learn: `ps` without it
     * selects only the caller's own processes on the caller's own terminal, and
     * an `exec` channel has no terminal — so the pane's shell and everything
     * under it were invisible and the oracle reported "not running" while the
     * command was running perfectly well.
     */
    private fun awaitHostSleep(running: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var count = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            count = AgentsFixture.exec(
                "ps -eo args= | grep -c '^sleep $SLEEP_SECONDS\$' || true",
            ).trim().toIntOrNull() ?: -1
            if ((count > 0) == running) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-sleep-$running", JOURNEY)
        throw AssertionError(
            "the host never reported `sleep $SLEEP_SECONDS` as " +
                "${if (running) "running" else "gone"} (count=$count).\n" +
                "The rendered viewport was:\n" + renderedTranscript() + "\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    /**
     * The host's own view of the pane, over an INDEPENDENT SSH connection —
     * never the app's. A seed or oracle sharing the transport under test would
     * turn a broken app connection into a broken-looking fixture.
     */
    private fun capturePane(): String =
        AgentsFixture.exec("tmux -S $SOCKET capture-pane -p -t '=$SESSION:' 2>/dev/null || true")

    /**
     * Whitespace-free view of terminal text, for wrap-proof matching.
     *
     * A terminal hard-wraps at its column count, and the phone's column count
     * is whatever the device's font metrics produced — so `capture-pane` and
     * the rendered transcript can each split a marker across two rows at
     * different points. Every string matched here is whitespace-free by
     * construction, so dropping whitespace makes the assertion independent of
     * the viewport width without weakening it.
     */
    private fun squashed(text: String): String = text.filterNot { it.isWhitespace() }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L

        /**
         * How long an inset/rotation animation is given to finish before
         * anything measures. A frame grabbed mid-animation reports a viewport
         * neither the old nor the new size.
         */
        const val IME_SETTLE_MS = 1_500L

        /** Gap between the two agreeing reads that count as a settled size. */
        const val SIZE_SETTLE_MS = 500L

        /**
         * How long ONE `stty size` attempt waits for its reply before the
         * measurement re-reads the host and asks again.
         *
         * Generous next to the round trip it covers (typing to rendered reply
         * is well under a second against the Docker fixture) but far short of
         * [TIMEOUT_MS], so a viewport that moved mid-measurement costs one
         * retry rather than the whole budget.
         */
        const val SIZE_REPLY_TIMEOUT_MS = 10_000L

        const val JOURNEY = "j03-attach-type"

        const val SESSION = "j03-shell"

        /** `stty size`'s output: `rows cols`, on a line of its own. */
        val STTY_SIZE_LINE = Regex("""\d{1,4} \d{1,4}""")

        /**
         * The interrupted command's duration, chosen to be unique in the
         * fixture's process table — the image's own idle agents run
         * `sleep 3600`, so `sleep 100` would be indistinguishable from them
         * under a loose match and `sleep 3600` from them under any match.
         */
        const val SLEEP_SECONDS = 987

        /**
         * The per-session socket convention `sessions attach` resolves against
         * (and `pocketshell sessions list` enumerates). Written as a shell
         * expression because only the host can expand `$(id -u)`.
         */
        const val SOCKET_DIR = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""
        const val SOCKET = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$SESSION\""

        const val PROMPT = "J03READY\$"
        const val BANNER = "J03-FIXTURE-PANE"
        const val MARKER = "pocketshell-u4-ok"
        const val CTRL_MARKER = "pocketshell-u5-interrupted"
        const val ENTER_MARKER = "pocketshell-u5-submitted"

        val HOST_IDS: Map<String, Long> = mapOf(
            "attachingToARealSessionRendersItAndAcceptsTyping" to 9_301L,
            "attachingToAVanishedSessionSaysSoInsteadOfHangingOnAttaching" to 9_302L,
            "theRemoteTerminalSizeTracksTheKeyboardAndRotation" to 9_303L,
            "ctrlFromTheKeyBarInterruptsARunningCommand" to 9_304L,
            "theKeyBarsEnterSubmitsATypedCommand" to 9_305L,
        )
    }
}
