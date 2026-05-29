#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
PACKAGE="${PACKAGE:-com.pocketshell.app}"
ACTIVITY="${ACTIVITY:-.MainActivity}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/issue271-startup-connect}"
RUN_ID="${RUN_ID:-issue-271-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
LAUNCHES="${LAUNCHES:-3}"
WAIT_AFTER_START_SEC="${WAIT_AFTER_START_SEC:-35}"
BUILD_APK="${BUILD_APK:-1}"
INSTALL_APK="${INSTALL_APK:-1}"
APP_APK="${APP_APK:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
SCENARIO="${SCENARIO:-tmux-process-death-resume}"
TASK_PRIME_WAIT_SEC="${TASK_PRIME_WAIT_SEC:-2}"
VERIFY_TMUX_MARKERS="${VERIFY_TMUX_MARKERS:-}"
TMUX_HOST_ID="${TMUX_HOST_ID:-271}"
TMUX_HOST_NAME="${TMUX_HOST_NAME:-Issue 271 Docker}"
TMUX_HOST="${TMUX_HOST:-10.0.2.2}"
TMUX_PORT="${TMUX_PORT:-2222}"
TMUX_USER="${TMUX_USER:-testuser}"
TMUX_SESSION="${TMUX_SESSION:-issue271-startup}"
TMUX_START_DIR="${TMUX_START_DIR:-}"
LOCAL_KEY_PATH="${LOCAL_KEY_PATH:-$ROOT_DIR/tests/docker/test_key}"
DEVICE_KEY_BASENAME="${DEVICE_KEY_BASENAME:-issue271_test_key.pem}"
LOGCAT_FILTER='Displayed|ProfileInstaller|userfaultfd|PsStartup|PsTmuxReconnect|issue173-sshj-guard'

if [[ -z "$VERIFY_TMUX_MARKERS" ]]; then
  if [[ "$SCENARIO" == "tmux-process-death-resume" ]]; then
    VERIFY_TMUX_MARKERS=1
  else
    VERIFY_TMUX_MARKERS=0
  fi
fi

if [[ -n "${ADB_SERIAL:-}" && -z "${ANDROID_SERIAL:-}" ]]; then
  export ANDROID_SERIAL="$ADB_SERIAL"
fi

usage() {
  cat <<'USAGE'
Usage: scripts/issue271-startup-connect-timing.sh

Measures issue #271 cold-launch to first SSH/tmux connect timing markers.

Default scenario:
  SCENARIO=tmux-process-death-resume

  The script installs the debug APK, copies LOCAL_KEY_PATH into the app's
  private files directory, creates a retained launcher task, writes a fresh
  LastSessionStore snapshot, backgrounds the task, kills the cached app process
  with `am kill`, then launches from the launcher. That gives MainActivity a
  non-null savedInstanceState and deterministically routes to TmuxSession before
  timing collection. Defaults target the deterministic Docker SSH fixture:
  testuser@10.0.2.2:2222 with tests/docker/test_key.

Other scenario:
  SCENARIO=hostlist-force-stop

  Repeats force-stop + launcher start and measures HostList startup only. This
  does not exercise tmux-screen-composed, tmux-connect-effect-start, or
  tmux-connect-attempt.

Environment:
  ADB_SERIAL=<serial>       adb device serial; forwarded to ANDROID_SERIAL
  RUN_ID=<name>             artifact directory name
  LOG_ROOT=<dir>            default build/issue271-startup-connect
  LAUNCHES=3                number of measured launches
  WAIT_AFTER_START_SEC=35   logcat capture window after each launch
  BUILD_APK=1               run ./gradlew :app:assembleDebug first
  INSTALL_APK=1             adb install -r the debug APK before launch 1
  APP_APK=<path>            APK path when BUILD_APK=0 or custom install
  SCENARIO=...              tmux-process-death-resume or hostlist-force-stop
  VERIFY_TMUX_MARKERS=1     fail if tmux markers are missing in tmux scenario
  LOCAL_KEY_PATH=...        private key copied into app files for tmux scenario
  TMUX_HOST_ID=271          synthetic host id stored in LastSessionStore
  TMUX_HOST_NAME=...        display name stored in LastSessionStore
  TMUX_HOST=10.0.2.2        SSH host used by the restored tmux destination
  TMUX_PORT=2222            SSH port used by the restored tmux destination
  TMUX_USER=testuser        SSH user used by the restored tmux destination
  TMUX_SESSION=issue271-startup
                            tmux session attached/created by the app
  TMUX_START_DIR=<path>     optional start directory for tmux new-session -A -c

Artifacts:
  build/issue271-startup-connect/<run-id>/am-start-launch<N>.txt
  build/issue271-startup-connect/<run-id>/logcat-full-launch<N>.txt
  build/issue271-startup-connect/<run-id>/logcat-filtered-startup-launch<N>.txt
  build/issue271-startup-connect/<run-id>/timings.tsv
  build/issue271-startup-connect/<run-id>/am-start-summary.tsv
  build/issue271-startup-connect/<run-id>/apk-profile-listing.txt
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Artifacts: %s\n' "$RUN_DIR" >&2
  exit 1
}

shell_quote() {
  printf "'%s'" "${1//\'/\'\\\'\'}"
}

xml_escape() {
  local value="$1"
  value="${value//&/&amp;}"
  value="${value//</&lt;}"
  value="${value//>/&gt;}"
  printf '%s' "$value"
}

run_logged() {
  local name="$1"
  shift
  local log_file="$RUN_DIR/$name.log"
  printf '[%s]\n' "$name"
  printf 'Command:'
  printf ' %q' "$@"
  printf '\n'
  {
    printf '[%s] %s\n' "$(date -Is)" "$name"
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } >"$log_file" 2>&1 || {
    cat "$log_file" >&2
    fail "$name failed"
  }
}

filter_logcat() {
  local input="$1"
  local output="$2"
  grep -E "$LOGCAT_FILTER" "$input" >"$output" || true
}

append_marker_timings() {
  local launch="$1"
  local filtered="$2"
  awk -v launch="$launch" '
    BEGIN { OFS = "\t" }
    {
      line = $0
      event = ""
      process_elapsed = ""
      elapsed_realtime = ""
      attach_elapsed = ""

      if (match(line, /event=[^ ]+/)) {
        event = substr(line, RSTART + 6, RLENGTH - 6)
      } else if (line ~ /tmux-connect-attempt/) {
        event = "tmux-connect-attempt"
      } else if (line ~ /tmux-ssh-handshake/) {
        event = "tmux-ssh-handshake"
      } else if (line ~ /ssh-connected/) {
        event = "ssh-connected"
      } else if (line ~ /tmux-control-command-started/) {
        event = "tmux-control-command-started"
      } else if (line ~ /tmux-connect-ready/) {
        event = "tmux-connect-ready"
      } else if (line ~ /Displayed/) {
        event = "activity-displayed"
      } else if (line ~ /ProfileInstaller/) {
        event = "profile-installer"
      } else if (line ~ /userfaultfd/) {
        event = "userfaultfd"
      } else if (line ~ /issue173-sshj-guard/) {
        event = "sshj-thread-guard"
      }

      if (event == "") next

      if (match(line, /processElapsedMs=[0-9]+/)) {
        process_elapsed = substr(line, RSTART + 17, RLENGTH - 17)
      }
      if (match(line, /elapsedRealtimeMs=[0-9]+/)) {
        elapsed_realtime = substr(line, RSTART + 18, RLENGTH - 18)
      }
      if (match(line, /elapsedMs=[0-9]+/)) {
        attach_elapsed = substr(line, RSTART + 10, RLENGTH - 10)
      }

      print launch, event, process_elapsed, elapsed_realtime, attach_elapsed, line
    }
  ' "$filtered" >>"$RUN_DIR/timings.tsv"
}

append_am_summary() {
  local launch="$1"
  local input="$2"
  awk -F': ' -v launch="$launch" '
    BEGIN { OFS = "\t" }
    /ThisTime|TotalTime|WaitTime/ {
      key = $1
      gsub(/^ +| +$/, "", key)
      value = $2
      gsub(/^ +| +$/, "", value)
      print launch, key, value
    }
  ' "$input" >>"$RUN_DIR/am-start-summary.tsv"
}

adb_shell_trimmed() {
  "$ADB" shell "$@" 2>/dev/null | tr -d '\r'
}

app_pid() {
  adb_shell_trimmed pidof "$PACKAGE" || true
}

wait_for_app_process_dead() {
  local deadline=$((SECONDS + 10))
  while (( SECONDS < deadline )); do
    if [[ -z "$(app_pid)" ]]; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

copy_tmux_key_into_app() {
  [[ -f "$LOCAL_KEY_PATH" ]] || fail "LOCAL_KEY_PATH does not exist: $LOCAL_KEY_PATH"
  local app_data_dir
  app_data_dir="$(adb_shell_trimmed run-as "$PACKAGE" pwd | tail -n 1)"
  [[ -n "$app_data_dir" ]] || fail "run-as $PACKAGE failed; install a debuggable APK for tmux-process-death-resume"
  TMUX_KEY_DEVICE_PATH="$app_data_dir/files/$DEVICE_KEY_BASENAME"

  local remote_tmp="/data/local/tmp/issue271-${RUN_ID}-key.pem"
  run_logged "04-push-tmux-key" "$ADB" push "$LOCAL_KEY_PATH" "$remote_tmp"
  local quoted_tmp quoted_base remote_cmd
  quoted_tmp="$(shell_quote "$remote_tmp")"
  quoted_base="$(shell_quote "$DEVICE_KEY_BASENAME")"
  remote_cmd="mkdir -p files && cp $quoted_tmp files/$quoted_base && chmod 600 files/$quoted_base"
  run_logged "05-install-tmux-key" "$ADB" shell "run-as $PACKAGE sh -c $(shell_quote "$remote_cmd")"
  "$ADB" shell rm "$remote_tmp" >/dev/null 2>&1 || true
}

write_last_session_snapshot() {
  local launch="$1"
  local snapshot="$RUN_DIR/last-session-launch${launch}.xml"
  local saved_at_ms
  saved_at_ms="$(date +%s%3N)"
  {
    printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
    printf "<map>\n"
    printf "    <long name=\"host_id\" value=\"%s\" />\n" "$(xml_escape "$TMUX_HOST_ID")"
    printf "    <string name=\"host_name\">%s</string>\n" "$(xml_escape "$TMUX_HOST_NAME")"
    printf "    <string name=\"hostname\">%s</string>\n" "$(xml_escape "$TMUX_HOST")"
    printf "    <int name=\"port\" value=\"%s\" />\n" "$(xml_escape "$TMUX_PORT")"
    printf "    <string name=\"username\">%s</string>\n" "$(xml_escape "$TMUX_USER")"
    printf "    <string name=\"key_path\">%s</string>\n" "$(xml_escape "$TMUX_KEY_DEVICE_PATH")"
    printf "    <string name=\"session_name\">%s</string>\n" "$(xml_escape "$TMUX_SESSION")"
    printf "    <string name=\"start_dir\">%s</string>\n" "$(xml_escape "$TMUX_START_DIR")"
    printf "    <string name=\"composer_draft\"></string>\n"
    printf "    <long name=\"saved_at\" value=\"%s\" />\n" "$saved_at_ms"
    printf "</map>\n"
  } >"$snapshot"

  local remote_tmp="/data/local/tmp/issue271-${RUN_ID}-last-session.xml"
  "$ADB" push "$snapshot" "$remote_tmp" >"$RUN_DIR/push-last-session-launch${launch}.txt" 2>&1 \
    || fail "failed to push LastSessionStore snapshot"
  local quoted_tmp
  quoted_tmp="$(shell_quote "$remote_tmp")"
  local remote_cmd
  remote_cmd="mkdir -p shared_prefs && cp $quoted_tmp shared_prefs/last_session.xml && chmod 600 shared_prefs/last_session.xml"
  "$ADB" shell "run-as $PACKAGE sh -c $(shell_quote "$remote_cmd")" \
    >"$RUN_DIR/install-last-session-launch${launch}.txt" 2>&1 \
    || fail "failed to install LastSessionStore snapshot with run-as"
  "$ADB" shell rm "$remote_tmp" >/dev/null 2>&1 || true
}

prime_retained_task() {
  "$ADB" shell am start -W -n "$PACKAGE/$ACTIVITY" \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    >"$RUN_DIR/prime-task.txt" 2>&1 || true
  sleep "$TASK_PRIME_WAIT_SEC"
}

prepare_tmux_process_death_resume_launch() {
  local launch="$1"
  if [[ "$launch" == "1" ]]; then
    prime_retained_task
  fi
  "$ADB" shell input keyevent KEYCODE_HOME >"$RUN_DIR/home-launch${launch}.txt" 2>&1 || true
  sleep 1
  write_last_session_snapshot "$launch"
  "$ADB" shell am kill "$PACKAGE" >"$RUN_DIR/am-kill-launch${launch}.txt" 2>&1 || true
  wait_for_app_process_dead || fail "app process survived am kill during tmux process-death setup"
}

prepare_hostlist_force_stop_launch() {
  local launch="$1"
  "$ADB" shell am force-stop "$PACKAGE" >"$RUN_DIR/force-stop-launch${launch}.txt" 2>&1 || true
  sleep 1
}

verify_tmux_markers_for_launch() {
  local launch="$1"
  local timings="$RUN_DIR/timings.tsv"
  local missing=()
  for marker in tmux-screen-composed tmux-connect-effect-start tmux-connect-attempt; do
    if ! awk -F '\t' -v launch="$launch" -v marker="$marker" \
      '$1 == launch && $2 == marker { found = 1 } END { exit found ? 0 : 1 }' "$timings"; then
      missing+=("$marker")
    fi
  done
  if (( ${#missing[@]} > 0 )); then
    fail "launch $launch did not reach tmux timing path; missing markers: ${missing[*]}"
  fi
}

mkdir -p "$RUN_DIR"

[[ -x "$ADB" ]] || fail "adb is not executable at $ADB"

run_logged "00-adb-version" "$ADB" version
run_logged "01-adb-devices" "$ADB" devices -l
"$ADB" get-state >/dev/null 2>&1 || fail "no selected adb device is online"
"$ADB" shell getprop >"$RUN_DIR/getprop.txt" 2>&1 || true

if [[ "$BUILD_APK" == "1" ]]; then
  run_logged "02-assemble-debug" ./gradlew :app:assembleDebug
fi

if command -v unzip >/dev/null 2>&1 && [[ -f "$APP_APK" ]]; then
  {
    printf 'APK: %s\n\n' "$APP_APK"
    unzip -l "$APP_APK" | grep -E 'dexopt/(baseline|startup)|baseline[.]prof|baseline[.]profm' || true
  } >"$RUN_DIR/apk-profile-listing.txt"
fi

if [[ "$INSTALL_APK" == "1" ]]; then
  [[ -f "$APP_APK" ]] || fail "APK does not exist at $APP_APK"
  run_logged "03-install-debug" "$ADB" install -r "$APP_APK"
fi

case "$SCENARIO" in
  tmux-process-death-resume)
    copy_tmux_key_into_app
    ;;
  hostlist-force-stop)
    ;;
  *)
    fail "unknown SCENARIO '$SCENARIO' (expected tmux-process-death-resume or hostlist-force-stop)"
    ;;
esac

printf 'launch\tevent\tprocess_elapsed_ms\telapsed_realtime_ms\tattach_elapsed_ms\tline\n' >"$RUN_DIR/timings.tsv"
printf 'launch\tmetric\tms\n' >"$RUN_DIR/am-start-summary.tsv"

for launch in $(seq 1 "$LAUNCHES"); do
  printf '[launch %s/%s]\n' "$launch" "$LAUNCHES"
  case "$SCENARIO" in
    tmux-process-death-resume)
      prepare_tmux_process_death_resume_launch "$launch"
      ;;
    hostlist-force-stop)
      prepare_hostlist_force_stop_launch "$launch"
      ;;
  esac
  "$ADB" logcat -c >/dev/null 2>&1 || true
  "$ADB" shell am start -W -n "$PACKAGE/$ACTIVITY" \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    >"$RUN_DIR/am-start-launch${launch}.txt" 2>&1 || true
  sleep "$WAIT_AFTER_START_SEC"
  "$ADB" logcat -d -v threadtime >"$RUN_DIR/logcat-full-launch${launch}.txt" 2>&1 || true
  filter_logcat \
    "$RUN_DIR/logcat-full-launch${launch}.txt" \
    "$RUN_DIR/logcat-filtered-startup-launch${launch}.txt"
  "$ADB" shell dumpsys gfxinfo "$PACKAGE" \
    >"$RUN_DIR/gfxinfo-launch${launch}.txt" 2>&1 || true
  append_am_summary "$launch" "$RUN_DIR/am-start-launch${launch}.txt"
  append_marker_timings "$launch" "$RUN_DIR/logcat-filtered-startup-launch${launch}.txt"
  if [[ "$VERIFY_TMUX_MARKERS" == "1" ]]; then
    verify_tmux_markers_for_launch "$launch"
  fi
done

cat >"$RUN_DIR/README.txt" <<EOF
Issue #271 startup/connect timing run.

Scenario: $SCENARIO

Use timings.tsv to compare PsStartup processElapsedMs markers:
- main-on-create-start / app-navigator-first-composed / app-navigator-current
- tmux-screen-composed / tmux-connect-effect-start
- PsTmuxReconnect tmux-connect-attempt / tmux-ssh-handshake / ssh-connected

For SCENARIO=tmux-process-death-resume, each measured launch backgrounds a
retained launcher task, seeds LastSessionStore with:
  $TMUX_USER@$TMUX_HOST:$TMUX_PORT session=$TMUX_SESSION key=$TMUX_KEY_DEVICE_PATH
then kills the cached process with am kill and starts the launcher task. This
captures the process-death resume route into TmuxSession.

For SCENARIO=hostlist-force-stop, each measured launch force-stops the package
and starts the launcher. That scenario measures HostList startup only.
EOF

printf '\nIssue #271 timing artifacts: %s\n' "$RUN_DIR"
printf 'Summary:\n'
cat "$RUN_DIR/am-start-summary.tsv"
printf '\nMarkers:\n'
cat "$RUN_DIR/timings.tsv"
