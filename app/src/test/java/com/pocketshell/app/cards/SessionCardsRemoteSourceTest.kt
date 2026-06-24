package com.pocketshell.app.cards

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
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
                      "id": "note-1",
                      "type": "note",
                      "title": "Heads up",
                      "created_at": "2026-06-20T21:00:00+00:00",
                      "updated_at": "2026-06-20T21:00:00+00:00",
                      "body": {"text": "later type"},
                      "state": {"read": false}
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
        val unknown = feed.cards[1] as SessionCardsRemoteSource.UnknownCard
        assertEquals("note", unknown.type)
        assertEquals("Heads up", unknown.title)

        val command = session.recorded.single()
        assertTrue(command, command.contains("push get --json"))
        assertTrue(command, command.contains("--session 'demo'"))
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
    ): RoutingSshSession = RoutingSshSession(
        getResult = getResult ?: ExecResult(getStdout, "", 0),
        checkResult = checkResult,
    )

    private class RoutingSshSession(
        private val getResult: ExecResult,
        private val checkResult: ExecResult,
    ) : SshSession {
        val recorded = mutableListOf<String>()
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            return when {
                command.contains("push get") -> getResult
                command.contains("push check") -> checkResult
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
