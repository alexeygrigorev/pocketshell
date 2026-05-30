#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

helpers="$tmpdir/walkthrough-visual-helpers.sh"
{
  sed -n '/^visual_audit_instrumentation_log_has_success()/,/^pull_device_screenshots()/p' "$ROOT_DIR/scripts/capture-walkthrough-screenshots.sh" |
    sed '$d'
} > "$helpers"

run_logged() {
  local name="$1"
  shift
  local log_file="$RUN_DIR/$name.log"
  {
    printf '[test] %s\n' "$name"
    "$@"
  } 2>&1 | tee "$log_file"
}

# shellcheck source=/dev/null
source "$helpers"

classification_log="$tmpdir/instrumentation.log"
classification_logcat="$tmpdir/logcat.txt"

printf 'INSTRUMENTATION_STATUS: numtests=1\n' > "$classification_log"
printf '05-30 10:00:00.000  123  456 I adbd    : host-123: read failed\n' > "$classification_logcat"
visual_audit_should_retry_interrupted_instrumentation 255 "$classification_log" "$classification_logcat" ||
  fail "adb transport-drop exit 255 without failure markers was not retryable"

printf 'INSTRUMENTATION_STATUS: numtests=1\nerror: closed\n' > "$classification_log"
: > "$classification_logcat"
visual_audit_should_retry_interrupted_instrumentation 1 "$classification_log" "$classification_logcat" ||
  fail "nonzero adb transport interruption from instrumentation output was not retryable"

printf 'INSTRUMENTATION_STATUS: numtests=1\n' > "$classification_log"
printf '05-30 10:00:00.000  123  456 I system  : UiAutomation service owner died\n' > "$classification_logcat"
visual_audit_should_retry_interrupted_instrumentation 255 "$classification_log" "$classification_logcat" ||
  fail "UiAutomation owner death was not retryable"

printf 'java.lang.IllegalStateException: UiAutomation not connected\n' > "$classification_log"
: > "$classification_logcat"
visual_audit_should_retry_interrupted_instrumentation 1 "$classification_log" "$classification_logcat" ||
  fail "UiAutomation not connected instrumentation output was not retryable"

printf 'FAILURES!!!\n' >> "$classification_log"
if visual_audit_should_retry_interrupted_instrumentation 255 "$classification_log" "$classification_logcat"; then
  fail "instrumentation failure marker was treated as retryable"
fi

printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n' > "$classification_log"
if visual_audit_should_retry_interrupted_instrumentation 255 "$classification_log" "$classification_logcat"; then
  fail "instrumentation crash result marker was treated as retryable"
fi

printf 'INSTRUMENTATION_STATUS: numtests=1\n' > "$classification_log"
printf '05-30 10:00:00.000  123  456 E AndroidRuntime: Process: com.pocketshell.app\n' > "$classification_logcat"
if visual_audit_should_retry_interrupted_instrumentation 255 "$classification_log" "$classification_logcat"; then
  fail "app crash marker was treated as retryable"
fi

printf 'INSTRUMENTATION_CODE: -1\nOK (1 test)\n' > "$classification_log"
printf '05-30 10:00:00.000  123  456 I adbd    : offline\n' > "$classification_logcat"
if visual_audit_should_retry_interrupted_instrumentation 255 "$classification_log" "$classification_logcat"; then
  fail "successful instrumentation output was treated as retryable"
fi

printf 'INSTRUMENTATION_STATUS: numtests=1\n' > "$classification_log"
printf '05-30 10:00:00.000  123  456 I adbd    : host-123: read failed\n' > "$classification_logcat"
if visual_audit_should_retry_interrupted_instrumentation 0 "$classification_log" "$classification_logcat"; then
  fail "zero status was treated as retryable"
fi

fake_adb="$tmpdir/adb"
cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_file="${FAKE_ADB_STATE:?}"

if [[ "$*" == "logcat -c" ]]; then
  exit 0
fi

if [[ "$*" == "logcat -d -v threadtime -t 4000" ]]; then
  count="$(cat "$state_file" 2>/dev/null || printf '0')"
  if [[ "$count" == "1" ]]; then
    printf '05-30 10:00:00.000  123  456 I adbd    : host-123: read failed\n'
  fi
  exit 0
fi

if [[ "$*" == "shell am instrument"* ]]; then
  count="$(cat "$state_file" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_file"
  if [[ "$count" -eq 1 ]]; then
    printf 'INSTRUMENTATION_STATUS: numtests=1\n'
    printf 'error: closed\n' >&2
    exit 255
  fi
  printf 'INSTRUMENTATION_CODE: -1\n'
  printf 'OK (1 test)\n'
  exit 0
fi

exit 0
EOF
chmod +x "$fake_adb"

RUN_DIR="$tmpdir/run"
ADB="$fake_adb"
DEVICE_OUTPUT_DIR="/sdcard/fake-output"
INSTRUMENTATION_ATTEMPTS=2
FAKE_ADB_STATE="$tmpdir/adb-state"
export FAKE_ADB_STATE
mkdir -p "$RUN_DIR"

run_instrumentation_class "strict-mode-instrumentation" "com.pocketshell.app.FakeVisualTest" ||
  fail "run_instrumentation_class did not retry a nonzero transport interruption"

attempts="$(cat "$FAKE_ADB_STATE")"
[[ "$attempts" == "2" ]] ||
  fail "expected two instrumentation attempts after retry, got $attempts"

grep -q 'INSTRUMENTATION_CODE: -1' "$RUN_DIR/strict-mode-instrumentation.log" ||
  fail "canonical instrumentation log did not contain final success"

printf 'PASS: walkthrough visual retry helper\n'
