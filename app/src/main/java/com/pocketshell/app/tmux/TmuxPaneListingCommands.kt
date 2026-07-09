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
    // Appended LAST so older tmux that omit it leave every prior field's
    // index unchanged (the parser tolerates its absence -> panePid 0).
    append("#{pane_pid}'")
}

private fun escapePaneListingSingleQuoted(input: String): String =
    input.replace("'", "'\\''")
