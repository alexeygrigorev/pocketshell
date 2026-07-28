#!/usr/bin/env bash
# Issue #1809: write ONE per-shard emulator-journey verdict token, stamped with
# the provenance the aggregation job (and a G5 on-call) needs to tell a fresh
# re-run verdict apart from a carried-over one.
#
# Why a stamp is needed at all — the mechanics of a single-shard re-run:
#   * Artifact NAMES are unique per workflow-run ATTEMPT, not per run. Re-running
#     one shard therefore does NOT collide with (and `overwrite: true` does NOT
#     delete) the previous attempt's artifact: both are stored under the same
#     name. Proven on this repo — run 30187024723 holds two
#     `emulator-journey-verdict-shard-0` artifacts, 8627574488 (attempt 1,
#     `INFRA`) and 8628504152 (attempt 2, `RED`).
#   * `actions/download-artifact` resolves a name with `latest: true`, so the
#     NEWEST upload wins. That is what lets a re-run shard's token supersede its
#     own earlier one while the shards that were NOT re-run legitimately keep
#     their earlier tokens.
#   * The hole that leaves: a shard that IS re-run but dies before the classifier
#     uploads NOTHING, and the aggregation job then silently consumes the
#     PREVIOUS attempt's token as if it were the re-run's verdict. Nothing in the
#     log says so. That is the stale reading G5 forbids.
#
# The fix has two halves and this script is both of them:
#   1. The emulator-journey job PRE-SEEDS a token (INFRA) right after checkout,
#      so every attempt that starts always contributes a token of its own. A
#      carried-over token then soundly means "this shard was not re-run".
#   2. Every token carries `run_id` / `run_attempt` / `shard`, so the aggregation
#      job can PRINT which attempt each verdict came from — turning "the re-run
#      produced this verdict" from a claim into an artifact.
#
# The seed value is INFRA (a re-run signal) and never CLEAN or RED: an attempt
# that died before classifying has neither proved the gate clean nor found a
# regression.
#
# Format (parsed by scripts/ci-journey-aggregate-verdict.sh):
#   line 1        the bare verdict token: CLEAN | INFRA | RED
#   line 2..n     `key=value` provenance lines
# Line 1 stays the bare token so the file remains trivially greppable and the
# aggregation parser never has to guess where the verdict is.
#
# The token lives in its OWN directory (`artifacts/ci-journey-shard-verdict/`),
# deliberately NOT inside `artifacts/ci-journey/`. Issue #1781's first-attempt
# snapshot is a byte-identical copy of `artifacts/ci-journey/` that must never
# carry a shard token (nobody may mistake a token in the snapshot for that
# attempt's verdict). Because the token is now pre-seeded at job start, keeping
# it under `artifacts/ci-journey/` would put it in that snapshot; a separate
# directory makes #1781's invariant STRUCTURAL rather than dependent on step
# ordering. The reports upload still packages this directory, so the token stays
# in the forensic bundle.
#
# Issue #1814 (verdict REASON): the token now also carries WHY it has the value
# it has. The concrete miss: an `exit 124 / outer_timeout / raw-junit count=0`
# attempt is what a killed mid-journey runner produces AND what a class cut
# short while Gradle was still BUILDING produces, so a shard RED caused by the
# cold build read exactly like a product defect (run 30323508796, shard 2). The
# reason is a short machine token (`cold_build_timeout`,
# `journey_failure_both_attempts`, `emulator_never_booted`, …) that the
# aggregation job prints per shard.
#
# The reason NEVER changes the verdict: line 1 stays the bare CLEAN|INFRA|RED
# token and every consumer's severity is unchanged. Labelling a failure is not
# the same as softening it — softening a build-cost timeout to a re-run signal
# would be exactly the masking D31/D32 forbid.
#
# Usage:
#   ci-journey-write-shard-verdict.sh <CLEAN|INFRA|RED> [REASON]
# Env:
#   SHARD_VERDICT_FILE                     output path (default
#                                          artifacts/ci-journey-shard-verdict/shard-verdict.txt)
#   SHARD_VERDICT_REASON                   fallback for the REASON argument
#   POCKETSHELL_JOURNEY_CI_SHARD_INDEX     shard index (set by the matrix)
#   GITHUB_RUN_ID / GITHUB_RUN_ATTEMPT     provenance (set by Actions)
#   GITHUB_OUTPUT                          when set, also exports
#                                          `shard_verdict=<token>` (so a later
#                                          step can gate on it) and
#                                          `shard_verdict_reason=<reason>`
set -uo pipefail

verdict="${1:-}"
case "$verdict" in
  CLEAN | INFRA | RED) ;;
  *)
    echo "usage: ci-journey-write-shard-verdict.sh <CLEAN|INFRA|RED> [REASON]" >&2
    exit 2
    ;;
esac

# The reason is advisory metadata, so it fails SOFT to `unspecified` rather than
# rejecting the write: a typo'd label must never cost the run its verdict token.
reason="${2:-${SHARD_VERDICT_REASON:-unspecified}}"
[[ "$reason" =~ ^[a-z][a-z0-9_]{0,63}$ ]] || reason="unspecified"

out="${SHARD_VERDICT_FILE:-artifacts/ci-journey-shard-verdict/shard-verdict.txt}"
if ! mkdir -p "$(dirname "$out")"; then
  echo "ci-journey-write-shard-verdict.sh: cannot create $(dirname "$out")" >&2
  exit 1
fi

shard="${POCKETSHELL_JOURNEY_CI_SHARD_INDEX:-unknown}"
run_id="${GITHUB_RUN_ID:-unknown}"
run_attempt="${GITHUB_RUN_ATTEMPT:-unknown}"

if ! {
  printf '%s\n' "$verdict"
  printf 'shard=%s\n' "$shard"
  printf 'run_id=%s\n' "$run_id"
  printf 'run_attempt=%s\n' "$run_attempt"
  printf 'verdict_reason=%s\n' "$reason"
  printf 'written_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$out"; then
  echo "ci-journey-write-shard-verdict.sh: cannot write $out" >&2
  exit 1
fi

echo "SHARD_VERDICT=$verdict (shard $shard, run $run_id attempt $run_attempt, reason $reason)"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'shard_verdict=%s\n' "$verdict" >> "$GITHUB_OUTPUT"
  printf 'shard_verdict_reason=%s\n' "$reason" >> "$GITHUB_OUTPUT"
fi
