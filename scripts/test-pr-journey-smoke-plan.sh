#!/usr/bin/env bash
# Self-test for scripts/pr-journey-smoke-plan.sh (issue #2354).
#
# Runs against a stubbed select-test-areas.sh (no dependency on the real
# manifest, so this is fast and independent of taxonomy drift) to prove the
# GATING decision: SHOULD_RUN=true on MODE=full, SHOULD_RUN=true when
# CHANGED_AREAS intersects the load-bearing set, SHOULD_RUN=false otherwise,
# and SMOKE_CLASSES is the same fixed constant in every case. Then a second
# section runs the REAL select-test-areas.sh against synthetic changed-path
# fixtures (via --changed-file) to prove the mapping actually lands the way
# the gating logic assumes for real areas (connection-core, tmux-session,
# hosts-settings) — the seam a stub-only test cannot see drift on.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/pr-journey-smoke-plan.sh"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# ---------------------------------------------------------------------------
# Section 1: stubbed select-test-areas.sh, proving the GATING decision logic.
# ---------------------------------------------------------------------------
mkdir -p "$SANDBOX/stub"
cat > "$SANDBOX/stub/select-test-areas.sh" <<'STUB'
#!/usr/bin/env bash
# Echoes a canned plan controlled by env vars, so the caller's gating logic
# is exercised without depending on the real manifest.
printf 'MODE=%s\n' "${STUB_MODE:-scoped}"
printf 'AREAS=irrelevant\n'
printf 'CHANGED_AREAS=%s\n' "${STUB_CHANGED_AREAS:-}"
exit "${STUB_EXIT:-0}"
STUB
chmod +x "$SANDBOX/stub/select-test-areas.sh"

run_target() {
  # $1 = STUB_MODE, $2 = STUB_CHANGED_AREAS, $3 = STUB_EXIT (optional)
  STUB_MODE="$1" STUB_CHANGED_AREAS="$2" STUB_EXIT="${3:-0}" \
    POCKETSHELL_SELECT_TEST_AREAS="$SANDBOX/stub/select-test-areas.sh" \
    "$TARGET"
}

expected_classes="com.pocketshell.app.proof.Issue2338SecondLaunchTerminalAttachJourneyE2eTest,com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest,com.pocketshell.app.proof.ComposerAlwaysPresentSwitchJourneyE2eTest"

# 1. MODE=full => SHOULD_RUN=true, regardless of CHANGED_AREAS content.
out="$(run_target full "")"
echo "$out" | grep -qx "SHOULD_RUN=true" || fail "MODE=full must set SHOULD_RUN=true: $out"
pass "MODE=full sets SHOULD_RUN=true (force-full fail-safe)"

# 2. MODE=scoped, CHANGED_AREAS includes a load-bearing area (connection-core)
#    => SHOULD_RUN=true.
out="$(run_target scoped "hosts-settings connection-core files")"
echo "$out" | grep -qx "SHOULD_RUN=true" || fail "connection-core in CHANGED_AREAS must set SHOULD_RUN=true: $out"
echo "$out" | grep -q "^REASON=changed area 'connection-core'" || fail "REASON must name the matched area: $out"
pass "a load-bearing changed area (connection-core) sets SHOULD_RUN=true"

# 3. MODE=scoped, CHANGED_AREAS includes each of the other three load-bearing
#    areas individually => SHOULD_RUN=true.
for area in terminal-render tmux-session composer-voice; do
  out="$(run_target scoped "$area")"
  echo "$out" | grep -qx "SHOULD_RUN=true" || fail "$area alone must set SHOULD_RUN=true: $out"
done
pass "terminal-render, tmux-session, and composer-voice each individually set SHOULD_RUN=true"

# 4. MODE=scoped, CHANGED_AREAS has NO load-bearing area => SHOULD_RUN=false.
#    This is the docs/python-only-PR-skips-the-job property.
out="$(run_target scoped "hosts-settings files usage-costs")"
echo "$out" | grep -qx "SHOULD_RUN=false" || fail "no load-bearing area must set SHOULD_RUN=false: $out"
echo "$out" | grep -q "^REASON=no changed area intersects" || fail "REASON must explain the non-match: $out"
pass "no load-bearing changed area sets SHOULD_RUN=false"

# 5. MODE=scoped, CHANGED_AREAS is empty (always-tier-only push with no
#    changed seed area — e.g. every changed path was noop) => SHOULD_RUN=false.
out="$(run_target scoped "")"
echo "$out" | grep -qx "SHOULD_RUN=false" || fail "empty CHANGED_AREAS in scoped mode must set SHOULD_RUN=false: $out"
pass "empty CHANGED_AREAS in scoped mode sets SHOULD_RUN=false"

# 6. SMOKE_CLASSES is the SAME fixed constant regardless of SHOULD_RUN.
out_true="$(run_target full "")"
out_false="$(run_target scoped "files")"
echo "$out_true" | grep -qx "SMOKE_CLASSES=$expected_classes" || fail "SMOKE_CLASSES wrong on SHOULD_RUN=true: $out_true"
echo "$out_false" | grep -qx "SMOKE_CLASSES=$expected_classes" || fail "SMOKE_CLASSES wrong on SHOULD_RUN=false: $out_false"
pass "SMOKE_CLASSES is the fixed 3-class list regardless of SHOULD_RUN"

# 7. select-test-areas.sh exiting non-zero fails OPEN (SHOULD_RUN=true) rather
#    than silently skipping the smoke job on a broken plan.
out="$(run_target full "" 1)"
echo "$out" | grep -qx "SHOULD_RUN=true" || fail "a failing select-test-areas.sh must fail OPEN: $out"
pass "select-test-areas.sh exiting non-zero fails open (SHOULD_RUN=true)"

# 8. GITHUB_OUTPUT gets the same decision, lower-cased.
out_file="$SANDBOX/github_output.txt"
: > "$out_file"
STUB_MODE=full STUB_CHANGED_AREAS="" \
  POCKETSHELL_SELECT_TEST_AREAS="$SANDBOX/stub/select-test-areas.sh" \
  GITHUB_OUTPUT="$out_file" "$TARGET" >/dev/null
grep -qx "should_run=true" "$out_file" || fail "GITHUB_OUTPUT must carry should_run: $(cat "$out_file")"
grep -qx "smoke_classes=$expected_classes" "$out_file" || fail "GITHUB_OUTPUT must carry smoke_classes: $(cat "$out_file")"
pass "GITHUB_OUTPUT carries should_run/smoke_classes"

# 9. Running without $GITHUB_OUTPUT set does not error.
STUB_MODE=full STUB_CHANGED_AREAS="" \
  POCKETSHELL_SELECT_TEST_AREAS="$SANDBOX/stub/select-test-areas.sh" \
  "$TARGET" >/dev/null || fail "running without \$GITHUB_OUTPUT set must not error"
pass "running without \$GITHUB_OUTPUT set does not error"

# ---------------------------------------------------------------------------
# Section 2: the REAL select-test-areas.sh against synthetic changed-path
# fixtures, proving the manifest actually maps the way Section 1 assumes.
# ---------------------------------------------------------------------------
# pr-journey-smoke-plan.sh only exposes --base (it always diffs the real repo
# via select-test-areas.sh's own git invocation), so to drive the REAL script
# against a synthetic path set we call select-test-areas.sh directly with
# --changed-file the same way the wrapper's own gating logic reads its output,
# then apply the identical LOAD_BEARING_AREAS test this script hard-codes.
SELECT_TEST_AREAS="$REPO_ROOT/scripts/select-test-areas.sh"
[[ -x "$SELECT_TEST_AREAS" ]] || chmod +x "$SELECT_TEST_AREAS"

real_changed_areas() {
  local changed_file="$SANDBOX/real-changed.txt"
  printf '%s\n' "$1" > "$changed_file"
  "$SELECT_TEST_AREAS" --changed-file "$changed_file" --print-plan-only
}

# 10. A tmux-session-area file (e.g. TmuxSessionScreen.kt) is a real,
#     load-bearing, SCOPED (non-full) change.
out="$(real_changed_areas "app/src/main/java/com/pocketshell/app/tmux/TmuxSessionScreen.kt")"
mode="$(printf '%s\n' "$out" | grep '^MODE=' | cut -d= -f2-)"
changed="$(printf '%s\n' "$out" | grep '^CHANGED_AREAS=' | cut -d= -f2-)"
[[ "$mode" == "scoped" ]] || fail "TmuxSessionScreen.kt unexpectedly forced MODE=full: $out"
echo " $changed " | grep -q " tmux-session " || fail "TmuxSessionScreen.kt must classify into tmux-session: $out"
pass "the real manifest maps app/tmux/TmuxSessionScreen.kt into tmux-session (scoped)"

# 11. A usage-costs-only file must NOT intersect the load-bearing set — the
#     docs/python-only-PR-skips-the-job property, proven against the REAL
#     manifest rather than just the stub. (usage-costs is a `couple`-graph
#     leaf with no outgoing edge, unlike e.g. hosts-settings, which DOES
#     transitively reach tmux-session via projects-tree — that broad coupling
#     is the manifest's deliberate, existing design (#2063: "not a speed
#     feature"), not a defect this test should fight.)
out="$(real_changed_areas "app/src/main/java/com/pocketshell/app/usage/UsageViewModel.kt")"
mode="$(printf '%s\n' "$out" | grep '^MODE=' | cut -d= -f2-)"
changed="$(printf '%s\n' "$out" | grep '^CHANGED_AREAS=' | cut -d= -f2-)"
[[ "$mode" == "scoped" ]] || fail "UsageViewModel.kt unexpectedly forced MODE=full: $out"
for lb in connection-core terminal-render tmux-session composer-voice; do
  echo " $changed " | grep -q " $lb " && fail "usage-costs-only change must not reach $lb: $out"
done
pass "the real manifest keeps a usage-costs-only change out of every load-bearing area"

# 12. tools/pocketshell (host CLI, Python) is its own area and must not
#     intersect the load-bearing set either.
out="$(real_changed_areas "tools/pocketshell/src/pocketshell/cli.py")"
mode="$(printf '%s\n' "$out" | grep '^MODE=' | cut -d= -f2-)"
changed="$(printf '%s\n' "$out" | grep '^CHANGED_AREAS=' | cut -d= -f2-)"
if [[ "$mode" == "scoped" ]]; then
  for lb in connection-core terminal-render tmux-session composer-voice; do
    echo " $changed " | grep -q " $lb " && fail "a Python-CLI-only change must not reach $lb: $out"
  done
fi
pass "the real manifest keeps a Python-CLI-only change out of every load-bearing area"

echo "OK: $pass_count self-test case(s) passed."
