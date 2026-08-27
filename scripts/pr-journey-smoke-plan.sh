#!/usr/bin/env bash
# scripts/pr-journey-smoke-plan.sh — issue #2354 (Phase 2 of #2350)
#
# Decides whether a PR needs the per-PR journey SMOKE job
# (.github/workflows/pr-journey-smoke.yml): a single, fast, emulator run of a
# small FIXED set of the most load-bearing journey classes (the #2338 class:
# 2nd-launch terminal attach; the A->B->A session-switch class; the
# composer-always-present class), so a regression like #2338 (terminal-attach
# broken on the 2nd MainActivity launch, which took out ~15 journey classes on
# `main` for days before the batched post-merge run caught it) is caught on
# the PR itself, not hours later on `main`.
#
# This wraps scripts/select-test-areas.sh (#2063/#2353) EXACTLY the way
# scripts/ci-plan.sh does for the push-scoped plan, but answers a different
# question: not "which journey classes to run" but "should the smoke job run
# AT ALL". The smoke job always runs the SAME fixed small class list
# (SMOKE_CLASSES below) when it runs at all — the issue's own ≤20 min budget
# requires a constant, small class count regardless of how many areas a diff
# touches, so this script does not try to grow/shrink the class list per area
# the way the push-scoped plan grows/shrinks JOURNEY_CLASSES.
#
# GATING RULE
#   SHOULD_RUN=true when EITHER:
#     * select-test-areas.sh returns MODE=full for this diff (force-full /
#       unmapped path / unreadable diff — the fail-safe: never filter a run
#       the taxonomy could not classify), OR
#     * the diff's CHANGED_AREAS (the actually-changed areas, NOT the
#       always-tier closure — see select-test-areas.sh's own
#       expand_seeds/expand distinction) intersects the fixed LOAD_BEARING
#       set below.
#   SHOULD_RUN=false otherwise — e.g. a docs-only, Python-CLI-only, or
#   hosts-settings-only PR, which cannot regress #2338's class.
#
# LOAD_BEARING_AREAS is deliberately a small, named, load-bearing subset of
# scripts/test-areas.txt's areas — connection-core, terminal-render,
# tmux-session, composer-voice — matching the issue's "connection/terminal/
# session/composer" scope. It is NOT "every area that could theoretically
# touch a journey class"; that is what the push-scoped plan (#2353) is for.
#
# USAGE
#   pr-journey-smoke-plan.sh --base SHA
#     --base SHA   git ref/sha to diff against (PR base sha). Optional —
#                  falls back to select-test-areas.sh's own default
#                  (merge-base origin/main HEAD), its documented fail-safe.
#
# Self-test: scripts/test-pr-journey-smoke-plan.sh
#
# OUTPUT (stdout, KEY=VALUE, one per line):
#   SHOULD_RUN=true|false
#   REASON=<free text>
#   MODE=<full|scoped>              (select-test-areas.sh's own decision)
#   CHANGED_AREAS=<space-separated, may be empty>
#   SMOKE_CLASSES=<comma-separated FQCNs — present even when SHOULD_RUN=false>
#
# If $GITHUB_OUTPUT is set, the same keys are ALSO appended there, lower-case
# (should_run, reason, mode, changed_areas, smoke_classes), for the workflow
# step's `if:`/env: consumption.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELECT_TEST_AREAS="${POCKETSHELL_SELECT_TEST_AREAS:-$SCRIPT_DIR/select-test-areas.sh}"

# Fixed, small, load-bearing journey class list (#2338, #710/#638, #810). Kept
# a hand-picked constant on purpose: growing it per-area would make the smoke
# job's wall-clock diff-dependent, defeating the ≤20 min budget target.
SMOKE_CLASSES="com.pocketshell.app.proof.Issue2338SecondLaunchTerminalAttachJourneyE2eTest,com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest,com.pocketshell.app.proof.ComposerAlwaysPresentSwitchJourneyE2eTest"

LOAD_BEARING_AREAS="connection-core terminal-render tmux-session composer-voice"

BASE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

base_args=()
[[ -n "$BASE" ]] && base_args=(--base "$BASE")

if ! plan_out="$("$SELECT_TEST_AREAS" "${base_args[@]}" --print-plan-only)"; then
  # select-test-areas.sh itself failing is the same fail-safe posture as its
  # own manifest-load-failure path: never silently skip the smoke job.
  printf 'SHOULD_RUN=true\n'
  printf 'REASON=select-test-areas.sh exited non-zero — failing open, running the smoke job\n'
  printf 'MODE=full\n'
  printf 'CHANGED_AREAS=\n'
  printf 'SMOKE_CLASSES=%s\n' "$SMOKE_CLASSES"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
      printf 'should_run=true\n'
      printf 'reason=select-test-areas.sh exited non-zero -- failing open, running the smoke job\n'
      printf 'mode=full\n'
      printf 'changed_areas=\n'
      printf 'smoke_classes=%s\n' "$SMOKE_CLASSES"
    } >> "$GITHUB_OUTPUT"
  fi
  exit 0
fi

mode="$(printf '%s\n' "$plan_out" | grep '^MODE=' | tail -1 | cut -d= -f2-)"
changed_areas="$(printf '%s\n' "$plan_out" | grep '^CHANGED_AREAS=' | tail -1 | cut -d= -f2-)"

should_run="false"
reason=""

if [[ "$mode" == "full" ]]; then
  should_run="true"
  reason="select-test-areas.sh returned MODE=full for this diff (force-full / unmapped path / unreadable diff) -- the smoke job must run"
else
  matched=""
  for area in $changed_areas; do
    for lb in $LOAD_BEARING_AREAS; do
      if [[ "$area" == "$lb" ]]; then
        matched="$area"
        break 2
      fi
    done
  done
  if [[ -n "$matched" ]]; then
    should_run="true"
    reason="changed area '$matched' is load-bearing (one of: $LOAD_BEARING_AREAS)"
  else
    reason="no changed area intersects the load-bearing set ($LOAD_BEARING_AREAS); changed=${changed_areas:-<none>}"
  fi
fi

printf 'SHOULD_RUN=%s\n' "$should_run"
printf 'REASON=%s\n' "$reason"
printf 'MODE=%s\n' "$mode"
printf 'CHANGED_AREAS=%s\n' "$changed_areas"
printf 'SMOKE_CLASSES=%s\n' "$SMOKE_CLASSES"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    printf 'should_run=%s\n' "$should_run"
    printf 'reason=%s\n' "$reason"
    printf 'mode=%s\n' "$mode"
    printf 'changed_areas=%s\n' "$changed_areas"
    printf 'smoke_classes=%s\n' "$SMOKE_CLASSES"
  } >> "$GITHUB_OUTPUT"
fi
