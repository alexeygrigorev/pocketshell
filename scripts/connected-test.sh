#!/usr/bin/env bash
set -euo pipefail

# Issue #672 (option 1 + 2): lock-wrapped ad-hoc connected-test runner.
#
# Ad-hoc `./gradlew :app:connectedDebugAndroidTest` runs fired by implementers
# and reviewers never sourced scripts/lib/avd-lock.sh, so parallel agents
# SIGKILLed each other on the shared AVD. This thin wrapper:
#
#   1. Acquires the existing AVD lock (option 1) so siblings serialise
#      politely on a single emulator instead of racing. When ANDROID_SERIAL
#      is set it takes a *per-serial* lock (pool-ready) so distinct emulators
#      do not block each other; otherwise it takes the single global lock.
#   2. Threads the per-worktree applicationIdSuffix (option 2) into the gradle
#      invocation so each worktree's DEBUG apk installs under a distinct
#      applicationId (e.g. com.pocketshell.app.i672) and multiple test apps
#      coexist on ONE emulator without uninstalling each other.
#
# Usage:
#   scripts/connected-test.sh [--suffix <token>] [--pool|--no-pool] [gradle args...]
#   POCKETSHELL_APP_ID_SUFFIX=i672 scripts/connected-test.sh \
#     -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.SomeTest
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
# (P2); pass --no-pool to opt out. Single-emulator / CI runs are unchanged.
#
# Flags:
#   --suffix <token>     Per-worktree applicationIdSuffix token (e.g. i672).
#                        Overrides POCKETSHELL_APP_ID_SUFFIX. Token must match
#                        [A-Za-z0-9._]+.  Default empty -> base package, so the
#                        wrapper is identical to a plain connectedDebugAndroidTest
#                        when no suffix is given.
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
#                        the port are released on exit. Without --pool the
#                        behaviour is unchanged: if ANDROID_SERIAL is preset it
#                        locks that serial, otherwise it takes the single global
#                        AVD lock, and the agents port defaults to 2222.
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
# :app:connectedDebugAndroidTest task (e.g. instrumentation-runner-argument
# filters). The base package (no suffix) and release build are never touched.

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

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"

SUFFIX="${POCKETSHELL_APP_ID_SUFFIX:-}"
CLEANUP_ONLY=0
USE_POOL=0
# 0 = unset (auto-decide), 1 = caller forced --pool, -1 = caller forced --no-pool.
POOL_FLAG=0
GRADLE_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix)
      [[ $# -ge 2 ]] || { printf 'FAIL: --suffix needs a value\n' >&2; exit 2; }
      SUFFIX="$2"
      shift 2
      ;;
    --suffix=*)
      SUFFIX="${1#--suffix=}"
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
  # acquired just before gradle (pocketshell_acquire_toxiproxy_lock). That lock
  # is SEPARATE from the per-serial AVD lock: two network-fault lanes on distinct
  # pool emulators each hold their own serial lock but must still not share the
  # singleton proxy, so they queue on this one shared lock.
  POCKETSHELL_TOXIPROXY_SERIALIZED=1
  printf 'Network-fault class detected (issue #776 P3): the toxiproxy proxy is a global singleton, so this run is SERIALIZED on a shared lock (no concurrent network-fault lanes).\n' >&2
fi

# Pool mode (issue #674): claim the first FREE emulator from the live pool and
# export ANDROID_SERIAL so AGP/adb pin to it. The claim is held for the life of
# this process and released on exit. This must happen BEFORE the per-serial
# lock selection below so ANDROID_SERIAL is populated. When ANDROID_SERIAL is
# already preset (explicit target), honour it and skip the pool claim.
if [[ "$USE_POOL" == "1" && -z "${ANDROID_SERIAL:-}" ]]; then
  export ADB ANDROID_SDK
  # Called DIRECTLY (not via $(...)) so its exported ANDROID_SERIAL /
  # POCKETSHELL_POOL_* vars and the EXIT release trap land in THIS shell.
  if ! pocketshell_claim_pool_serial "$ROOT_DIR"; then
    printf 'FAIL: could not claim a free pool emulator. Is scripts/avd-pool.sh start running?\n' >&2
    exit 1
  fi
  printf 'Pool mode: running on %s\n' "${ANDROID_SERIAL:-?}" >&2
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

# P1 (issue #776) — always pin ANDROID_SERIAL when MORE than one emulator is
# online, even WITHOUT --pool. AGP's connected DeviceProvider installs +
# instruments on EVERY online device by default, so the instant the avd-pool is
# up a bare `connected-test.sh` (no --pool) cross-installs onto the busy pool
# emulators and SIGKILLs whatever sibling lane is mid-run there (the
# `Process crashed`/signal-9 family). Claiming + pinning a single FREE serial
# closes that fan-out hole on the non-pool path too. Skipped when:
#   * --pool already claimed a serial above (ANDROID_SERIAL set),
#   * the caller preset ANDROID_SERIAL (explicit target),
#   * a cleanup-only run, OR
#   * 0/1 emulator online (single-AVD / CI is byte-for-byte unchanged: AGP
#     already targets the one device, no pin needed).
if [[ "$CLEANUP_ONLY" != "1" && -z "${ANDROID_SERIAL:-}" ]]; then
  if (( "$(online_emulator_count)" > 1 )); then
    export ADB ANDROID_SDK
    if ! pocketshell_claim_pool_serial "$ROOT_DIR"; then
      printf 'FAIL: >1 emulator online but no free one to pin. Other lanes hold them all.\n' >&2
      printf '      Wait for a lane to finish, or boot more pool emulators (scripts/avd-pool.sh start).\n' >&2
      exit 1
    fi
    printf 'Auto-pinned to a free emulator (issue #776): ANDROID_SERIAL=%s\n' "${ANDROID_SERIAL:-?}" >&2
  fi
fi

# Per-serial lock when a specific emulator is targeted; otherwise the single
# global lock. This keeps single-emulator agents serialised (option 1) while
# distinct emulators (pool mode) do not block each other.
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
  export POCKETSHELL_AVD_LOCK_FILE
fi

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
  local pkg removed=0
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
    [[ -n "$pkg" ]] || continue
    if [[ "$include_base" == "1" && ( "$pkg" == "$self_pkg" || "$pkg" == "$self_test_pkg" ) ]]; then
      continue
    fi
    printf 'Uninstalling leftover package: %s\n' "$pkg" >&2
    "${adb_target[@]}" uninstall "$pkg" >/dev/null 2>&1 || true
    removed=$((removed + 1))
  done < <(
    "${adb_target[@]}" shell pm list packages 2>/dev/null \
      | sed 's/^package://' \
      | grep -E "$match_re" || true
  )
  if [[ "$include_base" == "1" ]]; then
    printf 'Pre-run sweep (incl. base) removed %s package(s).\n' "$removed" >&2
  else
    printf 'Cleanup sweep removed %s suffixed package(s).\n' "$removed" >&2
  fi
}

# Acquire the AVD lock for BOTH cleanup and test runs so the sweep cannot race
# a sibling's install/test on the same device.
pocketshell_acquire_avd_lock "$ROOT_DIR"

if [[ "$CLEANUP_ONLY" == "1" ]]; then
  cleanup_suffixed_packages
  exit 0
fi

# P3 (issue #776) — serialize network-fault lanes on the shared toxiproxy lock
# (the proxy is a global singleton; concurrent lanes corrupt each other's
# toxics). Acquired AFTER the per-serial AVD lock so a network-fault lane holds
# BOTH its device lock and exclusive proxy access. A blocking flock: a second
# network-fault lane queues here until the first releases on exit.
if [[ "${POCKETSHELL_TOXIPROXY_SERIALIZED:-0}" == "1" ]]; then
  if ! pocketshell_acquire_toxiproxy_lock "$ROOT_DIR"; then
    printf 'FAIL: could not acquire the toxiproxy serialization lock.\n' >&2
    exit 1
  fi
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
  printf 'Running connectedDebugAndroidTest as com.pocketshell.app.%s\n' "$SUFFIX" >&2
else
  printf 'Running connectedDebugAndroidTest as com.pocketshell.app (no suffix)\n' >&2
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
  LANE_INIT_SCRIPT="$(mktemp "${TMPDIR:-/tmp}/pocketshell-lane-init-$SUFFIX.XXXXXX.gradle")"
  # Relocate each project's build dir to build/lane-<suffix> under the project
  # dir, so concurrent lanes never share intermediates. Nesting UNDER the
  # existing per-project `build/` keeps the lane outputs inside the already
  # gitignored build tree (/build, /app/build, /shared/*/build, */build/), so a
  # lane run never pollutes `git status` or risks being committed. allprojects
  # covers the root + every module the connected build touches.
  cat > "$LANE_INIT_SCRIPT" <<INIT
allprojects {
    layout.buildDirectory.set(file("\${projectDir}/build/lane-$SUFFIX"))
}
INIT
  GRADLE_INIT_ARGS+=("--init-script" "$LANE_INIT_SCRIPT")
  printf 'Pool lane build isolation: per-project build dir -> build/lane-%s\n' "$SUFFIX" >&2
  # Clean up the temp init script when this shell exits (the build dirs
  # themselves are left for artifact inspection; sweep with the suffix sweep).
  trap 'rm -f "$LANE_INIT_SCRIPT" 2>/dev/null || true; pocketshell_release_all' EXIT
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
GRADLE_PID=""
# Invoked indirectly via the INT/TERM traps below; shellcheck can't see that.
# shellcheck disable=SC2317
forward_signal() {
  local sig="$1"
  if [[ -n "$GRADLE_PID" ]]; then
    kill -s "$sig" "$GRADLE_PID" 2>/dev/null || true
  fi
}
trap 'forward_signal INT' INT
trap 'forward_signal TERM' TERM

pocketshell_scope_run "$SCOPE_UNIT" \
  ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  "${GRADLE_INIT_ARGS[@]}" \
  "${GRADLE_SUFFIX_ARGS[@]}" \
  "${GRADLE_ARGS[@]}" &
GRADLE_PID="$!"
wait "$GRADLE_PID"
rc=$?
exit "$rc"
