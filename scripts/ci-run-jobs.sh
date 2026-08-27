#!/usr/bin/env bash
# scripts/ci-run-jobs.sh — issue #2353
#
# Reads a completed GitHub Actions run's per-job conclusions by NAME, for the
# `.github/workflows/full-suite-notify.yml` `workflow_run` notify job: it does
# not have `needs:` access into `tests.yml`'s jobs (different workflow), so it
# re-derives "did the required top-level job fail" from the GitHub API instead.
#
# USAGE
#   ci-run-jobs.sh --repo OWNER/NAME --run-id ID [--gh PATH]
#
# OUTPUT (stdout, KEY=VALUE, one per line — always all four keys, "unknown"
# when a job is missing from the run, e.g. it never started):
#   UNIT_GATE=<conclusion>
#   PYTHON=<conclusion>
#   INTEGRATION=<conclusion>
#   EMULATOR_JOURNEY_VERDICT=<conclusion>
#
# If $GITHUB_OUTPUT is set, the same four keys (lowercased) are ALSO appended
# there for the workflow step to consume directly.
#
# Self-test: scripts/test-ci-run-jobs.sh

set -uo pipefail

REPO=""
RUN_ID=""
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$REPO" || -z "$RUN_ID" ]]; then
  echo "usage: $0 --repo OWNER/NAME --run-id ID [--gh PATH]" >&2
  exit 2
fi

jobs_json="{}"
if command -v "$GH_BIN" >/dev/null 2>&1; then
  jobs_json="$("$GH_BIN" api "repos/$REPO/actions/runs/$RUN_ID/jobs?per_page=100" 2>/dev/null)" || jobs_json="{}"
fi

job_conclusion() {
  local name="$1"
  local out
  out="$(printf '%s' "$jobs_json" | jq -r --arg n "$name" \
    '(.jobs // []) | map(select(.name == $n)) | .[0].conclusion // empty' 2>/dev/null)" || out=""
  [[ -n "$out" ]] && printf '%s' "$out" || printf 'unknown'
}

unit_gate="$(job_conclusion "Unit tests")"
python_r="$(job_conclusion "Python utility tests (pocketshell)")"
integration_r="$(job_conclusion "Integration tests (Docker)")"
journey_r="$(job_conclusion "Emulator journey aggregate verdict (#1458)")"

printf 'UNIT_GATE=%s\n' "$unit_gate"
printf 'PYTHON=%s\n' "$python_r"
printf 'INTEGRATION=%s\n' "$integration_r"
printf 'EMULATOR_JOURNEY_VERDICT=%s\n' "$journey_r"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    printf 'unit_gate=%s\n' "$unit_gate"
    printf 'python=%s\n' "$python_r"
    printf 'integration=%s\n' "$integration_r"
    printf 'emulator_journey_verdict=%s\n' "$journey_r"
  } >> "$GITHUB_OUTPUT"
fi
