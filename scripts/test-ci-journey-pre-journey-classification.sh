#!/usr/bin/env bash
# Issue #1913: a deterministic failure before either emulator journey action
# runs must be RED, never guessed to be #771 emulator-never-booted INFRA. Drive
# the REAL workflow classifier body and the REAL aggregate reducer so workflow
# and fixture cannot drift into two implementations of the decision.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# Extract a named workflow step's literal run body, substituting every GitHub
# expression from an explicit JSON map. An unmapped expression hard-fails so a
# workflow edit cannot silently turn this fixture into a different scenario.
extract_step_body() {
  local step_name="$1" out_path="$2" expressions="$3"
  STEP_NAME="$step_name" STEP_EXPRESSIONS="$expressions" \
    python3 - "$WORKFLOW" "$out_path" <<'PYEOF'
import json
import os
import re
import sys

workflow, out_path = sys.argv[1], sys.argv[2]
mapping = json.loads(os.environ["STEP_EXPRESSIONS"])
step_name = os.environ["STEP_NAME"]
lines = open(workflow, encoding="utf-8").read().splitlines()

step_re = re.compile(r"^(\s*)- name: " + re.escape(step_name) + r"\s*$")
start = indent = None
for index, line in enumerate(lines):
    match = step_re.match(line)
    if match:
        start, indent = index, len(match.group(1))
        break
if start is None:
    sys.exit("could not find step %r" % step_name)

run_index = None
for index in range(start + 1, len(lines)):
    line = lines[index]
    if line.strip() and len(line) - len(line.lstrip()) <= indent:
        break
    if re.match(r"^\s*run: \|\s*$", line):
        run_index = index
        break
if run_index is None:
    sys.exit("step %r has no run body" % step_name)

run_indent = len(lines[run_index]) - len(lines[run_index].lstrip())
body = []
for line in lines[run_index + 1:]:
    if line.strip() and len(line) - len(line.lstrip()) <= run_indent:
        break
    body.append(line)
pad = min(len(line) - len(line.lstrip()) for line in body if line.strip())
text = "\n".join(line[pad:] if line.strip() else "" for line in body)

unknown = []
def substitute(match):
    expression = match.group(1).strip()
    if expression not in mapping:
        unknown.append(expression)
        return ""
    return mapping[expression]

text = re.sub(r"\$\{\{(.*?)\}\}", substitute, text)
if unknown:
    sys.exit("unmapped workflow expression(s): %s" % ", ".join(sorted(set(unknown))))
open(out_path, "w", encoding="utf-8").write(text + "\n")
PYEOF
}

classify_expressions() {
  local first="$1" retry="$2"
  cat <<JSONEOF
{
  "steps.journey.outcome": "$first",
  "steps.journey_retry.outcome": "$retry",
  "steps.journey.conclusion": "$first",
  "steps.journey_retry.conclusion": "$retry",
  "steps.journey_summary.outputs.first_timeout": "false",
  "steps.journey_summary.outputs.first_failure": "false",
  "steps.journey_retry_budget.outputs.retry_allowed": "true",
  "steps.journey_retry_budget.outputs.retry_reason": "sufficient_remaining_budget",
  "steps.journey_retry_budget.outputs.retry_remaining_ms": "6000000",
  "steps.journey_retry_budget.outputs.retry_required_ms": "5400000",
  "steps.journey_retry_budget.outputs.retry_cost_model": "worst_case",
  "steps.journey_retry_budget.outputs.retry_shortfall_ms": "0",
  "steps.journey_retry_budget.outputs.retry_warm_build_deducted_ms": "0"
}
JSONEOF
}

CLASSIFY_OUT="" CLASSIFY_RC=0 CLASSIFY_TOKEN="" CLASSIFY_REASON=""
run_classify() {
  local name="$1" first="$2" retry="$3"
  local ws="$SANDBOX/$name" body="$SANDBOX/$name-classifier.sh"
  mkdir -p "$ws/artifacts/ci-journey-shard-verdict"
  ln -s "$SCRIPT_DIR" "$ws/scripts"
  extract_step_body "Classify emulator-journey result (infra-abort vs test-failure)" \
    "$body" "$(classify_expressions "$first" "$retry")" \
    || fail "could not extract the real classifier body"

  # Model #1809's at-job-start seed. The classifier must replace it with a
  # current, reasoned token even on the pre-journey failure path.
  printf 'INFRA\nverdict_reason=preseed_before_classify\n' \
    > "$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
  : > "$ws/github-output.txt"
  set +e
  CLASSIFY_OUT="$(cd "$ws" && \
    GITHUB_RUN_ID=30659266867 GITHUB_RUN_ATTEMPT=4 \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX=2 \
    GITHUB_OUTPUT="$ws/github-output.txt" \
    bash --noprofile --norc -eo pipefail "$body" 2>&1)"
  CLASSIFY_RC=$?
  set -e
  CLASSIFY_TOKEN="$(head -n 1 "$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt")"
  CLASSIFY_REASON="$(sed -n 's/^verdict_reason=//p' "$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt")"
  CLASSIFY_FILE="$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
}

write_token() {
  local dir="$1" shard="$2" token="$3"
  mkdir -p "$dir/emulator-journey-verdict-shard-$shard"
  printf '%s\n' "$token" > "$dir/emulator-journey-verdict-shard-$shard/shard-verdict.txt"
}

AGG_OUT="" AGG_RC=0 AGG_VERDICT=""
run_aggregate() {
  local dir="$1" upstream="$2"
  set +e
  AGG_OUT="$(EXPECTED_SHARDS=3 UPSTREAM_MATRIX_RESULT="$upstream" GITHUB_STEP_SUMMARY="" \
    bash "$AGG" "$dir" 2>&1)"
  AGG_RC=$?
  set -e
  AGG_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$AGG_OUT" | tail -n 1)"
}

echo "== #1913 pre-journey phase classification =="
run_classify skipped skipped skipped

# Complete the exact run-30659266867 chain before asserting: on the buggy base,
# the real classifier writes INFRA/emulator_never_booted and the real aggregate
# ignores the failed upstream matrix, yielding green RE-RUN. Keeping both
# observations in one failure makes the red proof cover the whole blind spot.
d="$SANDBOX/skipped-token-plus-clean"; mkdir -p "$d"
cp "$CLASSIFY_FILE" "$d/shard-verdict-2.txt"
write_token "$d" 0 CLEAN; write_token "$d" 1 CLEAN
run_aggregate "$d" failure
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_REASON" == "pre_journey_setup_failure" && "$CLASSIFY_RC" -ne 0 \
    && "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT" "$AGG_OUT"; fail "skipped journey expected classifier RED/pre_journey_setup_failure and aggregate RED; got classifier $CLASSIFY_TOKEN/$CLASSIFY_REASON/exit$CLASSIFY_RC -> aggregate $AGG_VERDICT/exit$AGG_RC"; }
grep -qx 'shard=2' "$CLASSIFY_FILE" || fail "classifier token lost shard provenance"
grep -qx 'run_id=30659266867' "$CLASSIFY_FILE" || fail "classifier token lost run provenance"
grep -qx 'run_attempt=4' "$CLASSIFY_FILE" || fail "classifier token lost rerun-attempt provenance"
grep -qx 'verdict_reason=preseed_before_classify' "$CLASSIFY_FILE" \
  && fail "classifier left the #1809 pre-seed in place"
pass "skipped first/retry -> RED pre_journey_setup_failure -> aggregate RED, with current provenance"

echo
echo "== #1913 upstream-matrix aggregate backstop =="
d="$SANDBOX/upstream-failed-all-infra"; mkdir -p "$d"
write_token "$d" 0 INFRA; write_token "$d" 1 INFRA; write_token "$d" 2 INFRA
run_aggregate "$d" failure
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "failed upstream without any RED token must fail closed to aggregate RED"; }
grep -q 'upstream' <<<"$AGG_OUT" || fail "aggregate mismatch error must name the upstream result"
pass "failed upstream + no RED token -> aggregate RED mismatch (run 30659266867 shape)"

echo
echo "== #470/#771 environmental controls =="
run_classify never-booted failure failure
[[ "$CLASSIFY_TOKEN" == "INFRA" && "$CLASSIFY_REASON" == "emulator_never_booted" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "attempted failure/no-summary must remain #771 INFRA"; }
d="$SANDBOX/genuine-infra"; mkdir -p "$d"
cp "$CLASSIFY_FILE" "$d/shard-verdict-2.txt"
write_token "$d" 0 CLEAN; write_token "$d" 1 CLEAN
run_aggregate "$d" success
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "successful upstream + #771 INFRA must remain green RE-RUN"; }
pass "attempted/no-summary #771 INFRA + successful upstream -> green RE-RUN"

run_classify cancelled cancelled cancelled
[[ "$CLASSIFY_TOKEN" == "INFRA" && "$CLASSIFY_REASON" == "attempt_cancelled" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "cancelled attempt must remain #470 INFRA"; }
pass "cancelled #470 attempt remains INFRA attempt_cancelled"

# Literal GitHub expression: shell expansion here would make the pin meaningless.
# shellcheck disable=SC2016
grep -Fq 'UPSTREAM_MATRIX_RESULT: ${{ needs.emulator-journey.result }}' "$WORKFLOW" \
  || fail "workflow aggregate step must pass needs.emulator-journey.result to the reducer"
grep -Fq 'scripts/test-ci-journey-pre-journey-classification.sh' "$WORKFLOW" \
  || fail "the #1913 regression must be wired into the Unit job"
pass "upstream matrix result and this cheap guard are wired in tests.yml"

echo
echo "PASS: #1913 real classifier + aggregate phase-aware verdict guard"
