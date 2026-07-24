#!/usr/bin/env bash
set -euo pipefail

# Wrapper-level serial-ownership regression harness (issue #1737).
#
# One physical emulator has one mutation domain: package cleanup, APK install,
# and instrumentation.  Before #1737, the pool wrapper locked
# `avd-lock-emulator-5554` while the one-emulator legacy wrapper locked the
# unrelated global `avd-lock`. Both therefore entered the mutation window and
# one instrumentation process was killed.
#
# This harness runs the REAL connected-test.sh from two fake worktrees against
# one fake AVD. The fake gradle process models install + instrumentation and
# hard-fails if another wrapper overlaps its mutation window. No emulator,
# Docker daemon, Gradle daemon, or maintainer package is touched.

unset POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_FD \
      POCKETSHELL_AVD_LOCK_HOLDER_PID \
      POCKETSHELL_AVD_LOCK_OWNER_PID \
      POCKETSHELL_POOL_HOLDER_PID \
      POCKETSHELL_POOL_OWNER_PID \
      POCKETSHELL_POOL_SERIAL \
      POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID \
      POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID \
      ANDROID_SERIAL \
      ADB_SERIAL

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ACTIVE_CASE_SANDBOX=""
ACTIVE_PROCESS_GROUPS=()

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

wait_for_file() {
  local target="$1" timeout="${2:-10}"
  local waited=0 limit=$((timeout * 20))
  while [[ ! -e "$target" ]]; do
    (( waited++ >= limit )) && return 1
    sleep 0.05
  done
}

wait_for_exit() {
  local pid="$1" timeout="${2:-10}"
  local waited=0 limit=$((timeout * 20))
  while kill -0 "$pid" 2>/dev/null; do
    (( waited++ >= limit )) && return 1
    sleep 0.05
  done
  wait "$pid"
}

wait_until_stopped() {
  local pid="$1" timeout="${2:-10}"
  local waited=0 limit=$((timeout * 20))
  while kill -0 "$pid" 2>/dev/null; do
    (( waited++ >= limit )) && return 1
    sleep 0.05
  done
}

kill_group() {
  local pid="${1:-}"
  [[ -n "$pid" ]] || return 0
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  local waited=0
  while ps -eo pgid=,stat= | awk -v pgid="$pid" \
      '$1 == pgid && $2 !~ /^Z/ { found = 1 } END { exit !found }'; do
    (( waited++ >= 100 )) && break
    sleep 0.02
  done
  kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
  waited=0
  while ps -eo pgid=,stat= | awk -v pgid="$pid" \
      '$1 == pgid && $2 !~ /^Z/ { found = 1 } END { exit !found }'; do
    (( waited++ >= 100 )) && break
    sleep 0.02
  done
  wait "$pid" 2>/dev/null || true
}

cleanup_active_case() {
  local pid
  for pid in "${ACTIVE_PROCESS_GROUPS[@]:-}"; do
    kill_group "$pid"
  done
  if [[ -n "$ACTIVE_CASE_SANDBOX" ]]; then
    rm -rf "$ACTIVE_CASE_SANDBOX"
  fi
}
trap cleanup_active_case EXIT

descendant_pids() {
  local parent="$1"
  local child
  while IFS= read -r child; do
    [[ -n "$child" ]] || continue
    printf '%s\n' "$child"
    descendant_pids "$child"
  done < <(ps -o pid= --ppid "$parent" 2>/dev/null | awk '{ print $1 }')
}

make_worktree() {
  local source_root="$1" target_root="$2"
  mkdir -p "$target_root/scripts/lib"
  cp "$source_root/scripts/connected-test.sh" "$target_root/scripts/connected-test.sh"
  cp "$source_root"/scripts/lib/*.sh "$target_root/scripts/lib/"
  chmod +x "$target_root/scripts/connected-test.sh"
}

make_fake_adb() {
  local sandbox="$1"
  cat > "$sandbox/bin/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail

serial="${ANDROID_SERIAL:-}"
if [[ "${1:-}" == "-s" ]]; then
  serial="$2"
  shift 2
fi
if [[ -z "$serial" ]]; then
  serial="${FAKE_DEFAULT_SERIAL:?}"
fi

case "${1:-}" in
  devices)
    printf 'List of devices attached\n'
    for candidate in $FAKE_ONLINE_SERIALS; do
      printf '%s\tdevice\n' "$candidate"
    done
    ;;
  shell)
    shift
    if [[ "${1:-}" == "pm" && "${2:-}" == "list" && "${3:-}" == "packages" ]]; then
      packages="$FAKE_DEVICE_STATE/packages-$serial"
      if [[ -e "$packages" ]]; then
        sed 's/^/package:/' "$packages"
      fi
    fi
    ;;
  uninstall)
    package="${2:?}"
    if [[ "${FAKE_KILL_HOLDER_ON_UNINSTALL_RUN_ID:-}" == "$FAKE_RUN_ID" ]]; then
      holder="${POCKETSHELL_POOL_HOLDER_PID:-${POCKETSHELL_AVD_LOCK_HOLDER_PID:-}}"
      [[ "$holder" =~ ^[0-9]+$ ]] || exit 96
      kill -KILL "$holder" 2>/dev/null || true
      touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-holder-killed"
      trap 'touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-terminated"; exit 143' TERM INT
      while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-continue" ]]; do
        sleep 0.02
      done
    fi
    printf '%s adb-uninstall %s %s\n' "$FAKE_RUN_ID" "$serial" "$package" \
      >> "$FAKE_DEVICE_STATE/events"
    packages="$FAKE_DEVICE_STATE/packages-$serial"
    if [[ -e "$packages" ]]; then
      grep -Fxv "$package" "$packages" > "$packages.next" || true
      mv "$packages.next" "$packages"
    fi
    if [[ -e "$FAKE_DEVICE_STATE/active-$package" ]]; then
      touch "$FAKE_DEVICE_STATE/killed-$package"
      rm -f "$FAKE_DEVICE_STATE/active-$package"
    fi
    ;;
  *)
    ;;
esac
ADB
  chmod +x "$sandbox/bin/adb"
}

make_fake_docker() {
  local sandbox="$1"
  cat > "$sandbox/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  compose)
    touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.docker-up"
    if [[ "${FAKE_PAUSE_DOCKER_PHASE:-}" == "up" ]]; then
      touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.docker-up-paused"
      while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.docker-continue" ]]; do
        sleep 0.02
      done
    fi
    ;;
  inspect)
    if [[ "${FAKE_PAUSE_DOCKER_PHASE:-}" == "health" ]]; then
      touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.docker-health-paused"
      while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.docker-continue" ]]; do
        sleep 0.02
      done
    fi
    printf 'healthy\n'
    ;;
esac
DOCKER
  chmod +x "$sandbox/bin/docker"
}

inject_agents_fork_pause() {
  local root="$1"
  # The literal shell fragment is injected into the sandbox copy and expands
  # only when that copied helper runs.
  # shellcheck disable=SC2016
  sed -i '/local holder_pid="\$!"/a\
  if [[ "${FAKE_PAUSE_AFTER_AGENTS_FORK_RUN_ID:-}" == "${FAKE_RUN_ID:-}" ]]; then\
    touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.agents-fork-paused"\
    while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.agents-fork-continue" ]]; do\
      _pocketshell_agents_run_without_avd_lock_fd sleep 0.02\
    done\
  fi' "$root/scripts/lib/agents-pool.sh"
}

make_fake_gradle() {
  local root="$1"
  cat > "$root/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
set -euo pipefail

serial="${ANDROID_SERIAL:-${FAKE_DEFAULT_SERIAL:?}}"
suffix=""
for arg in "$@"; do
  case "$arg" in
    -PpocketshellAppIdSuffix=*) suffix="${arg#*=}" ;;
  esac
done
package="com.pocketshell.app${suffix:+.$suffix}"
printf '%s\n' "$serial" > "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.serial"
printf '%s\n' "$*" > "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.gradle-args"

# Reviewer correction: kill the wrapper's helper holder after its final
# pre-Gradle ownership assertion but before this stub's first mutation. The
# pause makes the boundary deterministic: the test starts a same-serial
# contender, then either the wrapper notices the loss and terminates us or the
# old snapshot-only implementation lets both lanes proceed unlocked.
if [[ "${FAKE_KILL_HOLDER_RUN_ID:-}" == "$FAKE_RUN_ID" ]]; then
  holder="${POCKETSHELL_POOL_HOLDER_PID:-${POCKETSHELL_AVD_LOCK_HOLDER_PID:-}}"
  [[ "$holder" =~ ^[0-9]+$ ]] || {
    printf 'missing-holder-at-gradle-boundary\n' > "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.hook-error"
    exit 96
  }
  kill -KILL "$holder" 2>/dev/null || true
  touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.holder-killed"
  trap 'touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.terminated"; exit 143' TERM INT
  while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.continue" ]]; do
    sleep 0.02
  done
fi

if [[ "${FAKE_PAUSE_BEFORE_MUTATION_RUN_ID:-}" == "$FAKE_RUN_ID" ]]; then
  touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.pre-mutation-paused"
  trap 'touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.pause-terminated"; exit 143' TERM INT
  while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.continue" ]]; do
    sleep 0.02
  done
fi

# Install/instrumentation is one mutation window. A second wrapper entering it
# models the real AGP install that SIGKILLs the instrumentation already running
# on this serial.
guard="$FAKE_DEVICE_STATE/mutating-$serial"
if ! mkdir "$guard" 2>/dev/null; then
  printf '%s overlap %s\n' "$FAKE_RUN_ID" "$serial" >> "$FAKE_DEVICE_STATE/events"
  touch "$FAKE_DEVICE_STATE/overlap"
  for active in "$FAKE_DEVICE_STATE"/active-com.pocketshell.app*; do
    [[ -e "$active" ]] || continue
    victim="${active##*/active-}"
    touch "$FAKE_DEVICE_STATE/killed-$victim"
    rm -f "$active"
  done
else
  trap 'rmdir "$guard" 2>/dev/null || true' EXIT
fi

packages="$FAKE_DEVICE_STATE/packages-$serial"
touch "$packages"
if ! grep -Fxq "$package" "$packages"; then
  printf '%s\n' "$package" >> "$packages"
fi
touch "$FAKE_DEVICE_STATE/active-$package"
printf '%s install-instrument %s %s\n' "$FAKE_RUN_ID" "$serial" "$package" \
  >> "$FAKE_DEVICE_STATE/events"
touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.started"

while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.release" ]]; do
  if [[ -e "$FAKE_DEVICE_STATE/killed-$package" ]]; then
    printf '%s instrumentation-killed %s %s\n' "$FAKE_RUN_ID" "$serial" "$package" \
      >> "$FAKE_DEVICE_STATE/events"
    exit 9
  fi
  sleep 0.05
done

if [[ -e "$FAKE_DEVICE_STATE/killed-$package" || ! -e "$FAKE_DEVICE_STATE/active-$package" ]]; then
  printf '%s instrumentation-killed %s %s\n' "$FAKE_RUN_ID" "$serial" "$package" \
    >> "$FAKE_DEVICE_STATE/events"
  exit 9
fi
rm -f "$FAKE_DEVICE_STATE/active-$package"
printf '%s instrumentation-survived %s %s\n' "$FAKE_RUN_ID" "$serial" "$package" \
  >> "$FAKE_DEVICE_STATE/events"
exit "${FAKE_GRADLE_RC:-0}"
GRADLEW
  chmod +x "$root/gradlew"
}

make_sandbox() {
  local sandbox="$1" serials="${2:-emulator-5554}"
  mkdir -p "$sandbox/bin" "$sandbox/device-state" "$sandbox/locks"
  make_fake_adb "$sandbox"
  make_fake_docker "$sandbox"
  make_worktree "$ROOT_DIR" "$sandbox/pool-root"
  make_worktree "$ROOT_DIR" "$sandbox/legacy-root"
  make_fake_gradle "$sandbox/pool-root"
  make_fake_gradle "$sandbox/legacy-root"
  printf '%s\n' "$serials" > "$sandbox/serials"
}

start_wrapper() {
  local sandbox="$1" mode="$2" run_id="$3" suffix="$4"
  local online_serials="${5:-emulator-5554}"
  local explicit_serial="${6:-}"
  local pool_serials="${7:-$online_serials}"
  local wait_seconds="${8:-8}"
  local gradle_rc="${9:-0}"
  local agents_port="${10-2222}"
  local test_class="${11:-}"
  local root="$sandbox/$mode-root"
  local mode_arg="--no-pool"
  local extra_args=()
  [[ "$mode" == "pool" ]] && mode_arg="--pool"
  [[ -n "$test_class" ]] && extra_args=("--tests" "$test_class")

  setsid env \
    PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    ANDROID_SERIAL="$explicit_serial" \
    POCKETSHELL_AVD_LOCK_DIR="$sandbox/locks" \
    POCKETSHELL_POOL_SERIALS="$pool_serials" \
    POCKETSHELL_POOL_WAIT_SECONDS="$wait_seconds" \
    POCKETSHELL_AGENTS_PORT="$agents_port" \
    POCKETSHELL_AGENTS_POOL_PORTS=2243 \
    POCKETSHELL_AGENTS_WAIT_SECONDS=0 \
    POCKETSHELL_TEST_MEM=1G \
    FAKE_DEFAULT_SERIAL="${online_serials%% *}" \
    FAKE_ONLINE_SERIALS="$online_serials" \
    FAKE_DEVICE_STATE="$sandbox/device-state" \
    FAKE_RUN_ID="$run_id" \
    FAKE_GRADLE_RC="$gradle_rc" \
    FAKE_KILL_HOLDER_RUN_ID="${FAKE_KILL_HOLDER_RUN_ID:-}" \
    FAKE_PAUSE_BEFORE_MUTATION_RUN_ID="${FAKE_PAUSE_BEFORE_MUTATION_RUN_ID:-}" \
    FAKE_PAUSE_AFTER_AGENTS_FORK_RUN_ID="${FAKE_PAUSE_AFTER_AGENTS_FORK_RUN_ID:-}" \
    FAKE_PAUSE_DOCKER_PHASE="${FAKE_PAUSE_DOCKER_PHASE:-}" \
    bash "$root/scripts/connected-test.sh" "$mode_arg" --suffix "$suffix" \
      "${extra_args[@]}" \
      > "$sandbox/$run_id.out" 2> "$sandbox/$run_id.err" &
  WRAPPER_PID="$!"
  ACTIVE_PROCESS_GROUPS+=("$WRAPPER_PID")
}

start_cleanup() {
  local sandbox="$1" run_id="$2" wait_seconds="${3:-8}"
  local root="$sandbox/legacy-root"
  setsid env \
    PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    ANDROID_SERIAL="" \
    POCKETSHELL_AVD_LOCK_DIR="$sandbox/locks" \
    POCKETSHELL_POOL_SERIALS="emulator-5554" \
    POCKETSHELL_POOL_WAIT_SECONDS="$wait_seconds" \
    POCKETSHELL_AGENTS_PORT=2222 \
    FAKE_DEFAULT_SERIAL="emulator-5554" \
    FAKE_ONLINE_SERIALS="emulator-5554" \
    FAKE_DEVICE_STATE="$sandbox/device-state" \
    FAKE_RUN_ID="$run_id" \
    FAKE_KILL_HOLDER_ON_UNINSTALL_RUN_ID="${FAKE_KILL_HOLDER_ON_UNINSTALL_RUN_ID:-}" \
    bash "$root/scripts/connected-test.sh" --cleanup-suffixes \
      > "$sandbox/$run_id.out" 2> "$sandbox/$run_id.err" &
  WRAPPER_PID="$!"
  ACTIVE_PROCESS_GROUPS+=("$WRAPPER_PID")
}

assert_mixed_order_serialises() {
  local sandbox="$1" first_mode="$2" second_mode="$3"
  make_sandbox "$sandbox"

  local first_pid second_pid
  start_wrapper "$sandbox" "$first_mode" first i1737a
  first_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/first.started" 10 \
    || { kill_group "$first_pid"; fail "$first_mode first wrapper never reached instrumentation"; }

  start_wrapper "$sandbox" "$second_mode" second i1737b
  second_pid="$WRAPPER_PID"

  # Load-bearing assertion: while the first instrumentation owns emulator-5554,
  # the second wrapper must remain before cleanup/install/instrumentation.
  if wait_for_file "$sandbox/device-state/second.started" 2; then
    kill_group "$first_pid"
    kill_group "$second_pid"
    fail "$second_mode entered install/instrumentation while $first_mode owned emulator-5554 -- pool and legacy lock domains are split (issue #1737)"
  fi
  if [[ -e "$sandbox/device-state/overlap" ]]; then
    kill_group "$first_pid"
    kill_group "$second_pid"
    fail "mutation windows overlapped on emulator-5554"
  fi
  if grep -q '^second adb-uninstall ' "$sandbox/device-state/events" 2>/dev/null; then
    kill_group "$first_pid"
    kill_group "$second_pid"
    fail "$second_mode uninstalled packages before owning emulator-5554"
  fi

  touch "$sandbox/device-state/first.release"
  wait_for_exit "$first_pid" 10 \
    || { kill_group "$first_pid"; kill_group "$second_pid"; fail "first instrumentation did not survive/release"; }
  wait_for_file "$sandbox/device-state/second.started" 10 \
    || { kill_group "$second_pid"; fail "second wrapper did not acquire emulator-5554 after release"; }
  touch "$sandbox/device-state/second.release"
  wait_for_exit "$second_pid" 10 \
    || { kill_group "$second_pid"; fail "second instrumentation did not survive"; }

  [[ ! -e "$sandbox/device-state/overlap" ]] \
    || fail "pool/legacy mutation windows overlapped"
  [[ ! -e "$sandbox/device-state/killed-com.pocketshell.app.i1737a" ]] \
    || fail "first instrumentation package was killed"
  grep -q '^first instrumentation-survived emulator-5554 ' "$sandbox/device-state/events" \
    || fail "first instrumentation survival was not recorded"
  grep -q '^second instrumentation-survived emulator-5554 ' "$sandbox/device-state/events" \
    || fail "second instrumentation survival was not recorded"
  [[ "$(<"$sandbox/device-state/first.serial")" == "emulator-5554" ]] \
    || fail "first wrapper used the wrong serial"
  [[ "$(<"$sandbox/device-state/second.serial")" == "emulator-5554" ]] \
    || fail "second wrapper used the wrong serial"
}

pool_then_legacy_serialises() {
  assert_mixed_order_serialises "$1" pool legacy
}

legacy_then_pool_serialises() {
  assert_mixed_order_serialises "$1" legacy pool
}

cleanup_waits_for_serial_owner() {
  local sandbox="$1"
  make_sandbox "$sandbox"

  local owner_pid cleanup_pid
  start_wrapper "$sandbox" pool owner i1737owner
  owner_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/owner.started" 10 \
    || { kill_group "$owner_pid"; fail "owner wrapper never reached instrumentation"; }

  start_cleanup "$sandbox" cleanup
  cleanup_pid="$WRAPPER_PID"
  sleep 1
  if ! kill -0 "$cleanup_pid" 2>/dev/null; then
    kill_group "$owner_pid"
    fail "cleanup exited instead of waiting for the common serial owner"
  fi
  if grep -q '^cleanup adb-uninstall ' "$sandbox/device-state/events" 2>/dev/null; then
    kill_group "$owner_pid"
    kill_group "$cleanup_pid"
    fail "cleanup uninstalled a package while instrumentation owned emulator-5554"
  fi

  touch "$sandbox/device-state/owner.release"
  wait_for_exit "$owner_pid" 10 \
    || { kill_group "$owner_pid"; kill_group "$cleanup_pid"; fail "owner did not survive cleanup contention"; }
  wait_for_exit "$cleanup_pid" 10 \
    || { kill_group "$cleanup_pid"; fail "cleanup did not finish after serial release"; }
  grep -q '^owner instrumentation-survived emulator-5554 ' "$sandbox/device-state/events" \
    || fail "instrumentation package did not survive until ownership release"
}

early_failure_releases_for_other_mode() {
  local sandbox="$1"
  make_sandbox "$sandbox"

  local failed_pid next_pid failed_rc
  start_wrapper "$sandbox" legacy failed i1737failed \
    "emulator-5554" "" "emulator-5554" 8 7
  failed_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/failed.started" 10 \
    || { kill_group "$failed_pid"; fail "failing legacy wrapper never started"; }
  touch "$sandbox/device-state/failed.release"
  wait_until_stopped "$failed_pid" 10 \
    || { kill_group "$failed_pid"; fail "failing wrapper did not exit"; }
  set +e
  wait "$failed_pid"
  failed_rc=$?
  set -e
  [[ "$failed_rc" == "7" ]] \
    || fail "wrapper did not propagate the early Gradle failure (got $failed_rc, want 7)"

  start_wrapper "$sandbox" pool next i1737next
  next_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/next.started" 10 \
    || { kill_group "$next_pid"; fail "pool wrapper could not acquire after legacy failure"; }
  touch "$sandbox/device-state/next.release"
  wait_for_exit "$next_pid" 10 \
    || { kill_group "$next_pid"; fail "pool wrapper failed after prior early failure"; }
  [[ ! -e "$sandbox/device-state/overlap" ]] \
    || fail "early-failure cleanup left an overlapping mutation window"
}

busy_timeout_fails_before_mutation() {
  local sandbox="$1"
  make_sandbox "$sandbox"

  local owner_pid blocked_pid blocked_rc
  start_wrapper "$sandbox" pool owner i1737owner
  owner_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/owner.started" 10 \
    || { kill_group "$owner_pid"; fail "owner wrapper never started"; }

  start_wrapper "$sandbox" legacy blocked i1737blocked \
    "emulator-5554" "" "emulator-5554" 0
  blocked_pid="$WRAPPER_PID"
  wait_until_stopped "$blocked_pid" 5 \
    || { kill_group "$owner_pid"; kill_group "$blocked_pid"; fail "wait=0 did not preserve fail-fast timeout"; }
  set +e
  wait "$blocked_pid"
  blocked_rc=$?
  set -e
  [[ "$blocked_rc" != "0" ]] || fail "busy fail-fast wrapper unexpectedly succeeded"
  [[ ! -e "$sandbox/device-state/blocked.started" ]] \
    || fail "busy wrapper entered Gradle before failing ownership"
  ! grep -q '^blocked adb-uninstall ' "$sandbox/device-state/events" 2>/dev/null \
    || fail "busy wrapper uninstalled before failing ownership"
  grep -q 'no free emulator in the pool after 0s' "$sandbox/blocked.err" \
    || fail "busy failure lacked the bounded-timeout diagnostic"

  touch "$sandbox/device-state/owner.release"
  wait_for_exit "$owner_pid" 10 \
    || { kill_group "$owner_pid"; fail "owner did not survive fail-fast contender"; }
}

distinct_serials_run_concurrently() {
  local sandbox="$1"
  make_sandbox "$sandbox" "emulator-5554 emulator-5556"

  local legacy_pid pool_pid
  start_wrapper "$sandbox" legacy legacy i1737legacy \
    "emulator-5554 emulator-5556" "emulator-5554"
  legacy_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/legacy.started" 10 \
    || { kill_group "$legacy_pid"; fail "legacy distinct-serial wrapper never started"; }

  start_wrapper "$sandbox" pool pool i1737pool \
    "emulator-5554 emulator-5556" "emulator-5556"
  pool_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/pool.started" 3 \
    || { kill_group "$legacy_pid"; kill_group "$pool_pid"; fail "distinct serials were globally serialized"; }

  [[ "$(<"$sandbox/device-state/legacy.serial")" == "emulator-5554" ]] \
    || fail "legacy lane did not retain emulator-5554"
  [[ "$(<"$sandbox/device-state/pool.serial")" == "emulator-5556" ]] \
    || fail "pool lane did not retain emulator-5556"
  [[ ! -e "$sandbox/device-state/overlap" ]] \
    || fail "distinct serial mutation guards collided"

  touch "$sandbox/device-state/legacy.release" "$sandbox/device-state/pool.release"
  wait_for_exit "$legacy_pid" 10 \
    || { kill_group "$legacy_pid"; kill_group "$pool_pid"; fail "legacy distinct lane failed"; }
  wait_for_exit "$pool_pid" 10 \
    || { kill_group "$pool_pid"; fail "pool distinct lane failed"; }
}

lost_holder_fails_closed_and_stale_lock_recovers() {
  local sandbox="$1"
  make_sandbox "$sandbox"
  local result="$sandbox/lost-holder.result"

  # The single-quoted script intentionally expands inside the child shell.
  # shellcheck disable=SC2016
  env \
    PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    POCKETSHELL_AVD_LOCK_DIR="$sandbox/locks" \
    POCKETSHELL_POOL_SERIALS="emulator-5554" \
    POCKETSHELL_POOL_WAIT_SECONDS=0 \
    FAKE_DEFAULT_SERIAL="emulator-5554" \
    FAKE_ONLINE_SERIALS="emulator-5554" \
    bash -c '
      set -euo pipefail
      source "$1/scripts/lib/avd-lock.sh"
      pocketshell_claim_pool_serial "$1" >/dev/null
      lock_file="$POCKETSHELL_AVD_LOCK_FILE"
      holder="$POCKETSHELL_POOL_HOLDER_PID"
      kill "$holder"
      wait "$holder" 2>/dev/null || true
      if pocketshell_assert_avd_lock_owned "$lock_file" >/dev/null 2>"$2"; then
        echo "assert-succeeded" > "$3"
        exit 1
      fi
      flock -n "$lock_file" true
      echo "lost-holder-rejected-and-lock-recoverable" > "$3"
    ' bash "$sandbox/pool-root" "$sandbox/lost-holder.err" "$result" \
    || fail "lost-holder ownership check or stale-lock recovery failed"

  [[ "$(<"$result")" == "lost-holder-rejected-and-lock-recoverable" ]] \
    || fail "lost ownership did not fail closed"
  grep -q 'lost emulator ownership' "$sandbox/lost-holder.err" \
    || fail "lost ownership lacked a clear diagnostic"
  [[ ! -s "$sandbox/device-state/events" ]] \
    || fail "device mutation occurred after ownership loss"
}

holder_loss_at_gradle_boundary_fails_before_mutation() {
  local sandbox="$1"
  make_sandbox "$sandbox"

  local first_pid contender_pid first_rc
  FAKE_KILL_HOLDER_RUN_ID=first \
    start_wrapper "$sandbox" pool first i1737loss
  first_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/first.holder-killed" 10 \
    || { kill_group "$first_pid"; fail "holder-loss hook never reached the post-check/pre-mutation boundary"; }

  start_wrapper "$sandbox" legacy contender i1737contender
  contender_pid="$WRAPPER_PID"

  # With continuous wrapper-owned flock, the contender cannot enter while the
  # first wrapper diagnoses helper loss and terminates the not-yet-mutating
  # Gradle child. Snapshot-only ownership releases here and the contender
  # enters immediately -- the reviewer's exact counterexample.
  local waited=0
  while kill -0 "$first_pid" 2>/dev/null; do
    if [[ -e "$sandbox/device-state/contender.started" ]]; then
      touch "$sandbox/device-state/first.continue"
      kill_group "$first_pid"
      kill_group "$contender_pid"
      fail "same-serial contender entered after helper death while the first wrapper was still live -- ownership was only a snapshot"
    fi
    (( waited++ >= 200 )) && {
      touch "$sandbox/device-state/first.continue"
      kill_group "$first_pid"
      kill_group "$contender_pid"
      fail "wrapper did not terminate within the ownership-loss bound"
    }
    sleep 0.05
  done

  wait_until_stopped "$first_pid" 10 \
    || {
      touch "$sandbox/device-state/first.continue"
      kill_group "$first_pid"
      kill_group "$contender_pid"
      fail "wrapper did not fail closed after helper death at the Gradle boundary"
    }
  set +e
  wait "$first_pid"
  first_rc=$?
  set -e
  [[ "$first_rc" != "0" ]] || fail "ownership-losing wrapper unexpectedly succeeded"
  grep -q 'lost emulator ownership' "$sandbox/first.err" \
    || fail "boundary ownership loss lacked a clear diagnostic"
  [[ -e "$sandbox/device-state/first.terminated" ]] \
    || fail "wrapper did not terminate the pre-mutation Gradle child after ownership loss"
  [[ ! -e "$sandbox/device-state/first.started" ]] \
    || fail "first wrapper mutated after losing its helper at the Gradle boundary"
  ! grep -Eq '^first (adb-uninstall|install-instrument) ' "$sandbox/device-state/events" 2>/dev/null \
    || fail "first wrapper recorded device mutation after ownership loss"

  wait_for_file "$sandbox/device-state/contender.started" 10 \
    || { kill_group "$contender_pid"; fail "contender did not acquire after the failed wrapper released ownership"; }
  touch "$sandbox/device-state/contender.release"
  wait_for_exit "$contender_pid" 10 \
    || { kill_group "$contender_pid"; fail "contender failed after ownership-loss recovery"; }
  [[ ! -e "$sandbox/device-state/overlap" ]] \
    || fail "ownership-loss correction allowed overlapping mutation"

  local serial_lock="$sandbox/locks/avd-lock-emulator-5554"
  flock -n "$serial_lock" true \
    || fail "ownership-loss correction left a stale flock behind"
}

holder_loss_at_cleanup_boundary_fails_before_uninstall() {
  local sandbox="$1"
  make_sandbox "$sandbox"
  printf 'com.pocketshell.app.i1737stale\n' \
    > "$sandbox/device-state/packages-emulator-5554"

  local cleanup_pid contender_pid cleanup_rc
  FAKE_KILL_HOLDER_ON_UNINSTALL_RUN_ID=cleanup \
    start_cleanup "$sandbox" cleanup
  cleanup_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/cleanup.uninstall-holder-killed" 10 \
    || { kill_group "$cleanup_pid"; fail "cleanup holder-loss hook never reached the pre-uninstall boundary"; }

  start_wrapper "$sandbox" pool contender i1737cleanupcontender
  contender_pid="$WRAPPER_PID"

  local waited=0
  while kill -0 "$cleanup_pid" 2>/dev/null; do
    if [[ -e "$sandbox/device-state/contender.started" ]]; then
      touch "$sandbox/device-state/cleanup.uninstall-continue"
      kill_group "$cleanup_pid"
      kill_group "$contender_pid"
      fail "contender entered while ownership-losing cleanup wrapper was still live"
    fi
    (( waited++ >= 200 )) && {
      touch "$sandbox/device-state/cleanup.uninstall-continue"
      kill_group "$cleanup_pid"
      kill_group "$contender_pid"
      fail "cleanup wrapper did not terminate within the ownership-loss bound"
    }
    sleep 0.05
  done

  set +e
  wait "$cleanup_pid"
  cleanup_rc=$?
  set -e
  [[ "$cleanup_rc" != "0" ]] || fail "ownership-losing cleanup unexpectedly succeeded"
  grep -q 'lost emulator ownership' "$sandbox/cleanup.err" \
    || fail "cleanup ownership loss lacked a clear diagnostic"
  [[ -e "$sandbox/device-state/cleanup.uninstall-terminated" ]] \
    || fail "wrapper did not terminate adb before the ownership-losing uninstall"
  ! grep -q '^cleanup adb-uninstall ' "$sandbox/device-state/events" 2>/dev/null \
    || fail "cleanup uninstalled a package after helper loss"

  wait_for_file "$sandbox/device-state/contender.started" 10 \
    || { kill_group "$contender_pid"; fail "contender did not acquire after cleanup failed closed"; }
  touch "$sandbox/device-state/contender.release"
  wait_for_exit "$contender_pid" 10 \
    || { kill_group "$contender_pid"; fail "contender failed after cleanup ownership loss"; }
  [[ ! -e "$sandbox/device-state/overlap" ]] \
    || fail "cleanup ownership loss allowed overlapping mutation"
  flock -n "$sandbox/locks/avd-lock-emulator-5554" true \
    || fail "cleanup ownership loss left a stale flock"
}

hard_killed_wrapper_leaves_no_descendant_flock() {
  local sandbox="$1"
  make_sandbox "$sandbox"

  local wrapper_pid contender_pid
  FAKE_PAUSE_BEFORE_MUTATION_RUN_ID=crashed \
    start_wrapper "$sandbox" pool crashed i1737crashed
  wrapper_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/crashed.pre-mutation-paused" 10 \
    || { kill_group "$wrapper_pid"; fail "hard-crash case never reached the pre-mutation pause"; }

  local serial_lock="$sandbox/locks/avd-lock-emulator-5554"
  local descendants=()
  mapfile -t descendants < <(descendant_pids "$wrapper_pid")
  (( ${#descendants[@]} >= 2 )) \
    || fail "hard-crash case did not expose the async shell and mutation child"

  local pid fd target
  for pid in "${descendants[@]}"; do
    for fd in "/proc/$pid/fd/"*; do
      [[ -e "$fd" ]] || continue
      target="$(readlink -f "$fd" 2>/dev/null || true)"
      [[ "$target" != "$serial_lock" ]] \
        || fail "wrapper descendant $pid retained the continuous serial flock FD before the crash"
    done
  done

  # Kill ONLY the authoritative wrapper. Its EXIT trap cannot run. The paused
  # async shell/Gradle descendants deliberately remain alive, yet none may own
  # the wrapper's FD and the kernel flock must be immediately reclaimable.
  kill -KILL "$wrapper_pid"
  wait "$wrapper_pid" 2>/dev/null || true
  local live_descendant=0
  for pid in "${descendants[@]}"; do
    kill -0 "$pid" 2>/dev/null && live_descendant=1
  done
  (( live_descendant == 1 )) \
    || fail "hard-crash proof lost all descendants instead of exercising orphan FD behavior"
  if ! flock -n "$serial_lock" true; then
    for fd in /proc/[0-9]*/fd/*; do
      [[ "$(readlink -f "$fd" 2>/dev/null || true)" == "$serial_lock" ]] || continue
      printf 'leaked-lock-fd: pid=%s fd=%s cmd=%s\n' \
        "$(cut -d/ -f3 <<< "$fd")" "$fd" \
        "$(tr '\0' ' ' < "/proc/$(cut -d/ -f3 <<< "$fd")/cmdline" 2>/dev/null || true)" >&2
    done
    fail "SIGKILLed wrapper left its serial flock inherited by an async descendant"
  fi
  [[ ! -e "$sandbox/device-state/crashed.started" ]] \
    || fail "hard-killed wrapper mutated before ownership reclamation"

  start_wrapper "$sandbox" legacy contender i1737aftercrash
  contender_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/contender.started" 10 \
    || { kill_group "$contender_pid"; fail "contender could not reclaim the serial after wrapper SIGKILL"; }
  touch "$sandbox/device-state/contender.release"
  wait_for_exit "$contender_pid" 10 \
    || { kill_group "$contender_pid"; fail "post-crash contender failed"; }
  [[ ! -e "$sandbox/device-state/overlap" ]] \
    || fail "post-crash contender overlapped a mutation from the killed wrapper"

  kill_group "$wrapper_pid"
}

hard_killed_pool_setup_leaves_no_descendant_flock() {
  local sandbox="$1"
  make_sandbox "$sandbox"
  inject_agents_fork_pause "$sandbox/pool-root"

  local wrapper_pid
  FAKE_PAUSE_AFTER_AGENTS_FORK_RUN_ID=poolsetup \
    start_wrapper "$sandbox" pool poolsetup i1737poolsetup \
      emulator-5554 "" emulator-5554 8 0 ""
  wrapper_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/poolsetup.agents-fork-paused" 10 \
    || { kill_group "$wrapper_pid"; fail "dynamic agents-port claim never paused after its async fork"; }

  local serial_lock="$sandbox/locks/avd-lock-emulator-5554"
  local descendants=()
  mapfile -t descendants < <(descendant_pids "$wrapper_pid")
  (( ${#descendants[@]} >= 2 )) \
    || fail "dynamic agents-port claim did not leave its helper descendants alive"

  local pid fd target
  for pid in "${descendants[@]}"; do
    for fd in "/proc/$pid/fd/"*; do
      [[ -e "$fd" ]] || continue
      target="$(readlink -f "$fd" 2>/dev/null || true)"
      [[ "$target" != "$serial_lock" ]] \
        || fail "pool-setup descendant $pid retained the continuous serial flock FD before the crash"
    done
  done

  kill -KILL "$wrapper_pid"
  wait "$wrapper_pid" 2>/dev/null || true
  local live_descendant=0
  for pid in "${descendants[@]}"; do
    kill -0 "$pid" 2>/dev/null && live_descendant=1
  done
  (( live_descendant == 1 )) \
    || fail "pool-setup proof lost every descendant instead of exercising orphan FD behavior"
  flock -n "$serial_lock" true \
    || fail "SIGKILLed pool wrapper left its serial flock in the agents-port setup tree"
  [[ ! -e "$sandbox/device-state/poolsetup.started" ]] \
    || fail "pool wrapper reached emulator mutation before the setup crash proof"

  kill_group "$wrapper_pid"
}

assert_hard_killed_docker_phase_reclaims_serial() {
  local sandbox="$1" phase="$2"
  make_sandbox "$sandbox"

  local wrapper_pid marker="$sandbox/device-state/pooldocker.docker-$phase-paused"
  FAKE_PAUSE_DOCKER_PHASE="$phase" \
    start_wrapper "$sandbox" pool pooldocker "i1737docker$phase" \
      emulator-5554 "" emulator-5554 8 0 ""
  wrapper_pid="$WRAPPER_PID"
  wait_for_file "$marker" 10 \
    || { kill_group "$wrapper_pid"; fail "agents fixture Docker $phase child never paused"; }

  local serial_lock="$sandbox/locks/avd-lock-emulator-5554"
  local descendants=()
  mapfile -t descendants < <(descendant_pids "$wrapper_pid")
  (( ${#descendants[@]} >= 3 )) \
    || fail "Docker $phase proof did not leave sentinel, port-holder, and Docker descendants alive"

  local pid fd target
  for pid in "${descendants[@]}"; do
    for fd in "/proc/$pid/fd/"*; do
      [[ -e "$fd" ]] || continue
      target="$(readlink -f "$fd" 2>/dev/null || true)"
      [[ "$target" != "$serial_lock" ]] \
        || fail "agents Docker $phase descendant $pid retained the continuous serial flock FD"
    done
  done

  kill -KILL "$wrapper_pid"
  wait "$wrapper_pid" 2>/dev/null || true
  local live_descendant=0
  for pid in "${descendants[@]}"; do
    kill -0 "$pid" 2>/dev/null && live_descendant=1
  done
  (( live_descendant == 1 )) \
    || fail "Docker $phase proof lost all descendants after wrapper SIGKILL"
  flock -n "$serial_lock" true \
    || fail "SIGKILLed pool wrapper left its serial flock in Docker $phase setup"
  [[ ! -e "$sandbox/device-state/pooldocker.started" ]] \
    || fail "pool wrapper reached emulator mutation during Docker $phase crash proof"

  kill_group "$wrapper_pid"
}

hard_killed_agents_docker_up_leaves_no_descendant_flock() {
  assert_hard_killed_docker_phase_reclaims_serial "$1" up
}

hard_killed_agents_docker_health_leaves_no_descendant_flock() {
  assert_hard_killed_docker_phase_reclaims_serial "$1" health
}

hard_killed_toxiproxy_holder_leaves_no_descendant_flock() {
  local sandbox="$1"
  make_sandbox "$sandbox"

  local wrapper_pid
  FAKE_PAUSE_BEFORE_MUTATION_RUN_ID=toxcrash \
    start_wrapper "$sandbox" pool toxcrash i1737tox \
      emulator-5554 "" emulator-5554 8 0 2222 NetworkFaultProofBase
  wrapper_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/toxcrash.pre-mutation-paused" 10 \
    || { kill_group "$wrapper_pid"; fail "network-fault run never reached its mutation pause"; }
  [[ -e "$sandbox/locks/toxiproxy-serial-lock" ]] \
    || fail "network-fault run did not exercise the real toxiproxy holder path"

  local serial_lock="$sandbox/locks/avd-lock-emulator-5554"
  local descendants=()
  mapfile -t descendants < <(descendant_pids "$wrapper_pid")
  (( ${#descendants[@]} >= 3 )) \
    || fail "toxiproxy proof did not leave sentinel, toxiproxy, and mutation descendants alive"

  local pid fd target
  for pid in "${descendants[@]}"; do
    for fd in "/proc/$pid/fd/"*; do
      [[ -e "$fd" ]] || continue
      target="$(readlink -f "$fd" 2>/dev/null || true)"
      [[ "$target" != "$serial_lock" ]] \
        || fail "toxiproxy-path descendant $pid retained the continuous serial flock FD"
    done
  done

  kill -KILL "$wrapper_pid"
  wait "$wrapper_pid" 2>/dev/null || true
  local live_descendant=0
  for pid in "${descendants[@]}"; do
    kill -0 "$pid" 2>/dev/null && live_descendant=1
  done
  (( live_descendant == 1 )) \
    || fail "toxiproxy proof lost all descendants after wrapper SIGKILL"
  flock -n "$serial_lock" true \
    || fail "SIGKILLed network-fault wrapper left its serial flock in a descendant"
  [[ ! -e "$sandbox/device-state/toxcrash.started" ]] \
    || fail "network-fault wrapper mutated before the crash proof"

  kill_group "$wrapper_pid"
}

run_case() {
  local name="$1" sandbox
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-serial-owner.XXXXXX")"
  ACTIVE_CASE_SANDBOX="$sandbox"
  ACTIVE_PROCESS_GROUPS=()
  "$name" "$sandbox"
  cleanup_active_case
  ACTIVE_CASE_SANDBOX=""
  ACTIVE_PROCESS_GROUPS=()
  printf '  ok: %s\n' "$name"
}

CASES=(
  pool_then_legacy_serialises
  legacy_then_pool_serialises
  cleanup_waits_for_serial_owner
  early_failure_releases_for_other_mode
  busy_timeout_fails_before_mutation
  distinct_serials_run_concurrently
  lost_holder_fails_closed_and_stale_lock_recovers
  holder_loss_at_gradle_boundary_fails_before_mutation
  holder_loss_at_cleanup_boundary_fails_before_uninstall
  hard_killed_wrapper_leaves_no_descendant_flock
  hard_killed_pool_setup_leaves_no_descendant_flock
  hard_killed_agents_docker_up_leaves_no_descendant_flock
  hard_killed_agents_docker_health_leaves_no_descendant_flock
  hard_killed_toxiproxy_holder_leaves_no_descendant_flock
)
if [[ $# -gt 0 ]]; then
  CASES=("$@")
fi
for case_name in "${CASES[@]}"; do
  run_case "$case_name"
done

printf 'PASS: connected-test per-serial ownership (issue #1737)\n'
