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
 * (attach-if-exists), which PocketShell already uses.
 *
 * ## This object NEVER decides uniqueness (issue #1820, D22 hard cut)
 *
 * It used to accept an `existingNames` set and append `-2`/`-3`/… itself
 * (`derive(…)` + a private `disambiguate(…)`, both DELETED). That set came
 * from a screen's UI cache, which could be stale or — whenever the picker/tree
 * was not `Ready` — simply empty, and the "second session in this folder" then
 * silently asked for a name that was already taken. Uniqueness is now decided
 * ONCE, on the host, at create time:
 * [FolderListGateway.createSession] with [SessionNamePolicy.UniqueOnHost].
 *
 * The parameter is GONE rather than defaulted, deliberately: a defaulted
 * `existingNames = emptySet()` is exactly how a future caller silently inherits
 * the superseded semantics. There is no client-side second opinion to express.
 *
 * This object is intentionally pure (no Android / SSH dependencies) so the
 * convention is unit-testable on the JVM and so it does not need to reach
 * into the session-discovery gateway/viewmodel.
 */
internal object SessionNameDerivation {

    /**
     * Resolve the final tmux session name for a NEW session, honouring an
     * optional user-entered custom label (issue #1184).
     *
     * The directory-derived default ([baseName]/#429/#642) is preserved as the
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
     *
     * The result is the BASE name. Issue #1820: this function does NOT append
     * a `-2`/`-3` collision suffix and takes no `existingNames` — a duplicate
     * label is resolved against the host's live session list at create time
     * ([SessionNamePolicy.UniqueOnHost]), which is the only place that can
     * answer "is this name free?" correctly.
     */
    fun resolveSessionName(
        customName: String?,
        startDirectory: String,
        homeDirectory: String?,
    ): String {
        val custom = customName
            ?.let { sanitiseName(it) }
            ?.takeIf { name -> name.any(Char::isLetterOrDigit) }
        return (custom ?: baseName(startDirectory, homeDirectory)).ifBlank { "shell" }
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

    // Issue #1820, D22 hard cut: `derive(startDirectory, homeDirectory,
    // agentCommand, existingNames)` and the private `disambiguate(candidate,
    // existingNames)` are DELETED. `derive` had no production caller left once
    // `derivedSessionName` dropped `existingNames`, and `disambiguate` was the
    // last place a caller could express a client-side uniqueness opinion — with
    // an opt-OUT default, i.e. the exact "next caller silently inherits the
    // superseded semantics" hazard. Callers that want the directory convention
    // use [baseName]; callers that want the final name use [resolveSessionName];
    // uniqueness belongs to the host ([SessionNamePolicy.UniqueOnHost]).
}
