#!/usr/bin/env bash
# check-unit-gate-wiring.sh — issue #2067
#
# THE HAZARD THIS EXISTS FOR
#
# `main`'s ruleset requires one check literally named `Unit tests`. Since #2060
# that name belongs to the ~10-second `unit-gate` aggregator, which fans out to
# the real unit-lane jobs. The aggregator carries the blocking contract in THREE
# separate lists that nothing forced to agree:
#
#   1. `needs: [...]`                      — which jobs must finish first
#   2. `env: VAR: ${{ needs.<job>.result }}` — which results are read
#   3. the `for pair in "Label:$VAR"` loop  — which results are checked
#
# A job present in (1) but missing from (2)/(3) RUNS, can go RED, and the
# required check stays GREEN — a red job under a green required check, which is
# the precise silent-gate failure #2060 was created to prevent. A job present in
# (3) but not (1) reads an empty string and is equally decorative. And a job
# added to the workflow but wired into none of them is simply not gated at all.
#
# So this guard asserts, mechanically, per push:
#
#   C1  the aggregator is still named exactly `Unit tests`     (the ruleset contract)
#   C2  its `needs:` list is non-empty                          (no vacuous gate)
#   C3  every needed job actually exists in the workflow
#   C4  set(needs) == set(jobs read in `env:`)                  (list 1 == list 2)
#   C5  each env var name is its job key upper-snake-cased      (no crossed wires)
#   C6  set(env var names) == set(vars consumed by the loop)    (list 2 == list 3)
#   C7  every workflow job is either wired into the gate or explicitly exempt
#   C8  every exempt name still exists                          (the list cannot rot)
#   C9  the #2063 selection guards are NOT reachable from a Gradle test task
#
# C5 is the subtle one: without it `GUARDS_STATIC: ${{ needs.guards-ci-harness.result }}`
# passes C4 and C6 while the loop prints one job's label next to another job's
# result — every list "agrees" and the gate silently checks the wrong thing twice
# while never checking a third.
#
# C7's exempt set is deliberately small and named, so adding ANY new job forces a
# conscious decision: wire it into the required check, or say out loud why it is
# not part of it.
#
# C9 is the other half of #2067 and the one a future round is most likely to
# undo by good intentions. #2063 originally reached its coverage guards through a
# JVM test so `./gradlew test` would run them. That is correct instinct and the
# wrong lane: `test` runs BOTH variants, so the ~165 s suite was charged twice per
# push, on the Unit critical path, for work that compiles nothing — measured over
# 104 `main` commits as exactly cancelling the scheme's own saving. They now live
# in the `guards-test-selection` job. C9 makes "do not put them back" mechanical
# rather than a comment in a doc nobody re-reads.
#
#   scripts/check-unit-gate-wiring.sh              # check the real workflow
#   scripts/check-unit-gate-wiring.sh --self-test  # prove the checks can go red
#
# No JVM, no Gradle, no network. Runs in `guards-static`, which is itself one of
# the jobs this guard requires to be wired in.

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_WORKFLOW="$ROOT_DIR/.github/workflows/tests.yml"

# The aggregator that carries the protected-branch check name.
GATE_JOB="unit-gate"
GATE_CHECK_NAME="Unit tests"

# Jobs that are deliberately NOT part of the `Unit tests` required check.
# Each one is either its own required context or a batched heavy lane.
EXEMPT_JOBS=(
  "unit-gate"                # the aggregator itself
  "python"                   # separate required check: Python utility tests (pocketshell)
  "integration"              # batched Docker lane, not a per-PR blocker
  "emulator-journey"         # batched emulator lane, verdict-gated below
  "emulator-journey-verdict" # the emulator lane's own aggregate verdict
)

# C9: the #2063 coverage guards belong to `guards-test-selection`, never to a
# Gradle test task. Matched against unit/instrumentation test sources and build
# scripts — the two places from which a Gradle test could shell out to them.
SELECTION_GUARD_SCRIPTS='select-test-areas\.sh|select-test-areas-selftest\.sh|check-test-execution-ledger\.sh|check-test-execution-ledger-selftest\.sh|dev-fast-gate-parity-selftest\.sh'

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# --- parsing -----------------------------------------------------------------
# The workflow's shape is fixed and small, so these are targeted extractors, not
# a YAML parser. Every one of them fails loudly when it finds nothing: "I could
# not read it" must never read the same as "it is fine".

job_keys() {
  # Top-level job keys: exactly two spaces of indent, inside the `jobs:` mapping.
  awk '
    !injobs { if ($0 ~ /^jobs:[[:space:]]*$/) injobs = 1; next }
    /^  [A-Za-z0-9_-]+:[[:space:]]*(#.*)?$/ {
      key = $0
      sub(/^  /, "", key)
      sub(/:.*$/, "", key)
      print key
    }
  ' "$1"
}

job_block() {
  # Everything under job `$2`, up to the next top-level job key.
  awk -v want="$2" '
    !injobs { if ($0 ~ /^jobs:[[:space:]]*$/) injobs = 1; next }
    /^  [A-Za-z0-9_-]+:[[:space:]]*(#.*)?$/ {
      key = $0
      sub(/^  /, "", key)
      sub(/:.*$/, "", key)
      inblock = (key == want)
      next
    }
    inblock { print }
  ' "$1"
}

sorted_unique() {
  # stdin -> newline-separated, sorted, de-duplicated, blanks dropped.
  # `|| true` on the grep: empty input is a legitimate (and interesting) state
  # here — an emptied `needs:` list must reach the C2 check with its message,
  # not die silently under `set -euo pipefail` on grep's no-match exit 1.
  { grep -v '^[[:space:]]*$' || true; } | LC_ALL=C sort -u
}

upper_snake() {
  printf '%s' "$1" | tr '[:lower:]-' '[:upper:]_'
}

check_workflow() {
  local workflow="$1"
  [ -f "$workflow" ] || fail "workflow not found: $workflow"

  local all_jobs
  all_jobs="$(job_keys "$workflow" | sorted_unique)"
  [ -n "$all_jobs" ] || fail "no top-level jobs parsed out of $workflow — the extractor is broken or the file changed shape"

  printf '%s\n' "$all_jobs" | grep -qx "$GATE_JOB" ||
    fail "the aggregator job \`$GATE_JOB\` is gone from $workflow; the required \`$GATE_CHECK_NAME\` check has no owner"

  local block
  block="$(job_block "$workflow" "$GATE_JOB")"
  [ -n "$block" ] || fail "could not read the \`$GATE_JOB\` block out of $workflow"

  # --- C1: the ruleset-visible check name --------------------------------
  local gate_name
  gate_name="$(printf '%s\n' "$block" | sed -n 's/^    name:[[:space:]]*\(.*[^[:space:]]\)[[:space:]]*$/\1/p' | head -1)"
  [ -n "$gate_name" ] || fail "C1: \`$GATE_JOB\` has no \`name:\`"
  if [ "$gate_name" != "$GATE_CHECK_NAME" ]; then
    fail "C1: \`$GATE_JOB\` is named '$gate_name', but branch protection requires the literal check name '$GATE_CHECK_NAME'. Renaming it silently demotes every unit-lane guard to non-blocking."
  fi

  # --- C2/C3: the needs list ---------------------------------------------
  local needs_line
  needs_line="$(printf '%s\n' "$block" | sed -n 's/^    needs:[[:space:]]*\[\(.*\)\][[:space:]]*$/\1/p' | head -1)"
  if ! printf '%s\n' "$block" | grep -qE '^    needs:[[:space:]]*\['; then
    fail "C2: \`$GATE_JOB\` has no flow-sequence \`needs: [...]\` line. This guard reads that exact form on purpose — if the list is reformatted, update the guard rather than letting it read nothing."
  fi
  local needs
  needs="$(printf '%s' "$needs_line" | tr ',' '\n' | tr -d ' ' | sorted_unique)"
  [ -n "$needs" ] || fail "C2: \`$GATE_JOB\` needs nothing — the required check would pass with no unit-lane job gated"

  local missing_job=""
  local j
  while IFS= read -r j; do
    printf '%s\n' "$all_jobs" | grep -qx "$j" || missing_job="$missing_job $j"
  done <<< "$needs"
  [ -z "$missing_job" ] && : || fail "C3: \`$GATE_JOB\` needs job(s) that do not exist in the workflow:$missing_job"

  # --- list 2: the env mapping -------------------------------------------
  local env_pairs
  env_pairs="$(printf '%s\n' "$block" |
    sed -n 's/^          \([A-Z0-9_]\{1,\}\):[[:space:]]*\${{[[:space:]]*needs\.\([A-Za-z0-9_-]\{1,\}\)\.result[[:space:]]*}}[[:space:]]*$/\1 \2/p')"
  [ -n "$env_pairs" ] || fail "C4: no \`VAR: \${{ needs.<job>.result }}\` env entries found in \`$GATE_JOB\`"

  local env_jobs env_vars
  env_jobs="$(printf '%s\n' "$env_pairs" | awk '{print $2}' | sorted_unique)"
  env_vars="$(printf '%s\n' "$env_pairs" | awk '{print $1}' | sorted_unique)"

  # --- C4: needs == env-read jobs ----------------------------------------
  local only_needs only_env
  only_needs="$(comm -23 <(printf '%s\n' "$needs") <(printf '%s\n' "$env_jobs") | tr '\n' ' ')"
  only_env="$(comm -13 <(printf '%s\n' "$needs") <(printf '%s\n' "$env_jobs") | tr '\n' ' ')"
  if [ -n "${only_needs// /}" ]; then
    fail "C4: job(s) in \`needs:\` whose result the gate never reads: ${only_needs% }. They run, they can go RED, and the required \`$GATE_CHECK_NAME\` check stays GREEN."
  fi
  if [ -n "${only_env// /}" ]; then
    fail "C4: the gate reads results for job(s) it does not \`need\`: ${only_env% }. Those results are empty strings, not verdicts."
  fi

  # --- C5: env var name is the job key, upper-snake-cased -----------------
  local var job expected crossed=""
  while read -r var job; do
    [ -n "$var" ] || continue
    expected="$(upper_snake "$job")"
    [ "$var" = "$expected" ] || crossed="$crossed
  $var -> needs.$job.result (expected var name $expected)"
  done <<< "$env_pairs"
  if [ -n "$crossed" ]; then
    fail "C5: env var name does not match the job it reads, so a label can be paired with another job's result:$crossed"
  fi

  # --- list 3: the vars the result loop actually checks --------------------
  local loop_vars
  loop_vars="$(printf '%s\n' "$block" |
    grep -oE '"[^"]*:\$[A-Z0-9_]+"' |
    sed -E 's/.*:\$([A-Z0-9_]+)"/\1/' | sorted_unique)"
  [ -n "$loop_vars" ] || fail "C6: found no \"Label:\$VAR\" entries in \`$GATE_JOB\`'s result-checking loop"

  # --- C6: env vars == loop-consumed vars ---------------------------------
  local only_declared only_checked
  only_declared="$(comm -23 <(printf '%s\n' "$env_vars") <(printf '%s\n' "$loop_vars") | tr '\n' ' ')"
  only_checked="$(comm -13 <(printf '%s\n' "$env_vars") <(printf '%s\n' "$loop_vars") | tr '\n' ' ')"
  if [ -n "${only_declared// /}" ]; then
    fail "C6: env var(s) declared but never checked by the loop: ${only_declared% }. The job is needed and its result is read, and then thrown away — still a red job under a green required check."
  fi
  if [ -n "${only_checked// /}" ]; then
    fail "C6: the loop checks var(s) with no \`env:\` declaration: ${only_checked% }. Those expand to the empty string, which is not 'success', so this would red the gate for a job nobody wired in."
  fi

  # --- C7/C8: every job is gated or explicitly exempt ---------------------
  local exempt_list
  exempt_list="$(printf '%s\n' "${EXEMPT_JOBS[@]}" | sorted_unique)"

  local stale_exempt=""
  local e
  while IFS= read -r e; do
    printf '%s\n' "$all_jobs" | grep -qx "$e" || stale_exempt="$stale_exempt $e"
  done <<< "$exempt_list"
  [ -z "$stale_exempt" ] || fail "C8: EXEMPT_JOBS names job(s) that no longer exist:$stale_exempt (remove them so the exemption list cannot silently cover a future job of the same name)"

  local ungated
  ungated="$(comm -23 <(printf '%s\n' "$all_jobs") <(cat <(printf '%s\n' "$needs") <(printf '%s\n' "$exempt_list") | sorted_unique) | tr '\n' ' ')"
  if [ -n "${ungated// /}" ]; then
    fail "C7: workflow job(s) neither wired into \`$GATE_JOB\` nor listed as exempt: ${ungated% }. Add it to the gate's needs+env+loop, or add it to EXEMPT_JOBS in this script with a reason."
  fi

  # --- C9: the selection guards stay out of the Gradle test graph ----------
  local scan_root="${SCAN_ROOT:-$ROOT_DIR}"
  if [ -d "$scan_root/.git" ] || git -C "$scan_root" rev-parse --git-dir >/dev/null 2>&1; then
    local candidates offenders
    candidates="$(git -C "$scan_root" ls-files |
      grep -E '(/src/(test|androidTest)/|(^|/)build\.gradle(\.kts)?$)' || true)"
    offenders=""
    if [ -n "$candidates" ]; then
      offenders="$(cd "$scan_root" && printf '%s\n' "$candidates" |
        tr '\n' '\0' | xargs -0 -r grep -lE "$SELECTION_GUARD_SCRIPTS" 2>/dev/null || true)"
    fi
    if [ -n "$offenders" ]; then
      fail "C9: the #2063 selection guards are reachable from the Gradle test graph again:
$(printf '%s\n' "$offenders" | sed 's/^/  /')
They belong to the \`guards-test-selection\` job (#2067). A test task runs per
variant, so wiring them there charges the ~165 s suite TWICE per push on the Unit
critical path for work that compiles nothing — the regression #2067 removed. Add
a step to that job instead."
    fi
  else
    fail "C9: \`$scan_root\` is not a git checkout, so the Gradle-test-graph scan could not run. 'I could not check' is not 'I checked and it is fine'."
  fi

  echo "OK: \`$GATE_JOB\` is named '$GATE_CHECK_NAME' and its three lists agree."
  echo "    needs      : $(printf '%s' "$needs" | tr '\n' ' ')"
  echo "    env reads  : $(printf '%s' "$env_vars" | tr '\n' ' ')"
  echo "    loop checks: $(printf '%s' "$loop_vars" | tr '\n' ' ')"
  echo "    exempt     : $(printf '%s' "$exempt_list" | tr '\n' ' ')"
  echo "OK: the #2063 selection guards are not reachable from any Gradle test source or build script."
}

# --- self-test ---------------------------------------------------------------
# Each case mutates a COPY of the real workflow and asserts this guard goes red
# for the intended reason. The pass count is asserted at the end, so the
# anti-vacuous guard cannot itself pass vacuously.

SELFTEST_EXPECTED_CASES=11
selftest_passed=0
selftest_failed=0
# Overridden per case so C9's scan can be pointed at a sandbox checkout instead
# of the real tree (planting a file in the real worktree would be cross-agent
# damage of exactly the kind the process catalogue warns about).
EXPECT_SCAN_ROOT=""

st_ok() {
  echo "  ok   $*"
  selftest_passed=$((selftest_passed + 1))
}

st_bad() {
  echo "  FAIL $*" >&2
  selftest_failed=$((selftest_failed + 1))
}

run_guard_under_test() {
  # $1 = workflow path; honours EXPECT_SCAN_ROOT for the C9 scan.
  if [ -n "$EXPECT_SCAN_ROOT" ]; then
    SCAN_ROOT="$EXPECT_SCAN_ROOT" "${BASH_SOURCE[0]}" --workflow "$1" 2>&1
  else
    "${BASH_SOURCE[0]}" --workflow "$1" 2>&1
  fi
}

expect_red() {
  # expect_red <label> <expected-substring> <mutated-workflow>
  local label="$1" want="$2" file="$3" out rc
  set +e
  out="$(run_guard_under_test "$file")"
  rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    st_bad "$label: guard stayed GREEN on the mutated workflow"
    return
  fi
  case "$out" in
    *"$want"*) st_ok "$label (red, matched '$want')" ;;
    *) st_bad "$label: red, but for the wrong reason — wanted '$want', got:
$out" ;;
  esac
}

expect_green() {
  local label="$1" file="$2" out rc
  set +e
  out="$(run_guard_under_test "$file")"
  rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    st_ok "$label (green)"
  else
    st_bad "$label: guard went RED on an unmutated workflow:
$out"
  fi
}

self_test() {
  local src="$DEFAULT_WORKFLOW"
  [ -f "$src" ] || fail "self-test needs the real workflow at $src"
  # Global, not `local`: the EXIT trap fires after this function has returned,
  # and a `local` would be unbound there — which under `set -u` turns a PASSING
  # self-test into exit 1 from the cleanup handler.
  SELFTEST_SANDBOX="$(mktemp -d)"
  trap 'rm -rf "${SELFTEST_SANDBOX:-}"' EXIT
  local sandbox="$SELFTEST_SANDBOX"

  echo "== check-unit-gate-wiring self-test =="

  # Case 0 — the real workflow must be green, or every red below is meaningless.
  expect_green "0 real workflow" "$src"

  # Case 1 — a job in the loop and env but dropped from `needs:` (list 1 gap).
  local m1="$sandbox/m1.yml"
  sed 's/^    needs: \[unit, guards-static, guards-ci-harness, guards-test-selection, dex\]$/    needs: [unit, guards-static, guards-ci-harness, dex]/' "$src" > "$m1"
  cmp -s "$src" "$m1" && st_bad "1 mutation did not apply" || \
    expect_red "1 job dropped from needs:" "C4" "$m1"

  # Case 2 — a needed job whose result the gate never reads (list 2 gap).
  local m2="$sandbox/m2.yml"
  grep -v '^          GUARDS_TEST_SELECTION: ' "$src" > "$m2"
  cmp -s "$src" "$m2" && st_bad "2 mutation did not apply" || \
    expect_red "2 env mapping removed" "C4" "$m2"

  # Case 3 — THE HEADLINE CASE: needed, result read, never checked (list 3 gap).
  #          This is the shape that produces a red job under a green gate.
  local m3="$sandbox/m3.yml"
  grep -v '"Test-selection coverage guards:\$GUARDS_TEST_SELECTION" \\' "$src" > "$m3"
  cmp -s "$src" "$m3" && st_bad "3 mutation did not apply" || \
    expect_red "3 loop entry removed" "C6" "$m3"

  # Case 4 — the required check name renamed out from under branch protection.
  local m4="$sandbox/m4.yml"
  awk '
    /^  unit-gate:$/ { ingate = 1 }
    ingate && /^    name: Unit tests$/ { print "    name: Unit tests (fast)"; ingate = 0; next }
    { print }
  ' "$src" > "$m4"
  cmp -s "$src" "$m4" && st_bad "4 mutation did not apply" || \
    expect_red "4 gate renamed" "C1" "$m4"

  # Case 5 — crossed wires: the var names swapped between two real jobs. Every
  #          list still "agrees" as a set; only the pairing is wrong.
  local m5="$sandbox/m5.yml"
  sed -e 's/^          GUARDS_STATIC: \${{ needs.guards-static.result }}$/          GUARDS_STATIC: ${{ needs.guards-ci-harness.result }}/' \
      -e 's/^          GUARDS_CI_HARNESS: \${{ needs.guards-ci-harness.result }}$/          GUARDS_CI_HARNESS: ${{ needs.guards-static.result }}/' \
      "$src" > "$m5"
  cmp -s "$src" "$m5" && st_bad "5 mutation did not apply" || \
    expect_red "5 env var paired with the wrong job" "C5" "$m5"

  # Case 6 — a brand-new workflow job wired into nothing.
  local m6="$sandbox/m6.yml"
  awk '
    /^  unit-gate:$/ && !done {
      print "  brand-new-guard:"
      print "    name: Brand new guard"
      print "    runs-on: ubuntu-latest"
      print "    steps:"
      print "      - run: echo hi"
      print ""
      done = 1
    }
    { print }
  ' "$src" > "$m6"
  cmp -s "$src" "$m6" && st_bad "6 mutation did not apply" || \
    expect_red "6 new job gated nowhere" "C7" "$m6"

  # Case 7 — an empty needs list: every list agrees, and nothing is gated.
  local m7="$sandbox/m7.yml"
  awk '
    /^    needs: \[unit, guards-static/ { print "    needs: []"; next }
    { print }
  ' "$src" > "$m7"
  cmp -s "$src" "$m7" && st_bad "7 mutation did not apply" || \
    expect_red "7 empty needs list" "C2" "$m7"

  # Case 8 — a needed job that does not exist, wired consistently into all three
  #          lists, so ONLY the existence check can catch it.
  local m8="$sandbox/m8.yml"
  sed -e 's/^    needs: \[unit, guards-static, guards-ci-harness, guards-test-selection, dex\]$/    needs: [unit, guards-static, guards-ci-harness, guards-test-selection, dex, ghost-job]/' \
      -e 's/^          DEX: \${{ needs.dex.result }}$/          DEX: ${{ needs.dex.result }}\n          GHOST_JOB: ${{ needs.ghost-job.result }}/' \
      -e 's|^                      "Dex register-pressure ratchet:\$DEX"; do$|                      "Dex register-pressure ratchet:$DEX" \\\n                      "Ghost:$GHOST_JOB"; do|' \
      "$src" > "$m8"
  cmp -s "$src" "$m8" && st_bad "8 mutation did not apply" || \
    expect_red "8 needs a nonexistent job" "C3" "$m8"

  # Cases 9 and 10 exercise C9 against a SANDBOX checkout. Planting a file in the
  # real worktree to test a `git ls-files` scan would be exactly the shared-file
  # cross-agent damage the process catalogue documents, so the sandbox is its own
  # tiny git repository instead.
  local scan_sandbox="$sandbox/scan-repo"
  mkdir -p "$scan_sandbox/app/src/test/java/com/pocketshell/app/scripts" "$scan_sandbox/scripts"
  git -C "$scan_sandbox" init -q
  git -C "$scan_sandbox" config user.email selftest@example.invalid
  git -C "$scan_sandbox" config user.name selftest
  # A plain script referencing the guards is legitimate and must NOT trip C9.
  printf 'bash scripts/select-test-areas-selftest.sh\n' > "$scan_sandbox/scripts/some-wrapper.sh"
  git -C "$scan_sandbox" add -A
  git -C "$scan_sandbox" commit -qm base

  EXPECT_SCAN_ROOT="$scan_sandbox"
  expect_green "9 a non-test script may reference the guards" "$src"

  # Case 10 — the regression #2067 removed: a JVM test shelling out to the guards,
  # which puts the ~165 s suite back inside `./gradlew test`, once per variant.
  printf 'class SmartTestSelectionScriptTest { fun t() { runShell("scripts/select-test-areas-selftest.sh") } }\n' \
    > "$scan_sandbox/app/src/test/java/com/pocketshell/app/scripts/SmartTestSelectionScriptTest.kt"
  git -C "$scan_sandbox" add -A
  expect_red "10 a Gradle test task reaching the guards again" "C9" "$src"
  EXPECT_SCAN_ROOT=""

  echo
  echo "$selftest_passed passed, $selftest_failed failed"
  if [ "$selftest_failed" -ne 0 ]; then
    fail "self-test had $selftest_failed failing case(s)"
  fi
  if [ "$selftest_passed" -ne "$SELFTEST_EXPECTED_CASES" ]; then
    fail "self-test ran $selftest_passed case(s), expected exactly $SELFTEST_EXPECTED_CASES — a case was skipped or silently dropped"
  fi
  echo "OK: all $SELFTEST_EXPECTED_CASES self-test cases behaved as expected."
}

# --- entrypoint ---------------------------------------------------------------
MODE="check"
WORKFLOW="$DEFAULT_WORKFLOW"
while [ $# -gt 0 ]; do
  case "$1" in
    --self-test) MODE="selftest"; shift ;;
    --workflow) WORKFLOW="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,40p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

case "$MODE" in
  selftest) self_test ;;
  check) check_workflow "$WORKFLOW" ;;
esac
