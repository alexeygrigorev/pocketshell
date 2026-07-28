#!/usr/bin/env bash
# Issue #1827: every condition that turns the per-push emulator-journey suite RED
# must leave evidence the workflow classifier can read.
#
# THE DEFECT (reproduced end to end below)
# ---------------------------------------
# `scripts/ci-journey-summary-functions.sh` kept the core-terminal proofs in TWO
# hand-maintained lists: the suite's red/green condition, and the summary's
# "Failed BOTH attempts" header condition + bullets. They drifted.
# `SURFACE_REPAINT_STATUS` (#1203) and `SHELL_SNAPSHOT_STATUS` (#1233) reddened
# the suite but appeared in NEITHER half of the evidence section. So a run where
# only one of them failed twice wrote NO failed-both section at all; the
# workflow's `Inspect first journey summary` step then computed
# `first_failure=false`, the classify step fell through every RED branch, and the
# shard was typed `EMULATOR INFRA UNAVAILABLE` — INFRA, shard job green, run
# reported successful. A genuine failure laundered into a green.
#
# That is the same outcome #1822 closed, reached through the WRITER instead of
# the parser, and #1822's fix cannot catch it: its unreadable-item fail-safe only
# fires on a list item it cannot parse INSIDE a section, and here there is no
# section and no item.
#
# WHAT THIS TEST DRIVES (no reasoning, no re-implementation)
# ---------------------------------------------------------
#   real suite (scripts/ci-journey-suite.sh, stubbed gradle/adb)
#     -> real summary writer (finish_ci_journey_suite)
#       -> the REAL `Inspect first journey summary` step body, extracted from
#          .github/workflows/tests.yml and executed
#         -> the REAL `Classify emulator-journey result` step body, likewise
#           -> the real shard-verdict token + the real aggregate reducer
#
# The per-proof matrix is generated FROM the CORE_TERMINAL_PROOFS registry, so a
# proof added to the suite is automatically covered here. Each entry is run
# twice: against the real writer (must be RED) and against a per-entry MUTANT
# that models the pre-#1827 writer for exactly that entry — the red condition
# still sees the FAIL, the evidence section omits it. The mutant must reproduce
# the reported INFRA green, which is what proves the RED is driven by the
# registry entry and not by the fixture being red about everything.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"
SUITE="$SCRIPT_DIR/ci-journey-suite.sh"
CORE_TERMINAL_FN="$SCRIPT_DIR/ci-journey-core-terminal-functions.sh"
SUMMARY_FN="$SCRIPT_DIR/ci-journey-summary-functions.sh"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$WORKFLOW" "$SUITE" "$CORE_TERMINAL_FN" "$SUMMARY_FN" "$AGG"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# The registry under test, read from the real helper.
# shellcheck source=scripts/ci-journey-core-terminal-functions.sh
source "$CORE_TERMINAL_FN"
[[ "${#CORE_TERMINAL_PROOFS[@]}" -ge 9 ]] \
  || fail "CORE_TERMINAL_PROOFS has only ${#CORE_TERMINAL_PROOFS[@]} entries — the registry is missing proofs (issue #1827)"

echo "== the registry both halves read =="
for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
  IFS='|' read -r status_var class_var label <<<"$entry"
  [[ -n "$status_var" && -n "$class_var" && -n "$label" ]] \
    || fail "malformed CORE_TERMINAL_PROOFS entry: $entry"
  [[ -n "${!status_var:-}" ]] || fail "$status_var is not initialised by the core-terminal helper"
  [[ -n "${!class_var:-}" ]]  || fail "$class_var is not initialised by the core-terminal helper"
  echo "    $status_var -> ${!class_var}"
done
pass "${#CORE_TERMINAL_PROOFS[@]} registered proofs, each with a status var and a class var"

# ---------------------------------------------------------------------------
# (a) THE ANTI-DRIFT GUARD. Two mechanical checks that the two lists cannot come
#     apart again:
#       a1 — every `*_STATUS` the SUITE can set to FAIL/SKIPPED is registered.
#       a2 — the summary writer names no `*_STATUS` variable directly; it can
#            only reach them through the registry, so a status that is not in
#            the registry cannot appear in either half of the writer.
# ---------------------------------------------------------------------------
echo
echo "== (a) anti-drift: the red condition and the evidence section read ONE list =="

registered_status_vars=()
for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
  registered_status_vars+=("${entry%%|*}")
done
is_registered() {
  local needle="$1" v
  for v in "${registered_status_vars[@]}"; do [[ "$v" == "$needle" ]] && return 0; done
  return 1
}

mapfile -t suite_status_vars < <(
  grep -oE '^[[:space:]]*[A-Z0-9_]+_STATUS="(FAIL|SKIPPED)"' "$SUITE" \
    | grep -oE '[A-Z0-9_]+_STATUS' | sort -u
)
[[ "${#suite_status_vars[@]}" -ge 9 ]] \
  || fail "(a1) found only ${#suite_status_vars[@]} FAIL/SKIPPED status assignments in the suite — the scan is broken, not the suite"
for v in "${suite_status_vars[@]}"; do
  is_registered "$v" \
    || fail "(a1) $SUITE sets $v to FAIL/SKIPPED but it is NOT in CORE_TERMINAL_PROOFS — it would redden the suite while writing no failed-both evidence, which is exactly issue #1827"
done
pass "(a1) all ${#suite_status_vars[@]} suite-set proof statuses are registered: ${suite_status_vars[*]}"

# a2: the writer must not name a status variable outside the registry loop.
# `${entry%%|*}` / `${!status_var}` are indirections, not names, so a literal
# `FOO_STATUS` token in the writer means a second hand-maintained list is back.
stray="$(grep -nE '\$\{?[A-Z0-9_]+_STATUS' "$SUMMARY_FN" \
  | grep -vE 'JOURNEY_WARM_BUILD_STATUS' || true)"
[[ -z "$stray" ]] \
  || { printf '%s\n' "$stray"; fail "(a2) $SUMMARY_FN references a *_STATUS variable by name — the evidence section is a second hand-maintained list again (issue #1827)"; }
pass "(a2) the summary writer reaches proof statuses only through CORE_TERMINAL_PROOFS"

# ---------------------------------------------------------------------------
# Workflow step extraction (the #1809/#1814/#1822 method): pull a step's real
# `run:` block out of tests.yml, substitute its `${{ }}` expressions from an
# explicit map, and execute it. An UNMAPPED expression is a HARD failure — a
# silently blanked expression would make this harness lie.
# ---------------------------------------------------------------------------
extract_step_body() {
  local step_name="$1" out_path="$2" expressions="$3"
  CLASSIFY_EXPRESSIONS="$expressions" STEP_NAME="$step_name" \
    python3 - "$WORKFLOW" "$out_path" <<'PYEOF'
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
# summary" step over the workspace's artifacts. Sets FIRST_FAILURE /
# FIRST_TIMEOUT / FIRST_ELAPSED from the step's own $GITHUB_OUTPUT.
FIRST_FAILURE=""; FIRST_TIMEOUT=""; FIRST_ELAPSED=""
run_journey_summary_step() {
  local ws="$1"
  local body="$ws/journey-summary-step-body.sh"
  extract_step_body "Inspect first journey summary" "$body" '{}' \
    || fail "could not extract the 'Inspect first journey summary' step body from $WORKFLOW"
  : > "$ws/journey-summary-output.txt"
  ( cd "$ws" && GITHUB_OUTPUT="$ws/journey-summary-output.txt" \
      bash --noprofile --norc -eo pipefail "$body" ) > "$ws/journey-summary-step.log" 2>&1
  FIRST_FAILURE="$(sed -n 's/^first_failure=//p' "$ws/journey-summary-output.txt" | tail -n 1)"
  FIRST_TIMEOUT="$(sed -n 's/^first_timeout=//p' "$ws/journey-summary-output.txt" | tail -n 1)"
  FIRST_ELAPSED="$(sed -n 's/^first_suite_elapsed_secs=//p' "$ws/journey-summary-output.txt" | tail -n 1)"
}

# classify_expressions <journey-outcome> <first_timeout> <first_failure>
classify_expressions() {
  cat <<JSONEOF
{
  "steps.journey.outcome": "$1",
  "steps.journey_retry.outcome": "failure",
  "steps.journey.conclusion": "$1",
  "steps.journey_retry.conclusion": "failure",
  "steps.journey_summary.outputs.first_timeout": "$2",
  "steps.journey_summary.outputs.first_failure": "$3",
  "steps.journey_retry_budget.outputs.retry_allowed": "true",
  "steps.journey_retry_budget.outputs.retry_reason": "sufficient_remaining_budget",
  "steps.journey_retry_budget.outputs.retry_remaining_ms": "3112241",
  "steps.journey_retry_budget.outputs.retry_required_ms": "3028613",
  "steps.journey_retry_budget.outputs.retry_cost_model": "measured_first_attempt"
}
JSONEOF
}

CLASSIFY_OUT=""; CLASSIFY_RC=0; CLASSIFY_TOKEN=""; CLASSIFY_GH_OUTPUT=""
run_classify_step() {
  local ws="$1" expressions="$2"
  local body="$ws/classify-step-body.sh"
  extract_step_body "Classify emulator-journey result" "$body" "$expressions" \
    || fail "could not extract+substitute the classify step body from $WORKFLOW. If the step gained a new \${{ }} expression, add it to classify_expressions() here — a silently blanked expression would make this harness lie."
  : > "$ws/gh-output.txt"
  local token_file="$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
  rm -f "$token_file"
  mkdir -p "$(dirname "$token_file")"
  # GitHub's default Linux `run:` shell is `bash --noprofile --norc -eo pipefail`.
  CLASSIFY_OUT="$(cd "$ws" && \
    SHARD_VERDICT_FILE="$token_file" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX=1 \
    GITHUB_RUN_ID=30334306297 \
    GITHUB_RUN_ATTEMPT=1 \
    GITHUB_OUTPUT="$ws/gh-output.txt" \
    bash --noprofile --norc -eo pipefail "$body" 2>&1)"
  CLASSIFY_RC=$?
  CLASSIFY_TOKEN="$(head -n 1 "$token_file" 2>/dev/null || true)"
  CLASSIFY_GH_OUTPUT="$(cat "$ws/gh-output.txt" 2>/dev/null || true)"
}

AGG_OUT=""; AGG_RC=0; AGG_VERDICT=""
write_shard_token() {
  local dir="$1" idx="$2" token="$3"
  mkdir -p "$dir/emulator-journey-verdict-shard-$idx"
  printf '%s\n' "$token" > "$dir/emulator-journey-verdict-shard-$idx/shard-verdict.txt"
}
run_agg() {
  AGG_OUT="$(EXPECTED_SHARDS=3 GITHUB_STEP_SUMMARY="" bash "$AGG" "$1" 2>&1)"
  AGG_RC=$?
  AGG_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$AGG_OUT" | tail -n 1)"
}

# aggregate_with <token> — this shard's token alongside two CLEAN siblings.
aggregate_with() {
  local dir="$SANDBOX/verdicts-$RANDOM"
  rm -rf "$dir"; mkdir -p "$dir"
  write_shard_token "$dir" 0 CLEAN
  write_shard_token "$dir" 1 "$1"
  write_shard_token "$dir" 2 CLEAN
  run_agg "$dir"
}

# ---------------------------------------------------------------------------
# The sandbox "repo": the REAL suite + helpers, plus stub gradle/adb/connected-
# test. The suite derives REPO_ROOT from BASH_SOURCE, so running the copy makes
# the sandbox the repo — and the workflow step bodies then run with the same
# directory as their workspace, over the artifacts the suite actually wrote.
# ---------------------------------------------------------------------------
STUBBIN="$SANDBOX/stubbin"
mkdir -p "$STUBBIN"
cat > "$STUBBIN/adb" <<'STUB'
#!/usr/bin/env bash
set -u
emit_valid_png() {
  printf '\211\120\116\107\015\012\032\012\000\000\000\015\111\110\104\122\000\000\000\001\000\000\000\001\010\006\000\000\000\037\025\304\211\000\000\000\015\111\104\101\124\170\234\143\140\140\140\370\017\000\001\004\001\000\137\345\303\113\000\000\000\000\111\105\116\104\256\102\140\202'
}
if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\nemulator-5554\tdevice\n'
  exit 0
fi
if [[ "${1:-}" == "-s" ]]; then shift 2; fi
case "${1:-}" in
  logcat)  [[ "${2:-}" == "-c" ]] || printf 'stub-logcat\n' ;;
  exec-out) emit_valid_png ;;
  shell)
    case "${2:-}" in
      ps) printf 'PID NAME\n' ;;
      dumpsys) printf 'stub dumpsys\n' ;;
    esac
    ;;
esac
exit 0
STUB
chmod +x "$STUBBIN/adb"

# make_workspace <dir> — real scripts + stubs. `SUMMARY_MUTANT_OMIT`, when set,
# appends the pre-#1827 writer for exactly one registered status (see below).
make_workspace() {
  local root="$1" omit="${2:-}"
  rm -rf "$root"
  mkdir -p "$root"
  cp -a "$REPO_ROOT/scripts" "$root/scripts"
  chmod +x "$root/scripts/ci-journey-suite.sh"

  if [[ -n "$omit" ]]; then
    # THE RED CONTROL. This is the pre-#1827 writer for ONE proof: the suite's
    # red condition still sees its FAIL (so the suite still exits non-zero), but
    # the evidence section omits it — exactly the shape #1203/#1233 were in.
    cat >> "$root/scripts/ci-journey-summary-functions.sh" <<MUTANTEOF

# ---- TEST-ONLY MUTANT (issue #1827 red control): evidence section omits $omit
ci_journey_core_terminal_failed_bullets() {
  local entry status_var class_var label
  for entry in "\${CORE_TERMINAL_PROOFS[@]}"; do
    IFS='|' read -r status_var class_var label <<<"\$entry"
    [[ "\$status_var" == "$omit" ]] && continue
    [[ "\${!status_var}" == "FAIL" ]] || continue
    echo "- \\\`\${!class_var}\\\` (\${label#Core-terminal })"
  done
}
ci_journey_core_terminal_any_failed() {
  local entry status_var
  for entry in "\${CORE_TERMINAL_PROOFS[@]}"; do
    status_var="\${entry%%|*}"
    [[ "\$status_var" == "$omit" ]] && continue
    [[ "\${!status_var}" == "FAIL" ]] && return 0
  done
  return 1
}
ci_journey_assert_red_has_evidence() { :; }
MUTANTEOF
  fi

  # Gradle stub. Fails ONLY the invocation naming \$JOURNEY_STUB_FAIL_CLASS, and
  # writes real JUnit XML so the #1800 signature classifier has evidence.
  cat > "$root/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u
if [[ "${1:-}" == "--stop" ]]; then exit 0; fi

module_build_root() {
  if [[ "$*" == *":shared:core-terminal:"* ]]; then
    printf '%s\n' "$PWD/shared/core-terminal/build"
  else
    printf '%s\n' "$PWD/app/build"
  fi
}
write_result_xml() {
  local outcome="$1" classname="$2" message="$3" root
  shift 3
  root="$(module_build_root "$@")/outputs/androidTest-results/connected/debug"
  mkdir -p "$root"
  if [[ "$outcome" == failure ]]; then
    cat > "$root/TEST-stub.xml" <<XML
<testsuite tests="1" failures="1">
  <testcase classname="$classname" name="proofMethod"><failure message="$message"/></testcase>
</testsuite>
XML
  else
    cat > "$root/TEST-stub.xml" <<XML
<testsuite tests="1" failures="0">
  <testcase classname="$classname" name="proofMethod"/>
</testsuite>
XML
  fi
}
selector_class() {
  local sel="$1"
  sel="${sel#*class=}"
  sel="${sel%% *}"
  printf '%s\n' "${sel%%#*}"
}

echo "> Task :app:preBuild"
echo "> Task :app:compileDebugKotlin UP-TO-DATE"

case "$*" in
  *connectedDebugAndroidTest*)
    echo "> Task :connectedDebugAndroidTest"
    echo "Starting 1 tests on emulator-5554"
    target="${JOURNEY_STUB_FAIL_CLASS:-}"
    cls="$(selector_class "$*")"
    if [[ -n "$target" && "$*" == *"$target"* ]]; then
      write_result_xml failure "${target%%#*}" \
        "expected the surface to paint content, but the black fallback painted" "$@"
      echo "Finished 1 tests on emulator-5554"
      exit 1
    fi
    write_result_xml pass "$cls" "" "$@"
    echo "Finished 1 tests on emulator-5554"
    ;;
esac
exit 0
STUB
  chmod +x "$root/gradlew"

  cat > "$root/scripts/connected-test.sh" <<'STUB'
#!/usr/bin/env bash
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$root_dir/gradlew" :app:connectedDebugAndroidTest "$@"
STUB
  chmod +x "$root/scripts/connected-test.sh"
}

# run_real_suite <workspace> [env assignments...] — the REAL suite. A large shard
# total keeps the journey-class loop to a single class so the run is cheap; the
# core-terminal proofs are NOT sharded and all run on every shard, which is the
# part under test.
SUITE_RC=0
run_real_suite() {
  local ws="$1"; shift
  env PATH="$STUBBIN:$PATH" \
    JOURNEY_STEP_BUDGET_SECS="${JOURNEY_STEP_BUDGET_SECS_OVERRIDE:-900}" \
    JOURNEY_CLASS_TIMEOUT_SECS=30 \
    JOURNEY_NO_OUTPUT_TIMEOUT_SECS=25 \
    JOURNEY_CLASS_KILL_AFTER_SECS=1 \
    JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
    POCKETSHELL_JOURNEY_CI_SHARD_TOTAL=400 \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX=0 \
    "$@" \
    bash "$ws/scripts/ci-journey-suite.sh" > "$ws/suite.log" 2>&1
  SUITE_RC=$?
  # The workflow's "Preserve first journey attempt diagnostics" step snapshots
  # the first attempt before a retry can overwrite it; the classify step reads
  # both. Reproduce that layout.
  rm -rf "$ws/artifacts/ci-journey-attempt-1"
  mkdir -p "$ws/artifacts/ci-journey-attempt-1"
  cp -a "$ws/artifacts/ci-journey" "$ws/artifacts/ci-journey-attempt-1/ci-journey"
}

# ---------------------------------------------------------------------------
# (b) THE HEADLINE MATRIX — for EVERY registered proof, a suite where only that
#     proof fails twice must classify RED through the real workflow step bodies;
#     the per-entry pre-#1827 mutant must reproduce the reported INFRA green.
# ---------------------------------------------------------------------------
echo
echo "== (b) every suite-reddening core-terminal proof produces failed-both evidence =="

for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
  IFS='|' read -r status_var class_var label <<<"$entry"
  selector="${!class_var}"
  bare_class="${selector%%#*}"

  # --- RED CONTROL: the pre-#1827 writer for this one entry.
  mut="$SANDBOX/mutant-$status_var"
  make_workspace "$mut" "$status_var"
  run_real_suite "$mut" JOURNEY_STUB_FAIL_CLASS="$selector"
  [[ "$SUITE_RC" -ne 0 ]] \
    || { sed -n '1,40p' "$mut/suite.log"; fail "(b/$status_var) the red control did not redden the suite — the fixture proves nothing"; }
  grep -qE 'Failed BOTH attempts' "$mut/artifacts/ci-journey/summary.md" \
    && { cat "$mut/artifacts/ci-journey/summary.md"; fail "(b/$status_var) the red control still wrote a failed-both section — the control is contaminated"; }
  run_journey_summary_step "$mut"
  [[ "$FIRST_FAILURE" == "false" ]] \
    || fail "(b/$status_var) red control: the real journey_summary step reported first_failure=$FIRST_FAILURE, expected false"
  run_classify_step "$mut" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
  [[ "$CLASSIFY_TOKEN" == "INFRA" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "(b/$status_var) red control: expected the reported INFRA laundering, got '$CLASSIFY_TOKEN'"; }
  grep -q 'EMULATOR INFRA UNAVAILABLE' <<<"$CLASSIFY_OUT" \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "(b/$status_var) red control: expected the #771 infra-abort branch"; }
  aggregate_with "$CLASSIFY_TOKEN"
  [[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
    || { printf '%s\n' "$AGG_OUT"; fail "(b/$status_var) red control: expected the neutral-green RE-RUN, got $AGG_VERDICT/exit$AGG_RC"; }

  # --- GREEN: the real writer.
  ws="$SANDBOX/fixed-$status_var"
  make_workspace "$ws"
  run_real_suite "$ws" JOURNEY_STUB_FAIL_CLASS="$selector"
  [[ "$SUITE_RC" -ne 0 ]] \
    || { sed -n '1,40p' "$ws/suite.log"; fail "(b/$status_var) the suite exited 0 with a proof failed twice"; }
  summary="$ws/artifacts/ci-journey/summary.md"
  grep -qE 'Failed BOTH attempts' "$summary" \
    || { cat "$summary"; fail "(b/$status_var) no failed-both section was written for a proof that reddened the suite (issue #1827)"; }
  mapfile -t bullets < <(awk '/Failed BOTH attempts/{f=1; next} f && /^- /{print}' "$summary")
  # The issue's exact scenario is ONE proof failing twice — if the fixture
  # reddened several, "a section was written" would not be attributable to the
  # proof under test (G6).
  [[ "${#bullets[@]}" -eq 1 ]] \
    || { cat "$summary"; fail "(b/$status_var) expected exactly ONE failed-both bullet (only this proof failed), got ${#bullets[@]}: ${bullets[*]}"; }
  grep -qF "$selector" <<<"${bullets[0]}" \
    || { cat "$summary"; fail "(b/$status_var) the failed-both bullet is '${bullets[0]}', which does not name $selector"; }
  run_journey_summary_step "$ws"
  [[ "$FIRST_FAILURE" == "true" ]] \
    || fail "(b/$status_var) the real journey_summary step reported first_failure=$FIRST_FAILURE, expected true"
  run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
  [[ "$CLASSIFY_TOKEN" == "RED" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "(b/$status_var) the real classify body wrote '$CLASSIFY_TOKEN', not RED — the failure would be laundered into a green"; }
  [[ "$CLASSIFY_RC" -ne 0 ]] || fail "(b/$status_var) a RED classify body must exit non-zero"
  grep -q '^shard_verdict=RED$' <<<"$CLASSIFY_GH_OUTPUT" \
    || { printf '%s\n' "$CLASSIFY_GH_OUTPUT"; fail "(b/$status_var) the step must export shard_verdict=RED so the #1809 shard RED gate fires"; }
  grep -q '::error' <<<"$CLASSIFY_OUT" \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "(b/$status_var) a genuine failure must emit ::error"; }
  grep -q 'EMULATOR INFRA UNAVAILABLE' <<<"$CLASSIFY_OUT" \
    && { printf '%s\n' "$CLASSIFY_OUT"; fail "(b/$status_var) the infra-abort branch must not fire"; }
  aggregate_with "$CLASSIFY_TOKEN"
  [[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
    || { printf '%s\n' "$AGG_OUT"; fail "(b/$status_var) expected aggregate RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }

  pass "(b/$status_var) pre-#1827 writer -> INFRA / RE-RUN / exit 0   |   real writer -> RED / RED / exit 1   [$bare_class]"
done

# ---------------------------------------------------------------------------
# (c) The OTHER suite-reddening conditions, each through the same real path.
# ---------------------------------------------------------------------------
echo
echo "== (c) the non-proof reddening conditions still carry their evidence =="

# (c1) A journey CLASS that fails both attempts.
ws="$SANDBOX/journey-class"
make_workspace "$ws"
first_class="$(sed -n 's/^  "\(com\.[A-Za-z0-9_.$#]*\)".*/\1/p;s/^  "\$FQCN_PREFIX\.\([A-Za-z0-9_.$#]*\)".*/com.pocketshell.app.proof.\1/p' "$SUITE" | head -n 1)"
[[ -n "$first_class" ]] || fail "(c1) could not read the first journey class out of $SUITE"
run_real_suite "$ws" JOURNEY_STUB_FAIL_CLASS="$first_class"
[[ "$SUITE_RC" -ne 0 ]] || fail "(c1) a journey class failing both attempts must redden the suite"
grep -qE 'Failed BOTH attempts' "$ws/artifacts/ci-journey/summary.md" \
  || { cat "$ws/artifacts/ci-journey/summary.md"; fail "(c1) no failed-both section for a failed journey class"; }
run_journey_summary_step "$ws"
[[ "$FIRST_FAILURE" == "true" ]] || fail "(c1) first_failure=$FIRST_FAILURE, expected true"
run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(c1) expected RED/exit!=0, got '$CLASSIFY_TOKEN'/exit$CLASSIFY_RC"; }
pass "(c1) FAILED_CLASSES -> failed-both section -> first_failure=true -> RED [$first_class]"

# (c2) The #835 suite-budget timeout: its own JOURNEY_STEP_TIMEOUT evidence.
#
# DETERMINISM (issue #1839). This case used to drive the real suite with a
# ONE-SECOND budget and rely on bash's INTEGER `$SECONDS` having ticked before
# the suite's first `budget_exhausted` check. Whether that tick lands is a
# property of the MACHINE, not of the budget logic:
#
#   budget=1s elapsed=0s remaining=1s => NOT exhausted -> suite exits 0 -> FAIL
#   budget=1s elapsed=1s remaining=0s => EXHAUSTED     -> suite RED     -> pass
#
# and `run_real_suite` pins POCKETSHELL_JOURNEY_CI_SHARD_TOTAL=400 so exactly ONE
# class is selected — there is no second loop iteration to catch the budget
# later, making a single missed tick terminal. The hosted runner lost that race
# 2/2 (red `main` @ ae368467) while the dev box won it 5/5 on the identical
# commit. A guard whose whole purpose is to stop the gate reporting false greens
# was itself flaky-by-construction.
#
# The budget clock is now a pinnable seam (`JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE`,
# scripts/ci-journey-budget-functions.sh), so elapsed time is INJECTED rather
# than raced. The budget is NOT widened — widening only narrows the same race
# (G6). `budget_exhausted` is monotone in elapsed, so pinning BOTH extremes plus
# the exact boundary covers the whole distribution — strictly stronger than N
# sampled runs. The complementary "budget burns down over real wall time and
# trips mid-loop" shape is covered end-to-end by scripts/test-ci-journey-budget.sh,
# whose stub gradle `sleep`s are a hard FLOOR (faster hardware cannot lose it).
BUDGET_CLOCK_BUDGET_SECS=900

# run_budget_clock_arm <workspace> <pinned-elapsed-secs>
run_budget_clock_arm() {
  local ws="$1" elapsed="$2"
  make_workspace "$ws"
  JOURNEY_STEP_BUDGET_SECS_OVERRIDE="$BUDGET_CLOCK_BUDGET_SECS" \
    run_real_suite "$ws" JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE="$elapsed"
}

# (c2a) elapsed = 0 — the MINIMUM extreme, i.e. the "no tick landed" arm the
#       hosted runner deterministically hit. The budget is NOT spent, so the
#       suite must stay green and write NO timeout evidence. This turns the
#       losing side of the old coin flip into an explicit assertion, and it is
#       what makes (c2b)/(c2c) discriminating rather than vacuous (G6).
ws="$SANDBOX/budget-clock-unexhausted"
run_budget_clock_arm "$ws" 0
[[ "$SUITE_RC" -eq 0 ]] \
  || { sed -n '1,40p' "$ws/suite.log"; fail "(c2a) elapsed=0 of a ${BUDGET_CLOCK_BUDGET_SECS}s budget is NOT exhausted, but the suite exited $SUITE_RC"; }
grep -qE 'JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' "$ws/artifacts/ci-journey/summary.md" \
  && { cat "$ws/artifacts/ci-journey/summary.md"; fail "(c2a) an unspent budget must write NO timeout evidence"; }
run_journey_summary_step "$ws"
[[ "$FIRST_TIMEOUT" == "false" ]] || fail "(c2a) first_timeout=$FIRST_TIMEOUT, expected false"
pass "(c2a) pinned elapsed=0s of ${BUDGET_CLOCK_BUDGET_SECS}s -> not exhausted -> green, no timeout evidence"

# (c2b) elapsed = budget — the EXACT exhaustion boundary (`remaining <= 0`), and
#       (c2c) elapsed > budget — beyond it, through the negative-clamp. Both must
#       produce the #835 hard RED with classifier-readable evidence.
for arm in "boundary:$BUDGET_CLOCK_BUDGET_SECS:c2b" \
           "beyond:$((BUDGET_CLOCK_BUDGET_SECS + 1)):c2c"; do
  IFS=':' read -r arm_name arm_elapsed arm_id <<<"$arm"
  ws="$SANDBOX/budget-clock-$arm_name"
  run_budget_clock_arm "$ws" "$arm_elapsed"
  [[ "$SUITE_RC" -ne 0 ]] \
    || { sed -n '1,40p' "$ws/suite.log"; fail "($arm_id) an exhausted suite budget must redden the suite (pinned elapsed=${arm_elapsed}s of ${BUDGET_CLOCK_BUDGET_SECS}s)"; }
  grep -qE 'JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' "$ws/artifacts/ci-journey/summary.md" \
    || { cat "$ws/artifacts/ci-journey/summary.md"; fail "($arm_id) no JOURNEY_STEP_TIMEOUT evidence for a budget timeout"; }
  run_journey_summary_step "$ws"
  [[ "$FIRST_TIMEOUT" == "true" ]] || fail "($arm_id) first_timeout=$FIRST_TIMEOUT, expected true"
  run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
  [[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_RC" -ne 0 ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "($arm_id) a #835 budget timeout must stay a hard RED, got '$CLASSIFY_TOKEN'"; }
  aggregate_with "$CLASSIFY_TOKEN"
  [[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
    || { printf '%s\n' "$AGG_OUT"; fail "($arm_id) expected aggregate RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
  pass "($arm_id) pinned elapsed=${arm_elapsed}s of ${BUDGET_CLOCK_BUDGET_SECS}s ($arm_name) -> JOURNEY_STEP_TIMEOUT -> first_timeout=true -> RED (unchanged)"
done

# (c2d) The seam must not be able to DISABLE the budget silently: a malformed
#       pin is a hard, greppable abort, not an ignored override.
ws="$SANDBOX/budget-clock-invalid"
make_workspace "$ws"
run_real_suite "$ws" JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE=not-a-number
[[ "$SUITE_RC" -eq 2 ]] \
  || { sed -n '1,20p' "$ws/suite.log"; fail "(c2d) a malformed budget-clock pin must abort with exit 2, got $SUITE_RC"; }
grep -q 'JOURNEY_BUDGET_CLOCK_INVALID' "$ws/suite.log" \
  || { sed -n '1,20p' "$ws/suite.log"; fail "(c2d) a malformed budget-clock pin must say so greppably"; }
pass "(c2d) a malformed JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE aborts the suite instead of disabling the #835 budget"

# ---------------------------------------------------------------------------
# (d) The FAIL-SAFE backstop: a red suite whose summary carries no
#     classifier-readable evidence gets one appended, so a FUTURE reddening
#     cause outside the registry cannot repeat #1827.
# ---------------------------------------------------------------------------
echo
echo "== (d) fail-safe: a red suite always leaves classifier-readable evidence =="

ws="$SANDBOX/failsafe"
make_workspace "$ws"
# A red condition the writer knows nothing about: force a non-zero JOURNEY_EXIT
# with every registered status PASS, no failed classes, no budget timeout.
cat > "$ws/failsafe-driver.sh" <<'DRIVER'
#!/usr/bin/env bash
set -uo pipefail
ws="$1"
cd "$ws"
source "$ws/scripts/ci-journey-core-terminal-functions.sh"
source "$ws/scripts/ci-journey-summary-functions.sh"
SUITE_START=0
STEP_TIMEOUT_HIT=0
FAILED_CLASSES=(); RECOVERED_CLASSES=(); PASSED_FIRST_TRY=()
BUDGET_TIMEOUT_CLASSES=(); BUILD_PHASE_TIMEOUT_ATTEMPTS=(); BUILD_PHASE_FAILURE_ATTEMPTS=()
EFFECTIVE_JOURNEY_CLASSES=("com.example.OneClass")
JOURNEY_CI_SHARD_INDEX=1; JOURNEY_CI_SHARD_TOTAL=3
JOURNEY_WARM_BUILD_STATUS=ok; JOURNEY_WARM_BUILD_ELAPSED=12
JOURNEY_STEP_BUDGET_SECS=4200
mkdir -p "$ws/artifacts/ci-journey"
SUMMARY="$ws/artifacts/ci-journey/summary.md"
# A future reddening cause the summary body says nothing about: the writer's own
# classification is overridden after the fact, exactly as a new condition added
# to the red branch without an evidence section would behave.
finish_ci_journey_suite_original() { :; }
JOURNEY_EXIT=0
_orig_exit_guard() { :; }
# Re-declare the classification so JOURNEY_EXIT is 1 with no known cause.
ci_journey_core_terminal_all_passed() { return 1; }
ci_journey_core_terminal_none_failed() { return 0; }
finish_ci_journey_suite
DRIVER
chmod +x "$ws/failsafe-driver.sh"
# NOTE: errexit stays OFF (this harness asserts explicitly with `|| fail`); a
# stray `set -e` here aborted the run with no message at all.
bash "$ws/failsafe-driver.sh" "$ws" > "$ws/failsafe.log" 2>&1
failsafe_rc=$?
[[ "$failsafe_rc" -ne 0 ]] \
  || { cat "$ws/failsafe.log"; fail "(d) the unexplained-red fixture did not redden the suite"; }
grep -q 'JOURNEY_EVIDENCE_FAILSAFE' "$ws/failsafe.log" \
  || { cat "$ws/failsafe.log"; fail "(d) the fail-safe did not fire for a red suite with no evidence section"; }
grep -qE 'Failed BOTH attempts' "$ws/artifacts/ci-journey/summary.md" \
  || { cat "$ws/artifacts/ci-journey/summary.md"; fail "(d) the fail-safe did not append a classifier-readable section"; }
rm -rf "$ws/artifacts/ci-journey-attempt-1"
mkdir -p "$ws/artifacts/ci-journey-attempt-1"
cp -a "$ws/artifacts/ci-journey" "$ws/artifacts/ci-journey-attempt-1/ci-journey"
run_journey_summary_step "$ws"
[[ "$FIRST_FAILURE" == "true" ]] || fail "(d) first_failure=$FIRST_FAILURE, expected true"
run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(d) an unexplained red must classify RED, got '$CLASSIFY_TOKEN'"; }
pass "(d) a red suite with no known cause still writes evidence and classifies RED"

# ---------------------------------------------------------------------------
# (e) PRESERVED: #1800 / #1809 / #1814 / #1822 properties, through the same
#     real-`run:`-body path.
# ---------------------------------------------------------------------------
echo
echo "== (e) sibling properties unchanged =="

# (e1) All clean -> CLEAN / exit 0 / aggregate CLEAN; a MISSING token still
#      downgrades that same set to RE-RUN (`missing == 0` required for CLEAN).
ws="$SANDBOX/all-clean"
make_workspace "$ws"
run_real_suite "$ws"
[[ "$SUITE_RC" -eq 0 ]] \
  || { sed -n '1,40p' "$ws/suite.log"; fail "(e1) a healthy suite exited $SUITE_RC"; }
summary="$ws/artifacts/ci-journey/summary.md"
grep -qE 'Failed BOTH attempts|JOURNEY_STEP_TIMEOUT' "$summary" \
  && { cat "$summary"; fail "(e1) a healthy suite must write no failure evidence (no false red)"; }
for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
  IFS='|' read -r status_var class_var label <<<"$entry"
  grep -qF "$label (\`shared:core-terminal\`): **PASS**" "$summary" \
    || { cat "$summary"; fail "(e1) the summary lost the status line for $label"; }
done
grep -q 'Warm build (issue #1814): \*\*ok\*\*' "$summary" \
  || { cat "$summary"; fail "(e1) the #1814 warm-build line is missing from the summary"; }
run_journey_summary_step "$ws"
[[ "$FIRST_FAILURE" == "false" && "$FIRST_TIMEOUT" == "false" ]] \
  || fail "(e1) healthy summary reported first_failure=$FIRST_FAILURE first_timeout=$FIRST_TIMEOUT"
[[ "$FIRST_ELAPSED" =~ ^[0-9]+$ ]] \
  || fail "(e1) #1800 measured suite elapsed did not parse: '$FIRST_ELAPSED'"
run_classify_step "$ws" "$(classify_expressions success "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
[[ "$CLASSIFY_TOKEN" == "CLEAN" && "$CLASSIFY_RC" -eq 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(e1) expected CLEAN/exit0, got '$CLASSIFY_TOKEN'/exit$CLASSIFY_RC"; }
clean_dir="$SANDBOX/verdicts-clean"; rm -rf "$clean_dir"; mkdir -p "$clean_dir"
write_shard_token "$clean_dir" 0 CLEAN
write_shard_token "$clean_dir" 1 "$CLASSIFY_TOKEN"
write_shard_token "$clean_dir" 2 CLEAN
run_agg "$clean_dir"
[[ "$AGG_VERDICT" == "CLEAN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(e1) expected aggregate CLEAN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
rm -rf "$clean_dir/emulator-journey-verdict-shard-2"
run_agg "$clean_dir"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(e1) a missing shard token must downgrade CLEAN to RE-RUN, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(e1) #1458/#1800/#1814 preserved: all-CLEAN -> CLEAN -> aggregate CLEAN; missing token -> RE-RUN; every proof status line + the warm-build line intact"

# The remaining sibling properties (#1800 real-IME INFRA, #1822 mixed-summary
# RED, #1822 unreadable-entry RED, #1809 no-RED-with-INFRA neutral green) are
# driven over synthetic summaries because they need specific JUnit failure
# messages the suite stub cannot produce. Same real step bodies.
SATURATED_CLASS="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"
OCCLUSION_CLASS="com.pocketshell.app.tmux.TmuxShellComposerOcclusionE2eTest"
REAL_IME_MSG="The real system input-method window never became visible."

# write_synth_summary <ws> <failed-bullet...>
write_synth_summary() {
  local ws="$1"; shift
  mkdir -p "$ws/artifacts/ci-journey"
  {
    echo "# Per-push CI journey suite — summary"
    echo
    echo "| Selection | Args | Exit | Elapsed | Result |"
    echo "| --- | --- | --- | --- | --- |"
    echo "| 48 load-bearing journey classes (shard 1/3; per-class retry-once) | \`pocketshellCi=true\` | 1 | 2338s | **FAIL** |"
    echo
    echo "Warm build (issue #1814): **ok** in 12s — paid before the per-class budget clock, charged to the suite budget."
    if (( $# > 0 )); then
      echo
      echo "Failed BOTH attempts (\`JOURNEY_FAILED\` — job red):"
      local b
      for b in "$@"; do echo "- \`$b\`"; done
    fi
  } > "$ws/artifacts/ci-journey/summary.md"
}
# write_synth_xml <ws> <attempt> <classname> <method:kind>...
write_synth_xml() {
  local ws="$1" attempt="$2" classname="$3"; shift 3
  local dir="$ws/artifacts/ci-journey/class-attempts/app/synthetic-$attempt-${classname##*.}"
  mkdir -p "$dir"
  {
    echo "<testsuite tests=\"$#\" failures=\"$#\">"
    local spec method kind
    for spec in "$@"; do
      method="${spec%%:*}"; kind="${spec##*:}"
      case "$kind" in
        realime) echo "  <testcase classname=\"$classname\" name=\"$method\"><failure message=\"$REAL_IME_MSG\"/></testcase>" ;;
        *)       echo "  <testcase classname=\"$classname\" name=\"$method\"><failure message=\"androidx.compose.ui.test.ComposeTimeoutException: condition never became true\"/></testcase>" ;;
      esac
    done
    echo "</testsuite>"
  } > "$dir/TEST-synthetic.xml"
}
snapshot_attempt1() {
  local ws="$1"
  rm -rf "$ws/artifacts/ci-journey-attempt-1"
  mkdir -p "$ws/artifacts/ci-journey-attempt-1"
  cp -a "$ws/artifacts/ci-journey" "$ws/artifacts/ci-journey-attempt-1/ci-journey"
}

# (e2) #1800: a signature-only shard is still INFRA and still aggregates RE-RUN.
ws="$SANDBOX/synth-infra"; make_workspace "$ws"
write_synth_summary "$ws" "$SATURATED_CLASS"
write_synth_xml "$ws" 1 "$SATURATED_CLASS" "realImeReachability:realime"
snapshot_attempt1 "$ws"
run_journey_summary_step "$ws"
run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
[[ "$CLASSIFY_TOKEN" == "INFRA" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(e2) the real-IME precondition must stay INFRA, got '$CLASSIFY_TOKEN'"; }
grep -q '::error' <<<"$CLASSIFY_OUT" \
  && { printf '%s\n' "$CLASSIFY_OUT"; fail "(e2) a signature-only shard must not emit ::error"; }
aggregate_with "$CLASSIFY_TOKEN"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(e2) no-RED-with-INFRA must stay RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(e2) #1800/#1809 preserved: real-IME precondition -> INFRA -> RE-RUN neutral green (exit 0)"

# (e3) #1822: the mixed summary — the IME signature entry followed by a genuine
#      method-scoped failure — is still RED, and still fails its own shard job.
ws="$SANDBOX/synth-mixed"; make_workspace "$ws"
write_synth_summary "$ws" "$SATURATED_CLASS" \
  "$OCCLUSION_CLASS#shellComposerControlsAreVisibleAndReachableInBothKeyboardStates"
write_synth_xml "$ws" 1 "$SATURATED_CLASS" "realImeReachability:realime"
write_synth_xml "$ws" 2 "$OCCLUSION_CLASS" \
  "shellComposerControlsAreVisibleAndReachableInBothKeyboardStates:composetimeout"
snapshot_attempt1 "$ws"
run_journey_summary_step "$ws"
run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(e3) the #1822 mixed summary must stay RED, got '$CLASSIFY_TOKEN'/exit$CLASSIFY_RC"; }
grep -q '^shard_verdict=RED$' <<<"$CLASSIFY_GH_OUTPUT" \
  || fail "(e3) the #1809 shard RED gate key (shard_verdict=RED) is missing"
aggregate_with "$CLASSIFY_TOKEN"
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(e3) expected aggregate RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(e3) #1822 preserved: mixed summary -> RED, shard RED gate fires, aggregate RED (exit 1)"

# (e4) #1822: an unreadable failed-both entry is still RED.
ws="$SANDBOX/synth-unreadable"; make_workspace "$ws"
write_synth_summary "$ws" "$SATURATED_CLASS"
printf '%s\n' '- `9NotAnIdentifier#weird` (an entry this parser cannot read)' \
  >> "$ws/artifacts/ci-journey/summary.md"
write_synth_xml "$ws" 1 "$SATURATED_CLASS" "realImeReachability:realime"
snapshot_attempt1 "$ws"
run_journey_summary_step "$ws"
run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
[[ "$CLASSIFY_TOKEN" == "RED" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(e4) an unreadable failed-both entry must stay RED, got '$CLASSIFY_TOKEN'"; }
pass "(e4) #1822 preserved: an unreadable failed-both entry -> RED"

echo
echo "ALL TESTS PASSED"
