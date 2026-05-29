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

    /**
     * Live camera QR scanner (issue #129). Reachable from the Host list
     * top-bar actions menu. Hosted by
     * [com.pocketshell.app.hosts.QrScannerScreen]; on success the
     * payload is dispatched through the existing
     * [com.pocketshell.app.hosts.HostListViewModel.importSharedHostPayload]
     * entry point, which unwraps [com.pocketshell.app.hosts.QrChunkCodec]
     * envelopes before importing the SSH host payload.
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
     * bootstrapped host whose `pocketshell` detection succeeded. Reachable
     * from the Settings → Diagnostics row and from the in-session
     * kebab menus in [Session] / [TmuxSession]. Fix B and Fix C add
     * a host-list dashboard strip and a bootstrap-driven CTA — out of
     * scope for this destination.
     */
    data object Usage : AppDestination

    /**
     * Issue #181: client-side AI cost tracker. Sister to [Usage] (which
     * surfaces server-side `pocketshell usage` quotas) — shows lifetime / windowed
     * spend per OpenAI feature based on rows in `ai_api_call_log`.
     * Reachable from Settings → Voice → "AI Costs".
     */
    data object AiCosts : AppDestination

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
     * Per-host "Watched folders" configuration screen — issue #206. Lists
     * [com.pocketshell.core.storage.entity.ProjectRootEntity] rows for
     * the host and lets the user add / edit / delete / reorder them.
     * Reachable from the host overflow menu on the host list and from
     * the Settings → "Watched folders" host picker.
     *
     * SSH connection parameters are optional — they only enable the
     * "Discover from remote" affordance which queries
     * `~/git`, `~/code`, `~/projects` on the remote. When opened from
     * Settings (which has no decrypted passphrase) the destination
     * carries them as null and the discover button degrades to an
     * inline hint that asks the user to connect through the host card
     * first.
     *
     * @property hostId persistent host identifier the screen scopes to.
     * @property hostName user-facing saved-host label shown in the title.
     * @property hostname / [port] / [username] / [keyPath] resolved SSH
     *   connection parameters used by the optional discover button.
     *   Null means "no SSH credentials are available in this navigator
     *   slot" — see [Session] for the analogous routing.
     * @property passphrase already-unlocked key passphrase, if the key is
     *   passphrase-protected. The caller is responsible for zeroing it
     *   after navigation per the existing host-list contract.
     */
    data class WatchedFolders(
        val hostId: Long,
        val hostName: String,
        val hostname: String? = null,
        val port: Int? = null,
        val username: String? = null,
        val keyPath: String? = null,
        val passphrase: CharArray? = null,
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

    /**
     * Per-host folder list — issue #171.
     *
     * The landing destination after a user taps a host card (per the
     * spike's locked decisions). Sessions remain reachable from the flat
     * "Show all sessions on this host" link that the folder screen
     * renders at the bottom.
     *
     * SSH connection parameters are required because the screen
     * issues `tmux list-sessions` and a sibling `list-panes` exec via
     * `SshConnection` to read each session's `pane_current_path` /
     * `session_path` for folder grouping.
     */
    data class FolderList(
        val hostId: Long,
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
    ) : AppDestination

    /**
     * GitHub repos browser — issue #230 (app-side slice of #205).
     *
     * Lists the user's GitHub repositories (via `pocketshell repos list
     * --remote`) joined with the host's cloned repos (`--local`). Tapping
     * a not-yet-cloned repo clones it on the remote and opens a session in
     * the fresh clone; tapping an already-cloned repo opens a session in
     * its existing clone path. Reachable from [FolderList] via the
     * "Browse GitHub repos" affordance.
     *
     * SSH connection parameters are required because the screen issues
     * `pocketshell repos list / clone / open` exec calls via
     * `SshConnection`.
     */
    data class RepoBrowser(
        val hostId: Long,
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val cloneRoot: String = "~/git",
    ) : AppDestination

    /**
     * Per-folder `.env` / `.envrc` key management — issue #264.
     *
     * Reached from the "Env" action on a folder row in [FolderList].
     * Backed entirely by the host's `pocketshell env ...` CLI over SSH
     * (no biometric reveal, write-only by default — D24).
     *
     * @property hostId persistent host identifier the screen scopes to.
     * @property hostName user-facing saved-host label shown in the header.
     * @property hostname / [port] / [username] / [keyPath] / [passphrase]
     *   resolved SSH connection parameters — required because the screen
     *   shells out to `pocketshell env list/get/set/copy` via
     *   `SshConnection`.
     * @property directory absolute remote folder path whose env files are
     *   managed.
     * @property folderLabel user-visible label for [directory].
     * @property copySources the already-discovered folder set (path +
     *   label) the copy flow can read from — sourced from the same data
     *   the folder list shows so the user never types an arbitrary path
     *   (D24). The current folder is filtered out client-side.
     */
    data class EnvFiles(
        val hostId: Long,
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val directory: String,
        val folderLabel: String,
        val copySources: List<Pair<String, String>>,
    ) : AppDestination

    /** Per-session recurring jobs backed by the host's `pocketshell jobs` CLI. */
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
