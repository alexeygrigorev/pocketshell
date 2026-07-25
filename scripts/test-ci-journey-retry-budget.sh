#!/usr/bin/env bash
# Issue #1458: deterministic, JVM-free regression test for the emulator-journey
# cold-boot retry budget. A retry may start only when the absolute 95-minute job
# deadline leaves enough wall time for shutdown/boot, the selected suite, and
# classifier/artifact teardown.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HELPER="${CI_JOURNEY_RETRY_BUDGET_HELPER:-$SCRIPT_DIR/ci-journey-retry-budget.sh}"
WORKFLOW="${CI_JOURNEY_RETRY_BUDGET_WORKFLOW:-$REPO_ROOT/.github/workflows/tests.yml}"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$HELPER" ]] || fail "retry-budget helper missing: $HELPER"
[[ -f "$WORKFLOW" ]] || fail "workflow missing: $WORKFLOW"
[[ -f "$AGG" ]] || fail "aggregate helper missing: $AGG"

run_decision() {
  local start="${1-}" now="${2-}"
  DECISION_RC=0
  DECISION_OUT="$(bash "$HELPER" "$start" "$now" 2>&1)" || DECISION_RC=$?
  DECISION_ALLOWED="$(sed -n 's/^retry_allowed=//p' <<<"$DECISION_OUT" | tail -n 1)"
  DECISION_REASON="$(sed -n 's/^retry_reason=//p' <<<"$DECISION_OUT" | tail -n 1)"
  DECISION_REMAINING="$(sed -n 's/^retry_remaining_ms=//p' <<<"$DECISION_OUT" | tail -n 1)"
}

assert_decision() {
  local label="$1" start="$2" now="$3" allowed="$4" reason="$5" remaining="$6"
  run_decision "$start" "$now"
  [[ "$DECISION_RC" -eq 0 ]] \
    || { printf '%s\n' "$DECISION_OUT"; fail "$label: helper exited $DECISION_RC"; }
  [[ "$DECISION_ALLOWED" == "$allowed" ]] \
    || { printf '%s\n' "$DECISION_OUT"; fail "$label: allowed=$DECISION_ALLOWED, expected $allowed"; }
  [[ "$DECISION_REASON" == "$reason" ]] \
    || { printf '%s\n' "$DECISION_OUT"; fail "$label: reason=$DECISION_REASON, expected $reason"; }
  [[ "$DECISION_REMAINING" == "$remaining" ]] \
    || { printf '%s\n' "$DECISION_OUT"; fail "$label: remaining=$DECISION_REMAINING, expected $remaining"; }
}

echo "== #1458 absolute retry-budget boundaries =="

# Helper constants are pinned by the acceptance contract:
#   95m absolute job cap = 5,700,000ms
#   retry reserve = 900s boot/readiness + 4200s suite + 300s teardown
job_start=1000000
job_deadline=$((job_start + 5700000))
retry_threshold=5400000

assert_decision "threshold-1ms" "$job_start" "$((job_deadline - retry_threshold + 1))" \
  false insufficient_remaining_budget "$((retry_threshold - 1))"
pass "threshold-1ms denies the cold-boot retry"

assert_decision "threshold" "$job_start" "$((job_deadline - retry_threshold))" \
  true sufficient_remaining_budget "$retry_threshold"
pass "the exact threshold allows the cold-boot retry"

assert_decision "threshold+1ms" "$job_start" "$((job_deadline - retry_threshold - 1))" \
  true sufficient_remaining_budget "$((retry_threshold + 1))"
pass "threshold+1ms allows the cold-boot retry"

assert_decision "missing start" "" "$job_start" false missing_job_start 0
pass "a missing job-start epoch fails safe (retry denied)"

assert_decision "malformed start" "not-an-epoch" "$job_start" false malformed_job_start 0
pass "a malformed job-start epoch fails safe (retry denied)"

assert_decision "malformed now" "$job_start" "not-an-epoch" false malformed_now 0
pass "a malformed current epoch fails safe (retry denied)"

assert_decision "future start" "2000" "1000" false future_job_start 0
pass "a future job-start epoch fails safe (retry denied)"

assert_decision "leading-zero start" "08" "9" false noncanonical_job_start 0
pass "a leading-zero job-start epoch fails safe without octal parsing"

assert_decision "leading-zero now" "8" "09" false noncanonical_now 0
pass "a leading-zero current epoch fails safe without octal parsing"

oversized_epoch=99999999999999999999999999999999999999999999999999
assert_decision "oversized start" "$oversized_epoch" "$job_start" false out_of_range_job_start 0
pass "an oversized job-start epoch fails safe without shell overflow"

assert_decision "oversized now" "$job_start" "9223372036854775808" false out_of_range_now 0
pass "an oversized current epoch fails safe without shell overflow"

# A valid epoch near INT64_MAX must still use the same exact threshold without
# ever forming start+cap (which would overflow signed shell arithmetic).
near_int64_start=9223372036854475807
assert_decision "near-int64 exact threshold" "$near_int64_start" "9223372036854775807" \
  true sufficient_remaining_budget "$retry_threshold"
pass "valid near-INT64_MAX epochs retain exact-threshold green semantics"

echo
echo "== #1458 allowed/denied retry paths =="

retry_calls=0
run_retry_if_allowed() {
  local start="$1" now="$2"
  run_decision "$start" "$now"
  [[ "$DECISION_ALLOWED" == "true" ]] && retry_calls=$((retry_calls + 1))
}

run_retry_if_allowed "$job_start" "$((job_deadline - retry_threshold))"
[[ "$retry_calls" -eq 1 ]] || fail "allowed path did not invoke the retry exactly once"
pass "sufficient budget starts one retry"

run_retry_if_allowed "$job_start" "$((job_deadline - retry_threshold + 1))"
[[ "$retry_calls" -eq 1 ]] || fail "denied path invoked the retry"
pass "insufficient budget starts no retry"

echo
echo "== #1458 real-workflow wiring =="

grep -q 'JOURNEY_JOB_START_EPOCH_MS=' "$WORKFLOW" \
  || fail "workflow does not record a job-start epoch"
grep -q 'name: Check remaining job wall before journey retry' "$WORKFLOW" \
  || fail "workflow does not run the retry-budget decision step"
grep -q 'ci-journey-retry-budget.sh' "$WORKFLOW" \
  || fail "workflow does not call the real retry-budget helper"
grep -q "steps.journey_retry_budget.outputs.retry_allowed == 'true'" "$WORKFLOW" \
  || fail "cold-boot retry is not guarded by retry_allowed=true"
grep -q 'retry skipped — insufficient remaining job wall' "$WORKFLOW" \
  || fail "classifier lacks typed insufficient-budget INFRA evidence"
grep -q 'write_verdict INFRA' "$WORKFLOW" \
  || fail "classifier cannot emit the INFRA shard token"

retry_line="$(grep -n 'name: Retry journey subset on a fresh cold-booted emulator' "$WORKFLOW" | cut -d: -f1)"
classify_line="$(grep -n 'name: Classify emulator-journey result' "$WORKFLOW" | cut -d: -f1)"
upload_line="$(grep -n 'name: Upload shard verdict token' "$WORKFLOW" | cut -d: -f1)"
[[ "$retry_line" =~ ^[0-9]+$ && "$classify_line" =~ ^[0-9]+$ && "$upload_line" =~ ^[0-9]+$ ]] \
  || fail "could not locate retry/classifier/verdict-upload steps"
[[ "$retry_line" -lt "$classify_line" && "$classify_line" -lt "$upload_line" ]] \
  || fail "no-budget path cannot reach classifier then verdict artifact upload"
pass "denied path skips retry, then reaches classifier and shard-token upload"

# The new INFRA path must not soften the two pre-existing hard-red meanings:
# a healthy-console genuine first failure and a #835 suite-budget timeout.
# shellcheck disable=SC2016 # Literal workflow shell expressions, not test vars.
first_failure_line="$(grep -Fn 'if [[ "${first_failure:-false}" == "true" ]]' "$WORKFLOW" | cut -d: -f1)"
# shellcheck disable=SC2016
first_timeout_line="$(grep -Fn 'if [[ "${first_timeout:-false}" == "true" ]]' "$WORKFLOW" | cut -d: -f1)"
# shellcheck disable=SC2016
budget_infra_line="$(grep -Fn 'if [[ "${retry_allowed:-false}" != "true" ]]' "$WORKFLOW" | cut -d: -f1)"
[[ "$first_failure_line" =~ ^[0-9]+$ && "$first_timeout_line" =~ ^[0-9]+$ && "$budget_infra_line" =~ ^[0-9]+$ ]] \
  || fail "could not locate genuine-failure/timeout/budget classifier branches"
[[ "$first_failure_line" -lt "$budget_infra_line" && "$first_timeout_line" -lt "$budget_infra_line" ]] \
  || fail "insufficient-budget INFRA branch can mask a genuine failure or #835 timeout RED"
pass "genuine first-failure and #835 timeout RED semantics precede budget INFRA"

# Model the typed no-budget result through the REAL aggregate reducer. Three
# completed shard jobs each report INFRA, producing the existing RE-RUN state.
verdict_dir="$(mktemp -d)"
trap 'rm -rf "$verdict_dir"' EXIT
for shard in 0 1 2; do
  mkdir -p "$verdict_dir/emulator-journey-verdict-shard-$shard"
  printf 'INFRA\n' > "$verdict_dir/emulator-journey-verdict-shard-$shard/shard-verdict.txt"
done
agg_rc=0
agg_out="$(EXPECTED_SHARDS=3 GITHUB_STEP_SUMMARY="" bash "$AGG" "$verdict_dir" 2>&1)" || agg_rc=$?
[[ "$agg_rc" -eq 0 ]] || { printf '%s\n' "$agg_out"; fail "complete no-budget INFRA evidence did not aggregate cleanly"; }
grep -q '^AGGREGATE_VERDICT=RE-RUN$' <<<"$agg_out" \
  || { printf '%s\n' "$agg_out"; fail "complete no-budget INFRA evidence did not produce RE-RUN"; }
pass "complete INFRA shard evidence reaches aggregate RE-RUN (not cancellation)"

echo
echo "ALL TESTS PASSED: scripts/test-ci-journey-retry-budget.sh"
