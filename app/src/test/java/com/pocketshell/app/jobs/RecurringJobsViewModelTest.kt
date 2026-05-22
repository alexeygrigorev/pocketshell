package com.pocketshell.app.jobs

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecurringJobsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val remoteSource = TmuxctlJobsRemoteSource(TmuxctlJobsParser())

    @Test
    fun loadConnectsAndFetchesJobsForSession() = runTest {
        val session = FakeSshSession(
            pathAware("tmuxctl jobs list --session 'agent main'") to listOutput(id = 7, sessionName = "agent main"),
        )
        val viewModel = newViewModel(session)

        viewModel.load(
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "agent main",
        )
        advanceUntilIdle()

        assertEquals("Lab", viewModel.state.value.hostName)
        assertEquals("agent main", viewModel.state.value.sessionName)
        assertEquals(listOf(7), viewModel.state.value.jobs.map { it.id })
        assertFalse(viewModel.state.value.loading)
        assertEquals(null, viewModel.state.value.error)
        assertEquals(listOf(pathAware("tmuxctl jobs list --session 'agent main'")), session.recorded)
    }

    @Test
    fun refreshMapsMissingTmuxctlToErrorState() = runTest {
        val session = FakeSshSession(
            pathAware("tmuxctl jobs list --session 'codex'") to ExecResult("", "tmuxctl: not found", 127),
        )
        val viewModel = newViewModel(session)

        viewModel.load(
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "codex",
        )
        advanceUntilIdle()

        assertEquals("tmuxctl is not installed on this host", viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun addRefreshesTheBoundSessionAfterSuccess() = runTest {
        val session = FakeSshSession(
            pathAware("tmuxctl jobs list --session 'codex'") to listOf(
                listOutput(id = 1, sessionName = "codex"),
                listOutput(id = 2, sessionName = "codex"),
            ),
            pathAware("tmuxctl jobs add 'codex' --every '5m' --message 'continue'") to ExecResult("Created job 2\n", "", 0),
        )
        val viewModel = newViewModel(session)

        viewModel.load(
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "codex",
        )
        advanceUntilIdle()
        viewModel.add(RecurringJobDraft(sessionName = "codex", every = "5m", message = "continue"))
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.state.value.jobs.map { it.id })
        assertEquals(
            listOf(
                pathAware("tmuxctl jobs list --session 'codex'"),
                pathAware("tmuxctl jobs add 'codex' --every '5m' --message 'continue'"),
                pathAware("tmuxctl jobs list --session 'codex'"),
            ),
            session.recorded,
        )
    }

    private fun newViewModel(session: SshSession): RecurringJobsViewModel =
        RecurringJobsViewModel(
            remoteSource = remoteSource,
            connector = FakeConnector(Result.success(session)),
        )

    private class FakeConnector(
        private val result: Result<SshSession>,
    ) : RecurringJobsViewModel.RecurringJobsSshConnector {
        override suspend fun connect(target: RecurringJobsViewModel.Target): Result<SshSession> = result
    }

    private class FakeSshSession(
        vararg scripted: Pair<String, Any>,
    ) : SshSession {
        private val canned: Map<String, ArrayDeque<ExecResult>> =
            scripted.associate { (command, value) ->
                val results = when (value) {
                    is ExecResult -> listOf(value)
                    is List<*> -> value.filterIsInstance<ExecResult>()
                    else -> error("Unsupported fake result type: ${value::class.java.simpleName}")
                }
                command to ArrayDeque(results)
            }

        val recorded = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            return canned[command]?.removeFirstOrNull() ?: ExecResult("", "missing stub for $command", 127)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override fun close() = Unit
    }

    private fun listOutput(id: Int, sessionName: String): ExecResult =
        ExecResult(
            stdout = """
                ID  ENABLED  SESSION       EVERY  DELAY  SOURCE  NEXT RUN             DETAIL
                $id   yes      ${sessionName.padEnd(14)}15m    200    inline  2026-04-03 00:30:00 continue work
            """.trimIndent(),
            stderr = "",
            exitCode = 0,
        )

    private fun pathAware(command: String): String =
        "PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\"; $command"
}
