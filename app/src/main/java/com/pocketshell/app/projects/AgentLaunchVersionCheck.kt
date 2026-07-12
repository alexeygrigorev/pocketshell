package com.pocketshell.app.projects

import com.pocketshell.app.bootstrap.UV_EXCLUDE_NEWER_FLAG

/**
 * Graceful version-mismatch detection for the agent-launch flow (issue #759).
 *
 * Launching a coding agent in a new pane runs the short server-side wrapper
 * `pocketshell agent <kind> --dir '<dir>' …` (see [AgentCli.launchCommand]).
 * That `agent` subcommand only exists in `pocketshell` ≥ [MIN_AGENT_POCKETSHELL_VERSION].
 * When the host has an OLDER `pocketshell` installed, Click answers with a raw
 *
 * ```
 * Error: No such command 'agent'. (Did you mean one of: 'agent-log', 'usage'?)
 * ```
 *
 * and the user is left staring at an opaque CLI error with no idea what to do.
 *
 * This helper turns that into an actionable hint: either by detecting the
 * missing-subcommand failure in the wrapper's output (the primary, robust
 * signal — it does not depend on `--version` being parseable) or by comparing a
 * parsed `pocketshell --version` against the required minimum.
 *
 * Hard-cut (D22): there is NO compatibility shim that re-emits an old inline
 * launch line for outdated hosts. The host is expected to be current; the only
 * remedy offered is "update `pocketshell` on the host".
 */
object AgentLaunchVersionCheck {

    /**
     * The minimum host-side `pocketshell` version that ships the `agent`
     * subcommand the launch flow depends on. The `agent` command was added in
     * 0.3.34 (issue #759); anything older predates it.
     */
    const val MIN_AGENT_POCKETSHELL_VERSION: String = "0.3.34"

    /**
     * The copy-paste command shown to the user to bring the host's
     * `pocketshell` up to date. `uv` is the maintainer's primary install path;
     * the pipx / pip alternatives cover the other supported install methods.
     *
     * Issue #779: a plain `uv tool upgrade pocketshell` is silently capped by
     * the host's global `exclude-newer` (`~/.config/uv/uv.toml`) — when the
     * newest release is younger than that cutoff, uv reports "Nothing to
     * upgrade" and exits 0, so the upgrade is a no-op and the version mismatch
     * never clears.
     *
     * Issue #1492 widens that: the old `--exclude-newer-package
     * pocketshell=<far future>` override lifted the cap for `pocketshell` ONLY,
     * so a release that pins a sibling (`quse`) past the host's cutoff still
     * failed to resolve (a silent no-op). The global
     * [com.pocketshell.app.bootstrap.UV_EXCLUDE_NEWER_FLAG] lifts the cap for the
     * WHOLE tool-install resolution — pocketshell + its entire pinned closure —
     * leaving the user's reproducibility setting intact for everything else.
     *
     * Issue #1490 adds `--refresh`: even with the cap lifted, `uv tool install
     * --upgrade` resolves from a stale index/resolver cache that predates the new
     * publish and reports success while leaving the old version installed (the
     * maintainer's "found nothing newer to install" no-op). `--refresh` re-fetches
     * the index + metadata so a freshly-published release is seen.
     * `pipx upgrade` / `pip install -U` are not affected by uv's cutoff.
     */
    const val UPDATE_COMMAND: String =
        "uv tool install --refresh --upgrade $UV_EXCLUDE_NEWER_FLAG pocketshell"

    /**
     * The server-side wrapper line the agent-launch flow types into a fresh
     * pane is `pocketshell agent <kind> --dir '<dir>' …` (see
     * [AgentCli.buildAgentCommand]). This prefix is how the gateway recognises
     * that a start command is an agent launch (and therefore wants the
     * pre-flight version guard) versus a plain shell session.
     */
    const val AGENT_COMMAND_PREFIX: String = "pocketshell agent "

    /**
     * The pre-flight probe the gateway runs over the SAME warm lease session
     * (D21 — no new connection) before typing the agent launch line into the
     * new pane. `pocketshell agent --help` exits 0 and prints the subcommand's
     * help when the host's `pocketshell` is current; on an outdated host Click
     * answers with the same `No such command 'agent'` error the real launch
     * would, but synchronously and capturable as an [ExecResult] — so the
     * mismatch surfaces through `createSession`'s normal failure path instead
     * of silently scrolling past inside the detached pane.
     */
    const val AGENT_PROBE_COMMAND: String = "pocketshell agent --help"

    /**
     * The `pocketshell --version` command, run as a best-effort second probe so
     * an outdated-host hint can name the concrete installed version.
     */
    const val VERSION_PROBE_COMMAND: String = "pocketshell --version"

    /**
     * `true` when [startCommand] is an agent-launch wrapper line and therefore
     * needs the pre-flight version guard. Plain shell sessions ([startCommand]
     * is `null` or anything not starting with [AGENT_COMMAND_PREFIX]) do not
     * touch `pocketshell agent` and are skipped.
     */
    fun isAgentLaunchCommand(startCommand: String?): Boolean =
        startCommand?.trimStart()?.startsWith(AGENT_COMMAND_PREFIX) == true

    /**
     * Build the user-facing hint shown instead of the raw `No such command`
     * output. When [installedVersion] is a parseable version it is named so the
     * message is concrete ("this host's pocketshell is 0.3.33"); otherwise a
     * generic "too old" phrasing is used. The update command is appended on its
     * own line so the UI can present it as a single copyable block.
     */
    fun outdatedHint(installedVersion: String? = null): String {
        val parsed = installedVersion?.let { parseReportedVersion(it) }
        val lead = if (parsed != null) {
            "This host's pocketshell is $parsed; launching an agent needs " +
                "≥ $MIN_AGENT_POCKETSHELL_VERSION."
        } else {
            "This host's pocketshell is too old for this feature; it needs " +
                "≥ $MIN_AGENT_POCKETSHELL_VERSION."
        }
        return lead + " Update it on the host:\n" + UPDATE_COMMAND +
            "\n(or: pipx upgrade pocketshell / pip install -U pocketshell)"
    }

    /**
     * Detect the agent-launch version-mismatch failure from the wrapper's
     * combined output. This is the PRIMARY signal: it works even when
     * `pocketshell --version` cannot be obtained or parsed, because it matches
     * Click's own "No such command" error for the missing `agent` subcommand.
     *
     * [stdout] / [stderr] are the captured streams of the launch attempt;
     * [exitCode] is its process exit status. Click reports an unknown command
     * with exit code 2, but the textual match alone is enough.
     */
    fun isAgentSubcommandMissing(
        stdout: String,
        stderr: String,
        exitCode: Int? = null,
    ): Boolean {
        val output = "$stderr\n$stdout"
        return isMissingSubcommand(output, "agent")
    }

    /**
     * Generic "Click reports [subcommand] as unknown" detector. Matches both
     * single- and double-quoted forms Click can emit. Exposed so the same
     * robust signal can be reused for any other server-side subcommand the
     * client may emit ahead of a host update.
     */
    fun isMissingSubcommand(output: String, subcommand: String): Boolean {
        return output.contains("No such command '$subcommand'", ignoreCase = true) ||
            output.contains("No such command \"$subcommand\"", ignoreCase = true)
    }

    /**
     * Map an agent-launch attempt to an outdated-host hint, or `null` when the
     * attempt does not look like a version mismatch. The combined output is
     * checked for the missing `agent` subcommand; when present, the hint is
     * returned (named with [installedVersion] when known).
     */
    fun mapLaunchFailureToHint(
        stdout: String,
        stderr: String,
        exitCode: Int? = null,
        installedVersion: String? = null,
    ): String? {
        return if (isAgentSubcommandMissing(stdout, stderr, exitCode)) {
            outdatedHint(installedVersion)
        } else {
            null
        }
    }

    /**
     * Return `true` when [installedVersion] (a value already reduced to a
     * dotted-numeric string, e.g. from [parseReportedVersion]) is strictly
     * older than [MIN_AGENT_POCKETSHELL_VERSION]. Returns `false` when the
     * version cannot be compared cleanly — the missing-subcommand detection,
     * not version math, is the authoritative signal, so an unparseable version
     * never produces a false "too old" verdict here.
     */
    fun isOlderThanRequired(installedVersion: String): Boolean {
        val cmp = compareDottedVersions(installedVersion.trim(), MIN_AGENT_POCKETSHELL_VERSION)
        return cmp != null && cmp < 0
    }

    /**
     * Extract the dotted-numeric version out of a `pocketshell --version` line.
     * Click's `version_option(prog_name = "pocketshell")` prints
     * `pocketshell, version 0.3.34`, but this also tolerates a bare `0.3.34`,
     * an `pocketshell 0.3.34` form, and trailing build/pre-release suffixes
     * (`0.3.34.dev1`, `0.3.34+local`). Returns the leading `MAJOR.MINOR.PATCH…`
     * run, or `null` when no version-looking token is present.
     */
    fun parseReportedVersion(versionOutput: String): String? {
        val match = VERSION_TOKEN.find(versionOutput) ?: return null
        return match.value
    }

    private val VERSION_TOKEN = Regex("""\d+(?:\.\d+)+""")

    /**
     * Numeric compare of two dotted-numeric versions. Mirrors the bootstrap
     * compare (issue #514) so multi-digit components order correctly
     * ("0.3.10" > "0.3.9"). Returns negative/zero/positive, or `null` when
     * either side is not clean dotted-numeric.
     */
    internal fun compareDottedVersions(a: String, b: String): Int? {
        val left = parseComponents(a) ?: return null
        val right = parseComponents(b) ?: return null
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun parseComponents(version: String): List<Int>? {
        val trimmed = version.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('.')
        val components = ArrayList<Int>(parts.size)
        for (part in parts) {
            if (part.isEmpty()) return null
            val value = part.toIntOrNull() ?: return null
            if (value < 0) return null
            components += value
        }
        return components
    }
}
