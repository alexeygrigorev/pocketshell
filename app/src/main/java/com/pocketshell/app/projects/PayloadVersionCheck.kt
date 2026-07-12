package com.pocketshell.app.projects

import com.pocketshell.app.bootstrap.UV_EXCLUDE_NEWER_FLAG

/**
 * Passive payload-version mismatch detection (issue #885).
 *
 * ## Why this exists
 *
 * The old host-open version check (`HostBootstrapper.checkServerSetup` driven
 * from `HostListViewModel.bootstrapHost`) ran a slow BLOCKING `pocketshell
 * --version` exec on open, and it only fired reliably through the
 * home-list→open path — a direct/warm open never re-triggered it. So the check
 * was both laggy AND inconsistent (the maintainer's #885 dogfood:
 * *"only THEN does it check the CLI version — loading, loading, loading"*).
 *
 * ## The passive approach
 *
 * The PocketShell client already execs the `pocketshell tree get` /
 * `pocketshell tree reconcile` payloads over the warm SSH session on EVERY host
 * open (warm/direct included), as part of loading the project tree. Those
 * envelopes now carry the server-side CLI version as `cli_version`
 * (`tools/pocketshell/src/pocketshell/tree.py`). This helper compares that
 * payload-carried version against the version the app expects, so a mismatch is
 * detected PASSIVELY during normal use — no separate blocking on-open exec, and
 * consistently regardless of how the host was opened.
 *
 * Pure + side-effect-free so it is trivially unit-tested; the
 * [FolderListViewModel] feeds it the parsed payload version and surfaces a
 * mismatch as a small dismissible update prompt.
 */
object PayloadVersionCheck {

    /**
     * The copy-paste command shown to bring the host's `pocketshell` up to date.
     * Mirrors [com.pocketshell.app.projects.AgentLaunchVersionCheck.UPDATE_COMMAND]
     * — the global [com.pocketshell.app.bootstrap.UV_EXCLUDE_NEWER_FLAG] lifts the
     * host's `uv` `exclude-newer` cap for the WHOLE tool-install resolution
     * (issue #779 for pocketshell, widened by #1492 to cover its pinned siblings
     * like `quse`) so the upgrade is never a silent no-op.
     */
    const val UPDATE_COMMAND: String =
        "uv tool install --upgrade $UV_EXCLUDE_NEWER_FLAG pocketshell"

    /**
     * The verdict of comparing a payload-carried [hostVersion] against the
     * app's [expectedVersion].
     *
     * - [Match] — versions are equal (or close enough that there is nothing to
     *   do), OR there is no usable signal (an empty / unparseable payload
     *   version is treated as "no signal", never a false mismatch).
     * - [HostOutdated] — the host's CLI is OLDER than the app expects: the user
     *   should update `pocketshell` on the host. This is the one that surfaces
     *   the passive update prompt.
     * - [AppOutdated] — the host's CLI is NEWER than the app build: the host is
     *   fine, the APP is behind. Surfaced as an informational note, never as a
     *   host-installer prompt (mirrors the #514 AppUpdateRequired policy — a
     *   host upgrade can only land on the newest version, never the older one
     *   the app wants, so prompting a host update would loop).
     */
    sealed interface Verdict {
        data object Match : Verdict
        data class HostOutdated(
            val hostVersion: String,
            val expectedVersion: String,
        ) : Verdict
        data class AppOutdated(
            val hostVersion: String,
            val expectedVersion: String,
        ) : Verdict
    }

    /**
     * Compare a [hostVersion] (the `cli_version` from a `tree` payload, may be
     * `null`/blank when an old CLI omits it or the read failed) against the
     * [expectedVersion] the app build expects.
     *
     * A `null`/blank/unparseable [hostVersion] or [expectedVersion] yields
     * [Verdict.Match] (no signal): the passive check NEVER produces a false
     * mismatch from missing data. An OLD host CLI that predates this field
     * simply omits `cli_version`, so it yields no signal here — that host is
     * caught by the existing agent-launch / bootstrap guards instead, and the
     * once-the-host-is-updated payload then carries the field forever after.
     */
    fun evaluate(hostVersion: String?, expectedVersion: String?): Verdict {
        val host = hostVersion?.trim().orEmpty()
        val expected = expectedVersion?.trim().orEmpty()
        if (host.isEmpty() || expected.isEmpty()) return Verdict.Match
        val cmp = compareDottedVersions(host, expected) ?: return Verdict.Match
        return when {
            cmp < 0 -> Verdict.HostOutdated(hostVersion = host, expectedVersion = expected)
            cmp > 0 -> Verdict.AppOutdated(hostVersion = host, expectedVersion = expected)
            else -> Verdict.Match
        }
    }

    /**
     * Numeric compare of two dotted-numeric versions (mirrors the #514 bootstrap
     * compare and [AgentLaunchVersionCheck.compareDottedVersions] so multi-digit
     * components order correctly: "0.4.10" > "0.4.9"). Returns
     * negative/zero/positive, or `null` when either side is not clean
     * dotted-numeric (a pre-release suffix etc.), so a non-comparable shape
     * degrades to "no signal" rather than a guess.
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

    /**
     * The user-facing prompt text for a [Verdict.HostOutdated], naming the
     * installed version + the update command on its own line so the UI can
     * present the command as a single copyable block.
     */
    fun outdatedHostPrompt(verdict: Verdict.HostOutdated): String =
        "This host's pocketshell is ${verdict.hostVersion}; the app expects " +
            "${verdict.expectedVersion}. Update it on the host:\n" + UPDATE_COMMAND +
            "\n(or: pipx upgrade pocketshell / pip install -U pocketshell)"
}
