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
# Issue #1833 — the suite cost is now priced as a WARM retry.
#
# #1800's `1.10 x observed suite elapsed` charges the retry for EVERYTHING the
# first attempt's suite did. After #1814 that includes the shard's whole cold
# Gradle build, which the suite now pays as one named, separately MEASURED
# up-front phase ("Warm build (issue #1814): **ok** in 464s"). A within-job retry
# does not repeat that build: it re-creates the AVD and cold-boots a fresh
# emulator, but it runs on the SAME runner against the `~/.gradle` and
# `app/build` the first attempt already populated, so every assemble task is
# UP-TO-DATE the second time.
#
# That is observed, not assumed — run 30318408377, job 90153112226 (shard 0), the
# one recent run where the retry actually ran, same first class on both attempts:
#
#   attempt 1 (cold Gradle):            4200s -> 3889s  = 311s
#   attempt 2 (the retry, warm Gradle): 4200s -> 4179s  =  21s
#
# and the retry's whole suite came in 379s cheaper (1987s -> 1608s) on the
# identical 48-class selection. Post-#1814 the same work is measured directly:
# 373s / 464s / 489s across run 30383504733's three shards — 15-19% of each
# shard's entire suite elapsed, charged twice by the un-corrected model. On that
# run ALL THREE shards refused their retry with `insufficient_remaining_budget`,
# two of them by less than the cold build they were double-charged for.
#
# So when the caller supplies the measured warm-build seconds the suite cost
# becomes:
#   1.10 x (observed suite elapsed - measured warm build)   the work that repeats
# + 120s warm-rebuild reserve                               the UP-TO-DATE pass
# instead of `1.10 x observed suite elapsed`. The 120s reserve is ~6x the 21s a
# whole warm first class cost on run 30318408377, so it is generous against the
# thing it stands in for.
#
# This is a CORRECTION to the estimate, not a larger budget: JOURNEY_JOB_CAP_MS,
# JOURNEY_RETRY_REQUIRED_MS, the 4200s suite budget and the 95-minute job cap are
# all untouched (G6). It is also strictly optional — an absent, malformed,
# zero, or not-smaller-than-the-suite warm-build reading leaves #1800's model
# byte-for-byte unchanged, so the fail-safe direction stays "deny the retry".
#
# WHAT THIS CORRECTION DOES *NOT* FIX — read this before tuning anything here.
#
# It is a PARTIAL. Measured against every shard-run whose budget figures are
# still retrievable — runs 30351095421, 30339688411, 30383504733 and 30392101105,
# twelve shard-runs, ten of them denied — the correction restores THREE and
# leaves SEVEN denied. It also widens run 30392101105 shard 0 from a 2688ms
# ALLOWED margin (under 3 seconds of a 95-minute job) to 391988ms, which is real
# value, but it does not change the shape of the problem.
#
# Holding each shard-run's fixed costs and sweeping the suite, affordability has
# exactly one crossing point, and across all twelve it sits in a band only ~110s
# wide: roughly 2500-2650s of suite elapsed. SEVEN of the twelve observed suites
# are already past their own crossing. So the retry is affordable iff the suite
# is short enough, and the observed distribution straddles the line.
#
# Worse, the three restorations survive only 24s / 72s / 79s of suite growth
# (~1-3% of their suites), because the margin decays at 2100ms per second of
# suite elapsed — remaining falls 1000ms and required rises 1100ms. ONE
# twice-failing journey class cost 417s on run 30383504733. So the retry comes
# back only on runs where nothing went wrong, and a retry that is affordable
# only when it is not needed is not resilience.
#
# The binding constraint is therefore PER-SHARD LOAD, not this estimate. Do not
# respond to a future denial by tuning JOURNEY_RETRY_WARM_REBUILD_MS or the
# headrooms — that is the constant-chasing #1833 exists to end. The levers are
# the shard count / class distribution and the flake cost driving suite elapsed
# (#788 / #1819 / #1820), tracked as #1850. The scoreboard, the drift budgets and
# the crossing-point band are all asserted in
# scripts/test-ci-journey-retry-budget.sh cases (x), (x2), (y) and (z), so this
# conclusion cannot quietly go stale. The same file's #1850 load ratchet then
# redistributes a measured per-class fixture through the shipping selector at
# the matrix total from scripts/ci-journey-shard-count.sh, and requires every
# leg's retry headroom to exceed one twice-failing class (~420s).
#
# Issue #2374 — a DENIAL now names WHICH condition it is.
#
# On 2026-08-28 all six shards on `main` reported `insufficient_remaining_budget`
# and the run was triaged as a recurrence of #1833/#1850. It was not. Every one
# of those shards had already written a `Failed BOTH attempts` summary: 4 to 11
# journey classes per shard genuinely failing, each paying TWO full 420s
# per-class attempts. That is what drove suite elapsed to 2674-4206s — LONGER
# than the three-shard suites (1867-3017s) #1850 diagnosed as overload, on HALF
# the classes per leg — and it is the suite elapsed the model above consumes. No
# constant here moved; the shard's own failures ate the wall.
#
# The two conditions want OPPOSITE responses:
#
#   gate_capacity                  the shard failed NO journey and still cannot
#                                  afford a retry. This is #1833/#1850's loss of
#                                  resilience; the levers are the shard count,
#                                  the class distribution, and this estimate.
#   journey_failure_inflated_suite the shard's first attempt already failed
#                                  journeys. Its suite is long BECAUSE of them.
#                                  Nothing here is the lever — fix the classes.
#                                  Tuning a budget for this case buys a second
#                                  60-minute cold boot that re-fails the same
#                                  classes and still ends RED.
#
# #1833 shipped `retry_shortfall_ms` so an operator could tell them apart by eye
# (its own header: "denied by 224s" versus "denied by 22 minutes"). Nothing
# CLASSIFIED on it, so the next occurrence was read as its own cause. The caller
# already computes the discriminator — the workflow's `journey_summary` step
# greps the first attempt's own summary for `JOURNEY_FAILED` / `Failed BOTH
# attempts` — so this is an exact reading, not a heuristic on the shortfall.
#
# It is REPORTING ONLY: `retry_denial_class` is an additional output line and
# changes no decision, no other field, and no exit code. An absent or malformed
# reading reports `unknown` rather than guessing, because guessing either way
# re-creates the misdiagnosis this exists to end.
#
# Usage:
#   ci-journey-retry-budget.sh JOB_START_EPOCH_MS NOW_EPOCH_MS \
#     [FIRST_ATTEMPT_START_EPOCH_MS] [FIRST_SUITE_ELAPSED_SECS] \
#     [FIRST_WARM_BUILD_SECS] [FIRST_ATTEMPT_JOURNEY_FAILURE]
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
# Issue #1833: what the retry's own (UP-TO-DATE) warm build is allowed to cost.
readonly JOURNEY_RETRY_WARM_REBUILD_MS=120000
readonly MAX_SIGNED_INT64_DECIMAL=9223372036854775807
readonly MAX_MEASURED_SUITE_SECS=86400

RETRY_REQUIRED_MS="$JOURNEY_RETRY_REQUIRED_MS"
RETRY_COST_MODEL="worst_case"
# Issue #1833: how much measured cold-build cost was NOT charged a second time.
# 0 whenever the warm-build reading was absent or unusable, i.e. whenever the
# emitted requirement is #1800's unmodified one.
RETRY_WARM_BUILD_DEDUCTED_MS=0
# Issue #2374: which of the two denial conditions this is. `none` while the
# retry is allowed; `unknown` when the caller supplied no usable reading of
# whether the first attempt genuinely failed journeys.
RETRY_DENIAL_CLASS=none
# The caller's reading of whether the FIRST attempt already failed journeys.
# Set once per decision; every emit path (including the malformed-clock
# fail-safes) classifies from it, so no branch can emit an unclassified denial.
FIRST_ATTEMPT_JOURNEY_FAILURE=""

# journey_failure_denial_class <allowed> <first_attempt_journey_failure>
#
# Pure and total: every input maps to exactly one of the four tokens, and
# anything the caller cannot vouch for maps to `unknown` rather than to a guess.
journey_failure_denial_class() {
  local allowed="$1" first_failure="${2-}"
  if [[ "$allowed" == "true" ]]; then
    printf 'none'
    return 0
  fi
  case "$first_failure" in
    true)  printf 'journey_failure_inflated_suite' ;;
    false) printf 'gate_capacity' ;;
    *)     printf 'unknown' ;;
  esac
}

emit_decision() {
  local allowed="$1" reason="$2" remaining="$3" shortfall=0
  # Issue #1833: the shortfall is the load-bearing number for the operator — it
  # is what separates "denied by 224s" (an estimate worth correcting) from
  # "denied by 22 minutes" (a shard that genuinely does not fit). Reported for
  # every denial, including the malformed-clock fail-safes, where remaining=0 and
  # the shortfall is therefore the whole flat reserve.
  if [[ "$allowed" != "true" ]] && (( RETRY_REQUIRED_MS > remaining )); then
    shortfall=$((RETRY_REQUIRED_MS - remaining))
  fi
  RETRY_DENIAL_CLASS="$(
    journey_failure_denial_class "$allowed" "$FIRST_ATTEMPT_JOURNEY_FAILURE"
  )"
  printf 'retry_allowed=%s\n' "$allowed"
  printf 'retry_reason=%s\n' "$reason"
  printf 'retry_remaining_ms=%s\n' "$remaining"
  printf 'retry_required_ms=%s\n' "$RETRY_REQUIRED_MS"
  printf 'retry_cost_model=%s\n' "$RETRY_COST_MODEL"
  printf 'retry_shortfall_ms=%s\n' "$shortfall"
  printf 'retry_warm_build_deducted_ms=%s\n' "$RETRY_WARM_BUILD_DEDUCTED_MS"
  printf 'retry_denial_class=%s\n' "$RETRY_DENIAL_CLASS"
}

canonical_decimal() {
  local value="${1-}"
  [[ "$value" =~ ^[0-9]+$ ]] || return 1
  [[ "$value" == "0" || "$value" != 0* ]] || return 1
  return 0
}

# measured_retry_required_ms <attempt_start_ms> <suite_elapsed_secs> <now_ms> \
#                            [warm_build_secs]
#
# Echoes `<required_ms> <warm_build_deducted_ms>`, or nothing when the evidence
# is unusable. Never exceeds the legacy flat worst case. The second field is 0
# whenever the #1833 warm-build correction did not apply, i.e. whenever the first
# field is #1800's unmodified requirement. Both are echoed (rather than set as
# globals) so the function stays pure and callable from `$(...)`.
measured_retry_required_ms() {
  local attempt_start="${1-}" suite_secs="${2-}" now_value="$3" warm_secs="${4-}"
  local attempt_value suite_ms attempt_elapsed boot_ms boot_reserve suite_cost required
  local warm_ms repeat_ms rebuild_reserve deducted=0

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

  # Issue #1833: re-price the suite as the part the retry actually REPEATS. The
  # measured cold build (#1814) is deducted and replaced by a flat UP-TO-DATE
  # reserve. Deliberately narrow: the reading must be a canonical, in-range,
  # non-zero count that is STRICTLY SMALLER than the suite it is a phase of.
  # Anything else — absent, malformed, zero, or nonsensically >= the suite —
  # leaves #1800's arithmetic untouched.
  #
  # The `<` comparison below is what keeps #1800's one-way guarantee intact: the
  # correction is adopted ONLY when it lowers the requirement. Without it, a
  # deducted build smaller than the 120s reserve (1.10 x warm < reserve) would
  # make the model demand MORE than #1800 did and could refuse a retry the old
  # rule allowed — the exact inversion #1800's clamp exists to prevent.
  if canonical_decimal "$warm_secs" \
     && ! decimal_greater_than "$warm_secs" "$MAX_MEASURED_SUITE_SECS"; then
    warm_ms=$(( (10#$warm_secs) * 1000 ))
    if (( warm_ms > 0 && warm_ms < suite_ms )); then
      repeat_ms=$((suite_ms - warm_ms))
      rebuild_reserve=$((
        repeat_ms * JOURNEY_RETRY_SUITE_HEADROOM_NUMERATOR /
          JOURNEY_RETRY_SUITE_HEADROOM_DENOMINATOR
        + JOURNEY_RETRY_WARM_REBUILD_MS
      ))
      if (( rebuild_reserve < suite_cost )); then
        suite_cost="$rebuild_reserve"
        deducted="$warm_ms"
      fi
    fi
  fi
  required=$((boot_reserve + suite_cost + JOURNEY_RETRY_TEARDOWN_MS))
  (( required > JOURNEY_RETRY_REQUIRED_MS )) && required="$JOURNEY_RETRY_REQUIRED_MS"
  printf '%s %s' "$required" "$deducted"
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
  local attempt_start="${3-}" suite_secs="${4-}" warm_secs="${5-}"
  local first_failure="${6-}"

  RETRY_REQUIRED_MS="$JOURNEY_RETRY_REQUIRED_MS"
  RETRY_COST_MODEL="worst_case"
  RETRY_WARM_BUILD_DEDUCTED_MS=0
  # Issue #2374: only the two exact tokens the caller's own summary grep can
  # produce are accepted. Anything else — unset, empty, `TRUE`, `1`, a shell
  # expansion that silently blanked — is an unusable reading and must classify
  # `unknown`, never a guess in either direction.
  case "$first_failure" in
    true | false) FIRST_ATTEMPT_JOURNEY_FAILURE="$first_failure" ;;
    *)            FIRST_ATTEMPT_JOURNEY_FAILURE="" ;;
  esac

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
  #
  # Issue #1833: when the measured cold build is also supplied, the same helper
  # prices the suite as a WARM retry. `measured_retry_required_ms` adopts that
  # correction only when it LOWERS the requirement, so the one-way guarantee
  # above still holds and the model name reports which arithmetic was used.
  local measured measured_required measured_deducted
  if measured="$(
    measured_retry_required_ms "$attempt_start" "$suite_secs" "$now_value" "$warm_secs"
  )"; then
    read -r measured_required measured_deducted <<<"$measured"
    RETRY_REQUIRED_MS="$measured_required"
    RETRY_WARM_BUILD_DEDUCTED_MS="$measured_deducted"
    if (( RETRY_WARM_BUILD_DEDUCTED_MS > 0 )); then
      RETRY_COST_MODEL="measured_warm_retry"
    else
      RETRY_COST_MODEL="measured_first_attempt"
    fi
  fi

  if (( remaining >= RETRY_REQUIRED_MS )); then
    emit_decision true sufficient_remaining_budget "$remaining"
  else
    emit_decision false insufficient_remaining_budget "$remaining"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  journey_retry_budget_decision "${1-}" "${2-}" "${3-}" "${4-}" "${5-}" "${6-}"
fi
