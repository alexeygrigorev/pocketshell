#!/usr/bin/env bash
set -euo pipefail

# Verify that the product agents fixture exercises one real aplexer lifecycle.
# The static half runs without Docker; --docker builds an image and drives the
# same `pocketshell` shim over SSH, including a real PTY attach.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKERFILE="$ROOT_DIR/tests/docker/Dockerfile.agents"
SELFCHECK="$ROOT_DIR/tests/docker/agents-aplexer-selfcheck.py"
SHIM="$ROOT_DIR/tests/docker/agent-bin/pocketshell"
PYPROJECT="$ROOT_DIR/tools/pocketshell/pyproject.toml"

pass_count=0

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

ok() {
  pass_count=$((pass_count + 1))
  printf '  ok  %s\n' "$*"
}

printf 'Static agents-fixture aplexer invariants...\n'

[[ -f "$DOCKERFILE" ]] || fail "missing $DOCKERFILE"
[[ -f "$SELFCHECK" ]] || fail "missing $SELFCHECK"
[[ -x "$SHIM" ]] || fail "missing executable fixture shim $SHIM"

grep -Eq '^FROM[[:space:]]+debian:trixie-slim([[:space:]]|$)' "$DOCKERFILE" \
  || fail "Dockerfile.agents must use the glibc trixie base"
ok "agents image uses the glibc base required by the pinned aplexer release"

grep -Fq 'curl -fsSL -o /usr/bin/a' "$DOCKERFILE" \
  || fail "Dockerfile.agents does not install the bundled a binary"
grep -Fq 'curl -fsSL -o /usr/bin/aplexer' "$DOCKERFILE" \
  || fail "Dockerfile.agents does not install the sibling aplexer worker"
grep -Fq 'sed -n' "$DOCKERFILE" \
  || fail "Dockerfile.agents must derive the release from pyproject.toml"
grep -Fq 'pyproject.toml' "$DOCKERFILE" \
  || fail "Dockerfile.agents no longer reads the production pin"
ok "Dockerfile installs both binaries from the production-derived release"

if grep -Eiq 'tmux|tmuxctl|--backend|pocketshell-fixture-sessions' "$DOCKERFILE"; then
  fail "Dockerfile.agents still carries a retired session backend or helper"
fi
if grep -Eiq 'tmux|tmuxctl|--backend|pocketshell-fixture-sessions' "$SHIM"; then
  fail "the product fixture shim still carries a retired session backend"
fi
ok "agents image and shim contain no product tmux path"

grep -Eq '^RUN su testuser .*pocketshell-fixture-aplexer-selfcheck' "$DOCKERFILE" \
  || fail "the image does not run the aplexer self-check during build"
grep -Fq 'payload.get("schema") != 3' "$SELFCHECK" \
  || fail "the self-check does not assert the schema-3 list contract"
grep -Fq 'sessions", "create' "$SELFCHECK" \
  || fail "the self-check no longer creates through the real CLI"
grep -Fq '"sessions", "kill"' "$SELFCHECK" \
  || fail "the self-check no longer kills through the real CLI"
if grep -Eq "[\"']--version[\"']" "$SELFCHECK"; then
  fail "the self-check uses a version string instead of behaviour"
fi
ok "build-time self-check proves the schema-3 create/list/kill lifecycle"

for journey in \
  "$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/tree/J02SessionTreeListJourney.kt" \
  "$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/terminal/J03AttachAndTypeJourney.kt" \
  "$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/tree/J04CreateSessionJourney.kt" \
  "$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/tree/J14StopSessionJourney.kt"; do
  [[ -f "$journey" ]] || fail "missing journey $journey"
  if grep -Eiq 'tmux|tmuxctl|--backend' "$journey"; then
    fail "journey still names the retired backend: $journey"
  fi
  grep -Fq 'sessions create' "$journey" || fail "journey does not create an aplexer session: $journey"
  grep -Fq 'sessions list' "$journey" || fail "journey does not list an aplexer session: $journey"
done
grep -Fq 'a capture' "$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/terminal/J03AttachAndTypeJourney.kt" \
  || fail "J03 has no independent aplexer screen oracle"
grep -Fq 'sessions attach' "$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/terminal/J03AttachAndTypeJourney.kt" \
  || fail "J03 no longer exercises the attach command"
grep -Fq 'sessions kill' "$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/tree/J14StopSessionJourney.kt" \
  || fail "J14 no longer exercises the kill command"
ok "J02/J03/J04/J14 drive real aplexer create/list/attach/kill paths"

if [[ -e "$ROOT_DIR/tests/docker/agent-bin/tmux" || \
      -e "$ROOT_DIR/tests/docker/agent-bin/tmuxctl" || \
      -e "$ROOT_DIR/tests/docker/agent-bin-daemon/tmuxctl" || \
      -e "$ROOT_DIR/tests/docker/agent-fixtures/tmuxctl-list.txt" || \
      -e "$ROOT_DIR/tests/docker/agent-fixtures/pocketshell-sessions-list.txt" || \
      -e "$ROOT_DIR/tests/docker/agent-bin/pocketshell-fixture-sessions" ]]; then
  fail "retired product fixture files are still present"
fi
ok "retired Docker session shims and canned tmux tables are deleted"

python3 -m py_compile "$SELFCHECK"
bash -n "$ROOT_DIR/scripts/test-agents-fixture-aplexer.sh"
ok "fixture self-check and Docker guard parse"

if [[ $# -eq 0 ]]; then
  printf 'Static invariants passed (%d checks).\n' "$pass_count"
  exit 0
fi
[[ $# -eq 1 && "$1" == "--docker" ]] || fail "usage: $0 [--docker]"

command -v docker >/dev/null || fail "docker is required for --docker"
command -v ssh >/dev/null || fail "ssh is required for --docker"
command -v timeout >/dev/null || fail "timeout is required for --docker"

SUFFIX="${AGENTS_APLEXER_GUARD_SUFFIX:-$$}"
IMAGE="pocketshell-test:agents-aplexer-guard-${SUFFIX}"
CONTAINER="pocketshell-agents-aplexer-guard-${SUFFIX}"
TMP_DIR="$(mktemp -d)"
SESSION_NAME=""
KEY="$TMP_DIR/test_key"

cleanup() {
  if [[ -n "$SESSION_NAME" ]]; then
    ssh_exec pocketshell sessions kill "$SESSION_NAME" --json >/dev/null 2>&1 || true
  fi
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker image rm -f "$IMAGE" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

printf '\nBuilding %s...\n' "$IMAGE"
if ! docker build --file "$DOCKERFILE" --tag "$IMAGE" "$ROOT_DIR" >"$TMP_DIR/build.log" 2>&1; then
  tail -n 60 "$TMP_DIR/build.log" >&2
  fail "docker build failed for Dockerfile.agents"
fi
ok "agents image built with its build-time aplexer lifecycle"

docker run -d --name "$CONTAINER" -p 127.0.0.1::22 "$IMAGE" >/dev/null
SSH_PORT="$(docker port "$CONTAINER" 22/tcp | head -1 | sed 's/.*://')"
[[ -n "$SSH_PORT" ]] || fail "could not read the published SSH port"
cp "$ROOT_DIR/tests/docker/test_key" "$KEY"
chmod 600 "$KEY"
SSH_OPTS=(
  -i "$KEY" -p "$SSH_PORT" -o BatchMode=yes -o ConnectTimeout=5
  -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR
)
ssh_exec() { timeout 90 ssh "${SSH_OPTS[@]}" testuser@127.0.0.1 "$@"; }
ssh_pty() { timeout 90 ssh -tt "${SSH_OPTS[@]}" testuser@127.0.0.1 "$@"; }

for _ in $(seq 1 60); do
  ssh_exec true >/dev/null 2>&1 && break
  sleep 1
done
ssh_exec true >/dev/null 2>&1 || fail "sshd did not become reachable"
ok "agents fixture accepts its SSH key"

ssh_exec /usr/local/bin/pocketshell-fixture-aplexer-selfcheck >/dev/null \
  || fail "build-time aplexer self-check failed when rerun over SSH"
ok "real aplexer self-check passes over SSH"

TAG="guard-${SUFFIX}"
MARKER="GUARD_APLEXER_MARKER_${SUFFIX}"
create_json="$(ssh_exec pocketshell sessions create "$TAG" --cwd /home/testuser --mem none --json)" \
  || fail "pocketshell sessions create failed"
SESSION_NAME="$(printf '%s' "$create_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')"
[[ -n "$SESSION_NAME" ]] || fail "create returned no display name: $create_json"
printf '%s' "$create_json" | grep -Fq '"schema": 3' \
  || fail "create did not return schema 3: $create_json"
if printf '%s' "$create_json" | grep -Eiq 'manager|backend|tmux'; then
  fail "create returned a retired backend discriminator: $create_json"
fi
ok "pocketshell sessions create returned the schema-3 aplexer name $SESSION_NAME"

ssh_exec pocketshell sessions list --json >"$TMP_DIR/list.json" \
  || fail "pocketshell sessions list failed"
python3 - "$TMP_DIR/list.json" "$TAG" "$SESSION_NAME" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
assert payload["schema"] == 3, payload
assert isinstance(payload["errors"], list), payload
rows = [row for row in payload["sessions"] if row.get("tag") == sys.argv[2]]
assert len(rows) == 1, payload
row = rows[0]
assert row["name"] == sys.argv[3], row
assert row["phase"] == "running", row
assert row["alive"] is True, row
assert row["workspace"] == "/home/testuser", row
assert "manager" not in row, row
print("schema-3 row is live and attachable")
PY
ok "a separate SSH connection listed the live aplexer row"

# A real PTY is required for attach. Detach with aplexer's control sequence;
# the independent capture below proves the command ran in the session.
{
  sleep 2
  printf 'echo %s\r' "$MARKER"
  sleep 2
  printf '\002d'
  sleep 1
} | ssh_pty pocketshell sessions attach "$SESSION_NAME" >"$TMP_DIR/attach.log" 2>&1 || true
hits="$(ssh_exec a capture --workspace /home/testuser --tag "$TAG" --screen --plain | grep -c -- "$MARKER" || true)"
[[ "$hits" -ge 2 ]] || {
  sed 's/^/    /' "$TMP_DIR/attach.log" >&2
  fail "sessions attach did not leave the marker on the captured screen"
}
ok "PTY sessions attach reached the real aplexer workload"

ssh_exec pocketshell sessions list --json | grep -Fq "\"tag\": \"$TAG\"" \
  || fail "detaching removed the live aplexer session"
ok "detaching preserved the aplexer session"

kill_json="$(ssh_exec pocketshell sessions kill "$SESSION_NAME" --json)" \
  || fail "pocketshell sessions kill failed"
printf '%s' "$kill_json" | grep -Fq '"killed": true' \
  || fail "kill did not report killed=true: $kill_json"
printf '%s' "$kill_json" | grep -Fq '"reaped": true' \
  || fail "kill did not report reaped=true: $kill_json"
SESSION_NAME=""
ssh_exec pocketshell sessions list --json | grep -Fq "\"tag\": \"$TAG\"" \
  && fail "killed aplexer session remains in the list"
ok "pocketshell sessions kill reaped the live aplexer record"

printf 'Docker agents-fixture aplexer guard passed (%d static + lifecycle checks).\n' "$pass_count"
