#!/usr/bin/env bash
# Issues #1833 / #2374: emit the emulator-journey shard's ONE-SHOT annotation.
#
# Extracted from `.github/workflows/tests.yml`'s classify step so the workflow
# stays under scripts/check-file-size-hygiene.sh's workflow headroom, which is
# that guard's own prescribed remedy — the explanatory comments move here with
# the code they explain rather than being deleted.
#
# WHY THE ANNOTATION EXISTS (#1833). A shard that reaches its verdict UNABLE to
# start a cold-boot retry ran ONE-SHOT: half the gate's resilience is gone and a
# single #788-class swiftshader flake reddens `main` outright instead of
# recovering. On run 30383504733 all three shards were in that state and every
# one still reported a plain CLEAN/RED — a gate that had lost its retry looked
# identical to one that had not. Same principle as #1814's `outer_timeout_phase`
# and #1827's registry: a condition that changes what the gate CAN DO must
# appear in the gate's own evidence.
#
# WHY IT NOW NAMES A CLASS (#2374). `retry_affordable=false` alone proved too
# coarse. On run 33181062826 all six shards carried it, and the batch was triaged
# as a recurrence of #1833/#1850's capacity loss. It was not: every one of those
# shards had already written a `Failed BOTH attempts` summary, so 4-11
# genuinely failing classes — each paying TWO full 420s per-class attempts — are
# what pushed suite elapsed to 2674-4206s and consumed the wall. Suites at the
# six-shard matrix ran LONGER than the three-shard suites (1867-3017s) #1850
# diagnosed as overload, on HALF the classes per leg. No budget constant moved.
#
# The two conditions want opposite responses, so the annotation says which:
#   gate_capacity                   the shard failed NO journey and still could
#                                   not retry — #1833/#1850's condition; the
#                                   levers are the matrix and this estimate.
#   journey_failure_inflated_suite  the shard's own failing classes doubled its
#                                   suite; nothing here is the lever.
#   unknown                         no usable reading; assume neither.
#
# REPORTING ONLY. This script never changes a verdict, a token or an exit code,
# and it always exits 0 — an annotation must not be able to fail a shard.
#
# Usage:
#   ci-journey-retry-denial-notice.sh AFFORDABLE REASON CLASS SHORTFALL_MS \
#     REMAINING_MS REQUIRED_MS COST_MODEL
set -uo pipefail

affordable="${1:-false}"
reason="${2:-missing_decision}"
denial_class="${3:-unknown}"
shortfall="${4:-0}"
remaining="${5:-0}"
required="${6:-5400000}"
cost_model="${7:-worst_case}"

[[ "$affordable" == "true" ]] && exit 0

case "$denial_class" in
  gate_capacity)
    lever="This is #1833/#1850's condition: the shard failed NO journey and still could not retry. The levers are the shard count, the class distribution and the retry-cost estimate." ;;
  journey_failure_inflated_suite)
    lever="This is NOT #1833/#1850: the shard's OWN failing journeys each paid two full per-class attempts and doubled its suite. Fix the classes its summary names; no budget or shard count is the lever here (issue #2374)." ;;
  *)
    lever="The denial class could not be read, so treat it as neither condition until the shard's own budget evidence says which (issue #2374)." ;;
esac

echo "::warning title=Emulator journey shard ran ONE-SHOT — no cold-boot retry was affordable (#1833)::This shard reached its verdict unable to start a second cold boot (reason=${reason}, class=${denial_class}, remaining=${remaining}ms, required=${required}ms, short by ${shortfall}ms, cost model=${cost_model}). The verdict below is from ONE attempt: a #788-class environmental flake here reddens the aggregate instead of flake-recovering. ${lever}"
exit 0
