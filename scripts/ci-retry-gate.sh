#!/usr/bin/env bash
# scripts/ci-retry-gate.sh — issue #2459
#
# Pure decision function for the bounded one-shot retry-and-compare mechanism
# that automates the G5 ("infra/flake requires a captured signature and a
# clean re-run") classification step for a red SCHEDULED `Tests` run or a red
# `Nightly Extensive Tests` run.
#
# THE ANTI-INFINITE-LOOP MECHANISM
#
# `gh run rerun <run-id>` reruns the SAME workflow run — it does not create a
# new run id, it increments that run's `run_attempt` (1 -> 2 -> ...). The
# `workflow_run` `completed` event that fires when the rerun finishes carries
# the SAME `github.event.workflow_run.id` and the NEW `run_attempt`. That
# makes "is this event itself the retry's own completion" a mechanical,
# stateless check: retries are only ever allowed to start from
# `run_attempt == 1`. Once this script is asked to decide for
# `run_attempt >= 2` it ALWAYS returns `RETRY_ALLOWED=false` — regardless of
# whether that attempt is red or green — so a retry's own result can never
# trigger a third run. This is a total function of `run_attempt` alone: no
# external state (an issue comment, a file, a label) has to be read
# correctly for the bound to hold, so there is no failure mode where a
# corrupted/missing marker re-opens the loop.
#
# USAGE
#   ci-retry-gate.sh --run-attempt N --conclusion success|failure
#
# OUTPUT (stdout, KEY=VALUE, one per line — always all three keys):
#   PHASE=first_red | retry_red | retry_clean | noop
#   RETRY_ALLOWED=true|false
#   REASON=<free text>
#
# PHASE meanings (the caller workflow branches on this):
#   first_red   attempt 1 concluded failure — this is a genuinely new red run.
#               File/update the tracking issue, capture this run's failure
#               signature, and trigger exactly one retry.
#   retry_red   attempt >= 2 concluded failure — this IS the bounded retry,
#               and it reproduced red. Compare its signature against the
#               stored attempt-1 signature and classify (never retry again).
#   retry_clean attempt >= 2 concluded success — the bounded retry came back
#               clean. This is the G5 "clean re-run" case: classify as infra,
#               do not retry again.
#   noop        attempt 1 concluded success (an ordinary green scheduled run)
#               or the attempt number could not be read at all. Nothing to
#               do. A malformed/missing `run_attempt` fails CLOSED to noop —
#               the safe default for a COSTLY, semi-irreversible action (an
#               extra full-suite rerun) is to skip it, never to guess and
#               retry anyway.
#
# If $GITHUB_OUTPUT is set, the same three keys (lowercased) are ALSO
# appended there for the workflow step to consume directly.
#
# Self-test: scripts/test-ci-retry-gate.sh

set -uo pipefail

RUN_ATTEMPT=""
CONCLUSION=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-attempt) RUN_ATTEMPT="$2"; shift 2 ;;
    --conclusion) CONCLUSION="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,45p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$CONCLUSION" ]]; then
  echo "usage: $0 --run-attempt N --conclusion success|failure" >&2
  exit 2
fi

emit() {
  local phase="$1" allowed="$2" reason="$3"
  printf 'PHASE=%s\n' "$phase"
  printf 'RETRY_ALLOWED=%s\n' "$allowed"
  printf 'REASON=%s\n' "$reason"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
      printf 'phase=%s\n' "$phase"
      printf 'retry_allowed=%s\n' "$allowed"
      printf 'reason=%s\n' "$reason"
    } >> "$GITHUB_OUTPUT"
  fi
}

# Fail closed to noop on a missing/non-numeric attempt: never retry on data
# we cannot trust to be attempt 1.
if ! [[ "$RUN_ATTEMPT" =~ ^[0-9]+$ ]] || [[ "$RUN_ATTEMPT" -lt 1 ]]; then
  emit noop false "run_attempt was missing or not a positive integer ('$RUN_ATTEMPT') — failing closed, no retry"
  exit 0
fi

if [[ "$RUN_ATTEMPT" -eq 1 ]]; then
  if [[ "$CONCLUSION" == "failure" ]]; then
    emit first_red true "attempt 1 concluded failure — a genuinely new red run, trigger the one bounded retry"
  else
    emit noop false "attempt 1 concluded '$CONCLUSION' — an ordinary non-red scheduled run, nothing to do"
  fi
  exit 0
fi

# RUN_ATTEMPT >= 2: this event IS the bounded retry's own completion (or a
# later manual re-run of it). RETRY_ALLOWED is unconditionally false here —
# the bound holds regardless of this attempt's own conclusion.
if [[ "$CONCLUSION" == "failure" ]]; then
  emit retry_red false "attempt $RUN_ATTEMPT concluded failure — this IS the bounded retry; compare signatures and classify, never retry again"
else
  emit retry_clean false "attempt $RUN_ATTEMPT concluded '$CONCLUSION' — the bounded retry came back clean (G5 infra/flake), never retry again"
fi
