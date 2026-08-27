#!/usr/bin/env bash
# Self-test for scripts/ci-skip-check.sh (issue #2353).
#
# Stubs `gh` on PATH so this runs with no network, no real repo, no
# authentication. Every scenario the target script's fail-open contract
# depends on is exercised: missing gh, a failing API call, malformed JSON, no
# prior green run, HEAD matching/differing from the last green sha (including
# picking the MOST RECENT run by created_at rather than array position), the
# $GITHUB_OUTPUT mirror, and the required-argument usage error.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-skip-check.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/bin"

# $1 = the sha the fake API should report as the (only, most-recent-if-many)
#      run's head_sha, when relevant to the mode.
write_fake_gh() {
  local sha="$1"
  cat > "$SANDBOX/bin/gh" <<FAKEGH
#!/usr/bin/env bash
mode="\${FAKE_GH_MODE:-}"
case "\$mode" in
  fail) exit 1 ;;
  malformed) echo 'not json' ;;
  empty) echo '{"workflow_runs": []}' ;;
  one) echo '{"workflow_runs": [{"head_sha": "$sha", "created_at": "2026-01-01T00:00:00Z"}]}' ;;
  ordering)
    echo '{"workflow_runs": [
      {"head_sha": "0000000000000000000000000000000000older", "created_at": "2026-01-01T00:00:00Z"},
      {"head_sha": "$sha", "created_at": "2026-01-03T00:00:00Z"},
      {"head_sha": "0000000000000000000000000000000middle0", "created_at": "2026-01-02T00:00:00Z"}
    ]}'
    ;;
  *) echo '{"workflow_runs": []}' ;;
esac
FAKEGH
  chmod +x "$SANDBOX/bin/gh"
}

run_target() {
  # $1 = FAKE_GH_MODE, $2 = --sha value
  PATH="$SANDBOX/bin:$PATH" FAKE_GH_MODE="$1" \
    bash "$TARGET" --repo owner/repo --sha "$2" 2>&1
}

green_sha="cafef00d0000000000000000000000000000cafe"
write_fake_gh "$green_sha"

# 1. gh missing entirely => fail open (SKIP=false), no crash.
out="$(bash "$TARGET" --repo owner/repo --sha deadbeef00000000000000000000000000000000 --gh does-not-exist-gh-binary-xyz 2>&1)"
echo "$out" | grep -qx 'SKIP=false' || fail "missing gh must fail open: $out"
pass "missing gh fails open"

# 2. gh api call fails => fail open.
out="$(run_target fail deadbeef00000000000000000000000000000000)"
echo "$out" | grep -qx 'SKIP=false' || fail "gh api failure must fail open: $out"
pass "gh api failure fails open"

# 3. malformed JSON response => fail open.
out="$(run_target malformed deadbeef00000000000000000000000000000000)"
echo "$out" | grep -qx 'SKIP=false' || fail "malformed JSON must fail open: $out"
pass "malformed JSON fails open"

# 4. no prior green run => fail open, empty LAST_GREEN_SHA.
out="$(run_target empty deadbeef00000000000000000000000000000000)"
echo "$out" | grep -qx 'SKIP=false' || fail "no prior green run must fail open: $out"
echo "$out" | grep -qx 'LAST_GREEN_SHA=' || fail "no prior green run must report empty LAST_GREEN_SHA: $out"
pass "no prior green run fails open with empty last-green sha"

# 5. HEAD == last green sha => SKIP=true, LAST_GREEN_SHA reported.
out="$(run_target one "$green_sha")"
echo "$out" | grep -qx 'SKIP=true' || fail "matching HEAD must skip: $out"
echo "$out" | grep -qx "LAST_GREEN_SHA=$green_sha" || fail "matching HEAD must report the last green sha: $out"
pass "HEAD matching last green sha skips"

# 6. HEAD != last green sha => SKIP=false, LAST_GREEN_SHA still reported.
out="$(run_target one differentshaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)"
echo "$out" | grep -qx 'SKIP=false' || fail "differing HEAD must not skip: $out"
echo "$out" | grep -qx "LAST_GREEN_SHA=$green_sha" || fail "differing HEAD must still report the last green sha: $out"
pass "HEAD differing from last green sha does not skip, still reports last green sha"

# 7. multiple runs => pick the most recent by created_at, not array order.
out="$(run_target ordering "$green_sha")"
echo "$out" | grep -qx "LAST_GREEN_SHA=$green_sha" || fail "must pick the most recent run by created_at, not array position: $out"
pass "picks the most recent run by created_at"

# 8. $GITHUB_OUTPUT mirrors the decision.
gh_out_file="$SANDBOX/github_output.txt"
: > "$gh_out_file"
PATH="$SANDBOX/bin:$PATH" FAKE_GH_MODE=one GITHUB_OUTPUT="$gh_out_file" \
  bash "$TARGET" --repo owner/repo --sha "$green_sha" >/dev/null
grep -qx "should_run=false" "$gh_out_file" || fail "GITHUB_OUTPUT must record should_run=false on a skip: $(cat "$gh_out_file")"
grep -qx "last_green_sha=$green_sha" "$gh_out_file" || fail "GITHUB_OUTPUT must record last_green_sha: $(cat "$gh_out_file")"
pass "GITHUB_OUTPUT mirrors the decision"

gh_out_file2="$SANDBOX/github_output2.txt"
: > "$gh_out_file2"
PATH="$SANDBOX/bin:$PATH" FAKE_GH_MODE=one GITHUB_OUTPUT="$gh_out_file2" \
  bash "$TARGET" --repo owner/repo --sha differentshaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa >/dev/null
grep -qx "should_run=true" "$gh_out_file2" || fail "GITHUB_OUTPUT must record should_run=true when not skipping: $(cat "$gh_out_file2")"
pass "GITHUB_OUTPUT records should_run=true on a non-skip"

# 9. missing required arguments => usage error, exit 2.
set +e
bash "$TARGET" --repo owner/repo >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "missing --sha must exit 2, got $rc"
pass "missing required argument exits 2"

echo "OK: $pass_count self-test case(s) passed."
