#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Acquire an exclusive AVD lock so parallel-worktree gate runs serialize on the
# shared local Android emulator. Sibling `connectedAndroidTest` invocations
# from individual implementer/reviewer worktrees are intentionally NOT held by
# this lock — only the release-gate scripts that drive long sequential
# emulator workflows (see issue #182). Skipped when the caller is just asking
# for --help so help stays cheap and never blocks on a sibling gate run.
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

LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/release-emulator-validation}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
SUMMARY_PATH="$RUN_DIR/summary.md"
PRE_RELEASE_RUN_ID="$RUN_ID-pre-release"
PRE_RELEASE_GATE_LOG_ROOT="$ROOT_DIR/build/pre-release-confidence-gate"
PRE_RELEASE_GATE_RUN_DIR="$PRE_RELEASE_GATE_LOG_ROOT/$PRE_RELEASE_RUN_ID"
PRE_RELEASE_GATE_APK="$PRE_RELEASE_GATE_RUN_DIR/worktree/app/build/outputs/apk/debug/app-debug.apk"
VALIDATED_APK="$RUN_DIR/app-debug.apk"
TERMINAL_RELEASE_GATE="${TERMINAL_RELEASE_GATE:-0}"
TERMINAL_RELEASE_RUN_ID="$RUN_ID-terminal-release"
TERMINAL_WORKBENCH_LOG_ROOT="$ROOT_DIR/build/terminal-workbench"
REAL_AGENT_RELEASE_GATE_RUN_ID="$RUN_ID-real-agent-release-gate"
REAL_AGENT_RELEASE_GATE_LOG_ROOT="$ROOT_DIR/build/real-agent-release-gate"
REAL_AGENT_RELEASE_GATE_RUN_DIR="$REAL_AGENT_RELEASE_GATE_LOG_ROOT/$REAL_AGENT_RELEASE_GATE_RUN_ID"
REAL_AGENT_COMPOSE_FILE="${REAL_AGENT_COMPOSE_FILE:-tests/docker/real-agent/compose.yml}"
REAL_AGENT_RELEASE_GATE_TEST_CLASS="com.pocketshell.app.proof.RealAgentReleaseGateTest"

# Issue #150: shared health-status polling helper. Provides
# `wait_for_container_healthy` used by the real-agent gate below.
source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"
LONG_RUNNING_TEST="${LONG_RUNNING_TEST:-0}"
LONG_RUNNING_TEST_RUN_ID="$RUN_ID-long-running"
LONG_RUNNING_TEST_LOG_ROOT="$ROOT_DIR/build/long-running-session"
LONG_RUNNING_TEST_RUN_DIR="$LONG_RUNNING_TEST_LOG_ROOT/$LONG_RUNNING_TEST_RUN_ID"
LONG_RUNNING_TEST_CLASS="com.pocketshell.app.proof.LongRunningSessionStabilityTest"
LONG_RUNNING_COMPOSE_FILE="${LONG_RUNNING_COMPOSE_FILE:-tests/docker/docker-compose.yml}"
ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"

usage() {
  cat <<'USAGE'
Usage: scripts/release-emulator-validation.sh

Runs the required emulator-only pre-tag release validation from clean, pushed
main and writes a summary for scripts/push-release-tag.sh.

Acquires an exclusive `flock` on `build/.avd-lock` (relative to the repo
root) before touching the emulator so that parallel-worktree gate runs
serialise on the shared local AVD. If another emulator-touching gate script
is running, this invocation blocks until that script exits. The lock is
released automatically on script exit. See issue #182.

Required state:
  - current branch is main
  - worktree and index are clean
  - HEAD equals origin/main

Environment overrides:
  POCKETSHELL_AVD_LOCK_FILE
      Override the lock file path (default: <repo-root>/build/.avd-lock).
  RELEASE_VALIDATION_SKIP_MAIN_GUARD=1
      Skip the clean pushed-main guard for CI workflow_dispatch runs where the
      checkout is intentionally detached.
  TERMINAL_RELEASE_GATE=1
      Also run the optional high-confidence terminal release gate. This starts
      the real-agent Docker target, SSHes into it from the emulator, drives real
      interactive agent CLI screens, validates terminal artifacts, and runs the
      additional RealAgentReleaseGateTest against the same real-agent fixture to
      assert on visible CLI output and on the JSONL conversation logs the CLIs
      write back to disk.
  LONG_RUNNING_TEST=1
      Also run the opt-in 10-minute foreground hold regression
      (LongRunningSessionStabilityTest). Brings up the deterministic Docker
      `agents` service on host port 2222, attaches a tmux session through the
      emulator, sends a tick every 2 minutes for the full 10 minutes, and
      asserts on zero SSH transport teardown events plus < 50 MB memory growth
      via dumpsys meminfo. The test alone adds ~11 minutes of wall time, hence
      the opt-in.

Artifacts:
  build/release-emulator-validation/<run-id>/summary.md
  build/release-emulator-validation/<run-id>/app-debug.apk
  build/pre-release-confidence-gate/<run-id>-pre-release/
  build/terminal-workbench/<run-id>-terminal-release/ (optional)
  build/real-agent-release-gate/<run-id>-real-agent-release-gate/ (optional)
  build/long-running-session/<run-id>-long-running/ (optional)
  build/phone-dogfood/<run-id>-terminal-lab/
  build/phone-dogfood/<run-id>-tmux-existing-session/
  build/phone-dogfood/<run-id>-setup-detection/
  build/dogfood-visual-pass/<run-id>-visual-audit/
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Summary: %s\n' "$SUMMARY_PATH" >&2
  exit 1
}

require_clean_pushed_main() {
  if [[ "${RELEASE_VALIDATION_SKIP_MAIN_GUARD:-0}" == "1" ]]; then
    printf 'Skipping clean pushed-main guard because RELEASE_VALIDATION_SKIP_MAIN_GUARD=1\n'
    return 0
  fi

  local branch
  branch="$(git branch --show-current)"
  [[ "$branch" == "main" ]] || fail "release validation must run from main, not '$branch'"
  git diff --quiet || fail "worktree has unstaged changes"
  git diff --cached --quiet || fail "index has staged changes"
  [[ -z "$(git ls-files --others --exclude-standard)" ]] ||
    fail "worktree has untracked files"
  git fetch --quiet origin main
  local local_sha
  local local_origin_sha
  local_sha="$(git rev-parse HEAD)"
  local_origin_sha="$(git rev-parse origin/main)"
  [[ "$local_sha" == "$local_origin_sha" ]] ||
    fail "HEAD ($local_sha) must match origin/main ($local_origin_sha)"
}

write_summary_header() {
  mkdir -p "$RUN_DIR"
  {
    printf '# PocketShell Release Emulator Validation\n\n'
    printf 'Generated: %s\n' "$(date -Is)"
    printf 'Commit SHA: %s\n' "$(git rev-parse HEAD)"
    printf 'Branch: %s\n' "$(git branch --show-current)"
    printf 'Automated status: RUNNING\n'
    printf 'Visual audit inspected: no\n'
    printf 'Optional terminal release gate: %s\n' "$([[ "$TERMINAL_RELEASE_GATE" == "1" ]] && printf enabled || printf skipped)"
    printf 'Optional long-running session hold: %s\n' "$([[ "$LONG_RUNNING_TEST" == "1" ]] && printf enabled || printf skipped)"
    printf '\n## Required Artifacts\n\n'
  } > "$SUMMARY_PATH"
}

record_artifact() {
  local label="$1"
  local path="$2"
  printf -- '- %s: `%s`\n' "$label" "$path" >> "$SUMMARY_PATH"
}

run_required() {
  local label="$1"
  local artifact="$2"
  shift 2
  printf '\n[%s]\n' "$label"
  printf 'Command:'
  printf ' %q' "$@"
  printf '\nArtifact: %s\n' "$artifact"
  record_artifact "$label" "$artifact"
  "$@" || {
    sed -i 's/^Automated status: RUNNING$/Automated status: FAIL/' "$SUMMARY_PATH"
    fail "$label failed"
  }
}

publish_validated_apk() {
  [[ -f "$PRE_RELEASE_GATE_APK" ]] ||
    fail "validated debug APK was not created by the pre-release gate at $PRE_RELEASE_GATE_APK"
  cp "$PRE_RELEASE_GATE_APK" "$VALIDATED_APK"
  record_artifact "tested debug APK" "build/release-emulator-validation/$RUN_ID/app-debug.apk"
}

# Run RealAgentReleaseGateTest against the same real-agent Docker fixture the
# terminal-workbench step exercises. The workbench step covers
# viewport/visible-text capture; this additional step asserts on real-agent CLI
# output (deterministic visible substring) AND on the JSONL conversation log
# the CLIs write back to disk. The two checks are intentionally complementary:
# either one failing should block a release tag.
run_real_agent_release_gate_instrumentation() {
  local run_dir="$REAL_AGENT_RELEASE_GATE_RUN_DIR"
  local instrumentation_log="$run_dir/instrumentation.log"
  local docker_log="$run_dir/docker-real-agents.log"
  local ssh_log="$run_dir/docker-ssh-readiness.log"
  local logcat="$run_dir/logcat.txt"
  mkdir -p "$run_dir"

  printf '\n[real-agent release gate instrumentation]\n'
  printf 'Test class: %s\n' "$REAL_AGENT_RELEASE_GATE_TEST_CLASS"
  printf 'Artifact root: %s\n' "$run_dir"

  # Bring up (or verify) the real-agent compose service.
  docker compose -f "$REAL_AGENT_COMPOSE_FILE" up -d --build real-agents \
    2>&1 | tee "$run_dir/docker-up.log"

  # Issue #150: wait on the compose `healthcheck:` block via
  # `docker inspect`, not a host-side SSH retry loop. Keep one follow-up
  # SSH probe so the readiness log still records the real-agent CLI
  # sanity check (`claude --version && codex --version`).
  local ssh_key="$ROOT_DIR/tests/docker/test_key"
  chmod 600 "$ssh_key" 2>/dev/null || true
  if ! wait_for_container_healthy "$REAL_AGENT_COMPOSE_FILE" real-agents "$ssh_log" 60; then
    printf 'FAIL: real-agent SSH fixture did not become healthy (port 2240)\n' >&2
    tail -n 80 "$ssh_log" >&2 || true
    return 1
  fi
  {
    printf '[%s] health=healthy; running follow-up SSH sanity probe\n' "$(date -Is)"
    ssh \
      -i "$ssh_key" \
      -p 2240 \
      -o BatchMode=yes \
      -o ConnectTimeout=3 \
      -o ConnectionAttempts=1 \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      testuser@127.0.0.1 \
      'claude --version && codex --version'
  } >> "$ssh_log" 2>&1 || {
    printf 'FAIL: real-agent SSH fixture reported healthy but follow-up SSH probe failed (port 2240)\n' >&2
    tail -n 80 "$ssh_log" >&2 || true
    return 1
  }

  # Clear logcat so the captured slice belongs to this instrumentation only.
  "$ADB" logcat -c >/dev/null 2>&1 || true

  set +e
  "$ADB" shell am instrument -w -r \
    -e class "$REAL_AGENT_RELEASE_GATE_TEST_CLASS" \
    -e pocketshellRealAgentReleaseGate 1 \
    com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner \
    2>&1 | tee "$instrumentation_log"
  local instrumentation_status="${PIPESTATUS[0]}"
  set -e

  # Capture diagnostics regardless of outcome so reviewers can audit.
  docker compose -f "$REAL_AGENT_COMPOSE_FILE" logs --no-color --timestamps real-agents \
    > "$docker_log" 2>&1 || true
  "$ADB" logcat -d -v threadtime -t 6000 > "$logcat" 2>&1 || true

  if [[ "$instrumentation_status" -ne 0 ]]; then
    printf 'FAIL: RealAgentReleaseGateTest instrumentation exited %s\n' "$instrumentation_status" >&2
    return "$instrumentation_status"
  fi
  if ! grep -q 'INSTRUMENTATION_CODE: -1' "$instrumentation_log"; then
    printf 'FAIL: RealAgentReleaseGateTest did not report INSTRUMENTATION_CODE: -1\n' >&2
    return 1
  fi
  if ! grep -q 'OK (' "$instrumentation_log"; then
    printf 'FAIL: RealAgentReleaseGateTest did not report an OK summary\n' >&2
    return 1
  fi
  return 0
}

# Run LongRunningSessionStabilityTest against the deterministic Docker `agents`
# fixture (#148). Holds the activity in RESUMED for ~10 minutes, sends a tick
# every 2 minutes, and asserts on zero SSH transport teardown events and
# < 50 MB memory growth via dumpsys meminfo. The on-device test gates itself
# behind the instrumentation arg `pocketshellLongRunningTest=1`, so passing the
# arg is what opts the run in; without it the test class is silently skipped.
#
# Artifact bundle includes the captured logcat slice, the meminfo summary, and
# the run docker logs so the reviewer can audit reconnect counters and
# memory growth without needing to rerun the 10-minute hold.
run_long_running_session_instrumentation() {
  local run_dir="$LONG_RUNNING_TEST_RUN_DIR"
  local instrumentation_log="$run_dir/instrumentation.log"
  local docker_log="$run_dir/docker-agents.log"
  local logcat="$run_dir/logcat.txt"
  local artifacts_dir="$run_dir/artifacts/long-running-session"
  mkdir -p "$run_dir" "$artifacts_dir"

  printf '\n[long-running session stability instrumentation]\n'
  printf 'Test class: %s\n' "$LONG_RUNNING_TEST_CLASS"
  printf 'Artifact root: %s\n' "$run_dir"

  # Bring up (or verify) the deterministic agents compose service on port 2222.
  docker compose -f "$LONG_RUNNING_COMPOSE_FILE" up -d agents \
    2>&1 | tee "$run_dir/docker-up.log"

  # Clear logcat on the device before the test starts so the captured slice is
  # exclusively the run window. The instrumentation also clears logcat
  # internally for the reconnect-counter parse, but pre-clearing here keeps the
  # post-run pull short.
  "$ADB" logcat -c >/dev/null 2>&1 || true

  set +e
  # The on-device test gates picker round-trips on
  # TerminalTestTimeouts.terminalVisibilityTimeoutMs(), which is 60 s
  # locally and 180 s when `pocketshellCi=true` is set. The long-running
  # gate runs unattended for ~11 minutes, so we always opt into the
  # generous deadline — under heavy worktree contention the host picker
  # probe alone has been observed to exceed 30 s, and the 10-minute hold
  # alone is far more expensive than the extra slack on the picker.
  "$ADB" shell am instrument -w -r \
    -e class "$LONG_RUNNING_TEST_CLASS" \
    -e pocketshellLongRunningTest 1 \
    -e pocketshellCi true \
    com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner \
    2>&1 | tee "$instrumentation_log"
  local instrumentation_status="${PIPESTATUS[0]}"
  set -e

  # Capture diagnostics regardless of outcome so reviewers can audit.
  docker compose -f "$LONG_RUNNING_COMPOSE_FILE" logs --no-color --timestamps agents \
    > "$docker_log" 2>&1 || true
  "$ADB" logcat -d -v threadtime -t 60000 > "$logcat" 2>&1 || true

  # Pull the device artifact bundle written by the instrumentation
  # (long-running-summary.txt, captured logcat tail, final visible
  # transcript) into the run dir so summary.md can link directly to it
  # and the reviewer does not need to rerun the 10-minute hold to audit.
  "$ADB" pull \
    "/sdcard/Android/media/com.pocketshell.app/additional_test_output/long-running-session" \
    "$artifacts_dir" \
    >/dev/null 2>&1 || true

  if [[ "$instrumentation_status" -ne 0 ]]; then
    printf 'FAIL: LongRunningSessionStabilityTest instrumentation exited %s\n' "$instrumentation_status" >&2
    return "$instrumentation_status"
  fi
  if ! grep -q 'INSTRUMENTATION_CODE: -1' "$instrumentation_log"; then
    printf 'FAIL: LongRunningSessionStabilityTest did not report INSTRUMENTATION_CODE: -1\n' >&2
    return 1
  fi
  if ! grep -q 'OK (' "$instrumentation_log"; then
    printf 'FAIL: LongRunningSessionStabilityTest did not report an OK summary\n' >&2
    return 1
  fi
  return 0
}

require_clean_pushed_main
write_summary_header

run_required \
  "pre-release confidence gate" \
  "build/pre-release-confidence-gate/$PRE_RELEASE_RUN_ID/" \
  env LOG_ROOT="$PRE_RELEASE_GATE_LOG_ROOT" RUN_ID="$PRE_RELEASE_RUN_ID" scripts/pre-release-confidence-gate.sh

if [[ "$TERMINAL_RELEASE_GATE" == "1" ]]; then
  run_required \
    "optional terminal release gate" \
    "build/terminal-workbench/$TERMINAL_RELEASE_RUN_ID/" \
    env LOG_ROOT="$TERMINAL_WORKBENCH_LOG_ROOT" RUN_ID="$TERMINAL_RELEASE_RUN_ID" REAL_AGENTS=1 scripts/terminal-workbench.sh

  # Additive real-agent CLI + JSONL release gate (#146). The workbench step
  # above captures viewport/visible-text from the real-agent fixture; this
  # extra step exercises the same fixture's installed Claude / Codex CLIs
  # end-to-end and asserts on the on-disk JSONL conversation log.
  run_required \
    "real-agent CLI release gate" \
    "build/real-agent-release-gate/$REAL_AGENT_RELEASE_GATE_RUN_ID/" \
    run_real_agent_release_gate_instrumentation
fi

# Additive 10-minute foreground hold (#148). Mirrors the TERMINAL_RELEASE_GATE
# pattern above: a separate, independently opt-in branch that adds a single
# heavy regression suite without changing the default release-gate behaviour.
# When LONG_RUNNING_TEST is unset or 0 the entire block is skipped — the
# default release validation flow is unchanged.
if [[ "$LONG_RUNNING_TEST" == "1" ]]; then
  run_required \
    "optional long-running session hold" \
    "build/long-running-session/$LONG_RUNNING_TEST_RUN_ID/" \
    run_long_running_session_instrumentation
fi

run_required \
  "terminal-lab phone dogfood" \
  "build/phone-dogfood/$RUN_ID-terminal-lab/" \
  env RUN_ID="$RUN_ID-terminal-lab" scripts/phone-dogfood.sh terminal-lab

run_required \
  "tmux existing-session phone dogfood" \
  "build/phone-dogfood/$RUN_ID-tmux-existing-session/" \
  env RUN_ID="$RUN_ID-tmux-existing-session" scripts/phone-dogfood.sh tmux-existing-session

run_required \
  "setup-detection phone dogfood matrix" \
  "build/phone-dogfood/$RUN_ID-setup-detection/" \
  env RUN_ID="$RUN_ID-setup-detection" scripts/phone-dogfood.sh setup-detection

run_required \
  "visual-audit screenshot capture" \
  "build/dogfood-visual-pass/$RUN_ID-visual-audit/" \
  env RUN_ID="$RUN_ID-visual-audit" scripts/capture-dogfood-screenshots.sh

publish_validated_apk

{
  printf '\n## Release Notes Checklist\n\n'
  printf -- '- [ ] Attach or link every artifact directory listed above in the issue and tag notes.\n'
  if [[ "$TERMINAL_RELEASE_GATE" == "1" ]]; then
    printf -- '- [ ] Inspect `build/terminal-workbench/%s/artifact-summary.txt` and the authoritative `*-viewport.png` renders before treating terminal usability as release-ready.\n' "$TERMINAL_RELEASE_RUN_ID"
    printf -- '- [ ] Inspect `build/real-agent-release-gate/%s/instrumentation.log` plus the Docker/logcat artifacts in the same directory to confirm the real Claude/Codex CLI run was healthy.\n' "$REAL_AGENT_RELEASE_GATE_RUN_ID"
  else
    printf -- '- [ ] Optional terminal release gate was skipped. Run `TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh` when terminal usability is in release scope.\n'
  fi
  if [[ "$LONG_RUNNING_TEST" == "1" ]]; then
    printf -- '- [ ] Inspect `build/long-running-session/%s/artifacts/long-running-session/long-running-summary.txt` for reconnect counters, memory growth, and per-tick latencies before treating extended-foreground stability as release-ready.\n' "$LONG_RUNNING_TEST_RUN_ID"
  else
    printf -- '- [ ] Optional long-running session hold was skipped. Run `LONG_RUNNING_TEST=1 scripts/release-emulator-validation.sh` when extended-foreground stability is in release scope.\n'
  fi
  printf -- '- [ ] Download the tested debug APK from `release-emulator-validation/%s/app-debug.apk` inside the validation artifact, or `build/release-emulator-validation/%s/app-debug.apk` locally.\n' "$RUN_ID" "$RUN_ID"
  printf -- '- [ ] Inspect `build/dogfood-visual-pass/%s-visual-audit/screenshots/dogfood-visual-pass/` for release blockers.\n' "$RUN_ID"
  printf -- '- [ ] Treat physical phone testing as final user acceptance only; emulator/Docker validation catches basic release blockers before tagging.\n'
} >> "$SUMMARY_PATH"

sed -i 's/^Automated status: RUNNING$/Automated status: PASS/' "$SUMMARY_PATH"

printf '\nPASS: release emulator validation completed\n'
printf 'Summary: %s\n' "$SUMMARY_PATH"
