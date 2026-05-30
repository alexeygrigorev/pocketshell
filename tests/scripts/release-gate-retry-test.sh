#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

helpers="$tmpdir/release-gate-retry-helpers.sh"
sed -n \
  '/^real_agent_release_gate_instrumentation_log_has_success()/,/^# Run RealAgentReleaseGateTest/p' \
  "$ROOT_DIR/scripts/release-emulator-validation.sh" |
  sed '$d' > "$helpers"
# shellcheck source=/dev/null
source "$helpers"

instrumentation_log="$tmpdir/instrumentation.log"
logcat_file="$tmpdir/logcat.txt"

printf 'INSTRUMENTATION_STATUS: numtests=1\n' > "$instrumentation_log"
printf '05-30 10:00:00.000  123  456 I adbd    : host-123: read failed\n' > "$logcat_file"
real_agent_release_gate_should_retry_interrupted_instrumentation 255 "$instrumentation_log" "$logcat_file" ||
  fail "adb transport-drop exit 255 without failure markers was not retryable"

printf 'FAILURES!!!\n' >> "$instrumentation_log"
if real_agent_release_gate_should_retry_interrupted_instrumentation 255 "$instrumentation_log" "$logcat_file"; then
  fail "instrumentation failure marker was treated as retryable"
fi

printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n' > "$instrumentation_log"
if real_agent_release_gate_should_retry_interrupted_instrumentation 255 "$instrumentation_log" "$logcat_file"; then
  fail "instrumentation crash result marker was treated as retryable"
fi

printf 'INSTRUMENTATION_STATUS: numtests=1\n' > "$instrumentation_log"
printf '05-30 10:00:00.000  123  456 E AndroidRuntime: Process: com.pocketshell.app\n' > "$logcat_file"
if real_agent_release_gate_should_retry_interrupted_instrumentation 255 "$instrumentation_log" "$logcat_file"; then
  fail "app crash marker was treated as retryable"
fi

printf 'INSTRUMENTATION_CODE: -1\nOK (1 test)\n' > "$instrumentation_log"
printf '05-30 10:00:00.000  123  456 I adbd    : offline\n' > "$logcat_file"
if real_agent_release_gate_should_retry_interrupted_instrumentation 255 "$instrumentation_log" "$logcat_file"; then
  fail "successful instrumentation output was treated as retryable"
fi

printf 'INSTRUMENTATION_STATUS: numtests=1\n' > "$instrumentation_log"
printf '05-30 10:00:00.000  123  456 I adbd    : host-123: read failed\n' > "$logcat_file"
if real_agent_release_gate_should_retry_interrupted_instrumentation 1 "$instrumentation_log" "$logcat_file"; then
  fail "non-255 status was treated as retryable"
fi

printf 'PASS: release gate retry helper\n'
