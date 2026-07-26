#!/usr/bin/env bash
# Preserve Android connected-test outputs from authoritative Gradle project
# directories. Validate every source and destination before copying so symlinked
# paths cannot escape the repository or recursively package artifacts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="${CI_JOURNEY_REPO_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd -P)}"
SETTINGS_FILE="${CI_JOURNEY_SETTINGS_FILE:-}"
SETTINGS_PARSER="${CI_JOURNEY_SETTINGS_PARSER:-$SCRIPT_DIR/ci-journey-settings-project-dirs.py}"

fail() {
  echo "CI journey artifact preservation failed: $*" >&2
  exit 1
}

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 <snapshot-directory>" >&2
  exit 2
fi

[[ -d "$REPO_ROOT" ]] || fail "repository root is not a directory: $REPO_ROOT"
repo_root="$(realpath -e -- "$REPO_ROOT")"
[[ -n "$SETTINGS_FILE" ]] || SETTINGS_FILE="$repo_root/settings.gradle.kts"
[[ -f "$SETTINGS_FILE" ]] || fail "Gradle settings file is missing: $SETTINGS_FILE"
[[ -f "$SETTINGS_PARSER" ]] || fail "Gradle settings parser is missing: $SETTINGS_PARSER"

path_has_symlink() {
  local relative_path="$1"
  local cursor="$repo_root"
  local component
  local -a components=()

  [[ "$relative_path" == "." ]] && return 1
  IFS='/' read -r -a components <<<"$relative_path"
  for component in "${components[@]}"; do
    cursor="$cursor/$component"
    [[ -L "$cursor" ]] && return 0
  done
  return 1
}

validate_relative_path() {
  local relative_path="$1"
  local label="$2"
  local component
  local -a components=()

  [[ -n "$relative_path" ]] || fail "$label is empty"
  [[ "$relative_path" != /* ]] || fail "$label must be repository-relative: $relative_path"
  [[ "$relative_path" != "." ]] || return 0

  IFS='/' read -r -a components <<<"$relative_path"
  for component in "${components[@]}"; do
    [[ -n "$component" && "$component" != "." && "$component" != ".." ]] \
      || fail "$label has an unsafe component: $relative_path"
  done
}

validate_snapshot_write_path() {
  local target="$1"
  local relative
  local cursor="$repo_root"
  local canonical
  local component
  local -a components=()

  case "$target" in
    "$snapshot"|"$snapshot"/*) ;;
    *) fail "destination escapes snapshot: $target" ;;
  esac
  relative="${target#"$repo_root/"}"
  validate_relative_path "$relative" "snapshot destination"
  path_has_symlink "$relative" \
    && fail "snapshot destination has symlink ancestry: $relative"

  IFS='/' read -r -a components <<<"$relative"
  for component in "${components[@]}"; do
    cursor="$cursor/$component"
    [[ -e "$cursor" ]] || continue
    canonical="$(realpath -e -- "$cursor")" \
      || fail "snapshot destination ancestor cannot be canonicalized: $cursor"
    case "$canonical" in
      "$repo_root"|"$repo_root"/*) ;;
      *) fail "snapshot destination ancestor escapes repository: $cursor -> $canonical" ;;
    esac
  done
  if [[ -e "$target" ]]; then
    canonical="$(realpath -e -- "$target")" \
      || fail "snapshot destination cannot be canonicalized: $target"
    case "$canonical" in
      "$snapshot"|"$snapshot"/*) ;;
      *) fail "snapshot destination escapes canonical snapshot: $target -> $canonical" ;;
    esac
  fi
}

snapshot_arg="$1"
validate_relative_path "$snapshot_arg" "snapshot path"
path_has_symlink "$snapshot_arg" \
  && fail "snapshot path has symlink ancestry: $snapshot_arg"
snapshot_abs="$(realpath -m -- "$repo_root/$snapshot_arg")"
case "$snapshot_abs" in
  "$repo_root"/*) ;;
  *) fail "snapshot path escapes repository: $snapshot_arg" ;;
esac
[[ -d "$snapshot_abs" ]] || fail "snapshot directory does not exist: $snapshot_arg"
snapshot="$snapshot_abs"

declare -a source_paths=()
declare -a destination_paths=()
project_count=0

project_inventory="$(mktemp)"
trap 'rm -f "$project_inventory"' EXIT
python3 "$SETTINGS_PARSER" "$SETTINGS_FILE" > "$project_inventory" \
  || fail "could not derive Gradle project directories"

while IFS= read -r -d '' project_dir; do
  validate_relative_path "$project_dir" "Gradle projectDir"
  path_has_symlink "$project_dir" \
    && fail "Gradle projectDir has symlink ancestry: $project_dir"

  project_abs="$(realpath -e -- "$repo_root/$project_dir")" \
    || fail "Gradle projectDir does not exist: $project_dir"
  case "$project_abs" in
    "$repo_root"|"$repo_root"/*) ;;
    *) fail "Gradle projectDir escapes repository: $project_dir -> $project_abs" ;;
  esac
  [[ -d "$project_abs" ]] || fail "Gradle projectDir is not a directory: $project_dir"
  project_relative="$(realpath --relative-to="$repo_root" -- "$project_abs")"
  project_count=$((project_count + 1))

  build_abs="$project_abs/build"
  [[ -e "$build_abs" ]] || continue
  build_relative="${project_relative%/}/build"
  [[ "$project_relative" == "." ]] && build_relative="build"
  path_has_symlink "$build_relative" \
    && fail "Gradle build path has symlink ancestry: $build_relative"
  canonical_build="$(realpath -e -- "$build_abs")" \
    || fail "Gradle build path cannot be canonicalized: $build_relative"
  case "$canonical_build" in
    "$repo_root"/*) ;;
    *) fail "Gradle build path escapes repository: $build_relative -> $canonical_build" ;;
  esac
  [[ -d "$canonical_build" ]] || fail "Gradle build path is not a directory: $build_relative"

  for output_path in \
    reports/androidTests \
    outputs/androidTest-results \
    outputs/connected_android_test_additional_output; do
    source_abs="$canonical_build/$output_path"
    [[ -e "$source_abs" ]] || continue
    source_relative="$build_relative/$output_path"
    path_has_symlink "$source_relative" \
      && fail "Android output path has symlink ancestry: $source_relative"
    canonical_source="$(realpath -e -- "$source_abs")" \
      || fail "Android output path cannot be canonicalized: $source_relative"
    case "$canonical_source" in
      "$repo_root"/*) ;;
      *) fail "Android output path escapes repository: $source_relative -> $canonical_source" ;;
    esac
    [[ -d "$canonical_source" ]] || fail "Android output path is not a directory: $source_relative"
    source_paths+=("$canonical_source")
    destination_paths+=("$snapshot_abs/android-test-outputs/$source_relative")
  done
done < "$project_inventory"

[[ "$project_count" -gt 0 ]] || fail "Gradle settings contain no project directories"

validate_snapshot_write_path "$snapshot/android-test-outputs"
for destination in "${destination_paths[@]}"; do
  validate_snapshot_write_path "$destination"
done

if [[ "${#source_paths[@]}" -eq 0 ]]; then
  validate_snapshot_write_path "$snapshot/android-test-outputs-missing.txt"
  echo "No Android connected-test output directories existed after the first attempt." \
    > "$snapshot/android-test-outputs-missing.txt"
else
  for index in "${!source_paths[@]}"; do
    mkdir -p "$(dirname "${destination_paths[$index]}")"
    cp -a "${source_paths[$index]}" "${destination_paths[$index]}"
  done
fi
