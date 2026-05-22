#!/bin/sh
set -eu

fixture_dir="${POCKETSHELL_AGENT_FIXTURE_DIR:-/opt/pocketshell-agent-fixtures}"
encoded_cwd="-workspace-pocketshell"

install -d -o testuser -g testuser "/home/testuser/.claude/projects/${encoded_cwd}"
install -d -o testuser -g testuser "/home/testuser/.codex/sessions/2026/05/22"
install -d -o testuser -g testuser "/home/testuser/.local/share/opencode"

cp "${fixture_dir}/claude-session.jsonl" "/home/testuser/.claude/projects/${encoded_cwd}/pocketshell-claude.jsonl"
cp "${fixture_dir}/codex-session.jsonl" "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl"
cp "${fixture_dir}/opencode-rows.jsonl" "/home/testuser/.local/share/opencode/pocketshell-rows.jsonl"
chown -R testuser:testuser /home/testuser/.claude /home/testuser/.codex /home/testuser/.local
touch \
  "/home/testuser/.claude/projects/${encoded_cwd}/pocketshell-claude.jsonl" \
  "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl" \
  "/home/testuser/.local/share/opencode/pocketshell-rows.jsonl"

sh -c 'while true; do sleep 3600; done' claude-fixture &
sh -c 'while true; do sleep 3600; done' codex-fixture &
sh -c 'while true; do sleep 3600; done' opencode-fixture &

exec /usr/sbin/sshd -D -e
