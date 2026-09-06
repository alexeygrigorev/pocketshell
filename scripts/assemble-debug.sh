#!/usr/bin/env bash
# Fast local debug APK. This is NOT the release/visual-audit profile.
#
# The gate wrappers (`capture-walkthrough-screenshots.sh`, the pre-release
# confidence gate) use --no-daemon --no-build-cache --max-workers=1 so a
# release APK is reproducible and cannot OOM the box. That profile is the wrong
# default for "put this build on the phone": it throws away the Gradle daemon
# and the build cache, serialises every module, and compiles androidTest.
#
# This script keeps the daemon, keeps the cache, and raises the Kotlin heap.
#
# Issue #2570: the `--abi` option and the `-PpocketshellAbiFilters` property it
# passed are gone. They existed to compile only the connected device's ABI of
# `shared/core-terminal`'s JNI; #2566 deleted that native build, so the property
# was read by no Gradle file any more and the flag saved nothing. A flag nobody
# reads is worse than no flag (D22).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/scope-run.sh"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/alexey/Android/Sdk}}}"
ADB="${ASSEMBLE_DEBUG_ADB:-${ADB:-$ANDROID_SDK/platform-tools/adb}}"
WITH_ANDROID_TEST=0
INSTALL=0
PRINT_COMMAND=0

usage() {
  cat <<'USAGE'
Usage: scripts/assemble-debug.sh [options]

Fast local :app2:assembleDebug. Keeps the Gradle daemon and build cache.

Options:
  --android-test        Also assemble the androidTest APK.
  --install             adb install -r the debug APK after a successful build.
  --print-command       Print the Gradle command and exit (no build).
  -h, --help            Show this help.

Environment:
  ANDROID_SDK / ANDROID_HOME / ANDROID_SDK_ROOT
  ADB / ASSEMBLE_DEBUG_ADB
  ANDROID_SERIAL          pin which device --install uses
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
