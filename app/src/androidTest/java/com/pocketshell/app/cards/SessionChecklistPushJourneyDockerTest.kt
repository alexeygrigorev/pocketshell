package com.pocketshell.app.cards

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.uikit.theme.PocketShellTheme
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
 * Epic #859 Slice A (tracking issue #949) — the end-to-end emulator+Docker
 * acceptance journey for the host→app typed-card push (Phase-1 "verify-gone",
 * D33). Phase-1 (the host CLI `pocketshell push checklist|get|check|status` +
 * the app's [SessionCardsRemoteSource] + the checklist chip / bottom-sheet)
 * shipped code-complete on `main` but **WITHOUT** an end-to-end journey, so the
 * real warm-session path was unproven. This class proves it and gate-wires it
 * (per-push, see `scripts/ci-journey-suite.sh`) so the card-push transport
 * cannot silently regress.
 *
 * It exercises the REAL path, NOT a unit proxy:
 *
 *  1. A host (the deterministic Docker `agents` fixture, host port 2222 — or the
 *     pool-allocated port under `--pool`) runs `pocketshell push checklist
 *     --session <s> --item …` over a real SSH session to write the per-session
 *     card store.
 *  2. The app reads it through the PRODUCTION [SessionCardsRemoteSource.getCards]
 *     over the SAME warm [SshSession] (D21 — no new connection) and parses it to
 *     a [SessionCardsRemoteSource.ChecklistCard].
 *  3. The PRODUCTION checklist chip + bottom-sheet ([ChecklistChip] /
 *     [ChecklistCardsContent], the same composables the session screen mounts)
 *     render that real feed; the test asserts the card + its items ACTUALLY
 *     appear in the rendered feed (not a fabricated in-memory card).
 *  4. Tapping an item drives the PRODUCTION
 *     [SessionCardsRemoteSource.setChecklistItemChecked] tick exec over the same
 *     warm session.
 *  5. Re-reading the host (`push status` + the production [getCards]) confirms
 *     the tick ROUND-TRIPPED to the host store, and an untick round-trips too.
 *
 * RED→GREEN (D32 G10): [readPathBroken_wrongSessionName_yieldsEmptyFeed_redGuard]
 * is the inverted guard — reading the card under the WRONG tmux session name
 * (the read-path-broken signal) yields an empty feed and renders NO chip, so the
 * "card reaches the feed" assertions above are load-bearing (a broken read path
 * makes the journey fail, a working one makes it pass).
 *
 * Mirrors the structure of [com.pocketshell.app.projects.ManualKindWriterDockerTest]
 * (same fixture + key bootstrap), but exercises the card-push read/render/tick
 * round-trip rather than agent-kind classification.
 */
@RunWith(AndroidJUnit4::class)
class SessionChecklistPushJourneyDockerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val cleanupCommands = mutableListOf<String>()

    @After
    fun tearDown(): Unit = runBlocking {
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
    }

    @Test
    fun hostPushChecklistRendersInAppFeedAndTickRoundTripsToHost(): Unit = runBlocking {
        bootstrapKey()
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val sessionName = "issue859-cards-$suffix"
        // The card store lives under ~/.pocketshell/cards/<session>.json on the
        // host (the cards.py default), so clean both the session and its store.
        cleanupCommands += "rm -f \"\$HOME/.pocketshell/cards/$sessionName.json\" 2>/dev/null || true"

        val source = SessionCardsRemoteSource()

        // ---------------------------------------------------------------
        // (1) AGENT push: the host writes a per-session checklist card via the
        //     real `pocketshell push checklist` verb over a real SSH session.
        // ---------------------------------------------------------------
        val pushCmd = pocketshellExec(
            "push checklist --session ${shellQuote(sessionName)} " +
                "--title ${shellQuote("Deploy plan")} " +
                "--item ${shellQuote("Build the app")} " +
                "--item ${shellQuote("Run the tests")} " +
                "--item ${shellQuote("Ship it")}",
        )
        withSshSession { s ->
            val r = withTimeout(20_000) { s.exec(pushCmd) }
            assertEquals(
                "host `pocketshell push checklist` must succeed; " +
                    "stderr='${r.stderr}' stdout='${r.stdout}'",
                0,
                r.exitCode,
            )
            assertTrue(
                "push confirmation should name the session; got '${r.stdout}'",
                r.stdout.contains(sessionName),
            )
        }

        // ---------------------------------------------------------------
        // (2) APP read: the PRODUCTION remote source reads the card back over
        //     the warm session and parses it to a ChecklistCard with the
        //     pushed items (item ids are `<slug>-<index>`, faithful to cards.py).
        // ---------------------------------------------------------------
        val feed = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, sessionName) }
        }
        assertEquals("feed session must match the pushed session", sessionName, feed.session)
        val card = feed.cards.filterIsInstance<SessionCardsRemoteSource.ChecklistCard>()
            .singleOrNull()
        assertNotNull("the pushed checklist card must reach the app feed", card)
        card!!
        assertEquals("checklist", card.type)
        assertEquals("Deploy plan", card.title)
        assertEquals(
            "the pushed items must round-trip to the app feed in order",
            listOf("Build the app", "Run the tests", "Ship it"),
            card.items.map { it.text },
        )
        assertEquals(
            "item ids must be the cards.py <slug>-<index> shape",
            listOf("build-the-app-0", "run-the-tests-1", "ship-it-2"),
            card.items.map { it.id },
        )
        assertTrue(
            "no item should be pre-checked on a fresh push",
            card.checkedIds.isEmpty(),
        )

        // ---------------------------------------------------------------
        // (3) APP RENDER: the PRODUCTION chip + bottom-sheet render the REAL
        //     feed. The toggle path drives the PRODUCTION tick exec over the
        //     SAME warm session (this is the user's actual tap path). We hold
        //     the feed in app state and re-read it after each tick so the
        //     render reflects the host store, exactly like the VM does.
        // ---------------------------------------------------------------
        var renderedCards by mutableStateOf(
            feed.cards.filterIsInstance<SessionCardsRemoteSource.ChecklistCard>(),
        )

        // The production toggle: tick over the warm session, then re-read the
        // host store (mirrors TmuxSessionViewModel.toggleChecklistItem).
        suspend fun toggle(cardId: String, itemId: String, checked: Boolean) {
            withSshSession { s ->
                val ok = withTimeout(20_000) {
                    source.setChecklistItemChecked(
                        session = s,
                        tmuxSessionName = sessionName,
                        cardId = cardId,
                        itemId = itemId,
                        checked = checked,
                    )
                }
                assertTrue("the production tick exec must be acknowledged by the host", ok)
            }
            val refreshed = withSshSession { s ->
                withTimeout(20_000) { source.getCards(s, sessionName) }
            }
            renderedCards = refreshed.cards
                .filterIsInstance<SessionCardsRemoteSource.ChecklistCard>()
        }

        val toggleRequests = mutableListOf<Triple<String, String, Boolean>>()
        composeRule.setContent {
            PocketShellTheme {
                val chip = checklistChipState(renderedCards)
                if (chip != null) {
                    ChecklistChip(state = chip, onClick = {})
                }
                ChecklistCardsContent(
                    cards = renderedCards,
                    onToggle = { cardId, itemId, isChecked ->
                        toggleRequests += Triple(cardId, itemId, isChecked)
                    },
                    onClose = {},
                )
            }
        }

        // The chip is present (count chip over the real feed) and the card +
        // every pushed item are ACTUALLY rendered in the production sheet.
        composeRule.onNodeWithTag(SESSION_CHECKLIST_CHIP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_CHECKLIST_CARD_TAG_PREFIX + card.id)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Build the app").assertIsDisplayed()
        composeRule.onNodeWithText("Run the tests").assertIsDisplayed()
        composeRule.onNodeWithText("Ship it").assertIsDisplayed()

        // ---------------------------------------------------------------
        // (4)+(5) TICK round-trip: tap the second item in the rendered sheet ->
        //     production tick over the warm session -> host store reflects it.
        // ---------------------------------------------------------------
        composeRule.onNodeWithTag(
            SESSION_CHECKLIST_ITEM_TAG_PREFIX + card.id + ":run-the-tests-1",
        ).performClick()
        composeRule.waitForIdle()

        // The rendered tap requested a tick (checked=true) for that item.
        assertEquals(
            "the rendered item tap must request a tick of run-the-tests-1",
            Triple(card.id, "run-the-tests-1", true),
            toggleRequests.last(),
        )
        // Drive the production tick over the warm session for the requested tap.
        toggleRequests.last().let { (cardId, itemId, checked) ->
            toggle(cardId, itemId, checked)
        }
        composeRule.waitForIdle()

        // The host store now shows the item checked — proven via BOTH the
        // production read AND the agent's `push status` read-back.
        val afterTick = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, sessionName) }
        }.cards.filterIsInstance<SessionCardsRemoteSource.ChecklistCard>().single()
        assertTrue(
            "after the app tick the host store must mark run-the-tests-1 checked",
            "run-the-tests-1" in afterTick.checkedIds,
        )
        withSshSession { s ->
            val status = withTimeout(20_000) {
                s.exec(pocketshellExec("push status --json --session ${shellQuote(sessionName)}"))
            }
            assertEquals(0, status.exitCode)
            assertTrue(
                "agent `push status` must reflect the human's tick; got '${status.stdout}'",
                status.stdout.contains("run-the-tests-1"),
            )
        }

        // The rendered sheet now shows that item checked (host-backed state).
        assertTrue(
            "the rendered card must reflect the checked item",
            renderedCards.single().checkedIds.contains("run-the-tests-1"),
        )

        // ---------------------------------------------------------------
        // UNTICK round-trip — the human can change their mind.
        // ---------------------------------------------------------------
        toggle(card.id, "run-the-tests-1", checked = false)
        composeRule.waitForIdle()
        val afterUntick = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, sessionName) }
        }.cards.filterIsInstance<SessionCardsRemoteSource.ChecklistCard>().single()
        assertFalse(
            "after the app untick the host store must clear run-the-tests-1",
            "run-the-tests-1" in afterUntick.checkedIds,
        )
    }

    @Test
    fun noChecklistPushedYieldsEmptyFeedAndNoChip(): Unit = runBlocking {
        bootstrapKey()
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        // A session that never had a card pushed — the empty/no-card state.
        val emptySession = "issue859-empty-$suffix"

        val source = SessionCardsRemoteSource()
        val feed = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, emptySession) }
        }
        assertTrue(
            "a session with no pushed card must read back an empty feed",
            feed.cards.isEmpty(),
        )

        val cards = feed.cards.filterIsInstance<SessionCardsRemoteSource.ChecklistCard>()
        assertNull(
            "checklistChipState must be null for an empty feed (no chip)",
            checklistChipState(cards),
        )

        composeRule.setContent {
            PocketShellTheme {
                val chip = checklistChipState(cards)
                if (chip != null) {
                    ChecklistChip(state = chip, onClick = {})
                }
            }
        }
        composeRule.onNodeWithTag(SESSION_CHECKLIST_CHIP_TAG).assertDoesNotExist()
    }

    /**
     * RED→GREEN guard (D32 G10): the load-bearing "card reaches the feed"
     * assertions above only mean something if a BROKEN read path produces a
     * different, empty result. Here the card is pushed under [sessionName] but
     * the app reads under the WRONG tmux session name — the read-path-broken
     * signal — and the feed is empty + NO chip renders. So if a regression made
     * `getCards` ignore the session (or always return empty), the GREEN test
     * above would fail; if it always returned the card regardless of session,
     * THIS test would fail. The two together pin the real path.
     */
    @Test
    fun readPathBroken_wrongSessionName_yieldsEmptyFeed_redGuard(): Unit = runBlocking {
        bootstrapKey()
        waitForSshFixtureReady(sshKey, port = DEFAULT_PORT)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val sessionName = "issue859-guard-$suffix"
        cleanupCommands += "rm -f \"\$HOME/.pocketshell/cards/$sessionName.json\" 2>/dev/null || true"

        val source = SessionCardsRemoteSource()
        withSshSession { s ->
            val r = withTimeout(20_000) {
                s.exec(
                    pocketshellExec(
                        "push checklist --session ${shellQuote(sessionName)} " +
                            "--item ${shellQuote("Build the app")}",
                    ),
                )
            }
            assertEquals("push setup failed: '${r.stderr}'", 0, r.exitCode)
        }

        // Sanity: the RIGHT session name reads the card (the path works).
        val correct = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, sessionName) }
        }
        assertTrue(
            "control: the correct session name must read the pushed card",
            correct.cards.any { it is SessionCardsRemoteSource.ChecklistCard },
        )

        // The WRONG session name (read-path-broken signal) reads an empty feed.
        val wrong = withSshSession { s ->
            withTimeout(20_000) { source.getCards(s, sessionName + "-WRONG") }
        }
        assertTrue(
            "a wrong session name must NOT surface another session's card",
            wrong.cards.isEmpty(),
        )
        assertNull(
            "no chip renders for the broken read path",
            checklistChipState(
                wrong.cards.filterIsInstance<SessionCardsRemoteSource.ChecklistCard>(),
            ),
        )
    }

    // ----------------------------------------------------------- Helpers

    /**
     * Wrap a `pocketshell <args>` invocation the SAME way the app does
     * ([com.pocketshell.app.pocketshell.PocketshellCommand.wrap]) so the host
     * CLI is resolved from the non-interactive exec PATH. The fixture installs
     * `pocketshell` at `/usr/local/bin`, which the wrapper probes.
     */
    private fun pocketshellExec(args: String): String =
        com.pocketshell.app.pocketshell.PocketshellCommand.wrap(args)

    private fun bootstrapKey() {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue859-cards-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
    }

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
