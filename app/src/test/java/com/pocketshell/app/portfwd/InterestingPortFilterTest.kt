package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestingPortFilterTest {

    @Test
    fun `useful dev-server ports are in the default range`() {
        for (port in listOf(1000, 3000, 5432, 8080, 9999, 10_000)) {
            assertTrue("$port should be in range", InterestingPortFilter.isInRange(port))
        }
    }

    @Test
    fun `system ports below 1000 are out of the default range`() {
        for (port in listOf(22, 53, 80, 443, 999)) {
            assertFalse("$port should be out of range", InterestingPortFilter.isInRange(port))
        }
    }

    @Test
    fun `high or ephemeral ports above 10000 are out of the default range`() {
        for (port in listOf(10_001, 11_434, 49_152, 49_999, 50_000)) {
            assertFalse("$port should be out of range", InterestingPortFilter.isInRange(port))
        }
    }

    @Test
    fun `default filter keeps only the in-range ports`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(22, "sshd"),
                RemotePort(80, "nginx"),
                RemotePort(3000, "node"),
                RemotePort(11_434, "ollama"),
                RemotePort(49_152, "app"),
            ),
        )
        // 22/80 are <1000; 11434/49152 are >10000 — all hidden by default.
        assertEquals(listOf(3000), filtered.map { it.port })
    }

    @Test
    fun `show-all filter reveals the out-of-range ports`() {
        val ports = listOf(
            RemotePort(22, "sshd"),
            RemotePort(80, "nginx"),
            RemotePort(3000, "node"),
            RemotePort(11_434, "ollama"),
            RemotePort(49_152, "app"),
        )
        val all = InterestingPortFilter.filter(ports, showAll = true)
        // In-range port sorts first, then the out-of-range ports ascending.
        assertEquals(listOf(3000, 22, 80, 11_434, 49_152), all.map { it.port })
    }

    @Test
    fun `default filter surfaces in-range ports before out-of-range when show-all`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(11_434, "ollama"),
                RemotePort(49_152, "app"),
                RemotePort(3000, "node"),
                RemotePort(8080, "vite"),
            ),
            showAll = true,
        )
        // 3000 and 8080 are in range -> first, ascending; 11434, 49152 after.
        assertEquals(listOf(3000, 8080, 11_434, 49_152), filtered.map { it.port })
    }

    @Test
    fun `filter de-duplicates a port bound on multiple address families`() {
        // ss reports 0.0.0.0:3000 and [::]:3000 as two rows; only one should
        // survive, preferring the entry carrying the process name. Dedupe
        // applies in both modes.
        val ports = listOf(
            RemotePort(3000, ""),
            RemotePort(3000, "node"),
            RemotePort(3000, ""),
        )
        val filtered = InterestingPortFilter.filter(ports)
        assertEquals(1, filtered.size)
        assertEquals(3000, filtered.single().port)
        assertEquals("node", filtered.single().processName)

        val showAll = InterestingPortFilter.filter(ports, showAll = true)
        assertEquals(1, showAll.size)
        assertEquals("node", showAll.single().processName)
    }

    @Test
    fun `show-all de-duplicates out-of-range ports too`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(49_152, ""),
                RemotePort(49_152, "app"),
            ),
            showAll = true,
        )
        assertEquals(1, filtered.size)
        assertEquals(49_152, filtered.single().port)
        assertEquals("app", filtered.single().processName)
    }

    @Test
    fun `count reflects the de-duplicated in-range ports by default`() {
        val ports = listOf(
            RemotePort(22, "sshd"),
            RemotePort(22, "sshd"),
            RemotePort(53, "dnsmasq"),
            RemotePort(80, "nginx"),
            RemotePort(3000, "node"),
            RemotePort(3000, ""),
            RemotePort(8080, "python"),
            RemotePort(49_152, "app"),
        )
        // <1000 (22/53/80) and >10000 (49152) hidden, duplicate 3000 collapsed
        // -> 3000, 8080 = 2.
        assertEquals(2, InterestingPortFilter.count(ports))
        assertEquals(
            listOf(3000, 8080),
            InterestingPortFilter.filter(ports).map { it.port },
        )
        // Show-all counts every de-duplicated port -> 22, 53, 80, 3000, 8080, 49152.
        assertEquals(6, InterestingPortFilter.count(ports, showAll = true))
    }

    @Test
    fun `hiddenCount reports how many de-duplicated ports show-all would reveal`() {
        val ports = listOf(
            RemotePort(22, "sshd"),
            RemotePort(22, "sshd"),
            RemotePort(80, "nginx"),
            RemotePort(3000, "node"),
            RemotePort(8080, "python"),
            RemotePort(49_152, "app"),
        )
        // De-duplicated: 22, 80, 3000, 8080, 49152. In-range: 3000, 8080.
        // Hidden: 22, 80, 49152 = 3.
        assertEquals(3, InterestingPortFilter.hiddenCount(ports))
    }

    @Test
    fun `hiddenCount is zero when every port is in range`() {
        val ports = listOf(
            RemotePort(3000, "node"),
            RemotePort(8080, "vite"),
            RemotePort(1000, "x"),
            RemotePort(10_000, "y"),
        )
        assertEquals(0, InterestingPortFilter.hiddenCount(ports))
    }

    @Test
    fun `realistic noisy host collapses to a short readable in-range list by default`() {
        // Mirrors the maintainer's report: system + docker/test 222x noise
        // plus a couple of real dev servers, plus a high ephemeral port. The
        // default user-facing result is just the in-range ports, ascending.
        val noisy = listOf(
            RemotePort(22, "sshd"),
            RemotePort(22, "sshd"),
            RemotePort(53, "systemd-resolve"),
            RemotePort(80, "nginx"),
            RemotePort(2222, "docker-proxy"),
            RemotePort(2224, "docker-proxy"),
            RemotePort(2226, "docker-proxy"),
            RemotePort(3000, "node"),
            RemotePort(8080, "vite"),
            RemotePort(49_152, "app"),
        )
        val filtered = InterestingPortFilter.filter(noisy)
        // <1000 (22/53/80) and >10000 (49152) dropped; duplicate 22 collapsed.
        // Remaining in-range ports sort ascending.
        assertEquals(
            listOf(2222, 2224, 2226, 3000, 8080),
            filtered.map { it.port },
        )
        assertEquals(5, InterestingPortFilter.count(noisy))
        // 22, 53, 80, 49152 are the de-duplicated hidden ports.
        assertEquals(4, InterestingPortFilter.hiddenCount(noisy))
    }
}
