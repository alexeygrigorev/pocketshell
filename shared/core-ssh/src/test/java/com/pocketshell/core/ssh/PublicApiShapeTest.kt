package com.pocketshell.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure unit tests over the public surface. No network, no Docker.
 *
 * These exist mainly to lock down the data shapes documented in issue #4 so
 * a follow-up refactor can't quietly drift them.
 */
class PublicApiShapeTest {

    @Test
    fun `ExecResult carries stdout stderr and exit code`() {
        val r = ExecResult(stdout = "hi\n", stderr = "warn\n", exitCode = 7)
        assertEquals("hi\n", r.stdout)
        assertEquals("warn\n", r.stderr)
        assertEquals(7, r.exitCode)
    }

    @Test
    fun `SshKey Path wraps a File`() {
        val file = File("/tmp/nope")
        val key = SshKey.Path(file)
        assertEquals(file, key.file)
    }

    @Test
    fun `SshKey Pem wraps the raw key content`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----\n"
        val key = SshKey.Pem(pem)
        assertEquals(pem, key.content)
    }

    @Test
    fun `KnownHostsPolicy AcceptAll is a singleton`() {
        assertTrue(KnownHostsPolicy.AcceptAll === KnownHostsPolicy.AcceptAll)
    }

    @Test
    fun `KnownHostsPolicy KnownHostsFile wraps a File`() {
        val file = File("/tmp/known_hosts")
        val policy = KnownHostsPolicy.KnownHostsFile(file)
        assertEquals(file, policy.file)
    }

    @Test
    fun `SshException preserves message and cause`() {
        val cause = IllegalStateException("boom")
        val ex = SshException("wrap", cause)
        assertEquals("wrap", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `SshException is constructible without a cause`() {
        val ex = SshException("alone")
        assertEquals("alone", ex.message)
        assertNotNull(ex)
        assertFalse(ex.cause === ex)
    }
}
