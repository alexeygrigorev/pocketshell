#!/usr/bin/env bash

# Issue #2356 (Phase 4 of epic #2350): `app/build.gradle.kts` no longer has a
# literal `versionName = "X.Y.Z"` to parse — it is derived at Gradle
# configuration time from scripts/derive-version.sh (the git tag being
# built). This helper now delegates to that SAME script rather than carrying
# a second, independently-written derivation that would silently drift.
#
# $root_dir is the checkout to derive against; it must contain
# scripts/derive-version.sh (a real PocketShell checkout, or a synthetic
# fixture repo built the same way — see tests/scripts/app-version-test.sh).
pocketshell_app_version_name() {
  local root_dir="$1"
  local derive_script="$root_dir/scripts/derive-version.sh"
  local version_name

  if [[ ! -f "$derive_script" ]]; then
    printf 'missing %s\n' "$derive_script" >&2
    return 1
  fi

  version_name="$(bash "$derive_script" version-name 2>/dev/null)"
  if [[ -z "$version_name" ]]; then
    printf 'could not derive versionName via %s\n' "$derive_script" >&2
    return 1
  fi

  printf '%s\n' "$version_name"
}

pocketshell_agent_fixture_version_output() {
  local version_name="$1"
  printf 'pocketshell fixture %s\n' "$version_name"
}
