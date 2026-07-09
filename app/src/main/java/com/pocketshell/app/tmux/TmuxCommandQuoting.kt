package com.pocketshell.app.tmux

/**
 * Quote a string for inclusion inside single quotes in a tmux command line.
 * tmux's command parser uses POSIX-shell-ish single quoting: everything
 * between the outer pair of `'...'` is literal except the `'` character
 * itself, which must be closed and re-opened (`'\''`).
 */
internal fun escapeSingleQuoted(input: String): String =
    input.replace("'", "'\\''")
