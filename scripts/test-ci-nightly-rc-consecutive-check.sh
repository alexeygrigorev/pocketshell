#!/usr/bin/env bash
# Self-test for scripts/ci-nightly-rc-consecutive-check.sh (issue #2356).
#
# Stubs `gh api` on PATH (no network). Covers: the run immediately before
# the (excluded) current run also failed => CONSECUTIVE=true; the prior run
# succeeded => false (not a streak); a still-in-progress current run that
# already appears in the API listing is correctly EXCLUDED by id rather than
# being mistaken for "the prior run" (the exact race this script exists to
# avoid); no prior run at all => false (fail-open, no history to judge);
# `gh` missing/failing => CONSECUTIVE=false (fail-open, never a
# false-positive file).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-nightly-rc-consecutive-check.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/bin"

write_fake_gh_api() {
  local json="$1"
  cat > "$SANDBOX/bin/gh" <<FAKEGH
#!/usr/bin/env bash
if [[ "\$1" == "api" ]]; then
  cat <<'JSON'
$json
JSON
  exit 0
fi
exit 1
FAKEGH
  chmod +x "$SANDBOX/bin/gh"
}

get_field() {
  local out="$1" key="$2"
  printf '%s\n' "$out" | sed -n "s/^${key}=//p"
}

# --- the run immediately before the (excluded) current run also failed => true ---
write_fake_gh_api '{"workflow_runs":[
  {"id": 999, "created_at":"2026-08-27T02:00:00Z","conclusion":null},
  {"id": 998, "created_at":"2026-08-26T02:00:00Z","conclusion":"failure"},
  {"id": 997, "created_at":"2026-08-25T02:00:00Z","conclusion":"success"}
]}'
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" --repo owner/repo --exclude-run-id 999)"
[[ "$(get_field "$out" CONSECUTIVE)" == "true" ]] || fail "expected CONSECUTIVE=true (prior run also failure), got: $out"
pass "current run's known failure + prior run also 'failure' => CONSECUTIVE=true"

# --- the immediately-prior run succeeded => false, not a streak ---
write_fake_gh_api '{"workflow_runs":[
  {"id": 999, "created_at":"2026-08-27T02:00:00Z","conclusion":null},
  {"id": 998, "created_at":"2026-08-26T02:00:00Z","conclusion":"success"}
]}'
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" --repo owner/repo --exclude-run-id 999)"
[[ "$(get_field "$out" CONSECUTIVE)" == "false" ]] || fail "expected CONSECUTIVE=false (a single blip), got: $out"
pass "prior run succeeded => CONSECUTIVE=false"

# --- the current (still in-progress) run already appears in the API listing
#     with a NEWER created_at and null conclusion; it must be excluded by id
#     rather than mistaken for "the run before this one" ---
write_fake_gh_api '{"workflow_runs":[
  {"id": 999, "created_at":"2026-08-27T03:00:00Z","conclusion":null},
  {"id": 998, "created_at":"2026-08-26T02:00:00Z","conclusion":"failure"}
]}'
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" --repo owner/repo --exclude-run-id 999)"
[[ "$(get_field "$out" CONSECUTIVE)" == "true" ]] || fail "expected CONSECUTIVE=true after excluding the current (null-conclusion) run by id, got: $out"
pass "current in-progress run present in the listing is excluded BY ID, not by conclusion"

# --- no prior run at all => false, fail-open ---
write_fake_gh_api '{"workflow_runs":[
  {"id": 999, "created_at":"2026-08-27T02:00:00Z","conclusion":"failure"}
]}'
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" --repo owner/repo --exclude-run-id 999)"
[[ "$(get_field "$out" CONSECUTIVE)" == "false" ]] || fail "expected CONSECUTIVE=false with no prior run history, got: $out"
pass "no prior run (only the current, excluded) => CONSECUTIVE=false (fail-open)"

# --- gh missing entirely => false, fail-open ---
out="$("$TARGET" --repo owner/repo --exclude-run-id 999 --gh "definitely-not-a-real-binary-pocketshell-2356")"
[[ "$(get_field "$out" CONSECUTIVE)" == "false" ]] || fail "expected CONSECUTIVE=false when gh is missing, got: $out"
pass "gh binary missing => CONSECUTIVE=false (fail-open, never files on a broken query)"

echo "ALL $pass_count CHECKS PASSED"
