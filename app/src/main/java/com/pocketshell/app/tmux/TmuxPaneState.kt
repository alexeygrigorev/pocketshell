package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.ui.TerminalSurfaceState

/**
 * One row in [TmuxSessionViewModel.panes] — a single tmux pane bound to its
 * own Compose [TerminalSurfaceState].
 *
 * Per [D6](../../../../../../../../docs/decisions.md): PocketShell renders
 * exactly one pane at a time in its own [TerminalSurface], swiping
 * left/right between panes inside the current window. The view model
 * materialises this list from `tmux -CC` events
 * ([com.pocketshell.core.tmux.protocol.ControlEvent.WindowAdd] /
 * [com.pocketshell.core.tmux.protocol.ControlEvent.WindowClose] /
 * [com.pocketshell.core.tmux.protocol.ControlEvent.LayoutChange]); the
 * [terminalState] in each row has its
 * [TerminalSurfaceState.attachExternalProducer] already pointed at the
 * pane-filtered `%output` flow ([com.pocketshell.core.tmux.TmuxClient.outputFor]).
 *
 * Identity is by [paneId] alone. tmux pane IDs are stable (`%N`) for the
 * life of a pane and survive layout changes within a window, so reusing the
 * row when only [layout] / [title] / [windowId] change keeps the attached
 * [TerminalSurfaceState] from being torn down and rebuilt on every
 * `%layout-change` notification. (Tearing it down would re-attach the
 * emulator, which loses the visible scrollback.)
 *
 * @property paneId tmux pane identifier, e.g. `%0`. Includes the leading
 *   `%` so it can be passed straight back to tmux in
 *   `send-keys -t %N <bytes>` without re-prefixing.
 * @property windowId tmux window identifier the pane currently lives in,
 *   e.g. `@0`. May change if tmux re-parents a pane (rare in practice but
 *   the protocol permits it).
 * @property windowIndex tmux `#{window_index}` for this pane's window.
 *   Used only for one-shot navigation from the host-detail tree, where
 *   the tree already knows `w0`/`w1` but not the runtime `@N` id yet.
 * @property sessionId tmux session identifier the pane currently lives in,
 *   e.g. `$0`. Same caveat as [windowId].
 * @property title human-readable pane title from
 *   `display-message -p -t %N '#{pane_title}'`. Empty string when tmux
 *   has not yet been queried (initial bootstrap, between events).
 * @property inCopyMode true when tmux reports the pane is in a special
 *   mode (`#{pane_in_mode}`), including copy-mode. Input dispatch exits
 *   that mode before writing so keystrokes reach the foreground process.
 * @property terminalState the Compose-friendly state holder rendering this
 *   pane. Already attached to the [com.pocketshell.core.tmux.TmuxClient]'s
 *   per-pane output flow by [TmuxSessionViewModel]. The view simply hands
 *   this instance to a [com.pocketshell.core.terminal.ui.TerminalSurface].
 */
public data class TmuxPaneState(
    val paneId: String,
    val windowId: String,
    val windowIndex: Int? = null,
    val sessionId: String,
    val title: String,
    val cwd: String = "",
    val currentCommand: String = "",
    // Issue #186: per-window agent detection scopes its process scan to
    // the pane's TTY so a sibling window's JSONL log does not bleed
    // through and light up the Conversation tab on a non-agent window.
    // Carried via `#{pane_tty}` from `list-panes`; e.g. `/dev/pts/3`.
    // Empty when tmux has not yet been queried (initial bootstrap)
    // or when an older tmux fails to emit the field.
    val paneTty: String = "",
    // Epic #821 slice A2: the pane's `#{pane_pid}` — the foreground process
    // pid tmux reports for this pane. Used ONLY for the foreign-session
    // one-shot kind guess: it is sent to the host `pocketshell agents kind`
    // CLI / `agents.kind_for_panes` daemon RPC, which resolves it to a cgroup
    // scope and classifies the agent running there. Recorded sessions never
    // consult it (their kind is the authoritative `@ps_agent_kind`). 0 when
    // tmux has not yet been queried or an older tmux omits the field.
    val panePid: Long = 0L,
    val inCopyMode: Boolean = false,
    val terminalState: TerminalSurfaceState,
    // Issue #423: set when the local terminal surface for this pane has
    // failed to recover after repeated IME/resize/render exceptions inside
    // a short window (a recovery storm). The SSH/tmux transport is still
    // alive — this flag tells the screen to stop re-attaching the broken
    // surface and instead render an actionable "Recreate terminal" error
    // state so the user can retry the surface without reconnecting SSH.
    val surfaceError: Boolean = false,
)
