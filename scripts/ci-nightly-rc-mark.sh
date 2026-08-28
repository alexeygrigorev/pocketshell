#!/usr/bin/env bash
# scripts/ci-nightly-rc-mark.sh — issue #2356 (Phase 4 of epic #2350)
#
# Records the "validated RC" marker on a GREEN Release Emulator Validation
# run of the newest `main` SHA that has a green Phase-1 scheduled full-suite
# run (.github/workflows/tests.yml `schedule:` trigger — issue #2353). The
# marker is a FORCE-UPDATED, MOVING annotated git tag named `validated-rc`:
#
#   - Trivially queryable with one command: `git show validated-rc --quiet`
#     (or `git ls-remote --tags origin validated-rc` for the SHA alone).
#   - The tag message carries the SHA, the run URL, and an ISO-8601 UTC
#     timestamp, so all three required fields are in the ONE artifact.
#   - Unambiguous by construction: there is exactly one `validated-rc` ref,
#     always force-moved to the latest green run, so there is never a
#     stale-vs-fresh choice between multiple candidate markers — the
#     mechanism ci-red-issue.sh's sibling scripts already use for "the last
#     green run" (see scripts/ci-skip-check.sh) but as a durable ref instead
#     of a re-derived API query, so "the current validated RC" survives
#     workflow-run retention and is fetchable by ANY clone, not just one with
#     Actions API access.
#
# Usage:
#   ci-nightly-rc-mark.sh --sha SHA --run-url URL [--dry-run] [--remote NAME]
#
# Exits non-zero (and says why on stderr) on any git failure — recording the
# marker must itself be loud on failure, never silently skipped.

set -uo pipefail

SHA=""
RUN_URL=""
REMOTE="origin"
DRY_RUN=0

usage() {
  sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --run-url) RUN_URL="$2"; shift 2 ;;
    --remote) REMOTE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$SHA" || -z "$RUN_URL" ]]; then
  echo "usage: $0 --sha SHA --run-url URL [--dry-run] [--remote NAME]" >&2
  exit 2
fi

command -v git >/dev/null 2>&1 || { echo "git not found on PATH" >&2; exit 1; }

if ! git cat-file -e "${SHA}^{commit}" 2>/dev/null; then
  echo "SHA $SHA is not a known commit in this checkout (fetch it first)" >&2
  exit 1
fi

# Issue #2374: an ANNOTATED tag is a tag OBJECT, so git needs a tagger identity
# to write one. A GitHub hosted runner has none: actions/checkout does not set
# `user.name`/`user.email`, and git's auto-detection produces
# `runner@fv-az…-….(none)`, which it rejects outright:
#
#   fatal: unable to auto-detect email address (got 'runner@host.(none)')
#
# So `git tag -f -a validated-rc` below would have failed on EVERY nightly run,
# and #2356's marker could never have been created even once the scheduled
# full-suite went green. It has in fact never been created. Nothing caught it
# because the only exercise of this script is a self-test that ran on a
# developer machine, where an ambient `~/.gitconfig` identity always existed —
# and it was never wired into a CI job at all.
#
# Fail-safe and narrow: `git var GIT_COMMITTER_IDENT` is git's own answer to
# "can you form an identity right now?". When it succeeds we change NOTHING, so
# a local run still tags as the maintainer. Only when git says it cannot do we
# supply one, and we supply it through the COMMITTER env vars rather than
# `-c user.email`, because env beats config — `-c` would not override a caller
# whose identity is unusable because the env vars themselves are empty.
if ! git var GIT_COMMITTER_IDENT >/dev/null 2>&1; then
  echo "git has no usable committer identity; tagging as the CI bot (issue #2374)"
  export GIT_COMMITTER_NAME="pocketshell-ci"
  export GIT_COMMITTER_EMAIL="pocketshell-ci@users.noreply.github.com"
fi

TIMESTAMP="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

TAG_MESSAGE="$(
  cat <<EOF
PocketShell validated RC

SHA: $SHA
Run: $RUN_URL
Recorded: $TIMESTAMP

This is a MOVING marker (force-updated on every green Release Emulator
Validation run of the newest tier-3-fully-green main SHA — issue #2356). Do
not treat an older local copy of this tag as current; always \`git fetch
--tags --force\` before reading it.
EOF
)"

echo "Recording validated-rc marker: sha=$SHA run=$RUN_URL at $TIMESTAMP"

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "DRY RUN: would force-create annotated tag validated-rc at $SHA and force-push to $REMOTE"
  printf '%s\n' "$TAG_MESSAGE"
  exit 0
fi

# -f: this is a deliberately MOVING pointer, not a one-shot release tag.
if ! git tag -f -a "validated-rc" "$SHA" -m "$TAG_MESSAGE"; then
  echo "failed to create/update the local validated-rc tag" >&2
  exit 1
fi

if ! git push --force "$REMOTE" refs/tags/validated-rc; then
  echo "failed to push the validated-rc tag to $REMOTE" >&2
  exit 1
fi

echo "OK: validated-rc now points at $SHA"
