#!/usr/bin/env bash
# Issue #1800: deterministic, JVM-free, emulator-free fixture test for the
# emulator-journey failure-SIGNATURE classifier and the two aggregate outcomes
# it can produce.
#
# The load-bearing property: a shard whose ONLY failure is the CI swiftshader
# AVD's inability to raise a real system input-method window must aggregate to
# RE-RUN (neutral green), while a shard carrying ANY genuine journey assertion
# failure — including a containment/anchor failure in the SAME class, in the
# same run — must still aggregate to RED. Both outcomes are DEMONSTRATED here by
# driving the real classifier and then the real aggregate reducer, not argued.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SIGNATURE="$SCRIPT_DIR/ci-journey-infra-signature.sh"
SHARD_VERDICT="$SCRIPT_DIR/ci-journey-shard-signature-verdict.sh"
ELAPSED="$SCRIPT_DIR/ci-journey-suite-elapsed.sh"
RETRY_BUDGET="$SCRIPT_DIR/ci-journey-retry-budget.sh"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"
WORKFLOW="${CI_JOURNEY_INFRA_SIGNATURE_WORKFLOW:-$REPO_ROOT/.github/workflows/tests.yml}"
ANDROID_TEST="$REPO_ROOT/app/src/androidTest/java/com/pocketshell/app/composer/PromptComposerSaturatedImeAnchorE2eTest.kt"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$SIGNATURE" "$SHARD_VERDICT" "$ELAPSED" "$RETRY_BUDGET" "$AGG" "$WORKFLOW"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

REAL_IME_MESSAGE='java.lang.AssertionError: The real system input-method window never became visible.'
CONTAINMENT_MESSAGE="java.lang.AssertionError: Node 'prompt-composer-send-enter' must stay above the same-root keyboard boundary. node=Rect.fromLTRB(0.0, 1500.0, 1080.0, 1654.0) keyboardTopPx=1626.0"
SATURATED_CLASS="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"

# ---------------------------------------------------------------------------
# Fixture builders: a realistic suite summary + realistic connected-test JUnit
# XML under the exact artifact layout the suite writes
# (artifacts/ci-journey/class-attempts/<module>/<key>/attempt-N/...).
# ---------------------------------------------------------------------------

# write_summary <root> <elapsed-secs> <failed-class> [<failed-class> ...]
write_summary() {
  local root="$1" elapsed="$2"; shift 2
  mkdir -p "$root/ci-journey"
  {
    echo "# Per-push CI journey suite — summary"
    echo
    echo "| Selection | Args | Exit | Elapsed | Result |"
    echo "| --- | --- | --- | --- | --- |"
    echo "| 48 load-bearing journey classes (shard 0/3; per-class retry-once) | \`pocketshellCi=true\` | 1 | ${elapsed}s | **FAIL** |"
    echo
    echo "Classes exercised:"
    echo "- \`com.pocketshell.app.proof.SomeOtherJourneyE2eTest\`"
    echo "- \`$SATURATED_CLASS\`"
    if (( $# > 0 )); then
      echo
      echo "Failed BOTH attempts (\`JOURNEY_FAILED\` — job red):"
      local c
      for c in "$@"; do
        echo "- \`$c\`"
      done
    fi
  } > "$root/ci-journey/summary.md"
}

# write_case_xml <root> <class> <attempt> <method>:<outcome> ...
#   outcome: pass | realime | containment | error
write_case_xml() {
  local root="$1" class="$2" attempt="$3"; shift 3
  local key="${class##*.}"
  local dir="$root/ci-journey/class-attempts/app/$key/attempt-$attempt/android-test-outputs/androidTest-results/connected"
  mkdir -p "$dir"
  local file="$dir/TEST-emulator-5554-15_$key-$attempt.xml"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuite name=\"$class\" tests=\"$#\">"
    local spec method outcome
    for spec in "$@"; do
      method="${spec%%:*}"
      outcome="${spec##*:}"
      echo "  <testcase name=\"$method\" classname=\"$class[emulator-5554 - 15]\">"
      case "$outcome" in
        pass) ;;
        realime)
          echo "    <failure message=\"$REAL_IME_MESSAGE\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:814)</failure>"
          ;;
        containment)
          echo "    <failure message=\"$CONTAINMENT_MESSAGE\">at org.junit.Assert.fail(Assert.java:89)</failure>"
          ;;
        error)
          echo "    <error message=\"java.lang.IllegalStateException: harness exploded\">stack</error>"
          ;;
        *) fail "unknown fixture outcome: $outcome" ;;
      esac
      echo '  </testcase>'
    done
    echo '</testsuite>'
  } > "$file"
}

# run_signature <summary> <root> [<root>...] -> SIG_CLASS / SIG_OUT
run_signature() {
  local summary="$1"; shift
  SIG_OUT="$(bash "$SIGNATURE" "$summary" "$@" 2>/dev/null)"
  SIG_RC=$?
  SIG_CLASS="$(sed -n 's/^journey_failure_classification=//p' <<<"$SIG_OUT" | tail -n 1)"
}

# shard_verdict_for <artifacts-dir> — drive the REAL decision script the
# workflow's classify step calls, over the REAL canonical artifact layout, and
# map its verdict to the shard token that step writes. No replicated logic: the
# script is the same one tests.yml invokes (pinned by grep further down).
SHARD_VERDICT_OUT=""
SHARD_TOKEN=""
shard_verdict_for() {
  local artifacts="$1"
  SHARD_VERDICT_OUT="$(bash "$SHARD_VERDICT" "$artifacts" 2>/dev/null)"
  if [[ "$(sed -n 's/^shard_signature_verdict=//p' <<<"$SHARD_VERDICT_OUT" | tail -n 1)" == "INFRA" ]]; then
    SHARD_TOKEN="INFRA"
  else
    SHARD_TOKEN="RED"
  fi
}

write_shard_token() {
  local dir="$1" idx="$2" token="$3"
  mkdir -p "$dir/emulator-journey-verdict-shard-$idx"
  printf '%s\n' "$token" > "$dir/emulator-journey-verdict-shard-$idx/shard-verdict.txt"
}

run_agg() {
  local dir="$1"
  AGG_OUT="$(EXPECTED_SHARDS=3 GITHUB_STEP_SUMMARY="" bash "$AGG" "$dir" 2>&1)"
  AGG_RC=$?
  AGG_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$AGG_OUT" | tail -n 1)"
}

echo "== #1800 signature classifier =="

# (a) The captured CI signature alone -> real_ime_precondition.
root="$SANDBOX/a"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedQueuedDraftExpandsAboveImeThenReturnsWithoutDismissalOrLoss:pass" \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:pass" \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "real_ime_precondition" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(a) expected real_ime_precondition, got '$SIG_CLASS'"; }
grep -q '^journey_failing_testcases=2$' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(a) must attribute both failing attempts"; }
grep -q '^journey_signature_matches=2$' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(a) both failures must match the captured signature"; }
pass "(a) only the real-IME precondition -> real_ime_precondition"

# (b) A containment/anchor failure -> product_failure. This is the assertion the
#     signature must NEVER swallow.
root="$SANDBOX/b"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(b) a containment failure must be product_failure, got '$SIG_CLASS'"; }
pass "(b) a containment/anchor assertion failure -> product_failure (stays RED)"

# (c) MIXED in the same class, same run: the real-IME precondition AND a
#     containment failure. The narrow signature must not swallow the real one.
root="$SANDBOX/c"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime" \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(c) mixed evidence must be product_failure, got '$SIG_CLASS'"; }
grep -q 'journey_offending_failures=.*WithSyntheticIme' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(c) the offending containment failure must be named"; }
pass "(c) real-IME precondition MIXED with a containment failure -> product_failure"

# (d) A DIFFERENT class failing alongside -> product_failure (the signature is
#     per-run, not per-class: any other failed-both class keeps the shard red).
root="$SANDBOX/d"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS" "com.pocketshell.app.proof.SomeOtherJourneyE2eTest"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "com.pocketshell.app.proof.SomeOtherJourneyE2eTest" 1 \
  "someJourney:containment"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(d) a second failing class must be product_failure, got '$SIG_CLASS'"; }
pass "(d) a second genuinely failing class -> product_failure"

# (e) An earlier attempt's containment failure in the SAME class (recovered or
#     not) still forces product_failure — the classifier reads every attempt.
root="$SANDBOX/e"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(e) an earlier-attempt containment failure must be product_failure"; }
pass "(e) a containment failure in ANY preserved attempt -> product_failure"

# (f) Missing / unreadable evidence -> unclassified (fail-safe: stays RED).
root="$SANDBOX/f"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "unclassified" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(f) missing result XML must be unclassified, got '$SIG_CLASS'"; }

root="$SANDBOX/f2"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
corrupt="$(find "$root" -name 'TEST-*.xml' | head -n 1)"
printf '<testsuite><testcase' > "$corrupt"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" != "real_ime_precondition" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(f) a corrupt result file must never yield real_ime_precondition"; }
pass "(f) missing or corrupt evidence never downgrades the shard (stays RED)"

# (g) A clean run (no failed-both section) -> unclassified; the branch can never
#     fire on a green shard.
root="$SANDBOX/g"; mkdir -p "$root"
write_summary "$root" 2338
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "unclassified" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(g) a summary with no failed-both section must be unclassified"; }
pass "(g) no failed-both section -> unclassified"

# (h) An error (not failure) element is also a genuine failure -> product_failure.
root="$SANDBOX/h"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:error"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(h) an <error> element must be product_failure, got '$SIG_CLASS'"; }
pass "(h) a harness <error> element -> product_failure"

echo
echo "== #1800 end-to-end aggregate outcomes (observed, not argued) =="

# (i) A run whose ONLY shard failure is the captured signature -> RE-RUN.
#     Built in the REAL canonical layout, including the preserved first-attempt
#     snapshot the workflow writes, and decided by the REAL decision script.
root="$SANDBOX/i"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
token="$SHARD_TOKEN"
verdicts="$SANDBOX/verdicts-i"; rm -rf "$verdicts"; mkdir -p "$verdicts"
write_shard_token "$verdicts" 0 "$token"
write_shard_token "$verdicts" 1 CLEAN
write_shard_token "$verdicts" 2 CLEAN
run_agg "$verdicts"
printf '%s\n' "$SHARD_VERDICT_OUT" | sed 's/^/    decision: /'
echo "    -> shard token $token"
printf '%s\n' "$AGG_OUT" | sed 's/^/    /'
[[ "$token" == "INFRA" ]] || fail "(i) the signature-only shard must be typed INFRA, got $token"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || fail "(i) expected RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"
grep -q '::error' <<<"$AGG_OUT" \
  && fail "(i) a signature-only run must not emit ::error (no false red-CI email)"
pass "(i) signature-only shard failure -> INFRA -> aggregate RE-RUN (exit 0)"

# (j) A genuine journey assertion failure still -> RED. Same shape as (i), one
#     containment assertion instead of the precondition.
root="$SANDBOX/j"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
token="$SHARD_TOKEN"
verdicts="$SANDBOX/verdicts-j"; rm -rf "$verdicts"; mkdir -p "$verdicts"
write_shard_token "$verdicts" 0 "$token"
write_shard_token "$verdicts" 1 CLEAN
write_shard_token "$verdicts" 2 CLEAN
run_agg "$verdicts"
printf '%s\n' "$SHARD_VERDICT_OUT" | sed 's/^/    decision: /'
echo "    -> shard token $token"
printf '%s\n' "$AGG_OUT" | sed 's/^/    /'
[[ "$token" == "RED" ]] || fail "(j) a genuine assertion failure must stay RED, got $token"
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || fail "(j) expected RED/exit1, got $AGG_VERDICT/exit$AGG_RC"
pass "(j) genuine journey assertion failure -> RED -> aggregate RED (exit 1)"

# (j2) The preserved FIRST attempt carries a genuine containment failure while
#      the final attempt's summary is signature-only. Both snapshots are
#      inspected, so the shard must still be RED.
root="$SANDBOX/j2"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
write_case_xml "$root/ci-journey-attempt-1" "$SATURATED_CLASS" 9 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
shard_verdict_for "$root"
token="$SHARD_TOKEN"
printf '%s\n' "$SHARD_VERDICT_OUT" | sed 's/^/    decision: /'
[[ "$token" == "RED" ]] \
  || fail "(j2) a containment failure in the preserved first attempt must stay RED, got $token"
pass "(j2) a genuine failure in EITHER preserved attempt keeps the shard RED"

echo
echo "== #1800 measured cold-boot retry budget =="

# The observed shard-0 fixture from run 30305256109: job start 21:32:27.657,
# first attempt start 21:35:45.145, suite elapsed 2338s, 3112241ms remaining.
job_start=1785187947657
remaining=3112241
now=$((job_start + 5700000 - remaining))
attempt_start=1785188145145
suite_secs=2338

legacy_out="$(bash "$RETRY_BUDGET" "$job_start" "$now")"
grep -q '^retry_allowed=false$' <<<"$legacy_out" \
  || { printf '%s\n' "$legacy_out"; fail "(k) the flat worst case must still deny the shard-0 retry"; }
grep -q '^retry_required_ms=5400000$' <<<"$legacy_out" \
  || { printf '%s\n' "$legacy_out"; fail "(k) the unmeasured fallback must stay the flat 5400000ms"; }
grep -q '^retry_cost_model=worst_case$' <<<"$legacy_out" \
  || { printf '%s\n' "$legacy_out"; fail "(k) the unmeasured fallback must report worst_case"; }
printf '%s\n' "$legacy_out" | sed 's/^/    unmeasured: /'
pass "(k) without measurements the requirement is the unchanged flat 5400000ms (deny)"

measured_out="$(bash "$RETRY_BUDGET" "$job_start" "$now" "$attempt_start" "$suite_secs")"
printf '%s\n' "$measured_out" | sed 's/^/    measured:   /'
grep -q '^retry_allowed=true$' <<<"$measured_out" \
  || { printf '%s\n' "$measured_out"; fail "(k) shard-0's measured cost must PERMIT the retry at ${remaining}ms"; }
grep -q '^retry_cost_model=measured_first_attempt$' <<<"$measured_out" \
  || { printf '%s\n' "$measured_out"; fail "(k) the measured path must report measured_first_attempt"; }
measured_required="$(sed -n 's/^retry_required_ms=//p' <<<"$measured_out" | tail -n 1)"
[[ "$measured_required" =~ ^[0-9]+$ ]] || fail "(k) measured requirement is not numeric"
(( measured_required <= remaining )) \
  || fail "(k) measured requirement ${measured_required}ms must fit in ${remaining}ms"
(( measured_required < 5400000 )) \
  || fail "(k) measured requirement must be below the flat worst case"
pass "(k) shard-0 (remaining=${remaining}ms) now requires ${measured_required}ms -> retry PERMITTED"

# The measured model may only ever RELAX. A pathologically slow first attempt
# must never demand more than the legacy flat reserve.
slow_out="$(bash "$RETRY_BUDGET" "$job_start" "$now" "$((now - 9000000))" 8000)"
slow_required="$(sed -n 's/^retry_required_ms=//p' <<<"$slow_out" | tail -n 1)"
[[ "$slow_required" == "5400000" ]] \
  || { printf '%s\n' "$slow_out"; fail "(l) a slow first attempt must clamp to the flat 5400000ms, got $slow_required"; }
pass "(l) the measured model never demands MORE than the flat worst case"

# Malformed measurements fall back to the flat worst case, never to a smaller
# requirement derived from garbage.
for bad in "not-an-epoch 2338" "$attempt_start not-a-number" "$attempt_start 0" "0 2338" "$((now + 1000)) 2338"; do
  # shellcheck disable=SC2086 # deliberate word splitting of the fixture pair
  bad_out="$(bash "$RETRY_BUDGET" "$job_start" "$now" $bad)"
  grep -q '^retry_required_ms=5400000$' <<<"$bad_out" \
    || { printf '%s\n' "$bad_out"; fail "(m) malformed measurement '$bad' must fall back to the flat reserve"; }
  grep -q '^retry_cost_model=worst_case$' <<<"$bad_out" \
    || { printf '%s\n' "$bad_out"; fail "(m) malformed measurement '$bad' must report worst_case"; }
done
pass "(m) malformed/absent measurements fall back to the flat worst case"

# The measured elapsed comes from the suite's own summary table.
root="$SANDBOX/elapsed"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
parsed="$(bash "$ELAPSED" "$root/ci-journey/summary.md")" \
  || fail "(n) could not read the measured elapsed from a real-shaped summary"
[[ "$parsed" == "2338" ]] || fail "(n) measured elapsed parsed as '$parsed', expected 2338"
bash "$ELAPSED" "$SANDBOX/does-not-exist.md" >/dev/null 2>&1 \
  && fail "(n) a missing summary must fail so the caller keeps the flat reserve"
printf 'no table here\n' > "$SANDBOX/no-table.md"
bash "$ELAPSED" "$SANDBOX/no-table.md" >/dev/null 2>&1 \
  && fail "(n) a summary without the result table must fail rather than guess"
pass "(n) the measured elapsed is read from the suite's own summary table"

echo
echo "== #1800 real-workflow wiring =="

grep -q "scripts/ci-journey-shard-signature-verdict.sh artifacts" "$WORKFLOW" \
  || fail "the classify step does not call the real shard signature decision script"
grep -q 'JOURNEY_FIRST_ATTEMPT_START_EPOCH_MS=' "$WORKFLOW" \
  || fail "the workflow does not record the first journey attempt start epoch"
grep -q 'scripts/ci-journey-suite-elapsed.sh' "$WORKFLOW" \
  || fail "the workflow does not read the suite's measured elapsed"
grep -q 'first_suite_elapsed_secs' "$WORKFLOW" \
  || fail "the measured elapsed is not passed to the retry-budget helper"

signature_line="$(grep -n 'signature_verdict" == "INFRA"' "$WORKFLOW" | head -n 1 | cut -d: -f1)"
# shellcheck disable=SC2016 # literal workflow shell expressions, not test vars
first_failure_line="$(grep -Fn 'if [[ "${first_failure:-false}" == "true" ]]' "$WORKFLOW" | cut -d: -f1)"
retry_clean_line="$(grep -Fn 'if [[ "$retry" == "success" ]]' "$WORKFLOW" | cut -d: -f1)"
first_clean_line="$(grep -Fn 'if [[ "$first" == "success" ]]' "$WORKFLOW" | cut -d: -f1)"
for value in "$signature_line" "$first_failure_line" "$retry_clean_line" "$first_clean_line"; do
  [[ "$value" =~ ^[0-9]+$ ]] || fail "could not locate the classify-step branches in the workflow"
done
(( first_clean_line < signature_line && retry_clean_line < signature_line )) \
  || fail "the signature branch must not preempt a CLEAN shard"
(( signature_line < first_failure_line )) \
  || fail "the signature branch must be evaluated before the genuine-failure RED branch"
pass "classify-step branch order: CLEAN -> signature INFRA -> genuine-failure RED"

grep -q 'first_timeout:-false}" != "true"' "$WORKFLOW" \
  || fail "the signature branch does not exclude the #835 budget timeout"
pass "the signature branch can never fire for a #835 budget timeout"

echo
echo "== #1800 synthetic coverage is present and wired =="

[[ -f "$ANDROID_TEST" ]] || fail "missing $ANDROID_TEST"
grep -q 'fun saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide' "$ANDROID_TEST" \
  || fail "the synthetic mirror of the real-IME reachability journey is missing"
grep -q 'val REAL_IME_REACHABLE_TAGS' "$ANDROID_TEST" \
  || fail "the real-IME and synthetic paths no longer share one reachable-tag set"
grep -c 'REAL_IME_REACHABLE_TAGS.forEach' "$ANDROID_TEST" | grep -qE '^[3-9]$|^[0-9]{2,}$' \
  || fail "both paths must iterate the shared reachable-tag set"
grep -qE '\bassume(True|False|NotNull|That)[[:space:]]*\(' "$ANDROID_TEST" \
  && fail "no Assume self-skip may guard these load-bearing assertions (F3)"
grep -q "com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest" \
  "$REPO_ROOT/scripts/ci-journey-suite.sh" \
  || fail "the class carrying the synthetic mirror is not wired into the per-push journey gate"
pass "the synthetic mirror exists, shares the tag set, and runs in the per-push gate"

echo
echo "ALL TESTS PASSED: scripts/test-ci-journey-infra-signature.sh"
