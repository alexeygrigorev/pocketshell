#!/bin/sh
# Issue #145: deterministic mid-session disconnect fixture.
#
# Strategy
#
# 1. Pin the disconnect delay into a config file the per-connection wrapper
#    can read at run time. We pick a file (not an env var) because sshd
#    does not propagate arbitrary env to ForceCommand sessions by default,
#    while a world-readable file under /etc is always available to the
#    forced wrapper.
# 2. Drop in an `sshd_config.d/flaky.conf` override that points
#    `ForceCommand` at /usr/local/bin/pocketshell-flaky-disconnect-watcher.
#    The watcher backgrounds a `sleep N; kill $$` self-destruct, then
#    execs the user shell. When the timer fires it sends SIGHUP/SIGTERM/
#    SIGKILL to its own PID (the wrapper IS the SSH session's top-level
#    child from sshd's point of view), which collapses the SSH channel.
# 3. Seed a long-lived `flaky-main` tmux session as the testuser BEFORE
#    starting sshd, so the picker's first SSH probe (which itself runs
#    inside the disconnect watcher) sees the session immediately via
#    `tmuxctl list` / `tmux list-sessions`. The tmux server daemonises
#    on first `new-session`, so it survives all subsequent
#    SSH-channel teardowns.
# 4. Hand off to `sshd -D -e`. Each accepted connection forks a child
#    that runs the watcher, gets ~FLAKY_DISCONNECT_AFTER_SEC seconds of
#    normal interactive work, then is killed.

set -eu

disconnect_after="${FLAKY_DISCONNECT_AFTER_SEC:-8}"

# Defensive clamp: pathological values (<2s) make the fixture useless
# because the SSH+tmux handshake itself takes longer than the disconnect
# window. >60s is also pathological because the test would have to wait
# 60+s before exercising the disconnect — surface the value the operator
# asked for in the logs but pin it to a sane band.
case "$disconnect_after" in
    ''|*[!0-9]*) disconnect_after=8 ;;
esac
if [ "$disconnect_after" -lt 2 ]; then disconnect_after=2; fi
if [ "$disconnect_after" -gt 60 ]; then disconnect_after=60; fi

# World-readable config file the per-connection watcher reads. /etc is
# always present; the file is rewritten on every container start so an
# environment override picks up cleanly.
echo "$disconnect_after" > /etc/pocketshell-flaky-disconnect.conf
chmod 0644 /etc/pocketshell-flaky-disconnect.conf

# Per-connection disconnect log lives in /var/log so reviewers can
# tail it for forensic correlation. Truncate on container start so a
# rebuild starts with an empty trail. The watcher's bg subshell
# appends to this file rather than stderr to avoid holding the SSH
# session's stdout/stderr open (see flaky-disconnect-watcher.sh
# comment for why).
: > /var/log/pocketshell-flaky-disconnect.log
chmod 0666 /var/log/pocketshell-flaky-disconnect.log

# Force every interactive SSH session through the wrapper so the
# disconnect path is invariant to the user's shell preference. Alpine's
# stock sshd does NOT pull in /etc/ssh/sshd_config.d/* (no `Include`
# directive in the base config), so we append the override directly to
# the main config. Idempotent on container restart because the appended
# block is marker-fenced and we strip any previous copy first.
#
# The heredoc is quoted (`<<'EOF'`) so the shell does NOT expand
# backticks / `$var` inside — every byte between the markers is written
# verbatim. This matters because the explanatory comments mention SSH
# command lines that would otherwise be parsed as shell substitutions.
config_file="/etc/ssh/sshd_config"
marker_begin="# >>> pocketshell-flaky-agent (issue 145) >>>"
marker_end="# <<< pocketshell-flaky-agent (issue 145) <<<"
# Strip any previous block on rebuild before re-appending.
if grep -qF "$marker_begin" "$config_file" 2>/dev/null; then
    awk -v b="$marker_begin" -v e="$marker_end" '
        $0 == b { skip = 1; next }
        $0 == e { skip = 0; next }
        skip != 1 { print }
    ' "$config_file" > "$config_file.tmp" && mv "$config_file.tmp" "$config_file"
fi
{
    echo ""
    echo "$marker_begin"
    cat <<'EOF'
ForceCommand /usr/local/bin/pocketshell-flaky-disconnect-watcher
PermitTTY yes
EOF
    echo "$marker_end"
} >> "$config_file"

# Issue #145 round 2: seed the long-lived `flaky-main` tmux session
# before sshd starts so the picker's first SSH probe sees it in
# `tmuxctl list` / `tmux list-sessions`. Without this seed the
# connected test waited 45s for the picker to surface `flaky-main`
# while the gateway was returning `Sessions(emptyList())` — the round-1
# reviewer flagged this as the blocking failure mode.
#
# The seeded session runs `printf READY...; exec sh` in a tmux pane.
# `tmux new-session -d` daemonises the tmux server in the background
# (it survives SSH channel teardown via the disconnect watcher), so
# every subsequent SSH connection sees the server already running and
# `flaky-main` already attached-able.
#
# We seed AS the test user (the session must be owned by testuser so
# the picker's `tmux list-sessions` sees it under the same UID).
# `su-exec` is not in busybox; we use `su -s /bin/sh - testuser -c ...`
# instead which is portable across alpine releases.
seeded_session="${FLAKY_SEEDED_SESSION_NAME:-flaky-main}"
# Make sure the home dir is writable by testuser before tmux opens its
# socket there.
mkdir -p /home/testuser
chown testuser:testuser /home/testuser
su -s /bin/sh - testuser -c "
    tmux kill-session -t '$seeded_session' 2>/dev/null || true
    tmux new-session -d -s '$seeded_session' \
        \"printf 'POCKETSHELL_FLAKY_AGENT_SEEDED_SESSION_READY name=%s\\n' '$seeded_session'; exec sh\"
    tmux list-sessions || true
"
echo "POCKETSHELL_FLAKY_AGENT_SEEDED seeded_session=$seeded_session"

echo "POCKETSHELL_FLAKY_AGENT disconnect_after_sec=$disconnect_after"

exec /usr/sbin/sshd -D -e
