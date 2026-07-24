#!/usr/bin/env bash
set -euo pipefail

# Issue #672 (option 1 + 2): lock-wrapped ad-hoc connected-test runner.
#
# Ad-hoc `./gradlew :app:connectedDebugAndroidTest` runs fired by implementers
# and reviewers never sourced scripts/lib/avd-lock.sh, so parallel agents
# SIGKILLed each other on the shared AVD. This thin wrapper:
#
#   1. Resolves one emulator serial and acquires that serial's common ownership
#      lock (option 1) so pool and non-pool wrappers never race on one AVD.
#      Distinct emulators retain independent locks and run concurrently.
#   2. Threads the per-worktree applicationIdSuffix (option 2) into the gradle
#      invocation so each worktree's DEBUG apk installs under a distinct
#      applicationId (e.g. com.pocketshell.app.i672) and multiple test apps
#      coexist on ONE emulator without uninstalling each other.
#
# Usage:
#   scripts/connected-test.sh [--suffix <token>] [--module <gradle-module>] \
#     [--pool|--no-pool] [gradle args...]
#   POCKETSHELL_APP_ID_SUFFIX=i672 scripts/connected-test.sh \
#     -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.SomeTest
#
#   # Run a shared:* module's androidTest under the SAME lock + suffix machinery
#   # (issue #798) — the #796 proof CodexOutputBurstImeMainThreadProofTest lives
#   # in shared/core-terminal/src/androidTest/:
#   scripts/connected-test.sh --module shared:core-terminal --suffix i798 \
#     -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.terminal.core.CodexOutputBurstImeMainThreadProofTest
#
#   scripts/connected-test.sh --cleanup-suffixes   # uninstall leftover
#                                                   # com.pocketshell.app.i* apps
#
# Contention hardening (issue #776): even WITHOUT --pool, when more than one
# emulator is online the wrapper now (P1) claims + pins a single FREE serial so
# AGP can't fan the install onto every device (sibling SIGKILL), and (P0) sweeps
# a leftover base com.pocketshell.app[.test] off the pinned device before
# installing a suffixed APK so it can't hijack the suffixed MainActivity launch.
# Journey/E2e/Docker classes auto-default to --pool when >1 emulator is online
# (P2); pass --no-pool to opt out.
#
# Flags:
#   --suffix <token>     Per-worktree applicationIdSuffix token (e.g. i672).
#                        Overrides POCKETSHELL_APP_ID_SUFFIX. Token must match
#                        [A-Za-z0-9._]+.  Default empty -> base package, so the
#                        wrapper is identical to a plain connectedDebugAndroidTest
#                        when no suffix is given.
#   --module <module>    Gradle module whose connectedDebugAndroidTest task to run
#                        (issue #798). Accepts either Gradle path syntax
#                        (shared:core-terminal) or a leading-colon path
#                        (:shared:core-terminal). The wrapper appends
#                        :connectedDebugAndroidTest itself, so a shared:* module's
#                        androidTest runs under the SAME AVD flock +
#                        -PpocketshellAppIdSuffix coexistence as the app-module
#                        default. Default empty -> :app:connectedDebugAndroidTest
#                        (byte-for-byte the legacy behaviour). Pass the module path
#                        WITHOUT a trailing :connectedDebugAndroidTest; that task
#                        name is fixed because the suffix/lock plumbing assumes a
#                        connectedDebugAndroidTest target.
#   --pool               Lane pool mode (issues #674 + #724): claim a full
#                        ISOLATED lane = (a free emulator serial + a free agents
#                        fixture port). It (1) claims the first FREE emulator
#                        from the live pool (per-serial flock), exports
#                        ANDROID_SERIAL so AGP/adb pin to it; and (2) claims the
#                        first FREE agents fixture port (per-port flock), brings
#                        that agents container up healthy if needed, and threads
#                        it into gradle as
#                        -Pandroid.testInstrumentationRunnerArguments.agentsPort=<port>
#                        so the androidTest suite targets THIS lane's own
#                        SSH/tmux fixture (no cross-talk). Both the serial and
#                        the port are released on exit. Without --pool the agents
#                        port stays at 2222, but the wrapper still resolves and
#                        owns exactly one emulator serial before mutation.
#                        Pool emulators are booted via scripts/avd-pool.sh; the
#                        agents fixture pool is scripts/agents-pool.sh. When no
#                        emulator serial is claimable as a SECOND lane (single
#                        AVD, e.g. CI), --pool falls back cleanly to that one
#                        emulator + port 2222.
#                        WARNING: the toxiproxy network-fault proxies
#                        (NetworkFaultProofBase: hardcoded 10.0.2.2:2228 / API
#                        8474, single shared proxy) are NOT pool-isolated, so two
#                        network-fault lanes corrupt each other's toxics. The
#                        wrapper warns + serializes network-fault classes onto a
#                        single shared lock even under --pool (issue #776 P3).
#   --no-pool            Force the legacy single-lane path even for a journey/E2e
#                        class that would otherwise auto-default to --pool (P2).
#                        Still honours the P0/P1 base-sweep + serial-pin hygiene.
#   --cleanup-suffixes   Uninstall every accumulated com.pocketshell.app.i*
#                        (and .test) package from the target device, then exit.
#                        Prevents install pile-up across worktrees.
#
# Everything after the recognised flags is forwarded verbatim to gradle's
# connectedDebugAndroidTest task (e.g. instrumentation-runner-argument filters).
# The task defaults to :app:connectedDebugAndroidTest and is overridable per
# --module (issue #798). The base package (no suffix) and release build are
# never touched.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"
# Issue #724: agents-fixture pool helpers (Docker half of parallel journey
# testing). Sourcing this makes pocketshell_release_all also release the claimed
# agents port on exit, and provides pocketshell_claim_agents_port below.
source "$ROOT_DIR/scripts/lib/agents-pool.sh"
# Issue #730: per-invocation transient systemd --user scope so the heavy gradle
# connected-test build is accounted in a SIBLING cgroup under robust.slice
# (default MemoryMax 8G < the 12 GiB per-session cap), not in the calling tmux
# session's cgroup. A runaway then OOMs only its own scope; the parent session
# survives. Degrades to a bare invocation when user systemd is unavailable (CI).
source "$ROOT_DIR/scripts/lib/scope-run.sh"

# Retain the selected serial's flock in this wrapper for the complete package,
# install, and instrumentation window. Mutating children explicitly close their
# inherited copy; the wrapper remains the continuous owner (issue #1737).
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"

print_usage() {
  cat >&2 <<'USAGE'
Lock-wrapped ad-hoc connected-test runner (issues #672/#724/#798).

Usage:
  scripts/connected-test.sh [--suffix <token>] [--module <gradle-module>] \
    [--pool|--no-pool] [gradle args...]
  scripts/connected-test.sh --cleanup-suffixes
  scripts/connected-test.sh --help

Flags:
  --suffix <token>     Per-worktree applicationIdSuffix token (e.g. i672).
                       Overrides POCKETSHELL_APP_ID_SUFFIX. Token must match
                       [A-Za-z0-9._]+. Default empty -> base package.
  --module <module>    Gradle module whose connectedDebugAndroidTest task to run
                       (issue #798). Accepts shared:core-terminal or
                       :shared:core-terminal; the wrapper appends
                       :connectedDebugAndroidTest itself so a shared:* module's
                       androidTest runs under the SAME AVD flock +
                       -PpocketshellAppIdSuffix coexistence as the app default.
                       Default empty -> :app:connectedDebugAndroidTest (unchanged).
  --pool               Lane pool mode: claim an isolated (emulator, agents-port)
                       lane for parallel journey testing (issues #674 + #724).
  --no-pool            Force the legacy single-lane path.
  --cleanup-suffixes   Uninstall every accumulated com.pocketshell.app.i* package
                       from the target device, then exit.
  --help, -h           Print this help and exit.

Examples:
  # App-module proof (default task :app:connectedDebugAndroidTest):
  scripts/connected-test.sh --suffix i672 \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.SomeTest

  # shared:* module proof (task :shared:core-terminal:connectedDebugAndroidTest):
  scripts/connected-test.sh --module shared:core-terminal --suffix i798 \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.terminal.core.CodexOutputBurstImeMainThreadProofTest

Everything after the recognised flags is forwarded verbatim to gradle's
connectedDebugAndroidTest task. The base package (no suffix) and release build
are never touched.
USAGE
}

SUFFIX="${POCKETSHELL_APP_ID_SUFFIX:-}"
# Gradle module whose connectedDebugAndroidTest task to run (issue #798). Empty
# -> the :app default below. Set via --module.
MODULE=""
CLEANUP_ONLY=0
USE_POOL=0
# 0 = unset (auto-decide), 1 = caller forced --pool, -1 = caller forced --no-pool.
POOL_FLAG=0
GRADLE_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      print_usage
      exit 0
      ;;
    --suffix)
      [[ $# -ge 2 ]] || { printf 'FAIL: --suffix needs a value\n' >&2; exit 2; }
      SUFFIX="$2"
      shift 2
      ;;
    --suffix=*)
      SUFFIX="${1#--suffix=}"
      shift
      ;;
    --module)
      [[ $# -ge 2 ]] || { printf 'FAIL: --module needs a value\n' >&2; exit 2; }
      MODULE="$2"
      shift 2
      ;;
    --module=*)
      MODULE="${1#--module=}"
      shift
      ;;
    --pool)
      USE_POOL=1
      POOL_FLAG=1
      shift
      ;;
    --no-pool)
      USE_POOL=0
      POOL_FLAG=-1
      shift
      ;;
    --cleanup-suffixes)
      CLEANUP_ONLY=1
      shift
      ;;
    --)
      shift
      GRADLE_ARGS+=("$@")
      break
      ;;
    *)
      GRADLE_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ -n "$SUFFIX" ]]; then
  [[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || {
    printf 'FAIL: suffix must match [A-Za-z0-9._]+ (got: %s)\n' "$SUFFIX" >&2
    exit 2
  }
fi

# Resolve the gradle connectedDebugAndroidTest task (issue #798). Default is the
# app module; --module redirects to a shared:* (or any) module's task while the
# rest of the wrapper — the AVD flock, the serial pin, and -PpocketshellAppIdSuffix
# — applies unchanged. We OWN the :connectedDebugAndroidTest task name: the suffix
# + lock plumbing assumes that exact task, so the caller passes only the module
# path and we append the task. Normalise both `shared:core-terminal` and
# `:shared:core-terminal`, and tolerate a redundant trailing
# `:connectedDebugAndroidTest`.
CONNECTED_TASK=":app:connectedDebugAndroidTest"
if [[ -n "$MODULE" ]]; then
  module_path="$MODULE"
  # Strip a leading colon so we can re-add exactly one.
  module_path="${module_path#:}"
  # Strip any trailing colons (e.g. `shared:core-terminal:`).
  while [[ "$module_path" == *: ]]; do module_path="${module_path%:}"; done
  # Strip a redundant trailing task suffix if the caller included it, then any
  # trailing colons it leaves behind (`shared:core-terminal:connectedDebugAndroidTest:`).
  module_path="${module_path%:connectedDebugAndroidTest}"
  while [[ "$module_path" == *: ]]; do module_path="${module_path%:}"; done
  if [[ -z "$module_path" ]]; then
    printf 'FAIL: --module needs a gradle module path (e.g. shared:core-terminal)\n' >&2
    exit 2
  fi
  # Gradle module paths are colon-separated segments of [A-Za-z0-9._-]; reject
  # anything else so a typo can't smuggle in an arbitrary task or shell content.
  if [[ ! "$module_path" =~ ^[A-Za-z0-9._-]+(:[A-Za-z0-9._-]+)*$ ]]; then
    printf 'FAIL: invalid --module path (got: %s). Expected e.g. shared:core-terminal\n' "$MODULE" >&2
    exit 2
  fi
  CONNECTED_TASK=":${module_path}:connectedDebugAndroidTest"
fi

# Count emulators currently online (issue #776). Used to decide whether to
# pin a serial / default to pool: with a single emulator (CI / single-AVD) the
# legacy behaviour is correct and must stay unchanged; only when MORE than one
# emulator is online (the avd-pool is up) do the multi-emulator footguns —
# AGP fanning the install onto every device, two lanes contending one AVD —
# actually bite.
online_emulator_count() {
  pocketshell_pool_online_serials 2>/dev/null | grep -c . || true
}

# P2 (issue #776) — default journey/E2e/Docker lanes to --pool when more than
# one emulator is online. The documented intent (agents-pool.sh) is that journey
# lanes self-allocate an isolated (emulator, agents-port) fixture; before this,
# --pool was opt-in and reviewers who forgot it took the single global lock and
# contended one AVD (the #1 "why did it still wedge" cause). We auto-enable pool
# mode ONLY when ALL of:
#   * the caller did not force it either way (POOL_FLAG == 0; --pool / --no-pool
#     are always honoured verbatim),
#   * the gradle args target a journey-style class (*E2eTest / *JourneyE2eTest /
#     *DockerTest / *ProofBase-derived suites named in the runner-args class
#     filter), AND
#   * more than one emulator is online (so single-AVD / CI is untouched).
# An explicit ANDROID_SERIAL is left as-is: a caller pinning a device knows what
# it wants, and the pool claim already honours a preset serial.
if [[ "$POOL_FLAG" == "0" && "$CLEANUP_ONLY" != "1" ]]; then
  gradle_args_str="${GRADLE_ARGS[*]:-}"
  if [[ "$gradle_args_str" == *E2eTest* \
        || "$gradle_args_str" == *JourneyE2eTest* \
        || "$gradle_args_str" == *DockerTest* \
        || "$gradle_args_str" == *Journey* ]]; then
    if (( "$(online_emulator_count)" > 1 )); then
      USE_POOL=1
      printf 'Auto-pool (issue #776): journey/E2e class + %s emulators online -> --pool. Pass --no-pool to opt out.\n' \
        "$(online_emulator_count)" >&2
    fi
  fi
fi

# P3 (issue #776) — network-fault classes share ONE global toxiproxy.
# NetworkFaultProofBase connects to a HARDCODED 10.0.2.2:2228 / API 8474 single
# shared proxy and its @After does toxiproxy().reset(); --pool does NOT isolate
# that (it only relocates the agents SSH port, not the fault proxy). So two
# network-fault lanes running concurrently reset/blackhole/latency EACH OTHER's
# connection mid-test -> a spurious `Broken transport: EOF` on the innocent lane.
# Until the per-lane toxiproxy plumbing lands (research P3, an M-sized Kotlin +
# compose change), detect a network-fault class here and SERIALIZE it: force
# every such run onto ONE shared lock file regardless of emulator, so at most one
# network-fault lane touches the singleton proxy at a time. Detection matches the
# known NetworkFaultProofBase-derived class names.
NETWORK_FAULT_RUN=0
gradle_args_str="${GRADLE_ARGS[*]:-}"
if [[ "$CLEANUP_ONLY" != "1" ]]; then
  case "$gradle_args_str" in
    *NetworkFault*|*NetworkLatencyModel*|*PacketLoss*|*DisconnectBlackhole*\
      |*DisconnectFlap*|*KeepAliveDeadPeer*|*RideThrough*|*WithinGrace*\
      |*StaleLeaseSwitchRecovery*|*CodexRedrawOverflowReconnect*)
      NETWORK_FAULT_RUN=1
      ;;
  esac
fi
if [[ "$NETWORK_FAULT_RUN" == "1" ]]; then
  # Flag the run for the dedicated, machine-wide toxiproxy serialization lock
  # acquired before serial selection (pocketshell_acquire_toxiproxy_lock). That lock
  # is SEPARATE from the per-serial AVD lock: two network-fault lanes on distinct
  # pool emulators each hold their own serial lock but must still not share the
  # singleton proxy, so they queue on this one shared lock.
  POCKETSHELL_TOXIPROXY_SERIALIZED=1
  printf 'Network-fault class detected (issue #776 P3): the toxiproxy proxy is a global singleton, so this run is SERIALIZED on a shared lock (no concurrent network-fault lanes).\n' >&2
fi

# Claim non-emulator shared infrastructure before the serial FD exists. This
# keeps the toxiproxy holder and all of its setup children outside the serial
# ownership tree by construction.
if [[ "${POCKETSHELL_TOXIPROXY_SERIALIZED:-0}" == "1" ]]; then
  if ! pocketshell_acquire_toxiproxy_lock "$ROOT_DIR"; then
    printf 'FAIL: could not acquire the toxiproxy serialization lock.\n' >&2
    exit 1
  fi
fi

# Common serial ownership (issues #674/#776/#1737): every wrapper mode that can
# mutate an emulator must resolve and own one serial before package cleanup,
# install, or instrumentation. The old one-emulator legacy path held the global
# `avd-lock` while --pool held `avd-lock-emulator-5554`; those unrelated domains
# let both processes mutate the same AVD. The existing free-serial allocator is
# now the common allocator for pool, legacy, and cleanup-only modes.
#
# A caller-preset ANDROID_SERIAL remains an explicit target and takes the same
# per-serial lock below. With no preset serial, zero online emulators fail
# immediately; one or more online emulators are selected only when their common
# per-serial lock is free. POCKETSHELL_POOL_WAIT_SECONDS retains the existing
# bounded wait/fail-fast contract.
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  online_count="$(online_emulator_count)"
  if (( online_count == 0 )); then
    printf 'FAIL: no online emulator is available; refusing package/install mutation without per-serial ownership.\n' >&2
    exit 1
  fi
  export ADB ANDROID_SDK
  if ! pocketshell_claim_pool_serial "$ROOT_DIR"; then
    printf 'FAIL: could not claim an emulator serial; all online serials are owned by other connected-test runs.\n' >&2
    exit 1
  fi
  if [[ "$USE_POOL" == "1" ]]; then
    printf 'Pool mode: running on %s\n' "${ANDROID_SERIAL:-?}" >&2
  else
    printf 'Pinned legacy lane to owned emulator (issue #1737): ANDROID_SERIAL=%s\n' \
      "${ANDROID_SERIAL:-?}" >&2
  fi
fi

# Pool mode (issue #724): claim a free agents fixture PORT to pair with the
# emulator lane, bring its container up healthy, and thread the port into the
# gradle instrumentation arg so the androidTest suite targets THIS lane's own
# SSH/tmux fixture. The claim's flock + container stay tied to this process and
# the port is released by the shared pocketshell_release_all EXIT trap. When the
# caller preset POCKETSHELL_AGENTS_PORT (explicit target) we honour it and skip
# the claim. This MUST run before gradle is invoked so the arg is available.
if [[ "$USE_POOL" == "1" && -z "${POCKETSHELL_AGENTS_PORT:-}" ]]; then
  if ! pocketshell_claim_agents_port "$ROOT_DIR"; then
    printf 'FAIL: could not claim/bring up a free agents fixture port.\n' >&2
    exit 1
  fi
  printf 'Pool mode: agents fixture on host port %s\n' "${POCKETSHELL_AGENTS_PORT:-?}" >&2
fi

resolved_serial_lock=""
if [[ -n "${ANDROID_SERIAL:-}" && -z "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" ]]; then
  # The caller-pinned path is resolved before its serial FD is opened.
  resolved_serial_lock="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
fi

# Every path has an explicit serial at this point. Pool/auto claims already hold
# this lock and mark it acquired; a preset serial acquires the identical file at
# the common acquire call below.
if [[ -n "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" ]]; then
  resolved_serial_lock="${POCKETSHELL_AVD_LOCK_FILE:-}"
fi
if [[ -n "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" \
      && -n "${POCKETSHELL_AVD_LOCK_FILE:-}" \
      && "$POCKETSHELL_AVD_LOCK_FILE" != "$resolved_serial_lock" ]]; then
  printf 'FAIL: inherited emulator lock %s does not own selected serial %s (%s); refusing mutation.\n' \
    "$POCKETSHELL_AVD_LOCK_FILE" "$ANDROID_SERIAL" "$resolved_serial_lock" >&2
  exit 1
fi
POCKETSHELL_AVD_LOCK_FILE="$resolved_serial_lock"
export POCKETSHELL_AVD_LOCK_FILE

MUTATION_PID=""
POCKETSHELL_AVD_OWNERSHIP_LOST=""

pocketshell_collect_descendant_pids_postorder() {
  local pid="$1"
  local children=""
  if [[ -r "/proc/$pid/task/$pid/children" ]]; then
    IFS= read -r children < "/proc/$pid/task/$pid/children" || true
  fi
  local child
  for child in $children; do
    pocketshell_collect_descendant_pids_postorder "$child"
    POCKETSHELL_DESCENDANT_PIDS+=("$child")
  done
}

pocketshell_process_is_running() {
  local pid="$1"
  [[ -r "/proc/$pid/stat" ]] || return 1
  local _pid _comm state
  read -r _pid _comm state _ < "/proc/$pid/stat" || return 1
  [[ "$state" != "Z" ]]
}

pocketshell_terminate_process_tree() {
  local root_pid="$1"
  POCKETSHELL_DESCENDANT_PIDS=()
  pocketshell_collect_descendant_pids_postorder "$root_pid"
  local victims=("${POCKETSHELL_DESCENDANT_PIDS[@]}")
  victims+=("$root_pid")

  local pid
  for pid in "${victims[@]}"; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  local attempt running
  for ((attempt = 0; attempt < 40; attempt++)); do
    running=0
    for pid in "${victims[@]}"; do
      pocketshell_process_is_running "$pid" && running=1
    done
    (( running == 0 )) && return 0
    pocketshell_run_without_avd_lock_fd sleep 0.05
  done

  # A wedged child or ignored TERM must not outlive ownership cleanup.
  for pid in "${victims[@]}"; do
    if pocketshell_process_is_running "$pid"; then
      kill -KILL "$pid" 2>/dev/null || true
    fi
  done
  for ((attempt = 0; attempt < 40; attempt++)); do
    running=0
    for pid in "${victims[@]}"; do
      pocketshell_process_is_running "$pid" && running=1
    done
    (( running == 0 )) && return 0
    pocketshell_run_without_avd_lock_fd sleep 0.05
  done
  return 1
}

# Run one device-mutating command with the child's flock copy closed while the
# wrapper retains its own FD. The sentinel is monitored concurrently. If it
# dies after a boundary assertion, the still-locked wrapper terminates the
# child, reports ownership loss, and releases only after mutation has stopped.
pocketshell_run_guarded_mutation() {
  pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE" || return 70

  pocketshell_start_without_avd_lock_fd "$@"
  local child_pid="$POCKETSHELL_AVD_CHILD_PID"
  MUTATION_PID="$child_pid"
  local ownership_lost=0
  while kill -0 "$child_pid" 2>/dev/null; do
    if ! pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"; then
      ownership_lost=1
      POCKETSHELL_AVD_OWNERSHIP_LOST=1
      if [[ -n "${SCOPE_UNIT:-}" ]]; then
        pocketshell_run_without_avd_lock_fd \
          systemctl --user kill --signal=TERM --kill-whom=all "$SCOPE_UNIT.scope" \
          >/dev/null 2>&1 || true
      fi
      pocketshell_terminate_process_tree "$child_pid"
      break
    fi
    pocketshell_run_without_avd_lock_fd sleep 0.05
  done

  local child_rc=0
  wait "$child_pid" || child_rc=$?
  MUTATION_PID=""
  if (( ownership_lost == 1 )); then
    return 70
  fi
  return "$child_rc"
}

cleanup_suffixed_packages() {
  # Optional first arg:
  #   --include-base   ALSO uninstall the base com.pocketshell.app[.test] and
  #                    every suffixed sibling that is NOT this run's $SUFFIX.
  #                    Used as a pre-run hygiene sweep on a suffixed lane (P0,
  #                    issue #776) so a leftover base install can't hijack the
  #                    suffixed MainActivity launch — both register identical
  #                    MAIN/LAUNCHER + VIEW pocketshell://import intent filters,
  #                    so with two packages installed a launcher/VIEW launch is
  #                    ambiguous and Android can resolve to the wrong app.
  local include_base=0
  if [[ "${1:-}" == "--include-base" ]]; then
    include_base=1
  fi
  local adb_target=("$ADB")
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_target=("$ADB" -s "$ANDROID_SERIAL")
  fi
  pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
  local package_file="${TMPDIR:-/tmp}/pocketshell-packages.$$.$RANDOM"
  : > "$package_file"
  local list_rc=0
  pocketshell_run_guarded_mutation "${adb_target[@]}" shell pm list packages \
    > "$package_file" || list_rc=$?
  if (( list_rc != 0 )); then
    pocketshell_run_without_avd_lock_fd rm -f "$package_file"
    if [[ -n "$POCKETSHELL_AVD_OWNERSHIP_LOST" ]]; then
      return 70
    fi
    return "$list_rc"
  fi
  local pkg removed=0 cleanup_rc=0
  # The default (no --include-base) match: the per-worktree convention
  # com.pocketshell.app.i<token> (and its .test sibling) ONLY. This deliberately
  # excludes:
  #   * the base package        com.pocketshell.app
  #   * the base test package   com.pocketshell.app.test
  # so the sweep can never nuke a normal (non-suffixed) install's test app.
  # Worktree suffixes follow the `i<N>` convention (e.g. i672), so the token
  # must start with `i`.
  #
  # --include-base broadens the match to the base package + every suffixed
  # sibling, but then EXCLUDES this run's own $SUFFIX package(s) so the sweep
  # never uninstalls the app it is about to test. It is only ever invoked when
  # both $SUFFIX is non-empty AND $ANDROID_SERIAL is pinned (see the pre-run
  # call site), so it can only touch the one targeted emulator and never a bare
  # non-suffixed manual install on some other device.
  local match_re='^com\.pocketshell\.app\.i[A-Za-z0-9._]*(\.test)?$'
  if [[ "$include_base" == "1" ]]; then
    match_re='^com\.pocketshell\.app(\.i[A-Za-z0-9._]*)?(\.test)?$'
  fi
  # Packages belonging to THIS run's suffix, which --include-base must skip.
  local self_pkg="" self_test_pkg=""
  if [[ -n "$SUFFIX" ]]; then
    self_pkg="com.pocketshell.app.$SUFFIX"
    self_test_pkg="com.pocketshell.app.$SUFFIX.test"
  fi
  while IFS= read -r pkg; do
    pkg="${pkg#package:}"
    [[ -n "$pkg" ]] || continue
    [[ "$pkg" =~ $match_re ]] || continue
    if [[ "$include_base" == "1" && ( "$pkg" == "$self_pkg" || "$pkg" == "$self_test_pkg" ) ]]; then
      continue
    fi
    # Re-check immediately before each destructive adb operation. If the holder
    # was killed after allocation, fail closed instead of uninstalling another
    # run's package without ownership (issue #1737).
    pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
    printf 'Uninstalling leftover package: %s\n' "$pkg" >&2
    local uninstall_rc=0
    pocketshell_run_guarded_mutation "${adb_target[@]}" uninstall "$pkg" \
      >/dev/null || uninstall_rc=$?
    if (( uninstall_rc != 0 )) && [[ -n "$POCKETSHELL_AVD_OWNERSHIP_LOST" ]]; then
      cleanup_rc=70
      break
    fi
    removed=$((removed + 1))
  done < "$package_file"
  pocketshell_run_without_avd_lock_fd rm -f "$package_file"
  if (( cleanup_rc != 0 )); then
    return "$cleanup_rc"
  fi
  if [[ "$include_base" == "1" ]]; then
    printf 'Pre-run sweep (incl. base) removed %s package(s).\n' "$removed" >&2
  else
    printf 'Cleanup sweep removed %s suffixed package(s).\n' "$removed" >&2
  fi
}

# Acquire the AVD lock for BOTH cleanup and test runs so the sweep cannot race
# a sibling's install/test on the same device.
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

if [[ "$CLEANUP_ONLY" == "1" ]]; then
  cleanup_suffixed_packages
  exit 0
fi

# P0 (issue #776) — pre-run hygiene sweep on a SUFFIXED, SERIAL-PINNED lane.
# Before installing this lane's suffixed APK, uninstall the base
# com.pocketshell.app[.test] AND any stale suffixed sibling that is NOT this
# run's $SUFFIX, on the pinned emulator. A leftover base install shares the
# IDENTICAL MAIN/LAUNCHER + VIEW pocketshell://import intent filters with every
# suffixed package, so with two installed a launcher/VIEW launch is ambiguous
# and can resolve to the wrong app — the "leftover base install hijacks the
# suffixed MainActivity" failure observed today. Gated on BOTH $SUFFIX non-empty
# AND $ANDROID_SERIAL pinned so the sweep can only ever touch the one targeted
# device and never a legitimate bare non-suffixed install elsewhere (the safety
# guard called out in the research's top risk). cleanup_suffixed_packages
# --include-base excludes this run's own $SUFFIX so it never removes the app
# under test.
if [[ -n "$SUFFIX" && -n "${ANDROID_SERIAL:-}" ]]; then
  printf 'Pre-run sweep (issue #776): clearing base + stale siblings on %s before install...\n' \
    "$ANDROID_SERIAL" >&2
  cleanup_suffixed_packages --include-base
fi

GRADLE_SUFFIX_ARGS=()
if [[ -n "$SUFFIX" ]]; then
  GRADLE_SUFFIX_ARGS+=("-PpocketshellAppIdSuffix=$SUFFIX")
  printf 'Running %s as com.pocketshell.app.%s\n' "$CONNECTED_TASK" "$SUFFIX" >&2
else
  printf 'Running %s as com.pocketshell.app (no suffix)\n' "$CONNECTED_TASK" >&2
fi

# Issue #724: thread the claimed (or caller-preset) agents fixture port into the
# androidTest suite so this run targets THIS lane's own SSH/tmux fixture. The
# AgentsFixtureTarget helper reads `agentsPort` and defaults to 2222, so a run
# WITHOUT a port set is byte-for-byte the legacy single-lane behaviour — we only
# pass the arg when a port was actually claimed/preset.
if [[ -n "${POCKETSHELL_AGENTS_PORT:-}" ]]; then
  GRADLE_SUFFIX_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.agentsPort=$POCKETSHELL_AGENTS_PORT")
  printf 'androidTest targets agents fixture host port %s (agentsPort arg)\n' \
    "$POCKETSHELL_AGENTS_PORT" >&2
fi

# Serial pinning (issue #674): AGP's connected device provider installs +
# instruments on EVERY connected device by default. When more than one emulator
# is online (the pool), that would cross-install onto siblings and break them.
# AGP honours the `ANDROID_SERIAL` environment variable for its DeviceProvider:
# when set, it filters to exactly that one device. We exported it via the pool
# claim (or it was preset by the caller); re-export defensively so the gradle
# subprocess inherits it, and surface which device this run is pinned to.
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  export ANDROID_SERIAL
  printf 'Pinned to single device via ANDROID_SERIAL=%s (AGP DeviceProvider filters to it)\n' \
    "$ANDROID_SERIAL" >&2
else
  printf 'No ANDROID_SERIAL set: AGP will target the only connected device (single-emulator path)\n' >&2
fi

# Per-lane build-directory isolation (issue #724). Two `--pool` lanes run in the
# SAME git checkout, so a plain concurrent build has BOTH writing the one
# `app/build/intermediates/...` tree and racing on directory deletion (one lane
# dies with "Unable to delete directory ...transformDebugAndroidTestClassesWithAsm").
# applicationIdSuffix isolates the INSTALLED app identity but NOT the on-disk
# build outputs. So in pool mode we relocate every project's build directory to a
# per-suffix path via a generated init script — keeping the lanes' build outputs
# fully disjoint. This is gated on --pool: the single-lane / CI path is untouched
# and keeps the default `app/build`.
GRADLE_INIT_ARGS=()
LANE_INIT_SCRIPT=""
if [[ "$USE_POOL" == "1" && -n "$SUFFIX" ]]; then
  LANE_INIT_SCRIPT="${TMPDIR:-/tmp}/pocketshell-lane-init-$SUFFIX.$$.$RANDOM.gradle"
  # Relocate each project's build dir to build/lane-<suffix> under the project
  # dir, so concurrent lanes never share intermediates. Nesting UNDER the
  # existing per-project `build/` keeps the lane outputs inside the already
  # gitignored build tree (/build, /app/build, /shared/*/build, */build/), so a
  # lane run never pollutes `git status` or risks being committed. allprojects
  # covers the root + every module the connected build touches.
  printf '%s\n' \
    'allprojects {' \
    "    layout.buildDirectory.set(file(\"\${projectDir}/build/lane-$SUFFIX\"))" \
    '}' > "$LANE_INIT_SCRIPT"
  GRADLE_INIT_ARGS+=("--init-script" "$LANE_INIT_SCRIPT")
  printf 'Pool lane build isolation: per-project build dir -> build/lane-%s\n' "$SUFFIX" >&2
  # Clean up the temp init script when this shell exits (the build dirs
  # themselves are left for artifact inspection; sweep with the suffix sweep).
  # Invoked indirectly by the EXIT trap below.
  # shellcheck disable=SC2317
  pocketshell_cleanup_lane_init() {
    pocketshell_run_without_avd_lock_fd rm -f "$LANE_INIT_SCRIPT" 2>/dev/null || true
  }
  trap 'pocketshell_cleanup_lane_init; pocketshell_release_all' EXIT
fi

# Issue #730: the heavy gradle build runs inside its OWN transient systemd
# --user scope (a SIBLING of the session's per-session scope under robust.slice,
# NOT nested), so a runaway OOMs only its own scope and the parent tmux session
# survives. The unit name is UNIQUE per invocation (suffix + serial + pid) so
# parallel lanes get distinct sibling scopes that never collide. When user
# systemd is unavailable (CI), pocketshell_scope_run runs gradle BARE — identical
# to the legacy behaviour. Cap is env-tunable via POCKETSHELL_TEST_MEM (default
# 8G < the 12 GiB session cap so several lanes coexist under robust.slice).
SCOPE_TOKEN="${SUFFIX:-base}"
SCOPE_SERIAL="${ANDROID_SERIAL:-noserial}"
SCOPE_UNIT="pocketshell-test-${SCOPE_TOKEN//[^A-Za-z0-9._-]/_}-${SCOPE_SERIAL//[^A-Za-z0-9._-]/_}-$$"

# Run gradle as a CHILD process, NOT via `exec`. `exec` would replace this shell
# and prevent bash from firing its EXIT trap (pocketshell_release_all), which
# orphans the backgrounded per-serial flock holder and keeps the pool claim held
# forever (issue #674: AC2 release-on-exit). Running gradle as a child lets the
# trap run on exit so the claimed pool serial + its flock holder are released.
#
# Forward SIGINT/SIGTERM to the child so a Ctrl-C still tears it down; the EXIT
# trap then fires on our way out and releases the claim too. The child here is
# the scope-run wrapper (systemd-run --scope), which propagates the signal to
# the scoped gradle process tree.
# Invoked indirectly via the INT/TERM traps below; shellcheck can't see that.
# shellcheck disable=SC2317
forward_signal() {
  local sig="$1"
  if [[ -n "$MUTATION_PID" ]]; then
    kill -s "$sig" "$MUTATION_PID" 2>/dev/null || true
  fi
}
trap 'forward_signal INT' INT
trap 'forward_signal TERM' TERM

# The wrapper retains the serial flock while scope-run/Gradle has its inherited
# copy closed. Helper death is monitored until the mutation process exits.
set +e
pocketshell_run_guarded_mutation pocketshell_scope_run "$SCOPE_UNIT" \
  ./gradlew --no-daemon "$CONNECTED_TASK" \
  "${GRADLE_INIT_ARGS[@]}" \
  "${GRADLE_SUFFIX_ARGS[@]}" \
  "${GRADLE_ARGS[@]}"
rc=$?
set -e
exit "$rc"
