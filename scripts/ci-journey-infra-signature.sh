#!/usr/bin/env bash
# Issue #1800: fail-safe front end for the emulator-journey failure-signature
# classifier. Emits GitHub-step-output-compatible key=value lines and ALWAYS
# exits 0; the caller decides the shard verdict from the classification.
#
# The only classification that changes a shard's verdict is
# `real_ime_precondition` (the captured CI swiftshader "real system
# input-method window never became visible" precondition -> INFRA / RE-RUN).
# Everything else — including a missing python3, missing artifacts, an
# unreadable result file, or ANY other assertion failure — reports
# `unclassified` or `product_failure`, both of which keep the shard RED.
#
# Usage:
#   ci-journey-infra-signature.sh SUMMARY_FILE ARTIFACT_ROOT [ARTIFACT_ROOT ...]
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
CLASSIFIER="${CI_JOURNEY_INFRA_SIGNATURE_CLASSIFIER:-$SCRIPT_DIR/ci-journey-infra-signature.py}"
PYTHON_BIN="${CI_JOURNEY_PYTHON:-python3}"

emit_unclassified() {
  local reason="$1"
  echo "journey_failure_classification=unclassified"
  echo "journey_failed_classes="
  echo "journey_failing_testcases=0"
  echo "journey_signature_matches=0"
  echo "journey_offending_failures="
  echo "journey_signature_note=$reason" >&2
}

if [[ "$#" -lt 2 ]]; then
  emit_unclassified "usage: SUMMARY_FILE ARTIFACT_ROOT [ARTIFACT_ROOT ...]"
  exit 0
fi

if [[ ! -f "$CLASSIFIER" ]]; then
  emit_unclassified "classifier missing: $CLASSIFIER"
  exit 0
fi

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  emit_unclassified "python3 unavailable; keeping the shard verdict red"
  exit 0
fi

output=""
if ! output="$("$PYTHON_BIN" "$CLASSIFIER" "$@" 2>/dev/null)"; then
  emit_unclassified "classifier exited non-zero; keeping the shard verdict red"
  exit 0
fi

if ! grep -q '^journey_failure_classification=' <<<"$output"; then
  emit_unclassified "classifier produced no classification line"
  exit 0
fi

printf '%s\n' "$output"
exit 0
