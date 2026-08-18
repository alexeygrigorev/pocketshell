package com.pocketshell.app.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFaultPortsTest {

    @Test
    fun singleLaneKeepsHistoricalFaultIdentity() {
        assertEquals(2228, NetworkFaultPorts.faultSshPort(2222))
        assertEquals(2229, NetworkFaultPorts.packetLossSshPort(2222))
        assertEquals(8474, NetworkFaultPorts.toxiproxyApiPort(2222))
        assertFalse(NetworkFaultPorts.isPinnedToSharedFixture(2222))
    }

    @Test
    fun poolLaneMustNotResolveToSharedFaultPorts() {
        val agentsPort = 2243
        val fault = NetworkFaultPorts.faultSshPort(agentsPort)
        val loss = NetworkFaultPorts.packetLossSshPort(agentsPort)
        val api = NetworkFaultPorts.toxiproxyApiPort(agentsPort)
        assertNotEquals(
            "a --pool lane on agentsPort=2243 must not attach through the shared 2228 fixture (issue #2128)",
            2228,
            fault,
        )
        assertNotEquals(2222, fault)
        assertNotEquals(2229, loss)
        assertNotEquals(2222, loss)
        assertNotEquals(8474, api)
        assertFalse(NetworkFaultPorts.isPinnedToSharedFixture(agentsPort, fault, api))
    }

    @Test
    fun twoPoolLanesGetDisjointFaultPorts() {
        val a = Triple(
            NetworkFaultPorts.faultSshPort(2243),
            NetworkFaultPorts.packetLossSshPort(2243),
            NetworkFaultPorts.toxiproxyApiPort(2243),
        )
        val b = Triple(
            NetworkFaultPorts.faultSshPort(2244),
            NetworkFaultPorts.packetLossSshPort(2244),
            NetworkFaultPorts.toxiproxyApiPort(2244),
        )
        assertNotEquals(a.first, b.first)
        assertNotEquals(a.second, b.second)
        assertNotEquals(a.third, b.third)
        setOf(a.first, a.second, a.third, b.first, b.second, b.third).forEach { port ->
            assertFalse(
                "pool-derived port $port landed on a shared-fixture port",
                port == 2222 || port == 2228 || port == 2229 || port == 8474,
            )
        }
    }

    @Test
    fun pinDetectorCatchesAPoolLaneForcedOnto2228() {
        // The mutation this detector exists to catch: a leftover `?: 2228`
        // (or `?: 8474`) result on a pool agents port.
        assertTrue(NetworkFaultPorts.isPinnedToSharedFixture(2243, 2228, 8495))
        assertTrue(NetworkFaultPorts.isPinnedToSharedFixture(2243, 2253, 8474))
        assertFalse(NetworkFaultPorts.isPinnedToSharedFixture(2243, 2253, 8495))
        NetworkFaultPorts.checkNotPinnedToSharedFixture(2243)
    }
}
