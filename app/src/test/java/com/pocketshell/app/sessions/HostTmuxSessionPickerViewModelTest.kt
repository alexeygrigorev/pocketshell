package com.pocketshell.app.sessions

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.ConnectException

@OptIn(ExperimentalCoroutinesApi::class)
class HostTmuxSessionPickerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loadSortsRowsByActivityDescending() = runTest {
        val gateway = FakeGateway(
            HostTmuxSessionListResult.Sessions(
                listOf(
                    HostTmuxSessionRow(name = "old", lastActivity = 10L),
                    HostTmuxSessionRow(name = "new", lastActivity = 30L),
                    HostTmuxSessionRow(name = "mid", lastActivity = 20L),
                ),
            ),
        )
        val vm = HostTmuxSessionPickerViewModel(gateway)

        vm.load(request())
        runCurrent()

        val state = vm.state.value as HostTmuxSessionPickerState.Ready
        assertEquals(listOf("new", "mid", "old"), state.rows.map { it.name })
    }

    @Test
    fun emptySessionListStaysReadySoUserCanCreateOrUseSsh() = runTest {
        val vm = HostTmuxSessionPickerViewModel(
            FakeGateway(HostTmuxSessionListResult.Sessions(emptyList())),
        )

        vm.load(request())
        runCurrent()

        val state = vm.state.value as HostTmuxSessionPickerState.Ready
        assertTrue(state.rows.isEmpty())
        assertEquals("No tmux sessions found.", state.message)
    }

    @Test
    fun unavailableToolProducesFallbackState() = runTest {
        val vm = HostTmuxSessionPickerViewModel(
            FakeGateway(HostTmuxSessionListResult.ToolUnavailable),
        )

        vm.load(request())
        runCurrent()

        val state = vm.state.value as HostTmuxSessionPickerState.Fallback
        assertEquals("tmuxctl/tmux is not available on this host.", state.message)
    }

    @Test
    fun failedCommandProducesFallbackStateWithMessage() = runTest {
        val vm = HostTmuxSessionPickerViewModel(
            FakeGateway(HostTmuxSessionListResult.Failed("boom")),
        )

        vm.load(request())
        runCurrent()

        val state = vm.state.value as HostTmuxSessionPickerState.Fallback
        assertEquals("boom", state.message)
    }

    /**
     * Issue #109: connect failures must surface as a `ConnectError`
     * state so the sheet can render the user-facing summary rather than
     * the raw exception text.
     */
    @Test
    fun connectFailureProducesConnectErrorWithSummary() = runTest {
        val cause = SshException(
            "SSH connect to testuser@127.0.0.1:22 failed: ConnectException: failed to connect",
            ConnectException("failed to connect to /127.0.0.1 (port 22): ECONNREFUSED (Connection refused)"),
        )
        val vm = HostTmuxSessionPickerViewModel(
            FakeGateway(HostTmuxSessionListResult.ConnectFailed(cause)),
        )

        vm.load(request())
        runCurrent()

        val state = vm.state.value as HostTmuxSessionPickerState.ConnectError
        assertEquals(HostConnectErrorReason.ConnectionRefused, state.summary.reason)
        assertEquals("Connection refused.", state.summary.shortReason)
        assertTrue(
            "details should preserve the underlying exception for bug reports, got '${state.summary.details}'",
            state.summary.details.contains("ECONNREFUSED"),
        )
    }

    /**
     * Issue #109: the Retry button re-runs the same `load(...)` with
     * the originating request. We model this by switching the gateway
     * fixture on the second call so the test observes the new state.
     */
    @Test
    fun retryReExecutesLoadWithSameRequest() = runTest {
        val gateway = SwappingGateway(
            initial = HostTmuxSessionListResult.ConnectFailed(
                SshException("first", ConnectException("ECONNREFUSED")),
            ),
            next = HostTmuxSessionListResult.Sessions(emptyList()),
        )
        val vm = HostTmuxSessionPickerViewModel(gateway)

        vm.load(request())
        runCurrent()
        assertTrue(vm.state.value is HostTmuxSessionPickerState.ConnectError)

        vm.retry()
        runCurrent()
        assertTrue(
            "retry should re-run the gateway and land on the new (success) state, got ${vm.state.value}",
            vm.state.value is HostTmuxSessionPickerState.Ready,
        )
        assertEquals(2, gateway.calls)
    }

    /**
     * Issue #109: Cancel during the connect attempt aborts the in-flight
     * gateway suspension and returns the sheet to Idle. The fake
     * gateway never completes so the only way the state can be Idle is
     * via cancellation.
     */
    @Test
    fun cancelDuringConnectingReturnsToIdle() = runTest {
        val gate = CompletableDeferred<HostTmuxSessionListResult>()
        val vm = HostTmuxSessionPickerViewModel(SuspendingGateway(gate))

        vm.load(request())
        runCurrent()
        assertTrue(vm.state.value is HostTmuxSessionPickerState.Loading)

        vm.cancelLoading()
        runCurrent()
        assertEquals(HostTmuxSessionPickerState.Idle, vm.state.value)
    }

    private fun request(): HostTmuxSessionPickerRequest =
        HostTmuxSessionPickerRequest(
            host = HostEntity(
                id = 1L,
                name = "docker",
                hostname = "127.0.0.1",
                port = 2222,
                username = "testuser",
                keyId = 1L,
            ),
            keyPath = "/tmp/key",
            passphrase = null,
        )

    private class FakeGateway(
        private val result: HostTmuxSessionListResult,
    ) : HostTmuxSessionsGateway {
        override suspend fun listSessions(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): HostTmuxSessionListResult = result
    }

    private class SwappingGateway(
        private val initial: HostTmuxSessionListResult,
        private val next: HostTmuxSessionListResult,
    ) : HostTmuxSessionsGateway {
        var calls: Int = 0
        override suspend fun listSessions(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): HostTmuxSessionListResult {
            calls += 1
            return if (calls == 1) initial else next
        }
    }

    private class SuspendingGateway(
        private val gate: CompletableDeferred<HostTmuxSessionListResult>,
    ) : HostTmuxSessionsGateway {
        override suspend fun listSessions(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): HostTmuxSessionListResult = gate.await()
    }
}
