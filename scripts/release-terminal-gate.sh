#!/usr/bin/env bash
# PocketShell high-confidence terminal release gate (issue #95).
#
# Runs after normal build/unit checks. Chains a focused sequence of
# emulator + Docker connected tests that prove the terminal/SSH/tmux/agent
# stack is usable end-to-end before a release tag is pushed. Produces a
# single artifact root with a top-level summary so a reviewer can inspect
# authoritative terminal viewport screenshots, visible terminal text,
# timings, and logs from one place.
#
# This gate is MANUAL/OPTIONAL. It is not wired into CI yet. Invoke it
# locally before tagging when terminal usability is in release scope, or
# from scripts/release-emulator-validation.sh when TERMINAL_RELEASE_GATE=1.
#
# Steps (in order):
#   step-01-ssh-smoke               EmulatorDockerSshSmokeTest
#                                   (SSH into Docker fixture, agent tool PATH,
#                                    heru/tmuxctl/agent-log-explorer fixtures)
#   step-02-terminal-lab            TerminalLabDockerTest
#                                   #terminalWorkbenchKeepsDockerShellOpenForVisualIteration
#                                   (deterministic agents fixture; command input,
#                                    PTY sizing, viewport+visible text capture)
#   step-03-tmux-attach-prefill     TmuxAttachPrefillDockerTest
#                                   #attachExistingTmuxSessionPrefillsFullScreenQuickly
#                                   (full-screen attach, before/after viewport)
#   step-04-tmux-external-update    TmuxExternalUpdateDockerTest
#                                   #externalTmuxWriteRepaintsAttachedPocketShellViewport
#                                   (live repaint of attached pane after external write)
#   step-05-real-agent-cli          TerminalLabDockerTest
#                                   #terminalWorkbenchCapturesRealAgentCliScreens
#                                   (real interactive Claude/Codex/OpenCode CLI screen)
#
# Each step runs the existing scripts/terminal-workbench.sh under a distinct
# RUN_ID so its native validator (blank-viewport detection, stale hash
# detection, PTY sizing presence, summary completeness, real-agent CLI
# expected-text) fires per-step. The gate aggregates step results into
# build/release-terminal-gate/<run-id>/summary.md and points at the
# authoritative artifacts of each step.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Acquire an exclusive AVD lock so parallel-worktree gate runs serialize on the
# shared local Android emulator. Sibling `connectedAndroidTest` invocations
# from individual implementer/reviewer worktrees are intentionally NOT held by
# this lock — only the release-gate scripts that drive long sequential
# emulator workflows (see issue #182). When invoked from a parent gate script
# that already holds the lock, the env-var guard makes this a no-op. Skipped
# when the caller is just asking for --help so help stays cheap.
LOCK_FILE="${POCKETSHELL_AVD_LOCK_FILE:-$ROOT_DIR/build/.avd-lock}"
if [[ "${1:-}" != "--help" && "${1:-}" != "-h" && -z "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" ]]; then
  mkdir -p "$(dirname "$LOCK_FILE")"
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    echo "Another emulator-touching script holds the AVD lock ($LOCK_FILE); waiting..." >&2
    flock 9
  fi
  echo "Acquired AVD lock (fd 9): $LOCK_FILE" >&2
  export POCKETSHELL_AVD_LOCK_ACQUIRED=1
fi

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/release-terminal-gate}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
SUMMARY_PATH="$RUN_DIR/summary.md"

WORKBENCH_LOG_ROOT="$RUN_DIR/workbench"
SKIP_GRADLE_CHECKS="${SKIP_GRADLE_CHECKS:-0}"
SKIP_SSH_SMOKE="${SKIP_SSH_SMOKE:-0}"
SKIP_REAL_AGENT="${SKIP_REAL_AGENT:-0}"
DETERMINISTIC_COMPOSE_FILE="${DETERMINISTIC_COMPOSE_FILE:-tests/docker/docker-compose.yml}"
REAL_AGENT_COMPOSE_FILE="${REAL_AGENT_COMPOSE_FILE:-tests/docker/real-agent/compose.yml}"

# Issue #150: shared health-status polling helper. Provides
# `wait_for_container_healthy` so the embedded SSH-smoke step below can
# consume the compose `healthcheck:` block via `docker inspect`.
source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"

usage() {
  cat <<'USAGE'
Usage: scripts/release-terminal-gate.sh

High-confidence terminal release gate. Runs build/unit checks (unless
SKIP_GRADLE_CHECKS=1) followed by a focused chain of emulator + Docker
connected tests that exercise SSH, command input, PTY sizing, terminal
viewport capture, and at least one real interactive agent CLI screen.

This gate is manual/optional and is NOT enabled in CI by default.

Acquires an exclusive `flock` on `build/.avd-lock` (relative to the repo
root) before touching the emulator so that parallel-worktree gate runs
serialise on the shared local AVD. Released automatically on script exit.
See issue #182.

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  AVD_NAME=test
  LOG_ROOT=build/release-terminal-gate
  RUN_ID=<artifact directory name>
  SKIP_GRADLE_CHECKS=1     reuse existing build outputs (skip Gradle build/unit checks)
  SKIP_SSH_SMOKE=1         skip the EmulatorDockerSshSmokeTest step
  SKIP_REAL_AGENT=1        skip the real-agent CLI step (e.g. real-agent compose
                           file unavailable)
  DETERMINISTIC_COMPOSE_FILE=tests/docker/docker-compose.yml
  REAL_AGENT_COMPOSE_FILE=tests/docker/real-agent/compose.yml
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

mkdir -p "$RUN_DIR" "$WORKBENCH_LOG_ROOT"

STEP_NAMES=()
STEP_STATUSES=()
STEP_LOGS=()
STEP_ARTIFACT_ROOTS=()
STEP_WORKBENCH_SUMMARIES=()
STEP_NOTES=()
GATE_RESULT="FAIL"
FAILING_STEP=""
FAILURE_REASON=""

commit_sha() {
  git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf 'unknown'
}

fail() {
  FAILURE_REASON="${FAILURE_REASON:-$1}"
  printf '\nFAIL release terminal gate\n' >&2
  printf 'reason: %s\n' "$1" >&2
  printf 'summary: %s\n' "$SUMMARY_PATH" >&2
  exit 1
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -x "$path" ]] || fail "$label is not executable at $path"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 was not found on PATH"
}

require_emulator_ready() {
  if ! "$ADB" get-state >/dev/null 2>&1; then
    fail "no booted adb device is connected; start the '$AVD_NAME' emulator first"
  fi
  local boot_completed
  boot_completed="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_completed" != "1" ]]; then
    fail "adb device is connected but sys.boot_completed=$boot_completed; wait for the emulator to finish booting"
  fi
}

note() {
  local message="$1"
  printf '%s\n' "$message"
}

write_summary() {
  local exit_status="${1:-0}"
  if [[ "$exit_status" -eq 0 && "$GATE_RESULT" == "PASS" ]]; then
    : # status already reflects success
  else
    GATE_RESULT="FAIL"
  fi

  {
    printf '# PocketShell release terminal gate\n\n'
    printf 'Generated: %s\n' "$(date -Is)"
    printf 'Commit SHA: %s\n' "$(commit_sha)"
    printf 'Run ID: %s\n' "$RUN_ID"
    printf 'Run directory: `%s`\n' "$RUN_DIR"
    printf 'Result: %s\n' "$GATE_RESULT"
    printf 'Exit status: %s\n' "$exit_status"
    if [[ "$GATE_RESULT" != "PASS" ]]; then
      printf 'Failing step: %s\n' "${FAILING_STEP:-unknown}"
      printf 'Failure reason: %s\n' "${FAILURE_REASON:-unknown}"
    fi

    printf '\n## Steps\n\n'
    local i
    for i in "${!STEP_NAMES[@]}"; do
      printf '### %s\n\n' "${STEP_NAMES[$i]}"
      printf -- '- status: %s\n' "${STEP_STATUSES[$i]}"
      if [[ -n "${STEP_LOGS[$i]}" ]]; then
        printf -- '- log: `%s`\n' "${STEP_LOGS[$i]}"
      fi
      if [[ -n "${STEP_ARTIFACT_ROOTS[$i]}" ]]; then
        printf -- '- artifact root: `%s`\n' "${STEP_ARTIFACT_ROOTS[$i]}"
      fi
      if [[ -n "${STEP_WORKBENCH_SUMMARIES[$i]}" ]]; then
        printf -- '- workbench summary: `%s`\n' "${STEP_WORKBENCH_SUMMARIES[$i]}"
      fi
      if [[ -n "${STEP_NOTES[$i]}" ]]; then
        printf -- '- notes: %s\n' "${STEP_NOTES[$i]}"
      fi
      printf '\n#### Authoritative artifacts\n\n'
      if [[ -n "${STEP_ARTIFACT_ROOTS[$i]}" && -d "${STEP_ARTIFACT_ROOTS[$i]}" ]]; then
        local artifact_dir="${STEP_ARTIFACT_ROOTS[$i]}"
        local viewports
        viewports="$(find "$artifact_dir" -maxdepth 1 -type f -name '*-viewport.png' -printf '%f\n' 2>/dev/null | sort)"
        if [[ -n "$viewports" ]]; then
          printf 'viewport screenshots:\n'
          while IFS= read -r name; do
            [[ -n "$name" ]] || continue
            printf -- '- `%s/%s`\n' "$artifact_dir" "$name"
          done <<<"$viewports"
        else
          printf 'viewport screenshots: none (advisory step)\n'
        fi
        local sidecars
        sidecars="$(find "$artifact_dir" -maxdepth 1 -type f -name '*-visible-terminal.txt' -printf '%f\n' 2>/dev/null | sort)"
        if [[ -n "$sidecars" ]]; then
          printf '\nvisible terminal sidecars:\n'
          while IFS= read -r name; do
            [[ -n "$name" ]] || continue
            printf -- '- `%s/%s`\n' "$artifact_dir" "$name"
          done <<<"$sidecars"
        fi
        local summaries
        summaries="$(find "$artifact_dir" -maxdepth 1 -type f -name '*-summary.txt' -printf '%f\n' 2>/dev/null | sort)"
        if [[ -n "$summaries" ]]; then
          printf '\ncapture summaries:\n'
          while IFS= read -r name; do
            [[ -n "$name" ]] || continue
            printf -- '- `%s/%s`\n' "$artifact_dir" "$name"
          done <<<"$summaries"
        fi
        if [[ -s "$artifact_dir/timings.txt" ]]; then
          printf '\ntiming file:\n'
          printf -- '- `%s/timings.txt`\n' "$artifact_dir"
        fi
        local advisories
        advisories="$(find "$artifact_dir" -maxdepth 1 -type f -name '*.png' ! -name '*-viewport.png' -printf '%f\n' 2>/dev/null | sort)"
        if [[ -n "$advisories" ]]; then
          printf '\nadvisory screenshots (diagnostic only):\n'
          while IFS= read -r name; do
            [[ -n "$name" ]] || continue
            printf -- '- `%s/%s`\n' "$artifact_dir" "$name"
          done <<<"$advisories"
        fi
      else
        printf '(none pulled)\n'
      fi
      printf '\n#### Logs\n\n'
      local workbench_dir="$WORKBENCH_LOG_ROOT/${RUN_ID}-${STEP_NAMES[$i]}"
      if [[ -d "$workbench_dir" ]]; then
        local docker_log="$workbench_dir/docker-agents.log"
        local ssh_log="$workbench_dir/docker-ssh-readiness.log"
        local instrumentation_log="$workbench_dir/07-run-workbench.log"
        local logcat="$workbench_dir/logcat.txt"
        [[ -f "$docker_log" ]] && printf -- '- docker compose log: `%s`\n' "$docker_log"
        [[ -f "$ssh_log" ]] && printf -- '- docker SSH readiness log: `%s`\n' "$ssh_log"
        [[ -f "$instrumentation_log" ]] && printf -- '- instrumentation log: `%s`\n' "$instrumentation_log"
        [[ -f "$logcat" ]] && printf -- '- emulator logcat: `%s`\n' "$logcat"
      else
        printf '(no workbench log directory)\n'
      fi
      printf '\n'
    done

    printf '## Acceptance Criteria Coverage\n\n'
    printf -- '- Release-gate command/script after build/unit checks: this script. Build/unit checks run first unless `SKIP_GRADLE_CHECKS=1`.\n'
    printf -- '- SSH into Docker exercised: `step-01-ssh-smoke` (EmulatorDockerSshSmokeTest).\n'
    printf -- '- Command input + PTY sizing + terminal viewport capture: `step-02-terminal-lab` (terminal-workbench validator enforces blank-viewport, stale-hash, PTY sizing presence).\n'
    printf -- '- Full-screen attach repaint: `step-03-tmux-attach-prefill`.\n'
    printf -- '- Live external repaint: `step-04-tmux-external-update`.\n'
    printf -- '- Real interactive agent CLI screen: `step-05-real-agent-cli` (terminal-workbench validator enforces "Ask anything|Welcome to Codex|Welcome to Claude Code" presence).\n'
    printf -- '- Failure modes (blank captures, stale hashes, missing artifacts, missing visible text, failed SSH, failed PTY sizing) are enforced by terminal-workbench.sh per step and surfaced here as a step-level FAIL.\n'
    printf -- '- This gate is manual/optional. CI does not invoke it by default. `scripts/release-emulator-validation.sh` runs it only when `TERMINAL_RELEASE_GATE=1`.\n'
  } > "$SUMMARY_PATH"
}

on_exit() {
  local exit_status="$?"
  set +e
  write_summary "$exit_status"
}
trap on_exit EXIT

run_logged() {
  local name="$1"
  shift
  local log_file="$RUN_DIR/$name.log"
  printf '\n[%s]\n' "$name"
  printf 'Command:'
  printf ' %q' "$@"
  printf '\nLog: %s\n' "$log_file"
  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$name"
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } 2>&1 | tee "$log_file"
  local status="${PIPESTATUS[0]}"
  set -e
  return "$status"
}

record_step() {
  local name="$1"
  local status="$2"
  local log="$3"
  local artifact_root="$4"
  local workbench_summary="$5"
  local notes="$6"
  STEP_NAMES+=("$name")
  STEP_STATUSES+=("$status")
  STEP_LOGS+=("$log")
  STEP_ARTIFACT_ROOTS+=("$artifact_root")
  STEP_WORKBENCH_SUMMARIES+=("$workbench_summary")
  STEP_NOTES+=("$notes")
}

# Wrap scripts/terminal-workbench.sh for one (TEST_SELECTOR, REAL_AGENTS, name) tuple.
# Writes per-step logs under $RUN_DIR/$step_name/.
# Returns 0 on success, 1 on workbench failure.
run_workbench_step() {
  local step_name="$1"
  local test_selector="$2"
  local real_agents="$3"
  local build_apks="$4"
  local notes="$5"

  local step_run_id="${RUN_ID}-${step_name}"
  local step_run_dir="$WORKBENCH_LOG_ROOT/$step_run_id"
  local step_log="$RUN_DIR/$step_name.log"
  local artifact_dir="$step_run_dir/artifacts/terminal-lab"
  local workbench_summary="$step_run_dir/artifact-summary.txt"

  printf '\n[%s] starting workbench step\n' "$step_name"
  printf 'selector: %s\n' "$test_selector"
  printf 'real_agents: %s\n' "$real_agents"
  printf 'workbench run_id: %s\n' "$step_run_id"

  local env_args=(
    "LOG_ROOT=$WORKBENCH_LOG_ROOT"
    "RUN_ID=$step_run_id"
    "TEST_SELECTOR=$test_selector"
    "REAL_AGENTS=$real_agents"
    "BUILD_APKS=$build_apks"
  )

  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$step_name"
    printf 'selector: %s\n' "$test_selector"
    printf 'real_agents: %s\n' "$real_agents"
    printf 'build_apks: %s\n' "$build_apks"
    printf 'workbench run dir: %s\n\n' "$step_run_dir"
    env "${env_args[@]}" "$ROOT_DIR/scripts/terminal-workbench.sh"
  } 2>&1 | tee "$step_log"
  local status="${PIPESTATUS[0]}"
  set -e

  if [[ "$status" -eq 0 ]]; then
    record_step "$step_name" "PASS" "$step_log" "$artifact_dir" "$workbench_summary" "$notes"
    return 0
  fi

  record_step "$step_name" "FAIL" "$step_log" "$artifact_dir" "$workbench_summary" "$notes"
  FAILING_STEP="$step_name"
  FAILURE_REASON="terminal-workbench.sh exited $status for selector $test_selector"
  return "$status"
}

# Wrap scripts/tmux-attach-prefill.sh for step-03. That script has its own
# validator that correctly tolerates the empty pre-attach viewport (the
# generic terminal-workbench validator rejects zero-bright-pixel viewports,
# which is fine for connected/attached scenarios but wrong here because the
# scenario captures the screen BEFORE the tmux session is attached).
run_attach_prefill_step() {
  local step_name="step-03-tmux-attach-prefill"
  local step_run_id="${RUN_ID}-${step_name}"
  local step_run_dir="$WORKBENCH_LOG_ROOT/$step_run_id"
  local step_log="$RUN_DIR/$step_name.log"
  local artifact_dir="$step_run_dir/artifacts/terminal-lab"
  local workbench_summary="$step_run_dir/artifact-summary.txt"

  printf '\n[%s] starting attach-prefill step (wraps scripts/tmux-attach-prefill.sh)\n' "$step_name"

  local env_args=(
    "LOG_ROOT=$WORKBENCH_LOG_ROOT"
    "RUN_ID=$step_run_id"
    "BUILD_APKS=0"
  )

  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$step_name"
    printf 'wrapped script: scripts/tmux-attach-prefill.sh\n'
    printf 'workbench run dir: %s\n\n' "$step_run_dir"
    env "${env_args[@]}" "$ROOT_DIR/scripts/tmux-attach-prefill.sh"
  } 2>&1 | tee "$step_log"
  local status="${PIPESTATUS[0]}"
  set -e

  if [[ "$status" -eq 0 ]]; then
    record_step "$step_name" "PASS" "$step_log" "$artifact_dir" "$workbench_summary" \
      "Attach to a seeded tmux session and prove the visible terminal contains both the first and last seeded line within the perf target (issue #103). Uses scripts/tmux-attach-prefill.sh which validates attach_tap_to_first_content_ms timing, before/after viewport artifacts (the before viewport is intentionally empty), seed-line markers in the after sidecar, and capture_policy presence."
    return 0
  fi

  record_step "$step_name" "FAIL" "$step_log" "$artifact_dir" "$workbench_summary" \
    "scripts/tmux-attach-prefill.sh exited $status."
  FAILING_STEP="$step_name"
  FAILURE_REASON="scripts/tmux-attach-prefill.sh exited $status"
  return "$status"
}

# Pure SSH smoke step: runs EmulatorDockerSshSmokeTest directly via adb shell am instrument.
# Uses the deterministic agents Docker fixture (already brought up by the
# terminal-workbench steps that follow). We bring it up here explicitly to
# satisfy the "SSH into Docker" acceptance criterion even on a cold run.
run_ssh_smoke_step() {
  local step_name="step-01-ssh-smoke"
  local step_log="$RUN_DIR/$step_name.log"
  local step_run_dir="$WORKBENCH_LOG_ROOT/${RUN_ID}-${step_name}"
  mkdir -p "$step_run_dir"
  local artifact_dir="$step_run_dir/artifacts"
  mkdir -p "$artifact_dir"

  printf '\n[%s] starting SSH smoke step\n' "$step_name"

  local docker_log="$step_run_dir/docker-agents.log"
  local ssh_readiness="$step_run_dir/docker-ssh-readiness.log"
  local instrumentation_log="$step_run_dir/07-run-workbench.log"
  local logcat="$step_run_dir/logcat.txt"

  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$step_name"
    printf 'selector: %s\n' \
      'com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias'
    printf '\n# bringing up deterministic agents Docker fixture\n'
    docker compose -f "$DETERMINISTIC_COMPOSE_FILE" up -d --build agents
    printf '\n# Docker compose ps\n'
    docker compose -f "$DETERMINISTIC_COMPOSE_FILE" ps
    # Issue #150: wait on the compose `healthcheck:` block via
    # `docker inspect` (sourced wait_for_container_healthy) instead of
    # polling SSH with a retry loop. Follow up with a single SSH probe
    # so the readiness log still records the same "tmux ready" evidence.
    printf '\n# probing SSH readiness via docker inspect health status\n'
    local ssh_key="$ROOT_DIR/tests/docker/test_key"
    if ! wait_for_container_healthy "$DETERMINISTIC_COMPOSE_FILE" agents "$ssh_readiness" 60; then
      printf '\nFAIL: Docker SSH fixture did not become healthy (host port 2222)\n' >&2
      tail -n 80 "$ssh_readiness" >&2 || true
      exit 1
    fi
    {
      printf '[%s] health=healthy; running follow-up SSH sanity probe\n' "$(date -Is)"
      ssh \
        -i "$ssh_key" \
        -p 2222 \
        -o BatchMode=yes \
        -o ConnectTimeout=3 \
        -o ConnectionAttempts=1 \
        -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null \
        testuser@127.0.0.1 \
        "printf 'release terminal gate ssh ready '; tmux -V"
    } >> "$ssh_readiness" 2>&1 || {
      printf '\nFAIL: Docker SSH fixture reported healthy but follow-up SSH probe failed (host port 2222)\n' >&2
      tail -n 80 "$ssh_readiness" >&2 || true
      exit 1
    }
    tail -n 20 "$ssh_readiness"

    printf '\n# clearing emulator logcat\n'
    "$ADB" logcat -c || true
    printf '\n# running EmulatorDockerSshSmokeTest instrumentation\n'
    "$ADB" shell am instrument -w -r \
      -e class com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias \
      com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$instrumentation_log"
    printf '\n# capturing diagnostics\n'
    docker compose -f "$DETERMINISTIC_COMPOSE_FILE" logs --no-color --timestamps agents > "$docker_log" 2>&1 || true
    "$ADB" logcat -d -v threadtime -t 4000 > "$logcat" 2>&1 || true
  } 2>&1 | tee "$step_log"
  local status="${PIPESTATUS[0]}"
  set -e

  if [[ "$status" -ne 0 ]]; then
    record_step "$step_name" "FAIL" "$step_log" "$artifact_dir" "" \
      "Direct SSH smoke step failed before instrumentation completed."
    FAILING_STEP="$step_name"
    FAILURE_REASON="SSH smoke step failed with exit $status"
    return "$status"
  fi

  if ! grep -q 'INSTRUMENTATION_CODE: -1' "$instrumentation_log"; then
    record_step "$step_name" "FAIL" "$step_log" "$artifact_dir" "" \
      "EmulatorDockerSshSmokeTest did not report INSTRUMENTATION_CODE: -1."
    FAILING_STEP="$step_name"
    FAILURE_REASON="EmulatorDockerSshSmokeTest did not report INSTRUMENTATION_CODE: -1"
    return 1
  fi
  if ! grep -q 'OK (' "$instrumentation_log"; then
    record_step "$step_name" "FAIL" "$step_log" "$artifact_dir" "" \
      "EmulatorDockerSshSmokeTest did not report 'OK (' summary."
    FAILING_STEP="$step_name"
    FAILURE_REASON="EmulatorDockerSshSmokeTest did not report OK"
    return 1
  fi

  record_step "$step_name" "PASS" "$step_log" "$artifact_dir" "" \
    "Asserts SSH path from emulator to Docker fixture works, deterministic agent tools are on PATH (claude, codex, opencode, heru, agent-log-explorer, tmuxctl, uv), and the agent-log-explorer/heru/tmuxctl fixtures respond correctly."
  return 0
}

printf 'PocketShell high-confidence terminal release gate\n'
printf 'Run ID: %s\n' "$RUN_ID"
printf 'Run directory: %s\n' "$RUN_DIR"
printf 'Skip gradle checks: %s\n' "$SKIP_GRADLE_CHECKS"
printf 'Skip ssh smoke: %s\n' "$SKIP_SSH_SMOKE"
printf 'Skip real agent: %s\n' "$SKIP_REAL_AGENT"
printf 'Deterministic compose file: %s\n' "$DETERMINISTIC_COMPOSE_FILE"
printf 'Real-agent compose file: %s\n' "$REAL_AGENT_COMPOSE_FILE"

require_executable "$ADB" "adb"
require_executable "$EMULATOR" "emulator"
require_command docker
require_command ssh
[[ -f "$ROOT_DIR/tests/docker/test_key" ]] ||
  fail "SSH test key missing at tests/docker/test_key"
# OpenSSH refuses to use a private key with group/world-readable permissions.
# Worktrees frequently inherit 0664 because the umask differs, so normalise to
# 0600 here.
chmod 600 "$ROOT_DIR/tests/docker/test_key"

require_emulator_ready

# Make the Android SDK location discoverable for Gradle when the checkout
# does not have a local.properties (common in agent worktrees). The
# subprocess terminal-workbench.sh inherits these via the environment.
if [[ ! -f "$ROOT_DIR/local.properties" ]]; then
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
fi

# Acceptance criterion: "Define a release-gate command or script that runs the
# terminal-focused emulator checks after normal build/unit checks." We run the
# normal build/unit checks first unless explicitly skipped.
if [[ "$SKIP_GRADLE_CHECKS" != "1" ]]; then
  run_logged "00-gradle-build-and-unit-checks" \
    ./gradlew --no-daemon \
    :app:assembleDebug \
    :app:test \
    :shared:core-terminal:test \
    :shared:core-tmux:test \
    :shared:core-ssh:test \
    --stacktrace
else
  note "Skipping Gradle build/unit checks because SKIP_GRADLE_CHECKS=1"
fi

# We let terminal-workbench.sh build APKs on the first connected step
# (BUILD_APKS=1) and reuse the freshly built APKs for the remaining steps
# (BUILD_APKS=0). This avoids paying the APK build cost five times.
FIRST_WORKBENCH_BUILD_APKS=1

if [[ "$SKIP_SSH_SMOKE" != "1" ]]; then
  # The SSH smoke step does not rebuild the APKs. It depends on the APKs that
  # the subsequent steps build. To keep ordering simple and to satisfy the
  # gate's "SSH must work" acceptance criterion early, we rebuild the APKs in
  # the first workbench step, then run the SSH smoke step against the same
  # installed APK pair. So we first install via a small bootstrap workbench
  # invocation... but that costs an extra connected test we do not want. The
  # pragmatic compromise:
  #
  # 1. Run the first workbench step (terminal-lab) which builds and installs
  #    the APKs and validates the deterministic terminal-lab artifacts.
  # 2. Then run the SSH smoke step against the installed APK pair.
  # 3. Then run the remaining workbench steps with BUILD_APKS=0.
  #
  # This means step-01-ssh-smoke is logically ordered second in this script
  # but documented as "SSH smoke" because it covers the SSH-into-Docker
  # acceptance criterion. The summary lists steps in their actual run order.
  :
fi

# Step: deterministic terminal-lab (builds the APKs).
if ! run_workbench_step \
  "step-02-terminal-lab" \
  "com.pocketshell.app.terminal.TerminalLabDockerTest#terminalWorkbenchKeepsDockerShellOpenForVisualIteration" \
  "0" \
  "$FIRST_WORKBENCH_BUILD_APKS" \
  "Deterministic agents Docker fixture (host port 2222). Exercises command input through TerminalView, captures authoritative viewport screenshots, visible terminal text, and PTY sizing. terminal-workbench.sh enforces blank-viewport detection, stale-hash detection, PTY sizing presence, and summary completeness."; then
  fail "step-02-terminal-lab failed (see $SUMMARY_PATH)"
fi

# Step: SSH smoke (no APK rebuild; the previous step installed them).
if [[ "$SKIP_SSH_SMOKE" != "1" ]]; then
  if ! run_ssh_smoke_step; then
    fail "step-01-ssh-smoke failed (see $SUMMARY_PATH)"
  fi
fi

# Step: tmux attach prefill (full-screen attach). Uses the dedicated
# scripts/tmux-attach-prefill.sh wrapper because the generic terminal-workbench
# validator rejects the intentionally-empty pre-attach viewport.
if ! run_attach_prefill_step; then
  fail "step-03-tmux-attach-prefill failed (see $SUMMARY_PATH)"
fi

# Step: tmux external update (live repaint).
if ! run_workbench_step \
  "step-04-tmux-external-update" \
  "com.pocketshell.app.proof.TmuxExternalUpdateDockerTest#externalTmuxWriteRepaintsAttachedPocketShellViewport" \
  "0" \
  "0" \
  "Write to the attached tmux pane from outside the app and prove the PocketShell viewport repaints (issue #105 live repaint)."; then
  fail "step-04-tmux-external-update failed (see $SUMMARY_PATH)"
fi

# Step: real-agent CLI (interactive Claude/Codex/OpenCode screen).
if [[ "$SKIP_REAL_AGENT" != "1" ]]; then
  if [[ ! -f "$ROOT_DIR/$REAL_AGENT_COMPOSE_FILE" ]]; then
    fail "real-agent compose file is missing at $REAL_AGENT_COMPOSE_FILE (set SKIP_REAL_AGENT=1 to skip the real-agent CLI step)"
  fi
  if ! run_workbench_step \
    "step-05-real-agent-cli" \
    "com.pocketshell.app.terminal.TerminalLabDockerTest#terminalWorkbenchCapturesRealAgentCliScreens" \
    "1" \
    "0" \
    "Real-agent Docker fixture (host port 2240). Drives at least one interactive agent CLI screen (claude/codex/opencode) and captures the welcome banner. terminal-workbench.sh enforces 'Ask anything|Welcome to Codex|Welcome to Claude Code' visible-text presence."; then
    fail "step-05-real-agent-cli failed (see $SUMMARY_PATH)"
  fi
else
  note "Skipping step-05-real-agent-cli because SKIP_REAL_AGENT=1"
  record_step "step-05-real-agent-cli" "SKIPPED" "" "" "" \
    "Skipped because SKIP_REAL_AGENT=1. The release gate normally requires a real interactive agent CLI screen capture."
fi

GATE_RESULT="PASS"
printf '\nPASS: release terminal gate completed\n'
printf 'Summary: %s\n' "$SUMMARY_PATH"
printf 'Artifact root: %s\n' "$RUN_DIR"
