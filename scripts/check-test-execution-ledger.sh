#!/usr/bin/env bash
# Executed-classes ledger guard (issue #2063, analysis #2059).
#
# THE CLASS THIS EXISTS TO KILL
#
# `check-executed-test-counts.sh` (#1646) proves a test TASK executed more than
# zero tests. That is one level too low to see the failure this repo keeps
# hitting: a test class that belongs to NO suite, so nothing ever runs it, and
# nobody notices for months. #1851 *reported* `ColdInstallE2eTest` and
# `EmulatorWorkflowE2eTest` as unwired; nightly wholesale later proved they
# were selected and executed, but accounted incorrectly (#2082). #1853 found twelve dead
# shell harnesses the same way. #1859 found a nightly shard silently truncating
# at 98 of 226 tests — which leaves 128 classes unexecuted while every task-level
# count check stays green. All three were found INCIDENTALLY. None was found by
# a guard.
#
# Area-scoped selection (#2063) makes that class strictly more dangerous,
# because "this test did not run in this job" becomes NORMAL and therefore stops
# being suspicious. The coverage invariant — every test still runs on a bounded
# cadence — cannot rest on the manifest being correct; it has to be OBSERVED.
# This guard is that observation: it reads what actually executed, and fails
# when a registered class has not been seen inside the cadence window.
#
# WHAT "EXECUTED" MEANS HERE
#
# Appearing in a JUnit result XML as a testcase that was NOT skipped. A class
# whose every case is `<skipped/>` is recorded as SEEN-BUT-SKIPPED and does NOT
# satisfy the guard: an all-skipped class is the G3 vacuous pass, and treating it
# as coverage is exactly the mistake the 72-entry
# KNOWN_UNWIRED_ANDROID_E2E_DOCKER_CLASSES baseline currently invites.
#
# USAGE
#   check-test-execution-ledger.sh --record <results-root> [--tier NAME]
#       Scan <results-root> recursively for TEST-*.xml / *.xml JUnit results and
#       upsert `class -> last executed at` into the ledger.
#
#   check-test-execution-ledger.sh --verify [--max-age-days N]
#       Fail when any registered test class (a) belongs to no area, or (b) has
#       not executed within N days (default 7).
#
#   check-test-execution-ledger.sh --attendance --results-root DIR
#       Current-run selected / executed-unskipped / asserted ledger (#2082,
#       remaining #1859 contract). Compares the selector's class set against
#       THIS run's JUnit XML. A selected class with no result is RED (or sits
#       in an explicit missing bucket that still blocks a green attendance
#       verdict). Truncation is therefore distinguishable from a completed
#       passing shard.
#
#   --ledger PATH     ledger file (default $POCKETSHELL_TEST_LEDGER or
#                     build/test-execution-ledger.tsv)
#   --now EPOCH       pin "now" (tests and reproducible runs)
#   --report          print findings, always exit 0
#   --allow-empty     verify against an absent/empty ledger without failing.
#                     ONE-TIME seeding only; see the comment at is_ledger_usable.
#   --source-set S    verify only classes in this source set
#                     (unit | unit-debug | unit-release | androidTest | all).
#                     unit-debug = test+testDebug (the Debug job's artifact);
#                     unit-release = test+testRelease. Default all.
#   --newer-than F    --record/--attendance: ignore XML not newer than F
#                     (the #1646 UP-TO-DATE / FROM-CACHE marker).
#   --selected-file F attendance: selected FQCNs, one per line
#   --selected-from S attendance: derive selected set from the existing
#                     taxonomy (unit | unit-debug | unit-release |
#                     app2-journey). Reuses the #2474 wholesale-premise
#                     coverage; does not invent a second reachability analyzer.
#                     app2-journey is the whole app2/src/androidTest set (the
#                     own notClass list, plus documented nightly-connected
#                     unconventional rows — the classes
#                     :app:connectedDebugAndroidTest can actually emit.
#                     unit-debug / unit-release match the Gradle variant
#                     that produced the XML (testDebugUnitTest cannot emit
#                     src/testRelease) and mirror ordinary-task exclusions;
#                     opt-in RealLlmTest classes remain ledger-registered but
#                     are selected only by :app:realLlmTest.
#   --print-selected  print the selected FQCNs (one per line) and exit.
#                     Requires --selected-from or --selected-file.
#   --require-class C attendance: FQCN that must be asserted/load-bearing
#                     in THIS run's artifact (repeatable).
#   --identity K=V    attendance: run/shard signature field (repeatable).
#   --out PATH        attendance: write the machine-readable report here.
#   --merge-attendance DIR
#                     union shard attendance reports and fail closed on any
#                     selected class missing from the union.
#
# LEDGER FORMAT (tab-separated, sorted, human-diffable)
#   <fqcn>\t<epoch-seconds>\t<tier>
#
# WHERE THE LEDGER LIVES
#
# Deliberately not decided by this script. It is a plain file; the CI wiring
# restores it from an Actions cache (rolling key) before `--record` and saves it
# after, and the release gate verifies it. Keeping persistence out of the guard
# is what lets the self-test drive it entirely from temp directories.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${POCKETSHELL_TEST_AREAS_REPO_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
MANIFEST="${POCKETSHELL_TEST_AREAS_MANIFEST:-$SCRIPT_DIR/test-areas.txt}"
JOURNEY_SUITE="${POCKETSHELL_TEST_AREAS_JOURNEY_SUITE:-$SCRIPT_DIR/ci-app2-journey-suite.sh}"

# shellcheck source=lib/test-areas.sh
source "$SCRIPT_DIR/lib/test-areas.sh"
POCKETSHELL_TA_REPO_ROOT="$REPO_ROOT"

MODE=""
RESULTS_ROOT=""
TIER="unspecified"
LEDGER="${POCKETSHELL_TEST_LEDGER:-$REPO_ROOT/build/test-execution-ledger.tsv}"
MAX_AGE_DAYS=7
NOW="$(date +%s)"
REPORT_ONLY=0
ALLOW_EMPTY=0
SOURCE_SET="all"
NEWER_THAN=""
SELECTED_FILE=""
SELECTED_FROM=""
ATTENDANCE_OUT=""
MERGE_ATTENDANCE_DIR=""
REQUIRE_CLASSES=()
IDENTITY_PAIRS=()
UNCONVENTIONAL="${POCKETSHELL_TEST_AREAS_UNCONVENTIONAL:-$SCRIPT_DIR/test-unconventional-test-files.txt}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --record) MODE="record"; RESULTS_ROOT="$2"; shift 2 ;;
    --verify) MODE="verify"; shift ;;
    --attendance) MODE="attendance"; shift ;;
    --merge-attendance) MODE="merge-attendance"; MERGE_ATTENDANCE_DIR="$2"; shift 2 ;;
    --results-root) RESULTS_ROOT="$2"; shift 2 ;;
    --tier) TIER="$2"; shift 2 ;;
    --ledger) LEDGER="$2"; shift 2 ;;
    --max-age-days) MAX_AGE_DAYS="$2"; shift 2 ;;
    --now) NOW="$2"; shift 2 ;;
    --report) REPORT_ONLY=1; shift ;;
    --allow-empty) ALLOW_EMPTY=1; shift ;;
    --source-set) SOURCE_SET="$2"; shift 2 ;;
    --newer-than) NEWER_THAN="$2"; shift 2 ;;
    --selected-file) SELECTED_FILE="$2"; shift 2 ;;
    --selected-from) SELECTED_FROM="$2"; shift 2 ;;
    --require-class) REQUIRE_CLASSES+=("$2"); shift 2 ;;
    --identity) IDENTITY_PAIRS+=("$2"); shift 2 ;;
    --out) ATTENDANCE_OUT="$2"; shift 2 ;;
    --print-selected) MODE="print-selected"; shift ;;
    -h|--help) sed -n '2,80p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$MODE" ]]; then
  echo "error: one of --record <dir>, --verify, --attendance, --merge-attendance <dir>, or --print-selected is required" >&2
  exit 1
fi
if [[ "$MODE" == "attendance" && -z "$RESULTS_ROOT" ]]; then
  echo "error: --attendance requires --results-root <dir>" >&2
  exit 1
fi
IFS=',' read -ra _SOURCE_SET_TOKS <<< "$SOURCE_SET"
for _tok in "${_SOURCE_SET_TOKS[@]}"; do
  case "$_tok" in
    all|unit|unit-debug|unit-release|androidTest) ;;
    *) echo "error: --source-set token '$_tok' is not all, unit, unit-debug, unit-release, or androidTest" >&2; exit 1 ;;
  esac
done
unset _tok _SOURCE_SET_TOKS
if ! [[ "$MAX_AGE_DAYS" =~ ^[0-9]+$ ]]; then
  echo "error: --max-age-days must be a non-negative integer" >&2
  exit 1
fi
if ! [[ "$NOW" =~ ^[0-9]+$ ]]; then
  echo "error: --now must be epoch seconds" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# JUnit XML -> "<classname>\t<ran|skipped>"
#
# The XML is re-split so every tag starts a line, which makes both the
# self-closing `<testcase .../>` form and the `<testcase>…<skipped/></testcase>`
# form parseable without an XML library. A class counts as RAN as soon as one of
# its cases ran; all-skipped classes are reported separately and do NOT count.
# ---------------------------------------------------------------------------
normalize_cls() {
  # Inner/parameterized JUnit classnames (`FooTest$Inner`) credit the outer
  # registered class. Strip a trailing device suffix if a runner ever emits one.
  local c="$1"
  c="${c%%\$*}"
  printf '%s' "$c"
}

xml_is_fresh() {
  local f="$1"
  [[ -z "$NEWER_THAN" ]] && return 0
  [[ -e "$NEWER_THAN" ]] || return 1
  [[ "$f" -nt "$NEWER_THAN" ]]
}

parse_results() {
  local root="$1"
  [[ -d "$root" ]] || return 0
  local -a xmls=()
  local f
  while IFS= read -r -d '' f; do
    xml_is_fresh "$f" || continue
    xmls+=("$f")
  done < <(find "$root" -type f \( -name 'TEST-*.xml' -o -name '*.xml' \) -print0 2>/dev/null)
  [[ "${#xmls[@]}" -gt 0 ]] || return 0
  # time= is parsed so attendance can distinguish a body that ran (asserted)
  # from a skipped/zero-time placeholder. A skipped case is never asserted.
  printf '%s\0' "${xmls[@]}" |
    xargs -0 -r sed 's/</\n</g' 2>/dev/null |
    awk '
      /^<testcase/ {
        cls = ""
        if (match($0, /classname="[^"]*"/)) {
          cls = substr($0, RSTART + 11, RLENGTH - 12)
        }
        if (cls == "") { next }
        t = 0
        if (match($0, /time="[^"]*"/)) {
          t = substr($0, RSTART + 6, RLENGTH - 7) + 0
        }
        if ($0 ~ /\/>[[:space:]]*$/) { print cls "\tran\t" t; cur = ""; next }
        cur = cls; skipped = 0; cur_t = t
        next
      }
      /^<skipped/ { if (cur != "") skipped = 1; next }
      /^<\/testcase/ {
        if (cur != "") { print cur "\t" (skipped ? "skipped" : "ran") "\t" cur_t; cur = "" }
        next
      }
    '
}

is_unit_source_set() {
  case "$1" in test|testDebug|testRelease) return 0 ;; *) return 1 ;; esac
}

# Debug job runs testDebugUnitTest: src/test + src/testDebug. It cannot
# emit a src/testRelease class. Release is the mirror.
is_unit_debug_source_set() {
  case "$1" in test|testDebug) return 0 ;; *) return 1 ;; esac
}

is_unit_release_source_set() {
  case "$1" in test|testRelease) return 0 ;; *) return 1 ;; esac
}

# The `ordinary_unit_class_selected` predicate was DELETED with the opt-in
# real-LLM lane (D22). It modelled app/build.gradle.kts excluding
# `**/*RealLlmTest.class` from the ordinary unit tasks while a dedicated
# `:app:realLlmTest` task included it; the module, the task, the exclusion and
# the class are all gone. Keeping it would leave an always-true filter in the
# selector that reads like a policy and enforces nothing.

source_set_included() {
  local fqcn="$1" srcset="$2" tok
  local IFS=','
  for tok in $SOURCE_SET; do
    case "$tok" in
      all) return 0 ;;
      unit) is_unit_source_set "$srcset" && return 0 ;;
      unit-debug) is_unit_debug_source_set "$srcset" && return 0 ;;
      unit-release) is_unit_release_source_set "$srcset" && return 0 ;;
      androidTest) [[ "$srcset" == "androidTest" ]] && return 0 ;;
    esac
  done
  return 1
}

# ---------------------------------------------------------------------------
# Selected-class sets. unit / unit-debug / unit-release and app2-journey reuse
# the existing taxonomy (the #2474 wholesale premise for the journey lane).
# This is NOT a second reachability analyzer: app2-journey is every
# app2/src/androidTest class, because that lane runs
# :app2:connectedDebugAndroidTest unfiltered (not shared-module instrumented
# tests, which are other Gradle tasks).
# ---------------------------------------------------------------------------
load_selected_file() {
  local f="$1" line
  [[ -f "$f" ]] || { echo "error: selected file not found: $f" >&2; return 1; }
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    printf '%s\n' "$(normalize_cls "$line")"
  done < "$f"
}

# The instrumented lane's androidTest root, read off the suite that actually
# runs it. The old `nightly-phase1` lane was "every app/src/androidTest class
# MINUS the nightly suite's notClass list"; both halves died with the app module.
# app2's lane (issue #2474) runs `:app2:connectedDebugAndroidTest` unfiltered in
# ONE process, so the selected set is simply every androidTest class in that
# module — there is no exclusion list to subtract, which is why this reads a task
# rather than an exclusions mode. The no-filter premise is CHECKED, not assumed:
# if a class/package/annotation filter ever appears in the command the suite
# builds, "every class in the module" stops being true and this fails closed.
journey_lane_android_test_dir() {
  if [[ ! -f "$JOURNEY_SUITE" ]]; then
    echo "error: journey suite not found: $JOURNEY_SUITE" >&2
    return 1
  fi
  local task
  task="$(sed -nE 's/^JOURNEY_TASK="([^"]+)".*/\1/p' "$JOURNEY_SUITE" | head -1)"
  if [[ -z "$task" ]]; then
    echo "error: $JOURNEY_SUITE has no JOURNEY_TASK=\"...\" assignment, so the wholesale selected set cannot be read from the suite that actually runs" >&2
    return 1
  fi
  local args_body
  args_body="$(sed -n '/^gradle_args() {$/,/^}$/p' "$JOURNEY_SUITE")"
  if [[ -z "$args_body" ]]; then
    echo "error: $JOURNEY_SUITE has no gradle_args() function, so its command line cannot be checked for a class filter" >&2
    return 1
  fi
  if grep -Eq 'testInstrumentationRunnerArguments\.(class|package|annotation)=' <<<"$args_body"; then
    echo "error: $JOURNEY_SUITE now filters what it runs, so 'every androidTest class in $task' is no longer the selected set" >&2
    return 1
  fi
  local mod="${task#:}"
  mod="${mod%:*}"
  printf '%s/src/androidTest\n' "${mod//://}"
}

selected_from_unit() {
  # Optional $1: debug | release. Empty = every unit source set (both
  # variants). A single Gradle variant cannot emit the other variant's
  # source set, so CI attendance must pass debug or release, not this union.
  local variant="${1:-}"
  pocketshell_test_areas_build_index
  local fqcn srcset
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    srcset="${POCKETSHELL_TA_CLASS_SOURCESET[$fqcn]:-}"
    case "$variant" in
      debug) is_unit_debug_source_set "$srcset" || continue ;;
      release) is_unit_release_source_set "$srcset" || continue ;;
      *) is_unit_source_set "$srcset" || continue ;;
    esac
    printf '%s\n' "$fqcn"
  done
}

# The lane runs ONE module's instrumented set. shared/*/src/androidTest and
# vendored instrumented tests live in other Gradle tasks and must not be in this
# selected set — a complete module union has to be able to go green.
is_journey_lane_path() {
  local dir="$1" path="$2"
  [[ -n "$dir" ]] || return 1
  case "$path" in
    "$dir"/*) return 0 ;;
    *) return 1 ;;
  esac
}

selected_from_app2_journey() {
  pocketshell_test_areas_build_index
  local dir
  dir="$(journey_lane_android_test_dir)" || return 1
  local fqcn path
  for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
    [[ "${POCKETSHELL_TA_CLASS_SOURCESET[$fqcn]:-}" == "androidTest" ]] || continue
    path="${POCKETSHELL_TA_CLASS_PATH[$fqcn]:-}"
    is_journey_lane_path "$dir" "$path" || continue
    printf '%s\n' "$fqcn"
  done
  # There is deliberately no unconventional-file branch here any more. It existed
  # for `nightly-connected` exemption rows — an executor kind deleted with the
  # module it named (see scripts/test-unconventional-test-files.txt). An
  # instrumented file outside the naming convention has no execution claim the
  # guards will grant today, so none can be selected into this lane.
}

load_selected() {
  if [[ -n "$SELECTED_FILE" ]]; then
    load_selected_file "$SELECTED_FILE"
    return
  fi
  case "$SELECTED_FROM" in
    unit) selected_from_unit ;;
    unit-debug) selected_from_unit debug ;;
    unit-release) selected_from_unit release ;;
    app2-journey) selected_from_app2_journey ;;
    "")
      echo "error: --attendance requires --selected-file or --selected-from" >&2
      return 1
      ;;
    *)
      echo "error: unknown --selected-from '$SELECTED_FROM' (expected unit, unit-debug, unit-release, or app2-journey)" >&2
      return 1
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Registered classes: every tracked *Test.kt across every test source set, plus
# every journey-registry entry (some registry entries name a second class that
# lives inside a sibling's file, so the registry is not a strict subset).
# ---------------------------------------------------------------------------
# Reads THE index (scripts/lib/test-areas.sh) rather than re-deriving the class
# set. Round 1 had two derivations and two resolvers; the FQCN-side one probed a
# hardcoded list of source roots that omitted every `shared/*/src/test/java`, so
# 183 of 1047 registered classes — all 49 core-ssh and 22 core-connection D28
# classes among them — reported "belongs to NO area" and this guard was red on
# the real tree while its self-test (synthetic app-style trees only) stayed
# green. One derivation, one resolver, and a real-tree self-test case now.
registered_classes() {
  pocketshell_test_areas_build_index
  local fqcn
  printf '%s\n' "${!POCKETSHELL_TA_CLASS_PATH[@]}"

  # The journey registry used to be a hand-maintained FQCN list that could name
  # a class the index did not have (or vice versa), so it was UNIONED in here.
  # It is now derived FROM the tree — every class in the lane's androidTest
  # module — so it is a subset of the index by construction and a union would be
  # a no-op. What is NOT a no-op is checking that the derivation still works:
  # a broken premise must fail this guard rather than quietly shrink the
  # registered set to "whatever the index happened to contain".
  local lane_dir
  if ! lane_dir="$(journey_lane_android_test_dir)"; then
    echo "::error title=Test-execution ledger (issue #2063)::the instrumented lane's selected set cannot be derived, so the registered class set would silently omit every journey" >&2
    return 1
  fi
  if [[ ! -d "$REPO_ROOT/$lane_dir" ]]; then
    echo "::error title=Test-execution ledger (issue #2063)::instrumented lane root $lane_dir does not exist" >&2
    return 1
  fi
}

# ---------------------------------------------------------------------------
# record
# ---------------------------------------------------------------------------
do_record() {
  if [[ ! -d "$RESULTS_ROOT" ]]; then
    echo "::error title=Test-execution ledger (issue #2063)::results root not found: $RESULTS_ROOT"
    return 1
  fi

  if [[ -n "$NEWER_THAN" && ! -e "$NEWER_THAN" ]]; then
    echo "::error title=Test-execution ledger (issue #2082)::--newer-than marker not found: $NEWER_THAN — refusing to record (an absent marker is not a pass)"
    return 1
  fi

  local -A ran=()
  local -A seen=()
  local cls state t
  while IFS=$'\t' read -r cls state t; do
    [[ -z "$cls" ]] && continue
    cls="$(normalize_cls "$cls")"
    seen["$cls"]=1
    [[ "$state" == "ran" ]] && ran["$cls"]=1
  done < <(parse_results "$RESULTS_ROOT")

  if [[ "${#seen[@]}" -eq 0 ]]; then
    # A record pass that saw zero testcases is the vacuous-green shape
    # process.md catalogues: it would leave the ledger untouched and the next
    # verify would blame the age threshold instead of the empty result set.
    echo "::error title=Test-execution ledger (issue #2063)::no JUnit testcases found under $RESULTS_ROOT — refusing to record an empty run"
    return 1
  fi

  mkdir -p "$(dirname "$LEDGER")"
  local -A merged=()
  local -A merged_tier=()
  if [[ -f "$LEDGER" ]]; then
    local l_cls l_at l_tier
    while IFS=$'\t' read -r l_cls l_at l_tier; do
      [[ -z "$l_cls" ]] && continue
      merged["$l_cls"]="$l_at"
      merged_tier["$l_cls"]="$l_tier"
    done < "$LEDGER"
  fi
  for cls in "${!ran[@]}"; do
    merged["$cls"]="$NOW"
    merged_tier["$cls"]="$TIER"
  done

  local tmp
  tmp="$(mktemp)"
  for cls in "${!merged[@]}"; do
    printf '%s\t%s\t%s\n' "$cls" "${merged[$cls]}" "${merged_tier[$cls]:-unspecified}"
  done | LC_ALL=C sort > "$tmp"
  mv "$tmp" "$LEDGER"

  local skipped_only=0
  for cls in "${!seen[@]}"; do
    [[ -z "${ran[$cls]:-}" ]] && skipped_only=$((skipped_only + 1))
  done
  echo "recorded ${#ran[@]} executed class(es) into $LEDGER (tier=$TIER, now=$NOW)"
  echo "  ${#seen[@]} class(es) present in results; $skipped_only were ALL-SKIPPED and were NOT recorded as executed"
  return 0
}

# ---------------------------------------------------------------------------
# verify
#
# A missing or empty ledger FAILS. That direction is deliberate and it is the
# whole point: a guard that passes when it has no evidence is decoration, and
# an absent ledger is exactly the state a broken record step produces. Seeding
# is an explicit, visible `--allow-empty` run against a known full-suite result
# set, not a silent default.
# ---------------------------------------------------------------------------
is_ledger_usable() {
  [[ -s "$LEDGER" ]]
}

do_verify() {
  local failures=0
  echo "== test-execution ledger =="
  echo "ledger:  $LEDGER"
  echo "window:  ${MAX_AGE_DAYS} day(s)  (now=$NOW)"
  echo "source-set: $SOURCE_SET"

  if ! pocketshell_test_areas_load "$MANIFEST"; then
    echo "FAIL: area manifest did not load:"
    printf '  %s\n' "${POCKETSHELL_TA_LOAD_ERRORS[@]}"
    failures=$((failures + 1))
  fi

  local have_ledger=1
  if ! is_ledger_usable; then
    have_ledger=0
    if [[ "$ALLOW_EMPTY" -eq 1 ]]; then
      echo "WARN: ledger is missing/empty and --allow-empty was passed (one-time seeding)."
    else
      echo "FAIL: ledger is missing or empty — there is NO evidence that any test executed."
      failures=$((failures + 1))
    fi
  fi

  local -A last=()
  if [[ "$have_ledger" -eq 1 ]]; then
    local l_cls l_at l_tier
    while IFS=$'\t' read -r l_cls l_at l_tier; do
      [[ -z "$l_cls" ]] && continue
      last["$l_cls"]="$l_at"
    done < "$LEDGER"
  fi

  # Build the index HERE, in this shell. `area="$(pocketshell_test_area_for_class …)"`
  # forks, and a lazily-built index inside that fork is discarded on every
  # class — an O(n) index rebuilt n times, which is a two-minute hang rather
  # than a wrong answer, but a hang is still a guard nobody will keep enabled.
  pocketshell_test_areas_build_index

  local cutoff=$(( NOW - MAX_AGE_DAYS * 86400 ))
  local -a no_area=() never=() stale=()
  local -A seen_registered=()
  local cls area at total=0 srcset
  while IFS= read -r cls; do
    [[ -z "$cls" ]] && continue
    [[ -n "${seen_registered[$cls]:-}" ]] && continue
    srcset="${POCKETSHELL_TA_CLASS_SOURCESET[$cls]:-}"
    # Journey-registry-only names have no source set; they still belong in
    # the all-classes verify. --source-set unit/androidTest drops them unless
    # the index mapped them.
    if [[ "$SOURCE_SET" != "all" ]]; then
      source_set_included "$cls" "$srcset" || continue
    fi
    seen_registered["$cls"]=1
    total=$((total + 1))

    # THE resolver, read through its published global. Round-2 finding B7b: this
    # line used to re-inline the resolution chain right here — at the exact site
    # of the round-1 B4 two-resolver defect — so a fourth fallback added to
    # pocketshell_test_area_for_class would silently not apply in the one guard
    # whose whole job is to prove no class is unreachable.
    area=""
    pocketshell_test_area_for_class "$cls" >/dev/null && area="$POCKETSHELL_TEST_AREA_FOR_CLASS"
    if [[ -z "$area" ]]; then
      no_area+=("$cls")
    fi

    at="${last[$cls]:-}"
    if [[ -z "$at" ]]; then
      never+=("$cls")
    elif [[ "$at" -lt "$cutoff" ]]; then
      stale+=("$cls (last executed $(( (NOW - at) / 86400 ))d ago)")
    fi
  done < <(registered_classes)

  echo "classes: $total registered"

  if [[ "${#no_area[@]}" -gt 0 ]]; then
    echo
    echo "FAIL: ${#no_area[@]} registered class(es) belong to NO area — area selection can never choose them:"
    printf '  %s\n' "${no_area[@]}"
    failures=$((failures + 1))
  else
    echo "OK: every registered class belongs to an area"
  fi

  if [[ "$have_ledger" -eq 1 ]]; then
    if [[ "${#never[@]}" -gt 0 ]]; then
      echo
      echo "FAIL: ${#never[@]} registered class(es) have NEVER executed in the ledger (#1851 class):"
      printf '  %s\n' "${never[@]}"
      failures=$((failures + 1))
    fi
    if [[ "${#stale[@]}" -gt 0 ]]; then
      echo
      echo "FAIL: ${#stale[@]} registered class(es) have not executed within ${MAX_AGE_DAYS} day(s):"
      printf '  %s\n' "${stale[@]}"
      failures=$((failures + 1))
    fi
    if [[ "${#never[@]}" -eq 0 && "${#stale[@]}" -eq 0 ]]; then
      echo "OK: every registered class executed within the ${MAX_AGE_DAYS}-day window"
    fi
  fi

  if [[ "$REPORT_ONLY" -eq 1 ]]; then
    echo
    echo "Report mode (--report): findings printed; guard does not fail."
    return 0
  fi
  if [[ "$failures" -gt 0 ]]; then
    echo
    echo "::error title=Test-execution ledger (issue #2063)::$failures ledger check(s) failed. A registered test class is unmapped, never executed, or has fallen outside the ${MAX_AGE_DAYS}-day cadence window. Coverage is NOT invariant until this is green."
    return 1
  fi
  echo
  echo "PASS: every registered test class executed inside the cadence window."
  return 0
}

# ---------------------------------------------------------------------------
# attendance — current-run selected / executed / asserted
# ---------------------------------------------------------------------------
class_state_rank() {
  case "$1" in
    asserted) echo 4 ;;
    executed) echo 3 ;;
    skipped) echo 2 ;;
    missing) echo 1 ;;
    *) echo 0 ;;
  esac
}

best_state() {
  local a="$1" b="$2"
  if [[ "$(class_state_rank "$a")" -ge "$(class_state_rank "$b")" ]]; then
    printf '%s' "$a"
  else
    printf '%s' "$b"
  fi
}

write_attendance_report() {
  local dest="$1"
  local selected_n="$2" result_n="$3" executed_n="$4" asserted_n="$5" skipped_n="$6" missing_n="$7"
  shift 7
  {
    echo "# pocketshell-test-run-attendance v1"
    local pair
    for pair in "${IDENTITY_PAIRS[@]}"; do
      printf 'identity.%s\n' "${pair/=/$'\t'}"
    done
    printf 'selected_class_count\t%s\n' "$selected_n"
    printf 'result_class_count\t%s\n' "$result_n"
    printf 'executed_unskipped_count\t%s\n' "$executed_n"
    printf 'asserted_count\t%s\n' "$asserted_n"
    printf 'skipped_only_count\t%s\n' "$skipped_n"
    printf 'missing_class_count\t%s\n' "$missing_n"
    local cls
    for cls in "$@"; do
      printf 'class\t%s\t%s\n' "$cls" "${CLASS_STATE[$cls]}"
    done
  } > "$dest"
}

do_attendance() {
  if [[ -n "$NEWER_THAN" && ! -e "$NEWER_THAN" ]]; then
    echo "::error title=Test-run attendance (issue #2082)::--newer-than marker not found: $NEWER_THAN — an absent marker is not a pass"
    return 1
  fi
  if [[ ! -d "$RESULTS_ROOT" ]]; then
    echo "::error title=Test-run attendance (issue #2082)::results root not found: $RESULTS_ROOT"
    return 1
  fi

  local -A selected=()
  # CLASS_STATE is intentionally global so write_attendance_report can read it.
  unset CLASS_STATE 2>/dev/null || true
  declare -A CLASS_STATE=()
  local -a selected_list=()
  local cls
  while IFS= read -r cls; do
    [[ -z "$cls" ]] && continue
    cls="$(normalize_cls "$cls")"
    [[ -n "${selected[$cls]:-}" ]] && continue
    selected["$cls"]=1
    selected_list+=("$cls")
  done < <(load_selected) || return 1

  if [[ "${#selected_list[@]}" -eq 0 ]]; then
    echo "::error title=Test-run attendance (issue #2082)::selected set is empty — refusing to report a green attendance over nothing"
    return 1
  fi

  local -A seen=() ran=() asserted=()
  local state t
  while IFS=$'\t' read -r cls state t; do
    [[ -z "$cls" ]] && continue
    cls="$(normalize_cls "$cls")"
    seen["$cls"]=1
    if [[ "$state" == "ran" ]]; then
      ran["$cls"]=1
      # Asserted/load-bearing: the body ran. JUnit records a skipped
      # assumption as <skipped/>, which never takes this branch. That is
      # the observational distinction the existing ledger already uses.
      asserted["$cls"]=1
    fi
  done < <(parse_results "$RESULTS_ROOT")

  # A record/attendance pass that saw zero testcases is the vacuous-green
  # shape: truncation, FROM-CACHE, or a killed run. Fail closed.
  if [[ "${#seen[@]}" -eq 0 ]]; then
    echo "::error title=Test-run attendance (issue #2082)::no JUnit testcases found under $RESULTS_ROOT — an absent result is not a completed shard"
    return 1
  fi

  local -a missing=() skipped_only=()
  local executed_n=0 asserted_n=0
  for cls in "${selected_list[@]}"; do
    if [[ -z "${seen[$cls]:-}" ]]; then
      CLASS_STATE["$cls"]="missing"
      missing+=("$cls")
    elif [[ -n "${ran[$cls]:-}" ]]; then
      CLASS_STATE["$cls"]="asserted"
      executed_n=$((executed_n + 1))
      asserted_n=$((asserted_n + 1))
    else
      CLASS_STATE["$cls"]="skipped"
      skipped_only+=("$cls")
    fi
  done

  local result_n=0
  for cls in "${!seen[@]}"; do
    result_n=$((result_n + 1))
  done

  echo "== test-run attendance =="
  echo "selected:            ${#selected_list[@]}"
  echo "result classes:      $result_n"
  echo "executed unskipped:  $executed_n"
  echo "asserted:            $asserted_n"
  echo "skipped-only:        ${#skipped_only[@]}"
  echo "missing (no result): ${#missing[@]}"
  local pair
  for pair in "${IDENTITY_PAIRS[@]}"; do
    echo "identity: $pair"
  done

  if [[ -n "$ATTENDANCE_OUT" ]]; then
    mkdir -p "$(dirname "$ATTENDANCE_OUT")"
    local -a sorted_sel=()
    mapfile -t sorted_sel < <(printf '%s\n' "${selected_list[@]}" | LC_ALL=C sort)
    write_attendance_report "$ATTENDANCE_OUT" \
      "${#selected_list[@]}" "$result_n" "$executed_n" "$asserted_n" \
      "${#skipped_only[@]}" "${#missing[@]}" \
      "${sorted_sel[@]}"
    echo "wrote $ATTENDANCE_OUT"
  fi

  local failures=0
  if [[ "${#missing[@]}" -gt 0 ]]; then
    echo
    echo "FAIL: ${#missing[@]} selected class(es) produced NO result (#1859 truncation class):"
    printf '  %s\n' "${missing[@]}" | head -n 200
    if [[ "${#missing[@]}" -gt 200 ]]; then
      echo "  ... $(( ${#missing[@]} - 200 )) more"
    fi
    failures=$((failures + 1))
  fi

  local req
  for req in "${REQUIRE_CLASSES[@]}"; do
    req="$(normalize_cls "$req")"
    if [[ "${CLASS_STATE[$req]:-}" != "asserted" ]]; then
      echo
      echo "FAIL: required class $req was not asserted/load-bearing in this run's artifact (state=${CLASS_STATE[$req]:-absent})"
      failures=$((failures + 1))
    else
      echo "OK: required class $req is asserted/load-bearing"
    fi
  done

  if [[ "$REPORT_ONLY" -eq 1 ]]; then
    echo
    echo "Report mode (--report): findings printed; guard does not fail."
    return 0
  fi
  if [[ "$failures" -gt 0 ]]; then
    echo
    echo "::error title=Test-run attendance (issue #2082)::$failures attendance check(s) failed. A selected class is missing from the JUnit artifact, or a required FQCN did not reach its hard-asserting path."
    return 1
  fi
  echo
  echo "PASS: every selected class produced a result; required classes are asserted."
  return 0
}

read_attendance_file() {
  local f="$1" kind val cls st
  while IFS=$'\t' read -r kind val st || [[ -n "$kind" ]]; do
    [[ -z "$kind" || "$kind" == \#* ]] && continue
    case "$kind" in
      class)
        cls="$(normalize_cls "$val")"
        [[ -z "$cls" ]] && continue
        MERGE_STATE["$cls"]="$(best_state "${MERGE_STATE[$cls]:-missing}" "$st")"
        MERGE_SEEN["$cls"]=1
        ;;
    esac
  done < "$f"
}

do_merge_attendance() {
  if [[ ! -d "$MERGE_ATTENDANCE_DIR" ]]; then
    echo "::error title=Test-run attendance (issue #2082)::merge directory not found: $MERGE_ATTENDANCE_DIR"
    return 1
  fi

  local -a files=()
  local f
  while IFS= read -r -d '' f; do
    files+=("$f")
  done < <(find "$MERGE_ATTENDANCE_DIR" -type f \( -name '*attendance*' -o -name '*.tsv' \) -print0 2>/dev/null)

  if [[ "${#files[@]}" -eq 0 ]]; then
    echo "::error title=Test-run attendance (issue #2082)::no attendance reports under $MERGE_ATTENDANCE_DIR — an absent ledger is not a passing verify"
    return 1
  fi

  # MERGE_STATE / MERGE_SEEN are global so read_attendance_file can write them.
  unset MERGE_STATE MERGE_SEEN 2>/dev/null || true
  declare -A MERGE_STATE=() MERGE_SEEN=()
  local -A selected=()
  local -a selected_list=()
  local cls
  while IFS= read -r cls; do
    [[ -z "$cls" ]] && continue
    cls="$(normalize_cls "$cls")"
    [[ -n "${selected[$cls]:-}" ]] && continue
    selected["$cls"]=1
    selected_list+=("$cls")
  done < <(load_selected) || return 1

  if [[ "${#selected_list[@]}" -eq 0 ]]; then
    echo "::error title=Test-run attendance (issue #2082)::selected set is empty — refusing to merge a green attendance over nothing"
    return 1
  fi

  for f in "${files[@]}"; do
    read_attendance_file "$f"
  done

  if [[ "${#MERGE_SEEN[@]}" -eq 0 ]]; then
    echo "::error title=Test-run attendance (issue #2082)::attendance reports under $MERGE_ATTENDANCE_DIR contain no class rows"
    return 1
  fi

  unset CLASS_STATE 2>/dev/null || true
  declare -A CLASS_STATE=()
  local -a missing=() skipped_only=()
  local executed_n=0 asserted_n=0 result_n=0
  for cls in "${selected_list[@]}"; do
    local st="${MERGE_STATE[$cls]:-missing}"
    CLASS_STATE["$cls"]="$st"
    case "$st" in
      asserted)
        executed_n=$((executed_n + 1))
        asserted_n=$((asserted_n + 1))
        result_n=$((result_n + 1))
        ;;
      executed)
        executed_n=$((executed_n + 1))
        result_n=$((result_n + 1))
        ;;
      skipped)
        skipped_only+=("$cls")
        result_n=$((result_n + 1))
        ;;
      missing|*)
        missing+=("$cls")
        ;;
    esac
  done

  echo "== merged test-run attendance =="
  echo "reports:             ${#files[@]}"
  echo "selected:            ${#selected_list[@]}"
  echo "result classes:      $result_n"
  echo "executed unskipped:  $executed_n"
  echo "asserted:            $asserted_n"
  echo "skipped-only:        ${#skipped_only[@]}"
  echo "missing (no result): ${#missing[@]}"

  if [[ -n "$ATTENDANCE_OUT" ]]; then
    mkdir -p "$(dirname "$ATTENDANCE_OUT")"
    local -a sorted_sel=()
    mapfile -t sorted_sel < <(printf '%s\n' "${selected_list[@]}" | LC_ALL=C sort)
    write_attendance_report "$ATTENDANCE_OUT" \
      "${#selected_list[@]}" "$result_n" "$executed_n" "$asserted_n" \
      "${#skipped_only[@]}" "${#missing[@]}" \
      "${sorted_sel[@]}"
    echo "wrote $ATTENDANCE_OUT"
  fi

  local failures=0
  if [[ "${#missing[@]}" -gt 0 ]]; then
    echo
    echo "FAIL: ${#missing[@]} selected class(es) produced NO result across any shard (#1859 truncation class):"
    printf '  %s\n' "${missing[@]}" | head -n 200
    if [[ "${#missing[@]}" -gt 200 ]]; then
      echo "  ... $(( ${#missing[@]} - 200 )) more"
    fi
    failures=$((failures + 1))
  fi

  local req
  for req in "${REQUIRE_CLASSES[@]}"; do
    req="$(normalize_cls "$req")"
    if [[ "${CLASS_STATE[$req]:-}" != "asserted" ]]; then
      echo
      echo "FAIL: required class $req was not asserted/load-bearing in the merged artifact (state=${CLASS_STATE[$req]:-absent})"
      failures=$((failures + 1))
    else
      echo "OK: required class $req is asserted/load-bearing"
    fi
  done

  if [[ "$REPORT_ONLY" -eq 1 ]]; then
    echo
    echo "Report mode (--report): findings printed; guard does not fail."
    return 0
  fi
  if [[ "$failures" -gt 0 ]]; then
    echo
    echo "::error title=Test-run attendance (issue #2082)::$failures merged attendance check(s) failed."
    return 1
  fi
  echo
  echo "PASS: union of shard results covers every selected class; required classes are asserted."
  return 0
}

do_print_selected() {
  local cls n=0
  while IFS= read -r cls; do
    [[ -z "$cls" ]] && continue
    printf '%s\n' "$(normalize_cls "$cls")"
    n=$((n + 1))
  done < <(load_selected) || return 1
  if [[ "$n" -eq 0 ]]; then
    echo "error: selected set is empty — refusing to print a green selected set over nothing" >&2
    return 1
  fi
  return 0
}

case "$MODE" in
  record) do_record; exit $? ;;
  verify) do_verify; exit $? ;;
  attendance) do_attendance; exit $? ;;
  merge-attendance) do_merge_attendance; exit $? ;;
  print-selected) do_print_selected; exit $? ;;
esac
