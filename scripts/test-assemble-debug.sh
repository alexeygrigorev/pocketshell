#!/usr/bin/env bash
# Fast, Gradle-free checks for scripts/assemble-debug.sh.
#
# Proves the local APK path cannot silently fall back onto the release-gate
# profile (--no-daemon / --no-build-cache / --max-workers=1) and that --abi
# reaches Gradle as -PpocketshellAbiFilters.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/assemble-debug.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

cmd="$("$SCRIPT" --abi all --print-command)"
printf '%s\n' "$cmd" | grep -Fq ':app:assembleDebug' || \
  fail "command must assemble the debug APK; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq -- '--no-daemon' && \
  fail "local assemble must keep the Gradle daemon; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq -- '--no-build-cache' && \
  fail "local assemble must keep the Gradle build cache; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq -- '--max-workers=1' && \
  fail "local assemble must not serialise on the release-gate worker cap; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq -- '-Pkotlin.daemon.jvmargs=-Xmx3072m' || \
  fail "local assemble must pin the Kotlin daemon heap; got: $cmd"
grep -Fq 'ADB="$ADB" "$ROOT_DIR/scripts/install-update-apk.sh" "$apk"' "$SCRIPT" || \
  fail "--install must pass the selected ADB to the update installer"
printf '%s\n' "$cmd" | grep -Fq -- 'assembleDebugAndroidTest' && \
  fail "default command must not compile androidTest; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq -- 'pocketshellAbiFilters' && \
  fail "--abi all must compile every ABI; got: $cmd"

cmd="$("$SCRIPT" --android-test --print-command)"
printf '%s\n' "$cmd" | grep -Fq ':app:assembleDebugAndroidTest' || \
  fail "--android-test must add the androidTest APK; got: $cmd"

cmd="$("$SCRIPT" --abi arm64-v8a --print-command)"
printf '%s\n' "$cmd" | grep -Fq -- '-PpocketshellAbiFilters=arm64-v8a' || \
  fail "--abi arm64-v8a must reach Gradle; got: $cmd"

cmd="$("$SCRIPT" --abi all --print-command)"
printf '%s\n' "$cmd" | grep -Fq -- 'pocketshellAbiFilters' && \
  fail "--abi all must omit the ABI property; got: $cmd"

empty_adb="$(mktemp)"
trap 'rm -f "$fake_adb" "$empty_adb"' EXIT
cat > "$empty_adb" <<'FAKE'
#!/usr/bin/env bash
if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\n'
  exit 0
fi
exit 1
FAKE
chmod +x "$empty_adb"
cmd="$(ASSEMBLE_DEBUG_ADB="$empty_adb" "$SCRIPT" --abi auto --print-command)"
printf '%s\n' "$cmd" | grep -Fq -- 'pocketshellAbiFilters' && \
  fail "--abi auto with no device must compile every ABI; got: $cmd"

fake_adb="$(mktemp)"
cat > "$fake_adb" <<'FAKE'
#!/usr/bin/env bash
if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi
if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\n'
  printf 'emulator-5560\tdevice\n'
  exit 0
fi
if [[ "${1:-}" == "shell" && "${2:-}" == "getprop" ]]; then
  printf 'x86_64\n'
  exit 0
fi
exit 1
FAKE
chmod +x "$fake_adb"

cmd="$(ASSEMBLE_DEBUG_ADB="$fake_adb" ANDROID_SERIAL=emulator-5560 \
  "$SCRIPT" --abi auto --print-command)"
printf '%s\n' "$cmd" | grep -Fq -- '-PpocketshellAbiFilters=x86_64' || \
  fail "--abi auto must use the connected device ABI; got: $cmd"

if "$SCRIPT" --abi mips --print-command >/dev/null 2>&1; then
  fail "unsupported --abi must fail"
fi

printf 'PASS: scripts/test-assemble-debug.sh\n'
