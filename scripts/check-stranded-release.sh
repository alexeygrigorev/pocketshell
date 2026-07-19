#!/usr/bin/env bash
set -euo pipefail

# Stranded-release guard for issue #1669.
#
# The app `versionName` (app/build.gradle.kts) leads the newest pushed release
# tag briefly and legitimately during a release: the bump PR merges, then the
# tag is pushed. That in-flight lead is BOUNDED — at most one un-tagged version
# ahead of the newest origin tag. When it exceeds that bound, a version was
# BUMPED BUT NEVER TAGGED: the release was stranded.
#
# That is exactly what happened to v0.4.38 — the versionName moved on to the next
# value while v0.4.38 was never tagged, so the maintainer had to `git ls-remote`
# to discover the phone's build did not match any release. This guard turns that
# silent miss RED at push time.
#
# The "bounded interval" is realized as a bounded VERSION LEAD (default 1): the
# versionName may be at most one release ahead of the newest origin tag. Two or
# more ahead on the patch ladder means a version in between was skipped without a
# tag — a stranded release. It is a version-lead bound (not a wall-clock timer)
# so it is deterministic, needs only `git ls-remote --tags`, and works in a
# shallow CI checkout.
#
# Usage:
#   scripts/check-stranded-release.sh            # check the real tree + origin
#   scripts/check-stranded-release.sh --self-test
#                                                # in-process red->green proof on
#                                                # synthetic fixtures; exit 0 only
#                                                # if every case behaves correctly

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

GRADLE_REL="app/build.gradle.kts"

# Max releases the versionName may lead the newest tag by before it is stranded.
MAX_VERSION_LEAD="${MAX_VERSION_LEAD:-1}"

usage() {
  cat <<'USAGE'
Usage: scripts/check-stranded-release.sh [--self-test]

Fails when the app versionName (app/build.gradle.kts) is ahead of the newest
origin release tag by more than MAX_VERSION_LEAD (default 1) releases — the
"bumped but never tagged" miss that stranded v0.4.38. Exits 0 when the lead is
within bound (or the versionName is already tagged), non-zero when stranded.

--self-test runs a synthetic red->green proof over fixed (versionName, tags)
pairs and exits 0 only if every case yields the expected verdict.
USAGE
}

# Extract the app `versionName` from an app/build.gradle.kts file.
parse_version_name() {
  local file="$1"
  sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*$/\1/p' "$file" |
    head -n 1
}

# Print the newest semver among the tags fed on stdin (one `vX.Y.Z` per line).
# Ignores anything that is not a plain vMAJOR.MINOR.PATCH tag.
newest_semver_tag() {
  grep -oE '^v?[0-9]+\.[0-9]+\.[0-9]+$' |
    sed 's/^v//' |
    sort -t. -k1,1n -k2,2n -k3,3n |
    tail -n 1
}

# List origin release tags, newest last. Falls back to local tags when the
# remote is unreachable (offline dev box); the CI job always has the remote.
origin_release_tags() {
  local remote_tags
  if remote_tags="$(git -C "$ROOT_DIR" ls-remote --tags origin 2>/dev/null)"; then
    printf '%s\n' "$remote_tags" | grep -oE 'refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$' | sed 's#refs/tags/##'
  fi
  # Always also emit local tags so an offline run still has evidence; the caller
  # takes the max across both.
  git -C "$ROOT_DIR" tag --list 'v*' 2>/dev/null || true
}

# Compute how many releases $1 (versionName) leads $2 (newest tag) by, on the
# release ladder. Echoes an integer lead:
#   0  -> versionName == newest tag, or versionName is behind (already released)
#   1  -> exactly the next patch/minor/major (the legitimate in-flight window)
#   >1 -> a version in between was skipped -> STRANDED
version_lead() {
  local v="$1" t="$2"
  local vM vm vp tM tm tp
  IFS=. read -r vM vm vp <<<"$v"
  IFS=. read -r tM tm tp <<<"$t"

  # versionName strictly less than the tag (behind) -> already released, lead 0.
  if [[ "$vM" -lt "$tM" ]] ||
    { [[ "$vM" -eq "$tM" ]] && [[ "$vm" -lt "$tm" ]]; } ||
    { [[ "$vM" -eq "$tM" ]] && [[ "$vm" -eq "$tm" ]] && [[ "$vp" -le "$tp" ]]; }; then
    echo 0
    return
  fi

  # Same major.minor: lead is the patch delta (…37 tag, …39 version -> lead 2).
  if [[ "$vM" -eq "$tM" ]] && [[ "$vm" -eq "$tm" ]]; then
    echo $((vp - tp))
    return
  fi

  # One minor ahead, patch reset to 0 -> the single legitimate minor bump.
  if [[ "$vM" -eq "$tM" ]] && [[ "$vm" -eq $((tm + 1)) ]] && [[ "$vp" -eq 0 ]]; then
    echo 1
    return
  fi

  # One major ahead, minor+patch reset -> the single legitimate major bump.
  if [[ "$vM" -eq $((tM + 1)) ]] && [[ "$vm" -eq 0 ]] && [[ "$vp" -eq 0 ]]; then
    echo 1
    return
  fi

  # Any other forward jump skipped a release line -> treat as stranded (>bound).
  echo $((MAX_VERSION_LEAD + 1))
}

# Core check. Args: <versionName> <newest-tag>. Exits 0 within bound, 1 stranded.
check_lead() {
  local version_name="$1" newest_tag="$2"

  if [[ -z "$version_name" ]]; then
    printf 'FAIL: could not parse versionName.\n' >&2
    return 1
  fi
  if [[ -z "$newest_tag" ]]; then
    printf 'WARN: no origin release tags found; skipping stranded-release check.\n' >&2
    return 0
  fi

  printf 'app versionName:        %s\n' "$version_name"
  printf 'newest origin tag:      v%s\n' "$newest_tag"

  local lead
  lead="$(version_lead "$version_name" "$newest_tag")"
  printf 'release lead:           %s (bound %s)\n' "$lead" "$MAX_VERSION_LEAD"

  if [[ "$lead" -gt "$MAX_VERSION_LEAD" ]]; then
    printf 'FAIL: versionName %s leads newest tag v%s by %s releases (bound %s).\n' \
      "$version_name" "$newest_tag" "$lead" "$MAX_VERSION_LEAD" >&2
    printf '      A version between them was bumped but NEVER tagged — a stranded\n' >&2
    printf '      release (this is the v0.4.38 miss #1669 guards). Tag the missing\n' >&2
    printf '      release(s), or reset the bump, so the field log names a real build.\n' >&2
    return 1
  fi

  printf 'OK: versionName is within the bounded release lead of the newest tag.\n'
  return 0
}

run_self_test() {
  local failures=0

  # (versionName, newest-tag, expected-exit) triples. 0=pass, 1=stranded.
  local cases=(
    "0.4.39|0.4.38|0"   # normal in-flight: one patch ahead
    "0.4.38|0.4.38|0"   # already tagged
    "0.4.37|0.4.38|0"   # behind (hotfix branch): not this guard's concern
    "0.4.39|0.4.37|1"   # STRANDED: 0.4.38 skipped without a tag
    "0.5.0|0.4.38|0"    # legitimate minor bump
    "0.5.2|0.4.38|1"    # STRANDED: 0.5.0/0.5.1 skipped
    "1.0.0|0.4.38|0"    # legitimate major bump
    "0.4.41|0.4.38|1"   # STRANDED: several patches skipped
  )

  for triple in "${cases[@]}"; do
    IFS='|' read -r v t expected <<<"$triple"
    local actual=0
    check_lead "$v" "$t" >/dev/null 2>&1 || actual=1
    if [[ "$actual" -eq "$expected" ]]; then
      printf '  ok: (%s vs v%s) -> %s\n' "$v" "$t" "$([[ $actual -eq 0 ]] && echo PASS || echo STRANDED)"
    else
      printf '  FAIL: (%s vs v%s) expected %s got %s\n' "$v" "$t" "$expected" "$actual" >&2
      failures=$((failures + 1))
    fi
  done

  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: in-flight leads pass, stranded leads fail.\n'
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
      local version_name newest_tag
      version_name="$(parse_version_name "$ROOT_DIR/$GRADLE_REL")"
      newest_tag="$(origin_release_tags | newest_semver_tag)"
      check_lead "$version_name" "$newest_tag"
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
