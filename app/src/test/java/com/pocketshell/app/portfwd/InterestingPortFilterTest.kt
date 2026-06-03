package com.pocketshell.app.portfwd

import com.pocketshell.core.portfwd.RemotePort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestingPortFilterTest {

    @Test
    fun `keeps interesting dev-server ports in the primary range`() {
        for (port in listOf(1000, 3000, 5432, 8080, 9999)) {
            assertTrue("$port should be interesting", InterestingPortFilter.isInteresting(port))
        }
    }

    @Test
    fun `keeps interesting ports in the 49xxx dynamic range`() {
        for (port in listOf(49000, 49152, 49999)) {
            assertTrue("$port should be interesting", InterestingPortFilter.isInteresting(port))
        }
    }

    @Test
    fun `ports outside the interesting bands are not interesting`() {
        for (port in listOf(22, 80, 999, 10_000, 11_434, 48_999, 50_000)) {
            assertFalse("$port should not be interesting", InterestingPortFilter.isInteresting(port))
        }
    }

    @Test
    fun `hides system noise ports`() {
        for (port in listOf(22, 53, 80)) {
            assertTrue("$port should be hidden", InterestingPortFilter.isHidden(port))
        }
        assertFalse(InterestingPortFilter.isHidden(3000))
    }

    @Test
    fun `filter drops the system noise ports`() {
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(22, "sshd"),
                RemotePort(53, "dnsmasq"),
                RemotePort(80, "nginx"),
                RemotePort(3000, "node"),
            ),
        )
        assertEquals(listOf(3000), filtered.map { it.port })
    }

    @Test
    fun `filter surfaces interesting ports before other ports`() {
        // 11434 (ollama) is outside the interesting bands but not hidden, so it
        // stays in the set, ranked below the interesting 3000/49152 ports.
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(11_434, "ollama"),
                RemotePort(49_152, "app"),
                RemotePort(3000, "node"),
            ),
        )
        assertEquals(listOf(3000, 49_152, 11_434), filtered.map { it.port })
    }

    @Test
    fun `filter de-duplicates a port bound on multiple address families`() {
        // ss reports 0.0.0.0:3000 and [::]:3000 as two rows; only one should
        // survive, preferring the entry carrying the process name.
        val filtered = InterestingPortFilter.filter(
            listOf(
                RemotePort(3000, ""),
                RemotePort(3000, "node"),
                RemotePort(3000, ""),
            ),
        )
        assertEquals(1, filtered.size)
        assertEquals(3000, filtered.single().port)
        assertEquals("node", filtered.single().processName)
    }

    @Test
    fun `count reflects the de-duplicated non-hidden ports`() {
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
        // 22/53/80 hidden, duplicate 3000 collapsed -> 3000, 8080, 49152 = 3.
        assertEquals(3, InterestingPortFilter.count(ports))
        assertEquals(
            listOf(3000, 8080, 49_152),
            InterestingPortFilter.filter(ports).map { it.port },
        )
    }

    @Test
    fun `realistic noisy host collapses to a short readable interesting list`() {
        // Mirrors the maintainer's report: ~system + docker/test 222x noise
        // plus a couple of real dev servers. The user-facing result is just
        // the dev servers, interesting-first.
        val noisy = listOf(
            RemotePort(22, "sshd"),
            RemotePort(22, "sshd"),
            RemotePort(53, "systemd-resolve"),
            RemotePort(80, "nginx"),
            RemotePort(2222, "docker-proxy"),
            RemotePort(2224, "docker-proxy"),
            RemotePort(2226, "docker-proxy"),
            RemotePort(2229, "docker-proxy"),
            RemotePort(2230, "docker-proxy"),
            RemotePort(2231, "docker-proxy"),
            RemotePort(3000, "node"),
            RemotePort(8080, "vite"),
        )
        val filtered = InterestingPortFilter.filter(noisy)
        // 22/53/80 dropped as system noise; the duplicate 22 collapsed. The
        // remaining ports (incl. the docker/test 222x family, which is still
        // inside the interesting 1000-9999 band) sort ascending. The big win
        // is dropping the unambiguous system noise so the list is short and
        // readable instead of an ~80-row dump.
        assertEquals(
            listOf(2222, 2224, 2226, 2229, 2230, 2231, 3000, 8080),
            filtered.map { it.port },
        )
        assertEquals(8, InterestingPortFilter.count(noisy))
    }
}
