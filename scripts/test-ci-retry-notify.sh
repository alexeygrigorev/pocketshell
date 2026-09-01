#!/usr/bin/env bash
# Self-test for scripts/ci-retry-notify.sh (issue #2459).
#
# Stubs `gh` on PATH — no network, no real repo. Covers: `store` finds the
# marker-matched open issue and comments the raw signature keyed by run_id;
# `store` fails LOUDLY (non-zero) when no tracking issue can be found (a
# silent storage failure would blind the later `report` call); `report`
# re-finds the stored signature by run_id and correctly classifies both a
# clean retry (infra) and an identical-signature retry (regression), posting
# a comment citing both run URLs each time; `report` degrades gracefully
# (loud warning, still classifies as inconclusive, exits 0) when the tracking
# issue or the stored signature cannot be found.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-retry-notify.sh"
CLASSIFY="$SCRIPT_DIR/ci-retry-classify.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"
[[ -f "$CLASSIFY" ]] || fail "dependency not found: $CLASSIFY"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/bin"

STATE_DIR="$SANDBOX/gh-state"
mkdir -p "$STATE_DIR"

# $1 = issue number to report as found by marker search ("" = none found)
# Comments already "posted" accumulate under $STATE_DIR/comments-<n>.txt so
# a later `report` call's marker-search can be fed by an earlier `store`
# call's write, and assertions can inspect exactly what was posted.
#
# `gh issue view --json comments --jq EXPR` needs jq-like filtering: the
# target script calls `gh issue view N --repo R --json comments --jq EXPR`
# and expects `gh` itself to apply --jq. This fake emulates the ONE jq
# expression the target actually uses (`select(.body | contains("...")) |
# last | .body // empty`) directly in Python rather than depending on a
# system `jq` binary.
write_fake_gh_with_jq() {
  local existing="$1"
  cat > "$SANDBOX/bin/gh" <<FAKEGH
#!/usr/bin/env bash
state="$STATE_DIR"
if [[ "\$1 \$2" == "issue list" ]]; then
  echo '$existing'
  exit 0
fi
if [[ "\$1 \$2" == "issue view" ]]; then
  number="\$3"
  # Extract the --jq expression (always the last arg in this target's calls).
  jq_expr="\${@: -1}"
  file="\$state/comments-\$number.txt"
  python3 - "\$file" "\$jq_expr" <<'PYEOF'
import sys
path, jq_expr = sys.argv[1], sys.argv[2]
try:
    text = open(path, "r", encoding="utf-8").read()
    bodies = [b for b in text.split("\x00") if b]
except FileNotFoundError:
    bodies = []
needle = None
if "contains(\"" in jq_expr:
    needle = jq_expr.split("contains(\"", 1)[1].split("\")", 1)[0]
matches = [b for b in bodies if (needle is None or needle in b)]
print(matches[-1] if matches else "")
PYEOF
  exit 0
fi
if [[ "\$1 \$2" == "issue comment" ]]; then
  number="\$3"
  body="\${@: -1}"
  printf '%s\x00' "\$body" >> "\$state/comments-\$number.txt"
  echo "commented on #\$number"
  exit 0
fi
echo "unhandled fake gh invocation: \$*" >&2
exit 1
FAKEGH
  chmod +x "$SANDBOX/bin/gh"
}

reset_state() { rm -rf "$STATE_DIR"; mkdir -p "$STATE_DIR"; }

sig_clean="$SANDBOX/sig-orig.txt"
printf 'job:Unit tests\nclass:com.foo.BarTest#baz\n# status=ok\n' > "$sig_clean"
sig_same="$SANDBOX/sig-same.txt"
printf 'job:Unit tests\nclass:com.foo.BarTest#baz\n# status=ok\n' > "$sig_same"

# -----------------------------------------------------------------------
# 1. store: no open tracking issue found -> loud non-zero failure (never
#    silently blind the later report call).
reset_state
write_fake_gh_with_jq ""
set +e
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" store --repo owner/repo --marker MARK --run-id 99 --signature-file "$sig_clean" 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 ]] || fail "store with no tracking issue found must fail loudly: $out"
echo "$out" | grep -qi "cannot store" || fail "store failure must say why: $out"
pass "store fails loudly when no marker-matched tracking issue exists"

# -----------------------------------------------------------------------
# 2. store: issue #42 found -> comments the signature, tagged with run_id.
reset_state
write_fake_gh_with_jq "42"
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" store --repo owner/repo --marker MARK --run-id 99 --signature-file "$sig_clean" 2>&1)"
echo "$out" | grep -q "Stored retry signature for run_id 99 on tracking issue #42" || fail "store must confirm the stored issue number: $out"
grep -q "run_id=99" "$STATE_DIR/comments-42.txt" || fail "stored comment must be tagged with run_id=99"
grep -q "job:Unit tests" "$STATE_DIR/comments-42.txt" || fail "stored comment must carry the raw signature"
pass "store finds the marker-matched issue and comments the run_id-tagged signature"

# -----------------------------------------------------------------------
# 3. report (clean retry): after the store above, a clean retry classifies
#    infra, posts a comment citing both run URLs.
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" report --repo owner/repo --marker MARK --run-id 99 \
  --original-run-url "https://x/runs/1/attempts/1" \
  --retry-run-url "https://x/runs/1/attempts/2" \
  --retry-conclusion success \
  --classify-script "$CLASSIFY" 2>&1)"
echo "$out" | grep -q "CLASSIFICATION=infra" || fail "clean retry must classify infra: $out"
echo "$out" | grep -q "Posted bounded-retry verdict (infra) on tracking issue #42" || fail "must post the verdict comment: $out"
grep -q "https://x/runs/1/attempts/1" "$STATE_DIR/comments-42.txt" || fail "verdict comment must cite the original run URL"
grep -q "https://x/runs/1/attempts/2" "$STATE_DIR/comments-42.txt" || fail "verdict comment must cite the retry run URL"
grep -qi "not extending" "$STATE_DIR/comments-42.txt" || fail "an infra verdict must say it is not extending/triggering a freeze"
pass "report classifies a clean retry as infra and cites both run URLs on the tracking issue"

# -----------------------------------------------------------------------
# 4. report (same-signature retry): a fresh store + report round for a
#    DIFFERENT run_id classifies regression.
reset_state
write_fake_gh_with_jq "77"
PATH="$SANDBOX/bin:$PATH" "$TARGET" store --repo owner/repo --marker MARK --run-id 200 --signature-file "$sig_clean" >/dev/null
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" report --repo owner/repo --marker MARK --run-id 200 \
  --original-run-url "https://x/runs/2/attempts/1" \
  --retry-run-url "https://x/runs/2/attempts/2" \
  --retry-conclusion failure \
  --retry-signature-file "$sig_same" \
  --classify-script "$CLASSIFY" 2>&1)"
echo "$out" | grep -q "CLASSIFICATION=regression" || fail "identical-signature retry must classify regression: $out"
echo "$out" | grep -q "Posted bounded-retry verdict (regression) on tracking issue #77" || fail "must post the regression verdict: $out"
grep -qi "REGRESSION CONFIRMED" "$STATE_DIR/comments-77.txt" || fail "regression verdict must be clearly flagged"
grep -q "https://x/runs/2/attempts/1" "$STATE_DIR/comments-77.txt" || fail "regression verdict must cite the original run URL"
grep -q "https://x/runs/2/attempts/2" "$STATE_DIR/comments-77.txt" || fail "regression verdict must cite the retry run URL"
pass "report classifies an identical-signature retry as regression and cites both run URLs as confirming evidence"

# -----------------------------------------------------------------------
# 5. report: tracking issue vanished (no marker match) -> loud warning,
#    still classifies (inconclusive, since there's no stored signature to
#    compare against), exits 0 rather than crashing the retry-completion job.
reset_state
write_fake_gh_with_jq ""
set +e
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" report --repo owner/repo --marker MARK --run-id 300 \
  --original-run-url "https://x/runs/3/attempts/1" \
  --retry-run-url "https://x/runs/3/attempts/2" \
  --retry-conclusion failure \
  --retry-signature-file "$sig_same" \
  --classify-script "$CLASSIFY" 2>&1)"
rc=$?
set -e
[[ "$rc" -eq 0 ]] || fail "report must not crash the job when the tracking issue is missing: $out (rc=$rc)"
echo "$out" | grep -qi "WARNING.*no open tracking issue" || fail "report must warn loudly when the tracking issue is missing: $out"
echo "$out" | grep -q "CLASSIFICATION=inconclusive" || fail "with no stored signature to compare, must classify inconclusive, not a guess: $out"
pass "report degrades gracefully (loud warning, inconclusive, exit 0) when the tracking issue cannot be found"

echo "OK: $pass_count self-test case(s) passed."
