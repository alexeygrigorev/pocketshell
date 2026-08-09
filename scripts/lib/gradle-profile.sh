#!/usr/bin/env bash

# Issue #2054: ONE source of truth for the Gradle execution profile the local
# release / pre-release gate builds with, plus a fail-fast assertion that the
# profile actually carries its heap bounds.
#
# WHY this exists
# ---------------
# The release gate maintained its own Gradle flag string that had drifted away
# from the canonical profile the rest of the repo uses:
#
#   scripts/pre-release-confidence-gate.sh:23
#     GRADLE_FLAGS="--no-daemon --no-build-cache --no-parallel --max-workers=2"
#
# No `-Pkotlin.daemon.jvmargs`, no `-Dorg.gradle.jvmargs`, and TWO workers. The
# repo's `gradle.properties` sets `org.gradle.jvmargs=-Xmx2048m` and NO
# `kotlin.daemon.jvmargs` at all, so the Kotlin daemon inherited a 2 GiB heap and
# two compile tasks overlapped inside it. Combined with `scripts/lib/scope-run.sh`'s
# 8 GiB `MemoryMax` default, the v0.4.42 release validation died three times in
# the BUILD, before a single product assertion ran:
#
#   20260808-162416  OOMErrorException "Not enough memory to run compilation.
#                    Try to increase it via 'gradle.properties':
#                    kotlin.daemon.jvmargs=-Xmx<size>"   (18m59s in)
#   20260809-v0442   OutOfMemoryError: Java heap space in
#                    com.android.zipflinger.Compressor.deflate on :app:packageDebug
#                    (after stage 03 spent 43m58s thrashing the same ceiling)
#   20260809-v0442-r2 OutOfMemoryError: GC overhead limit exceeded ->
#                    OOMErrorException on :app:compile{Debug,Release}UnitTestKotlin
#                    (391s in)
#
# The box had ~44 GiB free at the time; the binding limit was the gate's own
# profile, not the machine.
#
# READ THIS BEFORE "FIXING" A GATE OOM BY RAISING AN OUTER MemoryMax
# ------------------------------------------------------------------
# An outer `systemd-run --user -p MemoryMax=...` around the release wrapper is
# COSMETIC. Every heavy step re-enters `scripts/cgroup-run.sh`, which creates its
# OWN transient sibling scope via `scripts/lib/scope-run.sh` under `robust.slice`
# — a sibling, NOT a child, of the wrapper's scope. Only the INNER value
# (`POCKETSHELL_TEST_MEM`) binds the compile. That is why raising the outer cap
# changed nothing across two runs.
#
# And raising the cgroup alone is never enough either: a Kotlin daemon that was
# never told `-Xmx` stays on its inherited 2 GiB heap no matter how much room the
# cgroup has. Both halves — the JVM heap flags AND the scope ceiling — are
# required, which is exactly what this file pins.
#
# WHAT IS PINNED
# --------------
# `POCKETSHELL_GRADLE_RESOURCE_ARGS` is the resource half of the EFFECTIVE
# profile and is spliced into every release-chain Gradle invocation. Callers keep
# their own daemon/cache choices (`--no-daemon`, `--no-build-cache`) and add
# these.
#
# TWO PROFILES, AND THE HEAP MUST BE PAIRED WITH THE SCOPE
# ---------------------------------------------------------
# There are exactly two supported shapes, and each one's heap half is only valid
# next to its own scope half. Handing the LOCAL heaps to the HOSTED scope is the
# same defect as handing the HOSTED scope the unbounded heaps — both halves move
# together or neither does:
#
#   LOCAL  (dev box, >= 20 GiB free)   3072m launcher / 3072m daemon / 24G scope
#   HOSTED (16 GiB GitHub runner)      1536m launcher / 3072m daemon /  8G scope
#
# The hosted numbers are NOT ours to choose: scripts/check-release-emulator-memory-budget.sh
# hard-fails anything but `-Dorg.gradle.jvmargs=-Xmx1536m` and
# `POCKETSHELL_TEST_MEM=8G` for the hosted chain (#1724). They are mirrored here
# so the four scripts that splice the array cannot land a launcher heap that
# guard forbids, and scripts/check-release-gate-execution-profile.sh reads the
# hosted workflow and asserts the two guards still agree.
#
# Heap sizing rationale:
#   -Pkotlin.daemon.jvmargs=-Xmx3072m  the canonical value from
#       scripts/full-jvm-gate.sh, proven on the complete `test --rerun-tasks`
#       graph. Kept exactly, in BOTH profiles.
#   -Dorg.gradle.jvmargs=-Xmx3072m     (LOCAL only) ABOVE the canonical 1536m on
#       purpose. The canonical gate only runs `test`; the release chain also runs
#       assembleDebug/packageDebug, and APK packaging (zipflinger's
#       Compressor.deflate) allocates in the GRADLE process, not the Kotlin
#       daemon. Run 20260809-v0442 proves 2048m is not enough there. The hosted
#       runner keeps 1536m because it only has 16 GiB total and an 8G build
#       scope; a bigger launcher heap there buys a swap-thrash, not headroom.
#   --max-workers=1                    two Kotlin Worker API sessions overlapping
#       inside one daemon heap is the #1761 OOM shape and is what run r2 hit with
#       --max-workers=2 (compileDebugUnitTestKotlin + compileReleaseUnitTestKotlin
#       failing together). Serialise. Both profiles.
#
# SELECTION ORDER (identical for both halves, so they cannot desynchronise):
#   1. an explicit caller value wins — GRADLE_FLAGS for the heaps,
#      POCKETSHELL_TEST_MEM for the scope (the hosted workflow sets both);
#   2. otherwise the LOCAL profile when the local floor applies;
#   3. otherwise the HOSTED profile.
#
# USAGE — and WHERE to call each of the two assertions
# -----------------------------------------------------
#   source scripts/lib/gradle-profile.sh
#   GRADLE_FLAGS="${GRADLE_FLAGS:-$(pocketshell_release_gate_gradle_flags)}"
#
#   # (a) FLAG assertion: safe at script load. The flag string is a CONSTANT of
#   #     the script, so this can never false-fail and it records the profile in
#   #     every run's log.
#   pocketshell_assert_gradle_execution_profile "pre-release gate" "$GRADLE_FLAGS"
#
#   # (b) SCOPE assertion: call it at the POINT OF USE — immediately before the
#   #     heavy Gradle invocation, inside whatever `if` decides to build.
#   pocketshell_apply_release_gate_scope_memory "pre-release gate"
#   ./gradlew --no-daemon "${POCKETSHELL_GRADLE_RESOURCE_ARGS[@]}" :app:assembleDebug
#
# The asymmetry is deliberate and was learned the hard way. `POCKETSHELL_TEST_MEM`
# is AMBIENT ENVIRONMENT, not a constant, and `scripts/full-jvm-gate.sh` exports
# `POCKETSHELL_TEST_MEM=8G` (its MEMORY_LIMIT) into every JVM unit test. Several
# unit tests drive these scripts through `tests/scripts/*.sh` harnesses in modes
# that never build anything (`--help`, `PHONE_WALKTHROUGH_VERIFY_DISPATCH_ONLY=1`),
# so a 20G floor asserted at script LOAD time hard-fails those harnesses.
#
# Worse, it fails ASYMMETRICALLY: the floor is CI-exempt (hosted runners have
# ~16 GiB), so a load-time floor is RED in the local canonical gate and GREEN on
# GitHub — it would sail through the required PR checks and break every local
# release gate. Asserting a build resource only where a build actually happens
# removes the whole class. Do not "fix" a harness failure by relaxing the floor
# or by adding a bypass variable; move the call to the point of use.

# Canonical floors and the two profiles' constants.
#
# Every assignment in this block is deliberately a PLAIN assignment, not
# `${VAR:-default}`. An environment-overridable floor is a bypass channel for the
# exact failure this file exists to stop (`export
# POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB=0` and the guard waves the 2048m heap
# through), and it also makes the guard's own mutation self-test vacuous: a
# preset variable means a mutated default is never read. The real, intended
# override knobs are POCKETSHELL_TEST_MEM and GRADLE_FLAGS.
#
# This is not a comment-only convention. `check_no_env_overridable_constants` in
# scripts/check-release-gate-execution-profile.sh presets every one of these
# names to a bypass value, re-sources this file in a FRESH interpreter, and
# requires the shipped values back — so reverting any single line to the
# `${VAR:-default}` form is a hard RED.
POCKETSHELL_GRADLE_LAUNCHER_HEAP_FLOOR_MIB=1536
POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB=3072

# Local clean release build scope floor + default (issue #2054 proposal item 2).
# The maintainer verified 24G completes the gate; 20G is the documented minimum
# for a clean Kotlin compile (AGENTS.md, "MemoryMax below ~20G OOMs the Kotlin
# daemon"). Hosted CI keeps its own smaller pinned budget — see
# pocketshell_release_scope_floor_applies below.
POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB=20
POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT=24G

# The hosted (16 GiB GitHub runner) half. Mirrors what
# scripts/check-release-emulator-memory-budget.sh hard-requires for
# .github/workflows/release-emulator-validation.yml; the two guards are pinned
# against each other so they can never drift into asserting opposite profiles.
POCKETSHELL_RELEASE_GATE_HOSTED_SCOPE_MEM=8G

POCKETSHELL_GRADLE_LOCAL_RESOURCE_ARGS=(
  --no-parallel
  --max-workers=1
  -Dorg.gradle.jvmargs=-Xmx3072m
  -Pkotlin.daemon.jvmargs=-Xmx3072m
)

POCKETSHELL_GRADLE_HOSTED_RESOURCE_ARGS=(
  --no-parallel
  --max-workers=1
  -Dorg.gradle.jvmargs=-Xmx1536m
  -Pkotlin.daemon.jvmargs=-Xmx3072m
)

# Print, one per line, the RESOURCE tokens of a Gradle flag string. Everything
# else a caller puts in GRADLE_FLAGS (daemon/cache choices, task selection,
# --stacktrace) is not this file's business; only the tokens that decide how much
# memory the build may use are.
pocketshell_gradle_resource_tokens() {
  local -a tokens=()
  read -r -a tokens <<< "${1-}"
  local token
  for token in "${tokens[@]}"; do
    case "$token" in
      --parallel | --no-parallel | --max-workers=* | -Dorg.gradle.jvmargs=* | -Pkotlin.daemon.jvmargs=*)
        printf '%s\n' "$token"
        ;;
    esac
  done
}

# The resource half this machine should build with. See SELECTION ORDER above.
# `--parallel` is deliberately in the extracted set: a caller who re-enables it
# must be REJECTED by the assertion, not silently filtered into compliance.
pocketshell_effective_gradle_resource_args() {
  local -a derived=()
  if [[ -n "${GRADLE_FLAGS:-}" ]]; then
    mapfile -t derived < <(pocketshell_gradle_resource_tokens "$GRADLE_FLAGS")
  fi
  if [[ "${#derived[@]}" -gt 0 ]]; then
    printf '%s\n' "${derived[@]}"
  elif pocketshell_release_scope_floor_applies; then
    printf '%s\n' "${POCKETSHELL_GRADLE_LOCAL_RESOURCE_ARGS[@]}"
  else
    printf '%s\n' "${POCKETSHELL_GRADLE_HOSTED_RESOURCE_ARGS[@]}"
  fi
}

# The build-scope ceiling this machine should build with — the other half of the
# same selection, kept in the same shape so the two cannot desynchronise.
pocketshell_effective_release_scope_memory() {
  if [[ -n "${POCKETSHELL_TEST_MEM:-}" ]]; then
    printf '%s' "$POCKETSHELL_TEST_MEM"
  elif pocketshell_release_scope_floor_applies; then
    printf '%s' "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT"
  else
    printf '%s' "$POCKETSHELL_RELEASE_GATE_HOSTED_SCOPE_MEM"
  fi
}

# The full default flag string for a gate that owns its whole `GRADLE_FLAGS`.
# Only reached when the caller did NOT set GRADLE_FLAGS, so it prints the
# machine-appropriate profile rather than always the local one.
pocketshell_release_gate_gradle_flags() {
  local -a args=()
  mapfile -t args < <(pocketshell_effective_gradle_resource_args)
  printf '%s' "--no-daemon --no-build-cache ${args[*]}"
}

# Parse a JVM `-Xmx<size>` value into MiB. Prints the MiB count and returns 0 on
# success; prints nothing and returns 1 when the value is absent/unparseable.
pocketshell_parse_xmx_mib() {
  local value="$1"
  local match
  match="$(printf '%s' "$value" | grep -oE -- '-Xmx[0-9]+[kKmMgGtT]?' | tail -n 1 || true)"
  [[ -n "$match" ]] || return 1
  local digits unit
  digits="$(printf '%s' "${match#-Xmx}" | tr -dc '0-9')"
  unit="$(printf '%s' "${match#-Xmx}" | tr -dc 'kKmMgGtT')"
  [[ -n "$digits" ]] || return 1
  case "${unit^^}" in
    "") printf '%s' $((digits / 1024 / 1024)) ;;
    K) printf '%s' $((digits / 1024)) ;;
    M) printf '%s' "$digits" ;;
    G) printf '%s' $((digits * 1024)) ;;
    T) printf '%s' $((digits * 1024 * 1024)) ;;
    *) return 1 ;;
  esac
}

# Parse a systemd size string (`24G`, `8G`, `20480M`, raw bytes) into GiB,
# rounding DOWN so a floor check can never be satisfied by rounding up.
pocketshell_parse_size_gib() {
  local value="$1"
  [[ "$value" =~ ^([0-9]+)([A-Za-z]*)$ ]] || return 1
  local num="${BASH_REMATCH[1]}"
  local unit="${BASH_REMATCH[2]}"
  case "${unit^^}" in
    "" | B) printf '%s' $((num / 1024 / 1024 / 1024)) ;;
    K | KB | KIB) printf '%s' $((num / 1024 / 1024)) ;;
    M | MB | MIB) printf '%s' $((num / 1024)) ;;
    G | GB | GIB) printf '%s' "$num" ;;
    T | TB | TIB) printf '%s' $((num * 1024)) ;;
    *) return 1 ;;
  esac
}

_pocketshell_profile_fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Issue #2054: the release gate must run Gradle with bounded, explicit heaps.\n' >&2
  printf 'Canonical profile: %s\n' "$(pocketshell_release_gate_gradle_flags)" >&2
  return 1
}

# pocketshell_assert_gradle_execution_profile <label> <flag string>
#
# Hard-fails BEFORE Gradle starts when the flag string is missing either heap
# bound, carries a heap below the canonical floor, or re-enables parallel
# compilation. Cheap: pure string work, no Gradle, no SDK, no daemon.
pocketshell_assert_gradle_execution_profile() {
  local label="$1"
  local flags="${2-}"
  local -a tokens=()
  read -r -a tokens <<< "$flags"

  local gradle_heap_token="" kotlin_heap_token="" workers_token=""
  local gradle_heap_count=0 kotlin_heap_count=0 workers_count=0
  local token
  for token in "${tokens[@]}"; do
    case "$token" in
      --parallel)
        _pocketshell_profile_fail "$label re-enables parallel project execution (--parallel); release-chain compilation must stay serialized" || return 1
        ;;
      --max-workers=*)
        workers_token="$token"
        workers_count=$((workers_count + 1))
        ;;
      -Dorg.gradle.jvmargs=*)
        gradle_heap_token="$token"
        gradle_heap_count=$((gradle_heap_count + 1))
        ;;
      -Pkotlin.daemon.jvmargs=*)
        kotlin_heap_token="$token"
        kotlin_heap_count=$((kotlin_heap_count + 1))
        ;;
    esac
  done

  if [[ "$gradle_heap_count" -eq 0 ]]; then
    _pocketshell_profile_fail "$label does not pass -Dorg.gradle.jvmargs=-Xmx<size>; the Gradle process would inherit gradle.properties' 2048m and OOM in :app:packageDebug (zipflinger)" || return 1
  fi
  if [[ "$kotlin_heap_count" -eq 0 ]]; then
    _pocketshell_profile_fail "$label does not pass -Pkotlin.daemon.jvmargs=-Xmx<size>; the Kotlin daemon would inherit a 2048m heap and OOM in :app:compile*Kotlin" || return 1
  fi
  if [[ "$gradle_heap_count" -gt 1 || "$kotlin_heap_count" -gt 1 || "$workers_count" -gt 1 ]]; then
    _pocketshell_profile_fail "$label passes a duplicated/conflicting resource flag (gradle-heap=$gradle_heap_count kotlin-heap=$kotlin_heap_count max-workers=$workers_count); the last one silently wins" || return 1
  fi

  local gradle_mib kotlin_mib
  if ! gradle_mib="$(pocketshell_parse_xmx_mib "$gradle_heap_token")"; then
    _pocketshell_profile_fail "$label has an unparseable Gradle heap bound: $gradle_heap_token" || return 1
  fi
  if ! kotlin_mib="$(pocketshell_parse_xmx_mib "$kotlin_heap_token")"; then
    _pocketshell_profile_fail "$label has an unparseable Kotlin daemon heap bound: $kotlin_heap_token" || return 1
  fi
  if [[ "$gradle_mib" -lt "$POCKETSHELL_GRADLE_LAUNCHER_HEAP_FLOOR_MIB" ]]; then
    _pocketshell_profile_fail "$label Gradle heap ${gradle_mib}m is below the ${POCKETSHELL_GRADLE_LAUNCHER_HEAP_FLOOR_MIB}m floor" || return 1
  fi
  if [[ "$kotlin_mib" -lt "$POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB" ]]; then
    _pocketshell_profile_fail "$label Kotlin daemon heap ${kotlin_mib}m is below the ${POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB}m floor" || return 1
  fi
  if [[ "$workers_count" -eq 0 || "$workers_token" != "--max-workers=1" ]]; then
    _pocketshell_profile_fail "$label must pin --max-workers=1 (got '${workers_token:-none}'); overlapping Kotlin worker sessions share one daemon heap" || return 1
  fi

  printf 'Gradle execution profile OK for %s: gradle-heap=%sm kotlin-daemon-heap=%sm %s\n' \
    "$label" "$gradle_mib" "$kotlin_mib" "$workers_token"
}

# The >=20 GiB scope floor is a LOCAL rule. Hosted runners have ~16 GiB total and
# deliberately pin POCKETSHELL_TEST_MEM=8G with the single-worker/split-heap
# profile (.github/workflows/release-emulator-validation.yml, guarded by
# scripts/check-release-emulator-memory-budget.sh). Applying the local floor there
# would fail every hosted release validation.
pocketshell_release_scope_floor_applies() {
  case "${CI:-}" in
    "" | 0 | false | FALSE | no | NO) return 0 ;;
    *) return 1 ;;
  esac
}

# pocketshell_assert_release_scope_memory <label> <value>
pocketshell_assert_release_scope_memory() {
  local label="$1"
  local value="$2"
  local gib
  if ! gib="$(pocketshell_parse_size_gib "$value")"; then
    _pocketshell_profile_fail "$label has an unparseable POCKETSHELL_TEST_MEM: $value" || return 1
  fi
  if pocketshell_release_scope_floor_applies &&
    [[ "$gib" -lt "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB" ]]; then
    _pocketshell_profile_fail "$label build scope MemoryMax=$value is below the ${POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB}G floor for a clean Kotlin compile; export POCKETSHELL_TEST_MEM=${POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT} or larger" || return 1
  fi
  printf 'Release build scope OK for %s: MemoryMax=%s (floor %sG, applies=%s)\n' \
    "$label" "$value" "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB" \
    "$(pocketshell_release_scope_floor_applies && printf 'yes' || printf 'no (CI)')"
}

# Export the release build-scope ceiling that scripts/cgroup-run.sh ->
# scripts/lib/scope-run.sh will apply to every heavy step, then assert it.
# An explicit POCKETSHELL_TEST_MEM from the caller/workflow always wins; a hosted
# runner with no explicit value gets the hosted 8G budget, never the local 24G
# (a 16 GiB runner cannot honour 24G, and forcing it there would be the same
# heap-without-scope mismatch this file exists to prevent, just inverted).
#
# CALL THIS AT THE POINT OF USE, not at script load — see the asymmetry note at
# the top of this file.
pocketshell_apply_release_gate_scope_memory() {
  local label="$1"
  local effective
  effective="$(pocketshell_effective_release_scope_memory)"
  export POCKETSHELL_TEST_MEM="$effective"
  pocketshell_assert_release_scope_memory "$label" "$POCKETSHELL_TEST_MEM"
}

# ---------------------------------------------------------------------------
# Load-time derivation of the EFFECTIVE resource half. This is a value
# derivation, never an assertion: sourcing this file must stay safe in a
# `--help` / dispatch-only run (see the asymmetry note at the top).
# ---------------------------------------------------------------------------
# shellcheck disable=SC2034 # consumed by the scripts that source this file.
POCKETSHELL_GRADLE_RESOURCE_ARGS=()
# shellcheck disable=SC2034 # consumed by the scripts that source this file.
mapfile -t POCKETSHELL_GRADLE_RESOURCE_ARGS < <(pocketshell_effective_gradle_resource_args)
