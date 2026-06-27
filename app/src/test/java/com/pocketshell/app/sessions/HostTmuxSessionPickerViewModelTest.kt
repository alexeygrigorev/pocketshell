package com.pocketshell.app.sessions

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("pocketshell/tmux is not available on this host.", state.message)
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

    @Test
    fun loadTimeoutProducesRetryableFallbackInsteadOfStayingLoading() = runTest {
        val gate = CompletableDeferred<HostTmuxSessionListResult>()
        val vm = HostTmuxSessionPickerViewModel(SuspendingGateway(gate))

        vm.load(request())
        runCurrent()
        assertTrue(vm.state.value is HostTmuxSessionPickerState.Loading)

        advanceTimeBy(HostTmuxSessionPickerViewModel.LOAD_TIMEOUT_MS)
        runCurrent()

        val state = vm.state.value as HostTmuxSessionPickerState.Fallback
        assertEquals("Timed out while loading tmux sessions. Please retry.", state.message)
    }

    @Test
    fun projectSwitcherFiltersWarmSiblingsToSameProjectPath() = runTest {
        // Issue #463: the project switcher must scope to the current
        // session's project path and sort by recent activity.
        val vm = HostTmuxSessionPickerViewModel(
            FakeGateway(
                result = HostTmuxSessionListResult.Sessions(emptyList()),
                liveResult = HostTmuxSessionListResult.Sessions(
                    listOf(
                        HostTmuxSessionRow(name = "a", lastActivity = 10L, path = "/proj"),
                        HostTmuxSessionRow(name = "b", lastActivity = 30L, path = "/proj"),
                        HostTmuxSessionRow(name = "other", lastActivity = 99L, path = "/elsewhere"),
                        HostTmuxSessionRow(name = "c", lastActivity = 20L, path = "/proj/"),
                    ),
                ),
            ),
        )
        val req = request()

        vm.refreshProjectSiblings(req.host, req.keyPath, currentSessionName = "a", projectPath = "/proj")
        runCurrent()

        val switcher = vm.projectSwitcher.value
        // Sorted by activity desc: b(30), c(20), a(10). 'other' excluded.
        assertEquals(listOf("b", "c", "a"), switcher.siblings.map { it.name })
        assertEquals("a", switcher.currentSessionName)
        assertTrue(switcher.hasSiblingsToSwitch)
    }

    @Test
    fun projectSwitcherSingleSessionHasNothingToSwitchTo() = runTest {
        val vm = HostTmuxSessionPickerViewModel(
            FakeGateway(
                result = HostTmuxSessionListResult.Sessions(emptyList()),
                liveResult = HostTmuxSessionListResult.Sessions(
                    listOf(
                        HostTmuxSessionRow(name = "solo", lastActivity = 10L, path = "/proj"),
                        HostTmuxSessionRow(name = "elsewhere", lastActivity = 30L, path = "/other"),
                    ),
                ),
            ),
        )
        val req = request()

        vm.refreshProjectSiblings(req.host, req.keyPath, currentSessionName = "solo", projectPath = "/proj")
        runCurrent()

        val switcher = vm.projectSwitcher.value
        assertEquals(listOf("solo"), switcher.siblings.map { it.name })
        assertFalse(switcher.hasSiblingsToSwitch)
    }

    @Test
    fun projectSwitcherWithoutLiveClientKeepsLastKnownSiblings() = runTest {
        // No live client (null) → keep the last known list so the dropdown
        // still opens instantly instead of collapsing.
        val swapping = LiveSwappingGateway(
            first = HostTmuxSessionListResult.Sessions(
                listOf(
                    HostTmuxSessionRow(name = "a", lastActivity = 10L, path = "/proj"),
                    HostTmuxSessionRow(name = "b", lastActivity = 20L, path = "/proj"),
                ),
            ),
            second = null,
        )
        val vm = HostTmuxSessionPickerViewModel(swapping)
        val req = request()

        vm.refreshProjectSiblings(req.host, req.keyPath, "a", "/proj")
        runCurrent()
        assertEquals(listOf("b", "a"), vm.projectSwitcher.value.siblings.map { it.name })

        vm.refreshProjectSiblings(req.host, req.keyPath, "a", "/proj")
        runCurrent()
        // Siblings retained despite the null (no live client) result.
        assertEquals(listOf("b", "a"), vm.projectSwitcher.value.siblings.map { it.name })
    }

    @Test
    fun projectSwitcherFallsBackToCurrentSessionPathWhenProjectPathUnknown() = runTest {
        val vm = HostTmuxSessionPickerViewModel(
            FakeGateway(
                result = HostTmuxSessionListResult.Sessions(emptyList()),
                liveResult = HostTmuxSessionListResult.Sessions(
                    listOf(
                        HostTmuxSessionRow(name = "a", lastActivity = 10L, path = "/proj"),
                        HostTmuxSessionRow(name = "b", lastActivity = 20L, path = "/proj"),
                        HostTmuxSessionRow(name = "other", lastActivity = 99L, path = "/x"),
                    ),
                ),
            ),
        )
        val req = request()

        vm.refreshProjectSiblings(req.host, req.keyPath, currentSessionName = "a", projectPath = null)
        runCurrent()

        // projectPath null → uses the current session's reported path (/proj).
        assertEquals(listOf("b", "a"), vm.projectSwitcher.value.siblings.map { it.name })
    }

    private class LiveSwappingGateway(
        private val first: HostTmuxSessionListResult,
        private val second: HostTmuxSessionListResult?,
    ) : HostTmuxSessionsGateway {
        private var calls = 0
        override suspend fun listSessions(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): HostTmuxSessionListResult = HostTmuxSessionListResult.Sessions(emptyList())

        override suspend fun listSessionsFromLiveClient(
            host: HostEntity,
            keyPath: String,
        ): HostTmuxSessionListResult? {
            calls += 1
            return if (calls == 1) first else second
        }
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
        private val liveResult: HostTmuxSessionListResult? = null,
    ) : HostTmuxSessionsGateway {
        override suspend fun listSessions(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): HostTmuxSessionListResult = result

        override suspend fun listSessionsFromLiveClient(
            host: HostEntity,
            keyPath: String,
        ): HostTmuxSessionListResult? = liveResult
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

        override suspend fun listSessionsFromLiveClient(
            host: HostEntity,
            keyPath: String,
        ): HostTmuxSessionListResult? = null
    }

    private class SuspendingGateway(
        private val gate: CompletableDeferred<HostTmuxSessionListResult>,
    ) : HostTmuxSessionsGateway {
        override suspend fun listSessions(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): HostTmuxSessionListResult = gate.await()

        override suspend fun listSessionsFromLiveClient(
            host: HostEntity,
            keyPath: String,
        ): HostTmuxSessionListResult? = null
    }
}
