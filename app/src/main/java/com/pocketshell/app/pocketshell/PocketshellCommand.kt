package com.pocketshell.app.pocketshell

/**
 * Centralised, PATH-robust way to invoke the server-side `pocketshell` CLI over
 * a NON-interactive SSH `exec` channel (issue #484).
 *
 * ## Why this exists
 *
 * PocketShell runs every server-side feature (`pocketshell usage --json`,
 * `pocketshell jobs ...`, `pocketshell env ...`, hooks) through a single
 * `session.exec(...)` call. That exec channel is NOT a login shell and NOT an
 * interactive shell. On a stock Debian/Ubuntu `~/.bashrc` the very first lines
 * are the interactive early-return guard:
 *
 * ```sh
 * case $- in
 *     *i*) ;;
 *       *) return;;
 * esac
 * ```
 *
 * Any `PATH=...$HOME/.local/bin...` export that lives *after* that guard never
 * runs for our exec channel. So a perfectly-installed `~/.local/bin/pocketshell`
 * reads as "command not found" and the app falsely reports "not installed".
 * The maintainer hit this for weeks across servers (issue #484, #490).
 *
 * ## What the wrapper does
 *
 * Every `pocketshell` invocation is wrapped so that, in ONE non-interactive
 * shell, we:
 *
 * 1. Prepend the common user-bin directories to `PATH` so `pocketshell` on the
 *    `PATH` (the happy path) keeps working with no extra round-trips.
 * 2. Resolve the binary: first via `command -v pocketshell` against that
 *    augmented `PATH`, then by probing a list of absolute candidate locations
 *    (`$HOME/.local/bin`, pipx, uv, pixi, `/usr/local/bin`, ...). The first
 *    candidate that exists and is executable wins.
 * 3. Exec the resolved binary with the requested arguments. If NO candidate is
 *    found, the wrapper exits `127` so callers can treat it as a genuine
 *    "tool missing" (their existing exit-127 handling already maps to
 *    ToolMissing) and surface the setup hint.
 *
 * A bare `bash -lc` was rejected as the primary mechanism: a login shell
 * sources `~/.profile` / `~/.bash_profile`, NOT `~/.bashrc`, and on many setups
 * the `~/.local/bin` export lives only in `~/.bashrc`. The absolute-path probe
 * is the reliable fallback, so it is the one we ship as the durable fix.
 *
 * @see docs/server-setup.md for the server-side guidance shown to the user.
 */
public object PocketshellCommand {

    /**
     * The directories prepended to `PATH` before resolving `pocketshell`.
     * Covers the common per-user install locations a non-interactive SSH exec
     * normally misses: pip `--user` / uv / pipx (`~/.local/bin`), cargo
     * (`~/.cargo/bin`), pixi (`~/.pixi/bin`), and the system-wide
     * `/usr/local/bin`.
     */
    public val PATH_PREFIX_DIRS: List<String> = listOf(
        "\$HOME/.local/bin",
        "\$HOME/.cargo/bin",
        "\$HOME/.pixi/bin",
        "\$HOME/bin",
        "/usr/local/bin",
    )

    /**
     * Absolute candidate paths probed (in order) when `command -v pocketshell`
     * still fails after the `PATH` prefix. These cover the bin dirs created by
     * the common Python install tooling that drops `pocketshell` outside the
     * non-interactive SSH `PATH`.
     */
    public val ABSOLUTE_CANDIDATES: List<String> = listOf(
        "\$HOME/.local/bin/pocketshell",
        "\$HOME/.local/pipx/venvs/pocketshell/bin/pocketshell",
        "\$HOME/.cargo/bin/pocketshell",
        "\$HOME/.pixi/bin/pocketshell",
        "\$HOME/bin/pocketshell",
        "/usr/local/bin/pocketshell",
        "/usr/bin/pocketshell",
        "/opt/pocketshell/bin/pocketshell",
    )

    /**
     * Wrap [args] (the `pocketshell` arguments, e.g. `"usage --json"` or
     * `"jobs list"`) so the command resolves and runs `pocketshell` even when
     * the non-interactive SSH `PATH` lacks `~/.local/bin`.
     *
     * [args] must already be shell-quoted/escaped by the caller exactly as it
     * would have been for a bare `pocketshell <args>` invocation — this wrapper
     * only takes over binary resolution, not argument quoting.
     *
     * The returned string is a single `/bin/sh`-compatible command suitable for
     * [com.pocketshell.core.ssh.SshSession.exec]. On a genuinely-absent
     * `pocketshell` it exits `127`.
     */
    public fun wrap(args: String): String {
        val pathPrefix = PATH_PREFIX_DIRS.joinToString(":")
        val candidatesList = ABSOLUTE_CANDIDATES.joinToString(" ")
        // Single non-interactive shell:
        //  - export an augmented PATH (happy path: pocketshell already on PATH)
        //  - resolve a runnable binary via `command -v`, else probe absolutes
        //  - exec it with the caller's args, or exit 127 when truly absent.
        return buildString {
            append("export PATH=\"")
            append(pathPrefix)
            append(":\$PATH\"; ")
            append("__ps_bin=\"\$(command -v pocketshell 2>/dev/null)\"; ")
            append("if [ -z \"\$__ps_bin\" ]; then ")
            append("for __ps_c in ")
            append(candidatesList)
            append("; do if [ -x \"\$__ps_c\" ]; then __ps_bin=\"\$__ps_c\"; break; fi; done; ")
            append("fi; ")
            append("if [ -z \"\$__ps_bin\" ]; then exit 127; fi; ")
            append("\"\$__ps_bin\" ")
            append(args)
        }
    }

    /**
     * Convenience wrapper that resolves `pocketshell` and runs it with [args],
     * but treats a successful resolution as a detection signal: prints the
     * resolved absolute path on stdout and exits `0` when found, or exits `127`
     * when genuinely absent. Used by the usage detection probe so "not
     * installed" only triggers on a real absence.
     */
    public fun detect(): String {
        val pathPrefix = PATH_PREFIX_DIRS.joinToString(":")
        val candidatesList = ABSOLUTE_CANDIDATES.joinToString(" ")
        return buildString {
            append("export PATH=\"")
            append(pathPrefix)
            append(":\$PATH\"; ")
            append("__ps_bin=\"\$(command -v pocketshell 2>/dev/null)\"; ")
            append("if [ -z \"\$__ps_bin\" ]; then ")
            append("for __ps_c in ")
            append(candidatesList)
            append("; do if [ -x \"\$__ps_c\" ]; then __ps_bin=\"\$__ps_c\"; break; fi; done; ")
            append("fi; ")
            append("if [ -z \"\$__ps_bin\" ]; then exit 127; fi; ")
            append("printf '%s\\n' \"\$__ps_bin\"")
        }
    }

    /**
     * One-line user-facing hint appended to "not installed" surfaces so the
     * maintainer knows the fix is a server-side PATH adjustment, not a missing
     * install. Kept short for the small usage-panel rows.
     */
    public const val NOT_INSTALLED_HINT: String =
        "Install pocketshell on the server or expose ~/.local/bin to non-interactive SSH (see docs/server-setup.md)."
}
