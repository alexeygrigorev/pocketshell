#!/usr/bin/env bash
# Fail-safe connected-phase classification for Nightly Extensive (#1991).
#
# This file is intentionally pure: it reads a preserved report tree and emits a
# classification. It never invokes adb, retries instrumentation, or changes the
# measured budgets of the product journeys.

nightly_phase_has_device_offline_signature() {
  local report_root="$1"
  [[ -d "$report_root" ]] || return 1

  # Require BOTH the UTP lifecycle context and ddmlib's exact cause. A generic
  # "offline" emitted by a product assertion or fixture is not enough to
  # downgrade a release-gating test failure.
  grep -R -a -q \
    'AndroidTestApkInstallerPlugin.*device offline' "$report_root" 2>/dev/null &&
    grep -R -a -q \
      'com[.]android[.]ddmlib[.]AdbCommandRejectedException: device offline' \
      "$report_root" 2>/dev/null
}

nightly_phase_has_substantive_junit_failure() {
  local report_root="$1"
  local xml flattened
  [[ -d "$report_root" ]] || return 1

  while IFS= read -r -d '' xml; do
    # UTP represents a device disappearance as an empty `<failure></failure>`.
    # Flatten only JUnit XML and require non-whitespace body text. This keeps a
    # real assertion load-bearing even if the device subsequently goes offline.
    flattened="$(tr '\n\r' '  ' < "$xml")"
    if printf '%s\n' "$flattened" |
      grep -E -q '<failure([^>]*)>[[:space:]]*[^<[:space:]]'; then
      return 0
    fi
  done < <(find "$report_root" -type f -name 'TEST-*.xml' -print0 2>/dev/null)
  return 1
}

# classify_nightly_phase <exit-code> <preserved-report-root>
#
# PRODUCT_AND_INFRA is deliberately distinct and remains product-red. Infra is
# allowed to own the phase only when the phase has the strong two-part offline
# signature and no substantive JUnit assertion/error body.
classify_nightly_phase() {
  local exit_code="$1"
  local report_root="$2"
  local offline=no substantive=no

  if [[ "$exit_code" -eq 0 ]]; then
    printf 'PASS\n'
    return 0
  fi
  nightly_phase_has_device_offline_signature "$report_root" && offline=yes
  nightly_phase_has_substantive_junit_failure "$report_root" && substantive=yes

  if [[ "$substantive" == yes && "$offline" == yes ]]; then
    printf 'PRODUCT_AND_INFRA\n'
  elif [[ "$substantive" == yes ]]; then
    printf 'PRODUCT_FAILURE\n'
  elif [[ "$offline" == yes ]]; then
    printf 'INFRA_DEVICE_OFFLINE\n'
  else
    # Unknown/empty failures fail closed. Only the exact strong signature earns
    # an infrastructure classification.
    printf 'PRODUCT_FAILURE\n'
  fi
}

write_nightly_phase_classification() {
  local output="$1"
  local phase="$2"
  local exit_code="$3"
  local report_root="$4"
  local classification offline=no substantive=no

  classification="$(classify_nightly_phase "$exit_code" "$report_root")"
  nightly_phase_has_device_offline_signature "$report_root" && offline=yes
  nightly_phase_has_substantive_junit_failure "$report_root" && substantive=yes
  mkdir -p "$(dirname "$output")"
  {
    printf 'phase=%s\n' "$phase"
    printf 'exit=%s\n' "$exit_code"
    printf 'classification=%s\n' "$classification"
    printf 'device_offline_signature=%s\n' "$offline"
    printf 'substantive_junit_failure=%s\n' "$substantive"
    printf 'report_root=%s\n' "$report_root"
  } > "$output"
}

nightly_phase_status() {
  case "$1" in
    PASS) printf 'PASS\n' ;;
    INFRA_DEVICE_OFFLINE) printf 'INFRA\n' ;;
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
