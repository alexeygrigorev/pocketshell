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
#     failure, retry overwrote summary.md with timeout-only content and did not
#     pass. The captured first_failure output must prevent advisory-green.
retry_timeout_summary="$SANDBOX/retry-timeout-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):' \
  '- `com.pocketshell.app.RetryTimedOutClass`' \
  > "$retry_timeout_summary"
overwrite_verdict="$(classify "$retry_timeout_summary" failure failure failure failure false true)"
[[ "$overwrite_verdict" == "FIRST_GENUINE_FAILURE" ]] \
  || fail "(h) first genuine failure + retry timeout overwrite routed to '$overwrite_verdict', expected FIRST_GENUINE_FAILURE"
pass "(h) first genuine failure remains red when retry overwrites summary with timeout-only content"

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
