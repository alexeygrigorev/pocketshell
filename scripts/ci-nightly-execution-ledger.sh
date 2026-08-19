#!/usr/bin/env bash
# Nightly current-run attendance + rolling-ledger merge (#2082 / #1859).
#
#   --shard     called from nightly-extensive-suite.sh after phase 1. Writes
#               an attendance report next to the preserved phase-1 XML. Does
#               NOT touch the rolling cache (parallel shards would clobber).
#
#   --aggregate called from the workflow's dedicated job after every shard
#               artifact is downloaded. Unions attendance, pins ColdInstall
#               and EmulatorWorkflow by FQCN, records into the rolling ledger,
#               and verifies androidTest classes. Missing shard reports or a
#               selected class with no result fail closed.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GUARD="$ROOT/scripts/check-test-execution-ledger.sh"
MODE=""
RESULTS_ROOT=""
OUT=""
LEDGER="${POCKETSHELL_TEST_LEDGER:-$ROOT/build/test-execution-ledger.tsv}"
ARTIFACT_ROOT=""
EXPECTED_SHARDS="${POCKETSHELL_NIGHTLY_SHARD_TOTAL:-3}"
PIN_COLD="com.pocketshell.app.proof.ColdInstallE2eTest"
PIN_WORKFLOW="com.pocketshell.app.proof.EmulatorWorkflowE2eTest"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --shard) MODE="shard"; shift ;;
    --aggregate) MODE="aggregate"; shift ;;
    --results-root) RESULTS_ROOT="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --ledger) LEDGER="$2"; shift 2 ;;
    --artifact-root) ARTIFACT_ROOT="$2"; shift 2 ;;
    --expected-shards) EXPECTED_SHARDS="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$MODE" ]]; then
  echo "error: --shard or --aggregate is required" >&2
  exit 1
fi

if [[ "$MODE" == "shard" ]]; then
  if [[ -z "$RESULTS_ROOT" || -z "$OUT" ]]; then
    echo "error: --shard requires --results-root and --out" >&2
    exit 1
  fi
  ident=()
  ident+=(--identity "workflow=${POCKETSHELL_LEDGER_IDENTITY_WORKFLOW:-${GITHUB_WORKFLOW:-Nightly Extensive Tests}}")
  ident+=(--identity "run_id=${POCKETSHELL_LEDGER_IDENTITY_RUN:-${GITHUB_RUN_ID:-local}}")
  ident+=(--identity "sha=${POCKETSHELL_LEDGER_IDENTITY_SHA:-${GITHUB_SHA:-unknown}}")
  ident+=(--identity "job=extensive")
  ident+=(--identity "shard=${POCKETSHELL_NIGHTLY_SHARD_INDEX:-0}")
  ident+=(--identity "selector=nightly-phase1-app-androidTest-minus-notClass")
  ident+=(--identity "results=$RESULTS_ROOT")
  # Per-shard attendance is REPORT-only for the missing-class comparison:
  # AndroidJUnitRunner shards by method, so this shard is not expected to
  # produce a result for every wholesale class. The load-bearing missing
  # check is --aggregate over the union. We still fail closed on empty XML.
  bash "$GUARD" --attendance --results-root "$RESULTS_ROOT" \
    --selected-from nightly-phase1 \
    --out "$OUT" \
    --report \
    "${ident[@]}"
  exit $?
fi

# --aggregate
if [[ -z "$ARTIFACT_ROOT" ]]; then
  echo "error: --aggregate requires --artifact-root" >&2
  exit 1
fi
if [[ ! -d "$ARTIFACT_ROOT" ]]; then
  echo "::error title=Test-run attendance (issue #2082)::artifact root not found: $ARTIFACT_ROOT — an absent download is not a passing verify"
  exit 1
fi

mapfile -t reports < <(find "$ARTIFACT_ROOT" -type f -name 'phase1-attendance.tsv' | LC_ALL=C sort)
echo "found ${#reports[@]} phase1 attendance report(s); expected $EXPECTED_SHARDS shard(s)"
if [[ "${#reports[@]}" -lt "$EXPECTED_SHARDS" ]]; then
  echo "::error title=Test-run attendance (issue #2082)::expected $EXPECTED_SHARDS shard attendance reports, found ${#reports[@]} — a missing shard artifact is not a completed run"
  printf '  %s\n' "${reports[@]:-}"
  exit 1
fi

merge_dir="$(mktemp -d)"
trap 'rm -rf "$merge_dir"' EXIT
i=0
for r in "${reports[@]}"; do
  cp "$r" "$merge_dir/shard-$i.tsv"
  i=$((i + 1))
done

mkdir -p "$(dirname "$LEDGER")"
OUT="${OUT:-$ROOT/build/nightly-execution-attendance.tsv}"

ident=()
ident+=(--identity "workflow=${POCKETSHELL_LEDGER_IDENTITY_WORKFLOW:-${GITHUB_WORKFLOW:-Nightly Extensive Tests}}")
ident+=(--identity "run_id=${POCKETSHELL_LEDGER_IDENTITY_RUN:-${GITHUB_RUN_ID:-local}}")
ident+=(--identity "sha=${POCKETSHELL_LEDGER_IDENTITY_SHA:-${GITHUB_SHA:-unknown}}")
ident+=(--identity "job=execution-ledger")
ident+=(--identity "selector=nightly-phase1-app-androidTest-minus-notClass")
ident+=(--identity "expected_shards=$EXPECTED_SHARDS")

bash "$GUARD" --merge-attendance "$merge_dir" \
  --selected-from nightly-phase1 \
  --require-class "$PIN_COLD" \
  --require-class "$PIN_WORKFLOW" \
  --out "$OUT" \
  "${ident[@]}"

# Record every shard's preserved JUnit XML into the rolling ledger (all
# phases, not just phase 1 — otherwise network-fault / bootstrap classes
# would look never-executed). Do NOT --verify the 7-day all-androidTest
# window here: release-only classes (LongRunning*) are not in this run.
xml_root="$(mktemp -d)"
trap 'rm -rf "$merge_dir" "$xml_root"' EXIT
copied=0
while IFS= read -r -d '' xml; do
  cp "$xml" "$xml_root/$(printf '%s' "$xml" | tr '/ ' '__')"
  copied=$((copied + 1))
done < <(find "$ARTIFACT_ROOT" -path '*phase-reports*' -type f -name '*.xml' -print0 2>/dev/null)

if [[ "$copied" -eq 0 ]]; then
  echo "::error title=Test-execution ledger (issue #2082)::no phase JUnit XML under $ARTIFACT_ROOT — refusing to record"
  exit 1
fi
echo "recording $copied phase JUnit XML file(s) into $LEDGER"
bash "$GUARD" --record "$xml_root" --ledger "$LEDGER" --tier nightly
