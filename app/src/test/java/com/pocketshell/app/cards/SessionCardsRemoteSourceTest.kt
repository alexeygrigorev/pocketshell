package com.pocketshell.app.cards

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCardsRemoteSourceTest {

    private val source = SessionCardsRemoteSource()

    @Test
    fun getCardsParsesChecklistAndPreservesUnknownCardTypes() = runTest {
        val session = cardsSession(
            getStdout = """
                {
                  "session": "demo",
                  "cards": [
                    {
                      "id": "checklist",
                      "type": "checklist",
                      "title": "Deploy",
                      "created_at": "2026-06-20T20:00:00+00:00",
                      "updated_at": "2026-06-20T20:01:00+00:00",
                      "body": {
                        "items": [
                          {"id": "build-0", "text": "build"},
                          {"id": "ship-1", "text": "ship"}
                        ]
                      },
                      "state": {"checked": ["build-0"]}
                    },
                    {
                      "id": "future-1",
                      "type": "approval",
                      "title": "Heads up",
                      "created_at": "2026-06-20T21:00:00+00:00",
                      "updated_at": "2026-06-20T21:00:00+00:00",
                      "body": {"choices": ["yes", "no"]},
                      "state": {"chosen": null}
                    }
                  ]
                }
            """.trimIndent(),
        )

        val feed = source.getCards(session, tmuxSessionName = "demo")

        assertEquals("demo", feed.session)
        assertEquals(2, feed.cards.size)
        val checklist = feed.cards[0] as SessionCardsRemoteSource.ChecklistCard
        assertEquals("checklist", checklist.id)
        assertEquals("Deploy", checklist.title)
        assertEquals("2026-06-20T20:00:00+00:00", checklist.createdAt)
        assertEquals(listOf("build", "ship"), checklist.items.map { it.text })
        assertEquals(setOf("build-0"), checklist.checkedIds)
        // A type with no registered renderer still degrades to UnknownCard.
        val unknown = feed.cards[1] as SessionCardsRemoteSource.UnknownCard
        assertEquals("approval", unknown.type)
        assertEquals("Heads up", unknown.title)

        val command = session.recorded.single()
        assertTrue(command, command.contains("push get --json"))
        assertTrue(command, command.contains("--session 'demo'"))
    }

    @Test
    fun getCardsParsesNoteCardViaRegistry() = runTest {
        // #859 Slice B: a `note` card now parses into a typed NoteCard (no code
        // change to the feed dispatch — it is a registered type), where before
        // Slice B it degraded to an UnknownCard tombstone.
        val session = cardsSession(
            getStdout = """
                {
                  "session": "demo",
                  "cards": [
                    {
                      "id": "note",
                      "type": "note",
                      "title": "Heads up",
                      "created_at": "2026-06-25T10:00:00+00:00",
                      "updated_at": "2026-06-25T10:01:00+00:00",
                      "body": {"text": "deploy finished"},
                      "state": {"read": true, "read_at": "2026-06-25T10:01:00+00:00"}
                    }
                  ]
                }
            """.trimIndent(),
        )

        val feed = source.getCards(session, tmuxSessionName = "demo")

        val note = feed.cards.single() as SessionCardsRemoteSource.NoteCard
        assertEquals("note", note.id)
        assertEquals("Heads up", note.title)
        assertEquals("deploy finished", note.text)
        assertTrue(note.read)
    }

    @Test
    fun getCardsParsesMixedChecklistAndNoteFeed() = runTest {
        val session = cardsSession(
            getStdout = """
                {
                  "session": "demo",
                  "cards": [
                    {
                      "id": "checklist", "type": "checklist", "title": "Deploy",
                      "body": {"items": [{"id": "build-0", "text": "build"}]},
                      "state": {"checked": []}
                    },
                    {
                      "id": "note", "type": "note", "title": null,
                      "body": {"text": "fyi"}, "state": {"read": false}
                    },
                    {
                      "id": "future", "type": "approval", "title": "Approve?",
                      "body": {}, "state": {}
                    }
                  ]
                }
            """.trimIndent(),
        )

        val feed = source.getCards(session, tmuxSessionName = "demo")

        assertEquals(3, feed.cards.size)
        assertTrue(feed.cards[0] is SessionCardsRemoteSource.ChecklistCard)
        val note = feed.cards[1] as SessionCardsRemoteSource.NoteCard
        assertEquals("fyi", note.text)
        assertFalse(note.read)
        // A genuinely unknown future type still degrades to UnknownCard.
        val unknown = feed.cards[2] as SessionCardsRemoteSource.UnknownCard
        assertEquals("approval", unknown.type)
    }

    @Test
    fun getCardsDropsDuplicateCardIds() = runTest {
        val session = cardsSession(
            getStdout = """
                {
                  "session": "demo",
                  "cards": [
                    {
                      "id": "same", "type": "checklist", "title": "First",
                      "body": {"items": [{"id": "build-0", "text": "build"}]},
                      "state": {"checked": []}
                    },
                    {
                      "id": "same", "type": "note", "title": "Duplicate",
                      "body": {"text": "should not render with the same lazy key"},
                      "state": {"read": false}
                    },
                    {
                      "id": "other", "type": "note", "title": "Other",
                      "body": {"text": "ok"},
                      "state": {"read": true}
                    }
                  ]
                }
            """.trimIndent(),
        )

        val feed = source.getCards(session, tmuxSessionName = "demo")

        assertEquals(listOf("same", "other"), feed.cards.map { it.id })
        assertTrue(feed.cards[0] is SessionCardsRemoteSource.ChecklistCard)
        assertTrue(feed.cards[1] is SessionCardsRemoteSource.NoteCard)
    }

    @Test
    fun malformedCardDoesNotReserveDuplicateId() = runTest {
        val session = cardsSession(
            getStdout = """
                {
                  "session": "demo",
                  "cards": [
                    {"id": "same", "title": "Missing type"},
                    {
                      "id": "same", "type": "note", "title": "Valid",
                      "body": {"text": "ok"},
                      "state": {"read": true}
                    }
                  ]
                }
            """.trimIndent(),
        )

        val feed = source.getCards(session, tmuxSessionName = "demo")

        val note = feed.cards.single() as SessionCardsRemoteSource.NoteCard
        assertEquals("same", note.id)
        assertEquals("Valid", note.title)
    }

    @Test
    fun setNoteReadBuildsReadCommandAndReturnsAck() = runTest {
        val session = cardsSession(readResult = ExecResult("note: read", "", 0))

        val ok = source.setNoteRead(
            session = session,
            tmuxSessionName = "demo ' prod",
            cardId = "no'te",
            read = true,
        )

        assertTrue(ok)
        val command = session.recorded.single()
        assertTrue(command, command.contains("push read"))
        assertTrue(command, command.contains("--id ${shellQuote("no'te")}"))
        assertTrue(command, command.contains("--read"))
        assertTrue(command, command.contains("--session ${shellQuote("demo ' prod")}"))
    }

    @Test
    fun setNoteUnreadUsesUnreadFlagAndFalseOnFailure() = runTest {
        val session = cardsSession(readResult = ExecResult("", "no card", 2))

        val ok = source.setNoteRead(
            session = session,
            tmuxSessionName = "demo",
            cardId = "note",
            read = false,
        )

        assertFalse(ok)
        val command = session.recorded.single()
        assertTrue(command, command.contains("--unread"))
        assertFalse(command.contains("--read "))
    }

    @Test
    fun blankNoteInputsDoNotRunReadCommand() = runTest {
        val session = cardsSession()

        assertFalse(
            source.setNoteRead(
                session = session,
                tmuxSessionName = " ",
                cardId = "note",
                read = true,
            ),
        )
        assertFalse(
            source.setNoteRead(
                session = session,
                tmuxSessionName = "demo",
                cardId = " ",
                read = true,
            ),
        )
        assertTrue(session.recorded.isEmpty())
    }

    @Test
    fun getCardsEmptyFeedReturnsEmptyList() = runTest {
        val session = cardsSession(getStdout = """{"session":"empty","cards":[]}""")

        val feed = source.getCards(session, tmuxSessionName = "empty")

        assertEquals("empty", feed.session)
        assertTrue(feed.cards.isEmpty())
    }

    @Test
    fun getCardsFailureDegradesToEmptyFeed() = runTest {
        assertTrue(
            source.getCards(
                cardsSession(getResult = ExecResult("", "missing", 127)),
                tmuxSessionName = "demo",
            ).cards.isEmpty(),
        )
        assertTrue(
            source.getCards(
                cardsSession(getStdout = "not json"),
                tmuxSessionName = "demo",
            ).cards.isEmpty(),
        )
    }

    @Test
    fun getCardsTimeoutDegradesToEmptyFeedAndClosesSession() = runTest {
        val timeoutSource = SessionCardsRemoteSource(execReadTimeoutMs = 10L).also {
            it.setExecDispatcherForTest(StandardTestDispatcher(testScheduler))
        }
        val session = NeverReturningSshSession()

        val feed = timeoutSource.getCards(session, tmuxSessionName = "demo")

        assertTrue(feed.cards.isEmpty())
        assertTrue(session.closed)
    }

    @Test
    fun checklistWriteTimeoutReturnsFalseAndClosesSession() = runTest {
        val timeoutSource = SessionCardsRemoteSource(execReadTimeoutMs = 10L).also {
            it.setExecDispatcherForTest(StandardTestDispatcher(testScheduler))
        }
        val session = NeverReturningSshSession()

        val ok = timeoutSource.setChecklistItemChecked(
            session = session,
            tmuxSessionName = "demo",
            cardId = "checklist",
            itemId = "build-0",
            checked = true,
        )

        assertFalse(ok)
        assertTrue(session.closed)
    }

    @Test
    fun setChecklistItemCheckedBuildsCheckCommandAndReturnsAck() = runTest {
        val session = cardsSession(checkResult = ExecResult("checklist: checklist 1/2 checked", "", 0))

        val ok = source.setChecklistItemChecked(
            session = session,
            tmuxSessionName = "demo ' prod",
            cardId = "check'list",
            itemId = "build ' apk-0",
            checked = true,
        )

        assertTrue(ok)
        val command = session.recorded.single()
        assertTrue(command, command.contains("push check"))
        assertTrue(command, command.contains("--id ${shellQuote("check'list")}"))
        assertTrue(command, command.contains("--item ${shellQuote("build ' apk-0")}"))
        assertTrue(command, command.contains("--done"))
        assertTrue(command, command.contains("--session ${shellQuote("demo ' prod")}"))
    }

    @Test
    fun setChecklistItemUncheckedUsesUndoneAndFalseOnFailure() = runTest {
        val session = cardsSession(checkResult = ExecResult("", "unknown item", 2))

        val ok = source.setChecklistItemChecked(
            session = session,
            tmuxSessionName = "demo",
            cardId = "checklist",
            itemId = "build-0",
            checked = false,
        )

        assertFalse(ok)
        val command = session.recorded.single()
        assertTrue(command, command.contains("--undone"))
        assertFalse(command.contains("--done "))
    }

    @Test
    fun blankInputsDoNotRunCheckCommand() = runTest {
        val session = cardsSession()

        assertFalse(
            source.setChecklistItemChecked(
                session = session,
                tmuxSessionName = " ",
                cardId = "checklist",
                itemId = "build-0",
                checked = true,
            ),
        )

        assertTrue(session.recorded.isEmpty())
    }

    @Test
    fun cancellationPropagates() {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { source.getCards(session, tmuxSessionName = "demo") }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                source.setChecklistItemChecked(
                    session = session,
                    tmuxSessionName = "demo",
                    cardId = "checklist",
                    itemId = "build-0",
                    checked = true,
                )
            }
        }
    }

    private fun cardsSession(
        getStdout: String = "",
        getResult: ExecResult? = null,
        checkResult: ExecResult = ExecResult("", "", 0),
        readResult: ExecResult = ExecResult("", "", 0),
    ): RoutingSshSession = RoutingSshSession(
        getResult = getResult ?: ExecResult(getStdout, "", 0),
        checkResult = checkResult,
        readResult = readResult,
    )

    private class RoutingSshSession(
        private val getResult: ExecResult,
        private val checkResult: ExecResult,
        private val readResult: ExecResult,
    ) : SshSession {
        val recorded = mutableListOf<String>()
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            return when {
                command.contains("push get") -> getResult
                command.contains("push check") -> checkResult
                command.contains("push read") -> readResult
                else -> ExecResult("", "no route for $command", 127)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() = Unit
    }

    private class NeverReturningSshSession : SshSession {
        override val isConnected: Boolean = true
        var closed = false
            private set

        override suspend fun exec(command: String): ExecResult = awaitCancellation()
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() {
            closed = true
        }
    }

    private class ThrowingSshSession(private val throwable: Throwable) : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = throw throwable
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() = Unit
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
