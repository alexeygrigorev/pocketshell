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
    public data class Installed(val path: String) : ToolStatus
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

    public val isReady: Boolean
        get() = missingTools.isEmpty() &&
            unknownTools.isEmpty() &&
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
     * Issue #41: [pathOverride] is an optional colon-separated PATH
     * fragment (e.g. `/home/u/git/pocketshell/.venv/bin`). When non-null and
     * non-blank it is prepended ahead of the standard built-in
     * augmentation, so binaries installed in venv-style locations the
     * user keeps in `~/.bashrc` (which is not sourced by `/bin/sh -lc`)
     * become visible to the probe.
     */
    public suspend fun checkTmux(
        session: SshSession,
        pathOverride: String? = null,
    ): TmuxStatus = try {
        val result = session.exec(pathAwareCommand("command -v tmux", pathOverride))
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
        pathOverride: String? = null,
    ): HostBootstrapReport {
        val tools = BootstrapTool.entries.associateWith { tool ->
            checkTool(session, tool.binaryName, pathOverride)
        }
        val installer = detectPythonToolInstaller(session, pathOverride)
        val daemon = if (tools[BootstrapTool.Pocketshell] is ToolStatus.Installed) {
            checkPocketshellDaemon(session, pathOverride)
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
        pathOverride: String? = null,
    ): InstallResult {
        val currentReport = freshenReport(session, report, pathOverride)
        if (currentReport.unknownTools.isNotEmpty()) {
            val unknown = currentReport.unknownTools.joinToString { it.binaryName }
            return InstallResult.Error("Could not detect required host tools: $unknown. Reconnect and try again.")
        }
        val missingTools = currentReport.missingTools
        if (missingTools.isNotEmpty()) {
            val installer = currentReport.installer ?: return InstallResult.Error(
                "Install uv or pipx on the host, then reconnect. PocketShell uses one of them to install pocketshell.",
            )
            for (tool in missingTools) {
                val result = installServerTool(session, installer, tool, pathOverride)
                if (result !is InstallResult.Success) return result
            }
        }

        val afterTools = checkServerSetup(session, pathOverride)
        val daemon = afterTools.daemon
        if (daemon is PocketshellDaemonStatus.Unavailable) {
            return InstallResult.Error(daemon.reason)
        }
        if (daemon is PocketshellDaemonStatus.Unknown) {
            return InstallResult.Error(daemon.reason)
        }
        if (daemon !is PocketshellDaemonStatus.Running || !daemon.enabled) {
            return installPocketshellUserDaemon(session, pathOverride)
        }
        return InstallResult.Success
    }

    private suspend fun freshenReport(
        session: SshSession,
        report: HostBootstrapReport?,
        pathOverride: String?,
    ): HostBootstrapReport {
        if (report == null || report.missingTools.isNotEmpty() || report.unknownTools.isNotEmpty()) {
            return checkServerSetup(session, pathOverride)
        }
        return report
    }

    internal suspend fun checkTool(
        session: SshSession,
        binaryName: String,
        pathOverride: String? = null,
    ): ToolStatus = try {
        val result = session.exec(pathAwareCommand("command -v ${shellQuote(binaryName)}", pathOverride))
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
        pathOverride: String? = null,
    ): PythonToolInstaller? {
        PythonToolInstaller.entries.forEach { installer ->
            if (checkTool(session, installer.binaryName, pathOverride) is ToolStatus.Installed) {
                return installer
            }
        }
        return null
    }

    internal suspend fun checkPocketshellDaemon(
        session: SshSession,
        pathOverride: String? = null,
    ): PocketshellDaemonStatus {
        when (val systemctl = checkTool(session, "systemctl", pathOverride)) {
            is ToolStatus.Installed -> Unit
            ToolStatus.Missing -> return PocketshellDaemonStatus.Unavailable("systemctl is not installed on this host")
            is ToolStatus.Unknown -> return PocketshellDaemonStatus.Unknown("could not locate systemctl: ${systemctl.reason}")
        }

        val active = try {
            session.exec(systemdUserCommand("systemctl --user is-active pocketshell-jobs.service"))
        } catch (t: Throwable) {
            return PocketshellDaemonStatus.Unknown(
                "failed to query systemd user service: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}",
            )
        }
        if (active.exitCode != 0 && active.looksLikeUnavailableSystemdUser()) {
            return PocketshellDaemonStatus.Unavailable(active.combinedOutput().ifBlank { "systemd user services are unavailable on this host" })
        }
        val enabled = try {
            session.exec(systemdUserCommand("systemctl --user is-enabled pocketshell-jobs.service"))
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
        pathOverride: String? = null,
    ): InstallResult = runPythonToolInstall(session, installer, tool, pathOverride)

    public suspend fun installPocketshellDaemon(
        session: SshSession,
        pathOverride: String? = null,
    ): InstallResult = installPocketshellUserDaemon(session, pathOverride)

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
        pathOverride: String?,
    ): InstallResult {
        val command = when (installer) {
            PythonToolInstaller.Uv -> "uv tool install ${tool.packageName}"
            PythonToolInstaller.Pipx -> "pipx install ${tool.packageName}"
        }
        return runInstall(session, pathAwareCommand(command, pathOverride), needsRoot = false)
    }

    private suspend fun installPocketshellUserDaemon(
        session: SshSession,
        pathOverride: String?,
    ): InstallResult {
        val pocketshell = when (val status = checkTool(session, BINARY_POCKETSHELL, pathOverride)) {
            is ToolStatus.Installed -> status.path
            ToolStatus.Missing -> return InstallResult.Error("pocketshell is not installed; install it before enabling the jobs daemon.")
            is ToolStatus.Unknown -> return InstallResult.Error("could not locate pocketshell: ${status.reason}")
        }
        if (checkTool(session, "systemctl", pathOverride) !is ToolStatus.Installed) {
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
        return runInstall(session, systemdUserCommand(command), needsRoot = false)
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
     * The base augmentation is the historical
     * `$HOME/.local/bin:$HOME/bin:$HOME/.cargo/bin` prefix, which covers
     * the canonical tool-install locations the probe needs to see. Issue
     * #41 adds an optional per-host override that is prepended *ahead*
     * of that base so it wins in PATH search order:
     *
     * ```
     * PATH="<override>:$HOME/.local/bin:$HOME/bin:$HOME/.cargo/bin:$PATH"
     * ```
     *
     * The override is taken verbatim from the user's Add/Edit Host
     * "Extra PATH directories" field. A `null` or all-whitespace value
     * disables the prepend (and produces a probe command that is
     * byte-identical to the v0 wrapper for backwards compatibility with
     * the existing HostBootstrapperTest fixtures).
     *
     * The override is not shell-escaped: the user is intentionally
     * choosing PATH entries that already contain forward slashes and
     * possibly `~`, and we want it to behave exactly the way the user
     * would type it into a shell — i.e. tilde expansion and `$VAR`
     * substitution happen if the user wrote them. The whole wrapper is
     * single-quoted as one big argument to `sh -lc`, so the override is
     * subject to the same expansion rules as the inline literals around
     * it. We re-quote the wrapper itself via [shellQuote] which escapes
     * any single-quote the user might paste in.
     */
    internal fun pathAwareCommand(command: String, pathOverride: String? = null): String {
        val trimmed = pathOverride?.trim().orEmpty()
        val prefix = if (trimmed.isNotEmpty()) "$trimmed:" else ""
        return posixShellCommand(
            "PATH=\"${prefix}\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"; export PATH; $command",
        )
    }

    private fun systemdUserCommand(command: String): String =
        posixShellCommand(
            "XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\"; export XDG_RUNTIME_DIR; " +
                "DBUS_SESSION_BUS_ADDRESS=\"\${DBUS_SESSION_BUS_ADDRESS:-unix:path=\$XDG_RUNTIME_DIR/bus}\"; " +
                "export DBUS_SESSION_BUS_ADDRESS; $command",
        )

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

    public companion object {
        /**
         * Binary name of the unified `pocketshell` CLI — the single
         * required server-side tool (issue #231, D22 hard cut). Used by
         * the tool probe ([checkTool] via [BootstrapTool.Pocketshell])
         * and the jobs-daemon installer. Centralised so the probe and
         * install paths agree on the spelling.
         */
        public const val BINARY_POCKETSHELL: String = "pocketshell"
    }
}
