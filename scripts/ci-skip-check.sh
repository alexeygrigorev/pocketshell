#!/usr/bin/env bash
# scripts/ci-skip-check.sh — issue #2353
#
# Decides whether the ~8h scheduled full-suite run (.github/workflows/tests.yml
# `schedule:` trigger) should actually pay for the full sharded emulator-journey
# + Docker integration + both unit variants + guards, or SKIP because `main`
# HEAD has not moved since the last GREEN scheduled run. Avoids spend when
# nothing landed between cadence ticks; never a correctness gate.
#
# FAIL-OPEN, ALWAYS. If `gh` is missing, unauthenticated, the API call fails,
# the response is unparsable, or no prior successful scheduled run exists, this
# prints SKIP=false — the safe default is to spend the ~2-3h run, never to
# silently stop validating `main`. Only an EXACT match between HEAD and the
# last green scheduled run's head_sha produces SKIP=true.
#
# USAGE
#   ci-skip-check.sh --repo OWNER/NAME --sha SHA
#     [--workflow-file NAME]     default: tests.yml
#     [--gh PATH]                default: gh
#     [--event NAME]             default: schedule (which runs to compare against)
#
# Self-test: scripts/test-ci-skip-check.sh
#
# OUTPUT (stdout, KEY=VALUE, one per line — always all three keys):
#   SKIP=true|false
#   REASON=<free text>
#   LAST_GREEN_SHA=<40-hex sha, or empty>
#
# If $GITHUB_OUTPUT is set (as it is inside a GitHub Actions step), the same
# decision is ALSO appended there as `should_run` (the inverse of SKIP — what
# the workflow's job `if:` consumes) and `last_green_sha`, so a single
# invocation serves both the human log and the workflow step outputs.

set -uo pipefail

REPO=""
SHA=""
WORKFLOW_FILE="tests.yml"
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"
EVENT="schedule"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --workflow-file) WORKFLOW_FILE="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    --event) EVENT="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

emit() {
  local skip="$1" reason="$2" last_green="$3"
  printf 'SKIP=%s\n' "$skip"
  printf 'REASON=%s\n' "$reason"
  printf 'LAST_GREEN_SHA=%s\n' "$last_green"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    local should_run="true"
    [[ "$skip" == "true" ]] && should_run="false"
    {
      printf 'should_run=%s\n' "$should_run"
      printf 'last_green_sha=%s\n' "$last_green"
    } >> "$GITHUB_OUTPUT"
  fi
}

if [[ -z "$REPO" || -z "$SHA" ]]; then
  echo "usage: $0 --repo OWNER/NAME --sha SHA [--workflow-file NAME] [--gh PATH] [--event NAME]" >&2
  exit 2
fi

if ! command -v "$GH_BIN" >/dev/null 2>&1; then
  emit false "gh CLI not found ($GH_BIN) — failing open" ""
  exit 0
fi

api_out=""
if ! api_out="$("$GH_BIN" api \
    "repos/$REPO/actions/workflows/$WORKFLOW_FILE/runs?event=$EVENT&status=success&per_page=10" \
    2>/dev/null)"; then
  emit false "gh api call failed — failing open" ""
  exit 0
fi

last_green=""
last_green="$(printf '%s' "$api_out" | jq -r \
  '(.workflow_runs // []) | sort_by(.created_at) | reverse | .[0].head_sha // empty' \
  2>/dev/null)" || last_green=""

if [[ -z "$last_green" ]]; then
  emit false "no prior successful '$EVENT' run of $WORKFLOW_FILE found — failing open" ""
  exit 0
fi

if [[ "$last_green" == "$SHA" ]]; then
  emit true "HEAD ($SHA) matches the last green '$EVENT' run's head_sha — nothing new to validate" "$last_green"
  exit 0
fi

emit false "HEAD ($SHA) differs from the last green '$EVENT' run's head_sha ($last_green)" "$last_green"
exit 0
