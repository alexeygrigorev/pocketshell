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
# 1/N slice of the journey classes. This (a) cuts each leg's wall-clock to ~1/N
# so it finishes comfortably inside the budget, and (b) keeps each emulator far
# healthier (~1/N install/teardown cycles), which directly attacks the #470
# enumeration-stall root (a churned, degraded AVD).
#
# ---------------------------------------------------------------------------
# Issue #1862: membership is assigned by HASHING THE CLASS NAME, not by the
# entry's POSITION in the JOURNEY_CLASSES array.
#
# THE DEFECT THIS REMOVES
# -----------------------
# The original partition was round-robin by array index (`index % total`). That
# makes a class's shard a function of how many entries happen to precede it, so
# INSERTING ONE ENTRY re-rolls the placement of every class after it.
#
# Measured on the #1845 merge (commit f2aa9a8e): registering ONE new journey
# class at index 36 moved 111 of the then-147 classes to a different shard. Two
# of them landed on shard 2 behind ~12 co-tenants they had never run behind, hit
# a latent timing-sensitive failure, and `main` went RED — from a commit whose
# production diff could not reach either class. Both cold boots failed the same
# way (4/4 per class), so it was not even re-runnable away.
#
# Two costs, and the second is the expensive one:
#   * an unrelated one-line suite registration can turn `main` red, and
#   * the red is MIS-ATTRIBUTED. The natural suspect is the commit that went in,
#     and its diff has nothing to do with the failure — so the investigation
#     starts from a false premise. (`ci-journey-warm-build-functions.sh` records
#     the same mechanism producing the now-disproven "shard 0 is pathological"
#     theory: index sharding rotates WHICH class absorbs the cold build.)
#
# THE FIX AND WHY THIS SHAPE
# --------------------------
# Assign by `hash(class_name) % total`. A class's shard then depends ONLY on its
# own name, so adding, removing, or reordering ANY other entry leaves every
# other class exactly where it was — the newcomer is the only class that moves.
# Adding a journey stops being a global re-roll of the suite's placement, and a
# red shard after a registration is real signal about the new class rather than
# noise from 111 relocated siblings.
#
# The hash is FNV-1a/32 computed in pure bash over the entry's bytes: no
# subprocess per class, no dependency on `cksum`/`md5sum`/awk availability or
# version, and the same value on every runner and every bash >= 4. `LC_ALL=C`
# pins byte semantics so a runner's locale cannot change the partition.
#
# Balance is statistical rather than exact (index round-robin was exact to +/-1).
# That trade is deliberate and the numbers are small: over the current 148-entry
# list the three shards get 52/48/48 against an ideal of 49.3. The suite budget
# already carries far more slack than a couple of classes' worth, and
# `scripts/test-ci-journey-budget.sh` pins the spread so a future list cannot
# drift into a lopsided partition unnoticed.
#
# Deliberately NOT chosen:
#   * reordering the array so the victim class moves back — that only relocates
#     which class is next to the hostile co-tenant, and re-rolls everything again
#     on the next insertion;
#   * pinning classes to shards by hand — a second registry to keep in sync with
#     the array, and it rots silently;
#   * salting/tuning the hash so a specific class lands on a specific shard —
#     that is the reordering hack wearing a hash costume, and it hides a real
#     failure instead of fixing it.
#
# This changes WHICH shard runs a class. It does NOT change the set of classes
# run: the partition is still disjoint and its union is still the full array.
#
# POCKETSHELL_JOURNEY_CI_SHARD_TOTAL / _INDEX are set by the workflow matrix.
# Default (unset / TOTAL<=1) = the unsharded serial path, UNCHANGED — so local
# runs and the budget self-test still run the FULL set. This is ORTHOGONAL to the
# #724 POCKETSHELL_JOURNEY_SHARD dev-box pool sharding (multiple emulators on ONE
# host); the two never combine on CI (one AVD per matrix leg).
#
# Issue #2110: the core-terminal proofs are sharded by this SAME hash now. They
# used to run on EVERY leg "so a regression in any of them is caught on every
# leg" — but every leg runs the same commit and these proofs are device-
# independent in-process tests, so five of the six verdicts were copies costing
# ~20-35 runner-minutes a push. The partition lives in
# scripts/ci-journey-core-terminal-functions.sh
# (select_effective_core_terminal_proofs), reusing journey_class_shard_index
# below so there is exactly one partition mechanism to reason about.

# FNV-1a/32 over the bytes of "$1", printed as an unsigned decimal.
#
# Pure bash on purpose: this runs once per class at suite startup (~150 calls),
# and a subprocess per class would cost more than the whole loop. Bash arithmetic
# is 64-bit signed, so the explicit 0xFFFFFFFF masks keep it in the 32-bit
# unsigned range that FNV-1a is defined over.
journey_class_shard_hash() {
  local _jcsh_s="$1"
  local _jcsh_len=${#_jcsh_s}
  local _jcsh_i _jcsh_byte
  local _jcsh_h=2166136261  # FNV-1a/32 offset basis
  local LC_ALL=C            # byte semantics, so locale cannot move a class
  for (( _jcsh_i = 0; _jcsh_i < _jcsh_len; _jcsh_i++ )); do
    printf -v _jcsh_byte '%d' "'${_jcsh_s:_jcsh_i:1}"
    _jcsh_h=$(( (_jcsh_h ^ (_jcsh_byte & 255)) & 0xFFFFFFFF ))
    _jcsh_h=$(( (_jcsh_h * 16777619) & 0xFFFFFFFF ))  # FNV-1a/32 prime
  done
  printf '%s' "$_jcsh_h"
}

# Shard index (0-based) this class belongs to for a given shard total.
journey_class_shard_index() {
  local _jcsi_total="$2"
  (( _jcsi_total > 0 )) || _jcsi_total=1
  printf '%s' "$(( $(journey_class_shard_hash "$1") % _jcsi_total ))"
}

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
    local _shard_class
    for _shard_class in "${JOURNEY_CLASSES[@]}"; do
      if (( $(journey_class_shard_hash "$_shard_class") % JOURNEY_CI_SHARD_TOTAL == JOURNEY_CI_SHARD_INDEX )); then
        EFFECTIVE_JOURNEY_CLASSES+=("$_shard_class")
      fi
    done
    echo ">>> CI journey shard ${JOURNEY_CI_SHARD_INDEX}/${JOURNEY_CI_SHARD_TOTAL} (issues #835, #1862): running ${#EFFECTIVE_JOURNEY_CLASSES[@]} of ${#JOURNEY_CLASSES[@]} journey classes (partitioned by class-name hash, so registering a journey does not re-roll the others); the core-terminal proofs are partitioned by the same hash (issue #2110)"
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
  echo "  (pocketshellCi=true; deterministic agents:2222 plus the existing Toxiproxy fixture for #1733)"
  echo "  (per-class retry-once for CI-AVD infra flakes — issue #712)"
  echo "=========================================================="
}
