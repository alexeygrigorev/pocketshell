package com.pocketshell.core.portfwd

import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.transport.HostConnection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the regex/parsing layer of [PortScanner], plus the
 * fallback-chain wiring (primary → fallback → last resort).
 *
 * No network. No Docker. We feed [PortScanner.scan] a [FakeHostConnection]
 * scripted to answer each strategy's command with a canned [ExecResult].
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
    fun `scan reports Failed when every strategy fails`() = runTest {
        // Issue #2489: the failure case is its own type, not an empty list —
        // the AutoForwarder must be able to tell "the scan didn't work" from
        // "the remote has nothing listening" before it tears tunnels down.
        val host = stubHost { _ -> exec(exitCode = 127) }
        assertEquals(PortScanResult.Failed, PortScanner.scan(host))
    }

    @Test
    fun `scan reports Failed when a strategy produces output no parser recognises`() = runTest {
        // Non-blank garbage that yields zero ports is a broken strategy, not
        // an empty host (issue #2489) — Ports(emptyList()) is unreachable.
        val host = stubHost { _ -> exec("bind: Address family not supported\n") }
        assertEquals(PortScanResult.Failed, PortScanner.scan(host))
    }

    @Test
    fun `scan treats a timed-out exec as a failed strategy, not truncated output`() = runTest {
        // Issue #2489: exec returns partial stdout with timedOut=true instead
        // of throwing. Parsing that half-written listing reports a short port
        // list as authoritative. RED on base: scan returns just :22.
        val host = stubHost { cmd ->
            when {
                cmd.startsWith("ss -tlnp") -> timedOutExec("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))\n")
                else -> exec(exitCode = 127)
            }
        }
        assertEquals(PortScanResult.Failed, PortScanner.scan(host))
    }

    @Test
    fun `scan falls through to netstat when the primary times out`() = runTest {
        // The timed-out primary must not short-circuit the chain either — the
        // next strategy still gets its turn and its full listing wins.
        val host = stubHost { cmd ->
            when {
                cmd.startsWith("ss -tlnp") -> timedOutExec("0.0.0.0:22 users:((\"sshd\",pid=1,fd=3))\n")
                cmd.startsWith("netstat -tlnp") -> exec("0.0.0.0:22 1/sshd\n0.0.0.0:3000 7/app\n")
                else -> error("last resort should not have run: cmd=$cmd")
            }
        }
        assertEquals(
            PortScanResult.Ports(listOf(RemotePort(22, "sshd"), RemotePort(3000, "app"))),
            PortScanner.scan(host),
        )
    }

    @Test
    fun `scan uses primary ss output when available`() = runTest {
        val host = stubHost { cmd ->
            when {
                cmd.startsWith("ss -tlnp") ->
                    exec("0.0.0.0:8080 users:((\"app\",pid=1,fd=4))\n")
                else -> error("primary strategy should have won: cmd=$cmd")
            }
        }
        assertEquals(
            PortScanResult.Ports(listOf(RemotePort(8080, "app"))),
            PortScanner.scan(host),
        )
    }

    @Test
    fun `scan falls through to netstat when ss returns nothing`() = runTest {
        val host = stubHost { cmd ->
            when {
                cmd.startsWith("ss -tlnp") -> exec("")
                cmd.startsWith("netstat -tlnp") -> exec("0.0.0.0:22 1/sshd\n")
                else -> error("last resort should not have run: cmd=$cmd")
            }
        }
        assertEquals(
            PortScanResult.Ports(listOf(RemotePort(22, "sshd"))),
            PortScanner.scan(host),
        )
    }

    @Test
    fun `scan falls through to last-resort port-only output`() = runTest {
        val host = stubHost { cmd ->
            when {
                cmd.startsWith("ss -tlnp") -> exec("")
                cmd.startsWith("netstat -tlnp") -> exec("")
                cmd.startsWith("ss -tln") -> exec("127.0.0.1:9000\n")
                else -> error("unexpected command: $cmd")
            }
        }
        val result = PortScanner.scan(host)
        assertTrue("expected a successful scan, got $result", result is PortScanResult.Ports)
        assertTrue((result as PortScanResult.Ports).ports.contains(RemotePort(9000, "")))
    }

    /**
     * A [FakeHostConnection] whose every exec is answered by [onExec], so a test
     * scripts the fallback chain by command prefix and nothing else about the
     * transport has to be stubbed.
     */
    private fun stubHost(onExec: (String) -> ExecResult): HostConnection =
        FakeHostConnection().onExecMatching("any command", match = { true }) { onExec(it) }

    /** A finished command: the scanner only ever reads stdout and the exit code. */
    private fun exec(stdout: String = "", exitCode: Int = 0): ExecResult =
        ExecResult(exitCode = exitCode, stdout = stdout, stderr = "", timedOut = false)

    /**
     * A command whose wall-clock budget elapsed: [HostConnection.exec] does
     * not throw for this, it hands back whatever was captured so far with
     * `timedOut = true` and a meaningless exit code.
     */
    private fun timedOutExec(partialStdout: String): ExecResult =
        ExecResult(exitCode = 0, stdout = partialStdout, stderr = "", timedOut = true)
}
