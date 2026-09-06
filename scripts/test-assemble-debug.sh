#!/usr/bin/env bash
# Fast, Gradle-free checks for scripts/assemble-debug.sh.
#
# Proves the local APK path cannot silently fall back onto the release-gate
# profile (--no-daemon / --no-build-cache / --max-workers=1).
#
# Issue #2570: it also pins the REMOVAL of the `--abi` option. That option
# passed `-PpocketshellAbiFilters`, which #2566 left read by no Gradle file when
# it deleted the last `externalNativeBuild`. An unread property that looks like
# it selects an ABI is exactly the flag someone re-adds a native build against
# and believes is working, so `--abi` must stay a hard error, not a no-op.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/assemble-debug.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

cmd="$("$SCRIPT" --print-command)"
printf '%s\n' "$cmd" | grep -Fq ':app2:assembleDebug' || \
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

# #2570: no ABI property may reach Gradle, on ANY invocation. Nothing reads it.
printf '%s\n' "$cmd" | grep -Fq -- 'pocketshellAbiFilters' && \
  fail "no Gradle file reads -PpocketshellAbiFilters since #2566; got: $cmd"
# Comment lines may still NAME the removed property (the script explains why it
# is gone); no executable line may pass it.
grep -vE '^[[:space:]]*#' "$SCRIPT" | grep -Fq 'pocketshellAbiFilters' && \
  fail "scripts/assemble-debug.sh must not pass the unread -PpocketshellAbiFilters (#2570)"

cmd="$("$SCRIPT" --android-test --print-command)"
printf '%s\n' "$cmd" | grep -Fq ':app2:assembleDebugAndroidTest' || \
  fail "--android-test must add the androidTest APK; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq -- 'pocketshellAbiFilters' && \
  fail "--android-test must not reintroduce the ABI property; got: $cmd"

# #2570: `--abi` is gone, and must fail loudly rather than be accepted-and-ignored.
abi_invocations=(
  "--abi auto"
  "--abi=auto"
  "--abi all"
  "--abi arm64-v8a"
)
for abi_arg in "${abi_invocations[@]}"; do
  # shellcheck disable=SC2086 # deliberate word splitting: pass the flag as typed
  if "$SCRIPT" $abi_arg --print-command >/dev/null 2>&1; then
    fail "'$abi_arg' must be rejected: the ABI property is unread since #2566 (#2570)"
  fi
done

# The device-ABI probe is gone too: --print-command must not shell out to adb.
probing_adb="$(mktemp)"
trap 'rm -f "$probing_adb"' EXIT
cat > "$probing_adb" <<'FAKE'
#!/usr/bin/env bash
printf 'assemble-debug.sh must not probe adb for an ABI (#2570)\n' >&2
exit 97
FAKE
chmod +x "$probing_adb"
cmd="$(ASSEMBLE_DEBUG_ADB="$probing_adb" ANDROID_SERIAL=emulator-5560 \
  "$SCRIPT" --print-command 2>/dev/null)"
printf '%s\n' "$cmd" | grep -Fq ':app2:assembleDebug' || \
  fail "--print-command must not depend on adb; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq -- 'pocketshellAbiFilters' && \
  fail "a connected device must not reintroduce the ABI property; got: $cmd"

if "$SCRIPT" --nonsense --print-command >/dev/null 2>&1; then
  fail "an unknown argument must fail"
fi

printf 'PASS: scripts/test-assemble-debug.sh\n'
