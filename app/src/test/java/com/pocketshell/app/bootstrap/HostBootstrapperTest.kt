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
            mapOf(pathAware("command -v tmux") to ExecResult("/usr/bin/tmux\n", "", 0)),
        )
        val status = bootstrapper.checkTmux(session)
        assertEquals(TmuxStatus.Installed, status)
        assertEquals(listOf(pathAware("command -v tmux")), session.recorded)
    }

    @Test
    fun checkTmux_returnsMissing_whenExitNonZero() = runTest {
        val session = FakeSshSession(
            mapOf(pathAware("command -v tmux") to ExecResult("", "", 1)),
        )
        val status = bootstrapper.checkTmux(session)
        assertEquals(TmuxStatus.Missing, status)
    }

    @Test
    fun checkTmux_returnsMissing_whenExitZeroButEmptyStdout() = runTest {
        val session = FakeSshSession(
            mapOf(pathAware("command -v tmux") to ExecResult("", "", 0)),
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
                shell("sudo apt-get install -y tmux") to ExecResult("done\n", "", 0),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains(shell("sudo apt-get install -y tmux")))
    }

    @Test
    fun installTmux_alpine_asRoot_skipsSudo() = runTest {
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("ID=alpine\n", "", 0),
                "id -u" to ExecResult("0\n", "", 0),
                shell("apk add tmux") to ExecResult("(1/1) Installing tmux\n", "", 0),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertEquals(InstallResult.Success, result)
        // Confirm no sudo prefix was used.
        assertTrue(session.recorded.any { it == shell("apk add tmux") })
        assertTrue(session.recorded.none { it.contains("sudo ") })
    }

    @Test
    fun installTmux_macos_viaUnameFallback_usesBrew() = runTest {
        // No /etc/os-release on macOS → cat returns non-zero, then we
        // fall back to `uname -s`.
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("", "No such file", 1),
                "uname -s" to ExecResult("Darwin\n", "", 0),
                shell("brew install tmux") to ExecResult("==> Pouring tmux\n", "", 0),
            ),
        )
        val result = bootstrapper.installTmux(session)
        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains(shell("brew install tmux")))
    }

    @Test
    fun installTmux_surfacesPackageManagerFailure() = runTest {
        val session = FakeSshSession(
            mapOf(
                "cat /etc/os-release" to ExecResult("ID=ubuntu\n", "", 0),
                "id -u" to ExecResult("0\n", "", 0),
                shell("apt-get install -y tmux") to ExecResult("", "E: Unable to locate package tmux\n", 100),
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

    @Test
    fun checkServerSetup_reportsReady_whenToolsAndDaemonArePresent() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'tmuxctl'") to ExecResult("/home/u/.local/bin/tmuxctl\n", "", 0),
                pathAware("command -v 'quse'") to ExecResult("/home/u/.local/bin/quse\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active tmuxctl-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled tmuxctl-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue(report.isReady)
        assertEquals(PythonToolInstaller.Uv, report.installer)
        assertTrue(report.daemon is TmuxctlDaemonStatus.Running)
        assertEquals(MoshStatus.Unsupported(MOSH_UNSUPPORTED_REASON), report.mosh)
        assertTrue(session.recorded.none { it.contains("mosh", ignoreCase = true) })
    }

    @Test
    fun checkServerSetup_usesUserLocalPathForToolDetection() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'tmuxctl'") to ExecResult("/home/u/.local/bin/tmuxctl\n", "", 0),
                pathAware("command -v 'quse'") to ExecResult("/home/u/bin/quse\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active tmuxctl-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled tmuxctl-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertEquals(
            ToolStatus.Installed("/home/u/bin/quse"),
            report.tools[BootstrapTool.Quse],
        )
        assertTrue(report.missingTools.isEmpty())
        assertTrue(report.isReady)
        assertTrue(session.recorded.all { command ->
            !command.contains("command -v '") ||
                command.startsWith("/bin/sh -lc ") &&
                command.contains("PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"")
        })
    }

    @Test
    fun checkServerSetup_wrapsBootstrapCommandsInPosixShell() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'tmuxctl'") to ExecResult("/home/u/.local/bin/tmuxctl\n", "", 0),
                pathAware("command -v 'quse'") to ExecResult("/home/u/.local/bin/quse\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active tmuxctl-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled tmuxctl-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue(report.isReady)
        assertTrue(session.recorded.all { it.startsWith("/bin/sh -lc ") })
        assertTrue(session.recorded.any { it.contains("PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"") })
        assertTrue(session.recorded.any { it.contains("XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\"") })
        assertTrue(session.recorded.none { it.startsWith("PATH=") })
        assertTrue(session.recorded.none { it.startsWith("systemctl --user") })
    }

    @Test
    fun bootstrapSheetRows_areEmpty_whenServerSetupIsReady() {
        // Issue #164: the Mosh row no longer renders (spike #159 returned
        // NO-GO), so a fully-ready host no longer has any sheet rows. The
        // MoshStatus model itself is preserved on the report for forward
        // compatibility — see assertions in
        // checkServerSetup_reportsReady_whenToolsAndDaemonArePresent.
        val report = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith {
                ToolStatus.Installed("/home/u/.local/bin/${it.binaryName}")
            },
            installer = PythonToolInstaller.Uv,
            daemon = TmuxctlDaemonStatus.Running(enabled = true),
        )

        assertTrue(report.isReady)
        assertTrue(!report.hasBootstrapSheetRows())
        assertTrue(!HostBootstrapSheetState.Prompt(needsTmux = false, report = report).hasActionableSetup())
    }

    @Test
    fun bootstrapPromptTreatsRunningButDisabledDaemonAsActionable() {
        val report = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith {
                ToolStatus.Installed("/home/u/.local/bin/${it.binaryName}")
            },
            installer = PythonToolInstaller.Uv,
            daemon = TmuxctlDaemonStatus.Running(enabled = false),
        )

        assertTrue(report.needsTmuxctlDaemonAction())
        assertTrue(HostBootstrapSheetState.Prompt(needsTmux = false, report = report).hasActionableSetup())
    }

    @Test
    fun checkServerSetup_prefersUvButFallsBackToPipx() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'tmuxctl'") to ExecResult("", "", 1),
                pathAware("command -v 'quse'") to ExecResult("", "", 1),
                pathAware("command -v 'uv'") to ExecResult("", "", 1),
                pathAware("command -v 'pipx'") to ExecResult("/usr/bin/pipx\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertEquals(PythonToolInstaller.Pipx, report.installer)
        assertEquals(BootstrapTool.entries, report.missingTools)
        assertTrue(report.daemon is TmuxctlDaemonStatus.Missing)
    }

    @Test
    fun installServerSetup_usesUvToolForMissingTools_thenInstallsSystemdUserUnit() = runTest {
        val installedTools = mutableSetOf<String>()
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'tmuxctl'") -> toolLookup("tmuxctl", installedTools)
                    pathAware("command -v 'quse'") -> toolLookup("quse", installedTools)
                    pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    pathAware("uv tool install tmuxctl") -> {
                        installedTools += "tmuxctl"
                        ExecResult("installed tmuxctl\n", "", 0)
                    }
                    pathAware("uv tool install quse") -> {
                        installedTools += "quse"
                        ExecResult("installed quse\n", "", 0)
                    }
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active tmuxctl-jobs.service") -> ExecResult("inactive\n", "", 3)
                    systemdAware("systemctl --user is-enabled tmuxctl-jobs.service") -> ExecResult("disabled\n", "", 1)
                    else -> if (command.contains("systemctl --user enable --now tmuxctl-jobs.service")) {
                        ExecResult("", "", 0)
                    } else {
                        null
                    }
                }
            },
        )
        val report = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith { ToolStatus.Missing },
            installer = PythonToolInstaller.Uv,
            daemon = TmuxctlDaemonStatus.Missing,
        )

        val result = bootstrapper.installServerSetup(session, report)

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains(pathAware("uv tool install tmuxctl")))
        assertTrue(session.recorded.contains(pathAware("uv tool install quse")))
        assertTrue(session.recorded.any { it.contains("ExecStart=\"/home/u/.local/bin/tmuxctl\" jobs daemon") })
        assertTrue(session.recorded.any { it.contains("systemctl --user enable --now tmuxctl-jobs.service") })
    }

    @Test
    fun installServerTool_usesPipxWithPathAwareLookup() = runTest {
        val session = FakeSshSession(
            mapOf(pathAware("pipx install quse") to ExecResult("installed quse\n", "", 0)),
        )

        val result = bootstrapper.installServerTool(session, PythonToolInstaller.Pipx, BootstrapTool.Quse)

        assertEquals(InstallResult.Success, result)
        assertEquals(listOf(pathAware("pipx install quse")), session.recorded)
    }

    @Test
    fun checkServerSetup_reportsUnavailable_whenSystemdUserBusIsUnavailable() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'tmuxctl'") to ExecResult("/home/u/.local/bin/tmuxctl\n", "", 0),
                pathAware("command -v 'quse'") to ExecResult("/home/u/.local/bin/quse\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active tmuxctl-jobs.service") to
                    ExecResult("", "Failed to connect to bus: No medium found\n", 1),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue(report.daemon is TmuxctlDaemonStatus.Unavailable)
        assertTrue((report.daemon as TmuxctlDaemonStatus.Unavailable).reason.contains("No medium"))
    }

    @Test
    fun checkServerSetup_reportsRunningDisabledDaemon() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'tmuxctl'") to ExecResult("/home/u/.local/bin/tmuxctl\n", "", 0),
                pathAware("command -v 'quse'") to ExecResult("/home/u/.local/bin/quse\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active tmuxctl-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled tmuxctl-jobs.service") to ExecResult("disabled\n", "", 1),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertEquals(TmuxctlDaemonStatus.Running(enabled = false), report.daemon)
        assertTrue(!report.isReady)
    }

    @Test
    fun checkServerSetup_reportsInstalledStoppedDisabledDaemon() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'tmuxctl'") to ExecResult("/home/u/.local/bin/tmuxctl\n", "", 0),
                pathAware("command -v 'quse'") to ExecResult("/home/u/.local/bin/quse\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active tmuxctl-jobs.service") to ExecResult("inactive\n", "", 3),
                systemdAware("systemctl --user is-enabled tmuxctl-jobs.service") to ExecResult("disabled\n", "", 1),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertEquals(TmuxctlDaemonStatus.InstalledStopped(enabled = false), report.daemon)
        assertTrue(!report.isReady)
    }

    @Test
    fun checkTmuxctlDaemon_reportsUnknown_whenStatusProbeThrows() = runTest {
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active tmuxctl-jobs.service") -> throw SshException("channel closed")
                    else -> null
                }
            },
        )

        val status = bootstrapper.checkTmuxctlDaemon(session)

        assertTrue(status is TmuxctlDaemonStatus.Unknown)
        assertTrue((status as TmuxctlDaemonStatus.Unknown).reason.contains("channel closed"))
    }

    @Test
    fun installServerSetup_enablesDaemon_whenRunningButDisabled() = runTest {
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'tmuxctl'") -> ExecResult("/home/u/.local/bin/tmuxctl\n", "", 0)
                    pathAware("command -v 'quse'") -> ExecResult("/home/u/.local/bin/quse\n", "", 0)
                    pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active tmuxctl-jobs.service") -> ExecResult("active\n", "", 0)
                    systemdAware("systemctl --user is-enabled tmuxctl-jobs.service") -> ExecResult("disabled\n", "", 1)
                    else -> if (command.contains("systemctl --user enable --now tmuxctl-jobs.service")) {
                        ExecResult("", "", 0)
                    } else {
                        null
                    }
                }
            },
        )
        val report = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith { ToolStatus.Installed("/home/u/.local/bin/${it.binaryName}") },
            installer = PythonToolInstaller.Uv,
            daemon = TmuxctlDaemonStatus.Running(enabled = false),
        )

        val result = bootstrapper.installServerSetup(session, report)

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.any { it.contains("systemctl --user enable --now tmuxctl-jobs.service") })
    }

    @Test
    fun installServerSetup_failsWhenPythonToolInstallerIsMissing() = runTest {
        val report = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith { ToolStatus.Missing },
            installer = null,
            daemon = TmuxctlDaemonStatus.Missing,
        )
        val result = bootstrapper.installServerSetup(FakeSshSession(), report)

        assertTrue(result is InstallResult.Error)
        assertTrue((result as InstallResult.Error).reason.contains("uv or pipx"))
    }

    @Test
    fun installServerSetup_reprobesBeforeInstallingStaleMissingTools() = runTest {
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'tmuxctl'") -> ExecResult("/home/u/.local/bin/tmuxctl\n", "", 0)
                    pathAware("command -v 'quse'") -> ExecResult("/home/u/.local/bin/quse\n", "", 0)
                    pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active tmuxctl-jobs.service") -> ExecResult("active\n", "", 0)
                    systemdAware("systemctl --user is-enabled tmuxctl-jobs.service") -> ExecResult("enabled\n", "", 0)
                    else -> null
                }
            },
        )
        val staleReport = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith { ToolStatus.Missing },
            installer = PythonToolInstaller.Uv,
            daemon = TmuxctlDaemonStatus.Missing,
        )

        val result = bootstrapper.installServerSetup(session, staleReport)

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.none { it.contains("uv tool install") })
        assertTrue(session.recorded.none { it.contains("systemctl --user enable --now") })
    }

    /**
     * Test-only fake. Records commands and returns canned results. Throws
     * for transport-failure tests when `throwOnExec` is non-null.
     */
    private class FakeSshSession(
        private val canned: Map<String, ExecResult> = emptyMap(),
        private val throwOnExec: Throwable? = null,
        private val dynamic: ((String) -> ExecResult?)? = null,
    ) : SshSession {
        val recorded: MutableList<String> = mutableListOf()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            throwOnExec?.let { throw it }
            return dynamic?.invoke(command)
                ?: canned[command]
                ?: ExecResult("", "command not stubbed: $command", 127)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used in this test")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("port forward not used in this test")

        override fun startShell(): SshShell = error("shell not used in this test")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private fun pathAware(command: String): String =
        shell("PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"; export PATH; $command")

    private fun systemdAware(command: String): String =
        shell(
            "XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\"; export XDG_RUNTIME_DIR; " +
                "DBUS_SESSION_BUS_ADDRESS=\"\${DBUS_SESSION_BUS_ADDRESS:-unix:path=\$XDG_RUNTIME_DIR/bus}\"; " +
                "export DBUS_SESSION_BUS_ADDRESS; $command",
        )

    private fun shell(command: String): String =
        "/bin/sh -lc ${shellQuote(command)}"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun toolLookup(binaryName: String, installedTools: Set<String>): ExecResult =
        if (binaryName in installedTools) {
            ExecResult("/home/u/.local/bin/$binaryName\n", "", 0)
        } else {
            ExecResult("", "", 1)
        }
}
