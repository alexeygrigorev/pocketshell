#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

state_file="$tmpdir/booted"
fake_adb="$tmpdir/adb"
cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_file="${FAKE_AVD_STATE:?}"

case "$*" in
  "start-server")
    ;;
  "devices"|"devices -l")
    printf 'List of devices attached\n'
    if [[ -f "$state_file" ]]; then
      printf 'emulator-5554\tdevice product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1\n'
    fi
    ;;
  "shell getprop sys.boot_completed")
    if [[ -f "$state_file" ]]; then
      printf '1\n'
    fi
    ;;
  "shell getprop")
    printf '[sys.boot_completed]: ['
    if [[ -f "$state_file" ]]; then
      printf '1'
    fi
    printf ']\n'
    ;;
  "get-state")
    if [[ -f "$state_file" ]]; then
      printf 'device\n'
    else
      printf 'unknown\n'
      exit 1
    fi
    ;;
  "get-serialno")
    if [[ -f "$state_file" ]]; then
      printf 'emulator-5554\n'
    else
      printf 'unknown\n'
    fi
    ;;
  *)
    printf 'unexpected adb args: %s\n' "$*" >&2
    exit 1
    ;;
esac
EOF
chmod +x "$fake_adb"

fake_emulator="$tmpdir/emulator"
cat > "$fake_emulator" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

case "$*" in
  "-list-avds")
    printf 'test\n'
    ;;
  "-accel-check")
    printf 'accel ok\n'
    ;;
  *)
    printf 'fake emulator invoked:'
    printf ' %q' "$@"
    printf '\n'
    touch "${FAKE_AVD_STATE:?}"
    ;;
esac
EOF
chmod +x "$fake_emulator"

fake_ps="$tmpdir/ps"
cat > "$fake_ps" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF
chmod +x "$fake_ps"

run_dir="$tmpdir/run"
FAKE_AVD_STATE="$state_file" \
POCKETSHELL_AVD_LOCK_FILE="$tmpdir/avd.lock" \
ANDROID_SDK="$tmpdir/android-sdk" \
ADB="$fake_adb" \
EMULATOR="$fake_emulator" \
AVD_NAME="test" \
LOG_ROOT="$run_dir" \
RUN_ID="managed" \
BOOT_TIMEOUT_SECONDS=10 \
PATH="$tmpdir:$PATH" \
  "$ROOT_DIR/scripts/start-local-avd.sh" > "$tmpdir/stdout.log" 2> "$tmpdir/stderr.log" ||
  fail "start-local-avd did not pass with fake emulator"

[[ -f "$state_file" ]] || fail "fake emulator was not started"
grep -q -- '-no-snapshot-load' "$run_dir/managed/emulator.log" ||
  fail "default start flags did not include -no-snapshot-load"
grep -q -- '-no-snapshot-save' "$run_dir/managed/emulator.log" ||
  fail "default start flags did not include -no-snapshot-save"
grep -q 'Status: PASS' "$run_dir/managed/summary.txt" ||
  fail "summary did not record PASS"
grep -q '== emulator -accel-check ==' "$run_dir/managed/diagnostics.txt" ||
  fail "diagnostics did not include accelerator check"

printf 'PASS: start-local-avd helper\n'
