package com.pocketshell.app.tmux

internal fun buildTmuxPaneListingCommand(sessionName: String?): String = buildString {
    // pane_index is appended last so we can sort within a window.
    // tmux can change index order on layout-rotate commands, so we
    // re-read it on every reconcile.
    //
    // Per #158: include `-s` so we list panes across every window
    // in the session, not only the current window.
    append("list-panes ")
    if (sessionName != null) {
        append("-s -t '${escapePaneListingSingleQuoted(sessionName)}' ")
    }
    append("-F ")
    // Issue #186: append `#{pane_tty}` so per-pane agent detection
    // can scope its process scan to the pane's TTY.
    append("'#{pane_id}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{window_id}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{window_index}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{session_id}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{session_name}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{pane_title}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{pane_index}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{pane_current_path}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{pane_current_command}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{pane_tty}")
    append(LIST_PANES_FIELD_SEPARATOR)
    append("#{pane_in_mode}")
    append(LIST_PANES_FIELD_SEPARATOR)
    // Epic #821 slice A2: `#{pane_pid}` feeds the foreign-session one-shot
    // kind guess (`pocketshell agents kind` / `agents.kind_for_panes`).
    append("#{pane_pid}")
    append(LIST_PANES_FIELD_SEPARATOR)
    // Issue #1158 (recurrence of #962/#1057): `#{alternate_on}` is the
    // SERVER-TRUTH alternate-screen-buffer flag (1 while the pane's program
    // holds the DEC-1049 alternate screen — what a full-screen agent TUI does
    // for its whole run; 0 for a plain shell at a prompt). It is the durable
    // detection-INDEPENDENT agent signal for the maintainer's fleet (agent
    // launched directly inside a `@ps_agent_kind=shell` session, live detection
    // never binds). It replaces the inert CLIENT-emulator `isAlternateBufferActive`
    // read: the tmux `-CC` capture-pane seed replays screen TEXT onto the CLIENT's
    // MAIN buffer and an idle agent emits no fresh `?1049h`, so the client
    // emulator never sees the alt buffer — but the SERVER always knows, and
    // `list-panes` reports it truthfully on every reconcile. Appended LAST so
    // older tmux that omit it leave every prior field's index unchanged (the
    // parser tolerates its absence -> alternateOn false).
    append("#{alternate_on}'")
}

private fun escapePaneListingSingleQuoted(input: String): String =
    input.replace("'", "'\\''")
