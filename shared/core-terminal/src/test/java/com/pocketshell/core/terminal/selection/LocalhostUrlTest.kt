package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the server-local (loopback) URL classification used by issue
 * #488's "tap localhost link → forward that port" flow. Exercises the pure
 * [classifyLocalhostUrl] / [isLocalhostHost] core.
 */
class LocalhostUrlTest {

    // --- Host classification ------------------------------------------------

    @Test
    fun recognizesLoopbackHostLiterals() {
        assertTrue(isLocalhostHost("localhost"))
        assertTrue(isLocalhostHost("127.0.0.1"))
        assertTrue(isLocalhostHost("0.0.0.0"))
        assertTrue(isLocalhostHost("::1"))
        assertTrue(isLocalhostHost("[::1]"))
        // Case-insensitive + tolerant of stray whitespace.
        assertTrue(isLocalhostHost("LOCALHOST"))
        assertTrue(isLocalhostHost(" localhost "))
    }

    @Test
    fun rejectsRealHostsAsNonLocal() {
        assertFalse(isLocalhostHost("example.com"))
        assertFalse(isLocalhostHost("myserver.internal"))
        assertFalse(isLocalhostHost("192.168.1.10"))
        assertFalse(isLocalhostHost("10.0.0.5"))
        // A hostname that merely contains "localhost" is not loopback.
        assertFalse(isLocalhostHost("localhost.evil.com"))
        assertFalse(isLocalhostHost("notlocalhost"))
    }

    // --- URL classification: positive (server-local with port) --------------

    @Test
    fun classifiesLocalhostWithPort() {
        val r = classifyLocalhostUrl("http://localhost:3000")
        assertEquals(3000, r?.remotePort)
        assertEquals("http", r?.scheme)
        assertEquals("", r?.pathAndQuery)
    }

    @Test
    fun classifies127001WithPort() {
        assertEquals(5173, classifyLocalhostUrl("http://127.0.0.1:5173")?.remotePort)
    }

    @Test
    fun classifies0000WithPort() {
        assertEquals(8080, classifyLocalhostUrl("http://0.0.0.0:8080")?.remotePort)
    }

    @Test
    fun classifiesIpv6LoopbackWithPort() {
        val r = classifyLocalhostUrl("http://[::1]:9000/admin")
        assertEquals(9000, r?.remotePort)
        assertEquals("/admin", r?.pathAndQuery)
    }

    @Test
    fun classifiesBareLocalhostReferenceWithPort() {
        val r = classifyLocalhostUrl("localhost:5173")
        assertEquals(5173, r?.remotePort)
        assertEquals("http", r?.scheme)
        assertEquals("", r?.pathAndQuery)
    }

    @Test
    fun classifiesBareLoopbackReferenceWithPath() {
        val r = classifyLocalhostUrl("127.0.0.1:3000/admin?tab=logs#tail")
        assertEquals(3000, r?.remotePort)
        assertEquals("http", r?.scheme)
        assertEquals("/admin?tab=logs#tail", r?.pathAndQuery)
    }

    @Test
    fun preservesSchemeAndPathAndQuery() {
        val r = classifyLocalhostUrl("https://localhost:8443/app/page?x=1#frag")
        assertEquals(8443, r?.remotePort)
        assertEquals("https", r?.scheme)
        assertEquals("/app/page?x=1#frag", r?.pathAndQuery)
    }

    @Test
    fun toLocalUrlRetargetsTheLocalPort() {
        val r = classifyLocalhostUrl("http://localhost:3000/dash")!!
        // Forward mapped server:3000 -> phone:14000; opened URL hits the phone.
        assertEquals("http://127.0.0.1:14000/dash", r.toLocalUrl(14000))
    }

    // --- URL classification: negative (real host / no port / non-http) ------

    @Test
    fun returnsNullForRealHost() {
        assertNull(classifyLocalhostUrl("http://example.com:3000"))
        assertNull(classifyLocalhostUrl("https://github.com/foo/bar"))
    }

    @Test
    fun returnsNullForLoopbackWithoutExplicitPort() {
        // No actionable port to forward → fall back to the normal browser route.
        assertNull(classifyLocalhostUrl("http://localhost"))
        assertNull(classifyLocalhostUrl("http://127.0.0.1/health"))
    }

    @Test
    fun returnsNullForNonHttpScheme() {
        assertNull(classifyLocalhostUrl("ftp://localhost:21"))
        assertNull(classifyLocalhostUrl("ssh://localhost:22"))
    }

    @Test
    fun returnsNullForOutOfRangePort() {
        assertNull(classifyLocalhostUrl("http://localhost:0"))
        assertNull(classifyLocalhostUrl("http://localhost:70000"))
        assertNull(classifyLocalhostUrl("http://localhost:notaport"))
    }

    // --- Plain-text scanning ------------------------------------------------

    @Test
    fun detectsCommonLoopbackReferencesInPlainText() {
        val refs = detectLocalhostPortReferences(
            "try http://localhost:3000, localhost:5173, 127.0.0.1:8000 and 0.0.0.0:9000.",
        )

        assertEquals(
            listOf(
                "http://localhost:3000",
                "localhost:5173",
                "127.0.0.1:8000",
                "0.0.0.0:9000",
            ),
            refs.map { it.text },
        )
        assertEquals(listOf(3000, 5173, 8000, 9000), refs.map { it.localhostUrl.remotePort })
    }

    @Test
    fun plainTextScannerStripsValidSentencePunctuation() {
        val refs = detectLocalhostPortReferences(
            "try (localhost:5173), \"http://localhost:3000.\" and 127.0.0.1:8000!",
        )

        assertEquals(
            listOf("localhost:5173", "http://localhost:3000", "127.0.0.1:8000"),
            refs.map { it.text },
        )
        assertEquals(listOf(5173, 3000, 8000), refs.map { it.localhostUrl.remotePort })
    }

    @Test
    fun plainTextScannerRejectsLoopbackPrefixInsideLargerTokens() {
        val refs = detectLocalhostPortReferences(
            "bad localhost:5173abc localhost:5173_ms localhost:5173.evil " +
                "http://localhost:3000abc http://localhost:3000_ms http://localhost:3000.evil",
        )

        assertTrue("expected no loopback references, got $refs", refs.isEmpty())
    }

    @Test
    fun plainTextScannerRejectsLargerHostnamesAndBadPorts() {
        val refs = detectLocalhostPortReferences(
            "notlocalhost:3000 localhost.evil.com:3000 http://example.com:3000 " +
                "localhost:70000 ftp://localhost:21",
        )

        assertTrue("expected no loopback references, got $refs", refs.isEmpty())
    }
}
