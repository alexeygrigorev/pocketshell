#!/usr/bin/env bash
# Exact Nightly phase-2 result guard for issue #1751.
#
# A class-level AndroidJUnitRunner selection can return green when the required
# sustained-cut method is renamed or skipped while another method in its class
# remains. The raw phase exit code also does not prove that the positive-band
# artifact was pulled. This helper makes all three conditions load-bearing.

EXPECTED_BRIEF_RIDE_THROUGH_CLASS="com.pocketshell.app.proof.RideThroughInterruptionE2eTest"
EXPECTED_BRIEF_RIDE_THROUGH_METHOD="briefLinkCutRidesThroughWithoutDisconnectOrTeardown"

control_artifact_has_user_visible_recovery_token() {
  local file="$1" line list token
  local -a tokens=()

  line="$(grep -E '^observer_first_violations=' "$file" | head -1)"
  case "$line" in
    observer_first_violations=\[*\]) ;;
    *) return 1 ;;
  esac

  # The Kotlin artifact is List.toString(): [token, token]. Parse complete
  # comma-separated tokens so an internal field cannot launder a visible-looking
  # substring (for example `not_reconnecting_pill` or `vm_status=Connected`).
  list="${line#observer_first_violations=[}"
  list="${list%]}"
  IFS=',' read -r -a tokens <<< "$list"
  for token in "${tokens[@]}"; do
    token="${token#${token%%[![:space:]]*}}"
    token="${token%${token##*[![:space:]]}}"
    case "$token" in
      vm_status=Idle|vm_status=Connecting|vm_status=Switching|vm_status=Reconnecting|vm_status=Failed|reconnecting_pill|attaching_hold|reconnect_band_retry_now|connecting_progress_row|settled_failed_band)
        return 0
        ;;
    esac
  done
  return 1
}

require_exact_successful_junit_method() {
  local results_root="$1" class_name="$2" method_name="$3"
  local matches successful_matches

  matches="$(
    find "$results_root" -type f -name 'TEST-*.xml' -exec grep -hF "name=\"$method_name\"" {} + 2>/dev/null \
      | grep -Fc "classname=\"$class_name\"" \
      | tr -d '[:space:]'
  )"
  # The UTP JUnit producer emits a successful testcase as a self-closing tag.
  # Skips, failures, and errors have a non-self-closing testcase with a child
  # element, so they deliberately do not count as successful here.
  successful_matches="$(
    find "$results_root" -type f -name 'TEST-*.xml' -exec grep -hF "name=\"$method_name\"" {} + 2>/dev/null \
      | grep -F "classname=\"$class_name\"" \
      | grep -Ec '/>[[:space:]]*$' \
      | tr -d '[:space:]'
  )"

  if [[ "$matches" != "1" || "$successful_matches" != "1" ]]; then
    echo "FAIL: expected exact nightly fault method once and successful (not skipped/failed), found total=${matches:-0} successful=${successful_matches:-0}: $class_name#$method_name" >&2
    return 1
  fi
  return 0
}

require_exact_junit_method() {
  local results_root="$1" artifacts_root="$2" class_name="$3" method_name="$4"
  local recovery_artifacts valid_recovery_artifacts

  require_exact_successful_junit_method "$results_root" "$class_name" "$method_name" || return 1

  recovery_artifacts="$(
    find "$artifacts_root" -type f -path '*/issue342-network-faults/recovery-band-longcut.txt' 2>/dev/null \
      | wc -l \
      | tr -d '[:space:]'
  )"
  valid_recovery_artifacts="$(
    find "$artifacts_root" -type f -path '*/issue342-network-faults/recovery-band-longcut.txt' \
      -exec sh -c \
        'grep -qx "reconnecting_band_appeared=true" "$1" && grep -qx "settled_failed_pre_empted=false" "$1"' \
        sh {} \; -print 2>/dev/null \
      | wc -l \
      | tr -d '[:space:]'
  )"

  if [[ "$recovery_artifacts" != "1" || "$valid_recovery_artifacts" != "1" ]]; then
    echo "FAIL: expected exactly one valid positive-band artifact, found total=${recovery_artifacts:-0} valid=${valid_recovery_artifacts:-0}: issue342-network-faults/recovery-band-longcut.txt" >&2
    return 1
  fi
  echo "PASS: exact nightly fault method executed once, unskipped, successful, with a valid positive-band artifact: $class_name#$method_name"
  return 0
}

brief_ride_through_artifact_has_no_forbidden_events() {
  local file="$1"
  awk '
    function metadata_has_event(metadata, event) {
      return metadata ~ ("(^|[,{[:space:]])event=" event "([,}]|[[:space:]]|$)")
    }

    BEGIN {
      in_snapshots = 0
      in_timeline = 0
      snapshot_headers = 0
      timeline_headers = 0
      snapshot_lines = 0
      malformed = 0

      forbidden_connection["liveness_probe_silent_drop"] = 1
      forbidden_connection["passive_disconnect"] = 1
      forbidden_connection["silent_reattach_start"] = 1
      forbidden_connection["tmux_client_reader_exit"] = 1
      forbidden_connection["tmux_client_command_timeout"] = 1
      forbidden_connection["network_loss_hold"] = 1
    }

    $0 == "recovery_and_identity_snapshots:" {
      if (snapshot_headers != 0 || in_timeline != 0) malformed = 1
      snapshot_headers++
      in_snapshots = 1
      in_timeline = 0
      next
    }

    $0 == "typed_connection_and_controller_timeline:" {
      if (timeline_headers != 0 || snapshot_headers != 1 || !in_snapshots) malformed = 1
      timeline_headers++
      in_snapshots = 0
      in_timeline = 1
      next
    }

    in_snapshots {
      if ($0 ~ /^[[:space:]]*$/) next
      snapshot_lines++
      # Kotlin BriefRideThroughSnapshot.asLine() emits forbiddenEvents last.
      # Require the field and its exact empty List.toString() representation;
      # missing or non-empty lists are both invalid.
      if ($0 !~ /^[[:space:]]+phase=[^[:space:]]+[[:space:]].*forbiddenEvents=\[\][[:space:]]*$/) {
        malformed = 1
      }
      next
    }

    in_timeline {
      if ($0 ~ /^[[:space:]]*$/) next
      line = $0
      sub(/^[[:space:]]+/, "", line)
      # Kotlin writes one flattened DiagnosticsEvent per line. Do not let an
      # unrecognised line or a truncated event silently count as clean.
      if (line !~ /^t=[0-9]+ms[[:space:]]+sequence=[0-9]+[[:space:]]+category=[^[:space:]]+[[:space:]]+name=[^[:space:]]+[[:space:]]+metadata=\{.*\}[[:space:]]*$/) {
        malformed = 1
        next
      }

      split(line, fields, /[[:space:]]+/)
      category = fields[3]
      sub(/^category=/, "", category)
      name = fields[4]
      sub(/^name=/, "", name)
      metadata = line
      sub(/^t=[^[:space:]]+[[:space:]]+sequence=[^[:space:]]+[[:space:]]+category=[^[:space:]]+[[:space:]]+name=[^[:space:]]+[[:space:]]+metadata=/, "", metadata)

      lower_name = tolower(name)
      if (category == "reconnect" ||
          lower_name ~ /reconnect/ ||
          name in forbidden_connection ||
          (name == "keepalive_death_budget_crossed" &&
              metadata ~ /(^|[,{[:space:]])outcome=declared_dead([,}]|[[:space:]]|$)/) ||
          (name == "submit" &&
              (metadata_has_event(metadata, "transport_dropped") ||
               metadata_has_event(metadata, "reconnect_ladder_entered") ||
               metadata_has_event(metadata, "reconnect_failed") ||
               metadata_has_event(metadata, "reconnect_gave_up") ||
               metadata_has_event(metadata, "network_lost")))) {
        malformed = 1
      }
      next
    }

    END {
      exit !(snapshot_headers == 1 && timeline_headers == 1 && snapshot_lines > 0 && malformed == 0)
    }
  ' "$file"
}

brief_ride_through_artifact_is_valid() {
  local file="$1"
  grep -qx 'phase_pre_install_executed=true' "$file" &&
    grep -qx 'phase_toxic_installed_executed=true' "$file" &&
    grep -qx 'phase_toxic_cleared_executed=true' "$file" &&
    grep -qx 'phase_post_restore_observation_executed=true' "$file" &&
    grep -qx 'brief_cut_target_ms=5000' "$file" &&
    awk -F= '$1 == "brief_cut_actual_ms" && $2 >= 5000 && $2 <= 6000 { ok=1 } END { exit !ok }' "$file" &&
    grep -qx 'post_restore_target_ms=4000' "$file" &&
    awk -F= '$1 == "post_restore_observed_ms" && $2 >= 4000 { ok=1 } END { exit !ok }' "$file" &&
    grep -qx 'detector_reproduction=physical-half-open-production-defaults' "$file" &&
    grep -qx 'production_liveness_probe_interval_ms=7000' "$file" &&
    grep -qx 'production_liveness_probe_timeout_ms=5000' "$file" &&
    grep -qx 'production_liveness_probe_failure_threshold=4' "$file" &&
    grep -qx 'production_half_open_detection_budget_ms=48000' "$file" &&
    grep -qx 'passive_disconnect_grace_ms=60000' "$file" &&
    grep -qx 'controller_grace_ms=90000' "$file" &&
    grep -qx 'brief_is_below_production_detection_budget=true' "$file" &&
    grep -qx 'sentinel_before_cut_completed=true' "$file" &&
    grep -qx 'sentinel_during_cut_completed=false' "$file" &&
    grep -qx 'sentinel_during_cut_marker_present=false' "$file" &&
    grep -qx 'sentinel_same_connection_after_restore_completed=true' "$file" &&
    grep -qx 'sentinel_after_restore_completed=true' "$file" &&
    grep -qx 'app_server_marker_verified=true' "$file" &&
    grep -qx 'phase_completed=true' "$file" &&
    grep -qx 'failure=none' "$file" &&
    awk -F= '$1 == "observed_ticks" && $2 >= 6 { ok=1 } END { exit !ok }' "$file" &&
    grep -q 'phase=fault_engaged .*status=Connected .*clientIdentity=.*clientDisconnected=false .*controllerState=Live.*serverClients=' "$file" &&
    grep -q 'phase=post_restore .*status=Connected .*clientIdentity=.*clientDisconnected=false .*controllerState=Live.*serverClients=' "$file" &&
    grep -qx 'typed_connection_and_controller_timeline:' "$file" || return 1

  # Keep the typed timeline predicate separate from the scalar field checks:
  # a fixture with otherwise-valid summary fields must still fail when a
  # forbidden snapshot event or typed connection/controller event is present.
  if ! brief_ride_through_artifact_has_no_forbidden_events "$file"; then
    return 1
  fi

  grep -qx 'sentinel_same_connection_after_restore_outcome_completed=true' "$file" &&
    grep -qx 'sentinel_same_connection_after_restore_marker_verified=true' "$file"
}

clean_close_control_typed_reader_eof_is_valid() {
  local file="$1"
  local reader_eof_artifact="$(dirname "$file")/reader-eof-control.txt"
  grep -qx 'typed_reader_eof_detected=true' "$file" &&
    grep -qx 'typed_reader_eof_event_name=tmux_client_reader_exit' "$file" &&
    awk -F= '$1 == "typed_reader_eof_event_elapsed_ms" && $2 >= 0 { ok=1 } END { exit !ok }' "$file" &&
    [[ -f "$reader_eof_artifact" ]] &&
    grep -Eq '^sequence=[0-9]+ category=connection name=tmux_client_reader_exit metadata=' "$reader_eof_artifact" ||
    return 1

  if grep -qx 'typed_reader_eof_disconnect_reason=reader_eof' "$file"; then
    return 0
  fi

  grep -qx 'typed_reader_eof_disconnect_reason=reader_exception' "$file" &&
    grep -qx 'typed_reader_eof_source=read_failure' "$file" &&
    grep -qx 'typed_reader_eof_message=eof' "$file"
}

clean_close_control_artifact_is_valid() {
  local file="$1"
  grep -qx 'production_disconnect_triggered=true' "$file" &&
    grep -qx 'observer_detected_recovery=true' "$file" &&
    awk -F= '$1 == "observer_recovery_detected_ms" && $2 >= 0 { ok=1 } END { exit !ok }' "$file" &&
    control_artifact_has_user_visible_recovery_token "$file" &&
    clean_close_control_typed_reader_eof_is_valid "$file" &&
    grep -qx 'app_server_marker_verified=true' "$file" &&
    grep -qx 'failure=none' "$file"
}

# Portable PNG contract: signature, chunk framing/CRC, zlib stream, and decoded
# scanline length, without trusting the filename or byte count. Split out of
# authoritative_terminal_viewport_png_is_valid so the self-test can assert this
# path DIRECTLY: on a host that ships ImageMagick, an `identify` rejection would
# otherwise mask a vacuous Python path, which is exactly how the missing
# exit-status check below survived (the `identify` call is optional, so its
# absent-branch status 0 became the function's return value and every existing
# file read as a valid PNG on ImageMagick-less hosts, i.e. on CI).
authoritative_terminal_viewport_png_decodes_in_python() {
  local file="$1"
  [[ -f "$file" ]] || return 1

  python3 - "$file" <<'PY'
import pathlib
import struct
import sys
import zlib

try:
    data = pathlib.Path(sys.argv[1]).read_bytes()
    signature = b"\x89PNG\r\n\x1a\n"
    if not data.startswith(signature):
        raise ValueError("bad PNG signature")

    pos = len(signature)
    ihdr = None
    idat = bytearray()
    saw_iend = False
    while pos < len(data):
        if len(data) - pos < 12:
            raise ValueError("truncated PNG chunk")
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        chunk_end = pos + 12 + length
        if chunk_end > len(data):
            raise ValueError("PNG chunk exceeds file")
        chunk_type = data[pos + 4:pos + 8]
        chunk_data = data[pos + 8:pos + 8 + length]
        expected_crc = struct.unpack(">I", data[pos + 8 + length:chunk_end])[0]
        actual_crc = zlib.crc32(chunk_type + chunk_data) & 0xffffffff
        if actual_crc != expected_crc:
            raise ValueError("PNG chunk CRC mismatch")
        if pos == len(signature) and chunk_type != b"IHDR":
            raise ValueError("PNG does not begin with IHDR")
        if saw_iend:
            raise ValueError("data follows IEND")

        if chunk_type == b"IHDR":
            if ihdr is not None or length != 13:
                raise ValueError("invalid IHDR")
            ihdr = struct.unpack(">IIBBBBB", chunk_data)
        elif chunk_type == b"IDAT":
            if ihdr is None:
                raise ValueError("IDAT precedes IHDR")
            idat.extend(chunk_data)
        elif chunk_type == b"IEND":
            if length != 0:
                raise ValueError("invalid IEND")
            saw_iend = True
        pos = chunk_end

    if ihdr is None or not idat or not saw_iend or pos != len(data):
        raise ValueError("incomplete PNG")

    width, height, bit_depth, color_type, compression, filter_method, interlace = ihdr
    if width == 0 or height == 0 or compression != 0 or filter_method != 0 or interlace not in (0, 1):
        raise ValueError("invalid PNG header")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
    allowed_depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
    if color_type not in channels or bit_depth not in allowed_depths[color_type]:
        raise ValueError("invalid PNG color/depth")

    decoded = zlib.decompress(bytes(idat))
    if interlace == 0:
        bits_per_pixel = channels[color_type] * bit_depth
        row_bytes = (width * bits_per_pixel + 7) // 8
        if len(decoded) != (row_bytes + 1) * height:
            raise ValueError("invalid decoded scanline length")
    elif not decoded:
        raise ValueError("empty interlaced image")
except (OSError, IndexError, struct.error, ValueError, zlib.error):
    sys.exit(1)
sys.exit(0)
PY
}

# ImageMagick is optional and is NOT installed on the CI static-guard runner, so
# "identify is absent" is the configuration that actually ships. Route the probe
# through one overridable seam so the self-test can exercise that configuration
# on a developer host that does ship ImageMagick — otherwise a locally-green
# self-test says nothing about the only host the guard really runs on.
nightly_guard_identify_is_available() {
  if [[ "${NIGHTLY_GUARD_FORCE_NO_IDENTIFY:-0}" == "1" ]]; then
    return 1
  fi
  command -v identify >/dev/null 2>&1
}

authoritative_terminal_viewport_png_is_valid() {
  local file="$1"
  [[ -f "$file" ]] || return 1

  # The Python decoder is the portable contract for CI/minimal review hosts, so
  # its exit status is load-bearing and must be checked explicitly.
  authoritative_terminal_viewport_png_decodes_in_python "$file" || return 1

  # Use a full image decoder as an additional check when present.
  if nightly_guard_identify_is_available; then
    identify -quiet -format '%m %w %h' "$file" 2>/dev/null \
      | grep -Eq '^PNG [1-9][0-9]* [1-9][0-9]*$' || return 1
  fi
  return 0
}

authoritative_terminal_artifact_pair_is_valid() {
  local artifacts_root="$1" stem="$2"
  local viewport_count valid_viewport_count text_count
  viewport_count="$(
    find "$artifacts_root" -type f \
      -path "*/issue342-network-faults/${stem}-viewport.png" 2>/dev/null \
      | wc -l | tr -d '[:space:]'
  )"
  valid_viewport_count="$(
    find "$artifacts_root" -type f \
      -path "*/issue342-network-faults/${stem}-viewport.png" \
      -exec bash -c 'source "$1"; authoritative_terminal_viewport_png_is_valid "$2"' \
        bash "${BASH_SOURCE[0]}" {} \; -print 2>/dev/null \
      | wc -l | tr -d '[:space:]'
  )"
  text_count="$(
    find "$artifacts_root" -type f \
      -path "*/issue342-network-faults/${stem}-visible-terminal.txt" -size +0c 2>/dev/null \
      | wc -l | tr -d '[:space:]'
  )"
  [[ "$viewport_count" == "1" && "$valid_viewport_count" == "1" && "$text_count" == "1" ]]
}

require_exact_brief_ride_through_method() {
  local results_root="$1" artifacts_root="$2" class_name="$3" method_name="$4"
  local artifacts valid_artifacts

  if [[ "$class_name" != "$EXPECTED_BRIEF_RIDE_THROUGH_CLASS" ||
        "$method_name" != "$EXPECTED_BRIEF_RIDE_THROUGH_METHOD" ]]; then
    echo "FAIL: phase-2 brief guard received the wrong 5-second RideThrough selector: $class_name#$method_name" >&2
    return 1
  fi
  require_exact_successful_junit_method "$results_root" "$class_name" "$method_name" || return 1
  artifacts="$(
    find "$artifacts_root" -type f -path '*/issue342-network-faults/brief-ride-through-timeline.txt' 2>/dev/null \
      | wc -l | tr -d '[:space:]'
  )"
  valid_artifacts="$(
    find "$artifacts_root" -type f -path '*/issue342-network-faults/brief-ride-through-timeline.txt' \
      -exec bash -c 'source "$1"; brief_ride_through_artifact_is_valid "$2"' \
        bash "${BASH_SOURCE[0]}" {} \; -print 2>/dev/null \
      | wc -l | tr -d '[:space:]'
  )"
  if [[ "$artifacts" != "1" || "$valid_artifacts" != "1" ]]; then
    echo "FAIL: expected exactly one complete brief ride-through artifact, found total=${artifacts:-0} valid=${valid_artifacts:-0}: issue342-network-faults/brief-ride-through-timeline.txt" >&2
    return 1
  fi
  if ! authoritative_terminal_artifact_pair_is_valid "$artifacts_root" "brief-pre-cut" ||
    ! authoritative_terminal_artifact_pair_is_valid "$artifacts_root" "brief-post-restore"; then
    echo "FAIL: brief ride-through is missing a non-empty authoritative viewport/text artifact pair" >&2
    return 1
  fi
  echo "PASS: exact brief nightly fault method executed once, unskipped, successful, with a complete non-vacuous timeline: $class_name#$method_name"
  return 0
}

require_exact_clean_close_control_method() {
  local results_root="$1" artifacts_root="$2" class_name="$3" method_name="$4"
  local artifacts valid_artifacts

  require_exact_successful_junit_method "$results_root" "$class_name" "$method_name" || return 1
  artifacts="$(
    find "$artifacts_root" -type f -path '*/issue342-network-faults/clean-close-control-timeline.txt' 2>/dev/null \
      | wc -l | tr -d '[:space:]'
  )"
  valid_artifacts="$(
    find "$artifacts_root" -type f -path '*/issue342-network-faults/clean-close-control-timeline.txt' \
      -exec bash -c 'source "$1"; clean_close_control_artifact_is_valid "$2"' \
        bash "${BASH_SOURCE[0]}" {} \; -print 2>/dev/null \
      | wc -l | tr -d '[:space:]'
  )"
  if [[ "$artifacts" != "1" || "$valid_artifacts" != "1" ]]; then
    echo "FAIL: expected exactly one valid clean-close positive-control artifact (the brief journey's silent observer must be PROVEN able to fire), found total=${artifacts:-0} valid=${valid_artifacts:-0}: issue342-network-faults/clean-close-control-timeline.txt" >&2
    return 1
  fi
  if ! authoritative_terminal_artifact_pair_is_valid "$artifacts_root" "control-pre-close" ||
    ! authoritative_terminal_artifact_pair_is_valid "$artifacts_root" "control-post-recovery"; then
    echo "FAIL: clean-close positive control is missing a non-empty authoritative viewport/text artifact pair" >&2
    return 1
  fi
  echo "PASS: exact clean-close positive control executed once, unskipped, successful, with an observer that demonstrably fired: $class_name#$method_name"
  return 0
}

# Self-test helper: derive a deterministic malformed PNG from a valid one.
# Each mode targets a distinct branch of the portable decoder so a single
# lenient branch cannot make the whole PNG check vacuous.
nightly_guard_selftest_mutate_png() {
  local source_png="$1" target_png="$2" mode="$3"
  python3 - "$source_png" "$target_png" "$mode" <<'PY'
import pathlib
import struct
import sys
import zlib

source, target, mode = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
data = bytearray(source.read_bytes())


def first_idat():
    pos = 8
    while pos + 12 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        if data[pos + 4:pos + 8] == b"IDAT" and length:
            return pos, length
        pos += 12 + length
    raise SystemExit("missing IDAT in valid PNG self-test fixture")


def rewrite_idat(payload):
    pos, length = first_idat()
    chunk = struct.pack(">I", len(payload)) + b"IDAT" + payload
    chunk += struct.pack(">I", zlib.crc32(b"IDAT" + payload) & 0xffffffff)
    return data[:pos] + bytearray(chunk) + data[pos + 12 + length:]


if mode == "idat-crc-only":
    # Payload and zlib stream stay valid; only the stored CRC is wrong, so the
    # CRC branch is the ONLY branch that can reject this one.
    pos, length = first_idat()
    data[pos + 8 + length] ^= 0xff
elif mode == "idat-bitflip":
    # Corrupt the payload and deliberately leave the stored CRC stale.
    pos, _ = first_idat()
    data[pos + 8] ^= 0xff
elif mode == "idat-bitflip-crc-fixed":
    # Corrupt the zlib stream but repair the CRC, so only the decompress
    # branch can reject it.
    pos, length = first_idat()
    payload = bytearray(data[pos + 8:pos + 8 + length])
    payload[0] ^= 0xff
    data = rewrite_idat(bytes(payload))
elif mode == "idat-truncated-scanline":
    # Valid CRC and valid zlib stream, but one byte short of the declared
    # scanline geometry, so only the decoded-length branch can reject it.
    data = rewrite_idat(zlib.compress(b"\x00" * 1))
elif mode == "signature":
    data[1] ^= 0xff
elif mode == "truncated":
    data = data[:len(data) - 5]
elif mode == "trailing-garbage":
    data.extend(b"pocketshell-trailing-bytes")
elif mode == "text":
    data = bytearray(b"not a png, just a text artifact\n")
elif mode == "empty":
    data = bytearray()
else:
    raise SystemExit("unknown PNG mutation mode: " + mode)

target.write_bytes(bytes(data))
PY
}

nightly_exact_method_guard_self_test() {
  local fixture_root results_root artifacts_root class_name method_name
  fixture_root="$(mktemp -d)"
  results_root="$fixture_root/results"
  artifacts_root="$fixture_root/artifacts"
  class_name="com.pocketshell.app.proof.RideThroughInterruptionE2eTest"
  method_name="sustainedLinkCutReconnectsCleanlyWithoutHang"
  trap 'rm -rf "$fixture_root"' RETURN
  mkdir -p "$results_root" "$artifacts_root/device/issue342-network-faults"

  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: an absent method passed" >&2
    return 1
  fi

  printf '<testsuite><testcase name="%s" classname="%s"><skipped /></testcase></testsuite>\n' \
    "$method_name" "$class_name" >"$results_root/TEST-guard.xml"
  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: a skipped method passed" >&2
    return 1
  fi

  printf '<testsuite><testcase name="%s" classname="%s"><failure /></testcase></testsuite>\n' \
    "$method_name" "$class_name" >"$results_root/TEST-guard.xml"
  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: a failed method passed" >&2
    return 1
  fi

  printf '<testsuite>\n  <testcase name="%s" classname="%s" />\n</testsuite>\n' \
    "$method_name" "$class_name" >"$results_root/TEST-guard.xml"
  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: a missing positive-band artifact passed" >&2
    return 1
  fi

  printf 'reconnecting_band_appeared=false\nsettled_failed_pre_empted=false\n' \
    >"$artifacts_root/device/issue342-network-faults/recovery-band-longcut.txt"
  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: an invalid positive-band artifact passed" >&2
    return 1
  fi

  printf 'reconnecting_band_appeared=true\nsettled_failed_pre_empted=false\n' \
    >"$artifacts_root/device/issue342-network-faults/recovery-band-longcut.txt"
  require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null || {
    echo "SELF-TEST FAIL: a successful exact method with a valid artifact failed" >&2
    return 1
  }

  local brief_method="briefLinkCutRidesThroughWithoutDisconnectOrTeardown"
  local control_method="cleanCloseControlSurfacesHonestRecoveryToTheSameObserver"
  local brief_artifact="$artifacts_root/device/issue342-network-faults/brief-ride-through-timeline.txt"
  local control_artifact="$artifacts_root/device/issue342-network-faults/clean-close-control-timeline.txt"
  local valid_png_base64='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='
  local mutant

  printf '<testsuite>
  <testcase name="%s" classname="%s" />
  <testcase name="%s" classname="%s" />
</testsuite>
' "$method_name" "$class_name" "$brief_method" "$class_name" >"$results_root/TEST-guard.xml"
  cat >"$brief_artifact" <<'EOF'
phase_pre_install_executed=true
phase_toxic_installed_executed=true
phase_toxic_cleared_executed=true
phase_post_restore_observation_executed=true
brief_cut_target_ms=5000
brief_cut_actual_ms=5001
post_restore_target_ms=4000
post_restore_observed_ms=4000
detector_reproduction=physical-half-open-production-defaults
production_liveness_probe_interval_ms=7000
production_liveness_probe_timeout_ms=5000
production_liveness_probe_failure_threshold=4
production_half_open_detection_budget_ms=48000
passive_disconnect_grace_ms=60000
controller_grace_ms=90000
brief_is_below_production_detection_budget=true
sentinel_before_cut_completed=true
sentinel_during_cut_completed=false
sentinel_during_cut_marker_present=false
sentinel_same_connection_after_restore_completed=true
sentinel_same_connection_after_restore_outcome_completed=true
sentinel_same_connection_after_restore_marker_verified=true
sentinel_after_restore_completed=true
app_server_marker_verified=true
phase_completed=true
failure=none
observed_ticks=27
phase_boundaries_monotonic:
  t=1ms phase=pre_install
  t=5001ms phase=toxic_cleared
recovery_and_identity_snapshots:
  phase=fault_engaged t=1ms status=Connected pill=false attachingHold=false retryNow=false progressRow=false settledFailed=false recoveryInProgress=false attempts=1 generation=1 connectJobActive=false clientIdentity=1 clientDisconnected=false controllerState=Live serverClients=[1] sentinelCompleted=false sentinelRemoteMarkerPresent=false connectionEvents=0 journalEvents=0 forbiddenEvents=[]
  phase=post_restore t=9001ms status=Connected pill=false attachingHold=false retryNow=false progressRow=false settledFailed=false recoveryInProgress=false attempts=1 generation=1 connectJobActive=false clientIdentity=1 clientDisconnected=false controllerState=Live serverClients=[1] sentinelCompleted=false sentinelRemoteMarkerPresent=false connectionEvents=0 journalEvents=0 forbiddenEvents=[]
typed_connection_and_controller_timeline:
EOF
  for stem in brief-pre-cut brief-post-restore; do
    printf '%s' "$valid_png_base64" \
      | base64 --decode >"$artifacts_root/device/issue342-network-faults/${stem}-viewport.png"
    printf 'BEFORE-or-APP-RESTORED\n' \
      >"$artifacts_root/device/issue342-network-faults/${stem}-visible-terminal.txt"
  done
  require_exact_brief_ride_through_method \
    "$results_root" "$artifacts_root" "$class_name" "$brief_method" >/dev/null || {
    echo "SELF-TEST FAIL: valid brief method/artifact failed" >&2
    return 1
  }

  if require_exact_brief_ride_through_method \
    "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: wrong method selector was accepted by the brief guard" >&2
    return 1
  else
    echo "ok   [brief selector] sustained method cannot satisfy the 5-second brief guard"
  fi

  for mutant in \
    's/sentinel_during_cut_completed=false/sentinel_during_cut_completed=true/|unengaged toxic' \
    's/sentinel_during_cut_marker_present=false/sentinel_during_cut_marker_present=true/|sentinel reached server' \
    's/sentinel_before_cut_completed=true/sentinel_before_cut_completed=false/|missing baseline' \
    's/sentinel_same_connection_after_restore_outcome_completed=true/sentinel_same_connection_after_restore_outcome_completed=false/|missing original outcome completion' \
    's/sentinel_same_connection_after_restore_marker_verified=true/sentinel_same_connection_after_restore_marker_verified=false/|missing original server marker' \
    's/sentinel_same_connection_after_restore_completed=true/sentinel_same_connection_after_restore_completed=false/|unproven same-connection restore' \
    's/sentinel_after_restore_completed=true/sentinel_after_restore_completed=false/|unproven restore' \
    's/app_server_marker_verified=true/app_server_marker_verified=false/|missing server marker' \
    's/brief_cut_actual_ms=5001/brief_cut_actual_ms=1200/|short cut' \
    's/post_restore_observed_ms=4000/post_restore_observed_ms=900/|short settle' \
    's/detector_reproduction=physical-half-open-production-defaults/detector_reproduction=synthetic-fast-eager-liveness-seam/|wrong reproduction class' \
    's/production_half_open_detection_budget_ms=48000/production_half_open_detection_budget_ms=5000/|wrong detector budget' \
    's/observed_ticks=27/observed_ticks=1/|no sampled window' \
    's/ clientDisconnected=false / clientDisconnected=true /|disconnected client' \
    's/controllerState=Live/controllerState=Reconnecting/|non-Live controller' \
    's/forbiddenEvents=\[\]/forbiddenEvents=[connection\/reconnect#9]/|forbidden typed event in snapshot'; do
    cp "$brief_artifact" "$brief_artifact.orig"
    sed -i "${mutant%%|*}" "$brief_artifact"
    if require_exact_brief_ride_through_method \
      "$results_root" "$artifacts_root" "$class_name" "$brief_method" >/dev/null 2>&1; then
      echo "SELF-TEST FAIL: vacuous brief artifact passed: ${mutant##*|}" >&2
      return 1
    fi
    mv "$brief_artifact.orig" "$brief_artifact"
  done

  cp "$brief_artifact" "$brief_artifact.orig"
  sed -i '/^typed_connection_and_controller_timeline:$/a\  t=1ms sequence=9 category=connection name=reconnect metadata={cause=forbidden-test}' \
    "$brief_artifact"
  if require_exact_brief_ride_through_method \
    "$results_root" "$artifacts_root" "$class_name" "$brief_method" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: forbidden typed timeline event passed" >&2
    return 1
  fi
  mv "$brief_artifact.orig" "$brief_artifact"
  echo "ok   [typed timeline mutation] forbidden event is rejected"

  local malformed_png="$artifacts_root/device/issue342-network-faults/brief-post-restore-viewport.png"
  local png_mutation png_mutation_mode png_mutation_label png_identify_mode
  cp "$malformed_png" "$malformed_png.orig"
  # The valid fixture must be accepted first, otherwise every rejection below
  # could be a false negative from a broken fixture. Check both host shapes:
  # with ImageMagick (developer boxes) and without it (the CI runner).
  authoritative_terminal_viewport_png_decodes_in_python "$malformed_png.orig" || {
    echo "SELF-TEST FAIL: valid viewport PNG rejected by the portable decoder" >&2
    return 1
  }
  for png_identify_mode in 0 1; do
    export NIGHTLY_GUARD_FORCE_NO_IDENTIFY="$png_identify_mode"
    authoritative_terminal_viewport_png_is_valid "$malformed_png.orig" || {
      echo "SELF-TEST FAIL: valid viewport PNG rejected by the decoder (force_no_identify=$png_identify_mode)" >&2
      return 1
    }
    authoritative_terminal_artifact_pair_is_valid "$artifacts_root" brief-post-restore || {
      echo "SELF-TEST FAIL: valid viewport pair rejected (force_no_identify=$png_identify_mode)" >&2
      return 1
    }
  done
  export NIGHTLY_GUARD_FORCE_NO_IDENTIFY=0
  for png_mutation in \
    'idat-crc-only|wrong IDAT chunk CRC over an intact payload' \
    'idat-bitflip|stale IDAT chunk CRC' \
    'idat-bitflip-crc-fixed|corrupt IDAT zlib stream with a recomputed CRC' \
    'idat-truncated-scanline|short decoded scanline with a recomputed CRC' \
    'signature|corrupt PNG signature' \
    'truncated|truncated chunk stream' \
    'trailing-garbage|bytes appended after IEND' \
    'text|plain text with a .png name' \
    'empty|empty file'; do
    png_mutation_mode="${png_mutation%%|*}"
    png_mutation_label="${png_mutation##*|}"
    nightly_guard_selftest_mutate_png \
      "$malformed_png.orig" "$malformed_png" "$png_mutation_mode" || {
      echo "SELF-TEST FAIL: could not build PNG mutant: $png_mutation_label" >&2
      return 1
    }
    # Assert the portable Python decoder DIRECTLY, not only through the wrapper:
    # it is the only decoder present on the CI runner.
    if authoritative_terminal_viewport_png_decodes_in_python "$malformed_png"; then
      echo "SELF-TEST FAIL: malformed PNG mutation passed the portable decoder: $png_mutation_label" >&2
      return 1
    fi
    # Then assert the wrapper and the pair check under BOTH host shapes. The
    # force_no_identify=1 pass is the load-bearing one: it is the CI runner's
    # configuration, and it is where an unchecked Python exit status turns the
    # whole PNG check vacuous while an ImageMagick-equipped host stays green.
    for png_identify_mode in 0 1; do
      export NIGHTLY_GUARD_FORCE_NO_IDENTIFY="$png_identify_mode"
      if authoritative_terminal_viewport_png_is_valid "$malformed_png"; then
        echo "SELF-TEST FAIL: malformed PNG mutation passed the decoder (force_no_identify=$png_identify_mode): $png_mutation_label" >&2
        return 1
      fi
      if authoritative_terminal_artifact_pair_is_valid "$artifacts_root" brief-post-restore; then
        echo "SELF-TEST FAIL: malformed PNG mutation passed the artifact pair check (force_no_identify=$png_identify_mode): $png_mutation_label" >&2
        return 1
      fi
    done
    export NIGHTLY_GUARD_FORCE_NO_IDENTIFY=0
    cp "$malformed_png.orig" "$malformed_png"
  done
  mv "$malformed_png.orig" "$malformed_png"
  unset NIGHTLY_GUARD_FORCE_NO_IDENTIFY
  echo "ok   [PNG mutation] malformed authoritative viewport is rejected on hosts with and without ImageMagick"

  rm "$artifacts_root/device/issue342-network-faults/brief-post-restore-viewport.png"
  if require_exact_brief_ride_through_method \
    "$results_root" "$artifacts_root" "$class_name" "$brief_method" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: missing authoritative brief viewport passed" >&2
    return 1
  fi
  printf '%s' "$valid_png_base64" \
    | base64 --decode >"$artifacts_root/device/issue342-network-faults/brief-post-restore-viewport.png"

  printf '<testsuite>
  <testcase name="%s" classname="%s" />
  <testcase name="%s" classname="%s" />
  <testcase name="%s" classname="%s" />
</testsuite>
' "$method_name" "$class_name" "$brief_method" "$class_name" \
    "$control_method" "$class_name" >"$results_root/TEST-guard.xml"
  if require_exact_clean_close_control_method \
    "$results_root" "$artifacts_root" "$class_name" "$control_method" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: a missing positive-control artifact passed" >&2
    return 1
  fi
  cat >"$control_artifact" <<'EOF'
test=RideThroughInterruptionE2eTest#cleanCloseControlSurfacesHonestRecoveryToTheSameObserver
production_disconnect_triggered=true
observer_detected_recovery=true
observer_recovery_detected_ms=1840
observer_first_violations=[vm_status=Reconnecting, reconnecting_pill]
typed_reader_eof_detected=true
typed_reader_eof_event_name=tmux_client_reader_exit
typed_reader_eof_disconnect_reason=reader_eof
typed_reader_eof_source=missing
typed_reader_eof_message=missing
typed_reader_eof_event_elapsed_ms=1841
app_server_marker_verified=true
failure=none
  observer_ticks:
EOF
  cat >"$artifacts_root/device/issue342-network-faults/reader-eof-control.txt" <<'EOF'
sequence=12 category=connection name=tmux_client_reader_exit metadata={disconnectReason=reader_eof}
EOF
  for stem in control-pre-close control-post-recovery; do
    printf '%s' "$valid_png_base64" \
      | base64 --decode >"$artifacts_root/device/issue342-network-faults/${stem}-viewport.png"
    printf 'BEFORE-or-CONTROL-RECOVERED\n' \
      >"$artifacts_root/device/issue342-network-faults/${stem}-visible-terminal.txt"
  done
  require_exact_clean_close_control_method \
    "$results_root" "$artifacts_root" "$class_name" "$control_method" >/dev/null || {
    echo "SELF-TEST FAIL: valid positive-control artifact failed" >&2
    return 1
  }

  local visible_token
  for visible_token in \
    vm_status=Idle \
    vm_status=Connecting \
    vm_status=Switching \
    vm_status=Reconnecting \
    vm_status=Failed \
    reconnecting_pill \
    attaching_hold \
    reconnect_band_retry_now \
    connecting_progress_row \
    settled_failed_band; do
    cp "$control_artifact" "$control_artifact.orig"
    sed -E -i "s/^observer_first_violations=.*/observer_first_violations=[$visible_token]/" \
      "$control_artifact"
    if ! require_exact_clean_close_control_method \
      "$results_root" "$artifacts_root" "$class_name" "$control_method" >/dev/null 2>&1; then
      echo "SELF-TEST FAIL: visible recovery token rejected: $visible_token" >&2
      return 1
    fi
    mv "$control_artifact.orig" "$control_artifact"
  done

  for mutant in \
    's/production_disconnect_triggered=true/production_disconnect_triggered=false/|missing production disconnect trigger' \
    's/observer_detected_recovery=true/observer_detected_recovery=false/|blind observer' \
    's/typed_reader_eof_detected=true/typed_reader_eof_detected=false/|missing typed reader EOF' \
    's/typed_reader_eof_disconnect_reason=reader_eof/typed_reader_eof_disconnect_reason=unknown/|missing typed reader cause' \
    's/observer_first_violations=\[vm_status=Reconnecting, reconnecting_pill\]/observer_first_violations=[connect_generation=2]/|internal-only observer violation' \
    's/observer_first_violations=\[.*\]/observer_first_violations=[]/|empty violation set' \
    's/observer_first_violations=\[vm_status=Reconnecting, reconnecting_pill\]/observer_first_violations=[vm_status=Connected]/|non-recovery status token' \
    's/app_server_marker_verified=true/app_server_marker_verified=false/|missing control marker' \
    's/failure=none/failure=AssertionError:boom/|failed control run'; do
    cp "$control_artifact" "$control_artifact.orig"
    sed -i "${mutant%%|*}" "$control_artifact"
    if require_exact_clean_close_control_method \
      "$results_root" "$artifacts_root" "$class_name" "$control_method" >/dev/null 2>&1; then
      echo "SELF-TEST FAIL: vacuous positive-control artifact passed: ${mutant##*|}" >&2
      return 1
    fi
    mv "$control_artifact.orig" "$control_artifact"
  done

  rm "$artifacts_root/device/issue342-network-faults/control-post-recovery-visible-terminal.txt"
  if require_exact_clean_close_control_method \
    "$results_root" "$artifacts_root" "$class_name" "$control_method" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: missing authoritative control text passed" >&2
    return 1
  fi

  echo "nightly-exact-method-guard self-test: PASS"
}

if [[ "${BASH_SOURCE[0]}" == "$0" && "${1:-}" == "--self-test" ]]; then
  nightly_exact_method_guard_self_test
fi
