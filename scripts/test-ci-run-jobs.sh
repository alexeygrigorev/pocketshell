#!/usr/bin/env bash
# Self-test for scripts/ci-run-jobs.sh (issue #2353).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-run-jobs.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/bin"

write_fake_gh() {
  # $1 = mode: "mixed" (some failed, some not), "empty" (no jobs), "fail" (gh errors)
  local mode="$1"
  cat > "$SANDBOX/bin/gh" <<FAKEGH
#!/usr/bin/env bash
case "\$1 \$2" in
  "api repos/owner/repo/actions/runs/123/jobs?per_page=100")
    case "$mode" in
      fail) exit 1 ;;
      empty) echo '{"jobs": []}' ;;
      mixed)
        echo '{"jobs": [
          {"name": "Unit tests", "conclusion": "failure"},
          {"name": "Python utility tests (pocketshell)", "conclusion": "success"},
          {"name": "Integration tests (Docker)", "conclusion": "success"},
          {"name": "Emulator journey aggregate verdict (#1458)", "conclusion": "failure"}
        ]}'
        ;;
    esac
    ;;
esac
FAKEGH
  chmod +x "$SANDBOX/bin/gh"
}

run_target() {
  PATH="$SANDBOX/bin:$PATH" "$TARGET" --repo owner/repo --run-id 123 "$@"
}

# 1. Mixed conclusions: each named job is read correctly and independently.
write_fake_gh mixed
out="$(run_target)"
echo "$out" | grep -qx "UNIT_GATE=failure" || fail "Unit tests must read failure: $out"
echo "$out" | grep -qx "PYTHON=success" || fail "Python must read success: $out"
echo "$out" | grep -qx "INTEGRATION=success" || fail "Integration must read success: $out"
echo "$out" | grep -qx "EMULATOR_JOURNEY_VERDICT=failure" || fail "Emulator journey verdict must read failure: $out"
pass "each named job's conclusion is read independently and correctly"

# 2. No jobs in the run at all -> every key is "unknown", not invented/blank.
write_fake_gh empty
out="$(run_target)"
echo "$out" | grep -qx "UNIT_GATE=unknown" || fail "missing job must report unknown, not blank/invented: $out"
echo "$out" | grep -qx "EMULATOR_JOURNEY_VERDICT=unknown" || fail "missing job must report unknown: $out"
pass "a run with no matching jobs reports 'unknown' for every key (never invented/blank)"

# 3. gh api call fails -> fails open to "unknown" for every key, does not crash.
write_fake_gh fail
out="$(run_target)"
rc=$?
[[ "$rc" -eq 0 ]] || fail "a failed gh api call must not crash the script, got rc=$rc"
echo "$out" | grep -qx "UNIT_GATE=unknown" || fail "a failed gh api call must fail open to unknown: $out"
pass "a failed gh api call fails open to 'unknown' for every key"

# 4. gh CLI missing entirely -> same fail-open behaviour.
out="$(SCRIPT_DIR_UNUSED=1 "$TARGET" --repo owner/repo --run-id 123 --gh does-not-exist-gh-binary-xyz)"
echo "$out" | grep -qx "UNIT_GATE=unknown" || fail "missing gh must fail open to unknown: $out"
pass "missing gh CLI fails open to 'unknown' for every key"

# 5. $GITHUB_OUTPUT mirrors the four values (lowercased keys).
write_fake_gh mixed
gh_out="$SANDBOX/github_output.txt"
: > "$gh_out"
PATH="$SANDBOX/bin:$PATH" GITHUB_OUTPUT="$gh_out" "$TARGET" --repo owner/repo --run-id 123 >/dev/null
grep -qx "unit_gate=failure" "$gh_out" || fail "GITHUB_OUTPUT must mirror unit_gate: $(cat "$gh_out")"
grep -qx "emulator_journey_verdict=failure" "$gh_out" || fail "GITHUB_OUTPUT must mirror emulator_journey_verdict: $(cat "$gh_out")"
pass "GITHUB_OUTPUT mirrors all four values with lowercased keys"

# 6. missing required arguments -> usage error, exit 2.
set +e
"$TARGET" --repo owner/repo >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "missing --run-id must exit 2, got $rc"
pass "missing required argument exits 2"

echo "OK: $pass_count self-test case(s) passed."
