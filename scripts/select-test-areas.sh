#!/usr/bin/env bash
# Host-CLI / area COVERAGE GUARD, which also scopes selection (issue #2063,
# analysis #2059).
#
# Turns a set of changed paths into a run plan: which unit test tasks, which
# `--tests` filters, and which journey classes. Coverage is INVARIANT — every
# test still runs on a bounded cadence (affected areas per push, always-tier on
# every push, full suite nightly and at the release gate); only the *when*
# becomes granular.
#
# WHAT THIS IS FOR — MEASURED, SO NOBODY READS IT AS A SPEED FEATURE
#
# Over the last 104 `main` commits: 58-62% force-full, and a typical commit
# still selects 89% of the journeys and 86% of the `:app` unit classes. That is
# **1.09x on the emulator lane and NEUTRAL-TO-SLIGHTLY-NEGATIVE on the Unit
# lane** once these guards' own ~100 s/variant (+3.3 min per full Unit run) is
# counted. Do not describe this as making tests faster, and do not tune it as
# if that were the goal.
#
# What it IS is the mechanism that can answer "is every test class still
# reachable, mapped, and actually executed" — the question behind #1851 (two
# classes in no suite), #1853 (twelve dead harnesses), #1859 (a shard truncating
# at 98 of 226) and #1509/#847 (the host-CLI wire seam). Its guards are the
# deliverable; the selection plan is a by-product of having the taxonomy.
# See docs/testing.md for the full measurement table and the merge-ordering
# constraint (#2067 must land the cheap-job step, or this is pure cost).
#
# MODES
#   (default)            print a human-readable plan for the current diff
#   --changed-file F     read changed paths from F (one per line) instead of git
#   --changed-stdin      read changed paths from stdin
#   --base REF           git base for the diff (default: merge-base origin/main)
#   --github-output      also append key=value lines to $GITHUB_OUTPUT
#   --print-plan-only    machine-readable KEY=VALUE plan on stdout, nothing else
#   --journeys           list every registered journey class with area + tier
#   --list-classes       TSV of every registered class: fqcn, area, module,
#                        source set, and its import-derived dependency areas
#   --verify-manifest    guard: the manifest is TOTAL and internally consistent
#   --coverage-invariant guard: prove the union over the cadence == all tests
#   --only I8,I9         run only those invariants (self-test mutations only —
#                        NEVER a CI mode; a filtered run says so in its verdict)
#   --self-check         --verify-manifest and --coverage-invariant together
#
# EXIT CODES
#   0  success
#   1  guard failure (verify/coverage modes) or usage error
#
# WHY THE FAIL-SAFE CANNOT BE INVERTED
#
# `pocketshell_test_area_classify` starts every path at `full` and only narrows
# on an explicit manifest match (scripts/lib/test-areas.sh). This script then
# adds three more one-way ratchets:
#   * a manifest that fails to load  => MODE=full;
#   * any path classified `full`     => MODE=full (no early exit — we keep
#     scanning so the plan can report EVERY reason, but the decision is latched);
#   * an empty diff                  => MODE=full.
# and the selected area set always contains every `always`-tier area, so the
# smallest possible selection is the always tier, never the empty set. The
# selftest mutates the manifest (delete rules, empty file, unknown path) and
# asserts the result is MORE running, never less.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${POCKETSHELL_TEST_AREAS_REPO_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
MANIFEST="${POCKETSHELL_TEST_AREAS_MANIFEST:-$SCRIPT_DIR/test-areas.txt}"
JOURNEY_SUITE="${POCKETSHELL_TEST_AREAS_JOURNEY_SUITE:-$SCRIPT_DIR/ci-journey-suite.sh}"
# Reviewed exemptions for @Test-bearing files outside the naming convention
# (#2065). Data, not an inline array, so each row carries a checkable
# justification and so the guard's own reds are self-testable.
UNCONVENTIONAL="${POCKETSHELL_TEST_AREAS_UNCONVENTIONAL:-$SCRIPT_DIR/test-unconventional-test-files.txt}"
NIGHTLY_SUITE="${POCKETSHELL_TEST_AREAS_NIGHTLY_SUITE:-$SCRIPT_DIR/nightly-extensive-suite.sh}"

# shellcheck source=lib/test-areas.sh
source "$SCRIPT_DIR/lib/test-areas.sh"

POCKETSHELL_TA_REPO_ROOT="$REPO_ROOT"

MODE="plan"
CHANGED_FILE=""
CHANGED_STDIN=0
BASE_REF=""
EMIT_GITHUB_OUTPUT=0
ONLY_INVARIANTS=""

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --changed-file) CHANGED_FILE="$2"; shift 2 ;;
    --changed-stdin) CHANGED_STDIN=1; shift ;;
    --base) BASE_REF="$2"; shift 2 ;;
    --github-output) EMIT_GITHUB_OUTPUT=1; shift ;;
    --print-plan-only) MODE="plan-only"; shift ;;
    --journeys) MODE="journeys"; shift ;;
    --list-classes) MODE="list-classes"; shift ;;
    --verify-manifest) MODE="verify"; shift ;;
    --coverage-invariant) MODE="coverage"; shift ;;
    --only) ONLY_INVARIANTS="$2"; shift 2 ;;
    --self-check) MODE="selfcheck"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Manifest load. A load failure is a FULL run, loudly.
# ---------------------------------------------------------------------------
MANIFEST_OK=1
if ! pocketshell_test_areas_load "$MANIFEST"; then
  MANIFEST_OK=0
fi

# ---------------------------------------------------------------------------
# Repo inventory helpers
# ---------------------------------------------------------------------------
tracked_files() {
  git -C "$REPO_ROOT" ls-files 2>/dev/null
}

# Journey registry entries, normalised to FQCNs (the `Class#method` split
# entries collapse to their class). Parsed with the SAME sed shape
# check-ci-journey-harness.sh uses, so the two guards cannot disagree about
# what the registry contains.
journey_registry_classes() {
  [[ -f "$JOURNEY_SUITE" ]] || return 0
  sed -nE \
    -e 's/.*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/com.pocketshell.app.proof.\1/p' \
    -e 's/.*"(com\.pocketshell\.app\.[A-Za-z0-9_.]+)(#[^"]*)?".*/\1/p' \
    "$JOURNEY_SUITE" |
    # Drop the `FQCN_PREFIX="com.pocketshell.app.proof"` assignment itself: a
    # class simple name always starts uppercase, a package segment never does.
    grep -E '\.[A-Z][A-Za-z0-9_]*$' |
    LC_ALL=C sort -u
}

# ---------------------------------------------------------------------------
# Changed paths
# ---------------------------------------------------------------------------
compute_changed_paths() {
  if [[ -n "$CHANGED_FILE" ]]; then
    cat "$CHANGED_FILE"
    return 0
  fi
  if [[ "$CHANGED_STDIN" -eq 1 ]]; then
    cat
    return 0
  fi
  local base="$BASE_REF"
  if [[ -z "$base" ]]; then
    base="$(git -C "$REPO_ROOT" merge-base origin/main HEAD 2>/dev/null)" || base=""
  fi
  if [[ -z "$base" ]]; then
    echo "select-test-areas: cannot compute a diff base (run 'git fetch origin main')" >&2
    # No base => we cannot know what changed => full run. Emit a sentinel path
    # that is guaranteed to classify as unmapped/full rather than an empty diff
    # that could be mistaken for "nothing to do".
    printf '%s\n' "__NO_DIFF_BASE__"
    return 0
  fi
  git -C "$REPO_ROOT" diff --name-only "$base"...HEAD 2>/dev/null
}

# ---------------------------------------------------------------------------
# Selection
# ---------------------------------------------------------------------------
SELECT_MODE=""          # full | scoped
declare -a SELECT_FULL_REASONS=()
declare -a SELECT_UNMAPPED=()
declare -a SELECT_CLASSIFICATION=()
SELECT_AREAS=""         # everything selected: seed closure + the always tier
SELECT_SEED_AREAS=""    # only what actually CHANGED (seeds + `couple` closure)

run_selection() {
  local -a paths=()
  local p
  while IFS= read -r p; do
    p="${p%%$'\r'}"
    [[ -z "${p// /}" ]] && continue
    paths+=("$p")
  done

  SELECT_MODE="scoped"
  SELECT_FULL_REASONS=()
  SELECT_UNMAPPED=()
  SELECT_CLASSIFICATION=()

  if [[ "$MANIFEST_OK" -ne 1 ]]; then
    SELECT_MODE="full"
    SELECT_FULL_REASONS+=("manifest failed to load: ${POCKETSHELL_TA_LOAD_ERRORS[*]:-unknown}")
  fi

  if [[ "${#paths[@]}" -eq 0 ]]; then
    SELECT_MODE="full"
    SELECT_FULL_REASONS+=("empty changed-path set — cannot prove which areas are safe to skip")
  fi

  local -A seed=()
  for p in "${paths[@]:-}"; do
    [[ -z "$p" ]] && continue
    pocketshell_test_area_classify "$p"
    SELECT_CLASSIFICATION+=("$(printf '%-6s %-20s %s' \
      "$POCKETSHELL_TEST_AREA_KIND" "${POCKETSHELL_TEST_AREA_NAME:--}" "$p")")
    case "$POCKETSHELL_TEST_AREA_KIND" in
      full)
        # Latch, but keep scanning so the plan reports every reason.
        SELECT_MODE="full"
        SELECT_FULL_REASONS+=("$p [$POCKETSHELL_TEST_AREA_REASON]")
        case "$POCKETSHELL_TEST_AREA_REASON" in
          unmapped-path|unmapped-test-path) SELECT_UNMAPPED+=("$p") ;;
        esac
        ;;
      area) seed["$POCKETSHELL_TEST_AREA_NAME"]=1 ;;
      noop) : ;;
    esac
  done

  if [[ "$SELECT_MODE" == "full" ]]; then
    SELECT_AREAS="$(printf '%s\n' "${POCKETSHELL_TA_AREAS[@]}" | LC_ALL=C sort | tr '\n' ' ')"
    SELECT_SEED_AREAS="$SELECT_AREAS"
  else
    SELECT_AREAS="$(pocketshell_test_areas_expand "${!seed[*]}" | tr '\n' ' ')"
    SELECT_SEED_AREAS="$(pocketshell_test_areas_expand_seeds "${!seed[*]}" | tr '\n' ' ')"
  fi
  SELECT_AREAS="${SELECT_AREAS% }"
  SELECT_SEED_AREAS="${SELECT_SEED_AREAS% }"
}

area_selected() {
  local a="$1" s
  for s in $SELECT_AREAS; do [[ "$s" == "$a" ]] && return 0; done
  return 1
}

# ---------------------------------------------------------------------------
# THE per-CLASS selection predicate.
#
# A test class runs when ANY of these holds:
#   * the run is FULL;
#   * its area cannot be resolved            (ratchet: unknown => run it);
#   * its area is `always`-tier              (the floor);
#   * its own area actually changed;
#   * it IMPORTS the production code of an area that actually changed.
#
# The last clause is the round-1 fix (finding B2): coupling is read off the
# compile graph per class instead of from hand-written area edges that were
# claimed in prose and missing from the manifest. Matching is against
# SELECT_SEED_AREAS — what changed — and NOT against the always tier, or every
# class importing connection-core would run on every push and the selection
# would collapse to full.
# ---------------------------------------------------------------------------
# Source sets the JVM unit tasks (`test`, `:app:test`, `:shared:X:test`) run.
# `testDebug` / `testRelease` are variant-only unit source sets and they DO
# exist here (2 classes in :app, 2 in :shared:core-connection); treating only
# `test` as "unit" silently drops them from the emitted --tests filter.
is_unit_source_set() {
  case "$1" in test|testDebug|testRelease) return 0 ;; *) return 1 ;; esac
}

class_selected() {
  local fqcn="${1%%#*}" area dep
  [[ "$SELECT_MODE" == "full" ]] && return 0
  # THE resolver, called for its globals so the hot loop does not fork. Do not
  # inline its chain here: two copies of "how a class resolves" is finding B4.
  pocketshell_test_area_for_class "$fqcn" >/dev/null
  area="$POCKETSHELL_TEST_AREA_FOR_CLASS"
  [[ -z "$area" ]] && return 0
  [[ "${POCKETSHELL_TA_AREA_TIER[$area]:-always}" == "always" ]] && return 0
  [[ " $SELECT_SEED_AREAS " == *" $area "* ]] && return 0
  pocketshell_test_area_deps_for_class "$fqcn" >/dev/null
  for dep in $POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS; do
    [[ " $SELECT_SEED_AREAS " == *" $dep "* ]] && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Plan rendering
# ---------------------------------------------------------------------------

# Gradle unit selection.
#
# TWO invocations on purpose: `--tests` applies to every test task in one
# invocation, so a shared-module task would be filtered by an :app class name
# and fail with "no tests found". Full mode emits the byte-identical whole-graph
# command the Unit job runs today, which is what keeps
# check-ci-unit-forced-execution.sh satisfied on the full path.
#
# TWO round-1 defects are fixed here.
#
# B3 — the emitted `:app` filter was `com.pocketshell.app.*Test`, and Gradle's
# `*` CROSSES package dots (the reviewer proved it: `--tests
# "com.pocketshell.app.*UsageWindowLabelTest"` ran
# `com.pocketshell.app.usage.UsageWindowLabelTest`). That one pattern therefore
# matched all 485 `:app` unit classes — 74% of unit test-case time — on every
# push, including the docs-only floor, so the plan's reported class count was
# not the number the command executed. There are no wildcards left: the plan
# emits EXACT fully-qualified class names, which cannot over- or under-match.
#
# B5 — the area -> `:shared:…:test` map was a hardcoded `case` no guard covered,
# so renaming one area token silently dropped a module's tests while both guards
# stayed green. Module tasks are now derived from the selected classes' own
# paths, so a module can only drop out by having no selected class.
plan_gradle_unit() {
  if [[ "$SELECT_MODE" == "full" ]]; then
    printf 'UNIT_MODE=full\n'
    printf 'UNIT_GRADLE_TASKS=test\n'
    printf 'UNIT_GRADLE_FILTERS=\n'
    printf 'UNIT_SHARED_TASKS=\n'
    printf 'UNIT_SELECTED_UNIT_CLASSES=%s\n' "$(unit_class_total)"
    printf 'UNIT_TOTAL_UNIT_CLASSES=%s\n' "$(unit_class_total)"
    return
  fi
  pocketshell_test_areas_build_index
  local -A shared_tasks=()
  local -a app_unit=()
  local fqcn mod total=0
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    # `test` / `:app:test` runs the JVM unit source sets only; androidTest and
    # integrationTest classes belong to the emulator and Docker lanes.
    is_unit_source_set "${POCKETSHELL_TA_CLASS_SOURCESET[$fqcn]}" || continue
    total=$((total + 1))
    class_selected "$fqcn" || continue
    mod="${POCKETSHELL_TA_CLASS_MODULE[$fqcn]}"
    if [[ "$mod" == ":app" ]]; then
      app_unit+=("$fqcn")
    else
      shared_tasks["$mod:test"]=1
    fi
  done
  printf 'UNIT_MODE=scoped\n'
  printf 'UNIT_SHARED_TASKS=%s\n' \
    "$( [[ "${#shared_tasks[@]}" -gt 0 ]] && printf '%s\n' "${!shared_tasks[@]}" | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//' )"
  # No selected :app unit class => NO :app task. `--tests` with a filter that
  # matches nothing makes Gradle fail the task ("No tests found for given
  # includes"), and a CI job that dies on an empty selection is a worse failure
  # mode than running too much.
  if [[ "${#app_unit[@]}" -gt 0 ]]; then
    printf 'UNIT_GRADLE_TASKS=:app:test\n'
  else
    printf 'UNIT_GRADLE_TASKS=\n'
  fi
  printf 'UNIT_GRADLE_FILTERS=%s\n' \
    "$( [[ "${#app_unit[@]}" -gt 0 ]] && printf '%s\n' "${app_unit[@]}" | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//' )"
  printf 'UNIT_SELECTED_UNIT_CLASSES=%s\n' "$(( ${#app_unit[@]} + $(shared_selected_unit_class_count) ))"
  printf 'UNIT_TOTAL_UNIT_CLASSES=%s\n' "$total"
}

unit_class_total() {
  pocketshell_test_areas_build_index
  local fqcn n=0
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    is_unit_source_set "${POCKETSHELL_TA_CLASS_SOURCESET[$fqcn]}" && n=$((n + 1))
  done
  printf '%s\n' "$n"
}

# Shared-module unit classes actually reached by the emitted module tasks: the
# task is unfiltered, so EVERY unit class in a selected module runs.
shared_selected_unit_class_count() {
  local fqcn mod n=0
  local -A mods=()
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    is_unit_source_set "${POCKETSHELL_TA_CLASS_SOURCESET[$fqcn]}" || continue
    mod="${POCKETSHELL_TA_CLASS_MODULE[$fqcn]}"
    [[ "$mod" == ":app" ]] && continue
    class_selected "$fqcn" && mods["$mod"]=1
  done
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    is_unit_source_set "${POCKETSHELL_TA_CLASS_SOURCESET[$fqcn]}" || continue
    mod="${POCKETSHELL_TA_CLASS_MODULE[$fqcn]}"
    [[ -n "${mods[$mod]:-}" ]] && n=$((n + 1))
  done
  printf '%s\n' "$n"
}

# Journey classes selected for this run.
plan_journeys() {
  pocketshell_test_areas_build_index
  local fqcn
  while IFS= read -r fqcn; do
    [[ -z "$fqcn" ]] && continue
    class_selected "$fqcn" && printf '%s\n' "$fqcn"
  done < <(journey_registry_classes)
}

print_plan() {
  echo "== select-test-areas: changed-path classification =="
  if [[ "${#SELECT_CLASSIFICATION[@]}" -eq 0 ]]; then
    echo "  (no changed paths)"
  else
    printf '  %s\n' "${SELECT_CLASSIFICATION[@]}"
  fi
  echo
  echo "== decision: ${SELECT_MODE^^} =="
  if [[ "${#SELECT_FULL_REASONS[@]}" -gt 0 ]]; then
    echo "  force-full reasons:"
    printf '    %s\n' "${SELECT_FULL_REASONS[@]}"
  fi
  if [[ "${#SELECT_UNMAPPED[@]}" -gt 0 ]]; then
    echo
    echo "::error title=Unmapped path (issue #2063)::These paths match no rule in scripts/test-areas.txt. The run fell back to FULL (fail-safe), but the manifest must be extended so the map lags loudly rather than silently:"
    printf '    %s\n' "${SELECT_UNMAPPED[@]}"
  fi
  echo
  echo "== selected areas =="
  printf '  %s\n' $SELECT_AREAS
  echo "  (changed: ${SELECT_SEED_AREAS:-<none — always-tier floor only>})"
  echo
  echo "== unit plan =="
  plan_gradle_unit | sed 's/^/  /'
  echo
  local -a j=()
  mapfile -t j < <(plan_journeys)
  local total
  total="$(journey_registry_classes | wc -l | tr -d ' ')"
  echo "== journey plan =="
  echo "  ${#j[@]} of $total registered journey classes"
  printf '    %s\n' "${j[@]:-}"
}

print_plan_only() {
  printf 'MODE=%s\n' "$SELECT_MODE"
  printf 'AREAS=%s\n' "$SELECT_AREAS"
  printf 'CHANGED_AREAS=%s\n' "$SELECT_SEED_AREAS"
  printf 'UNMAPPED_COUNT=%s\n' "${#SELECT_UNMAPPED[@]}"
  plan_gradle_unit
  printf 'JOURNEY_CLASSES=%s\n' "$(plan_journeys | tr '\n' ',' | sed 's/,$//')"
}

# ---------------------------------------------------------------------------
# GUARD: --verify-manifest
#
# The manifest must be TOTAL over the tracked tree. Anything it does not
# classify falls back to FULL at runtime, which is safe but silent; this guard
# is what makes it loud instead.
# ---------------------------------------------------------------------------
verify_manifest() {
  local failures=0
  echo "== verify-manifest =="
  echo "manifest: $MANIFEST"

  if [[ "$MANIFEST_OK" -ne 1 ]]; then
    printf 'FAIL: manifest did not load:\n'
    printf '  %s\n' "${POCKETSHELL_TA_LOAD_ERRORS[@]}"
    return 1
  fi

  # 1. Totality over every tracked file.
  local -a unmapped=()
  local f
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    pocketshell_test_area_classify "$f"
    case "$POCKETSHELL_TEST_AREA_REASON" in
      unmapped-path|unmapped-test-path) unmapped+=("$f") ;;
    esac
  done < <(tracked_files)
  if [[ "${#unmapped[@]}" -gt 0 ]]; then
    echo "FAIL: ${#unmapped[@]} tracked path(s) match no manifest rule (they fall back to FULL):"
    printf '  %s\n' "${unmapped[@]}"
    failures=$((failures + 1))
  else
    echo "OK: manifest is total over $(tracked_files | wc -l | tr -d ' ') tracked paths"
  fi

  # 2. Every test class resolves to exactly one area — asked of the SAME
  #    resolver the ledger guard uses (finding B4: round 1 had two resolvers,
  #    this one green by path and the ledger's red by FQCN for 183 classes).
  pocketshell_test_areas_build_index
  local -a classless=()
  local fqcn path
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    if ! pocketshell_test_area_for_class "$fqcn" >/dev/null; then
      classless+=("$fqcn (${POCKETSHELL_TA_CLASS_PATH[$fqcn]})")
    fi
  done
  if [[ "${#classless[@]}" -gt 0 ]]; then
    echo "FAIL: ${#classless[@]} test class(es) belong to no area:"
    printf '  %s\n' "${classless[@]}" | LC_ALL=C sort
    failures=$((failures + 1))
  else
    echo "OK: every tracked test class resolves to exactly one area (${POCKETSHELL_TA_INDEX_CLASSES} classes, one resolver)"
  fi

  # 3. Every registered journey class resolves to an area.
  local -a journeyless=()
  while IFS= read -r fqcn; do
    [[ -z "$fqcn" ]] && continue
    if ! pocketshell_test_area_for_class "$fqcn" >/dev/null; then
      journeyless+=("$fqcn")
    fi
  done < <(journey_registry_classes)
  if [[ "${#journeyless[@]}" -gt 0 ]]; then
    echo "FAIL: ${#journeyless[@]} registered journey class(es) resolve to no area:"
    printf '  %s\n' "${journeyless[@]}"
    failures=$((failures + 1))
  else
    echo "OK: every registered journey class resolves to an area ($(journey_registry_classes | wc -l | tr -d ' ') classes)"
  fi

  # 4. No declared area is a ghost (declared but reachable from nothing).
  local -A reachable=()
  local i
  for i in "${!POCKETSHELL_TA_SRC_AREA[@]}"; do reachable["${POCKETSHELL_TA_SRC_AREA[$i]}"]=1; done
  for i in "${!POCKETSHELL_TA_TEST_AREA[@]}"; do reachable["${POCKETSHELL_TA_TEST_AREA[$i]}"]=1; done
  local -a ghosts=() a
  for a in "${POCKETSHELL_TA_AREAS[@]}"; do
    [[ -z "${reachable[$a]:-}" ]] && ghosts+=("$a")
  done
  if [[ "${#ghosts[@]}" -gt 0 ]]; then
    echo "FAIL: declared area(s) named by no src/test rule: ${ghosts[*]}"
    failures=$((failures + 1))
  else
    echo "OK: all ${#POCKETSHELL_TA_AREAS[@]} declared areas are reachable from a rule"
  fi

  # 5. Hidden test classes. Every registry, guard and Gradle filter in this
  #    repo keys off the `*Test.kt` / `*Test.java` naming convention, so a
  #    @Test-bearing class that does not follow it is invisible to all of them —
  #    the #1851 shape. #2063 baselined the offenders here as a flat array of
  #    paths so a NEW one fails; #2065 moved that baseline into
  #    scripts/test-unconventional-test-files.txt and gave every row a
  #    justification this guard CHECKS: how the file's @Test methods actually
  #    reach a runner, and what accounts for the file given no registry can see
  #    it. The list stays per FILE, deliberately, and is NOT a `*Harness.kt`
  #    suffix rule — a suffix would be a self-serve escape hatch (name a real
  #    regression test `…Harness.kt` and it hides itself), whereas a row with a
  #    checked executor and a checked gate keeps a human decision in the path
  #    and rots loudly in every direction.
  local -a hidden_new=() hidden_stale=() exempt_bad=()
  local -A hidden_seen=() exempt_exec=() exempt_gate=() exempt_reason=()
  local -a exempt_paths=()
  if [[ ! -f "$UNCONVENTIONAL" ]]; then
    echo "FAIL: unconventional-test-file exemptions not found: $UNCONVENTIONAL"
    failures=$((failures + 1))
  else
    local line lineno=0 e_path e_exec e_gate e_reason rest
    while IFS= read -r line || [[ -n "$line" ]]; do
      lineno=$((lineno + 1))
      [[ -z "${line//[[:space:]]/}" ]] && continue
      [[ "$line" == \#* ]] && continue
      IFS=$'\t' read -r e_path e_exec e_gate rest <<<"$line"
      e_reason="${rest%%$'\t'*}"
      if [[ -z "$e_path" || -z "$e_exec" || -z "$e_gate" || -z "${e_reason//[[:space:]]/}" ]]; then
        exempt_bad+=("line $lineno: expected <path>TAB<executor>TAB<gate>TAB<reason>, got: $line")
        continue
      fi
      if [[ -n "${exempt_exec[$e_path]:-}" ]]; then
        exempt_bad+=("$e_path: listed twice")
        continue
      fi
      exempt_paths+=("$e_path")
      exempt_exec["$e_path"]="$e_exec"
      exempt_gate["$e_path"]="$e_gate"
      exempt_reason["$e_path"]="$e_reason"
    done < "$UNCONVENTIONAL"
  fi

  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    pocketshell_test_areas_is_test_path "$f" || continue
    pocketshell_test_areas_is_test_class_file "$f" && continue
    grep -qE '^[[:space:]]*@Test([[:space:]]|\(|$)' "$REPO_ROOT/$f" 2>/dev/null || continue
    hidden_seen["$f"]=1
    [[ -z "${exempt_exec[$f]:-}" ]] && hidden_new+=("$f")
  done < <(tracked_files)

  # The PREMISE under every `nightly-connected` row, checked ONCE: nightly
  # phase 1 runs :app:connectedDebugAndroidTest WHOLESALE and subtracts a
  # `notClass` list. Without this, a per-row "the class is not excluded" check
  # is a green assertion over a property that no longer holds — flip phase 1 to
  # a `class=` allowlist and every row would silently start lying while the
  # guard stayed green (G6). Checked only when a row actually depends on it.
  local nightly_wholesale=1 nightly_phase1=""
  local -a nightly_rows=()
  local e_path
  for e_path in "${exempt_paths[@]}"; do
    [[ "${exempt_exec[$e_path]}" == "nightly-connected" ]] && nightly_rows+=("$e_path")
  done
  if [[ "${#nightly_rows[@]}" -gt 0 && -f "$NIGHTLY_SUITE" ]]; then
    nightly_phase1="$(sed -n '/phase 1: journey\/E2E/,/^JOURNEY_EXIT=/p' "$NIGHTLY_SUITE")"
    if [[ -z "$nightly_phase1" ]]; then
      nightly_wholesale=0
      exempt_bad+=("$(basename "$NIGHTLY_SUITE"): phase-1 invocation block not found, so the wholesale premise behind every 'nightly-connected' row is unverifiable")
    elif ! grep -Fq ':app:connectedDebugAndroidTest' <<<"$nightly_phase1" ||
         ! grep -Fq 'RunnerArguments.notClass=' <<<"$nightly_phase1"; then
      nightly_wholesale=0
      exempt_bad+=("$(basename "$NIGHTLY_SUITE"): phase 1 no longer runs :app:connectedDebugAndroidTest minus a notClass list — the 'nightly-connected' rows rest on that shape")
    elif grep -Eq 'RunnerArguments\.(class|package|annotation)=' <<<"$nightly_phase1"; then
      nightly_wholesale=0
      exempt_bad+=("$(basename "$NIGHTLY_SUITE"): phase 1 now restricts what it runs (class/package/annotation filter) — it is an allowlist, so 'nightly-connected' no longer follows from merely not being excluded")
    fi
  fi

  for e_path in "${exempt_paths[@]}"; do
    if [[ -z "${hidden_seen[$e_path]:-}" ]]; then
      hidden_stale+=("$e_path")
      continue
    fi
    # The executor claim answers "does it execute in any gate?" — the question
    # #2065 exists to force per file. Only the SHAPE of each claim is machine-
    # checkable, and that is exactly what is checked: an unverifiable claim is
    # rejected rather than believed.
    case "${exempt_exec[$e_path]}" in
      unit-source-set)
        case "$e_path" in
          */src/test/*) : ;;
          *) exempt_bad+=("$e_path: executor 'unit-source-set' but the path is not under */src/test/, so \`./gradlew test\` does not run it") ;;
        esac
        ;;
      nightly-connected)
        case "$e_path" in
          app/src/androidTest/*) : ;;
          *) exempt_bad+=("$e_path: executor 'nightly-connected' but the path is not under app/src/androidTest/") ;;
        esac
        if [[ ! -f "$NIGHTLY_SUITE" ]]; then
          exempt_bad+=("$e_path: executor 'nightly-connected' cannot be checked — nightly suite not found: $NIGHTLY_SUITE")
        elif [[ "$nightly_wholesale" -eq 1 ]]; then
          # Nightly phase 1 runs :app:connectedDebugAndroidTest WHOLESALE with a
          # `notClass` exclusion list, which is why a class in no explicit suite
          # still executes. Fail-closed on ANY mention of the simple name: an
          # exclusion, a shard pin or even a comment means the wholesale claim
          # has to be argued again rather than inherited.
          local simple="${e_path##*/}"; simple="${simple%.kt}"; simple="${simple%.java}"
          if grep -Fq "$simple" "$NIGHTLY_SUITE"; then
            exempt_bad+=("$e_path: executor 'nightly-connected' claims the wholesale nightly run reaches it, but $simple is named in $(basename "$NIGHTLY_SUITE") — re-argue the claim")
          fi
        fi
        ;;
      *)
        exempt_bad+=("$e_path: unknown executor '${exempt_exec[$e_path]}' (expected unit-source-set or nightly-connected)")
        ;;
    esac
    # The gate is what accounts for the file. A FQCN is resolved through the
    # SAME class index the area manifest and the ledger use, so a gate is
    # provably a registered, area-resolved class rather than a name that reads
    # well; an `enumerated-by:` script must actually reference the path.
    case "${exempt_gate[$e_path]}" in
      enumerated-by:*)
        local script="${exempt_gate[$e_path]#enumerated-by:}"
        if [[ ! -f "$REPO_ROOT/$script" ]]; then
          exempt_bad+=("$e_path: gate 'enumerated-by:$script' names a script that does not exist")
        elif ! grep -Fq "$e_path" "$REPO_ROOT/$script"; then
          exempt_bad+=("$e_path: gate 'enumerated-by:$script' does not reference this path, so it cannot be enumerating it")
        fi
        ;;
      *)
        if [[ -z "${POCKETSHELL_TA_CLASS_PATH[${exempt_gate[$e_path]}]:-}" ]]; then
          exempt_bad+=("$e_path: gate '${exempt_gate[$e_path]}' is not a known test class — a gate must be a conventional, area-resolved class the registries can see")
        fi
        ;;
    esac
  done

  if [[ "${#hidden_new[@]}" -gt 0 ]]; then
    echo "FAIL: ${#hidden_new[@]} @Test-bearing file(s) do not follow the *Test.kt/*Test.java convention, so no registry or filter can see them:"
    printf '  %s\n' "${hidden_new[@]}"
    echo "  Rename to *Test.kt, or add a justified row to ${UNCONVENTIONAL#"$REPO_ROOT/"} (read its header first — a row is not a formality)."
    failures=$((failures + 1))
  fi
  if [[ "${#hidden_stale[@]}" -gt 0 ]]; then
    echo "FAIL: stale exemption(s) — these no longer carry @Test, no longer exist, or now follow the convention; drop them from ${UNCONVENTIONAL#"$REPO_ROOT/"}:"
    printf '  %s\n' "${hidden_stale[@]}"
    failures=$((failures + 1))
  fi
  if [[ "${#exempt_bad[@]}" -gt 0 ]]; then
    echo "FAIL: ${#exempt_bad[@]} unconventional-test-file exemption(s) are not justified:"
    printf '  %s\n' "${exempt_bad[@]}"
    failures=$((failures + 1))
  fi
  if [[ "${#hidden_new[@]}" -eq 0 && "${#hidden_stale[@]}" -eq 0 && "${#exempt_bad[@]}" -eq 0 ]]; then
    echo "OK: no new @Test-bearing file outside the *Test.kt/*Test.java convention (${#exempt_paths[@]} exempted, each with a checked executor and gate)"
  fi

  # 6. The `noop` premise: no test source reads a repo doc/markdown file at
  #    runtime. If one ever does, docs stop being inert and the noop rows are
  #    wrong — fail here rather than skip that test forever.
  local doc_readers
  # Every test source set, including the integrationTest sets round 1's version
  # of this grep missed (a completeness nit the reviewer flagged; the premise
  # holds either way, but a guard with a blind spot is how premises rot).
  doc_readers="$(grep -rnE --include='*.kt' --include='*.java' \
    '(File|Paths\.get|resolve)\([^)]*"(docs/|mockups/|AGENTS\.md|process\.md|README\.md|CLAUDE\.md)' \
    "$REPO_ROOT"/app/src/test "$REPO_ROOT"/app/src/androidTest "$REPO_ROOT"/app/src/integrationTest \
    "$REPO_ROOT"/shared/*/src/test "$REPO_ROOT"/shared/*/src/androidTest \
    "$REPO_ROOT"/shared/*/src/integrationTest 2>/dev/null || true)"
  if [[ -n "$doc_readers" ]]; then
    echo "FAIL: a test reads a repo doc at runtime, so 'noop docs/*' is no longer true:"
    printf '  %s\n' "$doc_readers"
    failures=$((failures + 1))
  else
    echo "OK: no test source reads a repo doc/markdown file at runtime (noop premise holds)"
  fi

  # 7. The dependency index actually RAN and is populated.
  #
  #    Everything the selection now narrows on rests on this index. If the grep
  #    silently matched nothing (marker renamed, source layout moved, `xargs`
  #    behaving differently), every dependency set would collapse to "own area
  #    only" and the selection would quietly go back to being under-inclusive —
  #    green, and wrong, which is exactly the round-1 shape. These floors are
  #    deliberately far below today's values: they detect a broken mechanism,
  #    not normal drift.
  #
  #    ROUND-2 FINDING B7: the two host-CLI floors used to be `>= 1`, and the
  #    reviewer cut the seam from 15 packages / 569 classes to 9 / 210 — a 63%
  #    narrowing of the coupling that protects the single most expensive break in
  #    this repo's history — with every check and every invariant still green. A
  #    `>= 1` floor only detects a TOTALLY dead mechanism, and a heuristic over
  #    source text is the component most likely to narrow quietly. Every floor
  #    below is now in the same spirit as its siblings, and the seam is floored
  #    PER END, because the round-2 defect (B6) was precisely a healthy-looking
  #    total (15 packages) hiding one whole end at zero — nothing in `shared/*`.
  local -a index_fail=()
  [[ "$POCKETSHELL_TA_INDEX_CLASSES"      -ge 500 ]] || index_fail+=("indexed test classes = $POCKETSHELL_TA_INDEX_CLASSES (< 500)")
  [[ "$POCKETSHELL_TA_INDEX_PROD_PKGS"    -ge 30  ]] || index_fail+=("production packages mapped = $POCKETSHELL_TA_INDEX_PROD_PKGS (< 30)")
  [[ "$POCKETSHELL_TA_INDEX_IMPORT_LINES" -ge 1000 ]] || index_fail+=("com.pocketshell import lines scanned = $POCKETSHELL_TA_INDEX_IMPORT_LINES (< 1000)")
  [[ "$POCKETSHELL_TA_INDEX_CROSS_AREA_CLASSES" -ge 200 ]] || index_fail+=("test classes with a cross-area production dependency = $POCKETSHELL_TA_INDEX_CROSS_AREA_CLASSES (< 200)")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_INVOKER_PKGS" -ge 12 ]] || index_fail+=("host-CLI wire-seam PRODUCER packages = $POCKETSHELL_TA_INDEX_HOSTCLI_INVOKER_PKGS (< 12) — the invoke marker /$POCKETSHELL_TA_HOSTCLI_MARKER/ has narrowed (#847 lockstep coupling)")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_CONSUMER_PKGS" -ge 15 ]] || index_fail+=("host-CLI wire-seam CONSUMER packages = $POCKETSHELL_TA_INDEX_HOSTCLI_CONSUMER_PKGS (< 15) — the reply end of the wire has narrowed (finding B6)")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_VOCAB_PKGS" -ge 14 ]] || index_fail+=("host-CLI wire-seam VOCABULARY packages = $POCKETSHELL_TA_INDEX_HOSTCLI_VOCAB_PKGS (< 14) — packages naming a real subcommand have narrowed")
  # ROUND-3 FINDING B9. A floor on the extracted count cannot see the failure
  # that actually happened: the extractor read ONE of the registration forms
  # `cli.py` uses, so it under-read the producer on the shipped tree while
  # printing a healthy-looking 16. Four more registrations moved to the form
  # `daemon` already used would have dropped `usage`/`tree`/`agents`/`qr-share`
  # from the vocabulary, taken `com.pocketshell.app.settings` off the seam and
  # cut unit selection 570 -> 560 with every floor still green. Under-reading
  # produces a plausible number, and no floor distinguishes a plausible number
  # from the right one. EQUALITY does: the reader counts registration SITES
  # independently of the names it resolves, so a form it cannot decode raises
  # one and not the other.
  #
  # The site floor survives with a narrower job — it is the only thing that
  # catches a totally dead reader, because `names == sites` is vacuous at 0 == 0.
  #
  # ROUND-5 FINDING. Equality was still satisfiable vacuously by a whole class of
  # registration: the reader keyed detection on the receiver being the literal
  # name `cli`, so `_g = cli; _g.add_command(…)` — or a helper taking the group
  # as a parameter — was neither a SITE nor a NAME. Six rounds closed six
  # spellings one at a time; the space of ways to name an object is unbounded, so
  # the reader is now receiver-AGNOSTIC: it takes a CENSUS of every registrar call
  # in the file and each one must resolve to the root group, be proven to be on a
  # different object, or be reported unresolved. This identity is what says the
  # census is total — if it holds, no registrar call escaped all three counters.
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SCANNED" -eq $((POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES + POCKETSHELL_TA_INDEX_HOSTCLI_CLI_NONROOT + POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED)) ]] || index_fail+=("host-CLI registrar census in $POCKETSHELL_TA_HOSTCLI_CLI_SOURCE does not balance: $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SCANNED calls scanned but $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES top-level + $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_NONROOT non-root + $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED unresolved classified — a registration fell out of the census, which is how under-reading stays silent")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED" -eq 0 ]] || index_fail+=("host-CLI vocabulary reader could not resolve the receiver of $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED registrar call(s) in $POCKETSHELL_TA_HOSTCLI_CLI_SOURCE — each one might be registering a top-level subcommand the seam cannot see")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES" -ge 12 ]] || index_fail+=("host-CLI registration SITES found in $POCKETSHELL_TA_HOSTCLI_CLI_SOURCE = $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES (< 12) — the producer-side vocabulary reader is dead, so the vocabulary end of the seam is blind")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_SUBCOMMANDS" -eq "$POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES" ]] || index_fail+=("host-CLI subcommand names read = $POCKETSHELL_TA_INDEX_HOSTCLI_SUBCOMMANDS but registration SITES in $POCKETSHELL_TA_HOSTCLI_CLI_SOURCE = $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES — the reader is UNDER-READING the producer (finding B9), so part of the wire vocabulary is invisible to the seam")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE" -eq 0 ]] || index_fail+=("host-CLI vocabulary reader could not decode $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE registration(s) in $POCKETSHELL_TA_HOSTCLI_CLI_SOURCE: $POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_SHARED_PKGS" -ge 8 ]] || index_fail+=("host-CLI wire-seam packages under shared/ = $POCKETSHELL_TA_INDEX_HOSTCLI_SHARED_PKGS (< 8) — this is EXACTLY the B6 defect: it was 0 while the total looked healthy, and :shared:core-usage:test (the strict parser of \`pocketshell usage --json\`) did not run on a host-CLI change")
  [[ "$POCKETSHELL_TA_INDEX_HOSTCLI_CLASSES" -ge 600 ]] || index_fail+=("test classes depending on the host CLI = $POCKETSHELL_TA_INDEX_HOSTCLI_CLASSES (< 600) — #1509 / the usage parser would stop running on a tools/pocketshell change")
  if [[ "${#index_fail[@]}" -gt 0 ]]; then
    echo "FAIL: the import-dependency index looks broken, so selection would under-run:"
    printf '  %s\n' "${index_fail[@]}"
    failures=$((failures + 1))
  else
    echo "OK: dependency index populated (${POCKETSHELL_TA_INDEX_CLASSES} classes, ${POCKETSHELL_TA_INDEX_PROD_PKGS} production packages, ${POCKETSHELL_TA_INDEX_IMPORT_LINES} imports, ${POCKETSHELL_TA_INDEX_CROSS_AREA_CLASSES} cross-area, ${POCKETSHELL_TA_INDEX_HOSTCLI_CLASSES} host-CLI-dependent via ${POCKETSHELL_TA_INDEX_HOSTCLI_PKGS} wire-seam packages = ${POCKETSHELL_TA_INDEX_HOSTCLI_INVOKER_PKGS} producer / ${POCKETSHELL_TA_INDEX_HOSTCLI_CONSUMER_PKGS} consumer / ${POCKETSHELL_TA_INDEX_HOSTCLI_VOCAB_PKGS} vocabulary over ${POCKETSHELL_TA_INDEX_HOSTCLI_SUBCOMMANDS} subcommands read from the ${POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES} top-level registration sites this reader could see in ${POCKETSHELL_TA_HOSTCLI_CLI_SOURCE} — a census of ${POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SCANNED} registrar calls, ${POCKETSHELL_TA_INDEX_HOSTCLI_CLI_NONROOT} of them resolved to a non-root receiver and ${POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED} unresolved — ${POCKETSHELL_TA_INDEX_HOSTCLI_SHARED_PKGS} of them under shared/)"
  fi

  # 8. Every Gradle test task the planner can emit is a real Gradle project.
  #
  #    Finding B5: the area -> module-task map used to be a hardcoded `case` no
  #    guard covered, so renaming one area token dropped `:shared:core-usage:test`
  #    while both guards stayed green. The map is derived from class paths now,
  #    and this check closes the other end: a derived task name that no
  #    `include(...)` backs would fail the CI run, not this guard, so assert it
  #    here where it is cheap.
  local -a task_fail=() derived_tasks=()
  local -A modules=()
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    is_unit_source_set "${POCKETSHELL_TA_CLASS_SOURCESET[$fqcn]}" || continue
    modules["${POCKETSHELL_TA_CLASS_MODULE[$fqcn]}"]=1
  done
  local m
  for m in "${!modules[@]}"; do
    derived_tasks+=("$m")
    grep -qF "include(\"$m\")" "$REPO_ROOT/settings.gradle.kts" 2>/dev/null || \
      task_fail+=("$m:test — no include(\"$m\") in settings.gradle.kts")
  done
  if [[ "${#task_fail[@]}" -gt 0 ]]; then
    echo "FAIL: ${#task_fail[@]} derived unit test task(s) name a project Gradle does not have:"
    printf '  %s\n' "${task_fail[@]}"
    failures=$((failures + 1))
  else
    echo "OK: all ${#derived_tasks[@]} derived unit test module(s) exist in settings.gradle.kts"
  fi

  if [[ "$failures" -gt 0 ]]; then
    echo
    echo "::error title=Test-area manifest (issue #2063)::$failures manifest check(s) failed. Extend scripts/test-areas.txt."
    return 1
  fi
  echo
  echo "PASS: manifest verified."
  return 0
}

# ---------------------------------------------------------------------------
# GUARD: --coverage-invariant
#
# The maintainer's non-negotiable: "I still want the same coverage." This
# asserts it mechanically instead of eyeballing it.
#
#   I1  every test class belongs to exactly one area          (verify-manifest)
#   I2  the union of per-area test sets == every test class
#   I3  touching an area selects that area's own tests
#   I4  the FULL selection == every test class and every journey
#   I5  the smallest possible selection (a noop-only diff) is still non-empty
#       and contains every always-tier test
#   I6  the union over the whole cadence window == every test class
# ---------------------------------------------------------------------------
# Invariant filter. Exists ONLY so a self-test mutation can drive the one
# invariant it targets without paying for the other nine; the guard as CI runs
# it is always unfiltered, and a filtered run labels itself in the verdict so a
# partial pass can never be mistaken for the whole thing.
want_inv() {
  [[ -z "$ONLY_INVARIANTS" ]] && return 0
  [[ ",$ONLY_INVARIANTS," == *",$1,"* ]]
}

coverage_invariant() {
  local failures=0
  echo "== coverage-invariant =="
  if [[ -n "$ONLY_INVARIANTS" ]]; then
    echo "FILTERED to: $ONLY_INVARIANTS (self-test mutation mode — NOT the full guard)"
    local want
    for want in ${ONLY_INVARIANTS//,/ }; do
      case "$want" in
        I2|I3|I4|I5|I6|I7|I8|I9|I10|I11) : ;;
        *) echo "FAIL: --only names unknown invariant '$want'"; return 1 ;;
      esac
    done
  fi

  if [[ "$MANIFEST_OK" -ne 1 ]]; then
    echo "FAIL: manifest did not load"
    return 1
  fi

  # Build class -> area once, from THE resolver.
  pocketshell_test_areas_build_index
  local -A class_area=()
  local -A area_classes=()
  local fqcn path a2
  local total_classes=0
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    total_classes=$((total_classes + 1))
    pocketshell_test_area_for_class "$fqcn" >/dev/null
    a2="$POCKETSHELL_TEST_AREA_FOR_CLASS"
    if [[ -z "$a2" ]]; then
      class_area["$fqcn"]="__NONE__"
      continue
    fi
    class_area["$fqcn"]="$a2"
    area_classes["$a2"]="${area_classes[$a2]:-} $fqcn"
  done

if want_inv I2; then
    # I2: union of per-area sets == all classes.
    local union=0 a
    for a in "${POCKETSHELL_TA_AREAS[@]}"; do
      # shellcheck disable=SC2086
      set -- ${area_classes[$a]:-}
      union=$((union + $#))
    done
    if [[ "$union" -ne "$total_classes" ]]; then
      echo "FAIL I2: union of per-area test sets is $union, but the repo has $total_classes test classes"
      failures=$((failures + 1))
    else
      echo "OK I2: union of the ${#POCKETSHELL_TA_AREAS[@]} per-area test sets == all $total_classes test classes"
    fi
  fi


  # I3: for every area, a change to one of its own paths selects it (and hence
  #     its whole test set). Driven through the REAL selection function, using
  #     a real tracked path per area, so this cannot pass on a stub.
  #     One representative per area, found by classifying each tracked
  #     DIRECTORY's first file rather than all 2079 files (same answer, a third
  #     of the time, and this guard runs several times per self-test).
  local -A rep=() dir_seen=()
  local f d
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    d="${f%/*}"
    [[ -n "${dir_seen[$d]:-}" ]] && continue
    dir_seen["$d"]=1
    pocketshell_test_area_classify "$f"
    [[ "$POCKETSHELL_TEST_AREA_KIND" == "area" ]] || continue
    [[ -z "${rep[$POCKETSHELL_TEST_AREA_NAME]:-}" ]] && rep["$POCKETSHELL_TEST_AREA_NAME"]="$f"
  done < <(tracked_files)
  local -a i3_fail=()
  for a in "${POCKETSHELL_TA_AREAS[@]}"; do
    if [[ -z "${rep[$a]:-}" ]]; then i3_fail+=("$a (no tracked path resolves to it)"); continue; fi
    run_selection <<<"${rep[$a]}"
    area_selected "$a" || i3_fail+=("$a (changing ${rep[$a]} did not select it)")
  done
  if [[ "${#i3_fail[@]}" -gt 0 ]]; then
    echo "FAIL I3: ${#i3_fail[@]} area(s) are not selected by a change to their own paths:"
    printf '  %s\n' "${i3_fail[@]}"
    failures=$((failures + 1))
  else
    echo "OK I3: every area is selected by a change to one of its own tracked paths"
  fi

  # I4: the FULL selection covers every class and every journey.
  run_selection <<<"scripts/ci-journey-suite.sh"
  if [[ "$SELECT_MODE" != "full" ]]; then
    echo "FAIL I4: a scripts/ change did not force a full run"
    failures=$((failures + 1))
  fi
  local full_area_count=0
  for a in $SELECT_AREAS; do full_area_count=$((full_area_count + 1)); done
  if [[ "$full_area_count" -ne "${#POCKETSHELL_TA_AREAS[@]}" ]]; then
    echo "FAIL I4: full mode selected $full_area_count areas, expected ${#POCKETSHELL_TA_AREAS[@]}"
    failures=$((failures + 1))
  fi
  local full_journeys registry_journeys
  full_journeys="$(plan_journeys | wc -l | tr -d ' ')"
  registry_journeys="$(journey_registry_classes | wc -l | tr -d ' ')"
  if [[ "$full_journeys" -ne "$registry_journeys" ]]; then
    echo "FAIL I4: full mode selected $full_journeys journeys, registry has $registry_journeys"
    failures=$((failures + 1))
  else
    echo "OK I4: full mode selects all ${#POCKETSHELL_TA_AREAS[@]} areas and all $registry_journeys registered journeys"
  fi

  # I5: the smallest possible selection. A docs-only diff is the minimum, and
  #     it must still be non-empty and contain the whole always tier.
  run_selection <<<"docs/testing.md"
  if [[ "$SELECT_MODE" != "scoped" ]]; then
    echo "FAIL I5: a docs-only diff did not produce a scoped run (got $SELECT_MODE)"
    failures=$((failures + 1))
  fi
  local -a i5_fail=()
  local min_count=0
  for a in $SELECT_AREAS; do min_count=$((min_count + 1)); done
  [[ "$min_count" -eq 0 ]] && i5_fail+=("the minimum selection is EMPTY — 'run nothing' is reachable")
  for a in "${POCKETSHELL_TA_AREAS[@]}"; do
    [[ "${POCKETSHELL_TA_AREA_TIER[$a]}" == "always" ]] || continue
    area_selected "$a" || i5_fail+=("always-tier area '$a' missing from the minimum selection")
  done
  if [[ "${#i5_fail[@]}" -gt 0 ]]; then
    printf 'FAIL I5: %s\n' "${i5_fail[@]}"
    failures=$((failures + 1))
  else
    echo "OK I5: the minimum selection is $min_count areas and contains every always-tier area"
  fi

  # I6: the cadence union. For every tracked path, the selection it triggers is
  #     computed and its area set unioned; that union must be every area (hence
  #     every test class, by I2). This is the mechanical statement of "coverage
  #     is invariant, only the *when* changed".
  #
  #     Computed over one representative path per area plus one force-full path
  #     plus the always tier — the selection function is a pure function of the
  #     area set, so per-area representatives are exhaustive over its range.
  local -A cadence=()
  for a in "${POCKETSHELL_TA_AREAS[@]}"; do
    [[ -z "${rep[$a]:-}" ]] && continue
    run_selection <<<"${rep[$a]}"
    local s
    for s in $SELECT_AREAS; do cadence["$s"]=1; done
  done
  local -a missing=()
  for a in "${POCKETSHELL_TA_AREAS[@]}"; do
    [[ -z "${cadence[$a]:-}" ]] && missing+=("$a")
  done
  if [[ "${#missing[@]}" -gt 0 ]]; then
    echo "FAIL I6: over the per-push cadence window these areas are never selected: ${missing[*]}"
    echo "        (they would only ever run nightly / at the release gate)"
    failures=$((failures + 1))
  else
    echo "OK I6: over the per-push cadence window the selected-area union == all ${#POCKETSHELL_TA_AREAS[@]} areas == all $total_classes test classes"
  fi

if want_inv I7; then
    # I7: the same statement at CLASS granularity. Selection is now per test
    #     class, so an area-level union is no longer the thing that has to be
    #     total — every individual CLASS must be selected by at least one change
    #     inside the per-push cadence window, or it only ever runs nightly.
    local -A cadence_classes=()
    local c
    for a in "${POCKETSHELL_TA_AREAS[@]}"; do
      [[ -z "${rep[$a]:-}" ]] && continue
      run_selection <<<"${rep[$a]}"
      for c in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
        [[ -n "${cadence_classes[$c]:-}" ]] && continue
        class_selected "$c" && cadence_classes["$c"]=1
      done
    done
    local -a never_selected=()
    for c in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
      [[ -z "${cadence_classes[$c]:-}" ]] && never_selected+=("$c")
    done
    if [[ "${#never_selected[@]}" -gt 0 ]]; then
      echo "FAIL I7: ${#never_selected[@]} test class(es) are selected by NO change in the per-push cadence window:"
      printf '  %s\n' "${never_selected[@]}" | LC_ALL=C sort | head -40
      failures=$((failures + 1))
    else
      echo "OK I7: every one of the $total_classes test classes is selected by at least one per-push change"
    fi
  fi


if want_inv I8; then
    # I8: INDEPENDENT blast-radius escape scan (the reviewer's B2 oracle, promoted
    #     into the guard). For every registered journey this re-greps the class's
    #     own imports and re-maps them through `pocketshell_test_area_classify` —
    #     it does NOT read the dependency index — then asserts a change to that
    #     production area actually selects the journey. If the index ever stops
    #     being built, or someone reverts to hand-listed area couples, this goes
    #     red naming the escape.
    local -A area_selection_cache=()
    local -a escapes=()
    local j jf imp p pa probe
    while IFS= read -r j; do
      [[ -z "$j" ]] && continue
      jf="${POCKETSHELL_TA_CLASS_PATH[$j]:-}"
      [[ -z "$jf" || ! -f "$REPO_ROOT/$jf" ]] && continue
      while IFS= read -r imp; do
        imp="${imp#*import }"
        imp="${imp%%[!A-Za-z0-9_.]*}"
        p="$imp"; pa=""
        while [[ "$p" == *.* ]]; do
          p="${p%.*}"
          if [[ -n "${POCKETSHELL_TA_PROD_PKG_AREA[$p]:-}" ]]; then pa="${POCKETSHELL_TA_PROD_PKG_AREA[$p]}"; break; fi
        done
        [[ -z "$pa" ]] && continue
        probe="${rep[$pa]:-}"
        [[ -z "$probe" ]] && continue
        if [[ -z "${area_selection_cache[$pa]:-}" ]]; then
          run_selection <<<"$probe"
          area_selection_cache["$pa"]="$SELECT_SEED_AREAS"
        fi
        SELECT_MODE="scoped"
        SELECT_SEED_AREAS="${area_selection_cache[$pa]}"
        class_selected "$j" || escapes+=("$j is NOT selected by a change to '$pa', which it imports ($imp)")
      done < <(grep -hE '^[[:space:]]*import[[:space:]]+com\.pocketshell\.' "$REPO_ROOT/$jf" 2>/dev/null)
    done < <(journey_registry_classes)
    if [[ "${#escapes[@]}" -gt 0 ]]; then
      printf '%s\n' "${escapes[@]}" | LC_ALL=C sort -u > /dev/null
      echo "FAIL I8: $(printf '%s\n' "${escapes[@]}" | LC_ALL=C sort -u | wc -l | tr -d ' ') blast-radius escape(s) — a journey compiles against an area whose change does not run it:"
      printf '%s\n' "${escapes[@]}" | LC_ALL=C sort -u | head -40 | sed 's/^/  /'
      failures=$((failures + 1))
    else
      echo "OK I8: no registered journey escapes an area it imports (independent re-scan of every journey's imports)"
    fi
  fi


  # I9: named regression pins. Each is a journey that a REAL past break needed,
  #     paired with the area whose change must run it. I8 is the general rule;
  #     these are the specific ones a future refactor must never silently lose,
  #     and they fail loudly if the class is renamed or deleted rather than
  #     quietly dropping out of the check.
  #
  #     Round-2 finding B6: this pin set was JOURNEYS ONLY, which is why nothing
  #     stopped a UNIT-level lockstep consumer escaping. The strict, fail-loud
  #     reader of `pocketshell usage --json` is a plain JVM test in a shared
  #     module, and it was not run by a change to the Python that produces that
  #     NDJSON. UNIT_PINS below closes that half; they are checked against the
  #     indexed class set rather than the journey registry.
  local -a UNIT_PINS=(
    # #1318 / #847: PocketshellUsageJsonParser is deliberately STRICT — any
    # schema drift in tools/pocketshell/src/pocketshell/usage.py throws and the
    # whole usage panel errors. The producer of that NDJSON must run it.
    "com.pocketshell.core.usage.PocketshellUsageJsonParserTest|host-cli"
    # core-storage: HostEntity carries the CLI lockstep version columns
    # (pocketshellCliVersion / pocketshellExpectedCliVersion) and its schema
    # test is what a version-contract change breaks. The round-2 reviewer asked
    # for this to be audited rather than assumed; it resolves through the
    # consumer end (core.storage.entity), not by hand.
    "com.pocketshell.core.storage.AppDatabaseTest|host-cli"
    # ui-kit: the models that mirror what the CLI writes — HostSetupState
    # (CLI missing / incompatible) and SessionAgentKind (the `pocketshell agent`
    # strings), both in com.pocketshell.uikit.model.
    "com.pocketshell.uikit.model.SessionAgentKindOptionTest|host-cli"
  )
  local -a PINS=(
    # #1509 G10 + #847 / v0.4.10: host-CLI/client version is a RUNTIME lockstep.
    "com.pocketshell.app.projects.FolderListHostOutdatedTreeVersionDaemonDockerTest|host-cli"
    "com.pocketshell.app.projects.FolderListOldCliHydrateDockerTest|host-cli"
    # D28 connection core: journeys that drive transport production types.
    "com.pocketshell.app.portfwd.ForwardingNetworkRideThroughE2eTest|connection-core"
    "com.pocketshell.app.diagnostics.ConnectionLogHostMirrorReconnectDockerTest|connection-core"
    "com.pocketshell.app.projects.Issue1876FolderListMobileRttDockerTest|connection-core"
    "com.pocketshell.app.projects.ProfileChipRelaunchDockerTest|connection-core"
    "com.pocketshell.app.hosts.HostResumeLastSessionE2eTest|connection-core"
    "com.pocketshell.app.tmux.TmuxConnectingStatesScreenshotTest|connection-core"
    "com.pocketshell.app.tmux.Issue1686QueueDrainWireOracleDockerTest|connection-core"
  )
  local -A registry=()
  while IFS= read -r j; do [[ -n "$j" ]] && registry["$j"]=1; done < <(journey_registry_classes)
  local -a pin_fail=() pin
  for pin in "${PINS[@]}"; do
    j="${pin%%|*}"; a="${pin##*|}"
    if [[ -z "${registry[$j]:-}" ]]; then
      pin_fail+=("$j is no longer in the journey registry — re-pin it or state why the coverage is gone")
      continue
    fi
    probe="${rep[$a]:-}"
    if [[ -z "$probe" ]]; then pin_fail+=("no tracked path represents area '$a'"); continue; fi
    run_selection <<<"$probe"
    class_selected "$j" || pin_fail+=("a change to '$a' ($probe) does NOT select $j")
  done
  # The UNIT half. Existence is checked against the indexed class set (these are
  # plain JVM classes, not journey-registry entries), and the selection question
  # is identical: a change to the pinned area must run them.
  for pin in "${UNIT_PINS[@]}"; do
    j="${pin%%|*}"; a="${pin##*|}"
    if [[ -z "${POCKETSHELL_TA_CLASS_PATH[$j]:-}" ]]; then
      pin_fail+=("$j is no longer a tracked test class — re-pin it or state why the coverage is gone")
      continue
    fi
    probe="${rep[$a]:-}"
    if [[ -z "$probe" ]]; then pin_fail+=("no tracked path represents area '$a'"); continue; fi
    run_selection <<<"$probe"
    if ! class_selected "$j"; then
      pin_fail+=("a change to '$a' ($probe) does NOT select unit class $j")
    elif is_unit_source_set "${POCKETSHELL_TA_CLASS_SOURCESET[$j]:-}"; then
      # Selection is necessary but not sufficient: the emitted plan must also
      # carry the class's Gradle module, or the guard is green while the run
      # never touches it. That IS the concrete B6 symptom —
      # `:shared:core-usage:test` absent from a `tools/pocketshell/**` plan.
      local pin_mod="${POCKETSHELL_TA_CLASS_MODULE[$j]:-}"
      local pin_tasks
      pin_tasks="$(plan_gradle_unit | sed -n 's/^UNIT_SHARED_TASKS=//p')"
      if [[ "$pin_mod" == :shared:* && " $pin_tasks " != *" ${pin_mod}:test "* ]]; then
        pin_fail+=("a change to '$a' ($probe) selects $j but the emitted plan does NOT run ${pin_mod}:test")
      fi
    fi
  done
  if [[ "${#pin_fail[@]}" -gt 0 ]]; then
    echo "FAIL I9: ${#pin_fail[@]} pinned blast-radius regression(s):"
    printf '  %s\n' "${pin_fail[@]}"
    failures=$((failures + 1))
  else
    echo "OK I9: all ${#PINS[@]} pinned lockstep/transport journeys and all ${#UNIT_PINS[@]} pinned unit lockstep consumers run on a change to the area that breaks them (unit pins also assert their module's task is in the emitted plan)"
  fi

if want_inv I10; then
    # I10: THE EMITTED COMMAND IS THE PLAN.
    #
    #      Finding B3: round 1 reported a selected class count while emitting
    #      `--tests com.pocketshell.app.*Test`, and Gradle's `*` crosses package
    #      dots, so the command ran all 485 :app unit classes on every push. The
    #      count and the command disagreed and nothing noticed. This asserts they
    #      are the SAME set: every emitted `:app` filter is an exact FQCN of a
    #      selected :app unit class, every selected :app unit class is emitted,
    #      and no filter contains a glob metacharacter at all.
    local -a i10_fail=()
    local probe_path filters tok
    for probe_path in "docs/testing.md" "${rep[usage-costs]:-}" "${rep[portfwd]:-}" "${rep[connection-core]:-}"; do
      [[ -z "$probe_path" ]] && continue
      run_selection <<<"$probe_path"
      [[ "$SELECT_MODE" == "scoped" ]] || continue
      filters="$(plan_gradle_unit | sed -n 's/^UNIT_GRADLE_FILTERS=//p')"
      local -A emitted=()
      local n_emitted=0 n_both_variants=0
      for tok in $filters; do
        case "$tok" in
          *'*'*|*'?'*|*'['*)
            i10_fail+=("[$probe_path] emitted filter '$tok' contains a glob metacharacter — Gradle's '*' crosses package dots (B3)")
            continue ;;
        esac
        if [[ "${POCKETSHELL_TA_CLASS_MODULE[$tok]:-}" != ":app" ]] || \
           ! is_unit_source_set "${POCKETSHELL_TA_CLASS_SOURCESET[$tok]:-}"; then
          i10_fail+=("[$probe_path] emitted filter '$tok' is not an :app unit test class")
          continue
        fi
        emitted["$tok"]=1
        n_emitted=$((n_emitted + 1))
        [[ "${POCKETSHELL_TA_CLASS_SOURCESET[$tok]}" == "test" ]] && n_both_variants=$((n_both_variants + 1))
      done
      # `:app:test` runs testDebugUnitTest AND testReleaseUnitTest with the SAME
      # --tests set. A set consisting only of `testDebug`/`testRelease`-only
      # classes would make the other variant fail with "No tests found for given
      # includes" — CI red for a selection that is otherwise correct. At least one
      # filter must name a class present in both variants.
      if [[ "$n_emitted" -gt 0 && "$n_both_variants" -eq 0 ]]; then
        i10_fail+=("[$probe_path] every emitted filter is variant-only; the other :app unit variant would fail with 'No tests found'")
      fi
      for c in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
        [[ "${POCKETSHELL_TA_CLASS_MODULE[$c]}" == ":app" ]] || continue
        is_unit_source_set "${POCKETSHELL_TA_CLASS_SOURCESET[$c]}" || continue
        if class_selected "$c"; then
          [[ -n "${emitted[$c]:-}" ]] || i10_fail+=("[$probe_path] $c is selected but NOT in the emitted --tests filter")
        else
          [[ -z "${emitted[$c]:-}" ]] || i10_fail+=("[$probe_path] $c is emitted but not selected")
        fi
      done
      unset emitted
    done
    if [[ "${#i10_fail[@]}" -gt 0 ]]; then
      echo "FAIL I10: ${#i10_fail[@]} plan/command mismatch(es) — the reported selection is not what Gradle would run:"
      printf '  %s\n' "${i10_fail[@]}" | head -20
      failures=$((failures + 1))
    else
      echo "OK I10: the emitted :app --tests filter is exactly the selected :app unit class set, with no wildcards"
    fi
  fi


if want_inv I11; then
    # I11: every shared module is reachable by its OWN change.
    #
    #      Finding B5: the area -> `:shared:…:test` map was a hardcoded `case`, so
    #      renaming an area token silently dropped `:shared:core-usage:test` while
    #      --verify-manifest and --coverage-invariant both stayed green. Asserting
    #      it per MODULE (not per area) is what makes the map's shape irrelevant.
    local -A module_rep=()
    while IFS= read -r f; do
      [[ -z "$f" ]] && continue
      case "$f" in shared/*/src/main/*) : ;; *) continue ;; esac
      m="${f%%/src/*}"; m=":${m//\//:}"
      [[ -z "${module_rep[$m]:-}" ]] && module_rep["$m"]="$f"
    done < <(tracked_files)
    local -a i11_fail=()
    local tasks
    for m in "${!module_rep[@]}"; do
      # test-support publishes the settle pump and has no tests of its own.
      [[ "$m" == ":shared:test-support" ]] && continue
      run_selection <<<"${module_rep[$m]}"
      if [[ "$SELECT_MODE" == "full" ]]; then continue; fi
      tasks="$(plan_gradle_unit | sed -n 's/^UNIT_SHARED_TASKS=//p')"
      [[ " $tasks " == *" $m:test "* ]] || \
        i11_fail+=("changing ${module_rep[$m]} does not run $m:test (got: ${tasks:-<none>})")
    done
    if [[ "${#i11_fail[@]}" -gt 0 ]]; then
      echo "FAIL I11: ${#i11_fail[@]} shared module(s) are not run by a change to their own source:"
      printf '  %s\n' "${i11_fail[@]}"
      failures=$((failures + 1))
    else
      echo "OK I11: every shared module's own change emits its own :test task (${#module_rep[@]} modules)"
    fi
  fi


  if [[ "$failures" -gt 0 ]]; then
    echo
    echo "::error title=Coverage invariant (issue #2063)::$failures invariant(s) failed — area selection would reduce what is ever executed."
    return 1
  fi
  echo
  if [[ -n "$ONLY_INVARIANTS" ]]; then
    echo "PASS: invariants $ONLY_INVARIANTS hold (FILTERED run — not the full coverage guard)."
  else
    echo "PASS: coverage invariant holds."
  fi
  return 0
}

list_journeys() {
  local fqcn area tier
  printf '%-8s %-22s %s\n' "TIER" "AREA" "CLASS"
  while IFS= read -r fqcn; do
    [[ -z "$fqcn" ]] && continue
    area="$(pocketshell_test_area_for_class "$fqcn")" || area="UNRESOLVED"
    tier="${POCKETSHELL_TA_AREA_TIER[$area]:-always}"
    printf '%-8s %-22s %s\n' "$tier" "$area" "$fqcn"
  done < <(journey_registry_classes)
}

# Every class the cadence is responsible for: tracked test files UNION the
# journey registry (a registry entry can name a second class living inside a
# sibling's file). Same union the ledger guard registers, printed here so the
# ledger's real-tree self-test can seed a complete ledger without a second,
# divergent derivation of the class set.
list_classes() {
  pocketshell_test_areas_build_index
  local fqcn area deps
  {
    printf '%s\n' "${!POCKETSHELL_TA_CLASS_PATH[@]}"
    journey_registry_classes
  } | LC_ALL=C sort -u | while IFS= read -r fqcn; do
    [[ -z "$fqcn" ]] && continue
    pocketshell_test_area_for_class "$fqcn" >/dev/null
    area="${POCKETSHELL_TEST_AREA_FOR_CLASS:-__NONE__}"
    pocketshell_test_area_deps_for_class "$fqcn" >/dev/null
    deps="$POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS"
    printf '%s\t%s\t%s\t%s\t%s\n' \
      "$fqcn" "$area" \
      "${POCKETSHELL_TA_CLASS_MODULE[$fqcn]:-__NONE__}" \
      "${POCKETSHELL_TA_CLASS_SOURCESET[$fqcn]:-__NONE__}" \
      "$(echo $deps)"
  done
}

case "$MODE" in
  verify)   verify_manifest; exit $? ;;
  list-classes) list_classes; exit 0 ;;
  coverage) coverage_invariant; exit $? ;;
  selfcheck)
    rc=0
    verify_manifest || rc=1
    echo
    coverage_invariant || rc=1
    exit "$rc"
    ;;
  journeys) list_journeys; exit 0 ;;
esac

run_selection < <(compute_changed_paths)

if [[ "$MODE" == "plan-only" ]]; then
  print_plan_only
else
  print_plan
fi

if [[ "$EMIT_GITHUB_OUTPUT" -eq 1 && -n "${GITHUB_OUTPUT:-}" ]]; then
  print_plan_only >> "$GITHUB_OUTPUT"
fi

exit 0
