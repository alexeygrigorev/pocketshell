#!/usr/bin/env bash
# Fail-safe connected-phase classification for Nightly Extensive (#1991, #2140).
#
# This file is intentionally pure: it reads a preserved report tree and emits a
# classification. It never invokes adb, retries instrumentation, or changes the
# measured budgets of the product journeys.

# AdbCommandRejectedException device-loss phrases (#2140 audit).
# INCLUDE (same two-part UTP plugin + ddmlib gate as #1991's device offline):
#   device offline | still connecting | unauthorized | ['<serial>'] not found
# INCLUDE independently (no plugin required):
#   "Expected N tests, received M" with M < N (truncated/aborted UTP run)
# EXCLUDE as standalone (too generic; product assertions can emit them):
#   "error: closed", connection reset/refused, protocol fault, adb server death,
#   "no devices/emulators found" alone. A dead adb server still trips the
#   truncated-run rule when UTP aborts mid-class.
_NIGHTLY_DEVICE_OFFLINE_PATTERN='device offline'
_NIGHTLY_DEVICE_STILL_CONNECTING_PATTERN='device still connecting'
_NIGHTLY_DEVICE_UNAUTHORIZED_PATTERN='device unauthorized'
# Quoted serial (`device 'emulator-5554' not found`), unquoted serial, or bare.
_NIGHTLY_DEVICE_NOT_FOUND_PATTERN="device( '[^']+'| [^[:space:]]+)? not found"

nightly_device_loss_regex() {
  printf '%s|%s|%s|%s' \
    "$_NIGHTLY_DEVICE_OFFLINE_PATTERN" \
    "$_NIGHTLY_DEVICE_STILL_CONNECTING_PATTERN" \
    "$_NIGHTLY_DEVICE_UNAUTHORIZED_PATTERN" \
    "$_NIGHTLY_DEVICE_NOT_FOUND_PATTERN"
}

# UTP's aborted-run sentence. Groups 1/2 are Expected/received counts.
_NIGHTLY_TRUNCATED_RUN_PATTERN='Expected[[:space:]]+([0-9]+)[[:space:]]+tests?,[[:space:]]+received[[:space:]]+([0-9]+)'

nightly_phase_has_device_offline_signature() {
  local report_root="$1"
  local phrases
  [[ -d "$report_root" ]] || return 1

  # Require BOTH the UTP lifecycle context and a recognised ddmlib device-loss
  # cause. Plugin and cause may be on separate lines (the #2140 log shape).
  # A generic "offline" / "not found" emitted by a product assertion is not
  # enough to downgrade a release-gating test failure.
  phrases="$(nightly_device_loss_regex)"
  grep -R -a -q 'AndroidTestApkInstallerPlugin' "$report_root" 2>/dev/null &&
    grep -R -a -qE \
      "com[.]android[.]ddmlib[.]AdbCommandRejectedException: (${phrases})" \
      "$report_root" 2>/dev/null
}

nightly_phase_has_truncated_run_signature() {
  local report_root="$1"
  local line expected received
  [[ -d "$report_root" ]] || return 1

  while IFS= read -r line; do
    if [[ "$line" =~ $_NIGHTLY_TRUNCATED_RUN_PATTERN ]]; then
      expected="${BASH_REMATCH[1]}"
      received="${BASH_REMATCH[2]}"
      if (( received < expected )); then
        return 0
      fi
    fi
  done < <(grep -R -a -h -E "$_NIGHTLY_TRUNCATED_RUN_PATTERN" "$report_root" 2>/dev/null || true)
  return 1
}

nightly_phase_has_substantive_junit_failure() {
  local report_root="$1"
  local xml flattened stripped
  [[ -d "$report_root" ]] || return 1

  while IFS= read -r -d '' xml; do
    # UTP represents a device disappearance / aborted run as an empty
    # `<failure></failure>`. Flatten only JUnit XML and require non-whitespace
    # body text after stripping the truncated-run sentence, so that sentence
    # cannot by itself look like a product assertion (#2140).
    flattened="$(tr '\n\r' '  ' < "$xml")"
    stripped="$(
      printf '%s\n' "$flattened" |
        sed -E 's/Test run failed to complete\. Expected [0-9]+ tests?, received [0-9]+//g'
    )"
    if printf '%s\n' "$stripped" |
      grep -E -q '<failure([^>]*)>[[:space:]]*[^<[:space:]]'; then
      return 0
    fi
  done < <(find "$report_root" -type f -name 'TEST-*.xml' -print0 2>/dev/null)
  return 1
}

# classify_nightly_phase <exit-code> <preserved-report-root>
#
# PRODUCT_AND_INFRA is deliberately distinct and remains product-red. Infra is
# allowed to own the phase only when a recognised device-loss or truncated-run
# signature is present and no substantive JUnit assertion/error body remains.
classify_nightly_phase() {
  local exit_code="$1"
  local report_root="$2"
  local offline=no truncated=no substantive=no

  if [[ "$exit_code" -eq 0 ]]; then
    printf 'PASS\n'
    return 0
  fi
  nightly_phase_has_device_offline_signature "$report_root" && offline=yes
  nightly_phase_has_truncated_run_signature "$report_root" && truncated=yes
  nightly_phase_has_substantive_junit_failure "$report_root" && substantive=yes

  if [[ "$substantive" == yes && ( "$offline" == yes || "$truncated" == yes ) ]]; then
    printf 'PRODUCT_AND_INFRA\n'
  elif [[ "$substantive" == yes ]]; then
    printf 'PRODUCT_FAILURE\n'
  elif [[ "$offline" == yes ]]; then
    # Device-loss wins when both signatures are present: the truncation is
    # the consequence of the lost device.
    printf 'INFRA_DEVICE_OFFLINE\n'
  elif [[ "$truncated" == yes ]]; then
    printf 'INFRA_TRUNCATED_RUN\n'
  else
    # Unknown/empty failures fail closed. Only a recognised signature earns
    # an infrastructure classification.
    printf 'PRODUCT_FAILURE\n'
  fi
}

write_nightly_phase_classification() {
  local output="$1"
  local phase="$2"
  local exit_code="$3"
  local report_root="$4"
  local classification offline=no truncated=no substantive=no

  classification="$(classify_nightly_phase "$exit_code" "$report_root")"
  nightly_phase_has_device_offline_signature "$report_root" && offline=yes
  nightly_phase_has_truncated_run_signature "$report_root" && truncated=yes
  nightly_phase_has_substantive_junit_failure "$report_root" && substantive=yes
  mkdir -p "$(dirname "$output")"
  {
    printf 'phase=%s\n' "$phase"
    printf 'exit=%s\n' "$exit_code"
    printf 'classification=%s\n' "$classification"
    printf 'device_offline_signature=%s\n' "$offline"
    printf 'truncated_run_signature=%s\n' "$truncated"
    printf 'substantive_junit_failure=%s\n' "$substantive"
    printf 'report_root=%s\n' "$report_root"
  } > "$output"
}

nightly_phase_status() {
  case "$1" in
    PASS) printf 'PASS\n' ;;
    INFRA_DEVICE_OFFLINE|INFRA_TRUNCATED_RUN) printf 'INFRA\n' ;;
    PRODUCT_FAILURE|PRODUCT_AND_INFRA) printf 'FAIL\n' ;;
    *) printf 'FAIL\n' ;;
  esac
}

# Read-only boundary evidence. This deliberately does not `adb reconnect`,
# restart the emulator, or rerun a phase: doing so would add an unmeasured retry
# budget and could hide the first failure. A transiently recovered device is
# recorded as such and the next phase may proceed; the failed phase keeps its
# preserved classification.
capture_nightly_device_boundary() {
  local output="$1"
  local phase="$2"
  local adb_bin="${ADB:-adb}"
  local adb_target=("$adb_bin")
  [[ -n "${ANDROID_SERIAL:-}" ]] && adb_target+=( -s "$ANDROID_SERIAL" )
  mkdir -p "$(dirname "$output")"
  {
    printf 'phase=%s\n' "$phase"
    printf 'android_serial=%s\n' "${ANDROID_SERIAL:-<unset>}"
    printf '%s\n' '--- adb devices -l ---'
    timeout 5s "$adb_bin" devices -l 2>&1 || true
    printf '%s\n' '--- adb get-state ---'
    timeout 5s "${adb_target[@]}" get-state 2>&1 || true
    printf '%s\n' '--- sys.boot_completed ---'
    timeout 5s "${adb_target[@]}" shell getprop sys.boot_completed 2>&1 || true
  } > "$output"
}
