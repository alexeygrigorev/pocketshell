#!/usr/bin/env bash
# CI-matrix journey class selection helpers for scripts/ci-journey-suite.sh.

# ---------------------------------------------------------------------------
# Issue #835 (REOPENED): CI-matrix sharding of the journey class list.
#
# Root cause of the persistent emulator-journey RED/cancel: ~83 journey classes
# + 6 core-terminal proofs run STRICTLY SERIALLY on ONE swiftshader AVD take
# ~69+ min of wall-clock AND degrade the single emulator (the #470 enumeration
# stall worsens as the one AVD is churned by ~89 install/run/teardown cycles).
# Even the right-sized 4200s budget / 95-min cap (#1055) could not finish: its
# own validation run (commit 98384e91, GH Actions run 28307686762) ran the full
# ~95 min and was CANCELLED — no green verdict. Daemon-reuse + a bigger budget cut
# per-class overhead but the SERIAL wall-clock and single-emulator fragility
# remained the structural blocker.
#
# Fix: shard the class list across a CI MATRIX of runners (.github/workflows/
# tests.yml `strategy.matrix.shard`). Each matrix leg is its OWN runner with its
# OWN cold-booted emulator + its OWN Docker `agents` fixtures, and runs only its
# 1/N slice of the journey classes (round-robin by index so the heavy
# reconnect/switch journeys at the FRONT of the list distribute evenly across
# shards rather than all landing on one). This (a) cuts each leg's wall-clock to
# ~1/N so it finishes comfortably inside the budget, and (b) keeps each emulator
# far healthier (~1/N install/teardown cycles), which directly attacks the #470
# enumeration-stall root (a churned, degraded AVD).
#
# POCKETSHELL_JOURNEY_CI_SHARD_TOTAL / _INDEX are set by the workflow matrix.
# Default (unset / TOTAL<=1) = the unsharded serial path, UNCHANGED — so local
# runs and the budget self-test still run the FULL set. This is ORTHOGONAL to the
# #724 POCKETSHELL_JOURNEY_SHARD dev-box pool sharding (multiple emulators on ONE
# host); the two never combine on CI (one AVD per matrix leg). The six
# core-terminal proofs are NOT sharded — they are cheap in-process Compose UI
# tests (no Docker churn) and run on EVERY shard so a regression in any of them is
# caught on every leg.
select_effective_journey_classes() {
  JOURNEY_CI_SHARD_TOTAL="${POCKETSHELL_JOURNEY_CI_SHARD_TOTAL:-1}"
  JOURNEY_CI_SHARD_INDEX="${POCKETSHELL_JOURNEY_CI_SHARD_INDEX:-0}"
  [[ "$JOURNEY_CI_SHARD_TOTAL" =~ ^[0-9]+$ ]] || JOURNEY_CI_SHARD_TOTAL=1
  [[ "$JOURNEY_CI_SHARD_INDEX" =~ ^[0-9]+$ ]] || JOURNEY_CI_SHARD_INDEX=0
  (( JOURNEY_CI_SHARD_TOTAL < 1 )) && JOURNEY_CI_SHARD_TOTAL=1
  (( JOURNEY_CI_SHARD_INDEX < 0 || JOURNEY_CI_SHARD_INDEX >= JOURNEY_CI_SHARD_TOTAL )) && JOURNEY_CI_SHARD_INDEX=0

  EFFECTIVE_JOURNEY_CLASSES=()
  if (( JOURNEY_CI_SHARD_TOTAL <= 1 )); then
    EFFECTIVE_JOURNEY_CLASSES=("${JOURNEY_CLASSES[@]}")
  else
    local _shard_i
    for _shard_i in "${!JOURNEY_CLASSES[@]}"; do
      if (( _shard_i % JOURNEY_CI_SHARD_TOTAL == JOURNEY_CI_SHARD_INDEX )); then
        EFFECTIVE_JOURNEY_CLASSES+=("${JOURNEY_CLASSES[$_shard_i]}")
      fi
    done
    echo ">>> CI journey shard ${JOURNEY_CI_SHARD_INDEX}/${JOURNEY_CI_SHARD_TOTAL} (issue #835): running ${#EFFECTIVE_JOURNEY_CLASSES[@]} of ${#JOURNEY_CLASSES[@]} journey classes (round-robin), plus all 6 core-terminal proofs"
  fi
}

print_journey_class_selection() {
  echo "=========================================================="
  echo "Per-push CI journey suite (issue #691) — load-bearing subset"
  echo "Included classes:"
  local c
  for c in "${EFFECTIVE_JOURNEY_CLASSES[@]}"; do
    echo "  - $c"
  done
  echo "  (pocketshellCi=true; deterministic agents:2222 only, no toxiproxy)"
  echo "  (per-class retry-once for CI-AVD infra flakes — issue #712)"
  echo "=========================================================="
}
