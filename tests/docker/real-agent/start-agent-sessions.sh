#!/usr/bin/env bash
set -euo pipefail

workspace="${POCKETSHELL_REAL_AGENT_WORKSPACE:-/workspace/pocketshell}"
mkdir -p "$workspace"
cd "$workspace"

start_session() {
  local session_name="$1"
  shift

  if tmux has-session -t "$session_name" 2>/dev/null; then
    return
  fi

  tmux new-session -d -s "$session_name" -c "$workspace" -- "$@"
}

start_session real-claude bash -lc 'claude; exec bash'
start_session real-codex bash -lc 'codex; exec bash'
start_session real-opencode bash -lc 'opencode; exec bash'

tmux list-sessions
