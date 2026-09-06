# Real attach captures for the finger-scroll regression tests (#2555)

These are **verbatim PTY captures**, taken on the maintainer's dev box
(RMTHZ, 2026-09-06) with a `pty.fork()` harness at 100x30 and
`TERM=xterm-256color`, of the first seconds of the four attach paths
PocketShell actually opens. They are the fixtures for
`shared/core-terminal/src/test/java/com/termux/view/TerminalScrollGestureTest.kt`,
whose whole point is that the emulator state a finger drag has to cope with
comes from a real host, not from a hand-written escape string.

| File | Produced by | Resulting emulator state |
|---|---|---|
| `tmux-default-config-attach.bin` | `tmux -f /dev/null attach-session` (tmux 3.4, **stock config**) | alt screen ON, mouse tracking **OFF** |
| `tmux-mouse-on-attach.bin` | `tmux attach-session` on a server that sourced a `set -g mouse on` config | alt screen ON, mouse tracking ON |
| `aplexer-altscreen-attach.bin` | `a attach <id>` (aplexer 0.1.3) onto a session running `less` | alt screen ON, mouse tracking **OFF** |
| `aplexer-normal-attach.bin` | `a attach <id>` (aplexer 0.1.3) onto a session running `bash -l` | alt screen off, mouse tracking off |

The two **OFF** rows are the non-happy-host fixtures issue #2555 needed and
did not have: `mouse` defaults to `off` in tmux, so any host whose
`~/.tmux.conf` does not turn it on lands the app's emulator in
"alternate screen active, mouse tracking inactive" — the state in which
upstream Termux's `doScroll` synthesised `KEYCODE_DPAD_UP`/`DOWN` and fed
arrow keys to whatever was running in the pane.

Regenerate with the harness described above if a future tmux/aplexer changes
its attach prologue; do not hand-edit.
