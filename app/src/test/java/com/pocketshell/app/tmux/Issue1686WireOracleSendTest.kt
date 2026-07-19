package com.pocketshell.app.tmux

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1686 (Track C, Layer 2): the composer-queue clog because the send
 * admission gate trusted the `ConnectionStatus` ENUM instead of the transport.
 *
 * The maintainer's reported symptom: "the composer queue gets clogged because it
 * thinks the connection is not there." The #1680 reconnect storm produces a
 * constant FALSE / flapping `not-Connected` label while the underlying `-CC`
 * transport is perfectly writable. On base, `liveTmuxClientForSendOrNull` REFUSED
 * a live `clientRef` unless the enum said `Connected` — so a dispatched send
 * failed ("Session is disconnected") even though the wire would have accepted the
 * bytes, and the backlog never drained.
 *
 * The fix makes the WIRE the oracle: admission reads the transport's own
 * `disconnected` truth, not the enum. These are pure JVM ViewModel tests
 * (gate-wired in the `Unit tests` job).
 *
 * RED reproduction (reviewer): restore the enum gate in
 * `liveTmuxClientForSendOrNull` (`if (inlineConnectionStatus !is Connected) return
 * null`) and [admitsLiveWritableClientWhenEnumFalselyNotConnected] fails — the live
 * client is refused on the false `Reconnecting` label.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1686WireOracleSendTest : TmuxSessionViewModelTestBase() {

    @Test
    fun admitsLiveWritableClientWhenEnumFalselyNotConnected() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Model the #1680 false-disconnect: the enum flips to `Reconnecting` while
        // the `-CC` transport (clientRef) stays live + writable.
        vm.forceInlineReconnectingStatusKeepingClientForTest()

        assertNotNull(
            "the WIRE is the oracle: a live, writable clientRef must be admitted even " +
                "when the ConnectionStatus enum falsely reports not-Connected (#1686)",
            vm.liveTmuxClientForSendOrNullForTest(),
        )
        assertTrue(
            "isSendTransportWritable must report the transport truth, not the enum (#1686)",
            vm.isSendTransportWritable(),
        )
    }

    @Test
    fun refusesADisconnectedWireEvenWhenEnumWouldAllowIt() = runTest(scheduler) {
        // Class coverage (G2): the negative — a genuinely DEAD wire is never admitted,
        // even with a Connected-looking enum (attachClientForTest sets Live). Admission
        // follows the transport's own `disconnected` truth in BOTH directions.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        client.disconnectedSignal.value = true

        assertNull(
            "a disconnected clientRef must NOT be admitted for a send (#1686)",
            vm.liveTmuxClientForSendOrNullForTest(),
        )
        assertFalse(vm.isSendTransportWritable())
    }

    @Test
    fun refusesWhenThereIsNoClientAtAll() = runTest(scheduler) {
        // Missing-data case (G2): no clientRef ever attached ⇒ not writable ⇒
        // transport-unavailable (the composer taxonomy keeps such rows queued).
        val vm = newVm()
        assertNull(vm.liveTmuxClientForSendOrNullForTest())
        assertFalse(vm.isSendTransportWritable())
    }
}
