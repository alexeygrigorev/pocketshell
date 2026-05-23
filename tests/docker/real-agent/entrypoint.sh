#!/usr/bin/env bash
set -euo pipefail

install -d -o root -g root -m 755 /run/sshd
install -d -o testuser -g testuser /workspace/pocketshell

if [[ "${POCKETSHELL_START_REAL_AGENT_SESSIONS:-0}" == "1" ]]; then
  su - testuser -c /usr/local/bin/pocketshell-start-agent-sessions
fi

exec /usr/sbin/sshd -D -e
