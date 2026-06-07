package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestingPortFilterTest {

    @Test
    fun `high app ports are in the default range`() {
        for (port in listOf(10_000, 11_434, 49_152, 49_999, 65_535)) {
            assertTrue("$port should be in range", InterestingPortFilter.isInRange(port))
        }
    }

    @Test
    fun `low ports below 10000 are out of the default range`() {
        for (port in listOf(22, 53, 80, 443, 999, 1000, 3000, 5432, 8080, 9999)) {
            assertFalse("$port should be out of range", InterestingPortFilter.isInRange(port))
        }
    }

    @Test
    fun `default filter keeps only high in-range ports`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(22, "sshd"),
                RemotePort(80, "nginx"),
                RemotePort(3000, "node"),
                RemotePort(11_434, "ollama"),
                RemotePort(49_152, "app"),
            ),
        )
        assertEquals(listOf(11_434, 49_152), filtered.map { it.port })
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
        // In-range ports sort first, then low hidden ports ascending.
        assertEquals(listOf(11_434, 49_152, 22, 80, 3000), all.map { it.port })
    }

    @Test
    fun `default filter surfaces high in-range ports before low ports when show-all`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(11_434, "ollama"),
                RemotePort(49_152, "app"),
                RemotePort(3000, "node"),
                RemotePort(8080, "vite"),
            ),
            showAll = true,
        )
        assertEquals(listOf(11_434, 49_152, 3000, 8080), filtered.map { it.port })
    }

    @Test
    fun `filter de-duplicates a port bound on multiple address families`() {
        // ss reports 0.0.0.0:11434 and [::]:11434 as two rows; only one should
        // survive, preferring the entry carrying the process name. Dedupe
        // applies in both modes.
        val ports = listOf(
            RemotePort(11_434, ""),
            RemotePort(11_434, "ollama"),
            RemotePort(11_434, ""),
        )
        val filtered = InterestingPortFilter.filter(ports)
        assertEquals(1, filtered.size)
        assertEquals(11_434, filtered.single().port)
        assertEquals("ollama", filtered.single().processName)

        val showAll = InterestingPortFilter.filter(ports, showAll = true)
        assertEquals(1, showAll.size)
        assertEquals("ollama", showAll.single().processName)
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
        // Low ports hidden, duplicate 3000 collapsed -> only 49152 visible.
        assertEquals(1, InterestingPortFilter.count(ports))
        assertEquals(
            listOf(49_152),
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
        // De-duplicated: 22, 80, 3000, 8080, 49152. In-range: 49152.
        // Hidden: 22, 80, 3000, 8080 = 4.
        assertEquals(4, InterestingPortFilter.hiddenCount(ports))
    }

    @Test
    fun `hiddenCount is zero when every port is in range`() {
        val ports = listOf(
            RemotePort(10_001, "x"),
            RemotePort(10_000, "y"),
            RemotePort(49_152, "app"),
        )
        assertEquals(0, InterestingPortFilter.hiddenCount(ports))
    }

    @Test
    fun `realistic noisy host collapses to a short readable in-range list by default`() {
        // Mirrors the maintainer's report: system + docker/test 222x noise
        // plus low local dev servers, plus high app ports. The default
        // user-facing result hides everything below 10000.
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
            RemotePort(11_434, "ollama"),
            RemotePort(49_152, "app"),
        )
        val filtered = InterestingPortFilter.filter(noisy)
        assertEquals(
            listOf(11_434, 49_152),
            filtered.map { it.port },
        )
        assertEquals(2, InterestingPortFilter.count(noisy))
        // 22, 53, 80, 2222, 2224, 2226, 3000, 8080 are the de-duplicated hidden ports.
        assertEquals(8, InterestingPortFilter.hiddenCount(noisy))
    }
}
