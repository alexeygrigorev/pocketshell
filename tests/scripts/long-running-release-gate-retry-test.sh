#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

helpers="$tmpdir/long-running-release-gate-helpers.sh"
sed -n \
  '/^real_agent_release_gate_instrumentation_log_has_success()/,/^require_clean_pushed_main$/p' \
  "$ROOT_DIR/scripts/release-emulator-validation.sh" |
  sed '$d' > "$helpers"

docker() {
  if [[ "$*" == compose\ *\ up\ * ]]; then
    printf 'fake docker up\n'
    return 0
  fi
  if [[ "$*" == compose\ *\ logs\ * ]]; then
    printf 'fake docker logs\n'
    return 0
  fi
  return 0
}

sleep() {
  :
}

# shellcheck source=/dev/null
source "$helpers"

fake_adb="$tmpdir/adb"
cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_file="${FAKE_ADB_STATE:?}"
mode="${FAKE_ADB_MODE:?}"
run_dir="$(dirname "$state_file")"
device_log="$run_dir/device-instrumentation.log"
device_status="$run_dir/device-instrumentation.status"
device_done="$run_dir/device-instrumentation.done"
log_poll_file="$run_dir/device-log-polls"

append_once() {
  local needle="$1"
  local line="$2"
  if ! grep -qF "$needle" "$device_log" 2>/dev/null; then
    printf '%s\n' "$line" >> "$device_log"
  fi
}

if [[ "$*" == "logcat -c" ]]; then
  exit 0
fi

if [[ "$*" == "logcat -d -v threadtime -t 60000" ]]; then
  count="$(cat "$state_file" 2>/dev/null || printf '0')"
  if [[ "$mode" == "transport_then_success" && "$count" == "1" ]]; then
    printf '05-31 23:04:00.186 I adbd    : host-17: read failed: Success\n'
    printf '05-31 23:04:00.186 I adbd    : host-17: offline\n'
    printf '05-31 23:04:00.186 I system  : UiAutomation service owner died\n'
  fi
  if [[ "$mode" == "assertion_failure" ]]; then
    printf '05-31 23:04:00.186 I adbd    : host-17: read failed: Success\n'
  fi
  exit 0
fi

if [[ "$*" == shell\ rm\ -f\ /data/local/tmp/pocketshell-long-running-*nohup*am\ instrument* ]]; then
  count="$(cat "$state_file" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_file"
  rm -f "$device_log" "$device_status" "$device_done" "$log_poll_file"

  if [[ "$mode" == "transport_then_success" && "$count" == "1" ]]; then
    {
      printf 'INSTRUMENTATION_STATUS: class=com.pocketshell.app.proof.LongRunningSessionStabilityTest\n'
      printf 'INSTRUMENTATION_STATUS: current=1\n'
      printf 'INSTRUMENTATION_STATUS: numtests=1\n'
      printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
    } > "$device_log"
    printf '255\n' > "$device_status"
    : > "$device_done"
    exit 0
  fi

  if [[ "$mode" == "delayed_success" ]]; then
    {
      printf 'INSTRUMENTATION_STATUS: class=com.pocketshell.app.proof.LongRunningSessionStabilityTest\n'
      printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
    } > "$device_log"
    exit 0
  fi

  if [[ "$mode" == "timeout" ]]; then
    {
      printf 'INSTRUMENTATION_STATUS: class=com.pocketshell.app.proof.LongRunningSessionStabilityTest\n'
      printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
    } > "$device_log"
    exit 0
  fi

  if [[ "$mode" == "missing_status" ]]; then
    {
      printf 'INSTRUMENTATION_STATUS: class=com.pocketshell.app.proof.LongRunningSessionStabilityTest\n'
      printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
      printf 'INSTRUMENTATION_RESULT: stream=\n'
      printf 'missing status fixture finished\n'
    } > "$device_log"
    : > "$device_done"
    exit 0
  fi

  if [[ "$mode" == "corrupt_status" ]]; then
    {
      printf 'INSTRUMENTATION_STATUS: class=com.pocketshell.app.proof.LongRunningSessionStabilityTest\n'
      printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
      printf 'INSTRUMENTATION_RESULT: stream=\n'
      printf 'corrupt status fixture finished\n'
    } > "$device_log"
    printf 'not-a-number\n' > "$device_status"
    : > "$device_done"
    exit 0
  fi

  if [[ "$mode" == "generic_nonzero" ]]; then
    {
      printf 'INSTRUMENTATION_STATUS: class=com.pocketshell.app.proof.LongRunningSessionStabilityTest\n'
      printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
      printf 'INSTRUMENTATION_RESULT: stream=\n'
      printf 'generic nonzero fixture finished\n'
    } > "$device_log"
    printf '42\n' > "$device_status"
    : > "$device_done"
    exit 0
  fi

  if [[ "$mode" == "assertion_failure" ]]; then
    {
      printf 'INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: expected no teardown events\n'
      printf 'FAILURES!!!\n'
    } > "$device_log"
    printf '1\n' > "$device_status"
    : > "$device_done"
    exit 0
  fi

  {
    printf 'INSTRUMENTATION_STATUS: stream=LONG_RUNNING_HEARTBEAT elapsed_ms=15000 next_tick_index=1 label=hold\n'
    printf 'INSTRUMENTATION_STATUS_CODE: 0\n'
    printf 'INSTRUMENTATION_CODE: -1\n'
    printf 'OK (1 test)\n'
  } > "$device_log"
  printf '0\n' > "$device_status"
  : > "$device_done"
  exit 0
fi

if [[ "$*" == shell\ cat\ /data/local/tmp/pocketshell-long-running-*.log* ]]; then
  if [[ "$mode" == "delayed_success" ]]; then
    poll="$(cat "$log_poll_file" 2>/dev/null || printf '0')"
    poll=$((poll + 1))
    printf '%s' "$poll" > "$log_poll_file"
    if (( poll >= 2 )); then
      append_once \
        'LONG_RUNNING_HEARTBEAT elapsed_ms=15000' \
        'INSTRUMENTATION_STATUS: stream=LONG_RUNNING_HEARTBEAT elapsed_ms=15000 next_tick_index=1 label=hold'
      append_once 'INSTRUMENTATION_STATUS_CODE: 0' 'INSTRUMENTATION_STATUS_CODE: 0'
    fi
    if (( poll >= 3 )); then
      append_once 'INSTRUMENTATION_CODE: -1' 'INSTRUMENTATION_CODE: -1'
      append_once 'OK (1 test)' 'OK (1 test)'
      printf '0\n' > "$device_status"
      : > "$device_done"
    fi
  fi
  cat "$device_log" 2>/dev/null || true
  exit 0
fi

if [[ "$*" == shell\ cat\ /data/local/tmp/pocketshell-long-running-*.status* ]]; then
  cat "$device_status" 2>/dev/null || true
  exit 0
fi

if [[ "$*" == shell\ test\ -f\ /data/local/tmp/pocketshell-long-running-*.done* ]]; then
  [[ -f "$device_done" ]]
  exit $?
fi

if [[ "$*" == shell\ rm\ -f\ /data/local/tmp/pocketshell-long-running-* ]]; then
  rm -f "$device_log" "$device_status" "$device_done"
  exit 0
fi

if [[ "$*" == pull\ * ]]; then
  exit 0
fi

exit 0
EOF
chmod +x "$fake_adb"

run_long_running_with_mode() {
  local mode="$1"
  local run_dir="$tmpdir/$mode"
  mkdir -p "$run_dir"

  ADB="$fake_adb"
  LONG_RUNNING_TEST_RUN_DIR="$run_dir"
  LONG_RUNNING_TEST_CLASS="com.pocketshell.app.proof.LongRunningSessionStabilityTest"
  LONG_RUNNING_TEST_INSTRUMENTATION_ATTEMPTS=2
  LONG_RUNNING_COMPOSE_FILE="tests/docker/docker-compose.yml"
  LONG_RUNNING_TEST_POLL_INTERVAL_SECONDS=0
  LONG_RUNNING_TEST_INSTRUMENTATION_TIMEOUT_SECONDS=900
  if [[ "$mode" == "timeout" ]]; then
    LONG_RUNNING_TEST_INSTRUMENTATION_TIMEOUT_SECONDS=0
  fi
  FAKE_ADB_MODE="$mode"
  FAKE_ADB_STATE="$run_dir/adb-state"
  export FAKE_ADB_MODE FAKE_ADB_STATE
  export LONG_RUNNING_TEST_POLL_INTERVAL_SECONDS LONG_RUNNING_TEST_INSTRUMENTATION_TIMEOUT_SECONDS

  run_long_running_session_instrumentation
}

expect_long_running_failure() {
  local mode="$1"
  local expected_status="$2"
  local status

  set +e
  status=0
  run_long_running_with_mode "$mode" || status="$?"
  set -e

  [[ "$status" == "$expected_status" ]] ||
    fail "expected $mode to exit $expected_status, got $status"
}

run_long_running_with_mode "delayed_success" ||
  fail "delayed detached polling did not recover final success"
attempts="$(cat "$tmpdir/delayed_success/adb-state")"
[[ "$attempts" == "1" ]] ||
  fail "expected delayed success to complete in one run, got $attempts runs"
polls="$(cat "$tmpdir/delayed_success/device-log-polls")"
[[ "$polls" -ge 3 ]] ||
  fail "expected delayed success to require at least three log polls, got $polls"
grep -q 'LONG_RUNNING_HEARTBEAT elapsed_ms=15000' "$tmpdir/delayed_success/instrumentation.log" ||
  fail "delayed success did not stream heartbeat output"
grep -q 'INSTRUMENTATION_CODE: -1' "$tmpdir/delayed_success/instrumentation.log" ||
  fail "delayed success canonical log did not contain final success"

run_long_running_with_mode "transport_then_success" ||
  fail "transport-only interruption did not recover"

attempts="$(cat "$tmpdir/transport_then_success/adb-state")"
[[ "$attempts" == "2" ]] ||
  fail "expected success on second instrumentation run, got $attempts runs"
grep -q '^instrumentation_attempts=2$' "$tmpdir/transport_then_success/instrumentation-status.txt" ||
  fail "status file did not record two instrumentation attempts"
grep -q 'INSTRUMENTATION_CODE: -1' "$tmpdir/transport_then_success/instrumentation.log" ||
  fail "canonical instrumentation log did not contain final success"
[[ -s "$tmpdir/transport_then_success/instrumentation-attempt-1.log" ]] ||
  fail "first attempt instrumentation log was not preserved"
[[ -s "$tmpdir/transport_then_success/logcat-attempt-1.txt" ]] ||
  fail "first attempt logcat was not preserved"

if run_long_running_with_mode "assertion_failure"; then
  fail "instrumentation assertion failure was treated as retryable"
fi

attempts="$(cat "$tmpdir/assertion_failure/adb-state")"
[[ "$attempts" == "1" ]] ||
  fail "expected assertion failure to stop after one run, got $attempts runs"
grep -q '^instrumentation_attempts=1$' "$tmpdir/assertion_failure/instrumentation-status.txt" ||
  fail "status file did not record immediate assertion failure"

expect_long_running_failure "timeout" "124"
grep -q 'detached instrumentation timed out' "$tmpdir/timeout/instrumentation-attempt-1.log" ||
  fail "timeout path did not record a specific timeout diagnostic"
grep -q '^instrumentation_exit_code=124$' "$tmpdir/timeout/instrumentation-status.txt" ||
  fail "timeout status file did not preserve exit 124"

expect_long_running_failure "missing_status" "1"
grep -q 'result status was missing, empty, or corrupt' "$tmpdir/missing_status/instrumentation-attempt-1.log" ||
  fail "missing status path did not record the specific status diagnostic"
grep -q 'device_status_path=/data/local/tmp/pocketshell-long-running-' "$tmpdir/missing_status/instrumentation-attempt-1.log" ||
  fail "missing status diagnostic did not name the device status path"
grep -q 'final_instrumentation_log_tail:' "$tmpdir/missing_status/instrumentation-attempt-1.log" ||
  fail "missing status diagnostic did not preserve final log tail"

expect_long_running_failure "corrupt_status" "1"
grep -q 'status_contents=not-a-number' "$tmpdir/corrupt_status/instrumentation-attempt-1.log" ||
  fail "corrupt status diagnostic did not preserve corrupt status contents"

expect_long_running_failure "generic_nonzero" "42"
grep -q '^instrumentation_exit_code=42$' "$tmpdir/generic_nonzero/instrumentation-status.txt" ||
  fail "generic nonzero status was not preserved"
grep -q 'generic nonzero fixture finished' "$tmpdir/generic_nonzero/instrumentation.log" ||
  fail "generic nonzero final log was not preserved"

printf 'PASS: long-running release gate retry helper\n'
