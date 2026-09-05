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

# Issue #1989: connected-test.sh now refuses to start below a free-space floor.
# This harness is about SERIAL OWNERSHIP, and it runs on hosted runners whose
# free space is not its business — an ownership test that goes red because the
# runner is 70% full is a test that gets disabled. Pin the floor to 0 MiB: a
# threshold, not a skip, so the preflight still runs on every fixture here.
# tests/scripts/disk-preflight-test.sh owns the disk behaviour.
export POCKETSHELL_DISK_MIN_FREE_MB=0
export POCKETSHELL_DISK_WARN_FREE_MB=0

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REAL_FLOCK="$(command -v flock)"
ACTIVE_CASE_SANDBOX=""
ACTIVE_PROCESS_GROUPS=()

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

lifecycle_event_epoch() {
  local lifecycle_log="$1" event="$2" timestamp
  timestamp="$(sed -n "s/^timestamp=\([^ ]*\) event=$event\([[:space:]].*\)\{0,1\}$/\1/p" \
    "$lifecycle_log" | tail -1)"
  [[ -n "$timestamp" ]] || fail "lifecycle log has no timestamp for event=$event"
  date -u --date="$timestamp" +%s 2>/dev/null \
    || fail "lifecycle event=$event has an unparsable UTC timestamp: $timestamp"
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
  if ps -eo pgid=,stat= | awk -v pgid="$pid" \
      '$1 == pgid && $2 !~ /^Z/ { found = 1 } END { exit !found }'; then
    ps -eo pid=,ppid=,pgid=,stat=,comm= | awk -v pgid="$pid" '$3 == pgid' >&2
    return 1
  fi
}

cleanup_active_case() {
  local pid cleanup_rc=0
  for pid in "${ACTIVE_PROCESS_GROUPS[@]:-}"; do
    kill_group "$pid" || cleanup_rc=1
  done
  if [[ -n "$ACTIVE_CASE_SANDBOX" ]]; then
    rm -rf "$ACTIVE_CASE_SANDBOX"
  fi
  return "$cleanup_rc"
}
trap 'cleanup_active_case || true' EXIT

descendant_pids() {
  local parent="$1"
  local child
  while IFS= read -r child; do
    [[ -n "$child" ]] || continue
    printf '%s\n' "$child"
    descendant_pids "$child"
  done < <(ps -o pid= --ppid "$parent" 2>/dev/null | awk '{ print $1 }')
}

serial_lock_fds_in_group() {
  local lock_file="$1" process_group="$2"
  local pid fd target
  while IFS= read -r pid; do
    pid="${pid//[[:space:]]/}"
    [[ -n "$pid" ]] || continue
    for fd in "/proc/$pid/fd/"*; do
      [[ -e "$fd" ]] || continue
      target="$(readlink -f "$fd" 2>/dev/null || true)"
      [[ "$target" == "$lock_file" ]] || continue
      printf 'lock-fd: resource=%s process_group=%s pid=%s fd=%s comm=%s\n' \
        "$lock_file" "$process_group" "$pid" "${fd##*/}" \
        "$(tr '\0' ' ' < "/proc/$pid/comm" 2>/dev/null || true)"
    done
  done < <(ps -eo pid=,pgid= | awk -v pgid="$process_group" '$2 == pgid { print $1 }')
}

# The ONLY reclaim oracle in this harness (issue #2421). Never probe a serial
# lock with a bare one-shot `flock -n`: `connected-test.sh` re-asserts ownership
# every 50ms by forking `( flock -n 9 ) {FD}>&- 9>"$lock"`, so at the instant a
# wrapper is SIGKILLed an already-forked probe legitimately acquires the
# just-released lock for a few scheduler turns. A zero-tolerance instantaneous
# probe reads that transient as an inherited-FD leak and reddens the harness
# (#2421: reproduced 3/300 under CPU contention with the exact
# "left its serial flock in a descendant" message).
#
# The property under test is steady state: a descendant that truly INHERITED the
# wrapper's continuous FD holds the lock forever, so it never becomes free and
# this helper still fails closed after its bounded window — proven by
# `retained_descendant_flock_fails_the_reclaim_oracle` below.
wait_for_serial_flock_reclaim() {
  local lock_file="$1" process_group="$2" timeout="${3:-3}"
  local waited=0 limit=$((timeout * 100))
  while ! "$REAL_FLOCK" -n "$lock_file" true; do
    if (( waited++ >= limit )); then
      serial_lock_fds_in_group "$lock_file" "$process_group" >&2
      return 1
    fi
    sleep 0.01
  done
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
      printf '%s\n' "$$" > "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-pid"
      if [[ "${FAKE_IGNORE_UNINSTALL_TERM_RUN_ID:-}" == "$FAKE_RUN_ID" ]]; then
        trap '' TERM INT
      else
        trap 'touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-terminated"; exit 143' TERM INT
      fi
      # Publish the deterministic test boundary only after signal handling is
      # installed. Previously the fake killed the ownership sentinel and
      # published its marker first; the wrapper could then TERM this shell
      # before the trap existed. The child was correctly gone, but the missing
      # trap-only marker falsely failed the complete Gradle gate (#1737
      # recurrence). The PID assertion below is the authoritative process-death
      # proof; this marker synchronizes the intended TERM-vs-KILL branch.
      touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-handler-ready"
      if [[ "${FAKE_PAUSE_AFTER_UNINSTALL_OWNERSHIP_LOSS_RUN_ID:-}" == "$FAKE_RUN_ID" ]]; then
        touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-handler-window"
      fi
      kill -KILL "$holder" 2>/dev/null || true
      touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-holder-killed"
      if [[ "${FAKE_PAUSE_AFTER_UNINSTALL_OWNERSHIP_LOSS_RUN_ID:-}" == "$FAKE_RUN_ID" ]]; then
        while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.uninstall-handler-continue" ]]; do
          sleep 0.02
        done
      fi
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

make_fake_scope_tools() {
  local sandbox="$1"
  cat > "$sandbox/bin/systemctl" <<'SYSTEMCTL'
#!/usr/bin/env bash
set -euo pipefail

printf 'systemctl %s\n' "$*" >> "$FAKE_DEVICE_STATE/scope-tool-events"
# An empty response makes scope-run treat user systemd as unavailable. The
# harness explicitly opts into its bare path below, so no process or unit ever
# enters the host's shared user systemd manager.
exit 1
SYSTEMCTL
  chmod +x "$sandbox/bin/systemctl"

  cat > "$sandbox/bin/systemd-run" <<'SYSTEMD_RUN'
#!/usr/bin/env bash
set -euo pipefail

printf 'systemd-run %s\n' "$*" >> "$FAKE_DEVICE_STATE/scope-tool-events"
touch "$FAKE_DEVICE_STATE/unexpected-systemd-run"
printf 'FAIL: hermetic ownership harness invoked the host scope launcher\n' >&2
exit 97
SYSTEMD_RUN
  chmod +x "$sandbox/bin/systemd-run"
}

make_fake_flock() {
  local sandbox="$1"
  cat > "$sandbox/bin/flock" <<'FLOCK'
#!/usr/bin/env bash
set -euo pipefail

real_flock="${FAKE_REAL_FLOCK:?}"
device_state="${FAKE_DEVICE_STATE:-}"
run_id="${FAKE_RUN_ID:-}"
paused_run_id="${FAKE_PAUSE_FLOCK_PROBE_RUN_ID:-}"

# Issue #2085 controlled reproduction. The production ownership assertion
# probes the selected serial with `flock -n 9` while the wrapper retains its
# continuous FD. If the wrapper is SIGKILLed after this probe is forked but
# before it runs, the probe can acquire the just-released lock and briefly look
# exactly like an inherited-FD leak. Hold that already-real flock long enough
# for the harness to observe the historical failure deterministically.
if [[ -n "$paused_run_id" \
      && "$paused_run_id" == "$run_id" \
      && "${1:-}" == "-n" \
      && "${2:-}" == "9" \
      && -e "$device_state/$run_id.pre-mutation-paused" ]] \
    && mkdir "$device_state/$run_id.flock-probe-once" 2>/dev/null; then
  printf '%s\n' "$BASHPID" > "$device_state/$run_id.flock-probe-pid"
  touch "$device_state/$run_id.flock-probe-ready"
  while [[ ! -e "$device_state/$run_id.flock-probe-continue" ]]; do
    sleep 0.01
  done
  lock_path="$(readlink -f "/proc/$BASHPID/fd/9")"
  exec "$real_flock" -n "$lock_path" bash -c '
    printf "%s\n" "$BASHPID" > "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.flock-probe-holder-pid"
    touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.flock-probe-acquired"
    while [[ ! -e "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.flock-probe-release" ]]; do
      sleep 0.01
    done
    # Keep the real lock across a few scheduler turns after the release request.
    # A retrying oracle passes; restoring the historical one-shot oracle is a
    # deterministic selective mutation for the issue #2085 regression.
    sleep 0.15
    touch "$FAKE_DEVICE_STATE/$FAKE_RUN_ID.flock-probe-released"
  '
fi

exec "$real_flock" "$@"
FLOCK
  chmod +x "$sandbox/bin/flock"
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

# Issue #2421 mutation. Re-inject the exact #1737 defect into ONE sandbox's
# private copy of the helper: drop the close-on-creation redirection that keeps
# the device-mutating child off the wrapper's continuous flock FD, so the child
# genuinely INHERITS it. Used to prove the bounded-retry reclaim oracle still
# fails closed on a retained FD rather than degrading into "wait until free".
inject_inherited_lock_fd_leak() {
  local root="$1"
  local target="$root/scripts/lib/avd-lock.sh"
  # Both literals are the sandbox file's exact bytes; nothing expands here.
  # shellcheck disable=SC2016
  local original='    "$@" {POCKETSHELL_AVD_LOCK_FD}>&- &'
  # shellcheck disable=SC2016
  local leaked='    "$@" &'
  local before after
  before="$(grep -cFx "$original" "$target" || true)"
  (( before == 1 )) \
    || fail "inherited-FD mutation target moved: expected exactly 1 close-on-creation line in $target, found $before"
  awk -v original="$original" -v leaked="$leaked" '
    $0 == original && replaced == 0 { print leaked; replaced = 1; next }
    { print }
  ' "$target" > "$target.mutated"
  mv "$target.mutated" "$target"
  after="$(grep -cFx "$original" "$target" || true)"
  (( after == 0 )) \
    || fail "inherited-FD mutation was a no-op: $target still closes the wrapper FD on child creation"
  grep -qFx "$leaked" "$target" \
    || fail "inherited-FD mutation did not install the leaking child-start line in $target"
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
if [[ "${FAKE_GRADLE_RC:-0}" == "0" && -n "${POCKETSHELL_CONNECTED_TEST_REPORT_DIR:-}" ]]; then
  mkdir -p "$POCKETSHELL_CONNECTED_TEST_REPORT_DIR"
  if [[ "${FAKE_GRADLE_XML_RED_RUN_ID:-}" == "$FAKE_RUN_ID" ]]; then
    cat > "$POCKETSHELL_CONNECTED_TEST_REPORT_DIR/TEST-stub.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="stub" tests="1" skipped="0" failures="1" errors="0"><testcase name="stub" classname="stub"><failure message="mutated authoritative result"/></testcase></testsuite>
XML
  else
    cat > "$POCKETSHELL_CONNECTED_TEST_REPORT_DIR/TEST-stub.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="stub" tests="1" skipped="0" failures="0" errors="0"><testcase name="stub" classname="stub"/></testsuite>
XML
  fi
fi
exit "${FAKE_GRADLE_RC:-0}"
GRADLEW
  chmod +x "$root/gradlew"
}

make_sandbox() {
  local sandbox="$1" serials="${2:-emulator-5554}"
  mkdir -p "$sandbox/bin" "$sandbox/device-state" "$sandbox/locks" "$sandbox/tmp"
  # Issue #2007: the wrapper now also owns its checkout's gradle output tree.
  # Anchor that lock inside this sandbox so a case can neither queue behind a
  # real build on the box nor leave a lock file per sandbox in the real
  # per-user lock directory.
  mkdir -p "$sandbox/gradle-output-locks"
  export POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$sandbox/gradle-output-locks"
  make_fake_adb "$sandbox"
  make_fake_docker "$sandbox"
  make_fake_scope_tools "$sandbox"
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
    TZ="${FAKE_WRAPPER_TZ:-${TZ:-UTC0}}" \
    TMPDIR="$sandbox/tmp" \
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
    POCKETSHELL_SCOPE_ALLOW_BARE=1 \
    POCKETSHELL_CONNECTED_TEST_LIFECYCLE_DIR="$sandbox/lifecycle" \
    FAKE_LIFECYCLE_SECRET=SuperSecretEnvironmentValue2004 \
    FAKE_DEFAULT_SERIAL="${online_serials%% *}" \
    FAKE_ONLINE_SERIALS="$online_serials" \
    FAKE_DEVICE_STATE="$sandbox/device-state" \
    FAKE_RUN_ID="$run_id" \
    FAKE_GRADLE_RC="$gradle_rc" \
    FAKE_GRADLE_XML_RED_RUN_ID="${FAKE_GRADLE_XML_RED_RUN_ID:-}" \
    FAKE_REAL_FLOCK="$REAL_FLOCK" \
    FAKE_PAUSE_FLOCK_PROBE_RUN_ID="${FAKE_PAUSE_FLOCK_PROBE_RUN_ID:-}" \
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
    TMPDIR="$sandbox/tmp" \
    PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    ANDROID_SERIAL="" \
    POCKETSHELL_AVD_LOCK_DIR="$sandbox/locks" \
    POCKETSHELL_POOL_SERIALS="emulator-5554" \
    POCKETSHELL_POOL_WAIT_SECONDS="$wait_seconds" \
    POCKETSHELL_AGENTS_PORT=2222 \
    POCKETSHELL_SCOPE_ALLOW_BARE=1 \
    FAKE_DEFAULT_SERIAL="emulator-5554" \
    FAKE_ONLINE_SERIALS="emulator-5554" \
    FAKE_DEVICE_STATE="$sandbox/device-state" \
    FAKE_RUN_ID="$run_id" \
    FAKE_KILL_HOLDER_ON_UNINSTALL_RUN_ID="${FAKE_KILL_HOLDER_ON_UNINSTALL_RUN_ID:-}" \
    FAKE_PAUSE_AFTER_UNINSTALL_OWNERSHIP_LOSS_RUN_ID="${FAKE_PAUSE_AFTER_UNINSTALL_OWNERSHIP_LOSS_RUN_ID:-}" \
    FAKE_IGNORE_UNINSTALL_TERM_RUN_ID="${FAKE_IGNORE_UNINSTALL_TERM_RUN_ID:-}" \
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
      # `pocketshell_claim_pool_serial` starts the holder inside a command
      # substitution, so it is orphaned to ppid 1 and `wait` returns 127
      # ("not a child of this shell") instantly -- `kill` alone is NOT a
      # synchronisation point (issue #2421, same defect family as the
      # single-shot flock probes). Poll for the holder to actually die, or the
      # ownership assertion below sees a still-live holder, passes, and reddens
      # this case for a scheduling reason rather than a behavioural one.
      waited=0
      while kill -0 "$holder" 2>/dev/null; do
        if (( waited++ >= 500 )); then
          echo "holder-outlived-kill" > "$3"
          exit 1
        fi
        sleep 0.01
      done
      if pocketshell_assert_avd_lock_owned "$lock_file" >/dev/null 2>"$2"; then
        echo "assert-succeeded" > "$3"
        exit 1
      fi
      # Bounded retry, never a single shot (issue #2421): the recoverable-lock
      # property is steady state. A lock genuinely retained by a surviving FD
      # never frees, so this still fails closed after its window.
      waited=0
      until flock -n "$lock_file" true; do
        if (( waited++ >= 300 )); then
          echo "stale-lock-not-recoverable" > "$3"
          exit 1
        fi
        sleep 0.01
      done
      echo "lost-holder-rejected-and-lock-recoverable" > "$3"
    ' bash "$sandbox/pool-root" "$sandbox/lost-holder.err" "$result" \
    || fail "lost-holder ownership check or stale-lock recovery failed (result=$(cat "$result" 2>/dev/null || true); stderr=$(cat "$sandbox/lost-holder.err" 2>/dev/null || true))"

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
  wait_for_serial_flock_reclaim "$serial_lock" "$first_pid" 3 \
    || fail "ownership-loss correction left a stale flock behind"
}

assert_holder_loss_at_cleanup_boundary_fails_closed() {
  local sandbox="$1" run_id="$2" ignore_term="$3"
  make_sandbox "$sandbox"
  printf 'com.pocketshell.app.i1737stale\n' \
    > "$sandbox/device-state/packages-emulator-5554"

  local cleanup_pid contender_pid cleanup_rc ignore_term_run_id=""
  if [[ "$ignore_term" == "1" ]]; then
    ignore_term_run_id="$run_id"
  fi
  FAKE_KILL_HOLDER_ON_UNINSTALL_RUN_ID="$run_id" \
    FAKE_PAUSE_AFTER_UNINSTALL_OWNERSHIP_LOSS_RUN_ID="$run_id" \
    FAKE_IGNORE_UNINSTALL_TERM_RUN_ID="$ignore_term_run_id" \
    start_cleanup "$sandbox" "$run_id"
  cleanup_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/$run_id.uninstall-handler-window" 10 \
    || { kill_group "$cleanup_pid"; fail "cleanup holder-loss hook never reached the pre-uninstall boundary"; }

  start_wrapper "$sandbox" pool contender "i1737${run_id}contender"
  contender_pid="$WRAPPER_PID"

  local waited=0
  while kill -0 "$cleanup_pid" 2>/dev/null; do
    if [[ -e "$sandbox/device-state/contender.started" ]]; then
      touch "$sandbox/device-state/$run_id.uninstall-continue"
      kill_group "$cleanup_pid"
      kill_group "$contender_pid"
      fail "contender entered while ownership-losing cleanup wrapper was still live"
    fi
    (( waited++ >= 200 )) && {
      touch "$sandbox/device-state/$run_id.uninstall-continue"
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
  grep -q 'lost emulator ownership' "$sandbox/$run_id.err" \
    || fail "cleanup ownership loss lacked a clear diagnostic"
  local uninstall_pid
  uninstall_pid="$(<"$sandbox/device-state/$run_id.uninstall-pid")"
  ! kill -0 "$uninstall_pid" 2>/dev/null \
    || fail "ownership-losing adb uninstall process remained alive after wrapper exit"
  if [[ "$ignore_term" == "1" ]]; then
    [[ ! -e "$sandbox/device-state/$run_id.uninstall-terminated" ]] \
      || fail "TERM-ignoring adb unexpectedly ran the graceful termination trap"
  else
    [[ -e "$sandbox/device-state/$run_id.uninstall-terminated" ]] \
      || fail "wrapper did not terminate adb before the ownership-losing uninstall"
  fi
  ! grep -q "^$run_id adb-uninstall " "$sandbox/device-state/events" 2>/dev/null \
    || fail "cleanup uninstalled a package after helper loss"

  wait_for_file "$sandbox/device-state/contender.started" 10 \
    || { kill_group "$contender_pid"; fail "contender did not acquire after cleanup failed closed"; }
  touch "$sandbox/device-state/contender.release"
  wait_for_exit "$contender_pid" 10 \
    || { kill_group "$contender_pid"; fail "contender failed after cleanup ownership loss"; }
  [[ ! -e "$sandbox/device-state/overlap" ]] \
    || fail "cleanup ownership loss allowed overlapping mutation"
  wait_for_serial_flock_reclaim "$sandbox/locks/avd-lock-emulator-5554" "$cleanup_pid" 3 \
    || fail "cleanup ownership loss left a stale flock"
}

holder_loss_at_cleanup_boundary_fails_before_uninstall() {
  assert_holder_loss_at_cleanup_boundary_fails_closed "$1" cleanup 0
}

holder_loss_at_cleanup_boundary_escalates_term_ignoring_uninstall() {
  assert_holder_loss_at_cleanup_boundary_fails_closed "$1" cleanupignore 1
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
  # the wrapper's FD. The kernel flock must be reclaimable after any already-
  # forked ownership probe in this invocation drains.
  kill -KILL "$wrapper_pid"
  wait "$wrapper_pid" 2>/dev/null || true
  local live_descendant=0
  for pid in "${descendants[@]}"; do
    kill -0 "$pid" 2>/dev/null && live_descendant=1
  done
  (( live_descendant == 1 )) \
    || fail "hard-crash proof lost all descendants instead of exercising orphan FD behavior"
  if ! wait_for_serial_flock_reclaim "$serial_lock" "$wrapper_pid" 3; then
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

controlled_inflight_probe_does_not_masquerade_as_inherited_flock() {
  local sandbox="$1"
  make_sandbox "$sandbox"
  make_fake_flock "$sandbox"

  local wrapper_pid
  FAKE_PAUSE_BEFORE_MUTATION_RUN_ID=probecrash \
    FAKE_PAUSE_FLOCK_PROBE_RUN_ID=probecrash \
    start_wrapper "$sandbox" pool probecrash i2085probecrash
  wrapper_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/probecrash.pre-mutation-paused" 10 \
    || { kill_group "$wrapper_pid"; fail "controlled probe case never reached the pre-mutation pause"; }
  wait_for_file "$sandbox/device-state/probecrash.flock-probe-ready" 10 \
    || { kill_group "$wrapper_pid"; fail "controlled probe case never exposed the in-flight ownership probe"; }

  local probe_pid probe_pgid serial_lock
  probe_pid="$(<"$sandbox/device-state/probecrash.flock-probe-pid")"
  probe_pgid="$(ps -o pgid= -p "$probe_pid" | tr -d ' ')"
  serial_lock="$sandbox/locks/avd-lock-emulator-5554"
  [[ "$probe_pgid" == "$wrapper_pid" ]] \
    || fail "controlled ownership probe escaped its invocation process group (wrapper_pgid=$wrapper_pid probe_pid=$probe_pid probe_pgid=$probe_pgid)"

  # Kill only the authoritative wrapper, exactly like the historical #1737
  # regression. The already-forked ownership probe is a known descendant: once
  # the wrapper releases its continuous FD, the probe legitimately acquires the
  # same per-invocation lock for a few scheduler turns. A one-shot reclaim oracle
  # misclassifies that short-lived probe as an inherited continuous FD.
  kill -KILL "$wrapper_pid"
  wait "$wrapper_pid" 2>/dev/null || true
  touch "$sandbox/device-state/probecrash.flock-probe-continue"
  wait_for_file "$sandbox/device-state/probecrash.flock-probe-acquired" 10 \
    || fail "controlled ownership probe did not acquire the just-released serial flock"
  local lane_init_files=()
  mapfile -t lane_init_files < <(
    find "$sandbox/tmp" -maxdepth 1 -type f \
      -name 'pocketshell-lane-init-i2085probecrash.*.gradle' -print
  )
  (( ${#lane_init_files[@]} == 1 )) \
    || fail "controlled hard-killed wrapper did not contain its lane-init artifact in the invocation sandbox (found ${#lane_init_files[@]})"
  local holder_pid
  holder_pid="$(<"$sandbox/device-state/probecrash.flock-probe-holder-pid")"
  printf 'CONTROLLED_FLOCK_CONFLICT resource=%s descendant_pid=%s holder_pid=%s process_group=%s\n' \
    "$serial_lock" "$probe_pid" "$holder_pid" "$wrapper_pid" >&2

  ! "$REAL_FLOCK" -n "$serial_lock" true \
    || fail "controlled ownership probe did not hold the exact serial resource"

  touch "$sandbox/device-state/probecrash.flock-probe-release"
  wait_for_serial_flock_reclaim "$serial_lock" "$wrapper_pid" 3 \
    || fail "in-flight ownership probe did not release its per-invocation serial flock"
  wait_for_file "$sandbox/device-state/probecrash.flock-probe-released" 3 \
    || fail "controlled ownership probe did not report its release"
  [[ ! -e "$sandbox/device-state/probecrash.started" ]] \
    || fail "controlled hard-killed wrapper mutated before ownership reclamation"
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
  wait_for_serial_flock_reclaim "$serial_lock" "$wrapper_pid" 3 \
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
  wait_for_serial_flock_reclaim "$serial_lock" "$wrapper_pid" 3 \
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
      emulator-5554 "" emulator-5554 8 0 2222 \
      com.pocketshell.next.terminal.J05ReconnectAfterDropJourney
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
  wait_for_serial_flock_reclaim "$serial_lock" "$wrapper_pid" 3 \
    || fail "SIGKILLed network-fault wrapper left its serial flock in a descendant"
  [[ ! -e "$sandbox/device-state/toxcrash.started" ]] \
    || fail "network-fault wrapper mutated before the crash proof"

  kill_group "$wrapper_pid"
}

# Issue #2421 regression proof for the bounded-retry reclaim oracle itself.
# Retrying `flock -n` tolerates the microseconds a SIGKILLed wrapper's already-
# forked ownership probe needs to drain — it must NOT tolerate the defect those
# probes are mistaken for. A descendant that truly inherited the wrapper's
# continuous FD keeps the open file description alive, so the kernel never frees
# the lock and the oracle must still go red. Without this case, replacing the
# one-shot probes with a retry loop would be indistinguishable from deleting
# the assertion.
retained_descendant_flock_fails_the_reclaim_oracle() {
  local sandbox="$1"
  make_sandbox "$sandbox"
  inject_inherited_lock_fd_leak "$sandbox/pool-root"

  local wrapper_pid
  FAKE_PAUSE_BEFORE_MUTATION_RUN_ID=leakcrash \
    start_wrapper "$sandbox" pool leakcrash i2421leak
  wrapper_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/leakcrash.pre-mutation-paused" 10 \
    || { kill_group "$wrapper_pid"; fail "inherited-FD case never reached the pre-mutation pause"; }

  local serial_lock="$sandbox/locks/avd-lock-emulator-5554"
  local descendants=()
  mapfile -t descendants < <(descendant_pids "$wrapper_pid")
  (( ${#descendants[@]} >= 2 )) \
    || fail "inherited-FD case did not expose the async shell and mutation child"

  # The mutation must be LIVE. A no-op mutation would leave this case asserting
  # nothing about the oracle while still exiting 0 — the mutation-testing
  # failure mode this repo rejects.
  local pid fd target leaked_pid=""
  for pid in "${descendants[@]}"; do
    for fd in "/proc/$pid/fd/"*; do
      [[ -e "$fd" ]] || continue
      target="$(readlink -f "$fd" 2>/dev/null || true)"
      [[ "$target" == "$serial_lock" ]] || continue
      leaked_pid="$pid"
    done
  done
  [[ -n "$leaked_pid" ]] \
    || fail "inherited-FD mutation is not live: no descendant holds the continuous serial flock FD, so this case cannot exercise the reclaim oracle"

  kill -KILL "$wrapper_pid"
  wait "$wrapper_pid" 2>/dev/null || true
  kill -0 "$leaked_pid" 2>/dev/null \
    || fail "the FD-inheriting descendant $leaked_pid died with the wrapper instead of retaining the lock"

  local reclaim_err="$sandbox/leakcrash.reclaim-err"
  if wait_for_serial_flock_reclaim "$serial_lock" "$wrapper_pid" 1 2>"$reclaim_err"; then
    fail "reclaim oracle PASSED while descendant $leaked_pid still held the inherited serial flock -- the bounded retry masks a real #1737 leak"
  fi
  grep -q "lock-fd: resource=$serial_lock process_group=$wrapper_pid pid=$leaked_pid " \
    "$reclaim_err" \
    || { cat "$reclaim_err" >&2; fail "reclaim failure did not diagnose the FD-inheriting descendant $leaked_pid"; }

  # ...and the oracle is live rather than permanently red: the same probe
  # succeeds once the leaking tree is gone.
  kill_group "$wrapper_pid" \
    || fail "inherited-FD case could not tear down its invocation process group"
  wait_for_serial_flock_reclaim "$serial_lock" "$wrapper_pid" 3 \
    || fail "serial flock stayed held after the FD-inheriting process group was torn down"
}

term_after_lock_records_lifecycle_and_preserves_rc143() {
  local sandbox="$1"
  make_sandbox "$sandbox"

  local wrapper_pid wrapper_rc=0 lifecycle_log child_pid
  local controller_started_utc controller_before_signal_utc controller_after_exit_utc
  local wrapper_timezone="${ISSUE2004_WRAPPER_TZ:-Europe/Berlin}"
  controller_started_utc="$(date -u +%s)"
  FAKE_WRAPPER_TZ="$wrapper_timezone" start_wrapper "$sandbox" legacy termdiag i2004 \
    emulator-5554 "" emulator-5554 8 0 2222 SuperSecretRunnerArgument2004
  wrapper_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/termdiag.started" 10 \
    || { kill_group "$wrapper_pid"; fail "TERM provenance run never reached its locked mutation window"; }

  controller_before_signal_utc="$(date -u +%s)"
  kill -TERM "$wrapper_pid"
  wait "$wrapper_pid" || wrapper_rc=$?
  controller_after_exit_utc="$(date -u +%s)"

  lifecycle_log="$(find "$sandbox/lifecycle" -maxdepth 1 -type f -name '*.log' -print -quit 2>/dev/null || true)"
  if [[ "$wrapper_rc" != "143" ]]; then
    printf '%s\n' '--- wrapper stderr ---' >&2
    sed -n '1,240p' "$sandbox/termdiag.err" >&2 || true
    printf '%s\n' '--- lifecycle log ---' >&2
    [[ -n "$lifecycle_log" ]] && sed -n '1,240p' "$lifecycle_log" >&2
    fail "externally TERM-terminated wrapper changed hard-failure semantics (rc=$wrapper_rc, want 143)"
  fi
  [[ -n "$lifecycle_log" ]] \
    || fail "TERM after AVD lock acquisition left no persistent wrapper lifecycle/provenance log"
  grep -Eq 'event=avd_lock_acquired .*wrapper_pid=[0-9]+ .*lock_file=.*avd-lock-emulator-5554 .*lock_fd=[0-9]+' "$lifecycle_log" \
    || fail "lifecycle log does not identify the wrapper and acquired AVD lock/FD"
  grep -Eq 'event=avd_lock_acquired .*lock_owner_pid=[0-9]+ .*lock_holder_pid=[0-9]+ .*android_serial=emulator-5554' "$lifecycle_log" \
    || fail "lifecycle log does not identify lock owner/helper and selected serial"
  grep -Eq 'event=mutation_started .*child_pid=[0-9]+' "$lifecycle_log" \
    || fail "lifecycle log does not identify the active scoped child"
  child_pid="$(sed -n 's/.*event=mutation_started .*child_pid=\([0-9][0-9]*\).*/\1/p' "$lifecycle_log" | tail -1)"
  [[ -n "$child_pid" ]] || fail "could not recover child PID from lifecycle log"
  grep -Eq "event=signal_received .*signal=TERM .*child_pid=$child_pid .*child_state=(running|exited)" "$lifecycle_log" \
    || fail "lifecycle log does not capture the TERM boundary and active child PID"
  grep -Eq "event=signal_forwarded .*signal=TERM .*child_pid=$child_pid .*forward_result=(sent|already_exited) .*child_state_before=(running|exited)" "$lifecycle_log" \
    || fail "lifecycle log does not capture TERM forwarding outcome"
  grep -Eq "event=mutation_finished .*child_pid=$child_pid .*child_rc=143" "$lifecycle_log" \
    || fail "lifecycle log does not capture the TERM-terminated child's final status"
  grep -Eq 'event=wrapper_exit .*rc=143 .*received_wrapper_signal_rc=143' "$lifecycle_log" \
    || fail "lifecycle log does not preserve the wrapper's final rc143"
  grep -Eq 'sender_pid=unavailable' "$lifecycle_log" \
    || fail "diagnostics must state that bash traps cannot recover the external signal sender PID"
  if grep -Eq 'sender_pid=[0-9]+' "$lifecycle_log"; then
    fail "diagnostics invented a sender PID unavailable to a bash signal trap"
  fi
  if grep -Fq 'SuperSecretRunnerArgument2004' "$lifecycle_log"; then
    fail "lifecycle diagnostics exposed a Gradle/runner argument instead of PID-only metadata"
  fi
  if grep -Fq 'SuperSecretEnvironmentValue2004' "$lifecycle_log"; then
    fail "lifecycle diagnostics exposed an environment value instead of PID-only metadata"
  fi
  grep -Eq 'event=process_snapshot .*pid=[0-9]+ .*ppid=[0-9]+ .*pgid=[0-9]+ .*sid=[0-9]+ .*comm=' "$lifecycle_log" \
    || fail "lifecycle log lacks the non-secret PID/PPID/session process snapshot"
  grep -Eq 'event=cgroup_snapshot .*pid=[0-9]+ .*cgroup=' "$lifecycle_log" \
    || fail "lifecycle log lacks best-effort cgroup state"
  grep -Eq 'event=scope_snapshot .*scope=pocketshell-test-.* .*state=' "$lifecycle_log" \
    || fail "lifecycle log lacks best-effort transient-scope state"
  awk 'NF && $1 !~ /^timestamp=[0-9]{4}-[0-9]{2}-[0-9]{2}T/ { exit 1 }' "$lifecycle_log" \
    || fail "a lifecycle record has no UTC timestamp"

  # Load-bearing UTC proof: the wrapper is deliberately in Europe/Berlin while
  # this harness records ordinary UTC boundaries. Every trap-time and ordinary
  # record must still correlate with that controller window, and the mixed
  # record stream must remain monotonic. A local-time value with a literal Z is
  # two hours in the future here and fails this assertion.
  local mutation_started_utc signal_received_utc signal_forwarded_utc
  local mutation_finished_utc wrapper_exit_utc
  mutation_started_utc="$(lifecycle_event_epoch "$lifecycle_log" mutation_started)"
  signal_received_utc="$(lifecycle_event_epoch "$lifecycle_log" signal_received)"
  signal_forwarded_utc="$(lifecycle_event_epoch "$lifecycle_log" signal_forwarded)"
  mutation_finished_utc="$(lifecycle_event_epoch "$lifecycle_log" mutation_finished)"
  wrapper_exit_utc="$(lifecycle_event_epoch "$lifecycle_log" wrapper_exit)"
  if (( mutation_started_utc < controller_started_utc \
        || wrapper_exit_utc > controller_after_exit_utc \
        || signal_received_utc < controller_before_signal_utc \
        || signal_received_utc > controller_after_exit_utc \
        || signal_forwarded_utc < controller_before_signal_utc \
        || signal_forwarded_utc > controller_after_exit_utc )); then
    fail "mixed lifecycle records do not correlate with the controller's real UTC window (controller=$controller_started_utc..$controller_after_exit_utc signal_boundary=$controller_before_signal_utc events=$mutation_started_utc,$signal_received_utc,$signal_forwarded_utc,$mutation_finished_utc,$wrapper_exit_utc TZ=$wrapper_timezone)"
  fi
  if (( mutation_started_utc > signal_received_utc \
        || signal_received_utc > signal_forwarded_utc \
        || signal_forwarded_utc > mutation_finished_utc \
        || mutation_finished_utc > wrapper_exit_utc )); then
    fail "mixed ordinary/trap lifecycle timestamps are not monotonic UTC (events=$mutation_started_utc,$signal_received_utc,$signal_forwarded_utc,$mutation_finished_utc,$wrapper_exit_utc TZ=$wrapper_timezone)"
  fi
  grep -Eq "CONNECTED_TEST_SIGNAL_RECEIVED signal=TERM wrapper_pid=[0-9]+ child_pid=$child_pid child_state=(running|exited) sender_pid=unavailable lifecycle_log=" "$sandbox/termdiag.err" \
    || fail "caller stderr does not retain the received-signal boundary when the wrapper dies"
  grep -Eq "CONNECTED_TEST_SIGNAL_FORWARDED signal=TERM child_pid=$child_pid result=(sent|already_exited)" "$sandbox/termdiag.err" \
    || fail "caller stderr does not retain the forwarded-signal outcome"
  grep -Eq 'CONNECTED_TEST_SIGNAL_EXIT rc=143 lifecycle_log=' "$sandbox/termdiag.err" \
    || fail "caller stderr does not retain final rc143 and the persistent-log location"
  if grep -q 'CONNECTED_TEST_CHILD_SIGNAL_EXIT' "$sandbox/termdiag.err"; then
    fail "wrapper-owned TERM was mislabeled as an unattributed child-only signal exit"
  fi

  # Adjacent attribution control: the same rc143 originating in the child (no
  # TERM delivered to the wrapper) must not claim a wrapper signal boundary.
  local child_only_pid child_only_rc=0 child_only_log
  start_wrapper "$sandbox" legacy childterm i2004child \
    emulator-5554 "" emulator-5554 8 143
  child_only_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/childterm.started" 10 \
    || { kill_group "$child_only_pid"; fail "child-only rc143 attribution control never started"; }
  touch "$sandbox/device-state/childterm.release"
  wait "$child_only_pid" || child_only_rc=$?
  [[ "$child_only_rc" == "143" ]] \
    || fail "child-only rc143 attribution control changed result (rc=$child_only_rc)"
  child_only_log="$(find "$sandbox/lifecycle" -maxdepth 1 -type f -name '*i2004child*.log' -print -quit 2>/dev/null || true)"
  [[ -n "$child_only_log" ]] || fail "child-only rc143 control left no lifecycle log"
  grep -Eq 'event=wrapper_exit .*rc=143 .*received_wrapper_signal_rc=0' "$child_only_log" \
    || fail "child-only rc143 was not distinguished from a wrapper-received signal"
  if grep -q 'event=signal_received' "$child_only_log"; then
    fail "child-only rc143 invented a wrapper signal-receipt event"
  fi
  grep -Eq 'CONNECTED_TEST_CHILD_SIGNAL_EXIT rc=143 received_wrapper_signal=none lifecycle_log=' "$sandbox/childterm.err" \
    || fail "caller stderr does not distinguish child rc143 from wrapper TERM"
  if grep -q 'CONNECTED_TEST_SIGNAL_RECEIVED' "$sandbox/childterm.err"; then
    fail "child-only rc143 stderr invented an external wrapper TERM"
  fi
}

# Issue #1662 / G5: a green Gradle process is not a green journey when the
# instrumentation runner's authoritative XML is red. This mutation keeps the
# wrapper child at rc=0 while publishing failures=1; connected-test.sh must
# reject the run from XML rather than laundering it through the wrapper status.
authoritative_red_xml_fails_closed() {
  local sandbox="$1" rc=0
  make_sandbox "$sandbox"
  FAKE_GRADLE_XML_RED_RUN_ID=redxml \
    start_wrapper "$sandbox" legacy redxml i1662red \
      emulator-5554 "" emulator-5554 8 0
  local wrapper_pid="$WRAPPER_PID"
  wait_for_file "$sandbox/device-state/redxml.started" 10 \
    || { kill_group "$wrapper_pid"; fail "red XML mutation never reached fake Gradle"; }
  touch "$sandbox/device-state/redxml.release"
  wait "$wrapper_pid" || rc=$?
  [[ "$rc" == "1" ]] \
    || fail "Gradle-zero/red-XML mutation was not rejected (wrapper rc=$rc)"
  grep -q 'CONNECTED_TEST_XML_FAILED: authoritative JUnit XML is red' \
    "$sandbox/redxml.err" \
    || fail "red XML mutation emitted no fail-closed verdict"
  ! grep -q 'CONNECTED_TEST_XML_RESULT' "$sandbox/redxml.err" \
    || fail "red XML mutation was also classified as green"
}

run_case() {
  local name="$1" sandbox
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-serial-owner.XXXXXX")"
  ACTIVE_CASE_SANDBOX="$sandbox"
  ACTIVE_PROCESS_GROUPS=()
  "$name" "$sandbox"
  [[ ! -e "$sandbox/device-state/unexpected-systemd-run" ]] \
    || fail "$name escaped its sandbox through the host user systemd manager"
  cleanup_active_case \
    || fail "$name left a live process in an invocation-owned process group"
  [[ ! -e "$sandbox" ]] \
    || fail "$name left its invocation sandbox behind"
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
  holder_loss_at_cleanup_boundary_escalates_term_ignoring_uninstall
  hard_killed_wrapper_leaves_no_descendant_flock
  controlled_inflight_probe_does_not_masquerade_as_inherited_flock
  authoritative_red_xml_fails_closed
  hard_killed_pool_setup_leaves_no_descendant_flock
  hard_killed_agents_docker_up_leaves_no_descendant_flock
  hard_killed_agents_docker_health_leaves_no_descendant_flock
  hard_killed_toxiproxy_holder_leaves_no_descendant_flock
  retained_descendant_flock_fails_the_reclaim_oracle
  term_after_lock_records_lifecycle_and_preserves_rc143
)
# Issue #2113: the full-suite size, hardcoded so that DELETING an entry from the
# CASES array above reddens this harness on its own — comparing the loop counter
# with `${#CASES[@]}` would only ever compare the loop with itself.
EXPECTED_FULL_CASES=19
FILTERED=0
if [[ $# -gt 0 ]]; then
  CASES=("$@")
  FILTERED=1
fi
(( FILTERED == 1 || ${#CASES[@]} == EXPECTED_FULL_CASES )) ||
  fail "expected $EXPECTED_FULL_CASES cases in the full suite, the array declares ${#CASES[@]}"

CASE_COUNT=0
for case_name in "${CASES[@]}"; do
  run_case "$case_name"
  CASE_COUNT=$((CASE_COUNT + 1))
done

# Issue #2113: a harness that exits 0 having run nothing is the vacuous green
# process.md catalogues. The count line is what makes the JVM assertion about
# behaviour rather than about bash's exit status. This harness accepts a case
# filter, so the count is the number ACTUALLY run and each JVM caller pins the
# count for the argument set it passes.
(( CASE_COUNT == ${#CASES[@]} && CASE_COUNT > 0 )) ||
  fail "expected ${#CASES[@]} cases to run, saw $CASE_COUNT"
printf 'PASS: connected-test per-serial ownership (issue #1737) (%s cases)\n' "$CASE_COUNT"
