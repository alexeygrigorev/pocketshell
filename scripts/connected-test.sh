#!/usr/bin/env bash
set -euo pipefail

# Issue #672 (option 1 + 2): lock-wrapped ad-hoc connected-test runner.
#
# Ad-hoc `./gradlew :app:connectedDebugAndroidTest` runs fired by implementers
# and reviewers never sourced scripts/lib/avd-lock.sh, so parallel agents
# SIGKILLed each other on the shared AVD. This thin wrapper:
#
#   1. Acquires the existing AVD lock (option 1) so siblings serialise
#      politely on a single emulator instead of racing. When ANDROID_SERIAL
#      is set it takes a *per-serial* lock (pool-ready) so distinct emulators
#      do not block each other; otherwise it takes the single global lock.
#   2. Threads the per-worktree applicationIdSuffix (option 2) into the gradle
#      invocation so each worktree's DEBUG apk installs under a distinct
#      applicationId (e.g. com.pocketshell.app.i672) and multiple test apps
#      coexist on ONE emulator without uninstalling each other.
#
# Usage:
#   scripts/connected-test.sh [--suffix <token>] [--pool] [gradle args...]
#   POCKETSHELL_APP_ID_SUFFIX=i672 scripts/connected-test.sh \
#     -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.SomeTest
#
#   scripts/connected-test.sh --cleanup-suffixes   # uninstall leftover
#                                                   # com.pocketshell.app.i* apps
#
# Flags:
#   --suffix <token>     Per-worktree applicationIdSuffix token (e.g. i672).
#                        Overrides POCKETSHELL_APP_ID_SUFFIX. Token must match
#                        [A-Za-z0-9._]+.  Default empty -> base package, so the
#                        wrapper is identical to a plain connectedDebugAndroidTest
#                        when no suffix is given.
#   --pool               AVD pool mode (issue #674): claim the first FREE
#                        emulator from the live pool (per-serial flock), export
#                        ANDROID_SERIAL so AGP/adb pin to it, run there, then
#                        release on exit. Without --pool the behaviour is
#                        unchanged: if ANDROID_SERIAL is preset it locks that
#                        serial, otherwise it takes the single global AVD lock.
#                        Pool emulators are booted via scripts/avd-pool.sh.
#   --cleanup-suffixes   Uninstall every accumulated com.pocketshell.app.i*
#                        (and .test) package from the target device, then exit.
#                        Prevents install pile-up across worktrees.
#
# Everything after the recognised flags is forwarded verbatim to gradle's
# :app:connectedDebugAndroidTest task (e.g. instrumentation-runner-argument
# filters). The base package (no suffix) and release build are never touched.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"

SUFFIX="${POCKETSHELL_APP_ID_SUFFIX:-}"
CLEANUP_ONLY=0
USE_POOL=0
GRADLE_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix)
      [[ $# -ge 2 ]] || { printf 'FAIL: --suffix needs a value\n' >&2; exit 2; }
      SUFFIX="$2"
      shift 2
      ;;
    --suffix=*)
      SUFFIX="${1#--suffix=}"
      shift
      ;;
    --pool)
      USE_POOL=1
      shift
      ;;
    --cleanup-suffixes)
      CLEANUP_ONLY=1
      shift
      ;;
    --)
      shift
      GRADLE_ARGS+=("$@")
      break
      ;;
    *)
      GRADLE_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ -n "$SUFFIX" ]]; then
  [[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || {
    printf 'FAIL: suffix must match [A-Za-z0-9._]+ (got: %s)\n' "$SUFFIX" >&2
    exit 2
  }
fi

# Pool mode (issue #674): claim the first FREE emulator from the live pool and
# export ANDROID_SERIAL so AGP/adb pin to it. The claim is held for the life of
# this process and released on exit. This must happen BEFORE the per-serial
# lock selection below so ANDROID_SERIAL is populated. When ANDROID_SERIAL is
# already preset (explicit target), honour it and skip the pool claim.
if [[ "$USE_POOL" == "1" && -z "${ANDROID_SERIAL:-}" ]]; then
  export ADB ANDROID_SDK
  # Called DIRECTLY (not via $(...)) so its exported ANDROID_SERIAL /
  # POCKETSHELL_POOL_* vars and the EXIT release trap land in THIS shell.
  if ! pocketshell_claim_pool_serial "$ROOT_DIR"; then
    printf 'FAIL: could not claim a free pool emulator. Is scripts/avd-pool.sh start running?\n' >&2
    exit 1
  fi
  printf 'Pool mode: running on %s\n' "${ANDROID_SERIAL:-?}" >&2
fi

# Per-serial lock when a specific emulator is targeted; otherwise the single
# global lock. This keeps single-emulator agents serialised (option 1) while
# distinct emulators (pool mode) do not block each other.
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
  export POCKETSHELL_AVD_LOCK_FILE
fi

cleanup_suffixed_packages() {
  local adb_target=("$ADB")
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_target=("$ADB" -s "$ANDROID_SERIAL")
  fi
  local pkg removed=0
  # Match the per-worktree convention com.pocketshell.app.i<token> (and its
  # .test sibling) ONLY. This deliberately excludes:
  #   * the base package        com.pocketshell.app
  #   * the base test package   com.pocketshell.app.test
  # so the sweep can never nuke a normal (non-suffixed) install's test app.
  # Worktree suffixes follow the `i<N>` convention (e.g. i672), so the token
  # must start with `i`.
  while IFS= read -r pkg; do
    [[ -n "$pkg" ]] || continue
    printf 'Uninstalling leftover suffixed package: %s\n' "$pkg" >&2
    "${adb_target[@]}" uninstall "$pkg" >/dev/null 2>&1 || true
    removed=$((removed + 1))
  done < <(
    "${adb_target[@]}" shell pm list packages 2>/dev/null \
      | sed 's/^package://' \
      | grep -E '^com\.pocketshell\.app\.i[A-Za-z0-9._]*(\.test)?$' || true
  )
  printf 'Cleanup sweep removed %s suffixed package(s).\n' "$removed" >&2
}

# Acquire the AVD lock for BOTH cleanup and test runs so the sweep cannot race
# a sibling's install/test on the same device.
pocketshell_acquire_avd_lock "$ROOT_DIR"

if [[ "$CLEANUP_ONLY" == "1" ]]; then
  cleanup_suffixed_packages
  exit 0
fi

GRADLE_SUFFIX_ARGS=()
if [[ -n "$SUFFIX" ]]; then
  GRADLE_SUFFIX_ARGS+=("-PpocketshellAppIdSuffix=$SUFFIX")
  printf 'Running connectedDebugAndroidTest as com.pocketshell.app.%s\n' "$SUFFIX" >&2
else
  printf 'Running connectedDebugAndroidTest as com.pocketshell.app (no suffix)\n' >&2
fi

# Serial pinning (issue #674): AGP's connected device provider installs +
# instruments on EVERY connected device by default. When more than one emulator
# is online (the pool), that would cross-install onto siblings and break them.
# AGP honours the `ANDROID_SERIAL` environment variable for its DeviceProvider:
# when set, it filters to exactly that one device. We exported it via the pool
# claim (or it was preset by the caller); re-export defensively so the gradle
# subprocess inherits it, and surface which device this run is pinned to.
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  export ANDROID_SERIAL
  printf 'Pinned to single device via ANDROID_SERIAL=%s (AGP DeviceProvider filters to it)\n' \
    "$ANDROID_SERIAL" >&2
else
  printf 'No ANDROID_SERIAL set: AGP will target the only connected device (single-emulator path)\n' >&2
fi

# Run gradle as a CHILD process, NOT via `exec`. `exec` would replace this shell
# and prevent bash from firing its EXIT trap (pocketshell_release_all), which
# orphans the backgrounded per-serial flock holder and keeps the pool claim held
# forever (issue #674: AC2 release-on-exit). Running gradle as a child lets the
# trap run on exit so the claimed pool serial + its flock holder are released.
#
# Forward SIGINT/SIGTERM to the gradle child so a Ctrl-C still tears it down;
# the EXIT trap then fires on our way out and releases the claim too.
GRADLE_PID=""
# Invoked indirectly via the INT/TERM traps below; shellcheck can't see that.
# shellcheck disable=SC2317
forward_signal() {
  local sig="$1"
  if [[ -n "$GRADLE_PID" ]]; then
    kill -s "$sig" "$GRADLE_PID" 2>/dev/null || true
  fi
}
trap 'forward_signal INT' INT
trap 'forward_signal TERM' TERM

./gradlew --no-daemon :app:connectedDebugAndroidTest \
  "${GRADLE_SUFFIX_ARGS[@]}" \
  "${GRADLE_ARGS[@]}" &
GRADLE_PID="$!"
wait "$GRADLE_PID"
rc=$?
exit "$rc"
