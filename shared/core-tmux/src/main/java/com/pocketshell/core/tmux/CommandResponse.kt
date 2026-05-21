package com.pocketshell.core.tmux

/**
 * Result of a single command sent over the `tmux -CC` control channel.
 *
 * Every command issued through [TmuxClient.sendCommand] produces a matching
 * `%begin <time> <number> <flags>` / `%end <time> <number> <flags>` pair on
 * the wire — or a `%begin` / `%error` pair on failure. The payload lines
 * between those markers are the command's textual output (e.g. the rows
 * printed by `list-sessions`), and they're collected verbatim here so the
 * caller can parse them however the specific tmux command's output format
 * demands.
 *
 * `output` is line-by-line because that's the granularity tmux emits: each
 * `\n`-terminated line inside the response block becomes one entry. We
 * deliberately don't join them — many tmux commands print one record per
 * line (e.g. `list-windows`) and re-splitting a joined string is more
 * fragile than handing the caller the pre-split list.
 *
 * @property number the `<command-number>` that tmux assigned to this
 *   request, taken from the opening `%begin` line. Useful for log
 *   correlation; tmux generates these sequentially per control-mode client.
 * @property output payload lines emitted by tmux between `%begin` and
 *   `%end` / `%error`, in arrival order. Empty for commands that produce
 *   no output (e.g. `kill-session`).
 * @property isError `true` if the block was closed by `%error` rather than
 *   `%end` — in that case `output` is the error message body tmux wrote
 *   into the response block.
 */
public data class CommandResponse(
    val number: Long,
    val output: List<String>,
    val isError: Boolean,
)
