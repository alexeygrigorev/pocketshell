package com.pocketshell.next.terminal

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
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
import com.pocketshell.next.connect.ToxiproxyControl
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
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
 * Journey J05 — the link dies under a live session, the app says so, and the
 * session comes back (rewrite task U-7).
 *
 * ## Why this has to be a device journey against a real outage
 *
 * `SessionViewModelTest` drives the same ladder over a scripted transport in
 * virtual time, and it cannot see any of what breaks here: whether sshj
 * actually reports a dropped socket, whether the PTY channel's output really
 * completes with no exit status (the app's whole discriminator between "the
 * session ended" and "the link went away"), whether the registry hands out a
 * FRESH connection rather than the spent one, whether the vendored terminal
 * survives being detached and re-fed by a second bridge, and whether the pane
 * the user was reading is still on screen while all of that happens. Every one
 * of those is production code against a real sshd here.
 *
 * ## The outage is real, and it is the fixture's
 *
 * The app dials the Toxiproxy fixture ([ToxiproxyControl]) instead of the
 * `agents` container directly, so the journey can cut and restore the link
 * instantly and completely. The SEED and the ORACLE go to the container's own
 * port over an INDEPENDENT sshj connection, which is what makes "the host still
 * has the session" a statement about the host rather than about the transport
 * under test.
 *
 * Bring both up before running:
 * ```
 * docker compose -f tests/docker/docker-compose.yml up -d --build agents network-fault-proxy
 * scripts/connected-test.sh --module app2 --suffix iu7
 * ```
 *
 * ## The oracle is the EMULATOR'S OWN SCREEN BUFFER, cross-checked on the host
 *
 * As in J03: assertions read `TerminalBuffer.getTranscriptText()` off the live
 * `TerminalView` in the running Activity — the exact text the renderer paints —
 * and the post-reconnect one is cross-checked against `tmux capture-pane -p`
 * over the independent connection. A device-only assertion could pass on
 * locally echoed bytes that never left; a host-only assertion could pass with a
 * black screen.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J05ReconnectAfterDropJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    private val proxy = ToxiproxyControl()

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        // A clean, enabled proxy first: a previous run that failed mid-outage
        // must not leave this one dialling a disabled proxy.
        proxy.reset()
        check(proxy.state().enabled) { "the network-fault proxy did not come up enabled" }

        val proxyPort = ToxiproxyControl.faultSshPortArg()
        // Probed THROUGH the proxy, so this doubles as the readiness gate for
        // the whole path under test (proxy up, upstream reachable, sshd
        // serving) instead of only for the container.
        val fingerprint = probeThroughProxy(proxyPort)
        println("J05_FIXTURE proxy=10.0.2.2:$proxyPort direct=${AgentsFixture.port} $fingerprint")

        seedTmuxSession()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j05_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j05-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = proxyPort,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
    }

    /**
     * Attach, cut the wire, watch the banner, restore the wire, watch the
     * session come back and take typing again.
     */
    @Test
    fun aDroppedLinkReconnectsAndTheSessionKeepsWorking() {
        openSession()
        val attached = awaitTranscript("the fixture's banner line") { it.contains(BANNER) }
        JourneyScreenshots.capture("01-attached", JOURNEY)
        assertTrue(
            "the live shell prompt must be on screen, got:\n$attached",
            squashed(attached).contains(PROMPT),
        )

        // 1. Cut it. Read the state back, because an HTTP 200 is not an outage.
        proxy.disable()
        val cut = proxy.state()
        assertTrue("the proxy must actually be disabled, got $cut", !cut.enabled)

        // 2. The screen says what is happening — with the attempt number and
        //    the countdown, not a bare spinner.
        awaitTag(SESSION_RECONNECT_BANNER_TAG)
        JourneyScreenshots.capture("02-reconnecting", JOURNEY)
        val banner = bannerText()
        assertTrue(
            "the reconnect banner must carry the attempt and the countdown, got: $banner",
            // `containsMatchIn`, not `matches`: the collected subtree text also
            // carries the banner's own Retry button label.
            BANNER_TEXT.containsMatchIn(banner),
        )
        assertTrue(
            "the reconnect banner must offer a manual retry, got: $banner",
            banner.contains("Retry"),
        )
        // 3. ...and the pane the user was reading is STILL THERE under it. A
        //    cleared terminal is the symptom this task exists to prevent, and it
        //    is invisible to any ViewModel-level assertion.
        compose.onNodeWithTag(SESSION_TERMINAL_TAG).assertIsDisplayed()
        assertTrue(
            "the last frame must survive the drop, got:\n" + renderedTranscript(),
            squashed(renderedTranscript()).contains(BANNER),
        )
        // Not a failure screen: this is recoverable and the app knows it.
        compose.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertDoesNotExist()

        // 4. Restore the wire. Nothing is tapped: the ladder is what recovers.
        proxy.enable()
        assertTrue("the proxy must be enabled again", proxy.state().enabled)

        // 5. The session repaints itself and takes typing again — the plan's
        //    own acceptance wording.
        awaitNoTag(SESSION_RECONNECT_BANNER_TAG)
        awaitTranscript("the reattached shell prompt") { it.contains(PROMPT) }
        JourneyScreenshots.capture("03-reattached", JOURNEY)

        typeLine("echo $MARKER")
        val afterTyping = awaitTranscript("the echoed marker twice") {
            // Twice: once as the shell echoed the typed line back, once as the
            // command's own output. A single occurrence is what a screen that
            // merely rendered the keystrokes locally would show.
            squashed(it).split(MARKER).size >= 3
        }
        JourneyScreenshots.capture("04-typed-after-reconnect", JOURNEY)
        assertTrue(
            "the recovered viewport must show the command's output, got:\n$afterTyping",
            squashed(afterTyping).contains(MARKER),
        )
        // And the host agrees: the bytes really crossed the NEW connection.
        val pane = capturePane()
        assertTrue(
            "the host's pane must show the command typed after the reconnect, got:\n$pane",
            squashed(pane).contains("echo$MARKER"),
        )
    }

    /**
     * The ladder is finite. An outage that outlasts it leaves a failure the
     * user can act on — and tapping Retry after the wire is back recovers the
     * same session, on the same terminal.
     */
    @Test
    fun anOutageThatOutlastsTheLadderLeavesAWorkingRetry() {
        openSession()
        awaitTranscript("the fixture's banner line") { it.contains(BANNER) }

        proxy.disable()
        assertTrue("the proxy must actually be disabled", !proxy.state().enabled)
        try {
            // 0 + 1s + 2s + 5s + 10s of ladder, then the give-up.
            awaitTag(SESSION_ERROR_BANNER_TAG, timeoutMs = LADDER_TIMEOUT_MS)
        } finally {
            // Unconditional: a failed assertion must not leave the shared
            // fixture disabled for the next test or the next run.
            proxy.enable()
        }
        JourneyScreenshots.capture("05-gave-up", JOURNEY)
        compose.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertIsDisplayed()

        assertTrue("the proxy must be enabled again", proxy.state().enabled)
        compose.onNodeWithTag(SESSION_RETRY_TAG).performClick()

        awaitTranscript("the shell prompt after a manual retry") { it.contains(PROMPT) }
        JourneyScreenshots.capture("06-retried", JOURNEY)
        assertTrue(
            "the host's pane must still be the same session",
            squashed(capturePane()).contains(BANNER),
        )
    }

    // --- fixture ----------------------------------------------------------

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
        AgentsFixture.exec("mkdir -p $SOCKET_DIR && chmod 700 $SOCKET_DIR")
        AgentsFixture.exec(
            "tmux -S $SOCKET new-session -d -s $SESSION -c /home/testuser -x 80 -y 24",
        )
        AgentsFixture.exec("tmux -S $SOCKET send-keys -t '=$SESSION:' 'PS1=\"$PROMPT \"' Enter")
        AgentsFixture.exec("tmux -S $SOCKET send-keys -t '=$SESSION:' 'clear; echo $BANNER' Enter")
        SystemClock.sleep(500)
        val pane = capturePane()
        check(squashed(pane).contains(BANNER)) {
            "the fixture tmux session did not come up: capture-pane says\n$pane"
        }
    }

    /**
     * The host key the fixture presents THROUGH the proxy.
     *
     * Reuses [AgentsFixture]'s probe by pointing it at the proxy port via the
     * same instrumentation argument the pooled lanes use, so there is one
     * implementation of "wait for sshd and tell me its fingerprint" rather than
     * a second one that could disagree.
     */
    private fun probeThroughProxy(proxyPort: Int): String {
        val arguments = InstrumentationRegistry.getArguments()
        val previous = arguments.getString("agentsPort")
        arguments.putString("agentsPort", proxyPort.toString())
        try {
            return AgentsFixture.probeHostKeyFingerprint()
        } finally {
            if (previous == null) arguments.remove("agentsPort")
            else arguments.putString("agentsPort", previous)
        }
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
     * The reconnect banner's rendered text, straight out of the semantics tree.
     *
     * Collected from the whole SUBTREE, not from the tagged node itself: the
     * test tag sits on the banner's Row, which merges nothing, so the text
     * lives one level down on the `Text` child. Reading only the tagged node's
     * own config returns "" — and an assertion against "" would have been a
     * vacuous pass had the check been `contains` rather than a full match.
     */
    private fun bannerText(): String {
        compose.awaitIdle("before reading the reconnect banner")
        val node = compose.onNodeWithTag(SESSION_RECONNECT_BANNER_TAG).fetchSemanticsNode()
        return collectText(node).trim()
    }

    private fun collectText(node: SemanticsNode): String {
        val own: List<AnnotatedString> = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        val mine = own.joinToString(separator = " ") { annotated -> annotated.text }
        val below = node.children.joinToString(separator = " ") { child -> collectText(child) }
        return (mine + " " + below).trim()
    }

    /**
     * Polls the LIVE emulator's screen buffer until [predicate] holds.
     *
     * `waitForIdle` on every turn is load bearing (it was a red run first):
     * under a Compose test rule the app's frame clock is DRIVEN BY THE TEST, so
     * a plain sleep-poll loop starves recomposition and the state change never
     * gets a frame. Reading the grid on the MAIN thread matters for the same
     * reason it does in J03 — that is where the vendored emulator parses.
     */
    private fun awaitTranscript(what: String, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("transcript poll: $what")
            last = renderedTranscript()
            if (predicate(squashed(last))) return last
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "the terminal never rendered $what within ${TIMEOUT_MS}ms.\n" +
                "Screen state: ${screenDiagnosis()}\n" +
                "Proxy state: " + runCatching { proxy.state().toString() }.getOrElse { "$it" } +
                "\nRendered viewport was:\n$last\n" +
                "The host's own capture-pane says:\n" + capturePane() + "\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    /**
     * Which of the session screen's states is on screen, and whether the
     * terminal view exists and has a grid — so an empty transcript is never
     * ambiguous between "never attached", "attached but not pumping",
     * "reconnecting" and "the view never laid out".
     */
    private fun screenDiagnosis(): String {
        fun present(tag: String) =
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

        val chrome = "connecting=${present(SESSION_CONNECTING_TAG)} " +
            "reconnecting=${present(SESSION_RECONNECT_BANNER_TAG)} " +
            "error=${present(SESSION_ERROR_BANNER_TAG)} " +
            "terminalComposed=${present(SESSION_TERMINAL_TAG)}"

        var view = "no TerminalView in the hierarchy"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            terminalView()?.let {
                view = "TerminalView ${it.width}x${it.height}, focused=${it.isFocused}, " +
                    "emulator=" + (it.mEmulator?.let { e -> "${e.mColumns}x${e.mRows}" } ?: "null")
            }
        }
        return "$chrome; $view"
    }

    /**
     * The text the terminal is actually showing, straight out of the vendored
     * `TerminalBuffer`.
     */
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

    /**
     * Types [line] followed by Enter as real key events, so the whole
     * `TerminalView.onKeyDown` → `TerminalSession.write` → bridge → PTY path is
     * exercised — which after a reconnect is a path through the SECOND bridge.
     */
    private fun typeLine(line: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.awaitIdle("before typing a line")
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
     * The host's own view of the pane, over an INDEPENDENT SSH connection to
     * the fixture's DIRECT port — never through the proxy, and never the app's
     * transport. That is what keeps the oracle readable while the link under
     * test is cut.
     */
    private fun capturePane(): String =
        AgentsFixture.exec("tmux -S $SOCKET capture-pane -p -t '=$SESSION:' 2>/dev/null || true")

    /** Whitespace-free view of terminal text, for wrap-proof matching (see J03). */
    private fun squashed(text: String): String = text.filterNot { it.isWhitespace() }

    private fun awaitTag(tag: String, timeoutMs: Long = TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoTag(tag: String, timeoutMs: Long = TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L

        /** The ladder is 18 s end to end; this is that plus room for the dials. */
        const val LADDER_TIMEOUT_MS = 90_000L

        const val POLL_MS = 250L
        const val JOURNEY = "j05-reconnect"

        const val SESSION = "j05-shell"

        const val SOCKET_DIR = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""
        const val SOCKET = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$SESSION\""

        const val PROMPT = "J05READY\$"
        const val BANNER = "J05-FIXTURE-PANE"
        const val MARKER = "j05-back"

        /**
         * The banner has to carry BOTH numbers. Matched as a shape rather than
         * a literal because which rung the ladder is on when the assertion runs
         * depends on real dial timings.
         */
        val BANNER_TEXT = Regex("Reconnecting… attempt \\d+ · retrying (now|in \\d+s)")

        val HOST_IDS: Map<String, Long> = mapOf(
            "aDroppedLinkReconnectsAndTheSessionKeepsWorking" to 9_501L,
            "anOutageThatOutlastsTheLadderLeavesAWorkingRetry" to 9_502L,
        )
    }
}
