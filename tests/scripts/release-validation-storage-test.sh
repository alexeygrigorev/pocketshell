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

# ---------------------------------------------------------------------------
# Issue #2435: the #2082 execution-ledger step vs. the generated-worktree
# cleanup that runs ~15 s before it.
#
# `Release Emulator Validation` concluded `failure` on a run whose own
# summary.md said `Automated status: PASS`, which made the #2356
# `Record validated-rc marker` job (gated on
# `needs.emulator-release-validation.result == 'success'`) impossible to reach.
# Two independent, each-sufficient defects:
#
#   1. ORDERING. pocketshell_release_validation_finish_run packaged the small
#      JUnit XML out of the isolated worktree only on `failure`. The release
#      wrapper finishes a green chain with `success`, so the worktree — the
#      only place the gate's `*/build/test-results/*` XML exists — was removed
#      with nothing copied out, and the workflow step that runs AFTER it found
#      zero XML.
#
#   2. PIPEFAIL/SIGPIPE INVERSION. The step's presence check was
#      `if ! find "$stage" … | grep -q .`. `grep -q` exits on its first match,
#      `find` dies of EPIPE, and `set -o pipefail` propagates find's non-zero
#      status — so the pipeline reported failure EXACTLY when XML was present.
#      Run 33337299691 logged `find: 'standard output': Broken pipe` /
#      `find: write error` immediately before `no JUnit XML`, in a run that had
#      just packaged 1473 XML files.
#
# These cases execute the REAL `run:` body extracted from the committed
# workflow YAML, not a re-spelling of it, so a future edit that reintroduces
# either shape reddens here. No emulator, no Docker, no Gradle.
# ---------------------------------------------------------------------------
LEDGER_STEP_NAME='- name: Record and verify execution ledger'

extract_ledger_step_body() {
  # Take the literal block scalar under the ledger step's `run: |`, dedented by
  # its fixed 10-space workflow indentation. Reading the committed YAML is the
  # point: a copy of the script in this harness would stay green while the
  # workflow regressed.
  awk -v marker="$LEDGER_STEP_NAME" '
    index($0, marker) { in_step = 1; next }
    in_step && !in_run && $0 ~ /^[[:space:]]*run: \|[[:space:]]*$/ { in_run = 1; next }
    in_run {
      # Hold blank lines until a body line follows them. Emitting them eagerly
      # appends the blank separator line BEFORE the next workflow step, so the
      # extraction stops being byte-identical to the YAML block scalar.
      if ($0 ~ /^[[:space:]]*$/) { pending++; next }
      if ($0 !~ /^          /) { exit }
      while (pending > 0) { print ""; pending-- }
      sub(/^          /, "")
      print
    }
  ' "$1"
}

write_junit_xml() {
  local destination="$1"
  local classname="$2"
  mkdir -p "$(dirname -- "$destination")"
  cat > "$destination" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$classname" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="proves_the_ledger_saw_real_content" classname="$classname" time="0.011"/>
</testsuite>
XML
}

# The pipefail/SIGPIPE inversion only fires once the writer still has data
# pending when the early-exiting reader goes away, i.e. once `find`'s output
# exceeds the 64 KiB pipe buffer. The release run that exposed this packaged
# 1473 XML files; this fixture reproduces the same regime with long FQCN-shaped
# names, measured deterministic at 20/20 reps with the GNU find/grep the hosted
# runner uses. Undersizing it would make the case pass on the unfixed body for
# the wrong reason (the G6 shape) — so the volume is load-bearing, not padding.
LEDGER_XML_FIXTURE_COUNT="${LEDGER_XML_FIXTURE_COUNT:-600}"
LEDGER_XML_LONG_NAME_PAD='ReleaseGateLedgerRegressionFixtureClassNamePaddingSegmentAlphaBravoCharlieDeltaEchoFoxtrotGolfHotelIndiaJulietKiloLimaMikeNovemberOscarPapaQuebecRomeo'

plant_release_junit_xml_volume() {
  # $1 = repository-or-worktree root the release gate produced results under.
  local root="$1"
  local unit_dir="$root/app/build/test-results/testDebugUnitTest"
  local connected_dir="$root/shared/core-terminal/build/outputs/androidTest-results/connected"
  mkdir -p "$unit_dir" "$connected_dir"
  write_junit_xml \
    "$unit_dir/TEST-com.pocketshell.app.AlphaTest.xml" \
    com.pocketshell.app.AlphaTest
  write_junit_xml \
    "$connected_dir/TEST-com.pocketshell.terminal.BetaTest.xml" \
    com.pocketshell.terminal.BetaTest
  local index target_dir classname
  for (( index = 0; index < LEDGER_XML_FIXTURE_COUNT; index++ )); do
    if (( index % 2 == 0 )); then
      target_dir="$unit_dir"
    else
      target_dir="$connected_dir"
    fi
    printf -v classname 'com.pocketshell.bulk.%s%04dTest' "$LEDGER_XML_LONG_NAME_PAD" "$index"
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="%s" tests="1"><testcase name="bulk" classname="%s" time="0.001"/></testsuite>\n' \
      "$classname" "$classname" > "$target_dir/TEST-$classname.xml"
  done
}

# The stub stands in for scripts/check-test-execution-ledger.sh. It records the
# arguments it was called with AND independently re-derives how many
# `<testcase` elements are reachable from the staged root it was handed, so a
# "the directory exists but is empty" staging regression cannot masquerade as a
# fix (the real script's own zero-testcase refusal is the production gate; this
# mirrors it so the harness fails at the staging boundary).
make_ledger_stub() {
  local destination="$1"
  local calls_file="$2"
  local testcases_file="$3"
  mkdir -p "$(dirname -- "$destination")"
  cat > "$destination" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >> '$calls_file'
if [[ "\${1:-}" == "--record" ]]; then
  staged_root="\${2:-}"
  if [[ ! -d "\$staged_root" ]]; then
    printf 'stub: staged root does not exist: %s\n' "\$staged_root" >&2
    exit 1
  fi
  testcases="\$(grep -rho '<testcase' "\$staged_root" 2>/dev/null | wc -l | tr -d ' ')"
  printf '%s\n' "\$testcases" > '$testcases_file'
  if [[ "\$testcases" -eq 0 ]]; then
    printf 'stub: no JUnit testcases reachable under %s\n' "\$staged_root" >&2
    exit 1
  fi
fi
exit 0
STUB
  chmod +x "$destination"
}

make_ledger_step_sandbox() {
  local sandbox="$1"
  local repository="$sandbox/repository"
  mkdir -p "$repository/scripts" "$sandbox/runner-temp"
  extract_ledger_step_body "$ROOT_DIR/.github/workflows/release-emulator-validation.yml" \
    > "$sandbox/ledger-step.sh"
  [[ -s "$sandbox/ledger-step.sh" ]] ||
    fail "could not extract the '$LEDGER_STEP_NAME' run: body from the release workflow"
  grep -q 'check-test-execution-ledger.sh --record' "$sandbox/ledger-step.sh" ||
    fail "extracted ledger step body does not --record; the extractor is reading the wrong block"
  grep -q 'check-test-execution-ledger.sh --verify' "$sandbox/ledger-step.sh" ||
    fail "extracted ledger step body does not --verify; the extractor is reading the wrong block"
  make_ledger_stub "$repository/scripts/check-test-execution-ledger.sh" \
    "$sandbox/ledger-calls" "$sandbox/staged-testcases"
  printf '%s\n' "$repository"
}

run_ledger_step() {
  local sandbox="$1"
  local repository="$2"
  (
    cd "$repository" || exit 90
    RUNNER_TEMP="$sandbox/runner-temp" bash "$sandbox/ledger-step.sh" 2>&1
  )
}

ledger_step_records_retained_release_xml() {
  local sandbox="$WORK_DIR/ledger-step-present"
  local repository retained
  repository="$(make_ledger_step_sandbox "$sandbox")"
  retained="$repository/build/pre-release-confidence-gate/gha-1-pre-release/retained-test-results"
  plant_release_junit_xml_volume "$retained"

  local output rc=0
  output="$(run_ledger_step "$sandbox" "$repository")" || rc=$?

  (( rc == 0 )) ||
    fail "ledger step failed although JUnit XML was present (rc=$rc): $output"
  [[ "$output" != *"no JUnit XML from the release run"* ]] ||
    fail "ledger step reported an empty run while XML was present: $output"
  [[ -s "$sandbox/ledger-calls" ]] ||
    fail "ledger step never invoked check-test-execution-ledger.sh: $output"
  grep -q -- '--record ' "$sandbox/ledger-calls" ||
    fail "ledger step did not --record this run's results: $(cat "$sandbox/ledger-calls")"
  grep -q -- '--verify ' "$sandbox/ledger-calls" ||
    fail "ledger step did not --verify the rolling ledger: $(cat "$sandbox/ledger-calls")"
  [[ "$(cat "$sandbox/staged-testcases" 2>/dev/null || printf 0)" -ge 2 ]] ||
    fail "ledger step staged a path with no real JUnit testcases in it (staged testcase count: $(cat "$sandbox/staged-testcases" 2>/dev/null))"
  pass_case "ledger step records real retained XML instead of failing a genuine PASS (#2435)"
}

ledger_step_still_refuses_a_run_with_no_junit_xml() {
  local sandbox="$WORK_DIR/ledger-step-absent"
  local repository
  repository="$(make_ledger_step_sandbox "$sandbox")"
  mkdir -p "$repository/build/release-emulator-validation/gha-1"
  printf 'Automated status: PASS\n' > "$repository/build/release-emulator-validation/gha-1/summary.md"

  local output rc=0
  output="$(run_ledger_step "$sandbox" "$repository")" || rc=$?

  (( rc == 1 )) ||
    fail "ledger step did not fail closed on a run with no JUnit XML (rc=$rc): $output"
  [[ "$output" == *"no JUnit XML from the release run — refusing to record or verify"* ]] ||
    fail "empty-run refusal lost its explicit #2082 message: $output"
  [[ ! -s "$sandbox/ledger-calls" ]] ||
    fail "ledger step recorded/verified over an empty result set: $(cat "$sandbox/ledger-calls")"
  pass_case "ledger step still fails loudly when the release run really produced no XML (#2435)"
}

successful_release_run_leaves_ledger_xml_for_the_post_cleanup_step() {
  local sandbox="$WORK_DIR/success-retention"
  local repository release_root run_dir worktree
  repository="$(make_ledger_step_sandbox "$sandbox")"
  release_root="$repository/build/pre-release-confidence-gate"
  run_dir="$release_root/gha-1-pre-release"
  worktree="$run_dir/worktree"
  pocketshell_release_validation_prepare_root "$repository" "$release_root" ||
    fail "could not authenticate the generated release root fixture"
  pocketshell_release_validation_mark_active "$release_root" gha-1-pre-release "$$" ||
    fail "could not mark the generated run active"
  plant_release_junit_xml_volume "$worktree"
  mkdir -p "$worktree/app/build/intermediates"
  printf 'bulk generated output\n' > "$worktree/app/build/intermediates/big.bin"

  local output rc=0
  output="$(pocketshell_release_validation_finish_run "$release_root" gha-1-pre-release success 2>&1)" || rc=$?
  (( rc == 0 )) || fail "successful finish_run failed (rc=$rc): $output"
  [[ ! -e "$worktree" ]] ||
    fail "successful finish_run kept the generated isolated worktree"
  [[ -s "$run_dir/retained-test-results/app/build/test-results/testDebugUnitTest/TEST-com.pocketshell.app.AlphaTest.xml" ]] ||
    fail "successful finish_run removed the worktree without retaining its unit JUnit XML: $output"
  [[ -s "$run_dir/retained-test-results/shared/core-terminal/build/outputs/androidTest-results/connected/TEST-com.pocketshell.terminal.BetaTest.xml" ]] ||
    fail "successful finish_run did not retain the connected androidTest JUnit XML: $output"
  [[ ! -e "$run_dir/retained-test-results/app/build/intermediates/big.bin" ]] ||
    fail "retention widened past JUnit XML into bulk generated build output"

  # The whole point of the issue: the workflow step that runs AFTER this
  # cleanup must find real results. Drive the real step body over the real
  # post-cleanup tree, not over a hand-built approximation of it.
  rc=0
  output="$(run_ledger_step "$sandbox" "$repository")" || rc=$?
  (( rc == 0 )) ||
    fail "the #2082 ledger step still failed after a SUCCESSFUL release run (rc=$rc): $output"
  grep -q -- '--record ' "$sandbox/ledger-calls" ||
    fail "post-success ledger step did not --record: $(cat "$sandbox/ledger-calls" 2>/dev/null)"
  [[ "$(cat "$sandbox/staged-testcases" 2>/dev/null || printf 0)" -ge 2 ]] ||
    fail "post-success ledger step staged no real JUnit testcases (count: $(cat "$sandbox/staged-testcases" 2>/dev/null))"
  pass_case "a green release run leaves the post-cleanup #2082 ledger step real XML to record (#2435)"
}

validated_rc_marker_still_requires_a_genuinely_successful_validation_job() {
  local workflow="$ROOT_DIR/.github/workflows/release-emulator-validation.yml"
  local marker_job
  marker_job="$(awk '/^  record-validated-rc:/{ found = 1 } found { print } found && /^  notify-nightly-rc-red:/{ exit }' "$workflow")"
  [[ -n "$marker_job" ]] ||
    fail "release workflow lost its record-validated-rc job"
  [[ "$marker_job" == *"needs.emulator-release-validation.result == 'success'"* ]] ||
    fail "record-validated-rc no longer requires a successful validation job — a red gate could publish a validated-rc tag"
  [[ "$marker_job" == *"github.event.workflow_run.event == 'schedule'"* ]] ||
    fail "record-validated-rc is no longer restricted to the nightly schedule path"
  # The ledger step must still be able to fail the job. `continue-on-error`
  # would turn this issue's fix into the opposite defect: a masked red.
  local ledger_step
  ledger_step="$(awk '
    index($0, "- name: Record and verify execution ledger") { found = 1 }
    found { print }
    found && index($0, "- name: Publish validation summary") { exit }
  ' "$workflow")"
  [[ "$ledger_step" != *"continue-on-error"* ]] ||
    fail "the #2082 ledger step became continue-on-error — a missing ledger would no longer redden the release job"
  [[ "$ledger_step" == *"if: always()"* ]] ||
    fail "the #2082 ledger step no longer runs on a failed validation run"
  pass_case "validated-rc still needs a genuinely successful job and the ledger step can still redden it (#2435)"
}

# ---------------------------------------------------------------------------
# Issue #2435, round 2: the REAL check-test-execution-ledger.sh, not a stub.
#
# The four cases above stub the ledger script, which is exactly why a THIRD
# defect stayed invisible through round 1: once the staging bug and the
# pipefail inversion were fixed, `--verify` finally executed for the first time
# in this workflow's history — and reported SEVEN registered classes that have
# NEVER executed. The job still concluded `failure`, so `record-validated-rc`
# was still unreachable and the issue's title symptom survived its own fix.
#
# The seven, and what each one actually was:
#
#   AssistantAgentLoopRealLlmTest        app/build.gradle.kts excludes
#                                        **/*RealLlmTest.class from BOTH unit
#                                        tasks and :app:realLlmTest needs real
#                                        provider credentials -> no CI lane can
#                                        EVER emit it, yet `--source-set unit`
#                                        demanded a 7-day cadence for it.
#   UiKitPrimitivesTest                  shared/ui-kit/src/androidTest; no lane
#                                        ran :shared:ui-kit:connectedDebugAndroidTest.
#   LongRunningSessionStabilityTest      excluded from nightly phase 1 by NAME
#   LongRunningInstrumentationHeartbeat  PREFIX and named by no later phase, so
#                                        both ran nowhere. (The #794 90 s
#                                        transport-flap method has no assume at
#                                        all; the heartbeat class is plain JVM
#                                        logic.)
#   HostCardStatusChipTest               selected by phase 1, which passes no
#   HostCardSetupBadgeTest               `pocketshellBootstrapScenarios` opt-in,
#                                        so every method came back <skipped/> —
#                                        attendance-green, ledger-uncredited.
#   LastSessionProcessRestartProofTest   runs every night in phase 5 through
#                                        direct `am instrument`, which produces
#                                        no host JUnit XML at all.
#
# These cases pin the three distinct shapes with the real scripts:
#   * lane completeness — every registered class is claimed by some lane;
#   * a real end-to-end --record/--verify over a realistically-shaped rolling
#     ledger, with a negative half proving the verify is live;
#   * opt-in-gated classes are not parked in a lane that only all-skips them,
#     and the real --record still refuses to credit an all-skipped class.
# ---------------------------------------------------------------------------
LEDGER_SCRIPT="$ROOT_DIR/scripts/check-test-execution-ledger.sh"
NIGHTLY_SUITE_SCRIPT="$ROOT_DIR/scripts/nightly-extensive-suite.sh"
CONFIDENCE_GATE_SCRIPT="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"

# The source-set the release job's ledger step actually verifies, read out of
# the committed workflow rather than restated here.
release_step_verify_source_set() {
  sed -nE 's/.*check-test-execution-ledger\.sh --verify .*--source-set ([A-Za-z,-]+).*/\1/p' \
    "$ROOT_DIR/.github/workflows/release-emulator-validation.yml" | head -n 1
}

# Every registered class the release step's --verify demands evidence for.
# Derived by asking the real guard itself (--report never fails, and with a
# one-row ledger every registered class lands in the never-executed list), so
# this harness has no second copy of the registration rules.
release_step_registered_classes() {
  local seed="$WORK_DIR/registered-seed.tsv"
  printf 'zz.HarnessSeed\t1\tseed\n' > "$seed"
  bash "$LEDGER_SCRIPT" --verify --report --max-age-days 7 \
    --source-set "$(release_step_verify_source_set)" \
    --ledger "$seed" --now 2000000000 2>&1 |
    sed -n '/have NEVER executed/,$p' | tail -n +2 | sed 's/^[[:space:]]*//' |
    grep -E '^[a-z][A-Za-z0-9_.]*\.[A-Z][A-Za-z0-9_]*$' | LC_ALL=C sort -u
}

# The androidTest classes the release gate itself can credit: every class in the
# shared modules whose :connectedDebugAndroidTest the confidence gate runs. The
# module list is grepped out of the gate script, so dropping a module from the
# gate makes this shrink and the lane-completeness case red — which is exactly
# how UiKitPrimitivesTest went unnoticed.
release_gate_shared_module_classes() {
  local module relative fqcn source_file
  while IFS= read -r module; do
    [[ -n "$module" ]] || continue
    [[ -d "$ROOT_DIR/$module/src/androidTest" ]] || continue
    while IFS= read -r source_file; do
      relative="${source_file#"$ROOT_DIR/$module"/src/androidTest/}"
      relative="${relative#java/}"
      relative="${relative#kotlin/}"
      relative="${relative%.kt}"
      relative="${relative%.java}"
      fqcn="${relative//\//.}"
      [[ "$fqcn" =~ ^[a-z][A-Za-z0-9_.]*\.[A-Z][A-Za-z0-9_]*$ ]] && printf '%s\n' "$fqcn"
    done < <(find "$ROOT_DIR/$module/src/androidTest" -type f \
      \( -name '*.kt' -o -name '*.java' \) 2>/dev/null)
    # Read the module list off the gate's actual `./gradlew …` invocation
    # lines only. Scanning the whole file would count a module named in a
    # COMMENT, so deleting the task while leaving the comment behind would keep
    # this harness green over a lane that no longer runs.
  done < <(grep -E '\./gradlew' "$CONFIDENCE_GATE_SCRIPT" |
    grep -oE ':shared:[a-z0-9-]+:connectedDebugAndroidTest' |
    sed 's/:connectedDebugAndroidTest//; s|^:||; s|:|/|g' | LC_ALL=C sort -u)
}

# The union of every lane that can put a class into the rolling ledger.
ledger_creditable_classes() {
  {
    bash "$LEDGER_SCRIPT" --print-selected --selected-from unit-debug
    bash "$LEDGER_SCRIPT" --print-selected --selected-from unit-release
    bash "$LEDGER_SCRIPT" --print-selected --selected-from nightly-phase1
    bash "$NIGHTLY_SUITE_SCRIPT" --print-later-phase-classes
    release_gate_shared_module_classes
  } | LC_ALL=C sort -u
}

every_registered_class_is_claimed_by_a_lane_that_can_credit_it() {
  local registered="$WORK_DIR/lane-registered.txt"
  local creditable="$WORK_DIR/lane-creditable.txt"
  release_step_registered_classes > "$registered"
  ledger_creditable_classes > "$creditable"

  [[ "$(wc -l < "$registered")" -gt 500 ]] ||
    fail "registered class derivation collapsed ($(wc -l < "$registered") classes) — the harness would pass vacuously"
  [[ "$(wc -l < "$creditable")" -gt 500 ]] ||
    fail "lane-creditable derivation collapsed ($(wc -l < "$creditable") classes)"
  grep -qx 'com.pocketshell.uikit.components.UiKitPrimitivesTest' "$creditable" ||
    fail "no lane can credit UiKitPrimitivesTest — the release gate must run :shared:ui-kit:connectedDebugAndroidTest (#2435)"
  grep -qx 'com.pocketshell.app.proof.LongRunningSessionStabilityTest' "$creditable" ||
    fail "no lane can credit LongRunningSessionStabilityTest — its #794 90s method must run in nightly phase 1 (#2435)"

  local orphans
  orphans="$(LC_ALL=C comm -23 "$registered" "$creditable")"
  [[ -z "$orphans" ]] ||
    fail "$(printf '%s\n' "the release ledger --verify demands evidence for class(es) no CI lane can produce; they will make every release job conclude failure (#2435):" "$orphans")"
  pass_case "every class the release ledger --verify demands is claimed by a lane that can credit it (#2435)"
}

# Build a sandbox in which the REAL ledger script runs against the real tree's
# registration rules but a fixture rolling ledger and fixture results.
make_real_ledger_sandbox() {
  local sandbox="$1"
  local repository="$sandbox/repository"
  mkdir -p "$repository" "$sandbox/runner-temp"
  # `scripts` is a symlink so `scripts/check-test-execution-ledger.sh` in the
  # extracted step body is the REAL script. `find` does not descend symlinks,
  # so the real repo's build output cannot leak into the staged set.
  ln -sfn "$ROOT_DIR/scripts" "$repository/scripts"
  extract_ledger_step_body "$ROOT_DIR/.github/workflows/release-emulator-validation.yml" \
    > "$sandbox/ledger-step.sh"
  [[ -s "$sandbox/ledger-step.sh" ]] ||
    fail "could not extract the ledger step body for the real-ledger case"
  printf '%s\n' "$repository"
}

run_real_ledger_step() {
  local sandbox="$1" repository="$2"
  (
    cd "$repository" || exit 90
    RUNNER_TEMP="$sandbox/runner-temp" \
      POCKETSHELL_TEST_LEDGER="$sandbox/rolling-ledger.tsv" \
      POCKETSHELL_TEST_AREAS_REPO_ROOT="$ROOT_DIR" \
      POCKETSHELL_TEST_AREAS_MANIFEST="$ROOT_DIR/scripts/test-areas.txt" \
      POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$ROOT_DIR/scripts/ci-journey-suite.sh" \
      POCKETSHELL_TEST_AREAS_UNCONVENTIONAL="$ROOT_DIR/scripts/test-unconventional-test-files.txt" \
      POCKETSHELL_TEST_AREAS_NIGHTLY_SUITE="$NIGHTLY_SUITE_SCRIPT" \
      bash "$sandbox/ledger-step.sh" 2>&1
  )
}

# Write one real JUnit XML per class into a results tree the step's collector
# reaches (`*/build/outputs/androidTest-results/*`).
stage_release_lane_results() {
  local repository="$1" classes_file="$2"
  local destination="$repository/shared/core-terminal/build/outputs/androidTest-results/connected"
  mkdir -p "$destination"
  local fqcn
  while IFS= read -r fqcn; do
    [[ -n "$fqcn" ]] || continue
    write_junit_xml "$destination/TEST-$fqcn.xml" "$fqcn"
  done < "$classes_file"
}

the_real_ledger_verify_passes_after_a_green_release_run() {
  local sandbox="$WORK_DIR/real-ledger-verify"
  local repository
  repository="$(make_real_ledger_sandbox "$sandbox")"

  local creditable="$WORK_DIR/real-creditable.txt"
  local release_owned="$WORK_DIR/real-release-owned.txt"
  local seeded="$WORK_DIR/real-seeded.txt"
  ledger_creditable_classes > "$creditable"
  release_gate_shared_module_classes | LC_ALL=C sort -u > "$release_owned"
  # The rolling ledger arrives from the unit + nightly caches with everything
  # EXCEPT what this release run is about to record itself.
  LC_ALL=C comm -23 "$creditable" "$release_owned" > "$seeded"
  [[ "$(wc -l < "$seeded")" -gt 500 ]] ||
    fail "seeded rolling ledger collapsed ($(wc -l < "$seeded") classes)"
  [[ "$(wc -l < "$release_owned")" -ge 2 ]] ||
    fail "the release lane owns fewer than 2 classes; the fixture would not exercise --record"

  local now
  now="$(date +%s)"
  awk -v now="$now" '{ printf "%s\t%s\tunit\n", $0, now }' "$seeded" |
    LC_ALL=C sort > "$sandbox/rolling-ledger.tsv"
  stage_release_lane_results "$repository" "$release_owned"

  local output rc=0
  output="$(run_real_ledger_step "$sandbox" "$repository")" || rc=$?
  (( rc == 0 )) ||
    fail "$(printf '%s\n' "the REAL ledger step failed after a green release run (rc=$rc):" "$output")"
  [[ "$output" == *"PASS: every registered test class executed inside the cadence window."* ]] ||
    fail "$(printf '%s\n' "the real --verify did not report a clean cadence window:" "$output")"
  [[ "$output" == *"recorded "* ]] ||
    fail "$(printf '%s\n' "the real --record never credited the release run's own results:" "$output")"

  # Negative half: the verify must be LIVE. Drop one class the release run does
  # not record and the same step must go red — otherwise the green above proves
  # nothing about the guard, only about the plumbing.
  local victim
  victim="$(head -n 1 "$seeded")"
  grep -v -F -x "$victim	$now	unit" "$sandbox/rolling-ledger.tsv" > "$sandbox/rolling-ledger.tsv.tmp"
  mv "$sandbox/rolling-ledger.tsv.tmp" "$sandbox/rolling-ledger.tsv"
  rc=0
  output="$(run_real_ledger_step "$sandbox" "$repository")" || rc=$?
  (( rc == 1 )) ||
    fail "the real ledger step passed with $victim missing from the rolling ledger (rc=$rc) — --verify is not live"
  [[ "$output" == *"$victim"* ]] ||
    fail "$(printf '%s\n' "the real --verify did not name the missing class $victim:" "$output")"
  pass_case "the REAL check-test-execution-ledger.sh --record/--verify chain closes for a green release run (#2435)"
}

optin_gated_classes_run_where_the_optin_is_and_all_skipped_results_stay_uncredited() {
  local phase1="$WORK_DIR/optin-phase1.txt"
  local excluded="$WORK_DIR/optin-excluded.txt"
  bash "$LEDGER_SCRIPT" --print-selected --selected-from nightly-phase1 | LC_ALL=C sort -u > "$phase1"
  bash "$NIGHTLY_SUITE_SCRIPT" --print-phase1-exclusions | LC_ALL=C sort -u > "$excluded"

  # Phase 3 is the only phase that passes pocketshellBootstrapScenarios. Any
  # class it selects must NOT also be selected by phase 1, which passes no
  # opt-in and would therefore only all-skip it — the exact shape that left
  # HostCardStatusChipTest / HostCardSetupBadgeTest uncredited for months.
  local phase3_classes fqcn
  phase3_classes="$(grep -oE 'com\.pocketshell\.app\.(bootstrap|hosts)\.[A-Za-z0-9_]+' \
    "$NIGHTLY_SUITE_SCRIPT" | LC_ALL=C sort -u)"
  [[ -n "$phase3_classes" ]] ||
    fail "could not read the phase-3 opt-in class set from the nightly suite"
  while IFS= read -r fqcn; do
    [[ -n "$fqcn" ]] || continue
    if grep -qx -- "$fqcn" "$phase1"; then
      fail "$fqcn needs the pocketshellBootstrapScenarios opt-in but is still selected by nightly phase 1, which passes none — every method there is <skipped/> and the execution ledger will never credit it (#2435)"
    fi
    grep -qx -- "$fqcn" "$excluded" ||
      fail "$fqcn is not in the phase-1 exclusion list, so phase 1 will all-skip it (#2435)"
  done <<< "$phase3_classes"

  # And prove the underlying rule with the real script: an all-skipped class is
  # NOT coverage, so parking an opt-in class in a lane without the opt-in can
  # never satisfy --verify.
  local results="$WORK_DIR/optin-results"
  local ledger="$WORK_DIR/optin-ledger.tsv"
  mkdir -p "$results"
  cat > "$results/TEST-com.example.OptInGatedTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.OptInGatedTest" tests="2" skipped="2" failures="0" errors="0">
  <testcase name="one" classname="com.example.OptInGatedTest" time="0"><skipped/></testcase>
  <testcase name="two" classname="com.example.OptInGatedTest" time="0"><skipped/></testcase>
</testsuite>
XML
  write_junit_xml "$results/TEST-com.example.RanTest.xml" com.example.RanTest
  bash "$LEDGER_SCRIPT" --record "$results" --ledger "$ledger" --tier harness --now 1700000000 \
    > /dev/null 2>&1 ||
    fail "the real --record refused a results tree that contained a genuinely executed class"
  grep -q '^com.example.RanTest	' "$ledger" ||
    fail "the real --record did not credit the executed class"
  ! grep -q '^com.example.OptInGatedTest	' "$ledger" ||
    fail "the real --record credited an ALL-SKIPPED class as executed"
  pass_case "opt-in-gated classes run in the phase that passes the opt-in; all-skipped results stay uncredited (#2435)"
}

detached_instrumentation_runs_produce_ledger_creditable_junit_xml() {
  local converter="$ROOT_DIR/scripts/instrumentation-log-to-junit-xml.sh"
  [[ -x "$converter" || -f "$converter" ]] ||
    fail "scripts/instrumentation-log-to-junit-xml.sh is missing (#2435)"
  local output rc=0
  output="$(bash "$converter" --self-test 2>&1)" || rc=$?
  (( rc == 0 )) ||
    fail "$(printf '%s\n' "instrumentation-log-to-junit-xml self-test failed (rc=$rc):" "$output")"
  [[ "$output" == *"--record credits the converted class"* ]] ||
    fail "$(printf '%s\n' "the converter self-test no longer proves the REAL ledger credits its XML:" "$output")"

  # The two production callers must actually invoke it, with --require-class so
  # a run that selected nothing cannot be laundered into a ledger entry.
  local two_phase="$ROOT_DIR/scripts/two-phase-android-instrumentation.sh"
  grep -q 'instrumentation-log-to-junit-xml.sh' "$two_phase" ||
    fail "the #2264 two-phase harness no longer emits JUnit XML; LastSessionProcessRestartProofTest becomes uncreditable again (#2435)"
  grep -q -- '--require-class' "$two_phase" ||
    fail "the #2264 two-phase harness converts without --require-class (an empty run could be credited)"
  grep -q 'junit-results' "$NIGHTLY_SUITE_SCRIPT" ||
    fail "nightly phase 5 no longer publishes its converted JUnit XML onto the phase-reports path the rolling ledger records from (#2435)"
  grep -q 'instrumentation-log-to-junit-xml.sh' "$ROOT_DIR/scripts/release-emulator-validation.sh" ||
    fail "the release gate's detached am-instrument runs no longer produce ledger JUnit XML (#2435)"
  pass_case "direct am instrument runs produce JUnit XML the real ledger credits (#2435)"
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
run_case ledger-step-present ledger_step_records_retained_release_xml
run_case ledger-step-absent ledger_step_still_refuses_a_run_with_no_junit_xml
run_case success-retention successful_release_run_leaves_ledger_xml_for_the_post_cleanup_step
run_case validated-rc-gate validated_rc_marker_still_requires_a_genuinely_successful_validation_job
run_case ledger-lane-coverage every_registered_class_is_claimed_by_a_lane_that_can_credit_it
run_case ledger-verify-real the_real_ledger_verify_passes_after_a_green_release_run
run_case ledger-optin-lane optin_gated_classes_run_where_the_optin_is_and_all_skipped_results_stay_uncredited
run_case ledger-instrumentation-xml detached_instrumentation_runs_produce_ledger_creditable_junit_xml

expected_cases=24
if [[ -n "${POCKETSHELL_RELEASE_STORAGE_TEST_CASE:-}" ]]; then
  expected_cases=1
fi
if (( CASES != expected_cases )); then
  fail "expected $expected_cases cases to run, saw $CASES (a case was skipped or silently removed)"
fi
printf 'PASS: release-validation storage harness (%s cases)\n' "$CASES"
