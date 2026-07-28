package com.pocketshell.core.tmux

/**
 * Issue #1820: build tmux `-t` targets that mean **exactly the session we
 * named**, never a sibling that merely starts with the same characters.
 *
 * ## Why this exists
 *
 * A bare `-t <name>` is NOT an exact match. tmux resolves a session target in
 * three escalating steps — exact name, then **name prefix**, then `fnmatch(3)`
 * pattern — so with only `foo-2` alive, `tmux has-session -t foo` **exits 0**
 * and every other verb happily operates on `foo-2`. Verified on real tmux 3.4:
 *
 * ```
 * $ tmux list-sessions -F '#{session_name}'      -> foo-2
 * $ tmux has-session   -t 'foo'   ; echo $?      -> 0   # WRONG: prefix match
 * $ tmux has-session   -t '=foo'  ; echo $?      -> 1   # correct: free
 * $ tmux set-option    -t 'foo'  @ps_test LEAK   -> 0
 * $ tmux show-options -v -t '=foo-2:' @ps_test   -> LEAK   # landed on foo-2
 * $ tmux kill-window   -t 'foo:0'                -> killed foo-2's window
 * ```
 *
 * That is not a theoretical hazard: `<base>` + `<base>-2` is the *intended,
 * routine* outcome of the #1820 host-side unique-name resolution, so every
 * bare-`-t` call site becomes reliably wrong for the OLDER sibling — Stop
 * reporting "still running" for a session it just killed (closed issue #168
 * returning), Rename reporting "was not renamed", a recorded agent kind written
 * onto the neighbour, a prompt typed into the neighbour's pane.
 *
 * ## The two forms — and the ONE rule that picks between them
 *
 * The `=` marker is honoured only by tmux's **session** lookup, and a target
 * string reaches that lookup only through the part before the `:`. So:
 *
 * > **A verb takes the bare `=name` form ONLY if the verb resolves `-t` as a
 * > target-SESSION. Everything else — target-pane, target-window, and the
 * > verbs that merely *report on* a session — needs the `:` so the name is
 * > split off and read by the session lookup.**
 *
 * | how the verb resolves `-t`                        | exact form            | verbs                                                                                              |
 * |---------------------------------------------------|-----------------------|----------------------------------------------------------------------------------------------------|
 * | target-**session**                                | [session] `=name`     | `has-session`, `kill-session`, `rename-session`, `list-windows`                                     |
 * | target-**pane**                                   | [pane] `=name:`       | `send-keys`, `set-option`, `set-window-option`, `show-options`, `capture-pane`, `display-message`, **`list-panes` (incl. `-s`)** |
 * | target-**window**                                 | [window] `=name:<i>`  | `kill-window`                                                                                        |
 *
 * **Do NOT file a verb by how its name or flags read, and do NOT assume a wrong
 * form fails loudly.** `list-panes -s` is the trap this table exists to close:
 * `-s` means "list every pane in the SESSION", so it reads like a target-session
 * verb — but `-s` only widens what gets *listed*, it does not change how `-t` is
 * *resolved*. `list-panes` keeps a pane/window lookup, so `=name` is silently
 * IGNORED and the prefix match still happens. Verified on tmux 3.4 with `aaa`
 * (current) and `zzz-2` alive and `zzz` gone — so this is a genuine prefix
 * match, not a fall-back to the current session:
 *
 * ```
 * $ tmux list-panes -s -t 'zzz'    -F '#{session_name}' -> zzz-2   rc=0  # prefix match
 * $ tmux list-panes -s -t '=zzz'   -F '#{session_name}' -> zzz-2   rc=0  # `=` IGNORED
 * $ tmux list-panes -s -t '=zzz:'  -F '#{session_name}' -> can't find session: zzz  rc=1
 * ```
 *
 * and the pane form does not cost `-s` its whole-session semantics — with
 * `foo-2` spanning two windows, `-t 'foo-2'` and `-t '=foo-2:'` both return all
 * three panes.
 *
 * `set-window-option` behaves the same way, and there the silent match is a
 * WRITE onto the neighbour:
 *
 * ```
 * $ tmux set-window-option -t '=zzz'  window-size latest  -> rc=0     # `=` IGNORED
 * $ tmux show-options -w -v -t '=zzz-2:' window-size      -> latest   # wrote to zzz-2
 * $ tmux set-window-option -t '=zzz:' window-size latest  -> rc=1 "no such window: =zzz:"
 * ```
 *
 * Most target-pane verbs DO reject the bare `=name` outright — `set-option`,
 * `show-options` (`no such session: =foo`), `send-keys`, `capture-pane` (`can't
 * find pane: =foo`) — because the `=` is only stripped once the string has been
 * split on `:`. But the two verbs above show that "rejected outright" is NOT
 * guaranteed: the ones that resolve `-t` to a WINDOW fall through to a session
 * lookup that prefix-matches anyway. That is exactly why the rule is about how
 * the verb resolves `-t`, not about what error you happen to see.
 *
 * Appending the empty window component (`=name:`) selects the session's CURRENT
 * window and active pane — i.e. exactly what a bare `name` selects, minus the
 * prefix matching. Verified identical on tmux 3.4:
 *
 * ```
 * $ tmux display-message -p -t '=foo:' '#{session_name}:#{window_index}.#{pane_index}' -> foo:1.0
 * $ tmux display-message -p -t 'foo'   '#{session_name}:#{window_index}.#{pane_index}' -> foo:1.0
 * ```
 *
 * These claims are not documentation-only: `SiblingSuffixExactTargetDockerTest`
 * pins ONE contract test per row of that table against a REAL tmux server, so a
 * wrong rule here fails a gate instead of quietly spreading to the next site.
 *
 * ## Usage
 *
 * These return the RAW target string; the caller still quotes it with whatever
 * shell-quoting helper it already uses, so a session name containing spaces or
 * shell metacharacters stays a single argument.
 *
 * ```kotlin
 * session.exec("tmux has-session -t ${shellQuote(TmuxTarget.session(name))}")
 * session.exec("tmux send-keys -t ${shellQuote(TmuxTarget.pane(name))} $cmd Enter")
 * session.exec("tmux kill-window -t ${shellQuote(TmuxTarget.window(name, idx))}")
 * ```
 *
 * Note the ONE case that must NOT go through here: `new-session -s <name>` and
 * `rename-session <newName>` take a session **name to create**, not a target to
 * resolve, so they are written verbatim. Pane IDs (`%3`) and window IDs (`@3`)
 * are already unambiguous and must not be `=`-prefixed either.
 */
public object TmuxTarget {

    /**
     * Exact **target-session** for `has-session` / `kill-session` /
     * `rename-session` / `list-windows`.
     */
    public fun session(sessionName: String): String = "=$sessionName"

    /**
     * Exact **target-pane** (session's current window, active pane) for
     * `send-keys` / `set-option` / `set-window-option` / `show-options` /
     * `capture-pane` / `display-message` — and for **`list-panes`, including
     * `list-panes -s`**, whose `-s` widens the LISTING without changing how
     * `-t` resolves.
     *
     * The trailing `:` is load-bearing — see the class KDoc. Without it tmux
     * either fails the lookup outright (`send-keys`, `show-options`) or, worse,
     * silently ignores the `=` and prefix-matches anyway (`list-panes`).
     */
    public fun pane(sessionName: String): String = "=$sessionName:"

    /** Exact **target-window** `<session>:<index>` for `kill-window`. */
    public fun window(sessionName: String, windowIndex: Int): String = "=$sessionName:$windowIndex"
}
