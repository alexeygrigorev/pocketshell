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
     */
    public suspend fun checkTmux(session: SshSession): TmuxStatus = try {
        val result = session.exec("command -v tmux")
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
                "Darwin" -> runInstall(session, "brew install tmux", needsRoot = false)
                else -> InstallResult.UnsupportedOs(osId = null)
            }
        }

        val osId = parseOsId(osRelease.stdout)
        val pm = detectPackageManager(osId) ?: return InstallResult.UnsupportedOs(osId)

        val needsRoot = pm.needsRoot && !runningAsRoot(session)
        val cmd = if (needsRoot) "sudo ${pm.command}" else pm.command
        return runInstall(session, cmd, needsRoot)
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
}
