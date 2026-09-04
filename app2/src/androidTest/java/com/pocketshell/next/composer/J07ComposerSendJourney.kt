package com.pocketshell.next.composer

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.terminal.SESSION_ERROR_BANNER_TAG
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.tree.SESSION_TREE_TAG
import com.pocketshell.next.tree.sessionRowTag
import com.termux.view.TerminalView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J07 — compose a message on a real session and watch it land (rewrite
 * task P-1).
 *
 * ## Why this has to be a device journey
 *
 * `ComposerViewModelTest` drives the same ViewModel over a scripted connection
 * on the host JVM and cannot see any of what breaks here: a composer laid out
 * under the keyboard, a `BasicTextField` that never takes focus, an IME
 * composing region the Send button reads as empty (the exact defect the old
 * client shipped — "Send is a no-op, I had to raise the keyboard and press
 * Enter"), `sendBytes` reaching a PTY that is not the one on screen, or a
 * carriage return the remote line discipline does not treat as Enter.
 *
 * ## The oracle is the terminal's OWN screen buffer, cross-checked on the host
 *
 * Assertions read `TerminalBuffer.getTranscriptText()` off the live
 * `TerminalView` in the running Activity — the pixels the renderer paints —
 * and then cross-check against `tmux capture-pane -p` over an INDEPENDENT SSH
 * connection. A device-only assertion could pass on locally echoed bytes that
 * never left; a host-only one could pass with a black screen. Same discipline
 * as J03, for the same D29 reason.
 *
 * ## The non-happy host is a REAL dead session
 *
 * The undelivered case is produced by killing the tmux session out from under
 * an attached screen, so the composer is asked to send into a genuinely dead
 * pane rather than a flag a test set. That is the state the maintainer hits
 * (the box went to sleep, the session ended), and a fixture that only ever
 * offers a healthy host proves nothing about it.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J07ComposerSendJourney {

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
        println("J07_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedTmuxSession()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j07_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j07-${description.methodName}", privateKeyPath = keyPath),
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
        // The sent-message log is app-global and survives an uninstall-less
        // rerun; a stale row would make a history assertion pass for the wrong
        // reason.
        graph.sentMessageDao().deleteBySessionKey("$hostId/$SESSION")
        // Same for the persisted draft: an undelivered send KEEPS its draft on
        // disk by design, so a previous run of this very journey would
        // otherwise pre-fill the composer and let an assertion pass without the
        // app doing anything.
        graph.composerDraftStore().clear("$hostId/$SESSION")
    }

    /** Same per-session `tmuxctl-<name>` socket convention `sessions attach` resolves. */
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
     * The headline journey: type into the composer, tap Send, watch the command
     * run on a real host — and the draft is gone afterwards, because it landed.
     */
    @Test
    fun composingAndSendingReachesTheRealSessionAndClearsTheDraft() {
        openSession()
        awaitTranscript("the fixture's banner line") { it.contains(BANNER) }

        compose.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("echo $MARKER")
        compose.waitForIdle()
        JourneyScreenshots.capture("01-composed", JOURNEY)

        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()

        // The command was echoed by the shell AND produced output: two
        // occurrences. One would be a screen that merely painted the text
        // locally without anything crossing the wire.
        val rendered = awaitTranscript("the echoed marker twice") {
            it.split(MARKER).size >= 3
        }
        JourneyScreenshots.capture("02-sent", JOURNEY)
        assertTrue(
            "the rendered viewport must show the command's output, got:\n$rendered",
            squashed(rendered).contains(MARKER),
        )
        // ...and the host agrees the bytes really arrived.
        val pane = capturePane()
        assertTrue(
            "the host's pane must show the sent command, got:\n$pane",
            squashed(pane).contains("echo$MARKER"),
        )

        // A delivered send clears the composer — and leaves no chip.
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_UNDELIVERED_TAG).assertDoesNotExist()
        compose.onNode(hasText(COMPOSER_PLACEHOLDER)).assertIsDisplayed()
    }

    /**
     * The other half of the contract: a send that cannot leave keeps the text
     * and says so.
     *
     * The session is killed while the screen is attached, so this is a real
     * dead pane — the state the maintainer actually hits.
     */
    @Test
    fun aSendIntoADeadSessionKeepsTheDraftAndShowsTheChip() {
        openSession()
        awaitTranscript("the fixture's banner line") { it.contains(BANNER) }

        // Kill the link out from under the attached screen. `kill-server`, not
        // `kill-session`: killing the session alone leaves the tmux server up,
        // and whether the attached client notices is a timing-dependent
        // property of the server's teardown — a first run passed on it and a
        // second timed out. Killing the server EOFs the attach's PTY
        // immediately, which is the deterministic version of the same event the
        // maintainer hits (the box slept, the session is gone). The socket is
        // this session's own `tmuxctl-<name>`, so nothing else on the fixture
        // is touched.
        AgentsFixture.exec("tmux -S $SOCKET kill-server 2>/dev/null || true")
        awaitTag(SESSION_ERROR_BANNER_TAG, "the session-ended banner")

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(UNDELIVERED_TEXT)
        compose.waitForIdle()
        // Prove the editor took the text and Send is live BEFORE asserting on
        // what Send does with it: a timeout on the chip alone cannot tell
        // "the send did the wrong thing" from "the tap never reached a send".
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .assertTextContains(UNDELIVERED_TEXT, substring = true)
        compose.onNodeWithTag(COMPOSER_SEND_TAG).assertIsEnabled()
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()

        awaitTag(COMPOSER_UNDELIVERED_TAG, "the not-delivered chip")
        JourneyScreenshots.capture("03-undelivered", JOURNEY)

        // The chip is on screen, and the text the user typed is still in the
        // field — both, not either.
        compose.onNodeWithTag(COMPOSER_UNDELIVERED_TAG).assertIsDisplayed()
        compose.onNode(hasText(COMPOSER_UNDELIVERED_TEXT)).assertIsDisplayed()
        compose.onNode(hasText(UNDELIVERED_TEXT)).assertIsDisplayed()

        // And it was logged as not delivered, so it is recoverable later.
        val logged = runBlocking {
            appGraph().sentMessageDao().recentOnce("$hostId/$SESSION", limit = 10)
        }
        assertEquals(listOf(UNDELIVERED_TEXT), logged.map { it.body })
        assertEquals(false, logged.single().delivered)
    }

    /** "Don't make me retype what I already sent": the log, and the tap that restores it. */
    @Test
    fun aSentMessageComesBackFromTheHistory() {
        openSession()
        awaitTranscript("the fixture's banner line") { it.contains(BANNER) }

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(HISTORY_TEXT)
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        awaitTranscript("the sent history line") { it.contains(squashed(HISTORY_TEXT)) }

        compose.onNodeWithTag(COMPOSER_HISTORY_TAG).performClick()
        awaitTag(COMPOSER_HISTORY_SHEET_TAG, "the history sheet")
        JourneyScreenshots.capture("04-history", JOURNEY)

        compose.onNode(hasText(HISTORY_TEXT)).performClick()
        compose.waitForIdle()
        JourneyScreenshots.capture("05-refilled", JOURNEY)

        // The composer holds the message again, ready to send a second time.
        compose.onNode(hasText(HISTORY_TEXT)).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_HISTORY_SHEET_TAG).assertDoesNotExist()
    }

    /**
     * Attachments, over REAL SFTP to the fixture.
     *
     * Driven through the app's own [ComposerAttachmentStager] and its own live
     * connection (resolved from the running Hilt graph) rather than the system
     * file picker, which an instrumented test cannot operate. Everything below
     * the picker is production: the connection, the SFTP channel, the directory
     * creation, the name generation, and the `~/`-prefixed path the message
     * carries. The oracle is an INDEPENDENT SSH `cat` of the uploaded file.
     */
    @Test
    fun anAttachmentUploadsOverSftpAndItsRemotePathGoesIntoTheMessage() {
        openSession()

        val graph = appGraph()
        val local = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "j07-attachment.txt",
        ).apply { writeText(ATTACHMENT_BODY) }

        val staged = runBlocking {
            val result = graph.connectionsRegistry().getOrConnect(hostId)
            val connection = (result as ConnectResult.Connected).connection
            graph.composerAttachmentStager().stage(
                sftp = connection.sftp(),
                homeDir = "/home/${AgentsFixture.USER}",
                scopeKey = "$hostId/$SESSION",
                picks = listOf(Uri.fromFile(local)),
            )
        }

        assertEquals("the stage must report no failure, got ${staged.failure}", null, staged.failure)
        val attachment = staged.uploaded.single()
        assertTrue(
            "the staged path must be the `~/`-prefixed shape the old flow used, got " +
                attachment.remotePath,
            attachment.remotePath.startsWith("~/.pocketshell/attachments/"),
        )

        // The host really has the bytes, at the path the message will name.
        val onHost = AgentsFixture.exec("cat ${attachment.remotePath}")
        assertEquals(ATTACHMENT_BODY, onHost.trim())

        // ...and the message the composer would send references exactly that path.
        assertEquals(
            "look\n\nAttached files:\n- ${attachment.remotePath}",
            ComposerText.compose("look", listOf(attachment.remotePath)),
        )
    }

    // --- helpers ----------------------------------------------------------

    private fun openSession() {
        awaitTag(hostRowTag(hostId))
        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(SESSION_TREE_TAG)
        awaitTag(sessionRowTag(SESSION))
        compose.onNodeWithTag(sessionRowTag(SESSION)).performClick()
        awaitTag(SESSION_SCREEN_TAG)
    }

    /**
     * Polls the LIVE emulator's screen buffer until [predicate] holds against
     * its whitespace-squashed text.
     *
     * `waitForIdle()` on every turn is load bearing: under a Compose test rule
     * the app's frame clock is driven by the TEST, so a plain sleep-poll loop
     * starves recomposition and the terminal is never created at all.
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
                "Rendered viewport was:\n$last\n" +
                "The host's own capture-pane says:\n" + capturePane() + "\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    /** The text the terminal is actually showing, read on the thread that renders it. */
    private fun renderedTranscript(): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = findTerminalView(compose.activity.window.decorView)
                ?.mEmulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun findTerminalView(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /** The host's own view of the pane, over an INDEPENDENT SSH connection. */
    private fun capturePane(): String =
        AgentsFixture.exec("tmux -S $SOCKET capture-pane -p -t '=$SESSION:' 2>/dev/null || true")

    /**
     * Whitespace-free view of terminal text, for wrap-proof matching: a
     * terminal hard-wraps at its column count and the phone's column count is
     * whatever the device's font metrics produced.
     */
    private fun squashed(text: String): String = text.filterNot { it.isWhitespace() }

    /**
     * Waits for [tag], and on timeout says WHY rather than just "condition not
     * satisfied after 60000 ms".
     *
     * A bare `waitUntil` timeout is the least useful failure a journey can
     * produce: it cannot distinguish "the app never got there", "the fixture
     * never changed" and "the composition stopped advancing", and each costs
     * another emulator round trip to tell apart. The screenshot plus the host's
     * own view of the session answers all three at once.
     */
    private fun awaitTag(tag: String, what: String = tag) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "$what never appeared within ${TIMEOUT_MS}ms.\n" +
                "Rendered viewport was:\n" + renderedTranscript() + "\n" +
                "The host says its sessions are:\n" +
                AgentsFixture.exec("tmux -S $SOCKET list-sessions 2>&1 || true") + "\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val JOURNEY = "j07-composer-send"

        const val SESSION = "j07-shell"

        const val SOCKET_DIR = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""
        const val SOCKET = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)/tmuxctl-$SESSION\""

        const val PROMPT = "J07READY\$"
        const val BANNER = "J07-FIXTURE-PANE"
        const val MARKER = "pocketshell-p1-ok"

        /** No spaces: it is asserted against the wrap-squashed transcript. */
        const val HISTORY_TEXT = "echo pocketshell-p1-history"
        const val UNDELIVERED_TEXT = "this-draft-must-survive"
        const val ATTACHMENT_BODY = "pocketshell-p1-attachment-bytes"

        val HOST_IDS: Map<String, Long> = mapOf(
            "composingAndSendingReachesTheRealSessionAndClearsTheDraft" to 9_701L,
            "aSendIntoADeadSessionKeepsTheDraftAndShowsTheChip" to 9_702L,
            "aSentMessageComesBackFromTheHistory" to 9_703L,
            "anAttachmentUploadsOverSftpAndItsRemotePathGoesIntoTheMessage" to 9_704L,
        )
    }
}
