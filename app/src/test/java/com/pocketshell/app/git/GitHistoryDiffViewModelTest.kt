package com.pocketshell.app.git

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Drives the in-app diff viewer state wiring on [GitHistoryViewModel] — issue
 * #1242. Tapping a commit runs `git show` over the SAME warm lease the log rode
 * (no new connection) and surfaces the parsed diff on `diffState`; a bad ref
 * surfaces a failure; dismiss returns to the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHistoryDiffViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher(scheduler))

    private val request = GitHistoryViewModel.Request(
        hostId = 1L,
        hostname = "lab.local",
        port = 22,
        username = "alexey",
        keyPath = "/tmp/key",
        passphrase = null,
        dir = "/repo",
    )

    @Test
    fun `loadDiff fetches and exposes the parsed diff over the warm lease`() = runTest(scheduler) {
        val dials = intArrayOf(0)
        val vm = viewModelOver(dials) { cmd ->
            when {
                "rev-parse --is-inside-work-tree" in cmd -> ExecResult("true\n", "", 0)
                "rev-parse --verify" in cmd -> ExecResult("a1b2c3d\n", "", 0)
                "show" in cmd -> ExecResult("@@ -1 +1 @@\n-old\n+new\n", "", 0)
                // log / status / gh probes during start(): degrade harmlessly.
                else -> ExecResult("", "", 0)
            }
        }
        vm.start(request)
        advanceUntilIdle()

        vm.loadDiff("a1b2c3d", "Add timeline view")
        advanceUntilIdle()

        val state = vm.diffState.value
        assertTrue("expected Ready but was $state", state is GitDiffUiState.Ready)
        val ready = state as GitDiffUiState.Ready
        assertEquals("Add timeline view", ready.subject)
        assertEquals("a1b2c3d", ready.diff.ref)
        assertTrue(ready.diff.lines.any { it.kind == DiffLineKind.Added })
        assertTrue(ready.diff.lines.any { it.kind == DiffLineKind.Removed })
        // D21 / #699: the whole screen + diff rode ONE warm transport.
        assertEquals("git diff must reuse the warm lease, not dial again", 1, dials[0])
    }

    @Test
    fun `loadDiff on an unknown ref surfaces a failure`() = runTest(scheduler) {
        val vm = viewModelOver(intArrayOf(0)) { cmd ->
            when {
                "rev-parse --is-inside-work-tree" in cmd -> ExecResult("true\n", "", 0)
                "rev-parse --verify" in cmd -> ExecResult("", "", 1)
                else -> ExecResult("", "", 0)
            }
        }
        vm.start(request)
        advanceUntilIdle()

        vm.loadDiff("deadbee", "gone")
        advanceUntilIdle()

        assertTrue(vm.diffState.value is GitDiffUiState.Failed)
    }

    @Test
    fun `dismissDiff returns to the hidden list state`() = runTest(scheduler) {
        val vm = viewModelOver(intArrayOf(0)) { cmd ->
            when {
                "rev-parse --is-inside-work-tree" in cmd -> ExecResult("true\n", "", 0)
                "rev-parse --verify" in cmd -> ExecResult("sha\n", "", 0)
                "show" in cmd -> ExecResult("+x\n", "", 0)
                else -> ExecResult("", "", 0)
            }
        }
        vm.start(request)
        advanceUntilIdle()
        vm.loadDiff("abc", "s")
        advanceUntilIdle()
        assertTrue(vm.diffState.value is GitDiffUiState.Ready)

        vm.dismissDiff()
        advanceUntilIdle()
        assertEquals(GitDiffUiState.Hidden, vm.diffState.value)
    }

    private fun viewModelOver(
        dials: IntArray,
        onExec: (String) -> ExecResult,
    ): GitHistoryViewModel {
        val session = scripted(onExec)
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { dials[0]++; Result.success(session) },
            connectTimeoutContext = StandardTestDispatcher(scheduler),
            nowMillis = { scheduler.currentTime },
        )
        return GitHistoryViewModel(
            sshLeaseManager = manager,
            ioDispatcher = StandardTestDispatcher(scheduler),
        )
    }

    private fun scripted(onExec: (String) -> ExecResult): SshSession = object : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = onExec(command)
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() = Unit
    }
}
