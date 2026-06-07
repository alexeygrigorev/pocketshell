package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderListPortForwardingSummaryTest {

    @Test
    fun `active tunnel does not mark every discovered port as forwarding`() {
        val rows = FolderListViewModel.mergeForwardingPortRows(
            discoveredPorts = listOf(
                HostDiscoveredPort(remotePort = 3000, process = "node"),
                HostDiscoveredPort(remotePort = 8080, process = "python"),
                HostDiscoveredPort(remotePort = 9000, process = "postgres"),
            ),
            activeRemotePorts = setOf(3000),
        )

        assertEquals(listOf(3000, 8080, 9000), rows.map { it.remotePort })
        assertEquals(3, HostPortForwardingSummary(discoveredPorts = rows).discoveredCount)
        assertEquals(
            listOf(
                HostPortForwardingPortStatus.FORWARDING,
                HostPortForwardingPortStatus.DISCOVERED,
                HostPortForwardingPortStatus.DISCOVERED,
            ),
            rows.map { it.status },
        )
    }

    @Test
    fun `active ports missing from discovery are still surfaced as forwarding`() {
        val rows = FolderListViewModel.mergeForwardingPortRows(
            discoveredPorts = listOf(
                HostDiscoveredPort(remotePort = 8080, process = "python"),
                HostDiscoveredPort(remotePort = 9000, process = "postgres"),
            ),
            activeRemotePorts = setOf(3000),
        )

        assertEquals(listOf(3000, 8080, 9000), rows.map { it.remotePort })
        assertEquals(2, HostPortForwardingSummary(discoveredPorts = rows).discoveredCount)
        assertEquals(HostPortForwardingPortStatus.FORWARDING, rows[0].status)
        assertEquals("", rows[0].process)
        assertEquals(false, rows[0].discovered)
        assertEquals(
            listOf(
                HostPortForwardingPortStatus.DISCOVERED,
                HostPortForwardingPortStatus.DISCOVERED,
            ),
            rows.drop(1).map { it.status },
        )
    }

    @Test
    fun `entry can be available while discovery is still loading or empty`() {
        val loading = HostPortForwardingSummary(
            entryAvailable = true,
            discoveryLoading = true,
        )
        assertEquals(true, loading.entryAvailable)
        assertEquals(true, loading.discoveryLoading)
        assertEquals(0, loading.discoveredCount)

        val empty = loading.copy(discoveryLoading = false)
        assertEquals(true, empty.entryAvailable)
        assertEquals(false, empty.discoveryLoading)
        assertEquals(0, empty.discoveredCount)
    }
}
