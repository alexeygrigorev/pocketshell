package com.pocketshell.core.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the id-tagged [ConnectionController.seeds] Flow output: only seeds for
 * the CURRENT target reach the flow; a seed for a superseded/non-current target
 * is dropped at the source (the #686 drop-by-id contract, enforced once in the
 * connection core instead of by a racing generation counter in the view).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerSeedsFlowTest {

    private val host = HostKey("alice@host:22")
    private val a = SessionId("A")
    private val b = SessionId("B")

    @Test
    fun `seeds flow emits only current-target seeds`() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransportPort()
        transport.setWarm(host, true)
        val controller = ConnectionController(FakeClock(), transport)

        val collected = mutableListOf<Seed>()
        val job = launch { controller.seeds.toList(collected) }

        // Become Live on A.
        controller.submit(ConnectionEvent.Enter(host, a))
        controller.submit(ConnectionEvent.SeedLanded(a, "%0"))

        // A seed for the current target A is emitted.
        controller.offerSeed(Seed(a, "%0", "frame-A0"))
        // A late seed for a superseded target B is dropped (never on the flow).
        controller.offerSeed(Seed(b, "%0", "frame-B0"))
        // Another active-pane seed for A still flows.
        controller.offerSeed(Seed(a, "%1", "frame-A1"))

        job.cancel()

        assertEquals(
            listOf(
                Seed(a, "%0", "frame-A0"),
                Seed(a, "%1", "frame-A1"),
            ),
            collected,
        )
    }

    @Test
    fun `after a switch only the new target's seeds flow`() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransportPort()
        transport.setWarm(host, true)
        val controller = ConnectionController(FakeClock(), transport)

        val collected = mutableListOf<Seed>()
        val job = launch { controller.seeds.toList(collected) }

        controller.submit(ConnectionEvent.Enter(host, a))
        controller.submit(ConnectionEvent.SeedLanded(a, "%0"))
        controller.offerSeed(Seed(a, "%0", "frame-A0"))

        // Switch to B; an in-flight A seed must now be dropped.
        controller.submit(ConnectionEvent.Switch(b))
        controller.offerSeed(Seed(a, "%0", "late-A")) // dropped — A is superseded
        controller.submit(ConnectionEvent.SeedLanded(b, "%0"))
        controller.offerSeed(Seed(b, "%0", "frame-B0"))

        job.cancel()

        assertEquals(
            listOf(
                Seed(a, "%0", "frame-A0"),
                Seed(b, "%0", "frame-B0"),
            ),
            collected,
        )
    }
}
