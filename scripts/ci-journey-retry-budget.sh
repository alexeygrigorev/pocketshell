#!/usr/bin/env bash
# Issue #1458: decide whether the emulator-journey job has enough absolute wall
# budget left to start a second fresh cold boot.
#
# The GitHub job cap is 95 minutes. A retry is allowed only when the remaining
# wall covers the retry path.
#
# Issue #1800 — the requirement now reflects the cost of redoing THIS job's
# observed work instead of a fixed worst case. The original flat reserve was:
#   900s emulator shutdown/cold-boot/readiness
# + 4200s selected journey-suite budget   <- the suite's CAP, not its cost
# + 300s classifier, Docker logs, artifact upload, and fixture teardown
# = 5400s minimum remaining.
# That over-denied badly: on run 30305256109 shard 0 the first attempt's suite
# took 2338s (not 4200s) and its emulator bringup took ~50s (not 900s), so a
# complete retry cost ~2688s while the flat rule demanded 5400s and refused with
# 3112241ms (~52 min) still on the clock.
#
# When the caller supplies this job's own measurements the requirement becomes:
#   boot reserve   = max(3 x observed bringup, 120s)   — 3x headroom, 2 min floor
# + suite cost     = 1.10 x observed suite elapsed     — 10% headroom
# + teardown       = 300s (unchanged)
# clamped to never EXCEED the legacy flat 5400s, so this can only ever permit
# retries the old rule refused — never refuse one it allowed. Without usable
# measurements the flat 5400s worst case is used unchanged (fail-safe).
#
# Usage:
#   ci-journey-retry-budget.sh JOB_START_EPOCH_MS NOW_EPOCH_MS \
#     [FIRST_ATTEMPT_START_EPOCH_MS] [FIRST_SUITE_ELAPSED_SECS]
#
# Output is GitHub-step-output-compatible key=value lines. Invalid/missing clock
# input fails safe by denying the retry while returning success, so the workflow
# can continue to its typed INFRA classifier and artifact steps.
set -uo pipefail

readonly JOURNEY_JOB_CAP_MS=5700000
readonly JOURNEY_RETRY_REQUIRED_MS=5400000
readonly JOURNEY_RETRY_TEARDOWN_MS=300000
readonly JOURNEY_RETRY_MIN_BOOT_RESERVE_MS=120000
readonly JOURNEY_RETRY_BOOT_HEADROOM_NUMERATOR=3
readonly JOURNEY_RETRY_SUITE_HEADROOM_NUMERATOR=110
readonly JOURNEY_RETRY_SUITE_HEADROOM_DENOMINATOR=100
readonly MAX_SIGNED_INT64_DECIMAL=9223372036854775807
readonly MAX_MEASURED_SUITE_SECS=86400

RETRY_REQUIRED_MS="$JOURNEY_RETRY_REQUIRED_MS"
RETRY_COST_MODEL="worst_case"

emit_decision() {
  local allowed="$1" reason="$2" remaining="$3"
  printf 'retry_allowed=%s\n' "$allowed"
  printf 'retry_reason=%s\n' "$reason"
  printf 'retry_remaining_ms=%s\n' "$remaining"
  printf 'retry_required_ms=%s\n' "$RETRY_REQUIRED_MS"
  printf 'retry_cost_model=%s\n' "$RETRY_COST_MODEL"
}

canonical_decimal() {
  local value="${1-}"
  [[ "$value" =~ ^[0-9]+$ ]] || return 1
  [[ "$value" == "0" || "$value" != 0* ]] || return 1
  return 0
}

# measured_retry_required_ms <attempt_start_ms> <suite_elapsed_secs> <now_ms>
#
# Echoes the measured requirement, or nothing when the evidence is unusable.
# Never exceeds the legacy flat worst case.
measured_retry_required_ms() {
  local attempt_start="${1-}" suite_secs="${2-}" now_value="$3"
  local attempt_value suite_ms attempt_elapsed boot_ms boot_reserve suite_cost required

  canonical_decimal "$attempt_start" || return 1
  canonical_decimal "$suite_secs" || return 1
  epoch_out_of_range "$attempt_start" && return 1
  decimal_greater_than "$suite_secs" "$MAX_MEASURED_SUITE_SECS" && return 1

  attempt_value=$((10#$attempt_start))
  (( attempt_value > 0 )) || return 1
  (( attempt_value <= now_value )) || return 1
  suite_ms=$(( (10#$suite_secs) * 1000 ))
  (( suite_ms > 0 )) || return 1

  attempt_elapsed=$((now_value - attempt_value))
  boot_ms=$((attempt_elapsed - suite_ms))
  (( boot_ms >= 0 )) || return 1

  boot_reserve=$((boot_ms * JOURNEY_RETRY_BOOT_HEADROOM_NUMERATOR))
  (( boot_reserve < JOURNEY_RETRY_MIN_BOOT_RESERVE_MS )) &&
    boot_reserve="$JOURNEY_RETRY_MIN_BOOT_RESERVE_MS"
  suite_cost=$((
    suite_ms * JOURNEY_RETRY_SUITE_HEADROOM_NUMERATOR /
      JOURNEY_RETRY_SUITE_HEADROOM_DENOMINATOR
  ))
  required=$((boot_reserve + suite_cost + JOURNEY_RETRY_TEARDOWN_MS))
  (( required > JOURNEY_RETRY_REQUIRED_MS )) && required="$JOURNEY_RETRY_REQUIRED_MS"
  printf '%s' "$required"
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
  local attempt_start="${3-}" suite_secs="${4-}"

  RETRY_REQUIRED_MS="$JOURNEY_RETRY_REQUIRED_MS"
  RETRY_COST_MODEL="worst_case"

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

  # Issue #1800: prefer this job's own measured retry cost over the flat
  # worst case. The helper never returns MORE than the legacy requirement, so
  # a measurement can only ever unlock a retry the flat rule refused.
  local measured
  if measured="$(measured_retry_required_ms "$attempt_start" "$suite_secs" "$now_value")"; then
    RETRY_REQUIRED_MS="$measured"
    RETRY_COST_MODEL="measured_first_attempt"
  fi

  if (( remaining >= RETRY_REQUIRED_MS )); then
    emit_decision true sufficient_remaining_budget "$remaining"
  else
    emit_decision false insufficient_remaining_budget "$remaining"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  journey_retry_budget_decision "${1-}" "${2-}" "${3-}" "${4-}"
fi
