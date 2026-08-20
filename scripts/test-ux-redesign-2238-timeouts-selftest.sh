#!/usr/bin/env bash
# Issue #2260: the browser-load phase must not consume the result-reporting
# phase's budget, while a page that never reports must still fail closed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SMOKE_SCRIPT="$SCRIPT_DIR/test-ux-redesign-2238.sh"
FAKE_BROWSER="$SCRIPT_DIR/test-ux-redesign-2238-fake-browser.py"
SANDBOX="$(mktemp -d)"
MUTANT_SCRIPT="$SCRIPT_DIR/.test-ux-redesign-2238-mutant.$$"
trap 'rm -rf "$SANDBOX" "$MUTANT_SCRIPT"' EXIT

fail() {
  echo "TEST FAIL: $*" >&2
  exit 1
}

run_case() {
  local timeout_seconds="$1"
  local log_path="$2"
  shift 2
  set +e
  timeout "${timeout_seconds}s" "$@" >"$log_path" 2>&1
  CASE_RC=$?
  set -e
}

assert_reported_result() {
  local label="$1" log_path="$2"
  (( CASE_RC == 0 )) || {
    tail -n 80 "$log_path" >&2
    fail "$label did not pass (rc=$CASE_RC)"
  }
  grep -Fqx 'PASS: #2238 HTML prototype browser smoke test' "$log_path" \
    || { tail -n 80 "$log_path" >&2; fail "$label had no PASS result"; }
}

assert_missing_result_failure() {
  local label="$1" log_path="$2"
  (( CASE_RC != 0 )) || fail "$label unexpectedly passed"
  grep -Fq 'browser interaction smoke test did not report a result' "$log_path" \
    || { tail -n 80 "$log_path" >&2; fail "$label did not fail at the result phase"; }
}

common_env=(
  env
  "POCKETSHELL_BROWSER=$FAKE_BROWSER"
  "POCKETSHELL_BROWSER_SMOKE_SERVER_START_TIMEOUT_SECONDS=5"
  "POCKETSHELL_BROWSER_SMOKE_RESULT_TIMEOUT_SECONDS=1"
  "POCKETSHELL_FAKE_BROWSER_RESULT_DELAY_SECONDS=0.25"
)

# Hosted-shaped regression: the observed runner needed more than the old 30s
# browser-load window before it requested the page. The default 60s budget must
# absorb that startup while retaining the one-second result budget.
run_case 75 "$SANDBOX/hosted-start.log" \
  "${common_env[@]}" \
  POCKETSHELL_FAKE_BROWSER_STARTUP_SECONDS=31 \
  POCKETSHELL_FAKE_BROWSER_MODE=report \
  "$SMOKE_SCRIPT"
assert_reported_result "hosted-shaped browser startup" "$SANDBOX/hosted-start.log"

# A two-second fake browser startup is longer than the one-second result
# budget. It must still pass because the result clock starts only after the
# server has served the smoke page.
run_case 10 "$SANDBOX/delayed-start.log" \
  "${common_env[@]}" \
  POCKETSHELL_BROWSER_SMOKE_BROWSER_LOAD_TIMEOUT_SECONDS=5 \
  POCKETSHELL_FAKE_BROWSER_STARTUP_SECONDS=2 \
  POCKETSHELL_FAKE_BROWSER_MODE=report \
  "$SMOKE_SCRIPT"
assert_reported_result "delayed browser startup" "$SANDBOX/delayed-start.log"

# Mutation proof: reintroduce the old shared-deadline shape into a temporary
# copy. The same delayed browser must then miss the already-expired result
# budget. This makes the timing assertion load-bearing rather than decorative.
cp "$SMOKE_SCRIPT" "$MUTANT_SCRIPT"
MUTANT_DEADLINE_LINE="smoke_deadline=\$((SECONDS + SMOKE_RESULT_TIMEOUT_SECONDS))"
MUTANT_RESULT_LINE="result_deadline=\"\$smoke_deadline\""
sed -i \
  -e "/^setsid env -u DBUS_SESSION_BUS_ADDRESS -u DBUS_SYSTEM_BUS_ADDRESS/i $MUTANT_DEADLINE_LINE" \
  -e "s/^result_deadline=.*\$/$MUTANT_RESULT_LINE/" \
  "$MUTANT_SCRIPT"
grep -Fq "$MUTANT_DEADLINE_LINE" "$MUTANT_SCRIPT" \
  || fail "shared-deadline mutation was not installed"
grep -Fq "$MUTANT_RESULT_LINE" "$MUTANT_SCRIPT" \
  || fail "shared-deadline mutation did not replace the result deadline"

run_case 10 "$SANDBOX/shared-deadline-mutant.log" \
  "${common_env[@]}" \
  POCKETSHELL_BROWSER_SMOKE_BROWSER_LOAD_TIMEOUT_SECONDS=5 \
  POCKETSHELL_FAKE_BROWSER_STARTUP_SECONDS=2 \
  POCKETSHELL_FAKE_BROWSER_MODE=report \
  "$MUTANT_SCRIPT"
assert_missing_result_failure "shared-deadline mutation" "$SANDBOX/shared-deadline-mutant.log"

# A real missing result must remain a bounded hard failure after the page has
# loaded. The one-second result budget keeps this self-test fast and selective.
run_case 10 "$SANDBOX/missing-result.log" \
  "${common_env[@]}" \
  POCKETSHELL_BROWSER_SMOKE_BROWSER_LOAD_TIMEOUT_SECONDS=5 \
  POCKETSHELL_FAKE_BROWSER_STARTUP_SECONDS=0 \
  POCKETSHELL_FAKE_BROWSER_MODE=missing \
  "$SMOKE_SCRIPT"
assert_missing_result_failure "missing result" "$SANDBOX/missing-result.log"

echo "PASS: #2260 UX smoke timeout self-test"
