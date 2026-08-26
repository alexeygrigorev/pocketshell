package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Issue2294ExactSessionGenerationTest : TmuxSessionViewModelTestBase() {
    @Test
    fun livePaneReconcileCompletesExactGenerationForColdRestore() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        vm.latestConnectIntent = ConnectIntent(
            target = checkNotNull(vm.activeTarget),
            trigger = TmuxConnectTrigger.ColdRestore,
            generation = 1L,
        )

        val sessionCreated = 1_700_000_003L
        client.responses += CommandResponse(
            number = 0L,
            output = listOf(
                exactGenerationPaneRow(
                    paneId = "%0",
                    sessionId = "\$7",
                    sessionName = "work",
                    sessionCreated = sessionCreated,
                ),
            ),
            isError = false,
        )
        assertTrue(vm.reconcilePanesForTest() is PaneReconcileResult.Ready)

        assertEquals(
            "the production reconcile must publish panes after promoting the exact generation",
            listOf("%0"),
            vm.panes.value.map { it.paneId },
        )
        assertEquals("\$7", vm.activeTarget?.tmuxSessionId)
        assertEquals(sessionCreated, vm.activeTarget?.sessionCreated)
        assertEquals("\$7", vm.latestRestoreIntentSnapshot()?.tmuxSessionId)
        assertEquals(sessionCreated, vm.latestRestoreIntentSnapshot()?.sessionCreated)
    }

    @Test
    fun latePaneReconcileFromDifferentHostCannotAdoptGeneration() = runTest {
        val vm = newVm()
        val staleClient = FakeTmuxClient()
        val currentClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "old host",
            host = "old.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = staleClient,
        )
        staleClient.responses += CommandResponse(
            number = 0L,
            output = listOf(
                exactGenerationPaneRow(
                    paneId = "%stale",
                    sessionId = "\$7",
                    sessionName = "work",
                    sessionCreated = 1_700_000_007L,
                ),
            ),
            isError = false,
        )
        var replaced = false
        staleClient.onCommandSent = { command ->
            if (!replaced && command.startsWith("list-panes")) {
                replaced = true
                vm.replaceClientForTest(
                    hostId = 2L,
                    hostName = "current host",
                    host = "current.example",
                    port = 22,
                    user = "alex",
                    keyPath = "/keys/a",
                    sessionName = "work",
                    client = currentClient,
                )
            }
        }

        val result = vm.reconcilePanesForTest()

        assertTrue("a stale client result must be dropped", result is PaneReconcileResult.NoClient)
        assertNull("a same-name row from another host must not be adopted", vm.activeTarget?.tmuxSessionId)
        assertTrue("a stale host result must not populate the current panes", vm.panes.value.isEmpty())
    }

    @Test
    fun latePaneReconcileAfterCrossSessionSwitchCannotAdoptGeneration() = runTest {
        val vm = newVm()
        val firstClient = FakeTmuxClient()
        val interveningClient = FakeTmuxClient()
        val currentClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = firstClient,
        )
        firstClient.responses += CommandResponse(
            number = 0L,
            output = listOf(
                exactGenerationPaneRow(
                    paneId = "%stale",
                    sessionId = "\$7",
                    sessionName = "work",
                    sessionCreated = 1_700_000_007L,
                ),
            ),
            isError = false,
        )
        var switched = false
        firstClient.onCommandSent = { command ->
            if (!switched && command.startsWith("list-panes")) {
                switched = true
                vm.replaceClientForTest(
                    hostId = 42L,
                    hostName = "docker",
                    host = "10.0.2.2",
                    port = 2222,
                    user = "alex",
                    keyPath = "/keys/a",
                    sessionName = "other",
                    client = interveningClient,
                )
                // Return to the original name before the old list result
                // lands. Name equality alone must not make this old runtime
                // authoritative for the new attach.
                vm.replaceClientForTest(
                    hostId = 42L,
                    hostName = "docker",
                    host = "10.0.2.2",
                    port = 2222,
                    user = "alex",
                    keyPath = "/keys/a",
                    sessionName = "work",
                    client = currentClient,
                )
            }
        }

        val result = vm.reconcilePanesForTest()

        assertTrue("a stale session runtime result must be dropped", result is PaneReconcileResult.NoClient)
        assertNull("a row from the superseded runtime must not be adopted", vm.activeTarget?.tmuxSessionId)
        assertTrue("a stale session result must not populate the current panes", vm.panes.value.isEmpty())
    }

    @Test
    fun ambiguousPaneGenerationsFailClosed() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.responses += CommandResponse(
            number = 0L,
            output = listOf(
                exactGenerationPaneRow("%0", "\$7", "work", 1_700_000_007L),
                exactGenerationPaneRow("%1", "\$8", "work", 1_700_000_008L),
            ),
            isError = false,
        )

        assertTrue(vm.reconcilePanesForTest() is PaneReconcileResult.Ready)
        assertNull("conflicting complete rows are ambiguous", vm.activeTarget?.tmuxSessionId)
        assertEquals(2, vm.panes.value.size)
    }

    @Test
    fun incompletePaneGenerationPreventsAdoptingAnotherCompleteRow() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.responses += CommandResponse(
            number = 0L,
            output = listOf(
                exactGenerationPaneRow("%0", "\$7", "work", 1_700_000_007L),
                exactGenerationPaneRow("%1", "\$8", "work", null),
            ),
            isError = false,
        )

        assertTrue(vm.reconcilePanesForTest() is PaneReconcileResult.Ready)
        assertNull("an incomplete row must block generation adoption", vm.activeTarget?.tmuxSessionId)
        assertNull("an incomplete row must block session-created adoption", vm.activeTarget?.sessionCreated)
        assertEquals(2, vm.panes.value.size)
    }

    private fun exactGenerationPaneRow(
        paneId: String,
        sessionId: String,
        sessionName: String,
        sessionCreated: Long?,
    ): String = listOf(
        paneId,
        "@0",
        "0",
        sessionId,
        sessionName,
        "shell",
        "0",
        "/tmp",
        "bash",
        "/dev/pts/1",
        "0",
        "123",
        "0",
        sessionCreated?.toString().orEmpty(),
        "",
    ).joinToString(LIST_PANES_FIELD_SEPARATOR)
}
