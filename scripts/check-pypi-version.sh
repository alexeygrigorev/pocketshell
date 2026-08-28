#!/usr/bin/env bash
set -euo pipefail

# Version coupling guard for issue #223, RESTRUCTURED for #2356 (Phase 4 of
# epic #2350).
#
# BEFORE #2356: compared the Android `versionName` (parsed as a string
# literal from app/build.gradle.kts) against the `pocketshell` Python package
# `version` (tools/pocketshell/pyproject.toml), optionally also against the
# release tag being built.
#
# AFTER #2356: app/build.gradle.kts's versionName is no longer a string
# literal — it is derived at configuration time by
# scripts/derive-version.sh (the git-tag-derived value), so there is nothing
# left to `sed`-parse out of the Gradle file. This guard now:
#   1. Derives the EXPECTED versionName for the given tag directly via
#      `scripts/derive-version.sh version-name --ref <tag>` (the same script
#      app/build.gradle.kts calls — never a second, independently-written
#      implementation).
#   2. Compares that expected value against `tools/pocketshell/pyproject.toml`
#      `version`, which .github/workflows/build.yml's publish-pypi job stamps
#      from the SAME script immediately before this guard runs (see the
#      "Stamp pyproject.toml version from tag" step) — so under normal
#      operation this is a proof that the stamping step didn't silently
#      diverge from the derivation, not two hand-maintained numbers.
#
# Usage:
#   scripts/check-pypi-version.sh --check-tag vX.Y.Z  # the release flow
#   scripts/check-pypi-version.sh --dry-run --check-tag vX.Y.Z
#                                                      # exit 0 even on
#                                                      # mismatch, but print
#                                                      # what *would* have
#                                                      # failed (smoke test)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DRY_RUN=0
TAG=""

usage() {
  cat <<'USAGE'
Usage: scripts/check-pypi-version.sh --check-tag <vX.Y.Z> [--dry-run]

Verifies that tools/pocketshell/pyproject.toml `version` equals what
scripts/derive-version.sh would derive as versionName for the given release
tag (with the leading `v` stripped). Exits 0 on match, non-zero on mismatch.
--dry-run swallows the failure exit so the smoke path can be exercised
without breaking CI.
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

if [[ -z "$TAG" ]]; then
  usage >&2
  fail "--check-tag is required"
fi

DERIVE_SCRIPT="scripts/derive-version.sh"
PYPROJECT_FILE="tools/pocketshell/pyproject.toml"

[[ -f "$DERIVE_SCRIPT" ]] || fail "missing $DERIVE_SCRIPT"
[[ -f "$PYPROJECT_FILE" ]] || fail "missing $PYPROJECT_FILE"

EXPECTED="${TAG#v}"

DERIVED="$(bash "$DERIVE_SCRIPT" version-name --ref "$TAG" 2>/dev/null || true)"
[[ -n "$DERIVED" ]] || fail "scripts/derive-version.sh could not derive a versionName for tag $TAG"

printf 'Release tag:              %s (expects %s)\n' "$TAG" "$EXPECTED"
printf 'derive-version.sh output: %s\n' "$DERIVED"

if [[ "$DERIVED" != "$EXPECTED" ]]; then
  fail "scripts/derive-version.sh derived '$DERIVED' for tag $TAG, expected an EXACT match '$EXPECTED'. This means the tag ref was not checked out with its tag object reachable (shallow checkout?) or derive-version.sh has a bug — either way, do not publish."
fi

PYPI_VERSION="$(
  sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' "$PYPROJECT_FILE" |
    head -n 1
)"
[[ -n "$PYPI_VERSION" ]] || fail "could not parse version from $PYPROJECT_FILE"

printf 'pocketshell PyPI version:  %s\n' "$PYPI_VERSION"

if [[ "$PYPI_VERSION" != "$EXPECTED" ]]; then
  fail "pyproject.toml version ($PYPI_VERSION) does not match release tag $TAG (expected $EXPECTED). The 'Stamp pyproject.toml version from tag' step in build.yml must run before this guard."
fi

printf 'OK: versions are aligned (tag-derived, issue #2356)\n'
