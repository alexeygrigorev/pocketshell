#!/bin/sh
set -eu

fixture_dir="${POCKETSHELL_AGENT_FIXTURE_DIR:-/opt/pocketshell-agent-fixtures}"
encoded_cwd="-workspace-pocketshell"

install -d -o testuser -g testuser "/home/testuser/.claude/projects/${encoded_cwd}"
install -d -o testuser -g testuser "/home/testuser/.codex/sessions/2026/05/22"
install -d -o testuser -g testuser "/home/testuser/.local/share/opencode"

# Seed discoverable agent profiles (#718 / #732) so a real `pocketshell
# profiles list --json` discovery journey finds something to select. The
# default ~/.claude dir → the "Claude" default profile; a sibling ~/.zlaude
# dir (the maintainer's Z.AI-routed Claude) → the "Claude (Z.AI)" profile.
# Each needs a real marker file (.claude.json / settings.json) so the
# fixture's profiles branch (mirroring profiles.py's conservative discovery)
# treats the dir as a true config dir rather than a phantom.
for marker_dir in .claude .zlaude; do
  install -d -o testuser -g testuser "/home/testuser/${marker_dir}"
  : > "/home/testuser/${marker_dir}/.claude.json"
  : > "/home/testuser/${marker_dir}/settings.json"
  chown testuser:testuser \
    "/home/testuser/${marker_dir}/.claude.json" \
    "/home/testuser/${marker_dir}/settings.json"
done

cp "${fixture_dir}/claude-session.jsonl" "/home/testuser/.claude/projects/${encoded_cwd}/pocketshell-claude.jsonl"
cp "${fixture_dir}/codex-session.jsonl" "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl"
cp "${fixture_dir}/opencode-rows.jsonl" "/home/testuser/.local/share/opencode/pocketshell-rows.jsonl"
now_ms="$(($(date +%s) * 1000))"
sqlite3 "/home/testuser/.local/share/opencode/opencode.db" <<SQL
CREATE TABLE IF NOT EXISTS project (
  id TEXT PRIMARY KEY,
  worktree TEXT
);
CREATE TABLE IF NOT EXISTS session (
  id TEXT PRIMARY KEY,
  project_id TEXT,
  directory TEXT,
  time_created INTEGER,
  time_updated INTEGER
);
CREATE TABLE IF NOT EXISTS message (
  id TEXT PRIMARY KEY,
  session_id TEXT,
  data TEXT,
  time_created INTEGER,
  time_updated INTEGER
);
CREATE TABLE IF NOT EXISTS part (
  id TEXT PRIMARY KEY,
  message_id TEXT,
  data TEXT,
  time_created INTEGER
);
DELETE FROM part;
DELETE FROM message;
DELETE FROM session;
DELETE FROM project;
INSERT INTO project (id, worktree) VALUES ('opencode-project', '/workspace/pocketshell');
INSERT INTO session (id, project_id, directory, time_created, time_updated)
  VALUES ('opencode-fixture', 'opencode-project', '/workspace/pocketshell', ${now_ms}, ${now_ms});
INSERT INTO message (id, session_id, data, time_created, time_updated)
  VALUES ('opencode-user-1', 'opencode-fixture', '{"role":"user"}', ${now_ms}, ${now_ms});
INSERT INTO part (id, message_id, data, time_created)
  VALUES ('opencode-user-part-1', 'opencode-user-1', '{"type":"text","text":"check the app status"}', ${now_ms});
INSERT INTO message (id, session_id, data, time_created, time_updated)
  VALUES ('opencode-assistant-1', 'opencode-fixture', '{"role":"assistant"}', ${now_ms} + 1, ${now_ms} + 1);
INSERT INTO part (id, message_id, data, time_created)
  VALUES ('opencode-assistant-part-1', 'opencode-assistant-1', '{"type":"output_text","text":"The deterministic fixture is ready."}', ${now_ms} + 1);
SQL
chown -R testuser:testuser /home/testuser/.claude /home/testuser/.codex /home/testuser/.local
touch \
  "/home/testuser/.claude/projects/${encoded_cwd}/pocketshell-claude.jsonl" \
  "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl" \
  "/home/testuser/.local/share/opencode/pocketshell-rows.jsonl" \
  "/home/testuser/.local/share/opencode/opencode.db"

sh -c 'while true; do sleep 3600; done' idle-agent-a &
sh -c 'while true; do sleep 3600; done' idle-agent-b &
sh -c 'while true; do sleep 3600; done' idle-agent-c &

install -d -m 0777 /tmp/pocketshell-fixture-daemon
: > /tmp/pocketshell-fixture-daemon/ready
chmod 0666 /tmp/pocketshell-fixture-daemon/ready
(
  echo "$$" > /tmp/pocketshell-fixture-daemon/pid
  while true; do sleep 3600; done
) &

exec /usr/sbin/sshd -D -e
