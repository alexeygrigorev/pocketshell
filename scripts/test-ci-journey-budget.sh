#!/usr/bin/env bash
# Focused test for the issue #835 suite-level time budget + classifier labelling
# in scripts/ci-journey-suite.sh.
#
# The recurring failure: the in-emulator #470 tmux `list-sessions` enumeration
# stall makes session/reconnect journeys burn their retry windows; with no
# suite-level deadline the 45-min workflow job cap SIGKILLs the suite mid-loop
# before summary.md is written, so the workflow classifier mis-routes the red to
# "EMULATOR INFRA UNAVAILABLE (#771)".
#
# This test drives the REAL ci-journey-suite.sh with a TINY budget and a STUBBED
# gradle that sleeps (modelling a stalling class), and proves:
#   (a) the suite stops launching new classes once the budget is spent
#       (it does NOT run all 30+ classes — it bails),
#   (b) it ALWAYS writes summary.md (the artifact the classifier needs),
#   (c) the summary carries the distinct `JOURNEY_STEP_TIMEOUT` marker
#       (NOT `JOURNEY_FAILED` and NOT a missing file),
#   (d) the suite still exits NON-ZERO so the first attempt outcome records the
#       timeout and the classifier can inspect the summary,
#   (e) the workflow classifier grep that distinguishes a #470 timeout from a
#       genuine failure / infra abort routes this summary to advisory-green.
#   (f) a cancelled retry is classified before any `Failed BOTH attempts`
#       summary, because summary.md can be stale from the first cold boot.
#   (g) first-attempt diagnostics are snapshotted before the workflow retry can
#       overwrite summary.md or connected-test outputs.
#
# It runs entirely on the JVM-free shell layer — NO emulator, NO Docker, NO
# gradle — so it can run as a fast unit check on any box and in the Unit CI job.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_SUITE="$SCRIPT_DIR/ci-journey-suite.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$REAL_SUITE" ]] || fail "cannot find ci-journey-suite.sh at $REAL_SUITE"

REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"
THIS_TEST="$SCRIPT_DIR/test-ci-journey-budget.sh"

# (pre) #908 budget/default/comment guard: prove the suite budget leaves
# explicit slack under the workflow cap after a worst-case emulator boot.
job_cap_min="$(awk '
  /^  emulator-journey:/ { in_job=1; next }
  in_job && /^  [A-Za-z0-9_-]+:/ { in_job=0 }
  in_job && /timeout-minutes:/ { print $2; exit }
' "$WORKFLOW")"
[[ "$job_cap_min" =~ ^[0-9]+$ ]] \
  || fail "(pre) could not parse emulator-journey timeout-minutes from tests.yml"
job_cap_secs=$((job_cap_min * 60))
[[ "$job_cap_secs" -eq 2700 ]] \
  || fail "(pre) emulator-journey job cap must stay 45 min / 2700s (got ${job_cap_secs}s)"

mapfile -t emulator_boot_timeout_values < <(awk '/emulator-boot-timeout:/ { print $2 }' "$WORKFLOW")
[[ "${#emulator_boot_timeout_values[@]}" -gt 0 ]] \
  || fail "(pre) could not parse emulator-boot-timeout from tests.yml"
for emulator_boot_timeout_secs in "${emulator_boot_timeout_values[@]}"; do
  [[ "$emulator_boot_timeout_secs" =~ ^[0-9]+$ ]] \
    || fail "(pre) emulator-boot-timeout must be numeric (got ${emulator_boot_timeout_secs})"
  [[ "$emulator_boot_timeout_secs" -eq 900 ]] \
    || fail "(pre) every emulator boot timeout must stay 900s (got ${emulator_boot_timeout_secs}s)"
done
emulator_boot_timeout_secs="${emulator_boot_timeout_values[0]}"

# Match the literal shell assignment in ci-journey-suite.sh.
# shellcheck disable=SC2016
default_suite_budget_secs="$(sed -n 's/^JOURNEY_STEP_BUDGET_SECS="${JOURNEY_STEP_BUDGET_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
[[ "$default_suite_budget_secs" =~ ^[0-9]+$ ]] \
  || fail "(pre) could not parse default JOURNEY_STEP_BUDGET_SECS from ci-journey-suite.sh"
[[ "$default_suite_budget_secs" -eq 1200 ]] \
  || fail "(pre) default JOURNEY_STEP_BUDGET_SECS must stay 1200s / 20 min (got ${default_suite_budget_secs}s)"

remaining_slack_secs=$((job_cap_secs - emulator_boot_timeout_secs - default_suite_budget_secs))
[[ "$remaining_slack_secs" -ge 600 ]] \
  || fail "(pre) insufficient post-boot slack: ${job_cap_secs}s job cap - ${emulator_boot_timeout_secs}s boot - ${default_suite_budget_secs}s suite = ${remaining_slack_secs}s (< 600s)"

grep -q 'default 20 min' "$REAL_SUITE" \
  || fail "(pre) ci-journey-suite.sh budget comment must document the 20-min default"
grep -q '45-min job cap (2700s) - worst-case emulator boot (900s) - default suite' "$REAL_SUITE" \
  || fail "(pre) ci-journey-suite.sh budget comment must show the safe arithmetic"
grep -q 'workflow job cap: 45 min' "$REAL_SUITE" \
  || fail "(pre) ci-journey-suite.sh log line must refer to the 45-min job cap"
grep -q '20-min suite budget' "$WORKFLOW" \
  || fail "(pre) tests.yml first-summary comment must match the 20-min suite budget"
stale_step_cap_re="workflow ste""p|ste""p cap|45-min ste""p|45 min ste""p"
if grep -qE "$stale_step_cap_re" \
  "$REAL_SUITE" "$WORKFLOW" "$THIS_TEST"; then
  fail "(pre) stale workflow-timeout wording found; use 45-min job cap"
fi

preserve_line="$(grep -n 'name: Preserve first journey attempt diagnostics' "$WORKFLOW" | cut -d: -f1)"
retry_line="$(grep -n 'name: Retry journey subset on a fresh cold-booted emulator' "$WORKFLOW" | cut -d: -f1)"
upload_line="$(grep -n 'artifacts/ci-journey-attempt-1/' "$WORKFLOW" | cut -d: -f1 | tail -n 1)"
[[ "$preserve_line" =~ ^[0-9]+$ ]] \
  || fail "(pre) workflow must preserve first-attempt diagnostics before retry"
[[ "$retry_line" =~ ^[0-9]+$ ]] \
  || fail "(pre) could not find emulator retry step in tests.yml"
[[ "$upload_line" =~ ^[0-9]+$ ]] \
  || fail "(pre) first-attempt diagnostics must be uploaded as an artifact"
[[ "$preserve_line" -lt "$retry_line" ]] \
  || fail "(pre) first-attempt diagnostics preservation must run before retry"
grep -q 'cp -a artifacts/ci-journey/.' "$WORKFLOW" \
  || fail "(pre) preservation step must snapshot artifacts/ci-journey before retry overwrites summary.md"
grep -q 'summary-missing.txt' "$WORKFLOW" \
  || fail "(pre) preservation step must record first-attempt missing-summary infra aborts"
pass "(pre) #908 budget arithmetic pinned (${job_cap_secs}s job - ${emulator_boot_timeout_secs}s boot - ${default_suite_budget_secs}s suite = ${remaining_slack_secs}s slack)"
pass "(pre) first-attempt diagnostics are preserved before emulator retry"

# (pre) Every standalone proof that can make the suite red must also appear in
# the Failed BOTH attempts summary. The workflow classifier only treats a first
# attempt as a genuine failure when that section is present, so omissions can
# incorrectly downgrade a proof failure followed by a retry timeout.
failed_summary_block="$(awk '
  /Failed BOTH attempts/ { capture=1 }
  capture { print }
  capture && /^  fi$/ { exit }
' "$REAL_SUITE")"
for proof_pair in \
  'APPEND_BURST_STATUS:CORE_TERMINAL_APPEND_BURST_CLASS' \
  'OUTPUT_BURST_IME_STATUS:CORE_TERMINAL_OUTPUT_BURST_IME_CLASS' \
  'MULTICHUNK_SEED_STATUS:CORE_TERMINAL_MULTICHUNK_SEED_CLASS' \
  'AGENT_LINK_AFFORDANCE_STATUS:CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS' \
  'REATTACH_REPAINT_STATUS:CORE_TERMINAL_REATTACH_REPAINT_CLASS'
do
  status_var="${proof_pair%%:*}"
  class_var="${proof_pair#*:}"
  grep -q "\$$status_var\" == \"FAIL\"" "$REAL_SUITE" \
    || fail "(pre) $status_var is not represented in the suite failure verdict"
  grep -q "\$$status_var\" == \"FAIL\"" <<< "$failed_summary_block" \
    || fail "(pre) $status_var is not represented in the Failed BOTH summary predicate"
  grep -q "\$$class_var" <<< "$failed_summary_block" \
    || fail "(pre) $class_var is not emitted in the Failed BOTH summary"
done
pass "(pre) every standalone proof failure is emitted under Failed BOTH attempts"

# ---------------------------------------------------------------------------
# Build a sandbox "repo root": a copy of the suite script + a stub gradlew that
# SLEEPS (modelling a #470-stalling class) + stub scripts the suite shells out
# to. REPO_ROOT in the suite is derived from BASH_SOURCE, so we run the COPY
# from inside the sandbox and it treats the sandbox as the repo.
SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

mkdir -p "$SANDBOX/scripts"
cp "$REAL_SUITE" "$SANDBOX/scripts/ci-journey-suite.sh"
chmod +x "$SANDBOX/scripts/ci-journey-suite.sh"

# Stub gradlew: each invocation sleeps GRADLE_STUB_SLEEP seconds then "passes".
# The sleep models a class that takes real wall-clock time, so the tiny budget
# trips after the first class or two — exactly the #470-stall time-burn shape.
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stop" ]]; then
  exit 0
fi
sleep "${GRADLE_STUB_SLEEP:-2}"
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

# The suite shells out to `scripts/connected-test.sh` only on the --pool sharded
# path (not exercised here) and to `adb` near the top. Stub `adb` on PATH so the
# top-of-script `settings put` loop is a harmless no-op.
STUBBIN="$SANDBOX/stubbin"
mkdir -p "$STUBBIN"
cat > "$STUBBIN/adb" <<'STUB'
#!/usr/bin/env bash
# `adb devices` -> emit one fake device so the show_ime loop iterates once and
# `adb -s <serial> shell ...` -> no-op success.
case "$1" in
  devices) printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
  *) exit 0 ;;
esac
STUB
chmod +x "$STUBBIN/adb"

echo "== Running ci-journey-suite.sh with a tiny budget + stalling gradle stub =="
out="$SANDBOX/run.log"
set +e
PATH="$STUBBIN:$PATH" \
  JOURNEY_STEP_BUDGET_SECS=3 \
  JOURNEY_CLASS_TIMEOUT_SECS=5 \
  GRADLE_STUB_SLEEP=2 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out" 2>&1
rc=$?
set -e

summary="$SANDBOX/artifacts/ci-journey/summary.md"

# (a) the suite bailed instead of running all classes — at least one class is
#     bucketed as a budget timeout (the loop stopped launching new classes).
grep -q 'JOURNEY_STEP_TIMEOUT' "$out" \
  || { sed -n '1,40p' "$out"; fail "(a) no JOURNEY_STEP_TIMEOUT log line — suite did not bail on the budget"; }
pass "(a) suite bailed on the budget (JOURNEY_STEP_TIMEOUT logged)"

# (b) summary.md was written despite the budget timeout.
[[ -f "$summary" ]] || fail "(b) summary.md was NOT written on a budget timeout — the classifier would mis-route to #771"
pass "(b) summary.md written even on budget timeout"

# (c) summary carries the distinct marker, NOT a genuine-failure marker.
grep -q 'JOURNEY_STEP_TIMEOUT' "$summary" \
  || { cat "$summary"; fail "(c) summary missing JOURNEY_STEP_TIMEOUT marker"; }
grep -q 'Suite step time budget exhausted' "$summary" \
  || fail "(c) summary missing 'Suite step time budget exhausted' line"
if grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$summary"; then
  cat "$summary"
  fail "(c) summary wrongly contains a genuine-failure marker on a pure budget timeout"
fi
pass "(c) summary has the timeout marker and NOT a genuine-failure marker"

# (d) the suite exited NON-ZERO so the workflow sees a failed first attempt and
#     then lets the classifier decide whether the summary is genuine-failure red
#     or timeout-only advisory-green.
[[ "$rc" -ne 0 ]] || fail "(d) suite exited 0 on a budget timeout — classifier would never inspect the timeout summary"
pass "(d) suite exited non-zero (rc=$rc) on a budget timeout"

# (e) classifier routing: replicate the workflow's grep ladder against this
#     summary and assert it lands on the TIMEOUT branch, not genuine-failure,
#     not infra-abort.
classify() {
  local s="$1"
  local first_outcome="${2:-failure}"
  local retry_outcome="${3:-failure}"
  local first_concl="${4:-failure}"
  local retry_concl="${5:-failure}"
  local first_timeout="${6:-}"
  local first_failure="${7:-false}"
  local first_failed_classes="${8:-}"
  if [[ -z "$first_timeout" ]]; then
    first_timeout="false"
    if [[ -f "$s" ]] \
      && ! grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$s" \
      && grep -qE 'JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' "$s"; then
      first_timeout="true"
    fi
  fi
  if [[ "$first_outcome" == "success" ]]; then
    echo "PASS_FIRST"; return
  fi
  if [[ "$retry_outcome" == "success" ]]; then
    echo "PASS_RETRY"; return
  fi
  if [[ "$first_failure" == "true" ]]; then
    if [[ -n "$first_failed_classes" ]] \
      && [[ -f "$s" ]] \
      && ! grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$s" \
      && grep -qE 'JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' "$s"; then
      local resolved all_cleared class_name
      resolved="$(awk '
        /^Passed first try:/ || /^Recovered on retry/ { capture=1; next }
        capture && /^- / {
          gsub(/`/, "")
          sub(/^- /, "")
          sub(/[[:space:]]+\(.*/, "")
          print
          next
        }
        capture && /^$/ { capture=0 }
      ' "$s" || true)"
      all_cleared="true"
      while IFS= read -r class_name; do
        [[ -z "$class_name" ]] && continue
        if ! grep -Fxq "$class_name" <<< "$resolved"; then
          all_cleared="false"
        fi
      done <<< "$first_failed_classes"
      if [[ "$all_cleared" == "true" ]]; then
        echo "FIRST_FAILURE_CLEARED_RETRY_TIMEOUT"; return
      fi
    fi
    echo "FIRST_GENUINE_FAILURE"; return
  fi
  if [[ "$first_timeout" == "true" ]]; then
    echo "JOURNEY_TIMEOUT_ADVISORY"; return
  fi
  if [[ "$first_outcome" == "cancelled" || "$retry_outcome" == "cancelled" || "$first_concl" == "cancelled" || "$retry_concl" == "cancelled" ]]; then
    echo "STEP_CANCELLED"; return
  fi
  if [[ -f "$s" ]] && grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$s"; then
    echo "GENUINE_FAILURE"; return
  fi
  if [[ -f "$s" ]] && grep -qE 'JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' "$s"; then
    echo "JOURNEY_TIMEOUT"; return
  fi
  echo "INFRA_UNAVAILABLE"
}
verdict="$(classify "$summary")"
[[ "$verdict" == "JOURNEY_TIMEOUT_ADVISORY" ]] \
  || fail "(e) classifier routed to '$verdict', expected JOURNEY_TIMEOUT_ADVISORY"
pass "(e) workflow classifier grep ladder routes this summary to advisory timeout"

# (f) stale-summary guard: the two cold-boot attempts share the same summary.md
#     path. If the retry is cancelled, an old `Failed BOTH attempts` summary
#     from the first attempt must NOT be used to report the retry as a genuine
#     failed-on-both-cold-boots verdict.
stale_summary="$SANDBOX/stale-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  '- `com.pocketshell.app.StaleFirstAttemptTest`' \
  > "$stale_summary"
stale_verdict="$(classify "$stale_summary" failure cancelled failure cancelled)"
[[ "$stale_verdict" == "STEP_CANCELLED" ]] \
  || fail "(f) cancelled retry with stale Failed BOTH summary routed to '$stale_verdict', expected STEP_CANCELLED"
pass "(f) cancelled retry takes precedence over stale Failed BOTH summary"

# (g) genuine journey failure still wins over timeout markers and remains red.
mixed_summary="$SANDBOX/mixed-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):' \
  '- `com.pocketshell.app.TimeoutOnlyClass`' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  '- `com.pocketshell.app.RealRegressionTest`' \
  > "$mixed_summary"
mixed_verdict="$(classify "$mixed_summary" failure failure failure failure false)"
[[ "$mixed_verdict" == "GENUINE_FAILURE" ]] \
  || fail "(g) mixed genuine-failure+timeout summary routed to '$mixed_verdict', expected GENUINE_FAILURE"
pass "(g) genuine Failed BOTH summary remains red even with timeout markers present"

# (h) workflow-real shared-summary overwrite: first attempt wrote a genuine
#     failure, retry overwrote summary.md with timeout-only content, and the
#     retry summary proves the first-failed class passed before the later budget
#     timeout. This is not a failed-on-both-cold-boots regression.
retry_timeout_summary="$SANDBOX/retry-timeout-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Passed first try:' \
  '- `com.pocketshell.app.RealRegressionTest`' \
  '' \
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):' \
  '- `com.pocketshell.app.RetryTimedOutClass`' \
  > "$retry_timeout_summary"
overwrite_verdict="$(classify "$retry_timeout_summary" failure failure failure failure false true $'com.pocketshell.app.RealRegressionTest')"
[[ "$overwrite_verdict" == "FIRST_FAILURE_CLEARED_RETRY_TIMEOUT" ]] \
  || fail "(h) cleared first failure + retry timeout routed to '$overwrite_verdict', expected FIRST_FAILURE_CLEARED_RETRY_TIMEOUT"
pass "(h) first-attempt failed class cleared on retry routes to timeout advisory"

# (i) Negative guard for (h): if the retry timeout summary does not prove the
#     first-failed class passed/recovered, preserve the first genuine-failure red.
uncleared_retry_timeout_summary="$SANDBOX/uncleared-retry-timeout-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Passed first try:' \
  '- `com.pocketshell.app.SomeOtherClass`' \
  '' \
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):' \
  '- `com.pocketshell.app.RetryTimedOutClass`' \
  > "$uncleared_retry_timeout_summary"
uncleared_verdict="$(classify "$uncleared_retry_timeout_summary" failure failure failure failure false true $'com.pocketshell.app.RealRegressionTest')"
[[ "$uncleared_verdict" == "FIRST_GENUINE_FAILURE" ]] \
  || fail "(i) uncleared first failure + retry timeout routed to '$uncleared_verdict', expected FIRST_GENUINE_FAILURE"
pass "(i) first-attempt failure remains red when retry summary does not prove it cleared"

# ---------------------------------------------------------------------------
# Issue #918: if the per-class `timeout` kills a Gradle invocation, the next
# retry can be poisoned by the still-running Gradle daemon/file-hash cache lock:
# "Cannot lock file hash cache ... locked by this process". Model that class:
# first :app invocation times out and leaves a poison marker; `gradlew --stop`
# clears it; the retry fails unless cleanup ran before it.
echo "== Timeout cleanup: per-class Gradle timeout stops daemons before retry =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${GRADLE_LOCK_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  rm -f "$state_dir/poisoned-lock"
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"

  if [[ "$count" -eq 1 ]]; then
    touch "$state_dir/poisoned-lock"
    sleep 30
    exit 0
  fi

  if [[ -e "$state_dir/poisoned-lock" ]]; then
    printf 'Cannot lock file hash cache (%s/caches/fileHashes) as it has already been locked by this process.\n' "$state_dir" >&2
    exit 77
  fi
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

lock_stub_dir="$SANDBOX/lock-stub"
rm -rf "$lock_stub_dir"
mkdir -p "$lock_stub_dir"
out_lock="$SANDBOX/run-timeout-cleanup.log"
set +e
PATH="$STUBBIN:$PATH" \
  GRADLE_LOCK_STUB_DIR="$lock_stub_dir" \
  JOURNEY_STEP_BUDGET_SECS=3600 \
  JOURNEY_CLASS_TIMEOUT_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_lock" 2>&1
rc_lock=$?
set -e

[[ "$rc_lock" -eq 0 ]] \
  || { sed -n '1,120p' "$out_lock"; fail "(i) timeout cleanup run exited $rc_lock; expected recovered success"; }
[[ -s "$lock_stub_dir/stop.log" ]] \
  || fail "(i) per-class timeout did not run gradlew --stop before retry"
grep -q 'GRADLE_TIMEOUT_CLEANUP:' "$out_lock" \
  || fail "(i) cleanup marker was not logged after timeout"
grep -q 'JOURNEY_FLAKE_RECOVERED:' "$out_lock" \
  || fail "(i) timed-out class did not recover on retry"
grep -q -- '--no-daemon :app:connectedDebugAndroidTest' "$lock_stub_dir/args.log" \
  || fail "(i) per-class journey invocation did not use --no-daemon"
if grep -q 'Cannot lock file hash cache' "$out_lock"; then
  sed -n '1,160p' "$out_lock"
  fail "(i) retry still saw the simulated Gradle file-hash lock"
fi
pass "(i) per-class timeout stops Gradle and retry avoids the poisoned file-hash lock"

# The hard timeout path matters too: GNU `timeout --kill-after` returns 137 when
# the child ignores TERM and is killed by the SIGKILL backstop. That path must
# still stop Gradle before retry or the #918 lock poisoning can survive.
echo "== Timeout cleanup: SIGKILL backstop also stops daemons before retry =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${GRADLE_LOCK_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  rm -f "$state_dir/poisoned-lock"
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"

  if [[ "$count" -eq 1 ]]; then
    trap '' TERM
    touch "$state_dir/poisoned-lock"
    sleep 30
    exit 0
  fi

  if [[ -e "$state_dir/poisoned-lock" ]]; then
    printf 'Cannot lock file hash cache (%s/caches/fileHashes) as it has already been locked by this process.\n' "$state_dir" >&2
    exit 77
  fi
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

kill_stub_dir="$SANDBOX/kill-lock-stub"
rm -rf "$kill_stub_dir"
mkdir -p "$kill_stub_dir"
out_kill="$SANDBOX/run-timeout-kill-cleanup.log"
set +e
PATH="$STUBBIN:$PATH" \
  GRADLE_LOCK_STUB_DIR="$kill_stub_dir" \
  JOURNEY_STEP_BUDGET_SECS=3600 \
  JOURNEY_CLASS_TIMEOUT_SECS=1 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_kill" 2>&1
rc_kill=$?
set -e

[[ "$rc_kill" -eq 0 ]] \
  || { sed -n '1,140p' "$out_kill"; fail "(j) SIGKILL timeout cleanup run exited $rc_kill; expected recovered success"; }
[[ -s "$kill_stub_dir/stop.log" ]] \
  || fail "(j) SIGKILL timeout did not run gradlew --stop before retry"
grep -q 'GRADLE_TIMEOUT_CLEANUP:' "$out_kill" \
  || fail "(j) cleanup marker was not logged after SIGKILL timeout"
grep -q 'JOURNEY_FLAKE_RECOVERED:' "$out_kill" \
  || fail "(j) SIGKILL-timed-out class did not recover on retry"
if grep -q 'Cannot lock file hash cache' "$out_kill"; then
  sed -n '1,180p' "$out_kill"
  fail "(j) retry still saw the simulated Gradle file-hash lock after SIGKILL timeout"
fi
pass "(j) SIGKILL timeout stops Gradle and retry avoids the poisoned file-hash lock"

echo "== Timeout cleanup: repeated SIGKILL timeout stays classified as timeout =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${GRADLE_LOCK_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  rm -f "$state_dir/poisoned-lock"
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"
  trap '' TERM
  touch "$state_dir/poisoned-lock"
  sleep 30
  exit 0
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

repeat_kill_stub_dir="$SANDBOX/repeat-kill-lock-stub"
rm -rf "$repeat_kill_stub_dir"
mkdir -p "$repeat_kill_stub_dir"
out_repeat_kill="$SANDBOX/run-timeout-repeat-kill-cleanup.log"
set +e
PATH="$STUBBIN:$PATH" \
  GRADLE_LOCK_STUB_DIR="$repeat_kill_stub_dir" \
  JOURNEY_STEP_BUDGET_SECS=5 \
  JOURNEY_CLASS_TIMEOUT_SECS=1 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_repeat_kill" 2>&1
rc_repeat_kill=$?
set -e

summary_repeat_kill="$SANDBOX/artifacts/ci-journey/summary.md"
[[ "$rc_repeat_kill" -ne 0 ]] \
  || fail "(k) repeated SIGKILL timeout exited 0; expected timeout-red"
[[ -f "$summary_repeat_kill" ]] \
  || fail "(k) repeated SIGKILL timeout did not write summary.md"
grep -q 'JOURNEY_STEP_TIMEOUT' "$summary_repeat_kill" \
  || { cat "$summary_repeat_kill"; fail "(k) repeated SIGKILL timeout summary missing JOURNEY_STEP_TIMEOUT"; }
grep -q 'JOURNEY_STEP_TIMEOUT: .*rc=137' "$out_repeat_kill" \
  || { sed -n '1,180p' "$out_repeat_kill"; fail "(k) repeated SIGKILL timeout did not exercise rc=137 classification"; }
if grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$summary_repeat_kill"; then
  cat "$summary_repeat_kill"
  fail "(k) repeated SIGKILL timeout was misclassified as a genuine failed-both regression"
fi
stop_count="$(wc -l < "$repeat_kill_stub_dir/stop.log" 2>/dev/null || printf '0')"
[[ "$stop_count" -ge 2 ]] \
  || fail "(k) repeated SIGKILL timeout did not clean up after both attempts"
pass "(k) repeated SIGKILL timeout remains JOURNEY_STEP_TIMEOUT, not JOURNEY_FAILED"

echo "== Timeout cleanup: immediate SIGKILL remains a genuine failure =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${GRADLE_LOCK_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  sleep "${GRADLE_STOP_STUB_SLEEP_SECS:-0}"
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"
  kill -9 "$$"
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

early_kill_stub_dir="$SANDBOX/early-kill-lock-stub"
rm -rf "$early_kill_stub_dir"
mkdir -p "$early_kill_stub_dir"
out_early_kill="$SANDBOX/run-timeout-early-kill-cleanup.log"
set +e
PATH="$STUBBIN:$PATH" \
  GRADLE_LOCK_STUB_DIR="$early_kill_stub_dir" \
  GRADLE_STOP_STUB_SLEEP_SECS=3 \
  JOURNEY_STEP_BUDGET_SECS=2 \
  JOURNEY_CLASS_TIMEOUT_SECS=30 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_early_kill" 2>&1
rc_early_kill=$?
set -e

summary_early_kill="$SANDBOX/artifacts/ci-journey/summary.md"
[[ "$rc_early_kill" -ne 0 ]] \
  || fail "(l) immediate SIGKILL exited 0; expected genuine failure"
[[ -f "$summary_early_kill" ]] \
  || fail "(l) immediate SIGKILL did not write summary.md"
grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$summary_early_kill" \
  || { cat "$summary_early_kill"; fail "(l) immediate SIGKILL summary missing genuine failed-both marker"; }
if grep -q 'JOURNEY_STEP_TIMEOUT: .*rc=137' "$out_early_kill"; then
  sed -n '1,180p' "$out_early_kill"
  fail "(l) immediate SIGKILL was misclassified as a timeout-owned rc=137"
fi
[[ -s "$early_kill_stub_dir/stop.log" ]] \
  || fail "(l) immediate SIGKILL still should run Gradle cleanup before retry"
pass "(l) immediate SIGKILL remains JOURNEY_FAILED even when cleanup spends the budget"

# ---------------------------------------------------------------------------
# Negative control: a clean PASS run (generous budget, fast gradle stub) must
# NOT trip the timeout path — proves the budget does not falsely flag healthy
# runs. We stub gradle to return instantly and give a large budget. (We do not
# need every real class to pass — we only assert NO JOURNEY_STEP_TIMEOUT marker
# is produced and summary.md is written.)
echo "== Negative control: generous budget + instant gradle stub =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

out2="$SANDBOX/run-clean.log"
set +e
PATH="$STUBBIN:$PATH" \
  JOURNEY_STEP_BUDGET_SECS=3600 \
  JOURNEY_CLASS_TIMEOUT_SECS=420 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out2" 2>&1
rc2=$?
set -e

summary2="$SANDBOX/artifacts/ci-journey/summary.md"
[[ -f "$summary2" ]] || fail "(neg) summary.md not written on the clean run"
if grep -q 'JOURNEY_STEP_TIMEOUT' "$summary2"; then
  cat "$summary2"
  fail "(neg) clean run wrongly produced a JOURNEY_STEP_TIMEOUT marker"
fi
# NOTE: this standalone classify() does not model the workflow's earlier
# "first attempt outcome == success -> exit 0" short-circuit; in the real
# workflow a clean PASS never reaches the grep ladder at all. We only assert
# the summary itself does NOT carry the timeout marker (so it could not be
# mis-routed to JOURNEY_TIMEOUT if the ladder were reached).
neg_verdict="$(classify "$summary2")"
[[ "$neg_verdict" != "JOURNEY_TIMEOUT" ]] \
  || fail "(neg) clean run mis-classified as JOURNEY_TIMEOUT"
pass "(neg) clean run: no false timeout (summary has no timeout marker; rc=$rc2)"

echo
echo "ALL TESTS PASSED"
