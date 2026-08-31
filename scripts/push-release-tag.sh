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
commit being tagged. Run it from `main` after any release candidate has been
fast-forwarded there (docs/release.md). HEAD must equal `origin/main`.

Issue #2356 (Phase 4 of epic #2350): there is no version-bump commit any
more. app/build.gradle.kts and tools/pocketshell derive their version FROM
the tag being pushed (scripts/derive-version.sh), so this script creates the
tag LOCALLY first and verifies the derivation produces the expected
versionName AND a strictly-monotonic versionCode (versus the newest tag
already reachable from origin/main) before ever pushing it — a derivation
bug is caught here, not after the tag has already reached origin and
triggered the Build workflow.

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

DERIVE_SCRIPT="scripts/derive-version.sh"
[[ -f "$DERIVE_SCRIPT" ]] || fail "missing $DERIVE_SCRIPT"

branch="$(git branch --show-current)"
# A release tag is a publication boundary. The validated commit must already
# be the pushed main head, not merely a candidate that might later merge with a
# different history.
[[ "$branch" == "main" ]] ||
  fail "release tags must be pushed from main, not '$branch'"
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

grep -Fxq "Commit SHA: $origin_sha" "$SUMMARY_PATH" ||
  fail "validation summary was not produced for origin/main commit $origin_sha"
grep -Fxq "Automated status: PASS" "$SUMMARY_PATH" ||
  fail "validation summary does not show automated emulator validation PASS"
grep -Eq '^Visual audit inspected: (no|yes)$' "$SUMMARY_PATH" ||
  fail "validation summary format is unexpected; rerun scripts/release-emulator-validation.sh"

expected_version="${TAG#v}"

# The newest v* release tag already reachable from origin_sha, BEFORE this
# tag exists — the baseline the new tag's versionCode must strictly exceed.
previous_tag_name=""
previous_code=0
newest_semver="$(
  git tag --list 'v*' --merged "$origin_sha" 2>/dev/null |
    sed -n 's/^v\([0-9]\+\.[0-9]\+\.[0-9]\+\)$/\1/p' |
    sort -t. -k1,1n -k2,2n -k3,3n |
    tail -n 1
)"
if [[ -n "$newest_semver" ]]; then
  previous_tag_name="v$newest_semver"
  previous_code="$(bash "$DERIVE_SCRIPT" version-code --ref "$previous_tag_name" 2>/dev/null || echo 0)"
  [[ "$previous_code" =~ ^[0-9]+$ ]] || previous_code=0
fi

tag_message="$(
  cat <<EOF
PocketShell $TAG

Emulator-only validation: $SUMMARY_PATH
Visual audit inspected: yes

Attach or link the validation artifact directories from the summary in the issue and release notes.
EOF
)"

# Create the tag LOCALLY first (issue #2356): scripts/derive-version.sh's
# exact-match path needs the tag object to exist to derive against it, so
# the only way to verify the derivation for THIS tag before pushing is to
# create it, check, and delete again on any failure (or on --dry-run).
git tag -a "$TAG" "$origin_sha" -m "$tag_message"

cleanup_local_tag() {
  git tag -d "$TAG" >/dev/null 2>&1 || true
}

derived_name="$(bash "$DERIVE_SCRIPT" version-name --ref "$TAG" 2>/dev/null || true)"
if [[ "$derived_name" != "$expected_version" ]]; then
  cleanup_local_tag
  fail "scripts/derive-version.sh derived versionName '$derived_name' for tag $TAG, expected an EXACT match '$expected_version'"
fi

derived_code="$(bash "$DERIVE_SCRIPT" version-code --ref "$TAG" 2>/dev/null || true)"
if [[ -z "$derived_code" || ! "$derived_code" =~ ^[0-9]+$ ]]; then
  cleanup_local_tag
  fail "scripts/derive-version.sh could not derive a numeric versionCode for tag $TAG (got '$derived_code')"
fi
if [[ "$derived_code" -le "$previous_code" ]]; then
  cleanup_local_tag
  fail "derived versionCode $derived_code does not strictly exceed the previous release's $previous_code (previous tag: ${previous_tag_name:-<none>}) — versionCode MUST be monotonically increasing (see scripts/derive-version.sh)"
fi

printf 'Verified derivation: versionName=%s versionCode=%s (previous tag %s versionCode=%s)\n' \
  "$derived_name" "$derived_code" "${previous_tag_name:-<none>}" "$previous_code"

if [[ "$DRY_RUN" -eq 1 ]]; then
  printf 'DRY RUN: would push annotated tag %s (already created locally, now removing it) at %s\n' "$TAG" "$origin_sha"
  printf 'Validation summary: %s\n' "$SUMMARY_PATH"
  cleanup_local_tag
  exit 0
fi

git push origin "refs/tags/$TAG"

printf 'Pushed release tag %s at %s\n' "$TAG" "$origin_sha"
