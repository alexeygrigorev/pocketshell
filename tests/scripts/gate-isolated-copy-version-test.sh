#!/usr/bin/env bash
set -euo pipefail

# tests/scripts/gate-isolated-copy-version-test.sh — issue #2381 (round 4).
#
# WHAT WENT WRONG (and what no gate would have told us):
#   scripts/pre-release-confidence-gate.sh rsyncs the release checkout to
#   `<checkout>/build/pre-release-confidence-gate/<run>/worktree` with
#   `--exclude='.git'` and re-execs there. EVERY APK the release chain
#   validates, journeys against and finally publishes is built inside that
#   copy. With no git of its own, scripts/derive-version.sh answered with the
#   `0.0.0-dev` / versionCode=1 placeholder — so the release gate has been
#   validating a binary that cannot express a release version at all, on a
#   checkout that (fetch-depth: 0) derives a perfectly good `0.4.45-N-gSHA`.
#
#   It stayed invisible because the failure was DEGENERATE, not loud: the
#   `agents`/bootstrap fixtures were reset to the same placeholder string, and
#   a string-equality version comparison matched two placeholders. The moment
#   the comparison became a real version comparison, every setup-detection
#   profile in HostBootstrapScenarioSuiteTest hard-failed on
#   release-emulator-validation.sh's REQUIRED `setup-detection` stage.
#
# WHAT THIS HARNESS PINS (all red-first — every green below is preceded by the
# corresponding red on the same tree, so it cannot pass vacuously):
#   1. A gate-shaped `.git`-less copy of a TAGGED checkout derives the
#      placeholder without the pin (the bug), and the source checkout's exact
#      version with it.
#   2. The pin carries versionCode too, not just versionName.
#   3. The real scripts/pre-release-confidence-gate.sh writes the pin AFTER its
#      rsync and BEFORE it `exec`s into the copy — the ordering is the whole
#      point, and a reordering/deletion is invisible to the compiler.
#   4. scripts/release-emulator-validation.sh's own release_chain_version_
#      preflight() (extracted and executed, not re-implemented) REFUSES a
#      tagless checkout with an actionable message and clears a tagged one —
#      so the anomaly is surfaced up front with a named cause instead of as
#      seven cause-free AssertionErrors ~40 minutes in — and its call site sits
#      before the AVD lock and the pre-release gate.
#
# JVM-free, Docker-free, emulator-free: synthetic git repos under mktemp. Never
# touches this repo's own tags or working tree.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

CASES=0
pass_case() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

expect_eq() {
  local desc="$1" actual="$2" expected="$3"
  [[ "$actual" == "$expected" ]] ||
    fail "$desc: got '$actual', expected '$expected'"
  pass_case "$desc -> $actual"
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

DERIVE="$ROOT_DIR/scripts/derive-version.sh"
GATE="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"
VALIDATION="$ROOT_DIR/scripts/release-emulator-validation.sh"
PIN_FILE_NAME=".pocketshell-version-pin"

for f in "$DERIVE" "$GATE" "$VALIDATION"; do
  [[ -f "$f" ]] || fail "missing production script under test: $f"
done

# ---------------------------------------------------------------------------
# A synthetic checkout shaped like this repo: real git, a real `v*` tag, and
# commits after it (the release chain's actual shape — a validated-RC build is
# N commits past the last tag).
# ---------------------------------------------------------------------------
source_repo="$tmpdir/checkout"
mkdir -p "$source_repo/scripts"
cp "$DERIVE" "$source_repo/scripts/derive-version.sh"
git -C "$source_repo" init --quiet -b main
git -C "$source_repo" config user.email "test@example.com"
git -C "$source_repo" config user.name "Gate Isolated Copy Test"
git -C "$source_repo" add -A
git -C "$source_repo" commit --quiet -m "c1"
git -C "$source_repo" tag v0.4.45
git -C "$source_repo" commit --quiet --allow-empty -m "c2"
git -C "$source_repo" commit --quiet --allow-empty -m "c3"

source_name="$(bash "$source_repo/scripts/derive-version.sh" version-name)"
source_code="$(bash "$source_repo/scripts/derive-version.sh" version-code)"
[[ "$source_name" == 0.4.45-2-g* ]] ||
  fail "fixture is not the release chain's shape: source derived '$source_name', expected 0.4.45-2-g<sha>"
[[ "$source_code" == "2" ]] ||
  fail "fixture versionCode drifted: got '$source_code', expected 2 (1 v* tag + offset)"
pass_case "fixture checkout derives a real post-tag release version ($source_code/$source_name)"

# ---------------------------------------------------------------------------
# Case 1-2 (RED): the gate's own rsync, extracted from the production script so
# this harness cannot drift from the real exclusion set.
# ---------------------------------------------------------------------------
gate_rsync_excludes="$(
  awk '/^  rsync -a --delete \\$/ { copy = 1 }
       copy { print; if ($0 ~ /isolated_root/) exit }' "$GATE" |
    grep -o -- "--exclude='[^']*'" | tr '\n' ' '
)"
[[ "$gate_rsync_excludes" == *"--exclude='.git'"* ]] ||
  fail "could not extract the gate's rsync exclusions (got: '$gate_rsync_excludes'); the harness is no longer testing the real copy shape"
pass_case "extracted the gate's real rsync exclusions ($gate_rsync_excludes)"

isolated_root="$source_repo/build/pre-release-confidence-gate/harness/worktree"
mkdir -p "$isolated_root"
# shellcheck disable=SC2086 # the extracted --exclude flags must word-split
eval rsync -a --delete $gate_rsync_excludes "\"\$source_repo/\"" "\"\$isolated_root/\""

[[ -e "$isolated_root/.git" ]] &&
  fail "the gate-shaped copy still carries .git; the extracted exclusions did not apply"
[[ -f "$isolated_root/scripts/derive-version.sh" ]] ||
  fail "the gate-shaped copy is missing scripts/derive-version.sh"

expect_eq "RED: unpinned gate copy derives the placeholder versionName" \
  "$(bash "$isolated_root/scripts/derive-version.sh" version-name)" "0.0.0-dev"
expect_eq "RED: unpinned gate copy derives the placeholder versionCode" \
  "$(bash "$isolated_root/scripts/derive-version.sh" version-code)" "1"

# ---------------------------------------------------------------------------
# Case 3-5 (GREEN): write-pin makes the copy version-identical to its source.
# ---------------------------------------------------------------------------
bash "$source_repo/scripts/derive-version.sh" write-pin "$isolated_root" >/dev/null ||
  fail "write-pin failed against the gate-shaped copy"
[[ -f "$isolated_root/$PIN_FILE_NAME" ]] ||
  fail "write-pin reported success but wrote no $PIN_FILE_NAME"

expect_eq "GREEN: pinned gate copy derives the SOURCE checkout's versionName" \
  "$(bash "$isolated_root/scripts/derive-version.sh" version-name)" "$source_name"
expect_eq "GREEN: pinned gate copy derives the SOURCE checkout's versionCode" \
  "$(bash "$isolated_root/scripts/derive-version.sh" version-code)" "$source_code"

# The load-bearing property, stated the way the failure was reported: the
# release CORE the app compares against the host CLI must not be 0.0.0.
pinned_core="$(bash "$isolated_root/scripts/derive-version.sh" version-name)"
pinned_core="${pinned_core#v}"
pinned_core="${pinned_core%%+*}"
pinned_core="${pinned_core%%-*}"
[[ -n "$pinned_core" && "$pinned_core" != "0.0.0" ]] ||
  fail "the pinned gate copy still resolves the 0.0.0 release core that makes every setup-detection profile vacuous"
expect_eq "GREEN: pinned gate copy resolves a real release core" "$pinned_core" "0.4.45"

# A re-run of the gate rsyncs again over the same copy. --delete must not strip
# the pin in a way the re-write cannot repair, and the copy must never silently
# drop back to the placeholder mid-gate.
# shellcheck disable=SC2086
eval rsync -a --delete $gate_rsync_excludes "\"\$source_repo/\"" "\"\$isolated_root/\""
bash "$source_repo/scripts/derive-version.sh" write-pin "$isolated_root" >/dev/null ||
  fail "write-pin failed on a re-rsynced copy"
expect_eq "a re-rsynced copy is re-pinned to the same version" \
  "$(bash "$isolated_root/scripts/derive-version.sh" version-name)" "$source_name"

# ---------------------------------------------------------------------------
# Case 7-8: the ORDERING inside the real gate script. The pin only works
# between the rsync and the exec; anywhere else and the child re-execs into an
# unpinned copy.
# ---------------------------------------------------------------------------
# `|| true` on each: under `set -euo pipefail` a non-matching grep would abort
# the harness with no message at all, replacing the actionable diagnostic below
# with a silent non-zero exit — which is exactly the failure shape this file
# exists to prevent.
gate_rsync_line="$(grep -n '^  rsync -a --delete \\$' "$GATE" | head -1 | cut -d: -f1 || true)"
gate_pin_line="$(grep -n 'derive-version\.sh" write-pin "\$isolated_root"' "$GATE" | head -1 | cut -d: -f1 || true)"
gate_exec_line="$(grep -n '^  exec "\$isolated_root/scripts/pre-release-confidence-gate\.sh"' "$GATE" | head -1 | cut -d: -f1 || true)"

[[ -n "$gate_rsync_line" ]] || fail "scripts/pre-release-confidence-gate.sh no longer rsyncs an isolated copy — this harness is stale"
[[ -n "$gate_pin_line" ]] ||
  fail "scripts/pre-release-confidence-gate.sh does not stamp its isolated copy with 'derive-version.sh write-pin \"\$isolated_root\"' (issue #2381). Without it the whole release chain builds, validates and publishes a 0.0.0-dev / versionCode=1 APK."
[[ -n "$gate_exec_line" ]] || fail "scripts/pre-release-confidence-gate.sh no longer execs into its isolated copy — this harness is stale"

(( gate_pin_line > gate_rsync_line )) ||
  fail "the isolated-copy version pin (line $gate_pin_line) must come AFTER the rsync (line $gate_rsync_line) — rsync --delete would remove it"
pass_case "the gate pins the isolated copy after the rsync (lines $gate_rsync_line -> $gate_pin_line)"

(( gate_pin_line < gate_exec_line )) ||
  fail "the isolated-copy version pin (line $gate_pin_line) must come BEFORE the exec into the copy (line $gate_exec_line) — the child would otherwise build from an unpinned tree"
pass_case "the gate pins the isolated copy before exec'ing into it (lines $gate_pin_line -> $gate_exec_line)"

# ---------------------------------------------------------------------------
# Case 9-11: release-emulator-validation.sh's tagless preflight. The PRODUCTION
# function is extracted and sourced (the same idiom
# tests/scripts/pre-release-version-test.sh uses on the gate's fixture check),
# so this exercises the real code without starting a 40-minute release run.
# ---------------------------------------------------------------------------
preflight_src="$tmpdir/preflight.sh"
awk '
  /^release_chain_version_preflight\(\)/ { copy = 1 }
  copy {
    print
    if ($0 == "}") exit
  }
' "$VALIDATION" > "$preflight_src"
grep -q 'issue #2381' "$preflight_src" ||
  fail "could not extract release_chain_version_preflight() from $VALIDATION — the harness is no longer testing the real preflight"
# shellcheck source=/dev/null
source "$preflight_src"
pass_case "extracted release_chain_version_preflight() from the production script"

chain_repo="$tmpdir/chain"
mkdir -p "$chain_repo/scripts"
cp "$DERIVE" "$chain_repo/scripts/derive-version.sh"
git -C "$chain_repo" init --quiet -b main
git -C "$chain_repo" config user.email "test@example.com"
git -C "$chain_repo" config user.name "Gate Isolated Copy Test"
git -C "$chain_repo" add -A
git -C "$chain_repo" commit --quiet -m "c1"

tagless_rc=0
tagless_output="$(release_chain_version_preflight "$chain_repo" 2>&1)" || tagless_rc=$?
(( tagless_rc != 0 )) ||
  fail "release_chain_version_preflight accepted a TAGLESS checkout (rc=0): $tagless_output"
grep -q 'REFUSING:.*issue #2381' <<< "$tagless_output" ||
  fail "the tagless refusal does not name issue #2381: $tagless_output"
grep -q 'fetch-depth: 0' <<< "$tagless_output" ||
  fail "the tagless refusal does not tell the operator how to fix the checkout: $tagless_output"
pass_case "RED: the release chain refuses a tagless checkout with an actionable message"

git -C "$chain_repo" tag v0.4.45
tagged_rc=0
tagged_output="$(release_chain_version_preflight "$chain_repo" 2>&1)" || tagged_rc=$?
(( tagged_rc == 0 )) ||
  fail "release_chain_version_preflight refused a properly TAGGED checkout (rc=$tagged_rc): $tagged_output"
grep -q 'Release chain version: 0.4.45 (core 0.4.45)' <<< "$tagged_output" ||
  fail "the tagged checkout did not report its resolved release version: $tagged_output"
pass_case "GREEN: the release chain clears the preflight on a tagged checkout"

# ---------------------------------------------------------------------------
# Case 12: the preflight's CALL SITE ordering. It must fire before the shared
# AVD lock (and therefore before the isolated rsync copy and every minute of
# Gradle) — a preflight that runs after the 40-minute gate is not a preflight.
# ---------------------------------------------------------------------------
preflight_call_line="$(grep -n '^  release_chain_version_preflight "\$ROOT_DIR" || exit 1$' "$VALIDATION" | head -1 | cut -d: -f1 || true)"
avd_lock_line="$(grep -n '^pocketshell_acquire_avd_lock "\$ROOT_DIR"' "$VALIDATION" | head -1 | cut -d: -f1 || true)"
gate_stage_line="$(grep -n 'scripts/pre-release-confidence-gate\.sh$' "$VALIDATION" | head -1 | cut -d: -f1 || true)"

[[ -n "$preflight_call_line" ]] ||
  fail "scripts/release-emulator-validation.sh defines release_chain_version_preflight but never CALLS it (issue #2381) — a tagless run would reach the setup-detection stage and fail there with seven cause-free AssertionErrors instead."
[[ -n "$avd_lock_line" ]] || fail "could not locate the AVD lock acquisition — this harness is stale"
[[ -n "$gate_stage_line" ]] || fail "could not locate the pre-release confidence gate stage — this harness is stale"

(( preflight_call_line < avd_lock_line && preflight_call_line < gate_stage_line )) ||
  fail "the #2381 version preflight (line $preflight_call_line) must run BEFORE the AVD lock (line $avd_lock_line) and the pre-release gate (line $gate_stage_line)"
pass_case "the version preflight runs before the AVD lock and the 40-minute gate (lines $preflight_call_line -> $avd_lock_line -> $gate_stage_line)"

# Issue #2113: the count line is what makes the JVM assertion about behaviour
# rather than about bash's exit status.
(( CASES == 14 )) || fail "expected 14 cases to run, saw $CASES"
printf 'PASS: gate isolated-copy version pin (%s cases)\n' "$CASES"
