package com.pocketshell.core.portfwd

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the regex/parsing layer of [PortScanner], plus the
 * fallback-chain wiring (primary → fallback → last resort).
 *
 * No network. No Docker. We feed [PortScanner.scan] a hand-written
 * [SshSession] that returns canned [ExecResult]s per command.
 */
class PortScannerTest {

    @Test
    fun `parseSsOutput extracts port and process name`() {
        // Realistic `ss -tlnp` awk-filtered output: `<localAddr> <users:(("name",...))>`.
        val out = """
            0.0.0.0:22 users:(("sshd",pid=1,fd=3))
            127.0.0.1:3000 users:(("python3",pid=42,fd=4))
            :::8080 users:(("nginx",pid=99,fd=6))
        """.trimIndent()

        val ports = PortScanner.parseSsOutput(out)

        assertEquals(3, ports.size)
        assertEquals(RemotePort(22, "sshd"), ports[0])
        assertEquals(RemotePort(3000, "python3"), ports[1])
        assertEquals(RemotePort(8080, "nginx"), ports[2])
    }

    @Test
    fun `parseSsOutput tolerates the single-paren legacy ss format`() {
        // Older iproute2 omitted the outer paren: `users:("name",...)`.
        val out = """0.0.0.0:5432 users:("postgres",pid=12,fd=4)"""
        val ports = PortScanner.parseSsOutput(out)
        assertEquals(listOf(RemotePort(5432, "postgres")), ports)
    }

    @Test
    fun `parseSsOutput skips lines with no port`() {
        val out = """
            this-line-has-no-colon
            127.0.0.1:not_a_number users:(("foo",pid=1,fd=3))
            127.0.0.1:9000 users:(("ok",pid=1,fd=3))
        """.trimIndent()

        val ports = PortScanner.parseSsOutput(out)

        assertEquals(listOf(RemotePort(9000, "ok")), ports)
    }

    @Test
    fun `parseNetstatOutput extracts port and process name from busybox netstat`() {
        // Format from `netstat -tlnp` on Alpine busybox.
        val out = """
            0.0.0.0:22 1/sshd
            :::22 1/sshd
            127.0.0.1:5432 42/postgres,extra
        """.trimIndent()

        val ports = PortScanner.parseNetstatOutput(out)

        assertEquals(3, ports.size)
        assertEquals(RemotePort(22, "sshd"), ports[0])
        assertEquals(RemotePort(22, "sshd"), ports[1])
        assertEquals(RemotePort(5432, "postgres"), ports[2])
    }

    @Test
    fun `parsePortsOnly extracts ports without process information`() {
        val out = """
            0.0.0.0:22
            127.0.0.1:3000
            :::8080
        """.trimIndent()

        val ports = PortScanner.parsePortsOnly(out)

        assertEquals(
            listOf(RemotePort(22, ""), RemotePort(3000, ""), RemotePort(8080, "")),
            ports,
        )
    }

    @Test
    fun `scan returns empty list when every strategy fails`() = runTest {
        val session = StubSession { _ -> ExecResult("", "", exitCode = 127) }
        assertEquals(emptyList<RemotePort>(), PortScanner.scan(session))
    }

    @Test
    fun `scan uses primary ss output when available`() = runTest {
        val session = StubSession { cmd ->
            when {
                cmd.startsWith("ss -tlnp") ->
                    ExecResult("0.0.0.0:8080 users:((\"app\",pid=1,fd=4))\n", "", 0)
                else -> error("primary strategy should have won: cmd=$cmd")
            }
        }
        assertEquals(listOf(RemotePort(8080, "app")), PortScanner.scan(session))
    }

    @Test
    fun `scan falls through to netstat when ss returns nothing`() = runTest {
        val session = StubSession { cmd ->
            when {
                cmd.startsWith("ss -tlnp") -> ExecResult("", "", 0)
                cmd.startsWith("netstat -tlnp") -> ExecResult("0.0.0.0:22 1/sshd\n", "", 0)
                else -> error("last resort should not have run: cmd=$cmd")
            }
        }
        assertEquals(listOf(RemotePort(22, "sshd")), PortScanner.scan(session))
    }

    @Test
    fun `scan falls through to last-resort port-only output`() = runTest {
        val session = StubSession { cmd ->
            when {
                cmd.startsWith("ss -tlnp") -> ExecResult("", "", 0)
                cmd.startsWith("netstat -tlnp") -> ExecResult("", "", 0)
                cmd.startsWith("ss -tln") -> ExecResult("127.0.0.1:9000\n", "", 0)
                else -> error("unexpected command: $cmd")
            }
        }
        assertTrue(PortScanner.scan(session).contains(RemotePort(9000, "")))
    }

    /** Minimal [SshSession] that resolves [exec] from a function and stubs everything else. */
    private class StubSession(
        private val onExec: (String) -> ExecResult,
    ) : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = onExec(command)
        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used by PortScanner tests")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("openLocalPortForward not used by PortScanner tests")
        override fun close() = Unit
    }
}
