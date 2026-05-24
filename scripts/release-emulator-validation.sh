#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

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

usage() {
  cat <<'USAGE'
Usage: scripts/release-emulator-validation.sh

Runs the required emulator-only pre-tag release validation from clean, pushed
main and writes a summary for scripts/push-release-tag.sh.

Required state:
  - current branch is main
  - worktree and index are clean
  - HEAD equals origin/main

Environment overrides:
  RELEASE_VALIDATION_SKIP_MAIN_GUARD=1
      Skip the clean pushed-main guard for CI workflow_dispatch runs where the
      checkout is intentionally detached.
  TERMINAL_RELEASE_GATE=1
      Also run the optional high-confidence terminal release gate. This starts
      the real-agent Docker target, SSHes into it from the emulator, drives real
      interactive agent CLI screens, and validates terminal artifacts.

Artifacts:
  build/release-emulator-validation/<run-id>/summary.md
  build/release-emulator-validation/<run-id>/app-debug.apk
  build/pre-release-confidence-gate/<run-id>-pre-release/
  build/terminal-workbench/<run-id>-terminal-release/ (optional)
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
  else
    printf -- '- [ ] Optional terminal release gate was skipped. Run `TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh` when terminal usability is in release scope.\n'
  fi
  printf -- '- [ ] Download the tested debug APK from `release-emulator-validation/%s/app-debug.apk` inside the validation artifact, or `build/release-emulator-validation/%s/app-debug.apk` locally.\n' "$RUN_ID" "$RUN_ID"
  printf -- '- [ ] Inspect `build/dogfood-visual-pass/%s-visual-audit/screenshots/dogfood-visual-pass/` for release blockers.\n' "$RUN_ID"
  printf -- '- [ ] Treat physical phone testing as final user acceptance only; emulator/Docker validation catches basic release blockers before tagging.\n'
} >> "$SUMMARY_PATH"

sed -i 's/^Automated status: RUNNING$/Automated status: PASS/' "$SUMMARY_PATH"

printf '\nPASS: release emulator validation completed\n'
printf 'Summary: %s\n' "$SUMMARY_PATH"
