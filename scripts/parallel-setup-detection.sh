#!/usr/bin/env bash
set -euo pipefail

# parallel-setup-detection.sh — fan-out/join wrapper that shards the
# setup-detection bootstrap matrix across N emulators so the 7 profiles run
# concurrently instead of serially behind the single global AVD lock.
#
# Why setup-detection first (issue #632, slice 2): its 7 bootstrap profiles
# already bind DISJOINT Docker ports 2230-2236 (tests/docker/docker-compose.yml),
# so a profile's container/port only ever lives in ONE shard. That makes this
# the easiest block to parallelize — no port remapping is needed, only:
#
#   * a distinct emulator per shard, via ANDROID_SERIAL;
#   * a per-shard AVD lock file (POCKETSHELL_AVD_LOCK_FILE) so shards do NOT
#     serialise against each other on build/.avd-lock — see
#     pocketshell_avd_lock_file_for_serial in scripts/lib/avd-lock.sh;
#   * a per-shard COMPOSE_PROJECT_NAME so concurrent `docker compose up`
#     invocations don't collide on the default project's network/bookkeeping.
#
# Each shard delegates to the existing building block:
#     scripts/phone-walkthrough.sh setup-detection:<profile> [setup-detection:<profile> ...]
# so every per-profile artifact the sequential path produces is still written.
#
# Single-serial / single-AVD environments degrade cleanly to one shard, which
# is exactly the existing sequential behaviour — no regression.
#
# This wrapper is ADDITIVE. The sequential setup-detection stage in
# scripts/release-emulator-validation.sh is unchanged; it can opt in via
# SETUP_DETECTION_SHARDS=N (see that script).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/parallel-setup-detection}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"

# Full bootstrap matrix (must mirror SETUP_DETECTION_PROFILES in
# scripts/phone-walkthrough.sh — the single source of truth for the profile set).
ALL_PROFILES=(
  "ready"
  "uv-install"
  "uv-upgrade"
  "unsupported"
  "daemon-disabled"
  "user-local-path"
  "fish-user-local-path"
)

usage() {
  cat <<'USAGE'
Usage: scripts/parallel-setup-detection.sh [options] [profile...]

Shards the setup-detection bootstrap matrix across multiple emulators and runs
the shards concurrently, then joins on pass/fail. With one serial it runs a
single sequential shard (the existing behaviour — no regression).

Profiles (default: the full matrix):
  ready uv-install uv-upgrade unsupported daemon-disabled
  user-local-path fish-user-local-path

Options:
  --serials "<s1> <s2> ..."  Space/comma-separated adb serials, one per shard.
                             Default: every booted adb device. With ANDROID_SERIAL
                             set and no --serials, that single serial is used.
  --shards N                 Cap the number of shards (<= number of serials).
                             Default: min(#serials, #profiles).
  --dry-run                  Print the shard plan (serial / lock / compose project
                             / profiles per shard) and the commands, then exit
                             WITHOUT touching any emulator or Docker.
  -h, --help                 Show this help.

Environment:
  RUN_ID    Artifact directory name (default: timestamp).
  LOG_ROOT  Artifact root (default: build/parallel-setup-detection).

Isolation per shard:
  ANDROID_SERIAL=<serial>
  POCKETSHELL_AVD_LOCK_FILE=build/.avd-lock-<serial>   (per-serial, NOT the global lock)
  COMPOSE_PROJECT_NAME=pocketshell-setup-detection-shard<i>

Artifacts: build/parallel-setup-detection/<run-id>/
  shard<i>/   per-shard phone-walkthrough run dir (per-profile artifacts inside)
  plan.txt    the shard plan
  summary.txt aggregated per-profile pass/fail
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

# --- argument parsing -------------------------------------------------------
SERIALS_ARG=""
SHARDS_CAP=""
DRY_RUN=0
declare -a PROFILES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serials)
      [[ $# -ge 2 ]] || fail "--serials requires a value"
      SERIALS_ARG="$2"
      shift 2
      ;;
    --serials=*)
      SERIALS_ARG="${1#--serials=}"
      shift
      ;;
    --shards)
      [[ $# -ge 2 ]] || fail "--shards requires a value"
      SHARDS_CAP="$2"
      shift 2
      ;;
    --shards=*)
      SHARDS_CAP="${1#--shards=}"
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    setup-detection:*)
      PROFILES+=("${1#setup-detection:}")
      shift
      ;;
    -*)
      fail "unknown option: $1"
      ;;
    *)
      PROFILES+=("$1")
      shift
      ;;
  esac
done

if [[ "${#PROFILES[@]}" -eq 0 ]]; then
  PROFILES=("${ALL_PROFILES[@]}")
fi

# Validate every requested profile against the known matrix.
is_known_profile() {
  local needle="$1" p
  for p in "${ALL_PROFILES[@]}"; do
    [[ "$p" == "$needle" ]] && return 0
  done
  return 1
}
for p in "${PROFILES[@]}"; do
  is_known_profile "$p" || fail "unknown setup-detection profile '$p'"
done

if [[ -n "$SHARDS_CAP" && ! "$SHARDS_CAP" =~ ^[1-9][0-9]*$ ]]; then
  fail "--shards must be a positive integer (got '$SHARDS_CAP')"
fi

# --- resolve serials --------------------------------------------------------
declare -a SERIALS=()
resolve_serials() {
  if [[ -n "$SERIALS_ARG" ]]; then
    # Accept comma- or space-separated.
    local normalized="${SERIALS_ARG//,/ }"
    read -r -a SERIALS <<<"$normalized"
    return
  fi
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    SERIALS=("$ANDROID_SERIAL")
    return
  fi
  if [[ "$DRY_RUN" -eq 1 ]]; then
    # In dry-run we don't require a real device; synthesise a placeholder so
    # the plan still prints. A real run resolves live adb devices below.
    local listed
    listed="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')" || true
    if [[ -n "$listed" ]]; then
      read -r -a SERIALS <<<"$(printf '%s ' $listed)"
    else
      SERIALS=("dry-run-serial-A" "dry-run-serial-B")
    fi
    return
  fi
  # Live run: every booted adb device.
  local listed
  listed="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  [[ -n "$listed" ]] || fail "no booted adb device found; start an emulator or pass --serials"
  read -r -a SERIALS <<<"$(printf '%s ' $listed)"
}
resolve_serials

[[ "${#SERIALS[@]}" -ge 1 ]] || fail "no emulator serials resolved"

# --- compute shard count ----------------------------------------------------
num_profiles="${#PROFILES[@]}"
num_serials="${#SERIALS[@]}"
num_shards="$num_serials"
if [[ "$num_shards" -gt "$num_profiles" ]]; then
  num_shards="$num_profiles"
fi
if [[ -n "$SHARDS_CAP" && "$SHARDS_CAP" -lt "$num_shards" ]]; then
  num_shards="$SHARDS_CAP"
fi

# --- distribute profiles round-robin across shards --------------------------
declare -a SHARD_PROFILES=()   # index i holds a space-joined profile list
for ((i = 0; i < num_shards; i++)); do
  SHARD_PROFILES[i]=""
done
for ((idx = 0; idx < num_profiles; idx++)); do
  shard=$((idx % num_shards))
  if [[ -z "${SHARD_PROFILES[shard]}" ]]; then
    SHARD_PROFILES[shard]="${PROFILES[idx]}"
  else
    SHARD_PROFILES[shard]="${SHARD_PROFILES[shard]} ${PROFILES[idx]}"
  fi
done

mkdir -p "$RUN_DIR"
PLAN_FILE="$RUN_DIR/plan.txt"
SUMMARY_FILE="$RUN_DIR/summary.txt"

compose_project_for_shard() {
  printf 'pocketshell-setup-detection-shard%s' "$1"
}

# --- print the plan ---------------------------------------------------------
{
  printf 'PocketShell parallel setup-detection\n'
  printf 'run-id: %s\n' "$RUN_ID"
  printf 'artifacts: %s\n' "$RUN_DIR"
  printf 'profiles (%s): %s\n' "$num_profiles" "${PROFILES[*]}"
  printf 'serials (%s): %s\n' "$num_serials" "${SERIALS[*]}"
  printf 'shards: %s\n' "$num_shards"
  printf '\n'
  for ((i = 0; i < num_shards; i++)); do
    serial="${SERIALS[i]}"
    lock_file="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$serial")"
    project="$(compose_project_for_shard "$i")"
    printf '== shard %s ==\n' "$i"
    printf '  ANDROID_SERIAL            = %s\n' "$serial"
    printf '  POCKETSHELL_AVD_LOCK_FILE = %s\n' "$lock_file"
    printf '  COMPOSE_PROJECT_NAME      = %s\n' "$project"
    printf '  profiles                  = %s\n' "${SHARD_PROFILES[i]}"
    # The exact command each shard runs:
    cmd_args=""
    for prof in ${SHARD_PROFILES[i]}; do
      cmd_args="$cmd_args setup-detection:$prof"
    done
    printf '  command                   = scripts/phone-walkthrough.sh%s\n' "$cmd_args"
    printf '\n'
  done
} | tee "$PLAN_FILE"

if [[ "$DRY_RUN" -eq 1 ]]; then
  printf '== --dry-run: not executing (no emulator/Docker touched) ==\n'
  printf 'plan: %s\n' "$PLAN_FILE"
  exit 0
fi

# --- build the APKs ONCE before fan-out -------------------------------------
# Every shard delegates to phone-walkthrough.sh, which by default rebuilds the
# app + androidTest APKs into the SHARED app/build/ project directory. Two
# concurrent assembleDebug passes against the same project dir corrupt each
# other (e.g. mergeDebugResources races). So we build once here, then run each
# shard with BUILD_APKS=0 + PHONE_WALKTHROUGH_CLEAN_GENERATED=0 so no shard
# touches app/build — the per-emulator `pm install` step remains safe to run
# concurrently (it targets each shard's own ANDROID_SERIAL).
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
PARALLEL_BUILD_APKS="${PARALLEL_BUILD_APKS:-1}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$LOG_ROOT/gradle-home}"
if [[ "$PARALLEL_BUILD_APKS" = "1" ]]; then
  build_log="$RUN_DIR/00-build-apks.log"
  printf 'building app + androidTest APKs once (shared by all shards) -> %s\n' "$build_log"
  if ! ./gradlew --no-daemon --no-build-cache --no-parallel \
      :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace \
      >"$build_log" 2>&1; then
    tail -n 40 "$build_log" >&2 || true
    fail "pre-shard APK build failed (see $build_log)"
  fi
fi
[[ -f "$APP_APK" ]] || fail "app APK missing at $APP_APK (set PARALLEL_BUILD_APKS=1 to build)"
[[ -f "$TEST_APK" ]] || fail "androidTest APK missing at $TEST_APK (set PARALLEL_BUILD_APKS=1 to build)"

# --- fan out ----------------------------------------------------------------
declare -a SHARD_PIDS=()
declare -a SHARD_LOGS=()

for ((i = 0; i < num_shards; i++)); do
  serial="${SERIALS[i]}"
  lock_file="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$serial")"
  project="$(compose_project_for_shard "$i")"
  shard_dir="$RUN_DIR/shard$i"
  mkdir -p "$shard_dir"
  shard_log="$shard_dir/shard.log"
  SHARD_LOGS[i]="$shard_log"

  # Build the per-shard scenario list (setup-detection:<profile>).
  declare -a scenario_args=()
  for prof in ${SHARD_PROFILES[i]}; do
    scenario_args+=("setup-detection:$prof")
  done

  printf 'launching shard %s on %s -> %s\n' "$i" "$serial" "$shard_log"
  (
    # Per-shard isolation. POCKETSHELL_AVD_LOCK_ACQUIRED must NOT leak in, or
    # phone-walkthrough would skip acquiring its own per-serial lock.
    unset POCKETSHELL_AVD_LOCK_ACQUIRED POCKETSHELL_AVD_LOCK_HOLDER_PID POCKETSHELL_AVD_LOCK_OWNER_PID
    export ANDROID_SERIAL="$serial"
    export POCKETSHELL_AVD_LOCK_FILE="$lock_file"
    export COMPOSE_PROJECT_NAME="$project"
    export LOG_ROOT="$shard_dir/phone-walkthrough"
    export RUN_ID="setup-detection"
    # Reuse the once-built APKs; never let a shard rebuild or wipe app/build,
    # which is shared across shards (see the pre-shard build above).
    export BUILD_APKS=0
    export PHONE_WALKTHROUGH_CLEAN_GENERATED=0
    exec "$ROOT_DIR/scripts/phone-walkthrough.sh" "${scenario_args[@]}"
  ) >"$shard_log" 2>&1 &
  SHARD_PIDS[i]="$!"
done

# --- join -------------------------------------------------------------------
declare -a SHARD_STATUS=()
overall_status=0
for ((i = 0; i < num_shards; i++)); do
  if wait "${SHARD_PIDS[i]}"; then
    SHARD_STATUS[i]=0
  else
    SHARD_STATUS[i]=$?
    overall_status=1
  fi
done

# --- aggregate per-profile pass/fail ---------------------------------------
{
  printf 'PocketShell parallel setup-detection — summary\n'
  printf 'run-id: %s\n' "$RUN_ID"
  printf 'shards: %s   serials: %s\n' "$num_shards" "${SERIALS[*]}"
  printf '\n'
  any_fail=0
  for ((i = 0; i < num_shards; i++)); do
    serial="${SERIALS[i]}"
    shard_log="${SHARD_LOGS[i]}"
    printf '== shard %s (serial=%s, exit=%s) ==\n' "$i" "$serial" "${SHARD_STATUS[i]}"
    for prof in ${SHARD_PROFILES[i]}; do
      # phone-walkthrough prints "PASS setup-detection:<profile>" on success.
      if grep -q "PASS setup-detection:$prof\b" "$shard_log" 2>/dev/null; then
        printf '  PASS  %s\n' "$prof"
      else
        printf '  FAIL  %s\n' "$prof"
        any_fail=1
      fi
    done
    printf '\n'
  done
  if [[ "$overall_status" -ne 0 || "$any_fail" -ne 0 ]]; then
    printf 'OVERALL: FAIL\n'
  else
    printf 'OVERALL: PASS\n'
  fi
} | tee "$SUMMARY_FILE"

printf 'summary: %s\n' "$SUMMARY_FILE"

if [[ "$overall_status" -ne 0 ]]; then
  exit 1
fi
# A shard could exit 0 yet a profile line be missing; treat that as failure too.
if grep -q '^OVERALL: FAIL$' "$SUMMARY_FILE"; then
  exit 1
fi
exit 0
