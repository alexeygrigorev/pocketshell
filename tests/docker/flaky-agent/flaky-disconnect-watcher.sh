#!/bin/sh
# Per-connection wrapper invoked by sshd's ForceCommand for every accepted
# session. Backgrounds a `sleep N; kill $$` self-destruct and then execs
# the user's login shell. When the timer fires it terminates this wrapper
# (the SSH child's top-level process from sshd's point of view), which
# collapses the SSH channel — the client observes an end-of-stream / EOF
# rather than a graceful tmux detach.
#
# Issue #145.

set -eu

config_file="/etc/pocketshell-flaky-disconnect.conf"
default_after="8"

if [ -r "$config_file" ]; then
    disconnect_after="$(cat "$config_file" 2>/dev/null || echo "$default_after")"
else
    disconnect_after="$default_after"
fi

case "$disconnect_after" in
    ''|*[!0-9]*) disconnect_after="$default_after" ;;
esac

shell="${SHELL:-/bin/sh}"
if [ ! -x "$shell" ]; then
    shell="/bin/sh"
fi

disconnect_log="/var/log/pocketshell-flaky-disconnect.log"

# Issue #145 round 2: the disconnect timer is ONLY applied to interactive
# sessions (where the client opened a shell channel for tmux attach).
# For non-interactive `session.exec("cmd")` calls — which is how the
# PocketShell host picker probes `tmuxctl list` / `tmux list-sessions` —
# we skip the timer entirely and just run the command. This keeps the
# picker probes fast (sub-second) so the test can reach the tmux attach
# path within its picker deadline, while still firing the disconnect
# timer when a real attach happens.
#
# Detection: ssh exports SSH_ORIGINAL_COMMAND for `ssh user@host -- cmd`
# style invocations and leaves it unset for plain `ssh user@host` shell
# logins. PocketShell's `session.exec` always supplies the command, so
# every gateway probe lands in the SSH_ORIGINAL_COMMAND branch. The
# `TmuxClientFactory.create` path that powers the disconnect E2E opens
# a shell + `tmux -CC` instead of an exec'd command, so it lands in
# the interactive branch and gets the watcher.
if [ -n "${SSH_ORIGINAL_COMMAND:-}" ]; then
    echo "POCKETSHELL_FLAKY_AGENT_EXEC pid=$$ disconnect_after_sec=$disconnect_after cmd_len=${#SSH_ORIGINAL_COMMAND}" \
        >> "$disconnect_log" 2>/dev/null
    exec "$shell" -c "$SSH_ORIGINAL_COMMAND"
fi

# Interactive shell path: arm the disconnect timer.
#
# Self-destruct timer. We capture the wrapper's PID BEFORE forking the
# background subshell because `$$` inside a POSIX subshell still expands
# to the parent shell's PID — which is the wrapper script itself, the
# process sshd cleans up the session around when it exits.
#
# The `exec "$shell"` below replaces THIS wrapper process with the shell.
# Crucially, `exec` preserves the PID — the new shell inherits the same
# PID we captured here, so the kill timer fires against the correct
# process even after the handoff. (POSIX exec(3p) guarantees PID
# preservation; verified empirically against alpine ash + bash.)
#
# We escalate from SIGHUP -> SIGTERM -> SIGKILL so the shell flushes
# any pending output (so the client sees tmux's last `%output` lines
# before the EOF), but never gets to ride past the disconnect window
# even if it has SIGTERM trapped. SIGHUP is the natural "your terminal
# went away" signal that interactive shells (ash, bash) handle
# uniformly.
watcher_target=$$
# The background subshell MUST close (redirect to /dev/null) its
# stdout/stderr — if it inherits the parent's SSH session stdout/stderr,
# sshd cannot close the channel until the subshell exits.
(
    sleep "$disconnect_after"
    echo "POCKETSHELL_FLAKY_AGENT_DISCONNECT pid=$watcher_target after_sec=$disconnect_after" \
        >> "$disconnect_log" 2>/dev/null
    kill -HUP "$watcher_target" 2>/dev/null || true
    sleep 0.5
    kill -TERM "$watcher_target" 2>/dev/null || true
    sleep 0.5
    kill -KILL "$watcher_target" 2>/dev/null || true
) </dev/null >/dev/null 2>/dev/null &

# Log marker so artifact reviewers can correlate the disconnect event
# against the per-session wall clock.
echo "POCKETSHELL_FLAKY_AGENT_SESSION_START pid=$$ disconnect_after_sec=$disconnect_after"

exec "$shell" -l
