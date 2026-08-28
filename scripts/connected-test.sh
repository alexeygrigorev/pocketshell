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
#     [--deny-notifications-before-instrumentation] \
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
#                        Network-fault classes under --pool (issue #2128) get a
#                        per-lane toxiproxy + packet-loss proxy on the same
#                        compose project as the claimed agents fixture, derived
#                        from that agents port (2243 -> 2253/2263/8495). They
#                        no longer attach through the shared 2228/8474
#                        singleton. The wrapper still serializes fault-class
#                        runs on the machine-wide toxiproxy lock (issue #776
#                        P3) so a --no-pool sibling cannot race the shared
#                        singleton; isolation is what stops a sibling wipe
#                        presenting as an empty session list.
#   --no-pool            Force the legacy single-lane path even for a journey/E2e
#                        class that would otherwise auto-default to --pool (P2).
#                        Still honours the P0/P1 base-sweep + serial-pin hygiene.
#   --cleanup-suffixes   Uninstall every accumulated com.pocketshell.app.i*
#                        (and .test) package from the target device, then exit.
#                        Prevents install pile-up across worktrees.
#   --deny-notifications-before-instrumentation
#                        Narrow issue-#1741 fixture for
#                        NoNotificationPromptOnAppOpenE2eTest. Installs the exact
#                        base/suffixed target APK, externally revokes and verifies
#                        POST_NOTIFICATIONS before the runner starts, validates a
#                        non-vacuous named result, then externally restores and
#                        verifies the grant after the runner exits.
#
# Everything after the recognised flags is forwarded verbatim to gradle's
# connectedDebugAndroidTest task (e.g. instrumentation-runner-argument filters).
# The task defaults to :app:connectedDebugAndroidTest and is overridable per
# --module (issue #798). The base package (no suffix) and release build are
# never touched.
#
# Lifecycle diagnostics (issue #2004): each mutation run writes a mode-0600 log
# under build/connected-test-lifecycle/ (override the directory with
# POCKETSHELL_CONNECTED_TEST_LIFECYCLE_DIR). It records timestamps, PID/PPID/
# process-group/session/cgroup metadata, lock ownership, scoped-child lifecycle,
# and received/forwarded signals without command lines or environments. A Bash
# trap does not receive Linux siginfo_t, so an external signal's sender PID is
# fundamentally unavailable here; the log says so explicitly and must be
# correlated with the parent orchestrator/system journal when attribution is
# needed. SIGINT/SIGTERM remain hard failures and are never retried or translated
# into a successful result.

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
# Issue #2007: the AVD lock protects the EMULATOR; nothing protected the Gradle
# OUTPUT TREE. A connected build and scripts/full-jvm-gate.py overlapping in one
# worktree both rewrite this checkout's app/build graph, and `--rerun-tasks`
# deletes intermediates the sibling is consuming (the #893 gate died on a
# missing processDebugResources/R.jar). Sourcing this makes
# pocketshell_release_all also release the output-tree lock on exit.
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
# Issue #1989: refuse to start on a full root filesystem. A connected run that
# starts with no room dies inside Docker BuildKit or Gradle with ENOSPC, which
# is indistinguishable from a real test failure until someone runs `df`.
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"

# Retain the selected serial's flock in this wrapper for the complete package,
# install, and instrumentation window. Mutating children explicitly close their
# inherited copy; the wrapper remains the continuous owner (issue #1737).
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
if [[ -z "${ADB:-}" ]]; then
  ADB="$(command -v adb 2>/dev/null || printf '%s/platform-tools/adb' "$ANDROID_SDK")"
fi

print_usage() {
  cat >&2 <<'USAGE'
Lock-wrapped ad-hoc connected-test runner (issues #672/#724/#798).

Usage:
  scripts/connected-test.sh [--suffix <token>] [--module <gradle-module>] \
    [--deny-notifications-before-instrumentation] \
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
  --deny-notifications-before-instrumentation
                       Run the NoNotificationPromptOnAppOpenE2eTest permission
                       fixture outside instrumentation: install, revoke+verify,
                       run, validate its named result, then grant+verify.
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
DENY_NOTIFICATIONS_BEFORE_INSTRUMENTATION=0
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
    --deny-notifications-before-instrumentation)
      DENY_NOTIFICATIONS_BEFORE_INSTRUMENTATION=1
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

NOTIFICATION_PERMISSION_TEST_CLASS="com.pocketshell.app.notifications.NoNotificationPromptOnAppOpenE2eTest"
NOTIFICATION_PERMISSION_TEST_METHOD="appOpenDoesNotPopNotificationPermissionDialog"
NOTIFICATION_PERMISSION="android.permission.POST_NOTIFICATIONS"
if [[ "$DENY_NOTIFICATIONS_BEFORE_INSTRUMENTATION" == "1" ]]; then
  if [[ "$CLEANUP_ONLY" == "1" ]]; then
    printf 'FAIL: notification-permission fixture cannot be combined with --cleanup-suffixes\n' >&2
    exit 2
  fi
  if [[ "$CONNECTED_TASK" != ":app:connectedDebugAndroidTest" ]]; then
    printf 'FAIL: notification-permission fixture is valid only for the app module\n' >&2
    exit 2
  fi
  notification_class_selected=0
  for arg in "${GRADLE_ARGS[@]}"; do
    case "$arg" in
      "-Pandroid.testInstrumentationRunnerArguments.class=$NOTIFICATION_PERMISSION_TEST_CLASS")
        notification_class_selected=1
        ;;
      -Pandroid.testInstrumentationRunnerArguments.numShards=*\
      |-Pandroid.testInstrumentationRunnerArguments.shardIndex=*)
        printf 'FAIL: notification-permission fixture must be a dedicated unsharded invocation\n' >&2
        exit 2
        ;;
    esac
  done
  if [[ "$notification_class_selected" != "1" ]]; then
    printf 'FAIL: notification-permission fixture requires dedicated class=%s\n' \
      "$NOTIFICATION_PERMISSION_TEST_CLASS" >&2
    exit 2
  fi
fi

# Issue #1989: free-disk preflight, run BEFORE anything expensive or shared.
#
# It is deliberately the FIRST thing after argument validation — before the
# #2007 Gradle output-tree lock, before the toxiproxy serialization lock, before
# an emulator serial is claimed, and before the agents fixture container is
# brought up. A lane that cannot possibly succeed must not first sit on the
# box's scarcest resource (often the ONE online emulator) while it discovers
# that; the #2007 header makes the same argument for the output lock.
#
# --cleanup-suffixes is exempt: it builds nothing, needs no space, and is part
# of how an operator recovers a contended box. Gating recovery on the condition
# it recovers from is the classic self-lockout.
if [[ "$CLEANUP_ONLY" != "1" ]]; then
  DISK_PREFLIGHT_RC=0
  pocketshell_disk_preflight "$ROOT_DIR" \
    "connected-test.sh $CONNECTED_TASK suffix=${SUFFIX:-<none>}" \
    || DISK_PREFLIGHT_RC=$?
  if (( DISK_PREFLIGHT_RC != 0 )); then
    exit "$DISK_PREFLIGHT_RC"
  fi
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

# P3 (issue #776) + #2128 — network-fault classes used to share ONE global
# toxiproxy (hardcoded 10.0.2.2:2228 / API 8474). --pool now isolates that:
# a pool lane brings up its own network-fault-proxy under the claimed
# agents compose project and NetworkFaultPorts derives the host ports from
# the agents port. The machine-wide toxiproxy lock still serializes
# fault-class runs so a --no-pool sibling on the shared 2228 singleton
# cannot race another --no-pool (or a pool fallback to 2222). Detection
# matches the known NetworkFaultProofBase-derived class names.
NETWORK_FAULT_RUN=0
gradle_args_str="${GRADLE_ARGS[*]:-}"
if [[ "$CLEANUP_ONLY" != "1" ]]; then
  case "$gradle_args_str" in
    *NetworkFault*|*NetworkLatencyModel*|*PacketLoss*|*DisconnectBlackhole*\
      |*DisconnectFlap*|*KeepAliveDeadPeer*|*RideThrough*|*WithinGrace*\
      |*StaleLeaseSwitchRecovery*|*CodexRedrawOverflowReconnect*\
      |*OutboundAttachmentOffsetResumeJourneyE2eTest*\
      |*SilentMidSessionDrop*|*ColdDialUnderBandwidth*|*RealisticWifiStability*\
      |*NatIdleMapping*|*MobileLatencyStorm*|*PushResumeDeadSocket*\
      |*ConversationOpenLatency*|*AttachNavigationMultiFolderE2eTest*)
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
  printf 'Network-fault class detected (issue #776 P3 / #2128): serialized on the shared toxiproxy lock; a --pool lane still gets its own derived proxy so a sibling wipe of 2222/2228 cannot look like a product failure.\n' >&2
fi

# Issue #2007: own this checkout's Gradle OUTPUT TREE before ANY other shared
# resource, and long before any gradle task runs. The per-serial AVD lock is a
# different resource and stays a separate concern: two lanes on DISTINCT
# emulators legitimately hold distinct AVD locks, yet they still write the one
# app/build graph of this worktree, so they must queue here. A `--pool` lane
# relocates its build dir to build/lane-<suffix> (issue #724) and therefore
# passes that lane token, keeping genuinely disjoint output trees concurrent.
#
# ORDER MATTERS (review round 2). This claim comes FIRST -- before the
# toxiproxy singleton, the emulator serial, and the agents fixture port --
# because a lane that loses the output-tree race would otherwise sit on the
# box's scarcest resource (often the ONE online emulator) for the whole bounded
# wait without building anything: a silent cross-lane starvation introduced by
# the change that removes one. It also makes the acquisition order uniform
# across every wrapper path (output tree -> toxiproxy -> serial -> agents
# port), so two lanes can never deadlock each holding half of the other's set.
# --cleanup-suffixes never invokes gradle, so it deliberately takes no output
# lock and must not queue behind a build.
GRADLE_OUTPUT_LANE=""
if [[ "$USE_POOL" == "1" && -n "$SUFFIX" ]]; then
  GRADLE_OUTPUT_LANE="lane-$SUFFIX"
fi
if [[ "$CLEANUP_ONLY" != "1" ]]; then
  GRADLE_OUTPUT_LOCK_RC=0
  pocketshell_acquire_gradle_output_lock "$ROOT_DIR" "$GRADLE_OUTPUT_LANE" \
    "connected-test.sh $CONNECTED_TASK suffix=${SUFFIX:-<none>}" \
    || GRADLE_OUTPUT_LOCK_RC=$?
  if (( GRADLE_OUTPUT_LOCK_RC != 0 )); then
    printf 'FAIL: refusing to start %s without exclusive ownership of this worktree gradle output tree (issue #2007).\n' \
      "$CONNECTED_TASK" >&2
    exit "$GRADLE_OUTPUT_LOCK_RC"
  fi
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

# Issue #2128: a pool fault-class lane must actually HAVE a per-lane
# toxiproxy. Claiming agents:2243 and then attaching through the shared
# 2228 proxy is the pin this issue exists to kill. Bring the proxies up
# on the derived host ports under this lane's compose project, then
# fingerprint the proxy so a mid-run wipe is rc 90, not an empty session
# list. Single-lane / 2222 fallback keeps the historical shared proxy
# (started by nightly / the caller) and does not recreate it here.
if [[ "$CLEANUP_ONLY" != "1" && "$NETWORK_FAULT_RUN" == "1" \
      && "$USE_POOL" == "1" \
      && -n "${POCKETSHELL_AGENTS_PORT:-}" \
      && "${POCKETSHELL_AGENTS_PORT}" != "2222" ]]; then
  if ! pocketshell_network_fault_fixture_up "$ROOT_DIR" "$POCKETSHELL_AGENTS_PORT"; then
    printf 'FAIL: could not bring up the per-lane network-fault proxy for agents port %s (issue #2128).\n' \
      "$POCKETSHELL_AGENTS_PORT" >&2
    exit 1
  fi
  pocketshell_network_fault_record_fixture_identity "$POCKETSHELL_AGENTS_PORT"
  printf 'Pool mode: network-fault proxy on host port %s (API %s) for agents %s\n' \
    "$(pocketshell_network_fault_ssh_port "$POCKETSHELL_AGENTS_PORT")" \
    "$(pocketshell_toxiproxy_api_port "$POCKETSHELL_AGENTS_PORT")" \
    "$POCKETSHELL_AGENTS_PORT" >&2
fi

# Optional same-run fixture evidence. The capture happens while this wrapper still
# owns BOTH the emulator and agents-port locks, so the container fingerprint,
# readiness probe, Docker logs, and instrumentation outputs describe one coherent
# run rather than a later inspection of a reusable pool container.
CONNECTED_EVIDENCE_DIR="${POCKETSHELL_CONNECTED_EVIDENCE_DIR:-}"
CONNECTED_EVIDENCE_STARTED_AT=""
CONNECTED_EVIDENCE_RUN_ID=""
if [[ -n "$CONNECTED_EVIDENCE_DIR" ]]; then
  mkdir -p "$CONNECTED_EVIDENCE_DIR"
  CONNECTED_EVIDENCE_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  CONNECTED_EVIDENCE_RUN_ID="${SUFFIX:-base}-${ANDROID_SERIAL:-unknown}-$$"
  evidence_container="$(pocketshell_agents_container_for_port "${POCKETSHELL_AGENTS_PORT:-2222}")"
  {
    printf 'run_id=%s\n' "$CONNECTED_EVIDENCE_RUN_ID"
    printf 'started_at=%s\n' "$CONNECTED_EVIDENCE_STARTED_AT"
    printf 'android_serial=%s\n' "${ANDROID_SERIAL:-unknown}"
    printf 'agents_port=%s\n' "${POCKETSHELL_AGENTS_PORT:-2222}"
    printf 'container=%s\n' "$evidence_container"
    printf 'claim_fingerprint=%s\n' "${POCKETSHELL_AGENTS_FIXTURE_IDENTITY:-unknown}"
  } > "$CONNECTED_EVIDENCE_DIR/run-manifest-start.txt"
  pocketshell_run_without_avd_lock_fd docker inspect "$evidence_container" \
    > "$CONNECTED_EVIDENCE_DIR/docker-inspect-start.json" 2>&1
  pocketshell_run_without_avd_lock_fd docker ps --no-trunc --filter "name=^/${evidence_container}$" \
    > "$CONNECTED_EVIDENCE_DIR/docker-ps-start.txt" 2>&1
  evidence_ssh_key="$(mktemp "${TMPDIR:-/tmp}/pocketshell-connected-evidence-key.XXXXXX")"
  cp "$ROOT_DIR/tests/docker/test_key" "$evidence_ssh_key"
  chmod 600 "$evidence_ssh_key"
  {
    printf '[%s] health=%s\n' "$(date -Is)" "$(docker inspect --format='{{.State.Health.Status}}' "$evidence_container")"
    ssh -i "$evidence_ssh_key" -p "${POCKETSHELL_AGENTS_PORT:-2222}" \
      -o BatchMode=yes -o ConnectTimeout=3 -o ConnectionAttempts=1 \
      -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      testuser@127.0.0.1 "printf 'issue1944 ssh ready '; tmux -V"
  } > "$CONNECTED_EVIDENCE_DIR/docker-ssh-readiness.log" 2>&1
  rm -f "$evidence_ssh_key"
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
POCKETSHELL_CONNECTED_TEST_SIGNAL_RC=0
POCKETSHELL_CONNECTED_TEST_SIGNAL_SNAPSHOT_PENDING=0

# Issue #2004: preserve enough wrapper-side lifecycle evidence to distinguish
# "Gradle failed" from "the already-locked wrapper received an external
# signal". Bash signal traps expose the signal name but NOT siginfo_t (and thus
# not si_pid), so this deliberately records sender_pid=unavailable instead of
# inventing attribution from PPID/session membership. The process snapshots use
# PID metadata and comm only; command lines and environments are excluded
# because Gradle runner arguments may contain credentials or other secrets.
CONNECTED_TEST_LIFECYCLE_DIR="${POCKETSHELL_CONNECTED_TEST_LIFECYCLE_DIR:-$ROOT_DIR/build/connected-test-lifecycle}"
CONNECTED_TEST_LIFECYCLE_LOG=""

pocketshell_lifecycle_value() {
  local value="${1:-unknown}"
  value="${value//$'\n'/;}"
  value="${value//$'\r'/;}"
  value="${value//$'\t'/_}"
  value="${value// /_}"
  printf '%s' "$value"
}

pocketshell_lifecycle_log() {
  local event="$1"
  shift
  [[ -n "$CONNECTED_TEST_LIFECYCLE_LOG" ]] || return 0
  local timestamp line field
  timestamp="$(date -u +%Y-%m-%dT%H:%M:%S.%NZ 2>/dev/null || printf 'unknown')"
  line="timestamp=$timestamp event=$event"
  for field in "$@"; do
    line+=" $field"
  done
  printf '%s\n' "$line" >> "$CONNECTED_TEST_LIFECYCLE_LOG" 2>/dev/null || true
}

# Signal handlers can interrupt this script while Bash is suspended inside a
# command substitution. Re-entering the general logger (which itself uses a
# command substitution for `date`) can corrupt the interrupted parser state.
# Keep trap-time persistence to Bash builtins only; richer /proc + systemd
# snapshots run immediately after the child has observed the forwarded signal.
pocketshell_lifecycle_signal_log() {
  local event="$1"
  shift
  [[ -n "$CONNECTED_TEST_LIFECYCLE_LOG" ]] || return 0
  local timestamp field
  # Bash's %(...)T formatter otherwise obeys the caller's local timezone. The
  # temporary TZ assignment and printf -v both stay inside the shell: no fork,
  # command substitution, argument leakage, or pre-forwarding external work.
  TZ=UTC0 printf -v timestamp '%(%Y-%m-%dT%H:%M:%SZ)T' -1
  {
    printf 'timestamp=%s event=%s' "$timestamp" "$event"
    for field in "$@"; do
      printf ' %s' "$field"
    done
    printf '\n'
  } >> "$CONNECTED_TEST_LIFECYCLE_LOG" 2>/dev/null || true
}

pocketshell_lifecycle_process_snapshot() {
  local pid="${1:-}" role="${2:-unknown}"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 0
  local process_line ppid="unavailable" pgid="unavailable" sid="unavailable"
  local state="unavailable" comm="unavailable" cgroup="unavailable"
  process_line="$(ps -o ppid=,pgid=,sid=,stat=,comm= -p "$pid" 2>/dev/null || true)"
  if [[ -n "$process_line" ]]; then
    read -r ppid pgid sid state comm <<< "$process_line" || true
  fi
  if [[ -r "/proc/$pid/cgroup" ]]; then
    cgroup="$(tr '\n' ';' < "/proc/$pid/cgroup" 2>/dev/null || true)"
    [[ -n "$cgroup" ]] || cgroup="unavailable"
  fi
  pocketshell_lifecycle_log process_snapshot \
    "pid=$pid" \
    "ppid=$(pocketshell_lifecycle_value "$ppid")" \
    "pgid=$(pocketshell_lifecycle_value "$pgid")" \
    "sid=$(pocketshell_lifecycle_value "$sid")" \
    "comm=$(pocketshell_lifecycle_value "$comm")" \
    "state=$(pocketshell_lifecycle_value "$state")" \
    "role=$(pocketshell_lifecycle_value "$role")"
  pocketshell_lifecycle_log cgroup_snapshot \
    "pid=$pid" \
    "cgroup=$(pocketshell_lifecycle_value "$cgroup")" \
    "role=$(pocketshell_lifecycle_value "$role")"
}

pocketshell_lifecycle_scope_snapshot() {
  local state="unavailable"
  if [[ -n "${SCOPE_UNIT:-}" ]] && command -v systemctl >/dev/null 2>&1; then
    state="$(timeout 2s systemctl --user show "$SCOPE_UNIT.scope" \
      --property=ActiveState,SubState,Result,MainPID,ControlGroup \
      --value 2>/dev/null | tr '\n' ',' || true)"
    [[ -n "$state" ]] || state="unavailable"
  fi
  pocketshell_lifecycle_log scope_snapshot \
    "scope=$(pocketshell_lifecycle_value "${SCOPE_UNIT:-unassigned}")" \
    "state=$(pocketshell_lifecycle_value "$state")"
}

pocketshell_lifecycle_snapshot_tree() {
  pocketshell_lifecycle_process_snapshot "$$" wrapper
  pocketshell_lifecycle_process_snapshot "$PPID" parent
  if [[ -n "$MUTATION_PID" ]]; then
    pocketshell_lifecycle_process_snapshot "$MUTATION_PID" mutation_child
    local descendant
    while IFS= read -r descendant; do
      [[ -n "$descendant" ]] || continue
      pocketshell_lifecycle_process_snapshot "$descendant" mutation_descendant
    done < <(ps -o pid= --ppid "$MUTATION_PID" 2>/dev/null | awk '{ print $1 }')
  fi
  pocketshell_lifecycle_scope_snapshot
}

pocketshell_lifecycle_init() {
  mkdir -p "$CONNECTED_TEST_LIFECYCLE_DIR" 2>/dev/null || return 0
  chmod 700 "$CONNECTED_TEST_LIFECYCLE_DIR" 2>/dev/null || true
  local started_at token
  started_at="$(date -u +%Y%m%dT%H%M%S 2>/dev/null || printf 'unknown')"
  token="${SUFFIX:-base}-${ANDROID_SERIAL:-unknown}-$$"
  token="${token//[^A-Za-z0-9._-]/_}"
  CONNECTED_TEST_LIFECYCLE_LOG="$(mktemp \
    --tmpdir="$CONNECTED_TEST_LIFECYCLE_DIR" \
    --suffix=.log \
    "$started_at-$token.XXXXXX" 2>/dev/null || true)"
  if [[ -z "$CONNECTED_TEST_LIFECYCLE_LOG" ]]; then
    CONNECTED_TEST_LIFECYCLE_LOG=""
    return 0
  fi
  chmod 600 "$CONNECTED_TEST_LIFECYCLE_LOG" 2>/dev/null || true
  pocketshell_lifecycle_log wrapper_started \
    "wrapper_pid=$$" "parent_pid=$PPID" \
    "sender_pid=unavailable" \
    "sender_limit=bash_traps_do_not_expose_kernel_siginfo"
  pocketshell_lifecycle_snapshot_tree
  printf 'Connected-test lifecycle log: %s\n' "$CONNECTED_TEST_LIFECYCLE_LOG" >&2
}

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
  local mutation_lock="${POCKETSHELL_AVD_LOCK_FILE:-unavailable}"
  mutation_lock="${mutation_lock// /_}"
  MUTATION_PID="$child_pid"
  pocketshell_lifecycle_signal_log mutation_started \
    "child_pid=$child_pid" \
    "child_state=spawned" \
    "lock_file=$mutation_lock"
  # Do not run the multi-command /proc snapshot here: the child may publish its
  # test-ready boundary immediately, and an external TERM can then interrupt
  # Bash while it is expanding that snapshot. Trap-time logging stays builtin-
  # only; the rich child/scope snapshot is taken after signal forwarding below.
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
  pocketshell_lifecycle_log mutation_finished \
    "child_pid=$child_pid" "child_rc=$child_rc" \
    "ownership_lost=${ownership_lost:-0}"
  if (( POCKETSHELL_CONNECTED_TEST_SIGNAL_SNAPSHOT_PENDING != 0 )); then
    pocketshell_lifecycle_snapshot_tree
    POCKETSHELL_CONNECTED_TEST_SIGNAL_SNAPSHOT_PENDING=0
  fi
  MUTATION_PID=""
  if (( ownership_lost == 1 )); then
    return 70
  fi
  return "$child_rc"
}

notification_permission_capture() {
  local output_file="$1"
  shift
  local adb_target=("$ADB")
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_target+=(-s "$ANDROID_SERIAL")
  fi
  : > "$output_file"
  pocketshell_run_guarded_mutation "${adb_target[@]}" "$@" > "$output_file"
}

notification_permission_state() {
  local target_package="$1"
  local output_file="$2"
  local line
  notification_permission_capture \
    "$output_file" \
    shell dumpsys package "$target_package"
  line="$(grep -F "$NOTIFICATION_PERMISSION: granted=" "$output_file" | head -1 || true)"
  case "$line" in
    *"granted=true"*)
      printf 'granted\n'
      ;;
    *"granted=false"*)
      printf 'denied\n'
      ;;
    *)
      printf 'unknown\n'
      ;;
  esac
}

notification_permission_prepare() {
  local target_package="$1"
  local scratch_dir="$2"
  local sdk_file="$scratch_dir/sdk"
  local package_file="$scratch_dir/package"
  local state_file="$scratch_dir/state"
  local adb_target=("$ADB")
  local sdk state
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_target+=(-s "$ANDROID_SERIAL")
  fi

  notification_permission_capture "$sdk_file" shell getprop ro.build.version.sdk
  sdk="$(tr -d '\r[:space:]' < "$sdk_file")"
  if [[ ! "$sdk" =~ ^[0-9]+$ || "$sdk" -lt 33 ]]; then
    printf 'FAIL: POST_NOTIFICATIONS fixture requires Android API 33+ (got: %s)\n' \
      "${sdk:-empty}" >&2
    return 1
  fi

  notification_permission_capture "$package_file" shell pm path "$target_package"
  if ! grep -q '^package:' "$package_file"; then
    printf 'FAIL: target package %s is not installed before permission revoke\n' \
      "$target_package" >&2
    return 1
  fi

  if ! pocketshell_run_guarded_mutation \
      "${adb_target[@]}" shell pm revoke \
      "$target_package" "$NOTIFICATION_PERMISSION"; then
    printf 'FAIL: external pre-instrumentation revoke failed for %s\n' \
      "$target_package" >&2
    return 1
  fi
  state="$(notification_permission_state "$target_package" "$state_file")"
  if [[ "$state" != "denied" ]]; then
    printf 'FAIL: %s must be denied before instrumentation (state=%s)\n' \
      "$NOTIFICATION_PERMISSION" "${state:-empty}" >&2
    return 1
  fi
  printf 'Notification permission fixture prepared externally: package=%s state=denied api=%s\n' \
    "$target_package" "$sdk"
}

notification_permission_restore() {
  local target_package="$1"
  local scratch_dir="$2"
  local target_apk="$3"
  local package_file="$scratch_dir/restore-package"
  local state_file="$scratch_dir/restore-state"
  local adb_target=("$ADB")
  local state
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_target+=(-s "$ANDROID_SERIAL")
  fi

  notification_permission_capture "$package_file" shell pm path "$target_package"
  if ! grep -q '^package:' "$package_file"; then
    if [[ ! -s "$target_apk" ]]; then
      printf 'NOTIFICATION_PERMISSION_CLEANUP_FAILED: target APK missing for restore: %s\n' \
        "$target_apk" >&2
      return 1
    fi
    if ! pocketshell_run_guarded_mutation \
        "${adb_target[@]}" install -r "$target_apk"; then
      printf 'NOTIFICATION_PERMISSION_CLEANUP_FAILED: reinstall failed for %s\n' \
        "$target_package" >&2
      return 1
    fi
    notification_permission_capture "$package_file" shell pm path "$target_package"
    if ! grep -q '^package:' "$package_file"; then
      printf 'NOTIFICATION_PERMISSION_CLEANUP_FAILED: %s absent after reinstall\n' \
        "$target_package" >&2
      return 1
    fi
  fi

  if ! pocketshell_run_guarded_mutation \
      "${adb_target[@]}" shell pm grant \
      "$target_package" "$NOTIFICATION_PERMISSION"; then
    printf 'NOTIFICATION_PERMISSION_CLEANUP_FAILED: external grant failed for %s\n' \
      "$target_package" >&2
    return 1
  fi
  state="$(notification_permission_state "$target_package" "$state_file")"
  if [[ "$state" != "granted" ]]; then
    printf 'NOTIFICATION_PERMISSION_CLEANUP_FAILED: %s was not restored for %s (state=%s)\n' \
      "$NOTIFICATION_PERMISSION" "$target_package" "${state:-empty}" >&2
    return 1
  fi
  printf 'Notification permission fixture restored externally: package=%s state=granted\n' \
    "$target_package"
}

notification_permission_validate_report() {
  local report_dir="$1"
  local tests=0 skipped=0 failures=0 errors=0
  local xml line value
  local named_method=0 xml_count=0

  while IFS= read -r -d '' xml; do
    xml_count=$((xml_count + 1))
    line="$(grep -m1 '<testsuite ' "$xml" 2>/dev/null || true)"
    [[ -n "$line" ]] || continue
    value="$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' <<< "$line")"
    tests=$((tests + ${value:-0}))
    value="$(sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p' <<< "$line")"
    skipped=$((skipped + ${value:-0}))
    value="$(sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p' <<< "$line")"
    failures=$((failures + ${value:-0}))
    value="$(sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p' <<< "$line")"
    errors=$((errors + ${value:-0}))
    if grep -q "classname=\"$NOTIFICATION_PERMISSION_TEST_CLASS\"" "$xml" \
        && grep -q "name=\"$NOTIFICATION_PERMISSION_TEST_METHOD\"" "$xml"; then
      named_method=1
    fi
  done < <(find "$report_dir" -type f -name '*.xml' -print0 2>/dev/null)

  local executed=$((tests - skipped))
  if (( xml_count == 0 || executed != 1 )); then
    printf 'FAIL: notification-permission invocation must execute exactly one test (xml=%s tests=%s skipped=%s executed=%s)\n' \
      "$xml_count" "$tests" "$skipped" "$executed" >&2
    return 1
  fi
  if (( skipped != 0 )); then
    printf 'FAIL: notification-permission invocation skipped %s test(s); no-self-skip policy requires zero\n' \
      "$skipped" >&2
    return 1
  fi
  if (( failures != 0 || errors != 0 )); then
    printf 'FAIL: notification-permission invocation report is red (failures=%s errors=%s)\n' \
      "$failures" "$errors" >&2
    return 1
  fi
  if [[ "$named_method" != "1" ]]; then
    printf 'FAIL: notification-permission report did not contain %s#%s\n' \
      "$NOTIFICATION_PERMISSION_TEST_CLASS" "$NOTIFICATION_PERMISSION_TEST_METHOD" >&2
    return 1
  fi
  printf 'NOTIFICATION_PERMISSION_TEST_RESULT executed=%s skipped=%s failures=%s errors=%s class=%s method=%s\n' \
    "$executed" "$skipped" "$failures" "$errors" \
    "$NOTIFICATION_PERMISSION_TEST_CLASS" "$NOTIFICATION_PERMISSION_TEST_METHOD"
}

# Issue #1662 / G5: Gradle's connected-test task can return zero after the
# instrumentation runner has written a red authoritative JUnit XML. Never let
# that wrapper result launder a product assertion failure into a green journey.
# The report directory is cleared immediately before this invocation, so every
# XML found here belongs to the current run rather than a previous attempt.
connected_test_report_dir() {
  local module_path="${CONNECTED_TASK%:connectedDebugAndroidTest}"
  module_path="${module_path#:}"
  module_path="${module_path//:/\/}"
  local build_dir="$ROOT_DIR/$module_path/build"
  if [[ "$USE_POOL" == "1" && -n "$SUFFIX" ]]; then
    build_dir="$build_dir/lane-$SUFFIX"
  fi
  printf '%s/outputs/androidTest-results/connected\n' "$build_dir"
}

connected_test_validate_report() {
  local report_dir
  report_dir="$(connected_test_report_dir)"
  local xml line value
  local xml_count=0 tests=0 skipped=0 failures=0 errors=0

  while IFS= read -r -d '' xml; do
    xml_count=$((xml_count + 1))
    line="$(grep -m1 '<testsuite ' "$xml" 2>/dev/null || true)"
    [[ -n "$line" ]] || continue
    value="$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' <<< "$line")"
    tests=$((tests + ${value:-0}))
    value="$(sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p' <<< "$line")"
    skipped=$((skipped + ${value:-0}))
    value="$(sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p' <<< "$line")"
    failures=$((failures + ${value:-0}))
    value="$(sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p' <<< "$line")"
    errors=$((errors + ${value:-0}))
  done < <(find "$report_dir" -type f -name 'TEST-*.xml' -print0 2>/dev/null)

  local executed=$((tests - skipped))
  if (( xml_count == 0 )); then
    printf 'CONNECTED_TEST_XML_FAILED: no authoritative JUnit XML under %s\n' \
      "$report_dir" >&2
    return 1
  fi
  if (( tests <= 0 || executed <= 0 )); then
    printf 'CONNECTED_TEST_XML_FAILED: invocation executed no tests (xml=%s tests=%s skipped=%s)\n' \
      "$xml_count" "$tests" "$skipped" >&2
    return 1
  fi
  if (( failures != 0 || errors != 0 )); then
    printf 'CONNECTED_TEST_XML_FAILED: authoritative JUnit XML is red (xml=%s tests=%s skipped=%s failures=%s errors=%s)\n' \
      "$xml_count" "$tests" "$skipped" "$failures" "$errors" >&2
    return 1
  fi
  printf 'CONNECTED_TEST_XML_RESULT executed=%s skipped=%s failures=%s errors=%s report=%s\n' \
    "$executed" "$skipped" "$failures" "$errors" "$report_dir"
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
pocketshell_lifecycle_init
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
pocketshell_lifecycle_log avd_lock_acquired \
  "wrapper_pid=$$" \
  "lock_file=$(pocketshell_lifecycle_value "${POCKETSHELL_AVD_LOCK_FILE:-unavailable}")" \
  "lock_fd=$(pocketshell_lifecycle_value "${POCKETSHELL_AVD_LOCK_FD:-unavailable}")" \
  "lock_owner_pid=$(pocketshell_lifecycle_value "${POCKETSHELL_AVD_LOCK_OWNER_PID:-unavailable}")" \
  "lock_holder_pid=$(pocketshell_lifecycle_value "${POCKETSHELL_AVD_LOCK_HOLDER_PID:-unavailable}")" \
  "android_serial=$(pocketshell_lifecycle_value "${ANDROID_SERIAL:-unavailable}")"
pocketshell_lifecycle_snapshot_tree

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

NOTIFICATION_PERMISSION_RESTORE_NEEDED=0
NOTIFICATION_PERMISSION_TARGET_PACKAGE="com.pocketshell.app${SUFFIX:+.$SUFFIX}"
NOTIFICATION_PERMISSION_SCRATCH=""
NOTIFICATION_PERMISSION_BUILD_DIR="$ROOT_DIR/app/build"
if [[ "$USE_POOL" == "1" && -n "$SUFFIX" ]]; then
  NOTIFICATION_PERMISSION_BUILD_DIR="$ROOT_DIR/app/build/lane-$SUFFIX"
fi
NOTIFICATION_PERMISSION_TARGET_APK="$NOTIFICATION_PERMISSION_BUILD_DIR/outputs/apk/debug/app-debug.apk"

# Invoked indirectly by the EXIT trap below.
# shellcheck disable=SC2317
pocketshell_connected_test_exit_cleanup() {
  local original_rc=$?
  pocketshell_lifecycle_log wrapper_exit \
    "rc=$original_rc" \
    "received_wrapper_signal_rc=$POCKETSHELL_CONNECTED_TEST_SIGNAL_RC" \
    "active_child_pid=$(pocketshell_lifecycle_value "${MUTATION_PID:-none}")" \
    "lock_file=$(pocketshell_lifecycle_value "${POCKETSHELL_AVD_LOCK_FILE:-unavailable}")" \
    "lock_fd=$(pocketshell_lifecycle_value "${POCKETSHELL_AVD_LOCK_FD:-unavailable}")"
  if (( POCKETSHELL_CONNECTED_TEST_SIGNAL_RC != 0 )); then
    printf 'CONNECTED_TEST_SIGNAL_EXIT rc=%s lifecycle_log=%s\n' \
      "$original_rc" "${CONNECTED_TEST_LIFECYCLE_LOG:-unavailable}" >&2
  elif (( original_rc > 128 )); then
    printf 'CONNECTED_TEST_CHILD_SIGNAL_EXIT rc=%s received_wrapper_signal=none lifecycle_log=%s\n' \
      "$original_rc" "${CONNECTED_TEST_LIFECYCLE_LOG:-unavailable}" >&2
  fi
  pocketshell_lifecycle_snapshot_tree
  if [[ -n "${CONNECTED_EVIDENCE_DIR:-}" && -n "${CONNECTED_EVIDENCE_STARTED_AT:-}" ]]; then
    local evidence_port evidence_container evidence_final_identity evidence_output_root
    evidence_port="${POCKETSHELL_AGENTS_PORT:-2222}"
    evidence_container="$(pocketshell_agents_container_for_port "$evidence_port")"
    evidence_final_identity="$(pocketshell_agents_fixture_identity "$evidence_port")"
    pocketshell_run_without_avd_lock_fd docker inspect "$evidence_container" \
      > "$CONNECTED_EVIDENCE_DIR/docker-inspect-end.json" 2>&1 || true
    pocketshell_run_without_avd_lock_fd docker logs --timestamps \
      --since "$CONNECTED_EVIDENCE_STARTED_AT" "$evidence_container" \
      > "$CONNECTED_EVIDENCE_DIR/docker-agents.log" 2>&1 || true
    pocketshell_run_without_avd_lock_fd docker ps --no-trunc --filter "name=^/${evidence_container}$" \
      > "$CONNECTED_EVIDENCE_DIR/docker-ps-end.txt" 2>&1 || true
    {
      printf 'run_id=%s\n' "$CONNECTED_EVIDENCE_RUN_ID"
      printf 'started_at=%s\n' "$CONNECTED_EVIDENCE_STARTED_AT"
      printf 'ended_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'android_serial=%s\n' "${ANDROID_SERIAL:-unknown}"
      printf 'agents_port=%s\n' "$evidence_port"
      printf 'container=%s\n' "$evidence_container"
      printf 'claim_fingerprint=%s\n' "${POCKETSHELL_AGENTS_FIXTURE_IDENTITY:-unknown}"
      printf 'final_fingerprint=%s\n' "$evidence_final_identity"
      printf 'wrapper_exit_rc=%s\n' "$original_rc"
    } > "$CONNECTED_EVIDENCE_DIR/run-manifest-end.txt"
    find "$CONNECTED_EVIDENCE_DIR" -maxdepth 1 -type f ! -name SHA256SUMS -print0 \
      | sort -z \
      | xargs -0 -r sha256sum \
      > "$CONNECTED_EVIDENCE_DIR/SHA256SUMS" 2>/dev/null || true
    evidence_output_root="$ROOT_DIR/app/build${SUFFIX:+/lane-$SUFFIX}/outputs/connected_android_test_additional_output"
    while IFS= read -r evidence_target; do
      cp -a "$CONNECTED_EVIDENCE_DIR" "$evidence_target/fixture-run-bundle"
    done < <(find "$evidence_output_root" -type d -name issue1526-exactly-once 2>/dev/null || true)
  fi
  if [[ "$NOTIFICATION_PERMISSION_RESTORE_NEEDED" == "1" \
        && -n "$NOTIFICATION_PERMISSION_SCRATCH" ]]; then
    notification_permission_restore \
      "$NOTIFICATION_PERMISSION_TARGET_PACKAGE" \
      "$NOTIFICATION_PERMISSION_SCRATCH" \
      "$NOTIFICATION_PERMISSION_TARGET_APK" || true
  fi
  if declare -F pocketshell_cleanup_lane_init >/dev/null 2>&1; then
    pocketshell_cleanup_lane_init
  fi
  if [[ -n "$NOTIFICATION_PERMISSION_SCRATCH" ]]; then
    pocketshell_run_without_avd_lock_fd rm -rf "$NOTIFICATION_PERMISSION_SCRATCH"
  fi
  pocketshell_release_all
  return "$original_rc"
}
trap pocketshell_connected_test_exit_cleanup EXIT

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
  local child_pid="${MUTATION_PID:-}" child_state="none" forward_result="no_active_child"
  case "$sig" in
    INT) POCKETSHELL_CONNECTED_TEST_SIGNAL_RC=130 ;;
    TERM) POCKETSHELL_CONNECTED_TEST_SIGNAL_RC=143 ;;
  esac
  if [[ -n "$child_pid" ]]; then
    if pocketshell_process_is_running "$child_pid"; then
      child_state="running"
    else
      child_state="exited"
    fi
  fi
  POCKETSHELL_CONNECTED_TEST_SIGNAL_SNAPSHOT_PENDING=1
  pocketshell_lifecycle_signal_log signal_received \
    "signal=$sig" \
    "child_pid=${child_pid:-none}" \
    "child_state=$child_state" \
    "sender_pid=unavailable" \
    "sender_limit=bash_traps_do_not_expose_kernel_siginfo"
  printf 'CONNECTED_TEST_SIGNAL_RECEIVED signal=%s wrapper_pid=%s child_pid=%s child_state=%s sender_pid=unavailable lifecycle_log=%s\n' \
    "$sig" "$$" "${child_pid:-none}" "$child_state" \
    "${CONNECTED_TEST_LIFECYCLE_LOG:-unavailable}" >&2
  if [[ -n "$MUTATION_PID" ]]; then
    if kill -s "$sig" "$MUTATION_PID" 2>/dev/null; then
      forward_result="sent"
    else
      forward_result="already_exited"
    fi
  fi
  pocketshell_lifecycle_signal_log signal_forwarded \
    "signal=$sig" \
    "child_pid=${child_pid:-none}" \
    "forward_result=$forward_result" \
    "child_state_before=$child_state"
  printf 'CONNECTED_TEST_SIGNAL_FORWARDED signal=%s child_pid=%s result=%s\n' \
    "$sig" "${child_pid:-none}" "$forward_result" >&2
}
trap 'forward_signal INT' INT
trap 'forward_signal TERM' TERM

# The wrapper retains the serial flock while scope-run/Gradle has its inherited
# copy closed. Helper death is monitored until the mutation process exits.
rc=0
cleanup_rc=0
connected_test_report_rc=0
notification_report_rc=0
notification_report_dir=""
if [[ "$DENY_NOTIFICATIONS_BEFORE_INSTRUMENTATION" == "1" ]]; then
  NOTIFICATION_PERMISSION_SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-notification-permission.XXXXXX")"

  # Install the exact base/suffixed target before changing its runtime grant.
  # No instrumentation process exists during this install/revoke/verify stage.
  set +e
  pocketshell_run_guarded_mutation pocketshell_scope_run "${SCOPE_UNIT}-permission-install" \
    ./gradlew --no-daemon :app:installDebug \
    "${GRADLE_INIT_ARGS[@]}" \
    "${GRADLE_SUFFIX_ARGS[@]}" \
    "${GRADLE_ARGS[@]}"
  rc=$?
  set -e
  if (( rc == 0 )); then
    NOTIFICATION_PERMISSION_RESTORE_NEEDED=1
    set +e
    notification_permission_prepare \
      "$NOTIFICATION_PERMISSION_TARGET_PACKAGE" \
      "$NOTIFICATION_PERMISSION_SCRATCH"
    rc=$?
    set -e
  fi

  notification_build_dir="$NOTIFICATION_PERMISSION_BUILD_DIR"
  notification_report_dir="$notification_build_dir/outputs/androidTest-results/connected"
  if (( rc == 0 )); then
    # Prevent a killed/zero-test runner from borrowing an earlier invocation's
    # XML. The directory is a generated output owned by this dedicated run.
    rm -rf "$notification_report_dir"
  fi
fi

if (( rc == 0 )); then
  connected_test_report_dir_path="$(connected_test_report_dir)"
  rm -rf "$connected_test_report_dir_path"
  # Expose the exact report root to the Gradle child as a convenience for
  # hermetic wrapper fixtures. Real Gradle writes the same path through AGP;
  # the authoritative verdict below still comes only from files found there.
  export POCKETSHELL_CONNECTED_TEST_REPORT_DIR="$connected_test_report_dir_path"
  set +e
  pocketshell_run_guarded_mutation pocketshell_scope_run "$SCOPE_UNIT" \
    ./gradlew --no-daemon "$CONNECTED_TASK" \
    "${GRADLE_INIT_ARGS[@]}" \
    "${GRADLE_SUFFIX_ARGS[@]}" \
    "${GRADLE_ARGS[@]}"
  rc=$?
  set -e
fi

# A zero Gradle exit code is not sufficient: the instrumentation XML is the
# authoritative test verdict. Validate it before the fixture classifier and
# before the EXIT trap can publish a green wrapper result.
if (( rc == 0 )); then
  set +e
  connected_test_validate_report
  connected_test_report_rc=$?
  set -e
  if (( connected_test_report_rc != 0 )); then
    rc="$connected_test_report_rc"
  fi
fi

# The generic report verdict remains primary, but the notification fixture has
# an additional identity/count contract whose diagnostic must survive a generic
# XML failure. Run that validator when it was the generic validator that made
# the invocation red, while never replacing an already-nonzero primary status.
if [[ "$DENY_NOTIFICATIONS_BEFORE_INSTRUMENTATION" == "1" \
      && ( "$rc" == "0" || "$connected_test_report_rc" != "0" ) ]]; then
  set +e
  notification_permission_validate_report "$notification_report_dir"
  notification_report_rc=$?
  set -e
  if (( rc == 0 && notification_report_rc != 0 )); then
    rc="$notification_report_rc"
  fi
fi

if [[ "$DENY_NOTIFICATIONS_BEFORE_INSTRUMENTATION" == "1" \
      && "$NOTIFICATION_PERMISSION_RESTORE_NEEDED" == "1" ]]; then
  set +e
  notification_permission_restore \
    "$NOTIFICATION_PERMISSION_TARGET_PACKAGE" \
    "$NOTIFICATION_PERMISSION_SCRATCH" \
    "$NOTIFICATION_PERMISSION_TARGET_APK"
  cleanup_rc=$?
  set -e
  NOTIFICATION_PERMISSION_RESTORE_NEEDED=0
  if (( cleanup_rc != 0 )); then
    printf 'NOTIFICATION_PERMISSION_PRIMARY_RC=%s CLEANUP_RC=%s\n' "$rc" "$cleanup_rc" >&2
    if (( rc == 0 )); then
      rc="$cleanup_rc"
    fi
  fi
fi

# Issue #1842: prove nobody recreated THIS lane's agents fixture while the run
# was in flight. This runs for BOTH outcomes on purpose:
#
#   * a disturbed RED must not be read as a product defect — a wiped tmux server
#     surfaces as an empty session list, which is indistinguishable from #1810 /
#     #1820 and cost two review rounds to the two lanes that hit it;
#   * a disturbed GREEN is no better. The run asserted against a fixture that
#     stopped existing partway through, so the pass is vacuous in exactly the
#     sense process.md's "green run that executed nothing" catalogue warns about.
#     We overwrite a 0 here rather than let a void pass be reported.
#
# Only a claimed pool lane has a fingerprint, so single-lane / CI runs are
# untouched (the helper returns 0 when there is nothing to compare).
rc="$(pocketshell_agents_final_rc "$rc")"

# A trapped external INT/TERM is always a hard failure even if a wrapper child
# races to report zero while the trap is forwarding the signal. Enforce this
# after every result classifier so nothing can translate an interrupted attempt
# into green (or into a different wrapper verdict). No work is retried.
if (( POCKETSHELL_CONNECTED_TEST_SIGNAL_RC != 0 )); then
  pocketshell_lifecycle_log signal_rc_enforced \
    "child_rc=$rc" "wrapper_rc=$POCKETSHELL_CONNECTED_TEST_SIGNAL_RC"
  rc="$POCKETSHELL_CONNECTED_TEST_SIGNAL_RC"
fi

exit "$rc"
