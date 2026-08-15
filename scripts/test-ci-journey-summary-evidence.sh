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
BUDGET_FN="$SCRIPT_DIR/ci-journey-budget-functions.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$WORKFLOW" "$SUITE" "$CORE_TERMINAL_FN" "$SUMMARY_FN" "$AGG" "$BUDGET_FN"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

journey_fixture_artifact_key() {
  local classname="$1" key suffix
  key="$(
    bash -c 'source "$1"; journey_class_artifact_key "$2"' \
      _ "$BUDGET_FN" "$classname"
  )" || fail "could not derive the production artifact key for $classname"
  suffix="${key#"$classname"--}"
  [[ "$key" == "$classname"--* && "$suffix" =~ ^[0-9a-f]{16}$ ]] \
    || fail "malformed production artifact key for $classname: $key"
  printf '%s\n' "$key"
}

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# ---------------------------------------------------------------------------
# The "exactly ONE journey class" selector (issue #1862).
#
# `run_real_suite` needs a run in which exactly ONE journey class is selected, so
# the fixture's failing class is unambiguous and the budget cases below have no
# second loop iteration. That used to be spelled `SHARD_TOTAL=400 / INDEX=0`,
# which worked only because membership was `array_index % total` — index 0 was
# the array's first entry and nothing else could land on bucket 0.
#
# Membership is now `hash(class_name) % total`, so bucket 0 of 400 is an
# arbitrary (possibly empty, possibly multi-class) set. Ask the REAL selection
# which bucket holds exactly one class, and use that bucket AND that class. The
# fixture's intent is preserved exactly and is no longer coupled to the
# partitioning arithmetic.
SOLO_SHARD_TOTAL=400
# shellcheck source=scripts/ci-journey-class-selection-functions.sh
source "$SCRIPT_DIR/ci-journey-class-selection-functions.sh"
declare -F journey_class_shard_hash > /dev/null \
  || fail "(setup) the selection helper no longer exposes journey_class_shard_hash — re-derive the single-class shard below against whatever replaced it (do NOT fall back to array position, issue #1862)"

SOLO_JOURNEY_CLASSES=()
mapfile -t SOLO_JOURNEY_CLASSES < <(
  awk '
    /^JOURNEY_CLASSES=\(/ { f = 1; next }
    /^\)/                 { f = 0 }
    f && match($0, /"[^"]+"/) {
      s = substr($0, RSTART + 1, RLENGTH - 2)
      gsub(/\$FQCN_PREFIX/, "com.pocketshell.app.proof", s)
      print s
    }
  ' "$SUITE"
)
(( ${#SOLO_JOURNEY_CLASSES[@]} > 0 )) \
  || fail "(setup) could not parse JOURNEY_CLASSES out of $SUITE"

# One O(N) pass to find a bucket holding exactly one class...
declare -A solo_bucket_count=() solo_bucket_class=()
for solo_c in "${SOLO_JOURNEY_CLASSES[@]}"; do
  solo_b=$(( $(journey_class_shard_hash "$solo_c") % SOLO_SHARD_TOTAL ))
  solo_bucket_count[$solo_b]=$(( ${solo_bucket_count[$solo_b]:-0} + 1 ))
  solo_bucket_class[$solo_b]="$solo_c"
done
# Lowest singleton bucket, NOT the first one bash's hash order happens to yield:
# associative-array iteration order is not guaranteed stable across bash builds,
# and a fixture that silently tests a different class on a different runner is
# exactly the kind of irreproducibility this suite exists to remove.
SOLO_SHARD_INDEX=""; SOLO_CLASS=""
for solo_b in $(printf '%s\n' "${!solo_bucket_count[@]}" | sort -n); do
  if (( solo_bucket_count[$solo_b] == 1 )); then
    SOLO_SHARD_INDEX="$solo_b"; SOLO_CLASS="${solo_bucket_class[$solo_b]}"
    break
  fi
done
[[ "$SOLO_SHARD_INDEX" =~ ^[0-9]+$ && -n "$SOLO_CLASS" ]] \
  || fail "(setup) no shard of $SOLO_SHARD_TOTAL holds exactly ONE journey class — the single-class fixture below would be ambiguous"

# ...then CONFIRM it against the REAL production selector, so the fixture never
# runs on a bucket the shipping code disagrees about. A mismatch is a hard fail,
# never a fallback.
JOURNEY_CLASSES=("${SOLO_JOURNEY_CLASSES[@]}")
EFFECTIVE_JOURNEY_CLASSES=()
POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$SOLO_SHARD_TOTAL" \
  POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$SOLO_SHARD_INDEX" \
  select_effective_journey_classes > /dev/null
(( ${#EFFECTIVE_JOURNEY_CLASSES[@]} == 1 )) && [[ "${EFFECTIVE_JOURNEY_CLASSES[0]}" == "$SOLO_CLASS" ]] \
  || fail "(setup) the real selector runs ${#EFFECTIVE_JOURNEY_CLASSES[@]} class(es) on shard $SOLO_SHARD_INDEX of $SOLO_SHARD_TOTAL (${EFFECTIVE_JOURNEY_CLASSES[*]:-none}), not just $SOLO_CLASS — the single-class fixture below would be ambiguous"
unset JOURNEY_CLASSES EFFECTIVE_JOURNEY_CLASSES
echo "   single-class fixture: shard $SOLO_SHARD_INDEX of $SOLO_SHARD_TOTAL selects only $SOLO_CLASS"

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
  "steps.journey_retry_budget.outputs.retry_cost_model": "measured_first_attempt",
  "steps.journey_retry_budget.outputs.retry_shortfall_ms": "0",
  "steps.journey_retry_budget.outputs.retry_warm_build_deducted_ms": "0"
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
# total keeps the journey-class loop to a single class so the run is cheap.
#
# Issue #2110: the core-terminal proofs are sharded by the SAME #1862 name hash,
# so "run the suite and every proof executes" is no longer true — each proof runs
# only on the leg its own name hashes to. Callers that need a SPECIFIC proof to
# execute set RUN_REAL_SUITE_SHARD_INDEX to that proof's owning index (derived
# from the production `journey_class_shard_index`, never hardcoded), which is
# what the (b) matrix below does. Everything else keeps the resolved single-class
# shard. Deliberately NOT done: an env knob that disables proof sharding for the
# harness — that would stop this file traversing the production selector, which
# is the exact "the guard presets the variable it exists to exercise" shape the
# repo keeps getting bitten by.
SUITE_RC=0
run_real_suite() {
  local ws="$1"; shift
  env PATH="$STUBBIN:$PATH" \
    JOURNEY_STEP_BUDGET_SECS="${JOURNEY_STEP_BUDGET_SECS_OVERRIDE:-900}" \
    JOURNEY_CLASS_TIMEOUT_SECS=30 \
    JOURNEY_NO_OUTPUT_TIMEOUT_SECS=25 \
    JOURNEY_CLASS_KILL_AFTER_SECS=1 \
    JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
    POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$SOLO_SHARD_TOTAL" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="${RUN_REAL_SUITE_SHARD_INDEX:-$SOLO_SHARD_INDEX}" \
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

  # Issue #2110: run this arm on the leg that OWNS this proof, computed by the
  # production selector rather than assumed. Before proof sharding every leg ran
  # every proof, so any index worked; now the wrong index would silently defer the
  # proof under test and turn both arms of this matrix vacuous (the suite would
  # stay green with nothing failing, and the "no failed-both section" assertion
  # in the red control would pass for entirely the wrong reason).
  RUN_REAL_SUITE_SHARD_INDEX="$(journey_class_shard_index "$selector" "$SOLO_SHARD_TOTAL")"
  export RUN_REAL_SUITE_SHARD_INDEX

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
  # Issue #2110 anti-vacuity: prove the proof actually EXECUTED on this leg. If
  # the ownership arithmetic above were wrong the proof would be deferred, the
  # suite would pass, and every assertion in this arm would be about a proof that
  # never ran.
  grep -qF "PROOF: $selector (attempt 1)" "$ws/suite.log" \
    || { sed -n '1,40p' "$ws/suite.log"; fail "(b/$status_var) $selector did not execute on shard $RUN_REAL_SUITE_SHARD_INDEX of $SOLO_SHARD_TOTAL — this arm would be vacuous (issue #2110 proof sharding)"; }
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

  pass "(b/$status_var) pre-#1827 writer -> INFRA / RE-RUN / exit 0   |   real writer -> RED / RED / exit 1   [$bare_class, shard $RUN_REAL_SUITE_SHARD_INDEX]"
  unset RUN_REAL_SUITE_SHARD_INDEX
done

# ---------------------------------------------------------------------------
# (c) The OTHER suite-reddening conditions, each through the same real path.
# ---------------------------------------------------------------------------
echo
echo "== (c) the non-proof reddening conditions still carry their evidence =="

# (c1) A journey CLASS that fails both attempts.
ws="$SANDBOX/journey-class"
make_workspace "$ws"
# Issue #1862: the ONE class this single-class run selects — resolved from the
# real selection at the top of this file, not from the array's first entry.
first_class="$SOLO_CLASS"
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
# and `run_real_suite` pins the resolved single-class shard (SOLO_SHARD_INDEX of
# SOLO_SHARD_TOTAL, issue #1862) so exactly ONE class is selected — there is no second loop iteration to catch the budget
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
# Issue #2110: every registered proof still gets a status LINE on every leg — the
# #1827 anti-drift property is unchanged — but its value is PASS only on the leg
# that OWNS it and OTHER_SHARD elsewhere. Assert the exact expected value per
# entry, derived from the production selector, so this stays a real constraint
# rather than "PASS or anything else".
e1_owned=0
for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
  IFS='|' read -r status_var class_var label <<<"$entry"
  owner="$(journey_class_shard_index "${!class_var}" "$SOLO_SHARD_TOTAL")"
  if [[ "$owner" == "$SOLO_SHARD_INDEX" ]]; then
    expected="PASS"
    e1_owned=$((e1_owned + 1))
  else
    expected="OTHER_SHARD"
  fi
  grep -qF "$label (\`shared:core-terminal\`): **$expected**" "$summary" \
    || { cat "$summary"; fail "(e1) the summary line for $label is not **$expected** on shard $SOLO_SHARD_INDEX of $SOLO_SHARD_TOTAL (issue #2110)"; }
done
echo "   (e1) shard $SOLO_SHARD_INDEX of $SOLO_SHARD_TOTAL owns $e1_owned of ${#CORE_TERMINAL_PROOFS[@]} proofs; the other ${#CORE_TERMINAL_PROOFS[@]} - $e1_owned carry OTHER_SHARD and still have a status line"
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

# (e1b) Issue #2110: (e1) runs on the resolved single-CLASS shard, which at
# SOLO_SHARD_TOTAL owns few or no proofs — so on its own it can only ever check
# the OTHER_SHARD half, and "every proof reports **PASS** on a healthy leg" would
# quietly stop being asserted anywhere. Run ONE more healthy suite on a leg that
# does own a proof and pin the exact split: that proof **PASS**, every other
# **OTHER_SHARD**, and the shard still CLEAN.
echo
echo "== (e1b) a healthy leg reports PASS for the proof it owns, OTHER_SHARD for the rest =="
e1b_entry="${CORE_TERMINAL_PROOFS[0]}"
IFS='|' read -r e1b_status_var e1b_class_var e1b_label <<<"$e1b_entry"
e1b_selector="${!e1b_class_var}"
ws="$SANDBOX/all-clean-owning-leg"
make_workspace "$ws"
RUN_REAL_SUITE_SHARD_INDEX="$(journey_class_shard_index "$e1b_selector" "$SOLO_SHARD_TOTAL")"
export RUN_REAL_SUITE_SHARD_INDEX
run_real_suite "$ws"
[[ "$SUITE_RC" -eq 0 ]] \
  || { sed -n '1,40p' "$ws/suite.log"; fail "(e1b) a healthy suite on the owning leg exited $SUITE_RC"; }
grep -qF "PROOF: $e1b_selector (attempt 1)" "$ws/suite.log" \
  || { sed -n '1,40p' "$ws/suite.log"; fail "(e1b) $e1b_selector did not execute on its own shard $RUN_REAL_SUITE_SHARD_INDEX — the PASS assertion below would be vacuous"; }
summary="$ws/artifacts/ci-journey/summary.md"
for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
  IFS='|' read -r status_var class_var label <<<"$entry"
  if [[ "$status_var" == "$e1b_status_var" ]]; then expected="PASS"; else
    owner="$(journey_class_shard_index "${!class_var}" "$SOLO_SHARD_TOTAL")"
    [[ "$owner" == "$RUN_REAL_SUITE_SHARD_INDEX" ]] && expected="PASS" || expected="OTHER_SHARD"
  fi
  grep -qF "$label (\`shared:core-terminal\`): **$expected**" "$summary" \
    || { cat "$summary"; fail "(e1b) the summary line for $label is not **$expected** on the owning shard $RUN_REAL_SUITE_SHARD_INDEX"; }
done
run_journey_summary_step "$ws"
[[ "$FIRST_FAILURE" == "false" && "$FIRST_TIMEOUT" == "false" ]] \
  || fail "(e1b) owning-leg healthy summary reported first_failure=$FIRST_FAILURE first_timeout=$FIRST_TIMEOUT"
run_classify_step "$ws" "$(classify_expressions success "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
[[ "$CLASSIFY_TOKEN" == "CLEAN" && "$CLASSIFY_RC" -eq 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(e1b) expected CLEAN/exit0 on the owning leg, got '$CLASSIFY_TOKEN'/exit$CLASSIFY_RC"; }
unset RUN_REAL_SUITE_SHARD_INDEX
pass "(e1b) on shard $(journey_class_shard_index "$e1b_selector" "$SOLO_SHARD_TOTAL") of $SOLO_SHARD_TOTAL the owned proof reports **PASS** and every deferred proof **OTHER_SHARD**; the leg still classifies CLEAN"

# The remaining sibling properties (#1800 real-IME INFRA, #1822 mixed-summary
# RED, #1822 unreadable-entry RED, #1809 no-RED-with-INFRA neutral green) are
# driven over synthetic summaries because they need specific JUnit failure
# messages the suite stub cannot produce. Same real step bodies.
SATURATED_CLASS="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"
OCCLUSION_CLASS="com.pocketshell.app.tmux.TmuxShellComposerOcclusionE2eTest"
REAL_IME_MSG="The real system input-method window never became visible."
FOREIGN_REAL_IME_DETAIL="app_window_focused=false active_window_pkg=com.google.android.apps.nexuslauncher active_window_class=com.android.launcher3.Launcher"
APP_OWNED_REAL_IME_DETAIL="app_window_focused=false active_window_pkg=com.pocketshell.app.i1882 active_window_class=android.widget.FrameLayout composer_editor_served=false"

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
  local key dir
  key="$(journey_fixture_artifact_key "$classname")"
  dir="$ws/artifacts/ci-journey/class-attempts/app/$key/attempt-$attempt/android-test-outputs/app/build/outputs/androidTest-results/connected/debug"
  mkdir -p "$dir"
  {
    echo "<testsuite tests=\"$#\" failures=\"$#\">"
    local spec method kind
    for spec in "$@"; do
      method="${spec%%:*}"; kind="${spec##*:}"
      case "$kind" in
        realime-foreign) echo "  <testcase classname=\"$classname\" name=\"$method\"><failure message=\"$REAL_IME_MSG $FOREIGN_REAL_IME_DETAIL\"/></testcase>" ;;
        realime-app) echo "  <testcase classname=\"$classname\" name=\"$method\"><failure message=\"$REAL_IME_MSG $APP_OWNED_REAL_IME_DETAIL\"/></testcase>" ;;
        realime-missing) echo "  <testcase classname=\"$classname\" name=\"$method\"><failure message=\"$REAL_IME_MSG\"/></testcase>" ;;
        realime-malformed) echo "  <testcase classname=\"$classname\" name=\"$method\"><failure message=\"$REAL_IME_MSG app_window_focused=false active_window_pkg=not/a/package\"/></testcase>" ;;
        realime-ambiguous) echo "  <testcase classname=\"$classname\" name=\"$method\"><failure message=\"$REAL_IME_MSG app_window_focused=false active_window_pkg=android active_window_class=com.android.server.am.AppNotRespondingDialog\"/></testcase>" ;;
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
write_synth_xml "$ws" 1 "$SATURATED_CLASS" "realImeReachability:realime-foreign"
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

# (e2b) #1882: only a positively identified foreign owner may use e2's INFRA
# relief valve. App-owned, missing, malformed, and framework-ambiguous owner
# evidence must remain product RED through the same summary + workflow bodies.
for unsafe_owner_case in \
  "app:realime-app" \
  "missing:realime-missing" \
  "malformed:realime-malformed" \
  "ambiguous-android:realime-ambiguous"
do
  unsafe_owner_label="${unsafe_owner_case%%:*}"
  unsafe_owner_kind="${unsafe_owner_case#*:}"
  ws="$SANDBOX/synth-real-ime-$unsafe_owner_label"; make_workspace "$ws"
  write_synth_summary "$ws" "$SATURATED_CLASS"
  write_synth_xml "$ws" 1 "$SATURATED_CLASS" \
    "realImeReachability:$unsafe_owner_kind"
  snapshot_attempt1 "$ws"
  run_journey_summary_step "$ws"
  run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
  [[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_RC" -ne 0 ]] \
    || {
      printf '%s\n' "$CLASSIFY_OUT"
      fail "(e2b/$unsafe_owner_label) unsafe owner evidence must stay RED, got '$CLASSIFY_TOKEN'/exit$CLASSIFY_RC"
    }
  grep -q '^shard_verdict=RED$' <<<"$CLASSIFY_GH_OUTPUT" \
    || fail "(e2b/$unsafe_owner_label) shard RED output is missing"
done
pass "(e2b) #1882: app-owned, missing, malformed, and android-ambiguous real-IME evidence stays RED"

# (e3) #1822: the mixed summary — the IME signature entry followed by a genuine
#      method-scoped failure — is still RED, and still fails its own shard job.
ws="$SANDBOX/synth-mixed"; make_workspace "$ws"
write_synth_summary "$ws" "$SATURATED_CLASS" \
  "$OCCLUSION_CLASS#shellComposerControlsAreVisibleAndReachableInBothKeyboardStates"
write_synth_xml "$ws" 1 "$SATURATED_CLASS" "realImeReachability:realime-foreign"
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
write_synth_xml "$ws" 1 "$SATURATED_CLASS" "realImeReachability:realime-foreign"
snapshot_attempt1 "$ws"
run_journey_summary_step "$ws"
run_classify_step "$ws" "$(classify_expressions failure "$FIRST_TIMEOUT" "$FIRST_FAILURE")"
[[ "$CLASSIFY_TOKEN" == "RED" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(e4) an unreadable failed-both entry must stay RED, got '$CLASSIFY_TOKEN'"; }
pass "(e4) #1822 preserved: an unreadable failed-both entry -> RED"

echo
echo "ALL TESTS PASSED"
