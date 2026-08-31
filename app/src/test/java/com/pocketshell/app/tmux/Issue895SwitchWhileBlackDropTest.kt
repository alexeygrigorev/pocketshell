package com.pocketshell.app.tmux

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #895 (switch-while-black) regression test — the R1 trigger.
 *
 * Defect: a transport drop that lands while the VM is in the `Switching`
 * (Attaching) window was SWALLOWED by the
 * VM-private `Connected` early-return
 * in [TmuxSessionViewModel.handlePassiveClientDisconnect], so the inline
 * passive-disconnect handling never ran and no diagnostic was recorded — the
 * user was left frozen on a black pane with no escapable state.
 *
 * The fix makes the passive-disconnect handling STATUS-AGNOSTIC: a drop during
 * `Switching`/`Reattaching`/`Reconnecting` drives the SAME controller-fed ladder
 * a `Connected`-state drop does, so an escapable Reconnecting band always
 * surfaces and the drop is always recorded.
 *
 * Class coverage (G2): both the `Connected` baseline and the `Switching` repro
 * are pinned, and both states surface an escapable band — proving the fix is not
 * a single-state special-case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue895SwitchWhileBlackDropTest {

    private val scheduler = TestCoroutineScheduler()
    private val testMainDispatcher = UnconfinedTestDispatcher(scheduler)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testMainDispatcher)

    private val teardown = mainDispatcherRule.tmuxSessionViewModelTeardown()
    private val factoryScope =
        teardown.trackRoot(
            "factoryScope",
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)),
        )
    private val leaseScope =
        teardown.trackRoot(
            "leaseScope",
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)),
        )

    private fun newVm(registry: ActiveTmuxClients): TmuxSessionViewModel =
        teardown.track(
            TmuxSessionViewModel(
                tmuxClientFactory = com.pocketshell.core.tmux.TmuxClientFactory(
                    factoryScope,
                ),
                activeTmuxClients = registry,
                hostDao = null,
                folderListGateway = null,
                runtimeCache = TmuxSessionRuntimeCache(),
                agentSessionMemory = AgentSessionMemory(),
                sshLeaseManager = SshLeaseManager(
                    connector = SshLeaseConnector { target ->
                        error("unexpected SSH lease connect for ${target.leaseKey}")
                    },
                    scope = leaseScope,
                    idleTtlMillis = 0L,
                    connectTimeoutContext = StandardTestDispatcher(scheduler),
                    abortTimeoutContext = StandardTestDispatcher(scheduler),
                    nowMillis = { scheduler.currentTime },
                ),
                sessionLifecycleSignals = null,
                // Issue #1168: the default real-IO `tailScope` is safe here — these
                // tests only connect/drop SHELL sessions and never start an agent
                // follow, so `tailScope` never launches a drain and there is no
                // `invokeOnCompletion -> bridgeScope.launch` Main read to leak past
                // teardown. (No agent pane is bound/followed anywhere in this class.)
                agentRepository = com.pocketshell.app.session.AgentConversationRepository(),
            ),
        ).also {
            it.setReconcileDispatcherForTest(testMainDispatcher)
            it.setReconcileApplyDispatcherForTest(testMainDispatcher)
            // Issue #926: pin the seed-IO dispatcher (off-Main hop for the
            // attach/switch/reattach `capture-pane`/`list-panes` IO) to the
            // virtual-clock test Main so the round-trips run inline on the test
            // scheduler — a real `Dispatchers.IO` default would leak a thread the
            // `runTest` virtual clock cannot advance. Production defaults to
            // `Dispatchers.IO` (off the UI thread, so the seed never freezes it).
            it.setSeedIoDispatcherForTest(testMainDispatcher)
            it.setPortDetectionDispatcherForTest(testMainDispatcher)
        }

    private fun connectVm(vm: TmuxSessionViewModel, client: FakeTmuxClient) {
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
    }

    private fun TmuxSessionViewModel.isEscapableBand(): Boolean {
        val status = connectionStatus.value
        return status is TmuxSessionViewModel.ConnectionStatus.Failed ||
            status is TmuxSessionViewModel.ConnectionStatus.Reconnecting
    }

    /**
     * BASELINE (control): a passive drop while CONNECTED surfaces an escapable
     * band (Reconnecting or Failed). Proves the harness drives the drop and that
     * the only difference between this and the repro is the connection state.
     */
    @Test
    fun connectedDrop_surfacesEscapableBand() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient()
        connectVm(vm, client)
        runCurrent()
        assertTrue(
            "precondition: VM is Connected",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        client.markDisconnectedForTest(
            com.pocketshell.core.tmux.TmuxDisconnectEvent(
                reason = com.pocketshell.core.tmux.TmuxDisconnectReason.ReaderEof,
                source = "eof",
                intent = "unknown",
            ),
        )

        assertTrue("clean-drop fixture must latch the selected client disconnected", client.disconnected.value)
        assertTrue(
            "clean-drop fixture must make the wire oracle unavailable immediately",
            vm.liveTmuxClientForSendOrNullForTest() == null,
        )
        advanceUntilIdle()

        assertTrue(
            "BASELINE: a Connected-state drop MUST surface an escapable band " +
                "(Failed/Reconnecting); got ${vm.connectionStatus.value}",
            vm.isEscapableBand(),
        )
    }

    /**
     * REGRESSION (#895 R1): a passive drop while SWITCHING (Attaching) must NOT
     * be swallowed — it must surface an escapable band exactly like the Connected
     * baseline.
     *
     * Fails RED on base (the `as? Connected ?: return` gate at the top of
     * [TmuxSessionViewModel.handlePassiveClientDisconnect] early-returns: the
     * status stays Switching, no escapable band). Passes GREEN with the gate
     * removed (the drop drives the same recovery the Connected baseline does).
     */
    @Test
    fun switchingDrop_surfacesEscapableBand() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient()
        connectVm(vm, client)
        runCurrent()

        // Enter the Switching window the way a same-host fast switch does
        // (Attaching before the Live flip). The controller now projects Switching.
        vm.forceControllerAttachingForTest()
        runCurrent()
        assertTrue(
            "precondition: VM is in the Switching window",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Switching,
        )

        // Transport drops mid-switch (the black/wedged-channel EOF case).
        client.markDisconnectedForTest(
            com.pocketshell.core.tmux.TmuxDisconnectEvent(
                reason = com.pocketshell.core.tmux.TmuxDisconnectReason.ReaderEof,
                source = "eof",
                intent = "unknown",
            ),
        )
        advanceUntilIdle()

        // Load-bearing assertion: the Switching-window drop SURFACES an escapable
        // band, matching the Connected baseline — the user is never left stuck on
        // Switching with nothing tappable.
        assertTrue(
            "#895 R1: a drop during Switching MUST surface an escapable band " +
                "(Failed/Reconnecting), not be swallowed; got " +
                "${vm.connectionStatus.value}",
            vm.isEscapableBand(),
        )
    }

    @Test
    fun switchingDisconnectedSignal_surfacesEscapableBand_viaControllerDriver() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient()
        connectVm(vm, client)
        runCurrent()

        vm.forceControllerAttachingForTest()
        runCurrent()
        assertTrue(
            "precondition: VM is in the Switching window",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Switching,
        )

        client.disconnectedSignal.value = true
        advanceUntilIdle()

        assertTrue(
            "#895/#766: the real disconnected signal must flow through the " +
                "driver-owned TransportDropped path and surface an escapable band; got " +
                "${vm.connectionStatus.value}",
            vm.isEscapableBand(),
        )
    }
}
