package com.pocketshell.next.ports

import com.pocketshell.core.portfwd.TunnelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default-visible band and its ordering. Ported from the old client's
 * `InterestingPortFilterTest` and extended for the tunnel-row shape app2 uses.
 */
class InterestingPortFilterTest {

    @Test
    fun `the default band is 3000 to 10000 inclusive`() {
        assertTrue(InterestingPortFilter.isVisibleByDefault(3_000))
        assertTrue(InterestingPortFilter.isVisibleByDefault(8_080))
        assertTrue(InterestingPortFilter.isVisibleByDefault(10_000))
        // The maintainer's v0.3.30 complaint: docker/agent SSH proxies in the
        // 222x family drowned the useful rows.
        assertTrue(InterestingPortFilter.isNoisy(2_222))
        assertTrue(InterestingPortFilter.isNoisy(22))
        assertTrue(InterestingPortFilter.isNoisy(10_001))
        assertFalse(InterestingPortFilter.isVisibleByDefault(2_999))
    }

    @Test
    fun `the default filter drops noisy rows and sorts by remote port`() {
        val rows = listOf(tunnel(8_080), tunnel(22), tunnel(3_000), tunnel(45_000))

        val visible = InterestingPortFilter.filter(rows, showAll = false)

        assertEquals(listOf(3_000, 8_080), visible.map { it.remotePort })
    }

    @Test
    fun `show-all keeps noisy rows but sorts them after the useful ones`() {
        val rows = listOf(tunnel(45_000), tunnel(8_080), tunnel(22), tunnel(3_000))

        val visible = InterestingPortFilter.filter(rows, showAll = true)

        assertEquals(listOf(3_000, 8_080, 22, 45_000), visible.map { it.remotePort })
    }

    @Test
    fun `a useful remote port on an ephemeral local port counts as noisy`() {
        // A forward is a row about BOTH ends: remote 3000 mirrored onto local
        // 48213 is not the `localhost:3000` the user is looking for.
        val mirrored = tunnel(remotePort = 3_000, localPort = 3_000)
        val relocated = tunnel(remotePort = 3_000, localPort = 48_213)

        assertTrue(InterestingPortFilter.isVisibleByDefault(mirrored))
        assertFalse(InterestingPortFilter.isVisibleByDefault(relocated))
        assertEquals(
            listOf(mirrored),
            InterestingPortFilter.filter(listOf(mirrored, relocated), showAll = false),
        )
    }

    @Test
    fun `hiddenCount reports exactly what the default filter removed`() {
        val rows = listOf(tunnel(22), tunnel(2_222), tunnel(3_000), tunnel(8_080))

        assertEquals(2, InterestingPortFilter.hiddenCount(rows))
        assertEquals(0, InterestingPortFilter.hiddenCount(listOf(tunnel(3_000))))
        assertEquals(0, InterestingPortFilter.hiddenCount(emptyList()))
    }

    private fun tunnel(remotePort: Int, localPort: Int = remotePort) = TunnelInfo(
        remotePort = remotePort,
        localPort = localPort,
        process = "app",
        status = TunnelInfo.Status.FORWARDING,
    )
}
