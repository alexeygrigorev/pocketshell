#!/usr/bin/env bash
# Issue #1800: deterministic, JVM-free, emulator-free fixture test for the
# emulator-journey failure-SIGNATURE classifier and the two aggregate outcomes
# it can produce.
#
# The load-bearing property: a shard whose ONLY failure is the CI swiftshader
# AVD's inability to raise a real system input-method window must aggregate to
# RE-RUN (neutral green), while a shard carrying ANY genuine journey assertion
# failure — including a containment/anchor failure in the SAME class, in the
# same run — must still aggregate to RED. Both outcomes are DEMONSTRATED here by
# driving the real classifier and then the real aggregate reducer, not argued.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SIGNATURE="$SCRIPT_DIR/ci-journey-infra-signature.sh"
SHARD_VERDICT="$SCRIPT_DIR/ci-journey-shard-signature-verdict.sh"
ELAPSED="$SCRIPT_DIR/ci-journey-suite-elapsed.sh"
RETRY_BUDGET="$SCRIPT_DIR/ci-journey-retry-budget.sh"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"
WORKFLOW="${CI_JOURNEY_INFRA_SIGNATURE_WORKFLOW:-$REPO_ROOT/.github/workflows/tests.yml}"
ANDROID_TEST="$REPO_ROOT/app/src/androidTest/java/com/pocketshell/app/composer/PromptComposerSaturatedImeAnchorE2eTest.kt"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$SIGNATURE" "$SHARD_VERDICT" "$ELAPSED" "$RETRY_BUDGET" "$AGG" "$WORKFLOW"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

REAL_IME_MESSAGE='java.lang.AssertionError: The real system input-method window never became visible.'
CONTAINMENT_MESSAGE="java.lang.AssertionError: Node 'prompt-composer-send-enter' must stay above the same-root keyboard boundary. node=Rect.fromLTRB(0.0, 1500.0, 1080.0, 1654.0) keyboardTopPx=1626.0"
SATURATED_CLASS="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"

# Issue #1822 — the two genuinely-failing, METHOD-SCOPED entries that ran on
# shard 1 of run 30334306297 alongside the real-IME precondition, and the exact
# Compose timeout each carried. The suite registers both of these classes at
# `Class#method` granularity, which is what the pre-#1822 bullet pattern could
# not read.
OCCLUSION_CLASS="com.pocketshell.app.tmux.TmuxShellComposerOcclusionE2eTest"
OCCLUSION_METHOD="shellComposerControlsAreVisibleAndReachableInBothKeyboardStates"
FILEVIEWER_CLASS="com.pocketshell.app.fileviewer.FileViewerDockerTest"
FILEVIEWER_METHOD="moduleOneArticleListsRenderIntactAndContinuedLinkOpensExactUrl"
COMPOSE_TIMEOUT_MESSAGE="androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 15000 ms"

# ---------------------------------------------------------------------------
# Fixture builders: a realistic suite summary + realistic connected-test JUnit
# XML under the exact artifact layout the suite writes
# (artifacts/ci-journey/class-attempts/<module>/<key>/attempt-N/...).
# ---------------------------------------------------------------------------

# write_summary <root> <elapsed-secs> <failed-entry> [<failed-entry> ...]
#   A <failed-entry> is written verbatim inside backticks, so it may be either a
#   bare FQCN (`com.example.Foo`) or the method-scoped form the suite writes for
#   the classes it registers at method granularity (`com.example.Foo#someMethod`).
write_summary() {
  local root="$1" elapsed="$2"; shift 2
  mkdir -p "$root/ci-journey"
  {
    echo "# Per-push CI journey suite — summary"
    echo
    echo "| Selection | Args | Exit | Elapsed | Result |"
    echo "| --- | --- | --- | --- | --- |"
    echo "| 48 load-bearing journey classes (shard 0/3; per-class retry-once) | \`pocketshellCi=true\` | 1 | ${elapsed}s | **FAIL** |"
    echo
    echo "Classes exercised:"
    echo "- \`com.pocketshell.app.proof.SomeOtherJourneyE2eTest\`"
    echo "- \`$SATURATED_CLASS\`"
    if (( $# > 0 )); then
      echo
      echo "Failed BOTH attempts (\`JOURNEY_FAILED\` — job red):"
      local c
      for c in "$@"; do
        echo "- \`$c\`"
      done
    fi
  } > "$root/ci-journey/summary.md"
}

# append_summary_line <root> <raw-line> — append a verbatim line to the end of
# the summary. The failed-BOTH section is the LAST section the suite writes, so
# this lands inside it. Used to model an entry the parser cannot read (#1822).
append_summary_line() {
  local root="$1" line="$2"
  printf '%s\n' "$line" >> "$root/ci-journey/summary.md"
}

# write_case_xml <root> <class> <attempt> <method>:<outcome> ...
#   outcome: pass | realime | containment | error
write_case_xml() {
  local root="$1" class="$2" attempt="$3"; shift 3
  local key="${class##*.}"
  local dir="$root/ci-journey/class-attempts/app/$key/attempt-$attempt/android-test-outputs/androidTest-results/connected"
  mkdir -p "$dir"
  local file="$dir/TEST-emulator-5554-15_$key-$attempt.xml"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuite name=\"$class\" tests=\"$#\">"
    local spec method outcome
    for spec in "$@"; do
      method="${spec%%:*}"
      outcome="${spec##*:}"
      echo "  <testcase name=\"$method\" classname=\"$class[emulator-5554 - 15]\">"
      case "$outcome" in
        pass) ;;
        realime)
          echo "    <failure message=\"$REAL_IME_MESSAGE\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:814)</failure>"
          ;;
        containment)
          echo "    <failure message=\"$CONTAINMENT_MESSAGE\">at org.junit.Assert.fail(Assert.java:89)</failure>"
          ;;
        composetimeout)
          # Issue #1822: the exact shape of the two failures that were laundered
          # into a green — an ordinary Compose await timeout, no IME wording.
          echo "    <failure message=\"$COMPOSE_TIMEOUT_MESSAGE\">at androidx.compose.ui.test.ComposeUiTest.waitUntil(ComposeUiTest.kt:100)"
          echo "at com.pocketshell.app.tmux.TmuxShellComposerOcclusionE2eTest.shellComposerControlsAreVisibleAndReachableInBothKeyboardStates(TmuxShellComposerOcclusionE2eTest.kt:363)</failure>"
          ;;
        error)
          echo "    <error message=\"java.lang.IllegalStateException: harness exploded\">stack</error>"
          ;;
        *) fail "unknown fixture outcome: $outcome" ;;
      esac
      echo '  </testcase>'
    done
    echo '</testsuite>'
  } > "$file"
}

# run_signature <summary> <root> [<root>...] -> SIG_CLASS / SIG_OUT
run_signature() {
  local summary="$1"; shift
  SIG_OUT="$(bash "$SIGNATURE" "$summary" "$@" 2>/dev/null)"
  SIG_RC=$?
  SIG_CLASS="$(sed -n 's/^journey_failure_classification=//p' <<<"$SIG_OUT" | tail -n 1)"
}

# shard_verdict_for <artifacts-dir> — drive the REAL decision script the
# workflow's classify step calls, over the REAL canonical artifact layout, and
# map its verdict to the shard token that step writes. No replicated logic: the
# script is the same one tests.yml invokes (pinned by grep further down).
SHARD_VERDICT_OUT=""
SHARD_TOKEN=""
shard_verdict_for() {
  local artifacts="$1"
  SHARD_VERDICT_OUT="$(bash "$SHARD_VERDICT" "$artifacts" 2>/dev/null)"
  if [[ "$(sed -n 's/^shard_signature_verdict=//p' <<<"$SHARD_VERDICT_OUT" | tail -n 1)" == "INFRA" ]]; then
    SHARD_TOKEN="INFRA"
  else
    SHARD_TOKEN="RED"
  fi
}

write_shard_token() {
  local dir="$1" idx="$2" token="$3"
  mkdir -p "$dir/emulator-journey-verdict-shard-$idx"
  printf '%s\n' "$token" > "$dir/emulator-journey-verdict-shard-$idx/shard-verdict.txt"
}

run_agg() {
  local dir="$1"
  AGG_OUT="$(EXPECTED_SHARDS=3 GITHUB_STEP_SUMMARY="" bash "$AGG" "$dir" 2>&1)"
  AGG_RC=$?
  AGG_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$AGG_OUT" | tail -n 1)"
}

echo "== #1800 signature classifier =="

# (a) The captured CI signature alone -> real_ime_precondition.
root="$SANDBOX/a"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedQueuedDraftExpandsAboveImeThenReturnsWithoutDismissalOrLoss:pass" \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:pass" \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "real_ime_precondition" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(a) expected real_ime_precondition, got '$SIG_CLASS'"; }
grep -q '^journey_failing_testcases=2$' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(a) must attribute both failing attempts"; }
grep -q '^journey_signature_matches=2$' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(a) both failures must match the captured signature"; }
pass "(a) only the real-IME precondition -> real_ime_precondition"

# (b) A containment/anchor failure -> product_failure. This is the assertion the
#     signature must NEVER swallow.
root="$SANDBOX/b"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(b) a containment failure must be product_failure, got '$SIG_CLASS'"; }
pass "(b) a containment/anchor assertion failure -> product_failure (stays RED)"

# (c) MIXED in the same class, same run: the real-IME precondition AND a
#     containment failure. The narrow signature must not swallow the real one.
root="$SANDBOX/c"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime" \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(c) mixed evidence must be product_failure, got '$SIG_CLASS'"; }
grep -q 'journey_offending_failures=.*WithSyntheticIme' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(c) the offending containment failure must be named"; }
pass "(c) real-IME precondition MIXED with a containment failure -> product_failure"

# (d) A DIFFERENT class failing alongside -> product_failure (the signature is
#     per-run, not per-class: any other failed-both class keeps the shard red).
root="$SANDBOX/d"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS" "com.pocketshell.app.proof.SomeOtherJourneyE2eTest"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "com.pocketshell.app.proof.SomeOtherJourneyE2eTest" 1 \
  "someJourney:containment"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(d) a second failing class must be product_failure, got '$SIG_CLASS'"; }
pass "(d) a second genuinely failing class -> product_failure"

# (e) An earlier attempt's containment failure in the SAME class (recovered or
#     not) still forces product_failure — the classifier reads every attempt.
root="$SANDBOX/e"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(e) an earlier-attempt containment failure must be product_failure"; }
pass "(e) a containment failure in ANY preserved attempt -> product_failure"

# (f) Missing / unreadable evidence -> unclassified (fail-safe: stays RED).
root="$SANDBOX/f"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "unclassified" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(f) missing result XML must be unclassified, got '$SIG_CLASS'"; }

root="$SANDBOX/f2"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
corrupt="$(find "$root" -name 'TEST-*.xml' | head -n 1)"
printf '<testsuite><testcase' > "$corrupt"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" != "real_ime_precondition" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(f) a corrupt result file must never yield real_ime_precondition"; }
pass "(f) missing or corrupt evidence never downgrades the shard (stays RED)"

# (g) A clean run (no failed-both section) -> unclassified; the branch can never
#     fire on a green shard.
root="$SANDBOX/g"; mkdir -p "$root"
write_summary "$root" 2338
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "unclassified" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(g) a summary with no failed-both section must be unclassified"; }
pass "(g) no failed-both section -> unclassified"

# (h) An error (not failure) element is also a genuine failure -> product_failure.
root="$SANDBOX/h"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:error"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(h) an <error> element must be product_failure, got '$SIG_CLASS'"; }
pass "(h) a harness <error> element -> product_failure"

echo
echo "== #1822 method-scoped bullets and fail-safe-toward-RED parsing =="

# (p) A method-scoped bullet is READ. Before #1822 the bullet pattern's
#     `[A-Za-z_][\w.$]*` class did not contain `#`, so a `Class#method` entry
#     matched nothing at all and the class was never collected.
root="$SANDBOX/p"; mkdir -p "$root"
write_summary "$root" 2870 "$OCCLUSION_CLASS#$OCCLUSION_METHOD"
write_case_xml "$root" "$OCCLUSION_CLASS" 1 "$OCCLUSION_METHOD:composetimeout"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
grep -q "^journey_failed_classes=$OCCLUSION_CLASS\$" <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(p) a \`Class#method\` bullet must be collected as its class"; }
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(p) a method-scoped genuine failure must be product_failure, got '$SIG_CLASS'"; }
pass "(p) a \`Class#method\` failed-both bullet is parsed and its failure is seen"

# (p2) The pre-existing bare-class bullet form, and the suite's core-terminal
#      bullets that carry trailing prose after the closing backtick, must keep
#      parsing exactly as before — widening the pattern may not narrow it.
root="$SANDBOX/p2"; mkdir -p "$root"
write_summary "$root" 2870 "$SATURATED_CLASS"
append_summary_line "$root" "- \`com.pocketshell.core.terminal.ui.CodexAppendBurstMainThreadProofTest\` (#803 append-burst proof)"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "com.pocketshell.core.terminal.ui.CodexAppendBurstMainThreadProofTest" 1 \
  "appendBurst:containment"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
grep -q 'journey_failed_classes=.*CodexAppendBurstMainThreadProofTest' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(p2) a bullet with trailing prose after the backtick must still parse"; }
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(p2) expected product_failure, got '$SIG_CLASS'"; }
pass "(p2) bare-class and trailing-prose bullets keep parsing (the widening did not narrow)"

# (q) THE LOAD-BEARING #1822 CASE — the exact shape of run 30334306297 shard 1.
#     A summary carrying ONE genuine INFRA signature FOLLOWED BY method-scoped
#     genuine product failures must classify RED (product_failure).
#
#     On the base classifier this returns `real_ime_precondition`: the first
#     method-scoped bullet failed to match AND ended the section, so
#     `journey_failed_classes` collapsed to the IME class alone, every failure
#     attributed to it carried the signature, and matches == failing -> INFRA ->
#     shard green -> aggregate RE-RUN -> run reported success. Two real failures
#     laundered into a green.
root="$SANDBOX/q"; mkdir -p "$root"
write_summary "$root" 2870 \
  "$SATURATED_CLASS" \
  "$OCCLUSION_CLASS#$OCCLUSION_METHOD" \
  "$FILEVIEWER_CLASS#$FILEVIEWER_METHOD"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "$OCCLUSION_CLASS" 1 "$OCCLUSION_METHOD:composetimeout"
write_case_xml "$root" "$OCCLUSION_CLASS" 2 "$OCCLUSION_METHOD:composetimeout"
write_case_xml "$root" "$FILEVIEWER_CLASS" 1 "$FILEVIEWER_METHOD:composetimeout"
write_case_xml "$root" "$FILEVIEWER_CLASS" 2 "$FILEVIEWER_METHOD:composetimeout"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
printf '%s\n' "$SIG_OUT" | sed 's/^/    /'
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(q) one INFRA signature + genuine method-scoped failures must be product_failure, got '$SIG_CLASS'"; }
for expected in "$SATURATED_CLASS" "$OCCLUSION_CLASS" "$FILEVIEWER_CLASS"; do
  grep -q "journey_failed_classes=.*$expected" <<<"$SIG_OUT" \
    || { printf '%s\n' "$SIG_OUT"; fail "(q) every failed-both entry must be collected; missing $expected"; }
done
grep -q "journey_offending_failures=.*$OCCLUSION_METHOD" <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(q) the genuine occlusion failure must be named as an offender"; }
grep -q "journey_offending_failures=.*$FILEVIEWER_METHOD" <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(q) the genuine file-viewer failure must be named as an offender"; }
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
printf '%s\n' "$SHARD_VERDICT_OUT" | sed 's/^/    decision: /'
[[ "$SHARD_TOKEN" == "RED" ]] \
  || fail "(q) the mixed shard must be typed RED, got $SHARD_TOKEN — a genuine failure was masked as INFRA"
pass "(q) MIXED summary (INFRA signature + genuine \`Class#method\` failures) -> product_failure -> shard RED"

# (q') The load-bearing control for (q) (G6). Remove ONLY the two genuine
#      method-scoped entries and the same shard drops to INFRA — proving it was
#      those entries, not the fixture shape, that drove the RED.
root="$SANDBOX/q-control"; mkdir -p "$root"
write_summary "$root" 2870 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
[[ "$SHARD_TOKEN" == "INFRA" ]] \
  || fail "(q') dropping the genuine entries must yield INFRA, got $SHARD_TOKEN"
pass "(q') control: the genuine \`Class#method\` entries are what turn the shard RED"

# (q2) ORDER INDEPENDENCE. The base defect was positional — the first
#      unreadable bullet truncated everything after it, so a run whose genuine
#      failure came FIRST was still (accidentally) caught. Both orders must be
#      RED now, so no future entry ordering can resurrect the mask.
root="$SANDBOX/q2"; mkdir -p "$root"
write_summary "$root" 2870 \
  "$OCCLUSION_CLASS#$OCCLUSION_METHOD" \
  "$SATURATED_CLASS"
write_case_xml "$root" "$OCCLUSION_CLASS" 1 "$OCCLUSION_METHOD:composetimeout"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(q2) genuine-failure-first ordering must also be product_failure, got '$SIG_CLASS'"; }
pass "(q2) the verdict does not depend on where the genuine entry sits in the list"

# (r) AN UNPARSEABLE IN-SECTION BULLET FAILS SAFE TO RED. A list item inside the
#     failed-both section that the parser cannot read is MISSING EVIDENCE, not a
#     section terminator: it must yield `unclassified` (RED), never let the
#     surviving entries decide the shard is environmental.
root="$SANDBOX/r"; mkdir -p "$root"
write_summary "$root" 2870 "$SATURATED_CLASS"
append_summary_line "$root" '- `9NotAnIdentifier#weird` (an entry this parser cannot read)'
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
printf '%s\n' "$SIG_OUT" | sed 's/^/    /'
[[ "$SIG_CLASS" == "unclassified" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(r) an unreadable in-section bullet must be unclassified, got '$SIG_CLASS'"; }
grep -q 'journey_offending_failures=.*unreadable-summary-entry' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(r) the unreadable entry must be reported, not silently swallowed"; }
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
[[ "$SHARD_TOKEN" == "RED" ]] \
  || fail "(r) an unreadable in-section bullet must keep the shard RED, got $SHARD_TOKEN"
pass "(r) an unparseable in-section bullet -> unclassified -> shard RED (fail-safe, never green)"

# (r2) Prose bullets are unreadable too — the fail-safe is not keyed on one
#      malformed FQCN shape.
root="$SANDBOX/r2"; mkdir -p "$root"
write_summary "$root" 2870 "$SATURATED_CLASS"
append_summary_line "$root" '- (none individually bucketed — budget spent during summary phase)'
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "unclassified" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(r2) a prose bullet in the section must be unclassified, got '$SIG_CLASS'"; }
pass "(r2) any unreadable list item in the section refuses classification"

# (s) A listed class with NO failing test case in the preserved evidence is
#     missing data. The remaining classes must not be allowed to decide the
#     shard is environmental — that is the same masking bug wearing a different
#     hat (the evidence is absent rather than the bullet unreadable).
root="$SANDBOX/s"; mkdir -p "$root"
write_summary "$root" 2870 "$SATURATED_CLASS" "$OCCLUSION_CLASS#$OCCLUSION_METHOD"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "unclassified" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(s) a failed-both class with no evidence must be unclassified, got '$SIG_CLASS'"; }
grep -q 'journey_offending_failures=.*no-failing-testcase-in-evidence' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(s) the class with no evidence must be named"; }
pass "(s) a failed-both class with no failing evidence -> unclassified (stays RED)"

echo
echo "== #1800 end-to-end aggregate outcomes (observed, not argued) =="

# (i) A run whose ONLY shard failure is the captured signature -> RE-RUN.
#     Built in the REAL canonical layout, including the preserved first-attempt
#     snapshot the workflow writes, and decided by the REAL decision script.
root="$SANDBOX/i"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
token="$SHARD_TOKEN"
verdicts="$SANDBOX/verdicts-i"; rm -rf "$verdicts"; mkdir -p "$verdicts"
write_shard_token "$verdicts" 0 "$token"
write_shard_token "$verdicts" 1 CLEAN
write_shard_token "$verdicts" 2 CLEAN
run_agg "$verdicts"
printf '%s\n' "$SHARD_VERDICT_OUT" | sed 's/^/    decision: /'
echo "    -> shard token $token"
printf '%s\n' "$AGG_OUT" | sed 's/^/    /'
[[ "$token" == "INFRA" ]] || fail "(i) the signature-only shard must be typed INFRA, got $token"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || fail "(i) expected RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"
grep -q '::error' <<<"$AGG_OUT" \
  && fail "(i) a signature-only run must not emit ::error (no false red-CI email)"
pass "(i) signature-only shard failure -> INFRA -> aggregate RE-RUN (exit 0)"

# (j) A genuine journey assertion failure still -> RED. Same shape as (i), one
#     containment assertion instead of the precondition.
root="$SANDBOX/j"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
token="$SHARD_TOKEN"
verdicts="$SANDBOX/verdicts-j"; rm -rf "$verdicts"; mkdir -p "$verdicts"
write_shard_token "$verdicts" 0 "$token"
write_shard_token "$verdicts" 1 CLEAN
write_shard_token "$verdicts" 2 CLEAN
run_agg "$verdicts"
printf '%s\n' "$SHARD_VERDICT_OUT" | sed 's/^/    decision: /'
echo "    -> shard token $token"
printf '%s\n' "$AGG_OUT" | sed 's/^/    /'
[[ "$token" == "RED" ]] || fail "(j) a genuine assertion failure must stay RED, got $token"
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || fail "(j) expected RED/exit1, got $AGG_VERDICT/exit$AGG_RC"
pass "(j) genuine journey assertion failure -> RED -> aggregate RED (exit 1)"

# (j2) The preserved FIRST attempt carries a genuine containment failure while
#      the final attempt's summary is signature-only. Both snapshots are
#      inspected, so the shard must still be RED.
root="$SANDBOX/j2"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
write_case_xml "$root/ci-journey-attempt-1" "$SATURATED_CLASS" 9 \
  "saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide:containment"
shard_verdict_for "$root"
token="$SHARD_TOKEN"
printf '%s\n' "$SHARD_VERDICT_OUT" | sed 's/^/    decision: /'
[[ "$token" == "RED" ]] \
  || fail "(j2) a containment failure in the preserved first attempt must stay RED, got $token"
pass "(j2) a genuine failure in EITHER preserved attempt keeps the shard RED"

echo
echo "== #1800 measured cold-boot retry budget =="

# The observed shard-0 fixture from run 30305256109: job start 21:32:27.657,
# first attempt start 21:35:45.145, suite elapsed 2338s, 3112241ms remaining.
job_start=1785187947657
remaining=3112241
now=$((job_start + 5700000 - remaining))
attempt_start=1785188145145
suite_secs=2338

legacy_out="$(bash "$RETRY_BUDGET" "$job_start" "$now")"
grep -q '^retry_allowed=false$' <<<"$legacy_out" \
  || { printf '%s\n' "$legacy_out"; fail "(k) the flat worst case must still deny the shard-0 retry"; }
grep -q '^retry_required_ms=5400000$' <<<"$legacy_out" \
  || { printf '%s\n' "$legacy_out"; fail "(k) the unmeasured fallback must stay the flat 5400000ms"; }
grep -q '^retry_cost_model=worst_case$' <<<"$legacy_out" \
  || { printf '%s\n' "$legacy_out"; fail "(k) the unmeasured fallback must report worst_case"; }
printf '%s\n' "$legacy_out" | sed 's/^/    unmeasured: /'
pass "(k) without measurements the requirement is the unchanged flat 5400000ms (deny)"

measured_out="$(bash "$RETRY_BUDGET" "$job_start" "$now" "$attempt_start" "$suite_secs")"
printf '%s\n' "$measured_out" | sed 's/^/    measured:   /'
grep -q '^retry_allowed=true$' <<<"$measured_out" \
  || { printf '%s\n' "$measured_out"; fail "(k) shard-0's measured cost must PERMIT the retry at ${remaining}ms"; }
grep -q '^retry_cost_model=measured_first_attempt$' <<<"$measured_out" \
  || { printf '%s\n' "$measured_out"; fail "(k) the measured path must report measured_first_attempt"; }
measured_required="$(sed -n 's/^retry_required_ms=//p' <<<"$measured_out" | tail -n 1)"
[[ "$measured_required" =~ ^[0-9]+$ ]] || fail "(k) measured requirement is not numeric"
(( measured_required <= remaining )) \
  || fail "(k) measured requirement ${measured_required}ms must fit in ${remaining}ms"
(( measured_required < 5400000 )) \
  || fail "(k) measured requirement must be below the flat worst case"
pass "(k) shard-0 (remaining=${remaining}ms) now requires ${measured_required}ms -> retry PERMITTED"

# The measured model may only ever RELAX. A pathologically slow first attempt
# must never demand more than the legacy flat reserve.
slow_out="$(bash "$RETRY_BUDGET" "$job_start" "$now" "$((now - 9000000))" 8000)"
slow_required="$(sed -n 's/^retry_required_ms=//p' <<<"$slow_out" | tail -n 1)"
[[ "$slow_required" == "5400000" ]] \
  || { printf '%s\n' "$slow_out"; fail "(l) a slow first attempt must clamp to the flat 5400000ms, got $slow_required"; }
pass "(l) the measured model never demands MORE than the flat worst case"

# Malformed measurements fall back to the flat worst case, never to a smaller
# requirement derived from garbage.
for bad in "not-an-epoch 2338" "$attempt_start not-a-number" "$attempt_start 0" "0 2338" "$((now + 1000)) 2338"; do
  # shellcheck disable=SC2086 # deliberate word splitting of the fixture pair
  bad_out="$(bash "$RETRY_BUDGET" "$job_start" "$now" $bad)"
  grep -q '^retry_required_ms=5400000$' <<<"$bad_out" \
    || { printf '%s\n' "$bad_out"; fail "(m) malformed measurement '$bad' must fall back to the flat reserve"; }
  grep -q '^retry_cost_model=worst_case$' <<<"$bad_out" \
    || { printf '%s\n' "$bad_out"; fail "(m) malformed measurement '$bad' must report worst_case"; }
done
pass "(m) malformed/absent measurements fall back to the flat worst case"

# The measured elapsed comes from the suite's own summary table.
root="$SANDBOX/elapsed"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
parsed="$(bash "$ELAPSED" "$root/ci-journey/summary.md")" \
  || fail "(n) could not read the measured elapsed from a real-shaped summary"
[[ "$parsed" == "2338" ]] || fail "(n) measured elapsed parsed as '$parsed', expected 2338"
bash "$ELAPSED" "$SANDBOX/does-not-exist.md" >/dev/null 2>&1 \
  && fail "(n) a missing summary must fail so the caller keeps the flat reserve"
printf 'no table here\n' > "$SANDBOX/no-table.md"
bash "$ELAPSED" "$SANDBOX/no-table.md" >/dev/null 2>&1 \
  && fail "(n) a summary without the result table must fail rather than guess"
pass "(n) the measured elapsed is read from the suite's own summary table"

echo
echo "== #1800 real-workflow wiring =="

grep -q "scripts/ci-journey-shard-signature-verdict.sh artifacts" "$WORKFLOW" \
  || fail "the classify step does not call the real shard signature decision script"
grep -q 'JOURNEY_FIRST_ATTEMPT_START_EPOCH_MS=' "$WORKFLOW" \
  || fail "the workflow does not record the first journey attempt start epoch"
grep -q 'scripts/ci-journey-suite-elapsed.sh' "$WORKFLOW" \
  || fail "the workflow does not read the suite's measured elapsed"
grep -q 'first_suite_elapsed_secs' "$WORKFLOW" \
  || fail "the measured elapsed is not passed to the retry-budget helper"

signature_line="$(grep -n 'signature_verdict" == "INFRA"' "$WORKFLOW" | head -n 1 | cut -d: -f1)"
# shellcheck disable=SC2016 # literal workflow shell expressions, not test vars
first_failure_line="$(grep -Fn 'if [[ "${first_failure:-false}" == "true" ]]' "$WORKFLOW" | cut -d: -f1)"
retry_clean_line="$(grep -Fn 'if [[ "$retry" == "success" ]]' "$WORKFLOW" | cut -d: -f1)"
first_clean_line="$(grep -Fn 'if [[ "$first" == "success" ]]' "$WORKFLOW" | cut -d: -f1)"
for value in "$signature_line" "$first_failure_line" "$retry_clean_line" "$first_clean_line"; do
  [[ "$value" =~ ^[0-9]+$ ]] || fail "could not locate the classify-step branches in the workflow"
done
(( first_clean_line < signature_line && retry_clean_line < signature_line )) \
  || fail "the signature branch must not preempt a CLEAN shard"
(( signature_line < first_failure_line )) \
  || fail "the signature branch must be evaluated before the genuine-failure RED branch"
pass "classify-step branch order: CLEAN -> signature INFRA -> genuine-failure RED"

grep -q 'first_timeout:-false}" != "true"' "$WORKFLOW" \
  || fail "the signature branch does not exclude the #835 budget timeout"
pass "the signature branch can never fire for a #835 budget timeout"

echo
echo "== #1822 the REAL classify step run: body, executed end to end =="

# Greps prove the branches exist in the right ORDER; they cannot prove what the
# step actually WRITES. This harness (the #1809 review method) extracts the
# classify step's real `run:` block out of tests.yml, substitutes its `${{ }}`
# expressions from an explicit map, executes it over a real canonical artifact
# tree through the real helper scripts, and reads back the verdict token and the
# `$GITHUB_OUTPUT` the shard's RED gate keys on. An unmapped expression is a HARD
# failure, so a workflow edit cannot drift silently past this harness.

# classify_expressions <journey-outcome> — the step-output map for one scenario.
classify_expressions() {
  local journey_outcome="$1"
  cat <<JSONEOF
{
  "steps.journey.outcome": "$journey_outcome",
  "steps.journey_retry.outcome": "failure",
  "steps.journey.conclusion": "$journey_outcome",
  "steps.journey_retry.conclusion": "failure",
  "steps.journey_summary.outputs.first_timeout": "false",
  "steps.journey_summary.outputs.first_failure": "true",
  "steps.journey_retry_budget.outputs.retry_allowed": "true",
  "steps.journey_retry_budget.outputs.retry_reason": "sufficient_remaining_budget",
  "steps.journey_retry_budget.outputs.retry_remaining_ms": "3112241",
  "steps.journey_retry_budget.outputs.retry_required_ms": "3028613",
  "steps.journey_retry_budget.outputs.retry_cost_model": "measured_first_attempt",
  "steps.journey_retry_budget.outputs.retry_shortfall_ms": "0",
  "steps.journey_retry_budget.outputs.retry_warm_build_deducted_ms": "0"
}
JSONEOF
}

CLASSIFY_OUT=""
CLASSIFY_RC=0
CLASSIFY_TOKEN=""
CLASSIFY_GH_OUTPUT=""
# run_classify_body <sandbox> <expressions-json>
run_classify_body() {
  local sandbox="$1" expressions="$2"
  local body="$sandbox/classify-step-body.sh"
  local extract_err="$sandbox/extract-error.txt"
  CLASSIFY_EXPRESSIONS="$expressions" python3 - "$WORKFLOW" "$body" 2>"$extract_err" <<'PYEOF'
import json
import os
import re
import sys

workflow, out_path = sys.argv[1], sys.argv[2]
mapping = json.loads(os.environ["CLASSIFY_EXPRESSIONS"])
lines = open(workflow, encoding="utf-8").read().splitlines()

step_re = re.compile(r"^(\s*)- name: Classify emulator-journey result")
start = indent = None
for i, line in enumerate(lines):
    m = step_re.match(line)
    if m:
        start, indent = i, len(m.group(1))
        break
if start is None:
    sys.exit("could not find the classify step in %s" % workflow)

run_idx = None
for j in range(start + 1, len(lines)):
    line = lines[j]
    if line.strip() and (len(line) - len(line.lstrip())) <= indent:
        break
    if re.match(r"^\s*run: \|\s*$", line):
        run_idx = j
        break
if run_idx is None:
    sys.exit("the classify step has no `run: |` block")

run_indent = len(lines[run_idx]) - len(lines[run_idx].lstrip())
body = []
for j in range(run_idx + 1, len(lines)):
    line = lines[j]
    if not line.strip():
        body.append("")
        continue
    if (len(line) - len(line.lstrip())) <= run_indent:
        break
    body.append(line)
if not [line for line in body if line.strip()]:
    sys.exit("the classify step's run block is empty")

pad = min(len(line) - len(line.lstrip()) for line in body if line.strip())
text = "\n".join(line[pad:] if line.strip() else "" for line in body)

unknown = []


def substitute(match):
    expr = match.group(1).strip()
    if expr not in mapping:
        unknown.append(expr)
        return ""
    return mapping[expr]


text = re.sub(r"\$\{\{(.*?)\}\}", substitute, text)
if unknown:
    sys.exit("unmapped workflow expression(s): %s" % ", ".join(sorted(set(unknown))))

with open(out_path, "w", encoding="utf-8") as handle:
    handle.write(text + "\n")
PYEOF
  local extract_rc=$?
  (( extract_rc == 0 )) || {
    cat "$extract_err" >&2
    fail "could not extract+substitute the classify step's run body from $WORKFLOW. If the step gained a new \`\${{ }}\` expression, add it to classify_expressions() in this file — running the step body with an unknown expression silently blanked would make this harness lie."
  }

  ln -sfn "$REPO_ROOT/scripts" "$sandbox/scripts"
  : > "$sandbox/gh-output.txt"
  local token_file="$sandbox/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
  rm -f "$token_file"
  mkdir -p "$(dirname "$token_file")"
  # GitHub's default Linux `run:` shell is `bash --noprofile --norc -eo pipefail`.
  CLASSIFY_OUT="$(cd "$sandbox" && \
    SHARD_VERDICT_FILE="$token_file" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX=1 \
    GITHUB_RUN_ID=30334306297 \
    GITHUB_RUN_ATTEMPT=1 \
    GITHUB_OUTPUT="$sandbox/gh-output.txt" \
    bash --noprofile --norc -eo pipefail "$body" 2>&1)"
  CLASSIFY_RC=$?
  CLASSIFY_TOKEN="$(head -n 1 "$token_file" 2>/dev/null || true)"
  CLASSIFY_GH_OUTPUT="$(cat "$sandbox/gh-output.txt" 2>/dev/null || true)"
}

# build_shard_artifacts <sandbox> — a canonical two-attempt artifact tree.
#   The caller has already populated "$sandbox/artifacts/ci-journey".
snapshot_first_attempt() {
  local sandbox="$1"
  rm -rf "$sandbox/artifacts/ci-journey-attempt-1"
  mkdir -p "$sandbox/artifacts/ci-journey-attempt-1"
  cp -a "$sandbox/artifacts/ci-journey" "$sandbox/artifacts/ci-journey-attempt-1/ci-journey"
}

# (t) #1800 PRESERVED: a signature-only shard still writes INFRA, still exits
#     non-zero, still emits a ::warning and NEVER a ::error.
sandbox="$SANDBOX/wf-infra"; mkdir -p "$sandbox/artifacts"
write_summary "$sandbox/artifacts" 2338 "$SATURATED_CLASS"
write_case_xml "$sandbox/artifacts" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
write_case_xml "$sandbox/artifacts" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions failure)"
[[ "$CLASSIFY_TOKEN" == "INFRA" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(t) the real classify body must write INFRA for a signature-only shard, got '$CLASSIFY_TOKEN'"; }
grep -q '^shard_verdict=INFRA$' <<<"$CLASSIFY_GH_OUTPUT" \
  || { printf '%s\n' "$CLASSIFY_GH_OUTPUT"; fail "(t) the step must export shard_verdict=INFRA"; }
grep -q '::warning title=Emulator journey INFRA' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(t) the INFRA branch must annotate as a warning"; }
grep -q '::error' <<<"$CLASSIFY_OUT" \
  && { printf '%s\n' "$CLASSIFY_OUT"; fail "(t) a signature-only shard must not emit ::error"; }
infra_token_dir="$SANDBOX/wf-verdicts-infra"; rm -rf "$infra_token_dir"; mkdir -p "$infra_token_dir"
write_shard_token "$infra_token_dir" 0 CLEAN
write_shard_token "$infra_token_dir" 1 "$CLASSIFY_TOKEN"
write_shard_token "$infra_token_dir" 2 CLEAN
run_agg "$infra_token_dir"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(t) an all-infra run must still aggregate RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(t) #1800 preserved: signature-only -> real body writes INFRA -> aggregate RE-RUN (neutral green)"

# (u) THE #1822 UN-MASKING, through the real step body. The exact run
#     30334306297 shard-1 shape: the IME signature entry FOLLOWED BY two
#     method-scoped genuine Compose-timeout failures. On the base classifier the
#     body wrote INFRA (shard job green, aggregate RE-RUN, run reported
#     success). It must now write RED, emit ::error, export shard_verdict=RED
#     (which is what #1809's "Fail this shard on a genuine RED verdict" gate
#     keys on), and aggregate RED/exit1.
sandbox="$SANDBOX/wf-mixed"; mkdir -p "$sandbox/artifacts"
write_summary "$sandbox/artifacts" 2870 \
  "$SATURATED_CLASS" \
  "$OCCLUSION_CLASS#$OCCLUSION_METHOD" \
  "$FILEVIEWER_CLASS#$FILEVIEWER_METHOD"
for attempt in 1 2; do
  write_case_xml "$sandbox/artifacts" "$SATURATED_CLASS" "$attempt" \
    "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
  write_case_xml "$sandbox/artifacts" "$OCCLUSION_CLASS" "$attempt" "$OCCLUSION_METHOD:composetimeout"
  write_case_xml "$sandbox/artifacts" "$FILEVIEWER_CLASS" "$attempt" "$FILEVIEWER_METHOD:composetimeout"
done
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions failure)"
printf '%s\n' "$CLASSIFY_OUT" | sed 's/^/    body: /'
[[ "$CLASSIFY_TOKEN" == "RED" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(u) the real classify body must write RED for the mixed shard, got '$CLASSIFY_TOKEN' — two genuine failures would be laundered into a green"; }
[[ "$CLASSIFY_RC" -ne 0 ]] || fail "(u) a RED classify body must exit non-zero"
grep -q '^shard_verdict=RED$' <<<"$CLASSIFY_GH_OUTPUT" \
  || { printf '%s\n' "$CLASSIFY_GH_OUTPUT"; fail "(u) the step must export shard_verdict=RED so the #1809 shard RED gate fires"; }
grep -q '::error' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(u) a genuine failure must emit ::error"; }
grep -q '::warning title=Emulator journey INFRA' <<<"$CLASSIFY_OUT" \
  && { printf '%s\n' "$CLASSIFY_OUT"; fail "(u) the INFRA branch must NOT fire on the mixed shard"; }
mixed_token_dir="$SANDBOX/wf-verdicts-mixed"; rm -rf "$mixed_token_dir"; mkdir -p "$mixed_token_dir"
write_shard_token "$mixed_token_dir" 0 CLEAN
write_shard_token "$mixed_token_dir" 1 "$CLASSIFY_TOKEN"
write_shard_token "$mixed_token_dir" 2 CLEAN
run_agg "$mixed_token_dir"
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(u) the mixed shard must aggregate RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(u) #1822: mixed shard -> real body writes RED -> shard RED gate fires -> aggregate RED (exit 1)"

# (v) #1822: an unreadable in-section bullet also drives the real body to RED.
sandbox="$SANDBOX/wf-unreadable"; mkdir -p "$sandbox/artifacts"
write_summary "$sandbox/artifacts" 2870 "$SATURATED_CLASS"
append_summary_line "$sandbox/artifacts" '- `9NotAnIdentifier#weird` (an entry this parser cannot read)'
write_case_xml "$sandbox/artifacts" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions failure)"
[[ "$CLASSIFY_TOKEN" == "RED" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(v) an unreadable summary entry must drive the real body to RED, got '$CLASSIFY_TOKEN'"; }
pass "(v) #1822: an unreadable failed-both entry -> the real body writes RED"

# (w) #1800/#1458 PRESERVED: a run that passed on the first cold boot still
#     writes CLEAN and exits 0 through the same body.
sandbox="$SANDBOX/wf-clean"; mkdir -p "$sandbox/artifacts"
write_summary "$sandbox/artifacts" 2338
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions success)"
[[ "$CLASSIFY_TOKEN" == "CLEAN" && "$CLASSIFY_RC" -eq 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(w) a first-attempt success must write CLEAN/exit0, got '$CLASSIFY_TOKEN'/exit$CLASSIFY_RC"; }
clean_token_dir="$SANDBOX/wf-verdicts-clean"; rm -rf "$clean_token_dir"; mkdir -p "$clean_token_dir"
write_shard_token "$clean_token_dir" 0 CLEAN
write_shard_token "$clean_token_dir" 1 "$CLASSIFY_TOKEN"
write_shard_token "$clean_token_dir" 2 CLEAN
run_agg "$clean_token_dir"
[[ "$AGG_VERDICT" == "CLEAN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(w) an all-clean run must aggregate CLEAN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
# ...and a MISSING token still downgrades that same all-clean set to RE-RUN:
#    `missing == 0` remains required for CLEAN (#1458).
rm -rf "$clean_token_dir/emulator-journey-verdict-shard-2"
run_agg "$clean_token_dir"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(w) a missing shard token must downgrade CLEAN to RE-RUN, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(w) #1458/#1800 preserved: first-attempt success -> CLEAN -> aggregate CLEAN; a missing token still forces RE-RUN"

# (x) #1840, THROUGH THE REAL STEP BODY: a shard whose RETRY died at the GRADLE
#     BUILD level must stay RED (never softened) but must be DISTINGUISHABLE in
#     the verdict from a class that genuinely failed twice. On run 30339688411
#     shard 0 this exact cascade — a per-class timeout, the suite's own
#     `gradlew --stop`, then a retry whose `compileDebugAndroidTestKotlin` died
#     clearing `kotlin-classes/debugAndroidTest` — was typed
#     `journey_failure_both_attempts`, i.e. reported as a product defect.
#
# write_attempt_manifest <root> <class> <attempt> <key>=<value>...
write_attempt_manifest() {
  local root="$1" class="$2" attempt="$3"; shift 3
  local key="${class##*.}"
  local dir="$root/ci-journey/class-attempts/app/$key/attempt-$attempt"
  mkdir -p "$dir"
  {
    printf 'format_version=1\n'
    printf 'module=app\n'
    printf 'class=%s\n' "$class"
    printf 'attempt=%s\n' "$attempt"
    local pair
    for pair in "$@"; do
      printf '%s\n' "$pair"
    done
  } > "$dir/manifest.txt"
}

BUILD_FAIL_CLASS="com.pocketshell.app.proof.TmuxSessionScreenArtVerifyE2eTest"

sandbox="$SANDBOX/wf-build-failure"; mkdir -p "$sandbox/artifacts"
write_summary "$sandbox/artifacts" 2870 "$BUILD_FAIL_CLASS"
# Attempt 1 was cut by the wall cap while Gradle was still building; attempt 2's
# BUILD then failed outright, so instrumentation never started and neither
# attempt produced a journey verdict.
write_attempt_manifest "$sandbox/artifacts" "$BUILD_FAIL_CLASS" 1 \
  'primary_classification=outer_timeout' 'raw_junit_count=0' \
  'outer_timeout_phase=build' 'attempt_failure_phase=not_applicable'
write_attempt_manifest "$sandbox/artifacts" "$BUILD_FAIL_CLASS" 2 \
  'primary_classification=failure' 'raw_junit_count=0' \
  'outer_timeout_phase=not_applicable' 'attempt_failure_phase=build'
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions failure)"
printf '%s\n' "$CLASSIFY_OUT" | sed 's/^/    body: /'
[[ "$CLASSIFY_TOKEN" == "RED" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x) a build-level retry failure must stay RED, got '$CLASSIFY_TOKEN' — naming it must never soften it"; }
[[ "$CLASSIFY_RC" -ne 0 ]] || fail "(x) a RED classify body must exit non-zero"
grep -q '^shard_verdict=RED$' <<<"$CLASSIFY_GH_OUTPUT" \
  || { printf '%s\n' "$CLASSIFY_GH_OUTPUT"; fail "(x) the step must export shard_verdict=RED so the #1809 shard RED gate fires"; }
grep -q '^shard_verdict_reason=build_level_failure$' <<<"$CLASSIFY_GH_OUTPUT" \
  || { printf '%s\n' "$CLASSIFY_GH_OUTPUT"; fail "(x) THE ACCEPTANCE: a build-level retry failure was not distinguishable in the shard verdict"; }
grep -Fqx 'verdict_reason=build_level_failure' \
  "$sandbox/artifacts/ci-journey-shard-verdict/shard-verdict.txt" \
  || { cat "$sandbox/artifacts/ci-journey-shard-verdict/shard-verdict.txt"; fail "(x) the verdict token does not carry the build-level reason"; }
grep -q '::warning title=Emulator journey — an attempt died at the Gradle BUILD level' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x) the build-level failure was not annotated"; }
grep -q "$BUILD_FAIL_CLASS" <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x) the annotation does not name the class"; }
build_fail_token_dir="$SANDBOX/wf-verdicts-build-failure"; rm -rf "$build_fail_token_dir"; mkdir -p "$build_fail_token_dir"
write_shard_token "$build_fail_token_dir" 0 CLEAN
write_shard_token "$build_fail_token_dir" 1 "$CLASSIFY_TOKEN"
write_shard_token "$build_fail_token_dir" 2 CLEAN
run_agg "$build_fail_token_dir"
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(x) a build-level failure must still aggregate RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(x) #1840: a retry that died at the BUILD level -> RED with reason build_level_failure (not journey_failure_both_attempts), aggregate RED"

# (y) THE LOAD-BEARING CONTROL (G6): a class that GENUINELY failed twice — its
#     attempts ran instrumentation and produced JUnit XML — must keep the plain
#     `journey_failure_both_attempts` reason. The new attribution must not
#     relabel real regressions as build problems.
sandbox="$SANDBOX/wf-genuine-twice"; mkdir -p "$sandbox/artifacts"
write_summary "$sandbox/artifacts" 2870 "$OCCLUSION_CLASS#$OCCLUSION_METHOD"
for attempt in 1 2; do
  write_case_xml "$sandbox/artifacts" "$OCCLUSION_CLASS" "$attempt" "$OCCLUSION_METHOD:composetimeout"
  write_attempt_manifest "$sandbox/artifacts" "$OCCLUSION_CLASS" "$attempt" \
    'primary_classification=failure' 'raw_junit_count=1' \
    'outer_timeout_phase=not_applicable' 'attempt_failure_phase=instrumentation'
done
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions failure)"
[[ "$CLASSIFY_TOKEN" == "RED" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(y) a genuine twice-failure must stay RED, got '$CLASSIFY_TOKEN'"; }
grep -q '^shard_verdict_reason=first_attempt_journey_failure$' <<<"$CLASSIFY_GH_OUTPUT" \
  || { printf '%s\n' "$CLASSIFY_GH_OUTPUT"; fail "(y) a genuine twice-failure lost its own verdict reason"; }
grep -q '::warning title=Emulator journey — an attempt died at the Gradle BUILD level' <<<"$CLASSIFY_OUT" \
  && { printf '%s\n' "$CLASSIFY_OUT"; fail "(y) a genuine twice-failure was annotated as a build-level failure"; }
grep -qx 'build_phase_failure_attempts=0' \
  <<<"$(bash "$REPO_ROOT/scripts/ci-journey-build-phase-failure.sh" "$sandbox/artifacts")" \
  || fail "(y) the build-level scanner claimed evidence for a genuine twice-failure"
pass "(y) #1840 control: a genuine twice-failure keeps reason first_attempt_journey_failure and is never annotated build-level"

# (z) #1814 PRESERVED: with no build-level failure present, a cold-build timeout
#     still wins the reason. The two attributions must not cannibalise each other.
sandbox="$SANDBOX/wf-cold-build"; mkdir -p "$sandbox/artifacts"
write_summary "$sandbox/artifacts" 2870 "$BUILD_FAIL_CLASS"
write_attempt_manifest "$sandbox/artifacts" "$BUILD_FAIL_CLASS" 1 \
  'primary_classification=outer_timeout' 'raw_junit_count=0' \
  'outer_timeout_phase=build' 'attempt_failure_phase=not_applicable'
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions failure)"
grep -q '^shard_verdict_reason=cold_build_timeout$' <<<"$CLASSIFY_GH_OUTPUT" \
  || { printf '%s\n' "$CLASSIFY_GH_OUTPUT"; fail "(z) #1814's cold-build attribution regressed"; }
pass "(z) #1814 preserved: a cold-build timeout with no build-level failure still reports cold_build_timeout"


echo
echo "== #1800 synthetic coverage is present and wired =="

[[ -f "$ANDROID_TEST" ]] || fail "missing $ANDROID_TEST"
grep -q 'fun saturatedDraftAndAllActionsStayReachableWithSyntheticImeThenRestoreAfterHide' "$ANDROID_TEST" \
  || fail "the synthetic mirror of the real-IME reachability journey is missing"
grep -q 'val REAL_IME_REACHABLE_TAGS' "$ANDROID_TEST" \
  || fail "the real-IME and synthetic paths no longer share one reachable-tag set"
grep -c 'REAL_IME_REACHABLE_TAGS.forEach' "$ANDROID_TEST" | grep -qE '^[3-9]$|^[0-9]{2,}$' \
  || fail "both paths must iterate the shared reachable-tag set"
grep -qE '\bassume(True|False|NotNull|That)[[:space:]]*\(' "$ANDROID_TEST" \
  && fail "no Assume self-skip may guard these load-bearing assertions (F3)"
grep -q "com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest" \
  "$REPO_ROOT/scripts/ci-journey-suite.sh" \
  || fail "the class carrying the synthetic mirror is not wired into the per-push journey gate"
pass "the synthetic mirror exists, shares the tag set, and runs in the per-push gate"

echo
echo "ALL TESTS PASSED: scripts/test-ci-journey-infra-signature.sh"
