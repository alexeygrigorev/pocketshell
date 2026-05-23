#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/../../.." && pwd)"
compose_file="${script_dir}/compose.yml"
ssh_key="${repo_root}/tests/docker/test_key"
port="${POCKETSHELL_REAL_AGENT_PORT:-2240}"

docker compose -f "$compose_file" up -d --build real-agents

for _ in $(seq 1 30); do
  if ssh -i "$ssh_key" -p "$port" \
      -o BatchMode=yes \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      testuser@127.0.0.1 true 2>/dev/null; then
    break
  fi
  sleep 1
done

ssh -i "$ssh_key" -p "$port" \
  -o BatchMode=yes \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  testuser@127.0.0.1 \
  'set -eu; opencode --version; codex --version; claude --version; pocketshell-start-agent-sessions >/tmp/pocketshell-real-agent-sessions.txt; cat /tmp/pocketshell-real-agent-sessions.txt'
