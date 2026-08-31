#!/usr/bin/env bash
# Self-test for scripts/ci-nightly-rc-issue.sh (issue #2356).
#
# Stubs `gh` on PATH (no network, no auth). Covers: no existing tracking
# issue => create with the marker + SHA + run URL in the body; an existing
# open issue found via the marker search => comment (never a second
# create); a failed `gh create` call surfaces as a loud non-zero exit.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-nightly-rc-issue.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/bin"

write_fake_gh() {
  local existing="$1" create_fail="${2:-0}"
  cat > "$SANDBOX/bin/gh" <<FAKEGH
#!/usr/bin/env bash
n=\$(( \$(cat "$SANDBOX/call-count.txt" 2>/dev/null || echo 0) + 1 ))
echo "\$n" > "$SANDBOX/call-count.txt"
printf '%s\0' "\$@" > "$SANDBOX/call-\$n.args"
if [[ "$create_fail" == "1" && "\$1 \$2" == "issue create" ]]; then exit 1; fi
case "\$1 \$2" in
  "issue list") echo '$existing' ;;
  "issue comment") echo "commented" ;;
  "issue create") echo "https://github.com/owner/repo/issues/999" ;;
esac
FAKEGH
  chmod +x "$SANDBOX/bin/gh"
}

reset_sandbox_calls() { rm -f "$SANDBOX"/call-*.args "$SANDBOX/call-count.txt"; }
call_body() {
  local n="$1"
  local args=()
  local a
  while IFS= read -r -d '' a; do args+=("$a"); done < "$SANDBOX/call-$n.args"
  local i
  for ((i = 0; i < ${#args[@]}; i++)); do
    if [[ "${args[$i]}" == "--body" ]]; then printf '%s' "${args[$((i + 1))]}"; return 0; fi
  done
}

# --- no existing issue => create, body carries marker + SHA + run URL ---
reset_sandbox_calls
write_fake_gh ""
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" --repo owner/repo --run-url "https://example/validation/1" --trigger-run-url "https://example/tests/1" --sha deadbeef)"
[[ "$out" == *"Created tracking issue"* ]] || fail "expected a create, got: $out"
create_call="$(for f in "$SANDBOX"/call-*.args; do
  if tr '\0' '\n' < "$f" | sed -n '1{N;s/\n/ /;p}' | grep -qx "issue create"; then
    echo "$f"; break
  fi
done | sed -E 's#.*call-([0-9]+)\.args#\1#')"
[[ -n "$create_call" ]] || fail "could not find the 'issue create' call args file"
body="$(call_body "$create_call")"
[[ "$body" == *"pocketshell-nightly-rc-red-marker"* ]] || fail "issue body missing the marker token"
[[ "$body" == *"deadbeef"* ]] || fail "issue body missing the SHA"
[[ "$body" == *"Release Emulator Validation run: https://example/validation/1"* ]] || fail "issue body missing release-validation run URL"
[[ "$body" == *"Triggering Tests run: https://example/tests/1"* ]] || fail "issue body missing triggering Tests run URL"
pass "no existing tracking issue => creates one with marker + SHA + distinct provenance URLs"

# --- existing open issue found via marker search => comment, never a 2nd create ---
reset_sandbox_calls
write_fake_gh "42"
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" --repo owner/repo --run-url "https://example/validation/2" --trigger-run-url "https://example/tests/2" --sha cafef00d)"
[[ "$out" == *"Commented on existing tracking issue #42"* ]] || fail "expected a comment on #42, got: $out"
create_calls=0
for f in "$SANDBOX"/call-*.args; do
  if tr '\0' '\n' < "$f" | sed -n '1{N;s/\n/ /;p}' | grep -qx "issue create"; then
    create_calls=$((create_calls + 1))
  fi
done
[[ "$create_calls" == "0" ]] || fail "expected zero 'issue create' calls when an existing issue was found"
pass "existing open issue found via marker => comments, never creates a second issue"

# --- gh create failure surfaces loudly ---
reset_sandbox_calls
write_fake_gh "" 1
if PATH="$SANDBOX/bin:$PATH" "$TARGET" --repo owner/repo --run-url "https://example/validation/3" --trigger-run-url "https://example/tests/3" --sha aaaa1111 >/dev/null 2>&1; then
  fail "expected non-zero exit when 'gh issue create' fails"
fi
pass "a failed 'gh issue create' call surfaces as a non-zero exit (never swallowed)"

echo "ALL $pass_count CHECKS PASSED"
