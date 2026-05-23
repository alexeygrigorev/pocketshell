package com.pocketshell.app.sessions

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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
}
