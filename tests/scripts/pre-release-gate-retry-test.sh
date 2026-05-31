#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

helpers="$tmpdir/pre-release-gate-helpers.sh"
sed -n \
  '/^run_app_walkthrough_script()/,/^docker_agents_pocketshell_version_script()/p' \
  "$ROOT_DIR/scripts/pre-release-confidence-gate.sh" |
  sed '$d' > "$helpers"
# shellcheck source=/dev/null
source "$helpers"

fake_adb="$tmpdir/adb"
cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_file="${FAKE_ADB_STATE:?}"
mode="${FAKE_ADB_MODE:?}"

if [[ "$*" == "logcat -c" ]]; then
  exit 0
fi

if [[ "$*" == "logcat -d -v time -t 5000" ]]; then
  count="$(cat "$state_file" 2>/dev/null || printf '0')"
  if [[ "$mode" == "transport_then_success" && "$count" -le 3 ]]; then
    printf '05-31 23:04:00.186 I/adbd    (29181): host-17: read failed: Success\n'
    printf '05-31 23:04:00.186 I/adbd    (29181): host-17: connection terminated: read failed\n'
    printf '05-31 23:04:00.186 I/adbd    (29181): host-17: offline\n'
  fi
  exit 0
fi

if [[ "$*" == "shell am instrument"* ]]; then
  count="$(cat "$state_file" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_file"

  if [[ "$mode" == "transport_then_success" && "$count" -le 3 ]]; then
    printf 'INSTRUMENTATION_STATUS: class=com.pocketshell.app.proof.EmulatorDockerSshSmokeTest\n'
    printf 'INSTRUMENTATION_STATUS: current=1\n'
    printf 'INSTRUMENTATION_STATUS: numtests=1\n'
    printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
    exit 255
  fi

  if [[ "$mode" == "assertion_failure" ]]; then
    printf 'INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: expected ready\n'
    printf 'FAILURES!!!\n'
    exit 1
  fi

  printf 'INSTRUMENTATION_CODE: -1\n'
  printf 'OK (1 test)\n'
  exit 0
fi

exit 0
EOF
chmod +x "$fake_adb"

run_generated_walkthrough() {
  local mode="$1"
  local run_dir="$tmpdir/$mode"
  mkdir -p "$run_dir"

  ADB="$fake_adb"
  APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS=3
  APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS=3
  FAKE_ADB_MODE="$mode"
  FAKE_ADB_STATE="$run_dir/adb-state"
  export FAKE_ADB_MODE FAKE_ADB_STATE

  local generated_script
  generated_script="$(run_app_walkthrough_script \
    "com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias" \
    "$run_dir/diagnostics.log" \
    "$run_dir/full-logcat.log")"

  bash -lc "$generated_script"
}

run_generated_walkthrough "transport_then_success" ||
  fail "transport-only interruptions did not recover using the separate recovery budget"

attempts="$(cat "$tmpdir/transport_then_success/adb-state")"
[[ "$attempts" == "4" ]] ||
  fail "expected success on fourth instrumentation run, got $attempts runs"

if run_generated_walkthrough "assertion_failure"; then
  fail "instrumentation assertion failure was treated as retryable"
fi

attempts="$(cat "$tmpdir/assertion_failure/adb-state")"
[[ "$attempts" == "1" ]] ||
  fail "expected assertion failure to stop after one run, got $attempts runs"

printf 'PASS: pre-release gate retry helper\n'
