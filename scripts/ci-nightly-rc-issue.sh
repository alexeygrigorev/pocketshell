#!/usr/bin/env bash
# scripts/ci-nightly-rc-issue.sh — issue #2356 (Phase 4 of epic #2350)
#
# On TWO CONSECUTIVE infra failures of the nightly Release Emulator
# Validation run (see scripts/ci-nightly-rc-consecutive-check.sh — a single
# flaky blip does not trigger this), auto-files (or updates) ONE tracking
# issue. Mirrors the pattern scripts/ci-red-issue.sh already established for
# the scheduled-full-suite red-run tracker (issue #2353): a stable marker
# token in the body finds and comments on an existing open issue instead of
# spamming a new one every night.
#
# Run from inside the workflow's own checkout (a stable marker search is
# enough here; no commit-window computation is needed since this tracks
# release-validation infra health, not a `main` regression).
#
# USAGE
#   ci-nightly-rc-issue.sh --repo OWNER/NAME --run-url URL --sha SHA [--gh PATH]
#
# Self-test: scripts/test-ci-nightly-rc-issue.sh
#
# Exits non-zero (and says why on stderr) on any `gh` failure — this must
# ITSELF be loud on failure, not swallowed.

set -uo pipefail

TITLE="CI: nightly Release Emulator Validation red twice in a row (issue #2356)"
MARKER="pocketshell-nightly-rc-red-marker"

REPO=""
RUN_URL=""
SHA=""
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --run-url) RUN_URL="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$REPO" || -z "$RUN_URL" || -z "$SHA" ]]; then
  echo "usage: $0 --repo OWNER/NAME --run-url URL --sha SHA [--gh PATH]" >&2
  exit 2
fi

if ! command -v "$GH_BIN" >/dev/null 2>&1; then
  echo "gh CLI not found ($GH_BIN) — cannot file/update the nightly-RC red tracking issue" >&2
  exit 1
fi

body="$(cat <<BODY
This is the standing tracking issue for TWO CONSECUTIVE red nightly
**Release Emulator Validation** runs (\`.github/workflows/release-emulator-validation.yml\`,
triggered on a green Phase-1 scheduled full-suite run — issue #2356 / epic
#2350). A single flaky blip does not open this issue; two failures in a row
do. Repeated streaks comment here instead of filing a new issue each time.

Marker: $MARKER

## Latest failing streak

- Latest run: $RUN_URL
- Commit under validation: \`$SHA\`

This means \`main\` currently has NO validated-RC candidate newer than the
last successful nightly run (\`git show validated-rc --quiet\` still points at
the last one that passed). Investigate whether this is release-validation
infra rot (the exact failure mode issue #2350's plan called out: "Release
Emulator Validation only runs at release time -> its own infra rot ...
discovered on release day") or a real regression the tier-3 full suite
missed. Re-run the workflow manually once diagnosed; this issue self-closes
by convention once a nightly run goes green again (close it manually after
confirming).
BODY
)"

existing=""
existing="$("$GH_BIN" issue list --repo "$REPO" --state open \
  --search "$MARKER in:body" --json number --limit 5 --jq '.[0].number // empty' \
  2>/dev/null)" || existing=""

if [[ -n "$existing" ]]; then
  if ! "$GH_BIN" issue comment "$existing" --repo "$REPO" --body "$body"; then
    echo "failed to comment on existing nightly-RC red tracking issue #$existing" >&2
    exit 1
  fi
  echo "Commented on existing tracking issue #$existing"
else
  created=""
  if ! created="$("$GH_BIN" issue create --repo "$REPO" --title "$TITLE" --body "$body")"; then
    echo "failed to create the nightly-RC red tracking issue" >&2
    exit 1
  fi
  echo "Created tracking issue: $created"
fi
