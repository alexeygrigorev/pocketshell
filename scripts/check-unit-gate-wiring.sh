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
#   C11 the aggregator uses `if: ${{ !cancelled() }}` and not `always()`
#       (issue #2187: a concurrency-cancel must conclude the required check
#       as cancelled, not dispatch the job so `!= success` paints it failure)
#   C13 the extracted result loop still fail-closes on skipped / empty /
#       failure / cancelled-while-running (the original #2060 property)
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
# Issue #2435 found C9's blind spot the expensive way. A JVM test rarely shells
# out to a guard directly here — it names a `tests/scripts/*.sh` harness, and
# the HARNESS runs the guard. Scanning only test sources therefore saw a Kotlin
# file naming a harness and nothing else, so four cases driving the real
# `--record`/`--verify` rode back onto the Unit lane; C9 went red purely by
# accident, on a KDoc paragraph that happened to spell one of the script names.
# Deleting that word would have restored green over an unchanged regression.
# C9 now scans one hop further, and the self-test pins both halves: a harness
# no Gradle test drives stays green, the same harness named by a test goes red.
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

# Issue #2134: `main`'s ruleset requires TWO literal check names, and C1 only
# ever pinned one. The second belonged to a job listed in EXEMPT_JOBS below with
# its check name written in a COMMENT — so renaming it (or, as #2134 refactors
# the workflow for size, moving/retitling it) would have silently unblocked
# `Python utility tests (pocketshell)` with every guard here still green.
PYTHON_JOB="python"
PYTHON_CHECK_NAME="Python utility tests (pocketshell)"

# Jobs that are deliberately NOT part of the `Unit tests` required check.
# Each one is either its own required context or a batched heavy lane.
EXEMPT_JOBS=(
  "unit-gate"                # the aggregator itself
  "python"                   # separate required check: Python utility tests (pocketshell)
  "integration"              # batched Docker lane, not a per-PR blocker
  "emulator-journey"         # batched emulator lane, verdict-gated below
  "emulator-journey-verdict" # the emulator lane's own aggregate verdict
  "sg"                       # issue #2353: scoped-push plan gate, not a per-PR job
)

# C9: the #2063 coverage guards belong to `guards-test-selection`, never to a
# Gradle test task. Matched against unit/instrumentation test sources, build
# scripts, and (issue #2435) the shell harnesses those files name in a string
# literal — `runShellHarness(relativePath = "tests/scripts/…")` is how a JVM
# test actually shells out here, and scanning only the Kotlin side sees the
# harness name, never the guard the harness runs.
SELECTION_GUARD_SCRIPTS='select-test-areas\.sh|select-test-areas-selftest\.sh|check-test-execution-ledger\.sh|check-test-execution-ledger-selftest\.sh|check-test-execution-ledger-wiring\.py|ci-record-test-execution-ledger\.sh|ci-nightly-execution-ledger\.sh|dev-fast-gate-parity-selftest\.sh'

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

# Issue #2187: the exact job-level `if:` that lets GitHub mark the required
# check `cancelled` when the workflow is cancelled, while still dispatching
# the job when a needed job failed or was skipped. `always()` is the bug —
# it keeps the job running after a concurrency-cancel, and the result loop
# then paints the required check red. `always() && !cancelled()` is also
# wrong: GitHub evaluates it (because of `always()`) and the false result
# is `skipped`, which required-check rulesets can treat as passing.
EXPECTED_GATE_IF='${{ !cancelled() }}'

extract_job_if() {
  # First job-level `if:` (4-space indent) in the supplied job block.
  printf '%s\n' "$1" | sed -n 's/^    if:[[:space:]]*//p' | head -1
}

extract_gate_script() {
  # The `run: |` body of the unit-gate result loop. Fails loudly on empty:
  # C13 executes this script, so "I could not extract it" must not look like
  # "the rollup is fine".
  printf '%s\n' "$1" | awk '
    /^        run: \|[[:space:]]*$/ { inrun = 1; next }
    inrun {
      if ($0 != "" && $0 !~ /^          /) exit
      sub(/^          /, "")
      print
    }
  '
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

  # --- C10: the OTHER ruleset-visible check name (issue #2134) ------------
  printf '%s\n' "$all_jobs" | grep -qx "$PYTHON_JOB" ||
    fail "C10: the \`$PYTHON_JOB\` job is gone from $workflow; the required \`$PYTHON_CHECK_NAME\` check has no owner"
  local python_block python_name
  python_block="$(job_block "$workflow" "$PYTHON_JOB")"
  [ -n "$python_block" ] || fail "C10: could not read the \`$PYTHON_JOB\` block out of $workflow"
  python_name="$(printf '%s\n' "$python_block" | sed -n 's/^    name:[[:space:]]*\(.*[^[:space:]]\)[[:space:]]*$/\1/p' | head -1)"
  [ -n "$python_name" ] || fail "C10: \`$PYTHON_JOB\` has no \`name:\`"
  if [ "$python_name" != "$PYTHON_CHECK_NAME" ]; then
    fail "C10: \`$PYTHON_JOB\` is named '$python_name', but branch protection requires the literal check name '$PYTHON_CHECK_NAME'. Renaming it makes the required check unsatisfiable and blocks every merge."
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
    local tracked direct harnesses candidates offenders
    tracked="$(git -C "$scan_root" ls-files)"
    # Tier 1: the Gradle test graph itself.
    direct="$(printf '%s\n' "$tracked" |
      grep -E '(/src/(test|androidTest)/|(^|/)build\.gradle(\.kts)?$)' || true)"
    # Tier 2 (issue #2435): the scripts a Gradle test task shells OUT to. The
    # JVM tests in app/src/test/.../scripts/ exist to run tests/scripts/*.sh
    # harnesses through `runShellHarness(relativePath = "...")`, so a harness
    # that invokes a selection guard puts it back on the Unit critical path just
    # as surely as a Kotlin test invoking it directly — while tier 1's scan sees
    # only the Kotlin file, which names the harness and not the guard. #2435
    # round 2 landed exactly that: two tests/scripts/*.sh harnesses ran the real
    # `--record`/`--verify`, and C9 caught it only by accident, through a KDoc
    # paragraph that happened to spell one of the script names.
    #
    # The set is DERIVED from the paths those files actually name, not a blanket
    # `tests/scripts/**` glob: a harness no Gradle test drives costs the Unit
    # lane nothing, and must not be policed as if it did (that is what makes
    # `guards-test-selection` a legitimate home rather than a rename away).
    #
    # TWO DELIBERATE BOUNDS, stated rather than pretended away:
    #   * ONE HOP. A harness that sources a production script which merely
    #     mentions a guard is not an invocation. A full transitive closure was
    #     measured and reaches ordinary production code (nightly-extensive-suite,
    #     journey-quarantine) that legitimately names these scripts — a guard
    #     that cries wolf there gets disabled, which is worse than this bound.
    #   * QUOTED STRING LITERALS ONLY. A path a test EXECUTES is a string
    #     literal; a path a comment discusses is prose. Several androidTest
    #     classes cite `scripts/nightly-extensive-suite.sh` in a comment
    #     explaining which lane durably gates them, and that is documentation,
    #     not a shell-out.
    harnesses=""
    if [ -n "$direct" ]; then
      harnesses="$(cd "$scan_root" && printf '%s\n' "$direct" | tr '\n' '\0' |
        xargs -0 -r grep -ohE '"(tests/)?scripts/[A-Za-z0-9._/-]+"' 2>/dev/null |
        tr -d '"' | LC_ALL=C sort -u |
        { grep -Fx -f <(printf '%s\n' "$tracked") || true; })"
    fi
    candidates="$(printf '%s\n%s\n' "$direct" "$harnesses" | sorted_unique)"
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
a step to that job instead (scripts/ci-test-selection-guards.sh).

This also covers a shell harness a JVM test drives via runShellHarness (#2435):
move the guard-invoking cases into a harness \`guards-test-selection\` runs.
Rewording a mention to dodge this grep without moving the invocation is guard
laundering — the cost stays, the signal goes."
    fi
  else
    fail "C9: \`$scan_root\` is not a git checkout, so the Gradle-test-graph scan could not run. 'I could not check' is not 'I checked and it is fine'."
  fi

  # --- C11: cancelled must not be dispatched into a manufactured failure ----
  # G6 mutation: restore `if: always()` (or wrap `!cancelled()` in `always()`).
  # That is exactly how run 31952697403 painted `Unit tests` failure over
  # five cancelled inputs: the job kept running after the concurrency-cancel
  # and `!= success` folded `cancelled` into failure.
  local gate_if
  gate_if="$(extract_job_if "$block")"
  [ -n "$gate_if" ] || fail "C11: \`$GATE_JOB\` has no job-level \`if:\`. The default \`success()\` skips the gate when a needed job fails (#2060) and leaves a cancelled run's required check pending. The required form is \`if: $EXPECTED_GATE_IF\` (#2187)."
  if [ "$gate_if" != "$EXPECTED_GATE_IF" ]; then
    fail "C11: \`$GATE_JOB\` if: is '$gate_if', but must be '$EXPECTED_GATE_IF' (#2187). \`always()\` keeps the job running after a concurrency-cancel so the result loop paints the required \`$GATE_CHECK_NAME\` check as failure; \`always() && !cancelled()\` evaluates to skipped, which a required-check ruleset can treat as passing. No \`always()\` is load-bearing: GitHub then marks the undispatched check cancelled."
  fi

  # --- C13: the result loop still fail-closes on every non-success ----------
  # The job-level if is what makes a SUPERSEDED run conclude cancelled. Once
  # the job actually runs (a needed job failed, skipped, or a single job was
  # cancelled while the workflow continued), the loop must still reject
  # anything that is not success. Narrowing it to `== "failure"` would let a
  # skipped or missing input pass — the original #2060 hole.
  local gate_script
  gate_script="$(extract_gate_script "$block")"
  [ -n "$gate_script" ] || fail "C13: could not extract \`$GATE_JOB\`'s \`run: |\` body — the fail-closed loop is unreadable, so this is not a pass"
  assert_rollup_exit() {
    local want="$1" label="$2"
    local unit="$3" gs="$4" gch="$5" gts="$6" dex="$7"
    local out rc
    set +e
    out="$(UNIT="$unit" GUARDS_STATIC="$gs" GUARDS_CI_HARNESS="$gch" \
           GUARDS_TEST_SELECTION="$gts" DEX="$dex" \
           bash <<<"$gate_script" 2>&1)"
    rc=$?
    set -e
    if [ "$rc" -ne "$want" ]; then
      fail "C13: $label: extracted rollup exited $rc, expected $want
$out"
    fi
  }
  assert_rollup_exit 0 "all-success" success success success success success
  assert_rollup_exit 1 "a failing input" failure success success success success
  assert_rollup_exit 1 "a skipped input" success skipped success success success
  assert_rollup_exit 1 "an empty/missing input" success success "" success success
  # Conservative: a cancelled input WHILE THIS JOB RAN is a single-job cancel
  # (timeout / runner death), not a concurrency-supersession. Those must still
  # fail closed. The concurrency case never reaches this script (C11).
  assert_rollup_exit 1 "cancelled-while-running" cancelled cancelled cancelled cancelled cancelled

  echo "OK: \`$GATE_JOB\` is named '$GATE_CHECK_NAME' and its three lists agree."
  echo "OK: \`$PYTHON_JOB\` is named '$PYTHON_CHECK_NAME' (the second required check)."
  echo "OK: \`$GATE_JOB\` if: is '$EXPECTED_GATE_IF' (concurrency-cancel concludes cancelled, #2187)."
  echo "    needs      : $(printf '%s' "$needs" | tr '\n' ' ')"
  echo "    env reads  : $(printf '%s' "$env_vars" | tr '\n' ' ')"
  echo "    loop checks: $(printf '%s' "$loop_vars" | tr '\n' ' ')"
  echo "    exempt     : $(printf '%s' "$exempt_list" | tr '\n' ' ')"
  echo "OK: the #2063 selection guards are not reachable from any Gradle test source or build script."
  echo "OK: the extracted rollup fail-closes on failure / skipped / empty / cancelled-while-running."
}

# --- self-test ---------------------------------------------------------------
# Each case mutates a COPY of the real workflow and asserts this guard goes red
# for the intended reason. The pass count is asserted at the end, so the
# anti-vacuous guard cannot itself pass vacuously.

SELFTEST_EXPECTED_CASES=18 # 12 + C11/C13 for #2187 (11-14) + C9 one-hop for #2435 (15, 16)
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

  # Case 4b (issue #2134) — the OTHER required check renamed. Same silent-gate
  #          class as case 4, on the check C1 never covered.
  local m4b="$sandbox/m4b.yml"
  awk '
    /^  python:$/ { inpy = 1 }
    inpy && /^    name: Python utility tests \(pocketshell\)$/ {
      print "    name: Python tests"; inpy = 0; next
    }
    { print }
  ' "$src" > "$m4b"
  cmp -s "$src" "$m4b" && st_bad "4b mutation did not apply" || \
    expect_red "4b python required check renamed" "C10" "$m4b"

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
  rm -f "$scan_sandbox/app/src/test/java/com/pocketshell/app/scripts/SmartTestSelectionScriptTest.kt"

  # Cases 15 and 16 (issue #2435) — the ONE-HOP hole. #2435 round 2 put four
  # cases that run the real `--record`/`--verify` into two tests/scripts/*.sh
  # harnesses that JVM tests drive through `runShellHarness(relativePath = …)`.
  # The Kotlin file names the HARNESS, not the guard, so tier 1's scan saw
  # nothing; C9 only went red by accident, on a KDoc paragraph that happened to
  # spell one of the script names. Deleting that word would have turned the
  # guard green over an unchanged ~25 s/push regression — guard laundering.
  mkdir -p "$scan_sandbox/tests/scripts"
  printf 'bash "$ROOT_DIR/scripts/check-test-execution-ledger.sh" --record "$dir"\n' \
    > "$scan_sandbox/tests/scripts/orphan-harness-test.sh"
  git -C "$scan_sandbox" add -A
  # 15 — CONTROL, and the reason tier 2 is derived from real references rather
  # than a blanket `tests/scripts/**` glob: a harness no Gradle test drives
  # costs the Unit lane nothing. Without this case, "move it to
  # guards-test-selection" would be indistinguishable from "rename the file".
  expect_green "15 a harness no Gradle test drives may reference the guards" "$src"

  # 16 — the #2435 regression itself: the SAME harness, now named by a JVM test
  # exactly the way DiskPreflightScriptTest names its own.
  printf 'class LedgerHarnessTest { fun t() { runShellHarness(relativePath = "tests/scripts/orphan-harness-test.sh") } }\n' \
    > "$scan_sandbox/app/src/test/java/com/pocketshell/app/scripts/LedgerHarnessTest.kt"
  git -C "$scan_sandbox" add -A
  expect_red "16 a JVM-driven shell harness reaching the guards (#2435)" "C9" "$src"
  EXPECT_SCAN_ROOT=""

  # Case 11 (issue #2187) — THE HEADLINE MUTATION: restore `if: always()`.
  # That is how a concurrency-superseded run manufactured a red required
  # check: the job kept running and `!= success` folded `cancelled` into
  # failure. This case is the reproduce-first proof.
  local m11="$sandbox/m11.yml"
  awk '
    /^  unit-gate:$/ { ingate = 1 }
    ingate && /^    if:/ { print "    if: always()"; ingate = 0; next }
    { print }
  ' "$src" > "$m11"
  cmp -s "$src" "$m11" && st_bad "11 mutation did not apply" || \
    expect_red "11 if: always() restored (cancelled becomes failure)" "C11" "$m11"

  # Case 12 — no job-level if: defaults to success(), so a failed needed job
  # skips the gate and a cancelled run leaves the required check pending.
  local m12="$sandbox/m12.yml"
  awk '
    /^  unit-gate:$/ { ingate = 1 }
    ingate && /^    if:/ { next }
    /^  [A-Za-z0-9_-]+:/ && !/^  unit-gate:$/ { ingate = 0 }
    { print }
  ' "$src" > "$m12"
  cmp -s "$src" "$m12" && st_bad "12 mutation did not apply" || \
    expect_red "12 job-level if: removed" "C11" "$m12"

  # Case 13 — `always() && !cancelled()` looks like the fix but GitHub
  # evaluates it (because of always()) and a false result is `skipped`,
  # which a required-check ruleset can treat as passing.
  local m13="$sandbox/m13.yml"
  awk '
    /^  unit-gate:$/ { ingate = 1 }
    ingate && /^    if:/ { print "    if: ${{ always() && !cancelled() }}"; ingate = 0; next }
    { print }
  ' "$src" > "$m13"
  cmp -s "$src" "$m13" && st_bad "13 mutation did not apply" || \
    expect_red "13 if: always() && !cancelled() (skipped, not cancelled)" "C11" "$m13"

  # Case 14 — the loop only reddens on `failure`, so skipped/empty/cancelled
  # while running pass. That is the original #2060 hole reopened while
  # "fixing" #2187.
  local m14="$sandbox/m14.yml"
  sed 's/if \[\[ "\$result" != "success" \]\]; then/if [[ "$result" == "failure" ]]; then/' \
    "$src" > "$m14"
  cmp -s "$src" "$m14" && st_bad "14 mutation did not apply" || \
    expect_red "14 loop only treats failure as bad" "C13" "$m14"

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
