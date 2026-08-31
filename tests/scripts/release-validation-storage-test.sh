#!/usr/bin/env bash
set -uo pipefail

# Release-validation disk budget + retained-output regression harness (#2055).
#
# The reported failure was not an abstract arithmetic bug: the real release
# entry points started an isolated copy on a filesystem with too little room,
# then Gradle failed with ENOSPC.  The real-entry cases therefore drive the
# scripts with fake statvfs reports and assert they refuse before either the
# AVD lock or rsync is reached. The partial-copy regression additionally takes
# a real flock on a fixture-local path, then proves the storage EXIT handler
# survives that acquisition. No filesystem is filled and no Gradle, Docker,
# emulator, or machine-shared lock is used.
#
# The retention cases use generated trees under mktemp.  In particular, the
# non-writable fixture is the live recurrence from the issue: owner-readable
# 0444 files below 0555 directories in an isolated copied worktree.  The
# cleanup must make that exact safe-listed output writable, remove it, and
# preserve the adjacent summary/logs and similarly named source-worktree.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-release-storage-test.XXXXXX")"
trap 'chmod -R u+rwX "$WORK_DIR" 2>/dev/null || true; rm -rf "$WORK_DIR"' EXIT

unset CI \
  POCKETSHELL_AVD_LOCK_ACQUIRED \
  POCKETSHELL_AVD_LOCK_FILE \
  POCKETSHELL_GATE_ISOLATED_COPY \
  POCKETSHELL_GATE_SOURCE_ROOT \
  POCKETSHELL_RELEASE_RETENTION_OWNER_PID \
  POCKETSHELL_RELEASE_RETENTION_OWNER_START \
  POCKETSHELL_TEST_MEM

CASES=0

# Every case is independently selectable for mutation evidence, so load the
# production helper here rather than relying on one earlier case having run.
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/release-validation-storage.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

pass_case() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

run_case() {
  local name="$1"
  local function_name="$2"
  if [[ -n "${POCKETSHELL_RELEASE_STORAGE_TEST_CASE:-}" &&
    "$POCKETSHELL_RELEASE_STORAGE_TEST_CASE" != "$name" ]]; then
    return 0
  fi
  "$function_name"
}

make_stat_stub() {
  local bin_dir="$1"
  local available_blocks="$2"
  mkdir -p "$bin_dir"
  cat > "$bin_dir/stat" <<STAT
#!/usr/bin/env bash
if [[ "\${1:-}" == "-f" ]]; then
  # scripts/lib/disk-preflight.sh asks for available blocks + fragment size.
  printf '%s %s\n' '$available_blocks' '1048576'
  exit 0
fi
exec /usr/bin/stat "\$@"
STAT
  chmod +x "$bin_dir/stat"
}

make_stateful_stat_stub() {
  local bin_dir="$1"
  local state_file="$2"
  local first_available_blocks="$3"
  local later_available_blocks="$4"
  mkdir -p "$bin_dir"
  cat > "$bin_dir/stat" <<STAT
#!/usr/bin/env bash
if [[ "\${1:-}" == "-f" ]]; then
  calls=0
  if [[ -f '$state_file' ]]; then
    calls="\$(cat '$state_file')"
  fi
  calls="\$((calls + 1))"
  printf '%s\n' "\$calls" > '$state_file'
  if (( calls == 1 )); then
    printf '%s %s\n' '$first_available_blocks' '1048576'
  else
    printf '%s %s\n' '$later_available_blocks' '1048576'
  fi
  exit 0
fi
exec /usr/bin/stat "\$@"
STAT
  chmod +x "$bin_dir/stat"
}

make_pre_release_sandbox() {
  local sandbox="$1"
  local root="$sandbox/root"
  mkdir -p \
    "$root/scripts/lib" \
    "$root/app" \
    "$root/shared/core-storage/src/main/java/com/pocketshell/core/storage" \
    "$sandbox/bin"
  cp "$ROOT_DIR/scripts/pre-release-confidence-gate.sh" "$root/scripts/"
  cp "$ROOT_DIR"/scripts/lib/*.sh "$root/scripts/lib/"
  chmod +x "$root/scripts/pre-release-confidence-gate.sh"
  cat > "$root/app/build.gradle.kts" <<'GRADLE'
android {
  defaultConfig {
    versionName = "test"
  }
}
GRADLE
  cat > "$root/shared/core-storage/src/main/java/com/pocketshell/core/storage/AppDatabase.kt" <<'KOTLIN'
const val APP_DATABASE_SCHEMA_VERSION = 1
KOTLIN
  printf '%s\n' "$root"
}

pre_release_refuses_low_space_before_isolated_copy_or_gradle() {
  local sandbox="$WORK_DIR/pre-release-low"
  local root
  root="$(make_pre_release_sandbox "$sandbox")"
  make_stat_stub "$sandbox/bin" 1024
  cat > "$sandbox/bin/rsync" <<'RSYNC'
#!/usr/bin/env bash
printf 'rsync reached\n' > "$RSYNC_MARKER"
exit 88
RSYNC
  chmod +x "$sandbox/bin/rsync"

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
    RSYNC_MARKER="$sandbox/rsync-reached" \
    POCKETSHELL_AVD_LOCK_ACQUIRED=1 \
    LOG_ROOT="$root/build/pre-release-confidence-gate" \
    RUN_ID=low-space \
    ADB="$sandbox/missing-adb" \
    EMULATOR="$sandbox/missing-emulator" \
      "$root/scripts/pre-release-confidence-gate.sh" 2>&1
  )" || rc=$?

  (( rc == 76 )) ||
    fail "pre-release gate did not return the disk-preflight verdict before starting (rc=$rc): $output"
  [[ ! -e "$sandbox/rsync-reached" ]] ||
    fail "pre-release gate started its isolated rsync copy on insufficient disk"
  [[ "$output" == *"RELEASE DISK PREFLIGHT FAILED (issue #2055)"* ]] ||
    fail "pre-release refusal did not name the release-specific guard: $output"
  [[ "$output" == *"scripts/disk-cleanup.sh --apply"* ]] ||
    fail "pre-release refusal did not print the safe cleanup command: $output"
  pass_case "pre-release gate refuses insufficient disk before isolated rsync/Gradle"
}

make_release_wrapper_sandbox() {
  local sandbox="$1"
  local root="$sandbox/root"
  mkdir -p "$root/scripts/lib" "$root/tests/docker/lib" "$sandbox/bin"
  cp "$ROOT_DIR/scripts/release-emulator-validation.sh" "$root/scripts/"
  cp "$ROOT_DIR"/scripts/lib/*.sh "$root/scripts/lib/"
  cp "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh" "$root/tests/docker/lib/"
  chmod +x "$root/scripts/release-emulator-validation.sh"
  printf '%s\n' "$root"
}

release_wrapper_refuses_low_space_before_avd_lock() {
  local sandbox="$WORK_DIR/release-low"
  local root
  root="$(make_release_wrapper_sandbox "$sandbox")"
  make_stat_stub "$sandbox/bin" 1024

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
    POCKETSHELL_AVD_LOCK_FILE="$sandbox/avd.lock" \
    LOG_ROOT="$sandbox/release-logs" \
      "$root/scripts/release-emulator-validation.sh" 2>&1
  )" || rc=$?

  (( rc == 76 )) ||
    fail "release wrapper did not return the disk-preflight verdict (rc=$rc): $output"
  [[ ! -e "$sandbox/avd.lock" ]] ||
    fail "release wrapper claimed the shared AVD lock before refusing low disk"
  [[ "$output" == *"RELEASE DISK PREFLIGHT FAILED (issue #2055)"* ]] ||
    fail "release-wrapper refusal did not name the release-specific guard: $output"
  pass_case "release wrapper refuses insufficient disk before the AVD lock"
}

release_floor_is_sized_above_the_canonical_single_run_floor() {
  # shellcheck disable=SC1091
  source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
  # shellcheck disable=SC1091
  source "$ROOT_DIR/scripts/lib/release-validation-storage.sh"
  local release_floor
  release_floor="$(pocketshell_release_disk_min_free_mb)" ||
    fail "release storage helper could not report its floor"
  (( release_floor == 24576 )) ||
    fail "release floor drifted from the measured 24 GiB budget: $release_floor MiB"
  (( release_floor > POCKETSHELL_DISK_PREFLIGHT_DEFAULT_MIN_FREE_MB )) ||
    fail "release floor ($release_floor) lowered/equaled the canonical single-run floor ($POCKETSHELL_DISK_PREFLIGHT_DEFAULT_MIN_FREE_MB)"
  grep -q '| below 24 GiB | refuse the release validation' "$ROOT_DIR/docs/testing.md" ||
    fail "docs/testing.md does not state the 24 GiB release floor"
  pass_case "release floor is a fixed 24 GiB and leaves the canonical 10 GiB floor intact"
}

hosted_workflow_establishes_the_release_storage_contract_before_the_avd() {
  local workflow="$ROOT_DIR/.github/workflows/release-emulator-validation.yml"
  local cleanup_line probe_line emulator_line step_body

  grep -q 'runs-on: ubuntu-latest' "$workflow" ||
    fail "manual release workflow no longer uses its supported standard hosted runner"
  cleanup_line="$(grep -n -m1 'name: Establish hosted release storage contract' "$workflow" | cut -d: -f1)"
  probe_line="$(grep -n -m1 'scripts/release-emulator-validation.sh --check-storage' "$workflow" | cut -d: -f1)"
  emulator_line="$(grep -n -m1 'uses: reactivecircus/android-emulator-runner@' "$workflow" | cut -d: -f1)"
  [[ "$cleanup_line" =~ ^[0-9]+$ && "$probe_line" =~ ^[0-9]+$ && "$emulator_line" =~ ^[0-9]+$ ]] ||
    fail "manual release workflow lacks an explicit hosted cleanup + release-storage probe"
  (( cleanup_line < probe_line && probe_line < emulator_line )) ||
    fail "hosted cleanup/probe must run before android-emulator-runner creates the AVD"

  step_body="$(sed -n "${cleanup_line},${probe_line}p" "$workflow")"
  for required in \
    'sudo rm -rf /usr/share/dotnet || true' \
    'sudo rm -rf /usr/local/lib/android/sdk/ndk || true' \
    'sudo rm -rf /opt/ghc /usr/local/.ghcup || true' \
    'sudo rm -rf /usr/local/share/boost || true' \
    'sudo rm -rf /opt/hostedtoolcache/CodeQL || true' \
    'sudo docker image prune -af || true' \
    '! -name "$keep_dir" -exec rm -rf {} + || true' \
    '! -x "$JAVA_HOME/bin/java"' \
    'scripts/release-emulator-validation.sh --check-storage'; do
    [[ "$step_body" == *"$required"* ]] ||
      fail "hosted release storage step lost required executable contract: $required"
  done
  pass_case "standard hosted workflow reclaims capacity and proves the 24 GiB contract before AVD creation"
}

storage_probe_passes_at_the_release_floor_without_taking_the_avd_lock() {
  local sandbox="$WORK_DIR/storage-probe"
  local root
  root="$(make_release_wrapper_sandbox "$sandbox")"
  make_stat_stub "$sandbox/bin" 24576

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
    POCKETSHELL_AVD_LOCK_FILE="$sandbox/avd.lock" \
      "$root/scripts/release-emulator-validation.sh" --check-storage 2>&1
  )" || rc=$?

  (( rc == 0 )) || fail "release storage-only probe failed at the exact floor (rc=$rc): $output"
  [[ ! -e "$sandbox/avd.lock" ]] ||
    fail "release storage-only probe took the shared AVD lock"
  [[ "$output" == *"Release storage contract satisfied"* ]] ||
    fail "release storage-only probe did not report its successful contract: $output"
  pass_case "release storage-only probe proves capacity without AVD, Gradle, Docker, or emulator work"
}

pre_release_rejects_the_repository_source_root_before_deletion() {
  local sandbox="$WORK_DIR/source-root-rejection"
  local root
  root="$(make_pre_release_sandbox "$sandbox")"
  mkdir -p "$root/gradle-home"
  printf 'must survive\n' > "$root/gradle-home/KEEP"
  make_stat_stub "$sandbox/bin" 1024

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
    POCKETSHELL_AVD_LOCK_ACQUIRED=1 \
    LOG_ROOT="$root" \
    RUN_ID=source-root \
    ADB="$sandbox/missing-adb" \
    EMULATOR="$sandbox/missing-emulator" \
      "$root/scripts/pre-release-confidence-gate.sh" 2>&1
  )" || rc=$?

  (( rc == 76 )) || fail "source-root override did not fail as a storage refusal (rc=$rc): $output"
  [[ -f "$root/gradle-home/KEEP" ]] ||
    fail "LOG_ROOT source-root substitution deleted an unrelated sentinel"
  [[ "$output" == *"REFUSING: release-validation storage root is not the canonical generated root"* ]] ||
    fail "source-root substitution was not rejected by the root provenance contract: $output"
  pass_case "pre-release LOG_ROOT rejects the repository source root before deletion"
}

release_wrapper_rejects_an_arbitrary_pre_release_root_before_deletion() {
  local sandbox="$WORK_DIR/arbitrary-root-rejection"
  local root other_repository arbitrary_root
  root="$(make_release_wrapper_sandbox "$sandbox")"
  other_repository="$sandbox/other-repository"
  arbitrary_root="$other_repository/build/pre-release-confidence-gate"
  mkdir -p "$arbitrary_root/gradle-home"
  printf 'must survive\n' > "$arbitrary_root/gradle-home/KEEP"
  # Even an internally valid, current-user-owned marker for ANOTHER repository
  # cannot authenticate an override. The entry script anchors to its own
  # ROOT_DIR before the lower-level marker is consulted.
  {
    printf 'kind=%s\n' "$POCKETSHELL_RELEASE_STORAGE_ROOT_KIND"
    printf 'repository_root=%s\n' "$other_repository"
    printf 'release_root=%s\n' "$arbitrary_root"
  } > "$arbitrary_root/$POCKETSHELL_RELEASE_STORAGE_ROOT_MARKER"
  make_stat_stub "$sandbox/bin" 1024

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
    POCKETSHELL_AVD_LOCK_FILE="$sandbox/avd.lock" \
    PRE_RELEASE_GATE_LOG_ROOT="$arbitrary_root" \
      "$root/scripts/release-emulator-validation.sh" 2>&1
  )" || rc=$?

  (( rc == 76 )) || fail "arbitrary pre-release root did not fail as a storage refusal (rc=$rc): $output"
  [[ -f "$arbitrary_root/gradle-home/KEEP" ]] ||
    fail "PRE_RELEASE_GATE_LOG_ROOT substitution deleted an unrelated sentinel"
  [[ ! -e "$sandbox/avd.lock" ]] ||
    fail "release wrapper took the AVD lock before rejecting its arbitrary storage root"
  [[ "$output" == *"REFUSING: release-validation storage root is not the canonical generated root"* ]] ||
    fail "arbitrary root was not rejected by the root provenance contract: $output"
  pass_case "release wrapper rejects arbitrary PRE_RELEASE_GATE_LOG_ROOT before deletion"
}

broad_var_root_and_unmarked_release_shaped_root_are_never_eligible() {
  # shellcheck disable=SC1091
  source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
  # shellcheck disable=SC1091
  source "$ROOT_DIR/scripts/lib/release-validation-storage.sh"
  local output rc=0
  output="$(_pocketshell_release_storage_root /var 2>&1)" || rc=$?
  (( rc != 0 )) || fail "/var was accepted as a release-owned storage root"
  [[ "$output" == *"REFUSING:"* ]] || fail "/var rejection was not explicit: $output"

  local repository_root="$WORK_DIR/unmarked/repository"
  local release_root="$repository_root/build/pre-release-confidence-gate"
  mkdir -p "$release_root/gradle-home"
  printf 'must survive\n' > "$release_root/gradle-home/KEEP"
  rc=0
  output="$(pocketshell_release_validation_cleanup_all "$release_root" apply 2>&1)" || rc=$?
  [[ -f "$release_root/gradle-home/KEEP" ]] ||
    fail "unmarked release-shaped root lost its destructive sentinel"
  (( rc != 0 )) || fail "unmarked release-shaped root was accepted for destructive cleanup"
  [[ "$output" == *"release-owned provenance marker"* ]] ||
    fail "unmarked-root refusal did not name the missing provenance marker: $output"
  pass_case "broad /var and unmarked release-shaped roots are ineligible for destructive cleanup"
}

preflight_reclaims_only_stale_generated_copy_before_measuring() {
  local sandbox="$WORK_DIR/preflight-reclaim"
  local repository_root="$sandbox/repository"
  local release_root="$repository_root/build/pre-release-confidence-gate"
  local run_dir="$release_root/abandoned-run"
  mkdir -p "$repository_root"
  pocketshell_release_validation_prepare_root "$repository_root" "$release_root" ||
    fail "could not authenticate the generated release root fixture"
  mkdir -p "$run_dir/worktree/build/deep" "$run_dir/source-worktree"
  printf 'Result: FAIL\n' > "$run_dir/summary.txt"
  printf 'generated\n' > "$run_dir/worktree/build/deep/output.bin"
  printf 'keep\n' > "$run_dir/source-worktree/KEEP"
  find "$run_dir/worktree" -type f -exec chmod 0444 {} +
  find "$run_dir/worktree" -type d -exec chmod 0555 {} +
  make_stat_stub "$sandbox/bin" 999999999

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
      pocketshell_release_disk_preflight "$sandbox" "$release_root" "test release preflight"
  )" || rc=$?
  (( rc == 0 )) || fail "high-space preflight failed while reclaiming stale generated output (rc=$rc): $output"
  [[ ! -e "$run_dir/worktree" ]] ||
    fail "preflight left an abandoned generated worktree before measuring"
  [[ -s "$run_dir/summary.txt" && -f "$run_dir/source-worktree/KEEP" ]] ||
    fail "preflight reclamation escaped the exact generated-output selector"
  [[ "$output" == *"Reclaiming stale generated release worktree:"* ]] ||
    fail "preflight did not report its bounded reclamation: $output"
  pass_case "preflight reclaims stale exact copied output and preserves diagnostics/source sibling"
}

low_preflight_reclaims_idle_private_gradle_home_before_refusal() {
  local sandbox="$WORK_DIR/preflight-gradle-home"
  local repository_root="$sandbox/repository"
  local release_root="$repository_root/build/pre-release-confidence-gate"
  mkdir -p "$repository_root"
  pocketshell_release_validation_prepare_root "$repository_root" "$release_root" ||
    fail "could not authenticate the generated release root fixture"
  mkdir -p "$release_root/gradle-home/caches"
  printf 'generated cache\n' > "$release_root/gradle-home/caches/cache.bin"
  make_stat_stub "$sandbox/bin" 20480

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
      pocketshell_release_disk_preflight "$sandbox" "$release_root" "test low preflight" 2>&1
  )" || rc=$?
  (( rc == 76 )) || fail "static low-space fixture should still refuse after reclaim (rc=$rc): $output"
  [[ ! -e "$release_root/gradle-home" ]] ||
    fail "low-space preflight retained the idle private Gradle home"
  [[ "$output" == *"Reclaiming idle generated release Gradle home before disk verdict:"* ]] ||
    fail "low-space preflight did not report private-cache reclamation: $output"
  pass_case "low preflight reclaims the idle private Gradle home before its final verdict"
}

failed_pre_release_retains_diagnostics_but_removes_its_copy() {
  local sandbox="$WORK_DIR/pre-release-failure"
  local root
  root="$(make_pre_release_sandbox "$sandbox")"
  # Far above every plausible release threshold.  The child then fails on the
  # intentionally absent adb executable, after summary/trap setup and before
  # any Gradle command.
  make_stat_stub "$sandbox/bin" 999999999

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
    POCKETSHELL_AVD_LOCK_ACQUIRED=1 \
    LOG_ROOT="$root/build/pre-release-confidence-gate" \
    RUN_ID=failed-run \
    ADB="$sandbox/missing-adb" \
    EMULATOR="$sandbox/missing-emulator" \
      "$root/scripts/pre-release-confidence-gate.sh" 2>&1
  )" || rc=$?

  (( rc != 0 )) || fail "failure fixture unexpectedly passed"
  [[ -s "$root/build/pre-release-confidence-gate/failed-run/summary.txt" ]] ||
    fail "failed run lost its summary"
  grep -q '^Result: FAIL' "$root/build/pre-release-confidence-gate/failed-run/summary.txt" ||
    fail "retained summary does not record the failure"
  [[ ! -e "$root/build/pre-release-confidence-gate/failed-run/worktree" ]] ||
    fail "failed run retained its generated isolated worktree/build outputs"
  [[ "$output" == *"Generated isolated worktree before cleanup:"* ]] ||
    fail "failure output did not report the isolated run size: $output"
  [[ "$output" == *"Retained failure diagnostics:"* ]] ||
    fail "failure output did not name the retained diagnostics: $output"
  [[ "$output" == *"scripts/disk-cleanup.sh --apply"* ]] ||
    fail "failure output omitted the explicit safe cleanup command: $output"
  pass_case "failed pre-release run keeps summary/logs and removes its generated copy"
}

partial_copy_failure_after_real_avd_lock_retains_and_releases() {
  local sandbox="$WORK_DIR/partial-copy-after-real-lock"
  local root release_root run_dir lock_file
  root="$(make_pre_release_sandbox "$sandbox")"
  release_root="$root/build/pre-release-confidence-gate"
  run_dir="$release_root/partial-copy"
  lock_file="$sandbox/avd.lock"
  make_stat_stub "$sandbox/bin" 999999999
  cat > "$sandbox/bin/rsync" <<'RSYNC'
#!/usr/bin/env bash
destination=""
for argument in "$@"; do
  destination="$argument"
done
mkdir -p "$destination/build/partial"
printf 'partial generated copy\n' > "$destination/build/partial/output.bin"
exit 88
RSYNC
  chmod +x "$sandbox/bin/rsync"

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
    POCKETSHELL_AVD_LOCK_FILE="$lock_file" \
    LOG_ROOT="$release_root" \
    RUN_ID=partial-copy \
    ADB="$sandbox/missing-adb" \
    EMULATOR="$sandbox/missing-emulator" \
      "$root/scripts/pre-release-confidence-gate.sh" 2>&1
  )" || rc=$?

  (( rc == 88 )) || fail "partial-copy rsync failure lost its exit status (rc=$rc): $output"
  [[ "$output" == *"Acquired AVD lock: $lock_file"* ]] ||
    fail "partial-copy fixture did not traverse the real AVD-lock acquisition path: $output"
  [[ ! -e "$run_dir/worktree" ]] ||
    fail "partial rsync failure stranded its generated worktree after real lock acquisition"
  [[ ! -e "$run_dir/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE" ]] ||
    fail "partial rsync failure stranded its owner marker after real lock acquisition"
  [[ -s "$run_dir/summary.txt" ]] ||
    fail "partial rsync failure after real lock acquisition lost its summary"
  grep -q '^Result: FAIL' "$run_dir/summary.txt" ||
    fail "partial-copy retained summary does not record failure"
  grep -q '^Failing step: isolated-worktree-copy' "$run_dir/summary.txt" ||
    fail "partial-copy retained summary does not identify its real failure boundary"
  [[ "$output" == *"Generated isolated worktree before cleanup:"* &&
    "$output" == *"Retained failure diagnostics:"* &&
    "$output" == *"scripts/disk-cleanup.sh --apply"* ]] ||
    fail "partial-copy failure omitted actionable retention diagnostics: $output"
  flock -n "$lock_file" true ||
    fail "partial-copy failure retained the real AVD lock"
  pass_case "real AVD-lock path retains partial rsync failure and releases generated copy/lock"
}

exact_floor_admission_is_not_rechecked_after_the_isolated_copy() {
  local sandbox="$WORK_DIR/exact-floor-post-copy-drop"
  local root release_root run_dir
  root="$(make_pre_release_sandbox "$sandbox")"
  release_root="$root/build/pre-release-confidence-gate"
  run_dir="$release_root/exact-floor"
  make_stateful_stat_stub "$sandbox/bin" "$sandbox/stat-calls" 24576 24575
  cat > "$sandbox/bin/rsync" <<'RSYNC'
#!/usr/bin/env bash
printf 'rsync reached\n' > "$RSYNC_MARKER"
exec /usr/bin/rsync "$@"
RSYNC
  chmod +x "$sandbox/bin/rsync"

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
    RSYNC_MARKER="$sandbox/rsync-reached" \
    POCKETSHELL_AVD_LOCK_ACQUIRED=1 \
    LOG_ROOT="$release_root" \
    RUN_ID=exact-floor \
    ADB="$sandbox/missing-adb" \
    EMULATOR="$sandbox/missing-emulator" \
      "$root/scripts/pre-release-confidence-gate.sh" 2>&1
  )" || rc=$?

  [[ -f "$sandbox/rsync-reached" ]] ||
    fail "exact-floor fixture never created the isolated copy: $output"
  (( rc != 0 && rc != 76 )) ||
    fail "admitted exact-floor run was refused by a post-copy disk recheck (rc=$rc): $output"
  [[ "$(cat "$sandbox/stat-calls")" == "1" ]] ||
    fail "pre-copy admission was not authoritative; statvfs was called $(cat "$sandbox/stat-calls") times"
  [[ -s "$run_dir/summary.txt" ]] ||
    fail "post-copy child failure did not retain an actionable summary"
  grep -q '^Result: FAIL' "$run_dir/summary.txt" ||
    fail "post-copy retained summary does not record the failure"
  [[ ! -e "$run_dir/worktree" ]] ||
    fail "post-copy child failure stranded its generated worktree"
  [[ ! -e "$run_dir/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE" ]] ||
    fail "post-copy child failure stranded its owner marker"
  [[ "$output" == *"Generated isolated worktree before cleanup:"* &&
    "$output" == *"Retained failure diagnostics:"* &&
    "$output" == *"scripts/disk-cleanup.sh --apply"* ]] ||
    fail "post-copy failure omitted actionable retention diagnostics: $output"
  pass_case "exact-floor admission remains authoritative after copy and child failure is fully retained"
}

make_cleanup_sandbox() {
  local sandbox="$1"
  local root="$sandbox/root"
  mkdir -p "$root/scripts/lib" "$sandbox/bin" "$sandbox/tmp"
  cp "$ROOT_DIR/scripts/disk-cleanup.sh" "$root/scripts/"
  cp "$ROOT_DIR"/scripts/lib/*.sh "$root/scripts/lib/"
  chmod +x "$root/scripts/disk-cleanup.sh"
  cat > "$sandbox/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
# Never let this fixture reach the host Docker daemon.
[[ "${1:-}" == "info" ]] && exit 1
exit 1
DOCKER
  chmod +x "$sandbox/bin/docker"
  printf '%s\n' "$root"
}

run_cleanup() {
  local sandbox="$1"
  PATH="$sandbox/bin:$PATH" \
  POCKETSHELL_DISK_CLEANUP_LOCK_DIR="$sandbox/locks" \
    "$sandbox/root/scripts/disk-cleanup.sh" \
      --root "$sandbox/root" --tmp-root "$sandbox/tmp" --tmp-age-days 1 --apply 2>&1
}

cleanup_handles_non_writable_generated_copy_and_preserves_diagnostics() {
  local sandbox="$WORK_DIR/non-writable"
  local root release_root run_dir
  root="$(make_cleanup_sandbox "$sandbox")"
  release_root="$root/build/pre-release-confidence-gate"
  run_dir="$release_root/failed-1714"
  mkdir -p "$run_dir/worktree/.worktrees/issue-1714/evidence/deep" \
    "$release_root/gradle-home/caches" \
    "$run_dir/source-worktree" \
    "$root/build/test-results"
  printf 'Result: FAIL\n' > "$run_dir/summary.txt"
  printf 'the useful failure log\n' > "$run_dir/03-gradle.log"
  printf 'generated evidence copy\n' > "$run_dir/worktree/.worktrees/issue-1714/evidence/deep/result.txt"
  printf 'similarly named but not generated\n' > "$run_dir/source-worktree/KEEP"
  printf 'shared generated cache\n' > "$release_root/gradle-home/caches/cache.bin"
  printf 'outside release scratch\n' > "$root/build/test-results/KEEP.xml"

  # Exact recurrence: owner-readable, deliberately non-writable nested content.
  find "$run_dir/worktree" -type f -exec chmod 0444 {} +
  find "$run_dir/worktree" -type d -exec chmod 0555 {} +
  chmod 0555 "$release_root/gradle-home/caches"
  chmod 0444 "$release_root/gradle-home/caches/cache.bin"

  local output rc=0
  output="$(run_cleanup "$sandbox")" || rc=$?
  (( rc == 0 )) || fail "cleanup could not reclaim non-writable safe-listed output (rc=$rc): $output"
  [[ ! -e "$run_dir/worktree" ]] ||
    fail "cleanup left the non-writable generated worktree behind"
  [[ ! -e "$release_root/gradle-home" ]] ||
    fail "cleanup left the generated release Gradle home behind"
  [[ -s "$run_dir/summary.txt" && -s "$run_dir/03-gradle.log" ]] ||
    fail "cleanup removed the small retained summary/log diagnostics"
  [[ -f "$run_dir/source-worktree/KEEP" ]] ||
    fail "cleanup used a broad *worktree* match and removed a similarly named source directory"
  [[ -f "$root/build/test-results/KEEP.xml" ]] ||
    fail "cleanup escaped the explicit release-validation safe list"
  pass_case "cleanup reclaims non-writable generated outputs and preserves diagnostics/selective sentinels"
}

active_release_copy_is_never_reclaimed() {
  local sandbox="$WORK_DIR/active"
  local root release_root run_dir
  root="$(make_cleanup_sandbox "$sandbox")"
  release_root="$root/build/pre-release-confidence-gate"
  run_dir="$release_root/active-run"
  mkdir -p "$run_dir/worktree/build" "$release_root/gradle-home/caches"
  printf 'active build\n' > "$run_dir/worktree/build/output.bin"
  printf 'active cache\n' > "$release_root/gradle-home/caches/output.bin"
  # shellcheck disable=SC1090
  source "$root/scripts/lib/release-validation-storage.sh"
  pocketshell_release_validation_prepare_root "$root" "$release_root" ||
    fail "could not authenticate the active generated release root fixture"
  pocketshell_release_validation_mark_active "$release_root" active-run "$$" ||
    fail "could not mark the generated run active"

  local output rc=0
  output="$(run_cleanup "$sandbox")" || rc=$?
  (( rc == 0 )) || fail "cleanup failed while skipping an active run (rc=$rc): $output"
  [[ -f "$run_dir/worktree/build/output.bin" ]] ||
    fail "cleanup deleted a live release validation's worktree"
  [[ -f "$release_root/gradle-home/caches/output.bin" ]] ||
    fail "cleanup deleted the shared release Gradle home while a run was live"
  [[ "$output" == *"SKIP active release-validation output"* ]] ||
    fail "cleanup did not explain why the active output was retained: $output"
  pass_case "cleanup refuses active release output and its shared Gradle home"
}


reserved_cleanup_names_cannot_be_run_ids_or_bypass_live_owner_protection() {
  local sandbox="$WORK_DIR/reserved-run-id"
  local root release_root gradle_home owner_start
  root="$(make_cleanup_sandbox "$sandbox")"
  release_root="$root/build/pre-release-confidence-gate"
  gradle_home="$release_root/gradle-home"
  pocketshell_release_validation_prepare_root "$root" "$release_root" ||
    fail "could not authenticate the reserved-name fixture root"

  local reserved_name
  for reserved_name in \
    gradle-home \
    "$POCKETSHELL_RELEASE_STORAGE_ROOT_MARKER" \
    "$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE" \
    "$POCKETSHELL_RELEASE_STORAGE_ROOT_MARKER.tmp.fixture"; do
    ! _pocketshell_release_run_id_is_safe "$reserved_name" ||
      fail "cleanup control name was accepted as a run id: $reserved_name"
  done
  ! pocketshell_release_validation_mark_active "$release_root" gradle-home "$$" >/dev/null 2>&1 ||
    fail "active-run writer accepted reserved RUN_ID=gradle-home"

  # Reproduce a marker written by the unfixed candidate before the new
  # reservation existed. Cleanup and low-space preflight must fail safe around
  # this live legacy collision rather than treating it as an idle shared cache.
  mkdir -p "$gradle_home/worktree/build" "$gradle_home/caches"
  printf 'live copied build\n' > "$gradle_home/worktree/build/LIVE"
  printf 'live shared cache\n' > "$gradle_home/caches/LIVE"
  owner_start="$(_pocketshell_release_pid_start "$$")" ||
    fail "could not read the current test owner start time"
  {
    printf 'pid=%s\n' "$$"
    printf 'start=%s\n' "$owner_start"
  } > "$gradle_home/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE"
  make_stat_stub "$sandbox/bin" 1024

  local output rc=0
  output="$(
    PATH="$sandbox/bin:$PATH" \
      pocketshell_release_disk_preflight "$root" "$release_root" "reserved-name preflight" 2>&1
  )" || rc=$?
  (( rc == 76 )) || fail "low-space collision fixture returned rc=$rc instead of 76: $output"
  [[ -f "$gradle_home/worktree/build/LIVE" && -f "$gradle_home/caches/LIVE" ]] ||
    fail "preflight deleted live output through the reserved gradle-home collision"

  rc=0
  output="$(run_cleanup "$sandbox")" || rc=$?
  (( rc == 0 )) || fail "cleanup failed while preserving the live reserved-name collision (rc=$rc): $output"
  [[ -f "$gradle_home/worktree/build/LIVE" && -f "$gradle_home/caches/LIVE" ]] ||
    fail "cleanup deleted live output through the reserved gradle-home collision"
  [[ "$output" == *"SKIP active release-validation"* ]] ||
    fail "cleanup did not report its live reserved-name refusal: $output"

  local pre_release_root
  pre_release_root="$(make_pre_release_sandbox "$sandbox/pre-release-entry")"
  make_stat_stub "$sandbox/pre-release-entry/bin" 999999999
  cat > "$sandbox/pre-release-entry/bin/rsync" <<'RSYNC'
#!/usr/bin/env bash
printf 'rsync reached\n' > "$RSYNC_MARKER"
exit 88
RSYNC
  chmod +x "$sandbox/pre-release-entry/bin/rsync"
  rc=0
  output="$(
    PATH="$sandbox/pre-release-entry/bin:$PATH" \
    RSYNC_MARKER="$sandbox/pre-release-entry/rsync-reached" \
    POCKETSHELL_AVD_LOCK_ACQUIRED=1 \
    LOG_ROOT="$pre_release_root/build/pre-release-confidence-gate" \
    RUN_ID=gradle-home \
      "$pre_release_root/scripts/pre-release-confidence-gate.sh" 2>&1
  )" || rc=$?
  (( rc == 76 )) || fail "standalone RUN_ID=gradle-home was not rejected with rc 76 (rc=$rc): $output"
  [[ ! -e "$sandbox/pre-release-entry/rsync-reached" ]] ||
    fail "standalone reserved run id reached isolated copy"

  local release_wrapper_root
  release_wrapper_root="$(make_release_wrapper_sandbox "$sandbox/release-wrapper-entry")"
  make_stat_stub "$sandbox/release-wrapper-entry/bin" 999999999
  rc=0
  output="$(
    PATH="$sandbox/release-wrapper-entry/bin:$PATH" \
    POCKETSHELL_AVD_LOCK_FILE="$sandbox/release-wrapper-entry/avd.lock" \
    RUN_ID=gradle-home \
      "$release_wrapper_root/scripts/release-emulator-validation.sh" 2>&1
  )" || rc=$?
  (( rc == 76 )) || fail "release-wrapper RUN_ID=gradle-home was not rejected with rc 76 (rc=$rc): $output"
  [[ ! -e "$sandbox/release-wrapper-entry/avd.lock" ]] ||
    fail "release wrapper allocated the AVD lock for a reserved run id"

  local workflow="$ROOT_DIR/.github/workflows/release-emulator-validation.yml"
  local validation_line emulator_line
  validation_line="$(grep -n -m1 'pocketshell_release_validation_require_run_id "\$RUN_ID"' "$workflow" | cut -d: -f1)"
  emulator_line="$(grep -n -m1 'uses: reactivecircus/android-emulator-runner@' "$workflow" | cut -d: -f1)"
  [[ "$validation_line" =~ ^[0-9]+$ && "$emulator_line" =~ ^[0-9]+$ ]] ||
    fail "manual workflow does not validate its sanitized run id"
  (( validation_line < emulator_line )) ||
    fail "manual workflow validates its run id only after allocating the emulator"
  pass_case "reserved cleanup names are rejected and legacy live collisions remain protected"
}

# Reproduction first. On the unfixed round-1 candidate the hosted contract is
# absent and each destructive root-substitution case loses its sentinel. A
# single-case selector lets mutation runs prove that one assertion, rather than
# some adjacent case, bears the cost of each contract.
run_case hosted-contract hosted_workflow_establishes_the_release_storage_contract_before_the_avd
run_case storage-probe storage_probe_passes_at_the_release_floor_without_taking_the_avd_lock
run_case source-root-rejection pre_release_rejects_the_repository_source_root_before_deletion
run_case arbitrary-root-rejection release_wrapper_rejects_an_arbitrary_pre_release_root_before_deletion
run_case broad-and-unmarked-rejection broad_var_root_and_unmarked_release_shaped_root_are_never_eligible
run_case pre-release-low pre_release_refuses_low_space_before_isolated_copy_or_gradle
run_case release-low release_wrapper_refuses_low_space_before_avd_lock
run_case fixed-floor release_floor_is_sized_above_the_canonical_single_run_floor
run_case stale-reclaim preflight_reclaims_only_stale_generated_copy_before_measuring
run_case gradle-home-reclaim low_preflight_reclaims_idle_private_gradle_home_before_refusal
run_case failed-retention failed_pre_release_retains_diagnostics_but_removes_its_copy
run_case partial-copy-real-lock partial_copy_failure_after_real_avd_lock_retains_and_releases
run_case exact-floor-post-copy exact_floor_admission_is_not_rechecked_after_the_isolated_copy
run_case non-writable-cleanup cleanup_handles_non_writable_generated_copy_and_preserves_diagnostics
run_case active-retention active_release_copy_is_never_reclaimed
run_case reserved-run-id reserved_cleanup_names_cannot_be_run_ids_or_bypass_live_owner_protection

# Issue #2435's #2082 execution-ledger cases are NOT here: they run the real
# #2063 selection-guard scripts, and #2067's C9 keeps those off the Gradle test
# graph — this harness is driven by DiskPreflightScriptTest, so it is charged
# once per variant on the Unit critical path. They live in
# tests/scripts/release-ledger-lane-coverage-test.sh, run by the blocking
# `guards-test-selection` job instead.

expected_cases=16
if [[ -n "${POCKETSHELL_RELEASE_STORAGE_TEST_CASE:-}" ]]; then
  expected_cases=1
fi
if (( CASES != expected_cases )); then
  fail "expected $expected_cases cases to run, saw $CASES (a case was skipped or silently removed)"
fi
printf 'PASS: release-validation storage harness (%s cases)\n' "$CASES"
