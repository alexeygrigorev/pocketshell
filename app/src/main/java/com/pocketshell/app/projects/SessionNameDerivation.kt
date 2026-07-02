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
 * The name is a **pure path-prefix** — agent and shell sessions in the
 * same directory derive the same base name (e.g. `~/git/pocketshell` →
 * `git-pocketshell` for both). This matches what `tmuxctl` /
 * `pocketshell sessions` name the same directory, so desktop navigation
 * stays consistent. The flat list distinguishes agent vs shell via the
 * badge, not the name. There is no agent-CLI decoration (#642, D22
 * hard-cut: the old `claude-…` prefix is removed, no compatibility shim).
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
     * @param agentCommand accepted for call-site convenience (the picker
     *   passes the agent CLI for agent sessions). It no longer affects the
     *   name — the convention is a pure path-prefix shared by agent and
     *   shell sessions (#642). The flat list distinguishes them via the
     *   badge, not the name.
     * @param existingNames names already taken on the host. When the
     *   derived base name is present, a deterministic `-2`, `-3`, … suffix
     *   is appended until a free name is found. Default empty (no
     *   disambiguation), in which case `tmux new-session -A` attaches to
     *   any same-named session rather than overwriting it.
     */
    fun derive(
        startDirectory: String,
        homeDirectory: String?,
        @Suppress("UNUSED_PARAMETER") agentCommand: String?,
        existingNames: Set<String> = emptySet(),
    ): String {
        // Pure path-prefix: the agent CLI no longer decorates the name
        // (#642). Agent and shell sessions in the same directory share the
        // same base; the flat list distinguishes them via the badge.
        val base = baseName(startDirectory, homeDirectory)
        return disambiguate(base, existingNames)
    }

    /**
     * Resolve the final tmux session name for a NEW session, honouring an
     * optional user-entered custom label (issue #1184).
     *
     * The directory-derived default ([derive]/#429/#642) is preserved as the
     * fallback — this ADDS a user override, it does not replace the default
     * (D22 hard-cut: no fork of the naming convention). Behaviour:
     *
     *  - A meaningful [customName] is sanitised to a tmux-safe name via
     *    [sanitiseName] (spaces / `.` / `:` and other disallowed characters
     *    are normalised) and used as the base.
     *  - A blank/`null` [customName] — including one with no real content once
     *    sanitised (all whitespace, or all-punctuation such as `...`/`:::`
     *    that leaves only `_`/`-` separators) — falls back to the
     *    directory-derived [baseName]. "Meaningful" means the sanitised label
     *    contains at least one letter or digit.
     *  - Either way the base is run through [disambiguate] against
     *    [existingNames], so a duplicate label gets a deterministic `-2`,
     *    `-3`, … suffix and can never silently attach to a DIFFERENT
     *    session's tmux.
     */
    fun resolveSessionName(
        customName: String?,
        startDirectory: String,
        homeDirectory: String?,
        existingNames: Set<String> = emptySet(),
    ): String {
        val custom = customName
            ?.let { sanitiseName(it) }
            ?.takeIf { name -> name.any(Char::isLetterOrDigit) }
        val base = custom ?: baseName(startDirectory, homeDirectory)
        return disambiguate(base, existingNames)
    }

    /**
     * Sanitise a whole user-entered custom label to a tmux-safe name (issue
     * #1184), reusing the per-component [sanitisePart] rules: `.`/`:` → `_`
     * (tmux forbids `.` and `:` in session names), any other disallowed run
     * → `-`, then strip leading/trailing `-`. Leading/trailing whitespace is
     * trimmed first. An all-punctuation label sanitises to the empty string,
     * which the caller treats as "fall back to the derived default".
     */
    fun sanitiseName(name: String): String = sanitisePart(name.trim())

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
    internal fun sanitisePart(part: String): String =
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
