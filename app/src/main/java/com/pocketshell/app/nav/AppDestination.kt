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
     * Live camera QR scanner (issue #129). Reachable from the Host list
     * top bar (a new "Scan" tab next to "Import"). Hosted by
     * [com.pocketshell.app.hosts.QrScannerScreen]; on success the
     * payload is dispatched through the existing
     * [com.pocketshell.app.hosts.HostListViewModel.importSharedHostPayload]
     * entry point, which already understands both the legacy
     * single-QR payload and the multi-QR envelope from
     * [com.pocketshell.app.hosts.QrChunkCodec].
     */
    data object Scan : AppDestination

    /** Local, user-shared-only crash reports captured after uncaught exceptions. */
    data object CrashReports : AppDestination

    /**
     * App-level Settings surface introduced in issue #112 — theme,
     * terminal defaults, diagnostics, and About. Reachable from the
     * top bar's gear affordance on [HostList].
     */
    data object Settings : AppDestination

    /**
     * Usage/quota panel introduced in issue #114 (Fix A). Renders
     * [com.pocketshell.app.usage.UsageScreen] populated from every
     * bootstrapped host whose `quse` detection succeeded. Reachable
     * from the Settings → Diagnostics row and from the in-session
     * kebab menus in [Session] / [TmuxSession]. Fix B and Fix C add
     * a host-list dashboard strip and a bootstrap-driven CTA — out of
     * scope for this destination.
     */
    data object Usage : AppDestination

    /** Host chooser opened from Android system forwarding surfaces such as the QS tile. */
    data object PortForwardChooser : AppDestination

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
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
    ) : AppDestination

    /** Per-host auto-forward panel backed by `core-portfwd`. */
    data class PortForwardPanel(
        val hostId: Long,
        val keyPath: String,
        val passphrase: CharArray?,
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
     * @property hostName user-facing saved-host label used by the dashboard
     *   registry and session rows.
     * @property hostname / [port] / [username] / [keyPath] resolved SSH
     *   connection parameters; same fields as [Session].
     * @property sessionName tmux session name to attach to (or create via
     *   `new-session -A -s`). Today defaulted by the caller to
     *   `"pocketshell"` to match
     *   [com.pocketshell.core.tmux.TmuxClientFactory]'s default.
     * @property startDirectory optional start folder passed to tmux
     *   `new-session -A -c` when this destination came from a create flow.
     */
    data class TmuxSession(
        val hostId: Long,
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val sessionName: String,
        val startDirectory: String? = null,
    ) : AppDestination

    /** Per-session recurring jobs backed by the host's `tmuxctl jobs` CLI. */
    data class RecurringJobs(
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val sessionName: String,
    ) : AppDestination
}
