package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure [PortDetector] (issue #448, epic #432 slice
 * C): each regex pattern, the rolling-buffer boundary case, the
 * session-scoped de-dup of already-seen / dismissed / forwarded ports,
 * and the false-positive confirm contract.
 */
class PortDetectorTest {

    private fun ports(detector: PortDetector, chunk: String): List<Int> =
        detector.scan(chunk).map { it.port }

    // --- regex patterns ---

    @Test
    fun `matches Listening on host port`() {
        assertEquals(listOf(8000), ports(PortDetector(), "Listening on 0.0.0.0:8000\n"))
    }

    @Test
    fun `matches Listening on http url`() {
        assertEquals(
            listOf(5000),
            ports(PortDetector(), "Listening on http://127.0.0.1:5000/\n"),
        )
    }

    @Test
    fun `matches Listening on port N phrasing`() {
        assertEquals(listOf(3000), ports(PortDetector(), "Listening on port 3000\n"))
    }

    @Test
    fun `matches localhost url`() {
        assertEquals(
            listOf(8888),
            ports(PortDetector(), "Open http://localhost:8888 in your browser\n"),
        )
    }

    @Test
    fun `matches bare localhost host port`() {
        assertEquals(
            listOf(5173),
            ports(PortDetector(), "Open localhost:5173 in your browser\n"),
        )
    }

    @Test
    fun `matches bare loopback host port`() {
        assertEquals(
            listOf(3000),
            ports(PortDetector(), "Next.js ready on 127.0.0.1:3000\n"),
        )
    }

    @Test
    fun `matches bare any-address host port`() {
        assertEquals(
            listOf(9000),
            ports(PortDetector(), "Serving at 0.0.0.0:9000\n"),
        )
    }

    @Test
    fun `matches loopback url`() {
        assertEquals(listOf(5173), ports(PortDetector(), "  http://127.0.0.1:5173/\n"))
    }

    @Test
    fun `matches vite Local line`() {
        assertEquals(
            listOf(5173),
            ports(PortDetector(), "  Local:   http://localhost:5173/\n"),
        )
    }

    @Test
    fun `matches running on url`() {
        assertEquals(
            listOf(5000),
            ports(PortDetector(), "Running on http://0.0.0.0:5000 (Press CTRL+C)\n"),
        )
    }

    @Test
    fun `matches server running at`() {
        assertEquals(
            listOf(4000),
            ports(PortDetector(), "Server running at http://localhost:4000\n"),
        )
    }

    @Test
    fun `matches loopback host port phrase`() {
        assertEquals(
            listOf(3000),
            ports(PortDetector(), "Preview available on localhost port 3000.\n"),
        )
        assertEquals(
            listOf(5173),
            ports(PortDetector(), "127.0.0.1 is running on port 5173\n"),
        )
        assertEquals(
            listOf(8080),
            ports(PortDetector(), "0.0.0.0 bound to port 8080\n"),
        )
    }

    @Test
    fun `matches port phrase before loopback host`() {
        assertEquals(
            listOf(3000),
            ports(PortDetector(), "Port 3000 on localhost is ready.\n"),
        )
        assertEquals(
            listOf(5173),
            ports(PortDetector(), "port 5173 is ready at 127.0.0.1\n"),
        )
    }

    // --- false positives the regex tolerates (confirm scan is the guard) ---

    @Test
    fun `ignores bare port mention in prose`() {
        assertTrue(ports(PortDetector(), "the default port 8080 was changed\n").isEmpty())
    }

    @Test
    fun `ignores remote host url`() {
        assertTrue(ports(PortDetector(), "see https://example.com:8443/docs\n").isEmpty())
    }

    @Test
    fun `ignores real host port phrases`() {
        assertTrue(ports(PortDetector(), "example.com port 3000 is documented\n").isEmpty())
        assertTrue(ports(PortDetector(), "port 3000 on example.com is documented\n").isEmpty())
    }

    @Test
    fun `ignores loopback substring inside larger hostname`() {
        val text = "notlocalhost:3000 localhost.evil.com:3001 http://example.com:3002\n"
        assertTrue(ports(PortDetector(), text).isEmpty())
    }

    @Test
    fun `ignores loopback prefix inside larger token`() {
        val text = "Open localhost:5173abc localhost:5173_ms localhost:5173.evil " +
            "http://localhost:3000abc http://localhost:3000_ms http://localhost:3000.evil " +
            "localhost port 5173abc port 3000_ms on localhost\n"
        assertTrue(ports(PortDetector(), text).isEmpty())
    }

    @Test
    fun `matches loopback port followed by sentence punctuation`() {
        assertEquals(
            setOf(5173, 3000, 8000),
            ports(
                PortDetector(),
                "Open (localhost:5173), http://localhost:3000. and 127.0.0.1:8000!\n",
            ).toSet(),
        )
    }

    @Test
    fun `ignores out of range port`() {
        assertTrue(ports(PortDetector(), "http://localhost:99999/\n").isEmpty())
    }

    // --- rolling buffer across chunk boundaries ---

    @Test
    fun `finds port split across two chunks`() {
        val detector = PortDetector()
        // First chunk ends mid-port — nothing complete yet.
        assertTrue(ports(detector, "Listening on http://127.0.0.1:51").isEmpty())
        // Second chunk completes "5173" across the boundary.
        assertEquals(listOf(5173), ports(detector, "73/\n"))
    }

    @Test
    fun `finds bare localhost port split across two chunks`() {
        val detector = PortDetector()
        assertTrue(ports(detector, "localhost:51").isEmpty())
        assertEquals(listOf(5173), ports(detector, "73\n"))
    }

    // --- de-dup: a confirmed port is never offered twice ---

    @Test
    fun `same port is offered once then suppressed after confirm`() {
        val detector = PortDetector()
        val first = ports(detector, "Listening on 0.0.0.0:8000\n")
        assertEquals(listOf(8000), first)
        assertTrue(detector.confirmed(8000))
        // Re-printed later in the session — not offered again.
        assertTrue(ports(detector, "Listening on 0.0.0.0:8000\n").isEmpty())
    }

    @Test
    fun `port pending confirm is not re-offered`() {
        val detector = PortDetector()
        assertEquals(listOf(8000), ports(detector, "Listening on 0.0.0.0:8000\n"))
        // Same port reprinted before the confirm resolves: no duplicate.
        assertTrue(ports(detector, "still Listening on 0.0.0.0:8000\n").isEmpty())
    }

    // --- de-dup: dismissed / forwarded ports stay suppressed ---

    @Test
    fun `dismissed port is not re-offered`() {
        val detector = PortDetector()
        ports(detector, "Listening on 0.0.0.0:8000\n")
        detector.dismissed(8000)
        assertTrue(ports(detector, "Listening on 0.0.0.0:8000\n").isEmpty())
    }

    @Test
    fun `forwarded port is not re-offered`() {
        val detector = PortDetector()
        ports(detector, "Listening on 0.0.0.0:8000\n")
        detector.forwarded(8000)
        assertTrue(ports(detector, "Listening on 0.0.0.0:8000\n").isEmpty())
    }

    @Test
    fun `already forwarded ports seeded at construction are never offered`() {
        val detector = PortDetector(alreadyForwarded = setOf(8000))
        assertTrue(ports(detector, "Listening on 0.0.0.0:8000\n").isEmpty())
    }

    // --- false-positive confirm contract ---

    @Test
    fun `confirm failure releases the port for a later real bind`() {
        val detector = PortDetector()
        // First print is an echoed URL — ss says not listening.
        assertEquals(listOf(8000), ports(detector, "Listening on 0.0.0.0:8000\n"))
        detector.confirmFailed(8000)
        // The agent later actually binds the same port — re-offered.
        assertEquals(listOf(8000), ports(detector, "Listening on 0.0.0.0:8000\n"))
    }

    @Test
    fun `confirmed returns false on a duplicate confirm`() {
        val detector = PortDetector()
        ports(detector, "Listening on 0.0.0.0:8000\n")
        assertTrue(detector.confirmed(8000))
        // A stale second confirm for an already-handled port must not
        // re-surface the overlay.
        assertFalse(detector.confirmed(8000))
    }

    @Test
    fun `multiple distinct ports in one chunk are all offered`() {
        val detector = PortDetector()
        val found = ports(
            detector,
            "Local:   http://localhost:5173/\nListening on 0.0.0.0:8000\n",
        )
        assertEquals(setOf(5173, 8000), found.toSet())
    }
}
