package com.pocketshell.app.projects

/**
 * Derive a tmux session name from the user's picker choice - issue #429.
 *
 * Mirrors the `tmuxctl` (`t`) convention the maintainer already uses on
 * the server: the name encodes the directory (relative to `$HOME` when
 * possible) rather than the old cryptic `<basename>-<6-digit-timestamp>`.
 * See [SessionNameDerivation] for the full convention.
 *
 *  - `~/git/pocketshell` (agent or shell) -> `git-pocketshell`
 *  - `/var/log` (shell)                   -> `var-log`
 *  - `$HOME` itself                       -> `home-<homeBasename>`
 *
 * @param homeDirectory the remote `$HOME` if known, so paths under home
 *   collapse to their home-relative form (and `~` is recognised). May be
 *   `null` when home is unknown, in which case absolute paths are named
 *   from their full components.
 * @param existingNames session names already present on the host. A
 *   genuinely different second session in the same directory gets a
 *   deterministic `-2`, `-3`, ... suffix instead of colliding; an exact
 *   re-pick still attaches via the gateway's `tmux new-session -A`.
 */
internal fun derivedSessionName(
    choice: SessionTypeChoice,
    homeDirectory: String? = null,
    existingNames: Set<String> = emptySet(),
): String = SessionNameDerivation.resolveSessionName(
    // Issue #1184: honour a user-entered custom label when present; a blank
    // custom name falls back to the directory-derived default (#429/#642),
    // and either base is disambiguated against [existingNames].
    customName = choice.customName,
    startDirectory = choice.startDirectory,
    homeDirectory = homeDirectory,
    existingNames = existingNames,
)

/**
 * The directory-derived DEFAULT session name (no collision suffix) used to
 * prefill the "Session name" field in the new-session picker - issue #1184.
 * The picker keeps this in sync with the chosen start folder until the user
 * types their own label; the final collision `disambiguate` still runs at
 * create time in [derivedSessionName].
 */
internal fun defaultSessionBaseName(
    startDirectory: String,
    homeDirectory: String?,
): String = SessionNameDerivation.baseName(startDirectory, homeDirectory)

/**
 * Conventional remote `$HOME` inferred from the SSH [username] - issue
 * #429. The remote home is what `tmuxctl` keys its naming off, but the
 * authoritative value lives on the remote and is not plumbed into this
 * screen yet (#430/#438 own the gateway/viewmodel that would carry it).
 * Until then this gives the correct home for the maintainer's hosts:
 * `root` -> `/root`, anything else -> `/home/<user>`. Returns `null` for a
 * blank username so the deriver falls back to absolute-path naming.
 */
internal fun conventionalRemoteHome(username: String): String? {
    val user = username.trim()
    return when {
        user.isEmpty() -> null
        user == "root" -> "/root"
        else -> "/home/$user"
    }
}

/**
 * Session names already discovered for this host, used so a genuinely new
 * second session in the same directory gets a deterministic `-2`/`-3`
 * suffix rather than colliding (issue #429).
 */
internal fun knownSessionNames(state: FolderListUiState): Set<String> =
    when (state) {
        is FolderListUiState.Ready ->
            state.folders.flatMap { it.sessions }.map { it.sessionName }.toSet()
        else -> emptySet()
    }
