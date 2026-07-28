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
 * The result is the BASE name only. Collision disambiguation (`-2`, `-3`, …)
 * is NOT done here — issue #1820 moved it to
 * [FolderListGateway.createSession] with [SessionNamePolicy.UniqueOnHost],
 * which resolves the free name against the host's live session list at create
 * time. The screens used to pass their own `existingNames` set from a UI cache;
 * that cache could be stale or (when the picker/tree was not `Ready`) simply
 * empty, and then the "second session in this folder" silently requested a
 * colliding name. There is deliberately no client-side second opinion left.
 *
 * @param homeDirectory the remote `$HOME` if known, so paths under home
 *   collapse to their home-relative form (and `~` is recognised). May be
 *   `null` when home is unknown, in which case absolute paths are named
 *   from their full components.
 */
internal fun derivedSessionName(
    choice: SessionTypeChoice,
    homeDirectory: String? = null,
): String = SessionNameDerivation.resolveSessionName(
    // Issue #1184: honour a user-entered custom label when present; a blank
    // custom name falls back to the directory-derived default (#429/#642).
    customName = choice.customName,
    startDirectory = choice.startDirectory,
    homeDirectory = homeDirectory,
)

/**
 * The directory-derived DEFAULT session name (no collision suffix) used to
 * prefill the "Session name" field in the new-session picker - issue #1184.
 * The picker keeps this in sync with the chosen start folder until the user
 * types their own label. Issue #1820: there is no client-side collision step
 * after this — the host resolves the free name at create time
 * ([SessionNamePolicy.UniqueOnHost]).
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

// Issue #1820, D22 hard cut: `knownSessionNames(FolderListUiState)` is DELETED.
// It answered "what names are taken?" from the screen's own UI state, returning
// an EMPTY set for every non-`Ready` state, and callers fed that straight into
// the create name. The host answers that question now, at create time, in
// FolderListGateway with SessionNamePolicy.UniqueOnHost. Nothing client-side
// should re-derive it — two deciders for one fact is the defect this removed.
