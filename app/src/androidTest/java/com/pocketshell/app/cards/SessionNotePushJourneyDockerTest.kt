package com.pocketshell.app.cards

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.tmux.tmuxSessionCardInteractions
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Epic #859 remaining gap — note mark-as-read write-back over a real
 * emulator+Docker warm session (D33 / D32 G10).
 *
 * Checklist push→tick already has [SessionChecklistPushJourneyDockerTest].
 * Notes rendered, and [SessionCardsRemoteSource.setNoteRead] existed, but
 * the session-screen callback was a no-op. This class is the proof that
 * the **production screen callback** writes through the VM to the host:
 *
 *  1. Host (Docker `agents` fixture) runs `pocketshell push note`.
 *  2. A production [TmuxSessionViewModel] is bound to a warm [SshSession]
 *     (D21 — no new connection; [TmuxSessionViewModel.replaceClientForTest]
 *     installs the live session the screen would hold).
 *  3. Production [SessionCardFeedChip] / [SessionCardFeedContent] render the
 *     VM feed (same composables the session screen mounts).
 *  4. Tapping "mark read" drives [tmuxSessionCardInteractions] — the factory
 *     [com.pocketshell.app.tmux.TmuxSessionScreen] remember()s — which calls
 *     [TmuxSessionViewModel.setNoteRead]. The test body does **not** call
 *     [SessionCardsRemoteSource.setNoteRead].
 *  5. Re-reading (`sessionCards` + agent `push status`) shows `read:true`,
 *     and unread round-trips too.
 *
 * G6: stubbing [tmuxSessionCardInteractions] `onSetNoteRead` back to `Unit`
 * (the original screen no-op) leaves the host unread, so the
 * `"read":true` assertion fails. Direct `source.setNoteRead()` from the
 * test body is banned for that reason.
 *
 * [readPathBroken_wrongSessionName_yieldsEmptyFeed_redGuard] is the
 * inverted session-key guard — a broken get that ignores the session name
 * would still return the card here.
 */
@RunWith(AndroidJUnit4::class)
class SessionNotePushJourneyDockerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val cleanupCommands = mutableListOf<String>()
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var viewModel: TmuxSessionViewModel? = null
    private var warmSession: SshSession? = null

    @After
    fun tearDown(): Unit { runBlocking {
        viewModel?.clearForTest()
        viewModel = null
        runCatching { warmSession?.close() }
        warmSession = null
        factoryScope.cancel()
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    withSshSession { session ->
                        session.exec(cleanupCommands.joinToString("\n"))
                    }
                }
            }
        }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun hostPushNoteRendersInAppFeedAndMarkReadRoundTripsToHost(): Unit { runBlocking {
        bootstrapKey()
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val sessionName = "issue859-note-$suffix"
        cleanupCommands += "rm -f \"\$HOME/.pocketshell/cards/$sessionName.json\" 2>/dev/null || true"

        val pushCmd = pocketshellExec(
            "push note --session ${shellQuote(sessionName)} " +
                "--title ${shellQuote("Heads up")} " +
                "--text ${shellQuote("Deploy finished")}",
        )
        val session = openSshSession()
        warmSession = session
        val r = withTimeout(20_000) { session.exec(pushCmd) }
        assertEquals(
            "host `pocketshell push note` must succeed; " +
                "stderr='${r.stderr}' stdout='${r.stdout}'",
            0,
            r.exitCode,
        )
        assertTrue(
            "push confirmation should name the session; got '${r.stdout}'",
            r.stdout.contains(sessionName),
        )

        val vm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
        )
        viewModel = vm
        vm.replaceClientForTest(
            hostId = 859L,
            hostName = "issue859-note",
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            keyPath = keyFile.absolutePath,
            sessionName = sessionName,
            client = InertTmuxClient(),
            session = session,
        )
        assertTrue(
            "VM must accept a card refresh over the warm session",
            vm.refreshActiveSessionCards(),
        )
        composeRule.waitUntil(20_000) {
            vm.sessionCards.value.feed.cards
                .filterIsInstance<SessionCardsRemoteSource.NoteCard>()
                .isNotEmpty()
        }

        val card = vm.sessionCards.value.feed.cards
            .filterIsInstance<SessionCardsRemoteSource.NoteCard>()
            .singleOrNull()
        assertNotNull("the pushed note card must reach the app feed", card)
        card!!
        assertEquals("note", card.type)
        assertEquals("Heads up", card.title)
        assertEquals("Deploy finished", card.text)
        assertFalse(
            "a fresh push must be unread (G6: a store that defaults read=true " +
                "would make the mark-read assertion vacuous)",
            card.read,
        )

        composeRule.setContent {
            PocketShellTheme {
                val cardsState by vm.sessionCards.collectAsState()
                val renderedCards = cardsState.feed.cards
                val interactions = remember(vm) { tmuxSessionCardInteractions(vm) }
                val chip = cardFeedChipState(renderedCards)
                if (chip != null) {
                    SessionCardFeedChip(state = chip, onClick = {})
                }
                SessionCardFeedContent(
                    cards = renderedCards,
                    interactions = interactions,
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag(SESSION_CARD_FEED_CHIP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_NOTE_CARD_TAG_PREFIX + card.id)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Deploy finished").assertIsDisplayed()

        composeRule.onNodeWithTag(SESSION_NOTE_READ_TOGGLE_TAG_PREFIX + card.id)
            .performClick()
        composeRule.waitUntil(20_000) {
            vm.sessionCards.value.feed.cards
                .filterIsInstance<SessionCardsRemoteSource.NoteCard>()
                .singleOrNull()
                ?.read == true
        }
        composeRule.waitForIdle()

        assertTrue(
            "the rendered card must reflect the host-backed read state",
            vm.sessionCards.value.feed.cards
                .filterIsInstance<SessionCardsRemoteSource.NoteCard>()
                .single()
                .read,
        )

        // Fresh SSH login — reconnect / app-restart persistence (host store).
        // Load-bearing for the screen→VM path: if the production callback is
        // a no-op, this host re-read stays unread.
        val afterRead = withSshSession { s ->
            withTimeout(20_000) { SessionCardsRemoteSource().getCards(s, sessionName) }
        }.cards.filterIsInstance<SessionCardsRemoteSource.NoteCard>().single()
        assertTrue(
            "after the app mark-read the host store must persist read=true",
            afterRead.read,
        )
        withSshSession { s ->
            val status = withTimeout(20_000) {
                s.exec(pocketshellExec("push status --json --session ${shellQuote(sessionName)}"))
            }
            assertEquals(0, status.exitCode)
            assertTrue(
                "agent `push status` must reflect the human's read ack; got '${status.stdout}'",
                status.stdout.contains("\"read\":true") ||
                    status.stdout.contains("\"read\": true"),
            )
        }

        composeRule.onNodeWithTag(SESSION_NOTE_READ_TOGGLE_TAG_PREFIX + card.id)
            .performClick()
        composeRule.waitUntil(20_000) {
            vm.sessionCards.value.feed.cards
                .filterIsInstance<SessionCardsRemoteSource.NoteCard>()
                .singleOrNull()
                ?.read == false
        }
        composeRule.waitForIdle()
        val afterUnread = withSshSession { s ->
            withTimeout(20_000) { SessionCardsRemoteSource().getCards(s, sessionName) }
        }.cards.filterIsInstance<SessionCardsRemoteSource.NoteCard>().single()
        assertFalse(
            "after the app unread the host store must clear read",
            afterUnread.read,
        )
    } }

    @Test
    fun noNotePushedYieldsEmptyFeedAndNoChip(): Unit { runBlocking {
        bootstrapKey()
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val emptySession = "issue859-note-empty-$suffix"

        val source = SessionCardsRemoteSource()
        val feed = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, emptySession) }
        }
        assertTrue(
            "a session with no pushed note must read back an empty feed",
            feed.cards.isEmpty(),
        )

        assertNull(
            "cardFeedChipState must be null for an empty feed (no chip)",
            cardFeedChipState(feed.cards),
        )

        composeRule.setContent {
            PocketShellTheme {
                val chip = cardFeedChipState(feed.cards)
                if (chip != null) {
                    SessionCardFeedChip(state = chip, onClick = {})
                }
            }
        }
        composeRule.onNodeWithTag(SESSION_CARD_FEED_CHIP_TAG).assertDoesNotExist()
    } }

    /**
     * RED→GREEN guard (D32 G10): the load-bearing "note reaches the feed"
     * assertions only mean something if a BROKEN read path produces a
     * different, empty result. The note is pushed under [sessionName] but the
     * app reads under the WRONG tmux session name.
     */
    @Test
    fun readPathBroken_wrongSessionName_yieldsEmptyFeed_redGuard(): Unit { runBlocking {
        bootstrapKey()
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val sessionName = "issue859-note-guard-$suffix"
        cleanupCommands += "rm -f \"\$HOME/.pocketshell/cards/$sessionName.json\" 2>/dev/null || true"

        val source = SessionCardsRemoteSource()
        withSshSession { s ->
            val r = withTimeout(20_000) {
                s.exec(
                    pocketshellExec(
                        "push note --session ${shellQuote(sessionName)} " +
                            "--text ${shellQuote("Deploy finished")}",
                    ),
                )
            }
            assertEquals("push setup failed: '${r.stderr}'", 0, r.exitCode)
        }

        val correct = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, sessionName) }
        }
        assertTrue(
            "control: the correct session name must read the pushed note",
            correct.cards.any { it is SessionCardsRemoteSource.NoteCard },
        )

        val wrong = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, sessionName + "-WRONG") }
        }
        assertTrue(
            "a wrong session name must NOT surface another session's note",
            wrong.cards.isEmpty(),
        )
        assertNull(
            "no chip renders for the broken read path",
            cardFeedChipState(wrong.cards),
        )
    } }

    private fun pocketshellExec(args: String): String =
        com.pocketshell.app.pocketshell.PocketshellCommand.wrap(args)

    private fun bootstrapKey() {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue859-note-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
    }

    private suspend fun openSshSession(): SshSession =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = openSshSession()
        return session.use { block(it) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Minimal [TmuxClient] for [TmuxSessionViewModel.replaceClientForTest].
     * Card write-back reads only `activeTarget` + [SshSession]; no tmux
     * commands are issued on this path.
     */
    private class InertTmuxClient : TmuxClient {
        private val disconnectedState = MutableStateFlow(false)
        private val disconnectEventState = MutableStateFlow<TmuxDisconnectEvent?>(null)

        override val events: Flow<ControlEvent> = emptyFlow()
        override val disconnected: StateFlow<Boolean> = disconnectedState.asStateFlow()
        override val disconnectEvent: StateFlow<TmuxDisconnectEvent?> =
            disconnectEventState.asStateFlow()
        override val outputBacklogOverflows: Flow<TmuxOutputBacklogOverflow> = emptyFlow()

        override suspend fun connect() = Unit

        override suspend fun sendCommand(cmd: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override fun outputFor(paneId: String): Flow<ControlEvent.Output> = emptyFlow()
        override fun drainPaneOutputBacklog(paneId: String): Int = 0

        override fun close() = Unit

        override suspend fun setWindowSizeLatest(sessionId: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun detachCleanly(timeoutMs: Long) = Unit
    }
}
