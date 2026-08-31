#!/usr/bin/env bash
# The guard functions are extracted from the production script and eval'd
# here, so ShellCheck cannot see the indirect definitions/calls.
# shellcheck disable=SC2030,SC2031,SC2034,SC2317
set -uo pipefail

# ---------------------------------------------------------------------------
# Release-line branch guard (docs/release.md, issue #2430)
#
# WHAT THIS PINS
# --------------
# docs/release.md stabilizes a `release/vX.Y.Z` candidate in its own worktree,
# then fast-forwards that exact SHA onto main before publication. The two
# release scripts deliberately have different branch contracts:
#
#   scripts/release-emulator-validation.sh  (require_clean_pushed_main)
#   scripts/push-release-tag.sh             (main only; publication boundary)
#
# It proves that:
#
#   1. A `release/vX.Y.Z` branch is accepted for validation but rejected for
#      tagging even when it is clean and pushed.
#   2. `main` at `origin/main` is accepted by both scripts.
#   3. An arbitrary branch is rejected by both.
#   4. An unpushed main commit is rejected against `origin/main`.
#   5. The tag helper binds the summary's `Commit SHA:` to `origin/main`.
#
# HOW (no network, no emulator, no Gradle)
# ----------------------------------------
# A throwaway fixture repo with a local bare `origin`. push-release-tag.sh is
# copied in verbatim alongside its only dependency, scripts/derive-version.sh,
# and driven with --dry-run so no tag is ever pushed anywhere. The validation
# script's guard is EXTRACTED from the production file at run time and eval'd,
# so it cannot drift away from what the release actually runs.
# ---------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATION_SCRIPT="$ROOT_DIR/scripts/release-emulator-validation.sh"
TAG_SCRIPT="$ROOT_DIR/scripts/push-release-tag.sh"
DERIVE_SCRIPT="$ROOT_DIR/scripts/derive-version.sh"
RELEASE_OWNER_PROMPT="$ROOT_DIR/.claude/agents/release-owner.md"
RELEASE_DOC="$ROOT_DIR/docs/release.md"
ROOT_README="$ROOT_DIR/README.md"
DOC_INDEX="$ROOT_DIR/docs/README.md"
EVIDENCE_GUARD="$ROOT_DIR/scripts/run-release-evidence-guards.sh"

failures=0

pass() { printf 'PASS: %s\n' "$1"; }
fail_case() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

for f in \
  "$VALIDATION_SCRIPT" \
  "$TAG_SCRIPT" \
  "$DERIVE_SCRIPT" \
  "$RELEASE_OWNER_PROMPT" \
  "$RELEASE_DOC" \
  "$ROOT_README" \
  "$DOC_INDEX" \
  "$EVIDENCE_GUARD"; do
  [[ -f "$f" ]] || {
    printf 'FAIL: missing %s\n' "$f" >&2
    exit 1
  }
done

# The operator-facing summaries, canonical procedure, workflow wrapper, and
# release-owner prompt must all preserve the publication boundary. This is a
# deliberately structural check: executable branch rejection is insufficient
# if the discoverability path still tells an operator to tag the candidate.
release_order_contract_ok=true
grep -Fq 'candidate branch, stabilize, fast-forward the exact SHA to main, push main, tag from main' \
  "$ROOT_README" || release_order_contract_ok=false
grep -Fq 'cut candidate, stabilize, fast-forward the exact SHA to main, push main, and tag from main' \
  "$DOC_INDEX" || release_order_contract_ok=false
grep -Fq 'Validation may run on the pushed candidate, but publication must fast-forward that exact SHA to main, push main, then tag from main.' \
  "$EVIDENCE_GUARD" || release_order_contract_ok=false

# Backticks below are literal Markdown, not shell expansion.
# shellcheck disable=SC2016
doc_merge_line="$(grep -nF '### 4. Merge the candidate back to `main`' "$RELEASE_DOC" | cut -d: -f1)"
doc_tag_line="$(grep -nF '### 5. Tag the release' "$RELEASE_DOC" | cut -d: -f1)"
# shellcheck disable=SC2016
prompt_merge_line="$(grep -nF 'Fast-forward the validated candidate SHA to `main`' "$RELEASE_OWNER_PROMPT" | cut -d: -f1)"
# shellcheck disable=SC2016
prompt_tag_line="$(grep -nF 'Tag the pushed `origin/main` head' "$RELEASE_OWNER_PROMPT" | cut -d: -f1)"
if [[ -z "$doc_merge_line" || -z "$doc_tag_line" || "$doc_merge_line" -ge "$doc_tag_line" ||
      -z "$prompt_merge_line" || -z "$prompt_tag_line" || "$prompt_merge_line" -ge "$prompt_tag_line" ]]; then
  release_order_contract_ok=false
fi

if grep -Eq 'stabilize, tag, merge back|stabilize, tag, and merge back|cut, stabilize, tag, and merge back' \
  "$ROOT_README" "$DOC_INDEX" "$EVIDENCE_GUARD"; then
  release_order_contract_ok=false
fi

if [[ "$release_order_contract_ok" == true ]]; then
  pass 'release docs and owner prompt require merge and pushed main before tag'
else
  fail_case 'release docs or owner prompt regressed to candidate/tag-before-merge publication order'
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-release-branch-guard.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

git_q() { git "$@" >/dev/null 2>&1; }

# --- fixture repo ----------------------------------------------------------
ORIGIN="$WORK/origin.git"
REPO="$WORK/repo"
git_q init --bare -b main "$ORIGIN"
git_q init -b main "$REPO"
(
  cd "$REPO" || exit 1
  git config user.email release-guard-test@example.invalid
  git config user.name 'Release Guard Test'
  git config commit.gpgsign false
  git config tag.gpgsign false
  mkdir -p scripts
  cp "$TAG_SCRIPT" "$DERIVE_SCRIPT" scripts/
  chmod +x scripts/push-release-tag.sh scripts/derive-version.sh
  printf 'fixture\n' > README.md
  git add -A
  git commit -qm 'fixture: release-line guard'
  git remote add origin "$ORIGIN"
  git push -q -u origin main
  # A prior release tag, so push-release-tag.sh's strict-monotonic
  # versionCode check has a real baseline to beat.
  git tag -a v0.1.0 -m 'fixture v0.1.0'
  git push -q origin refs/tags/v0.1.0
  # Move main past the tag, so tagging v0.1.1 on main's head is an
  # unambiguous NEW version rather than a second name for v0.1.0.
  printf 'post-release main work\n' > MAIN_WORK.md
  git add -A
  git commit -qm 'main keeps moving'
  git push -q origin main

  git checkout -q -b release/v0.1.1
  printf 'candidate stabilizing fix\n' > STABILIZE.md
  git add -A
  git commit -qm 'stabilize the candidate'
  git push -q -u origin release/v0.1.1

  git checkout -q -b feature/x main
  git push -q -u origin feature/x
  git checkout -q release/v0.1.1
) || {
  printf 'FAIL: could not build the fixture repo\n' >&2
  exit 1
}

MAIN_SHA="$(git -C "$REPO" rev-parse main)"
CANDIDATE_SHA="$(git -C "$REPO" rev-parse release/v0.1.1)"
[[ "$MAIN_SHA" != "$CANDIDATE_SHA" ]] || {
  printf 'FAIL: fixture is degenerate — candidate and main are the same SHA\n' >&2
  exit 1
}

write_summary() {
  # $1 = destination, $2 = Commit SHA to claim
  cat > "$1" <<EOF
# PocketShell Release Emulator Validation

Generated: 2026-08-28T00:00:00+00:00
Commit SHA: $2
Branch: release/v0.1.1
Automated status: PASS
Visual audit inspected: no
EOF
}

SUMMARY_CANDIDATE="$WORK/summary-candidate.md"
SUMMARY_MAIN="$WORK/summary-main.md"
write_summary "$SUMMARY_CANDIDATE" "$CANDIDATE_SHA"
write_summary "$SUMMARY_MAIN" "$MAIN_SHA"

run_tag_helper() {
  # $1 = branch to run from, $2 = summary path, $3 = tag
  git -C "$REPO" checkout -q "$1"
  (
    cd "$REPO" || exit 1
    bash scripts/push-release-tag.sh --dry-run --visual-audit-inspected "$3" "$2" 2>&1
  )
}

expect_ok() {
  local name="$1" out="$2" rc="$3"
  if [[ "$rc" -eq 0 ]]; then
    pass "$name"
  else
    fail_case "$name (exit $rc): $out"
  fi
}

expect_rejected() {
  local name="$1" out="$2" rc="$3" needle="$4"
  if [[ "$rc" -eq 0 ]]; then
    fail_case "$name — expected a rejection, got success: $out"
  elif [[ "$out" != *"$needle"* ]]; then
    fail_case "$name — rejected, but not for the expected reason ('$needle'): $out"
  else
    pass "$name"
  fi
}

# --- push-release-tag.sh ---------------------------------------------------

# 1. Candidate branches may be validated but never published directly.
out="$(run_tag_helper release/v0.1.1 "$SUMMARY_CANDIDATE" v0.1.1)"
rc=$?
expect_rejected 'push-release-tag.sh rejects a release/vX.Y.Z candidate' \
  "$out" "$rc" "release tags must be pushed from main, not 'release/v0.1.1'"
# --dry-run must leave no tag behind, locally or on the remote.
if git -C "$REPO" rev-parse -q --verify refs/tags/v0.1.1 >/dev/null 2>&1; then
  fail_case 'push-release-tag.sh --dry-run left a local tag behind'
fi
if git -C "$REPO" ls-remote --exit-code --tags origin refs/tags/v0.1.1 >/dev/null 2>&1; then
  fail_case 'push-release-tag.sh --dry-run pushed a tag'
fi

# 2. No regression: main is still a valid release line.
out="$(run_tag_helper main "$SUMMARY_MAIN" v0.1.1)"
rc=$?
expect_ok 'push-release-tag.sh still tags from main at its pushed head' "$out" "$rc"

# 3. Arbitrary branches are rejected too.
out="$(run_tag_helper feature/x "$SUMMARY_MAIN" v0.1.1)"
rc=$?
expect_rejected 'push-release-tag.sh rejects an arbitrary branch' "$out" "$rc" \
  "release tags must be pushed from main, not 'feature/x'"

# 4. Evidence must be for origin/main, not for a candidate commit.
out="$(run_tag_helper main "$SUMMARY_CANDIDATE" v0.1.1)"
rc=$?
expect_rejected 'push-release-tag.sh refuses a summary produced for a different commit' \
  "$out" "$rc" 'validation summary was not produced for'

# 5. The pushed-head check binds exclusively to origin/main.
git -C "$REPO" checkout -q main
printf 'unpushed\n' > "$REPO/UNPUSHED.md"
git -C "$REPO" add -A
git -C "$REPO" commit -qm 'unpushed main commit'
UNPUSHED_SHA="$(git -C "$REPO" rev-parse HEAD)"
write_summary "$WORK/summary-unpushed.md" "$UNPUSHED_SHA"
out="$(run_tag_helper main "$WORK/summary-unpushed.md" v0.1.1)"
rc=$?
expect_rejected 'push-release-tag.sh refuses an unpushed main commit' "$out" "$rc" \
  "must match origin/main"
git -C "$REPO" reset -q --hard "$MAIN_SHA"

# --- release-emulator-validation.sh: require_clean_pushed_main --------------
# Extracted from the production file, never transcribed.
guard_src="$(sed -n '/^fail() {$/,/^}$/p' "$VALIDATION_SCRIPT")"
guard_src+=$'\n'"$(sed -n '/^require_clean_pushed_main() {$/,/^}$/p' "$VALIDATION_SCRIPT")"
if [[ "$guard_src" != *'require_clean_pushed_main() {'* || "$guard_src" != *'fail() {'* ]]; then
  printf 'FAIL: could not extract the guard from %s (did it get renamed?)\n' \
    "$VALIDATION_SCRIPT" >&2
  exit 1
fi

run_validation_guard() {
  # $1 = branch to run the guard from
  git -C "$REPO" checkout -q "$1"
  (
    cd "$REPO" || exit 1
    SUMMARY_PATH="$WORK/unused-summary.md"
    export SUMMARY_PATH
    eval "$guard_src"
    require_clean_pushed_main
  ) 2>&1
}

out="$(run_validation_guard release/v0.1.1)"
rc=$?
expect_ok 'release validation guard accepts a release/vX.Y.Z candidate at its pushed head' "$out" "$rc"

out="$(run_validation_guard main)"
rc=$?
expect_ok 'release validation guard still accepts main at its pushed head' "$out" "$rc"

out="$(run_validation_guard feature/x)"
rc=$?
expect_rejected 'release validation guard rejects an arbitrary branch' "$out" "$rc" \
  'release validation must run from main or a release/vX.Y.Z candidate branch'

git -C "$REPO" checkout -q release/v0.1.1
printf 'unpushed again\n' > "$REPO/UNPUSHED2.md"
git -C "$REPO" add -A
git -C "$REPO" commit -qm 'second unpushed candidate commit'
out="$(run_validation_guard release/v0.1.1)"
rc=$?
expect_rejected 'release validation guard refuses an unpushed candidate commit' "$out" "$rc" \
  'must match origin/release/v0.1.1'
git -C "$REPO" reset -q --hard "$CANDIDATE_SHA"

# A dirty candidate worktree is still refused (the widened branch check must
# not have swallowed the cleanliness checks).
printf 'dirty\n' >> "$REPO/README.md"
out="$(run_validation_guard release/v0.1.1)"
rc=$?
expect_rejected 'release validation guard refuses a dirty candidate worktree' "$out" "$rc" \
  'worktree has unstaged changes'
git -C "$REPO" checkout -q -- README.md

if [[ "$failures" -ne 0 ]]; then
  printf 'FAIL: release-line branch guard — %d case(s) failed\n' "$failures" >&2
  exit 1
fi

printf 'PASS: release-line branch guard (docs/release.md worktree flow)\n'
