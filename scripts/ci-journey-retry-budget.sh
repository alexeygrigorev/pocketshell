#!/usr/bin/env bash
# Issue #1458: decide whether the emulator-journey job has enough absolute wall
# budget left to start a second fresh cold boot.
#
# The GitHub job cap is 95 minutes. A retry is allowed only when the remaining
# wall covers the full worst-case retry path:
#   900s emulator shutdown/cold-boot/readiness
# + 4200s selected journey-suite budget
# + 300s classifier, Docker logs, artifact upload, and fixture teardown
# = 5400s minimum remaining.
#
# Usage:
#   ci-journey-retry-budget.sh JOB_START_EPOCH_MS NOW_EPOCH_MS
#
# Output is GitHub-step-output-compatible key=value lines. Invalid/missing clock
# input fails safe by denying the retry while returning success, so the workflow
# can continue to its typed INFRA classifier and artifact steps.
set -uo pipefail

readonly JOURNEY_JOB_CAP_MS=5700000
readonly JOURNEY_RETRY_REQUIRED_MS=5400000
readonly MAX_SIGNED_INT64_DECIMAL=9223372036854775807

emit_decision() {
  local allowed="$1" reason="$2" remaining="$3"
  printf 'retry_allowed=%s\n' "$allowed"
  printf 'retry_reason=%s\n' "$reason"
  printf 'retry_remaining_ms=%s\n' "$remaining"
  printf 'retry_required_ms=%s\n' "$JOURNEY_RETRY_REQUIRED_MS"
}

# decimal_greater_than <canonical-decimal-a> <canonical-decimal-b>
#
# Compare validated, non-negative canonical decimal strings without shell
# arithmetic. This is safe even at INT64_MAX and is used before parsing either
# untrusted epoch.
decimal_greater_than() {
  local left="$1" right="$2"
  if (( ${#left} != ${#right} )); then
    (( ${#left} > ${#right} ))
    return
  fi
  [[ "$left" > "$right" ]]
}

epoch_out_of_range() {
  local value="$1"
  decimal_greater_than "$value" "$MAX_SIGNED_INT64_DECIMAL"
}

journey_retry_budget_decision() {
  local start="${1-}" now="${2-}"

  if [[ -z "$start" ]]; then
    emit_decision false missing_job_start 0
    return 0
  fi
  if [[ ! "$start" =~ ^[0-9]+$ ]]; then
    emit_decision false malformed_job_start 0
    return 0
  fi
  if [[ "$start" != "0" && "$start" == 0* ]]; then
    emit_decision false noncanonical_job_start 0
    return 0
  fi
  if epoch_out_of_range "$start"; then
    emit_decision false out_of_range_job_start 0
    return 0
  fi
  if [[ ! "$now" =~ ^[0-9]+$ ]]; then
    emit_decision false malformed_now 0
    return 0
  fi
  if [[ "$now" != "0" && "$now" == 0* ]]; then
    emit_decision false noncanonical_now 0
    return 0
  fi
  if epoch_out_of_range "$now"; then
    emit_decision false out_of_range_now 0
    return 0
  fi
  if decimal_greater_than "$start" "$now"; then
    emit_decision false future_job_start 0
    return 0
  fi

  # Both values are now canonical base-10 decimals in signed-64 range and
  # now >= start. Parse explicitly as base 10, then compute in overflow-safe
  # order: elapsed first, followed by cap - elapsed only while elapsed < cap.
  # Never form start + cap, which can overflow near INT64_MAX.
  local start_value now_value elapsed remaining
  start_value=$((10#$start))
  now_value=$((10#$now))
  elapsed=$((now_value - start_value))
  if (( elapsed >= JOURNEY_JOB_CAP_MS )); then
    remaining=0
  else
    remaining=$((JOURNEY_JOB_CAP_MS - elapsed))
  fi

  if (( remaining >= JOURNEY_RETRY_REQUIRED_MS )); then
    emit_decision true sufficient_remaining_budget "$remaining"
  else
    emit_decision false insufficient_remaining_budget "$remaining"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  journey_retry_budget_decision "${1-}" "${2-}"
fi
