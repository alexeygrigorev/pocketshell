package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.SshException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class HostConnectErrorTest {

    /**
     * The bug-report case from issue #108 / #109: the wrapped sshj
     * exception nests a `ConnectException` carrying ECONNREFUSED. The
     * mapper must look past the wrapper and produce
     * "Connection refused." instead of leaking the raw text.
     */
    @Test
    fun connectionRefusedShortReason() {
        val wrapped = SshException(
            "SSH connect to test@127.0.0.1:22 failed: ConnectException",
            ConnectException(
                "failed to connect to /127.0.0.1 (port 22) from /127.0.0.1 (port 44494) " +
                    "after 30000ms: isConnected failed: ECONNREFUSED (Connection refused)",
            ),
        )

        val summary = summarizeConnectError(wrapped)

        assertEquals(HostConnectErrorReason.ConnectionRefused, summary.reason)
        assertEquals("Connection refused.", summary.shortReason)
        assertTrue(
            "details must retain the raw ECONNREFUSED text for bug reports",
            summary.details.contains("ECONNREFUSED"),
        )
    }

    @Test
    fun unknownHostShortReason() {
        val wrapped = SshException(
            "SSH connect to test@nope:22 failed: UnknownHostException",
            UnknownHostException("nope"),
        )

        val summary = summarizeConnectError(wrapped)

        assertEquals(HostConnectErrorReason.UnknownHost, summary.reason)
        assertEquals("Host not found.", summary.shortReason)
    }

    @Test
    fun timeoutShortReason() {
        val wrapped = SshException(
            "SSH connect to test@10.0.2.2:22 failed: SocketTimeoutException",
            SocketTimeoutException("connect timed out"),
        )

        val summary = summarizeConnectError(wrapped)

        assertEquals(HostConnectErrorReason.TimedOut, summary.reason)
        assertEquals("Connection timed out.", summary.shortReason)
    }

    @Test
    fun userAuthExceptionMapsToAuthFailed() {
        val wrapped = SshException(
            "SSH connect to test@10.0.2.2:22 failed: UserAuthException",
            UserAuthException("Exhausted available authentication methods"),
        )

        val summary = summarizeConnectError(wrapped)

        assertEquals(HostConnectErrorReason.AuthFailed, summary.reason)
        assertEquals("SSH key rejected.", summary.shortReason)
    }

    @Test
    fun unknownExceptionKeepsRawDetails() {
        val wrapped = SshException(
            "SSH connect failed",
            IOException("some weird i/o failure with no recognisable keyword"),
        )

        val summary = summarizeConnectError(wrapped)

        assertEquals(HostConnectErrorReason.Unknown, summary.reason)
        assertEquals("Unable to connect.", summary.shortReason)
        assertTrue(
            "details must include the IOException message verbatim",
            summary.details.contains("some weird i/o failure"),
        )
    }

    @Test
    fun bodyLineComposesUserHostPort() {
        val summary = summarizeConnectError(
            SshException("wrapped", ConnectException("ECONNREFUSED")),
        )
        val body = formatHostConnectErrorBody(
            user = "testuser",
            host = "10.0.2.2",
            port = 2299,
            summary = summary,
        )

        assertEquals("Couldn't reach testuser@10.0.2.2:2299. Connection refused.", body)
    }

    /**
     * Cycles in the cause chain (rare but possible) must not deadlock
     * the classifier — the chain-walker enforces a `seen` set.
     */
    @Test
    fun cyclicCauseChainIsSafe() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        // Stitch a -> b -> a to force a cycle.
        a.initCause(b)
        val summary = summarizeConnectError(b)
        assertEquals(HostConnectErrorReason.Unknown, summary.reason)
    }
}
