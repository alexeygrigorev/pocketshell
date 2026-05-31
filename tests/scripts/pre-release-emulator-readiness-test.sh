#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

helpers="$tmpdir/pre-release-emulator-readiness-helpers.sh"
sed -n \
  '/^emulator_readiness_script()/,/^fail()/p' \
  "$ROOT_DIR/scripts/pre-release-confidence-gate.sh" |
  sed '$d' > "$helpers"

fake_adb="$tmpdir/adb"
cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_file="${FAKE_EMULATOR_STATE:?}"

case "$*" in
  "devices"|"devices -l")
    printf 'List of devices attached\n'
    if [[ -f "$state_file" ]]; then
      printf 'emulator-5554\tdevice product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1\n'
    fi
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
  "shell getprop sys.boot_completed")
    if [[ -f "$state_file" ]]; then
      printf '1\n'
    fi
    ;;
esac
EOF
chmod +x "$fake_adb"

fake_emulator="$tmpdir/emulator"
cat > "$fake_emulator" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf 'fake emulator invoked:'
printf ' %q' "$@"
printf '\n'
touch "${FAKE_EMULATOR_STATE:?}"
EOF
chmod +x "$fake_emulator"

fake_ps="$tmpdir/ps"
cat > "$fake_ps" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

case "${FAKE_PS_MODE:-empty}" in
  self_match)
    printf '%s\n' '1319962 bash bash -lc emulator_process_pattern="emulator.*-avd[ =]$avd_name|qemu-system.*$avd_name|qemu-system"'
    ;;
esac
EOF
chmod +x "$fake_ps"

# shellcheck source=/dev/null
source "$helpers"

run_readiness_script() {
  local mode="$1"
  local run_dir="$tmpdir/$mode"
  mkdir -p "$run_dir"
  rm -f "$tmpdir/emulator-started"

  ADB="$fake_adb"
  EMULATOR="$fake_emulator"
  AVD_NAME="test"
  RUN_DIR="$run_dir"
  PRE_RELEASE_EMULATOR_START_ARGS="-no-window -no-audio"
  FAKE_EMULATOR_STATE="$tmpdir/emulator-started"
  FAKE_PS_MODE="empty"
  PATH="$tmpdir:$PATH"
  export FAKE_EMULATOR_STATE FAKE_PS_MODE PATH

  case "$mode" in
    managed)
      PRE_RELEASE_MANAGE_EMULATOR=1
      ;;
    diagnostic)
      PRE_RELEASE_MANAGE_EMULATOR=0
      ;;
    self-match-managed)
      PRE_RELEASE_MANAGE_EMULATOR=1
      FAKE_PS_MODE="self_match"
      ;;
    self-match-diagnostic)
      PRE_RELEASE_MANAGE_EMULATOR=0
      FAKE_PS_MODE="self_match"
      ;;
  esac

  bash -lc "$(emulator_readiness_script)" > "$run_dir/stdout.log" 2> "$run_dir/stderr.log"
}

run_readiness_script managed ||
  fail "managed readiness did not start the fake emulator and pass"
[[ -f "$tmpdir/emulator-started" ]] ||
  fail "managed readiness did not invoke the emulator"
grep -q 'Emulator readiness confirmed' "$tmpdir/managed/stdout.log" ||
  fail "managed readiness did not report readiness"
grep -q 'fake emulator invoked' "$tmpdir/managed/emulator-readiness-managed-emulator.log" ||
  fail "managed emulator log did not capture the emulator invocation"

run_readiness_script self-match-managed ||
  fail "managed readiness treated the generated shell command as an emulator process"
[[ -f "$tmpdir/emulator-started" ]] ||
  fail "managed readiness self-match case did not invoke the emulator"
grep -q 'Emulator readiness confirmed' "$tmpdir/self-match-managed/stdout.log" ||
  fail "managed readiness self-match case did not report readiness"

if run_readiness_script diagnostic; then
  fail "diagnostic readiness passed without an adb device or emulator process"
fi
grep -q 'Infrastructure readiness failure: no ADB devices and no emulator process' "$tmpdir/diagnostic/stderr.log" ||
  fail "diagnostic readiness did not explain the missing emulator"
grep -q '== adb devices ==' "$tmpdir/diagnostic/emulator-readiness-diagnostics.log" ||
  fail "diagnostic readiness did not write adb diagnostics"
grep -q '== emulator processes ==' "$tmpdir/diagnostic/emulator-readiness-diagnostics.log" ||
  fail "diagnostic readiness did not write process diagnostics"

if run_readiness_script self-match-diagnostic; then
  fail "diagnostic readiness passed by treating the generated shell command as an emulator process"
fi
grep -q 'Infrastructure readiness failure: no ADB devices and no emulator process' "$tmpdir/self-match-diagnostic/stderr.log" ||
  fail "diagnostic readiness self-match case did not explain the missing emulator"
if grep -q 'emulator_process_pattern' "$tmpdir/self-match-diagnostic/emulator-readiness-diagnostics.log"; then
  fail "diagnostic readiness listed the generated shell command as an emulator process"
fi

printf 'PASS: pre-release emulator readiness helper\n'
