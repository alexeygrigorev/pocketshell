package com.pocketshell.app.tmux

/**
 * Quote a string for inclusion inside single quotes in a tmux command line.
 * tmux's command parser uses POSIX-shell-ish single quoting: everything
 * between the outer pair of `'...'` is literal except the `'` character
 * itself, which must be closed and re-opened (`'\''`).
 *
 * NOTE: single-quoting alone is NOT sufficient for an argument that carries
 * arbitrary user text — see [tmuxQuotedArgument], which every payload-bearing
 * tmux argument must use instead.
 */
internal fun escapeSingleQuoted(input: String): String =
    input.replace("'", "'\\''")

/**
 * Issue #1845 — quote [value] as ONE complete tmux command argument,
 * including the surrounding single quotes.
 *
 * ## Why single-quoting is not enough
 *
 * Our tmux commands run as `tmux <cmd> ... '<value>'` through the remote
 * shell, so the shell strips the quotes and tmux receives `<value>` as one
 * `argv` element. tmux then parses its OWN `argv` for command separators, and
 * that rule inspects the LAST CHARACTER of every element
 * (`cmd_parse_from_arguments`): a trailing `;` is DELETED and starts a new
 * command, unless the character before it is a `\`, which makes it a literal
 * `;` instead.
 *
 * So `tmux send-keys -l -t %0 -- 'abc;'` types `abc` — the trailing `;` is
 * silently swallowed and tmux still reports success. A `;` anywhere else in
 * the argument is already literal, and a trailing `\` is untouched; only the
 * final character is special.
 *
 * That is the #1845 defect. `EmulatorWorkflowE2eTest` types
 * `d=<dir>;mkdir -p $d;cd $d;printf '<...>';pwd` in 4-character chunks, so the
 * chunks that happen to END on a `;` (`e0r;`, ` $d;`) lost it while chunks with
 * the `;` in the middle (`d;pr`, `;pwd`) kept it. On a real device the same
 * thing happens whenever a per-pane input batch boundary
 * ([TmuxPaneInputQueue.takeBatch] batches by arrival timing) falls right after
 * a typed `;` — which is why the loss position looked random.
 *
 * ## The escape
 *
 * tmux's own escape for a literal trailing `;` is `\;`, and because only the
 * final two characters are inspected, inserting exactly one `\` before a
 * trailing `;` is correct for every input — including a value that genuinely
 * ends in `\;` (sent as `\\;`, which tmux resolves back to `\;`).
 *
 * The `\` is inserted BEFORE the single-quote escaping and carries no `'`, so
 * the two escapes never interact. The result is also a valid POSIX shell
 * single-quoted word, so it can be embedded in a `session.exec("tmux ...")`
 * command line unchanged.
 */
internal fun tmuxQuotedArgument(value: String): String {
    val separatorSafe = if (value.endsWith(';')) value.dropLast(1) + "\\;" else value
    return "'" + escapeSingleQuoted(separatorSafe) + "'"
}
