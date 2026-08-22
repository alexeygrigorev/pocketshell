#!/usr/bin/env bash
# Issue #2239 AC9: source-matched real process-restart evidence.
#
# This wrapper deliberately delegates the device boundary to the repository's
# two-phase harness. Its job is to bind that run to one exact worktree HEAD,
# capture a complete relevant-source manifest before and after the run, and
# reject empty or drifting provenance rather than publishing a vacuous green.
# shellcheck disable=SC2317
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$ROOT_DIR/scripts/two-phase-android-instrumentation.sh"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
CACHE_BASE="${XDG_CACHE_HOME:-${HOME:?HOME is required}/.cache}"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$CACHE_BASE/pocketshell/evidence/issue-2239-process-restart}"
RUN_NAMESPACE="${RUN_NAMESPACE:-issue2239-process-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
RUN_DIR="${RUN_DIR:-$CACHE_BASE/pocketshell/evidence/android-process-restart/$RUN_NAMESPACE}"
META_DIR="${META_DIR:-$EVIDENCE_ROOT/$RUN_NAMESPACE}"
SUFFIX="${SUFFIX:-i2239process}"
BUILD_APKS="${BUILD_APKS:-1}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"
SOURCE_BASE_COMMIT="${SOURCE_BASE_COMMIT:-HEAD^}"

HEAD_SHA=""
BASE_SHA=""
PARENT_SHA=""
MERGE_BASE_SHA=""
RUN_RC=125
RUN_STARTED=0
FINAL_RC=125

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/issue-2239-process-restart.sh

Runs the real two-process LastSessionStore/tmux-generation proof through
scripts/two-phase-android-instrumentation.sh and writes a source-bound bundle.

Environment:
  ANDROID_SERIAL=<serial>       required; one isolated emulator serial
  SUFFIX=i2239process            suffixed target/test package identity
  RUN_NAMESPACE=<token>          unique device/artifact namespace
  RUN_DIR=<path>                 two-phase device artifact directory
  META_DIR=<path>                source/provenance artifact directory
  SOURCE_BASE_COMMIT=<rev>      comparison base, default HEAD^
  BUILD_APKS=1                  build exact worktree source before install
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
[[ $# -eq 0 ]] || { usage >&2; exit 2; }

[[ -x "$ADB" ]] || fail "adb is not executable: $ADB"
[[ -x "$HARNESS" ]] || fail "two-phase harness is not executable: $HARNESS"
[[ -n "$ANDROID_SERIAL" ]] || fail "ANDROID_SERIAL is required"
[[ "$ANDROID_SERIAL" != "emulator-5554" ]] \
  || fail "emulator-5554 is reserved by another issue lane"
[[ "$BUILD_APKS" == "0" || "$BUILD_APKS" == "1" ]] \
  || fail "BUILD_APKS must be 0 or 1"
[[ "$RUN_NAMESPACE" =~ ^[A-Za-z0-9._-]+$ ]] \
  || fail "RUN_NAMESPACE must match [A-Za-z0-9._-]+"
[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ && -n "$SUFFIX" ]] \
  || fail "SUFFIX must match [A-Za-z0-9._]+"
[[ "$ROOT_DIR" == "$PWD" || "$ROOT_DIR" == "$(pwd -P)" ]] \
  || fail "run from the target worktree: expected $ROOT_DIR, got $PWD"
[[ ! -e "$RUN_DIR" ]] || fail "RUN_DIR already exists: $RUN_DIR"
[[ ! -e "$META_DIR" ]] || fail "META_DIR already exists: $META_DIR"
mkdir -p "$(dirname "$RUN_DIR")" "$META_DIR"
chmod 700 "$META_DIR"

HEAD_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
BASE_SHA="$(git -C "$ROOT_DIR" rev-parse "$SOURCE_BASE_COMMIT^{commit}")"
PARENT_SHA="$(git -C "$ROOT_DIR" rev-parse "$HEAD_SHA^")"
MERGE_BASE_SHA="$(git -C "$ROOT_DIR" merge-base "$BASE_SHA" "$HEAD_SHA")"

if [[ -n "${EXPECTED_HEAD:-}" && "$HEAD_SHA" != "$EXPECTED_HEAD" ]]; then
  fail "HEAD changed before launch: expected $EXPECTED_HEAD, observed $HEAD_SHA"
fi
if [[ -n "${EXPECTED_BASE:-}" && "$BASE_SHA" != "$EXPECTED_BASE" ]]; then
  fail "base changed before launch: expected $EXPECTED_BASE, observed $BASE_SHA"
fi

# The runner itself may be the one intentional untracked file in this
# worktree. Every tracked product/source path must be clean; any other change
# makes a source-matched APK claim unsafe.
validate_worktree_status() {
  local status_line
  while IFS= read -r status_line; do
    [[ -z "$status_line" ]] && continue
    case "$status_line" in
      "?? scripts/issue-2239-process-restart.sh") ;;
      *)
        printf 'unexpected worktree change: %s\n' "$status_line" >&2
        return 1
        ;;
    esac
  done < <(git -C "$ROOT_DIR" status --porcelain=v1 --untracked-files=all)
  git -C "$ROOT_DIR" diff --quiet HEAD -- \
    app/src shared tools/pocketshell scripts
  git -C "$ROOT_DIR" diff --cached --quiet HEAD -- \
    app/src shared tools/pocketshell scripts
}

validate_worktree_status \
  || fail "tracked source is dirty or an unrelated worktree change is present"

source_files() {
  git -C "$ROOT_DIR" ls-files -- app/src shared tools/pocketshell scripts
}

hash_file_list() {
  local list_file="$1" hashes_file="$2" rel
  : > "$hashes_file"
  while IFS= read -r rel; do
    [[ -n "$rel" ]] || continue
    [[ -f "$ROOT_DIR/$rel" ]] \
      || { printf 'missing_source=%s\n' "$rel" >&2; return 1; }
    (cd "$ROOT_DIR" && sha256sum "$rel") >> "$hashes_file"
  done < "$list_file"
  [[ -s "$hashes_file" ]] || { echo "source hash manifest is empty" >&2; return 1; }
}

snapshot_sources() {
  local label="$1"
  local list_file="$META_DIR/source-files-$label.txt"
  local hashes_file="$META_DIR/source-hashes-$label.txt"
  source_files > "$list_file"
  [[ -s "$list_file" ]] || { echo "source file manifest is empty" >&2; return 1; }
  hash_file_list "$list_file" "$hashes_file" || return 1
  sha256sum "$hashes_file" | awk '{print $1}' > \
    "$META_DIR/source-aggregate-$label.sha256"
  [[ "$(wc -l < "$list_file" | tr -d ' ')" == \
      "$(wc -l < "$hashes_file" | tr -d ' ')" ]] \
    || { echo "source file/hash manifest count mismatch ($label)" >&2; return 1; }
  git -C "$ROOT_DIR" rev-parse HEAD > "$META_DIR/head-$label.txt"
  git -C "$ROOT_DIR" status --porcelain=v1 --untracked-files=all > \
    "$META_DIR/status-$label.txt"
  sha256sum "$ROOT_DIR/scripts/issue-2239-process-restart.sh" | \
    awk '{print $1}' > "$META_DIR/runner-sha256-$label.txt"
}

hash_changed_sources() {
  local rel
  git -C "$ROOT_DIR" diff --name-only "$BASE_SHA" "$HEAD_SHA" -- \
    app/src shared tools/pocketshell scripts > "$META_DIR/changed-source-files.txt"
  : > "$META_DIR/changed-source-hashes.txt"
  while IFS= read -r rel; do
    [[ -n "$rel" ]] || continue
    [[ -f "$ROOT_DIR/$rel" ]] || {
      printf 'missing_changed_source=%s\n' "$rel" >&2
      return 1
    }
    (cd "$ROOT_DIR" && sha256sum "$rel") >> \
      "$META_DIR/changed-source-hashes.txt"
  done < "$META_DIR/changed-source-files.txt"
  [[ -s "$META_DIR/changed-source-hashes.txt" ]] \
    || { echo "changed-source hash manifest is empty" >&2; return 1; }
}

write_provenance_start() {
  {
    printf 'worktree=%s\n' "$ROOT_DIR"
    printf 'head=%s\n' "$HEAD_SHA"
    printf 'parent=%s\n' "$PARENT_SHA"
    printf 'source_base=%s\n' "$BASE_SHA"
    printf 'merge_base=%s\n' "$MERGE_BASE_SHA"
    printf 'branch=%s\n' "$(git -C "$ROOT_DIR" symbolic-ref --short -q HEAD || echo DETACHED)"
    printf 'run_namespace=%s\n' "$RUN_NAMESPACE"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'meta_dir=%s\n' "$META_DIR"
    printf 'android_serial=%s\n' "$ANDROID_SERIAL"
    printf 'suffix=%s\n' "$SUFFIX"
    printf 'build_apks=%s\n' "$BUILD_APKS"
    printf 'harness=%s\n' "$HARNESS"
    printf 'source_scope=app/src,shared,tools/pocketshell,scripts\n'
    printf 'source_files_before=%s\n' \
      "$(wc -l < "$META_DIR/source-files-before.txt" | tr -d ' ')"
    printf 'source_hashes_before=%s\n' \
      "$META_DIR/source-hashes-before.txt"
    printf 'source_aggregate_before=%s\n' \
      "$(tr -d '[:space:]' < "$META_DIR/source-aggregate-before.sha256")"
    printf 'changed_source_files=%s\n' "$META_DIR/changed-source-files.txt"
    printf 'changed_source_hashes=%s\n' "$META_DIR/changed-source-hashes.txt"
  } > "$META_DIR/source-provenance.txt"
  {
    printf 'env ANDROID_SERIAL=%q BUILD_APKS=%q SUFFIX=%q RUN_NAMESPACE=%q RUN_DIR=%q EVIDENCE_ROOT=%q %q\n' \
      "$ANDROID_SERIAL" "$BUILD_APKS" "$SUFFIX" "$RUN_NAMESPACE" \
      "$RUN_DIR" "$CACHE_BASE/pocketshell/evidence/android-process-restart" \
      "$HARNESS"
  } > "$META_DIR/runner-command.txt"
  {
    printf 'runner_pid=%s\n' "$$"
    printf 'started_at=%s\n' "$(date --iso-8601=seconds)"
    printf 'unit=%s\n' "${RUNNER_UNIT:-direct-shell}"
    printf 'run_namespace=%s\n' "$RUN_NAMESPACE"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'serial=%s\n' "$ANDROID_SERIAL"
    printf 'avd_name=%s\n' "$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
    printf 'head=%s\n' "$HEAD_SHA"
    printf 'parent=%s\n' "$PARENT_SHA"
    printf 'source_base=%s\n' "$BASE_SHA"
    printf 'source_aggregate_before=%s\n' \
      "$(tr -d '[:space:]' < "$META_DIR/source-aggregate-before.sha256")"
  } > "$META_DIR/runner-start.txt"
}

validate_after_snapshot() {
  local before_aggregate after_aggregate before_head after_head
  before_aggregate="$(tr -d '[:space:]' < "$META_DIR/source-aggregate-before.sha256")"
  after_aggregate="$(tr -d '[:space:]' < "$META_DIR/source-aggregate-after.sha256")"
  before_head="$(tr -d '[:space:]' < "$META_DIR/head-before.txt")"
  after_head="$(tr -d '[:space:]' < "$META_DIR/head-after.txt")"
  [[ "$before_aggregate" =~ ^[[:xdigit:]]{64}$ ]] || return 1
  [[ "$after_aggregate" =~ ^[[:xdigit:]]{64}$ ]] || return 1
  [[ "$before_head" == "$HEAD_SHA" && "$after_head" == "$HEAD_SHA" ]] || return 1
  cmp -s "$META_DIR/source-files-before.txt" "$META_DIR/source-files-after.txt" || return 1
  cmp -s "$META_DIR/source-hashes-before.txt" "$META_DIR/source-hashes-after.txt" || return 1
  [[ "$before_aggregate" == "$after_aggregate" ]] || return 1
  cmp -s "$META_DIR/runner-sha256-before.txt" "$META_DIR/runner-sha256-after.txt" || return 1
  validate_worktree_status
}

write_runner_finish() {
  local process_dir_exists=false summary_result=ABSENT source_stable=false head_stable=false
  local status_stable=false before_aggregate=UNAVAILABLE after_aggregate=UNAVAILABLE
  [[ -d "$RUN_DIR" ]] && process_dir_exists=true
  [[ -f "$RUN_DIR/summary.txt" ]] && \
    summary_result="$(sed -n 's/^result=//p' "$RUN_DIR/summary.txt" | head -1)"
  if [[ -s "$META_DIR/source-aggregate-before.sha256" ]]; then
    before_aggregate="$(tr -d '[:space:]' < "$META_DIR/source-aggregate-before.sha256")"
  fi
  if [[ -s "$META_DIR/source-aggregate-after.sha256" ]]; then
    after_aggregate="$(tr -d '[:space:]' < "$META_DIR/source-aggregate-after.sha256")"
  fi
  if [[ "$before_aggregate" =~ ^[[:xdigit:]]{64}$ && \
        "$before_aggregate" == "$after_aggregate" ]]; then
    source_stable=true
  fi
  if [[ -s "$META_DIR/head-before.txt" && -s "$META_DIR/head-after.txt" && \
        "$(tr -d '[:space:]' < "$META_DIR/head-before.txt")" == "$HEAD_SHA" && \
        "$(tr -d '[:space:]' < "$META_DIR/head-after.txt")" == "$HEAD_SHA" ]]; then
    head_stable=true
  fi
  if [[ -f "$META_DIR/status-before.txt" && -f "$META_DIR/status-after.txt" ]] && \
      cmp -s "$META_DIR/status-before.txt" "$META_DIR/status-after.txt"; then
    status_stable=true
  fi
  {
    printf 'exit_code=%s\n' "$RUN_RC"
    printf 'finished_at=%s\n' "$(date --iso-8601=seconds)"
    printf 'runner_pid=%s\n' "$$"
    printf 'run_namespace=%s\n' "$RUN_NAMESPACE"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'meta_dir=%s\n' "$META_DIR"
    printf 'head=%s\nparent=%s\nsource_base=%s\nmerge_base=%s\n' \
      "$HEAD_SHA" "$PARENT_SHA" "$BASE_SHA" "$MERGE_BASE_SHA"
    printf 'source_aggregate_before=%s\n' "$before_aggregate"
    printf 'source_aggregate_after=%s\n' "$after_aggregate"
    printf 'source_hashes_stable=%s\n' "$source_stable"
    printf 'head_stable=%s\n' "$head_stable"
    printf 'status_stable=%s\n' "$status_stable"
    printf 'process_run_dir_exists=%s\n' "$process_dir_exists"
    printf 'harness_summary_result=%s\n' "$summary_result"
  } > "$META_DIR/runner-finish.txt"
}

write_final_manifest() {
  local verdict="$1"
  {
    printf 'verdict=%s\n' "$verdict"
    printf 'classification=%s\n' \
      "$([[ "$verdict" == PASS ]] && echo valid_source_matched_real_two_process_proof || echo invalid_or_blocked)"
    printf 'worktree=%s\nhead=%s\nparent=%s\nsource_base=%s\nmerge_base=%s\n' \
      "$ROOT_DIR" "$HEAD_SHA" "$PARENT_SHA" "$BASE_SHA" "$MERGE_BASE_SHA"
    printf 'meta_dir=%s\nrun_dir=%s\n' "$META_DIR" "$RUN_DIR"
    printf 'runner_command=%s\n' "$META_DIR/runner-command.txt"
    printf 'runner_start=%s\nrunner_finish=%s\n' \
      "$META_DIR/runner-start.txt" "$META_DIR/runner-finish.txt"
    printf 'source_provenance=%s\n' "$META_DIR/source-provenance.txt"
    printf 'source_files_before=%s\nsource_files_after=%s\n' \
      "$META_DIR/source-files-before.txt" "$META_DIR/source-files-after.txt"
    printf 'source_hashes_before=%s\nsource_hashes_after=%s\n' \
      "$META_DIR/source-hashes-before.txt" "$META_DIR/source-hashes-after.txt"
    printf 'source_aggregate_before_file=%s\nsource_aggregate_after_file=%s\n' \
      "$META_DIR/source-aggregate-before.sha256" "$META_DIR/source-aggregate-after.sha256"
    printf 'two_phase_summary=%s\n' "$RUN_DIR/summary.txt"
    printf 'phase_1_artifact=%s\nphase_2_artifact=%s\nforce_stop_evidence=%s\n' \
      "$RUN_DIR/phase-1.txt" "$RUN_DIR/phase-2.txt" "$RUN_DIR/force-stop-evidence.txt"
    printf 'mutation_control_unchanged=true\n'
  } > "$META_DIR/real-proof-final-manifest.txt"
}

finish() {
  local trap_rc=$?
  local provenance_ok=0
  set +e
  if [[ "$RUN_STARTED" == "1" ]]; then
    snapshot_sources after
  else
    : > "$META_DIR/source-files-after.txt"
    : > "$META_DIR/source-hashes-after.txt"
    : > "$META_DIR/source-aggregate-after.sha256"
    git -C "$ROOT_DIR" rev-parse HEAD > "$META_DIR/head-after.txt"
    git -C "$ROOT_DIR" status --porcelain=v1 --untracked-files=all > \
      "$META_DIR/status-after.txt"
    sha256sum "$ROOT_DIR/scripts/issue-2239-process-restart.sh" | \
      awk '{print $1}' > "$META_DIR/runner-sha256-after.txt"
  fi
  if [[ "$RUN_RC" == "125" && "$trap_rc" != "0" ]]; then
    RUN_RC="$trap_rc"
  fi
  if [[ -s "$META_DIR/source-aggregate-before.sha256" && \
        -s "$META_DIR/source-aggregate-after.sha256" ]]; then
    validate_after_snapshot && provenance_ok=1
  fi
  write_runner_finish
  if [[ "$RUN_RC" == "0" && "$provenance_ok" == "1" && \
        -f "$RUN_DIR/summary.txt" && \
        "$(sed -n 's/^result=//p' "$RUN_DIR/summary.txt" | head -1)" == "PASS" ]]; then
    FINAL_RC=0
    write_final_manifest PASS
  else
    FINAL_RC="${RUN_RC:-1}"
    [[ "$FINAL_RC" == "0" ]] && FINAL_RC=1
    write_final_manifest BLOCKED_OR_FAILED
  fi
  find "$META_DIR" -maxdepth 1 -type f ! -name SHA256SUMS -print0 \
    | sort -z | xargs -0 sha256sum > "$META_DIR/SHA256SUMS"
  exit "$FINAL_RC"
}

trap finish EXIT

source_files > "$META_DIR/source-files-before.txt"
hash_file_list "$META_DIR/source-files-before.txt" \
  "$META_DIR/source-hashes-before.txt"
sha256sum "$META_DIR/source-hashes-before.txt" | awk '{print $1}' > \
  "$META_DIR/source-aggregate-before.sha256"
git -C "$ROOT_DIR" rev-parse HEAD > "$META_DIR/head-before.txt"
git -C "$ROOT_DIR" status --porcelain=v1 --untracked-files=all > \
  "$META_DIR/status-before.txt"
sha256sum "$ROOT_DIR/scripts/issue-2239-process-restart.sh" | \
  awk '{print $1}' > "$META_DIR/runner-sha256-before.txt"
hash_changed_sources
write_provenance_start

RUN_STARTED=1
set +e
env \
  ANDROID_SERIAL="$ANDROID_SERIAL" \
  BUILD_APKS="$BUILD_APKS" \
  SUFFIX="$SUFFIX" \
  RUN_NAMESPACE="$RUN_NAMESPACE" \
  RUN_DIR="$RUN_DIR" \
  EVIDENCE_ROOT="$CACHE_BASE/pocketshell/evidence/android-process-restart" \
  "$HARNESS" > "$META_DIR/two-phase.log" 2>&1
RUN_RC=$?
set -e
exit "$RUN_RC"
