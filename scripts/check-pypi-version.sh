#!/usr/bin/env bash
set -euo pipefail

# Version coupling guard for issue #223.
#
# Compares the Android `versionName` (app/build.gradle.kts) against the
# `pocketshell` Python package `version` (tools/pocketshell/pyproject.toml).
# Optionally also compares both against a release tag passed as $1
# (with or without the leading `v`). Exits non-zero on mismatch.
#
# The release flow uses this to fail loudly when the maintainer bumps one
# side but forgets the other before pushing a tag. The GitHub Actions
# `build.yml` calls this in --check-tag mode on every tag push so the
# PyPI publish step never runs against a stale pyproject.toml version.
#
# Usage:
#   scripts/check-pypi-version.sh                 # local match check
#   scripts/check-pypi-version.sh --check-tag vX  # also check against tag
#   scripts/check-pypi-version.sh --dry-run       # exit 0 even on mismatch,
#                                                 # but print what *would*
#                                                 # have failed (smoke test)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DRY_RUN=0
TAG=""

usage() {
  cat <<'USAGE'
Usage: scripts/check-pypi-version.sh [--dry-run] [--check-tag <tag>]

Verifies that the Android `versionName` in app/build.gradle.kts equals
the `pocketshell` Python package `version` in
tools/pocketshell/pyproject.toml. When --check-tag <vX.Y.Z> is
passed, also asserts that both equal the tag (with the leading `v`
stripped).

Exits 0 on match, non-zero on mismatch. --dry-run swallows the failure
exit so the smoke path can be exercised without breaking CI.
USAGE
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --check-tag)
      TAG="${2:-}"
      if [[ -z "$TAG" ]]; then
        printf 'FAIL: --check-tag requires a value\n' >&2
        exit 2
      fi
      shift 2
      ;;
    *)
      printf 'FAIL: unknown arg: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

fail() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf 'DRY RUN would FAIL: %s\n' "$1" >&2
    exit 0
  fi
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

GRADLE_FILE="app/build.gradle.kts"
PYPROJECT_FILE="tools/pocketshell/pyproject.toml"

[[ -f "$GRADLE_FILE" ]] || fail "missing $GRADLE_FILE"
[[ -f "$PYPROJECT_FILE" ]] || fail "missing $PYPROJECT_FILE"

VERSION_NAME="$(
  sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' "$GRADLE_FILE" |
    head -n 1
)"
[[ -n "$VERSION_NAME" ]] || fail "could not parse versionName from $GRADLE_FILE"

# Pull the first `version = "..."` under [project] from pyproject.toml.
# Hatchling pyproject.toml has both [build-system] (no `version` key) and
# [project] (the one we want), so grabbing the first hit is reliable
# enough for our single-package layout.
PYPI_VERSION="$(
  sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' "$PYPROJECT_FILE" |
    head -n 1
)"
[[ -n "$PYPI_VERSION" ]] || fail "could not parse version from $PYPROJECT_FILE"

printf 'Android versionName:    %s\n' "$VERSION_NAME"
printf 'pocketshell PyPI version: %s\n' "$PYPI_VERSION"

if [[ "$VERSION_NAME" != "$PYPI_VERSION" ]]; then
  fail "versionName ($VERSION_NAME) != pyproject.toml version ($PYPI_VERSION). Bump both in lockstep before tagging."
fi

if [[ -n "$TAG" ]]; then
  EXPECTED="${TAG#v}"
  printf 'Release tag:             %s (expects %s)\n' "$TAG" "$EXPECTED"
  if [[ "$VERSION_NAME" != "$EXPECTED" ]]; then
    fail "versionName ($VERSION_NAME) does not match release tag $TAG"
  fi
  if [[ "$PYPI_VERSION" != "$EXPECTED" ]]; then
    fail "pyproject.toml version ($PYPI_VERSION) does not match release tag $TAG"
  fi
fi

printf 'OK: versions are aligned\n'
