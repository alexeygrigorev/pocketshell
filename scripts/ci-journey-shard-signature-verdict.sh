#!/usr/bin/env bash
# Issue #1800: decide whether an emulator-journey shard's failure evidence is
# the ONE captured environment signature (the CI swiftshader AVD cannot raise a
# real system input-method window) rather than a genuine journey failure.
#
# This is the exact decision the workflow's classify step applies, extracted so
# it can be driven — and observed — without an emulator
# (scripts/test-ci-journey-infra-signature.sh).
#
# Usage:
#   ci-journey-shard-signature-verdict.sh <artifacts-dir>
#
# <artifacts-dir> is the job workspace's artifacts root, holding the canonical
# layout the journey suite + workflow write:
#   <artifacts-dir>/ci-journey/                 (final attempt)
#   <artifacts-dir>/ci-journey-attempt-1/ci-journey/  (preserved first attempt)
#
# Output (GitHub-step-output-compatible key=value lines):
#   shard_signature_verdict=INFRA | NONE
#   shard_signature_summaries=<count of summaries inspected>
#   shard_signature_detail=<per-summary classifications, space separated>
#
# INFRA is emitted ONLY when at least one summary was inspected AND every
# inspected summary classified as `real_ime_precondition`. Anything else — a
# genuine assertion failure, mixed evidence, missing/corrupt result XML, a
# missing classifier — emits NONE, which leaves the caller's existing RED
# branches in charge. Always exits 0.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
SIGNATURE="${CI_JOURNEY_INFRA_SIGNATURE:-$SCRIPT_DIR/ci-journey-infra-signature.sh}"

artifacts="${1-}"

verdict="NONE"
seen=0
detail=""

if [[ -n "$artifacts" && -d "$artifacts" && -f "$SIGNATURE" ]]; then
  all_signature=true
  for summary in \
    "$artifacts/ci-journey-attempt-1/ci-journey/summary.md" \
    "$artifacts/ci-journey/summary.md"; do
    [[ -f "$summary" ]] || continue
    out="$(bash "$SIGNATURE" "$summary" \
      "$artifacts/ci-journey" \
      "$artifacts/ci-journey-attempt-1" 2>/dev/null || true)"
    classification="$(sed -n 's/^journey_failure_classification=//p' <<<"$out" | tail -n 1)"
    [[ -n "$classification" ]] || classification="unclassified"
    seen=$((seen + 1))
    detail="${detail:+$detail }${summary}=${classification}"
    [[ "$classification" == "real_ime_precondition" ]] || all_signature=false
  done
  if (( seen > 0 )) && [[ "$all_signature" == "true" ]]; then
    verdict="INFRA"
  fi
fi

printf 'shard_signature_verdict=%s\n' "$verdict"
printf 'shard_signature_summaries=%s\n' "$seen"
printf 'shard_signature_detail=%s\n' "$detail"
exit 0
