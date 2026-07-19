#!/usr/bin/env bash
# Issue #1646: prove a green unit-test run actually EXECUTED tests.
#
# THE GAP THIS CLOSES
# -------------------
# Gradle prints test counts ONLY on failure. A green `:app:testReleaseUnitTest`
# prints no count at all, and tests.yml used to upload the result XML only
# `if: failure()`. So on a green run there was NO artifact and NO log line
# answering the one question that separates a real pass from a vacuous one:
# "how many tests actually ran?"
#
# On 2026-07-16 that cost a red `main` three separate ways, all the same shape
# (a confident green over nothing) — and in ALL THREE the exit code and the
# BUILD SUCCESSFUL banner said green. Only the executed count exposed the truth:
#
#   1. WRONG TASK.   An integration gate ran :app:testDebugUnitTest (green,
#                    3906/0) and merged. CI's required Unit job runs `test` —
#                    BOTH variants — and :app:testReleaseUnitTest was red.
#                    -> caught here by REQUIRED-TASK COVERAGE: a module with
#                       test sources must produce results for EVERY expected
#                       variant task; a missing results dir is a hard failure.
#
#   2. UP-TO-DATE.   Gradle skips a *passing* test task on re-run (while a
#                    *failing* one always re-executes), manufacturing exactly
#                    the shape "1 fail, then a long green streak". Four
#                    `BUILD SUCCESSFUL in 3s` runs executed ZERO tests and two
#                    actors reasoned from them; both were wrong.
#                    -> caught here by --newer-than: the XML must be newer than
#                       a marker touched at the start of the run. Stale XML left
#                       behind by an UP-TO-DATE task fails.
#
#   3. KILLED PROC.  A nohup'd gate died at generateDebugResources and reported
#                    exit 0. Zero XML files.
#                    -> caught here by the same coverage rule: no XML, no pass.
#
# This is G3 ("ban '0 tests completed / all skipped' as a pass") applied to the
# GATE ITSELF rather than to a test, and the mechanical backstop for the
# process.md rules in a8af5d2d + 8dc16024 ("--rerun-tasks is mandatory AND the
# executed test count must be asserted from the result XML, every run").
#
# EXECUTED, NOT DECLARED
# ----------------------
# executed = tests - skipped, per <testsuite>. A suite that declares 40 tests
# and skips all 40 executed ZERO and is reported as such — that is G3's actual
# wording, enforced mechanically.
#
# TASK-SCOPED, NOT REPO-WIDE (issue #1646 acceptance criterion)
# ------------------------------------------------------------
# `NO-SOURCE` on :shared:test-support is LEGITIMATE — it is a testImplementation
# -only support module with no tests of its own. An on-call had to verify that
# by hand. This script ENCODES the distinction instead of allowlisting it: a
# module's expectation is DERIVED from whether it actually has test sources
# under src/test/. No test sources -> NO-SOURCE is expected and fine. Has test
# sources -> every expected variant task must show a non-zero executed count.
# The allowlist maintains itself.
#
# THE DECLARATION OVERRIDES THE DERIVATION
# ----------------------------------------
# Derivation alone has an inverse hole: a module that quietly LOSES all its test
# sources becomes, by that same rule, "legitimately empty" — every one of its
# tests vanishes and the guard goes green. So an EXPLICIT entry in the floors
# manifest is checked FIRST and wins: it is a deliberate statement that the task
# HAS tests, and such a task is NEVER granted the NO-SOURCE exemption. Deleting
# a module's tests for real therefore requires deleting its floors line in the
# same change — an intentional act, which is the point.
#
# WHAT THIS SCRIPT DOES NOT COVER
# -------------------------------
# Modules with no declared floor and no test sources are exempt, and their tests
# disappearing is indistinguishable here from their never having existed. The
# floors manifest is the only mechanism that makes a module's tests mandatory;
# a module you care about that way must be listed in it.
#
# NOT TOO STRICT (G6)
# -------------------
# A check that red-lights honest runs is worse than the gap, because people then
# ignore it. Floors are deliberately conservative (default 1; explicit floors in
# scripts/executed-test-count-floors.txt sit far below real counts) and NOT an
# exact-count ratchet, so adding or removing a few tests never reddens CI.
#
# Usage:
#   scripts/check-executed-test-counts.sh [--newer-than FILE] [--gradle-log FILE]
#                                         [--root DIR]
#   scripts/check-executed-test-counts.sh --self-test
#
# Options:
#   --newer-than FILE  require each task's XML to be strictly newer than FILE
#                      (the --rerun-tasks / UP-TO-DATE-skip enforcement).
#                      Omit to only check coverage + counts.
#   --gradle-log FILE  ALSO scan Gradle's console output for test tasks that did
#                      not execute (UP-TO-DATE / FROM-CACHE / SKIPPED), and
#                      enforce that NO-SOURCE is claimed only by a module that
#                      genuinely has no test sources. Needed because this repo
#                      sets org.gradle.caching=true: a FROM-CACHE test task
#                      unpacks last run's XML with a FRESH mtime, so it defeats
#                      --newer-than while executing zero tests.
#                      The scan first PROVES it parsed the log: every expected
#                      task must appear as a recognised `> Task :…` line, and an
#                      empty/truncated/reformatted log is a hard failure rather
#                      than a silent pass. Requires `--console=plain`.
#   --root DIR         repo root to inspect (default: this script's repo).
#   --self-test        synthetic red->green proof; no Gradle, no Android SDK.
#
# Exit: 0 all expected tasks executed at/above their floor; 1 otherwise.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FLOORS_REL="scripts/executed-test-count-floors.txt"
DEFAULT_FLOOR=1

usage() {
  sed -n '/^# Usage:/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# XML parsing: executed = tests - skipped, summed over every TEST-*.xml in a
# task's result directory.
# ---------------------------------------------------------------------------
executed_count_for_dir() {
  local dir="$1"
  local total=0 file line tests skipped

  while IFS= read -r file; do
    # Gradle emits the <testsuite ...> attributes on a single line.
    line="$(grep -m1 -o '<testsuite [^>]*>' "$file" 2>/dev/null)" || line=""
    [ -n "$line" ] || continue
    tests="$(printf '%s' "$line" | grep -o 'tests="[0-9]*"' | head -1 | tr -dc '0-9')"
    skipped="$(printf '%s' "$line" | grep -o 'skipped="[0-9]*"' | head -1 | tr -dc '0-9')"
    [ -n "$tests" ] || tests=0
    [ -n "$skipped" ] || skipped=0
    total=$(( total + tests - skipped ))
  done < <(find "$dir" -maxdepth 1 -name 'TEST-*.xml' -type f 2>/dev/null)

  printf '%s' "$total"
}

# True when at least one TEST-*.xml under $1 is strictly newer than marker $2.
dir_has_fresh_xml() {
  local dir="$1" marker="$2"
  [ -n "$(find "$dir" -maxdepth 1 -name 'TEST-*.xml' -type f -newer "$marker" -print -quit 2>/dev/null)" ]
}

in_list() {
  local needle="$1"; shift
  local item
  for item in "$@"; do
    [ "$item" = "$needle" ] && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Module discovery + expectation derivation.
# ---------------------------------------------------------------------------
modules_from_settings() {
  local root="$1"
  local settings="$root/settings.gradle.kts"
  [ -f "$settings" ] || return 0
  # include(":shared:core-ssh") -> shared/core-ssh
  grep -oE '^[[:space:]]*include\("[^"]+"\)' "$settings" |
    sed -E 's/.*include\("//; s/"\)//' |
    sed -E 's/^://; s/:/\//g'
}

module_has_test_sources() {
  local dir="$1"
  [ -d "$dir/src/test" ] || return 1
  [ -n "$(find "$dir/src/test" -type f \( -name '*.kt' -o -name '*.java' \) -print -quit 2>/dev/null)" ]
}

# Android application/library modules produce testDebugUnitTest +
# testReleaseUnitTest; a plain JVM module produces `test`.
expected_tasks_for_module() {
  local dir="$1"
  local build_file="$dir/build.gradle.kts"
  if [ -f "$build_file" ] && grep -qE 'plugins\.android\.(application|library)|com\.android\.(application|library)' "$build_file"; then
    echo "testDebugUnitTest"
    echo "testReleaseUnitTest"
  else
    echo "test"
  fi
}

# The floor EXPLICITLY declared for a task, or empty when the task is not listed.
# Exact string match on field 1 — no regex, so a task path's ':' and '-' can
# never be misread as metacharacters.
#
# An entry here is a DELIBERATE DECLARATION that the task has tests, so its
# presence/absence is load-bearing for the NO-SOURCE exemption below, not just
# for the count threshold. Hence "explicit" is distinguishable from "defaulted".
explicit_floor_for_task() {
  local root="$1" task_path="$2"
  local floors="$root/$FLOORS_REL"
  [ -f "$floors" ] || return 0
  awk -v want="$task_path" '
    /^[[:space:]]*#/ { next }
    $1 == want && $2 ~ /^[0-9]+$/ { print $2; exit }
  ' "$floors" 2>/dev/null
}

# ---------------------------------------------------------------------------
# The check.
# ---------------------------------------------------------------------------
check_root() {
  local root="$1" marker="${2:-}" gradle_log="${3:-}"
  local violations=() rows=() module task task_path results_dir count floor
  local total_executed=0 checked_tasks=0 nosource_modules=()
  local expected_task_paths=() testfree_task_paths=() sourceless_task_paths=()

  local module_list
  module_list="$(modules_from_settings "$root")"
  if [ -z "$module_list" ]; then
    echo "FAIL: no Gradle modules discovered from $root/settings.gradle.kts" >&2
    return 1
  fi

  while IFS= read -r module; do
    [ -n "$module" ] || continue
    local dir="$root/$module"
    [ -d "$dir" ] || continue
    local task_prefix=":${module//\//:}"

    local has_sources=0
    module_has_test_sources "$dir" && has_sources=1
    local module_tasks=0 module_exempt=0

    while IFS= read -r task; do
      [ -n "$task" ] || continue
      task_path="$task_prefix:$task"
      results_dir="$dir/build/test-results/$task"
      module_tasks=$(( module_tasks + 1 ))

      # ---- is this task REQUIRED to produce counts? -----------------------
      # Two independent ways to be required, and THE DECLARATION WINS:
      #   1. the module has test sources under src/test/  (derived), or
      #   2. the task has an EXPLICIT floor in the floors manifest (declared).
      #
      # (2) is consulted FIRST and is not overridable by (1). That ordering is
      # what makes "a module quietly LOSES all its test sources" a hard failure
      # instead of a silent promotion into the "legitimately empty" bucket: a
      # floors entry is a deliberate statement that this task HAS tests, so if
      # its sources vanish the two sources of truth disagree — and a
      # disagreement about whether tests exist at all is precisely the thing
      # this script refuses to resolve in favour of green.
      local explicit_floor
      explicit_floor="$(explicit_floor_for_task "$root" "$task_path")"

      if [ "$has_sources" -eq 0 ] && [ -z "$explicit_floor" ]; then
        # Encoded, not allowlisted: derived from the absence of src/test
        # sources AND the absence of a declaration. This is the legitimate
        # :shared:test-support case — NO-SOURCE must not trip the check.
        testfree_task_paths+=("$task_path")
        module_exempt=$(( module_exempt + 1 ))
        continue
      fi

      expected_task_paths+=("$task_path")
      floor="${explicit_floor:-$DEFAULT_FLOOR}"
      checked_tasks=$(( checked_tasks + 1 ))

      if [ "$has_sources" -eq 0 ]; then
        # Declared in the floors manifest, but its test sources are GONE.
        sourceless_task_paths+=("$task_path")
        rows+=("$(printf '  %-46s %10s  SOURCES GONE' "$task_path" "-")")
        violations+=("$task_path is declared in $FLOORS_REL with a floor of $floor — a deliberate statement that this task HAS tests — but $module has NO test sources under src/test/. Every one of its tests vanished, and a task with a declared floor is NEVER granted the 'legitimately empty' NO-SOURCE exemption. Either the sources were deleted/moved by mistake, or the removal is intentional and its line must be removed from $FLOORS_REL in the same change.")
        continue
      fi

      if [ ! -d "$results_dir" ]; then
        rows+=("$(printf '  %-46s %10s  MISSING' "$task_path" "-")")
        violations+=("$task_path executed 0 tests: no result XML at ${results_dir#"$root"/} — the task did not run (wrong task selected, build died before it, or the process was killed). A task that produces no results cannot pass.")
        continue
      fi

      if [ -n "$marker" ] && ! dir_has_fresh_xml "$results_dir" "$marker"; then
        rows+=("$(printf '  %-46s %10s  STALE' "$task_path" "-")")
        violations+=("$task_path executed 0 tests THIS RUN: every TEST-*.xml in ${results_dir#"$root"/} predates the run marker — the task was UP-TO-DATE/skipped and the reports are left over from an earlier run. Use --rerun-tasks.")
        continue
      fi

      count="$(executed_count_for_dir "$results_dir")"
      total_executed=$(( total_executed + count ))

      if [ "$count" -lt "$floor" ]; then
        rows+=("$(printf '  %-46s %10s  BELOW FLOOR (min %s)' "$task_path" "$count" "$floor")")
        if [ "$count" -eq 0 ]; then
          violations+=("$task_path executed 0 tests: result XML exists but every test was skipped or filtered out. A green run over zero executed tests is not evidence (G3).")
        else
          violations+=("$task_path executed $count tests, below its floor of $floor (see $FLOORS_REL). Either tests were filtered out, or the floor needs a deliberate update.")
        fi
        continue
      fi

      rows+=("$(printf '  %-46s %10s  ok' "$task_path" "$count")")
    done < <(expected_tasks_for_module "$dir")

    # Reported as exempt only when EVERY one of its tasks was exempt — i.e. no
    # test sources AND nothing declared for it in the floors manifest.
    if [ "$module_tasks" -gt 0 ] && [ "$module_exempt" -eq "$module_tasks" ]; then
      nosource_modules+=("$task_prefix")
    fi
  done <<< "$module_list"

  # ---- Gradle console scan: a no-op task must FAIL, not pass silently -----
  # Needed on top of the XML check because org.gradle.caching=true means a
  # FROM-CACHE test task unpacks last run's XML with a fresh mtime: non-zero
  # counts, newer than the marker, ZERO tests actually executed this run.
  # This is also where the NO-SOURCE distinction gets ENCODED rather than
  # eyeballed: NO-SOURCE is accepted only from a module with no test sources.
  if [ -n "$gradle_log" ]; then
    if [ ! -f "$gradle_log" ]; then
      echo "FAIL: --gradle-log file not found: $gradle_log" >&2
      return 1
    fi
    # ---- FIRST: prove the log was actually PARSED (absence of evidence) ----
    # The scan below only greps for BAD outcomes, so on its own "healthy run"
    # and "I read nothing" are indistinguishable — it would report ok for an
    # empty, truncated, colourised, or reformatted log while the ONLY defense
    # against the FROM-CACHE disguise silently disappeared. (FROM-CACHE XML is
    # fresh and carries real counts, so --newer-than and the count check both
    # pass over it; the console outcome is the sole witness.)
    #
    # So: positively confirm every task we expect to see was OBSERVED in the
    # log as a recognised `> Task :…` line, and hard-fail if not. This is the
    # script's own rule — a guard that cannot distinguish "I checked and it is
    # fine" from "I could not check" must not report ok — applied to itself.
    local observed_task_paths=()
    while IFS= read -r log_task; do
      [ -n "$log_task" ] || continue
      observed_task_paths+=("$log_task")
    done < <(grep -E '^> Task (:[A-Za-z0-9_:.-]*test[A-Za-z0-9_]*UnitTest|:[A-Za-z0-9_:.-]*:test)( [A-Z][A-Z-]*)?$' "$gradle_log" 2>/dev/null |
             sed -E 's/^> Task //; s/ [A-Z][A-Z-]*$//' | sort -u)

    if [ ${#observed_task_paths[@]} -eq 0 ]; then
      {
        echo
        echo "FAIL: cannot verify the run from $gradle_log (issue #1646)."
        echo "  - The log contains NO recognisable '> Task :…test…' line, so the console"
        echo "    scan read NOTHING. That is not a pass: a FROM-CACHE test task restores"
        echo "    last run's XML with a fresh mtime and real counts, so the console"
        echo "    outcome is the ONLY thing that can expose zero tests executed. An"
        echo "    unparsable log means that defense is gone, and 'I could not check' must"
        echo "    never read the same as 'I checked and it is fine'."
        echo "  - Likely cause: the tee/--console=plain contract broke (colourised or"
        echo "    rich console, a truncated/empty log, or Gradle changed its task-line"
        echo "    format). Re-run './gradlew test --console=plain' and tee the output."
      } >&2
      return 1
    fi

    local missing_from_log=()
    local exp_task
    for exp_task in "${expected_task_paths[@]:-}"; do
      [ -n "$exp_task" ] || continue
      # A task whose sources vanished is already reported by the B1 rule above;
      # do not pile a second, more confusing violation onto the same cause.
      in_list "$exp_task" "${sourceless_task_paths[@]:-}" && continue
      if ! in_list "$exp_task" "${observed_task_paths[@]}"; then
        missing_from_log+=("$exp_task")
      fi
    done
    if [ ${#missing_from_log[@]} -gt 0 ]; then
      local m
      for m in "${missing_from_log[@]}"; do
        violations+=("$m never appears in $gradle_log as a '> Task $m' line, so the console scan could not confirm it executed. Either the task never ran, or the log is truncated/reformatted and the scan is blind — both mean this run is unproven, not fine.")
      done
    fi

    local log_task log_outcome
    while read -r log_task log_outcome; do
      [ -n "$log_task" ] || continue
      # Already reported by the B1 sources-gone rule with the precise cause.
      in_list "$log_task" "${sourceless_task_paths[@]:-}" && continue
      if in_list "$log_task" "${expected_task_paths[@]:-}"; then
        case "$log_outcome" in
          NO-SOURCE)
            violations+=("$log_task reported NO-SOURCE, but the module HAS test sources under src/test/ — its tests were not compiled or not picked up. NO-SOURCE is only legitimate for a module with no tests of its own (e.g. :shared:test-support).")
            ;;
          *)
            violations+=("$log_task did not execute: Gradle reported $log_outcome. A skipped/no-op test task cannot pass the gate — re-run with --rerun-tasks (process.md 8dc16024).")
            ;;
        esac
      elif in_list "$log_task" "${testfree_task_paths[@]:-}"; then
        # The legitimate NO-SOURCE case, verified against the source tree.
        [ "$log_outcome" = "NO-SOURCE" ] || true
      fi
    done < <(grep -oE '^> Task (:[A-Za-z0-9_:.-]*test[A-Za-z0-9_]*UnitTest|:[A-Za-z0-9_:.-]*:test) (UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE)$' "$gradle_log" 2>/dev/null |
             sed -E 's/^> Task //')
  fi

  # ---- report (echoed on GREEN too — that is the point of issue #1646) ----
  {
    echo "Executed test counts (issue #1646) — executed = tests - skipped, from build/test-results/*/TEST-*.xml"
    echo
    printf '  %-46s %10s  %s\n' "TASK" "EXECUTED" "STATUS"
    local row
    for row in "${rows[@]}"; do echo "$row"; done
    echo
    if [ ${#nosource_modules[@]} -gt 0 ]; then
      echo "  NO-SOURCE (no src/test sources — legitimately exempt): ${nosource_modules[*]}"
    fi
    echo "  TOTAL EXECUTED: $total_executed across $checked_tasks task(s)"
  }

  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### Executed test counts (issue #1646)"
      echo
      echo "| Task | Executed | Status |"
      echo "| --- | ---: | --- |"
      for row in "${rows[@]}"; do
        # "  <task>   <count>  <status>" -> markdown row
        awk '{ task=$1; count=$2; $1=""; $2=""; sub(/^[ \t]+/,""); printf("| `%s` | %s | %s |\n", task, count, $0) }' <<< "$row"
      done
      echo
      if [ ${#nosource_modules[@]} -gt 0 ]; then
        echo "NO-SOURCE (no \`src/test\` sources — legitimately exempt): ${nosource_modules[*]}"
        echo
      fi
      echo "**Total executed: $total_executed** across $checked_tasks task(s)."
    } >> "$GITHUB_STEP_SUMMARY"
  fi

  if [ ${#violations[@]} -gt 0 ]; then
    {
      echo
      echo "FAIL: a green build is not evidence that tests ran (issue #1646)."
      local v
      for v in "${violations[@]}"; do echo "  - $v"; done
    } >&2
    return 1
  fi

  return 0
}

# ---------------------------------------------------------------------------
# Self-test: synthetic red->green proof. No Gradle, no Android SDK, no network.
# ---------------------------------------------------------------------------
SELFTEST_RUN=0
SELFTEST_FAIL=0

selftest_assert() {
  local name="$1" expected="$2" actual="$3"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if [ "$expected" = "$actual" ]; then
    echo "  ok   $name (exit $actual)"
  else
    echo "  FAIL $name: expected exit $expected, got $actual" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi
}

# Build a synthetic repo: :app (android, has test sources) and
# :shared:test-support (android, NO test sources — the legit NO-SOURCE case).
selftest_make_root() {
  local root="$1"
  mkdir -p "$root/app/src/test/java" "$root/shared/test-support/src/main/java"
  cat > "$root/settings.gradle.kts" <<'EOF'
include(":app")
include(":shared:test-support")
EOF
  cat > "$root/app/build.gradle.kts" <<'EOF'
plugins { alias(libs.plugins.android.application) }
EOF
  cat > "$root/shared/test-support/build.gradle.kts" <<'EOF'
plugins { alias(libs.plugins.android.library) }
EOF
  echo "class SomeTest" > "$root/app/src/test/java/SomeTest.kt"
  echo "class SettlePump" > "$root/shared/test-support/src/main/java/SettlePump.kt"
}

# Write a task result dir with $count executed tests ($skipped skipped).
selftest_write_results() {
  local root="$1" task="$2" count="$3" skipped="${4:-0}"
  local dir="$root/app/build/test-results/$task"
  mkdir -p "$dir"
  cat > "$dir/TEST-SomeTest.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SomeTest" tests="$(( count + skipped ))" skipped="$skipped" failures="0" errors="0" time="0.1">
  <properties/>
</testsuite>
EOF
}

# The BASE (pre-fix) gate: exit code only. This is what CI actually did before
# issue #1646 — and it is why all three incidents shipped green.
selftest_base_gate() {
  local gradle_exit="$1"
  return "$gradle_exit"
}

run_self_test() {
  echo "check-executed-test-counts.sh --self-test (issue #1646)"
  echo

  local tmp; tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  # -- T1 RED ON BASE: reproduce the vacuous pass ---------------------------
  # A required test task no-ops: Gradle exits 0 and writes no XML. The base
  # gate (exit code only) says GREEN. That is the bug, reproduced.
  local root="$tmp/t1"; mkdir -p "$root"; selftest_make_root "$root"
  selftest_write_results "$root" "testDebugUnitTest" 3906
  # testReleaseUnitTest never ran (incident 1: wrong task) — no results dir.
  selftest_base_gate 0
  selftest_assert "T1 base gate (exit code only) PASSES the vacuous run — the bug" 0 "$?"

  # -- T2 GREEN WITH FIX: the same no-op now fails, naming task + zero count -
  local out; out="$(check_root "$root" 2>&1)"; local rc=$?
  selftest_assert "T2 fix FAILS the same vacuous run" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q ':app:testReleaseUnitTest' <<< "$out" && grep -q 'executed 0 tests' <<< "$out"; then
    echo "  ok   T2 message names the task and its zero count"
  else
    echo "  FAIL T2: message did not name :app:testReleaseUnitTest and its zero count" >&2
    echo "$out" | sed 's/^/       /' >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi

  # -- T3 THE LOAD-BEARING NEGATIVE (G6): a genuine green STILL PASSES ------
  # If the check red-lights honest runs it is worse than the gap.
  root="$tmp/t3"; mkdir -p "$root"; selftest_make_root "$root"
  selftest_write_results "$root" "testDebugUnitTest" 3906
  selftest_write_results "$root" "testReleaseUnitTest" 3906
  out="$(check_root "$root" 2>&1)"; rc=$?
  selftest_assert "T3 a genuine full green run PASSES" 0 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'TOTAL EXECUTED: 7812' <<< "$out"; then
    echo "  ok   T3 counts are reported on GREEN (the artifact-free evidence)"
  else
    echo "  FAIL T3: executed counts not reported on green" >&2
    echo "$out" | sed 's/^/       /' >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi

  # -- T4 NO-SOURCE on a genuinely test-free module does NOT trip the check --
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'NO-SOURCE.*:shared:test-support' <<< "$out"; then
    echo "  ok   T4 :shared:test-support classified NO-SOURCE, exempt, not a failure"
  else
    echo "  FAIL T4: :shared:test-support was not reported as an exempt NO-SOURCE module" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi
  # ...and the exemption is DERIVED, not allowlisted: give it test sources and
  # the very same module must now be required to produce results.
  mkdir -p "$root/shared/test-support/src/test/java"
  echo "class Nope" > "$root/shared/test-support/src/test/java/Nope.kt"
  out="$(check_root "$root" 2>&1)"; rc=$?
  selftest_assert "T4b exemption is DERIVED: same module WITH test sources now FAILS" 1 "$rc"
  rm -rf "$root/shared/test-support/src/test"

  # -- T5 UP-TO-DATE / stale XML (incident 2) -------------------------------
  # Gradle skips a *passing* test task on re-run and leaves last run's XML
  # behind. Counts alone would pass; freshness catches it.
  root="$tmp/t5"; mkdir -p "$root"; selftest_make_root "$root"
  selftest_write_results "$root" "testDebugUnitTest" 3906
  selftest_write_results "$root" "testReleaseUnitTest" 3906
  find "$root" -name 'TEST-*.xml' -exec touch -d '2020-01-01 00:00:00' {} +
  local marker="$tmp/t5-marker"; touch -d '2021-01-01 00:00:00' "$marker"
  out="$(check_root "$root" "$marker" 2>&1)"; rc=$?
  selftest_assert "T5 UP-TO-DATE skip (stale XML older than the run marker) FAILS" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'UP-TO-DATE/skipped' <<< "$out"; then
    echo "  ok   T5 message identifies the UP-TO-DATE skip"
  else
    echo "  FAIL T5: message did not identify the stale/UP-TO-DATE cause" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi
  # Freshly re-executed XML under the same marker passes (G6 negative case).
  find "$root" -name 'TEST-*.xml' -exec touch -d '2022-01-01 00:00:00' {} +
  check_root "$root" "$marker" >/dev/null 2>&1
  selftest_assert "T5b freshly re-executed XML PASSES the same marker check" 0 "$?"

  # -- T6 zero executed because everything was SKIPPED (G3 verbatim) --------
  root="$tmp/t6"; mkdir -p "$root"; selftest_make_root "$root"
  selftest_write_results "$root" "testDebugUnitTest" 0 40   # 40 declared, 40 skipped
  selftest_write_results "$root" "testReleaseUnitTest" 3906
  out="$(check_root "$root" 2>&1)"; rc=$?
  selftest_assert "T6 XML present but ALL tests skipped -> 0 executed -> FAILS" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'every test was skipped or filtered out' <<< "$out"; then
    echo "  ok   T6 message identifies the all-skipped vacuous pass"
  else
    echo "  FAIL T6: message did not identify the all-skipped cause" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi

  # -- T7 below-floor (a filter that matched almost nothing) ----------------
  root="$tmp/t7"; mkdir -p "$root/scripts"; selftest_make_root "$root"
  cat > "$root/$FLOORS_REL" <<'EOF'
:app:testDebugUnitTest 1000
:app:testReleaseUnitTest 1000
EOF
  selftest_write_results "$root" "testDebugUnitTest" 3
  selftest_write_results "$root" "testReleaseUnitTest" 3906
  out="$(check_root "$root" 2>&1)"; rc=$?
  selftest_assert "T7 executed 3 tests, below the 1000 floor -> FAILS" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'below its floor of 1000' <<< "$out"; then
    echo "  ok   T7 message names the floor"
  else
    echo "  FAIL T7: message did not name the floor" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi
  # ...and a normal count under the same floors file still passes (G6).
  selftest_write_results "$root" "testDebugUnitTest" 3906
  check_root "$root" >/dev/null 2>&1
  selftest_assert "T7b a normal count under the same floors file PASSES" 0 "$?"

  # -- T8 FROM-CACHE: fresh XML, real counts, ZERO tests executed -----------
  # org.gradle.caching=true means a cached test task unpacks last run's XML with
  # a FRESH mtime. Counts pass, the marker passes, nothing ran. Only the console
  # outcome exposes it.
  root="$tmp/t8"; mkdir -p "$root"; selftest_make_root "$root"
  selftest_write_results "$root" "testDebugUnitTest" 3906
  selftest_write_results "$root" "testReleaseUnitTest" 3906
  local log="$tmp/t8.log"
  cat > "$log" <<'EOF'
> Task :app:testDebugUnitTest
> Task :app:testReleaseUnitTest FROM-CACHE
> Task :shared:test-support:testDebugUnitTest NO-SOURCE
> Task :shared:test-support:testReleaseUnitTest NO-SOURCE
BUILD SUCCESSFUL in 41s
EOF
  # Counts alone cannot see it: the XML-only check passes.
  check_root "$root" >/dev/null 2>&1
  selftest_assert "T8 counts-only check PASSES the FROM-CACHE run — why the log scan exists" 0 "$?"
  out="$(check_root "$root" "" "$log" 2>&1)"; rc=$?
  selftest_assert "T8b log scan FAILS the FROM-CACHE run (zero tests executed)" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q ':app:testReleaseUnitTest did not execute: Gradle reported FROM-CACHE' <<< "$out"; then
    echo "  ok   T8b message names the task and the FROM-CACHE outcome"
  else
    echo "  FAIL T8b: message did not name the task/outcome" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi

  # -- T9 NO-SOURCE on the genuinely test-free module does NOT trip it ------
  # This is the on-call's manual 2026-07-16 inspection, encoded.
  cat > "$log" <<'EOF'
> Task :app:testDebugUnitTest
> Task :app:testReleaseUnitTest
> Task :shared:test-support:testDebugUnitTest NO-SOURCE
> Task :shared:test-support:testReleaseUnitTest NO-SOURCE
BUILD SUCCESSFUL in 41s
EOF
  check_root "$root" "" "$log" >/dev/null 2>&1
  selftest_assert "T9 NO-SOURCE on a genuinely test-free module PASSES (not tripped)" 0 "$?"

  # -- T10 ...but NO-SOURCE from a module that HAS test sources FAILS -------
  # Derived, not allowlisted: :app has test sources, so its NO-SOURCE is a bug
  # (tests not compiled / not picked up), not an exemption.
  cat > "$log" <<'EOF'
> Task :app:testDebugUnitTest NO-SOURCE
> Task :app:testReleaseUnitTest
> Task :shared:test-support:testDebugUnitTest NO-SOURCE
BUILD SUCCESSFUL in 41s
EOF
  out="$(check_root "$root" "" "$log" 2>&1)"; rc=$?
  selftest_assert "T10 NO-SOURCE from a module WITH test sources FAILS" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'but the module HAS test sources' <<< "$out"; then
    echo "  ok   T10 message distinguishes an illegitimate NO-SOURCE from the exempt one"
  else
    echo "  FAIL T10: message did not distinguish the illegitimate NO-SOURCE" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi

  # -- T11 UP-TO-DATE in the console (incident 2, as CI would see it) -------
  cat > "$log" <<'EOF'
> Task :app:testDebugUnitTest UP-TO-DATE
> Task :app:testReleaseUnitTest UP-TO-DATE
BUILD SUCCESSFUL in 3s
EOF
  out="$(check_root "$root" "" "$log" 2>&1)"; rc=$?
  selftest_assert "T11 'BUILD SUCCESSFUL in 3s' with both tasks UP-TO-DATE FAILS" 1 "$rc"

  # -- T12 the load-bearing negative (G6): a real full run STILL passes -----
  cat > "$log" <<'EOF'
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
> Task :app:testReleaseUnitTest
> Task :shared:test-support:testDebugUnitTest NO-SOURCE
> Task :shared:test-support:testReleaseUnitTest NO-SOURCE
> Task :shared:test-support:compileDebugUnitTestKotlin NO-SOURCE
BUILD SUCCESSFUL in 6m 12s
EOF
  find "$root" -name 'TEST-*.xml' -exec touch {} +
  marker="$tmp/t12-marker"; touch -d '2020-01-01 00:00:00' "$marker"
  out="$(check_root "$root" "$marker" "$log" 2>&1)"; rc=$?
  selftest_assert "T12 a genuine full run PASSES all three checks together" 0 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'TOTAL EXECUTED: 7812' <<< "$out"; then
    echo "  ok   T12 counts reported on green with marker + log scan active"
  else
    echo "  FAIL T12: counts not reported on the full green path" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi

  # -- T13 (B1) a module with a DECLARED floor whose test sources VANISH -----
  # The inverse hole in the derivation rule: without the declaration-wins
  # ordering, deleting :app/src/test makes :app "legitimately empty" and all
  # ~3900 of its tests disappear while the guard reports green.
  root="$tmp/t13"; mkdir -p "$root/scripts"; selftest_make_root "$root"
  cat > "$root/$FLOORS_REL" <<'EOF'
:app:testDebugUnitTest 1000
:app:testReleaseUnitTest 1000
EOF
  selftest_write_results "$root" "testDebugUnitTest" 3906
  selftest_write_results "$root" "testReleaseUnitTest" 3906
  # ...and now every test source is gone, along with the results.
  rm -rf "$root/app/src/test" "$root/app/build/test-results"
  out="$(check_root "$root" 2>&1)"; rc=$?
  selftest_assert "T13 module with a DECLARED floor loses all test sources -> FAILS" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'NO test sources under src/test/' <<< "$out" &&
     grep -q ':app:testDebugUnitTest is declared in' <<< "$out"; then
    echo "  ok   T13 message names the task and the vanished sources"
  else
    echo "  FAIL T13: message did not name the declared task / vanished sources" >&2
    echo "$out" | sed 's/^/       /' >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi
  # ...and it must NOT be reported as a legitimately-exempt NO-SOURCE module.
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'NO-SOURCE.*legitimately exempt.*:app' <<< "$out"; then
    echo "  FAIL T13b: :app was granted the 'legitimately exempt' NO-SOURCE bucket" >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  else
    echo "  ok   T13b a declared task is never granted the NO-SOURCE exemption"
  fi
  # ...and the DECLARATION is what makes it mandatory (G6: not a blanket ban on
  # removing tests). Drop its floors lines and the same tree is legitimately
  # exempt again — removal stays possible, but only as a deliberate act.
  cat > "$root/$FLOORS_REL" <<'EOF'
# every :app floor deliberately removed alongside its tests
EOF
  out="$(check_root "$root" 2>&1)"; rc=$?
  selftest_assert "T13c same tree, floors line deliberately removed -> PASSES (G6)" 0 "$rc"

  # -- T14 (B2) an EMPTY console log must FAIL, not silently pass ------------
  # The scan only greps for BAD outcomes, so without a parse-confirmation
  # "healthy" and "I read nothing" are indistinguishable — and FROM-CACHE XML
  # passes both the count check and the marker, so the console scan is the ONLY
  # witness. If it goes blind, it must say so.
  root="$tmp/t14"; mkdir -p "$root"; selftest_make_root "$root"
  selftest_write_results "$root" "testDebugUnitTest" 3906
  selftest_write_results "$root" "testReleaseUnitTest" 3906
  log="$tmp/t14.log"
  : > "$log"
  out="$(check_root "$root" "" "$log" 2>&1)"; rc=$?
  selftest_assert "T14 EMPTY console log FAILS (absence of evidence != evidence)" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q 'NO recognisable' <<< "$out"; then
    echo "  ok   T14 message says the scan read nothing and names the likely cause"
  else
    echo "  FAIL T14: message did not report an unparsable log" >&2
    echo "$out" | sed 's/^/       /' >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi

  # -- T15 (B2) a REFORMATTED log the scan cannot read must FAIL -------------
  # The exact probe that exposed the silent pass: a FROM-CACHE line that the
  # outcome grep misses because the '> Task ' prefix is gone (a colourised or
  # rich console). Counts + marker still pass, so the run would have been
  # certified green over zero executed tests.
  cat > "$log" <<'EOF'
:app:testDebugUnitTest FROM-CACHE
:app:testReleaseUnitTest FROM-CACHE
BUILD SUCCESSFUL in 10s
EOF
  out="$(check_root "$root" "" "$log" 2>&1)"; rc=$?
  selftest_assert "T15 log without the '> Task ' prefix FAILS (scan is blind)" 1 "$rc"

  # -- T16 (B2) a TRUNCATED log (killed mid-run) must FAIL ------------------
  # Parsable, but only one of the two expected tasks was ever observed.
  cat > "$log" <<'EOF'
> Task :app:testDebugUnitTest
EOF
  out="$(check_root "$root" "" "$log" 2>&1)"; rc=$?
  selftest_assert "T16 TRUNCATED log (expected task never observed) FAILS" 1 "$rc"
  SELFTEST_RUN=$(( SELFTEST_RUN + 1 ))
  if grep -q ':app:testReleaseUnitTest never appears' <<< "$out"; then
    echo "  ok   T16 message names the task the scan could not confirm"
  else
    echo "  FAIL T16: message did not name the unconfirmed task" >&2
    echo "$out" | sed 's/^/       /' >&2
    SELFTEST_FAIL=$(( SELFTEST_FAIL + 1 ))
  fi

  # -- meta: assert the self-test itself is not vacuous ---------------------
  local expected_checks=33
  echo
  # G3 on the self-test itself: a self-test that ran ZERO cases and reported
  # PASS would be this script's own disease. Assert count > 0 explicitly before
  # the exact-count assertion, so the failure mode is named rather than implied.
  if [ "$SELFTEST_RUN" -le 0 ]; then
    echo "FAIL: self-test ran ZERO checks and would have reported PASS — the" >&2
    echo "      vacuous green this script exists to ban (issue #1646)." >&2
    return 1
  fi
  if [ "$SELFTEST_RUN" -ne "$expected_checks" ]; then
    echo "FAIL: self-test ran $SELFTEST_RUN checks, expected $expected_checks — the" >&2
    echo "      anti-vacuous-test guard must not itself pass vacuously (issue #1646)." >&2
    return 1
  fi
  if [ "$SELFTEST_FAIL" -ne 0 ]; then
    echo "FAIL: $SELFTEST_FAIL/$SELFTEST_RUN self-test checks failed." >&2
    return 1
  fi
  echo "PASS: $SELFTEST_RUN/$SELFTEST_RUN self-test checks (issue #1646)."
  return 0
}

# ---------------------------------------------------------------------------
main() {
  local root="$DEFAULT_ROOT" marker="" gradle_log="" self_test=0

  while [ $# -gt 0 ]; do
    case "$1" in
      --self-test) self_test=1; shift ;;
      --newer-than) marker="${2:-}"; shift 2 || true ;;
      --gradle-log) gradle_log="${2:-}"; shift 2 || true ;;
      --root) root="${2:-}"; shift 2 || true ;;
      -h|--help) usage; return 0 ;;
      *) echo "FAIL: unknown argument: $1" >&2; usage >&2; return 2 ;;
    esac
  done

  if [ "$self_test" -eq 1 ]; then
    run_self_test
    return $?
  fi

  if [ -n "$marker" ] && [ ! -e "$marker" ]; then
    echo "FAIL: --newer-than marker not found: $marker" >&2
    return 1
  fi

  check_root "$root" "$marker" "$gradle_log"
}

main "$@"
