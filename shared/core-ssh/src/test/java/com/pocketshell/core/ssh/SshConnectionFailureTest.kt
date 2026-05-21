package com.pocketshell.core.ssh

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Failure-mode tests that don't require a running SSH server. These cover
 * the "errors land in Result.failure, not as thrown exceptions" contract of
 * [SshConnection.connect].
 */
class SshConnectionFailureTest {

    @Test
    fun `connect with a missing key file returns Result failure`() = runTest {
        val result = SshConnection.connect(
            host = "127.0.0.1",
            port = 1, // port 1 is unlikely to host an SSH server
            user = "nobody",
            key = SshKey.Path(File("/definitely/not/a/real/key/file")),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 2_000,
        )
        assertTrue("expected failure, got $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("expected SshException, got ${ex!!.javaClass.name}", ex is SshException)
    }

    @Test
    fun `connect to closed port returns Result failure`() = runTest {
        // Port 1 is reserved/privileged and nothing listens there on a dev
        // box — guaranteed connection refusal within the timeout.
        val pem = "not a real key"
        val result = SshConnection.connect(
            host = "127.0.0.1",
            port = 1,
            user = "nobody",
            key = SshKey.Pem(pem),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 2_000,
        )
        assertTrue("expected failure, got $result", result.isFailure)
    }
}
