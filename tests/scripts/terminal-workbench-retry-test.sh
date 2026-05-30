#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

helpers="$tmpdir/terminal-workbench-helpers.sh"
sed -n \
  '/^instrumentation_log_has_success()/,/^instrumentation_status=0/p' \
  "$ROOT_DIR/scripts/terminal-workbench.sh" |
  sed '$d' > "$helpers"

run_logged() {
  local name="$1"
  shift
  local log_file="$RUN_DIR/$name.log"
  {
    printf '[test] %s\n' "$name"
    "$@"
  } 2>&1 | tee "$log_file"
}

sleep() {
  :
}

# shellcheck source=/dev/null
source "$helpers"

classification_log="$tmpdir/instrumentation.log"
classification_logcat="$tmpdir/logcat.txt"
printf 'FAILURES!!!\n' > "$classification_log"
printf '05-30 10:00:00.000  123  456 I adbd    : host-123: read failed\n' > "$classification_logcat"
if should_retry_interrupted_instrumentation 255 "$classification_log" "$classification_logcat"; then
  fail "instrumentation failure marker was treated as retryable"
fi

printf 'INSTRUMENTATION_STATUS: numtests=1\n' > "$classification_log"
printf '05-30 10:00:00.000  123  456 E AndroidRuntime: Process: com.pocketshell.app\n' > "$classification_logcat"
if should_retry_interrupted_instrumentation 255 "$classification_log" "$classification_logcat"; then
  fail "app crash marker was treated as retryable"
fi

fake_adb="$tmpdir/adb"
cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_file="${FAKE_ADB_STATE:?}"
mode="${FAKE_ADB_MODE:?}"

if [[ "$*" == "logcat -c" ]]; then
  exit 0
fi

if [[ "$*" == "logcat -d -v threadtime -t 4000" ]]; then
  count="$(cat "$state_file" 2>/dev/null || printf '0')"
  if [[ "$mode" == "exhausted" || "$count" == "1" ]]; then
    printf '05-30 10:00:00.000  123  456 I adbd    : host-123: read failed\n'
  fi
  exit 0
fi

if [[ "$*" == "shell am instrument"* ]]; then
  count="$(cat "$state_file" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_file"
  if [[ "$mode" == "exhausted" || "$count" -eq 1 ]]; then
    printf 'INSTRUMENTATION_STATUS: numtests=1\n'
    printf 'attempt=%s\n' "$count"
    exit 255
  fi
  printf 'INSTRUMENTATION_CODE: -1\n'
  printf 'OK (1 test)\n'
  printf 'attempt=%s\n' "$count"
  exit 0
fi

exit 0
EOF
chmod +x "$fake_adb"

ADB="$fake_adb"
DEVICE_ARTIFACT_DIR="/sdcard/fake-output/terminal-lab"
INSTRUMENTATION_ARGS=(-e class com.pocketshell.app.FakeTerminalWorkbenchTest)
WORKBENCH_INSTRUMENTATION_ATTEMPTS=2

RUN_DIR="$tmpdir/exhausted"
FAKE_ADB_STATE="$tmpdir/exhausted-state"
FAKE_ADB_MODE="exhausted"
export FAKE_ADB_STATE FAKE_ADB_MODE
mkdir -p "$RUN_DIR"

run_terminal_workbench_instrumentation

[[ "$instrumentation_retry_exhausted" == "1" ]] ||
  fail "retry budget exhaustion was not recorded"
[[ "$instrumentation_attempt" == "2" ]] ||
  fail "expected exhausted attempt count 2, got $instrumentation_attempt"
[[ "$instrumentation_status" == "255" ]] ||
  fail "expected exhausted instrumentation status 255, got $instrumentation_status"
[[ -s "$RUN_DIR/07-run-workbench-attempt-1.log" ]] ||
  fail "first attempt log was not preserved"
[[ -s "$RUN_DIR/07-run-workbench-attempt-2.log" ]] ||
  fail "final exhausted attempt log was not preserved"
[[ -s "$RUN_DIR/logcat-workbench-attempt-1.txt" ]] ||
  fail "first attempt logcat was not preserved"
[[ -s "$RUN_DIR/logcat-workbench-attempt-2.txt" ]] ||
  fail "final exhausted attempt logcat was not preserved"
grep -q 'attempt=2' "$RUN_DIR/07-run-workbench.log" ||
  fail "canonical instrumentation log did not contain the final attempt"

if ( fail_if_terminal_workbench_retry_exhausted ); then
  fail "exhausted retry budget did not fail before artifact pull"
fi
grep -q '^artifact_pull_exit_code=not_run$' "$RUN_DIR/instrumentation-status.txt" ||
  fail "exhausted status file did not record skipped artifact pull"
[[ ! -e "$RUN_DIR/08-pull-artifacts.log" ]] ||
  fail "artifact pull log exists after exhausted retry budget"

RUN_DIR="$tmpdir/success-after-retry"
FAKE_ADB_STATE="$tmpdir/success-state"
FAKE_ADB_MODE="success_after_retry"
export FAKE_ADB_STATE FAKE_ADB_MODE
instrumentation_status=0
instrumentation_attempt=0
instrumentation_retry_exhausted=0
mkdir -p "$RUN_DIR"

run_terminal_workbench_instrumentation
fail_if_terminal_workbench_retry_exhausted

[[ "$instrumentation_retry_exhausted" == "0" ]] ||
  fail "successful retry was marked exhausted"
[[ "$instrumentation_attempt" == "2" ]] ||
  fail "expected success attempt count 2, got $instrumentation_attempt"
[[ "$instrumentation_status" == "0" ]] ||
  fail "expected successful instrumentation status 0, got $instrumentation_status"
grep -q 'INSTRUMENTATION_CODE: -1' "$RUN_DIR/07-run-workbench.log" ||
  fail "canonical instrumentation log did not contain final success"
[[ -s "$RUN_DIR/07-run-workbench-attempt-1.log" ]] ||
  fail "success-after-retry first attempt log was not preserved"
[[ -s "$RUN_DIR/07-run-workbench-attempt-2.log" ]] ||
  fail "success-after-retry final attempt log was not preserved"

printf 'PASS: terminal workbench retry helper\n'
