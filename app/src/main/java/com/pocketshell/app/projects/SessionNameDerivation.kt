package com.pocketshell.app.projects

/**
 * Directory-derived tmux session naming — issue #429.
 *
 * Mirrors the maintainer's `tmuxctl` (`t`) `_current_directory_session_name`
 * convention so a glance at a session name tells you which directory it
 * belongs to, instead of the old cryptic `<basename>-<6-digit-timestamp>`:
 *
 *  - When the directory **is** `$HOME` → `home-<homeBasename>`
 *    (e.g. `/home/alexey` → `home-alexey`).
 *  - When the directory is **under** `$HOME` → the path relative to home,
 *    with each path component normalised and joined by `-`
 *    (e.g. `~/git/pocketshell` → `git-pocketshell`).
 *  - Otherwise (outside home, or home unknown) → the absolute path
 *    components normalised and joined by `-`
 *    (e.g. `/var/log` → `var-log`).
 *
 * Agent sessions keep the agent CLI as a prefix so the flat list still
 * reads "what kind of session is this" — e.g. `claude-git-pocketshell`.
 *
 * The random timestamp suffix is gone. Idempotency for re-picking the
 * same directory is handled server-side by `tmux new-session -A`
 * (attach-if-exists), which PocketShell already uses. When the caller
 * supplies the set of session names it already knows about, a *genuinely
 * different* second session in the same directory gets a deterministic,
 * human-readable `-2`, `-3`, … suffix instead of silently colliding.
 *
 * This object is intentionally pure (no Android / SSH dependencies) so the
 * convention is unit-testable on the JVM and so it does not need to reach
 * into the session-discovery gateway/viewmodel.
 */
internal object SessionNameDerivation {

    /**
     * Derive a tmux-safe session name from a directory.
     *
     * @param startDirectory the chosen cwd. May be absolute
     *   (`/home/alexey/git/pocketshell`), `~`-relative (`~`, `~/git/x`),
     *   or have a trailing slash. Leading/trailing whitespace is trimmed.
     * @param homeDirectory the remote `$HOME` if known (used to compute the
     *   home-relative path and the `$HOME` special case). When `null` only
     *   the `~`-prefix form can be recognised as home; absolute paths are
     *   treated as outside-home.
     * @param agentCommand for agent sessions, the CLI command to prefix
     *   (e.g. `claude`); `null`/blank for shell sessions.
     * @param existingNames names already taken on the host. When the
     *   derived base name is present, a deterministic `-2`, `-3`, … suffix
     *   is appended until a free name is found. Default empty (no
     *   disambiguation), in which case `tmux new-session -A` attaches to
     *   any same-named session rather than overwriting it.
     */
    fun derive(
        startDirectory: String,
        homeDirectory: String?,
        agentCommand: String?,
        existingNames: Set<String> = emptySet(),
    ): String {
        val base = baseName(startDirectory, homeDirectory)
        val prefix = agentCommand?.trim()?.takeIf { it.isNotEmpty() }?.let { sanitisePart(it) }
        val candidate = if (!prefix.isNullOrEmpty()) "$prefix-$base" else base
        return disambiguate(candidate, existingNames)
    }

    /**
     * The directory-derived part of the name (no agent prefix, no
     * collision suffix). Exposed for focused unit tests of the tmuxctl
     * path logic.
     */
    fun baseName(startDirectory: String, homeDirectory: String?): String {
        val home = homeDirectory?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val raw = startDirectory.trim().trimEnd('/').ifBlank { "" }

        // Resolve a leading `~` / `~/` against the known home so the same
        // directory yields the same name whether the caller passed the
        // shortcut or the absolute path.
        val resolved = when {
            raw.isBlank() -> home ?: ""
            raw == "~" -> home ?: raw
            raw.startsWith("~/") -> if (home != null) "$home/${raw.removePrefix("~/")}" else raw
            else -> raw
        }

        // Directory IS $HOME → `home-<homeBasename>`.
        if (home != null && resolved == home) {
            val homeTail = home.substringAfterLast('/').ifBlank { "home" }
            return joinParts(listOf("home", homeTail))
        }

        // Directory is UNDER $HOME → relative path parts, joined by `-`.
        if (home != null && resolved.startsWith("$home/")) {
            val relative = resolved.removePrefix("$home/")
            return joinParts(splitPathParts(relative)).ifBlank { "shell" }
        }

        // Unresolved `~`-form with no known home: treat the part after `~/`
        // as the relative path (best effort), `~` alone as `home`.
        if (resolved == "~") return "home"
        if (resolved.startsWith("~/")) {
            return joinParts(splitPathParts(resolved.removePrefix("~/"))).ifBlank { "shell" }
        }

        // Outside home (or home unknown) → absolute path parts joined by `-`.
        return joinParts(splitPathParts(resolved)).ifBlank { "shell" }
    }

    private fun splitPathParts(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() && it != "." }

    private fun joinParts(parts: List<String>): String =
        parts.map { sanitisePart(it) }.filter { it.isNotEmpty() }.joinToString("-")

    /**
     * Normalise a single path component to tmux-safe characters, mirroring
     * tmuxctl: `.`/`:` collapse to `_` first (tmux forbids `.` and `:` in
     * session names), then any other disallowed run collapses to `-`, then
     * strip leading/trailing `-`.
     */
    private fun sanitisePart(part: String): String =
        part
            .replace(Regex("[.:]+"), "_")
            .replace(Regex("[^A-Za-z0-9_-]+"), "-")
            .trim('-')

    /**
     * Append the smallest `-<n>` (n ≥ 2) that is free in [existingNames].
     * Deterministic and human-readable, unlike the old random suffix.
     */
    private fun disambiguate(candidate: String, existingNames: Set<String>): String {
        val base = candidate.ifBlank { "shell" }
        if (base !in existingNames) return base
        var n = 2
        while ("$base-$n" in existingNames) n++
        return "$base-$n"
    }
}
