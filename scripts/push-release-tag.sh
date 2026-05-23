#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DRY_RUN=0
VISUAL_AUDIT_INSPECTED=0

usage() {
  cat <<'USAGE'
Usage: scripts/push-release-tag.sh [--dry-run] --visual-audit-inspected <tag> <validation-summary>

Pushes a release tag only after emulator-only validation has passed on the
current origin/main commit.

Example:
  scripts/release-emulator-validation.sh
  scripts/push-release-tag.sh --visual-audit-inspected v0.2.4 build/release-emulator-validation/20260523-120000/summary.md
USAGE
}

args=()
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
    --visual-audit-inspected)
      VISUAL_AUDIT_INSPECTED=1
      shift
      ;;
    *)
      args+=("$1")
      shift
      ;;
  esac
done

if [[ "${#args[@]}" -ne 2 ]]; then
  usage >&2
  exit 2
fi

TAG="${args[0]}"
SUMMARY_PATH="${args[1]}"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

[[ "$VISUAL_AUDIT_INSPECTED" -eq 1 ]] ||
  fail "pass --visual-audit-inspected after reviewing the visual-audit screenshots"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  fail "tag must look like vMAJOR.MINOR.PATCH"
[[ -f "$SUMMARY_PATH" ]] || fail "validation summary does not exist: $SUMMARY_PATH"

command -v git >/dev/null 2>&1 || fail "git was not found on PATH"

branch="$(git branch --show-current)"
[[ "$branch" == "main" ]] || fail "release tags must be pushed from main, not '$branch'"
git diff --quiet || fail "worktree has unstaged changes"
git diff --cached --quiet || fail "index has staged changes"
[[ -z "$(git ls-files --others --exclude-standard)" ]] ||
  fail "worktree has untracked files"

git fetch --quiet --tags origin main
local_sha="$(git rev-parse HEAD)"
origin_sha="$(git rev-parse origin/main)"
[[ "$local_sha" == "$origin_sha" ]] ||
  fail "HEAD ($local_sha) must match origin/main ($origin_sha)"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  fail "local tag already exists: $TAG"
fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  fail "remote tag already exists: $TAG"
fi

version_name="$(
  sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' app/build.gradle.kts |
    head -n 1
)"
expected_version="${TAG#v}"
[[ "$version_name" == "$expected_version" ]] ||
  fail "app/build.gradle.kts versionName '$version_name' must match $expected_version"

grep -Fxq "Commit SHA: $origin_sha" "$SUMMARY_PATH" ||
  fail "validation summary was not produced for origin/main commit $origin_sha"
grep -Fxq "Automated status: PASS" "$SUMMARY_PATH" ||
  fail "validation summary does not show automated emulator validation PASS"
grep -Eq '^Visual audit inspected: (no|yes)$' "$SUMMARY_PATH" ||
  fail "validation summary format is unexpected; rerun scripts/release-emulator-validation.sh"

tag_message="$(
  cat <<EOF
PocketShell $TAG

Emulator-only validation: $SUMMARY_PATH
Visual audit inspected: yes

Attach or link the validation artifact directories from the summary in the issue and release notes.
EOF
)"

if [[ "$DRY_RUN" -eq 1 ]]; then
  printf 'DRY RUN: would create annotated tag %s at %s and push it to origin\n' "$TAG" "$origin_sha"
  printf 'Validation summary: %s\n' "$SUMMARY_PATH"
  exit 0
fi

git tag -a "$TAG" "$origin_sha" -m "$tag_message"
git push origin "refs/tags/$TAG"

printf 'Pushed release tag %s at %s\n' "$TAG" "$origin_sha"
