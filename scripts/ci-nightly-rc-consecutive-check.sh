#!/usr/bin/env bash
# scripts/ci-nightly-rc-consecutive-check.sh — issue #2356 (Phase 4 of #2350)
#
# Decides whether the CURRENT nightly Release Emulator Validation run (the
# one that just failed — the caller only invokes this from a job gated on
# `needs.<job>.result == 'failure'`) is the SECOND CONSECUTIVE infra failure
# of that same nightly trigger — not "two failures ever", specifically two
# IN A ROW, so a single flaky blip does not file a tracking issue. Only runs
# TRIGGERED BY `workflow_run` count towards the streak (a manual
# workflow_dispatch run failing does not perturb or reset it — it is a
# different signal the maintainer explicitly asked for).
#
# The CURRENT run's own failure is asserted by the CALLER (the workflow only
# invokes this script from a job whose `if:` already requires the current
# run to have failed) — this script's job is solely to look up whether the
# run immediately BEFORE it also failed. It explicitly EXCLUDES the current
# run id from the API query (--exclude-run-id) rather than trusting the API
# to already list the current run's final conclusion, because this job runs
# WHILE the overall workflow run is still in progress (other jobs may still
# be executing), so the Actions API may not yet reflect this run's own
# outcome — querying "the 2 most recent runs" without excluding the current
# one would silently look at the wrong pair.
#
# FAIL-OPEN in the sense that a `gh`/API problem never SILENTLY drops a real
# two-in-a-row streak: on any lookup failure this reports CONSECUTIVE=false
# (does not file), matching the existing project convention
# (scripts/ci-skip-check.sh) that a broken query must never masquerade as a
# stronger signal than it can actually support.
#
# USAGE
#   ci-nightly-rc-consecutive-check.sh --repo OWNER/NAME --exclude-run-id ID
#     [--workflow-file NAME]  default: release-emulator-validation.yml
#     [--gh PATH]             default: gh
#
# Self-test: scripts/test-ci-nightly-rc-consecutive-check.sh
#
# OUTPUT (stdout, KEY=VALUE, one per line):
#   CONSECUTIVE=true|false
#   REASON=<free text>
#
# If $GITHUB_OUTPUT is set, also appends `consecutive_failures` there.

set -uo pipefail

REPO=""
WORKFLOW_FILE="release-emulator-validation.yml"
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"
EXCLUDE_RUN_ID=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --workflow-file) WORKFLOW_FILE="$2"; shift 2 ;;
    --exclude-run-id) EXCLUDE_RUN_ID="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,34p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

emit() {
  local consecutive="$1" reason="$2"
  printf 'CONSECUTIVE=%s\n' "$consecutive"
  printf 'REASON=%s\n' "$reason"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'consecutive_failures=%s\n' "$consecutive" >> "$GITHUB_OUTPUT"
  fi
}

if [[ -z "$REPO" || -z "$EXCLUDE_RUN_ID" ]]; then
  echo "usage: $0 --repo OWNER/NAME --exclude-run-id ID [--workflow-file NAME] [--gh PATH]" >&2
  exit 2
fi

if ! command -v "$GH_BIN" >/dev/null 2>&1; then
  emit false "gh CLI not found ($GH_BIN) — failing open (no file)"
  exit 0
fi

api_out=""
if ! api_out="$("$GH_BIN" api \
    "repos/$REPO/actions/workflows/$WORKFLOW_FILE/runs?event=workflow_run&per_page=10" \
    2>/dev/null)"; then
  emit false "gh api call failed — failing open (no file)"
  exit 0
fi

# The most recent workflow_run-triggered run OTHER than the current
# (excluded by id) run — the "run immediately before this one" whose
# conclusion, combined with the current known failure, would make a
# two-in-a-row streak.
prior="$(printf '%s' "$api_out" | jq -r --argjson exclude "$EXCLUDE_RUN_ID" \
  '(.workflow_runs // []) | map(select(.id != $exclude)) | sort_by(.created_at) | reverse | .[0].conclusion // empty' \
  2>/dev/null)" || prior=""

if [[ -z "$prior" ]]; then
  emit false "no prior workflow_run-triggered run found (excluding the current run $EXCLUDE_RUN_ID) — failing open (no file)"
  exit 0
fi

if [[ "$prior" == "failure" ]]; then
  emit true "the current run failed and the prior nightly run also concluded 'failure'"
  exit 0
fi

emit false "the current run failed but the prior nightly run concluded '$prior' — not two in a row"
exit 0
