#!/usr/bin/env bash
# Fast local debug APK. This is NOT the release/visual-audit profile.
#
# The gate wrappers (`capture-walkthrough-screenshots.sh`, phone-walkthrough,
# pre-release confidence) use --no-daemon --no-build-cache --max-workers=1 so a
# release APK is reproducible and cannot OOM the box. That profile is the wrong
# default for "put this build on the phone": it throws away the Gradle daemon
# and the build cache, serialises every module, and compiles all four native
# ABIs plus androidTest.
#
# This script keeps the daemon, keeps the cache, raises the Kotlin heap, and
# optionally compiles only the connected device ABI.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/scope-run.sh"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/alexey/Android/Sdk}}}"
ADB="${ASSEMBLE_DEBUG_ADB:-${ADB:-$ANDROID_SDK/platform-tools/adb}}"
ABI_MODE="auto"
WITH_ANDROID_TEST=0
INSTALL=0
PRINT_COMMAND=0
ALLOWED_ABIS='arm64-v8a|armeabi-v7a|x86|x86_64'

usage() {
  cat <<'USAGE'
Usage: scripts/assemble-debug.sh [options]

Fast local :app2:assembleDebug. Keeps the Gradle daemon and build cache.

Options:
  --abi auto|all|<abi>  Native ABI to compile (default: auto).
                        auto = the connected device's ABI when exactly one
                        device (or ANDROID_SERIAL) is available, otherwise all.
  --android-test        Also assemble the androidTest APK.
  --install             adb install -r the debug APK after a successful build.
  --print-command       Print the Gradle command and exit (no build).
  -h, --help            Show this help.

Environment:
  ANDROID_SDK / ANDROID_HOME / ANDROID_SDK_ROOT
  ADB / ASSEMBLE_DEBUG_ADB
  ANDROID_SERIAL          pin which device --abi auto and --install use
  POCKETSHELL_TEST_MEM    cgroup MemoryMax (default 24G for this script)
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --abi)
      [[ $# -ge 2 ]] || fail "--abi needs a value"
      ABI_MODE="$2"
      shift 2
      ;;
    --abi=*)
      ABI_MODE="${1#--abi=}"
      shift
      ;;
    --android-test)
      WITH_ANDROID_TEST=1
      shift
      ;;
    --install)
      INSTALL=1
      shift
      ;;
    --print-command)
      PRINT_COMMAND=1
      shift
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

adb_cmd() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    "$ADB" -s "$ANDROID_SERIAL" "$@"
  else
    "$ADB" "$@"
  fi
}

connected_serials() {
  # `adb devices` header plus optional trailing blank line. Missing adb, or
  # no devices, must fall through to "all ABIs" rather than abort --print-command.
  adb_cmd devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }' || true
}

device_abi() {
  adb_cmd shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true
}

resolve_abi_filter() {
  local mode="$1"
  local serials serial count abi
  case "$mode" in
    all)
      return 1
      ;;
    auto)
      mapfile -t serials < <(connected_serials)
      count="${#serials[@]}"
      if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        abi="$(device_abi)"
      elif [[ "$count" -eq 1 ]]; then
        serial="${serials[0]}"
        abi="$("$ADB" -s "$serial" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
      else
        return 1
      fi
      [[ -n "$abi" ]] || return 1
      printf '%s\n' "$abi"
      return 0
      ;;
    *)
      printf '%s\n' "$mode"
      return 0
      ;;
  esac
}

ABI_FILTER=""
if ABI_FILTER="$(resolve_abi_filter "$ABI_MODE")"; then
  [[ "$ABI_FILTER" =~ ^($ALLOWED_ABIS)$ ]] || \
    fail "unsupported ABI '$ABI_FILTER' (want $ALLOWED_ABIS)"
else
  ABI_FILTER=""
fi

GRADLE_ARGS=(
  --parallel
  --max-workers=4
  -Dorg.gradle.jvmargs=-Xmx3072m
  -Pkotlin.daemon.jvmargs=-Xmx3072m
  :app2:assembleDebug
)
if [[ "$WITH_ANDROID_TEST" -eq 1 ]]; then
  GRADLE_ARGS+=(:app2:assembleDebugAndroidTest)
fi
if [[ -n "$ABI_FILTER" ]]; then
  GRADLE_ARGS+=(-PpocketshellAbiFilters="$ABI_FILTER")
fi
GRADLE_ARGS+=(--stacktrace)

if [[ "$PRINT_COMMAND" -eq 1 ]]; then
  printf './gradlew'
  printf ' %q' "${GRADLE_ARGS[@]}"
  printf '\n'
  exit 0
fi

export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
export POCKETSHELL_TEST_MEM="${POCKETSHELL_TEST_MEM:-24G}"

printf 'PocketShell local debug APK\n'
printf '  abi: %s\n' "${ABI_FILTER:-all}"
printf '  androidTest: %s\n' "$([[ "$WITH_ANDROID_TEST" -eq 1 ]] && echo yes || echo no)"
printf '  MemoryMax: %s\n' "$POCKETSHELL_TEST_MEM"

start_seconds="$(date +%s)"
"$ROOT_DIR/scripts/cgroup-run.sh" --unit "pocketshell-assemble-debug-$(pocketshell_unit_token "$$")" -- \
  ./gradlew "${GRADLE_ARGS[@]}"
end_seconds="$(date +%s)"

apk="$ROOT_DIR/app2/build/outputs/apk/debug/app2-debug.apk"
[[ -f "$apk" ]] || fail "expected APK missing at $apk"
printf 'PASS: assembled %s in %ss\n' "$apk" "$((end_seconds - start_seconds))"

if [[ "$INSTALL" -eq 1 ]]; then
  ADB="$ADB" "$ROOT_DIR/scripts/install-update-apk.sh" "$apk"
fi
