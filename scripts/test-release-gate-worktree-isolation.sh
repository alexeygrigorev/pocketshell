#!/usr/bin/env bash
# The rsync exclude list is extracted from production and replayed here, so
# ShellCheck cannot see the indirect array use.
# shellcheck disable=SC2034,SC2317
set -uo pipefail

# ---------------------------------------------------------------------------
# The pre-release gate's isolated copy must never carry git into itself
#
# WHY THIS EXISTS
# ---------------
# `scripts/pre-release-confidence-gate.sh` rsyncs the checkout into
# `<run>/worktree/` and re-execs there, so a 40-minute build cannot be
# perturbed by edits to the source tree. That isolation assumed `.git` is a
# DIRECTORY and excluded it as `--exclude='.git/'`.
#
# docs/release.md cuts every release on a `release/vX.Y.Z` branch in a git
# WORKTREE, and in a worktree `.git` is a FILE containing
# `gitdir: /…/.git/worktrees/<name>`. A trailing-slash rsync exclude matches
# directories only, so that file was copied straight through and the "isolated"
# copy became a live second checkout of the branch being released:
#
#   * any `git` command the gate ran inside the copy reached the SOURCE
#     worktree's index/HEAD — the exact coupling the exclude exists to stop;
#   * `scripts/derive-version.sh` succeeded there and produced a real
#     tag-derived version (e.g. `0.4.44-185-gd8e83c21`) while the Docker
#     `agents` fixture still reports its baked `0.0.0-dev`, hard-failing the
#     gate's `docker-agents-pocketshell-version` step — a release blocker that
#     appears ONLY when the gate runs from a worktree, which is now the only
#     supported way to cut a release.
#
# WHAT THIS PINS
# --------------
# The exclude list is read OUT OF the production script (not transcribed) and
# replayed against a fixture tree that contains a `.git` file, a `.git`
# directory, `.gradle/`, `build/`, and ordinary content. The copy must contain
# the ordinary content and none of the excluded shapes. A revert to
# `--exclude='.git/'` reddens the `.git`-file case.
# ---------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE_SCRIPT="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"

failures=0
pass() { printf 'PASS: %s\n' "$1"; }
fail_case() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

[[ -f "$GATE_SCRIPT" ]] || {
  printf 'FAIL: missing %s\n' "$GATE_SCRIPT" >&2
  exit 1
}
command -v rsync >/dev/null 2>&1 || {
  printf 'FAIL: rsync is required to drive this harness\n' >&2
  exit 1
}

# Extract the excludes from the one rsync that builds the isolated copy.
mapfile -t EXCLUDES < <(
  awk '
    /^  rsync -a --delete \\$/ { inblock = 1; next }
    inblock && /--exclude=/ {
      line = $0
      sub(/^[[:space:]]*--exclude=./, "", line)
      sub(/.[[:space:]]*\\?[[:space:]]*$/, "", line)
      print line
      next
    }
    inblock { exit }
  ' "$GATE_SCRIPT"
)

if [[ "${#EXCLUDES[@]}" -eq 0 ]]; then
  printf 'FAIL: could not extract the isolated-copy rsync excludes from %s\n' "$GATE_SCRIPT" >&2
  exit 1
fi
printf 'Extracted isolated-copy rsync excludes: %s\n' "${EXCLUDES[*]}"

for required in .git .gradle build; do
  found=0
  for e in "${EXCLUDES[@]}"; do
    [[ "${e%/}" == "$required" ]] && found=1
  done
  [[ "$found" -eq 1 ]] ||
    fail_case "the isolated-copy rsync no longer excludes '$required' at all"
done

# The load-bearing assertion: `.git` must be excluded WITHOUT a trailing
# slash, or the worktree case leaks. Asserted on behaviour below too, but
# named here so a failure reads as the cause rather than a stray file.
git_exclude=""
for e in "${EXCLUDES[@]}"; do
  [[ "${e%/}" == ".git" ]] && git_exclude="$e"
done
if [[ "$git_exclude" == ".git" ]]; then
  pass "the isolated-copy rsync excludes '.git' as both a file and a directory"
else
  fail_case "the isolated-copy rsync excludes '$git_exclude' — a trailing slash matches DIRECTORIES only, so a worktree's .git FILE is copied into the 'isolated' tree"
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-gate-isolation.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# --- case 1: source is a git WORKTREE (.git is a file) ---------------------
SRC_WT="$WORK/src-worktree"
mkdir -p "$SRC_WT/scripts" "$SRC_WT/app/src" "$SRC_WT/.gradle" "$SRC_WT/build"
printf 'gitdir: /somewhere/.git/worktrees/release-v0.0.0\n' > "$SRC_WT/.git"
printf 'real source\n' > "$SRC_WT/app/src/Main.kt"
printf 'stale gradle state\n' > "$SRC_WT/.gradle/state"
printf 'stale build output\n' > "$SRC_WT/build/output"

# --- case 2: source is a plain checkout (.git is a directory) --------------
SRC_PLAIN="$WORK/src-plain"
mkdir -p "$SRC_PLAIN/app/src" "$SRC_PLAIN/.git/objects" "$SRC_PLAIN/.gradle" "$SRC_PLAIN/build"
printf 'ref: refs/heads/main\n' > "$SRC_PLAIN/.git/HEAD"
printf 'real source\n' > "$SRC_PLAIN/app/src/Main.kt"
printf 'stale gradle state\n' > "$SRC_PLAIN/.gradle/state"
printf 'stale build output\n' > "$SRC_PLAIN/build/output"

copy_with_production_excludes() {
  local src="$1" dst="$2" args=() e
  for e in "${EXCLUDES[@]}"; do args+=("--exclude=$e"); done
  rsync -a --delete "${args[@]}" "$src/" "$dst/"
}

assert_isolated() {
  local label="$1" dst="$2"
  if [[ ! -f "$dst/app/src/Main.kt" ]]; then
    fail_case "$label: the copy is missing real source — the excludes are too broad"
    return
  fi
  if [[ -e "$dst/.git" ]]; then
    fail_case "$label: .git reached the isolated copy ($(
      [[ -d "$dst/.git" ]] && printf 'directory' || printf 'file'
    )) — the copy is not isolated from the source repository"
    return
  fi
  if [[ -e "$dst/.gradle" ]]; then
    fail_case "$label: .gradle reached the isolated copy"
    return
  fi
  if [[ -e "$dst/build" ]]; then
    fail_case "$label: build/ reached the isolated copy"
    return
  fi
  pass "$label: isolated copy carries source and none of .git/.gradle/build"
}

copy_with_production_excludes "$SRC_WT" "$WORK/dst-worktree"
assert_isolated "git worktree source (.git is a FILE)" "$WORK/dst-worktree"

copy_with_production_excludes "$SRC_PLAIN" "$WORK/dst-plain"
assert_isolated "plain checkout source (.git is a DIRECTORY)" "$WORK/dst-plain"

# --- the harness must itself be able to go red ------------------------------
# Replay the OLD, directory-only pattern and prove it lets the worktree case
# through; otherwise a future rsync/behaviour change could make every
# assertion above vacuous.
rm -rf "$WORK/dst-selftest"
rsync -a --delete --exclude='.git/' --exclude='.gradle/' --exclude='build/' \
  "$SRC_WT/" "$WORK/dst-selftest/"
if [[ -f "$WORK/dst-selftest/.git" ]]; then
  pass "self-test: the old --exclude='.git/' pattern demonstrably leaks a worktree's .git file"
else
  fail_case "self-test: the old --exclude='.git/' pattern did NOT leak — this harness can no longer detect the regression it exists for"
fi

if [[ "$failures" -ne 0 ]]; then
  printf 'FAIL: pre-release gate isolated-copy isolation — %d case(s) failed\n' "$failures" >&2
  exit 1
fi

printf 'PASS: pre-release gate isolated copy is git-free for both worktree and plain checkouts\n'
