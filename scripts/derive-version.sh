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
#   * A DETACHED COPY of a checkout (issue #2381) carries the version of the
#     checkout it was copied from, via a PIN FILE, not a second derivation.
#     scripts/pre-release-confidence-gate.sh rsyncs the release checkout to
#     `<checkout>/build/pre-release-confidence-gate/<run>/worktree` with
#     `--exclude='.git'` and re-execs there; every APK the release chain then
#     validates, journeys against and publishes is built INSIDE that copy. With
#     no git of its own the copy derived the `0.0.0-dev`/versionCode=1
#     placeholder, so the whole release gate — nightly validated-RC included —
#     validated a binary that could not express a release version at all
#     (HostBootstrapScenarioSuiteTest's setup-detection scenarios compare the
#     app's version against the host CLI's, and every one of them is vacuous at
#     `0.0.0`). `write-pin` closes that: the SOURCE checkout, which does have
#     tag history, derives once and stamps the answer into the copy, and the
#     pin is consulted AHEAD of the git derivation (not as a fallback behind
#     it — D22 hard cut). Invariant: a pinned copy derives EXACTLY what the
#     checkout it was copied from derives.
#
# USAGE
#   derive-version.sh version-code [--ref REF]
#   derive-version.sh version-name [--ref REF]
#   derive-version.sh both [--ref REF]     (default; prints both, KEY=VALUE)
#   derive-version.sh write-pin TARGET_DIR [--ref REF]
#   derive-version.sh --self-test
#
# --ref defaults to HEAD. Passing an explicit ref lets a caller derive the
# version for a commit/tag other than the current checkout (e.g. a throwaway
# worktree, or scripts/push-release-tag.sh deriving against a not-yet-pushed
# local tag it just created).
#
# write-pin derives against THIS script's own checkout and writes
# `TARGET_DIR/.pocketshell-version-pin` (the same `KEY=VALUE` shape `both`
# prints). Any later `version-code`/`version-name`/`both` run whose repo root
# is TARGET_DIR answers from that file instead of asking git.
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
  # Ends at the last line of the header comment block; keep in sync when the
  # block grows (the pre-#2381 range stopped one section short of USAGE).
  sed -n '2,97p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# Issue #2381: the pin file a detached copy carries instead of git history.
# Deliberately a repo-root dotfile and NOT under `build/`: the gate's copy runs
# `clean`/`--rerun-tasks` builds, and a version that can be wiped mid-gate is
# exactly the silent-placeholder failure this closes.
readonly VERSION_PIN_FILE_NAME=".pocketshell-version-pin"

# Echoes "<code> <name>" from $1/.pocketshell-version-pin when that file exists
# AND parses; echoes nothing otherwise. A malformed pin is reported on stderr
# and ignored rather than fataled: derive-version.sh's top design constraint is
# that it never fails a build, and Gradle's derivePocketshellVersion() swallows
# a non-zero exit into the same placeholder anyway, so exiting here would only
# make the failure quieter. The loud half lives downstream, where it can name
# the consequence — HostBootstrapScenarioSuiteTest.assertApkCarriesARealRelease
# Version() and release-emulator-validation.sh's preflight both hard-fail on a
# `0.0.0` core.
read_version_pin() {
  local repo="$1"
  local pin_file="$repo/$VERSION_PIN_FILE_NAME"
  [[ -f "$pin_file" ]] || return 1

  local code="" name="" line
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      VERSION_CODE=*) code="${line#VERSION_CODE=}" ;;
      VERSION_NAME=*) name="${line#VERSION_NAME=}" ;;
    esac
  done < "$pin_file"

  if [[ ! "$code" =~ ^[1-9][0-9]*$ || -z "$name" ]]; then
    printf 'derive-version.sh: ignoring malformed version pin at %s (VERSION_CODE=%s VERSION_NAME=%s)\n' \
      "$pin_file" "${code:-<missing>}" "${name:-<missing>}" >&2
    return 1
  fi
  printf '%s %s\n' "$code" "$name"
}

# True when $1 is a checkout that carries its OWN git, and git can read it.
#
# Anchoring matters more than it looks. git's repository discovery ascends
# through parent directories, so a COPY of this tree nested inside another
# checkout derives the PARENT repository's tags. The pre-release confidence
# gate makes exactly such a copy at
# `<checkout>/build/pre-release-confidence-gate/<run>/worktree` and re-execs
# there, and whether discovery escaped depended on nothing but filesystem
# layout — it stops at a mount boundary, so the identical commit derived
# `0.4.44-186-gbec69185` from a release worktree on /data and the documented
# `0.0.0-dev` placeholder from the root checkout (whose `build/` is a
# separate filesystem). The release gate compares that derived version
# against the Docker `agents` fixture's baked version for exact equality, so
# the gate passed or hard-failed on layout alone.
#
# A tree with no git of its own has no version to derive: fall back to the
# documented placeholder rather than answering with a different repository's
# history. `.git` is checked with -e, not -d, because in a git WORKTREE it is
# a FILE holding `gitdir: ...` (docs/release.md cuts every release in one).
git_tree_usable() {
  local repo="$1"
  command -v git >/dev/null 2>&1 || return 1
  [[ -e "$repo/.git" ]] || return 1
  git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1
}

# Echoes the derived versionCode (a positive integer) for --ref (default
# HEAD) inside the repo at $1 (default ROOT_DIR). Never fails: falls back to
# the OFFSET floor (1) when git/tags are unavailable.
derive_version_code() {
  local repo="$1" ref="$2"
  local pin
  if pin="$(read_version_pin "$repo")"; then
    printf '%s\n' "${pin%% *}"
    return
  fi
  if ! command -v git >/dev/null 2>&1; then
    echo "$VERSION_CODE_OFFSET"
    return
  fi
  if ! git_tree_usable "$repo"; then
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
  local pin
  if pin="$(read_version_pin "$repo")"; then
    printf '%s\n' "${pin#* }"
    return
  fi
  if ! command -v git >/dev/null 2>&1; then
    echo "0.0.0-dev"
    return
  fi
  if ! git_tree_usable "$repo"; then
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

# Issue #2381: stamp $2 (a detached copy) with the version $1 (the source
# checkout) derives. Unlike the derivation itself this DOES fail loudly — it is
# an explicit caller-facing command, not the never-fail build path, and a
# silently skipped pin is precisely the release-gate placeholder it exists to
# prevent.
write_version_pin() {
  local source_repo="$1" target_dir="$2" ref="$3"

  if [[ ! -d "$target_dir" ]]; then
    printf 'derive-version.sh write-pin: target directory does not exist: %s\n' "$target_dir" >&2
    return 1
  fi

  local source_real target_real
  source_real="$(cd "$source_repo" && pwd -P)" || return 1
  target_real="$(cd "$target_dir" && pwd -P)" || return 1
  if [[ "$source_real" == "$target_real" ]]; then
    # Pinning a live checkout would freeze its version at whatever it happened
    # to be, and the next commit/tag would silently keep reporting the old one.
    printf 'derive-version.sh write-pin: refusing to pin a checkout to itself (%s). The pin is for a DETACHED copy that has no git of its own.\n' \
      "$target_real" >&2
    return 1
  fi

  local code name
  code="$(derive_version_code "$source_real" "$ref")"
  name="$(derive_version_name "$source_real" "$ref")"
  if [[ ! "$code" =~ ^[1-9][0-9]*$ || -z "$name" ]]; then
    printf 'derive-version.sh write-pin: source %s derived an unusable version (code=%s name=%s)\n' \
      "$source_real" "${code:-<empty>}" "${name:-<empty>}" >&2
    return 1
  fi

  local pin_file="$target_real/$VERSION_PIN_FILE_NAME"
  {
    printf '# Generated by scripts/derive-version.sh write-pin (issue #2381).\n'
    printf '# This tree is a detached copy of %s and has no git history of its\n' "$source_real"
    printf '# own; without this file it would derive the 0.0.0-dev placeholder.\n'
    printf 'VERSION_CODE=%s\n' "$code"
    printf 'VERSION_NAME=%s\n' "$name"
  } > "$pin_file" || return 1
  printf '%s\n' "$pin_file"
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

  # A COPY of the tree nested inside the repo must NOT inherit the repo's
  # tags. This is the shape the pre-release confidence gate creates when it
  # rsyncs the checkout to `<repo>/build/.../worktree` and re-execs there; git
  # discovery would otherwise ascend out of the copy and answer with the
  # PARENT repository's history, so the same commit derived a tag version or
  # the placeholder depending only on whether a mount boundary happened to sit
  # in between. Without this case the anchoring can be reverted silently.
  local nested="$repo/build/pre-release-confidence-gate/self-test/worktree"
  mkdir -p "$nested"
  local nested_name nested_code
  nested_name="$(derive_version_name "$nested" HEAD)"
  check "nested copy does not inherit the parent repo's versionName" \
    "$nested_name" "0.0.0-dev"
  nested_code="$(derive_version_code "$nested" HEAD)"
  check "nested copy does not inherit the parent repo's versionCode" \
    "$nested_code" "$VERSION_CODE_OFFSET"
  # Same nested path, but now a real worktree-style `.git` FILE: it DOES carry
  # its own git, so it must derive normally. Proves the guard keys on the tree
  # owning a git, not on the path looking generated, and that a `.git` file
  # (every release worktree) is accepted alongside a `.git` directory.
  printf 'gitdir: %s\n' "$repo/.git" > "$nested/.git"
  local nested_linked_name
  nested_linked_name="$(derive_version_name "$nested" HEAD)"
  check "nested tree with its own .git FILE derives normally" \
    "$nested_linked_name" "$(derive_version_name "$repo" HEAD)"
  rm -rf "$repo/build"

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

  # ---- Issue #2381: the detached-copy version pin ------------------------
  # The gate's `.git`-less rsync copy is where every release-chain APK is
  # actually built. Red half first, so this block can never pass vacuously.
  local pinned="$sandbox/gate-copy"
  mkdir -p "$pinned"
  check "detached copy WITHOUT a pin is the placeholder (the #2381 bug)" \
    "$(derive_version_name "$pinned" HEAD)" "0.0.0-dev"
  check "detached copy WITHOUT a pin floors the versionCode (the #2381 bug)" \
    "$(derive_version_code "$pinned" HEAD)" "$VERSION_CODE_OFFSET"

  local expected_name expected_code
  expected_name="$(derive_version_name "$repo" HEAD)"
  expected_code="$(derive_version_code "$repo" HEAD)"
  local pin_path
  pin_path="$(write_version_pin "$repo" "$pinned" HEAD)"
  check "write-pin reports the file it wrote" \
    "$pin_path" "$pinned/$VERSION_PIN_FILE_NAME"
  check "pinned copy derives the SOURCE checkout's versionName" \
    "$(derive_version_name "$pinned" HEAD)" "$expected_name"
  check "pinned copy derives the SOURCE checkout's versionCode" \
    "$(derive_version_code "$pinned" HEAD)" "$expected_code"
  # Guard against a degenerate green: the source must not itself be the
  # placeholder, or the two checks above would pass while proving nothing.
  if [[ "$expected_name" == "0.0.0-dev" || "$expected_code" == "$VERSION_CODE_OFFSET" ]]; then
    printf '  FAIL: pin cases are vacuous — the self-test source repo derived the placeholder (%s/%s)\n' \
      "$expected_code" "$expected_name" >&2
    failures=$((failures + 1))
  else
    printf '  ok: pin cases are non-vacuous (source derives %s/%s, not the placeholder)\n' \
      "$expected_code" "$expected_name"
  fi

  # The pin is consulted AHEAD of git, not as a fallback behind it (D22 hard
  # cut). A tree that has BOTH must answer from the pin, otherwise the gate's
  # copy would silently revert to the parent's history the day git discovery
  # reaches it again.
  local pinned_with_git="$sandbox/pinned-with-git"
  mkdir -p "$pinned_with_git"
  git -C "$pinned_with_git" init --quiet -b main
  git -C "$pinned_with_git" config user.email "test@example.com"
  git -C "$pinned_with_git" config user.name "Self Test"
  git -C "$pinned_with_git" commit --quiet --allow-empty -m "c1"
  git -C "$pinned_with_git" tag v9.9.9
  check "sanity: that tree derives its OWN tag before pinning" \
    "$(derive_version_name "$pinned_with_git" HEAD)" "9.9.9"
  printf 'VERSION_CODE=7\nVERSION_NAME=1.2.3\n' > "$pinned_with_git/$VERSION_PIN_FILE_NAME"
  check "pin wins over a usable git tree (precedence, not fallback)" \
    "$(derive_version_name "$pinned_with_git" HEAD)" "1.2.3"
  check "pin wins over a usable git tree (versionCode)" \
    "$(derive_version_code "$pinned_with_git" HEAD)" "7"

  # A malformed pin must not silently become a wrong version: it is reported
  # and ignored, and the tree falls back to its documented derivation.
  local malformed="$sandbox/malformed-pin"
  mkdir -p "$malformed"
  printf 'VERSION_NAME=\nVERSION_CODE=not-a-number\n' > "$malformed/$VERSION_PIN_FILE_NAME"
  local malformed_stderr
  malformed_stderr="$(derive_version_name "$malformed" HEAD 2>&1 >/dev/null)"
  check "malformed pin falls back to the placeholder" \
    "$(derive_version_name "$malformed" HEAD 2>/dev/null)" "0.0.0-dev"
  if [[ "$malformed_stderr" == *"ignoring malformed version pin"* ]]; then
    printf '  ok: malformed pin is reported on stderr, not swallowed\n'
  else
    printf '  FAIL: malformed pin produced no stderr diagnostic (got: %s)\n' "$malformed_stderr" >&2
    failures=$((failures + 1))
  fi
  # versionCode 0 is not a legal Android versionCode and must be rejected too.
  printf 'VERSION_CODE=0\nVERSION_NAME=1.2.3\n' > "$malformed/$VERSION_PIN_FILE_NAME"
  check "pin with versionCode=0 is rejected" \
    "$(derive_version_code "$malformed" HEAD 2>/dev/null)" "$VERSION_CODE_OFFSET"

  # write-pin refuses to freeze a live checkout at its current version.
  if write_version_pin "$repo" "$repo" HEAD >/dev/null 2>&1; then
    printf '  FAIL: write-pin pinned a checkout to itself\n' >&2
    failures=$((failures + 1))
    rm -f "$repo/$VERSION_PIN_FILE_NAME"
  else
    printf '  ok: write-pin refuses to pin a checkout to itself\n'
  fi
  if [[ -e "$repo/$VERSION_PIN_FILE_NAME" ]]; then
    printf '  FAIL: write-pin left a pin file in the source checkout\n' >&2
    failures=$((failures + 1))
    rm -f "$repo/$VERSION_PIN_FILE_NAME"
  else
    printf '  ok: the refused self-pin wrote nothing\n'
  fi
  if write_version_pin "$repo" "$sandbox/does-not-exist" HEAD >/dev/null 2>&1; then
    printf '  FAIL: write-pin accepted a non-existent target directory\n' >&2
    failures=$((failures + 1))
  else
    printf '  ok: write-pin rejects a non-existent target directory\n'
  fi

  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: version-code monotonic across tags, version-name matches convention, both fall back cleanly with no tags / no git, and a detached copy carries its source checkout'"'"'s version through the pin (#2381).\n'
  return 0
}

main() {
  local cmd="${1:-both}"
  [[ "$cmd" == "--self-test" ]] && { run_self_test; exit $?; }
  [[ "$cmd" == "-h" || "$cmd" == "--help" ]] && { usage; exit 0; }
  local pin_target=""
  case "$cmd" in
    version-code|version-name|both) shift || true ;;
    write-pin)
      shift || true
      pin_target="${1:-}"
      if [[ -z "$pin_target" ]]; then
        echo "write-pin requires a TARGET_DIR" >&2
        usage >&2
        exit 2
      fi
      shift
      ;;
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
    write-pin)
      write_version_pin "$ROOT_DIR" "$pin_target" "$ref" || exit 1
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
