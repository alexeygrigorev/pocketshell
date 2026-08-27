#!/usr/bin/env bash
# Self-test for scripts/ci-red-issue.sh (issue #2353).
#
# Stubs `gh` on PATH so this runs with no network, no real repo, no
# authentication. Captures every `gh` invocation's exact args (NUL-separated,
# so a call is never mis-parsed by whitespace in --body) to inspect what was
# actually sent. Uses a throwaway git repo with real commits so the bounded
# commit-window computation is exercised for real, not stubbed. Covers: no
# existing tracking issue => create with the exact commit range + failure
# signature; an existing open issue found via the marker search => comment
# (never a second create); only jobs reporting "failure" are named in the
# signature; a missing/unresolvable last-green sha reported honestly rather
# than invented; a failed search still creates rather than silently dropping
# the notification; and a failed `gh` call (create, or the binary missing)
# surfaces as a loud non-zero exit.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-red-issue.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/bin"

git init --quiet "$SANDBOX/repo"
git -C "$SANDBOX/repo" -c user.email=t@t -c user.name=t commit --quiet --allow-empty -m "commit A (last green)"
sha_a="$(git -C "$SANDBOX/repo" rev-parse HEAD)"
git -C "$SANDBOX/repo" -c user.email=t@t -c user.name=t commit --quiet --allow-empty -m "commit B"
git -C "$SANDBOX/repo" -c user.email=t@t -c user.name=t commit --quiet --allow-empty -m "commit C (red)"
sha_c="$(git -C "$SANDBOX/repo" rev-parse HEAD)"

# $1 = the issue number `issue list` should report as already-existing (empty
#      string = none found, so the target must create a new one).
write_fake_gh() {
  local existing="$1"
  cat > "$SANDBOX/bin/gh" <<FAKEGH
#!/usr/bin/env bash
n=\$(( \$(cat "$SANDBOX/call-count.txt" 2>/dev/null || echo 0) + 1 ))
echo "\$n" > "$SANDBOX/call-count.txt"
printf '%s\0' "\$@" > "$SANDBOX/call-\$n.args"
if [[ "\${FAKE_GH_ISSUE_LIST_FAIL:-}" == "1" && "\$1 \$2" == "issue list" ]]; then exit 1; fi
if [[ "\${FAKE_GH_CREATE_FAIL:-}" == "1" && "\$1 \$2" == "issue create" ]]; then exit 1; fi
case "\$1 \$2" in
  "issue list") echo '$existing' ;;
  "issue comment") echo "commented" ;;
  "issue create") echo "https://github.com/owner/repo/issues/999" ;;
esac
FAKEGH
  chmod +x "$SANDBOX/bin/gh"
}

read_call_args() {
  local _rca_n="$1" _rca_outvar="$2"
  local -a _rca_accum=()
  local _rca_a
  while IFS= read -r -d '' _rca_a; do
    _rca_accum+=("$_rca_a")
  done < "$SANDBOX/call-$_rca_n.args"
  eval "$_rca_outvar=(\"\${_rca_accum[@]}\")"
}

arg_value_after() {
  local n="$1" flag="$2"
  local -a args=()
  read_call_args "$n" args
  local i
  for ((i = 0; i < ${#args[@]}; i++)); do
    if [[ "${args[$i]}" == "$flag" ]]; then
      printf '%s' "${args[$((i + 1))]}"
      return 0
    fi
  done
  return 1
}

reset_sandbox_calls() { rm -f "$SANDBOX"/call-*.args "$SANDBOX/call-count.txt"; }

# -----------------------------------------------------------------------
# 1. No existing tracking issue => creates one with the real commit window
#    (commit A excluded, B/C included) and only-the-failed-jobs signature.
reset_sandbox_calls
write_fake_gh ""
out="$(cd "$SANDBOX/repo" && PATH="$SANDBOX/bin:$PATH" "$TARGET" \
  --repo owner/repo \
  --run-url "https://github.com/owner/repo/actions/runs/123" \
  --sha "$sha_c" \
  --last-green-sha "$sha_a" \
  --unit-gate failure --python success --integration skipped --emulator-journey-verdict failure 2>&1)"
echo "$out" | grep -q "Created tracking issue" || fail "expected a create when no existing issue is found: $out"
calls="$(cat "$SANDBOX/call-count.txt")"
[[ "$calls" -eq 2 ]] || fail "expected exactly 2 gh calls (list, create), got $calls"
create_body="$(arg_value_after 2 --body)" || fail "create call must carry --body"
echo "$create_body" | grep -q "commit B" || fail "created body must include commit B"
echo "$create_body" | grep -q "commit C (red)" || fail "created body must include commit C"
echo "$create_body" | grep -q "commit A (last green)" && fail "created body must NOT include commit A (the last-green boundary, excluded by a..b)"
echo "$create_body" | grep -q "Unit tests" || fail "created body must name Unit tests as failed"
echo "$create_body" | grep -q "Emulator journey aggregate verdict" || fail "created body must name the emulator-journey-verdict failure"
echo "$create_body" | grep -qi "python" && fail "created body must NOT name Python (it reported success)"
echo "$create_body" | grep -qi "Integration tests" && fail "created body must NOT name Integration (it was skipped, not failed)"
echo "$create_body" | grep -q "pocketshell-full-suite-red-marker" || fail "created body must include the stable marker"
pass "no existing issue creates a new tracking issue with the real bounded commit range and only-failed-jobs signature"

# -----------------------------------------------------------------------
# 2. Existing open issue found => comments on it, never creates.
reset_sandbox_calls
write_fake_gh "42"
out="$(cd "$SANDBOX/repo" && PATH="$SANDBOX/bin:$PATH" "$TARGET" \
  --repo owner/repo --run-url "https://github.com/owner/repo/actions/runs/124" \
  --sha "$sha_c" --unit-gate failure 2>&1)"
echo "$out" | grep -q "Commented on existing tracking issue #42" || fail "expected a comment on the existing issue: $out"
calls="$(cat "$SANDBOX/call-count.txt")"
[[ "$calls" -eq 2 ]] || fail "expected exactly 2 gh calls (list, comment), got $calls"
read_call_args 2 comment_args
[[ "${comment_args[2]}" == "42" ]] || fail "must comment on issue #42, args were: ${comment_args[*]}"
comment_body="$(arg_value_after 2 --body)" || fail "comment call must carry --body"
echo "$comment_body" | grep -q "no prior known-green scheduled run recorded" || fail "missing last-green sha must be reported honestly"
echo "$comment_body" | grep -q "commit list unavailable" || fail "missing last-green sha must report an unavailable commit list, not invented content"
pass "existing open issue found by marker search gets a comment, not a new issue; missing last-green is reported honestly"

# -----------------------------------------------------------------------
# 3. gh issue list fails => still falls through to creating (fail toward
#    over-notifying, never silently drops the red-run signal).
reset_sandbox_calls
write_fake_gh ""
out="$(cd "$SANDBOX/repo" && PATH="$SANDBOX/bin:$PATH" FAKE_GH_ISSUE_LIST_FAIL=1 "$TARGET" \
  --repo owner/repo --run-url "u" --sha "$sha_c" --unit-gate failure 2>&1)"
echo "$out" | grep -q "Created tracking issue" || fail "a failed search must still result in a create (never silently drop the notification): $out"
pass "a failed issue-list search still creates a tracking issue rather than dropping the notification"

# -----------------------------------------------------------------------
# 4. gh issue create fails => the step is loud (non-zero exit, clear message).
reset_sandbox_calls
write_fake_gh ""
set +e
out="$(cd "$SANDBOX/repo" && PATH="$SANDBOX/bin:$PATH" FAKE_GH_CREATE_FAIL=1 "$TARGET" \
  --repo owner/repo --run-url "u" --sha "$sha_c" --unit-gate failure 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 ]] || fail "a failed gh issue create must exit non-zero"
echo "$out" | grep -qi "failed to create" || fail "a failed gh issue create must say so: $out"
pass "a failed gh issue create surfaces loudly"

# -----------------------------------------------------------------------
# 5. gh CLI missing entirely => loud non-zero exit.
set +e
out="$(bash "$TARGET" --repo owner/repo --run-url "u" --sha "$sha_c" --gh does-not-exist-gh-binary-xyz 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 ]] || fail "missing gh must exit non-zero"
echo "$out" | grep -qi "gh CLI not found" || fail "missing gh must say so: $out"
pass "missing gh CLI surfaces loudly"

# -----------------------------------------------------------------------
# 6. missing required arguments => usage error, exit 2.
set +e
bash "$TARGET" --repo owner/repo >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "missing required arguments must exit 2, got $rc"
pass "missing required arguments exits 2"

echo "OK: $pass_count self-test case(s) passed."
