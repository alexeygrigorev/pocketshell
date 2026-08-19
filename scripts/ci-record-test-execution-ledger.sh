#!/usr/bin/env bash
# CI wrapper for the unit-lane execution ledger (#2082).
#
# Keeps the Tests workflow YAML small (file-size ratchet) by owning:
#   * collecting this run's JUnit XML into a tight results root
#   * --record into the rolling ledger
#   * current-run attendance against the variant that just ran
#     (test + testDebug on Debug, test + testRelease on Release)
#   * --verify --source-set unit-debug|unit-release
#
# An absent results root, empty XML set, or missing marker fails closed.
# --variant is required: unscoped selected-from=unit includes both
# src/testDebug and src/testRelease, which a single matrix shard cannot emit.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TIER="unit"
VARIANT=""
SOURCE_SET=""
NEWER_THAN=""
LEDGER="${POCKETSHELL_TEST_LEDGER:-$ROOT/build/test-execution-ledger.tsv}"
ATTENDANCE_OUT="${POCKETSHELL_TEST_ATTENDANCE_OUT:-$ROOT/build/test-execution-attendance.tsv}"
RESULTS_STAGING="${POCKETSHELL_LEDGER_RESULTS_STAGING:-${RUNNER_TEMP:-/tmp}/pocketshell-ledger-results}"

usage() {
  sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tier) TIER="$2"; shift 2 ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --source-set) SOURCE_SET="$2"; shift 2 ;;
    --newer-than) NEWER_THAN="$2"; shift 2 ;;
    --ledger) LEDGER="$2"; shift 2 ;;
    --out) ATTENDANCE_OUT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

SELECTED_FROM=""
case "$VARIANT" in
  Debug)
    SELECTED_FROM="unit-debug"
    SOURCE_SET="${SOURCE_SET:-unit-debug}"
    ;;
  Release)
    SELECTED_FROM="unit-release"
    SOURCE_SET="${SOURCE_SET:-unit-release}"
    ;;
  *)
    echo "error: --variant Debug|Release is required (attendance is current-run; testDebugUnitTest cannot emit src/testRelease)" >&2
    exit 1
    ;;
esac

if [[ -n "$NEWER_THAN" && ! -e "$NEWER_THAN" ]]; then
  echo "::error title=Test-execution ledger (issue #2082)::--newer-than marker not found: $NEWER_THAN"
  exit 1
fi

rm -rf "$RESULTS_STAGING"
mkdir -p "$RESULTS_STAGING" "$(dirname "$LEDGER")" "$(dirname "$ATTENDANCE_OUT")"

copied=0
while IFS= read -r -d '' xml; do
  if [[ -n "$NEWER_THAN" && ! "$xml" -nt "$NEWER_THAN" ]]; then
    continue
  fi
  cp "$xml" "$RESULTS_STAGING/$(printf '%s' "$xml" | tr '/ ' '__')"
  copied=$((copied + 1))
done < <(find . -path '*/build/test-results/*' -type f -name '*.xml' -print0 2>/dev/null)

if [[ "$copied" -eq 0 ]]; then
  echo "::error title=Test-execution ledger (issue #2082)::no JUnit XML under */build/test-results — refusing to record or verify an empty run"
  exit 1
fi
echo "staged $copied JUnit XML file(s) from */build/test-results into $RESULTS_STAGING"

GUARD="$ROOT/scripts/check-test-execution-ledger.sh"
IDENT_ARGS=(
  --identity "workflow=${POCKETSHELL_LEDGER_IDENTITY_WORKFLOW:-${GITHUB_WORKFLOW:-Tests}}"
  --identity "run_id=${POCKETSHELL_LEDGER_IDENTITY_RUN:-${GITHUB_RUN_ID:-local}}"
  --identity "sha=${POCKETSHELL_LEDGER_IDENTITY_SHA:-${GITHUB_SHA:-unknown}}"
  --identity "job=${POCKETSHELL_LEDGER_IDENTITY_JOB:-unit}"
  --identity "shard=${POCKETSHELL_LEDGER_IDENTITY_SHARD:-$VARIANT}"
  --identity "selector=$SELECTED_FROM"
  --identity "tier=$TIER"
)

bash "$GUARD" --record "$RESULTS_STAGING" --ledger "$LEDGER" --tier "$TIER"
bash "$GUARD" --attendance --results-root "$RESULTS_STAGING" \
  --selected-from "$SELECTED_FROM" \
  --out "$ATTENDANCE_OUT" \
  "${IDENT_ARGS[@]}"
bash "$GUARD" --verify --ledger "$LEDGER" --source-set "$SOURCE_SET"
