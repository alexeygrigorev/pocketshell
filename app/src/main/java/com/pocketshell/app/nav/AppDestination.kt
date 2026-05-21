package com.pocketshell.app.nav

/**
 * Simple Compose state-based navigator destinations.
 *
 * `androidx.navigation:navigation-compose` is intentionally not on the
 * version catalog (the brief for #18 forbids new `libs.versions.toml`
 * entries). With only four screens this issue ships, a sealed-class
 * state machine and a `remember { mutableStateOf(...) }` in
 * [com.pocketshell.app.MainActivity] is functionally equivalent — same
 * back-stack semantics, an order of magnitude less plumbing. The trade-off
 * is that we hand-roll deep links and saved-state restoration; neither
 * are in scope for Phase 1.
 *
 * Phase 2+ may revisit if the navigator grows nested graphs or process
 * death is too painful — the issue body explicitly calls out the swap
 * cost as low: "the next issue that needs deep links / saved state can
 * land `navigation-compose` and re-route through it".
 */
sealed interface AppDestination {

    /** Initial / landing destination — the saved-host list. */
    data object HostList : AppDestination

    /** Add a new host. `hostId` is null. */
    data object AddHost : AppDestination

    /** Edit the host identified by [hostId]. */
    data class EditHost(val hostId: Long) : AppDestination

    /** Manage SSH keys (add / list / delete). */
    data object SshKeys : AppDestination

    /**
     * Open a live session for the host identified by [hostId].
     *
     * [keyPath] is the resolved absolute path of the host's private key on
     * disk (read from the corresponding [com.pocketshell.core.storage.entity.SshKeyEntity]
     * by `HostListScreen`). The session screen does not look it up itself
     * — it consumes the resolved values directly.
     */
    data class Session(
        val hostId: Long,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
    ) : AppDestination

    /**
     * Open a live `tmux -CC` session — see [com.pocketshell.app.tmux.TmuxSessionScreen].
     *
     * Distinct from [Session] (which is plain-SSH per #18). Per
     * [D5](../../../../../../../../docs/decisions.md) / [D6] tmux drives
     * per-pane rendering with swipe navigation; the screen materialises
     * one [com.pocketshell.app.tmux.TmuxPaneState] per tmux pane and binds
     * each to its own [com.pocketshell.core.terminal.ui.TerminalSurface].
     *
     * Issue #45 only wires this destination; the host picker swap from
     * [Session] → [TmuxSession] (so opening a host that has tmux installed
     * lands here automatically) is the follow-up #48 lifecycle work.
     *
     * @property hostId persistent host identifier — same shape as
     *   [Session.hostId]. Kept on the destination so future deep-link
     *   restoration can re-resolve the host without re-opening the picker.
     * @property hostname / [port] / [username] / [keyPath] resolved SSH
     *   connection parameters; same fields as [Session].
     * @property sessionName tmux session name to attach to (or create via
     *   `new-session -A -s`). Today defaulted by the caller to
     *   `"pocketshell"` to match
     *   [com.pocketshell.core.tmux.TmuxClientFactory]'s default.
     */
    data class TmuxSession(
        val hostId: Long,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val sessionName: String,
    ) : AppDestination
}
