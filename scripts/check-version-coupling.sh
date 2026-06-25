#!/usr/bin/env bash
set -euo pipefail

# PR-time version-coupling guard for issue #948.
#
# The Android app derives the EXPECTED host `pocketshell` CLI version from its
# own `versionName` (app/build.gradle.kts). `FolderListViewModel`'s
# `expectedPocketshellVersionProvider` reads
# `packageManager.getPackageInfo(...).versionName`, and
# `PayloadVersionCheck.evaluate` compares the host CLI version against it to
# drive the in-app host-version-mismatch banner. So the app `versionName` and
# the `tools/pocketshell` Python package `version` MUST be the same string.
#
# They drifted on 2026-06-25 (app 0.4.16 vs package 0.4.14); the in-app banner
# nagged the maintainer and PR #946 bumped the package manually. The existing
# `scripts/check-pypi-version.sh` only runs on TAG push in build.yml, so the
# drift was never caught at PR time. This guard closes that gap: it runs in the
# cheap Python job of .github/workflows/tests.yml so a `versionName` bump
# without a matching `pyproject.toml` bump goes RED at PR time.
#
# Usage:
#   scripts/check-version-coupling.sh        # check the real tree
#   scripts/check-version-coupling.sh --self-test
#                                            # run an in-process red->green
#                                            # proof on synthetic fixtures and
#                                            # exit 0 only if both pass

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

GRADLE_REL="app/build.gradle.kts"
PYPROJECT_REL="tools/pocketshell/pyproject.toml"

usage() {
  cat <<'USAGE'
Usage: scripts/check-version-coupling.sh [--self-test]

Verifies that the app's expected host `pocketshell` version (the Android
`versionName` in app/build.gradle.kts) equals the `pocketshell` Python package
`version` in tools/pocketshell/pyproject.toml. Exits 0 on match, non-zero (1) on
mismatch with a clear message.

--self-test runs a synthetic red->green proof: it builds a matched fixture pair
(expects PASS) and a skewed fixture pair (expects FAIL), and exits 0 only if the
guard behaves correctly on both. Use it to prove the guard works without
touching the real version files.
USAGE
}

# Extract the app `versionName` from an app/build.gradle.kts file.
parse_version_name() {
  local file="$1"
  sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' "$file" |
    head -n 1
}

# Extract the `pocketshell` package `version` from a pyproject.toml file.
# Hatchling pyproject.toml has both [build-system] (no `version`) and [project]
# (the one we want); grabbing the first `version = "..."` is reliable for the
# single-package layout.
parse_pyproject_version() {
  local file="$1"
  sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' "$file" |
    head -n 1
}

# Core comparison. Args: <gradle-file> <pyproject-file>.
# Exits 0 on match, 1 on mismatch. Prints both versions and the verdict.
check_pair() {
  local gradle_file="$1"
  local pyproject_file="$2"

  if [[ ! -f "$gradle_file" ]]; then
    printf 'FAIL: missing %s\n' "$gradle_file" >&2
    return 1
  fi
  if [[ ! -f "$pyproject_file" ]]; then
    printf 'FAIL: missing %s\n' "$pyproject_file" >&2
    return 1
  fi

  local version_name pkg_version
  version_name="$(parse_version_name "$gradle_file")"
  pkg_version="$(parse_pyproject_version "$pyproject_file")"

  if [[ -z "$version_name" ]]; then
    printf 'FAIL: could not parse versionName from %s\n' "$gradle_file" >&2
    return 1
  fi
  if [[ -z "$pkg_version" ]]; then
    printf 'FAIL: could not parse version from %s\n' "$pyproject_file" >&2
    return 1
  fi

  printf 'app versionName (expected host version): %s\n' "$version_name"
  printf 'tools/pocketshell package version:       %s\n' "$pkg_version"

  if [[ "$version_name" != "$pkg_version" ]]; then
    printf 'FAIL: tools/pocketshell version (%s) != app versionName (%s).\n' \
      "$pkg_version" "$version_name" >&2
    printf '      The app expects the host `pocketshell` CLI to report %s ' \
      "$version_name" >&2
    printf '(it reads its own versionName);\n' >&2
    printf '      a mismatch makes the in-app host-version banner nag the user.\n' >&2
    printf '      Bump %s `version` to %s (or fix the versionName) and commit both in lockstep.\n' \
      "$pyproject_file" "$version_name" >&2
    return 1
  fi

  printf 'OK: app versionName and tools/pocketshell version are aligned (%s).\n' \
    "$version_name"
  return 0
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  # Matched fixtures -> expect PASS (exit 0).
  mkdir -p "$tmp/match/app" "$tmp/match/tools/pocketshell"
  printf '    versionName = "9.9.9"\n' > "$tmp/match/app/build.gradle.kts"
  printf '[project]\nversion = "9.9.9"\n' > "$tmp/match/tools/pocketshell/pyproject.toml"

  # Skewed fixtures -> expect FAIL (exit 1).
  mkdir -p "$tmp/skew/app" "$tmp/skew/tools/pocketshell"
  printf '    versionName = "9.9.9"\n' > "$tmp/skew/app/build.gradle.kts"
  printf '[project]\nversion = "9.9.8"\n' > "$tmp/skew/tools/pocketshell/pyproject.toml"

  local failures=0

  printf '== self-test: matched pair (expect PASS) ==\n'
  if check_pair "$tmp/match/app/build.gradle.kts" "$tmp/match/tools/pocketshell/pyproject.toml"; then
    printf '   -> PASS as expected\n\n'
  else
    printf '   -> UNEXPECTED FAIL on matched pair\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: skewed pair (expect FAIL) ==\n'
  if check_pair "$tmp/skew/app/build.gradle.kts" "$tmp/skew/tools/pocketshell/pyproject.toml"; then
    printf '   -> UNEXPECTED PASS on skewed pair\n\n' >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected\n\n'
  fi

  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: matched pair passes, skewed pair fails.\n'
  return 0
}

main() {
  case "${1:-}" in
    -h|--help)
      usage
      exit 0
      ;;
    --self-test)
      run_self_test
      exit $?
      ;;
    "")
      check_pair "$ROOT_DIR/$GRADLE_REL" "$ROOT_DIR/$PYPROJECT_REL"
      exit $?
      ;;
    *)
      printf 'FAIL: unknown arg: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
