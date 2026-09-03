#!/usr/bin/env bash
# scripts/ci-nightly-extensive-red-issue.sh — issue #2459
#
# On a RED `Nightly Extensive Tests` run (.github/workflows/nightly-
# extensive.yml `schedule:` trigger), file (or update) ONE tracking issue
# with the failure signature and the bounded list of `main` commits since the
# last known-green nightly run. Mirrors scripts/ci-red-issue.sh's pattern
# (issue #2353) for the SCHEDULED `Tests` workflow's own red-run tracker —
# same marker-search-then-comment-or-create shape, same "loud on gh failure"
# discipline — but this is the workflow #2459's Scope explicitly calls out as
# missing an equivalent notifier for ("add the equivalent for Nightly
# Extensive Tests if it doesn't already have a red-run notifier").
#
# Unlike ci-red-issue.sh, this does not hardcode a fixed 4-job summary
# (`Unit tests` / `Python` / `Integration` / `Emulator journey verdict`) —
# nightly-extensive.yml's job names are different and its shard count is not
# fixed at 4 keys, so the failed-job summary is a single pre-joined
# `--failed-jobs` string the caller derives from
# scripts/ci-retry-signature.py's `job:` lines instead.
#
# Run from inside the workflow's own checkout (needs full history —
# `fetch-depth: 0` — to resolve `--last-green-sha..--sha`).
#
# USAGE
#   ci-nightly-extensive-red-issue.sh
#     --repo OWNER/NAME --run-url URL --sha SHA
#     [--last-green-sha SHA] [--failed-jobs "name1; name2; ..."]
#     [--gh PATH]
#
# Self-test: scripts/test-ci-nightly-extensive-red-issue.sh
#
# Exits non-zero (and says why on stderr) on any `gh` failure — a red run
# that fails to notify must ITSELF be loud, not swallowed.

set -uo pipefail

TITLE="CI: scheduled Nightly Extensive Tests is red (issue #2459)"
MARKER="pocketshell-nightly-extensive-red-marker"

REPO=""
RUN_URL=""
SHA=""
LAST_GREEN_SHA=""
FAILED_JOBS=""
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --run-url) RUN_URL="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --last-green-sha) LAST_GREEN_SHA="$2"; shift 2 ;;
    --failed-jobs) FAILED_JOBS="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$REPO" || -z "$RUN_URL" || -z "$SHA" ]]; then
  echo "usage: $0 --repo OWNER/NAME --run-url URL --sha SHA [--last-green-sha SHA] [--failed-jobs STR] [--gh PATH]" >&2
  exit 2
fi

if ! command -v "$GH_BIN" >/dev/null 2>&1; then
  echo "gh CLI not found ($GH_BIN) — cannot file/update the red-run tracking issue" >&2
  exit 1
fi

commits_section="commit list unavailable (no prior known-green nightly run, or history was not fetched)"
if [[ -n "$LAST_GREEN_SHA" ]] && git cat-file -e "${LAST_GREEN_SHA}^{commit}" 2>/dev/null; then
  commits_section="$(git log "${LAST_GREEN_SHA}..${SHA}" --oneline 2>/dev/null)"
  [[ -n "$commits_section" ]] || commits_section="(no commits between last-green and HEAD)"
fi

failure_summary="$FAILED_JOBS"
[[ -n "$failure_summary" ]] || failure_summary="(no failed job names supplied; check the run directly)"

last_green_line="(no prior known-green nightly run recorded)"
[[ -n "$LAST_GREEN_SHA" ]] && last_green_line="$LAST_GREEN_SHA"

body="$(cat <<BODY
This is the standing tracking issue for a red **scheduled Nightly Extensive
Tests** run (\`.github/workflows/nightly-extensive.yml\` \`schedule:\`
trigger — issue #2459). Repeated red runs comment here instead of filing a
new issue each cycle.

Marker: $MARKER

## Latest red run

- Run: $RUN_URL
- Commit: \`$SHA\`
- Failed job(s): $failure_summary
- Last known-green nightly run: \`$last_green_line\`

## Bounded merge window (commits since the last known-green nightly run)

\`\`\`
$commits_section
\`\`\`

A bounded one-shot retry is automatically triggered for this run; its result
will be posted as a follow-up comment on this issue (issue #2459).
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
