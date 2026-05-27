# Deterministic flaky-disconnect SSH+tmux fixture for PocketShell.
#
# Issue #145: tests/docker/flaky-agent verifies the mid-session disconnect +
# reconnect path. The fixture accepts SSH, allows the client to attach to
# a tmux session and run 1-2 commands, then forcibly kills the SSH child
# process after a configurable delay (default 8s, env
# `FLAKY_DISCONNECT_AFTER_SEC`). The disconnect is wire-level: the kernel
# tears the channel down so the client observes an end-of-stream, not a
# clean tmux detach.
#
# Mirrors `Dockerfile.agents` for the parts that matter (alpine + sshd +
# tmux + tmuxctl shim + testuser fixture) so the fixture key, user, and
# command surface line up with the rest of the test fleet.
#
# `tmuxctl` here is the same shape of static shim used by the
# `agents` fixture (just hardcoded to return `flaky-main`) — the
# host-picker's `HostTmuxSessionsGateway` probes `tmuxctl list` first
# and uses the parsed `IDX SESSION CREATED` table directly, so the
# picker surfaces the seeded session within ~200ms of the first SSH
# probe instead of falling through to `tmux list-sessions -F '...'`
# (whose `\t` literals are not interpreted by tmux's `-F` flag and
# would be parsed as a single field).
#
# The entrypoint seeds a long-lived `flaky-main` tmux session BEFORE
# starting sshd so any tmux-CC attach against the seeded name lands
# on a live tmux server instead of having to spin one up under the
# disconnect watcher.
FROM alpine:latest

RUN apk add --no-cache openssh-server tmux procps bash coreutils \
    && ssh-keygen -A \
    && adduser -D -s /bin/sh testuser \
    && passwd -u testuser \
    && mkdir -p /home/testuser/.ssh \
    && chmod 700 /home/testuser/.ssh

COPY sshd_config /etc/ssh/sshd_config
COPY test_key.pub /home/testuser/.ssh/authorized_keys
COPY flaky-agent/entrypoint.sh /usr/local/bin/pocketshell-flaky-agent-entrypoint
COPY flaky-agent/flaky-disconnect-watcher.sh /usr/local/bin/pocketshell-flaky-disconnect-watcher
COPY flaky-agent/tmuxctl /usr/local/bin/tmuxctl

RUN chmod 600 /home/testuser/.ssh/authorized_keys \
    && chmod +x /usr/local/bin/pocketshell-flaky-agent-entrypoint \
                /usr/local/bin/pocketshell-flaky-disconnect-watcher \
                /usr/local/bin/tmuxctl \
    && chown -R testuser:testuser /home/testuser/.ssh

# Default disconnect-after value. Overridable via the compose `environment`
# block (`FLAKY_DISCONNECT_AFTER_SEC=12`) so individual tests can tune the
# blip window without rebuilding the image.
ENV FLAKY_DISCONNECT_AFTER_SEC=8

# Name of the tmux session the entrypoint seeds. The reconnect E2E test
# attaches to this name; if you change it you must also update
# `SshReconnectE2eTest.sessionName`.
ENV FLAKY_SEEDED_SESSION_NAME=flaky-main

EXPOSE 22

CMD ["/usr/local/bin/pocketshell-flaky-agent-entrypoint"]
