#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/device-sessions}"
CAPTURE_SECONDS="${CAPTURE_SECONDS:-}"
SCREENRECORD_TIME_LIMIT="${SCREENRECORD_TIME_LIMIT:-180}"
CLEAR_LOGCAT="${CLEAR_LOGCAT:-1}"

if [[ -n "${ADB_SERIAL:-}" && -z "${ANDROID_SERIAL:-}" ]]; then
  export ANDROID_SERIAL="$ADB_SERIAL"
fi

# Avoid MSYS rewriting Android device paths such as /dev/input/eventN.
export MSYS_NO_PATHCONV="${MSYS_NO_PATHCONV:-1}"

usage() {
  cat <<'USAGE'
Usage: scripts/capture-device-session.sh <run-id>

Records a human-watchable screen video, raw touchscreen getevent trace, and
logcat into:

  build/device-sessions/<run-id>/

The capture runs until Enter is pressed. For noninteractive smoke checks, set:

  CAPTURE_SECONDS=5 scripts/capture-device-session.sh smoke

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  ANDROID_SERIAL=<adb serial> or ADB_SERIAL=<adb serial>
  LOG_ROOT=build/device-sessions
  CAPTURE_SECONDS=<seconds>
  SCREENRECORD_TIME_LIMIT=180
  CLEAR_LOGCAT=1
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

write_touch_metadata() {
  local dump_file="$1"
  local metadata_file="$2"
  local touch_device="$3"
  local screen_size="$4"
  local density="$5"

  {
    printf 'RUN_ID=%s\n' "$RUN_ID"
    printf 'DEVICE_SERIAL=%s\n' "$DEVICE_SERIAL"
    printf 'TOUCH_DEVICE=%s\n' "$touch_device"
    printf 'SCREEN_SIZE=%s\n' "$screen_size"
    printf 'SCREEN_DENSITY=%s\n' "$density"
    awk -v target="$touch_device" '
      function print_range(prefix, line) {
        min = ""
        max = ""
        if (match(line, /min[[:space:]]+-?[0-9]+/)) {
          min = substr(line, RSTART + 4, RLENGTH - 4)
        }
        if (match(line, /max[[:space:]]+-?[0-9]+/)) {
          max = substr(line, RSTART + 4, RLENGTH - 4)
        }
        if (min != "") {
          printf "%s_MIN=%s\n", prefix, min
        }
        if (max != "") {
          printf "%s_MAX=%s\n", prefix, max
        }
      }
      /^add device[[:space:]]+[0-9]+:/ { in_target = ($4 == target); next }
      in_target && /ABS_MT_POSITION_X|(^|[[:space:]])0035([[:space:]]|$)/ {
        print_range("ABS_MT_POSITION_X", $0)
      }
      in_target && /ABS_MT_POSITION_Y|(^|[[:space:]])0036([[:space:]]|$)/ {
        print_range("ABS_MT_POSITION_Y", $0)
      }
    ' "$dump_file"
  } > "$metadata_file"
}

safe_remote_name() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9_.-' '_'
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
if [[ -e "$RUN_DIR" ]]; then
  printf 'FAIL: artifact directory already exists: %s\n' "$RUN_DIR" >&2
  exit 1
fi

require_executable "$ADB" adb
mkdir -p "$RUN_DIR"

DEVICE_SERIAL="$(resolve_serial)"
ADB_DEVICE_ARGS=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB_DEVICE_ARGS=(-s "$ANDROID_SERIAL")
fi
GETEVENT_LP="$RUN_DIR/getevent-lp.txt"
TOUCH_TRACE="$RUN_DIR/getevent-touchscreen.txt"
LOGCAT_FILE="$RUN_DIR/logcat.txt"
SCREENRECORD_LOG="$RUN_DIR/screenrecord.log"
VIDEO_FILE="$RUN_DIR/screen.mp4"
METADATA_FILE="$RUN_DIR/metadata.env"
REMOTE_VIDEO="/sdcard/Download/pocketshell-device-session-$(safe_remote_name "$RUN_ID").mp4"

adb_base devices -l > "$RUN_DIR/adb-devices.txt"
adb_base shell getprop > "$RUN_DIR/getprop.txt" 2>&1 || true
adb_base shell getevent -lp | trim_carriage_returns > "$GETEVENT_LP"
TOUCH_DEVICE="$(detect_touchscreen_device_from_dump < "$GETEVENT_LP")"
if [[ -z "$TOUCH_DEVICE" ]]; then
  fail "could not auto-detect a direct touchscreen input device from adb shell getevent -lp"
fi

SCREEN_SIZE="$(adb_base shell wm size | trim_carriage_returns)"
SCREEN_DENSITY="$(adb_base shell wm density | trim_carriage_returns)"
write_touch_metadata "$GETEVENT_LP" "$METADATA_FILE" "$TOUCH_DEVICE" "$SCREEN_SIZE" "$SCREEN_DENSITY"

if [[ "$CLEAR_LOGCAT" == "1" ]]; then
  adb_base logcat -c || true
fi
adb_base shell rm -f "$REMOTE_VIDEO" >/dev/null 2>&1 || true

logcat_pid=""
getevent_pid=""
screenrecord_pid=""
stopping=0

stop_capture() {
  local exit_code=$?
  if [[ "$stopping" == "1" ]]; then
    exit "$exit_code"
  fi
  stopping=1

  printf '\nStopping capture...\n'
  if [[ -n "$screenrecord_pid" ]] && kill -0 "$screenrecord_pid" >/dev/null 2>&1; then
    kill -INT "$screenrecord_pid" >/dev/null 2>&1 || true
  fi
  adb_base shell pkill -INT screenrecord >/dev/null 2>&1 || true
  sleep 1
  if [[ -n "$screenrecord_pid" ]] && kill -0 "$screenrecord_pid" >/dev/null 2>&1; then
    kill "$screenrecord_pid" >/dev/null 2>&1 || true
    sleep 0.2
  fi
  if [[ -n "$screenrecord_pid" ]] && kill -0 "$screenrecord_pid" >/dev/null 2>&1; then
    kill -KILL "$screenrecord_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$screenrecord_pid" ]]; then
    wait "$screenrecord_pid" >/dev/null 2>&1 || true
  fi
  sleep 1

  if [[ -n "$getevent_pid" ]] && kill -0 "$getevent_pid" >/dev/null 2>&1; then
    kill "$getevent_pid" >/dev/null 2>&1 || true
    wait "$getevent_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$logcat_pid" ]] && kill -0 "$logcat_pid" >/dev/null 2>&1; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" >/dev/null 2>&1 || true
  fi

  adb_base logcat -d -v threadtime > "$RUN_DIR/logcat-final-dump.txt" 2>&1 || true
  if adb_base shell ls "$REMOTE_VIDEO" >/dev/null 2>&1; then
    adb_base pull "$REMOTE_VIDEO" "$VIDEO_FILE" > "$RUN_DIR/adb-pull-screenrecord.log" 2>&1 || true
    adb_base shell rm -f "$REMOTE_VIDEO" >/dev/null 2>&1 || true
  fi

  {
    printf 'exit_code=%s\n' "$exit_code"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'device_serial=%s\n' "$DEVICE_SERIAL"
    printf 'touch_device=%s\n' "$TOUCH_DEVICE"
    printf 'timestamp=%s\n' "$(date -Is)"
    [[ -s "$TOUCH_TRACE" ]] && printf 'getevent_lines=%s\n' "$(wc -l < "$TOUCH_TRACE" | tr -d ' ')"
    [[ -s "$VIDEO_FILE" ]] && printf 'screen_video=%s\n' "$VIDEO_FILE"
  } > "$RUN_DIR/summary.txt"

  if [[ "$exit_code" -eq 0 ]]; then
    printf 'Artifacts: %s\n' "$RUN_DIR"
    printf 'Replay: scripts/replay-device-session.sh %q\n' "$RUN_ID"
  else
    printf 'Capture exited with status %s; artifacts: %s\n' "$exit_code" "$RUN_DIR" >&2
  fi
  exit "$exit_code"
}
trap stop_capture EXIT INT TERM

printf 'Recording device session %q on %s\n' "$RUN_ID" "$DEVICE_SERIAL"
printf 'Touchscreen: %s\n' "$TOUCH_DEVICE"
printf 'Artifacts: %s\n' "$RUN_DIR"

"$ADB" "${ADB_DEVICE_ARGS[@]}" logcat -v threadtime > "$LOGCAT_FILE" 2>&1 &
logcat_pid=$!
"$ADB" "${ADB_DEVICE_ARGS[@]}" shell getevent -lt "$TOUCH_DEVICE" > "$TOUCH_TRACE" 2> "$RUN_DIR/getevent-touchscreen.err" &
getevent_pid=$!
"$ADB" "${ADB_DEVICE_ARGS[@]}" shell screenrecord --verbose --time-limit "$SCREENRECORD_TIME_LIMIT" "$REMOTE_VIDEO" > "$SCREENRECORD_LOG" 2>&1 &
screenrecord_pid=$!

sleep 1
if ! kill -0 "$getevent_pid" >/dev/null 2>&1; then
  fail "getevent capture exited early; see $RUN_DIR/getevent-touchscreen.err"
fi
if ! kill -0 "$screenrecord_pid" >/dev/null 2>&1; then
  fail "screenrecord exited early; see $SCREENRECORD_LOG"
fi

if [[ -n "$CAPTURE_SECONDS" ]]; then
  sleep "$CAPTURE_SECONDS"
else
  printf 'Perform the phone steps now, then press Enter to stop capture.\n'
  read -r _
fi
