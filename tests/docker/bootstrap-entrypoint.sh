#!/bin/sh
set -eu

scenario="${POCKETSHELL_BOOTSTRAP_SCENARIO:-ready}"
bin_dir="/opt/pocketshell-bootstrap-bin"
local_bin="/home/testuser/.local/bin"
state_file="/tmp/pocketshell-bootstrap-systemctl-state"

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

case "$scenario" in
  ready)
    for tool in tmuxctl heru agent-log-explorer systemctl; do
      install_tool "$tool" /usr/local/bin
    done
    write_daemon_state active enabled
    ;;
  uv-install)
    install_tool uv /usr/local/bin
    install_tool systemctl /usr/local/bin
    write_daemon_state active enabled
    ;;
  unsupported)
    write_daemon_state inactive disabled
    ;;
  daemon-disabled)
    for tool in tmuxctl heru agent-log-explorer systemctl; do
      install_tool "$tool" /usr/local/bin
    done
    write_daemon_state active disabled
    ;;
  user-local-path)
    for tool in tmuxctl heru agent-log-explorer; do
      install_tool "$tool" "$local_bin"
    done
    install_tool systemctl /usr/local/bin
    write_daemon_state active enabled
    ;;
  *)
    printf 'unknown PocketShell bootstrap scenario: %s\n' "$scenario" >&2
    exit 64
    ;;
esac

chown -R testuser:testuser /home/testuser/.local 2>/dev/null || true

exec /usr/sbin/sshd -D -e
