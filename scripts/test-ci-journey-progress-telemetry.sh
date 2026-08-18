#!/usr/bin/env bash
# Issue #2090: JVM-free, emulator-free proof that last-completed-class + job
# metadata survive a hosted journey-shard runner disappearing, that a missing
# shard never aggregates CLEAN, and that a successful shard keeps its existing
# verdict/artifact contract while adding only bounded telemetry.
#
# Reproduce-first (D32): a publisher that writes ONLY on the runner filesystem
# leaves no last-completed-class after a simulated runner death. That is the
# #2038 hole. The real publisher must keep the record in an extra-runner store.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
PROGRESS="$SCRIPT_DIR/ci-journey-progress-telemetry.sh"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"
WRITER="$SCRIPT_DIR/ci-journey-write-shard-verdict.sh"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"
NIGHTLY="$REPO_ROOT/.github/workflows/nightly-extensive.yml"
SUITE="$SCRIPT_DIR/ci-journey-suite.sh"
CLASS_LOOP="$SCRIPT_DIR/ci-journey-class-loop-functions.sh"
NIGHTLY_SUITE="$SCRIPT_DIR/nightly-extensive-suite.sh"
SUMMARY_FN="$SCRIPT_DIR/ci-journey-summary-functions.sh"
SHARD_COUNT="$SCRIPT_DIR/ci-journey-shard-count.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$PROGRESS" "$AGG" "$WRITER" "$WORKFLOW" "$SUITE" "$CLASS_LOOP" \
                "$NIGHTLY_SUITE" "$SUMMARY_FN" "$SHARD_COUNT"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done
chmod +x "$PROGRESS" "$AGG" "$WRITER"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

CLASS_A="com.pocketshell.app.proof.DeepLinkSessionSwitchE2eTest"
CLASS_B="com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest"
CLASS_C="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"

# Issue #2060: derive the shipped matrix length. Hardcoded EXPECTED_SHARDS=3 /
# POCKETSHELL_JOURNEY_CI_SHARD_TOTAL=6 / `for idx in 0 1 2` are the exact
# shapes the downward-only ratchet refuses to absorb as a new file.
SHARD_TOTAL="$(bash "$SHARD_COUNT" "$WORKFLOW")" \
  || fail "could not derive the emulator-journey shard count from the matrix"
(( SHARD_TOTAL >= 2 )) \
  || fail "matrix must have at least 2 shards so a missing-shard case exists"
LAST_SHARD="$((SHARD_TOTAL - 1))"

# ---------------------------------------------------------------------------
# AC1 RED: local-only publisher (the pre-#2090 shape) loses last-completed-class
# after a simulated runner death. This is the reproduction; it must stay RED.
# ---------------------------------------------------------------------------
echo "== #2090 AC1 reproduction: local-only publisher loses evidence =="

runner_dead="$SANDBOX/runner-local-only"
external_dead="$SANDBOX/external-local-only"
mkdir -p "$runner_dead" "$external_dead"
local_only="$runner_dead/local-only-publisher.sh"
cat > "$local_only" <<'LOCAL'
#!/usr/bin/env bash
# Mutant: writes the progress record only on the runner filesystem.
set -uo pipefail
cmd="${1:-}"; shift || true
file="${CI_JOURNEY_PROGRESS_FILE:-$PWD/progress.txt}"
mkdir -p "$(dirname "$file")"
case "$cmd" in
  start)
    printf 'schema=pocketshell.journey.progress.v1\nowner=infra\nphase=started\nlast_completed_class=\nseq=0\n' > "$file"
    ;;
  class-completed)
    printf 'schema=pocketshell.journey.progress.v1\nowner=infra\nphase=class_completed\nlast_completed_class=%s\nlast_completed_status=%s\nseq=2\nrun_id=%s\njob_name=%s\n' \
      "${1-}" "${2-}" "${GITHUB_RUN_ID:-unknown}" "${GITHUB_JOB:-unknown}" > "$file"
    ;;
esac
exit 0
LOCAL
chmod +x "$local_only"

(
  cd "$runner_dead" || exit 1
  export CI_JOURNEY_PROGRESS_FILE="$runner_dead/artifacts/ci-journey-progress/progress.txt"
  export CI_JOURNEY_PROGRESS_EXTERNAL_DIR=""
  export CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1
  export GITHUB_RUN_ID=2038 GITHUB_RUN_ATTEMPT=1 GITHUB_JOB=extensive
  export POCKETSHELL_NIGHTLY_SHARD_INDEX="$LAST_SHARD" POCKETSHELL_NIGHTLY_SHARD_TOTAL="$SHARD_TOTAL"
  bash "$local_only" start
  bash "$local_only" class-completed "$CLASS_A" pass
  bash "$local_only" class-completed "$CLASS_B" pass
)
rm -rf "$runner_dead"
if [[ -e "$external_dead/journey-progress-shard-${LAST_SHARD}-attempt-1-run-2038.txt" ]] \
   || grep -Rqs "$CLASS_B" "$external_dead" 2>/dev/null; then
  fail "AC1 RED control was vacuous: the local-only mutant still left extra-runner evidence"
fi
if [[ -e "$runner_dead" ]]; then
  fail "AC1 RED control: simulated runner death did not wipe the runner workspace"
fi
pass "RED: local-only publisher leaves no last-completed-class after runner death"

# ---------------------------------------------------------------------------
# AC1 GREEN: the real publisher keeps last-completed-class + job metadata in
# the extra-runner store after the same wipe.
# ---------------------------------------------------------------------------
echo "== #2090 AC1 green: extra-runner store survives runner death =="

runner_live="$SANDBOX/runner-real"
external_live="$SANDBOX/external-real"
mkdir -p "$runner_live" "$external_live"

progress_env=(
  CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1
  GITHUB_RUN_ID=2090
  GITHUB_RUN_ATTEMPT=1
  GITHUB_JOB=emulator-journey
  GITHUB_JOB_ID=92770537075
  GITHUB_WORKFLOW=Tests
  GITHUB_SHA=e08c336bdeadbeef
  RUNNER_NAME=GitHubActions-hosted
  RUNNER_OS=Linux
  POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$LAST_SHARD"
  POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$SHARD_TOTAL"
  CI_JOURNEY_PROGRESS_CLASSES_SELECTED=3
)

(
  cd "$runner_live" || exit 1
  export CI_JOURNEY_PROGRESS_FILE="$runner_live/artifacts/ci-journey-progress/progress.txt"
  export CI_JOURNEY_PROGRESS_EXTERNAL_DIR="$external_live"
  export "${progress_env[@]}"
  bash "$PROGRESS" start
  bash "$PROGRESS" class-started "$CLASS_A"
  bash "$PROGRESS" class-completed "$CLASS_A" pass
  bash "$PROGRESS" class-started "$CLASS_B"
  bash "$PROGRESS" class-completed "$CLASS_B" pass
  bash "$PROGRESS" class-started "$CLASS_C"
  # Abrupt loss mid-class C: no suite-completed, no artifacts-uploaded.
)
# Simulated hosted-runner disappearance: workspace gone, later if:always()
# steps never ran, no verdict artifact uploaded.
rm -rf "$runner_live"

surviving="$(find "$external_live" -type f -name 'journey-progress-shard-*.txt' | head -n 1)"
[[ -n "$surviving" && -f "$surviving" ]] \
  || fail "AC1 GREEN: extra-runner store has no progress record after runner death"
grep -qx "last_completed_class=$CLASS_B" "$surviving" \
  || { cat "$surviving"; fail "AC1 GREEN: surviving record is missing last_completed_class=$CLASS_B"; }
grep -qx "in_progress_class=$CLASS_C" "$surviving" \
  || { cat "$surviving"; fail "AC1 GREEN: surviving record is missing in_progress_class=$CLASS_C"; }
grep -qx "owner=infra" "$surviving" \
  || fail "AC1 GREEN: surviving record must be owned by infra, not a product verdict"
grep -qx "verdict_role=diagnostic" "$surviving" \
  || fail "AC1 GREEN: surviving record must be diagnostic, not a product verdict"
grep -qx "run_id=2090" "$surviving" \
  || fail "AC1 GREEN: surviving record missing run_id job metadata"
grep -qx "run_attempt=1" "$surviving" \
  || fail "AC1 GREEN: surviving record missing run_attempt"
grep -qx "job_name=emulator-journey" "$surviving" \
  || fail "AC1 GREEN: surviving record missing job_name"
grep -qx "job_id=92770537075" "$surviving" \
  || fail "AC1 GREEN: surviving record missing job_id"
grep -qx "runner_name=GitHubActions-hosted" "$surviving" \
  || fail "AC1 GREEN: surviving record missing runner_name"
grep -qx "shard=$LAST_SHARD" "$surviving" \
  || fail "AC1 GREEN: surviving record missing shard"
[[ ! -e "$runner_live" ]] \
  || fail "AC1 GREEN: runner workspace was not wiped"
pass "GREEN: last-completed-class + job metadata survive runner death"

# Classify the three loss modes from the surviving record + job hints.
echo "== #2090 loss-mode signatures stay infra =="

classify_out="$(
  CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1 \
  CI_JOURNEY_PROGRESS_VERDICT_PRESENT=false \
  CI_JOURNEY_PROGRESS_LATER_STEPS_RAN=false \
  CI_JOURNEY_PROGRESS_LOST_COMMUNICATION=true \
  bash "$PROGRESS" classify "$surviving"
)"
grep -qx "signature=hosted_runner_vm_loss" <<<"$classify_out" \
  || { printf '%s\n' "$classify_out"; fail "lost-communication must classify as hosted_runner_vm_loss"; }
grep -qx "owner=infra" <<<"$classify_out" \
  || fail "vm-loss signature must stay owner=infra"
grep -qx "verdict_role=diagnostic" <<<"$classify_out" \
  || fail "vm-loss signature must stay diagnostic"
grep -qx "last_completed_class=$CLASS_B" <<<"$classify_out" \
  || fail "vm-loss classification must carry last_completed_class"
pass "provider VM loss -> hosted_runner_vm_loss (infra diagnostic)"

classify_out="$(
  CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1 \
  CI_JOURNEY_PROGRESS_VERDICT_PRESENT=false \
  CI_JOURNEY_PROGRESS_LATER_STEPS_RAN=true \
  CI_JOURNEY_PROGRESS_LOST_COMMUNICATION=false \
  bash "$PROGRESS" classify "$surviving"
)"
grep -qx "signature=hosted_runner_process_death" <<<"$classify_out" \
  || { printf '%s\n' "$classify_out"; fail "later-steps-without-verdict must classify as hosted_runner_process_death"; }
grep -qx "owner=infra" <<<"$classify_out" \
  || fail "process-death signature must stay owner=infra"
pass "process death -> hosted_runner_process_death (infra diagnostic)"

upload_fail_record="$SANDBOX/upload-fail.progress.txt"
cp "$surviving" "$upload_fail_record"
# Suite finished and wrote a local verdict, but the upload step failed.
{
  grep -vE '^(phase|suite_state)=' "$surviving"
  echo "phase=suite_completed"
  echo "suite_state=completed"
} > "$upload_fail_record"
classify_out="$(
  CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1 \
  CI_JOURNEY_PROGRESS_VERDICT_PRESENT=false \
  CI_JOURNEY_PROGRESS_LATER_STEPS_RAN=true \
  CI_JOURNEY_PROGRESS_LOST_COMMUNICATION=false \
  bash "$PROGRESS" classify "$upload_fail_record"
)"
grep -qx "signature=hosted_runner_artifact_upload_failure" <<<"$classify_out" \
  || { printf '%s\n' "$classify_out"; fail "completed-suite without uploaded verdict must classify as artifact-upload failure"; }
grep -qx "owner=infra" <<<"$classify_out" \
  || fail "upload-failure signature must stay owner=infra"
pass "artifact-upload failure -> hosted_runner_artifact_upload_failure (infra diagnostic)"

# ---------------------------------------------------------------------------
# AC2: missing shard is never CLEAN. Drive the REAL aggregator. Progress is
# diagnostic INFRA, not a product RED/CLEAN flip.
# ---------------------------------------------------------------------------
echo "== #2090 AC2: missing shard stays fail-closed / not green =="

verdict_dir="$SANDBOX/verdicts-missing"
mkdir -p "$verdict_dir"
for (( idx = 0; idx < SHARD_TOTAL; idx++ )); do
  (( idx == LAST_SHARD )) && continue
  mkdir -p "$verdict_dir/emulator-journey-verdict-shard-$idx"
  GITHUB_RUN_ID=2090 GITHUB_RUN_ATTEMPT=1 POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
    SHARD_VERDICT_FILE="$verdict_dir/emulator-journey-verdict-shard-$idx/shard-verdict.txt" \
    bash "$WRITER" CLEAN journey_ok >/dev/null
done
# last shard missing — only extra-runner progress survived.

set +e
AGG_OUT="$(
  EXPECTED_SHARDS="$SHARD_TOTAL" \
  GITHUB_STEP_SUMMARY="" \
  GITHUB_RUN_ATTEMPT=1 \
  CI_JOURNEY_PROGRESS_DIR="$external_live" \
  bash "$AGG" "$verdict_dir" 2>&1
)"
AGG_RC=$?
set -e
AGG_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$AGG_OUT" | tail -n 1)"
[[ "$AGG_VERDICT" != "CLEAN" ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "AC2: a missing shard must not aggregate CLEAN"; }
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "AC2: missing shard expected existing RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
grep -q "$CLASS_B" <<<"$AGG_OUT" \
  || { printf '%s\n' "$AGG_OUT"; fail "AC2: aggregate must name the surviving last-completed-class"; }
grep -qi 'infra' <<<"$AGG_OUT" \
  || { printf '%s\n' "$AGG_OUT"; fail "AC2: last-completed-class note must be linked to infra, not a product verdict"; }
grep -q '::error' <<<"$AGG_OUT" \
  && fail "AC2: missing-shard + progress must not become product RED"
pass "missing shard stays not-CLEAN; last-completed-class is infra diagnostic"

# Mutation: an aggregator that reports CLEAN when a shard is missing must go
# RED here. That is the G6 check for AC2.
agg_mutant="$SANDBOX/agg-clean-on-missing.sh"
python3 - "$AGG" "$agg_mutant" <<'PY'
import pathlib, sys
src = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
old = '''if (( have_infra > 0 || missing > 0 )); then
  echo "::warning title=Emulator journey verdict — RE-RUN (environmental)::No genuine journey failure, but ${have_infra} shard(s) hit an environmental infra abort (#470 cancel / #771 never-booted / retry budget denied) and ${missing} shard(s) did not report. This is a re-run signal, NOT a product regression — the run stays green so main-health is readable and no false red-CI email fires. Re-run the emulator-journey job."
  emit_summary "RE-RUN" "${have_infra} infra shard(s) + ${missing} missing shard(s), no genuine failure — re-run"
  echo "AGGREGATE_VERDICT=RE-RUN"
  exit 0
fi'''
new = '''if (( have_infra > 0 || missing > 0 )); then
  echo "AGGREGATE_VERDICT=CLEAN"
  exit 0
fi'''
if old not in src:
    raise SystemExit("could not locate the missing-shard RE-RUN branch to mutate")
pathlib.Path(sys.argv[2]).write_text(src.replace(old, new, 1), encoding="utf-8")
PY
chmod +x "$agg_mutant"
set +e
MUT_OUT="$(
  EXPECTED_SHARDS="$SHARD_TOTAL" \
  GITHUB_STEP_SUMMARY="" \
  GITHUB_RUN_ATTEMPT=1 \
  CI_JOURNEY_PROGRESS_DIR="$external_live" \
  bash "$agg_mutant" "$verdict_dir" 2>&1
)"
set -e
MUT_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$MUT_OUT" | tail -n 1)"
[[ "$MUT_VERDICT" == "CLEAN" ]] \
  || { printf '%s\n' "$MUT_OUT"; fail "AC2 mutant did not report CLEAN — the mutation is not exercising the missing-shard branch"; }
# The production assertion above already rejected CLEAN. Confirm selectivity:
# the mutant is the only thing that went green.
[[ "$AGG_VERDICT" != "CLEAN" && "$MUT_VERDICT" == "CLEAN" ]] \
  || fail "AC2 mutant was not selective"
pass "mutation: CLEAN-on-missing reddens AC2 (G6)"

# ---------------------------------------------------------------------------
# AC3: a normal successful shard adds only bounded telemetry and keeps the
# existing verdict/artifact contract.
# ---------------------------------------------------------------------------
echo "== #2090 AC3: successful shard keeps the existing contract =="

success_runner="$SANDBOX/success-runner"
success_external="$SANDBOX/success-external"
success_verdicts="$SANDBOX/success-verdicts"
mkdir -p "$success_runner" "$success_external" "$success_verdicts"

for (( idx = 0; idx < SHARD_TOTAL; idx++ )); do
  mkdir -p "$success_verdicts/emulator-journey-verdict-shard-$idx"
  GITHUB_RUN_ID=2090 GITHUB_RUN_ATTEMPT=1 POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
    SHARD_VERDICT_FILE="$success_verdicts/emulator-journey-verdict-shard-$idx/shard-verdict.txt" \
    bash "$WRITER" CLEAN journey_ok >/dev/null
done

# Pin the pre-#2090 verdict contract: line 1 is the bare token, then key=value.
success_token="$success_verdicts/emulator-journey-verdict-shard-$LAST_SHARD/shard-verdict.txt"
first_line="$(awk 'NR==1 { print; exit }' "$success_token")"
[[ "$first_line" == "CLEAN" ]] \
  || fail "AC3: successful shard verdict line 1 must stay the bare CLEAN token"
grep -q '^shard=' "$success_token" \
  || fail "AC3: successful shard verdict must keep #1809 provenance"
grep -q '^run_id=' "$success_token" \
  || fail "AC3: successful shard verdict must keep run_id"

(
  cd "$success_runner" || exit 1
  export CI_JOURNEY_PROGRESS_FILE="$success_runner/artifacts/ci-journey-progress/progress.txt"
  export CI_JOURNEY_PROGRESS_EXTERNAL_DIR="$success_external"
  export CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1
  export GITHUB_RUN_ID=2090 GITHUB_RUN_ATTEMPT=1
  export GITHUB_JOB=emulator-journey
  export POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$LAST_SHARD"
  export POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$SHARD_TOTAL"
  export CI_JOURNEY_PROGRESS_CLASSES_SELECTED=3
  bash "$PROGRESS" start
  bash "$PROGRESS" class-completed "$CLASS_A" pass
  bash "$PROGRESS" class-completed "$CLASS_B" pass
  bash "$PROGRESS" class-completed "$CLASS_C" pass
  bash "$PROGRESS" suite-completed completed
  bash "$PROGRESS" artifacts-uploaded
)

success_progress="$(find "$success_external" -type f -name "journey-progress-shard-${LAST_SHARD}-*.txt" | head -n 1)"
[[ -f "$success_progress" ]] || fail "AC3: successful shard wrote no bounded telemetry"
progress_bytes="$(wc -c < "$success_progress" | tr -d ' ')"
(( progress_bytes <= 4096 )) \
  || fail "AC3: telemetry is not bounded (${progress_bytes}B > 4096)"
# Last class only — a full class dump would be the unbounded shape.
grep -qx "last_completed_class=$CLASS_C" "$success_progress" \
  || fail "AC3: successful telemetry must keep the LAST completed class"
class_lines="$(grep -c 'com.pocketshell.app' "$success_progress" || true)"
(( class_lines <= 2 )) \
  || fail "AC3: telemetry must not retain a full class list (found $class_lines class lines)"
grep -qx "suite_state=artifacts_uploaded" "$success_progress" \
  || fail "AC3: successful shard should mark artifacts-uploaded"
# Local snapshot is a sibling of ci-journey/, never inside it (#1781).
[[ -f "$success_runner/artifacts/ci-journey-progress/progress.txt" ]] \
  || fail "AC3: local snapshot should live under artifacts/ci-journey-progress/"
[[ ! -e "$success_runner/artifacts/ci-journey/progress.txt" ]] \
  || fail "AC3: telemetry must not land inside artifacts/ci-journey/"

set +e
SUCCESS_OUT="$(
  EXPECTED_SHARDS="$SHARD_TOTAL" \
  GITHUB_STEP_SUMMARY="" \
  GITHUB_RUN_ATTEMPT=1 \
  CI_JOURNEY_PROGRESS_DIR="$success_external" \
  bash "$AGG" "$success_verdicts" 2>&1
)"
SUCCESS_RC=$?
set -e
SUCCESS_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$SUCCESS_OUT" | tail -n 1)"
[[ "$SUCCESS_VERDICT" == "CLEAN" && "$SUCCESS_RC" -eq 0 ]] \
  || { printf '%s\n' "$SUCCESS_OUT"; fail "AC3: all-CLEAN shards plus telemetry must stay CLEAN, got $SUCCESS_VERDICT/exit$SUCCESS_RC"; }
pass "successful shards stay CLEAN with bounded sibling telemetry"

classify_out="$(
  CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1 \
  CI_JOURNEY_PROGRESS_VERDICT_PRESENT=true \
  CI_JOURNEY_PROGRESS_LATER_STEPS_RAN=true \
  CI_JOURNEY_PROGRESS_LOST_COMMUNICATION=false \
  bash "$PROGRESS" classify "$success_progress"
)"
grep -qx "signature=none" <<<"$classify_out" \
  || { printf '%s\n' "$classify_out"; fail "AC3: a completed uploaded shard must not carry a runner-loss signature"; }
pass "successful shard classify() is signature=none"

# observe-stream must record last-completed-class from Gradle/instrumentation
# lines (the nightly path has no per-class bash loop).
echo "== #2090 nightly observe-stream =="
obs_external="$SANDBOX/observe-external"
mkdir -p "$obs_external"
obs_file="$SANDBOX/observe-local.txt"
{
  echo "com.pocketshell.app.proof.FooTest > foo PASSED"
  echo "INSTRUMENTATION_STATUS: class=com.pocketshell.app.proof.BarTest"
  echo "INSTRUMENTATION_STATUS_CODE: 0"
} | CI_JOURNEY_PROGRESS_FILE="$obs_file" \
    CI_JOURNEY_PROGRESS_EXTERNAL_DIR="$obs_external" \
    CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1 \
    GITHUB_RUN_ID=obs GITHUB_RUN_ATTEMPT=1 \
    POCKETSHELL_NIGHTLY_SHARD_INDEX="$LAST_SHARD" POCKETSHELL_NIGHTLY_SHARD_TOTAL="$SHARD_TOTAL" \
    bash "$PROGRESS" observe-stream >/dev/null
obs_surviving="$(find "$obs_external" -type f -name "journey-progress-shard-${LAST_SHARD}-*.txt" | head -n 1)"
[[ -f "$obs_surviving" ]] || fail "observe-stream wrote no extra-runner record"
grep -qx "last_completed_class=com.pocketshell.app.proof.BarTest" "$obs_surviving" \
  || { cat "$obs_surviving"; fail "observe-stream must keep the last completed class"; }
pass "observe-stream records last completed class for the nightly path"

# ---------------------------------------------------------------------------
# Wiring: the class loop, suite, nightly, summary, and per-push Unit job must
# actually invoke this helper. A helper nobody calls is not coverage.
# ---------------------------------------------------------------------------
echo "== #2090 production + Unit-job wiring =="

grep -q 'ci-journey-progress-telemetry.sh' "$CLASS_LOOP" \
  || fail "class loop must invoke ci-journey-progress-telemetry.sh"
grep -q 'class-completed' "$CLASS_LOOP" \
  || fail "class loop must record class-completed"
grep -q 'class-started' "$CLASS_LOOP" \
  || fail "class loop must record class-started"
grep -q 'ci-journey-progress-telemetry.sh' "$SUITE" \
  || fail "ci-journey-suite.sh must start progress telemetry"
grep -q 'ci-journey-progress-telemetry.sh' "$SUMMARY_FN" \
  || fail "finish_ci_journey_suite must mark suite-completed"
grep -q 'ci-journey-progress-telemetry.sh' "$NIGHTLY_SUITE" \
  || fail "nightly-extensive-suite.sh must start / observe progress telemetry"
grep -q 'observe-stream' "$NIGHTLY_SUITE" \
  || fail "nightly-extensive-suite.sh must pipe phase-1 output through observe-stream"
grep -q 'test-ci-journey-progress-telemetry.sh' "$WORKFLOW" \
  || fail "tests.yml Unit/guards job must run this self-test"
grep -q 'ci-journey-progress-telemetry.sh artifacts-uploaded' "$WORKFLOW" \
  || fail "emulator-journey must mark artifacts-uploaded after the verdict upload"
grep -q 'CI_JOURNEY_PROGRESS_DIR' "$WORKFLOW" \
  || fail "aggregate job must collect surviving progress into CI_JOURNEY_PROGRESS_DIR"
if [[ -f "$NIGHTLY" ]]; then
  grep -q 'ci-journey-progress-telemetry.sh artifacts-uploaded' "$NIGHTLY" \
    || fail "nightly-extensive.yml must mark artifacts-uploaded after report upload"
fi
grep -q 'CI_JOURNEY_PROGRESS_DIR' "$AGG" \
  || fail "aggregate script must read CI_JOURNEY_PROGRESS_DIR for missing shards"
pass "production hooks and the per-push Unit guard are wired"

echo
echo "PASS: test-ci-journey-progress-telemetry ($SECONDS s)"
