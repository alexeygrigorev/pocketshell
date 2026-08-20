package com.pocketshell.app.tmux

import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelSessionCardsTest : TmuxSessionViewModelTestBase() {
    @Test
    fun refreshActiveSessionCardsPublishesFeedForActiveSession() = runTest(scheduler) {
        val session = CardsSshSession(
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        assertTrue(vm.refreshActiveSessionCards())
        awaitCardsState(vm) { !it.loading && it.sessionName == "work" && it.feed.cards.isNotEmpty() }

        val card = vm.sessionCards.value.feed.cards.single() as SessionCardsRemoteSource.ChecklistCard
        assertEquals("release", card.id)
        assertEquals(setOf("build-0"), card.checkedIds)
        assertTrue(
            "card refresh must use the current tmux session name",
            session.execCommands.any { it.contains("push get") && it.contains("--session 'work'") },
        )
    }

    @Test
    fun refreshActiveSessionCardsDropsLateFeedWhenHostChangesButSessionNameMatches() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val oldSession = CardsSshSession(
            execGate = gate,
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = oldSession,
        )

        assertTrue(vm.refreshActiveSessionCards())
        awaitCondition { oldSession.execCommands.any { it.contains("push get") } }

        vm.replaceClientForTest(
            hostId = 2L,
            hostName = "beta",
            host = "beta.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/b",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = CardsSshSession(),
        )
        gate.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            "late card feed from old host must not publish after switching to same-named session",
            vm.sessionCards.value.feed.cards.isEmpty(),
        )
    }

    @Test
    fun refreshActiveSessionCardsReturnsFalseWithoutWarmSession() = runTest(scheduler) {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = null,
        )

        assertFalse(vm.refreshActiveSessionCards())
        assertEquals(TmuxSessionViewModel.SessionCardsUiState(), vm.sessionCards.value)
    }

    @Test
    fun toggleChecklistItemWritesThenRefreshesFeedOnSuccess() = runTest(scheduler) {
        val session = CardsSshSession(
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        assertTrue(vm.toggleChecklistItem(cardId = "release", itemId = "build-0", checked = true))
        awaitCardsState(vm) { !it.loading && it.feed.cards.isNotEmpty() }

        val checkCommand = session.execCommands.single { it.contains("push check") }
        assertTrue(checkCommand.contains("--id 'release'"))
        assertTrue(checkCommand.contains("--item 'build-0'"))
        assertTrue(checkCommand.contains("--done"))
        assertTrue(checkCommand.contains("--session 'work'"))
        val card = vm.sessionCards.value.feed.cards.single() as SessionCardsRemoteSource.ChecklistCard
        assertEquals(setOf("build-0"), card.checkedIds)
    }

    @Test
    fun toggleChecklistItemDropsPostWriteRefreshWhenHostChangesButSessionNameMatches() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val oldSession = CardsSshSession(
            execGate = gate,
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = oldSession,
        )

        assertTrue(vm.toggleChecklistItem(cardId = "release", itemId = "build-0", checked = true))
        awaitCondition { oldSession.execCommands.any { it.contains("push check") } }

        vm.replaceClientForTest(
            hostId = 2L,
            hostName = "beta",
            host = "beta.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/b",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = CardsSshSession(),
        )
        gate.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertTrue(oldSession.execCommands.none { it.contains("push get") })
        assertTrue(
            "post-write refresh from old host must not publish after switching to same-named session",
            vm.sessionCards.value.feed.cards.isEmpty(),
        )
    }

    @Test
    fun toggleChecklistItemDoesNotRefreshOnHostFailure() = runTest(scheduler) {
        val session = CardsSshSession(
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
            cardCheckExitCode = 1,
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        assertTrue(vm.toggleChecklistItem(cardId = "release", itemId = "build-0", checked = false))
        awaitCondition { session.execCommands.any { it.contains("push check") } }

        assertTrue(session.execCommands.none { it.contains("push get") })
        assertEquals(TmuxSessionViewModel.SessionCardsUiState(), vm.sessionCards.value)
    }

    @Test
    fun productionScreenMarkReadCallbackWritesThroughViewModel() = runTest(scheduler) {
        // G6: the remaining #859 gap was TmuxSessionScreen.onSetNoteRead = Unit.
        // That callback now lives in tmuxSessionCardInteractions (the factory
        // the screen remember()s). Stubbing it back to Unit issues no `push
        // read` and this wait times out.
        val session = CardsSshSession(
            cardGetStdouts = listOf(noteFeedJson("work", read = true)),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        tmuxSessionCardInteractions(vm).onSetNoteRead(cardId = "note", read = true)
        awaitCardsState(vm) { !it.loading && it.feed.cards.isNotEmpty() }

        val readCommand = session.execCommands.single { it.contains("push read") }
        assertTrue(readCommand.contains("--id 'note'"))
        assertTrue(readCommand.contains("--read"))
        assertTrue(readCommand.contains("--session 'work'"))
        val card = vm.sessionCards.value.feed.cards.single() as SessionCardsRemoteSource.NoteCard
        assertTrue(card.read)
    }

    @Test
    fun setNoteReadWritesThenRefreshesFeedOnSuccess() = runTest(scheduler) {
        // G6 mutation: make setNoteRead a no-op (or skip the host write) →
        // no `push read` command is recorded and the feed stays unread.
        val session = CardsSshSession(
            cardGetStdouts = listOf(noteFeedJson("work", read = true)),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        assertTrue(vm.setNoteRead(cardId = "note", read = true))
        awaitCardsState(vm) { !it.loading && it.feed.cards.isNotEmpty() }

        val readCommand = session.execCommands.single { it.contains("push read") }
        assertTrue(readCommand.contains("--id 'note'"))
        assertTrue(readCommand.contains("--read"))
        assertTrue(readCommand.contains("--session 'work'"))
        val card = vm.sessionCards.value.feed.cards.single() as SessionCardsRemoteSource.NoteCard
        assertTrue(card.read)
    }

    @Test
    fun setNoteReadDropsPostWriteRefreshWhenHostChangesButSessionNameMatches() = runTest(scheduler) {
        // G6 mutation: drop the sessionCardsTargetKey() guard after the write →
        // the late refresh from the old host publishes into the new same-named
        // session (the cross-session leak this guard exists to stop).
        val gate = CompletableDeferred<Unit>()
        val oldSession = CardsSshSession(
            execGate = gate,
            cardGetStdouts = listOf(noteFeedJson("work", read = true)),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = oldSession,
        )

        assertTrue(vm.setNoteRead(cardId = "note", read = true))
        awaitCondition { oldSession.execCommands.any { it.contains("push read") } }

        vm.replaceClientForTest(
            hostId = 2L,
            hostName = "beta",
            host = "beta.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/b",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = CardsSshSession(),
        )
        gate.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertTrue(oldSession.execCommands.none { it.contains("push get") })
        assertTrue(
            "post-write refresh from old host must not publish after switching to same-named session",
            vm.sessionCards.value.feed.cards.isEmpty(),
        )
    }

    @Test
    fun setNoteReadDoesNotRefreshOnHostFailure() = runTest(scheduler) {
        // G6 mutation: refresh the feed even when push read fails → a `push get`
        // appears and the unread host state is painted as if the write landed.
        val session = CardsSshSession(
            cardGetStdouts = listOf(noteFeedJson("work", read = true)),
            cardReadExitCode = 1,
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        assertTrue(vm.setNoteRead(cardId = "note", read = false))
        awaitCondition { session.execCommands.any { it.contains("push read") } }

        assertTrue(session.execCommands.none { it.contains("push get") })
        assertEquals(TmuxSessionViewModel.SessionCardsUiState(), vm.sessionCards.value)
    }

    private class CardsSshSession(
        private val execGate: CompletableDeferred<Unit>? = null,
        private val cardGetStdouts: List<String> = emptyList(),
        private val cardCheckExitCode: Int = 0,
        private val cardReadExitCode: Int = 0,
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        private var cardGetIndex: Int = 0

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            execGate?.await()
            return when {
                command.contains("push get") -> {
                    val stdout = cardGetStdouts.getOrNull(cardGetIndex)
                        ?: cardGetStdouts.lastOrNull()
                        ?: ""
                    if (cardGetStdouts.isNotEmpty()) cardGetIndex += 1
                    ExecResult(stdout = stdout, stderr = "", exitCode = 0)
                }
                command.contains("push check") ->
                    ExecResult(stdout = "", stderr = "", exitCode = cardCheckExitCode)
                command.contains("push read") ->
                    ExecResult(stdout = "", stderr = "", exitCode = cardReadExitCode)
                else ->
                    ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()

        override fun startShell(): SshShell = throw NotImplementedError()

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
