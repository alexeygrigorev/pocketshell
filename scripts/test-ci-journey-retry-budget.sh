#!/usr/bin/env bash
# Issue #1458: deterministic, JVM-free regression test for the emulator-journey
# cold-boot retry budget. A retry may start only when the absolute 95-minute job
# deadline leaves enough wall time for shutdown/boot, the selected suite, and
# classifier/artifact teardown.
#
# Issue #1833 extends this with the WARM-retry correction and the one-shot
# surfacing. See the `#1833` sections at the bottom.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HELPER="${CI_JOURNEY_RETRY_BUDGET_HELPER:-$SCRIPT_DIR/ci-journey-retry-budget.sh}"
WORKFLOW="${CI_JOURNEY_RETRY_BUDGET_WORKFLOW:-$REPO_ROOT/.github/workflows/tests.yml}"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"
WARM_ELAPSED="$SCRIPT_DIR/ci-journey-warm-build-elapsed.sh"
SUITE_ELAPSED="$SCRIPT_DIR/ci-journey-suite-elapsed.sh"
WRITER="$SCRIPT_DIR/ci-journey-write-shard-verdict.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$HELPER" ]] || fail "retry-budget helper missing: $HELPER"
[[ -f "$WORKFLOW" ]] || fail "workflow missing: $WORKFLOW"
[[ -f "$AGG" ]] || fail "aggregate helper missing: $AGG"
[[ -f "$WARM_ELAPSED" ]] || fail "warm-build elapsed reader missing: $WARM_ELAPSED"
[[ -f "$SUITE_ELAPSED" ]] || fail "suite elapsed reader missing: $SUITE_ELAPSED"
[[ -f "$WRITER" ]] || fail "shard verdict writer missing: $WRITER"

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
# a genuine first failure and a #835 suite-budget timeout.
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
echo "== #1833 the OBSERVED shortfall, from run 30383504733's three shard logs =="

# THE REPRODUCTION. These five numbers per shard are read out of the shard job
# logs of run 30383504733 (main @ 944b1769) — nothing here is invented:
#
#   shard 0, job 90365277532: "Warm build (issue #1814): **ok** in 464s",
#     "| 49 load-bearing journey classes (shard 0/3 ...) | ... | 2512s |",
#     "cold-boot retry remaining/required: 2992443/3216836ms"
#   shard 1, job 90365277531: warm 373s, suite 3017s, 2396465/3752989ms
#   shard 2, job 90365277470: warm 489s, suite 2531s, 2976238/3240868ms
#
# All three reported `retry_allowed=false reason=insufficient_remaining_budget`.
# `boot_ms` below is the residual the model derives — (now - attempt start) -
# suite — and it is pinned by requiring the 4-arg model to reproduce the observed
# `required` EXACTLY. If that reconstruction ever stops matching, this fixture is
# no longer describing the real run and the test says so instead of drifting.
#
# JOB_START is arbitrary: only `now - JOB_START` (which fixes `remaining`) and
# `now - attempt_start` (which fixes the residual) are load-bearing.
JOB_CAP_MS=5700000
fixture_job_start=1000000

# reconstruct_now <remaining_ms>
reconstruct_now() { printf '%s' "$((fixture_job_start + JOB_CAP_MS - $1))"; }
# reconstruct_attempt_start <now> <suite_secs> <boot_ms>
reconstruct_attempt_start() { printf '%s' "$(( $1 - ($2 * 1000) - $3 ))"; }

BUDGET_OUT=""
budget_field() { sed -n "s/^$1=//p" <<<"$BUDGET_OUT" | tail -n 1; }
run_budget() {
  BUDGET_OUT="$(bash "$HELPER" "$@" 2>&1)" \
    || fail "retry-budget helper exited non-zero for args: $*"
}

# shard | remaining | observed required | suite s | warm s | boot ms
SHARD_FIXTURES=(
  "0|2992443|3216836|2512|464|51212"
  "1|2396465|3752989|3017|373|44763"
  "2|2976238|3240868|2531|489|52256"
)

restored=0
for fixture in "${SHARD_FIXTURES[@]}"; do
  IFS='|' read -r idx remaining observed_required suite warm boot_ms <<<"$fixture"
  now="$(reconstruct_now "$remaining")"
  attempt_start="$(reconstruct_attempt_start "$now" "$suite" "$boot_ms")"

  # (o) RED — the model as it shipped (#1800, no warm-build reading) reproduces
  #     the observed refusal AND the observed `required` to the millisecond.
  run_budget "$fixture_job_start" "$now" "$attempt_start" "$suite"
  [[ "$(budget_field retry_remaining_ms)" == "$remaining" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(o/shard $idx) reconstruction gives remaining=$(budget_field retry_remaining_ms), the log says $remaining"; }
  [[ "$(budget_field retry_required_ms)" == "$observed_required" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(o/shard $idx) the #1800 model gives required=$(budget_field retry_required_ms), the log says $observed_required — this fixture no longer describes run 30383504733"; }
  [[ "$(budget_field retry_allowed)" == "false" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(o/shard $idx) the #1800 model must reproduce the observed REFUSAL"; }
  [[ "$(budget_field retry_reason)" == "insufficient_remaining_budget" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(o/shard $idx) expected insufficient_remaining_budget, got $(budget_field retry_reason)"; }
  [[ "$(budget_field retry_cost_model)" == "measured_first_attempt" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(o/shard $idx) the 4-arg path must still report measured_first_attempt — #1800 is preserved unchanged"; }
  old_required="$(budget_field retry_required_ms)"
  old_shortfall="$(budget_field retry_shortfall_ms)"
  old_deducted="$(budget_field retry_warm_build_deducted_ms)"

  # (p) GREEN — with the measured cold build supplied, the requirement drops by
  #     the double-charged build and the affordability verdict is re-decided.
  run_budget "$fixture_job_start" "$now" "$attempt_start" "$suite" "$warm"
  new_required="$(budget_field retry_required_ms)"
  (( new_required < old_required )) \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(p/shard $idx) the warm correction must LOWER the requirement ($new_required vs $old_required)"; }
  [[ "$(budget_field retry_cost_model)" == "measured_warm_retry" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(p/shard $idx) the corrected path must name itself measured_warm_retry"; }
  [[ "$(budget_field retry_warm_build_deducted_ms)" == "$((warm * 1000))" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(p/shard $idx) expected ${warm}s deducted, got $(budget_field retry_warm_build_deducted_ms)ms"; }
  [[ "$old_deducted" == "0" ]] \
    || fail "(p/shard $idx) the 4-arg #1800 path must deduct nothing, reported '$old_deducted'"
  [[ "$old_shortfall" == "$((observed_required - remaining))" ]] \
    || fail "(p/shard $idx) the #1800 shortfall '$old_shortfall' != required-minus-remaining"
  [[ "$(budget_field retry_remaining_ms)" == "$remaining" ]] \
    || fail "(p/shard $idx) the correction must not touch the remaining wall"
  echo "    shard $idx: suite ${suite}s (warm build ${warm}s) — required ${old_required}ms -> ${new_required}ms against ${remaining}ms remaining => allowed=$(budget_field retry_allowed)"

  if [[ "$idx" == "1" ]]; then
    # Shard 1 is the evidenced EXCEPTION, and it is arithmetic, not opinion: its
    # first attempt's suite alone (3017s) already exceeds the 2396s of job wall it
    # had left. A retry that repeated the suite at EXACTLY the first attempt's
    # cost, with a free boot, no headroom and zero teardown, would still overrun.
    # No correction to the ESTIMATE can make this shard afford a retry; what ate
    # its wall was the ~418s the twice-failing PreExistingMultiWindowSeedE2eTest
    # plus ~127s of three flake retries spent INSIDE the suite. So the honest
    # outcome here is the surfacing below, not a cheaper number.
    [[ "$(budget_field retry_allowed)" == "false" ]] \
      || { printf '%s\n' "$BUDGET_OUT"; fail "(p/shard 1) must NOT be talked into affording a retry it cannot run"; }
    (( suite * 1000 > remaining )) \
      || fail "(p/shard 1) the fixture no longer shows suite > remaining, so the unaffordability argument no longer holds"
    (( new_required > remaining )) \
      || fail "(p/shard 1) required must still exceed remaining"
    pass "(p) shard 1 stays REFUSED — its 3017s suite alone exceeds the 2396s of wall it had left (unaffordable by arithmetic, not by estimate)"
  else
    [[ "$(budget_field retry_allowed)" == "true" ]] \
      || { printf '%s\n' "$BUDGET_OUT"; fail "(p/shard $idx) the corrected model must restore this shard's retry"; }
    [[ "$(budget_field retry_shortfall_ms)" == "0" ]] \
      || fail "(p/shard $idx) an affordable retry must report zero shortfall"
    restored=$((restored + 1))
  fi
done
(( restored == 2 )) || fail "(p) expected shards 0 and 2 to regain their retry, got $restored"
pass "(o) the #1800 model reproduces run 30383504733's refusal AND required-ms on all three shards"
pass "(p) on run 30383504733 the warm-retry correction restores the retry on shards 0 and 2"

echo
echo "== #1833 AC2: the correction across EVERY run with retrievable figures =="

# (x) THE HONEST SCOREBOARD. One run is not a population. These are all nine
#     shard-runs for which the job logs still carry `retry_remaining_ms` /
#     `retry_required_ms` / the summary's suite elapsed:
#
#       30351095421  main @ 26a43822  (post-#1814; warm builds MEASURED 353/479/434s)
#       30339688411  main @ 8a6c04b5  (pre-#1814; no warm-build line exists)
#       30383504733  main @ 944b1769  (post-#1814; warm builds MEASURED 464/373/489s)
#       30392101105  main @ 45f34af8  (post-#1814; warm builds MEASURED 463/462/448s)
#
#     Each row's `boot residual` is derived by inverting #1800's own arithmetic
#     from the logged `required`, and case (o2) below re-asserts that inversion
#     against the logged number for every row — so if any figure here ever stops
#     describing the real run, this test says so rather than drifting.
#
#     Run 30339688411 predates #1814, so it has no measured warm build at all.
#     It is modelled at 489s — the LARGEST value in the whole observed 373-489s
#     band, i.e. the most generous deduction the correction could possibly make
#     for it. If it is refused even there, no warm measurement could have saved
#     it.
#
#     The result is the number this issue's AC2 has to be judged on: the
#     correction restores 3 of the 10 denied shard-runs, and 7 stay denied.
#
# run | shard | remaining | logged required (#1800) | suite s | warm s | boot residual ms
ALL_SHARD_RUNS=(
  "30351095421|0|3585619|2501456|1867|353|49252"
  "30351095421|1|2862065|3384547|2654|479|55049"
  "30351095421|2|2963004|3267938|2542|434|57246"
  "30339688411|0|2843970|3376016|2653|489|52572"
  "30339688411|1|2601782|3657891|2916|489|50097"
  "30339688411|2|2573208|3719876|2968|489|51692"
  "30383504733|0|2992443|3216836|2512|464|51212"
  "30383504733|1|2396465|3752989|3017|373|44763"
  "30383504733|2|2976238|3240868|2531|489|52256"
  "30392101105|0|3104994|3102306|2367|463|66202"
  "30392101105|1|2771142|3452850|2724|462|52150"
  "30392101105|2|2532733|3745191|2994|448|50597"
)
# The verdicts this scoreboard asserts, in the same order:
#   allow-allow  already affordable before the correction (2 rows)
#   deny-allow   RESTORED by the correction               (3 rows)
#   deny-deny    still denied after the correction        (7 rows)
ALL_SHARD_EXPECTED=(
  "allow|allow" "deny|deny" "deny|allow"
  "deny|deny"   "deny|deny" "deny|deny"
  "deny|allow"  "deny|deny" "deny|allow"
  "allow|allow" "deny|deny" "deny|deny"
)
already=0; restored_all=0; still_denied=0
for i in "${!ALL_SHARD_RUNS[@]}"; do
  IFS='|' read -r run idx remaining logged_required suite warm boot_ms <<<"${ALL_SHARD_RUNS[$i]}"
  IFS='|' read -r want_before want_after <<<"${ALL_SHARD_EXPECTED[$i]}"
  now="$(reconstruct_now "$remaining")"
  attempt_start="$(reconstruct_attempt_start "$now" "$suite" "$boot_ms")"

  # (o2) the reconstruction must still reproduce the logged #1800 requirement.
  run_budget "$fixture_job_start" "$now" "$attempt_start" "$suite"
  [[ "$(budget_field retry_remaining_ms)" == "$remaining" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(o2/$run shard $idx) reconstruction gives remaining=$(budget_field retry_remaining_ms), the log says $remaining"; }
  [[ "$(budget_field retry_required_ms)" == "$logged_required" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(o2/$run shard $idx) the #1800 model gives required=$(budget_field retry_required_ms), the log says $logged_required — this row no longer describes run $run"; }
  got_before=deny
  [[ "$(budget_field retry_allowed)" == "true" ]] && got_before=allow
  [[ "$got_before" == "$want_before" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(x/$run shard $idx) #1800 verdict is $got_before, the log says $want_before"; }

  # (x) the corrected model's verdict for the same shard-run.
  run_budget "$fixture_job_start" "$now" "$attempt_start" "$suite" "$warm"
  got_after=deny
  [[ "$(budget_field retry_allowed)" == "true" ]] && got_after=allow
  [[ "$got_after" == "$want_after" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(x/$run shard $idx) the corrected model says $got_after, this scoreboard records $want_after"; }

  margin=$(( $(budget_field retry_remaining_ms) - $(budget_field retry_required_ms) ))
  echo "    $run shard $idx: suite ${suite}s warm ${warm}s — ${want_before} -> ${want_after} (margin ${margin}ms)"
  if [[ "$want_before" == "allow" ]]; then already=$((already + 1))
  elif [[ "$want_after" == "allow" ]]; then restored_all=$((restored_all + 1))
  else still_denied=$((still_denied + 1)); fi
done
(( already + restored_all + still_denied == 12 )) || fail "(x) the scoreboard lost a row"
(( already == 2 )) \
  || fail "(x) expected exactly 2 shard-runs that could already afford their retry, got $already"
(( restored_all == 3 )) \
  || fail "(x) the correction is recorded as restoring 3 of the 10 denied shard-runs; this run says $restored_all. If the model changed, the issue's AC2 conclusion must be re-derived — do not just update this number."
(( still_denied == 7 )) \
  || fail "(x) the correction is recorded as leaving 7 of the 10 denied shard-runs denied; this run says $still_denied"
pass "(o2) the reconstruction reproduces the logged #1800 requirement on all 12 retrievable shard-runs"
pass "(x) the correction restores $restored_all of the 10 denied shard-runs and leaves $still_denied denied — per-shard load, not the estimate, is what refuses the other 7"

# (x2) …and the correction is still WORTH KEEPING where it does not flip a
#      verdict. Run 30392101105 shard 0 was allowed by #1800 with a margin of
#      2688ms — under three seconds of the 95-minute job. That is a coin flip,
#      not health: 1.3s of extra suite would have refused it. The correction
#      turns that into 391988ms. This is the half of AC2 the pricing fix does
#      genuinely deliver, and it must not be lost when the load argument is made.
now="$(reconstruct_now 3104994)"
attempt_start="$(reconstruct_attempt_start "$now" 2367 66202)"
run_budget "$fixture_job_start" "$now" "$attempt_start" 2367
razor_1800=$(( $(budget_field retry_remaining_ms) - $(budget_field retry_required_ms) ))
(( razor_1800 >= 0 && razor_1800 < 5000 )) \
  || fail "(x2) run 30392101105 shard 0 is recorded as allowed by #1800 on a sub-5s margin; this run computes ${razor_1800}ms"
run_budget "$fixture_job_start" "$now" "$attempt_start" 2367 463
razor_1833=$(( $(budget_field retry_remaining_ms) - $(budget_field retry_required_ms) ))
(( razor_1833 > razor_1800 * 100 )) \
  || fail "(x2) the correction must widen a razor-thin ALLOWED margin by orders of magnitude, ${razor_1800}ms -> ${razor_1833}ms"
pass "(x2) on the one already-allowed razor case the correction widens +${razor_1800}ms (1s of drift) to +${razor_1833}ms ($((razor_1833 / 2100))s of drift) — the pricing fix earns its keep even where it flips no verdict"

echo
echo "== #1833 AC2: how much suite drift each restored shard-run survives =="

# (y) A restoration that survives 24 seconds is not a restoration you can rely
#     on, and reporting it without that number would be the thin-margin claim
#     this issue's own scope warns against.
#
#     Drift is modelled the way it actually happens: the suite runs δ seconds
#     longer, so the job has spent δ more seconds of wall by the time the budget
#     is asked (remaining -1000δ ms) and the retry is priced for a δ-longer
#     suite (required +1100δ ms). The boot residual is held fixed. The margin
#     therefore decays at 2100 ms per second of suite growth — but nothing here
#     ASSUMES that: each boundary below is found by driving the REAL helper at
#     the recorded drift and again one second past it.
#
# run | shard | remaining | suite s | warm s | boot ms | last affordable drift s
RESTORED_DRIFT=(
  "30351095421|2|2963004|2542|434|57246|24"
  "30383504733|0|2992443|2512|464|51212|79"
  "30383504733|2|2976238|2531|489|52256|72"
)
(( ${#RESTORED_DRIFT[@]} == restored_all )) \
  || fail "(y) every restored shard-run must have its drift budget recorded; ${#RESTORED_DRIFT[@]} recorded vs $restored_all restored"
# drift_verdict <remaining> <suite> <warm> <boot_ms> <delta_secs> -> echoes allow|deny
drift_verdict() {
  local remaining="$1" suite="$2" warm="$3" boot_ms="$4" delta="$5" d_now d_start
  d_now=$(( fixture_job_start + JOB_CAP_MS - remaining + delta * 1000 ))
  d_start=$(( d_now - (suite + delta) * 1000 - boot_ms ))
  run_budget "$fixture_job_start" "$d_now" "$d_start" "$((suite + delta))" "$warm"
  if [[ "$(budget_field retry_allowed)" == "true" ]]; then echo allow; else echo deny; fi
}
for fixture in "${RESTORED_DRIFT[@]}"; do
  IFS='|' read -r run idx remaining suite warm boot_ms drift <<<"$fixture"
  [[ "$(drift_verdict "$remaining" "$suite" "$warm" "$boot_ms" 0)" == "allow" ]] \
    || fail "(y/$run shard $idx) this row is listed as restored but is refused at zero drift"
  [[ "$(drift_verdict "$remaining" "$suite" "$warm" "$boot_ms" "$drift")" == "allow" ]] \
    || fail "(y/$run shard $idx) recorded as surviving ${drift}s of suite drift, but the real helper refuses it there"
  [[ "$(drift_verdict "$remaining" "$suite" "$warm" "$boot_ms" "$((drift + 1))" )" == "deny" ]] \
    || fail "(y/$run shard $idx) recorded as flipping back at $((drift + 1))s of drift, but the real helper still allows it — the recorded margin is understated"
  pct=$(( drift * 1000 / suite ))
  echo "    $run shard $idx: survives ${drift}s of suite drift (${pct}‰ of its ${suite}s suite), refused at $((drift + 1))s"
done
# The whole point of the number: a SINGLE flaked journey class costs more than
# any of these margins. On run 30383504733, shard 1's PreExistingMultiWindowSeed
# failure alone cost 417s across its two attempts — 5x the largest margin here.
worst_drift=0
for fixture in "${RESTORED_DRIFT[@]}"; do
  IFS='|' read -r _ _ _ _ _ _ drift <<<"$fixture"
  (( drift > worst_drift )) && worst_drift="$drift"
done
(( worst_drift < 417 )) \
  || fail "(y) the restored margins now exceed the 417s a single twice-failing class cost on run 30383504733; the 'a retry only when nothing went wrong' finding must be re-derived"
pass "(y) every restored shard-run is within ${worst_drift}s of flipping back — less than the 417s ONE twice-failing class cost on the same run, so the retry returns only on runs that did not need it"

echo
echo "== #1833 AC2: affordability is a function of SUITE ELAPSED, and we sit on the line =="

# (z) THE RELATIONSHIP, not another anecdote. Hold each shard-run's fixed costs
#     — the pre-suite job wall, the boot residual, the measured warm build — and
#     sweep the suite. Every row has a single crossing point: below it the
#     corrected model allows the retry, above it the model refuses. There is no
#     second variable; the retry is affordable iff the suite is short enough.
#
#     Across all twelve retrievable shard-runs that crossing sits in a band only
#     ~110s wide, and SEVEN of the twelve observed suites are above their own
#     crossing. That is the quantified form of "per-shard load is the binding
#     constraint": the gate is not near a fixable estimate, it is sitting on the
#     affordability line with the distribution straddling it.
#
#     Found by driving the REAL helper, not by re-deriving its arithmetic here.
BREAKEVEN_LO=2500
BREAKEVEN_HI=2650

# breakeven_suite <remaining> <suite> <warm> <boot_ms> — largest suite (secs) at
# which the corrected model still allows the retry, all else held fixed. Bisects
# the REAL helper; hard-fails if the boundary is not bracketed.
breakeven_suite() {
  local remaining="$1" suite="$2" warm="$3" boot_ms="$4"
  local pre_suite lo=600 hi=4200 mid b_now b_start
  pre_suite=$(( JOB_CAP_MS - remaining - suite * 1000 ))
  _verdict_at() {
    b_now=$(( fixture_job_start + pre_suite + $1 * 1000 ))
    b_start=$(( b_now - $1 * 1000 - boot_ms ))
    run_budget "$fixture_job_start" "$b_now" "$b_start" "$1" "$warm"
    [[ "$(budget_field retry_allowed)" == "true" ]]
  }
  _verdict_at "$lo" || { echo "unbracketed-low"; return; }
  _verdict_at "$hi" && { echo "unbracketed-high"; return; }
  while (( hi - lo > 1 )); do
    mid=$(( (lo + hi) / 2 ))
    if _verdict_at "$mid"; then lo="$mid"; else hi="$mid"; fi
  done
  echo "$lo"
}

over_the_line=0
for entry in "${ALL_SHARD_RUNS[@]}"; do
  IFS='|' read -r run idx remaining _logged suite warm boot_ms <<<"$entry"
  bk="$(breakeven_suite "$remaining" "$suite" "$warm" "$boot_ms")"
  [[ "$bk" =~ ^[0-9]+$ ]] \
    || fail "(z/$run shard $idx) the affordability boundary is not bracketed in 600-4200s ($bk) — affordability is not the single-crossing function this conclusion assumes"
  (( bk >= BREAKEVEN_LO && bk <= BREAKEVEN_HI )) \
    || fail "(z/$run shard $idx) break-even suite ${bk}s falls outside the recorded ${BREAKEVEN_LO}-${BREAKEVEN_HI}s band; the AC2 conclusion is derived from that band and must be re-derived"
  # The boundary must actually decide this row: below it allow, above it deny.
  if (( suite > bk )); then
    over_the_line=$((over_the_line + 1))
    verdict=deny
  else
    verdict=allow
  fi
  now="$(reconstruct_now "$remaining")"
  attempt_start="$(reconstruct_attempt_start "$now" "$suite" "$boot_ms")"
  run_budget "$fixture_job_start" "$now" "$attempt_start" "$suite" "$warm"
  got=deny; [[ "$(budget_field retry_allowed)" == "true" ]] && got=allow
  [[ "$got" == "$verdict" ]] \
    || fail "(z/$run shard $idx) suite ${suite}s vs break-even ${bk}s predicts $verdict but the helper says $got — affordability is not decided by suite elapsed alone, so the whole conclusion is wrong"
  echo "    $run shard $idx: break-even ${bk}s, actual suite ${suite}s => $got"
done
(( over_the_line == 7 )) \
  || fail "(z) 7 of the 12 retrievable shard-runs are recorded as running longer than their own affordability boundary; this run says $over_the_line"
pass "(z) the retry is affordable iff suite elapsed < ~${BREAKEVEN_LO}-${BREAKEVEN_HI}s, and $over_the_line of 12 observed shard-runs are already past that line — the binding constraint is per-shard load"

echo
echo "== #1833 the correction is one-way and fail-safe =="

# (q) The measured model's #1800 guarantee — it may only ever RELAX — must
#     survive the new arithmetic. A warm build so small that 1.10x it is under
#     the 120s rebuild reserve would otherwise INFLATE the requirement and could
#     refuse a retry the old rule allowed.
now="$(reconstruct_now 3000000)"
attempt_start="$(reconstruct_attempt_start "$now" 2000 50000)"
run_budget "$fixture_job_start" "$now" "$attempt_start" 2000
baseline_required="$(budget_field retry_required_ms)"
for tiny in 1 5 30 100 109; do
  run_budget "$fixture_job_start" "$now" "$attempt_start" 2000 "$tiny"
  (( $(budget_field retry_required_ms) <= baseline_required )) \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(q) a ${tiny}s warm build inflated the requirement to $(budget_field retry_required_ms) over $baseline_required"; }
done
pass "(q) a warm build too small to pay for its own reserve never RAISES the requirement"

# (q2) ... and the deduction still never exceeds the flat legacy worst case.
run_budget "$fixture_job_start" "$now" "$((now - 9000000))" 8000 500
[[ "$(budget_field retry_required_ms)" == "5400000" ]] \
  || { printf '%s\n' "$BUDGET_OUT"; fail "(q2) a pathologically slow attempt must still clamp to the flat 5400000ms"; }
pass "(q2) the warm path still clamps at the legacy flat worst case"

# (r) Unusable warm readings must leave #1800's output byte-for-byte alone.
#     `2500` is >= the 2000s suite (nonsensical), `0` is a no-op measurement,
#     and the rest are malformed. Fail-safe direction is toward DENYING.
run_budget "$fixture_job_start" "$now" "$attempt_start" 2000
unmeasured_out="$BUDGET_OUT"
for bad in "" "0" "007" "not-a-number" "-5" "2500" "2000" "99999999999999999999999999"; do
  run_budget "$fixture_job_start" "$now" "$attempt_start" 2000 "$bad"
  [[ "$BUDGET_OUT" == "$unmeasured_out" ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(r) warm reading '$bad' changed the decision; it must fall back to #1800 unchanged"; }
done
pass "(r) absent/zero/malformed/not-smaller-than-suite warm readings leave #1800's decision untouched"

echo
echo "== #1833 the warm-build reading comes from the suite's own summary =="

warm_ws="$(mktemp -d)"
trap 'rm -rf "$verdict_dir" "$warm_ws"' EXIT

# write_real_summary <dir> <suite-secs> <warm-status> <warm-secs> [failed-class]
#
# DERIVED FROM THE REAL WRITER, NOT SHAPED LIKE IT. Every summary this harness
# parses is produced by driving `finish_ci_journey_suite` in
# scripts/ci-journey-summary-functions.sh — the same function the suite calls in
# CI — with fixture state.
#
# This is deliberate, and it is the whole point. An earlier revision of this
# helper hand-wrote a byte-shaped COPY of the writer's output. That copy passed
# every test while being, structurally, a second maintained list: a wording or
# number-format change at ci-journey-summary-functions.sh's `Warm build (issue
# #1814): ...` line would have silently stopped
# scripts/ci-journey-warm-build-elapsed.sh from parsing anything, reverting the
# entire #1833 correction to #1800's model — with every test in this file still
# green. That is exactly the invisible-loss shape #1833 exists to end, so the
# fixture must not be able to disagree with the writer. Case (s4) below pins the
# coupling from the other side: it mutates the writer and requires the reader to
# fail loudly.
#
# The `unset SECONDS` is what makes SUITE_ELAPSED deterministic. `SECONDS` is a
# special bash variable that counts wall-clock seconds; unsetting it drops that
# attribute and leaves an ordinary variable, so `SUITE_ELAPSED=$((SECONDS -
# SUITE_START))` inside the real writer yields exactly the requested number and
# this harness reads no clock (#1839).
SUMMARY_FN_SRC="$SCRIPT_DIR/ci-journey-summary-functions.sh"
CORE_TERMINAL_FN_SRC="$SCRIPT_DIR/ci-journey-core-terminal-functions.sh"
[[ -f "$SUMMARY_FN_SRC" ]] || fail "real summary writer missing: $SUMMARY_FN_SRC"
[[ -f "$CORE_TERMINAL_FN_SRC" ]] || fail "core-terminal registry missing: $CORE_TERMINAL_FN_SRC"

# write_summary_with_writer <summary-fn-path> <dir> <suite> <status> <secs> [failed]
write_summary_with_writer() {
  local writer_src="$1" root="$2" elapsed="$3" warm_status="$4" warm_secs="$5" failed="${6-}"
  local driver="$root/.summary-driver.sh"
  mkdir -p "$root/artifacts/ci-journey"
  cat > "$driver" <<'DRIVEREOF'
#!/usr/bin/env bash
set -uo pipefail
writer_src="$1"; summary_out="$2"; elapsed="$3"; warm_status="$4"; warm_secs="$5"
failed="${6-}"
# shellcheck source=/dev/null
source "$(dirname "$writer_src")/ci-journey-core-terminal-functions.sh"
# shellcheck source=/dev/null
source "$writer_src"
# Pin the writer's clock: see the comment on write_real_summary.
unset SECONDS
SECONDS="$elapsed"
SUITE_START=0
STEP_TIMEOUT_HIT=0
PASSED_FIRST_TRY=(); RECOVERED_CLASSES=(); BUDGET_TIMEOUT_CLASSES=()
# Every array the writer's evidence sections read. The writer runs under
# `set -u`, so a section added later that reads a NEW array will abort here
# rather than silently emitting nothing — see the unbound-variable branch in
# write_summary_with_writer for what that failure says.
BUILD_PHASE_TIMEOUT_ATTEMPTS=()   # issue #1814
BUILD_PHASE_FAILURE_ATTEMPTS=()   # issue #1840
FAILED_CLASSES=()
[[ -n "$failed" ]] && FAILED_CLASSES=("$failed")
EFFECTIVE_JOURNEY_CLASSES=()
for _i in $(seq 1 49); do
  EFFECTIVE_JOURNEY_CLASSES+=("com.pocketshell.app.proof.Fixture${_i}E2eTest")
done
JOURNEY_CI_SHARD_INDEX=1; JOURNEY_CI_SHARD_TOTAL=3
JOURNEY_WARM_BUILD_STATUS="$warm_status"
JOURNEY_WARM_BUILD_ELAPSED="$warm_secs"
JOURNEY_STEP_BUDGET_SECS=4200
SUMMARY="$summary_out"
finish_ci_journey_suite
DRIVEREOF
  bash "$driver" "$writer_src" "$root/artifacts/ci-journey/summary.md" \
    "$elapsed" "$warm_status" "$warm_secs" "$failed" > "$root/.summary-driver.log" 2>&1
  local rc=$?
  # A writer section that reads an array this driver does not seed aborts under
  # `set -u`. Name that explicitly: it is a one-line fix (seed the array above),
  # and the alternative — quietly treating it as a writer failure — would hide a
  # perfectly ordinary sibling change behind an opaque exit code.
  if grep -q 'unbound variable' "$root/.summary-driver.log" 2>/dev/null; then
    cat "$root/.summary-driver.log"
    fail "the real summary writer needs a fixture variable this driver does not seed (see above). ci-journey-summary-functions.sh has gained an evidence section reading a new array — add it to the driver's array block; do NOT go back to hand-shaping the summary."
  fi
  # finish_ci_journey_suite exits with the suite's own verdict: 0 clean, 1 when a
  # class failed both attempts. Anything else means the writer itself blew up.
  if [[ -n "$failed" ]]; then
    (( rc == 1 )) || { cat "$root/.summary-driver.log"; fail "the real summary writer exited $rc for a failing fixture (expected 1)"; }
  else
    (( rc == 0 )) || { cat "$root/.summary-driver.log"; fail "the real summary writer exited $rc for a clean fixture (expected 0)"; }
  fi
  [[ -s "$root/artifacts/ci-journey/summary.md" ]] \
    || { cat "$root/.summary-driver.log"; fail "the real summary writer produced no summary"; }
}

write_real_summary() {
  write_summary_with_writer "$SUMMARY_FN_SRC" "$@"
}

root="$warm_ws/ok"; write_real_summary "$root" 3017 ok 373
parsed="$(bash "$WARM_ELAPSED" "$root/artifacts/ci-journey/summary.md")" \
  || fail "(s) could not read the warm build from a real-shaped summary"
[[ "$parsed" == "373" ]] || fail "(s) warm build parsed as '$parsed', expected 373"
[[ "$(bash "$SUITE_ELAPSED" "$root/artifacts/ci-journey/summary.md")" == "3017" ]] \
  || fail "(s) the #1800 suite-elapsed reader must be unaffected by the warm line"
pass "(s) the warm-build reader parses the suite's real summary line"

for status in failed skipped not_run; do
  root="$warm_ws/$status"; write_real_summary "$root" 3017 "$status" 373
  bash "$WARM_ELAPSED" "$root/artifacts/ci-journey/summary.md" >/dev/null 2>&1 \
    && fail "(s2) a '$status' warm build must NOT be deductible — the class loop paid that build, so there is nothing separately measured to subtract"
done
bash "$WARM_ELAPSED" "$warm_ws/does-not-exist.md" >/dev/null 2>&1 \
  && fail "(s3) a missing summary must fail so the caller keeps #1800's model"
printf 'no warm line here\n' > "$warm_ws/no-warm.md"
bash "$WARM_ELAPSED" "$warm_ws/no-warm.md" >/dev/null 2>&1 \
  && fail "(s3) a summary with no warm-build line must fail rather than guess"
pass "(s2/s3) a non-ok, missing, or unreadable warm build falls back to #1800 instead of guessing"

# (s4) THE COUPLING PIN — the other half of deriving the fixture from the real
#      writer. Deriving proves the reader can parse TODAY's writer. This proves
#      the pair is genuinely coupled: if the writer's line drifts, the reader
#      must FAIL LOUDLY rather than quietly return nothing and revert the whole
#      #1833 correction to #1800 with every test still green.
#
#      Each mutant is applied to a COPY of the real writer, and each is proven
#      LIVE before its verdict is trusted: the mutated writer must still produce
#      a summary, and that summary's warm-build line must actually differ from
#      the real one. A mutation that never took effect would read exactly like a
#      well-coupled pair, which is the mutation-testing failure mode this repo
#      has hit before (#1641).
mutant_dir="$warm_ws/writer-mutants"
mkdir -p "$mutant_dir"
cp "$CORE_TERMINAL_FN_SRC" "$mutant_dir/"
real_root="$warm_ws/coupling-real"
write_real_summary "$real_root" 2531 ok 489
real_warm_line="$(grep '^Warm build' "$real_root/artifacts/ci-journey/summary.md")"
[[ -n "$real_warm_line" ]] || fail "(s4) the real writer emitted no warm-build line at all"

# apply_writer_mutant <src> <out> <literal-old> <literal-new>
#
# Literal, single-occurrence substitution. Exits non-zero unless <literal-old>
# occurred EXACTLY once, so a mutant that silently matched nothing (or matched
# a comment as well as the code) can never be mistaken for a live one.
apply_writer_mutant() {
  MUTANT_OLD="$3" MUTANT_NEW="$4" python3 - "$1" "$2" <<'PYEOF'
import os, sys
src, out = sys.argv[1], sys.argv[2]
old, new = os.environ["MUTANT_OLD"], os.environ["MUTANT_NEW"]
text = open(src, encoding="utf-8").read()
if text.count(old) != 1:
    sys.exit("mutant anchor occurred %d times, expected exactly 1" % text.count(old))
open(out, "w", encoding="utf-8").write(text.replace(old, new))
PYEOF
}

# label | literal old | literal new — each is a plausible future edit to the
# writer's warm-build line, not an artificial corruption.
# shellcheck disable=SC2016 # These are the writer's literal `${...}` source text.
WRITER_MUTANTS=(
  'issue tag renamed|Warm build (issue #1814):|Warm build (#1814):'
  'status emphasis dropped|**${JOURNEY_WARM_BUILD_STATUS:-not_run}**|${JOURNEY_WARM_BUILD_STATUS:-not_run}'
  'unit spaced off the number|in ${JOURNEY_WARM_BUILD_ELAPSED:-0}s|in ${JOURNEY_WARM_BUILD_ELAPSED:-0} s'
  'seconds relabelled|in ${JOURNEY_WARM_BUILD_ELAPSED:-0}s|in ${JOURNEY_WARM_BUILD_ELAPSED:-0}sec'
  'phase renamed|Warm build (issue #1814)|Up-front build (issue #1814)'
)
for entry in "${WRITER_MUTANTS[@]}"; do
  label="${entry%%|*}"; rest="${entry#*|}"
  old_text="${rest%%|*}"; new_text="${rest#*|}"
  mutant="$mutant_dir/ci-journey-summary-functions.sh"
  apply_writer_mutant "$SUMMARY_FN_SRC" "$mutant" "$old_text" "$new_text" \
    || fail "(s4) the '$label' mutant's anchor is no longer present exactly once in ci-journey-summary-functions.sh. Either the writer's warm-build line has ALREADY drifted (in which case scripts/ci-journey-warm-build-elapsed.sh has stopped reading it and #1833 has silently reverted to #1800 — fix the reader), or this mutant needs re-anchoring. Do not delete the case."
  ! cmp -s "$mutant" "$SUMMARY_FN_SRC" \
    || fail "(s4) the '$label' mutant did not change the writer — the mutation is a no-op and its verdict would be meaningless"
  mroot="$warm_ws/mutant-$RANDOM$RANDOM"
  write_summary_with_writer "$mutant" "$mroot" 2531 ok 489
  mutant_summary="$mroot/artifacts/ci-journey/summary.md"
  # Liveness, proven from the OUTPUT rather than assumed from the edit: the
  # mutated writer must still write a real summary, and that summary must differ
  # from the real writer's.
  [[ "$(bash "$SUITE_ELAPSED" "$mutant_summary")" == "2531" ]] \
    || fail "(s4) the '$label' mutant broke the writer outright (its summary no longer carries the #1800 suite elapsed) — that models a build break, not the wording drift under test"
  ! cmp -s "$mutant_summary" "$real_root/artifacts/ci-journey/summary.md" \
    || fail "(s4) the '$label' mutant produced a summary IDENTICAL to the real writer's — the mutation is not live and its verdict would be meaningless"
  if bash "$WARM_ELAPSED" "$mutant_summary" >/dev/null 2>&1; then
    grep -n '1814' "$mutant_summary" || true
    fail "(s4) the reader still parsed a '$label' writer — the reader and writer are NOT coupled, so a wording change to ci-journey-summary-functions.sh would silently revert #1833 to #1800 with every test green"
  fi
done
pass "(s4) ${#WRITER_MUTANTS[@]} live mutations of the REAL writer's warm-build line each make the reader fail loudly — the pair cannot drift apart in silence"

echo
echo "== #1833 the workflow wires the reading through the REAL step bodies =="

# extract_step_body <step-name> <out-path> <expressions-json>
#
# Pull the step's actual `run: |` block out of tests.yml and substitute its
# `${{ }}` expressions from an explicit map. An UNMAPPED expression is a HARD
# FAILURE (#1827): silently blanking one would let this harness report green
# over a wiring it never actually exercised.
extract_step_body() {
  STEP_NAME="$1" CLASSIFY_EXPRESSIONS="$3" python3 - "$WORKFLOW" "$2" <<'PYEOF'
import json
import os
import re
import sys

workflow, out_path = sys.argv[1], sys.argv[2]
mapping = json.loads(os.environ["CLASSIFY_EXPRESSIONS"])
step_name = os.environ["STEP_NAME"]
lines = open(workflow, encoding="utf-8").read().splitlines()

step_re = re.compile(r"^(\s*)- name: " + re.escape(step_name))
start = indent = None
for i, line in enumerate(lines):
    m = step_re.match(line)
    if m:
        start, indent = i, len(m.group(1))
        break
if start is None:
    sys.exit("could not find step %r in %s" % (step_name, workflow))

run_idx = None
for j in range(start + 1, len(lines)):
    line = lines[j]
    if line.strip() and (len(line) - len(line.lstrip())) <= indent:
        break
    if re.match(r"^\s*run: \|\s*$", line):
        run_idx = j
        break
if run_idx is None:
    sys.exit("step %r has no `run: |` block" % step_name)

run_indent = len(lines[run_idx]) - len(lines[run_idx].lstrip())
body = []
for j in range(run_idx + 1, len(lines)):
    line = lines[j]
    if not line.strip():
        body.append("")
        continue
    if (len(line) - len(line.lstrip())) <= run_indent:
        break
    body.append(line)
if not [line for line in body if line.strip()]:
    sys.exit("step %r has an empty run block" % step_name)

pad = min(len(line) - len(line.lstrip()) for line in body if line.strip())
text = "\n".join(line[pad:] if line.strip() else "" for line in body)

unknown = []


def substitute(match):
    expr = match.group(1).strip()
    if expr not in mapping:
        unknown.append(expr)
        return ""
    return mapping[expr]


text = re.sub(r"\$\{\{(.*?)\}\}", substitute, text)
if unknown:
    sys.exit("unmapped workflow expression(s): %s" % ", ".join(sorted(set(unknown))))

with open(out_path, "w", encoding="utf-8") as handle:
    handle.write(text + "\n")
PYEOF
}

# run_journey_summary_step <workspace> — execute the REAL "Inspect first journey
# summary" step. Sets SUMMARY_SUITE_SECS / SUMMARY_WARM_SECS from its own
# $GITHUB_OUTPUT, so the wiring under test is the production one.
SUMMARY_SUITE_SECS=""; SUMMARY_WARM_SECS=""; SUMMARY_FAILURE=""
run_journey_summary_step() {
  local ws="$1" body="$ws/journey-summary-step.sh"
  mkdir -p "$ws/scripts"
  cp "$WARM_ELAPSED" "$SUITE_ELAPSED" "$ws/scripts/"
  extract_step_body "Inspect first journey summary" "$body" '{}' \
    || fail "could not extract the 'Inspect first journey summary' step body"
  : > "$ws/summary-output.txt"
  ( cd "$ws" && GITHUB_OUTPUT="$ws/summary-output.txt" \
      bash --noprofile --norc -eo pipefail "$body" ) > "$ws/summary-step.log" 2>&1 \
    || { cat "$ws/summary-step.log"; fail "the real journey-summary step exited non-zero"; }
  SUMMARY_SUITE_SECS="$(sed -n 's/^first_suite_elapsed_secs=//p' "$ws/summary-output.txt" | tail -n 1)"
  SUMMARY_WARM_SECS="$(sed -n 's/^first_warm_build_secs=//p' "$ws/summary-output.txt" | tail -n 1)"
  SUMMARY_FAILURE="$(sed -n 's/^first_failure=//p' "$ws/summary-output.txt" | tail -n 1)"
}

# (t) End to end through the production step: shard 2's real summary must yield
#     both measurements, and feeding them to the real helper must restore its
#     retry — the exact hop the shard job performs.
ws="$warm_ws/wire-shard2"
write_real_summary "$ws" 2531 ok 489
run_journey_summary_step "$ws"
[[ "$SUMMARY_SUITE_SECS" == "2531" ]] \
  || fail "(t) the real step read suite elapsed '$SUMMARY_SUITE_SECS', expected 2531"
[[ "$SUMMARY_WARM_SECS" == "489" ]] \
  || fail "(t) the real step read warm build '$SUMMARY_WARM_SECS', expected 489 — the #1833 output is not wired"
now="$(reconstruct_now 2976238)"
attempt_start="$(reconstruct_attempt_start "$now" "$SUMMARY_SUITE_SECS" 52256)"
run_budget "$fixture_job_start" "$now" "$attempt_start" "$SUMMARY_SUITE_SECS" "$SUMMARY_WARM_SECS"
[[ "$(budget_field retry_allowed)" == "true" ]] \
  || { printf '%s\n' "$BUDGET_OUT"; fail "(t) the production step's own outputs must restore shard 2's retry"; }
pass "(t) the real 'Inspect first journey summary' step emits first_warm_build_secs, and it restores shard 2's retry"

# (t2) The same step over a non-ok warm build hands the helper nothing, so the
#      decision stays exactly #1800's.
ws="$warm_ws/wire-warmfailed"
write_real_summary "$ws" 2531 failed 489
run_journey_summary_step "$ws"
[[ -z "$SUMMARY_WARM_SECS" ]] \
  || fail "(t2) a failed warm build must produce an EMPTY first_warm_build_secs, got '$SUMMARY_WARM_SECS'"
run_budget "$fixture_job_start" "$now" "$attempt_start" "$SUMMARY_SUITE_SECS" "$SUMMARY_WARM_SECS"
[[ "$(budget_field retry_allowed)" == "false" && "$(budget_field retry_cost_model)" == "measured_first_attempt" ]] \
  || { printf '%s\n' "$BUDGET_OUT"; fail "(t2) a failed warm build must leave #1800's refusal in place"; }
pass "(t2) a failed warm build yields no reading and the decision reverts to #1800"

# (t3) the retry-budget step passes BOTH measurements to the real helper.
grep -q 'steps.journey_summary.outputs.first_warm_build_secs' "$WORKFLOW" \
  || fail "(t3) the retry-budget step does not forward the measured warm build"
grep -q 'first_warm_build_secs=' "$WORKFLOW" \
  || fail "(t3) the journey-summary step does not export first_warm_build_secs"
pass "(t3) the workflow forwards the measured warm build into the budget decision"

echo
echo "== #1833 a shard that cannot afford its retry SAYS SO =="

# classify_expressions <journey-outcome> <first_timeout> <first_failure>
#                      <retry_allowed> <retry_reason> <shortfall>
classify_expressions() {
  cat <<JSONEOF
{
  "steps.journey.outcome": "$1",
  "steps.journey_retry.outcome": "skipped",
  "steps.journey.conclusion": "$1",
  "steps.journey_retry.conclusion": "skipped",
  "steps.journey_summary.outputs.first_timeout": "$2",
  "steps.journey_summary.outputs.first_failure": "$3",
  "steps.journey_retry_budget.outputs.retry_allowed": "$4",
  "steps.journey_retry_budget.outputs.retry_reason": "$5",
  "steps.journey_retry_budget.outputs.retry_remaining_ms": "2976238",
  "steps.journey_retry_budget.outputs.retry_required_ms": "3240868",
  "steps.journey_retry_budget.outputs.retry_cost_model": "measured_first_attempt",
  "steps.journey_retry_budget.outputs.retry_shortfall_ms": "$6",
  "steps.journey_retry_budget.outputs.retry_warm_build_deducted_ms": "0"
}
JSONEOF
}

CLASSIFY_OUT=""; CLASSIFY_RC=0; CLASSIFY_TOKEN=""; CLASSIFY_TOKEN_FILE=""
# run_classify_step <workspace> <shard-index> <expressions-json>
run_classify_step() {
  local ws="$1" idx="$2" expressions="$3" body="$ws/classify-step.sh"
  mkdir -p "$ws/scripts"
  cp "$WRITER" "$SCRIPT_DIR/ci-journey-build-phase-timeout.sh" \
     "$SCRIPT_DIR/ci-journey-shard-signature-verdict.sh" \
     "$SCRIPT_DIR/ci-journey-infra-signature.sh" "$SCRIPT_DIR/ci-journey-infra-signature.py" \
     "$ws/scripts/"
  extract_step_body "Classify emulator-journey result" "$body" "$expressions" \
    || fail "could not extract+substitute the classify step body. If the step gained a new \${{ }} expression, add it to classify_expressions() here — a silently blanked expression would make this harness lie."
  CLASSIFY_TOKEN_FILE="$ws/shard-verdict.txt"
  rm -f "$CLASSIFY_TOKEN_FILE"
  CLASSIFY_OUT="$(cd "$ws" && \
    SHARD_VERDICT_FILE="$CLASSIFY_TOKEN_FILE" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
    GITHUB_RUN_ID=30383504733 \
    GITHUB_RUN_ATTEMPT=1 \
    GITHUB_OUTPUT="$ws/gh-output.txt" \
    bash --noprofile --norc -eo pipefail "$body" 2>&1)"
  CLASSIFY_RC=$?
  CLASSIFY_TOKEN="$(head -n 1 "$CLASSIFY_TOKEN_FILE" 2>/dev/null || true)"
}

# (u) THE INVISIBILITY REPRODUCTION. Run 30383504733's shards 0 and 2 PASSED on
#     the first attempt while unable to afford a retry, and their tokens said
#     nothing but `CLEAN`. The verdict must stay CLEAN/exit 0 — severity is not
#     the point — but the evidence must now name the one-shot condition.
ws="$warm_ws/classify-clean-unaffordable"
mkdir -p "$ws/artifacts/ci-journey"
write_real_summary "$ws" 2531 ok 489
run_classify_step "$ws" 2 "$(classify_expressions success false false false insufficient_remaining_budget 264630)"
[[ "$CLASSIFY_TOKEN" == "CLEAN" && "$CLASSIFY_RC" -eq 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(u) a passing shard must stay CLEAN/exit0, got $CLASSIFY_TOKEN/exit$CLASSIFY_RC — surfacing must never change severity"; }
grep -q '^retry_affordable=false$' "$CLASSIFY_TOKEN_FILE" \
  || { cat "$CLASSIFY_TOKEN_FILE"; fail "(u) a CLEAN one-shot shard's token does not record retry_affordable=false — this is exactly the invisibility #1833 reports"; }
grep -q '^retry_shortfall_ms=264630$' "$CLASSIFY_TOKEN_FILE" \
  || { cat "$CLASSIFY_TOKEN_FILE"; fail "(u) the token does not carry the measured shortfall"; }
grep -q '^retry_denied_reason=insufficient_remaining_budget$' "$CLASSIFY_TOKEN_FILE" \
  || { cat "$CLASSIFY_TOKEN_FILE"; fail "(u) the token does not carry why the retry was denied"; }
grep -q 'ran ONE-SHOT' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(u) the classifier emitted no one-shot annotation"; }
pass "(u) a CLEAN shard that could not afford its retry now says so in its own verdict token"

# (u2) The control: an AFFORDABLE shard must not cry wolf.
ws="$warm_ws/classify-clean-affordable"
mkdir -p "$ws/artifacts/ci-journey"
write_real_summary "$ws" 2531 ok 489
run_classify_step "$ws" 2 "$(classify_expressions success false false true sufficient_remaining_budget 0)"
[[ "$CLASSIFY_TOKEN" == "CLEAN" && "$CLASSIFY_RC" -eq 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(u2) an affordable CLEAN shard must stay CLEAN/exit0"; }
grep -q '^retry_affordable=true$' "$CLASSIFY_TOKEN_FILE" \
  || { cat "$CLASSIFY_TOKEN_FILE"; fail "(u2) an affordable shard must record retry_affordable=true"; }
grep -q 'ran ONE-SHOT' <<<"$CLASSIFY_OUT" \
  && { printf '%s\n' "$CLASSIFY_OUT"; fail "(u2) an affordable shard must NOT emit the one-shot annotation"; }
pass "(u2) an affordable shard records retry_affordable=true and emits no one-shot annotation"

# (u3) Shard 1's real shape: a genuine first-attempt failure decided with no
#      retry. It must stay RED/exit1 (D31: labelling never softens), and it must
#      ALSO carry the one-shot stamp so a reader knows the RED came from ONE run.
ws="$warm_ws/classify-red-unaffordable"
mkdir -p "$ws/artifacts/ci-journey"
write_real_summary "$ws" 3017 ok 373 "com.pocketshell.app.proof.PreExistingMultiWindowSeedE2eTest"
run_classify_step "$ws" 1 "$(classify_expressions failure false true false insufficient_remaining_budget 1356524)"
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(u3) a genuine first-attempt failure must stay RED/exit non-zero, got $CLASSIFY_TOKEN/exit$CLASSIFY_RC"; }
grep -q '^retry_affordable=false$' "$CLASSIFY_TOKEN_FILE" \
  || { cat "$CLASSIFY_TOKEN_FILE"; fail "(u3) shard 1's RED token must record that it was decided with no cold-boot retry"; }
grep -q '^retry_shortfall_ms=1356524$' "$CLASSIFY_TOKEN_FILE" \
  || { cat "$CLASSIFY_TOKEN_FILE"; fail "(u3) shard 1's RED token must carry its 1356524ms shortfall"; }
pass "(u3) a RED decided without a retry stays RED and records that it was one-shot"

# (u4) The pre-seeded token (written at job start, before any budget decision)
#      must read `unknown` — honest, and distinct from an actual denial.
ws="$warm_ws/preseed"; mkdir -p "$ws"
( cd "$ws" && SHARD_VERDICT_FILE="$ws/seed.txt" POCKETSHELL_JOURNEY_CI_SHARD_INDEX=0 \
    GITHUB_RUN_ID=30383504733 GITHUB_RUN_ATTEMPT=1 GITHUB_OUTPUT="" \
    bash "$WRITER" INFRA preseeded_before_journey >/dev/null ) \
  || fail "(u4) the writer refused the pre-seed"
grep -q '^retry_affordable=unknown$' "$ws/seed.txt" \
  || { cat "$ws/seed.txt"; fail "(u4) the pre-seeded token must read unknown, not a false affordability"; }
pass "(u4) the pre-seeded token records retry_affordable=unknown (it has not asked yet)"

echo
echo "== #1833 the aggregate carries the one-shot condition, without changing it =="

# (v) All three of run 30383504733's shards were one-shot; the aggregate must
#     name them. Two CLEAN + one RED still aggregates to RED/exit1 (#1458).
agg_dir="$(mktemp -d)"
trap 'rm -rf "$verdict_dir" "$warm_ws" "$agg_dir"' EXIT
seed_agg_token() {
  local idx="$1" token="$2" affordable="$3" shortfall="$4"
  local sub="$agg_dir/emulator-journey-verdict-shard-$idx"
  mkdir -p "$sub"
  SHARD_VERDICT_FILE="$sub/shard-verdict.txt" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
    GITHUB_RUN_ID=30383504733 GITHUB_RUN_ATTEMPT=1 GITHUB_OUTPUT="" \
    SHARD_RETRY_AFFORDABLE="$affordable" \
    SHARD_RETRY_DENIED_REASON=insufficient_remaining_budget \
    SHARD_RETRY_SHORTFALL_MS="$shortfall" \
    bash "$WRITER" "$token" fixture >/dev/null \
    || fail "(v) writer refused a $token token for shard $idx"
}
seed_agg_token 0 CLEAN false 224393
seed_agg_token 1 RED   false 1356524
seed_agg_token 2 CLEAN false 264630
agg_rc=0
agg_out="$(EXPECTED_SHARDS=3 GITHUB_RUN_ATTEMPT=1 GITHUB_STEP_SUMMARY="$agg_dir/step-summary.md" \
  bash "$AGG" "$agg_dir" 2>&1)" || agg_rc=$?
[[ "$agg_rc" -eq 1 ]] \
  || { printf '%s\n' "$agg_out"; fail "(v) a RED shard must still turn the aggregate red (exit 1), got $agg_rc"; }
grep -q '^AGGREGATE_VERDICT=RED$' <<<"$agg_out" \
  || { printf '%s\n' "$agg_out"; fail "(v) expected AGGREGATE_VERDICT=RED"; }
grep -q 'ran ONE-SHOT' <<<"$agg_out" \
  || { printf '%s\n' "$agg_out"; fail "(v) the aggregate does not name the one-shot shards — a gate at half resilience still looks identical to a healthy one"; }
for idx in 0 1 2; do
  grep -q "shard ${idx} (" <<<"$agg_out" \
    || { printf '%s\n' "$agg_out"; fail "(v) shard $idx is missing from the one-shot report"; }
done
grep -q 'ONE-SHOT' "$agg_dir/step-summary.md" \
  || { cat "$agg_dir/step-summary.md"; fail "(v) the step summary does not carry the one-shot condition"; }
pass "(v) the aggregate names every one-shot shard and still resolves RED/exit1"

# (v2) An all-affordable run must be silent about it — the signal only fires on
#      the condition it reports, or it becomes noise nobody reads.
rm -rf "$agg_dir"; mkdir -p "$agg_dir"
seed_agg_token 0 CLEAN true 0
seed_agg_token 1 CLEAN true 0
seed_agg_token 2 CLEAN true 0
agg_rc=0
agg_out="$(EXPECTED_SHARDS=3 GITHUB_RUN_ATTEMPT=1 GITHUB_STEP_SUMMARY="" \
  bash "$AGG" "$agg_dir" 2>&1)" || agg_rc=$?
[[ "$agg_rc" -eq 0 ]] && grep -q '^AGGREGATE_VERDICT=CLEAN$' <<<"$agg_out" \
  || { printf '%s\n' "$agg_out"; fail "(v2) three affordable CLEAN shards must aggregate CLEAN/exit0"; }
grep -q 'ONE-SHOT' <<<"$agg_out" \
  && { printf '%s\n' "$agg_out"; fail "(v2) a fully-resilient run must not report a one-shot condition"; }
pass "(v2) an all-affordable run stays CLEAN and reports no one-shot condition"

# (v3) A token written before the stamp existed (no retry_affordable line) must
#      neither be read as one-shot nor break the rollup.
rm -rf "$agg_dir"; mkdir -p "$agg_dir/emulator-journey-verdict-shard-0"
printf 'CLEAN\nshard=0\nrun_id=30383504733\nrun_attempt=1\nverdict_reason=passed_first_attempt\n' \
  > "$agg_dir/emulator-journey-verdict-shard-0/shard-verdict.txt"
agg_rc=0
agg_out="$(EXPECTED_SHARDS=1 GITHUB_RUN_ATTEMPT=1 GITHUB_STEP_SUMMARY="" \
  bash "$AGG" "$agg_dir" 2>&1)" || agg_rc=$?
[[ "$agg_rc" -eq 0 ]] && grep -q '^AGGREGATE_VERDICT=CLEAN$' <<<"$agg_out" \
  || { printf '%s\n' "$agg_out"; fail "(v3) an unstamped legacy token must still aggregate CLEAN/exit0"; }
grep -q 'ONE-SHOT' <<<"$agg_out" \
  && { printf '%s\n' "$agg_out"; fail "(v3) an unstamped token must not be reported as one-shot"; }
pass "(v3) a token without the #1833 stamp is neither one-shot nor a parse failure"

echo
echo "== #1833 no budget or job cap was raised (G6) =="

grep -q '^readonly JOURNEY_JOB_CAP_MS=5700000$' "$HELPER" \
  || fail "(w) the 95-minute job cap changed"
grep -q '^readonly JOURNEY_RETRY_REQUIRED_MS=5400000$' "$HELPER" \
  || fail "(w) the legacy flat retry reserve changed"
grep -q '^readonly JOURNEY_RETRY_TEARDOWN_MS=300000$' "$HELPER" \
  || fail "(w) the teardown reserve changed"
grep -q '^readonly JOURNEY_RETRY_SUITE_HEADROOM_NUMERATOR=110$' "$HELPER" \
  || fail "(w) #1800's 10% suite headroom changed"
grep -q '^readonly JOURNEY_RETRY_BOOT_HEADROOM_NUMERATOR=3$' "$HELPER" \
  || fail "(w) #1800's 3x boot headroom changed"
grep -q 'timeout-minutes: 95' "$WORKFLOW" \
  || fail "(w) the emulator job's 95-minute cap changed"
grep -q 'JOURNEY_STEP_BUDGET_SECS:-4200}' "$SCRIPT_DIR/ci-journey-suite.sh" \
  || fail "(w) the #835 4200s suite budget changed"
pass "(w) job cap, suite budget, flat reserve, teardown, and both #1800 headrooms are untouched"

echo
echo "== #1850 per-shard load: the shipped matrix must leave >420s retry headroom =="

# THE LOAD RATCHET. Cases (x)/(y)/(z) above pin the 3-shard world that denied
# the cold-boot retry; they stay as historical evidence. This case is current
# reality: redistribute a measured per-class fixture through the SHIPPING
# selector at the matrix total scripts/ci-journey-shard-count.sh reads from
# $WORKFLOW, drive the UNCHANGED production helper, and require every leg's
# retry margin to exceed one twice-failing journey class (the issue's ~420s
# AC2 bar). The 3-way split is the denial class — it must stay under that bar
# so the fixture cannot pass vacuously if the matrix is still overloaded.
#
# Conservative fixed-cost inputs (worst observed in the #1850/#2060
# derivation). They are fixture inputs to the production formula, NOT new
# budget constants — case (w) above still pins those.
LOAD_FIXTURE="$SCRIPT_DIR/fixtures/ci-journey-run-31961310072-class-seconds.tsv"
LOAD_SELECTION_HELPER="$SCRIPT_DIR/ci-journey-class-selection-functions.sh"
LOAD_CORE_HELPER="$SCRIPT_DIR/ci-journey-core-terminal-functions.sh"
LOAD_SHARD_COUNT="$SCRIPT_DIR/ci-journey-shard-count.sh"
LOAD_SUITE="$SCRIPT_DIR/ci-journey-suite.sh"
TWICE_FAILING_CLASS_MS=420000
LOAD_PRE_SUITE_MS=259046
LOAD_BOOT_MS=59699
LOAD_WARM_SECS=478
[[ -f "$LOAD_FIXTURE" ]] || fail "(aa) missing measured class-seconds fixture: $LOAD_FIXTURE"
[[ -f "$LOAD_SELECTION_HELPER" ]] || fail "(aa) missing class-selection helper: $LOAD_SELECTION_HELPER"
[[ -f "$LOAD_CORE_HELPER" ]] || fail "(aa) missing core-terminal helper: $LOAD_CORE_HELPER"
[[ -f "$LOAD_SHARD_COUNT" ]] || fail "(aa) missing shard-count helper: $LOAD_SHARD_COUNT"
[[ -f "$LOAD_SUITE" ]] || fail "(aa) missing journey suite: $LOAD_SUITE"

declare -A LOAD_COST_SECS=()
load_fixture_rows=0
while IFS=$'\t' read -r load_fqcn load_secs; do
  [[ -z "$load_fqcn" || "$load_fqcn" == \#* ]] && continue
  [[ "$load_secs" =~ ^[1-9][0-9]*$ ]] \
    || fail "(aa) fixture row '$load_fqcn' has non-canonical seconds '$load_secs'"
  [[ -z "${LOAD_COST_SECS[$load_fqcn]+x}" ]] \
    || fail "(aa) fixture lists $load_fqcn twice"
  LOAD_COST_SECS["$load_fqcn"]="$load_secs"
  load_fixture_rows=$((load_fixture_rows + 1))
done < "$LOAD_FIXTURE"
(( load_fixture_rows >= 150 )) \
  || fail "(aa) fixture has only $load_fixture_rows rows — a truncated TSV cannot constrain per-shard load"

mapfile -t LOAD_SORTED_COSTS < <(printf '%s\n' "${LOAD_COST_SECS[@]}" | sort -n)
LOAD_MEDIAN="${LOAD_SORTED_COSTS[$(( ${#LOAD_SORTED_COSTS[@]} / 2 ))]}"
[[ "$LOAD_MEDIAN" =~ ^[1-9][0-9]*$ ]] \
  || fail "(aa) could not derive a median from the fixture"

# shellcheck source=scripts/ci-journey-class-selection-functions.sh
source "$LOAD_SELECTION_HELPER"
# shellcheck source=scripts/ci-journey-core-terminal-functions.sh
source "$LOAD_CORE_HELPER"
declare -F select_effective_journey_classes >/dev/null \
  || fail "(aa) selection helper does not define select_effective_journey_classes"
declare -F select_effective_core_terminal_proofs >/dev/null \
  || fail "(aa) core-terminal helper does not define select_effective_core_terminal_proofs"

mapfile -t LOAD_JOURNEY_CLASSES < <(
  awk '
    /^JOURNEY_CLASSES=\(/ { f = 1; next }
    /^\)/                 { f = 0 }
    f && match($0, /"[^"]+"/) {
      s = substr($0, RSTART + 1, RLENGTH - 2)
      gsub(/\$FQCN_PREFIX/, "com.pocketshell.app.proof", s)
      print s
    }
  ' "$LOAD_SUITE"
)
(( ${#LOAD_JOURNEY_CLASSES[@]} >= 80 )) \
  || fail "(aa) parsed only ${#LOAD_JOURNEY_CLASSES[@]} journey classes from the real suite"
# The shipping selector partitions JOURNEY_CLASSES, not our local copy.
JOURNEY_CLASSES=("${LOAD_JOURNEY_CLASSES[@]}")

LOAD_CORE_SELECTORS=()
for load_ct_entry in "${CORE_TERMINAL_PROOFS[@]}"; do
  IFS='|' read -r _load_status_var load_class_var _load_label <<<"$load_ct_entry"
  LOAD_CORE_SELECTORS+=("${!load_class_var}")
done
(( ${#LOAD_CORE_SELECTORS[@]} >= 9 )) \
  || fail "(aa) parsed only ${#LOAD_CORE_SELECTORS[@]} core-terminal proofs from the registry"

load_unknowns=()
for load_fqcn in "${LOAD_JOURNEY_CLASSES[@]}" "${LOAD_CORE_SELECTORS[@]}"; do
  [[ -n "${LOAD_COST_SECS[$load_fqcn]+x}" ]] && continue
  load_unknowns+=("$load_fqcn")
done
if (( ${#load_unknowns[@]} > 0 )); then
  echo "  (aa) ${#load_unknowns[@]} class(es) not in the fixture — costing each at the fixture median ${LOAD_MEDIAN}s:"
  printf '    %s\n' "${load_unknowns[@]}"
fi
(( ${#load_unknowns[@]} <= 20 )) \
  || fail "(aa) ${#load_unknowns[@]} classes have no fixture cost — the TSV is too stale to ratchet load; re-derive it from a real run"

load_cost_of() {
  local fqcn="$1"
  printf '%s' "${LOAD_COST_SECS[$fqcn]:-$LOAD_MEDIAN}"
}

# instrumentation_secs <total> <idx> — journey + core-terminal seconds the
# SHIPPING selectors actually assign to this leg. stdout of the selectors is
# discarded; the property is which FQCNs they populate.
load_instrumentation_secs() {
  local total="$1" idx="$2" secs=0 fqcn entry status_var class_var _label
  EFFECTIVE_JOURNEY_CLASSES=()
  POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$total" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
    select_effective_journey_classes >/dev/null
  if (( ${#EFFECTIVE_JOURNEY_CLASSES[@]} > 0 )); then
    for fqcn in "${EFFECTIVE_JOURNEY_CLASSES[@]}"; do
      secs=$((secs + $(load_cost_of "$fqcn")))
    done
  fi
  EFFECTIVE_CORE_TERMINAL_PROOFS=()
  POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$total" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
    JOURNEY_CI_SHARD_TOTAL="$total" \
    JOURNEY_CI_SHARD_INDEX="$idx" \
    select_effective_core_terminal_proofs >/dev/null
  if (( ${#EFFECTIVE_CORE_TERMINAL_PROOFS[@]} > 0 )); then
    for entry in "${EFFECTIVE_CORE_TERMINAL_PROOFS[@]}"; do
      IFS='|' read -r status_var class_var _label <<<"$entry"
      secs=$((secs + $(load_cost_of "${!class_var}")))
    done
  fi
  printf '%s' "$secs"
}

# Drive the production helper with this leg's modelled suite. Margin may be
# negative — that is the denial-class signal.
load_model_margin_ms() {
  local total="$1" idx="$2" instr suite_secs now_ms attempt_start remaining required
  instr="$(load_instrumentation_secs "$total" "$idx")"
  suite_secs=$((instr + LOAD_WARM_SECS))
  now_ms=$((fixture_job_start + LOAD_PRE_SUITE_MS + suite_secs * 1000))
  attempt_start=$((now_ms - suite_secs * 1000 - LOAD_BOOT_MS))
  run_budget "$fixture_job_start" "$now_ms" "$attempt_start" "$suite_secs" "$LOAD_WARM_SECS"
  remaining="$(budget_field retry_remaining_ms)"
  required="$(budget_field retry_required_ms)"
  [[ "$remaining" =~ ^[0-9]+$ && "$required" =~ ^[0-9]+$ ]] \
    || { printf '%s\n' "$BUDGET_OUT"; fail "(aa) production helper did not emit remaining/required for total=$total shard=$idx"; }
  printf '%s' "$((remaining - required))"
}

load_model_min_margin_ms() {
  local total="$1" idx margin min=""
  for (( idx = 0; idx < total; idx++ )); do
    margin="$(load_model_margin_ms "$total" "$idx")"
    if [[ -z "$min" ]] || (( margin < min )); then
      min="$margin"
    fi
  done
  printf '%s' "$min"
}

load_report_legs() {
  local label="$1" total="$2" idx instr suite_secs margin
  echo "  $label (total=$total):"
  for (( idx = 0; idx < total; idx++ )); do
    instr="$(load_instrumentation_secs "$total" "$idx")"
    suite_secs=$((instr + LOAD_WARM_SECS))
    margin="$(load_model_margin_ms "$total" "$idx")"
    echo "    shard $idx: instrumentation ${instr}s suite ${suite_secs}s margin ${margin}ms"
  done
}

# (aa1) THE DENIAL CLASS. A 3-way split of the current list, using the same
# selector and formula as production. This is the issue's measured failure:
# 3 shards no longer fit. Kept as a control so an all-median / all-cheap
# fixture cannot report "the matrix is fine" while 3-way load still denies.
load_report_legs "denial-class 3-way split" 3
LOAD_MIN_THREE="$(load_model_min_margin_ms 3)"
(( LOAD_MIN_THREE <= TWICE_FAILING_CLASS_MS )) \
  || fail "(aa1) the 3-way split now has min margin ${LOAD_MIN_THREE}ms > ${TWICE_FAILING_CLASS_MS}ms — the denial-class control is not live; the fixture no longer reproduces #1850"
pass "(aa1) a 3-way split of the current list still misses the ${TWICE_FAILING_CLASS_MS}ms bar (min margin ${LOAD_MIN_THREE}ms) — 3 shards no longer fit"

# (aa2) SHIPPED MATRIX. Total is read from $WORKFLOW, never hardcoded, so
# reverting the matrix to 3 legs reddens this assertion rather than the
# denial-class control.
LOAD_SHIPPED="$("$LOAD_SHARD_COUNT" "$WORKFLOW")" \
  || fail "(aa2) ci-journey-shard-count.sh could not parse $WORKFLOW"
[[ "$LOAD_SHIPPED" =~ ^[0-9]+$ && "$LOAD_SHIPPED" -ge 2 ]] \
  || fail "(aa2) implausible shipped shard total '$LOAD_SHIPPED' from $WORKFLOW"
load_report_legs "shipped matrix" "$LOAD_SHIPPED"
LOAD_SHIPPED_MIN=""
for (( load_idx = 0; load_idx < LOAD_SHIPPED; load_idx++ )); do
  load_margin="$(load_model_margin_ms "$LOAD_SHIPPED" "$load_idx")"
  (( load_margin > TWICE_FAILING_CLASS_MS )) \
    || fail "(aa2) shipped total=$LOAD_SHIPPED shard $load_idx margin ${load_margin}ms is not > ${TWICE_FAILING_CLASS_MS}ms — this matrix still overloads a shard (issue #1850 AC2)"
  if [[ -z "$LOAD_SHIPPED_MIN" ]] || (( load_margin < LOAD_SHIPPED_MIN )); then
    LOAD_SHIPPED_MIN="$load_margin"
  fi
done
pass "(aa2) shipped $LOAD_SHIPPED-shard matrix: every leg's retry margin > ${TWICE_FAILING_CLASS_MS}ms (tightest ${LOAD_SHIPPED_MIN}ms)"

# (aa3) G6: the 3-way denial is a property of the MEASURED costs, not of
# "any 3-way split". Flat 1s costs at total=3 clear the bar, so (aa1) would
# go quiet if the fixture stopped carrying the heavy tail.
load_saved_costs=()
for load_fqcn in "${!LOAD_COST_SECS[@]}"; do
  load_saved_costs+=("$load_fqcn" "${LOAD_COST_SECS[$load_fqcn]}")
  LOAD_COST_SECS["$load_fqcn"]=1
done
LOAD_MEDIAN_SAVED="$LOAD_MEDIAN"
LOAD_MEDIAN=1
LOAD_FLAT_THREE="$(load_model_min_margin_ms 3)"
LOAD_MEDIAN="$LOAD_MEDIAN_SAVED"
for (( load_i = 0; load_i < ${#load_saved_costs[@]}; load_i += 2 )); do
  LOAD_COST_SECS["${load_saved_costs[$load_i]}"]="${load_saved_costs[$((load_i + 1))]}"
done
(( LOAD_FLAT_THREE > TWICE_FAILING_CLASS_MS )) \
  || fail "(aa3) G6 is not live: a 3-way split with every class costing 1s still misses the bar (min ${LOAD_FLAT_THREE}ms) — (aa1) would then be a tautology of shard count, not of load"
pass "(aa3) G6: the same 3-way split with 1s classes clears the bar (min ${LOAD_FLAT_THREE}ms), so (aa1) is carried by the measured costs"

# (aa4) G6: a selector that dumps every class onto shard 0 must redden (aa2).
# If the assertion only counted classes or averaged load, this mutant would
# still pass while one leg was overloaded.
load_hash_saved="$(declare -f journey_class_shard_hash)"
journey_class_shard_hash() { printf '0'; }
LOAD_DEGEN="$(load_model_min_margin_ms "$LOAD_SHIPPED")"
eval "$load_hash_saved"
(( LOAD_DEGEN <= TWICE_FAILING_CLASS_MS )) \
  || fail "(aa4) G6 is not live: putting every class on shard 0 still cleared AC2 (min ${LOAD_DEGEN}ms) — the shipped assertion is not measuring per-shard load"
pass "(aa4) G6: a degenerate all-on-shard-0 partition reddens AC2 (min ${LOAD_DEGEN}ms)"

# (aa5) G6: collapsing THIS workflow's emulator-journey matrix to the 3-way
# denial class makes shard-count report 3, which is the same total (aa2)
# would then use. If (aa2) hardcoded a passing total, this mutant would stay
# green while the matrix was the overloaded 3-way split.
load_mutant="$warm_ws/denial-class-workflow.yml"
cp "$WORKFLOW" "$load_mutant"
python3 - "$load_mutant" <<'PY'
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text()
job = re.search(r"(?ms)^  emulator-journey:$.*?(?=^  [A-Za-z0-9_-]+:|\Z)", text)
if job is None:
    sys.exit("no emulator-journey job")
block = job.group(0)
new_block, n = re.subn(
    r"^(\s+shard: )\[[^\]]+\]\s*$",
    r"\1[0, 1, 2]",
    block,
    count=1,
    flags=re.M,
)
if n != 1:
    sys.exit("emulator-journey shard matrix not unique (replacements=%d)" % n)
if new_block == block:
    sys.exit("matrix mutation was a no-op")
path.write_text(text[: job.start()] + new_block + text[job.end() :])
PY
LOAD_MUTANT_TOTAL="$("$LOAD_SHARD_COUNT" "$load_mutant")" \
  || fail "(aa5) shard-count could not parse the collapsed workflow"
[[ "$LOAD_MUTANT_TOTAL" == "3" ]] \
  || fail "(aa5) collapsed matrix parsed as $LOAD_MUTANT_TOTAL, expected 3"
LOAD_MUTANT_MIN="$(load_model_min_margin_ms "$LOAD_MUTANT_TOTAL")"
(( LOAD_MUTANT_MIN <= TWICE_FAILING_CLASS_MS )) \
  || fail "(aa5) G6 is not live: a tests.yml whose emulator-journey matrix is [0, 1, 2] still clears AC2 (min ${LOAD_MUTANT_MIN}ms) — (aa2) would not redden if the matrix still over-loaded a shard"
pass "(aa5) G6: collapsing the workflow matrix to [0, 1, 2] makes shard-count return 3 and AC2 fail (min ${LOAD_MUTANT_MIN}ms)"

echo
echo "ALL TESTS PASSED: scripts/test-ci-journey-retry-budget.sh"
