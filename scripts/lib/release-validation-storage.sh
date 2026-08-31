#!/usr/bin/env bash

# Release-validation disk budget and generated-output retention (issue #2055).
#
# This is deliberately separate from disk-preflight.sh's 10 GiB one-run floor.
# A release validation creates an isolated source copy, builds debug + release
# unit variants and the debug/androidTest APK pair, owns a private Gradle home,
# and then captures emulator artifacts.  The generic floor remains correct for
# one JVM/connected run; it is not large enough for this release envelope.
#
# The 24 GiB floor is fixed, not environment-overridable.  It budgets two
# 10 GiB clean-run envelopes plus 4 GiB for the private Gradle home, APK copies,
# emulator evidence, and filesystem churn.  The incident retained 4.5 GiB in
# two failed isolated copies plus 1.9 GiB in the private Gradle home; admitting
# the release at the generic 10 GiB floor therefore certified a run that could
# not finish.  There is no skip/lower knob.
#
# Retention is equally narrow. The root must first be authenticated as this
# checkout's exact <repository>/build/pre-release-confidence-gate through an
# owner-only provenance marker; LOG_ROOT/PRE_RELEASE_GATE_LOG_ROOT cannot bless
# source, /var, or arbitrary directories. Only an immediate
#   <release-root>/<run-id>/worktree
# directory and the exact
#   <release-root>/gradle-home
# cache are removable.  Summaries, step logs, retained test XML, similarly
# named directories, source worktrees, and anything outside the supplied log
# root are unreachable.  Live runs carry a pid + /proc start-time owner record,
# so a cleanup cannot remove the copy or shared Gradle home out from under a
# running validation.  Non-writable content is made owner-writable only after
# the exact safe-list check and is removed with --one-file-system.

POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC=76
POCKETSHELL_RELEASE_DISK_MIN_FREE_MB=24576
POCKETSHELL_RELEASE_GRADLE_HOME_DIR=gradle-home
POCKETSHELL_RELEASE_RETENTION_OWNER_FILE=.release-validation-owner
POCKETSHELL_RELEASE_STORAGE_ROOT_MARKER=.release-validation-storage-root
POCKETSHELL_RELEASE_STORAGE_ROOT_KIND=pocketshell-release-validation-storage-v1

pocketshell_release_disk_min_free_mb() {
  printf '%s\n' "$POCKETSHELL_RELEASE_DISK_MIN_FREE_MB"
}

_pocketshell_release_storage_root() {
  local root="$1"
  local canonical marker marker_kind marker_repository marker_root expected_root line_count
  if [[ -z "$root" || "$root" != /* || "$root" == "/" ]]; then
    printf 'REFUSING: release-validation storage root must be a non-root absolute path: %s\n' "$root" >&2
    return 1
  fi
  if [[ -L "$root" ]]; then
    printf 'REFUSING: release-validation storage root must not be a symlink: %s\n' "$root" >&2
    return 1
  fi
  root="${root%/}"
  canonical="$(realpath -m -- "$root" 2>/dev/null)" || return 1
  if [[ "$canonical" != "$root" ]]; then
    printf 'REFUSING: release-validation storage root contains a symlink or non-canonical segment: %s -> %s\n' \
      "$root" "$canonical" >&2
    return 1
  fi
  case "$root" in
    /home | /tmp | /var | "${HOME:-/__unset_home__}")
      printf 'REFUSING: release-validation storage root is too broad to clean safely: %s\n' "$root" >&2
      return 1
      ;;
  esac
  if [[ ! -d "$root" || ! -O "$root" ]]; then
    printf 'REFUSING: release-validation storage root is not an owner-controlled directory: %s\n' \
      "$root" >&2
    return 1
  fi

  marker="$root/$POCKETSHELL_RELEASE_STORAGE_ROOT_MARKER"
  if [[ ! -f "$marker" || -L "$marker" || ! -O "$marker" ]]; then
    printf 'REFUSING: release-validation storage root lacks its release-owned provenance marker: %s\n' \
      "$marker" >&2
    return 1
  fi
  marker_kind="$(sed -n 's/^kind=//p' "$marker")"
  marker_repository="$(sed -n 's/^repository_root=//p' "$marker")"
  marker_root="$(sed -n 's/^release_root=//p' "$marker")"
  line_count="$(wc -l < "$marker" | tr -d ' ')"
  if [[ "$marker_kind" != "$POCKETSHELL_RELEASE_STORAGE_ROOT_KIND" ||
    -z "$marker_repository" || "$marker_repository" != /* ||
    "$marker_root" != "$root" || "$line_count" != "3" ]]; then
    printf 'REFUSING: release-validation storage root has an invalid provenance marker: %s\n' \
      "$marker" >&2
    return 1
  fi
  marker_repository="${marker_repository%/}"
  if [[ ! -d "$marker_repository" || -L "$marker_repository" || ! -O "$marker_repository" ||
    "$(realpath -e -- "$marker_repository" 2>/dev/null)" != "$marker_repository" ]]; then
    printf 'REFUSING: release-validation provenance repository root is not owner-controlled and canonical: %s\n' \
      "$marker_repository" >&2
    return 1
  fi
  expected_root="$marker_repository/build/pre-release-confidence-gate"
  if [[ "$root" != "$expected_root" ]]; then
    printf 'REFUSING: release-validation provenance does not anchor the canonical generated root: %s\n' \
      "$root" >&2
    return 1
  fi
  printf '%s\n' "$root"
}

# Establish the root marker only after proving the caller supplied this exact
# checkout's generated release directory. Environment overrides are therefore
# incapable of blessing themselves: source root, /var, and arbitrary absolute
# roots are rejected before mkdir/chmod/rm can be reached. The marker lets every
# lower-level cleanup helper fail closed even when called directly.
pocketshell_release_validation_prepare_root() {
  local repository_root="$1"
  local requested_root="$2"
  local canonical_repository canonical_requested expected_root marker temporary_marker

  [[ -n "$repository_root" && "$repository_root" == /* && ! -L "$repository_root" ]] || {
    printf 'REFUSING: release-validation repository root must be a non-symlink absolute path: %s\n' \
      "$repository_root" >&2
    return 1
  }
  canonical_repository="$(realpath -e -- "${repository_root%/}" 2>/dev/null)" || {
    printf 'REFUSING: release-validation repository root does not exist: %s\n' \
      "$repository_root" >&2
    return 1
  }
  canonical_requested="$(realpath -m -- "${requested_root%/}" 2>/dev/null)" || return 1
  expected_root="$canonical_repository/build/pre-release-confidence-gate"
  if [[ "$requested_root" != /* || "$requested_root" != "$canonical_requested" ||
    "$canonical_requested" != "$expected_root" ]]; then
    printf 'REFUSING: release-validation storage root is not the canonical generated root: %s (expected %s)\n' \
      "$requested_root" "$expected_root" >&2
    return 1
  fi
  [[ ! -L "$canonical_requested" ]] || {
    printf 'REFUSING: release-validation storage root must not be a symlink: %s\n' \
      "$canonical_requested" >&2
    return 1
  }
  mkdir -p -- "$canonical_requested" || return 1
  [[ -O "$canonical_requested" ]] || {
    printf 'REFUSING: release-validation storage root is not owned by uid %s: %s\n' \
      "$(id -u)" "$canonical_requested" >&2
    return 1
  }

  marker="$canonical_requested/$POCKETSHELL_RELEASE_STORAGE_ROOT_MARKER"
  if [[ ! -e "$marker" ]]; then
    temporary_marker="$(
      mktemp --tmpdir="$canonical_requested" '.release-validation-storage-root.tmp.XXXXXX'
    )" || return 1
    (umask 077
      {
        printf 'kind=%s\n' "$POCKETSHELL_RELEASE_STORAGE_ROOT_KIND"
        printf 'repository_root=%s\n' "$canonical_repository"
        printf 'release_root=%s\n' "$canonical_requested"
      } > "$temporary_marker") || return 1
    if ! mv -T -- "$temporary_marker" "$marker"; then
      rm -f -- "$temporary_marker"
      return 1
    fi
  fi
  _pocketshell_release_storage_root "$canonical_requested" >/dev/null
}

_pocketshell_release_run_id_is_safe() {
  local run_id="$1"
  [[ "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || return 1
  # Run directories and root control paths share one parent. Keep their
  # namespaces disjoint: otherwise a live owner marker below `gradle-home`
  # makes the cache look like a run to the writer but like a skipped control
  # directory to cleanup scanners. The dot-prefixed controls are already
  # excluded by the grammar, but list every control name explicitly so a future
  # relaxation cannot silently make one eligible.
  case "$run_id" in
    "$POCKETSHELL_RELEASE_GRADLE_HOME_DIR"|"$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE"|"$POCKETSHELL_RELEASE_STORAGE_ROOT_MARKER"|"$POCKETSHELL_RELEASE_STORAGE_ROOT_MARKER".tmp.*)
      return 1
      ;;
  esac
}

pocketshell_release_validation_require_run_id() {
  local run_id="$1"
  _pocketshell_release_run_id_is_safe "$run_id" || {
    printf 'REFUSING: unsafe or reserved release-validation run id: %s\n' "$run_id" >&2
    return 1
  }
}

_pocketshell_release_pid_start() {
  local pid="$1"
  [[ "$pid" =~ ^[1-9][0-9]*$ && -r "/proc/$pid/stat" ]] || return 1
  awk '{print $22}' "/proc/$pid/stat" 2>/dev/null
}

pocketshell_release_validation_mark_active() {
  local root run_id owner_pid owner_start run_dir owner_file
  root="$(_pocketshell_release_storage_root "$1")" || return 1
  run_id="$2"
  owner_pid="${3:-$$}"
  _pocketshell_release_run_id_is_safe "$run_id" || {
    printf 'FAIL: unsafe release-validation run id: %s\n' "$run_id" >&2
    return 1
  }
  owner_start="$(_pocketshell_release_pid_start "$owner_pid")" || {
    printf 'FAIL: cannot identify release-validation owner pid %s\n' "$owner_pid" >&2
    return 1
  }
  run_dir="$root/$run_id"
  [[ ! -L "$run_dir" ]] || {
    printf 'FAIL: release-validation run directory must not be a symlink: %s\n' "$run_dir" >&2
    return 1
  }
  mkdir -p "$run_dir"
  owner_file="$run_dir/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE"
  {
    printf 'pid=%s\n' "$owner_pid"
    printf 'start=%s\n' "$owner_start"
  } > "$owner_file"
}

pocketshell_release_validation_run_is_active() {
  local run_dir="$1"
  local owner_file="$run_dir/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE"
  local owner_pid owner_start current_start
  [[ -f "$owner_file" && ! -L "$owner_file" ]] || return 1
  owner_pid="$(sed -n 's/^pid=//p' "$owner_file" | head -n 1)"
  owner_start="$(sed -n 's/^start=//p' "$owner_file" | head -n 1)"
  [[ "$owner_pid" =~ ^[1-9][0-9]*$ && "$owner_start" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$owner_pid" 2>/dev/null || return 1
  current_start="$(_pocketshell_release_pid_start "$owner_pid")" || return 1
  [[ "$current_start" == "$owner_start" ]]
}

_pocketshell_release_output_is_safe() {
  local root="$1"
  local target="$2"
  local relative run_id
  [[ "$target" == /* && ! -L "$target" ]] || return 1
  if [[ "$target" == "$root/$POCKETSHELL_RELEASE_GRADLE_HOME_DIR" ]]; then
    return 0
  fi
  [[ "$target" == "$root/"*/worktree ]] || return 1
  relative="${target#"$root/"}"
  # Exactly RUN_ID/worktree: no nested glob, no source-worktree sibling.
  [[ "$relative" == */worktree && "$relative" != */*/* ]] || return 1
  run_id="${relative%/worktree}"
  _pocketshell_release_run_id_is_safe "$run_id" || return 1
  [[ -d "$root/$run_id" && ! -L "$root/$run_id" ]]
}

pocketshell_release_validation_output_size_mb() {
  local target="$1"
  du -sm -- "$target" 2>/dev/null | awk 'NR == 1 {print $1}'
}

_pocketshell_release_remove_output() {
  local root="$1"
  local target="$2"
  _pocketshell_release_output_is_safe "$root" "$target" || {
    printf 'REFUSING: not an exact generated release-validation output: %s\n' "$target" >&2
    return 1
  }
  [[ -e "$target" ]] || return 0

  # The #1714 recurrence deliberately contained 0555 directories and 0444
  # files.  Walk directories pre-order and chmod each immediately so find can
  # descend into the next level.  Symlinks are never chmodded or traversed.
  chmod u+rwx -- "$target" 2>/dev/null || true
  find -P "$target" -xdev -type d -exec chmod u+rwx -- {} \; 2>/dev/null || true
  find -P "$target" -xdev -type f -exec chmod u+rw -- {} + 2>/dev/null || true
  if ! rm -rf --one-file-system -- "$target"; then
    printf 'FAIL: could not remove generated release-validation output: %s\n' "$target" >&2
    return 1
  fi
  [[ ! -e "$target" ]] || {
    printf 'FAIL: generated release-validation output survived removal: %s\n' "$target" >&2
    return 1
  }
}

# Copy the small JUnit XML out of the generated worktree before it is removed.
#
# Issue #2435: this is the ONLY surviving JUnit evidence once the copy is gone,
# and .github/workflows/release-emulator-validation.yml's #2082 ledger step
# runs AFTER this cleanup, from the outer checkout. So it must cover BOTH result
# shapes the gate produces and that the ledger step looks for — plain unit
# results under */build/test-results/ and connected results under
# */build/outputs/androidTest-results/ (the gate runs
# :shared:core-terminal:connectedDebugAndroidTest, whose XML lands in the
# latter). The 5 MiB per-file bound keeps this a diagnostics copy, not a second
# copy of the build.
_pocketshell_release_package_test_results() {
  local worktree="$1"
  local run_dir="$2"
  local destination="$run_dir/retained-test-results"
  local file relative copied=0
  [[ -d "$worktree" ]] || return 0
  while IFS= read -r -d '' file; do
    relative="${file#"$worktree/"}"
    mkdir -p "$destination/$(dirname -- "$relative")" || return 1
    cp -p -- "$file" "$destination/$relative" || return 1
    copied=$((copied + 1))
  done < <(
    find -P "$worktree" -xdev -type f \
      \( -path '*/build/test-results/*' -o -path '*/build/outputs/androidTest-results/*' \) \
      -name 'TEST-*.xml' -size -5120k -print0 \
      2>/dev/null
  )
  if (( copied > 0 )); then
    printf 'Packaged %s small test-result XML file(s): %s\n' "$copied" "$destination"
  fi
}

# Finish one run owned by the caller.  Preserve small test XML in addition to
# the summary + step logs already written outside the worktree.  The exact
# copied worktree is then removed.  This is idempotent so the outer release
# wrapper can finish a run whose failing child already cleaned itself.
#
# Issue #2435: the packaging used to be conditional on `result == failure`.
# A GREEN release chain therefore deleted its 1354 MiB worktree — every
# */build/test-results/ XML the gate produced with it — and copied nothing out,
# so the #2082 ledger step that runs ~15 s later in the hosted workflow found
# no evidence and failed the job on a genuine `Automated status: PASS`. That
# made the #2356 `Record validated-rc marker` job, which needs
# `needs.emulator-release-validation.result == 'success'`, permanently
# unreachable. Success is exactly the outcome whose execution evidence the
# release ledger has to record, so it is now packaged on every outcome.
pocketshell_release_validation_finish_run() {
  local root run_id result run_dir worktree before_mb retention_file
  root="$(_pocketshell_release_storage_root "$1")" || return 1
  run_id="$2"
  result="${3:-failure}"
  _pocketshell_release_run_id_is_safe "$run_id" || {
    printf 'FAIL: unsafe release-validation run id: %s\n' "$run_id" >&2
    return 1
  }
  run_dir="$root/$run_id"
  worktree="$run_dir/worktree"
  [[ -d "$run_dir" && ! -L "$run_dir" ]] || return 0

  if [[ ! -e "$worktree" ]]; then
    rm -f -- "$run_dir/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE"
    return 0
  fi
  before_mb="$(pocketshell_release_validation_output_size_mb "$worktree")"
  [[ "$before_mb" =~ ^[0-9]+$ ]] || before_mb=0
  printf 'Generated isolated worktree before cleanup: %s MiB (%s)\n' "$before_mb" "$worktree"
  _pocketshell_release_package_test_results "$worktree" "$run_dir" ||
    printf 'WARN: could not package retained test XML before generated-output cleanup\n' >&2
  _pocketshell_release_remove_output "$root" "$worktree" || return 1
  # Keep the owner marker in place until packaging + removal are complete so a
  # concurrent manual cleanup/preflight cannot race the finishing owner.
  rm -f -- "$run_dir/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE"

  retention_file="$run_dir/retention.txt"
  {
    printf 'result=%s\n' "$result"
    printf 'removed_generated_worktree=%s\n' "$worktree"
    printf 'generated_worktree_size_mb=%s\n' "$before_mb"
    printf 'retained_diagnostics=%s\n' "$run_dir"
  } > "$retention_file" 2>/dev/null || true
  if [[ "$result" == "failure" ]]; then
    printf 'Retained failure diagnostics: %s (summary, step logs, and small test XML when present)\n' "$run_dir"
  else
    printf 'Retained release diagnostics: %s (summary, step logs, and small test XML when present)\n' "$run_dir"
  fi
  printf 'Safe cleanup command for older generated release scratch: scripts/disk-cleanup.sh --apply\n'
}

# Reclaim abandoned/completed copied worktrees before measuring a new release.
# A live owner is always skipped.  The shared Gradle home is deliberately not
# touched here: a sibling active release may still be using it.
pocketshell_release_validation_cleanup_stale() {
  local root run_dir run_id target size_mb failed=0
  root="$(_pocketshell_release_storage_root "$1")" || return 1
  [[ -d "$root" ]] || return 0
  for run_dir in "$root"/*; do
    [[ -d "$run_dir" && ! -L "$run_dir" ]] || continue
    run_id="$(basename -- "$run_dir")"
    _pocketshell_release_run_id_is_safe "$run_id" || continue
    target="$run_dir/worktree"
    [[ -e "$target" ]] || continue
    if pocketshell_release_validation_run_is_active "$run_dir"; then
      printf 'SKIP active release-validation output: %s\n' "$target"
      continue
    fi
    size_mb="$(pocketshell_release_validation_output_size_mb "$target")"
    [[ "$size_mb" =~ ^[0-9]+$ ]] || size_mb=0
    printf 'Reclaiming stale generated release worktree: %s (%s MiB)\n' "$target" "$size_mb"
    _pocketshell_release_remove_output "$root" "$target" || failed=1
    rm -f -- "$run_dir/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE"
  done
  (( failed == 0 ))
}

pocketshell_release_validation_any_run_is_active() {
  local root run_dir run_id
  root="$(_pocketshell_release_storage_root "$1")" || return 2
  [[ -d "$root" ]] || return 1
  # A round-2 process could already have written a live marker into the shared
  # cache through RUN_ID=gradle-home. Although new writers reject that name,
  # fail safe around the legacy collision until its owner exits.
  pocketshell_release_validation_run_is_active \
    "$root/$POCKETSHELL_RELEASE_GRADLE_HOME_DIR" && return 0
  for run_dir in "$root"/*; do
    [[ -d "$run_dir" && ! -L "$run_dir" ]] || continue
    run_id="$(basename -- "$run_dir")"
    _pocketshell_release_run_id_is_safe "$run_id" || continue
    pocketshell_release_validation_run_is_active "$run_dir" && return 0
  done
  return 1
}

# Manual safe-list sweep used by scripts/disk-cleanup.sh.  It retains every
# run's small diagnostics, removes only exact copied worktrees, and removes the
# private Gradle home only when no live release run exists.
pocketshell_release_validation_cleanup_all() {
  local root mode run_dir run_id target size_mb active=0 failed=0
  root="$(_pocketshell_release_storage_root "$1")" || return 1
  mode="${2:-apply}"
  [[ "$mode" == "apply" || "$mode" == "dry-run" ]] || return 1
  [[ -d "$root" ]] || return 0

  if pocketshell_release_validation_run_is_active \
    "$root/$POCKETSHELL_RELEASE_GRADLE_HOME_DIR"; then
    active=1
  fi

  for run_dir in "$root"/*; do
    [[ -d "$run_dir" && ! -L "$run_dir" ]] || continue
    run_id="$(basename -- "$run_dir")"
    _pocketshell_release_run_id_is_safe "$run_id" || continue
    target="$run_dir/worktree"
    if pocketshell_release_validation_run_is_active "$run_dir"; then
      active=1
      if [[ -e "$target" ]]; then
        printf '  SKIP active release-validation output: %s\n' "$target"
      else
        printf '  SKIP active release-validation run before copied output exists: %s\n' "$run_dir"
      fi
      continue
    fi
    [[ -e "$target" ]] || continue
    size_mb="$(pocketshell_release_validation_output_size_mb "$target")"
    [[ "$size_mb" =~ ^[0-9]+$ ]] || size_mb=0
    if [[ "$mode" == "dry-run" ]]; then
      printf '  [DRY-RUN] would remove generated release worktree %s (%s MiB); keep diagnostics in %s\n' \
        "$target" "$size_mb" "$run_dir"
    else
      _pocketshell_release_remove_output "$root" "$target" || failed=1
      printf '  [APPLY]   removed generated release worktree %s (%s MiB); kept diagnostics in %s\n' \
        "$target" "$size_mb" "$run_dir"
      rm -f -- "$run_dir/$POCKETSHELL_RELEASE_RETENTION_OWNER_FILE"
    fi
  done

  target="$root/$POCKETSHELL_RELEASE_GRADLE_HOME_DIR"
  if [[ -e "$target" ]]; then
    size_mb="$(pocketshell_release_validation_output_size_mb "$target")"
    [[ "$size_mb" =~ ^[0-9]+$ ]] || size_mb=0
    if (( active == 1 )); then
      printf '  SKIP active release-validation output uses shared Gradle home: %s\n' "$target"
    elif [[ "$mode" == "dry-run" ]]; then
      printf '  [DRY-RUN] would remove generated release Gradle home %s (%s MiB)\n' "$target" "$size_mb"
    else
      _pocketshell_release_remove_output "$root" "$target" || failed=1
      printf '  [APPLY]   removed generated release Gradle home %s (%s MiB)\n' "$target" "$size_mb"
    fi
  fi
  (( failed == 0 ))
}

pocketshell_release_disk_preflight() {
  local path="$1"
  local log_root="$2"
  local label="${3:-release validation}"
  local free_mb min_free_mb

  # Bounded retention is part of the preflight: abandoned completed copies are
  # reclaimed before the free-space verdict, while a live copy is untouchable.
  pocketshell_release_validation_cleanup_stale "$log_root" || {
    printf 'FAIL: %s refuses to start because stale generated release output could not be reclaimed safely.\n' "$label" >&2
    return "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
  }
  min_free_mb="$(pocketshell_release_disk_min_free_mb)"
  free_mb="$(pocketshell_disk_free_mb "$path")" || {
    printf 'FAIL: %s refuses to start without a readable free-space report for %s (issue #2055).\n' \
      "$label" "$path" >&2
    return "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
  }
  # When stale copied worktrees were not enough, the private release Gradle
  # home is the remaining large safe-listed cache from the incident (~1.9 GiB).
  # Reclaim it only while every release owner is idle, then re-measure. Never
  # delete a cache merely because the box is healthy.
  if (( free_mb < min_free_mb )) &&
    [[ -e "$log_root/$POCKETSHELL_RELEASE_GRADLE_HOME_DIR" ]]; then
    if pocketshell_release_validation_any_run_is_active "$log_root"; then
      printf 'SKIP active release-validation output uses shared Gradle home: %s\n' \
        "$log_root/$POCKETSHELL_RELEASE_GRADLE_HOME_DIR"
    else
      printf 'Reclaiming idle generated release Gradle home before disk verdict: %s\n' \
        "$log_root/$POCKETSHELL_RELEASE_GRADLE_HOME_DIR"
      _pocketshell_release_remove_output \
        "$log_root" "$log_root/$POCKETSHELL_RELEASE_GRADLE_HOME_DIR" ||
        return "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
      free_mb="$(pocketshell_disk_free_mb "$path")" ||
        return "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
    fi
  fi
  if (( free_mb < min_free_mb )); then
    {
      printf '\n=== RELEASE DISK PREFLIGHT FAILED (issue #2055) ===\n'
      printf '%s refuses to start before the AVD lock, isolated copy, or Gradle.\n' "$label"
      printf '  path:      %s\n' "$path"
      printf '  free:      %s MiB\n' "$free_mb"
      printf '  required:  %s MiB (24 GiB release-validation floor)\n' "$min_free_mb"
      printf '  filesystem usage:\n'
      df -h -- "$path" 2>/dev/null | sed 's/^/    /'
      printf '\nThe release envelope includes two clean variant families, the isolated\n'
      printf 'worktree/build outputs, the private Gradle home, and APK/emulator evidence.\n'
      printf 'The generic 10 GiB one-run floor is intentionally unchanged and is not\n'
      printf 'large enough for this chain. This run did NOT start.\n'
      printf '\nReclaim only generated safe-listed scratch:\n'
      printf '    scripts/disk-cleanup.sh            # dry run; preserves summaries/logs\n'
      printf '    scripts/disk-cleanup.sh --apply    # removes copied worktrees/caches\n'
      printf 'The cleanup never touches source worktrees, .worktrees/issue-*, user files,\n'
      printf '.gradle/caches, .android/avd, or pocketshell-test:* images.\n'
    } >&2
    return "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
  fi
  printf 'Release disk preflight OK (issue #2055): %s MiB free; required %s MiB.\n' \
    "$free_mb" "$min_free_mb"
}
