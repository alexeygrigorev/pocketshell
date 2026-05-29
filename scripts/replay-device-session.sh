#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/device-sessions}"
REPLAY_SPEED="${REPLAY_SPEED:-1}"
MIN_SLEEP_SECONDS="${MIN_SLEEP_SECONDS:-0.000001}"

if [[ -n "${ADB_SERIAL:-}" && -z "${ANDROID_SERIAL:-}" ]]; then
  export ANDROID_SERIAL="$ADB_SERIAL"
fi

# Avoid MSYS rewriting Android device paths such as /dev/input/eventN.
export MSYS_NO_PATHCONV="${MSYS_NO_PATHCONV:-1}"

usage() {
  cat <<'USAGE'
Usage: scripts/replay-device-session.sh <run-id>

Replays the raw touchscreen events captured by capture-device-session.sh with
the original inter-event timing. The script auto-detects the current touchscreen
device and substitutes it for the captured eventN path, so event numbering can
change between capture and replay.

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  ANDROID_SERIAL=<adb serial> or ADB_SERIAL=<adb serial>
  LOG_ROOT=build/device-sessions
  REPLAY_SPEED=1          2 means twice as fast; 0.5 means half speed
  MIN_SLEEP_SECONDS=0.000001 skip smaller timing gaps in generated replay script
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Artifacts: %s\n' "$RUN_DIR" >&2
  exit 1
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -x "$path" ]] || {
    printf 'FAIL: %s is not executable at %s\n' "$label" "$path" >&2
    exit 1
  }
}

adb_base() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    "$ADB" -s "$ANDROID_SERIAL" "$@"
  else
    "$ADB" "$@"
  fi
}

trim_carriage_returns() {
  tr -d '\r'
}

resolve_serial() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_base get-state >/dev/null
    adb_base get-serialno | trim_carriage_returns
    return
  fi

  local serials
  serials="$("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')"
  local count
  count="$(printf '%s\n' "$serials" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [[ "$count" != "1" ]]; then
    printf 'Connected adb devices:\n' >&2
    "$ADB" devices -l >&2 || true
    printf '\nSet ANDROID_SERIAL or ADB_SERIAL when zero or multiple devices are connected.\n' >&2
    exit 1
  fi
  ANDROID_SERIAL="$serials"
  export ANDROID_SERIAL
  printf '%s\n' "$ANDROID_SERIAL"
}

detect_touchscreen_device_from_dump() {
  awk '
    function emit() {
      if (dev != "" && has_x && has_y) {
        event_num = dev
        sub(/^.*event/, "", event_num)
        print (direct ? 2 : 1), event_num, dev
      }
    }
    /^add device[[:space:]]+[0-9]+:/ {
      emit()
      dev = $4
      direct = 0
      has_x = 0
      has_y = 0
      next
    }
    /INPUT_PROP_DIRECT/ { direct = 1 }
    /ABS_MT_POSITION_X|(^|[[:space:]])0035([[:space:]]|$)/ { has_x = 1 }
    /ABS_MT_POSITION_Y|(^|[[:space:]])0036([[:space:]]|$)/ { has_y = 1 }
    END { emit() }
  ' | sort -k1,1nr -k2,2n | awk 'NR == 1 {print $3}'
}

metadata_value() {
  local key="$1"
  local file="$2"
  awk -F= -v key="$key" '$1 == key {print substr($0, length(key) + 2); exit}' "$file"
}

generate_replay_script() {
  local trace_file="$1"
  local script_file="$2"
  local touch_device="$3"
  local source_touch_device="$4"
  local speed="$5"
  local min_sleep="$6"

  awk \
    -v touch_device="$touch_device" \
    -v source_touch_device="$source_touch_device" \
    -v speed="$speed" \
    -v min_sleep="$min_sleep" '
    BEGIN {
      if (speed <= 0) {
        print "REPLAY_SPEED must be > 0" > "/dev/stderr"
        exit 2
      }
      printf "#!/system/bin/sh\n"
      printf "set -eu\n"
      printf "TOUCH_DEVICE=%s\n", touch_device
    }
    function hex_to_dec(value, i, c, n, result) {
      sub(/^0x/, "", value)
      result = 0
      for (i = 1; i <= length(value); i++) {
        c = substr(value, i, 1)
        if (c >= "0" && c <= "9") {
          n = c + 0
        } else if (c >= "a" && c <= "f") {
          n = index("abcdef", c) + 9
        } else if (c >= "A" && c <= "F") {
          n = index("ABCDEF", c) + 9
        } else {
          return ""
        }
        result = result * 16 + n
      }
      if (length(value) == 8 && result >= 2147483648) {
        result = result - 4294967296
      }
      return result
    }
    function event_number(token) {
      if (token == "EV_SYN" || token == "SYN_REPORT") return 0
      if (token == "EV_KEY" || token == "BTN_TOUCH" || token == "BTN_TOOL_FINGER" || token == "BTN_TOOL_DOUBLETAP" || token == "BTN_TOOL_TRIPLETAP" || token == "BTN_TOOL_QUADTAP" || token == "BTN_TOOL_QUINTTAP") return 1
      if (token == "EV_ABS" || token == "ABS_X" || token == "ABS_Y" || token == "ABS_MT_SLOT" || token == "ABS_MT_TOUCH_MAJOR" || token == "ABS_MT_TOUCH_MINOR" || token == "ABS_MT_WIDTH_MAJOR" || token == "ABS_MT_WIDTH_MINOR" || token == "ABS_MT_ORIENTATION" || token == "ABS_MT_POSITION_X" || token == "ABS_MT_POSITION_Y" || token == "ABS_MT_TOOL_TYPE" || token == "ABS_MT_BLOB_ID" || token == "ABS_MT_TRACKING_ID" || token == "ABS_MT_PRESSURE" || token == "ABS_PRESSURE") return 3
      return ""
    }
    function code_number(type, token) {
      if (type == 0 && token == "SYN_REPORT") return 0
      if (type == 1 && token == "BTN_TOUCH") return 330
      if (type == 1 && token == "BTN_TOOL_FINGER") return 325
      if (type == 1 && token == "BTN_TOOL_DOUBLETAP") return 333
      if (type == 1 && token == "BTN_TOOL_TRIPLETAP") return 334
      if (type == 1 && token == "BTN_TOOL_QUADTAP") return 335
      if (type == 1 && token == "BTN_TOOL_QUINTTAP") return 328
      if (type == 3 && token == "ABS_X") return 0
      if (type == 3 && token == "ABS_Y") return 1
      if (type == 3 && token == "ABS_PRESSURE") return 24
      if (type == 3 && token == "ABS_MT_SLOT") return 47
      if (type == 3 && token == "ABS_MT_TOUCH_MAJOR") return 48
      if (type == 3 && token == "ABS_MT_TOUCH_MINOR") return 49
      if (type == 3 && token == "ABS_MT_WIDTH_MAJOR") return 50
      if (type == 3 && token == "ABS_MT_WIDTH_MINOR") return 51
      if (type == 3 && token == "ABS_MT_ORIENTATION") return 52
      if (type == 3 && token == "ABS_MT_POSITION_X") return 53
      if (type == 3 && token == "ABS_MT_POSITION_Y") return 54
      if (type == 3 && token == "ABS_MT_TOOL_TYPE") return 55
      if (type == 3 && token == "ABS_MT_BLOB_ID") return 56
      if (type == 3 && token == "ABS_MT_TRACKING_ID") return 57
      if (type == 3 && token == "ABS_MT_PRESSURE") return 58
      return ""
    }
    function numeric_field(field) {
      if (field ~ /^[0-9a-fA-F]+$/) {
        return hex_to_dec(field)
      }
      return ""
    }
    function event_value(field) {
      if (field == "DOWN") return 1
      if (field == "UP") return 0
      return hex_to_dec(field)
    }
    function parse_event(fields, count, type, code, value, maybe_type) {
      type = numeric_field(fields[count - 2])
      code = numeric_field(fields[count - 1])
      value = event_value(fields[count])
      if (type != "" && code != "" && value != "") {
        parsed_type = type
        parsed_code = code
        parsed_value = value
        return 1
      }

      maybe_type = event_number(fields[count - 2])
      if (maybe_type == "") {
        return 0
      }
      code = code_number(maybe_type, fields[count - 1])
      value = event_value(fields[count])
      if (code == "" || value == "") {
        return 0
      }
      parsed_type = maybe_type
      parsed_code = code
      parsed_value = value
      return 1
    }
    /^\[/ {
      timestamp = $2
      sub(/\]/, "", timestamp)
      if (timestamp !~ /^[0-9]+\.[0-9]+$/) {
        skipped++
        next
      }
      device = ""
      event_start = 3
      if ($3 ~ /^\/dev\/input\/event[0-9]+:$/) {
        device = $3
        sub(/:$/, "", device)
        event_start = 4
      }
      if (source_touch_device != "" && device != "" && device != source_touch_device) {
        next
      }
      split("", fields)
      count = 0
      for (i = event_start; i <= NF; i++) {
        if ($i != "") {
          count++
          fields[count] = $i
        }
      }
      if (count < 3 || !parse_event(fields, count)) {
        skipped++
        next
      }
      if (seen) {
        delay = (timestamp - previous_timestamp) / speed
        if (delay > 0 && delay >= min_sleep) {
          printf "sleep %.6f\n", delay
        }
      }
      printf "sendevent \"$TOUCH_DEVICE\" %d %d %d\n", parsed_type, parsed_code, parsed_value
      previous_timestamp = timestamp
      seen = 1
      emitted++
    }
    END {
      if (!emitted) {
        print "no replayable touchscreen events found in trace" > "/dev/stderr"
        exit 3
      }
      if (skipped) {
        printf "# skipped_unparsed_events=%d\n", skipped
      }
    }
  ' "$trace_file" > "$script_file"
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

RUN_ID="${1:-}"
if [[ -z "$RUN_ID" ]]; then
  usage >&2
  exit 1
fi
if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9_.-]+$ || "$RUN_ID" == .* || "$RUN_ID" == *..* ]]; then
  printf 'FAIL: run-id must use only letters, numbers, dot, underscore, and dash, got %q\n' "$RUN_ID" >&2
  exit 1
fi

if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_DIR="$LOG_ROOT/$RUN_ID"
METADATA_FILE="$RUN_DIR/metadata.env"
TRACE_FILE="$RUN_DIR/getevent-touchscreen.txt"
GETEVENT_LP="$RUN_DIR/replay-getevent-lp.txt"
REPLAY_SCRIPT="$RUN_DIR/replay-sendevent.sh"
REPLAY_LOG="$RUN_DIR/replay.log"
REMOTE_REPLAY="/data/local/tmp/pocketshell-replay-$RUN_ID.sh"

[[ -d "$RUN_DIR" ]] || {
  printf 'FAIL: no such captured session directory: %s\n' "$RUN_DIR" >&2
  exit 1
}
[[ -s "$TRACE_FILE" ]] || fail "missing or empty $TRACE_FILE"
[[ -f "$METADATA_FILE" ]] || fail "missing $METADATA_FILE"

require_executable "$ADB" adb
DEVICE_SERIAL="$(resolve_serial)"
adb_base shell getevent -lp | trim_carriage_returns > "$GETEVENT_LP"
TOUCH_DEVICE="$(detect_touchscreen_device_from_dump < "$GETEVENT_LP")"
if [[ -z "$TOUCH_DEVICE" ]]; then
  fail "could not auto-detect a direct touchscreen input device from adb shell getevent -lp"
fi

SOURCE_TOUCH_DEVICE="$(metadata_value TOUCH_DEVICE "$METADATA_FILE")"
SOURCE_SCREEN_SIZE="$(metadata_value SCREEN_SIZE "$METADATA_FILE")"
CURRENT_SCREEN_SIZE="$(adb_base shell wm size | trim_carriage_returns)"

generate_replay_script \
  "$TRACE_FILE" \
  "$REPLAY_SCRIPT" \
  "$TOUCH_DEVICE" \
  "$SOURCE_TOUCH_DEVICE" \
  "$REPLAY_SPEED" \
  "$MIN_SLEEP_SECONDS"
chmod +x "$REPLAY_SCRIPT"

{
  printf 'timestamp=%s\n' "$(date -Is)"
  printf 'device_serial=%s\n' "$DEVICE_SERIAL"
  printf 'captured_touch_device=%s\n' "$SOURCE_TOUCH_DEVICE"
  printf 'replay_touch_device=%s\n' "$TOUCH_DEVICE"
  printf 'captured_screen_size=%s\n' "$SOURCE_SCREEN_SIZE"
  printf 'replay_screen_size=%s\n' "$CURRENT_SCREEN_SIZE"
  printf 'replay_script=%s\n' "$REPLAY_SCRIPT"
} > "$RUN_DIR/replay-summary.txt"

if [[ -n "$SOURCE_SCREEN_SIZE" && "$SOURCE_SCREEN_SIZE" != "$CURRENT_SCREEN_SIZE" ]]; then
  printf 'WARN: captured screen size differs from current screen size:\n' >&2
  printf '  captured: %s\n' "$SOURCE_SCREEN_SIZE" >&2
  printf '  current:  %s\n' "$CURRENT_SCREEN_SIZE" >&2
  printf 'Raw touchscreen units are replayed unchanged; use the same Pixel 7a orientation/resolution for deterministic results.\n' >&2
fi

printf 'Replaying %s on %s\n' "$RUN_ID" "$DEVICE_SERIAL"
printf 'Captured touchscreen: %s\n' "$SOURCE_TOUCH_DEVICE"
printf 'Replay touchscreen:   %s\n' "$TOUCH_DEVICE"
printf 'Replay script: %s\n' "$REPLAY_SCRIPT"

adb_base push "$REPLAY_SCRIPT" "$REMOTE_REPLAY" > "$RUN_DIR/adb-push-replay.log"
adb_base shell chmod 700 "$REMOTE_REPLAY"
adb_base shell sh "$REMOTE_REPLAY" 2>&1 | tee "$REPLAY_LOG"
adb_base shell rm -f "$REMOTE_REPLAY" >/dev/null 2>&1 || true
printf 'Replay complete. Log: %s\n' "$REPLAY_LOG"
