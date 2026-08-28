#!/usr/bin/env bash
# scripts/derive-version.sh — issue #2356 (Phase 4 of epic #2350)
#
# SINGLE SOURCE OF TRUTH for both the Android app's `versionCode`/`versionName`
# (app/build.gradle.kts) and the `tools/pocketshell` PyPI package version
# (stamped into pyproject.toml right before publish — see build.yml). Every
# consumer MUST shell out to THIS script rather than re-implementing the git
# commands, so the two sides can never independently drift (the property
# scripts/check-version-coupling.sh now enforces structurally).
#
# Replaces the pre-#2356 "bump versionName + pyproject.toml version by hand,
# commit, PR, merge, THEN tag" release procedure (D22 hard cut — no legacy
# path). The tag pushed by scripts/push-release-tag.sh IS the version
# declaration now; nothing else declares it.
#
# DESIGN CONSTRAINTS (all load-bearing, do not relax without re-reading the
# issue):
#   * MUST NEVER fail/crash/hang a build. A dev box with no tags, a shallow CI
#     checkout with no tag history, or a missing `git` binary must all still
#     produce a valid (if approximate) version. Every code path below either
#     succeeds or falls through to the documented placeholder.
#   * versionCode MUST be monotonically increasing release-over-release. It is
#     derived as (count of `v*` tags reachable from the ref, inclusive) plus a
#     constant OFFSET. Tag count is monotonic by construction: once a tag
#     exists it is never un-reachable from a later commit (this repo never
#     deletes release tags — scripts/push-release-tag.sh refuses to reuse a
#     tag name), so every NEW release tag strictly increases the count by
#     exactly 1. OFFSET=1 was chosen to exactly reproduce the committed
#     history from v0.4.16 onward (see the table below) rather than jumping
#     the sequence; it has no other significance and must never be changed
#     retroactively (that would either collide with or skip already-shipped
#     versionCodes).
#
#     Verified against the FULL real tag history (v0.1.0..v0.4.44, 90 tags) on
#     2026-08-28:
#       - `git tag --list 'v*' --merged <tag>` (inclusive count) matches the
#         real committed versionCode at that tag, MINUS this OFFSET, for every
#         tag from v0.2.1 onward without exception (72 consecutive matches).
#       - v0.4.16 begins a permanent +1 shift versus the v0.2.1..v0.4.14 run:
#         a versionCode bump landed once, pre-#2356, without a matching tag
#         (v0.4.15 does not exist), permanently offsetting the "clean" tag-
#         count formula by one release. OFFSET=1 absorbs that historical
#         artifact so the FIRST tag-derived versionCode (for the next release
#         after v0.4.44, i.e. the already-staged 92) matches the value that
#         was already committed and tested before this issue landed — no
#         value is ever skipped or reused.
#       - v0.1.0 and v0.2.0 predate the lockstep-bump convention entirely
#         (v0.2.0's committed versionCode was never bumped past v0.1.0's) and
#         are historical anomalies this script cannot and need not reproduce;
#         they are many releases behind HEAD and never re-built.
#
#   * versionName, for an EXACT tag build (`git describe --exact-match --tags`
#     succeeds), is the tag with its leading `v` stripped — e.g. `v0.4.45` ->
#     `0.4.45`, matching the pre-#2356 convention exactly (so
#     scripts/push-release-tag.sh's downstream consumers see the same shape).
#     For a non-exact build (local dev loop, PR/branch CI), it is
#     `git describe --tags --always` with the leading `v` stripped (e.g.
#     `0.4.44-12-gabc1234`), or `0.0.0-dev+<short-sha>` when no `v*` tag is
#     reachable at all, or the bare placeholder `0.0.0-dev` when git itself is
#     unavailable/not a repo.
#
# USAGE
#   derive-version.sh version-code [--ref REF]
#   derive-version.sh version-name [--ref REF]
#   derive-version.sh both [--ref REF]     (default; prints both, KEY=VALUE)
#   derive-version.sh --self-test
#
# --ref defaults to HEAD. Passing an explicit ref lets a caller derive the
# version for a commit/tag other than the current checkout (e.g. a throwaway
# worktree, or scripts/push-release-tag.sh deriving against a not-yet-pushed
# local tag it just created).
#
# Self-test: run this script with --self-test (builds synthetic git repos
# under mktemp, never touches this repo's own tags).

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# See the historical-anomaly note above. NEVER change this without re-reading
# the full derivation table in the block comment — changing it silently
# reuses or skips a real, already-shipped Android versionCode.
readonly VERSION_CODE_OFFSET=1

usage() {
  sed -n '2,58p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# True if $1 is inside a usable git work tree with a `git` binary on PATH.
git_usable() {
  command -v git >/dev/null 2>&1 || return 1
  git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1
}

# Echoes the derived versionCode (a positive integer) for --ref (default
# HEAD) inside the repo at $1 (default ROOT_DIR). Never fails: falls back to
# the OFFSET floor (1) when git/tags are unavailable.
derive_version_code() {
  local repo="$1" ref="$2"
  if ! command -v git >/dev/null 2>&1; then
    echo "$VERSION_CODE_OFFSET"
    return
  fi
  if ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "$VERSION_CODE_OFFSET"
    return
  fi
  local n
  n="$(git -C "$repo" tag --list 'v*' --merged "$ref" 2>/dev/null | grep -c '^v[0-9]')" || n=0
  [[ "$n" =~ ^[0-9]+$ ]] || n=0
  echo $((n + VERSION_CODE_OFFSET))
}

# Echoes the derived versionName (a non-empty string) for --ref (default
# HEAD) inside the repo at $1 (default ROOT_DIR). Never fails.
derive_version_name() {
  local repo="$1" ref="$2"
  if ! command -v git >/dev/null 2>&1; then
    echo "0.0.0-dev"
    return
  fi
  if ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "0.0.0-dev"
    return
  fi

  local exact
  if exact="$(git -C "$repo" describe --exact-match --tags --match 'v*' "$ref" 2>/dev/null)" &&
    [[ -n "$exact" ]]; then
    printf '%s\n' "${exact#v}"
    return
  fi

  local desc
  if desc="$(git -C "$repo" describe --tags --match 'v*' --always "$ref" 2>/dev/null)" &&
    [[ -n "$desc" ]]; then
    if [[ "$desc" == v* ]]; then
      # Non-exact but relative to a real v* tag: "v0.4.44-12-gabc1234".
      printf '%s\n' "${desc#v}"
    else
      # No v* tag reachable at all: --always fell back to a bare short sha.
      printf '0.0.0-dev+%s\n' "$desc"
    fi
    return
  fi

  echo "0.0.0-dev"
}

run_self_test() {
  local failures=0
  local sandbox
  sandbox="$(mktemp -d)"
  trap 'rm -rf "$sandbox"' RETURN

  local repo="$sandbox/repo"
  mkdir -p "$repo"
  git -C "$repo" init --quiet -b main
  git -C "$repo" config user.email "test@example.com"
  git -C "$repo" config user.name "Self Test"

  check() {
    local desc="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then
      printf '  ok: %s -> %s\n' "$desc" "$actual"
    else
      printf '  FAIL: %s -> got %s, expected %s\n' "$desc" "$actual" "$expected" >&2
      failures=$((failures + 1))
    fi
  }

  # No commits, no tags at all yet: must not crash, must fall back cleanly.
  # (git describe on an unborn HEAD fails; derive_version_name must still
  # return the placeholder, not propagate the error.)
  local vc vn
  vc="$(derive_version_code "$repo" HEAD 2>/dev/null || echo ERROR)"
  check "empty repo versionCode" "$vc" "1"

  git -C "$repo" commit --quiet --allow-empty -m "c1"
  vn="$(derive_version_name "$repo" HEAD)"
  check "untagged repo versionName" "$vn" "0.0.0-dev+$(git -C "$repo" rev-parse --short HEAD)"
  vc="$(derive_version_code "$repo" HEAD)"
  check "untagged repo versionCode" "$vc" "1"

  # First tag: exact-match build.
  git -C "$repo" tag v0.1.0
  vn="$(derive_version_name "$repo" HEAD)"
  check "v0.1.0 exact versionName" "$vn" "0.1.0"
  vc="$(derive_version_code "$repo" HEAD)"
  check "v0.1.0 versionCode (1 tag + offset $VERSION_CODE_OFFSET)" "$vc" "$((1 + VERSION_CODE_OFFSET))"

  # Advance past the tag without a new tag: non-exact dev build.
  git -C "$repo" commit --quiet --allow-empty -m "c2"
  local sha
  sha="$(git -C "$repo" rev-parse --short HEAD)"
  vn="$(derive_version_name "$repo" HEAD)"
  check "post-tag dev versionName" "$vn" "0.1.0-1-g${sha}"
  vc="$(derive_version_code "$repo" HEAD)"
  check "post-tag dev versionCode (still 1 tag reachable)" "$vc" "$((1 + VERSION_CODE_OFFSET))"

  # Second tag: monotonicity — versionCode MUST strictly increase.
  git -C "$repo" tag v0.1.1
  vn="$(derive_version_name "$repo" HEAD)"
  check "v0.1.1 exact versionName" "$vn" "0.1.1"
  local vc2
  vc2="$(derive_version_code "$repo" HEAD)"
  check "v0.1.1 versionCode (2 tags + offset)" "$vc2" "$((2 + VERSION_CODE_OFFSET))"
  if [[ "$vc2" -le "$vc" ]]; then
    printf '  FAIL: versionCode did not strictly increase across tags (%s -> %s)\n' "$vc" "$vc2" >&2
    failures=$((failures + 1))
  else
    printf '  ok: versionCode strictly increased across tags (%s -> %s)\n' "$vc" "$vc2"
  fi

  # A minor/major bump tag (v0.2.0) must still just add 1 more to the count —
  # the derivation does not special-case the semver component that changed.
  git -C "$repo" commit --quiet --allow-empty -m "c3"
  git -C "$repo" tag v0.2.0
  local vc3
  vc3="$(derive_version_code "$repo" HEAD)"
  check "v0.2.0 versionCode (3 tags + offset)" "$vc3" "$((3 + VERSION_CODE_OFFSET))"

  # Non-`v*` tags must not be counted (a stray CI/local tag should not perturb
  # the release-count-derived versionCode).
  git -C "$repo" tag not-a-release-tag
  local vc4
  vc4="$(derive_version_code "$repo" HEAD)"
  check "stray non-v* tag ignored" "$vc4" "$vc3"

  # git binary unavailable: must fall back to the placeholder, never crash.
  # Exercised by pointing at a PATH with no git.
  local empty_path_dir
  empty_path_dir="$sandbox/no-git-path"
  mkdir -p "$empty_path_dir"
  local no_git_code no_git_name
  no_git_code="$(PATH="$empty_path_dir" bash -c "source '${BASH_SOURCE[0]}' 2>/dev/null; derive_version_code '$repo' HEAD" 2>/dev/null || echo "$VERSION_CODE_OFFSET")"
  check "no-git PATH versionCode fallback" "$no_git_code" "$VERSION_CODE_OFFSET"
  no_git_name="$(PATH="$empty_path_dir" bash -c "source '${BASH_SOURCE[0]}' 2>/dev/null; derive_version_name '$repo' HEAD" 2>/dev/null || echo "0.0.0-dev")"
  check "no-git PATH versionName fallback" "$no_git_name" "0.0.0-dev"

  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: version-code monotonic across tags, version-name matches convention, both fall back cleanly with no tags / no git.\n'
  return 0
}

main() {
  local cmd="${1:-both}"
  [[ "$cmd" == "--self-test" ]] && { run_self_test; exit $?; }
  [[ "$cmd" == "-h" || "$cmd" == "--help" ]] && { usage; exit 0; }
  case "$cmd" in
    version-code|version-name|both) shift || true ;;
    *) echo "unknown command: $cmd" >&2; usage >&2; exit 2 ;;
  esac

  local ref="HEAD"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --ref) ref="$2"; shift 2 ;;
      *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
  done

  case "$cmd" in
    version-code)
      derive_version_code "$ROOT_DIR" "$ref"
      ;;
    version-name)
      derive_version_name "$ROOT_DIR" "$ref"
      ;;
    both)
      printf 'VERSION_CODE=%s\n' "$(derive_version_code "$ROOT_DIR" "$ref")"
      printf 'VERSION_NAME=%s\n' "$(derive_version_name "$ROOT_DIR" "$ref")"
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
