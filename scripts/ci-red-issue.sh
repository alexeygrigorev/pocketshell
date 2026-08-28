#!/usr/bin/env bash
# scripts/ci-red-issue.sh — issue #2353
#
# On a RED scheduled full-suite run (.github/workflows/tests.yml `schedule:`
# trigger), file (or update) ONE tracking issue with the failure signature and
# the bounded list of `main` commits since the last known-green full run — the
# "bisect window" that let v0.4.45's 11-day red `main` (#2338/#2294) take days
# to attribute instead of hours. Repeated red runs COMMENT on the same issue
# (matched by a stable marker token in the body) rather than spamming a new
# issue every ~8h.
#
# Run from inside the workflow's own checkout (needs full history —
# `fetch-depth: 0` — to resolve `--last-green-sha..--sha`).
#
# USAGE
#   ci-red-issue.sh
#     --repo OWNER/NAME --run-url URL --sha SHA
#     [--last-green-sha SHA]
#     [--unit-gate RESULT] [--python RESULT] [--integration RESULT]
#     [--emulator-journey-verdict RESULT]
#     [--gh PATH]
#   (each RESULT is a `needs.<job>.result` value; only "failure" counts as
#   red — "skipped"/"success"/"cancelled" are all silently ignored.)
#
# Self-test: scripts/test-ci-red-issue.sh
#
# Exits non-zero (and says why on stderr) on any `gh` failure — a red run that
# fails to notify must ITSELF be loud, not swallowed.

set -uo pipefail

TITLE="CI: scheduled full-suite is red (issue #2353)"
MARKER="pocketshell-full-suite-red-marker"

REPO=""
RUN_URL=""
SHA=""
LAST_GREEN_SHA=""
UNIT_GATE=""
PYTHON_R=""
INTEGRATION_R=""
JOURNEY_R=""
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --run-url) RUN_URL="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --last-green-sha) LAST_GREEN_SHA="$2"; shift 2 ;;
    --unit-gate) UNIT_GATE="$2"; shift 2 ;;
    --python) PYTHON_R="$2"; shift 2 ;;
    --integration) INTEGRATION_R="$2"; shift 2 ;;
    --emulator-journey-verdict) JOURNEY_R="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$REPO" || -z "$RUN_URL" || -z "$SHA" ]]; then
  echo "usage: $0 --repo OWNER/NAME --run-url URL --sha SHA [--last-green-sha SHA] [--unit-gate R] [--python R] [--integration R] [--emulator-journey-verdict R] [--gh PATH]" >&2
  exit 2
fi

if ! command -v "$GH_BIN" >/dev/null 2>&1; then
  echo "gh CLI not found ($GH_BIN) — cannot file/update the red-run tracking issue" >&2
  exit 1
fi

commits_section="commit list unavailable (no prior known-green scheduled run, or history was not fetched)"
if [[ -n "$LAST_GREEN_SHA" ]] && git cat-file -e "${LAST_GREEN_SHA}^{commit}" 2>/dev/null; then
  commits_section="$(git log "${LAST_GREEN_SHA}..${SHA}" --oneline 2>/dev/null)"
  [[ -n "$commits_section" ]] || commits_section="(no commits between last-green and HEAD)"
fi

declare -a failed=()
[[ "$UNIT_GATE" == "failure" ]] && failed+=("Unit tests")
[[ "$PYTHON_R" == "failure" ]] && failed+=("Python utility tests (pocketshell)")
[[ "$INTEGRATION_R" == "failure" ]] && failed+=("Integration tests (Docker)")
[[ "$JOURNEY_R" == "failure" ]] && failed+=("Emulator journey aggregate verdict (one or more of the matrix shards; see the run for which)")
failure_summary="$(printf '%s; ' "${failed[@]:-}")"
failure_summary="${failure_summary%; }"
[[ -n "$failure_summary" ]] || failure_summary="(no job reported failure=true; check the run directly)"

last_green_line="(no prior known-green scheduled run recorded)"
[[ -n "$LAST_GREEN_SHA" ]] && last_green_line="$LAST_GREEN_SHA"

body="$(cat <<BODY
This is the standing tracking issue for a red **scheduled full-suite** run
(\`.github/workflows/tests.yml\` \`schedule:\` trigger — issue #2353 / epic #2350).
Repeated red runs comment here instead of filing a new issue each cycle.

Marker: $MARKER

## Latest red run

- Run: $RUN_URL
- Commit: \`$SHA\`
- Failure signature: $failure_summary
- Last known-green full run: \`$last_green_line\`

## Bounded merge window (commits since the last known-green full run)

\`\`\`
$commits_section
\`\`\`

Bisect the regression to one of the commits above — that is the whole point of
the ~8h cadence (the merge window a red run must be attributed within).
BODY
)"

existing=""
existing="$("$GH_BIN" issue list --repo "$REPO" --state open \
  --search "$MARKER in:body" --json number --limit 5 --jq '.[0].number // empty' \
  2>/dev/null)" || existing=""

if [[ -n "$existing" ]]; then
  if ! "$GH_BIN" issue comment "$existing" --repo "$REPO" --body "$body"; then
    echo "failed to comment on existing red-run tracking issue #$existing" >&2
    exit 1
  fi
  echo "Commented on existing tracking issue #$existing"
else
  created=""
  if ! created="$("$GH_BIN" issue create --repo "$REPO" --title "$TITLE" --body "$body")"; then
    echo "failed to create the red-run tracking issue" >&2
    exit 1
  fi
  echo "Created tracking issue: $created"
fi
