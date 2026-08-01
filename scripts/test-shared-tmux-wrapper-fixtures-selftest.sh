#!/usr/bin/env bash
# Self-test the shared tmux-wrapper consumer discovery without optional tools.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_GUARD="$SCRIPT_DIR/test-shared-tmux-wrapper-fixtures.sh"

fail() {
  printf 'SELFTEST FAIL: %s\n' "$*" >&2
  exit 1
}

[[ -x "$REAL_GUARD" ]] || fail "missing executable guard: $REAL_GUARD"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
repo="$tmp_dir/repo"
minimal_bin="$tmp_dir/minimal-bin"
mkdir -p "$repo/scripts" "$repo/tests/docker" "$minimal_bin"
cp "$REAL_GUARD" "$repo/scripts/test-shared-tmux-wrapper-fixtures.sh"
chmod +x "$repo/scripts/test-shared-tmux-wrapper-fixtures.sh"

# Model ubuntu-latest's required baseline tools while deliberately excluding
# ripgrep. The guard must not depend on optional developer-machine tooling.
for tool in dirname find grep sort; do
  tool_path="$(command -v "$tool")"
  [[ -x "$tool_path" ]] || fail "required baseline tool is unavailable: $tool"
  ln -s "$tool_path" "$minimal_bin/$tool"
done
if PATH="$minimal_bin" /bin/bash -c 'command -v rg' >/dev/null 2>&1; then
  fail "minimal PATH unexpectedly exposes rg"
fi

cat > "$repo/tests/docker/Dockerfile.agents" <<'DOCKERFILE'
FROM scratch
RUN mv /usr/bin/tmux /usr/bin/tmux.real
COPY tests/docker/agent-bin/ /usr/local/bin/
RUN ln -sf /usr/local/bin/tmux /usr/bin/tmux
DOCKERFILE

valid_output="$tmp_dir/valid-output.log"
PATH="$minimal_bin" /bin/bash "$repo/scripts/test-shared-tmux-wrapper-fixtures.sh" \
  >"$valid_output" 2>&1 \
  || { cat "$valid_output" >&2; fail "guard failed without rg on a valid consumer"; }
grep -Fq 'Static shared-tmux invariant passed for 1 consumers.' "$valid_output" \
  || { cat "$valid_output" >&2; fail "guard did not discover the valid consumer"; }
printf '  ok: no-rg PATH discovered and accepted the valid consumer\n'

# Add a fourth-style future consumer after the first run. Discovery must be
# dynamic, and the missing delegate/symlink invariant must be load-bearing.
cat > "$repo/tests/docker/Dockerfile.future-shared-wrapper" <<'DOCKERFILE'
FROM scratch
COPY tests/docker/agent-bin/tmux /usr/local/bin/tmux
DOCKERFILE

broken_output="$tmp_dir/broken-output.log"
set +e
PATH="$minimal_bin" /bin/bash "$repo/scripts/test-shared-tmux-wrapper-fixtures.sh" \
  >"$broken_output" 2>&1
broken_rc=$?
set -e
[[ "$broken_rc" -ne 0 ]] || fail "guard accepted a dynamically added broken consumer"
grep -Fq 'Dockerfile.future-shared-wrapper copies the shared tmux shim but does not preserve real tmux' \
  "$broken_output" \
  || { cat "$broken_output" >&2; fail "guard failed for the wrong reason"; }
printf '  ok: dynamically added broken consumer rejected (rc=%d)\n' "$broken_rc"

printf 'Shared tmux-wrapper discovery self-test passed without rg.\n'
