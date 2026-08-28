#!/usr/bin/env bash
# Self-test for scripts/ci-nightly-rc-mark.sh (issue #2356).
#
# Uses a throwaway git repo with a real "remote" (a second bare repo on the
# same filesystem) so the force-tag + force-push behavior is exercised for
# real. Covers: first mark creates the tag; a SECOND mark on a NEWER commit
# force-moves it (proves the marker is a MOVING pointer, not append-only);
# the tag message carries SHA + run URL + a timestamp line; an unknown SHA
# fails loudly instead of silently no-op'ing; --dry-run makes no git-level
# change.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-nightly-rc-mark.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

git init --quiet --bare "$SANDBOX/remote.git"
git init --quiet -b main "$SANDBOX/repo"
git -C "$SANDBOX/repo" remote add origin "$SANDBOX/remote.git"
git -C "$SANDBOX/repo" -c user.email=t@t -c user.name=t commit --quiet --allow-empty -m "commit A"
sha_a="$(git -C "$SANDBOX/repo" rev-parse HEAD)"
git -C "$SANDBOX/repo" -c user.email=t@t -c user.name=t commit --quiet --allow-empty -m "commit B"
sha_b="$(git -C "$SANDBOX/repo" rev-parse HEAD)"
git -C "$SANDBOX/repo" push --quiet origin main

# --- unknown SHA fails loudly ---
if (cd "$SANDBOX/repo" && "$TARGET" --sha 0000000000000000000000000000000000dead --run-url "https://example/run/1" >/dev/null 2>&1); then
  fail "expected non-zero exit for an unknown SHA"
fi
pass "unknown SHA fails loudly"

# --- --dry-run makes no change ---
before_local_tags="$(git -C "$SANDBOX/repo" tag -l)"
(cd "$SANDBOX/repo" && "$TARGET" --sha "$sha_a" --run-url "https://example/run/1" --dry-run >/dev/null)
after_local_tags="$(git -C "$SANDBOX/repo" tag -l)"
[[ "$before_local_tags" == "$after_local_tags" ]] || fail "--dry-run created a local tag"
pass "--dry-run makes no local git change"

# --- first mark creates the tag, pointing at sha_a, with SHA/run/timestamp in the message ---
(cd "$SANDBOX/repo" && "$TARGET" --sha "$sha_a" --run-url "https://example/run/1" >/dev/null)
resolved="$(git -C "$SANDBOX/repo" rev-list -n1 validated-rc)"
[[ "$resolved" == "$sha_a" ]] || fail "validated-rc does not resolve to sha_a after first mark (got $resolved)"
message="$(git -C "$SANDBOX/repo" for-each-ref --format='%(contents)' refs/tags/validated-rc)"
[[ "$message" == *"SHA: $sha_a"* ]] || fail "tag message missing SHA line"
[[ "$message" == *"Run: https://example/run/1"* ]] || fail "tag message missing run URL line"
[[ "$message" == *"Recorded: "*"Z"* ]] || fail "tag message missing an ISO-8601 UTC timestamp line"
pass "first mark creates validated-rc at sha_a with SHA/run/timestamp"

# The tag must also have reached the remote, so a fresh clone sees it (the
# "discoverable by one command" property).
clone_dir="$SANDBOX/clone1"
git clone --quiet "$SANDBOX/remote.git" "$clone_dir"
git -C "$clone_dir" fetch --quiet --tags origin
clone_resolved="$(git -C "$clone_dir" rev-list -n1 validated-rc)"
[[ "$clone_resolved" == "$sha_a" ]] || fail "validated-rc was not pushed to origin (fresh clone doesn't see it)"
pass "validated-rc is pushed to origin and visible from a fresh clone"

# --- second mark on a NEWER commit force-moves the SAME tag name (moving pointer, not append-only) ---
(cd "$SANDBOX/repo" && "$TARGET" --sha "$sha_b" --run-url "https://example/run/2" >/dev/null)
resolved2="$(git -C "$SANDBOX/repo" rev-list -n1 validated-rc)"
[[ "$resolved2" == "$sha_b" ]] || fail "validated-rc did not move to sha_b on the second mark (got $resolved2)"
tag_count="$(git -C "$SANDBOX/repo" tag -l 'validated-rc' | wc -l | tr -d ' ')"
[[ "$tag_count" -eq 1 ]] || fail "expected exactly one validated-rc tag, found $tag_count"
pass "second mark force-moves validated-rc (one moving pointer, not two tags)"

echo "ALL $pass_count CHECKS PASSED"
