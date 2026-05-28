# tmux Default Socket Recovery

PocketShell must not compete with tests or coding agents for the maintainer's
default tmux socket at `/tmp/tmux-$UID/default`. Automation that needs tmux
uses an isolated namespace unless the maintainer explicitly asks to use the
default socket. Use `tmux -L`, an explicit `tmux -S` socket, or an isolated
`TMUX_TMPDIR`.

## Detect Split Brain

Default-socket split brain means an older tmux server is still alive. The
filesystem socket path now points at a different server. Bare `tmux ls` then
shows only the newer server, while old panes continue running but are stranded.

Start with the current default server:

```console
$ uid=$(id -u)
$ sock="${TMUX_TMPDIR:-/tmp}/tmux-$uid/default"
$ tmux display-message -p '#{pid} #{socket_path} #{session_name}' 2>/dev/null
$ tmux ls 2>/dev/null
```

Look for more than one tmux listener that reports the same socket path:

```console
$ current_pid=$(tmux display-message -p '#{pid}' 2>/dev/null || true)
$ ss -xlpn | rg "$sock|$current_pid"
```

If the socket file is missing, check whether an old tmux server still appears
in process or socket listings. Do not run `tmux new-session -A` or
`tmux -CC new-session -A` in that state. Those commands can create a fresh
default server and deepen the split brain.

```console
$ [ -S "$sock" ] || ss -xlpn | rg "tmux: server|$sock"
$ ps -eo pid,ppid,lstart,stat,cmd | rg 'tmux(: server| -CC| new-session)'
```

## Recover Safely

tmux can recreate a removed socket when the server receives `SIGUSR1`. If the
default path is already occupied by a newer server, move the current socket
aside first. Otherwise `SIGUSR1` can make the default path point back at the
old server and strand the newer one.

This flow preserves both servers.

Replace the PIDs with the values from the detection commands:

```console
$ uid=$(id -u)
$ sockdir="${TMUX_TMPDIR:-/tmp}/tmux-$uid"
$ current="$sockdir/default"
$ new="$sockdir/default.current-NEWPID"
$ old="$sockdir/default.recovered-OLDPID"

$ tmux display-message -p '#{pid} #{socket_path} #{session_name}'
$ ss -xlpn | rg "$current|NEWPID|OLDPID"

$ mv "$current" "$new"
$ kill -USR1 OLDPID
$ tmux -S "$current" ls

$ mv "$current" "$old"
$ mv "$new" "$current"

$ tmux -S "$current" ls
$ tmux -S "$old" ls
```

After this, the normal default socket still points at the newer server, and the
old server is reachable through `default.recovered-OLDPID`.

## Commands To Avoid

Do not run destructive tmux or socket cleanup commands while preserving work:

```console
$ tmux kill-server
$ pkill tmux
$ kill OLDPID
$ rm -rf /tmp/tmux-$UID
$ rm /tmp/tmux-$UID/default
```

Also avoid bare default-socket session creation while the default socket is
missing or suspicious:

```console
$ tmux new-session -A -s ...
$ tmux -CC new-session -A -s ...
```

## Automation Guardrail

Tests, orchestrators, and agents use isolated tmux namespaces by default:

```console
$ tmux -L "pocketshell-$RUN_ID" new-session -d -s test
$ tmux -S "/tmp/pocketshell-tmux-$RUN_ID.sock" new-session -d -s test
$ TMUX_TMPDIR="$(mktemp -d)" tmux new-session -d -s test
```

Use `/tmp/tmux-$UID/default` only when the maintainer explicitly asks for a
live default-socket repro or recovery task.
