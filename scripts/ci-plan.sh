#!/usr/bin/env bash
# scripts/ci-plan.sh — issue #2353
#
# Wraps scripts/select-test-areas.sh for the `sgate` workflow job in
# .github/workflows/tests.yml: picks the diff base for a `main`-push run,
# runs the plan with --github-output (MODE/AREAS/JOURNEY_CLASSES/etc land in
# $GITHUB_OUTPUT), echoes the human-readable plan to stdout AND appends it to
# $GITHUB_STEP_SUMMARY when set (so the caller does not need its own
# `| tee -a` — one fewer thing for the size-ratcheted workflow to spell out),
# then derives ONE extra output — SCOPED_CLASSES — that is the journey-class
# list ONLY when MODE=scoped, and EMPTY when MODE=full.
# This is the safety-critical gate that keeps a force-full push from ever
# having its journey classes silently filtered: computing it HERE (unit-
# testable bash) rather than as a `mode == 'scoped' && ... || ''` conditional
# inline in the workflow YAML means the "never filter on full" property has
# its own test coverage instead of living only in an unexercised expression.
#
# The base is the PREVIOUS `main` push (github.event.before) — the exact set
# of commits THIS push introduces. A missing/unreadable `before` (first push
# to a new ref, a force-push, or a shallow checkout that doesn't have it) falls
# back to select-test-areas.sh's own default base (merge-base origin/main) —
# its documented fail-safe: an unreadable diff or empty path set both latch
# MODE=full, never a silent under-selection.
#
# USAGE
#   ci-plan.sh --before SHA
#
# Self-test: scripts/test-ci-plan.sh

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BEFORE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --before) BEFORE="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

ZERO="0000000000000000000000000000000000000000"
base_args=()
if [[ -n "$BEFORE" && "$BEFORE" != "$ZERO" ]] && git cat-file -e "${BEFORE}^{commit}" 2>/dev/null; then
  base_args=(--base "$BEFORE")
fi

plan_out="$("$SCRIPT_DIR/select-test-areas.sh" "${base_args[@]}" --github-output)"
printf '%s\n' "$plan_out"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  printf '%s\n' "$plan_out" >> "$GITHUB_STEP_SUMMARY"
fi

if [[ -n "${GITHUB_OUTPUT:-}" && -f "${GITHUB_OUTPUT:-}" ]]; then
  mode="$(grep '^MODE=' "$GITHUB_OUTPUT" | tail -1 | cut -d= -f2-)"
  classes="$(grep '^JOURNEY_CLASSES=' "$GITHUB_OUTPUT" | tail -1 | cut -d= -f2-)"
  scoped=""
  [[ "$mode" == "scoped" ]] && scoped="$classes"
  printf 'SCOPED_CLASSES=%s\n' "$scoped" >> "$GITHUB_OUTPUT"
fi
