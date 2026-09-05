#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/scope-run.sh"
source "$ROOT_DIR/scripts/lib/gradle-profile.sh"
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/release-validation-storage.sh"
# Issue #2064: the debug + androidTest APK pair is built HERE, once, and every
# downstream release stage installs and ships THESE bytes. See
# scripts/lib/apk-identity.sh for why "same source" was not good enough.
source "$ROOT_DIR/scripts/lib/apk-identity.sh"
# Issue #2054: the AVD lock is acquired further down, AFTER the execution-profile
# assertion. Queuing behind another emulator-touching run can take an hour; an
# under-resourced profile should be rejected in the first second, not after that
# wait. The lock still guards every emulator-touching step.

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
PYTHON3="${PYTHON3:-python3}"
AVD_NAME="${AVD_NAME:-test}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/pre-release-confidence-gate}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$LOG_ROOT/gradle-home}"
# Issue #2054: reuse the shared release-chain Gradle execution profile instead of
# maintaining a private flag string here. The old default
# (`--no-daemon --no-build-cache --no-parallel --max-workers=2`, no heap flags at
# all) let the Kotlin daemon inherit gradle.properties' 2048m and ran two compile
# workers inside it, which OOMed the v0.4.42 release validation three times before
# any product assertion executed. See scripts/lib/gradle-profile.sh for the
# evidence and the sizing rationale. An explicit GRADLE_FLAGS from the caller
# (e.g. the hosted release workflow's 16 GiB-runner profile) still wins, and the
# assertion below rejects it fast if it lost a heap bound.
GRADLE_FLAGS="${GRADLE_FLAGS:-$(pocketshell_release_gate_gradle_flags)}"
GATE_ISOLATED_WORKTREE="${GATE_ISOLATED_WORKTREE:-1}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
SSH_KEY="${SSH_KEY:-tests/docker/test_key}"
APK_PATH="${APK_PATH:-app2/build/outputs/apk/debug/app2-debug.apk}"
TEST_APK_PATH="${TEST_APK_PATH:-app2/build/outputs/apk/androidTest/debug/app2-debug-androidTest.apk}"
APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS="${APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS:-3}"
APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS="${APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS:-3}"
# Issue #449: when an instrumentation attempt fails because the emulator's GL
# stack never initialised (`Failed to initialize 101010-2 format` -> Compose
# `setContent` never renders -> "No compose hierarchies found"), retrying the
# same selector in place is useless: the broken GL surface persists for the
# whole emulator process lifetime. Instead we cold-reboot the emulator so the
# next boot gets a fresh GL init, then retry the selector. Bounded so a real
# rendering defect can never be masked into an infinite reboot loop.
APP_WALKTHROUGH_GL_REBOOT_ATTEMPTS="${APP_WALKTHROUGH_GL_REBOOT_ATTEMPTS:-2}"
APP_WALKTHROUGH_GL_REBOOT_BOOT_TIMEOUT_SECONDS="${APP_WALKTHROUGH_GL_REBOOT_BOOT_TIMEOUT_SECONDS:-300}"
LEGACY_V1_DB_MIGRATION_ATTEMPTS="${LEGACY_V1_DB_MIGRATION_ATTEMPTS:-3}"
CORE_TERMINAL_CONNECTED_ATTEMPTS="${CORE_TERMINAL_CONNECTED_ATTEMPTS:-2}"
PRE_RELEASE_MANAGE_EMULATOR="${PRE_RELEASE_MANAGE_EMULATOR:-0}"
PRE_RELEASE_EMULATOR_START_ARGS="${PRE_RELEASE_EMULATOR_START_ARGS:--no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save}"

# Issue #2481 — the focused walkthrough SELECTOR LIST is gone; this gate now runs
# app2's WHOLE instrumented set, unfiltered, in ONE instrumentation process.
#
# WHY THE LIST HAD TO GO. Every one of the eight selectors it named
# (PromptComposerVisualScreenshotTest, PromptComposerSendDismissE2eTest,
# SnippetPickerSendButtonsTest, SnippetPickerTmuxZOrderDockerTest,
# PromptComposerCancelRecordingTest, PromptComposerSendWhileRecordingTest,
# EmulatorDockerSshSmokeTest, ReconnectStormLivelockE2eTest) lived in
# `app/src/androidTest`, which the rewrite's hard cut deleted along with the
# whole module. The #749 selector-existence guard below would have hard-failed
# this gate on the first selector — which is the guard working as designed, and
# also means no release could be cut until this was repointed.
#
# WHY UNFILTERED, NOT A NEW LIST. Issue #2474 settled this for app2's suite:
# every journey runs in a FRESH instrumentation process under a per-class
# filter, so state one journey leaks into the next is structurally invisible —
# that is exactly how #2477's cross-journey pollution was found. The CI lane
# (`scripts/ci-app2-journey-suite.sh`, `.github/workflows/app2.yml`) therefore
# runs `:app2:connectedDebugAndroidTest` with NO
# `-Pandroid.testInstrumentationRunnerArguments.class=` filter, ever. The
# release gate must not contradict its own journey lane, so it runs the same
# set the same way — the difference being that the gate drives it through
# `am instrument` against the EXACT validated APK pair it built and recorded
# (issue #2064), rather than through a fresh Gradle build.
#
# The vacuity guard moved with it: `assert_app2_instrumented_suite_exists`
# replaces the per-selector existence check and hard-fails when app2's
# androidTest tree has no journeys left to run, so "unfiltered" can never
# silently become "nothing".
APP2_INSTRUMENTED_SUITE_LABEL="app2-instrumented-suite"

# Issue #2435, repointed by #2481: `am instrument` produces NO host-side JUnit
# XML — Gradle's connected task is what normally writes
# build/outputs/androidTest-results/**/TEST-*.xml. That is an ACCOUNTING gap,
# not a coverage gap, but the #2082 execution ledger reads JUnit XML and nothing
# else, so every app2 journey this gate executes would report NEVER EXECUTED and
# the release job could not conclude success (which is what made the #2356
# `validated-rc` marker unreachable). The old callers of this helper lived in
# scripts/release-emulator-validation.sh's real-agent and long-running branches;
# both were deleted with the `app` module classes they drove, and the surviving
# detached run is the unfiltered app2 suite below.
#
# THE `--require-class` PIN. The run is deliberately UNFILTERED (issue #2474) so
# there is no single class it "was supposed to" credit — but converting without
# a pin would let an all-skipped transcript become a ledger entry, which is the
# exact laundering #2435 closed. J01 is the pin because it is the foundational
# connect-and-trust journey: if it produced no non-skipped case, the run is not
# credible release evidence whatever else it reported.
# assert_app2_instrumented_suite_exists checks the class still exists, so the
# pin cannot rot into a silent no-op.
APP2_LEDGER_REQUIRED_CLASS="${APP2_LEDGER_REQUIRED_CLASS:-com.pocketshell.next.connect.J01ConnectAndTrustJourney}"
DETACHED_INSTRUMENTATION_RESULTS_DIR="${DETACHED_INSTRUMENTATION_RESULTS_DIR:-$ROOT_DIR/build/outputs/androidTest-results/detached-instrumentation}"

record_detached_instrumentation_junit_xml() {
  local test_class="$1" instrumentation_log="$2"
  local out="$DETACHED_INSTRUMENTATION_RESULTS_DIR/TEST-$test_class.xml"
  if bash "$ROOT_DIR/scripts/instrumentation-log-to-junit-xml.sh" \
    --log "$instrumentation_log" \
    --out "$out" \
    --suite "$test_class" \
    --require-class "$test_class"; then
    return 0
  fi
  # Not fatal on its own: the release job's ledger `--verify` is the backstop and
  # will redden with "$test_class has NEVER executed". Turning a conversion
  # hiccup into a second hard release blocker would add a failure mode without
  # adding detection.
  printf 'WARN: could not record ledger JUnit XML for %s from %s\n' \
    "$test_class" "$instrumentation_log" >&2
  return 0
}

usage() {
  cat <<'USAGE'
Usage: scripts/pre-release-confidence-gate.sh [--check-profile]

  --check-profile   Assert the Gradle execution profile + build-scope ceiling
                    and exit (issue #2054). No Gradle, no emulator, no AVD lock.

Runs the local APK pre-release-confidence gate:
  - compile/unit checks
  - deterministic Docker agent target (agents, agents-old-cli, network-fault-proxy)
  - emulator readiness with explicit Android SDK paths
  - shipped-v1 + #261 marker Room migration with post-launch data validation
  - app2's WHOLE instrumented set, unfiltered, in one instrumentation process
    against the validated APK pair, plus its journey screenshots
  - debug APK build and data-preserving update install sanity

Acquires an exclusive `flock` on `build/.avd-lock` (relative to the repo
root) before touching the emulator so that parallel-worktree gate runs
serialise on the shared local AVD. Released automatically on script exit.
See issue #182.

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  PYTHON3=python3
  AVD_NAME=test
  LOG_ROOT=build/pre-release-confidence-gate
  GRADLE_USER_HOME=build/pre-release-confidence-gate/gradle-home
USAGE
  # Issue #2054: print the two resource knobs from the SAME source of truth the
  # gate actually runs with, so this help text cannot drift back into
  # documenting a profile nobody uses (the old copy still advertised
  # `--max-workers=2` with no heap flags long after that combination started
  # OOMing the release build).
  printf '  GRADLE_FLAGS="%s"\n' "$(pocketshell_release_gate_gradle_flags)"
  printf '  POCKETSHELL_TEST_MEM=%s   (build-scope MemoryMax; floor %sG locally)\n' \
    "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT" \
    "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB"
  cat <<'USAGE'
  GATE_ISOLATED_WORKTREE=1
  COMPOSE_FILE=tests/docker/docker-compose.yml
  APK_PATH=app2/build/outputs/apk/debug/app2-debug.apk
  TEST_APK_PATH=app2/build/outputs/apk/androidTest/debug/app2-debug-androidTest.apk
  APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS=3
  APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS=3
  APP_WALKTHROUGH_GL_REBOOT_ATTEMPTS=2
  APP_WALKTHROUGH_GL_REBOOT_BOOT_TIMEOUT_SECONDS=300
  LEGACY_V1_DB_MIGRATION_ATTEMPTS=3
  CORE_TERMINAL_CONNECTED_ATTEMPTS=2
  PRE_RELEASE_MANAGE_EMULATOR=0
  PRE_RELEASE_EMULATOR_START_ARGS="-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save"
  POCKETSHELL_EMULATOR_SG_KVM=auto
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

pocketshell_release_validation_require_run_id "$RUN_ID" ||
  exit "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"

# Arm retention before an isolated child rechecks its authenticated location or
# execution profile. The pre-copy parent is the only disk-budget admission
# authority, but every later child exit must still retain a diagnostic and
# remove the generated copy + owner marker. This early trap is replaced by the
# full summary trap once the child has initialized its detailed gate state.
EARLY_RETENTION_ARMED=0
if [[ -n "${POCKETSHELL_GATE_ISOLATED_COPY:-}" ]]; then
  EARLY_RETENTION_ARMED=1
fi
write_early_release_failure_summary() {
  local exit_status="$1"
  local summary_path="$RUN_DIR/summary.txt"
  local failing_step="isolated-worktree-copy"
  local failure_message="release gate exited while preparing its isolated generated worktree"
  if [[ -n "${POCKETSHELL_GATE_ISOLATED_COPY:-}" ]]; then
    failing_step="isolated-child-initialization"
    failure_message="isolated child exited before the detailed summary was initialized"
  fi
  [[ -s "$summary_path" ]] && return 0
  mkdir -p "$RUN_DIR" || return 1
  {
    printf 'PocketShell pre-release confidence gate summary\n'
    printf 'Generated: %s\n' "$(date -Is)"
    printf 'Result: FAIL\n'
    printf 'Exit status: %s\n' "$exit_status"
    printf 'Run ID: %s\n' "$RUN_ID"
    printf 'Run directory: %s\n' "$RUN_DIR"
    printf 'Failing step: %s\n' "$failing_step"
    printf 'Failure message: %s\n' "$failure_message"
    printf 'Safe cleanup command: scripts/disk-cleanup.sh --apply\n'
  } > "$summary_path"
}
early_release_exit() {
  local exit_status="$?"
  set +e
  pocketshell_release_all
  if [[ "$exit_status" -ne 0 && "$EARLY_RETENTION_ARMED" == "1" ]]; then
    cd "$RUN_DIR" 2>/dev/null || cd "$LOG_ROOT" 2>/dev/null || true
    write_early_release_failure_summary "$exit_status" || true
    pocketshell_release_validation_finish_run "$LOG_ROOT" "$RUN_ID" failure || true
  fi
}
trap early_release_exit EXIT

# Issue #2054: fail on an under-resourced execution profile in the first second,
# not 40 minutes into a compile. Runs before the isolated-worktree rsync (so a
# bad profile does not even pay for the copy) and again in the re-exec'd child,
# which re-derives GRADLE_FLAGS from the exported value.
pocketshell_assert_gradle_execution_profile "pre-release confidence gate" "$GRADLE_FLAGS"
pocketshell_apply_release_gate_scope_memory "pre-release confidence gate"

# Issue #2054: let an operator (or the orchestrator, before committing a machine
# to a ~40 minute run) verify the execution profile on its own. No Gradle, no
# emulator, no AVD lock, no isolated-worktree rsync.
if [[ "${1:-}" == "--check-profile" ]]; then
  printf 'Execution-profile preflight only: no Gradle, no emulator, no AVD lock taken.\n'
  exit 0
fi

# Issue #2055: the generic 10 GiB one-run floor is not large enough for this
# chain's isolated source copy, two variant families, private Gradle home, and
# APK/emulator evidence. Authenticate the exact checkout-owned generated root
# before any retention helper can delete, reclaim only stale generated copies,
# then require the fixed 24 GiB release envelope BEFORE the AVD lock, rsync,
# Docker, or Gradle. LOG_ROOT remains useful for non-release scripts, but it may
# not redirect this destructive release path into source or arbitrary storage.
if [[ -n "${POCKETSHELL_GATE_ISOLATED_COPY:-}" ]]; then
  # The outer checkout already created the release-owned root marker before it
  # copied this child. Re-authenticate that marker and prove this script is
  # executing from exactly <root>/<run>/worktree; never re-anchor the root to
  # the copied checkout (which would incorrectly expect worktree/build/...).
  _pocketshell_release_storage_root "$LOG_ROOT" >/dev/null ||
    exit "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
  expected_isolated_root="$LOG_ROOT/$RUN_ID/worktree"
  if [[ "$ROOT_DIR" != "$expected_isolated_root" ]]; then
    printf 'REFUSING: isolated release child is outside its authenticated generated worktree: %s (expected %s)\n' \
      "$ROOT_DIR" "$expected_isolated_root" >&2
    exit "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
  fi
  if ! pocketshell_release_validation_run_is_active "$LOG_ROOT/$RUN_ID"; then
    printf 'REFUSING: isolated release child lacks the live owner recorded by its admitted parent: %s\n' \
      "$LOG_ROOT/$RUN_ID" >&2
    exit "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
  fi
else
  pocketshell_release_validation_prepare_root "$ROOT_DIR" "$LOG_ROOT" ||
    exit "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
  # This is the one authoritative admission check. The isolated copy consumes
  # part of the already-budgeted 24 GiB envelope, so rechecking the same floor
  # from inside that copy would silently raise the real start threshold.
  pocketshell_release_disk_preflight \
    "$ROOT_DIR" "$LOG_ROOT" "pre-release confidence gate"
fi

pocketshell_acquire_avd_lock "$ROOT_DIR" "${1:-}"
# pocketshell_acquire_avd_lock installs its own EXIT handler. Restore this
# gate's release-aware handler after the real acquisition so both obligations
# survive: early copy failures release the AVD lock first, then retain the
# summary/diagnostics and remove only the authenticated generated worktree.
# Without this composition, a partially successful rsync strands both its copy
# and owner marker because the lock helper has clobbered early_release_exit.
trap early_release_exit EXIT

mkdir -p "$RUN_DIR"

if [[ "$GATE_ISOLATED_WORKTREE" != "0" && -z "${POCKETSHELL_GATE_ISOLATED_COPY:-}" ]]; then
  isolated_root="$RUN_DIR/worktree"
  # A release wrapper owns the copy until every downstream APK consumer has
  # finished. A standalone pre-release run owns it itself. The pid start time
  # closes the stale-marker/PID-reuse hole for later cleanup passes.
  POCKETSHELL_RELEASE_RETENTION_OWNER_PID="${POCKETSHELL_RELEASE_RETENTION_OWNER_PID:-$$}"
  export POCKETSHELL_RELEASE_RETENTION_OWNER_PID
  pocketshell_release_validation_mark_active \
    "$LOG_ROOT" "$RUN_ID" "$POCKETSHELL_RELEASE_RETENTION_OWNER_PID"
  EARLY_RETENTION_ARMED=1
  printf 'Preparing isolated worktree copy: %s\n' "$isolated_root"
  # No trailing slash on `.git`: in a git WORKTREE (docs/release.md cuts every
  # release in one) `.git` is a FILE holding `gitdir: .../worktrees/<name>`,
  # and `--exclude='.git/'` matches directories ONLY, so that file was copied
  # in. The copy then resolved to the real gitdir — it was a live second
  # checkout of the branch being released, so any `git` the gate ran inside
  # the copy could reach the source worktree's index/HEAD, and
  # scripts/derive-version.sh derived a tag-based version there while the
  # Docker `agents` fixture still reports its baked `0.0.0-dev`, hard-failing
  # docker-agents-pocketshell-version. Excluding both shapes makes a
  # worktree-based gate run byte-for-byte equivalent to a plain-checkout one.
  rsync -a --delete \
    --exclude='.git' \
    --exclude='.gradle/' \
    --exclude='build/' \
    "$ROOT_DIR/" "$isolated_root/"
  # Issue #2381: the copy has no git, so scripts/derive-version.sh inside it
  # derived the `0.0.0-dev` / versionCode=1 placeholder — and EVERY APK the
  # release chain validates, journeys against and publishes is built in here.
  # The whole release gate was therefore validating a binary that cannot
  # express a release version: HostBootstrapScenarioSuiteTest's setup-detection
  # profiles compare the app's version against the host CLI's, and at a `0.0.0`
  # core every one of them is meaningless (they only "passed" because the
  # fixture was reset to the same placeholder string, so a string-equality
  # comparison matched two placeholders). Stamp the version the OUTER checkout
  # — the tagged one this gate was invoked on — derives, so the copy is
  # version-identical to its source. `--exclude='.git'` still applies: the copy
  # gets the ANSWER, never the history.
  # `fail()` is not defined this early (it needs $RUN_DIR-scoped summary state),
  # so this uses the same explicit printf+exit shape as the admission checks
  # above; `early_release_exit` is already trapped and does the cleanup.
  if ! bash "$ROOT_DIR/scripts/derive-version.sh" write-pin "$isolated_root" >/dev/null; then
    printf 'REFUSING: could not stamp the isolated worktree copy with %s'"'"'s derived version (issue #2381). Without the pin the copy builds a 0.0.0-dev / versionCode=1 APK and the whole release chain validates, journeys against and publishes a binary that cannot express a release version.\n' \
      "$ROOT_DIR" >&2
    exit 1
  fi
  printf 'Isolated worktree version pin: %s (issue #2381)\n' \
    "$(bash "$isolated_root/scripts/derive-version.sh" both | tr '\n' ' ')"
  export POCKETSHELL_GATE_ISOLATED_COPY=1
  # Normally the outer release checkout is the exact clean/pushed source of
  # truth. Preserve an explicit source root for pre-merge reviewer validation,
  # where the candidate contains only gate-script changes but its JVM evidence
  # must still be tied to the clean pushed base; the acceptor independently
  # enforces clean HEAD == origin/main on whichever root is supplied.
  export POCKETSHELL_GATE_SOURCE_ROOT="${POCKETSHELL_GATE_SOURCE_ROOT:-$ROOT_DIR}"
  # Issue #2054: POCKETSHELL_TEST_MEM must cross into the isolated copy too — the
  # child is what actually invokes scripts/cgroup-run.sh for every heavy step, so
  # without this the build scope silently falls back to scope-run.sh's 8G default.
  export LOG_ROOT RUN_ID GRADLE_USER_HOME GRADLE_FLAGS POCKETSHELL_TEST_MEM
  exec "$isolated_root/scripts/pre-release-confidence-gate.sh" "$@"
fi

source "$ROOT_DIR/scripts/lib/app-version.sh"
APP_VERSION_NAME="$(pocketshell_app_version_name "$ROOT_DIR")"
APP_DATABASE_SCHEMA_VERSION="$(
  sed -nE \
    's/^const val APP_DATABASE_SCHEMA_VERSION = ([0-9]+)$/\1/p' \
    "$ROOT_DIR/shared/core-storage/src/main/java/com/pocketshell/core/storage/AppDatabase.kt"
)"
export POCKETSHELL_AGENT_FIXTURE_VERSION="$APP_VERSION_NAME"

SUMMARY_PATH="$RUN_DIR/summary.txt"
SUMMARY_WRITTEN=0
GATE_RESULT="FAIL"
GATE_RESULT_MESSAGE="FAIL"
FAILURE_MESSAGE=""
FAILING_STEP=""
FAILING_LOG_PATH=""
FAILURE_DIAGNOSTICS_PATH=""
FAILURE_LOGCAT_PATH=""
EMULATOR_SERIAL="unknown"
APP_WALKTHROUGH_INSTALL_STATUS="not_run"
FINAL_INSTALL_STATUS="not_run"
LEGACY_V1_DB_MIGRATION_STATUS="not_run"
# Issue #1314: step 12 (core-terminal burst proof) runs non-fatally so a slow-AVD
# timing red no longer front-gates the #1302 journey; its captured result is
# folded into the final verdict after the downstream stages run.
CONNECTED_TERMINAL_INPUT_STATUS="not_run"
# Issue #2481: where the unfiltered app2 suite's JourneyScreenshots PNGs are
# pulled to. Set for real just after the suite runs; declared here so a failure
# before that point still writes a well-formed summary.
APP2_JOURNEY_SCREENSHOT_DIR="not_collected"
LEGACY_V1_DB_MIGRATION_LOGCAT="$RUN_DIR/legacy-v1-db-migration-logcat.log"
STEP_NAMES=()
STEP_STATUSES=()
STEP_LOGS=()
STEP_COMMANDS=()
# Issue #2064: run_step has always MEASURED every step ("PASS: <name> (Ns)") but
# never kept the numbers, so the gate's own cost stayed unaggregated and the
# release chain was described as "unmeasured" for a year. Keep them and print a
# ranked table in the summary; no new instrumentation, just not throwing the
# existing measurement away.
STEP_SECONDS=()
# Issue #2064: how the unit-test evidence for this release was obtained —
# "reused-ci" (the required `Unit tests` check on this exact SHA, independently
# re-verified against the downloaded result XML) or "local" (the gate ran the
# suite itself). Recorded in the summary either way; a missing/unusable CI
# result always falls back to "local", never to "assumed green".
UNIT_EVIDENCE_MODE="local"
UNIT_EVIDENCE_DETAIL="local Gradle check graph (assembleDebug check -x lint -x lintDebug)"
CI_UNIT_EVIDENCE_DIR="$RUN_DIR/ci-unit-evidence"
APK_IDENTITY_FILE="$RUN_DIR/$POCKETSHELL_APK_IDENTITY_FILE_NAME"
FOCUSED_SELECTORS=("$APP2_INSTRUMENTED_SUITE_LABEL")
FOCUSED_STATUSES=()
FOCUSED_LOGS=()
FOCUSED_DIAGNOSTICS=()
FOCUSED_LOGCATS=()

for _selector in "${FOCUSED_SELECTORS[@]}"; do
  FOCUSED_STATUSES+=("not_run")
  FOCUSED_LOGS+=("")
  FOCUSED_DIAGNOSTICS+=("")
  FOCUSED_LOGCATS+=("")
done
unset _selector

commit_sha() {
  local git_root="${POCKETSHELL_GATE_SOURCE_ROOT:-$ROOT_DIR}"
  git -C "$git_root" rev-parse HEAD 2>/dev/null || printf 'unknown'
}

update_emulator_serial() {
  if [[ -x "$ADB" ]]; then
    EMULATOR_SERIAL="$("$ADB" get-serialno 2>/dev/null | tr -d '\r' || true)"
    [[ -n "$EMULATOR_SERIAL" ]] || EMULATOR_SERIAL="unknown"
  fi
}

set_focused_status() {
  local selector="$1"
  local status="$2"
  local log_file="${3:-}"
  local diagnostics_file="${4:-}"
  local logcat_file="${5:-}"

  local i
  for i in "${!FOCUSED_SELECTORS[@]}"; do
    if [[ "${FOCUSED_SELECTORS[$i]}" == "$selector" ]]; then
      FOCUSED_STATUSES[i]="$status"
      [[ -n "$log_file" ]] && FOCUSED_LOGS[i]="$log_file"
      [[ -n "$diagnostics_file" ]] && FOCUSED_DIAGNOSTICS[i]="$diagnostics_file"
      [[ -n "$logcat_file" ]] && FOCUSED_LOGCATS[i]="$logcat_file"
      return 0
    fi
  done
}

write_summary() {
  local exit_status="${1:-0}"
  if [[ "$SUMMARY_WRITTEN" == "1" ]]; then
    return 0
  fi
  SUMMARY_WRITTEN=1

  update_emulator_serial
  mkdir -p "$RUN_DIR"

  if [[ "$exit_status" -eq 0 && "$GATE_RESULT" == "PASS" ]]; then
    GATE_RESULT_MESSAGE="PASS: pre-release confidence gate completed"
  elif [[ "$GATE_RESULT" != "PASS" ]]; then
    GATE_RESULT="FAIL"
    GATE_RESULT_MESSAGE="FAIL"
  fi

  {
    printf 'PocketShell pre-release confidence gate summary\n'
    printf 'Generated: %s\n' "$(date -Is)"
    printf 'Result: %s\n' "$GATE_RESULT_MESSAGE"
    printf 'Exit status: %s\n' "$exit_status"
    printf 'Commit SHA: %s\n' "$(commit_sha)"
    printf 'Run ID: %s\n' "$RUN_ID"
    printf 'Run directory: %s\n' "$RUN_DIR"
    if [[ -n "${POCKETSHELL_GATE_SOURCE_ROOT:-}" ]]; then
      printf 'Source workspace: %s\n' "$POCKETSHELL_GATE_SOURCE_ROOT"
      printf 'Isolated worktree: %s\n' "$ROOT_DIR"
    fi
    printf 'APK path: %s\n' "$APK_PATH"
    printf 'Test APK path: %s\n' "$TEST_APK_PATH"
    printf 'Emulator serial: %s\n' "$EMULATOR_SERIAL"
    printf 'Docker compose file: %s\n' "$COMPOSE_FILE"
    printf 'Docker profile/service: agents\n'
    printf 'Docker SSH target: 127.0.0.1:2222\n'
    # Issue #2054: the execution profile is release evidence. Three v0.4.42 gate
    # runs died in the build with no artifact naming the heap/scope they used.
    printf 'Gradle flags: %s\n' "$GRADLE_FLAGS"
    printf 'Build scope MemoryMax (POCKETSHELL_TEST_MEM): %s\n' "${POCKETSHELL_TEST_MEM:-unset}"
    # Issue #2064: WHERE the 12,362-test unit evidence for this release came
    # from is release evidence in its own right. "reused-ci" names the run;
    # "local" says the gate executed the suite itself.
    printf 'Unit evidence: %s\n' "$UNIT_EVIDENCE_MODE"
    printf 'Unit evidence detail: %s\n' "$UNIT_EVIDENCE_DETAIL"
    # Issue #2064: the one binary this whole chain validates and ships.
    printf 'APK identity file: %s\n' "$APK_IDENTITY_FILE"
    if [[ -f "$APK_IDENTITY_FILE" ]]; then
      printf 'Validated app APK sha256: %s\n' \
        "$(pocketshell_read_apk_identity_field "$APK_IDENTITY_FILE" app_apk_sha256 2>/dev/null || printf 'unknown')"
      printf 'Validated androidTest APK sha256: %s\n' \
        "$(pocketshell_read_apk_identity_field "$APK_IDENTITY_FILE" test_apk_sha256 2>/dev/null || printf 'unknown')"
    fi
    printf 'Connected core-terminal burst proof (step 12, non-fatal — issue #1314): %s\n' "$CONNECTED_TERMINAL_INPUT_STATUS"
    printf 'app2 journey screenshots: %s\n' "${APP2_JOURNEY_SCREENSHOT_DIR:-not_collected}"
    printf 'Focused app cold-reset APK install status: %s\n' "$APP_WALKTHROUGH_INSTALL_STATUS"
    printf 'Final data-preserving update install status: %s\n' "$FINAL_INSTALL_STATUS"
    printf 'Legacy v1 database migration status: %s\n' "$LEGACY_V1_DB_MIGRATION_STATUS"
    printf 'Legacy v1 database migration logcat: %s\n' "$LEGACY_V1_DB_MIGRATION_LOGCAT"
    if [[ "$GATE_RESULT" != "PASS" ]]; then
      printf 'Failing step: %s\n' "${FAILING_STEP:-unknown}"
      printf 'Failure message: %s\n' "${FAILURE_MESSAGE:-unknown}"
      printf 'Failing step log: %s\n' "${FAILING_LOG_PATH:-unknown}"
      printf 'Failure diagnostics: %s\n' "${FAILURE_DIAGNOSTICS_PATH:-unknown}"
      printf 'Failure logcat: %s\n' "${FAILURE_LOGCAT_PATH:-unknown}"
    fi

    printf '\nSteps:\n'
    local i
    local total_step_seconds=0
    for i in "${!STEP_NAMES[@]}"; do
      printf -- '- name: %s\n' "${STEP_NAMES[$i]}"
      printf '  status: %s\n' "${STEP_STATUSES[$i]}"
      printf '  seconds: %s\n' "${STEP_SECONDS[$i]:-0}"
      printf '  log: %s\n' "${STEP_LOGS[$i]}"
      printf '  command: %s\n' "${STEP_COMMANDS[$i]}"
      total_step_seconds=$((total_step_seconds + ${STEP_SECONDS[$i]:-0}))
    done

    # Issue #2064: the ranked cost table. The gate emitted every one of these
    # numbers already; nobody had ever aggregated them, which is why the local
    # release chain was "unmeasured". Cheapest possible measurement — sort what
    # run_step already timed.
    printf '\nStep timings (slowest first, seconds):\n'
    printf 'Total step seconds: %s\n' "$total_step_seconds"
    for i in "${!STEP_NAMES[@]}"; do
      printf '%s\t%s\t%s\n' "${STEP_SECONDS[$i]:-0}" "${STEP_NAMES[$i]}" "${STEP_STATUSES[$i]}"
    done | sort -rn | while IFS=$'\t' read -r secs name status; do
      if [[ "$total_step_seconds" -gt 0 ]]; then
        printf -- '- %6ss  %5s%%  %-52s %s\n' \
          "$secs" "$((secs * 100 / total_step_seconds))" "$name" "$status"
      else
        printf -- '- %6ss  %5s   %-52s %s\n' "$secs" "n/a" "$name" "$status"
      fi
    done

    printf '\nFocused selectors:\n'
    for i in "${!FOCUSED_SELECTORS[@]}"; do
      printf -- '- selector: %s\n' "${FOCUSED_SELECTORS[$i]}"
      printf '  status: %s\n' "${FOCUSED_STATUSES[$i]}"
      [[ -n "${FOCUSED_LOGS[$i]}" ]] && printf '  log: %s\n' "${FOCUSED_LOGS[$i]}"
      [[ -n "${FOCUSED_DIAGNOSTICS[$i]}" ]] && printf '  diagnostics: %s\n' "${FOCUSED_DIAGNOSTICS[$i]}"
      [[ -n "${FOCUSED_LOGCATS[$i]}" ]] && printf '  logcat: %s\n' "${FOCUSED_LOGCATS[$i]}"
    done
  } > "$SUMMARY_PATH"
}

on_exit() {
  local exit_status="$?"
  set +e
  write_summary "$exit_status"
  pocketshell_release_all
  # A failed isolated run has no shippable APK consumer. Keep the summary,
  # step logs, and small test XML outside the copy, report its size, then remove
  # only the exact generated <run>/worktree. The outer release wrapper performs
  # the same finish after downstream stages on a successful chain.
  if [[ "$exit_status" -ne 0 && -n "${POCKETSHELL_GATE_ISOLATED_COPY:-}" ]]; then
    cd "$RUN_DIR" 2>/dev/null || cd "$LOG_ROOT" 2>/dev/null || true
    pocketshell_release_validation_finish_run "$LOG_ROOT" "$RUN_ID" failure || true
  fi
}

trap on_exit EXIT

log_path_for() {
  local name="$1"
  printf '%s/%02d-%s.log' "$RUN_DIR" "$STEP_INDEX" "$name"
}

print_failure_log_tail() {
  local log_file="$1"
  if [[ -s "$log_file" ]]; then
    printf '\nLast 80 lines from %s:\n' "$log_file" >&2
    tail -n 80 "$log_file" >&2 || true
  else
    printf '\nNo output was captured in %s\n' "$log_file" >&2
  fi
}

STEP_INDEX=0
run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local log_file
  log_file="$(log_path_for "$name")"
  local start_seconds end_seconds elapsed_seconds
  start_seconds="$(date +%s)"
  local command_string=""
  local arg
  local quoted_arg
  for arg in "$@"; do
    printf -v quoted_arg '%q' "$arg"
    command_string+=" $quoted_arg"
  done
  command_string="${command_string# }"

  STEP_NAMES+=("$name")
  STEP_STATUSES+=("running")
  STEP_LOGS+=("$log_file")
  STEP_COMMANDS+=("$command_string")
  STEP_SECONDS+=("0")
  local step_array_index=$((${#STEP_NAMES[@]} - 1))

  printf '\n[%02d] %s\n' "$STEP_INDEX" "$name"
  printf 'Log: %s\n' "$log_file"

  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$name"
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } > "$log_file" 2>&1
  local status=$?
  set -e
  end_seconds="$(date +%s)"
  elapsed_seconds=$((end_seconds - start_seconds))
  STEP_SECONDS[step_array_index]="$elapsed_seconds"

  if [[ "$status" -eq 0 ]]; then
    STEP_STATUSES[step_array_index]="passed"
    printf 'PASS: %s (%ss)\n' "$name" "$elapsed_seconds"
    case "$name" in
      cold-reset-install-app-walkthrough-apks)
        APP_WALKTHROUGH_INSTALL_STATUS="passed"
        ;;
      update-install-debug-apk)
        FINAL_INSTALL_STATUS="passed"
        ;;
      migrate-legacy-v1-databases)
        LEGACY_V1_DB_MIGRATION_STATUS="passed"
        ;;
    esac
  else
    STEP_STATUSES[step_array_index]="failed"
    FAILING_STEP="$name"
    FAILING_LOG_PATH="$log_file"
    FAILURE_MESSAGE="step '$name' failed with status $status"
    printf 'FAIL: %s exited %s after %ss\n' "$name" "$status" "$elapsed_seconds" >&2
    print_failure_log_tail "$log_file"
    case "$name" in
      emulator-readiness)
        FAILURE_MESSAGE="infrastructure readiness failed before connected tests; see emulator-readiness diagnostics"
        FAILURE_DIAGNOSTICS_PATH="$RUN_DIR/emulator-readiness-diagnostics.log"
        ;;
      cold-reset-install-app-walkthrough-apks)
        APP_WALKTHROUGH_INSTALL_STATUS="failed"
        ;;
      update-install-debug-apk)
        FINAL_INSTALL_STATUS="failed"
        ;;
      migrate-legacy-v1-databases)
        LEGACY_V1_DB_MIGRATION_STATUS="failed"
        FAILURE_LOGCAT_PATH="$LEGACY_V1_DB_MIGRATION_LOGCAT"
        ;;
    esac
  fi

  return "$status"
}

# A CI-evidence refusal is an expected fail-closed branch, not a release-gate
# failure: the next step runs the complete local graph. Preserve its timing/log
# while preventing stale failure state from leaking into an otherwise-green
# summary.
mark_step_declined() {
  local name="$1"
  local index
  for (( index=${#STEP_NAMES[@]} - 1; index >= 0; index-- )); do
    if [[ "${STEP_NAMES[index]}" == "$name" ]]; then
      STEP_STATUSES[index]="declined"
      break
    fi
  done
  if [[ "$FAILING_STEP" == "$name" ]]; then
    FAILING_STEP=""
    FAILING_LOG_PATH=""
    FAILURE_MESSAGE=""
    FAILURE_DIAGNOSTICS_PATH=""
    FAILURE_LOGCAT_PATH=""
  fi
}

run_bash_step() {
  local name="$1"
  local script="$2"
  run_step "$name" bash -lc "$script"
}

# Issue #2435: this step now runs :shared:ui-kit:connectedDebugAndroidTest
# alongside :shared:core-terminal's. It is the ONLY lane that can execute
# UiKitPrimitivesTest — app2's own journey lane is :app2:connectedDebugAndroidTest, and
# the shared-module instrumented suites are already this gate's responsibility
# (the 20 :shared:core-terminal instrumented classes are credited nowhere else
# either). Until now UiKitPrimitivesTest executed on NO lane at all, so the
# release job's own #2082 execution-ledger verify reported it NEVER EXECUTED
# and the job could not conclude success, which kept the #2356 validated-rc
# marker unreachable. Measured cost on a swiftshader AVD: 8 tests, 46 s.
#
# NOTE: the body below is an UNQUOTED heredoc, so $, backticks and \ are live.
# Keep prose out of it — a stray backtick becomes a command substitution that
# runs on the release gate.
core_terminal_connected_input_script() {
  cat <<CORE_TERMINAL_SCRIPT
set -euo pipefail

wait_for_android_media_storage() {
  for i in {1..60}; do
    if '$ADB' shell 'mkdir -p /sdcard/Android/media && test -d /sdcard/Android/media' >/dev/null 2>&1; then
      printf 'Android shared media storage is ready: /sdcard/Android/media\n'
      return 0
    fi
    sleep 1
  done
  printf 'Android shared media storage did not become ready: /sdcard/Android/media\n' >&2
  '$ADB' devices >&2 || true
  '$ADB' shell 'ls -ld /sdcard /sdcard/Android /sdcard/Android/media' >&2 || true
  return 1
}

core_terminal_connected_should_retry() {
  local attempt_log="\$1"
  local logcat_file="\$2"
  grep -q 'Starting 0 tests' "\$attempt_log" || return 1
  grep -q 'Test run failed to complete[.] No test results' "\$attempt_log" || return 1
  {
    grep -qi 'Connection refused' "\$attempt_log" ||
      grep -Eq 'adbd[[:space:]].*(connection terminated|offline|read failed)|host-[0-9]+: read failed' "\$logcat_file"
  }
}

wait_for_android_media_storage

for attempt in \$(seq 1 '$CORE_TERMINAL_CONNECTED_ATTEMPTS'); do
  attempt_log='$RUN_DIR/connected-terminal-input-attempt-'\$attempt'.log'
  attempt_logcat='$RUN_DIR/connected-terminal-input-attempt-'\$attempt'-logcat.log'
  printf 'Core-terminal connected test attempt %s/%s\n' "\$attempt" '$CORE_TERMINAL_CONNECTED_ATTEMPTS'
  '$ADB' logcat -c >/dev/null 2>&1 || true

  set +e
  # Issue #2435: shared:ui-kit joins this step (see the note above the function).
  '$ROOT_DIR/scripts/cgroup-run.sh' --unit "pocketshell-pre-release-core-terminal-\$attempt-$$" -- \
    ./gradlew $GRADLE_FLAGS :shared:core-terminal:connectedDebugAndroidTest :shared:ui-kit:connectedDebugAndroidTest --stacktrace 2>&1 | tee "\$attempt_log"
  status=\${PIPESTATUS[0]}
  set -e

  '$ADB' logcat -d -v time -t 5000 > "\$attempt_logcat" 2>&1 || true

  if [ "\$status" -eq 0 ]; then
    exit 0
  fi

  if [ "\$attempt" -lt '$CORE_TERMINAL_CONNECTED_ATTEMPTS' ] &&
    core_terminal_connected_should_retry "\$attempt_log" "\$attempt_logcat"; then
    printf 'Core-terminal connected test produced UTP no-results/transport cleanup failure on attempt %s; retrying.\n' "\$attempt" >&2
    '$ADB' reconnect >/dev/null 2>&1 || true
    '$ADB' wait-for-device >/dev/null 2>&1 || true
    for package in com.termux.view.test com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
    done
    '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
    '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
    wait_for_android_media_storage
    sleep 2
    continue
  fi

  exit "\$status"
done
CORE_TERMINAL_SCRIPT
}

emulator_readiness_script() {
  cat <<READINESS_SCRIPT
set -euo pipefail

diagnostics='$RUN_DIR/emulator-readiness-diagnostics.log'
managed_emulator_log='$RUN_DIR/emulator-readiness-managed-emulator.log'
managed_emulator_pid='$RUN_DIR/emulator-readiness-managed-emulator.pid'
managed_emulator_scope='$RUN_DIR/emulator-readiness-managed-emulator.scope'
manage_emulator='$PRE_RELEASE_MANAGE_EMULATOR'
avd_name='$AVD_NAME'
source '$ROOT_DIR/scripts/lib/scope-run.sh'

list_emulator_processes() {
  ps -eo pid=,comm=,args= |
    awk -v avd="\$avd_name" '
      {
        command_name = \$2
      }
      command_name == "emulator" {
        for (i = 3; i <= NF; i++) {
          if (\$i == "-avd" && (i + 1) <= NF && \$(i + 1) == avd) {
            print
            next
          }
          if (\$i == ("-avd=" avd)) {
            print
            next
          }
        }
      }
      command_name ~ /^qemu-system/ {
        for (i = 3; i <= NF; i++) {
          if (index(\$i, avd) > 0) {
            print
            next
          }
        }
      }
    '
}

record_diagnostics() {
  {
    printf 'timestamp=%s\n' "\$(date -Is)"
    printf 'adb=%s\n' '$ADB'
    printf 'emulator=%s\n' '$EMULATOR'
    printf 'avd=%s\n' "\$avd_name"
    printf 'manage_emulator=%s\n' "\$manage_emulator"
    printf 'managed_start_args=%s\n' '$PRE_RELEASE_EMULATOR_START_ARGS'
    printf 'managed_emulator_pid=%s\n' "\$managed_emulator_pid"
    printf 'managed_emulator_scope=%s\n' "\$managed_emulator_scope"
    printf '\n== adb devices ==\n'
    '$ADB' devices -l || true
    printf '\n== adb get-state ==\n'
    '$ADB' get-state || true
    printf '\n== adb get-serialno ==\n'
    '$ADB' get-serialno || true
    printf '\n== emulator processes ==\n'
    list_emulator_processes || true
    printf '\n== managed emulator log tail ==\n'
    if [ -f "\$managed_emulator_log" ]; then
      tail -n 120 "\$managed_emulator_log" || true
    else
      printf 'no managed emulator log at %s\n' "\$managed_emulator_log"
    fi
  } > "\$diagnostics" 2>&1
}

has_adb_device() {
  '$ADB' devices | awk 'NR > 1 && \$2 == "device" { found = 1 } END { exit found ? 0 : 1 }'
}

has_emulator_process() {
  [ -n "\$(list_emulator_processes)" ]
}

boot_completed() {
  state=\$('$ADB' shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  [ "\$state" = 1 ]
}

start_managed_emulator() {
  if [ "\$manage_emulator" != "1" ]; then
    return 1
  fi
  if has_adb_device || has_emulator_process; then
    return 0
  fi

  printf 'No ADB devices and no emulator process for AVD %s; starting managed emulator.\n' "\$avd_name" >&2
  printf '[%s] starting managed emulator for AVD %s\n' "\$(date -Is)" "\$avd_name" >> "\$managed_emulator_log"
  read -r -a start_args <<< '$PRE_RELEASE_EMULATOR_START_ARGS'
  declare -a emulator_cmd=()
  pocketshell_build_sg_kvm_command emulator_cmd '$EMULATOR' -avd "\$avd_name" "\${start_args[@]}"
  local scope_unit
  scope_unit="pocketshell-pre-release-avd-\$(pocketshell_unit_token "\$avd_name")-\$(pocketshell_unit_token '$RUN_ID')-$$"
  pocketshell_scope_start_background "\$scope_unit" "\$managed_emulator_log" "\$managed_emulator_pid" "\${emulator_cmd[@]}"
  printf '%s.scope\n' "\$scope_unit" > "\$managed_emulator_scope"
}

'$ADB' devices -l || true
start_managed_emulator || true

for i in {1..90}; do
  if boot_completed; then
    record_diagnostics
    printf 'Emulator readiness confirmed: sys.boot_completed=1\n'
    exit 0
  fi
  if ! has_adb_device && ! has_emulator_process; then
    if ! start_managed_emulator; then
      record_diagnostics
      printf 'Infrastructure readiness failure: no ADB devices and no emulator process for AVD %s before connected tests.\n' "\$avd_name" >&2
      printf 'Diagnostics: %s\n' "\$diagnostics" >&2
      exit 2
    fi
  fi
  sleep 2
done

record_diagnostics
printf 'Infrastructure readiness failure: AVD %s did not report sys.boot_completed=1 before connected tests.\n' "\$avd_name" >&2
printf 'Diagnostics: %s\n' "\$diagnostics" >&2
exit 2
READINESS_SCRIPT
}

fail() {
  FAILURE_MESSAGE="$1"
  [[ -n "$FAILING_STEP" ]] || FAILING_STEP="preflight"
  printf '\nFAIL: %s\nLogs: %s\n' "$1" "$RUN_DIR" >&2
  exit 1
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -x "$path" ]] || fail "$label is not executable at $path"
}

require_command_or_executable() {
  local command_or_path="$1"
  local label="$2"
  if [[ "$command_or_path" == */* ]]; then
    require_executable "$command_or_path" "$label"
  elif ! command -v "$command_or_path" >/dev/null 2>&1; then
    fail "$label is not available on PATH as $command_or_path"
  fi
}

# Issue #749, repointed by #2481: the VACUITY guard. Before doing any expensive
# work (compile, emulator boot, APK install) prove that the unfiltered
# instrumentation run below actually has journeys to execute.
#
# The old shape resolved a hardcoded `class#method` list against
# `app/src/androidTest/java`; both the list and that source tree are gone (see
# the note on APP2_INSTRUMENTED_SUITE_LABEL above). An unfiltered `am instrument`
# has no selector that can go stale, but it has the OPPOSITE failure mode the
# #749 guard existed to prevent: on an empty or filtered-to-nothing suite it
# exits `INSTRUMENTATION_CODE: -1` with `OK (0 tests)` and the gate reports a
# green over zero executed tests (docs/ci-pitfalls.md). So the guard now asserts
# app2's instrumented tree is non-empty and still carries journey classes, and
# the run script below separately refuses an `OK (0 tests)` result.
#
# Resolves against ROOT_DIR, which works both in the source workspace and in the
# rsynced isolated worktree copy (app2/src is preserved by the rsync).
assert_app2_instrumented_suite_exists() {
  local androidtest_root="$ROOT_DIR/app2/src/androidTest/java"
  if [[ ! -d "$androidtest_root" ]]; then
    fail "app2 has no androidTest source tree at $androidtest_root; the unfiltered release journey run would execute nothing (issue #2481)"
  fi

  local test_sources journey_classes
  test_sources="$(grep -rlE '^[[:space:]]*@Test\b' "$androidtest_root" --include='*.kt' 2>/dev/null | wc -l)"
  journey_classes="$(find "$androidtest_root" -type f -name 'J*Journey.kt' 2>/dev/null | wc -l)"

  if [[ "$test_sources" -lt 1 ]]; then
    fail "no @Test-carrying source under $androidtest_root; the unfiltered release journey run would execute nothing (issue #2481)"
  fi
  if [[ "$journey_classes" -lt 1 ]]; then
    fail "no J*Journey class under $androidtest_root; the release gate would run a suite with no user journey in it (issue #2481)"
  fi

  # The ledger `--require-class` pin must name a class that still exists, or the
  # release run silently converts nothing and the ledger reports every app2
  # journey as NEVER EXECUTED.
  local ledger_pin_path="$androidtest_root/${APP2_LEDGER_REQUIRED_CLASS//.//}.kt"
  if [[ ! -f "$ledger_pin_path" ]]; then
    fail "the execution-ledger pin class $APP2_LEDGER_REQUIRED_CLASS does not exist at $ledger_pin_path; the release run would record no ledger JUnit XML (issue #2435/#2481)"
  fi

  printf 'app2 instrumented-suite guard passed: %s @Test source file(s), %s J*Journey class(es) under %s; ledger pin %s exists.\n' \
    "$test_sources" "$journey_classes" "$androidtest_root" "$APP2_LEDGER_REQUIRED_CLASS"
}

cold_reset_app_packages_script() {
  cat <<RESET_SCRIPT
set -euo pipefail
package_installed() {
  '$ADB' shell pm path "\$1" >/dev/null 2>&1
}
wait_package_manager_idle() {
  '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
}
for package in com.pocketshell.app.test com.pocketshell.app; do
  '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
done
for package in com.pocketshell.app.test com.pocketshell.app; do
  if package_installed "\$package"; then
    printf 'COLD-RESET: clearing package data without uninstalling: %s\n' "\$package"
    '$ADB' shell pm clear "\$package" || true
  else
    printf 'Package not installed: %s\n' "\$package"
  fi
done
wait_package_manager_idle
for package in com.pocketshell.app.test com.pocketshell.app; do
  '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
done
printf 'COLD-RESET: focused app walkthrough package state reset without package deletion\n'
RESET_SCRIPT
}

cold_reset_install_app_walkthrough_apks_script() {
  cat <<INSTALL_SCRIPT
set -euo pipefail
wait_package_manager_idle() {
  '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
}
packages_present() {
  for package in com.pocketshell.app com.pocketshell.app.test; do
    '$ADB' shell pm path "\$package" >/dev/null || return 1
  done
}
post_install_removal_seen() {
  '$ADB' logcat -d -v time -t 1000 2>/dev/null |
    grep -E 'PACKAGE_FULLY_REMOVED|PACKAGE_REMOVED|deletePackageX' |
    grep -E 'com[.]pocketshell[.]app([.]test)?' >/dev/null
}
uninstall_with_idle_wait() {
  local package="\$1"
  if '$ADB' shell pm path "\$package" >/dev/null 2>&1; then
    printf 'COLD-RESET: uninstall fallback for incompatible package: %s\n' "\$package"
    '$ADB' uninstall "\$package" || true
  fi
  wait_package_manager_idle
  for i in {1..60}; do
    if ! '$ADB' shell pm path "\$package" >/dev/null 2>&1; then
      wait_package_manager_idle
      printf 'Package removed after fallback uninstall: %s\n' "\$package"
      return 0
    fi
    sleep 1
  done
  printf 'Package still installed after fallback uninstall wait: %s\n' "\$package" >&2
  exit 1
}
install_or_fallback_uninstall() {
  local package="\$1"
  local apk="\$2"
  local output
  set +e
  output=\$('$ADB' install -r -d -t "\$apk" 2>&1)
  local status=\$?
  set -e
  printf '%s\n' "\$output"
  if [ "\$status" -eq 0 ]; then
    wait_package_manager_idle
    return 0
  fi
  if printf '%s\n' "\$output" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
    uninstall_with_idle_wait "\$package"
    '$ADB' install -r -d -t "\$apk"
    wait_package_manager_idle
    return 0
  fi
  exit "\$status"
}
install_pair() {
  install_or_fallback_uninstall com.pocketshell.app '$APK_PATH'
  install_or_fallback_uninstall com.pocketshell.app.test '$TEST_APK_PATH'
  wait_package_manager_idle
}
for attempt in {1..3}; do
  printf 'COLD-RESET: focused app walkthrough APK install attempt %s\n' "\$attempt"
  '$ADB' logcat -c || true
  install_pair
  stable=true
  for i in {1..20}; do
    wait_package_manager_idle
    if ! packages_present; then
      printf 'Package disappeared during post-install stability window on attempt %s.\n' "\$attempt" >&2
      stable=false
      break
    fi
    if post_install_removal_seen; then
      printf 'Delayed package removal broadcast seen during post-install stability window on attempt %s.\n' "\$attempt" >&2
      stable=false
      break
    fi
    sleep 1
  done
  if [ "\$stable" = true ]; then
    printf 'COLD-RESET: focused app walkthrough APK install is package-manager idle and stable\n'
    exit 0
  fi
  printf 'Recent package removal context before reinstall:\n' >&2
  '$ADB' logcat -d -v time -t 500 |
    grep -E 'PackageManager|PackageInstaller|PACKAGE_|deletePackageX|com[.]pocketshell[.]app' >&2 || true
  wait_package_manager_idle
  sleep 3
done
printf 'COLD-RESET: focused app walkthrough APK install did not stabilize after retries.\n' >&2
exit 1
INSTALL_SCRIPT
}

quiesce_app_walkthrough_processes_script() {
  cat <<QUIESCE_SCRIPT
set -euo pipefail
packages_stopped() {
  for package in com.pocketshell.app.test com.pocketshell.app; do
    if ! '$ADB' shell dumpsys package "\$package" 2>/dev/null | grep -q 'stopped=true'; then
      return 1
    fi
  done
}
processes_stopped() {
  if ! '$ADB' shell ps -A | grep -E 'com[.]pocketshell[.]app(\$|:|[[:space:]])|com[.]pocketshell[.]app[.]test(\$|:|[[:space:]])' >/dev/null; then
    return 0
  fi
  return 1
}
dump_quiesce_context() {
    printf 'Visible PocketShell processes:\n' >&2
    '$ADB' shell ps -A | grep -E 'com[.]pocketshell[.]app(\$|:|[[:space:]])|com[.]pocketshell[.]app[.]test(\$|:|[[:space:]])' >&2 || true
    printf 'Package paths:\n' >&2
    for package in com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell pm path "\$package" >&2 || true
    done
    printf 'Package stopped state:\n' >&2
    for package in com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell dumpsys package "\$package" 2>/dev/null | grep 'stopped=' >&2 || true
    done
    printf 'Recent package manager and activity context:\n' >&2
    '$ADB' logcat -d -v time -t 500 | grep -E 'PackageManager|PackageInstaller|PACKAGE_|ActivityManager.*com[.]pocketshell[.]app|Force stopping|deletePackageX' >&2 || true
}
for attempt in 1 2 3; do
  for package in com.pocketshell.app.test com.pocketshell.app; do
    '$ADB' shell am force-stop "\$package" || true
  done
  '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
  for i in {1..30}; do
    if processes_stopped && packages_stopped; then
      printf 'PocketShell app/test processes are stopped before focused instrumentation\n'
      break
    fi
    if [ "\$i" -eq 30 ]; then
      printf 'PocketShell app/test processes or package stopped state did not settle before focused instrumentation on attempt %s.\n' "\$attempt" >&2
      dump_quiesce_context
      if [ "\$attempt" -eq 3 ]; then
        exit 1
      fi
      sleep 2
      continue 2
    fi
    sleep 1
  done

  for i in {1..5}; do
    if ! processes_stopped || ! packages_stopped; then
      printf 'PocketShell app/test quiesce state changed during settle window on attempt %s.\n' "\$attempt" >&2
      dump_quiesce_context
      if [ "\$attempt" -eq 3 ]; then
        exit 1
      fi
      sleep 2
      continue 2
    fi
    sleep 1
  done
  printf 'PocketShell app/test force-stop settle window completed before focused instrumentation\n'
  exit 0
done
printf 'PocketShell app/test force-stop settle window did not complete before focused instrumentation\n' >&2
exit 1
QUIESCE_SCRIPT
}

legacy_v1_database_migration_script() {
  cat <<LEGACY_V1_SCRIPT
set -euo pipefail

tagged_db_host='$RUN_DIR/tagged-v030-pocketshell.db'
marker_db_host='$RUN_DIR/issue-261-marker-pocketshell.db'
staged_db_device='/data/local/tmp/legacy-v1-pocketshell.db'
logcat_file='$LEGACY_V1_DB_MIGRATION_LOGCAT'
expected_schema_version='$APP_DATABASE_SCHEMA_VERSION'

wait_package_manager_idle() {
  '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
}

install_or_fallback_uninstall() {
  local output
  set +e
  output=\$('$ADB' install -r -d -t '$APK_PATH' 2>&1)
  local status=\$?
  set -e
  printf '%s\n' "\$output"
  if [ "\$status" -eq 0 ]; then
    wait_package_manager_idle
    return 0
  fi
  if printf '%s\n' "\$output" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
    printf 'LEGACY-V1: uninstall fallback for incompatible app package before migration setup\n'
    '$ADB' uninstall com.pocketshell.app >/dev/null 2>&1 || true
    wait_package_manager_idle
    '$ADB' install -r -d -t '$APK_PATH'
    wait_package_manager_idle
    return 0
  fi
  exit "\$status"
}

adb_output_has_transport_drop_markers() {
  printf '%s\n' "\${1:-}" | grep -Eiq 'device offline|device still connecting|error: closed|error: device .+ not found|no devices/emulators found|connection reset|connection refused|protocol fault|failed to read|read failed|transport.*(offline|error|closed)|adb: failed to'
}

logcat_has_app_crash_signature() {
  [ -f "\$1" ] || return 1
  grep -Eiq 'Room cannot verify|Expected identity hash|Process: com[.]pocketshell[.]app|FATAL EXCEPTION.*com[.]pocketshell[.]app|AndroidRuntime.*com[.]pocketshell[.]app' "\$1"
}

logcat_has_adb_transport_drop_markers() {
  [ -f "\$1" ] || return 1
  grep -Eq 'adbd[[:space:]].*(connection terminated|offline|read failed)|host-[0-9]+: read failed|UiAutomation service owner died' "\$1"
}

should_retry_launch_attempt() {
  [ "\$attempt" -lt '$LEGACY_V1_DB_MIGRATION_ATTEMPTS' ] || return 1
  ! logcat_has_app_crash_signature "\$attempt_logcat_file" || return 1
  if [ "\$start_status" -ne 0 ] && {
    adb_output_has_transport_drop_markers "\$start_output" || logcat_has_adb_transport_drop_markers "\$attempt_logcat_file"
  }; then
    return 0
  fi
  if [ "\$pid_status" -ne 0 ] && {
    adb_output_has_transport_drop_markers "\$pid_output" || logcat_has_adb_transport_drop_markers "\$attempt_logcat_file"
  }; then
    return 0
  fi
  return 1
}

'$PYTHON3' - "\$tagged_db_host" "\$marker_db_host" <<'PY'
import sqlite3
import sys
from pathlib import Path

tagged_path = Path(sys.argv[1])
marker_path = Path(sys.argv[2])
for path in (tagged_path, marker_path):
    path.unlink(missing_ok=True)

tagged = sqlite3.connect(tagged_path)
try:
    tagged.executescript(
        """
        PRAGMA journal_mode=WAL;
        PRAGMA foreign_keys=OFF;
        CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT);
        CREATE TABLE ssh_keys (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            privateKeyPath TEXT NOT NULL,
            hasPassphrase INTEGER NOT NULL,
            createdAt INTEGER NOT NULL
        );
        CREATE TABLE hosts (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            hostname TEXT NOT NULL,
            port INTEGER NOT NULL,
            username TEXT NOT NULL,
            keyId INTEGER NOT NULL,
            maxAutoPort INTEGER NOT NULL,
            skipPortsBelow INTEGER NOT NULL,
            scanIntervalSec INTEGER NOT NULL,
            enabled INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            lastConnectedAt INTEGER,
            tmuxInstalled INTEGER,
            lastBootstrapAt INTEGER,
            pocketshellInstalled INTEGER,
            pocketshellLastDetectedAt INTEGER,
            usageCommandOverride TEXT,
            pathOverride TEXT,
            FOREIGN KEY(keyId) REFERENCES ssh_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_hosts_keyId ON hosts(keyId);
        CREATE TABLE port_remappings (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            remotePort INTEGER NOT NULL,
            localPort INTEGER NOT NULL,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_port_remappings_hostId ON port_remappings(hostId);
        CREATE UNIQUE INDEX index_port_remappings_hostId_remotePort
            ON port_remappings(hostId, remotePort);
        CREATE TABLE port_usage (
            hostId INTEGER NOT NULL,
            remotePort INTEGER NOT NULL,
            clickCount INTEGER NOT NULL,
            totalBytes INTEGER NOT NULL,
            lastUsedAt INTEGER NOT NULL,
            PRIMARY KEY(hostId, remotePort),
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_port_usage_hostId ON port_usage(hostId);
        CREATE TABLE project_roots (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            label TEXT NOT NULL,
            path TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_project_roots_hostId ON project_roots(hostId);
        CREATE UNIQUE INDEX index_project_roots_hostId_path ON project_roots(hostId, path);
        CREATE TABLE sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            name TEXT NOT NULL,
            lastSeenAt INTEGER NOT NULL,
            tags TEXT,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_sessions_hostId ON sessions(hostId);
        CREATE UNIQUE INDEX index_sessions_hostId_name ON sessions(hostId, name);
        CREATE TABLE snippets (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            label TEXT,
            body TEXT NOT NULL,
            kind TEXT NOT NULL,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        );
        CREATE INDEX index_snippets_hostId ON snippets(hostId);
        CREATE TABLE agent_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            paneRef TEXT NOT NULL,
            agent TEXT NOT NULL,
            jsonlPath TEXT,
            detectedAt INTEGER NOT NULL
        );
        CREATE UNIQUE INDEX index_agent_sessions_paneRef ON agent_sessions(paneRef);
        CREATE TABLE ai_api_call_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            timestampMillis INTEGER NOT NULL,
            provider TEXT NOT NULL,
            feature TEXT NOT NULL,
            inputUnits INTEGER NOT NULL,
            outputUnits INTEGER NOT NULL,
            unitCostUsdMillicents INTEGER NOT NULL,
            computedCostUsdMillicents INTEGER NOT NULL,
            metadataJson TEXT
        );
        CREATE INDEX index_ai_api_call_log_timestampMillis ON ai_api_call_log(timestampMillis);
        CREATE INDEX index_ai_api_call_log_provider_feature ON ai_api_call_log(provider, feature);
        CREATE TABLE pending_transcriptions (
            id TEXT NOT NULL,
            audioPath TEXT NOT NULL,
            recordingTimestampMs INTEGER NOT NULL,
            destinationContext TEXT NOT NULL,
            retryCount INTEGER NOT NULL,
            lastErrorMessage TEXT,
            audioByteSize INTEGER NOT NULL,
            createdAtMs INTEGER NOT NULL,
            PRIMARY KEY(id)
        );
        CREATE INDEX index_pending_transcriptions_recordingTimestampMs
            ON pending_transcriptions(recordingTimestampMs);

        INSERT INTO room_master_table(id, identity_hash)
            VALUES(42, '5c2d470ba861de091b4dad454b282704');
        INSERT INTO ssh_keys(id, name, privateKeyPath, hasPassphrase, createdAt)
            VALUES(300, 'gate-v030-key', '/keys/gate-v030', 1, 3000);
        INSERT INTO hosts(
            id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
            scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
            lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
            usageCommandOverride, pathOverride
        ) VALUES(
            301, 'gate-v030-host', 'v030.example.com', 2230, 'alexey', 300,
            13000, 1300, 10, 1, 3001, 3002, 1, 3003, 1, 3004,
            'gate-usage-v030', '/opt/gate-v030/bin'
        );
        INSERT INTO port_remappings(id, hostId, remotePort, localPort)
            VALUES(302, 301, 4300, 14300);
        INSERT INTO port_usage(hostId, remotePort, clickCount, totalBytes, lastUsedAt)
            VALUES(301, 4300, 7, 1747, 3005);
        INSERT INTO project_roots(id, hostId, label, path, createdAt)
            VALUES(304, 301, 'gate-root', '/srv/gate-v030', 3006);
        INSERT INTO snippets(id, hostId, label, body, kind)
            VALUES(303, 301, 'gate-snippet', 'echo gate-v030-preserved', 'command');
        INSERT INTO ai_api_call_log(
            id, timestampMillis, provider, feature, inputUnits, outputUnits,
            unitCostUsdMillicents, computedCostUsdMillicents, metadataJson
        ) VALUES(305, 3007, 'openai', 'whisper', 17, 47, 10, 174, '{"layout":"v030"}');
        INSERT INTO pending_transcriptions(
            id, audioPath, recordingTimestampMs, destinationContext, retryCount,
            lastErrorMessage, audioByteSize, createdAtMs
        ) VALUES(
            'gate-v030-pending', '/audio/gate-v030.wav', 3008,
            'composer', 1, 'offline', 1747, 3009
        );
        PRAGMA user_version = 1;
        """
    )
    for stub_table in ("sessions", "agent_sessions"):
        if tagged.execute(f"SELECT COUNT(*) FROM {stub_table}").fetchone()[0] != 0:
            raise SystemExit(f"tagged-v030 fixture: {stub_table} must be empty")
    room_metadata = tagged.execute(
        "SELECT id, identity_hash FROM room_master_table ORDER BY id"
    ).fetchall()
    if room_metadata != [(42, "5c2d470ba861de091b4dad454b282704")]:
        raise SystemExit(
            f"tagged-v030 fixture: unexpected Room metadata {room_metadata!r}"
        )
    tagged.commit()
finally:
    tagged.close()

marker = sqlite3.connect(marker_path)
try:
    if marker.execute("PRAGMA journal_mode=WAL").fetchone()[0].lower() != "wal":
        raise SystemExit("issue-261 marker fixture failed to enter WAL mode")
    marker.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
    marker.execute(
        "INSERT INTO room_master_table (id, identity_hash) VALUES(42, ?)",
        ("4a479a15dfcab2d576e00c7ce10ac581",),
    )
    marker.execute("CREATE TABLE stale_issue_261_marker (id INTEGER PRIMARY KEY)")
    if marker.execute("SELECT COUNT(*) FROM stale_issue_261_marker").fetchone()[0] != 0:
        raise SystemExit("issue-261 marker fixture must be data-free")
    marker.execute("PRAGMA user_version = 1")
    marker.commit()
finally:
    marker.close()
PY

rm -f "\$logcat_file"

run_migration_scenario() {
  scenario="\$1"
  source_db="\$2"
  migrated_db_dir_host='$RUN_DIR/migrated-'"\$scenario"'-database'
  migrated_db_host="\$migrated_db_dir_host/pocketshell.db"

  '$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true
  install_or_fallback_uninstall
  printf 'LEGACY-V1: clearing app data before injecting %s fixture\n' "\$scenario"
  '$ADB' shell pm clear com.pocketshell.app
  wait_package_manager_idle
  '$ADB' push "\$source_db" "\$staged_db_device"
  '$ADB' shell run-as com.pocketshell.app sh -c "'mkdir -p databases && cp \$staged_db_device databases/pocketshell.db && chmod 600 databases/pocketshell.db && rm -f databases/pocketshell.db-wal databases/pocketshell.db-shm databases/pocketshell.db-journal'"
  '$ADB' shell rm -f "\$staged_db_device" >/dev/null 2>&1 || true

  for attempt in \$(seq 1 '$LEGACY_V1_DB_MIGRATION_ATTEMPTS'); do
    attempt_logcat_file='$RUN_DIR/legacy-v1-'"\$scenario"'-attempt'"\$attempt"'.log'
    rm -f "\$attempt_logcat_file"
    '$ADB' logcat -c || true
    '$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true

    set +e
    start_output=\$('$ADB' shell am start -W -n com.pocketshell.app/com.pocketshell.next.MainActivity 2>&1)
    start_status=\$?
    set -e
    printf '%s\n' "\$start_output"

    if [ "\$start_status" -eq 0 ]; then
      sleep 5
    else
      sleep 2
    fi

    set +e
    pid_output=\$('$ADB' shell pidof com.pocketshell.app 2>&1)
    pid_status=\$?
    set -e
    pid=""
    if [ "\$pid_status" -eq 0 ]; then
      pid=\$(printf '%s\n' "\$pid_output" | tr -d '\r' | awk '/^[[:space:]]*[0-9]+[[:space:]]*$/ { print \$1; exit }')
    fi
    '$ADB' logcat -d -v time -t 5000 > "\$attempt_logcat_file" 2>&1 || true
    {
      printf '\n===== %s attempt %s =====\n' "\$scenario" "\$attempt"
      cat "\$attempt_logcat_file"
    } >> "\$logcat_file"

    if logcat_has_app_crash_signature "\$attempt_logcat_file"; then
      printf 'Crash signature found after launching the %s legacy-v1 fixture.\n' "\$scenario" >&2
      grep -Ei -C 40 'Room cannot verify|Expected identity hash|AndroidRuntime|FATAL EXCEPTION|com[.]pocketshell' "\$attempt_logcat_file" >&2 || true
      exit 1
    fi

    if [ "\$start_status" -ne 0 ]; then
      if should_retry_launch_attempt; then
        printf 'Legacy-v1 %s launch was interrupted by adb transport on attempt %s; retrying.\n' "\$scenario" "\$attempt" >&2
        '$ADB' reconnect >/dev/null 2>&1 || true
        '$ADB' wait-for-device >/dev/null 2>&1 || true
        sleep 2
        continue
      fi
      printf 'Launching PocketShell with the %s legacy-v1 fixture failed with status %s.\n' "\$scenario" "\$start_status" >&2
      printf '%s\n' "\$start_output" >&2
      exit "\$start_status"
    fi

    if [ -z "\$pid" ]; then
      if should_retry_launch_attempt; then
        printf 'Legacy-v1 %s pid check was interrupted by adb transport on attempt %s; retrying.\n' "\$scenario" "\$attempt" >&2
        '$ADB' reconnect >/dev/null 2>&1 || true
        '$ADB' wait-for-device >/dev/null 2>&1 || true
        sleep 2
        continue
      fi
      printf 'PocketShell process was not alive after the %s legacy-v1 migration.\n' "\$scenario" >&2
      exit 1
    fi

    break
  done

  '$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true
  '$ADB' shell sync >/dev/null 2>&1 || true
  mkdir -p "\$migrated_db_dir_host"
  rm -f \
    "\$migrated_db_dir_host/pocketshell.db" \
    "\$migrated_db_dir_host/pocketshell.db-wal" \
    "\$migrated_db_dir_host/pocketshell.db-shm"
  for suffix in '' '-wal' '-shm'; do
    pulled_file="\$migrated_db_dir_host/pocketshell.db\$suffix"
    set +e
    '$ADB' exec-out run-as com.pocketshell.app \
      cat "databases/pocketshell.db\$suffix" > "\$pulled_file" 2>/dev/null
    pull_status=\$?
    set -e
    if [ -z "\$suffix" ]; then
      if [ "\$pull_status" -ne 0 ] || [ ! -s "\$pulled_file" ]; then
        printf 'Migrated database pull was empty for %s.\n' "\$scenario" >&2
        exit 1
      fi
    elif [ "\$pull_status" -ne 0 ] || [ ! -s "\$pulled_file" ]; then
      rm -f "\$pulled_file"
    fi
  done
  if [ ! -s "\$migrated_db_host" ]; then
    printf 'Migrated database pull was empty for %s.\n' "\$scenario" >&2
    exit 1
  fi
  printf 'LEGACY-V1: pulled authoritative stopped-app SQLite bundle for %s:' "\$scenario"
  for bundle_file in "\$migrated_db_dir_host"/pocketshell.db*; do
    printf ' %s' "\$(basename "\$bundle_file")"
  done
  printf '\n'

  '$PYTHON3' - "\$migrated_db_host" "\$scenario" "\$expected_schema_version" <<'PY'
import sqlite3
import sys

database_path, scenario, expected_version = sys.argv[1], sys.argv[2], int(sys.argv[3])
connection = sqlite3.connect(f"file:{database_path}?mode=ro", uri=True)
try:
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    if version != expected_version:
        raise SystemExit(f"{scenario}: expected schema v{expected_version}, found v{version}")
    tables = {
        row[0]
        for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")
    }
    if "sessions" in tables or "agent_sessions" in tables:
        raise SystemExit(f"{scenario}: v17 session stubs were not removed")
    if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
        raise SystemExit(f"{scenario}: foreign-key validation failed")

    if scenario == "tagged-v030":
        expected = [
            (
                """
                SELECT id, name, privateKeyPath, hasPassphrase, createdAt, fingerprint
                FROM ssh_keys WHERE id = 300
                """,
                (300, "gate-v030-key", "/keys/gate-v030", 1, 3000, ""),
            ),
            (
                """
                SELECT id, name, hostname, port, username, keyId, maxAutoPort,
                       skipPortsBelow, scanIntervalSec, enabled, createdAt,
                       lastConnectedAt, tmuxInstalled, lastBootstrapAt,
                       pocketshellInstalled, pocketshellLastDetectedAt,
                       pocketshellCliVersion, pocketshellExpectedCliVersion,
                       pocketshellVersionCompatible, pocketshellDaemonRunning,
                       pocketshellDaemonEnabled, usageCommandOverride
                FROM hosts WHERE id = 301
                """,
                (
                    301, "gate-v030-host", "v030.example.com", 2230, "alexey", 300,
                    13000, 1300, 10, 1, 3001, 3002, 1, 3003, 1, 3004,
                    None, None, None, None, None, "gate-usage-v030",
                ),
            ),
            (
                "SELECT id, hostId, remotePort, localPort FROM port_remappings WHERE id = 302",
                (302, 301, 4300, 14300),
            ),
            (
                "SELECT id, hostId, label, body, kind FROM snippets WHERE id = 303",
                (303, 301, "gate-snippet", "echo gate-v030-preserved", "command"),
            ),
            (
                "SELECT id, hostId, label, path, createdAt FROM project_roots WHERE id = 304",
                (304, 301, "gate-root", "/srv/gate-v030", 3006),
            ),
            (
                """
                SELECT id, timestampMillis, provider, feature, inputUnits, outputUnits,
                       unitCostUsdMillicents, computedCostUsdMillicents, metadataJson
                FROM ai_api_call_log WHERE id = 305
                """,
                (305, 3007, "openai", "whisper", 17, 47, 10, 174, '{"layout":"v030"}'),
            ),
            (
                """
                SELECT hostId, remotePort, clickCount, totalBytes, lastUsedAt
                FROM port_usage WHERE hostId = 301 AND remotePort = 4300
                """,
                (301, 4300, 7, 1747, 3005),
            ),
            (
                """
                SELECT id, audioPath, recordingTimestampMs, destinationContext,
                       retryCount, lastErrorMessage, audioByteSize, createdAtMs
                FROM pending_transcriptions WHERE id = 'gate-v030-pending'
                """,
                (
                    "gate-v030-pending", "/audio/gate-v030.wav", 3008,
                    "composer", 1, "offline", 1747, 3009,
                ),
            ),
        ]
        for query, wanted in expected:
            row = connection.execute(query).fetchone()
            if row != wanted:
                raise SystemExit(
                    f"{scenario}: sentinel mismatch for {query!r}; expected {wanted!r}, got {row!r}"
                )
        host_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(hosts)")
        }
        if "pathOverride" in host_columns:
            raise SystemExit(f"{scenario}: dropped pathOverride column unexpectedly survived")
        for table_name in (
            "ssh_keys",
            "hosts",
            "port_remappings",
            "snippets",
            "project_roots",
            "ai_api_call_log",
            "port_usage",
            "pending_transcriptions",
        ):
            count = connection.execute(f"SELECT COUNT(*) FROM {table_name}").fetchone()[0]
            if count != 1:
                raise SystemExit(
                    f"{scenario}: expected exactly one writer-backed sentinel in "
                    f"{table_name}, found {count}"
                )
    elif scenario == "issue-261-marker":
        if "stale_issue_261_marker" in tables:
            raise SystemExit(f"{scenario}: legacy marker still exists after migration")
        if "hosts" not in tables:
            raise SystemExit(f"{scenario}: current hosts table was not created")
    else:
        raise SystemExit(f"unknown validation scenario: {scenario}")
finally:
    connection.close()
PY

  printf 'LEGACY-V1: %s migrated to schema v%s with post-launch data validation; pid was %s\n' \
    "\$scenario" "\$expected_schema_version" "\$pid"
}

run_migration_scenario "tagged-v030" "\$tagged_db_host"
run_migration_scenario "issue-261-marker" "\$marker_db_host"
printf 'Legacy-v1 migration logcat artifact: %s\n' "\$logcat_file"
LEGACY_V1_SCRIPT
}

safe_step_name() {
  printf '%s' "$1" | tr '#.' '--'
}

# Issue #2481: runs app2's WHOLE instrumented set through `am instrument` with
# NO `-e class` filter, against the exact validated APK pair this gate installed
# (issue #2064). One process for the whole set, matching the app2 journey lane's
# deliberate shape (issue #2474) — see APP2_INSTRUMENTED_SUITE_LABEL above.
run_app2_instrumented_suite_script() {
  local diagnostics_file="$1"
  local full_logcat_file="$2"
  cat <<RUN_SCRIPT
set -euo pipefail

diagnostics_file='$diagnostics_file'
full_logcat_file='$full_logcat_file'

dump_instrumentation_diagnostics() {
  local reason="\$1"
  {
    printf 'Instrumentation failure reason: %s\n' "\$reason"
    printf 'Suite: %s (unfiltered app2 instrumented set)\n\n' '$APP2_INSTRUMENTED_SUITE_LABEL'
    printf '=== instrumentation output ===\n'
    printf '%s\n\n' "\${output:-<no instrumentation output captured>}"
    printf '=== filtered logcat crash context ===\n'
    if [ -f "\$full_logcat_file" ]; then
      grep -E -C 80 \
        'AndroidRuntime|FATAL EXCEPTION|FATAL SIGNAL|Process: com[.]pocketshell[.]app|ActivityManager.*(Crash|Killing|Force stopping).*com[.]pocketshell[.]app|am_crash|TestRunner|AndroidJUnitRunner|Instrumentation' \
        "\$full_logcat_file" || true
    else
      printf 'Full logcat artifact was not created: %s\n' "\$full_logcat_file"
    fi
    printf '\n=== latest dropbox app crash entries ===\n'
    '$ADB' shell dumpsys dropbox --print data_app_crash system_app_crash 2>/dev/null | tail -n 500 || true
    printf '\n=== tombstone listing ===\n'
    '$ADB' shell ls -lt /data/tombstones 2>/dev/null | head -20 || true
  } | tee "\$diagnostics_file"
  printf 'Instrumentation diagnostics: %s\n' "\$diagnostics_file"
  printf 'Full logcat: %s\n' "\$full_logcat_file"
}

instrumentation_output_has_failure_markers() {
  # STATUS_CODE -1/-2 (STATUS_ERROR/STATUS_FAILURE) are real failures.
  # -3 (STATUS_IGNORED, an @Ignore'd/quarantined test per D36) and -4
  # (STATUS_ASSUMPTION_FAILURE) are not: JUnit itself does not count either as
  # a failure, and this suite carries deliberately-quarantined tests (#2478)
  # whose whole point is to NOT gate anything. Matching every negative code
  # made a clean "0 failed, 2 ignored" run indistinguishable from a real
  # failure, which is exactly the false failure this run reproduced.
  printf '%s\n' "\$output" | grep -Eq '(^FAILURES!!!$|^FAILURE: |^INSTRUMENTATION_STATUS_CODE: -[12]$|^INSTRUMENTATION_STATUS: stack=|^[[:space:]]*at (com[.]pocketshell|androidx[.]test|org[.]junit|kotlin[.]|java[.]|android[.])|^[[:alnum:]_.]*(Exception|Error): |^Process crashed[.])'
}

logcat_has_app_or_test_failure_markers() {
  grep -Eq 'Process: com[.]pocketshell[.]app|FATAL EXCEPTION.*com[.]pocketshell[.]app|FATAL SIGNAL.*com[.]pocketshell[.]app|AndroidRuntime.*com[.]pocketshell[.]app|(^|[[:space:]])FAILURES!!!($|[[:space:]])|INSTRUMENTATION_STATUS: stack=|INSTRUMENTATION_RESULT: shortMsg=Process crashed' "\$full_logcat_file"
}

logcat_has_adb_transport_drop_markers() {
  grep -Eq 'adbd[[:space:]].*(connection terminated|offline|read failed)|host-[0-9]+: read failed|UiAutomation service owner died' "\$full_logcat_file"
}

# Issue #449: the emulator GL init flake. When the runner's emulator process
# fails to bring up its GL surface ('Failed to initialize 101010-2 format'),
# the app's Compose Activity launches but never renders a hierarchy, and the
# AndroidX Compose test framework aborts with "No compose hierarchies found".
# This is a per-emulator-process driver fault, NOT an app rendering defect, so
# the only recovery is a fresh emulator boot. We require the compose-hierarchy
# failure to be present (in instrumentation output OR logcat) so a genuine
# Compose regression that legitimately throws this is never matched without
# also having the GL marker reproduce across a fresh boot.
instrumentation_has_gl_compose_failure_signature() {
  # The authoritative app-level signature: Compose surface never rendered.
  if printf '%s\n' "\$output" |
    grep -Eq 'No compose hierarchies found|the Activity that calls setContent did not launch'; then
    return 0
  fi
  if [ -f "\$full_logcat_file" ] &&
    grep -Eq 'No compose hierarchies found|the Activity that calls setContent did not launch' "\$full_logcat_file"; then
    return 0
  fi
  return 1
}

logcat_has_gl_init_failure_marker() {
  [ -f "\$full_logcat_file" ] || return 1
  grep -Eq 'Failed to initialize 101010-2 format|eglCreateWindowSurface|eglMakeCurrent.*error|emugl: .*EGL|OpenGLRenderer.*Unable to (match|create) the EGL|Failed to choose config' "\$full_logcat_file"
}

# Treat the failure as the GL flake only when the Compose-never-rendered
# signature is present. The EGL/101010-2 logcat marker is corroborating
# evidence we log when available, but its absence (logcat can roll the line
# out of the captured window) does not block the reboot recovery: the
# compose-hierarchy signature alone, distinct from any real assertion failure,
# is specific enough.
should_recover_gl_init_failure() {
  instrumentation_has_gl_compose_failure_signature
}

cold_reboot_emulator_for_gl_recovery() {
  local boot_timeout='$APP_WALKTHROUGH_GL_REBOOT_BOOT_TIMEOUT_SECONDS'
  printf 'Cold-rebooting the emulator to recover from the GL init flake (fresh EGL surface on next boot).\n' >&2
  if logcat_has_gl_init_failure_marker; then
    printf 'Corroborating EGL/101010-2 GL init failure marker found in logcat.\n' >&2
  else
    printf 'No EGL/101010-2 logcat marker in the captured window; recovering on the compose-hierarchy signature alone.\n' >&2
  fi
  for package in com.pocketshell.app.test com.pocketshell.app; do
    '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
  done
  '$ADB' reboot >/dev/null 2>&1 || true
  # The transport drops while the device reboots; reconnect and wait for it
  # to come back, then block on a fully completed boot before retrying.
  '$ADB' wait-for-device >/dev/null 2>&1 || true
  local waited=0
  local boot_state=""
  while [ "\$waited" -lt "\$boot_timeout" ]; do
    '$ADB' reconnect >/dev/null 2>&1 || true
    '$ADB' wait-for-device >/dev/null 2>&1 || true
    boot_state=\$('$ADB' shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [ "\$boot_state" = "1" ]; then
      printf 'Emulator reported sys.boot_completed=1 after %s seconds of GL-recovery reboot.\n' "\$waited" >&2
      # Dismiss any boot keyguard so the relaunched activity is visible.
      '$ADB' shell wm dismiss-keyguard >/dev/null 2>&1 || true
      '$ADB' shell input keyevent 82 >/dev/null 2>&1 || true
      '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
      '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
      return 0
    fi
    sleep 3
    waited=\$((waited + 3))
  done
  printf 'Emulator did not report sys.boot_completed=1 within %s seconds after GL-recovery reboot.\n' "\$boot_timeout" >&2
  return 1
}

should_retry_interrupted_instrumentation() {
  [ "\$instrument_status" -eq 255 ] || return 1
  printf '%s\n' "\$output" | grep -q 'INSTRUMENTATION_CODE: -1' && return 1
  instrumentation_output_has_failure_markers && return 1
  logcat_has_app_or_test_failure_markers && return 1
  logcat_has_adb_transport_drop_markers
}

'$ADB' logcat -c || true
attempt=1
transport_recovery_attempts=0
gl_reboot_recovery_attempts=0
app_walkthrough_instrumentation_attempts='$APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS'
max_transport_recovery_attempts='$APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS'
max_gl_reboot_recovery_attempts='$APP_WALKTHROUGH_GL_REBOOT_ATTEMPTS'
max_instrumentation_runs=\$(( app_walkthrough_instrumentation_attempts + max_transport_recovery_attempts + max_gl_reboot_recovery_attempts ))
while [ "\$attempt" -le "\$max_instrumentation_runs" ]; do
  '$ADB' logcat -c || true
  set +e
  output=\$('$ADB' shell am instrument -w -r com.pocketshell.app.test/com.pocketshell.next.HiltNextTestRunner 2>&1)
  instrument_status=\$?
  set -e
  if [ "\$instrument_status" -ne 0 ]; then
    sleep 2
  fi
  '$ADB' logcat -d -v time -t 5000 > "\$full_logcat_file" 2>&1 || true
  printf '%s\n' "\$output"
  if [ "\$instrument_status" -eq 0 ] &&
    printf '%s\n' "\$output" | grep -q 'INSTRUMENTATION_CODE: -1' &&
    ! instrumentation_output_has_failure_markers; then
    # Issue #2481 / G3: an unfiltered run has no selector that can go stale, so
    # the way it lies is 'OK (0 tests)' — a green over nothing
    # (docs/ci-pitfalls.md). Refuse it here rather than let the gate publish it.
    if printf '%s\n' "\$output" | grep -Eq '^OK \\(0 tests\\)'; then
      dump_instrumentation_diagnostics "the app2 instrumented suite reported OK (0 tests): the run executed nothing, so it proves nothing"
      exit 1
    fi
    if ! printf '%s\n' "\$output" | grep -Eq '^OK \\([1-9][0-9]* tests?\\)'; then
      dump_instrumentation_diagnostics "the app2 instrumented suite did not report an 'OK (N tests)' summary with N >= 1"
      exit 1
    fi
    printf '%s\n' "\$output" | grep -E '^OK \\([0-9]+ tests?\\)'
    exit 0
  fi
  if [ "\$attempt" -eq 1 ] &&
    { printf '%s\n' "\$output" | grep -q 'Process crashed'; } &&
    grep -q 'Crash of app com[.]pocketshell[.]app running instrumentation' "\$full_logcat_file"; then
    cp "\$full_logcat_file" "\$full_logcat_file.attempt1" || true
    printf 'Focused instrumentation crashed after external app force-stop; retrying selector once.\n' >&2
    for package in com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
    done
    '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
    '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
    sleep 2
    attempt=\$((attempt + 1))
    continue
  fi
  if should_retry_interrupted_instrumentation &&
    [ "\$transport_recovery_attempts" -lt "\$max_transport_recovery_attempts" ]; then
    transport_recovery_attempts=\$((transport_recovery_attempts + 1))
    cp "\$full_logcat_file" "\$full_logcat_file.attempt\$attempt" || true
    printf '%s\n' "\$output" > "\$diagnostics_file.attempt\$attempt-output" || true
    printf 'Focused instrumentation interrupted by adb transport drop on attempt %s; recovery %s/%s; retrying selector without treating it as an app/test retry.\n' "\$attempt" "\$transport_recovery_attempts" "\$max_transport_recovery_attempts" >&2
    '$ADB' reconnect >/dev/null 2>&1 || true
    timeout 60s '$ADB' wait-for-device >/dev/null 2>&1 || true
    for package in com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
    done
    '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
    '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
    sleep 2
    attempt=\$((attempt + 1))
    continue
  fi
  if should_recover_gl_init_failure &&
    [ "\$gl_reboot_recovery_attempts" -lt "\$max_gl_reboot_recovery_attempts" ]; then
    gl_reboot_recovery_attempts=\$((gl_reboot_recovery_attempts + 1))
    cp "\$full_logcat_file" "\$full_logcat_file.gl-attempt\$attempt" || true
    printf '%s\n' "\$output" > "\$diagnostics_file.gl-attempt\$attempt-output" || true
    printf 'Focused instrumentation hit the emulator GL init flake (no compose hierarchies) on attempt %s; GL reboot recovery %s/%s; cold-rebooting the emulator and retrying the selector without treating it as an app/test retry.\n' "\$attempt" "\$gl_reboot_recovery_attempts" "\$max_gl_reboot_recovery_attempts" >&2
    if ! cold_reboot_emulator_for_gl_recovery; then
      dump_instrumentation_diagnostics "emulator GL-recovery reboot did not reach sys.boot_completed=1 after the 101010-2 / no-compose-hierarchies flake on attempt \$attempt"
      exit 1
    fi
    sleep 2
    attempt=\$((attempt + 1))
    continue
  fi
  if [ "\$instrument_status" -ne 0 ]; then
    if should_retry_interrupted_instrumentation; then
      dump_instrumentation_diagnostics "adb shell am instrument exited with status \$instrument_status after \$transport_recovery_attempts transport-only recoveries"
    else
      dump_instrumentation_diagnostics "adb shell am instrument exited with status \$instrument_status"
    fi
    exit "\$instrument_status"
  fi
  if printf '%s\n' "\$output" | grep -q 'INSTRUMENTATION_CODE: -1' &&
    instrumentation_output_has_failure_markers; then
    dump_instrumentation_diagnostics "instrumentation reported INSTRUMENTATION_CODE: -1 with failure markers"
    exit 1
  fi
  dump_instrumentation_diagnostics "instrumentation did not report INSTRUMENTATION_CODE: -1"
  exit 1
done

dump_instrumentation_diagnostics "instrumentation exhausted \$max_instrumentation_runs attempts including \$transport_recovery_attempts transport-only recoveries"
exit 1
RUN_SCRIPT
}

docker_agents_pocketshell_version_script() {
  local expected_version="$1"
  local expected_output
  local quoted_ssh_key
  local quoted_expected_output
  expected_output="$(pocketshell_agent_fixture_version_output "$expected_version")"
  printf -v quoted_ssh_key '%q' "$SSH_KEY"
  printf -v quoted_expected_output '%q' "$expected_output"

  cat <<SCRIPT
set -euo pipefail
chmod 600 $quoted_ssh_key
output=\$(ssh -i $quoted_ssh_key -p 2222 -o BatchMode=yes -o ConnectTimeout=3 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 'pocketshell --version')
printf '%s\n' "\$output"
expected_output=$quoted_expected_output
if [ "\$output" != "\$expected_output" ]; then
  printf 'expected Docker pocketshell fixture version output exactly: %s\n' "\$expected_output" >&2
  exit 1
fi
SCRIPT
}

printf 'PocketShell pre-release confidence gate\n'
printf 'Run directory: %s\n' "$RUN_DIR"
if [[ -n "${POCKETSHELL_GATE_SOURCE_ROOT:-}" ]]; then
  printf 'Source workspace: %s\n' "$POCKETSHELL_GATE_SOURCE_ROOT"
  printf 'Isolated worktree: %s\n' "$ROOT_DIR"
fi
printf 'Android SDK: %s\n' "$ANDROID_SDK"
printf 'ADB: %s\n' "$ADB"
printf 'Emulator: %s\n' "$EMULATOR"
printf 'AVD: %s\n' "$AVD_NAME"
printf 'App versionName: %s\n' "$APP_VERSION_NAME"
printf 'Gradle user home: %s\n' "$GRADLE_USER_HOME"
printf 'Gradle flags: %s\n' "$GRADLE_FLAGS"
read -r -a GRADLE_ARGS <<< "$GRADLE_FLAGS"
printf 'Manage emulator during readiness: %s\n' "$PRE_RELEASE_MANAGE_EMULATOR"

require_executable "$ADB" "adb"
require_executable "$EMULATOR" "emulator"
require_command_or_executable "$PYTHON3" "python3"

if ! [[ "$APP_DATABASE_SCHEMA_VERSION" =~ ^[1-9][0-9]*$ ]]; then
  fail "Could not read APP_DATABASE_SCHEMA_VERSION from AppDatabase.kt"
fi
if ! [[ "$APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  fail "APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS must be a positive integer"
fi
if ! [[ "$APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS" =~ ^[0-9]+$ ]]; then
  fail "APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS must be a non-negative integer"
fi
if ! [[ "$LEGACY_V1_DB_MIGRATION_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  fail "LEGACY_V1_DB_MIGRATION_ATTEMPTS must be a positive integer"
fi
if ! [[ "$APP_WALKTHROUGH_GL_REBOOT_ATTEMPTS" =~ ^[0-9]+$ ]]; then
  fail "APP_WALKTHROUGH_GL_REBOOT_ATTEMPTS must be a non-negative integer"
fi
if ! [[ "$APP_WALKTHROUGH_GL_REBOOT_BOOT_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  fail "APP_WALKTHROUGH_GL_REBOOT_BOOT_TIMEOUT_SECONDS must be a positive integer"
fi

# Issue #749, repointed by #2481: the cheap vacuity guard runs before the heavy
# steps so an emptied app2 androidTest tree fails fast with a clear message
# instead of producing a green 'OK (0 tests)' deep inside the run.
assert_app2_instrumented_suite_exists

run_step "android-sdk-paths" "$ADB" version
run_step "available-avds" "$EMULATOR" -list-avds
if ! "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
  fail "AVD '$AVD_NAME' was not listed by $EMULATOR -list-avds"
fi

# Issue #2064: SHA-matched reuse of the required `Unit tests` evidence.
#
# MEASURED DUPLICATION. In release run 20260809-v0442-r3 (`Commit SHA
# 0248d0ee`) this gate's `gradle-compile-unit` step cost 1083s of a 2516s
# chain, and 890s of that (82%) was unit-test compile + execution: 12,362 tests
# re-run against source the required `Unit tests` check had already tested green
# on the very same commit. Task extraction from that run's console confirmed
# `check` wires no custom guard tasks here, so `assembleDebug check` is exactly
# `assembleDebug` + `./gradlew test`.
#
# AND CI IS STRICTER. The `unit` job runs `--rerun-tasks --no-build-cache` and
# then the #1646 executed-count floors + freshness + FROM-CACHE console scan;
# the local `check` applies none of those. Reusing that result RAISES the bar.
#
# NON-FATAL BY CONSTRUCTION. This step declines — loudly — on a dirty tree, an
# unpushed HEAD, a missing/red/foreign-SHA check run, a weakened workflow, or
# result XML that does not independently satisfy the floors. A decline means
# the gate runs the whole suite locally below. A missing CI result can never
# read as a pass.
if run_step "reuse-ci-unit-evidence" \
  "$ROOT_DIR/scripts/reuse-ci-unit-evidence.sh" \
    --source-root "${POCKETSHELL_GATE_SOURCE_ROOT:-$ROOT_DIR}" \
    --tree-root "$ROOT_DIR" \
    --out-dir "$CI_UNIT_EVIDENCE_DIR"; then
  UNIT_EVIDENCE_MODE="reused-ci"
  UNIT_EVIDENCE_DETAIL="$(grep -m1 '^Workflow run URL: ' "$CI_UNIT_EVIDENCE_DIR/evidence.txt" 2>/dev/null | sed 's/^Workflow run URL: //')"
  UNIT_EVIDENCE_DETAIL="reused the required 'Unit tests' check on this exact commit (${UNIT_EVIDENCE_DETAIL:-unknown run}); downloaded result XML re-verified locally against scripts/executed-test-count-floors.txt — see $CI_UNIT_EVIDENCE_DIR/evidence.txt"
  GATE_COMPILE_UNIT_TASKS="assembleDebug"
  printf 'Unit evidence: REUSING the CI `Unit tests` result for this commit; this gate builds only, and does not re-run the suite.\n'
else
  mark_step_declined "reuse-ci-unit-evidence"
  UNIT_EVIDENCE_MODE="local"
  UNIT_EVIDENCE_DETAIL="local Gradle check graph (assembleDebug check -x lint -x lintDebug); CI evidence was declined — see $RUN_DIR/03-reuse-ci-unit-evidence.log"
  GATE_COMPILE_UNIT_TASKS="assembleDebug check -x lint -x lintDebug"
  printf 'WARN: CI unit evidence was NOT accepted for this commit; running the full unit suite locally (issue #2064 fail-closed path). See the reuse-ci-unit-evidence step log for the reason.\n' >&2
fi

run_bash_step "gradle-compile-unit" \
  "'$ROOT_DIR/scripts/cgroup-run.sh' --unit 'pocketshell-pre-release-$(pocketshell_unit_token "$RUN_ID")-ksp-hilt' -- ./gradlew $GRADLE_FLAGS :app2:kspDebugKotlin :app2:kspReleaseKotlin :app2:kspDebugAndroidTestKotlin :app2:kspDebugUnitTestKotlin :app2:kspReleaseUnitTestKotlin :app2:hiltJavaCompileDebug :app2:hiltJavaCompileRelease :app2:hiltJavaCompileDebugAndroidTest --stacktrace && '$ROOT_DIR/scripts/cgroup-run.sh' --unit 'pocketshell-pre-release-$(pocketshell_unit_token "$RUN_ID")-assemble-check' -- ./gradlew $GRADLE_FLAGS $GATE_COMPILE_UNIT_TASKS --stacktrace"

# Issue #2381 — stamp the `agents` fixture with the SAME versionName this gate
# asserts a few lines below (docker-agents-pocketshell-version) and that the APK
# it installs reports. Since #2356 the version is derived from git rather than a
# literal in app2/build.gradle.kts, so the fixture can no longer parse it out of
# the build context; it is passed in through the environment instead (see
# scripts/lib/agents-fixture-version.sh). Without this the fixture reports its
# baked `0.0.0-dev`, this gate's own version assertion hard-fails on every
# STANDALONE invocation (the one docs/docker-emulator-runbook.md documents), and
# every app screen it drives sits under the bootstrap "Host setup needed" sheet.
#
# Pinning to $APP_VERSION_NAME rather than re-deriving makes "what we stamp" and
# "what we assert" the same value by construction, in both the plain-checkout
# and the isolated-copy (git-less, `0.0.0-dev`) gate modes.
POCKETSHELL_AGENT_FIXTURE_VERSION="$APP_VERSION_NAME"
# shellcheck source=scripts/lib/agents-fixture-version.sh
source "$ROOT_DIR/scripts/lib/agents-fixture-version.sh"
export_agents_fixture_version
run_step "docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build agents
# Issue #150: wait on the compose `healthcheck:` block via
# `docker inspect`. The follow-up SSH sanity check still verifies the
# fixture's tool surface (`claude codex opencode quse tmuxctl uv`), but
# the readiness poll itself is event-based, not retry-sleep.
run_bash_step "docker-agents-health" \
  "source '$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh' && wait_for_container_healthy '$COMPOSE_FILE' agents '$RUN_DIR/docker-agents-health.log' 60"
run_bash_step "docker-agents-pocketshell-version" \
  "$(docker_agents_pocketshell_version_script "$APP_VERSION_NAME")"
run_bash_step "docker-agents-ssh-sanity" \
  "chmod 600 '$SSH_KEY' && ssh -i '$SSH_KEY' -p 2222 -o BatchMode=yes -o ConnectTimeout=3 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 'for tool in claude codex opencode quse tmuxctl uv; do command -v \"\$tool\"; done && quse --json >/dev/null && tmuxctl jobs list --session codex >/dev/null'"

# Issue #847: bring up the OLD-CLI agents fixture (port 2238) whose
# `pocketshell` lacks the new-in-0.4.10 `tree` / `agents kind` subcommands, and
# assert the host-side shape that triggered the v0.4.10 connect-hang:
#   * `pocketshell tree get` errors with a NON-ZERO `No such command` exit
#     (the cold-start hydrate read fails — the exact CLI mismatch), AND
#   * the host is otherwise live (`tmux` works), so a correct client MUST fall
#     back to the live tree instead of hanging on "loading tree".
# This is the deterministic Docker half of the #847 regression gate.
#
# Issue #2481: the on-emulator half used to be
# `com.pocketshell.app.projects.FolderListOldCliHydrateDockerTest`, deleted with
# the `app` module. app2's successor for "the host CLI is too old" is the typed
# `HostCliTooOld` rejection in :shared:core-hostapi (schema < 2), covered on the
# per-push Unit lane, plus scripts/ci-verify-agents-old-cli-mismatch.sh in CI.
# The FIXTURE assertion below is kept regardless of which client consumes it:
# it is the only place that proves the 2238 host really is version-mismatched,
# and a happy-only fixture set is exactly what let the v0.4.10 connect break
# ship (D32/G10).
# Issue #2481: app2's instrumented set is run UNFILTERED below, and it contains
# J05ReconnectAfterDropJourney, which dials the fixture THROUGH the Toxiproxy
# `network-fault-proxy` (see docs/testing.md and app2's ToxiproxyControl). The
# old eight-selector list contained no fault journey, so the gate never needed
# this service; running the whole set without it would fail the reconnect
# journey as if the product were broken (issue #2128's exact symptom).
#
# `--no-deps` plus an API-version poll, not wait_for_container_healthy: the
# toxiproxy image declares no compose `healthcheck:`, so the health helper would
# block for its whole timeout and then fail on a perfectly live proxy. This is
# the same readiness shape .github/workflows/app2.yml's `app2-journey` job uses.
run_step "docker-network-fault-proxy-up" \
  docker compose -f "$COMPOSE_FILE" up -d --no-deps network-fault-proxy
run_bash_step "docker-network-fault-proxy-ready" \
  "for attempt in \$(seq 1 30); do if curl --fail --silent --show-error http://127.0.0.1:8474/version; then exit 0; fi; sleep 1; done; docker compose -f '$COMPOSE_FILE' logs --no-color network-fault-proxy; exit 1"

run_step "docker-agents-old-cli-up" docker compose -f "$COMPOSE_FILE" up -d --build agents-old-cli
run_bash_step "docker-agents-old-cli-health" \
  "source '$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh' && wait_for_container_healthy '$COMPOSE_FILE' agents-old-cli '$RUN_DIR/docker-agents-old-cli-health.log' 60"
# The old CLI rejects `tree` BEFORE reading stdin (Click exits on the unknown
# command), so we don't need a valid JSON payload — `</dev/null` is enough. A
# zero exit would mean the fixture is NOT an old CLI, which fails the gate.
run_bash_step "docker-agents-old-cli-mismatch-sanity" \
  "chmod 600 '$SSH_KEY' && ssh -i '$SSH_KEY' -p 2238 -o BatchMode=yes -o ConnectTimeout=3 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 'pocketshell tree get </dev/null; rc=\$?; if [ \"\$rc\" -eq 0 ]; then echo \"old-cli fixture unexpectedly accepted tree get (rc=0)\" >&2; exit 1; fi; tmux -V >/dev/null && echo \"old-cli mismatch confirmed: tree get rc=\$rc, host still live\"'"

run_bash_step "emulator-readiness" \
  "$(emulator_readiness_script)"
update_emulator_serial

# Issue #1314: the heavy core-terminal burst proof (step 12) is a slow-AVD,
# throughput-sensitive check that historically FRONT-GATED — via the `set -e`
# abort on this bare step — the release-critical stages that follow it: the
# #1302 black-screen recovery journey, the EmulatorDockerSshSmokeTest
# walkthroughs, and the visual-audit. A single timing red here left every one of
# them `not_run`, so no release gate could ever produce clean journey evidence.
# Run it NON-FATALLY: capture the result, keep going so the downstream product-
# validation phases still run and produce their evidence, and fold the result
# back into the final gate verdict below (a real red still FAILS the gate — just
# not before the journey stages ran). This is the de-front-gate half of #1314;
# the deterministic half is the poll-to-deadline final-marker wait in
# CodexAppendBurstMainThreadProofTest.
if run_bash_step "connected-terminal-input" "$(core_terminal_connected_input_script)"; then
  CONNECTED_TERMINAL_INPUT_STATUS="passed"
else
  CONNECTED_TERMINAL_INPUT_STATUS="failed"
  printf 'WARN: connected-terminal-input (step 12) FAILED; continuing to the downstream #1302 journey + walkthroughs + visual-audit so their evidence is still produced. The gate will still FAIL at the end (issue #1314).\n' >&2
fi

run_step "build-app-test-apks" \
  "$ROOT_DIR/scripts/cgroup-run.sh" --unit "pocketshell-pre-release-$(pocketshell_unit_token "$RUN_ID")-build-app-test-apks" -- \
  ./gradlew "${GRADLE_ARGS[@]}" :app2:assembleDebug :app2:assembleDebugAndroidTest --stacktrace
[[ -f "$APK_PATH" ]] || fail "APK artifact was not created at $APK_PATH"
[[ -f "$TEST_APK_PATH" ]] || fail "Android test APK artifact was not created at $TEST_APK_PATH"

# Issue #2064: this is the ONE build of the release pair. Record its identity so
# every downstream stage installs THESE bytes and publish_validated_apk ships
# THESE bytes. Before this, terminal-lab / tmux-existing-session /
# setup-detection / visual-audit each `rm -rf app/build` and rebuilt their own  (all four stages are now deleted, issue #2481)
# byte-different pair, so the journey evidence a tag rests on came from a binary
# nothing else in the chain had validated.
run_step "record-validated-apk-identity" \
  pocketshell_record_apk_identity "$APK_IDENTITY_FILE" \
  "$(cd "$(dirname "$APK_PATH")" && pwd)/$(basename "$APK_PATH")" \
  "$(cd "$(dirname "$TEST_APK_PATH")" && pwd)/$(basename "$TEST_APK_PATH")"

# Issue #2481: this proof is REPOINTED at app2, not deleted. app2 runs under its
# own applicationId (`com.pocketshell.app`) today, so no real device has a
# legacy `pocketshell.db` in its sandbox yet — but app2 reads the SAME schema
# from :shared:core-storage and deliberately wires the whole migration array
# (app2/src/main/java/com/pocketshell/next/di/AppModule.kt: "an install that
# later becomes the primary app must open an existing v-N file rather than fail
# Room's schema validation"). That claim is exactly what this step proves, and
# it becomes load-bearing the moment rewrite task X-3 renames the applicationId
# to `com.pocketshell.app` over the shipping client's existing database. Testing
# it now, on every release, is the difference between finding a broken migration
# chain here and finding it on the maintainer's phone at cutover.
LEGACY_V1_DB_MIGRATION_STATUS="running"
run_bash_step "migrate-legacy-v1-databases" "$(legacy_v1_database_migration_script)"

run_bash_step "cold-reset-app-packages-before-app-walkthrough" "$(cold_reset_app_packages_script)"
run_bash_step "cold-reset-install-app-walkthrough-apks" "$(cold_reset_install_app_walkthrough_apks_script)"

# Issue #2481: ONE unfiltered instrumentation run of app2's whole set, against
# the validated APK pair installed above. It replaces the eight-selector loop
# that drove the deleted `app` module's androidTest classes; see
# APP2_INSTRUMENTED_SUITE_LABEL for why unfiltered (issue #2474) rather than a
# fresh selector list.
app2_suite_label="$APP2_INSTRUMENTED_SUITE_LABEL"
set_focused_status "$app2_suite_label" "pending"
if ! run_bash_step "quiesce-app-walkthrough-processes-$app2_suite_label" "$(quiesce_app_walkthrough_processes_script)"; then
  set_focused_status "$app2_suite_label" "blocked"
  exit 1
fi

app2_suite_step_index=$((STEP_INDEX + 1))
app2_suite_diagnostics_file="$(printf '%s/%02d-connected-app-walkthrough-%s-diagnostics.log' "$RUN_DIR" "$app2_suite_step_index" "$app2_suite_label")"
app2_suite_full_logcat_file="$(printf '%s/%02d-connected-app-walkthrough-%s-full-logcat.log' "$RUN_DIR" "$app2_suite_step_index" "$app2_suite_label")"
set_focused_status "$app2_suite_label" "running" "" "$app2_suite_diagnostics_file" "$app2_suite_full_logcat_file"
app2_suite_step_log="$RUN_DIR/$(printf '%02d-connected-app-walkthrough-%s.log' "$app2_suite_step_index" "$app2_suite_label")"
if run_bash_step "connected-app-walkthrough-$app2_suite_label" "$(run_app2_instrumented_suite_script "$app2_suite_diagnostics_file" "$app2_suite_full_logcat_file")"; then
  set_focused_status "$app2_suite_label" "passed" "$app2_suite_step_log" "$app2_suite_diagnostics_file" "$app2_suite_full_logcat_file"
  # Re-encode the runner's own INSTRUMENTATION_STATUS stream where Gradle would
  # have put it, so the #2082 execution ledger can credit the journeys that just
  # ran. See record_detached_instrumentation_junit_xml above for why this exists
  # and why it is pinned to J01.
  record_detached_instrumentation_junit_xml \
    "$APP2_LEDGER_REQUIRED_CLASS" "$app2_suite_step_log"
else
  set_focused_status "$app2_suite_label" "failed" "$app2_suite_step_log" "$app2_suite_diagnostics_file" "$app2_suite_full_logcat_file"
  FAILURE_DIAGNOSTICS_PATH="$app2_suite_diagnostics_file"
  FAILURE_LOGCAT_PATH="$app2_suite_full_logcat_file"
  exit 1
fi

# Issue #2481: the journey screenshots are the release VISUAL-AUDIT artifact now
# that the `scripts/phone-walkthrough.sh visual-audit` stage is gone (it drove
# three deleted `app` module screenshot tests: WalkthroughVisualScreenshotTest,
# WalkthroughConversationScreenshotTest and PromptComposerVisualScreenshotTest —
# and the conversation view itself is a cut feature, see
# docs/rewrite-implementation-plan.md "Scope amendment"). app2's journeys write
# PNGs through `JourneyScreenshots.capture` into the app's external files dir,
# which is exactly what scripts/ci-app2-journey-suite.sh collects in CI.
#
# Best-effort on purpose: a missing screenshot must not redden a green suite, so
# this is a `run_step` over a pull that cannot fail the gate, and the summary
# records where they landed. The HARD per-journey assertion ("every journey
# rendered at least one frame") lives in the standalone
# scripts/capture-walkthrough-screenshots.sh, which is deliberately NOT a chain
# stage: running it here would be a second full run of the set just executed.
APP2_JOURNEY_SCREENSHOT_DIR="$RUN_DIR/journey-screenshots"
mkdir -p "$APP2_JOURNEY_SCREENSHOT_DIR"
run_step "pull-app2-journey-screenshots" bash -lc \
  "'$ADB' pull /sdcard/Android/data/com.pocketshell.app/files '$APP2_JOURNEY_SCREENSHOT_DIR' >/dev/null 2>&1 || printf 'no journey screenshots were pulled (best effort)\\n'; find '$APP2_JOURNEY_SCREENSHOT_DIR' -type f -name '*.png' | sort" ||
  printf 'WARN: journey screenshot collection failed; the suite result above is unaffected (issue #2481).\n' >&2

# Issue #2064: this step used to re-run the app module's `assembleDebug` after
# `build-app-test-apks` had already produced the APK — a fourth build of the
# same binary inside a chain that was already building it four times. It is
# replaced by the assertion that makes the redundancy unnecessary AND provable:
# the APK about to be update-installed and published must still hash to the
# value recorded at the single build. A rebuild here would have masked exactly
# the divergence this gate now refuses to ship.
run_step "verify-debug-apk-identity" \
  pocketshell_assert_apk_identity "pre-release gate (update-install candidate)" \
  "$APK_PATH" \
  "$(pocketshell_read_apk_identity_field "$APK_IDENTITY_FILE" app_apk_sha256)"

run_step "update-install-debug-apk" "$ROOT_DIR/scripts/install-update-apk.sh" "$APK_PATH"

# Issue #1314: fold the deferred step-12 (connected-terminal-input) result into
# the final verdict. If it failed, the gate FAILS here — AFTER the downstream
# #1302 journey + walkthroughs + visual-audit produced their evidence — so a
# real core-terminal red is never lost, but a step-12 red never again masks the
# release-critical journey stages by aborting before them.
if [[ "$CONNECTED_TERMINAL_INPUT_STATUS" == "failed" ]]; then
  FAILING_STEP="connected-terminal-input"
  fail "connected-terminal-input (step 12) failed; the downstream #1302 journey + walkthroughs + visual-audit were still run for evidence (issue #1314). See the connected-terminal-input step log."
fi

GATE_RESULT="PASS"
GATE_RESULT_MESSAGE="PASS: pre-release confidence gate completed"
printf '\nPASS: pre-release confidence gate completed\n'
printf 'Logs: %s\n' "$RUN_DIR"
printf 'APK: %s\n' "$APK_PATH"
