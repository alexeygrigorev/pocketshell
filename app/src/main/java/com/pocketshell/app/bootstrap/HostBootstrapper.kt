package com.pocketshell.app.bootstrap

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession

/**
 * Detection result for the tmux presence probe.
 *
 * - [Installed] — `command -v tmux` returned exit 0.
 * - [Missing] — `command -v tmux` returned non-zero exit (POSIX semantics:
 *   command not found).
 * - [Unknown] — the probe itself failed (transport error, channel closed,
 *   etc.). The reason string is carried so callers can surface it for
 *   debugging; the bootstrap sheet treats `Unknown` the same as
 *   "skip" — we cannot prove tmux is missing without a successful probe.
 */
public sealed interface TmuxStatus {
    public data object Installed : TmuxStatus
    public data object Missing : TmuxStatus
    public data class Unknown(val reason: String) : TmuxStatus
}

/**
 * Outcome of the `installTmux` flow.
 *
 * - [Success] — the package manager command returned exit 0. The
 *   bootstrapper does NOT re-run `command -v tmux` after install; the
 *   caller can call [HostBootstrapper.checkTmux] again if it wants
 *   confirmation. Empirically `apt-get install -y tmux` failing silently
 *   on a "fully up to date" system would still leave tmux present, so
 *   the exit code is the trustworthy signal.
 * - [Failed] — non-zero exit. `stderr` and `exitCode` surface verbatim so
 *   the sheet can render the real error to the user.
 * - [UnsupportedOs] — `/etc/os-release` did not match any package manager
 *   we know about. The detected OS id (if any) is carried.
 * - [Error] — transport-level failure mid-install.
 */
public sealed interface InstallResult {
    public data object Success : InstallResult
    public data class Failed(val stderr: String, val exitCode: Int) : InstallResult
    public data class UnsupportedOs(val osId: String?) : InstallResult
    public data class Error(val reason: String) : InstallResult
}

public enum class BootstrapTool(
    public val binaryName: String,
    public val packageName: String = binaryName,
) {
    // Issue #231 (D22 hard cut): the unified `pocketshell` CLI replaces
    // the legacy `tmuxctl` + `quse` binaries. There is exactly one
    // required server-side tool now — its `usage` / `sessions` / `jobs`
    // subcommands cover everything the two old tools did.
    Pocketshell(binaryName = "pocketshell"),
}

public enum class PythonToolInstaller(
    public val binaryName: String,
) {
    Uv("uv"),
    Pipx("pipx"),
}

public sealed interface ToolStatus {
    public data class Installed(
        val path: String,
        val version: String? = null,
        val expectedVersion: String? = null,
    ) : ToolStatus
    public data class VersionMismatch(
        val path: String,
        val currentVersion: String,
        val expectedVersion: String,
    ) : ToolStatus
    public data object Missing : ToolStatus
    public data class Unknown(val reason: String) : ToolStatus
}

public sealed interface PocketshellDaemonStatus {
    public data class Running(val enabled: Boolean) : PocketshellDaemonStatus
    public data class InstalledStopped(val enabled: Boolean) : PocketshellDaemonStatus
    public data object Missing : PocketshellDaemonStatus
    public data class Unavailable(val reason: String) : PocketshellDaemonStatus
    public data class Unknown(val reason: String) : PocketshellDaemonStatus
}

/**
 * Bootstrap-report slot for Mosh status on a remote host.
 *
 * Kept on the data model even though the bootstrap sheet does not render a
 * Mosh row anymore. The research spike for issue
 * [#159](https://github.com/alexeygrigorev/pocketshell/issues/159) returned
 * NO-GO because Mosh's display-replication protocol is incompatible with the
 * `tmux -CC` control mode PocketShell relies on for per-pane rendering, so we
 * are not shipping Mosh support. Issue #164 then removed the dead UI row but
 * intentionally left this type, the always-`Unsupported` default, and
 * [MOSH_UNSUPPORTED_REASON] in place so re-adding the row later (if the
 * upstream Mosh story ever changes) stays a one-file UI change instead of a
 * model + UI change.
 */
public sealed interface MoshStatus {
    public data class Unsupported(val reason: String) : MoshStatus
}

public const val MOSH_UNSUPPORTED_REASON: String =
    "Mosh is deferred. PocketShell currently uses SSH over TCP via sshj; real Mosh support needs UDP transport and a mosh-server binary on the host."

public data class HostBootstrapReport(
    val tools: Map<BootstrapTool, ToolStatus>,
    val installer: PythonToolInstaller?,
    val daemon: PocketshellDaemonStatus,
    val mosh: MoshStatus = MoshStatus.Unsupported(MOSH_UNSUPPORTED_REASON),
) {
    public val missingTools: List<BootstrapTool>
        get() = BootstrapTool.entries.filter { tools[it] is ToolStatus.Missing }

    public val unknownTools: List<BootstrapTool>
        get() = BootstrapTool.entries.filter { tools[it] is ToolStatus.Unknown }

    public val versionMismatchedTools: List<BootstrapTool>
        get() = BootstrapTool.entries.filter { tools[it] is ToolStatus.VersionMismatch }

    public val pocketshellVersionMismatch: ToolStatus.VersionMismatch?
        get() = tools[BootstrapTool.Pocketshell] as? ToolStatus.VersionMismatch

    public val isReady: Boolean
        get() = missingTools.isEmpty() &&
            unknownTools.isEmpty() &&
            versionMismatchedTools.isEmpty() &&
            daemon is PocketshellDaemonStatus.Running &&
            daemon.enabled
}

/**
 * Detects whether `tmux` is installed on a remote host and offers a
 * best-effort one-tap install.
 *
 * Lives in `app/` (not `core-ssh`) because the install command set is a
 * UX policy — "what does a one-tap install mean per OS family?" — rather
 * than a transport-level concern. The probe + install are both stateless
 * `SshSession.exec` calls, so the class itself has no fields and is safe
 * to construct with `@Inject` (Hilt will hand it out per request).
 *
 * The 24h cache + sheet wiring lives in
 * [com.pocketshell.app.hosts.HostListViewModel]; this class is a pure
 * SSH-shaped helper so it can be unit-tested against a fake [SshSession]
 * without standing up a connection.
 *
 * Acceptance criteria mapping (issue #49):
 *
 * - "checkTmux returns Installed/Missing/Unknown" — [checkTmux]
 * - "installTmux detects OS and runs the right package manager command" —
 *   [installTmux] via [detectPackageManager]
 *
 * The bootstrap sheet, cache, and re-check semantics live in the
 * ViewModel layer — the bootstrapper itself is intentionally minimal so
 * it remains a thin SSH helper with no Android dependencies.
 */
public class HostBootstrapper @javax.inject.Inject constructor() {

    /**
     * Run `command -v tmux` on [session] and return whether tmux is on the
     * remote PATH.
     *
     * `command -v` is the POSIX-portable replacement for `which` /
     * `type` — it's a shell builtin on bash / dash / zsh / ash (Alpine)
     * and returns exit 0 with the resolved path on stdout when present,
     * exit 1 with empty stdout when absent. Empty stdout is treated as a
     * defensive backstop in case some shell surfaces a non-fatal warning
     * but still exits 0.
     *
     * Issue #294: before the probe runs, PocketShell asks the remote
     * user's configured shell for its interactive rc-derived PATH. The
     * wrapper then prepends PocketShell's default user-bin locations to
     * that effective PATH, so `~/.local/bin` / `~/bin` / `~/.cargo/bin`
     * keep winning while `.bashrc` / `.zshrc` / fish config additions are
     * still visible without a manual app setting.
     */
    public suspend fun checkTmux(session: SshSession): TmuxStatus = try {
        val bootstrapPath = detectBootstrapPath(session)
        val result = session.exec(pathAwareCommand("command -v tmux", bootstrapPath))
        when {
            result.exitCode == 0 && result.stdout.isNotBlank() -> TmuxStatus.Installed
            // Non-zero exit is the POSIX "command not found" signal.
            result.exitCode != 0 -> TmuxStatus.Missing
            // Exit 0 with empty stdout is unexpected — treat as Missing
            // rather than Installed; the user can re-trigger via the
            // sheet if they're sure.
            else -> TmuxStatus.Missing
        }
    } catch (e: SshException) {
        TmuxStatus.Unknown(e.message ?: e.javaClass.simpleName)
    } catch (t: Throwable) {
        TmuxStatus.Unknown("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    public suspend fun checkServerSetup(
        session: SshSession,
        expectedPocketshellVersion: String? = null,
    ): HostBootstrapReport {
        val bootstrapPath = detectBootstrapPath(session)
        val tools = BootstrapTool.entries.associateWith { tool ->
            checkBootstrapToolWithPath(session, tool, bootstrapPath, expectedPocketshellVersion)
        }
        val installer = detectPythonToolInstaller(session, bootstrapPath)
        val daemon = if (tools[BootstrapTool.Pocketshell].isPresentOnRemote()) {
            checkPocketshellDaemon(session, bootstrapPath)
        } else {
            PocketshellDaemonStatus.Missing
        }
        return HostBootstrapReport(
            tools = tools,
            installer = installer,
            daemon = daemon,
            mosh = MoshStatus.Unsupported(MOSH_UNSUPPORTED_REASON),
        )
    }

    public suspend fun installServerSetup(
        session: SshSession,
        report: HostBootstrapReport? = null,
        expectedPocketshellVersion: String? = null,
    ): InstallResult {
        val bootstrapPath = detectBootstrapPath(session)
        val currentReport = freshenReport(session, report, bootstrapPath, expectedPocketshellVersion)
        if (currentReport.unknownTools.isNotEmpty()) {
            val unknown = currentReport.unknownTools.joinToString { it.binaryName }
            return InstallResult.Error("Could not detect required host tools: $unknown. Reconnect and try again.")
        }
        val mismatchedTools = currentReport.versionMismatchedTools
        if (mismatchedTools.isNotEmpty()) {
            val installer = currentReport.installer ?: return InstallResult.Error(
                "PocketShell found pocketshell on the host, but it is not app-compatible. Upgrade it with `uv tool upgrade pocketshell` or `pipx upgrade pocketshell`, then reconnect.",
            )
            for (tool in mismatchedTools) {
                val result = upgradeServerTool(session, installer, tool, bootstrapPath)
                if (result !is InstallResult.Success) return result
            }
        }
        val missingTools = currentReport.missingTools
        if (missingTools.isNotEmpty()) {
            val installer = currentReport.installer ?: return InstallResult.Error(
                "Install uv or pipx on the host, then reconnect. PocketShell uses one of them to install pocketshell.",
            )
            for (tool in missingTools) {
                val result = installServerTool(session, installer, tool, bootstrapPath)
                if (result !is InstallResult.Success) return result
            }
        }

        val afterTools = checkServerSetup(session, expectedPocketshellVersion)
        val daemon = afterTools.daemon
        if (daemon is PocketshellDaemonStatus.Unavailable) {
            return InstallResult.Error(daemon.reason)
        }
        if (daemon is PocketshellDaemonStatus.Unknown) {
            return InstallResult.Error(daemon.reason)
        }
        if (daemon !is PocketshellDaemonStatus.Running || !daemon.enabled) {
            return installPocketshellUserDaemon(session, bootstrapPath)
        }
        return InstallResult.Success
    }

    private suspend fun freshenReport(
        session: SshSession,
        report: HostBootstrapReport?,
        bootstrapPath: String?,
        expectedPocketshellVersion: String?,
    ): HostBootstrapReport {
        if (report == null ||
            report.missingTools.isNotEmpty() ||
            report.unknownTools.isNotEmpty() ||
            report.versionMismatchedTools.isNotEmpty()
        ) {
            val tools = BootstrapTool.entries.associateWith { tool ->
                checkBootstrapToolWithPath(session, tool, bootstrapPath, expectedPocketshellVersion)
            }
            val installer = detectPythonToolInstaller(session, bootstrapPath)
            val daemon = if (tools[BootstrapTool.Pocketshell].isPresentOnRemote()) {
                checkPocketshellDaemon(session, bootstrapPath)
            } else {
                PocketshellDaemonStatus.Missing
            }
            return HostBootstrapReport(
                tools = tools,
                installer = installer,
                daemon = daemon,
                mosh = MoshStatus.Unsupported(MOSH_UNSUPPORTED_REASON),
            )
        }
        return report
    }

    private suspend fun checkBootstrapToolWithPath(
        session: SshSession,
        tool: BootstrapTool,
        bootstrapPath: String?,
        expectedPocketshellVersion: String?,
    ): ToolStatus {
        val status = checkToolWithPath(session, tool.binaryName, bootstrapPath)
        if (tool != BootstrapTool.Pocketshell || status !is ToolStatus.Installed) {
            return status
        }
        val expected = expectedPocketshellVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return status
        return checkPocketshellVersion(session, status.path, bootstrapPath, expected)
    }

    private suspend fun checkPocketshellVersion(
        session: SshSession,
        path: String,
        bootstrapPath: String?,
        expectedVersion: String,
    ): ToolStatus {
        return try {
        val result = session.exec(pathAwareCommand("${shellQuote(BINARY_POCKETSHELL)} --version", bootstrapPath))
        if (result.exitCode != 0) {
            ToolStatus.Unknown("pocketshell --version failed: ${result.stderr.ifBlank { "exit ${result.exitCode}" }}")
        } else {
            val current = parsePocketshellVersion(result.stdout.ifBlank { result.stderr })
                ?: return ToolStatus.Unknown("pocketshell --version did not report a parseable version")
            if (current == expectedVersion) {
                ToolStatus.Installed(path = path, version = current, expectedVersion = expectedVersion)
            } else {
                ToolStatus.VersionMismatch(
                    path = path,
                    currentVersion = current,
                    expectedVersion = expectedVersion,
                )
            }
        }
        } catch (e: SshException) {
            ToolStatus.Unknown(e.message ?: e.javaClass.simpleName)
        } catch (t: Throwable) {
            ToolStatus.Unknown("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        }
    }

    internal fun parsePocketshellVersion(output: String): String? {
        val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return VERSION_PATTERN.find(firstLine)?.groupValues?.get(1)
    }

    internal suspend fun checkTool(
        session: SshSession,
        binaryName: String,
    ): ToolStatus = checkToolWithPath(session, binaryName, detectBootstrapPath(session))

    private suspend fun checkToolWithPath(
        session: SshSession,
        binaryName: String,
        bootstrapPath: String?,
    ): ToolStatus = try {
        val result = session.exec(pathAwareCommand("command -v ${shellQuote(binaryName)}", bootstrapPath))
        when {
            result.exitCode == 0 && result.stdout.isNotBlank() -> ToolStatus.Installed(result.stdout.trim())
            result.exitCode != 0 -> ToolStatus.Missing
            else -> ToolStatus.Missing
        }
    } catch (e: SshException) {
        ToolStatus.Unknown(e.message ?: e.javaClass.simpleName)
    } catch (t: Throwable) {
        ToolStatus.Unknown("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    internal suspend fun detectPythonToolInstaller(
        session: SshSession,
    ): PythonToolInstaller? = detectPythonToolInstaller(session, detectBootstrapPath(session))

    private suspend fun detectPythonToolInstaller(
        session: SshSession,
        bootstrapPath: String?,
    ): PythonToolInstaller? {
        PythonToolInstaller.entries.forEach { installer ->
            if (checkToolWithPath(session, installer.binaryName, bootstrapPath) is ToolStatus.Installed) {
                return installer
            }
        }
        return null
    }

    internal suspend fun checkPocketshellDaemon(
        session: SshSession,
    ): PocketshellDaemonStatus = checkPocketshellDaemon(session, detectBootstrapPath(session))

    private suspend fun checkPocketshellDaemon(
        session: SshSession,
        bootstrapPath: String?,
    ): PocketshellDaemonStatus {
        when (val systemctl = checkToolWithPath(session, "systemctl", bootstrapPath)) {
            is ToolStatus.Installed -> Unit
            is ToolStatus.VersionMismatch -> Unit
            ToolStatus.Missing -> return PocketshellDaemonStatus.Unavailable("systemctl is not installed on this host")
            is ToolStatus.Unknown -> return PocketshellDaemonStatus.Unknown("could not locate systemctl: ${systemctl.reason}")
        }

        val active = try {
            session.exec(systemdUserCommand("systemctl --user is-active pocketshell-jobs.service", bootstrapPath))
        } catch (t: Throwable) {
            return PocketshellDaemonStatus.Unknown(
                "failed to query systemd user service: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}",
            )
        }
        if (active.exitCode != 0 && active.looksLikeUnavailableSystemdUser()) {
            return PocketshellDaemonStatus.Unavailable(active.combinedOutput().ifBlank { "systemd user services are unavailable on this host" })
        }
        val enabled = try {
            session.exec(systemdUserCommand("systemctl --user is-enabled pocketshell-jobs.service", bootstrapPath))
        } catch (_: Throwable) {
            return PocketshellDaemonStatus.Unknown("failed to query whether pocketshell-jobs.service is enabled")
        }
        if (enabled.exitCode != 0 && enabled.looksLikeUnavailableSystemdUser()) {
            return PocketshellDaemonStatus.Unavailable(enabled.combinedOutput().ifBlank { "systemd user services are unavailable on this host" })
        }
        val isEnabled = enabled.exitCode == 0 && enabled.stdout.trim() == "enabled"
        return when {
            active.exitCode == 0 && active.stdout.trim() == "active" -> PocketshellDaemonStatus.Running(isEnabled)
            active.stdout.trim() == "inactive" || active.stdout.trim() == "failed" -> {
                PocketshellDaemonStatus.InstalledStopped(isEnabled)
            }
            else -> PocketshellDaemonStatus.Missing
        }
    }

    public suspend fun installServerTool(
        session: SshSession,
        installer: PythonToolInstaller,
        tool: BootstrapTool,
    ): InstallResult = runPythonToolInstall(session, installer, tool, detectBootstrapPath(session))

    public suspend fun upgradeServerTool(
        session: SshSession,
        installer: PythonToolInstaller,
        tool: BootstrapTool,
    ): InstallResult = upgradeServerTool(session, installer, tool, detectBootstrapPath(session))

    private suspend fun installServerTool(
        session: SshSession,
        installer: PythonToolInstaller,
        tool: BootstrapTool,
        bootstrapPath: String?,
    ): InstallResult = runPythonToolInstall(session, installer, tool, bootstrapPath)

    private suspend fun upgradeServerTool(
        session: SshSession,
        installer: PythonToolInstaller,
        tool: BootstrapTool,
        bootstrapPath: String?,
    ): InstallResult = runPythonToolUpgrade(session, installer, tool, bootstrapPath)

    public suspend fun installPocketshellDaemon(session: SshSession): InstallResult =
        installPocketshellUserDaemon(session, detectBootstrapPath(session))

    /**
     * Detect the host's OS family and run the matching package-manager
     * install. Best-effort one-tap install — we deliberately don't run
     * `apt-get update` first or chain commands together so the user sees
     * exactly one command's output in the failure dialog.
     *
     * Privilege escalation: prefix each command with `sudo` when the
     * remote user isn't root. We probe `id -u` once and choose; this
     * matches what a human would do at the shell. Hosts where the user
     * has neither root nor passwordless `sudo` will see a `Failed`
     * result with the sudo prompt error in `stderr` — at that point the
     * user knows to fix their host, which is the right ceiling for a
     * one-tap helper.
     */
    public suspend fun installTmux(session: SshSession): InstallResult {
        val osRelease = try {
            session.exec("cat /etc/os-release")
        } catch (t: Throwable) {
            return InstallResult.Error(
                "failed to read /etc/os-release: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}",
            )
        }

        if (osRelease.exitCode != 0) {
            // No `/etc/os-release` — could be macOS (which uses
            // `/System/Library/CoreServices/SystemVersion.plist`) or a
            // very old distro. Try `uname -s` as a fallback so we still
            // catch Darwin -> brew.
            val uname = try {
                session.exec("uname -s")
            } catch (t: Throwable) {
                return InstallResult.Error("failed to detect OS: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
            }
            return when (uname.stdout.trim()) {
                "Darwin" -> runInstall(session, posixShellCommand("brew install tmux"), needsRoot = false)
                else -> InstallResult.UnsupportedOs(osId = null)
            }
        }

        val osId = parseOsId(osRelease.stdout)
        val pm = detectPackageManager(osId) ?: return InstallResult.UnsupportedOs(osId)

        val needsRoot = pm.needsRoot && !runningAsRoot(session)
        val cmd = if (needsRoot) "sudo ${pm.command}" else pm.command
        return runInstall(session, posixShellCommand(cmd), needsRoot)
    }

    /**
     * Visible for testing: parse the `ID=` value from `/etc/os-release`.
     * Returns the raw lowercase id (e.g. `"debian"`, `"ubuntu"`,
     * `"alpine"`, `"arch"`, `"fedora"`, `"rhel"`, `"centos"`, `"rocky"`,
     * `"almalinux"`, `"opensuse-tumbleweed"`). Returns `null` if the file
     * has no `ID=` line.
     */
    internal fun parseOsId(osRelease: String): String? {
        for (line in osRelease.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("ID=")) continue
            val raw = trimmed.removePrefix("ID=").trim().trim('"').trim('\'')
            if (raw.isEmpty()) return null
            return raw.lowercase()
        }
        return null
    }

    /**
     * Visible for testing: map a detected OS id to the install command.
     * Returns `null` for unknown OSes — callers turn that into
     * [InstallResult.UnsupportedOs].
     *
     * Apt is chosen for Debian/Ubuntu derivatives; `apk` for Alpine;
     * `dnf` / `yum` for Red Hat derivatives (`dnf` for modern Fedora /
     * RHEL 8+ / Rocky / Alma; `yum` for older CentOS / RHEL 7); `pacman`
     * for Arch; `zypper` for SUSE; `brew` for macOS (no root). The
     * `-y` / `--noconfirm` flags suppress the interactive yes/no prompt
     * — without them the `exec` channel would just hang.
     */
    internal fun detectPackageManager(osId: String?): PackageManager? = when (osId) {
        "debian", "ubuntu", "raspbian", "pop", "linuxmint", "kali", "elementary" ->
            PackageManager("apt-get install -y tmux", needsRoot = true)
        "alpine" ->
            PackageManager("apk add tmux", needsRoot = true)
        "fedora", "rhel", "rocky", "almalinux", "centos" ->
            PackageManager("dnf install -y tmux", needsRoot = true)
        "arch", "manjaro", "endeavouros" ->
            PackageManager("pacman -S --noconfirm tmux", needsRoot = true)
        "opensuse", "opensuse-tumbleweed", "opensuse-leap", "suse", "sles" ->
            PackageManager("zypper install -y tmux", needsRoot = true)
        "darwin", "macos" ->
            PackageManager("brew install tmux", needsRoot = false)
        null -> null
        else -> null
    }

    private suspend fun runInstall(
        session: SshSession,
        command: String,
        @Suppress("UNUSED_PARAMETER") needsRoot: Boolean,
    ): InstallResult = try {
        val result: ExecResult = session.exec(command)
        if (result.exitCode == 0) {
            InstallResult.Success
        } else {
            InstallResult.Failed(stderr = result.stderr, exitCode = result.exitCode)
        }
    } catch (t: Throwable) {
        InstallResult.Error("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    private suspend fun runPythonToolInstall(
        session: SshSession,
        installer: PythonToolInstaller,
        tool: BootstrapTool,
        bootstrapPath: String?,
    ): InstallResult {
        val command = when (installer) {
            PythonToolInstaller.Uv -> "uv tool install ${tool.packageName}"
            PythonToolInstaller.Pipx -> "pipx install ${tool.packageName}"
        }
        return runInstall(session, pathAwareCommand(command, bootstrapPath), needsRoot = false)
    }

    private suspend fun runPythonToolUpgrade(
        session: SshSession,
        installer: PythonToolInstaller,
        tool: BootstrapTool,
        bootstrapPath: String?,
    ): InstallResult {
        val command = when (installer) {
            PythonToolInstaller.Uv -> "uv tool upgrade ${tool.packageName}"
            PythonToolInstaller.Pipx -> "pipx upgrade ${tool.packageName}"
        }
        return runInstall(session, pathAwareCommand(command, bootstrapPath), needsRoot = false)
    }

    private suspend fun installPocketshellUserDaemon(
        session: SshSession,
        bootstrapPath: String?,
    ): InstallResult {
        val pocketshell = when (val status = checkToolWithPath(session, BINARY_POCKETSHELL, bootstrapPath)) {
            is ToolStatus.Installed -> status.path
            is ToolStatus.VersionMismatch -> status.path
            ToolStatus.Missing -> return InstallResult.Error("pocketshell is not installed; install it before enabling the jobs daemon.")
            is ToolStatus.Unknown -> return InstallResult.Error("could not locate pocketshell: ${status.reason}")
        }
        if (checkToolWithPath(session, "systemctl", bootstrapPath) !is ToolStatus.Installed) {
            return InstallResult.Error("systemctl is not installed on this host; enable pocketshell jobs daemon manually.")
        }
        val command = buildString {
            append("mkdir -p ~/.config/systemd/user && ")
            append("cat > ~/.config/systemd/user/pocketshell-jobs.service <<'EOF'\n")
            append("[Unit]\n")
            append("Description=pocketshell jobs daemon\n")
            append("After=default.target\n\n")
            append("[Service]\n")
            append("Type=simple\n")
            append("ExecStart=${systemdExecArg(pocketshell)} jobs daemon\n")
            append("Restart=on-failure\n")
            append("RestartSec=5s\n\n")
            append("[Install]\n")
            append("WantedBy=default.target\n")
            append("EOF\n")
            append("systemctl --user daemon-reload && ")
            append("systemctl --user enable --now pocketshell-jobs.service")
        }
        return runInstall(session, systemdUserCommand(command, bootstrapPath), needsRoot = false)
    }

    private suspend fun runningAsRoot(session: SshSession): Boolean = try {
        val r = session.exec("id -u")
        r.exitCode == 0 && r.stdout.trim() == "0"
    } catch (_: Throwable) {
        // If we can't tell, assume non-root and try sudo. Worse case the
        // install fails with a sudo error the user can read.
        false
    }

    /**
     * The package-manager command for a detected OS family. Internal so
     * unit tests can poke at the mapping without touching SSH.
     */
    internal data class PackageManager(
        val command: String,
        val needsRoot: Boolean,
    )

    /**
     * Build the `/bin/sh -lc '…'` wrapper that augments PATH before
     * running [command].
     *
     * When [bootstrapPath] is present it is the already-expanded PATH
     * detected from the user's interactive shell rc plus PocketShell's
     * default user-bin prefix. We install it as a single quoted value so
     * rc-derived directories are data, not shell code. When PATH
     * detection fails, the wrapper falls back to the historical default
     * `$HOME/.local/bin:$HOME/bin:$HOME/.cargo/bin:$PATH` augmentation.
     */
    internal fun pathAwareCommand(command: String, bootstrapPath: String? = null): String {
        val detected = bootstrapPath?.trim().orEmpty()
        val pathAssignment = if (detected.isNotEmpty()) {
            "PATH=${shellQuote(detected)}"
        } else {
            "PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\""
        }
        return posixShellCommand("$pathAssignment; export PATH; $command")
    }

    internal suspend fun detectBootstrapPath(session: SshSession): String? = try {
        val result = session.exec(detectBootstrapPathCommand())
        parseDetectedBootstrapPath(result.stdout)
    } catch (_: Throwable) {
        null
    }

    internal fun detectBootstrapPathCommand(): String {
        val posixBody = """
            if [ -r "${'$'}HOME/.profile" ]; then . "${'$'}HOME/.profile"; fi
            PATH="${'$'}HOME/.local/bin:${'$'}HOME/bin:${'$'}HOME/.cargo/bin:${'$'}PATH"; export PATH
            printf '${PATH_BEGIN}\n%s\n${PATH_END}\n' "${'$'}PATH"
        """.trimIndent()
        val bashBody = """
            [ -r "${'$'}HOME/.profile" ] && . "${'$'}HOME/.profile"
            [ -r "${'$'}HOME/.bashrc" ] && . "${'$'}HOME/.bashrc"
            PATH="${'$'}HOME/.local/bin:${'$'}HOME/bin:${'$'}HOME/.cargo/bin:${'$'}PATH"; export PATH
            printf '${PATH_BEGIN}\n%s\n${PATH_END}\n' "${'$'}PATH"
        """.trimIndent()
        val zshBody = """
            [ -r "${'$'}HOME/.zprofile" ] && . "${'$'}HOME/.zprofile"
            [ -r "${'$'}HOME/.profile" ] && . "${'$'}HOME/.profile"
            [ -r "${'$'}HOME/.zshrc" ] && . "${'$'}HOME/.zshrc"
            PATH="${'$'}HOME/.local/bin:${'$'}HOME/bin:${'$'}HOME/.cargo/bin:${'$'}PATH"; export PATH
            printf '${PATH_BEGIN}\n%s\n${PATH_END}\n' "${'$'}PATH"
        """.trimIndent()
        val fishBody = """
            set -gx PATH ${'$'}HOME/.local/bin ${'$'}HOME/bin ${'$'}HOME/.cargo/bin ${'$'}PATH
            printf '${PATH_BEGIN}\n%s\n${PATH_END}\n' (string join : ${'$'}PATH)
        """.trimIndent()
        return posixShellCommand(
            """
            __pocketshell_shell="${'$'}{SHELL:-}"
            __pocketshell_base="${'$'}{__pocketshell_shell##*/}"
            case "${'$'}__pocketshell_base" in
                bash) if [ -x "${'$'}__pocketshell_shell" ]; then exec "${'$'}__pocketshell_shell" -ic ${shellQuote(bashBody)}; fi ;;
                zsh) if [ -x "${'$'}__pocketshell_shell" ]; then exec "${'$'}__pocketshell_shell" -ic ${shellQuote(zshBody)}; fi ;;
                fish) if [ -x "${'$'}__pocketshell_shell" ]; then exec "${'$'}__pocketshell_shell" -ic ${shellQuote(fishBody)}; fi ;;
            esac
            $posixBody
            """.trimIndent(),
        )
    }

    internal fun parseDetectedBootstrapPath(output: String): String? {
        val begin = output.indexOf(PATH_BEGIN)
        if (begin < 0) return null
        val pathStart = begin + PATH_BEGIN.length
        val end = output.indexOf(PATH_END, startIndex = pathStart)
        if (end < 0) return null
        return output.substring(pathStart, end)
            .trim('\r', '\n')
            .takeIf { it.isNotBlank() }
    }

    private fun systemdUserCommand(command: String, bootstrapPath: String?): String {
        val detected = bootstrapPath?.trim().orEmpty()
        val pathPrefix = if (detected.isNotEmpty()) {
            "PATH=${shellQuote(detected)}; export PATH; "
        } else {
            ""
        }
        return posixShellCommand(
            pathPrefix +
                "XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\"; export XDG_RUNTIME_DIR; " +
                "DBUS_SESSION_BUS_ADDRESS=\"\${DBUS_SESSION_BUS_ADDRESS:-unix:path=\$XDG_RUNTIME_DIR/bus}\"; " +
                "export DBUS_SESSION_BUS_ADDRESS; $command",
        )
    }

    private fun posixShellCommand(command: String): String =
        "/bin/sh -lc ${shellQuote(command)}"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun systemdExecArg(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("%", "%%") + "\""

    private fun ExecResult.combinedOutput(): String =
        listOf(stdout.trim(), stderr.trim()).filter { it.isNotBlank() }.joinToString("\n")

    private fun ExecResult.looksLikeUnavailableSystemdUser(): Boolean {
        val output = combinedOutput().lowercase()
        return output.contains("failed to connect to bus") ||
            output.contains("no medium found") ||
            output.contains("not been booted with systemd") ||
            output.contains("system has not been booted") ||
            output.contains("transport endpoint is not connected")
    }

    private fun ToolStatus?.isPresentOnRemote(): Boolean =
        this is ToolStatus.Installed || this is ToolStatus.VersionMismatch

    public companion object {
        /**
         * Binary name of the unified `pocketshell` CLI — the single
         * required server-side tool (issue #231, D22 hard cut). Used by
         * the tool probe ([checkTool] via [BootstrapTool.Pocketshell])
         * and the jobs-daemon installer. Centralised so the probe and
         * install paths agree on the spelling.
         */
        public const val BINARY_POCKETSHELL: String = "pocketshell"

        private val VERSION_PATTERN: Regex = Regex("""\b(\d+(?:\.\d+){1,3}(?:[-+][0-9A-Za-z.-]+)?)\b""")
        private const val PATH_BEGIN: String = "__POCKETSHELL_PATH_BEGIN__"
        private const val PATH_END: String = "__POCKETSHELL_PATH_END__"
    }
}
