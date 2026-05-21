package com.pocketshell.app.bootstrap

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HostBootstrapper] driven by a hand-rolled fake
 * [SshSession].
 *
 * The fake records each `exec` invocation and returns a canned
 * [ExecResult] keyed by the command string. This lets us exercise both
 * the detection branches and the OS-id → package-manager mapping without
 * touching the network or Docker (Testcontainers is reserved for the
 * end-to-end integration tier).
 *
 * Acceptance-criterion coverage:
 *
 * - `checkTmux returns Installed/Missing/Unknown` — three tests
 *   ([checkTmux_returnsInstalled_whenExitZero],
 *   [checkTmux_returnsMissing_whenExitNonZero],
 *   [checkTmux_returnsUnknown_whenExecThrows]).
 * - `installTmux detects OS and runs the right package manager command`
 *   — one test per OS family ([installTmux_ubuntu_usesAptGet] +
 *   sibling tests).
 */
class HostBootstrapperTest {

    private val bootstrapper = HostBootstrapper()

    @Test
    fun checkTmux_returnsInstalled_whenExitZero() = runTest {
        val session = FakeSshSession(
            mapOf("command -v tmux" to ExecResult("/usr/bin/tmux\n", "", 0)),
        )
        val status = bootstrapper.checkTmux(session)
        assertEquals(TmuxStatus.Installed, status)
        assertEquals(listOf("command -v tmux"), session.recorded)
    }

    @Test
    fun checkTmux_returnsMissing_whenExitNonZero() = runTest {
        val session = FakeSshSession(
            mapOf("command -v tmux" to ExecResult("", "", 1)),
        )
        val status = bootstrapper.checkTmux(session)
        assertEquals(TmuxStatus.Missing, status)
    }

    @Test
    fun checkTmux_returnsMissing_whenExitZeroButEmptyStdout() = runTest {
        val session = FakeSshSession(
            mapOf("command -v tmux" to ExecResult("", "", 0)),
        )
        val status = bootstrapper.checkTmux(session)
        // Defensive: exit 0 with no output is treated as missing.
        assertEquals(TmuxStatus.Missing, status)
    }

    @Test
    fun checkTmux_returnsUnknown_whenExecThrows() = runTest {
        val session = FakeSshSession(throwOnExec = SshException("transport closed"))
        val status = bootstrapper.checkTmux(session)
        assertTrue(status is TmuxStatus.Unknown)
        val unknown = status as TmuxStatus.Unknown
        assertTrue(unknown.reason.contains("transport closed"))
    }

    @Test
    fun parseOsId_extractsLowercaseId() {
        val raw = """
            NAME="Ubuntu"
            VERSION="22.04.3 LTS (Jammy Jellyfish)"
            ID=ubuntu
            ID_LIKE=debian
        """.trimIndent()
        assertEquals("ubuntu", bootstrapper.parseOsId(raw))
    }

    @Test
    fun parseOsId_stripsQuotes() {
        val raw = """
            NAME="Alpine Linux"
            ID="alpine"
        """.trimIndent()
        assertEquals("alpine", bootstrapper.parseOsId(raw))
    }

    @Test
    fun parseOsId_returnsNull_whenMissing() {
        val raw = "PRETTY_NAME=\"Something weird\"\nVERSION=1\n"
        assertNull(bootstrapper.parseOsId(raw))
    }

    @Test
    fun detectPackageManager_debianFamily_isAptGet() {
        listOf("debian", "ubuntu", "raspbian", "pop", "linuxmint", "kali").forEach { id ->
            val pm = bootstrapper.detectPackageManager(id)
            assertNotNull("expected a PM for $id", pm)
            assertEquals("apt-get install -y tmux", pm!!.command)
            assertTrue("debian-family needs root", pm.needsRoot)
        }
    }

    @Test
    fun detectPackageManager_alpine_isApk() {
        val pm = bootstrapper.detectPackageManager("alpine")
        assertNotNull(pm)
        assertEquals("apk add tmux", pm!!.command)
    }

    @Test
    fun detectPackageManager_rhelFamily_isDnf() {
        listOf("fedora", "rhel", "rocky", "almalinux", "centos").forEach { id ->
            val pm = bootstrapper.detectPackageManager(id)
            assertNotNull("expected a PM for $id", pm)
            assertEquals("dnf install -y tmux", pm!!.command)
        }
    }

    @Test
    fun detectPackageManager_arch_isPacman() {
        val pm = bootstrapper.detectPackageManager("arch")
        assertNotNull(pm)
        assertEquals("pacman -S --noconfirm tmux", pm!!.command)
    }

    @Test
    fun detectPackageManager_macos_isBrewNoRoot() {
        val pm = bootstrapper.detectPackageManager("darwin")
        assertNotNull(pm)
        assertEquals("brew install tmux", pm!!.command)
        assertTrue("brew never needs root", !pm.needsRoot)
    }

    @Test
    fun detectPackageManager_unknown_returnsNull() {
        assertNull(bootstrapper.detectPackageManager("plan9"))
        assertNull(bootstrapper.detectPackageManager(null))
    }

    @Test
    fun installTmux_ubuntu_usesAptGet() = runTest {
        // Non-root user → command gets a `sudo` prefix.
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("ID=ubuntu\n", "", 0),
                "id -u" to ExecResult("1000\n", "", 0),
                "sudo apt-get install -y tmux" to ExecResult("done\n", "", 0),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains("sudo apt-get install -y tmux"))
    }

    @Test
    fun installTmux_alpine_asRoot_skipsSudo() = runTest {
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("ID=alpine\n", "", 0),
                "id -u" to ExecResult("0\n", "", 0),
                "apk add tmux" to ExecResult("(1/1) Installing tmux\n", "", 0),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertEquals(InstallResult.Success, result)
        // Confirm no sudo prefix was used.
        assertTrue(session.recorded.any { it == "apk add tmux" })
        assertTrue(session.recorded.none { it.startsWith("sudo ") })
    }

    @Test
    fun installTmux_macos_viaUnameFallback_usesBrew() = runTest {
        // No /etc/os-release on macOS → cat returns non-zero, then we
        // fall back to `uname -s`.
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("", "No such file", 1),
                "uname -s" to ExecResult("Darwin\n", "", 0),
                "brew install tmux" to ExecResult("==> Pouring tmux\n", "", 0),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains("brew install tmux"))
    }

    @Test
    fun installTmux_surfacesPackageManagerFailure() = runTest {
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("ID=ubuntu\n", "", 0),
                "id -u" to ExecResult("0\n", "", 0),
                "apt-get install -y tmux" to ExecResult("", "E: Unable to locate package tmux\n", 100),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertTrue(result is InstallResult.Failed)
        val failed = result as InstallResult.Failed
        assertEquals(100, failed.exitCode)
        assertTrue(failed.stderr.contains("Unable to locate"))
    }

    @Test
    fun installTmux_unsupportedOs_returnsUnsupportedOs() = runTest {
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("ID=plan9\n", "", 0),
                "id -u" to ExecResult("0\n", "", 0),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertTrue(result is InstallResult.UnsupportedOs)
        assertEquals("plan9", (result as InstallResult.UnsupportedOs).osId)
    }

    @Test
    fun installTmux_osReleaseAndUnameBothFail_returnsUnsupportedOs() = runTest {
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("", "", 1),
                "uname -s" to ExecResult("WeirdOS\n", "", 0),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertTrue(result is InstallResult.UnsupportedOs)
        assertNull((result as InstallResult.UnsupportedOs).osId)
    }

    /**
     * Test-only fake. Records commands and returns canned results. Throws
     * for transport-failure tests when `throwOnExec` is non-null.
     */
    private class FakeSshSession(
        private val canned: Map<String, ExecResult> = emptyMap(),
        private val throwOnExec: Throwable? = null,
    ) : SshSession {
        val recorded: MutableList<String> = mutableListOf()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            throwOnExec?.let { throw it }
            return canned[command] ?: ExecResult("", "command not stubbed: $command", 127)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used in this test")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("port forward not used in this test")

        override fun startShell(): SshShell = error("shell not used in this test")

        override fun close() = Unit
    }
}
