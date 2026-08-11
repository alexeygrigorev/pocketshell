#!/usr/bin/env bash

# ---------------------------------------------------------------------------
# Free-disk preflight for the canonical local gates (issue #1989)
#
# THE DEFECT this exists to stop coming back. During #1963 validation on the dev
# box the root filesystem filled completely (436G, 24M available, 100%) and two
# gates died BEFORE a single test ran:
#
#   * connected lane i1963green1 — Docker BuildKit could not write
#     ~/.docker/buildx/...: `no space left on device`; then Gradle could not
#     create ~/.gradle/caches/8.13/... and the build failed in 7s.
#   * scripts/full-jvm-gate.py — `:app:kspDebugKotlin` failed at 1m12s.
#
# Why that costs more than the disk: NONE of those is an assertion failure, but
# every one of them LOOKS like a broken gate. An ENOSPC failure is
# indistinguishable from a real gate failure until a human remembers to run
# `df`, so it burns a review round and gets misattributed to the change under
# test — the same "contention manufacturing evidence for a bug that is not
# there" shape as the #1842 fixture collision and the #2007 output-tree race.
# AGENTS.md already records the tell ("Gradle could not receive a message from
# the daemon -> check `df -h /` FIRST"). This makes the machine say it instead
# of relying on a human remembering.
#
# THE CONTRACT. Refuse to START, loudly and early, rather than fail confusingly
# halfway through. The preflight runs before any expensive shared resource is
# claimed (before the #2007 Gradle output-tree lock, before the emulator serial,
# before the agents fixture port) so a lane that cannot possibly succeed never
# sits on the box's scarcest resources while it discovers that.
#
# TWO HALVES THAT MUST AGREE. `scripts/connected-test.sh` sources this file;
# `scripts/full-jvm-gate.py` is an isolated Python program that deliberately
# does not source shell libraries, so it reimplements the identical arithmetic
# and the identical thresholds in Python. Both halves measure with statvfs
# semantics (`f_bavail * f_frsize`, i.e. space available to a non-root user) —
# `stat -f -c '%a %S'` here and `os.statvfs()` there — so the two wrappers can
# never disagree about whether the box has room.
# `tests/scripts/disk-preflight-test.sh` pins the two implementations against
# each other; if you change a threshold here, change it there in the same edit.
#
# MEASUREMENT FAILS CLOSED. If free space cannot be determined at all, the
# preflight refuses. An unmeasurable filesystem is exactly the state in which a
# silent pass is most dangerous, and on Linux `stat -f` does not fail on a real
# path — so a failure here means something is wrong that a gate should not
# paper over.
#
# THE THRESHOLD IS A KNOB, NOT A SWITCH. POCKETSHELL_DISK_MIN_FREE_MB moves the
# hard floor and POCKETSHELL_DISK_WARN_FREE_MB moves the advisory line; there is
# deliberately NO "skip the preflight" variable (same rule as the #2007 lock).
# A malformed value is a hard failure rather than a silent fallback to the
# default: a typo that quietly disables a guard is how guards become decoration.
# The knob exists so the regression harness is deterministic — a test that
# depends on the box's real free space proves nothing and flakes with the disk.
#
# WHY 10 GiB, MEASURED. The floor has to clear ONE RUN'S OWN WORKING SET, or it
# certifies a run that then dies of the very thing it was checked for — a rubber
# stamp, which is worse than no preflight because the ENOSPC now carries a "disk
# preflight OK" line above it.
#
# Measured on the dev box (2026-08-06), a completed gate's build output in a
# single worktree:
#     app/build                 2.7 GiB
#     all module build/ dirs    3.1 GiB total
# and that is before the Gradle daemon's temp, ~/.gradle/caches growth, and (for
# a connected lane) Docker image + BuildKit layers. A run admitted at exactly
# 4 GiB therefore has under 1 GiB of headroom for its own 3.1 GiB of output.
# 10 GiB is ~3x the measured working set.
#
# An earlier revision of this file set the floor to 4 GiB on the premise that
# "these wrappers also run on hosted CI runners where free space is tight
# mid-job". THAT PREMISE IS FALSE, and it is recorded here so it is not
# rediscovered as a reason to lower the floor again:
#   * This gate's real path NEVER runs on a hosted runner. .github/workflows/
#     tests.yml invokes scripts/full-jvm-gate.py only as --profile-guard-self-test
#     and --profile-guard-check (both exempt from this preflight, both building
#     nothing), and the real path additionally refuses to run with CI set.
#   * The one CI exposure is connected-test.sh in the emulator job, and that job
#     measured 110134 MB free after its own cleanup step (main run 31040932815,
#     2026-08-05). The same step ALREADY hard-fails the job below 9000 MB for the
#     AVD userdata partition, so a 10240 MiB floor sits barely above a bar CI
#     enforces for itself and cannot be the thing that false-positives.
#
# The 20 GiB WARN line carries the "clean up before root reaches 100%" half of
# the issue. That is where an operator still has room to act; it is advisory and
# never blocks, so it can sit high without ever breaking a run.
#
# Usage as a library:
#   source scripts/lib/disk-preflight.sh
#   pocketshell_disk_preflight "$ROOT_DIR" "connected-test.sh" || exit $?
#
# Usage as a command (what the harness drives):
#   scripts/lib/disk-preflight.sh --path DIR [--label TEXT]
#   scripts/lib/disk-preflight.sh --path DIR --print-free-mb
#   scripts/lib/disk-preflight.sh --print-min-free-mb
#   scripts/lib/disk-preflight.sh --print-warn-free-mb
# ---------------------------------------------------------------------------

# Distinct from a build failure, from the #2007 output-lock timeout (75), and
# from the #1842 disturbed-fixture verdict (90), so "the box was full" is never
# read as "the change under test is red".
POCKETSHELL_DISK_PREFLIGHT_FAIL_RC=76

# Keep byte-identical to full-jvm-gate.py's DISK_PREFLIGHT_* constants.
POCKETSHELL_DISK_PREFLIGHT_DEFAULT_MIN_FREE_MB=10240
POCKETSHELL_DISK_PREFLIGHT_DEFAULT_WARN_FREE_MB=20480

# Read one threshold override. A missing/empty value takes the default; a
# malformed value is fatal (never a silent fallback — see the header).
_pocketshell_disk_threshold_mb() {
  local name="$1"
  local default_value="$2"
  local raw="${!name-}"
  if [[ -z "$raw" ]]; then
    printf '%s\n' "$default_value"
    return 0
  fi
  if [[ ! "$raw" =~ ^[0-9]+$ ]]; then
    printf 'FAIL: %s must be a whole number of MiB (got: %s)\n' "$name" "$raw" >&2
    return 1
  fi
  printf '%s\n' "$raw"
}

pocketshell_disk_min_free_mb() {
  _pocketshell_disk_threshold_mb \
    POCKETSHELL_DISK_MIN_FREE_MB \
    "$POCKETSHELL_DISK_PREFLIGHT_DEFAULT_MIN_FREE_MB"
}

pocketshell_disk_warn_free_mb() {
  _pocketshell_disk_threshold_mb \
    POCKETSHELL_DISK_WARN_FREE_MB \
    "$POCKETSHELL_DISK_PREFLIGHT_DEFAULT_WARN_FREE_MB"
}

# Free MiB on the filesystem holding <path>, using statvfs semantics so the
# Python half of this preflight measures the identical quantity.
pocketshell_disk_free_mb() {
  local path="$1"
  local blocks_and_size available_blocks block_size
  if ! blocks_and_size="$(stat -f -c '%a %S' -- "$path" 2>/dev/null)"; then
    printf 'FAIL: cannot determine free space for %s\n' "$path" >&2
    return 1
  fi
  read -r available_blocks block_size <<< "$blocks_and_size"
  if [[ ! "$available_blocks" =~ ^[0-9]+$ || ! "$block_size" =~ ^[0-9]+$ ]]; then
    printf 'FAIL: unreadable free-space report for %s: %s\n' "$path" "$blocks_and_size" >&2
    return 1
  fi
  printf '%s\n' $(( available_blocks * block_size / 1048576 ))
}

# pocketshell_disk_preflight <path> <label>
#
# Returns 0 when the filesystem holding <path> has at least the minimum free
# space (printing an advisory WARN line when it is under the warn threshold),
# and $POCKETSHELL_DISK_PREFLIGHT_FAIL_RC when it does not or cannot be
# measured. Never starts, cleans, or deletes anything itself: the operator (or
# scripts/disk-cleanup.sh) decides what to remove.
pocketshell_disk_preflight() {
  local path="$1"
  local label="${2:-canonical gate}"
  local min_free_mb warn_free_mb free_mb

  min_free_mb="$(pocketshell_disk_min_free_mb)" || return "$POCKETSHELL_DISK_PREFLIGHT_FAIL_RC"
  warn_free_mb="$(pocketshell_disk_warn_free_mb)" || return "$POCKETSHELL_DISK_PREFLIGHT_FAIL_RC"
  free_mb="$(pocketshell_disk_free_mb "$path")" || {
    printf 'FAIL: %s refuses to start without a readable free-space report for %s (issue #1989).\n' \
      "$label" "$path" >&2
    return "$POCKETSHELL_DISK_PREFLIGHT_FAIL_RC"
  }

  if (( free_mb < min_free_mb )); then
    {
      printf '\n'
      printf '=== DISK PREFLIGHT FAILED (issue #1989) ===\n'
      printf '%s refuses to start: not enough free disk space.\n' "$label"
      printf '  path:      %s\n' "$path"
      printf '  free:      %s MiB\n' "$free_mb"
      printf '  required:  %s MiB\n' "$min_free_mb"
      printf '  filesystem usage:\n'
      df -h -- "$path" 2>/dev/null | sed 's/^/    /' >&2
      printf '\n'
      printf 'This run did NOT start; nothing was built. A gate that starts on a full\n'
      printf 'disk fails with ENOSPC deep inside Gradle/Docker/KSP, which reads exactly\n'
      printf 'like a real test failure and gets blamed on the change under test.\n'
      printf '\n'
      printf 'Reclaim space with the serialized, safe-list cleanup path:\n'
      printf '    scripts/disk-cleanup.sh            # dry run: shows what it WOULD free\n'
      printf '    scripts/disk-cleanup.sh --apply    # actually free it\n'
      printf 'It never touches active containers, .gradle/caches, .android/avd,\n'
      printf 'pocketshell-test:* images, .worktrees/issue-*, or any worktree holding\n'
      printf 'uncommitted or unpushed work.\n'
      printf '\n'
      printf 'To run against a different floor (there is deliberately no way to skip\n'
      printf 'this check): POCKETSHELL_DISK_MIN_FREE_MB=<MiB>\n'
    } >&2
    return "$POCKETSHELL_DISK_PREFLIGHT_FAIL_RC"
  fi

  if (( free_mb < warn_free_mb )); then
    printf 'WARN: disk preflight (issue #1989): %s MiB free on %s is under the %s MiB comfort line (hard floor %s MiB). Run scripts/disk-cleanup.sh before the box reaches 100%%.\n' \
      "$free_mb" "$path" "$warn_free_mb" "$min_free_mb" >&2
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Command mode
# ---------------------------------------------------------------------------
_pocketshell_disk_preflight_usage() {
  cat >&2 <<'USAGE'
Free-disk preflight for the canonical local gates (issue #1989).

Usage:
  scripts/lib/disk-preflight.sh --path <dir> [--label <text>]
  scripts/lib/disk-preflight.sh --path <dir> --print-free-mb
  scripts/lib/disk-preflight.sh --print-min-free-mb
  scripts/lib/disk-preflight.sh --print-warn-free-mb

Exits 0 when the filesystem holding <dir> has at least
POCKETSHELL_DISK_MIN_FREE_MB (default 10240) MiB free, and 76 when it does not
or cannot be measured. There is deliberately no switch that skips the check.
USAGE
}

_pocketshell_disk_preflight_main() {
  local path="" label="disk-preflight.sh" mode="check"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help | -h)
        _pocketshell_disk_preflight_usage
        return 0
        ;;
      --path)
        [[ $# -ge 2 ]] || { printf 'FAIL: --path needs a value\n' >&2; return 2; }
        path="$2"
        shift 2
        ;;
      --path=*)
        path="${1#--path=}"
        shift
        ;;
      --label)
        [[ $# -ge 2 ]] || { printf 'FAIL: --label needs a value\n' >&2; return 2; }
        label="$2"
        shift 2
        ;;
      --label=*)
        label="${1#--label=}"
        shift
        ;;
      --print-free-mb)
        mode="print-free"
        shift
        ;;
      --print-min-free-mb)
        mode="print-min"
        shift
        ;;
      --print-warn-free-mb)
        mode="print-warn"
        shift
        ;;
      *)
        printf 'FAIL: unexpected argument %s\n' "$1" >&2
        _pocketshell_disk_preflight_usage
        return 2
        ;;
    esac
  done

  case "$mode" in
    print-min)
      pocketshell_disk_min_free_mb || return "$POCKETSHELL_DISK_PREFLIGHT_FAIL_RC"
      return 0
      ;;
    print-warn)
      pocketshell_disk_warn_free_mb || return "$POCKETSHELL_DISK_PREFLIGHT_FAIL_RC"
      return 0
      ;;
  esac

  if [[ -z "$path" ]]; then
    printf 'FAIL: --path is required\n' >&2
    return 2
  fi

  if [[ "$mode" == "print-free" ]]; then
    pocketshell_disk_free_mb "$path" || return "$POCKETSHELL_DISK_PREFLIGHT_FAIL_RC"
    return 0
  fi

  local rc=0
  pocketshell_disk_preflight "$path" "$label" || rc=$?
  return "$rc"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  set -uo pipefail
  _pocketshell_disk_preflight_main "$@"
  exit $?
fi
