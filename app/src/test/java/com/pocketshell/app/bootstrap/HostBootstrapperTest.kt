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
    fun successSubtitleDoesNotClaimOptionalDaemonIsEnabled() {
        val subtitle = hostBootstrapSuccessSubtitle("devbox")

        assertEquals("devbox · tmux and the pocketshell CLI are ready.", subtitle)
        assertTrue(!subtitle.contains("daemon", ignoreCase = true))
        assertTrue(!subtitle.contains("enabled", ignoreCase = true))
    }

    @Test
    fun checkTmux_returnsInstalled_whenExitZero() = runTest {
        val session = FakeSshSession(
            mapOf(pathAware("command -v tmux") to ExecResult("/usr/bin/tmux\n", "", 0)),
        )
        val status = bootstrapper.checkTmux(session)
        assertEquals(TmuxStatus.Installed, status)
        assertEquals(listOf(detectPathCommand(), pathAware("command -v tmux")), session.recorded)
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
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue(report.isReady)
        assertEquals(PythonToolInstaller.Uv, report.installer)
        assertEquals("/home/u/.local/bin/uv", report.installerPath)
        assertTrue(report.daemon is PocketshellDaemonStatus.Running)
        assertEquals(MoshStatus.Unsupported(MOSH_UNSUPPORTED_REASON), report.mosh)
        assertTrue(session.recorded.none { it.contains("mosh", ignoreCase = true) })
    }

    @Test
    fun checkServerSetup_usesUserLocalPathForToolDetection() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/bin/pocketshell\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertEquals(
            ToolStatus.Installed("/home/u/bin/pocketshell"),
            report.tools[BootstrapTool.Pocketshell],
        )
        assertTrue(report.missingTools.isEmpty())
        assertTrue(report.isReady)
        assertTrue(session.recorded.all { command ->
            !command.contains("command -v '") ||
                command.startsWith("/bin/sh -lc ") &&
                command.contains(DEFAULT_BOOTSTRAP_PATH)
        })
    }

    @Test
    fun checkServerSetup_detectsPocketshellInCommonLocation_whenRemotePathMissesIt() = runTest {
        val session = FakeSshSession(
            dynamic = { command ->
                when {
                    command == pathAware("command -v 'pocketshell'") -> ExecResult("", "", 1)
                    command.contains("for p in") && command.contains("/pocketshell") ->
                        ExecResult("/home/u/.local/bin/pocketshell\n", "", 0)
                    command == pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    command == pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    command == systemdAware("systemctl --user is-active pocketshell-jobs.service") ->
                        ExecResult("active\n", "", 0)
                    command == systemdAware("systemctl --user is-enabled pocketshell-jobs.service") ->
                        ExecResult("enabled\n", "", 0)
                    else -> null
                }
            },
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue(report.isReady)
        assertEquals(
            ToolStatus.Installed("/home/u/.local/bin/pocketshell"),
            report.tools[BootstrapTool.Pocketshell],
        )
        assertTrue(session.recorded.any { it.contains("\$HOME/.local/bin/pocketshell") })
    }

    @Test
    fun parsePocketshellVersion_extractsClickVersionOutput() {
        assertEquals("0.3.7", bootstrapper.parsePocketshellVersion("pocketshell, version 0.3.7\n"))
        assertEquals("1.2.3", bootstrapper.parsePocketshellVersion("pocketshell 1.2.3\n"))
        assertNull(bootstrapper.parsePocketshellVersion("pocketshell dev build\n"))
    }

    @Test
    fun checkServerSetup_reportsReady_whenPocketshellVersionMatchesAppExpectation() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("'/home/u/.local/bin/pocketshell' --version") to ExecResult("pocketshell, version 0.3.7\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion = "0.3.7")

        assertTrue(report.isReady)
        assertEquals(
            ToolStatus.Installed(
                path = "/home/u/.local/bin/pocketshell",
                version = "0.3.7",
                expectedVersion = "0.3.7",
            ),
            report.tools[BootstrapTool.Pocketshell],
        )
        assertTrue(session.recorded.contains(pathAware("'/home/u/.local/bin/pocketshell' --version")))
    }

    @Test
    fun checkServerSetup_reportsMismatch_whenPocketshellVersionDiffersFromAppExpectation() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("'/home/u/.local/bin/pocketshell' --version") to ExecResult("pocketshell, version 0.3.6\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion = "0.3.7")

        assertTrue(!report.isReady)
        assertTrue(report.missingTools.isEmpty())
        assertEquals("/home/u/.local/bin/uv", report.installerPath)
        assertEquals(listOf(BootstrapTool.Pocketshell), report.versionMismatchedTools)
        assertEquals(
            ToolStatus.VersionMismatch(
                path = "/home/u/.local/bin/pocketshell",
                currentVersion = "0.3.6",
                expectedVersion = "0.3.7",
            ),
            report.tools[BootstrapTool.Pocketshell],
        )
    }

    @Test
    fun checkServerSetup_reportsAppUpdateRequired_whenRemotePocketshellIsNewerThanApp() = runTest {
        // Issue #514: remote CLI 0.3.23 is NEWER than the app's expected
        // 0.3.22. The host is fine; the APP is behind. This must NOT be a
        // VersionMismatch (which would loop the host installer forever).
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("'/home/u/.local/bin/pocketshell' --version") to ExecResult("pocketshell, version 0.3.23\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion = "0.3.22")

        val status = report.tools[BootstrapTool.Pocketshell]
        assertTrue(
            "remote-newer must NOT be VersionMismatch (avoids host installer loop)",
            status !is ToolStatus.VersionMismatch,
        )
        assertEquals(
            ToolStatus.AppUpdateRequired(
                path = "/home/u/.local/bin/pocketshell",
                currentVersion = "0.3.23",
                expectedVersion = "0.3.22",
            ),
            status,
        )
        // Host is fine: app-update-required does not block readiness and
        // must not appear in the host-installer mismatch set.
        assertTrue(report.versionMismatchedTools.isEmpty())
        assertTrue(report.isRequiredReady)
        assertEquals(
            ToolStatus.AppUpdateRequired(
                path = "/home/u/.local/bin/pocketshell",
                currentVersion = "0.3.23",
                expectedVersion = "0.3.22",
            ),
            report.pocketshellAppUpdateRequired,
        )
        // Issue #514 (DESIGN REFINEMENT): the remote-newer state must NOT
        // produce an actionable setup sheet — there is nothing to
        // install/upgrade on the host, and the presentation is a soft
        // dismissible banner, not a takeover sheet. So the report yields no
        // bootstrap-sheet rows and the host is ready (navigates normally).
        assertTrue(
            "remote-newer must not generate any actionable bootstrap-sheet rows",
            !report.hasBootstrapSheetRows(),
        )
        assertTrue("remote-newer host must be fully ready", report.isReady)
    }

    @Test
    fun checkServerSetup_reportsMismatch_whenRemotePocketshellIsOlderThanApp() = runTest {
        // Issue #514: remote CLI older than app expected → host genuinely
        // behind → keep VersionMismatch host-upgrade flow.
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("'/home/u/.local/bin/pocketshell' --version") to ExecResult("pocketshell, version 0.3.21\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion = "0.3.22")

        assertEquals(listOf(BootstrapTool.Pocketshell), report.versionMismatchedTools)
        assertEquals(
            ToolStatus.VersionMismatch(
                path = "/home/u/.local/bin/pocketshell",
                currentVersion = "0.3.21",
                expectedVersion = "0.3.22",
            ),
            report.tools[BootstrapTool.Pocketshell],
        )
        assertNull(report.pocketshellAppUpdateRequired)
        assertTrue(!report.isRequiredReady)
    }

    @Test
    fun checkServerSetup_treatsMultiDigitPatchAsNewer_notStringEqual() = runTest {
        // Issue #514: 0.3.10 > 0.3.9 numerically; string compare would put
        // "0.3.10" < "0.3.9". Expected 0.3.9, remote 0.3.10 → app behind.
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("'/home/u/.local/bin/pocketshell' --version") to ExecResult("pocketshell, version 0.3.10\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion = "0.3.9")

        assertEquals(
            ToolStatus.AppUpdateRequired(
                path = "/home/u/.local/bin/pocketshell",
                currentVersion = "0.3.10",
                expectedVersion = "0.3.9",
            ),
            report.tools[BootstrapTool.Pocketshell],
        )
        assertTrue(report.versionMismatchedTools.isEmpty())
    }

    @Test
    fun compareSemver_ordersNumericallyAndHandlesOddShapes() {
        assertEquals(0, compareSemver("0.3.22", "0.3.22"))
        assertTrue((compareSemver("0.3.21", "0.3.22") ?: 0) < 0)
        assertTrue((compareSemver("0.3.23", "0.3.22") ?: 0) > 0)
        assertTrue((compareSemver("0.3.10", "0.3.9") ?: 0) > 0)
        assertTrue((compareSemver("0.3.9", "0.3.10") ?: 0) < 0)
        assertTrue((compareSemver("1.0.0", "0.99.99") ?: 0) > 0)
        // Differing component counts: shorter is padded with zeros.
        assertEquals(0, compareSemver("0.3", "0.3.0"))
        assertTrue((compareSemver("0.3.1", "0.3") ?: 0) > 0)
        // Unparseable shapes → null (caller falls back to mismatch path).
        assertNull(compareSemver("dev", "0.3.22"))
        assertNull(compareSemver("0.3.22", ""))
        assertNull(compareSemver("0.3.x", "0.3.22"))
    }

    @Test
    fun checkServerSetup_wrapsBootstrapCommandsInPosixShell() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue(report.isReady)
        assertTrue(session.recorded.all { it.startsWith("/bin/sh -lc ") })
        assertTrue(session.recorded.any { it.contains("__POCKETSHELL_PATH_BEGIN__") })
        assertTrue(session.recorded.any { it.contains(DEFAULT_BOOTSTRAP_PATH) })
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
            daemon = PocketshellDaemonStatus.Running(enabled = true),
        )

        assertTrue(report.isReady)
        assertTrue(!report.hasBootstrapSheetRows())
        assertTrue(!HostBootstrapSheetState.Prompt(needsTmux = false, report = report).hasActionableSetup())
    }

    @Test
    fun bootstrapPromptDoesNotTreatRunningButDisabledDaemonAsRequiredSetup() {
        val report = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith {
                ToolStatus.Installed("/home/u/.local/bin/${it.binaryName}")
            },
            installer = PythonToolInstaller.Uv,
            daemon = PocketshellDaemonStatus.Running(enabled = false),
        )

        assertTrue(report.needsPocketshellDaemonAction())
        assertTrue(!report.hasBootstrapSheetRows())
        assertTrue(!HostBootstrapSheetState.Prompt(needsTmux = false, report = report).hasActionableSetup())
    }

    @Test
    fun versionMismatchDetail_includesVersionsPathsInstallerAndManualCommand() {
        val mismatch = ToolStatus.VersionMismatch(
            path = "/home/u/.local/bin/pocketshell",
            currentVersion = "0.1.0",
            expectedVersion = "0.3.7",
        )

        val detail = versionMismatchDetail(
            installer = PythonToolInstaller.Uv,
            mismatch = mismatch,
            installerPath = "/home/u/.local/bin/uv",
        )

        assertTrue(detail.contains("/home/u/.local/bin/pocketshell"))
        assertTrue(detail.contains("remote 0.1.0"))
        assertTrue(detail.contains("expected 0.3.7"))
        assertTrue(detail.contains("uv at /home/u/.local/bin/uv"))
        assertTrue(detail.contains(UV_POCKETSHELL_UPGRADE_COMMAND))
    }

    @Test
    fun cliUpdateFailureMessage_includesManualGuidanceAndExitReason() {
        val mismatch = ToolStatus.VersionMismatch(
            path = "/home/u/.local/bin/pocketshell",
            currentVersion = "0.1.0",
            expectedVersion = "0.3.7",
        )

        val message = cliUpdateFailureMessage(
            mismatch = mismatch,
            installer = PythonToolInstaller.Pipx,
            stderr = "pipx: package is not installed",
            exitCode = 1,
        )

        assertTrue(message.contains("exit 1"))
        assertTrue(message.contains("Remote: 0.1.0"))
        assertTrue(message.contains("Expected: 0.3.7"))
        assertTrue(message.contains("Path: /home/u/.local/bin/pocketshell"))
        assertTrue(message.contains("pipx: package is not installed"))
        assertTrue(message.contains("pipx upgrade pocketshell"))
        assertTrue(message.contains("~/.local/bin"))
    }

    @Test
    fun cliUpdateNoChangeMessage_explainsTheNoOpUpgradeAndNamesVersions() {
        // Issue #779: the update exited 0 but the version is unchanged. The
        // message must NOT be silent — it names the still-installed version,
        // the expected version, the likely cause, and the manual command.
        val mismatch = ToolStatus.VersionMismatch(
            path = "/home/u/.local/bin/pocketshell",
            currentVersion = "0.3.33",
            expectedVersion = "0.4.1",
        )

        val message = cliUpdateNoChangeMessage(
            mismatch = mismatch,
            installer = PythonToolInstaller.Uv,
        )

        assertTrue(message.contains("did not change the installed version"))
        assertTrue(message.contains("still reports 0.3.33"))
        assertTrue(message.contains("needs 0.4.1"))
        assertTrue(message.contains("/home/u/.local/bin/pocketshell"))
        assertTrue(message.contains("nothing newer to install"))
        // The retry command is the exclude-newer-bypassing form.
        assertTrue(message.contains(UV_POCKETSHELL_UPGRADE_COMMAND))
    }

    @Test
    fun uvPocketshellCommands_includeTargetedExcludeNewerOverride() {
        assertEquals(
            "uv tool install --exclude-newer-package pocketshell=2099-12-31 pocketshell",
            uvToolInstallCommand(BootstrapTool.Pocketshell, upgrade = false),
        )
        assertEquals(
            "uv tool install --upgrade --exclude-newer-package pocketshell=2099-12-31 pocketshell",
            uvToolInstallCommand(BootstrapTool.Pocketshell, upgrade = true),
        )
        assertTrue(!upgradeCommand(PythonToolInstaller.Pipx, BootstrapTool.Pocketshell).contains("exclude-newer"))
    }

    @Test
    fun checkServerSetup_prefersUvButFallsBackToPipx() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("", "", 1),
                pathAware("command -v 'uv'") to ExecResult("", "", 1),
                pathAware("command -v 'pipx'") to ExecResult("/usr/bin/pipx\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertEquals(PythonToolInstaller.Pipx, report.installer)
        assertEquals(BootstrapTool.entries, report.missingTools)
        assertTrue(report.daemon is PocketshellDaemonStatus.Missing)
    }

    @Test
    fun installServerSetup_usesUvToolForMissingRequiredTools_withoutInstallingSystemdUserUnit() = runTest {
        val installedTools = mutableSetOf<String>()
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'pocketshell'") -> toolLookup("pocketshell", installedTools)
                    pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    pathAware("'/home/u/.local/bin/uv' $UV_POCKETSHELL_INSTALL_ARGS") -> {
                        installedTools += "pocketshell"
                        ExecResult("installed pocketshell\n", "", 0)
                    }
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active pocketshell-jobs.service") -> ExecResult("inactive\n", "", 3)
                    systemdAware("systemctl --user is-enabled pocketshell-jobs.service") -> ExecResult("disabled\n", "", 1)
                    else -> if (command.contains("systemctl --user enable --now pocketshell-jobs.service")) {
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
            daemon = PocketshellDaemonStatus.Missing,
        )

        val result = bootstrapper.installServerSetup(session, report)

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains(pathAware("'/home/u/.local/bin/uv' $UV_POCKETSHELL_INSTALL_ARGS")))
        assertTrue(session.recorded.none { it.contains("ExecStart=\"/home/u/.local/bin/pocketshell\" jobs daemon") })
        assertTrue(session.recorded.none { it.contains("systemctl --user enable --now pocketshell-jobs.service") })
    }

    @Test
    fun installServerTool_usesPipxWithPathAwareLookup() = runTest {
        val session = FakeSshSession(
            mapOf(pathAware("pipx install pocketshell") to ExecResult("installed pocketshell\n", "", 0)),
        )

        val result = bootstrapper.installServerTool(session, PythonToolInstaller.Pipx, BootstrapTool.Pocketshell)

        assertEquals(InstallResult.Success, result)
        assertEquals(listOf(detectPathCommand(), pathAware("pipx install pocketshell")), session.recorded)
    }

    @Test
    fun checkServerSetup_reportsUnavailable_whenSystemdUserBusIsUnavailable() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to
                    ExecResult("", "Failed to connect to bus: No medium found\n", 1),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue(report.daemon is PocketshellDaemonStatus.Unavailable)
        assertTrue((report.daemon as PocketshellDaemonStatus.Unavailable).reason.contains("No medium"))
    }

    @Test
    fun checkServerSetup_reportsRunningDisabledDaemon() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("disabled\n", "", 1),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertEquals(PocketshellDaemonStatus.Running(enabled = false), report.daemon)
        assertTrue(report.isRequiredReady)
    }

    @Test
    fun checkServerSetup_reportsInstalledStoppedDisabledDaemon() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("inactive\n", "", 3),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("disabled\n", "", 1),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertEquals(PocketshellDaemonStatus.InstalledStopped(enabled = false), report.daemon)
        assertTrue(report.isRequiredReady)
    }

    @Test
    fun checkPocketshellDaemon_reportsUnknown_whenStatusProbeThrows() = runTest {
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active pocketshell-jobs.service") -> throw SshException("channel closed")
                    else -> null
                }
            },
        )

        val status = bootstrapper.checkPocketshellDaemon(session)

        assertTrue(status is PocketshellDaemonStatus.Unknown)
        assertTrue((status as PocketshellDaemonStatus.Unknown).reason.contains("channel closed"))
    }

    @Test
    fun installServerSetup_doesNotEnableOptionalDaemon_whenRunningButDisabled() = runTest {
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'pocketshell'") -> ExecResult("/home/u/.local/bin/pocketshell\n", "", 0)
                    pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active pocketshell-jobs.service") -> ExecResult("active\n", "", 0)
                    systemdAware("systemctl --user is-enabled pocketshell-jobs.service") -> ExecResult("disabled\n", "", 1)
                    else -> if (command.contains("systemctl --user enable --now pocketshell-jobs.service")) {
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
            daemon = PocketshellDaemonStatus.Running(enabled = false),
        )

        val result = bootstrapper.installServerSetup(session, report)

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.none { it.contains("systemctl --user enable --now pocketshell-jobs.service") })
    }

    @Test
    fun installServerSetup_failsWhenPythonToolInstallerIsMissing() = runTest {
        val report = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith { ToolStatus.Missing },
            installer = null,
            daemon = PocketshellDaemonStatus.Missing,
        )
        val result = bootstrapper.installServerSetup(FakeSshSession(), report)

        assertTrue(result is InstallResult.Error)
        val reason = (result as InstallResult.Error).reason
        assertTrue(reason.contains("uv or pipx"))
        assertTrue(reason.contains("uv $UV_POCKETSHELL_INSTALL_ARGS"))
        assertTrue(reason.contains("pipx install pocketshell"))
    }

    @Test
    fun installServerSetup_missingInstallerForMismatchShowsUvCutoffUpgradeGuidance() = runTest {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("/home/u/.local/bin/pocketshell\n", "", 0),
                pathAware("'/home/u/.local/bin/pocketshell' --version") to
                    ExecResult("pocketshell, version 0.1.0\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("", "", 1),
                pathAware("command -v 'pipx'") to ExecResult("", "", 1),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to ExecResult("enabled\n", "", 0),
            ),
        )
        val report = HostBootstrapReport(
            tools = mapOf(
                BootstrapTool.Pocketshell to ToolStatus.VersionMismatch(
                    path = "/home/u/.local/bin/pocketshell",
                    currentVersion = "0.1.0",
                    expectedVersion = "0.3.7",
                ),
            ),
            installer = null,
            daemon = PocketshellDaemonStatus.Running(enabled = true),
        )

        val result = bootstrapper.installServerSetup(
            session,
            report,
            expectedPocketshellVersion = "0.3.7",
        )

        assertTrue(result is InstallResult.Error)
        val reason = (result as InstallResult.Error).reason
        assertTrue(reason.contains("uv $UV_POCKETSHELL_UPGRADE_ARGS"))
        assertTrue(reason.contains("pipx upgrade pocketshell"))
    }

    @Test
    fun pathAwareCommand_usesDefaultUserBins_whenDetectedPathIsMissing() {
        val expected = shell(
            "PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"; export PATH; command -v 'pocketshell'",
        )
        assertEquals(expected, bootstrapper.pathAwareCommand("command -v 'pocketshell'", bootstrapPath = null))
        assertEquals(expected, bootstrapper.pathAwareCommand("command -v 'pocketshell'", bootstrapPath = ""))
        assertEquals(expected, bootstrapper.pathAwareCommand("command -v 'pocketshell'", bootstrapPath = "   "))
    }

    @Test
    fun detectBootstrapPathCommand_sourcesRemoteShellRcAndPrependsDefaultUserBins() {
        val command = bootstrapper.detectBootstrapPathCommand()
        assertTrue(command.startsWith("/bin/sh -lc "))
        assertTrue(command.contains("__pocketshell_shell="))
        assertTrue(command.contains("bash)"))
        assertTrue(command.contains("zsh)"))
        assertTrue(command.contains("fish)"))
        assertTrue(command.contains("__POCKETSHELL_PATH_BEGIN__"))
        assertTrue(command.contains(".local/bin"))
        assertTrue(command.contains(".cargo/bin"))
    }

    @Test
    fun parseDetectedBootstrapPath_ignoresRcNoiseAroundSentinels() {
        assertEquals(
            "/home/u/.local/bin:/home/u/git/pocketshell/.venv/bin:/usr/bin",
            bootstrapper.parseDetectedBootstrapPath(
                "hello from rc\n__POCKETSHELL_PATH_BEGIN__\n" +
                    "/home/u/.local/bin:/home/u/git/pocketshell/.venv/bin:/usr/bin\n" +
                    "__POCKETSHELL_PATH_END__\n",
            ),
        )
    }

    @Test
    fun checkTool_returnsInstalled_whenRcDerivedPathResolvesTheBinary() = runTest {
        val rcPath = "/home/u/.local/bin:/home/u/git/pocketshell/.venv/bin:/usr/bin:/bin"
        val session = FakeSshSession(
            mapOf(
                detectPathCommand() to detectedPathResult(rcPath),
                pathAwareWithDetectedPath("command -v 'pocketshell'", rcPath) to
                    ExecResult("/home/u/git/pocketshell/.venv/bin/pocketshell\n", "", 0),
            ),
        )

        val installed = bootstrapper.checkTool(session, "pocketshell")
        assertTrue(installed is ToolStatus.Installed)
        assertEquals("/home/u/git/pocketshell/.venv/bin/pocketshell", (installed as ToolStatus.Installed).path)
    }

    @Test
    fun checkServerSetup_forwardsRcDerivedPath_toEveryToolProbe() = runTest {
        val rcPath = "/home/u/.local/bin:/home/u/bin:/home/u/.cargo/bin:/home/u/git/pocketshell/.venv/bin:/usr/bin:/bin"
        val session = FakeSshSession(
            mapOf(
                detectPathCommand() to detectedPathResult(rcPath),
                pathAwareWithDetectedPath("command -v 'pocketshell'", rcPath) to
                    ExecResult("/home/u/git/pocketshell/.venv/bin/pocketshell\n", "", 0),
                pathAwareWithDetectedPath("command -v 'uv'", rcPath) to
                    ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAwareWithDetectedPath("command -v 'systemctl'", rcPath) to
                    ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAwareWithDetectedPath("systemctl --user is-active pocketshell-jobs.service", rcPath) to
                    ExecResult("active\n", "", 0),
                systemdAwareWithDetectedPath("systemctl --user is-enabled pocketshell-jobs.service", rcPath) to
                    ExecResult("enabled\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue(report.isReady)
        assertEquals(
            ToolStatus.Installed("/home/u/git/pocketshell/.venv/bin/pocketshell"),
            report.tools[BootstrapTool.Pocketshell],
        )
        assertTrue(
            "expected every probe wrapper to carry the rc-derived PATH",
            session.recorded
                .filter { it.contains("command -v '") || it.contains("systemctl --user") }
                .all { it.contains(rcPath) },
        )
    }

    // ---- Issue #231 (D22 hard cut): unified `pocketshell` required tool. ----

    @Test
    fun checkServerSetup_reportsNotReadyAndMissing_whenPocketshellAbsent() = runTest {
        // Hard-cut invariant: `pocketshell` is now the single required
        // server tool. A host without it must NOT be ready and must
        // surface `pocketshell` in the required-tools missingTools set so
        // the bootstrap sheet offers to install it.
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("", "", 1),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
            ),
        )

        val report = bootstrapper.checkServerSetup(session)

        assertTrue("pocketshell missing must block isReady", !report.isReady)
        assertEquals(listOf(BootstrapTool.Pocketshell), report.missingTools)
        assertEquals(ToolStatus.Missing, report.tools[BootstrapTool.Pocketshell])
        // Daemon is not probed when pocketshell is absent — short-circuit
        // to Missing without a systemctl --user query.
        assertTrue(report.daemon is PocketshellDaemonStatus.Missing)
        assertTrue(session.recorded.none { it.contains("is-active") })
    }

    @Test
    fun installServerSetup_reprobesBeforeInstallingStaleMissingTools() = runTest {
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'pocketshell'") -> ExecResult("/home/u/.local/bin/pocketshell\n", "", 0)
                    pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active pocketshell-jobs.service") -> ExecResult("active\n", "", 0)
                    systemdAware("systemctl --user is-enabled pocketshell-jobs.service") -> ExecResult("enabled\n", "", 0)
                    else -> null
                }
            },
        )
        val staleReport = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith { ToolStatus.Missing },
            installer = PythonToolInstaller.Uv,
            daemon = PocketshellDaemonStatus.Missing,
        )

        val result = bootstrapper.installServerSetup(session, staleReport)

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.none { it.contains("uv tool install") })
        assertTrue(session.recorded.none { it.contains("systemctl --user enable --now") })
    }

    @Test
    fun installServerSetup_upgradesMismatchedPocketshellWithUv() = runTest {
        var upgraded = false
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'pocketshell'") -> ExecResult("/home/u/.local/bin/pocketshell\n", "", 0)
                    pathAware("'/home/u/.local/bin/pocketshell' --version") -> ExecResult(
                        "pocketshell, version ${if (upgraded) "0.3.7" else "0.3.6"}\n",
                        "",
                        0,
                    )
                    pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    pathAware("'/home/u/.local/bin/uv' $UV_POCKETSHELL_UPGRADE_ARGS") -> {
                        upgraded = true
                        ExecResult("upgraded pocketshell\n", "", 0)
                    }
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active pocketshell-jobs.service") -> ExecResult("active\n", "", 0)
                    systemdAware("systemctl --user is-enabled pocketshell-jobs.service") -> ExecResult("enabled\n", "", 0)
                    else -> null
                }
            },
        )
        val report = HostBootstrapReport(
            tools = mapOf(
                BootstrapTool.Pocketshell to ToolStatus.VersionMismatch(
                    path = "/home/u/.local/bin/pocketshell",
                    currentVersion = "0.3.6",
                    expectedVersion = "0.3.7",
                ),
            ),
            installer = PythonToolInstaller.Uv,
            daemon = PocketshellDaemonStatus.Running(enabled = true),
        )

        val result = bootstrapper.installServerSetup(
            session,
            report,
            expectedPocketshellVersion = "0.3.7",
        )

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains(pathAware("'/home/u/.local/bin/uv' $UV_POCKETSHELL_UPGRADE_ARGS")))
        assertTrue(session.recorded.none { it.contains("uv tool upgrade pocketshell") })
    }

    @Test
    fun installServerSetup_usesFallbackUvPathForMismatchedPocketshellUpgrade() = runTest {
        var upgraded = false
        val session = FakeSshSession(
            dynamic = { command ->
                when {
                    command == pathAware("command -v 'pocketshell'") ->
                        ExecResult("/home/u/.local/bin/pocketshell\n", "", 0)
                    command == pathAware("'/home/u/.local/bin/pocketshell' --version") ->
                        ExecResult("pocketshell, version ${if (upgraded) "0.3.7" else "0.1.0"}\n", "", 0)
                    command == pathAware("command -v 'uv'") ->
                        ExecResult("", "", 1)
                    command.contains("for p in") && command.contains("/uv") ->
                        ExecResult("/opt/homebrew/bin/uv\n", "", 0)
                    command == pathAware("'/opt/homebrew/bin/uv' $UV_POCKETSHELL_UPGRADE_ARGS") -> {
                        upgraded = true
                        ExecResult("upgraded pocketshell\n", "", 0)
                    }
                    command == pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    command == systemdAware("systemctl --user is-active pocketshell-jobs.service") ->
                        ExecResult("active\n", "", 0)
                    command == systemdAware("systemctl --user is-enabled pocketshell-jobs.service") ->
                        ExecResult("enabled\n", "", 0)
                    else -> null
                }
            },
        )
        val report = HostBootstrapReport(
            tools = mapOf(
                BootstrapTool.Pocketshell to ToolStatus.VersionMismatch(
                    path = "/home/u/.local/bin/pocketshell",
                    currentVersion = "0.1.0",
                    expectedVersion = "0.3.7",
                ),
            ),
            installer = PythonToolInstaller.Uv,
            daemon = PocketshellDaemonStatus.Running(enabled = true),
        )

        val result = bootstrapper.installServerSetup(
            session,
            report,
            expectedPocketshellVersion = "0.3.7",
        )

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains(pathAware("'/opt/homebrew/bin/uv' $UV_POCKETSHELL_UPGRADE_ARGS")))
        assertTrue(session.recorded.none { it.contains("PATH=") && it.contains("; uv tool install --upgrade") })
    }

    @Test
    fun installServerSetup_usesFallbackPipxPathForMissingPocketshellInstall() = runTest {
        var installed = false
        val session = FakeSshSession(
            dynamic = { command ->
                when {
                    command == pathAware("command -v 'pocketshell'") ->
                        if (installed) {
                            ExecResult("/home/u/.local/bin/pocketshell\n", "", 0)
                        } else {
                            ExecResult("", "", 1)
                        }
                    command.contains("for p in") && command.contains("/pocketshell") ->
                        ExecResult("", "", 1)
                    command == pathAware("command -v 'uv'") ->
                        ExecResult("", "", 1)
                    command.contains("for p in") && command.contains("/uv") ->
                        ExecResult("", "", 1)
                    command == pathAware("command -v 'pipx'") ->
                        ExecResult("", "", 1)
                    command.contains("for p in") && command.contains("/pipx") ->
                        ExecResult("/usr/local/bin/pipx\n", "", 0)
                    command == pathAware("'/usr/local/bin/pipx' install pocketshell") -> {
                        installed = true
                        ExecResult("installed pocketshell\n", "", 0)
                    }
                    command == pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    command == systemdAware("systemctl --user is-active pocketshell-jobs.service") ->
                        ExecResult("active\n", "", 0)
                    command == systemdAware("systemctl --user is-enabled pocketshell-jobs.service") ->
                        ExecResult("enabled\n", "", 0)
                    else -> null
                }
            },
        )
        val report = HostBootstrapReport(
            tools = BootstrapTool.entries.associateWith { ToolStatus.Missing },
            installer = PythonToolInstaller.Pipx,
            daemon = PocketshellDaemonStatus.Missing,
        )

        val result = bootstrapper.installServerSetup(session, report)

        assertEquals(InstallResult.Success, result)
        assertTrue(session.recorded.contains(pathAware("'/usr/local/bin/pipx' install pocketshell")))
        assertTrue(session.recorded.none { it.contains("PATH=") && it.contains("; pipx install") })
    }

    @Test
    fun installServerSetup_returnsSetupIncomplete_whenPocketshellStillMismatchedAfterUpgrade() = runTest {
        val session = FakeSshSession(
            dynamic = { command ->
                when (command) {
                    pathAware("command -v 'pocketshell'") -> ExecResult("/home/u/.local/bin/pocketshell\n", "", 0)
                    pathAware("'/home/u/.local/bin/pocketshell' --version") -> ExecResult("pocketshell, version 0.3.6\n", "", 0)
                    pathAware("command -v 'uv'") -> ExecResult("/home/u/.local/bin/uv\n", "", 0)
                    pathAware("'/home/u/.local/bin/uv' $UV_POCKETSHELL_UPGRADE_ARGS") ->
                        ExecResult("upgraded pocketshell\n", "", 0)
                    pathAware("command -v 'systemctl'") -> ExecResult("/usr/bin/systemctl\n", "", 0)
                    systemdAware("systemctl --user is-active pocketshell-jobs.service") -> ExecResult("active\n", "", 0)
                    systemdAware("systemctl --user is-enabled pocketshell-jobs.service") -> ExecResult("enabled\n", "", 0)
                    else -> null
                }
            },
        )
        val report = HostBootstrapReport(
            tools = mapOf(
                BootstrapTool.Pocketshell to ToolStatus.VersionMismatch(
                    path = "/home/u/.local/bin/pocketshell",
                    currentVersion = "0.3.6",
                    expectedVersion = "0.3.7",
                ),
            ),
            installer = PythonToolInstaller.Uv,
            daemon = PocketshellDaemonStatus.Running(enabled = true),
        )

        val result = bootstrapper.installServerSetup(
            session,
            report,
            expectedPocketshellVersion = "0.3.7",
        )

        assertTrue(result is InstallResult.SetupIncomplete)
        val incomplete = result as InstallResult.SetupIncomplete
        assertEquals(listOf(BootstrapTool.Pocketshell), incomplete.report.versionMismatchedTools)
        assertTrue(incomplete.reason.contains("not app-compatible"))
        assertTrue(session.recorded.contains(pathAware("'/home/u/.local/bin/uv' $UV_POCKETSHELL_UPGRADE_ARGS")))
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
                ?: if (command == HostBootstrapper().detectBootstrapPathCommand()) {
                    ExecResult(
                        "__POCKETSHELL_PATH_BEGIN__\n$DEFAULT_BOOTSTRAP_PATH\n__POCKETSHELL_PATH_END__\n",
                        "",
                        0,
                    )
                } else {
                    null
                }
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

    private fun detectPathCommand(): String =
        HostBootstrapper().detectBootstrapPathCommand()

    private fun detectedPathResult(path: String = DEFAULT_BOOTSTRAP_PATH): ExecResult =
        ExecResult("__POCKETSHELL_PATH_BEGIN__\n$path\n__POCKETSHELL_PATH_END__\n", "", 0)

    private fun pathAware(command: String): String =
        pathAwareWithDetectedPath(command, DEFAULT_BOOTSTRAP_PATH)

    private fun pathAwareWithDetectedPath(command: String, detectedPath: String): String =
        shell("PATH=${shellQuote(detectedPath)}; export PATH; $command")

    private fun systemdAware(command: String): String =
        systemdAwareWithDetectedPath(command, DEFAULT_BOOTSTRAP_PATH)

    private fun systemdAwareWithDetectedPath(command: String, detectedPath: String): String =
        shell(
            "PATH=${shellQuote(detectedPath)}; export PATH; " +
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

    private companion object {
        const val UV_POCKETSHELL_INSTALL_ARGS: String =
            "tool install --exclude-newer-package pocketshell=2099-12-31 pocketshell"
        const val UV_POCKETSHELL_UPGRADE_ARGS: String =
            "tool install --upgrade --exclude-newer-package pocketshell=2099-12-31 pocketshell"
        const val UV_POCKETSHELL_UPGRADE_COMMAND: String = "uv $UV_POCKETSHELL_UPGRADE_ARGS"
        const val DEFAULT_BOOTSTRAP_PATH: String =
            "/home/u/.local/bin:/home/u/bin:/home/u/.cargo/bin:/usr/local/bin:/usr/bin:/bin"
    }
}
