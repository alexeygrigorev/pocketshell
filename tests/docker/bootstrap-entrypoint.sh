#!/bin/sh
set -eu

scenario="${POCKETSHELL_BOOTSTRAP_SCENARIO:-ready}"
bin_dir="/opt/pocketshell-bootstrap-bin"
local_bin="/home/testuser/.local/bin"
state_file="/tmp/pocketshell-bootstrap-systemctl-state"
version_file="/tmp/pocketshell-bootstrap-pocketshell-version"

install_tool() {
  tool="$1"
  target_dir="$2"
  install -d -o testuser -g testuser "$target_dir"
  cp "${bin_dir}/${tool}" "${target_dir}/${tool}"
  chmod +x "${target_dir}/${tool}"
  chown testuser:testuser "${target_dir}/${tool}"
}

write_daemon_state() {
  printf '%s %s\n' "$1" "$2" > "$state_file"
  chmod 666 "$state_file"
}

write_pocketshell_version() {
  printf '%s\n' "$1" > "$version_file"
  chmod 666 "$version_file"
}

# Issue #231 (D22 hard-cut): the bootstrapper now probes/installs the single
# unified `pocketshell` CLI instead of the legacy `tmuxctl` + `quse` pair.
case "$scenario" in
  ready)
    for tool in pocketshell systemctl; do
      install_tool "$tool" /usr/local/bin
    done
    write_daemon_state active enabled
    ;;
  uv-install)
    install_tool uv /usr/local/bin
    install_tool systemctl /usr/local/bin
    write_daemon_state active enabled
    ;;
  uv-upgrade)
    for tool in pocketshell uv systemctl; do
      install_tool "$tool" /usr/local/bin
    done
    write_pocketshell_version 0.3.6
    write_daemon_state active enabled
    ;;
  unsupported)
    write_daemon_state inactive disabled
    ;;
  daemon-disabled)
    for tool in pocketshell systemctl; do
      install_tool "$tool" /usr/local/bin
    done
    write_daemon_state active disabled
    ;;
  user-local-path)
    install_tool pocketshell "$local_bin"
    install_tool systemctl /usr/local/bin
    write_daemon_state active enabled
    ;;
  notifications)
    # Issue #1236 (D26): a host with the CLI + tmux ready but the agent
    # stop/idle notification hooks NOT installed — a SILENT host. Enable the
    # fixture's real `hooks` support (marker) and seed a PRE-EXISTING foreign
    # Claude hook so the bootstrap install can be asserted to MERGE
    # non-destructively (the pre-existing hook must survive).
    for tool in pocketshell systemctl; do
      install_tool "$tool" /usr/local/bin
    done
    write_daemon_state active enabled
    install -d -o testuser -g testuser /home/testuser/.claude
    cat > /home/testuser/.claude/settings.json <<'JSON'
{
  "model": "sonnet",
  "hooks": {
    "Stop": [
      { "hooks": [ { "type": "command", "command": "my-preexisting-stop-hook" } ] }
    ]
  }
}
JSON
    chown testuser:testuser /home/testuser/.claude/settings.json
    # Enable the fixture's real hooks behaviour for THIS scenario only.
    touch /home/testuser/.pocketshell-fixture-hooks-enabled
    chown testuser:testuser /home/testuser/.pocketshell-fixture-hooks-enabled
    # Ensure hooks start UNINSTALLED so the app surfaces the "enable
    # notifications" nudge before the install.
    rm -rf /home/testuser/.cache/pocketshell/hooks/.installed
    ;;
  *)
    printf 'unknown PocketShell bootstrap scenario: %s\n' "$scenario" >&2
    exit 64
    ;;
esac

chown -R testuser:testuser /home/testuser/.local 2>/dev/null || true

exec /usr/sbin/sshd -D -e
