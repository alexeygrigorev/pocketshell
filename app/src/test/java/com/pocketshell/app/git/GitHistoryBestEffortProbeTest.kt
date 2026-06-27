package com.pocketshell.app.git

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class GitHistoryBestEffortProbeTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher(scheduler))

    @Test
    fun hungOverviewProbeDoesNotKeepLoadedCommitsInLoading() = runTest(scheduler) {
        val session = RoutingSshSession { command ->
            when {
                command.contains("rev-parse --is-inside-work-tree") ->
                    ExecResult(stdout = "true\n", stderr = "", exitCode = 0)
                command.contains(" log --no-color --max-count=") ->
                    ExecResult(stdout = commitLog(), stderr = "", exitCode = 0)
                command.contains("status --porcelain=v2 --branch") ->
                    awaitCancellation()
                command.contains("remote get-url origin") ->
                    ExecResult(stdout = "", stderr = "no origin", exitCode = 2)
                command.contains("github status --json") ->
                    ExecResult(
                        stdout = """{"installed":false,"authenticated":false,"hint":"install gh"}""",
                        stderr = "",
                        exitCode = 0,
                    )
                else -> ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
        }
        val viewModel = newViewModel(session, probeTimeoutMs = 100L)

        viewModel.start(request())
        runCurrent()
        assertTrue(viewModel.state.value is GitHistoryUiState.Loading)

        advanceTimeBy(100L)
        runCurrent()

        val ready = viewModel.state.value as GitHistoryUiState.Ready
        assertEquals(listOf("abc1234"), ready.commits.map { it.shortHash })
        assertNull(ready.overview)
        assertEquals("install gh", ready.ghHint)
    }

    @Test
    fun hungIssueListDoesNotKeepLoadedCommitsInLoading() = runTest(scheduler) {
        val session = RoutingSshSession { command ->
            when {
                command.contains("rev-parse --is-inside-work-tree") ->
                    ExecResult(stdout = "true\n", stderr = "", exitCode = 0)
                command.contains(" log --no-color --max-count=") ->
                    ExecResult(stdout = commitLog(), stderr = "", exitCode = 0)
                command.contains("status --porcelain=v2 --branch") ->
                    ExecResult(stdout = "# branch.head main\n", stderr = "", exitCode = 0)
                command.contains(" log -1 --no-color") ->
                    ExecResult(stdout = "abc1234 Initial", stderr = "", exitCode = 0)
                command.contains("branch --no-color --format") ->
                    ExecResult(stdout = "", stderr = "", exitCode = 0)
                command.contains("worktree list --porcelain") ->
                    ExecResult(stdout = "", stderr = "", exitCode = 0)
                command.contains("remote get-url origin") ->
                    ExecResult(stdout = "", stderr = "no origin", exitCode = 2)
                command.contains("github status --json") ->
                    ExecResult(
                        stdout = """{"installed":true,"authenticated":true,"account":"alexey"}""",
                        stderr = "",
                        exitCode = 0,
                    )
                command.contains("gh issue list") ->
                    awaitCancellation()
                else -> ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
        }
        val viewModel = newViewModel(session, probeTimeoutMs = 100L)

        viewModel.start(request())
        runCurrent()
        assertTrue(viewModel.state.value is GitHistoryUiState.Loading)

        advanceTimeBy(100L)
        runCurrent()

        val ready = viewModel.state.value as GitHistoryUiState.Ready
        assertEquals(listOf("abc1234"), ready.commits.map { it.shortHash })
        assertNull(ready.issues)
        assertNull(ready.ghHint)
    }

    @Test
    fun hungIssueCreateSettlesTheCreateStateAsFailure() = runTest(scheduler) {
        val session = RoutingSshSession { command ->
            when {
                command.contains("rev-parse --is-inside-work-tree") ->
                    ExecResult(stdout = "true\n", stderr = "", exitCode = 0)
                command.contains(" log --no-color --max-count=") ->
                    ExecResult(stdout = commitLog(), stderr = "", exitCode = 0)
                command.contains("status --porcelain=v2 --branch") ->
                    ExecResult(stdout = "# branch.head main\n", stderr = "", exitCode = 0)
                command.contains(" log -1 --no-color") ->
                    ExecResult(stdout = "abc1234 Initial", stderr = "", exitCode = 0)
                command.contains("remote get-url origin") ->
                    ExecResult(stdout = "", stderr = "no origin", exitCode = 2)
                command.contains("github status --json") ->
                    ExecResult(
                        stdout = """{"installed":false,"authenticated":false,"hint":"install gh"}""",
                        stderr = "",
                        exitCode = 0,
                    )
                command.contains("gh issue create") ->
                    awaitCancellation()
                else -> ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
        }
        val viewModel = newViewModel(session, createIssueTimeoutMs = 100L)
        viewModel.start(request())
        runCurrent()

        viewModel.createIssue(title = "Bug", body = "Body")
        runCurrent()
        assertEquals(CreateIssueUiState.Submitting, viewModel.createState.value)

        advanceTimeBy(100L)
        runCurrent()

        val failure = viewModel.createState.value as CreateIssueUiState.Failure
        assertTrue(failure.message.contains("timed out"))
    }

    private fun newViewModel(
        session: SshSession,
        probeTimeoutMs: Long = 1_000L,
        createIssueTimeoutMs: Long = 1_000L,
    ): GitHistoryViewModel =
        GitHistoryViewModel(
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { _: SshLeaseTarget -> Result.success(session) },
                idleTtlMillis = 0L,
                connectTimeoutContext = StandardTestDispatcher(scheduler),
                nowMillis = { scheduler.currentTime },
            ),
            ioDispatcher = StandardTestDispatcher(scheduler),
            bestEffortProbeTimeoutMs = probeTimeoutMs,
            createIssueTimeoutMs = createIssueTimeoutMs,
        )

    private fun request(): GitHistoryViewModel.Request =
        GitHistoryViewModel.Request(
            hostId = 1L,
            hostname = "example.test",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            dir = "/repo",
        )

    private fun commitLog(): String {
        val unit = '\u001F'
        val record = '\u001E'
        return "abc1234${unit}Alexey${unit}just now${unit}Initial commit$record"
    }

    private class RoutingSshSession(
        private val onExec: suspend (String) -> ExecResult,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult =
            onExec(command)

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() = Unit
    }
}
