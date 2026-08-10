#!/usr/bin/env bash
# Executed-classes ledger guard (issue #2063, analysis #2059).
#
# THE CLASS THIS EXISTS TO KILL
#
# `check-executed-test-counts.sh` (#1646) proves a test TASK executed more than
# zero tests. That is one level too low to see the failure this repo keeps
# hitting: a test class that belongs to NO suite, so nothing ever runs it, and
# nobody notices for months. #1851 found `ColdInstallE2eTest` and
# `EmulatorWorkflowE2eTest` in that state by accident. #1853 found twelve dead
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
#   --ledger PATH     ledger file (default $POCKETSHELL_TEST_LEDGER or
#                     build/test-execution-ledger.tsv)
#   --now EPOCH       pin "now" (tests and reproducible runs)
#   --report          print findings, always exit 0
#   --allow-empty     verify against an absent/empty ledger without failing.
#                     ONE-TIME seeding only; see the comment at is_ledger_usable.
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
JOURNEY_SUITE="${POCKETSHELL_TEST_AREAS_JOURNEY_SUITE:-$SCRIPT_DIR/ci-journey-suite.sh}"

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

while [[ $# -gt 0 ]]; do
  case "$1" in
    --record) MODE="record"; RESULTS_ROOT="$2"; shift 2 ;;
    --verify) MODE="verify"; shift ;;
    --tier) TIER="$2"; shift 2 ;;
    --ledger) LEDGER="$2"; shift 2 ;;
    --max-age-days) MAX_AGE_DAYS="$2"; shift 2 ;;
    --now) NOW="$2"; shift 2 ;;
    --report) REPORT_ONLY=1; shift ;;
    --allow-empty) ALLOW_EMPTY=1; shift ;;
    -h|--help) sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$MODE" ]]; then
  echo "error: one of --record <dir> or --verify is required" >&2
  exit 1
fi
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
parse_results() {
  local root="$1"
  [[ -d "$root" ]] || return 0
  find "$root" -type f -name '*.xml' -print0 2>/dev/null |
    xargs -0 -r sed 's/</\n</g' 2>/dev/null |
    awk '
      /^<testcase/ {
        cls = ""
        if (match($0, /classname="[^"]*"/)) {
          cls = substr($0, RSTART + 11, RLENGTH - 12)
        }
        if (cls == "") { next }
        if ($0 ~ /\/>[[:space:]]*$/) { print cls "\tran"; cur = ""; next }
        cur = cls; skipped = 0
        next
      }
      /^<skipped/ { if (cur != "") skipped = 1; next }
      /^<\/testcase/ {
        if (cur != "") { print cur "\t" (skipped ? "skipped" : "ran"); cur = "" }
        next
      }
    '
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

  if [[ -f "$JOURNEY_SUITE" ]]; then
    sed -nE \
      -e 's/.*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/com.pocketshell.app.proof.\1/p' \
      -e 's/.*"(com\.pocketshell\.app\.[A-Za-z0-9_.]+)(#[^"]*)?".*/\1/p' \
      "$JOURNEY_SUITE" | grep -E '\.[A-Z][A-Za-z0-9_]*$'
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

  local -A ran=()
  local -A seen=()
  local cls state
  while IFS=$'\t' read -r cls state; do
    [[ -z "$cls" ]] && continue
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
  local cls area at total=0
  while IFS= read -r cls; do
    [[ -z "$cls" ]] && continue
    [[ -n "${seen_registered[$cls]:-}" ]] && continue
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

case "$MODE" in
  record) do_record; exit $? ;;
  verify) do_verify; exit $? ;;
esac
