# Real attach captures for the finger-scroll regression tests (#2555)

These are **verbatim PTY captures**, taken on the maintainer's dev box
(RMTHZ, 2026-09-06) with a `pty.fork()` harness at 100x30 and
`TERM=xterm-256color`, of the first seconds of the aplexer attach paths
PocketShell actually opens. They are the fixtures for
`shared/core-terminal/src/test/java/com/termux/view/TerminalScrollGestureTest.kt`,
whose whole point is that the emulator state a finger drag has to cope with
comes from a real host, not from a hand-written escape string.

| File | Produced by | Resulting emulator state |
|---|---|---|
| `aplexer-altscreen-attach.bin` | `a attach <id>` (aplexer 0.1.4) onto a session running `less` | alt screen ON, mouse tracking **OFF** |
| `aplexer-normal-attach.bin` | `a attach <id>` (aplexer 0.1.4) onto a session running `bash -l` | alt screen off, mouse tracking off |

The alternate-screen row is the non-happy-host fixture issue #2555 needed:
the app's emulator is in "alternate screen active, mouse tracking inactive"
when the attached workload does not request mouse tracking. That is the state
in which upstream Termux's `doScroll` synthesised `KEYCODE_DPAD_UP`/`DOWN` and
fed arrow keys to whatever was running in the session.

Regenerate with the harness described above if a future aplexer changes
its attach prologue; do not hand-edit.
