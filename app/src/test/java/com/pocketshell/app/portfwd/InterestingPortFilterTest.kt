package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestingPortFilterTest {

    @Test
    fun `dev-server ports from 3000 through 10000 are visible by default`() {
        // #602 (maintainer dogfood v0.3.30): the default band is the dev-server
        // range. 4000/4001 are the maintainer's "meaningful app ports" that must
        // stay; 3000/5173/8080 are the other common dev servers.
        for (port in listOf(3_000, 4_000, 4_001, 5_173, 5_432, 8_080, 9_999, 10_000)) {
            assertTrue("$port should be visible", InterestingPortFilter.isVisibleByDefault(port))
        }
    }

    @Test
    fun `low system infra and high ports are hidden by default`() {
        // #602: the 222x family (docker/agent/test SSH proxies — 2222/2224/2226/
        // 2228/2229/2230/2240) is exactly the noise the maintainer saw dominating
        // the list. They are below 3000 and must be hidden by default.
        for (port in listOf(
            1, 22, 80, 443, 999, 1_000, 2_222, 2_224, 2_226, 2_228, 2_229,
            2_230, 2_240, 2_999, 10_001, 11_434, 49_152, 65_535,
        )) {
            assertTrue("$port should be noisy", InterestingPortFilter.isNoisy(port))
            assertFalse("$port should be hidden", InterestingPortFilter.isVisibleByDefault(port))
        }
    }

    @Test
    fun `default filter shows only the user useful 3000 through 10000 range`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(22, "sshd"),
                RemotePort(80, "nginx"),
                RemotePort(2222, "docker-proxy"),
                RemotePort(3000, "node"),
                RemotePort(10_000, "app"),
                RemotePort(11_434, "ollama"),
                RemotePort(49_152, "app"),
            ),
        )
        assertEquals(listOf(3000, 10_000), filtered.map { it.port })
    }

    @Test
    fun `show-all filter reveals ports outside the useful range`() {
        val ports = listOf(
            RemotePort(22, "sshd"),
            RemotePort(80, "nginx"),
            RemotePort(3000, "node"),
            RemotePort(10_000, "app"),
            RemotePort(11_434, "ollama"),
            RemotePort(49_152, "app"),
        )
        val all = InterestingPortFilter.filter(ports, showAll = true)
        assertEquals(listOf(3000, 10_000, 22, 80, 11_434, 49_152), all.map { it.port })
    }

    @Test
    fun `show-all filter surfaces useful ports before hidden ports`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(11_434, "ollama"),
                RemotePort(49_152, "app"),
                RemotePort(3000, "node"),
                RemotePort(8080, "vite"),
            ),
            showAll = true,
        )
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
    fun `show-all de-duplicates hidden noisy ports too`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(11_434, ""),
                RemotePort(11_434, "app"),
            ),
            showAll = true,
        )
        assertEquals(1, filtered.size)
        assertEquals(11_434, filtered.single().port)
        assertEquals("app", filtered.single().processName)
    }

    @Test
    fun `count reflects the de-duplicated default-visible ports by default`() {
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
        // Outside-range ports hidden, duplicate 3000 collapsed.
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
        // De-duplicated: 22, 80, 3000, 8080, 49152. Hidden: 22, 80, 49152 = 3.
        assertEquals(3, InterestingPortFilter.hiddenCount(ports))
    }

    @Test
    fun `hiddenCount is zero when every port is default-visible`() {
        val ports = listOf(
            RemotePort(3_000, "node"),
            RemotePort(4_000, "app"),
            RemotePort(9_999, "x"),
            RemotePort(10_000, "y"),
        )
        assertEquals(0, InterestingPortFilter.hiddenCount(ports))
    }

    @Test
    fun `realistic noisy host hides the 222x infra ports and keeps dev servers`() {
        // Mirrors the maintainer's exact v0.3.30 dogfood screenshot
        // (issue-602-portfwd-clutter.png): the 222x / 2240 docker/agent/test SSH
        // proxies dominate the list and are pure noise. After #602 they are
        // hidden by default; only the meaningful app ports (4000/4001) plus other
        // dev servers in the 3000..10000 band stay visible.
        val noisy = listOf(
            RemotePort(22, "sshd"),
            RemotePort(22, "sshd"),
            RemotePort(53, "systemd-resolve"),
            RemotePort(80, "nginx"),
            RemotePort(2222, "docker-proxy"),
            RemotePort(2224, "docker-proxy"),
            RemotePort(2226, "docker-proxy"),
            RemotePort(2228, "docker-proxy"),
            RemotePort(2229, "docker-proxy"),
            RemotePort(2230, "docker-proxy"),
            RemotePort(2240, "docker-proxy"),
            RemotePort(3000, "node"),
            RemotePort(4000, "app"),
            RemotePort(4001, "app"),
            RemotePort(8080, "vite"),
            RemotePort(10_000, "dev"),
            RemotePort(11_434, "ollama"),
            RemotePort(49_152, "app"),
        )
        val filtered = InterestingPortFilter.filter(noisy)
        // The 222x/2240 noise is gone; only the dev-server band survives.
        assertEquals(
            listOf(3000, 4000, 4001, 8080, 10_000),
            filtered.map { it.port },
        )
        assertEquals(5, InterestingPortFilter.count(noisy))
        // De-duplicated hidden ports: 22, 53, 80, 2222, 2224, 2226, 2228, 2229,
        // 2230, 2240, 11434, 49152 = 12.
        assertEquals(12, InterestingPortFilter.hiddenCount(noisy))
    }
}
