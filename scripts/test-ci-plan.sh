#!/usr/bin/env bash
# Self-test for scripts/ci-plan.sh (issue #2353).
#
# Runs against a throwaway git repo (no dependency on the real pocketshell
# tree's test-area manifest) so this is fast and independent of taxonomy
# drift. Proves: a valid --before SHA is passed through as --base; a missing,
# all-zero, or unresolvable --before SHA falls back to
# select-test-areas.sh's own default base instead of erroring; and — the
# SAFETY-CRITICAL property, since the workflow trusts this script's output
# unconditionally — the derived SCOPED_CLASSES output is the journey
# list ONLY when the plan is MODE=scoped, and is EMPTY whenever MODE=full
# (never silently filters journeys on a force-full push).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-plan.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# A stub select-test-areas.sh that echoes its args (so this test proves the
# BASE ARGUMENT WIRING, not the real taxonomy) and, when $STUB_MODE is set,
# also writes MODE=/JOURNEY_CLASSES= to $GITHUB_OUTPUT the way the real
# --github-output flag does — this is what lets the SCOPED_CLASSES
# derivation below be exercised without depending on the real manifest.
mkdir -p "$SANDBOX/scripts"
cat > "$SANDBOX/scripts/select-test-areas.sh" <<'STUB'
#!/usr/bin/env bash
echo "ARGS: $*"
if [[ -n "${STUB_MODE:-}" && -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "MODE=${STUB_MODE}"
    echo "JOURNEY_CLASSES=${STUB_CLASSES:-}"
  } >> "$GITHUB_OUTPUT"
fi
STUB
chmod +x "$SANDBOX/scripts/select-test-areas.sh"
cp "$TARGET" "$SANDBOX/scripts/ci-plan.sh"
chmod +x "$SANDBOX/scripts/ci-plan.sh"

# A real (throwaway) git repo so `git cat-file -e` has something to resolve
# against, with one commit to reference as a valid --before SHA.
git init --quiet "$SANDBOX/repo"
git -C "$SANDBOX/repo" -c user.email=t@t -c user.name=t commit --quiet --allow-empty -m "initial"
valid_sha="$(git -C "$SANDBOX/repo" rev-parse HEAD)"

run_target() {
  (cd "$SANDBOX/repo" && "$SANDBOX/scripts/ci-plan.sh" "$@")
}

# 1. A valid, resolvable --before SHA is passed through as --base.
out="$(run_target --before "$valid_sha")"
echo "$out" | grep -q -- "--base $valid_sha" || fail "a valid --before sha must be forwarded as --base: $out"
echo "$out" | grep -q -- "--github-output" || fail "must always pass --github-output: $out"
pass "a valid --before sha is forwarded as --base"

# 2. All-zero SHA (first push to a new ref) falls back — no --base at all.
zero="0000000000000000000000000000000000000000"
out="$(run_target --before "$zero")"
echo "$out" | grep -q -- "--base" && fail "an all-zero --before must NOT be forwarded as --base: $out"
echo "$out" | grep -q -- "--github-output" || fail "must still pass --github-output on fallback: $out"
pass "an all-zero --before sha falls back to select-test-areas.sh's own default base"

# 3. Missing --before entirely falls back the same way.
out="$(run_target --before "")"
echo "$out" | grep -q -- "--base" && fail "an empty --before must NOT be forwarded as --base: $out"
pass "an empty --before sha falls back to select-test-areas.sh's own default base"

# 4. An unresolvable (garbage/unknown) --before falls back rather than erroring.
out="$(run_target --before "0123456789abcdef0123456789abcdef01234567")"
echo "$out" | grep -q -- "--base" && fail "an unresolvable --before must NOT be forwarded as --base: $out"
pass "an unresolvable --before sha falls back rather than erroring"

run_target_with_output() {
  # $1 = STUB_MODE, $2 = STUB_CLASSES
  local out_file="$SANDBOX/github_output.txt"
  : > "$out_file"
  (cd "$SANDBOX/repo" && STUB_MODE="$1" STUB_CLASSES="$2" GITHUB_OUTPUT="$out_file" \
    "$SANDBOX/scripts/ci-plan.sh" --before "$valid_sha" >/dev/null)
  cat "$out_file"
}

# 5. MODE=scoped => SCOPED_CLASSES carries the real journey list.
gh_out="$(run_target_with_output scoped "com.pocketshell.app.proof.Foo,com.pocketshell.app.proof.Bar")"
echo "$gh_out" | grep -qx "SCOPED_CLASSES=com.pocketshell.app.proof.Foo,com.pocketshell.app.proof.Bar" \
  || fail "MODE=scoped must pass the journey list through as SCOPED_CLASSES: $gh_out"
pass "MODE=scoped carries the journey list into SCOPED_CLASSES"

# 6. MODE=full => SCOPED_CLASSES is EMPTY, even though JOURNEY_CLASSES
#    itself is non-empty (a full plan always lists every registered class) —
#    this is the property that stops a force-full push from ever having its
#    journey classes silently filtered by the shard-side intersection.
gh_out="$(run_target_with_output full "com.pocketshell.app.proof.Foo,com.pocketshell.app.proof.Bar,com.pocketshell.app.proof.Baz")"
echo "$gh_out" | grep -qx "SCOPED_CLASSES=" \
  || fail "MODE=full must leave SCOPED_CLASSES EMPTY (safety property): $gh_out"
pass "MODE=full leaves SCOPED_CLASSES empty even though JOURNEY_CLASSES is non-empty"

# 7. No $GITHUB_OUTPUT at all (e.g. local invocation) doesn't error.
(cd "$SANDBOX/repo" && STUB_MODE=scoped STUB_CLASSES=x "$SANDBOX/scripts/ci-plan.sh" --before "$valid_sha" >/dev/null) \
  || fail "running without \$GITHUB_OUTPUT set must not error"
pass "running without \$GITHUB_OUTPUT set does not error"

# 8. $GITHUB_STEP_SUMMARY: the script itself appends the human-readable plan
#    (the same text it prints to stdout), so the workflow step does not need
#    its own `| tee -a`.
summary_file="$SANDBOX/step_summary.txt"
: > "$summary_file"
stdout_out="$(cd "$SANDBOX/repo" && GITHUB_STEP_SUMMARY="$summary_file" "$SANDBOX/scripts/ci-plan.sh" --before "$valid_sha")"
[[ -s "$summary_file" ]] || fail "GITHUB_STEP_SUMMARY must be written to when set"
diff <(printf '%s\n' "$stdout_out") "$summary_file" >/dev/null \
  || fail "GITHUB_STEP_SUMMARY content must match stdout exactly"
pass "GITHUB_STEP_SUMMARY gets the same human-readable plan as stdout"

# 9. No $GITHUB_STEP_SUMMARY set (e.g. local invocation) doesn't error.
(cd "$SANDBOX/repo" && "$SANDBOX/scripts/ci-plan.sh" --before "$valid_sha" >/dev/null) \
  || fail "running without \$GITHUB_STEP_SUMMARY set must not error"
pass "running without \$GITHUB_STEP_SUMMARY set does not error"

echo "OK: $pass_count self-test case(s) passed."
