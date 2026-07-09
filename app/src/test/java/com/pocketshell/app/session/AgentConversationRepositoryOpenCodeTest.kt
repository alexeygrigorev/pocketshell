package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class AgentConversationRepositoryOpenCodeTest {
    @Test
    fun openCodeReadInitialEventsExportsSqliteRowsForDetectedSession() = runTest {
        val session = FakeSshSession(
            sqliteOutput = """
                {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"check this\"}","part_time_created":101}
            """.trimIndent(),
        )
        val events = AgentConversationRepository().readInitialEvents(session, openCodeDetection())

        val message = events.single() as ConversationEvent.Message
        assertEquals(ConversationRole.User, message.role)
        assertEquals("check this", message.text)
        assertTrue(session.execCommands.single().contains("sqlite3 -readonly"))
        assertTrue(session.execCommands.single().contains("/home/alexey/.local/share/opencode/opencode.db"))
        assertTrue(session.execCommands.single().contains("WHERE session_id = "))
        assertTrue(session.execCommands.single().contains("ses_123"))
    }

    @Test
    fun openCodeReadInitialEventsLimitsMessagesBeforeJoiningParts() = runTest {
        val session = FakeSshSession(
            sqliteOutput = """
                {"message_id":"m2","message_data":"{\"role\":\"assistant\"}","message_time_created":200,"message_time_updated":200,"part_id":"p2a","part_data":"{\"type\":\"output_text\",\"text\":\"new a\"}","part_time_created":201}
                {"message_id":"m2","message_data":"{\"role\":\"assistant\"}","message_time_created":200,"message_time_updated":200,"part_id":"p2b","part_data":"{\"type\":\"output_text\",\"text\":\"new b\"}","part_time_created":202}
            """.trimIndent(),
        )

        val events = AgentConversationRepository().readInitialEvents(
            session = session,
            detection = openCodeDetection(),
            maxLines = 1,
        )

        assertEquals(listOf("new a", "new b"), events.map { (it as ConversationEvent.Message).text })
        val command = session.execCommands.single()
        assertTrue(command.contains("WITH recent_messages AS"))
        assertTrue(command.contains("FROM recent_messages m LEFT JOIN part p"))
        assertTrue(command.contains("LIMIT 1"))
    }

    @Test
    fun openCodeLineCountUsesDbMtimeCursor() = runTest {
        val session = FakeSshSession(statOutputs = ArrayDeque(listOf("1710000000\n")))

        val count = AgentConversationRepository().lineCount(session, openCodeDetection())

        assertEquals(1_710_000_000L, count)
        assertTrue(session.execCommands.single().contains("stat -c '%Y' "))
        assertTrue(session.execCommands.single().contains("stat -f '%m' "))
        assertTrue(session.execCommands.single().contains("opencode.db"))
        assertFalse(session.execCommands.single().contains("wc -l"))
    }

    @Test
    fun openCodeTailExportsSnapshotAndEmitsNewRowsWithoutStartupSeed() = runTest {
        val initialOutput = """
            {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"old\"}","part_time_created":101}
        """.trimIndent()
        val racedOutput = """
            {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"old\"}","part_time_created":101}
            {"message_id":"m2","message_data":"{\"role\":\"assistant\"}","message_time_created":200,"part_id":"p2","part_data":"{\"type\":\"output_text\",\"text\":\"arrived during startup\"}","part_time_created":201}
        """.trimIndent()
        val session = FakeSshSession(
            sqliteOutputs = ArrayDeque(listOf(initialOutput, racedOutput)),
        )
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val initialEvents = repository.readInitialEvents(session, openCodeDetection())
        val events = mutableListOf<ConversationEvent>()
        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(1L)
        job?.cancel()

        assertEquals(listOf("old"), initialEvents.map { (it as ConversationEvent.Message).text })
        assertEquals(
            listOf("old", "arrived during startup"),
            events.map { (it as ConversationEvent.Message).text },
        )
        assertEquals(0, session.tailCalls)
        assertEquals(2, session.execCommands.count { it.contains("sqlite3 -readonly") })
    }

    @Test
    fun openCodeTailEmitsSameIdWhenContentChanges() = runTest {
        val session = FakeSshSession(
            sqliteOutputs = ArrayDeque(
                listOf(
                    """
                        {"message_id":"m1","message_data":"{\"role\":\"assistant\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"output_text\",\"text\":\"draft\"}","part_time_created":101}
                    """.trimIndent(),
                    """
                        {"message_id":"m1","message_data":"{\"role\":\"assistant\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"output_text\",\"text\":\"final\"}","part_time_created":101}
                    """.trimIndent(),
                ),
            ),
        )
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(25L)
        job?.cancel()

        assertEquals(listOf("draft", "final"), events.map { (it as ConversationEvent.Message).text })
        assertEquals(listOf("p1", "p1"), events.map { it.id })
        assertEquals(0, session.tailCalls)
        assertFalse(session.execCommands.any { it.contains("stat -c '%Y' ") })
    }

    @Test
    fun openCodeTailDoesNotMissWritesWhenDbMtimeStaysTheSame() = runTest {
        val session = FakeSshSession(
            statOutputs = ArrayDeque(listOf("10\n", "10\n", "10\n")),
            sqliteOutputs = ArrayDeque(
                listOf(
                    """
                        {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"old\"}","part_time_created":101}
                    """.trimIndent(),
                    """
                        {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"old\"}","part_time_created":101}
                        {"message_id":"m2","message_data":"{\"role\":\"assistant\"}","message_time_created":200,"part_id":"p2","part_data":"{\"type\":\"output_text\",\"text\":\"same-mtime write\"}","part_time_created":201}
                    """.trimIndent(),
                ),
            ),
        )
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(25L)
        job?.cancel()

        assertEquals(
            listOf("old", "same-mtime write"),
            events.map { (it as ConversationEvent.Message).text },
        )
        assertFalse(session.execCommands.any { it.contains("stat -c '%Y' ") })
    }

    @Test
    fun openCodeTailDoesNotReemitChangedOldIdAfterCacheTrim() = runTest {
        val initialRows = openCodeRows(
            (1..DEFAULT_MAX_AGENT_EVENTS * 2).map { index ->
                openCodeRow(index = index, text = "message $index")
            },
        )
        val changedRows = openCodeRows(
            listOf(openCodeRow(index = 1, text = "message 1 updated")) +
                (3..DEFAULT_MAX_AGENT_EVENTS * 2 + 1).map { index ->
                    openCodeRow(index = index, text = "message $index")
                },
        )
        val session = FakeSshSession(
            sqliteOutputs = ArrayDeque(listOf(initialRows, changedRows, changedRows)),
        )
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(25L)
        job?.cancel()

        val texts = events.map { (it as ConversationEvent.Message).text }
        assertEquals(1, texts.count { it == "message 1 updated" })
        assertEquals(DEFAULT_MAX_AGENT_EVENTS * 2 + 2, events.size)
        assertEquals(3, session.execCommands.count { it.contains("sqlite3 -readonly") })
    }

    @Test
    fun openCodeTailDoesNotReemitStableRowsWhenSnapshotHasMoreEventsThanMessages() = runTest {
        val snapshot = openCodeRows(
            (1..DEFAULT_MAX_AGENT_EVENTS * 2).flatMap { index ->
                listOf(
                    openCodePartRow(index = index, partId = "p${index}a", text = "message $index part a"),
                    openCodePartRow(index = index, partId = "p${index}b", text = "message $index part b"),
                )
            },
        )
        val session = FakeSshSession(
            sqliteOutputs = ArrayDeque(listOf(snapshot, snapshot)),
        )
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(15L)
        job?.cancel()

        assertEquals(DEFAULT_MAX_AGENT_EVENTS * 4, events.size)
        assertEquals(events.map { it.id }.distinct(), events.map { it.id })
        assertEquals(2, session.execCommands.count { it.contains("sqlite3 -readonly") })
    }

    @Test
    fun openCodeTailEndsCleanlyWhenSqliteExportThrows() = runTest {
        val session = FakeSshSession(sqliteFailure = SshException("transport closed"))
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        } ?: error("OpenCode detection should start a polling job")
        job.join()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertTrue(events.isEmpty())
        assertEquals(1, session.execCommands.count { it.contains("sqlite3 -readonly") })
    }

    @Test
    fun recordedOpenCodeSourceBindsSqliteOverStaleClaudeJsonl() = runTest {
        val nowSeconds = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|${nowSeconds}|/workspace/pocketshell|/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl
                opencode|${nowSeconds - 60}|/workspace/pocketshell|/home/testuser/.local/share/opencode/opencode.db#opencode-1
            """.trimIndent(),
            hostWideProcessOutput = """
                4242 4000 pts/3 node node /usr/local/bin/opencode --fixture /home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl
            """.trimIndent(),
        )

        // Epic #821 A2: source resolution for a KNOWN (recorded/guessed)
        // OpenCode kind binds to the OpenCode SQLite session, not the stale
        // Claude JSONL. (Kind-guessing is hard-cut; the kind is passed in.)
        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/pocketshell",
            paneTty = "/dev/pts/3",
            paneCommand = "opencode",
            recordedKind = AgentKind.OpenCode,
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("opencode-1", detection?.sessionId)
    }

    private fun openCodeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.OpenCode,
        sourcePath = "/home/alexey/.local/share/opencode/opencode.db#ses_123",
        sessionId = "ses_123",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun openCodeRows(rows: List<String>): String = rows.joinToString("\n")

    private fun openCodeRow(index: Int, text: String): String =
        """{"message_id":"m$index","message_data":"{\"role\":\"assistant\"}","message_time_created":$index,"message_time_updated":$index,"part_id":"p$index","part_data":"{\"type\":\"output_text\",\"text\":\"$text\"}","part_time_created":$index}"""

    private fun openCodePartRow(index: Int, partId: String, text: String): String =
        """{"message_id":"m$index","message_data":"{\"role\":\"assistant\"}","message_time_created":$index,"message_time_updated":$index,"part_id":"$partId","part_data":"{\"type\":\"output_text\",\"text\":\"$text\"}","part_time_created":$index}"""

    private class FakeSshSession(
        private val sqliteOutput: String = "",
        private val statOutputs: ArrayDeque<String> = ArrayDeque(),
        private val sqliteOutputs: ArrayDeque<String> = ArrayDeque(),
        private val sqliteFailure: Throwable? = null,
        private val detectionOutput: String = "",
        private val hostWideProcessOutput: String = "",
    ) : SshSession {
        val execCommands = mutableListOf<String>()
        var tailCalls = 0

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val stdout = when {
                command.contains("claude_dir=") -> detectionOutput
                command.contains("ps -eo pid,ppid,tty,comm,args") -> hostWideProcessOutput
                command.contains("ps -eo pid,tty,comm,args") -> hostWideProcessOutput
                command.contains("stat -c '%Y' ") ->
                    statOutputs.removeFirstOrNull() ?: statOutputs.lastOrNull() ?: "0\n"
                command.contains("sqlite3 -readonly") -> {
                    sqliteFailure?.let { throw it }
                    sqliteOutputs.removeFirstOrNull() ?: sqliteOutput
                }
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job {
            tailCalls += 1
            return Job()
        }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job {
            tailCalls += 1
            return Job()
        }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            throw NotImplementedError()
        }

        override fun startShell(): SshShell {
            throw NotImplementedError()
        }

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }
}
