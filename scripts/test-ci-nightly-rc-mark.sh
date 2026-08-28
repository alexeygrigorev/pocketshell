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

# --- issue #2374: the marker must be creatable with NO ambient git identity ---
#
# THE GAP THIS CLOSES. Every case above inherits the running user's
# `~/.gitconfig`, so `git tag -a` always had a tagger. A GitHub hosted runner
# does not: actions/checkout sets no `user.name`/`user.email` and git rejects its
# own auto-detected `runner@host.(none)`. `record-validated-rc` would therefore
# have failed on every nightly run — and #2356's marker has in fact never been
# created once. This case runs the real script in an environment where git can
# form no identity at all.
#
# The precondition is PROVEN LIVE first: a bare `git tag -a` in the same
# environment must fail. Without that, a host whose git CAN auto-detect an
# identity would make this case pass vacuously, which is exactly how the gap
# survived in the first place.
noident_home="$SANDBOX/noident-home"
mkdir -p "$noident_home"
# GIT_CONFIG_NOSYSTEM drops /etc/gitconfig; the empty HOME drops ~/.gitconfig;
# the empty COMMITTER vars defeat auto-detection on ANY host, so the failure is
# reproducible everywhere rather than only where the hostname has no domain.
noident_env=(
  env -i
  "PATH=$PATH"
  "HOME=$noident_home"
  GIT_CONFIG_NOSYSTEM=1
  GIT_COMMITTER_NAME=
  GIT_COMMITTER_EMAIL=
)
if (cd "$SANDBOX/repo" && "${noident_env[@]}" git tag -f -a ident-probe "$sha_a" -m probe) >/dev/null 2>&1; then
  git -C "$SANDBOX/repo" tag -d ident-probe >/dev/null 2>&1 || true
  fail "the no-identity precondition is not reproducible on this host — a bare 'git tag -a' succeeded, so the #2374 case below would pass vacuously"
fi
pass "#2374 precondition is live: a bare annotated 'git tag -a' fails with no git identity"

git -C "$SANDBOX/repo" tag -d validated-rc >/dev/null 2>&1 || true
git -C "$SANDBOX/repo" push --quiet --delete origin validated-rc >/dev/null 2>&1 || true
noident_log="$SANDBOX/noident.log"
if ! (cd "$SANDBOX/repo" && "${noident_env[@]}" bash "$TARGET" \
        --sha "$sha_a" --run-url "https://example/run/3") > "$noident_log" 2>&1; then
  cat "$noident_log"
  fail "#2374: ci-nightly-rc-mark.sh cannot record the marker without an ambient git identity — this is what blocks record-validated-rc on every hosted runner"
fi
noident_resolved="$(git -C "$SANDBOX/repo" rev-list -n1 validated-rc)"
[[ "$noident_resolved" == "$sha_a" ]] \
  || fail "#2374: validated-rc does not resolve to the requested SHA after an identity-less run (got $noident_resolved)"
noident_clone="$SANDBOX/clone-noident"
git clone --quiet "$SANDBOX/remote.git" "$noident_clone"
git -C "$noident_clone" fetch --quiet --tags origin
[[ "$(git -C "$noident_clone" rev-list -n1 validated-rc)" == "$sha_a" ]] \
  || fail "#2374: the identity-less run did not push validated-rc to origin"
pass "#2374 the marker is created and pushed with no ambient git identity (the hosted-runner case)"

# The fallback must be NARROW: a caller that HAS an identity keeps it, so a
# local `--dry-run`/manual mark is still attributable to the person who ran it.
git -C "$SANDBOX/repo" tag -d validated-rc >/dev/null 2>&1 || true
git -C "$SANDBOX/repo" push --quiet --delete origin validated-rc >/dev/null 2>&1 || true
( cd "$SANDBOX/repo" && GIT_COMMITTER_NAME="Real Person" \
    GIT_COMMITTER_EMAIL="real@example.com" \
    bash "$TARGET" --sha "$sha_b" --run-url "https://example/run/4" >/dev/null ) \
  || fail "#2374: the script failed for a caller that DOES have an identity"
tagger="$(git -C "$SANDBOX/repo" for-each-ref --format='%(taggeremail)' refs/tags/validated-rc)"
[[ "$tagger" == "<real@example.com>" ]] \
  || fail "#2374: the CI fallback overrode a caller's real identity (tagger $tagger); it must only apply when git has none"
pass "#2374 a caller with a real git identity keeps it — the fallback is narrow"

echo "ALL $pass_count CHECKS PASSED"
