package com.pocketshell.next.terminal

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
    private fun awaitTranscript(what: String, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            last = renderedTranscript()
            if (predicate(squashed(last))) return last
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        instrumentation.runOnMainSync {
            val view = terminalView()
            checkNotNull(view) { "no TerminalView on screen to type into" }
            view.requestFocus()
        }
        instrumentation.waitForIdleSync()
        instrumentation.sendStringSync(line)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        instrumentation.waitForIdleSync()
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
        const val JOURNEY = "j03-attach-type"

        const val SESSION = "j03-shell"

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

        val HOST_IDS: Map<String, Long> = mapOf(
            "attachingToARealSessionRendersItAndAcceptsTyping" to 9_301L,
            "attachingToAVanishedSessionSaysSoInsteadOfHangingOnAttaching" to 9_302L,
        )
    }
}
