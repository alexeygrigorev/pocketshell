#!/usr/bin/env bash
# Issue #1800: deterministic, JVM-free, emulator-free fixture test for the
# emulator-journey failure-SIGNATURE classifier and the two aggregate outcomes
# it can produce.
#
# The load-bearing property: a shard whose ONLY failure is the CI swiftshader
# AVD's inability to raise a real system input-method window under a resolved
# foreign active-window owner must aggregate to RE-RUN (neutral green), while a
# shard carrying ANY genuine journey assertion failure — including an app-owned
# focus/serviceability failure or a containment/anchor failure in the SAME
# class, in the same run — must still aggregate to RED. Both outcomes are
# DEMONSTRATED here by driving the real classifier and then the real aggregate
# reducer, not argued.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SIGNATURE="$SCRIPT_DIR/ci-journey-infra-signature.sh"
SHARD_VERDICT="$SCRIPT_DIR/ci-journey-shard-signature-verdict.sh"
ELAPSED="$SCRIPT_DIR/ci-journey-suite-elapsed.sh"
RETRY_BUDGET="$SCRIPT_DIR/ci-journey-retry-budget.sh"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"
BUDGET_FN="$SCRIPT_DIR/ci-journey-budget-functions.sh"
WORKFLOW="${CI_JOURNEY_INFRA_SIGNATURE_WORKFLOW:-$REPO_ROOT/.github/workflows/tests.yml}"
ANDROID_TEST="$REPO_ROOT/app/src/androidTest/java/com/pocketshell/app/composer/PromptComposerSaturatedImeAnchorE2eTest.kt"
SHOW_KEYBOARD_TEST="$REPO_ROOT/app/src/androidTest/java/com/pocketshell/app/session/ShowKeyboardChipE2eTest.kt"
WINDOW_FOCUS_SIGNALS="$REPO_ROOT/app/src/androidTest/java/com/pocketshell/app/proof/signals/WindowFocusSignals.kt"
CLASSIFIER_PY="$SCRIPT_DIR/ci-journey-infra-signature.py"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$SIGNATURE" "$SHARD_VERDICT" "$ELAPSED" "$RETRY_BUDGET" "$AGG" "$WORKFLOW" "$BUDGET_FN"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

journey_fixture_artifact_key() {
  local classname="$1" key suffix
  key="$(
    bash -c 'source "$1"; journey_class_artifact_key "$2"' \
      _ "$BUDGET_FN" "$classname"
  )" || fail "could not derive the production artifact key for $classname"
  suffix="${key#"$classname"--}"
  [[ "$key" == "$classname"--* && "$suffix" =~ ^[0-9a-f]{16}$ ]] \
    || fail "malformed production artifact key for $classname: $key"
  printf '%s\n' "$key"
}

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

REAL_IME_MESSAGE='java.lang.AssertionError: The real system input-method window never became visible.'
APP_OWNED_REAL_IME_DETAIL='app_window_focused=false active_window_pkg=com.pocketshell.app.i1882 active_window_class=android.widget.FrameLayout composer_editor_served=false'
FOREIGN_REAL_IME_DETAIL='app_window_focused=false active_window_pkg=com.google.android.apps.nexuslauncher active_window_class=com.android.launcher3.Launcher'
AMBIGUOUS_ANR_REAL_IME_DETAIL='app_window_focused=false active_window_pkg=android active_window_class=com.android.server.am.AppNotRespondingDialog framework_error_dialog=android:id/aerr_wait'
RESIDUAL_IME_DETAIL='physicalImeWindows=[package=com.google.android.inputmethod.latin active=false focused=false bounds=Rect(0, 1517 - 1080, 2400)]'
FOREIGN_FOCUS_SIGNATURE_XML='The app window never held input focus, so the system refused every showSoftInput() call (&quot;is not served&quot;).'
FOREIGN_FOCUS_TAIL='The show-keyboard chip cannot be measured in that state, so this is NOT a chip failure (cycle 1): app_window_focused=false'
CONTAINMENT_MESSAGE="java.lang.AssertionError: Node 'prompt-composer-send-enter' must stay above the same-root keyboard boundary. node=Rect.fromLTRB(0.0, 1500.0, 1080.0, 1654.0) keyboardTopPx=1626.0"
SATURATED_CLASS="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"
SHOW_KEYBOARD_CLASS="com.pocketshell.app.session.ShowKeyboardChipE2eTest"
SHOW_KEYBOARD_METHOD="foreignFocusOwnerIsNamedAsTheCauseInsteadOfBlamingTheChip"

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
  local key dir
  key="$(journey_fixture_artifact_key "$class")"
  dir="$root/ci-journey/class-attempts/app/$key/attempt-$attempt/android-test-outputs/app/build/outputs/androidTest-results/connected/debug"
  mkdir -p "$dir"
  local file="$dir/TEST-emulator-5554-15_$key-$attempt.xml"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuite name=\"$class\" tests=\"$#\">"
    local spec method outcome owner
    for spec in "$@"; do
      method="${spec%%:*}"
      outcome="${spec#*:}"
      echo "  <testcase name=\"$method\" classname=\"${class}[emulator-5554 - 15]\">"
      case "$outcome" in
        pass) ;;
        realime)
          echo "    <failure message=\"$REAL_IME_MESSAGE $FOREIGN_REAL_IME_DETAIL\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:814)</failure>"
          ;;
        realime-app)
          echo "    <failure message=\"$REAL_IME_MESSAGE $APP_OWNED_REAL_IME_DETAIL\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:814)</failure>"
          ;;
        realime-app-residual)
          echo "    <failure message=\"$REAL_IME_MESSAGE $APP_OWNED_REAL_IME_DETAIL $RESIDUAL_IME_DETAIL\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:814)</failure>"
          ;;
        realime-anr-ambiguous)
          echo "    <failure message=\"$REAL_IME_MESSAGE $AMBIGUOUS_ANR_REAL_IME_DETAIL\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:814)</failure>"
          ;;
        realime-foreign-residual)
          echo "    <failure message=\"$REAL_IME_MESSAGE $FOREIGN_REAL_IME_DETAIL $RESIDUAL_IME_DETAIL\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:814)</failure>"
          ;;
        realime-no-owner)
          echo "    <failure message=\"$REAL_IME_MESSAGE $RESIDUAL_IME_DETAIL\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:814)</failure>"
          ;;
        realime-bare)
          echo "    <failure message=\"$REAL_IME_MESSAGE\">at org.junit.Assert.fail(Assert.java:89)</failure>"
          ;;
        realime-unavailable-owner)
          echo "    <failure message=\"$REAL_IME_MESSAGE app_window_focused=false active_window_pkg=&lt;unavailable&gt;\">at org.junit.Assert.fail(Assert.java:89)</failure>"
          ;;
        realime-malformed-owner)
          echo "    <failure message=\"$REAL_IME_MESSAGE app_window_focused=false active_window_pkg=not/a/package\">at org.junit.Assert.fail(Assert.java:89)</failure>"
          ;;
        realime-prefix-owner)
          echo "    <failure message=\"$REAL_IME_MESSAGE app_window_focused=false active_window_pkg=com.pocketshell.appspoof\">at org.junit.Assert.fail(Assert.java:89)</failure>"
          ;;
        realime-mixed-owners)
          echo "    <failure message=\"$REAL_IME_MESSAGE $FOREIGN_REAL_IME_DETAIL later_active_window_pkg=com.pocketshell.app.i1882 active_window_pkg=com.pocketshell.app.i1882\">at org.junit.Assert.fail(Assert.java:89)</failure>"
          ;;
        focus:*)
          owner="${outcome#focus:}"
          echo "    <failure message=\"java.lang.AssertionError: $FOREIGN_FOCUS_SIGNATURE_XML $FOREIGN_FOCUS_TAIL active_window_pkg=$owner active_window_class=android.widget.FrameLayout.\">at org.junit.Assert.fail(Assert.java:89)"
          echo "at com.pocketshell.app.session.ShowKeyboardChipE2eTest.runShowKeyboardCycle(ShowKeyboardChipE2eTest.kt:547)</failure>"
          ;;
        focus-no-owner)
          echo "    <failure message=\"java.lang.AssertionError: $FOREIGN_FOCUS_SIGNATURE_XML $FOREIGN_FOCUS_TAIL\">at org.junit.Assert.fail(Assert.java:89)</failure>"
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

# Issue #1919: write one of the four minimal class-attempt bundles preserved
# from exact-main run 30665705369. The failure text and the decisive
# ProcessRecord/mNotResponding ownership lines are byte-for-byte faithful to
# that run; unrelated dumpsys/report noise is deliberately omitted.
write_issue1919_attempt() {
  local root="$1" class="$2" key="$3" attempt="$4"
  local dir="$root/ci-journey/class-attempts/app/$key/attempt-$attempt"
  local xml_dir="$dir/android-test-outputs/app/build/outputs/androidTest-results/connected/debug"
  local snapshot_sha snapshot_size capture_token
  capture_token="$(printf '%s' "$root|$class|$attempt" | sha256sum | awk '{ print $1 }')"
  mkdir -p "$xml_dir"
  if [[ "$class" == "$SATURATED_CLASS" ]]; then
    cat > "$xml_dir/TEST-emulator-5554 - 15-_app-.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$class" tests="1" failures="1">
  <testcase name="saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide" classname="${class}[emulator-5554 - 15]">
    <failure>java.lang.AssertionError: The real system input-method window never became visible. stage=before_show app_window_focused=false active_window_pkg=android physicalImeWindows=[]
at org.junit.Assert.fail(Assert.java:89)
at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.requestRealImeAndAssertVisible(PromptComposerSaturatedImeAnchorE2eTest.kt:1091)
at com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest.saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide(PromptComposerSaturatedImeAnchorE2eTest.kt:762)
</failure>
  </testcase>
</testsuite>
EOF
  else
    cat > "$xml_dir/TEST-emulator-5554 - 15-_app-.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$class" tests="2" failures="2">
  <testcase name="showKeyboardChipBringsUpSoftInput" classname="${class}[emulator-5554 - 15]">
    <failure>java.lang.AssertionError: The app window never held input focus, so the system refused every showSoftInput() call (&quot;is not served&quot;). The show-keyboard chip cannot be measured in that state, so this is NOT a chip failure (cycle 1): app_window_focused=false active_window_pkg=android active_window_class=android.widget.FrameLayout.
at org.junit.Assert.fail(Assert.java:89)
at com.pocketshell.app.session.ShowKeyboardChipE2eTest.runShowKeyboardCycle(ShowKeyboardChipE2eTest.kt:500)
at com.pocketshell.app.session.ShowKeyboardChipE2eTest.showKeyboardChipBringsUpSoftInput(ShowKeyboardChipE2eTest.kt:241)
</failure>
  </testcase>
  <testcase name="foreignFocusOwnerIsNamedAsTheCauseInsteadOfBlamingTheChip" classname="${class}[emulator-5554 - 15]">
    <failure>java.lang.AssertionError: an app-owned focus thief is a PRODUCT signal and must be reported as such, never cleared by the harness; expected active_window_pkg=com.pocketshell.app in: The app window never held input focus, so the system refused every showSoftInput() call (&quot;is not served&quot;). The show-keyboard chip cannot be measured in that state, so this is NOT a chip failure (cycle 1): app_window_focused=false active_window_pkg=android active_window_class=android.widget.FrameLayout.
at org.junit.Assert.fail(Assert.java:89)
at org.junit.Assert.assertTrue(Assert.java:42)
at com.pocketshell.app.session.ShowKeyboardChipE2eTest.foreignFocusOwnerIsNamedAsTheCauseInsteadOfBlamingTheChip(ShowKeyboardChipE2eTest.kt:426)
</failure>
  </testcase>
</testsuite>
EOF
  fi
  cat > "$dir/activity-processes.txt" <<'EOF'
ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
  *APP* UID 10179 ProcessRecord{194efbc 1188:com.google.android.apps.nexuslauncher/u0a179}
    user #0 uid=10179 gids={50179, 20179, 9997}
    packageList={com.google.android.apps.nexuslauncher}
    pid=1188
    foregroundActivities=true (rep=true)
     mCrashing=false null mNotResponding=true [com.android.server.am.AppNotRespondingDialog@8fc5bd] bad=false
EOF
  printf '\nPOCKETSHELL_ATTEMPT_CAPTURE_TOKEN=%s\n' "$capture_token" \
    >> "$dir/activity-processes.txt"
  snapshot_sha="$(sha256sum "$dir/activity-processes.txt" | awk '{ print $1 }')"
  snapshot_size="$(wc -c < "$dir/activity-processes.txt")"
  cat > "$dir/manifest.txt" <<EOF
format_version=1
module=app
class=$class
attempt=$attempt
capture_token=$capture_token
started_at_utc=2026-07-31T15:00:0${attempt}Z
status=running
activity_processes_sha256=$snapshot_sha
activity_processes_size_bytes=$snapshot_size
activity_processes_captured_at_utc=2026-07-31T15:00:30Z
snapshot_status=complete
status=complete
finished_at_utc=2026-07-31T15:01:0${attempt}Z
EOF
}

# Issue #788 / exact-main run 30747057492: write the two focus-handoff
# failures that each repeated twice in one boot while the same Pixel Launcher
# ANR dialog remained above the app.  These are the exact causal oracle texts;
# no generic IME timeout, Copy timeout, or product assertion is substituted.
write_issue788_focus_handoff_attempt() {
  local root="$1" class="$2" key="$3" attempt="$4"
  local dir="$root/ci-journey/class-attempts/app/$key/attempt-$attempt"
  local xml_dir="$dir/android-test-outputs/app/build/outputs/androidTest-results/connected/debug"
  local snapshot_sha snapshot_size capture_token
  capture_token="$(printf '%s' "$root|$class|$attempt" | sha256sum | awk '{ print $1 }')"
  mkdir -p "$xml_dir"
  if [[ "$class" == "$OCCLUSION_CLASS" ]]; then
    cat > "$xml_dir/TEST-emulator-5554 - 15-_app-.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$class" tests="1" failures="1">
  <testcase name="$OCCLUSION_METHOD" classname="${class}[emulator-5554 - 15]">
    <failure>java.lang.AssertionError: the sent-snippet modal must release input focus before the keyboard-up shell-composer phase: app_window_focused=false active_window_pkg=android active_window_class=android.widget.FrameLayout
at org.junit.Assert.fail(Assert.java:89)
at com.pocketshell.app.tmux.TmuxShellComposerOcclusionE2eTest.awaitTestOpenedSnippetPickerDismissed(TmuxShellComposerOcclusionE2eTest.kt:495)
</failure>
  </testcase>
</testsuite>
EOF
  else
    cat > "$xml_dir/TEST-emulator-5554 - 15-_app-.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$class" tests="1" failures="1">
  <testcase name="$FILEVIEWER_METHOD" classname="${class}[emulator-5554 - 15]">
    <failure>java.lang.AssertionError: file-viewer activity must regain focus after the synthetic owner is dismissed; active_window_pkg=android active_window_class=android.widget.FrameLayout
at org.junit.Assert.fail(Assert.java:89)
at com.pocketshell.app.fileviewer.FileViewerDockerTest.dismissSyntheticFocusStealingWindow(FileViewerDockerTest.kt:980)
</failure>
  </testcase>
</testsuite>
EOF
  fi
  cat > "$dir/activity-processes.txt" <<'EOF'
ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
  *APP* UID 10179 ProcessRecord{19927a0 1229:com.google.android.apps.nexuslauncher/u0a179}
    user #0 uid=10179 gids={50179, 20179, 9997}
    packageList={com.google.android.apps.nexuslauncher}
    pid=1229
    foregroundActivities=true (rep=true)
     mCrashing=false null mNotResponding=true [com.android.server.am.AppNotRespondingDialog@b1901c8] bad=false
EOF
  printf '\nPOCKETSHELL_ATTEMPT_CAPTURE_TOKEN=%s\n' "$capture_token" \
    >> "$dir/activity-processes.txt"
  snapshot_sha="$(sha256sum "$dir/activity-processes.txt" | awk '{ print $1 }')"
  snapshot_size="$(wc -c < "$dir/activity-processes.txt")"
  cat > "$dir/manifest.txt" <<EOF
format_version=1
module=app
class=$class
attempt=$attempt
capture_token=$capture_token
started_at_utc=2026-08-02T13:1${attempt}:00Z
status=running
activity_processes_sha256=$snapshot_sha
activity_processes_size_bytes=$snapshot_size
activity_processes_captured_at_utc=2026-08-02T13:1${attempt}:30Z
snapshot_status=complete
status=complete
finished_at_utc=2026-08-02T13:1${attempt}:59Z
EOF
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
echo "== #1882 real-IME signature owner gate =="

# (x1) LOAD-BEARING RED. The existing #1800 classifier matches only the
# assertion sentence, so it currently swallows this concrete product failure:
# the composer editor lost serviceability to a PocketShell-owned window. The
# applicationIdSuffix shape is the real per-worktree CI package family.
root="$SANDBOX/x1-app-owned"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime-app"
write_case_xml "$root" "$SATURATED_CLASS" 2 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime-app"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(x1) an app-owned composer focus/serviceability failure must stay product_failure, got '$SIG_CLASS'"; }
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
[[ "$SHARD_TOKEN" == "RED" ]] \
  || { printf '%s\n' "$SHARD_VERDICT_OUT"; fail "(x1) an app-owned failure must keep the shard RED, got '$SHARD_TOKEN'"; }
verdicts="$SANDBOX/verdicts-x1"; mkdir -p "$verdicts"
write_shard_token "$verdicts" 0 "$SHARD_TOKEN"
write_shard_token "$verdicts" 1 CLEAN
write_shard_token "$verdicts" 2 CLEAN
run_agg "$verdicts"
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(x1) app-owned evidence must aggregate RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(x1) app-owned real-IME failure -> product_failure -> shard/aggregate RED"

# (x2) Control: the narrowing must not delete #1800's environment relief valve.
# An explicitly identified non-PocketShell active-window owner remains the
# captured environmental precondition.
root="$SANDBOX/x2-foreign"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "real_ime_precondition" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(x2) an explicitly foreign active-window owner must remain real_ime_precondition, got '$SIG_CLASS'"; }
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
[[ "$SHARD_TOKEN" == "INFRA" ]] \
  || { printf '%s\n' "$SHARD_VERDICT_OUT"; fail "(x2) genuinely foreign evidence must remain INFRA, got '$SHARD_TOKEN'"; }
pass "(x2) genuinely foreign active-window owner remains real_ime_precondition -> INFRA"

# (x3) Fail closed for every owner shape that is not positively identifiable as
# foreign. This includes both PocketShell applicationIdSuffixes and malformed or
# missing diagnostics. A missing parser input is never an environmental fact.
for unsafe_case in \
  "realime-app:the app under test owns focus" \
  "realime-prefix-owner:the owner shares the protected com.pocketshell.app prefix" \
  "realime-unavailable-owner:the owner could not be read" \
  "realime-malformed-owner:the owner is not a package" \
  "realime-bare:no owner reading was emitted"
do
  outcome="${unsafe_case%%:*}"
  why="${unsafe_case#*:}"
  root="$SANDBOX/x3-${outcome}"; mkdir -p "$root"
  write_summary "$root" 2338 "$SATURATED_CLASS"
  write_case_xml "$root" "$SATURATED_CLASS" 1 \
    "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:$outcome"
  run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
  [[ "$SIG_CLASS" == "product_failure" ]] \
    || { printf '%s\n' "$SIG_OUT"; fail "(x3) $why must stay product_failure, got '$SIG_CLASS'"; }
done
pass "(x3) app-owned, unresolved, malformed, and absent owners all stay loud"

# (x4) The observed #1879 ANR shape is deliberately NOT treated as genuinely
# foreign from active-window data alone. Framework error-dialog windows belong
# to package `android` whether the faulting process is Pixel Launcher or
# PocketShell itself (#796). Downgrading this ambiguous reading would hide an
# app-owned ANR product regression.
root="$SANDBOX/x4-anr"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime-anr-ambiguous"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(x4) active_window_pkg=android is ambiguous and must stay product_failure, got '$SIG_CLASS'"; }
pass "(x4) observed framework ANR focus theft (active_window_pkg=android) stays loud"

# (x5) The recurring #1818 residual/phantom IME shape is not itself proof of a
# foreign focus owner. It must not launder an app-owned failure, nor compensate
# for a missing owner reading. With a separately proven foreign active-window
# owner, the original #1800 downgrade remains available.
for residual_case in \
  "realime-app-residual:product_failure" \
  "realime-no-owner:product_failure" \
  "realime-foreign-residual:real_ime_precondition"
do
  outcome="${residual_case%%:*}"
  expected="${residual_case#*:}"
  root="$SANDBOX/x5-${outcome}"; mkdir -p "$root"
  write_summary "$root" 2338 "$SATURATED_CLASS"
  write_case_xml "$root" "$SATURATED_CLASS" 1 \
    "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:$outcome"
  run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
  [[ "$SIG_CLASS" == "$expected" ]] \
    || { printf '%s\n' "$SIG_OUT"; fail "(x5) residual shape $outcome expected '$expected', got '$SIG_CLASS'"; }
done
pass "(x5) residual-IME evidence never substitutes for a genuinely foreign owner"

# (x6) Every owner reading in a failing element must be genuinely foreign. A
# stale/earlier foreign reading cannot outvote a later app-owned focus owner.
root="$SANDBOX/x6-mixed-owners"; mkdir -p "$root"
write_summary "$root" 2338 "$SATURATED_CLASS"
write_case_xml "$root" "$SATURATED_CLASS" 1 \
  "saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide:realime-mixed-owners"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(x6) one app-owned reading among foreign readings must stay product_failure, got '$SIG_CLASS'"; }
pass "(x6) one app-owned reading vetoes the downgrade across the whole failure"

# (x7) The real journey must emit the evidence the classifier decides on and
# hard-establish the app-window-focus precondition. Otherwise the foreign
# control above would be a fixture-only value the production path cannot emit.
grep -q 'private fun waitForAppWindowFocus' "$ANDROID_TEST" \
  || fail "(x7) the real-IME journey does not hard-establish app window focus"
grep -q 'activeAppWindowRoots(activity).any { it.hasWindowFocus() }' "$ANDROID_TEST" \
  || fail "(x7) the precondition does not observe every app-owned window root"
grep -q 'active_window_pkg=' "$ANDROID_TEST" \
  || fail "(x7) the real failure does not emit the classifier's owner reading"
grep -q 'physicalImeWindows=' "$ANDROID_TEST" \
  || fail "(x7) the real failure does not preserve the observed #1818 residual-window shape"
grep -q 'The real system input-method window never became visible\.' "$ANDROID_TEST" \
  || fail "(x7) the exact #1800 signature sentence was removed"
grep -qE '\bassume(True|False|NotNull|That)[[:space:]]*\(' "$ANDROID_TEST" \
  && fail "(x7) no Assume self-skip may shield the load-bearing real-IME assertion"
pass "(x7) production journey hardens focus and emits owner/residual evidence without a skip"

echo
echo "== #1879 foreign-window focus is a loud label, never auto-INFRA =="

# The #1879 diagnosis has a different safety contract from #1882's real-IME
# signature. It reports the package of the WINDOW owning focus. A framework
# ANR/crash dialog belongs to `android` whether the faulting process is the
# launcher or PocketShell itself (#796), so no owner value can safely license
# an automatic INFRA downgrade for this message. Every producible owner shape
# therefore stays product_failure/RED.
for owner_case in \
  "android:framework error dialog, ambiguous between launcher and PocketShell ANR" \
  "com.pocketshell.app.i1879:app-owned focus thief" \
  "com.android.launcher3:foreign app own window" \
  "&lt;unavailable&gt;:owner could not be read"
do
  owner="${owner_case%%:*}"
  why="${owner_case#*:}"
  root="$SANDBOX/n1-${owner//[^A-Za-z0-9]/_}"; mkdir -p "$root"
  write_summary "$root" 2338 "$SHOW_KEYBOARD_CLASS"
  write_case_xml "$root" "$SHOW_KEYBOARD_CLASS" 1 \
    "$SHOW_KEYBOARD_METHOD:focus:$owner"
  write_case_xml "$root" "$SHOW_KEYBOARD_CLASS" 2 \
    "$SHOW_KEYBOARD_METHOD:focus:$owner"
  run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
  [[ "$SIG_CLASS" == "product_failure" ]] \
    || { printf '%s\n' "$SIG_OUT"; fail "(n1) active_window_pkg=$owner ($why) must stay product_failure, got '$SIG_CLASS'"; }
  mkdir -p "$root/ci-journey-attempt-1"
  cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
  shard_verdict_for "$root"
  [[ "$SHARD_TOKEN" == "RED" ]] \
    || { printf '%s\n' "$SHARD_VERDICT_OUT"; fail "(n1) active_window_pkg=$owner ($why) must keep the shard RED, got '$SHARD_TOKEN'"; }
done
pass "(n1) every producible #1879 active-window owner, including android, stays RED"

root="$SANDBOX/n2-no-owner"; mkdir -p "$root"
write_summary "$root" 2338 "$SHOW_KEYBOARD_CLASS"
write_case_xml "$root" "$SHOW_KEYBOARD_CLASS" 1 \
  "$SHOW_KEYBOARD_METHOD:focus-no-owner"
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(n2) the #1879 label without an owner must stay product_failure, got '$SIG_CLASS'"; }
pass "(n2) the #1879 label without owner evidence stays RED"

# Extract the production Kotlin constant, then prove the classifier registers
# exactly that label. #1919 permits it only when the same class-attempt bundle
# also proves foreign ProcessRecord ownership; the owner-less (n1/n2) cases
# above remain the load-bearing anti-mask controls.
[[ -f "$WINDOW_FOCUS_SIGNALS" ]] || fail "(n3) missing $WINDOW_FOCUS_SIGNALS"
KOTLIN_SIGNATURE="$(python3 - "$WINDOW_FOCUS_SIGNALS" <<'PYEOF'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
match = re.search(
    r'const val FOREIGN_WINDOW_FOCUS_SIGNATURE: String =\s*(.*?)\n\n',
    text,
    re.S,
)
if not match:
    sys.exit("could not read FOREIGN_WINDOW_FOCUS_SIGNATURE")
pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', match.group(1))
if not pieces:
    sys.exit("the Kotlin constant has no string literal parts")
print("".join(piece.replace('\\"', '"') for piece in pieces))
PYEOF
)" || fail "(n3) could not extract the Kotlin #1879 signature"
python3 - "$CLASSIFIER_PY" "$KOTLIN_SIGNATURE" <<'PYEOF' \
  || fail "(n3) the classifier does not use the production #1879 label"
import ast
import sys

source_path, signature = sys.argv[1], sys.argv[2]
tree = ast.parse(open(source_path, encoding="utf-8").read())
docstring_node = None
if tree.body and isinstance(tree.body[0], ast.Expr):
    value = tree.body[0].value
    if isinstance(value, ast.Constant) and isinstance(value.value, str):
        docstring_node = value
found = False
for node in ast.walk(tree):
    if node is docstring_node:
        continue
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        if signature in node.value:
            found = True
if not found:
    raise SystemExit(1)
PYEOF

# The verdict layer refuses the old owner-only hypothetical value, while
# admitting only #1882 and #1919's explicit typed classifications.
root="$SANDBOX/n3-verdict"; mkdir -p "$root/ci-journey"
: >"$root/ci-journey/summary.md"
stub="$SANDBOX/stub-foreign-window-classifier.sh"
printf '%s\n' '#!/usr/bin/env bash' \
  'echo journey_failure_classification=foreign_window_focus' >"$stub"
chmod +x "$stub"
stub_out="$(CI_JOURNEY_INFRA_SIGNATURE="$stub" bash "$SHARD_VERDICT" "$root")"
[[ "$(sed -n 's/^shard_signature_verdict=//p' <<<"$stub_out" | tail -n 1)" != "INFRA" ]] \
  || fail "(n3) foreign_window_focus must not downgrade a shard"
printf '%s\n' '#!/usr/bin/env bash' \
  'echo journey_failure_classification=real_ime_precondition' >"$stub"
stub_out="$(CI_JOURNEY_INFRA_SIGNATURE="$stub" bash "$SHARD_VERDICT" "$root")"
[[ "$(sed -n 's/^shard_signature_verdict=//p' <<<"$stub_out" | tail -n 1)" == "INFRA" ]] \
  || fail "(n3) #1882 real_ime_precondition control no longer reaches INFRA"
printf '%s\n' '#!/usr/bin/env bash' \
  'echo journey_failure_classification=foreign_framework_anr_focus' >"$stub"
stub_out="$(CI_JOURNEY_INFRA_SIGNATURE="$stub" bash "$SHARD_VERDICT" "$root")"
[[ "$(sed -n 's/^shard_signature_verdict=//p' <<<"$stub_out" | tail -n 1)" == "INFRA" ]] \
  || fail "(n3) #1919 foreign_framework_anr_focus does not reach INFRA"
pass "(n3) owner-only focus stays RED; only #1882/#1919 typed values reach INFRA"

# The precondition observes and diagnoses only. Acting on the UI or mutating
# process-wide UiAutomation flags can dismiss an app-owned modal and mask a
# genuine session-screen product regression.
for banned in 'performGlobalAction' 'GLOBAL_ACTION_' 'ACTION_CLICK' 'performAction' 'serviceInfo'; do
  grep -q "$banned" "$WINDOW_FOCUS_SIGNALS" \
    && fail "(n4) $WINDOW_FOCUS_SIGNALS must observe only; found '$banned'"
done
pass "(n4) #1879 focus precondition has no UI action or shared-service mutation"

[[ -f "$SHOW_KEYBOARD_TEST" ]] || fail "(n5) missing $SHOW_KEYBOARD_TEST"
grep -qE '\bassume(True|False|NotNull|That)[[:space:]]*\(' "$SHOW_KEYBOARD_TEST" \
  && fail "(n5) no Assume self-skip may guard the ShowKeyboardChip proof"
grep -q 'expected the soft keyboard to be VISIBLE after ONE tap on the show-keyboard chip' \
  "$SHOW_KEYBOARD_TEST" \
  || fail "(n5) the hard post-tap acceptance assertion is gone"
grep -q 'expected the soft keyboard to be HIDDEN before tapping the show-keyboard' \
  "$SHOW_KEYBOARD_TEST" \
  || fail "(n5) the hard keyboard-down precondition is gone"
[[ "$(grep -c 'SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).performClick()' "$SHOW_KEYBOARD_TEST")" == "1" ]] \
  || fail "(n5) the chip must be tapped from exactly one production site"
grep -q "fun $SHOW_KEYBOARD_METHOD" "$SHOW_KEYBOARD_TEST" \
  || fail "(n5) the #1879 connected reproduction is missing"
grep -q "$SHOW_KEYBOARD_CLASS" "$REPO_ROOT/scripts/ci-journey-suite.sh" \
  || fail "(n5) the #1879 class is not wired into the per-push journey gate"
pass "(n5) hard assertions remain and the #1879 reproduction is gate-wired"

echo
echo "== #1919 exact run 30665705369 foreign-framework-ANR fixture =="

# Regression-first: exact main 76f0c401 classified this four-attempt fixture as
# product_failure because active_window_pkg=android is correctly ambiguous in
# isolation. It may turn green only when each failure is associated with its
# own class-attempt activity-processes.txt and that snapshot proves the one
# foreign ProcessRecord owns the framework AppNotRespondingDialog.
root="$SANDBOX/issue1919-exact"; mkdir -p "$root"
ISSUE1919_SAT_KEY="$(journey_fixture_artifact_key "$SATURATED_CLASS")"
ISSUE1919_SHOW_KEY="$(journey_fixture_artifact_key "$SHOW_KEYBOARD_CLASS")"
write_summary "$root" 2910 "$SATURATED_CLASS" "$SHOW_KEYBOARD_CLASS"
for attempt in 1 2; do
  write_issue1919_attempt "$root" "$SATURATED_CLASS" \
    "$ISSUE1919_SAT_KEY" "$attempt"
  write_issue1919_attempt "$root" "$SHOW_KEYBOARD_CLASS" \
    "$ISSUE1919_SHOW_KEY" "$attempt"
done
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
printf '%s\n' "$SIG_OUT" | sed 's/^/    /'
[[ "$SIG_CLASS" == "foreign_framework_anr_focus" ]] \
  || fail "(#1919-red) exact four-attempt launcher-ANR fixture must classify foreign_framework_anr_focus, got '$SIG_CLASS'"
grep -q '^journey_failing_testcases=6$' <<<"$SIG_OUT" \
  || fail "(#1919-red) all six failure elements across the four attempts must be covered"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
[[ "$SHARD_TOKEN" == "INFRA" ]] \
  || fail "(#1919-red) exact launcher-ANR shard must be INFRA, got $SHARD_TOKEN"
verdicts="$SANDBOX/verdicts-1919"; mkdir -p "$verdicts"
write_shard_token "$verdicts" 0 CLEAN
write_shard_token "$verdicts" 1 "$SHARD_TOKEN"
write_shard_token "$verdicts" 2 CLEAN
run_agg "$verdicts"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || fail "(#1919-red) exact launcher-ANR run must aggregate RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"
pass "(#1919) exact four-attempt launcher ANR -> INFRA -> RE-RUN"
ISSUE1919_FIXTURE="$root"

echo
echo "== #788 exact run 30747057492 cross-journey launcher-ANR focus handoff =="

# Regression-first RED: exact main cfeafc27 classified these four failures as
# product_failure even though every attempt-local snapshot proved the SAME sole
# Pixel Launcher AppNotRespondingDialog owner.  Both repaired journeys therefore
# failed twice in one boot and hardened into a RED when the cold-boot retry did
# not fit.  The narrow handoff classifier must route that complete evidence to
# the existing INFRA -> fresh-cold-boot path, without changing either journey's
# real IME/Copy deadline or dismissing any window.
root="$SANDBOX/issue788-focus-handoff"; mkdir -p "$root"
ISSUE788_OCCLUSION_KEY="$(journey_fixture_artifact_key "$OCCLUSION_CLASS")"
ISSUE788_FILEVIEWER_KEY="$(journey_fixture_artifact_key "$FILEVIEWER_CLASS")"
write_summary "$root" 2910 \
  "$OCCLUSION_CLASS#$OCCLUSION_METHOD" \
  "$FILEVIEWER_CLASS#$FILEVIEWER_METHOD"
for attempt in 1 2; do
  write_issue788_focus_handoff_attempt "$root" "$OCCLUSION_CLASS" \
    "$ISSUE788_OCCLUSION_KEY" "$attempt"
  write_issue788_focus_handoff_attempt "$root" "$FILEVIEWER_CLASS" \
    "$ISSUE788_FILEVIEWER_KEY" "$attempt"
done
run_signature "$root/ci-journey/summary.md" "$root/ci-journey"
printf '%s\n' "$SIG_OUT" | sed 's/^/    /'
[[ "$SIG_CLASS" == "foreign_framework_anr_focus" ]] \
  || fail "(#788-focus) exact four-attempt handoff fixture must classify foreign_framework_anr_focus, got '$SIG_CLASS'"
grep -q '^journey_failing_testcases=4$' <<<"$SIG_OUT" \
  || fail "(#788-focus) all four handoff failures must be covered"
mkdir -p "$root/ci-journey-attempt-1"
cp -a "$root/ci-journey" "$root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$root"
[[ "$SHARD_TOKEN" == "INFRA" ]] \
  || fail "(#788-focus) exact launcher-ANR handoff shard must be INFRA, got $SHARD_TOKEN"
verdicts="$SANDBOX/verdicts-788-focus"; mkdir -p "$verdicts"
write_shard_token "$verdicts" 0 CLEAN
write_shard_token "$verdicts" 1 "$SHARD_TOKEN"
write_shard_token "$verdicts" 2 CLEAN
run_agg "$verdicts"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || fail "(#788-focus) exact handoff run must aggregate RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"
pass "(#788-focus) composer + FileViewer x2 in one boot -> INFRA -> RE-RUN"

# Anti-mask controls specific to the new handoff wording.  Active-window text
# alone is never enough: app ownership, a second owner, a PocketShell ANR owner,
# or an ordinary product assertion must all stay RED.
ISSUE788_FOCUS_FIXTURE="$root"
issue788_focus_xml() {
  printf '%s/ci-journey/class-attempts/app/%s/attempt-%s/android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 15-_app-.xml\n' \
    "$1" "$2" "$3"
}
issue788_focus_snapshot() {
  printf '%s/ci-journey/class-attempts/app/%s/attempt-%s/activity-processes.txt\n' \
    "$1" "$2" "$3"
}
rebind_issue788_focus_snapshot() {
  local case_root="$1" key="$2" attempt="$3"
  local snapshot manifest sha size token
  snapshot="$(issue788_focus_snapshot "$case_root" "$key" "$attempt")"
  manifest="$case_root/ci-journey/class-attempts/app/$key/attempt-$attempt/manifest.txt"
  token="$(sed -n 's/^capture_token=//p' "$manifest" | tail -n 1)"
  sed -i '/^POCKETSHELL_ATTEMPT_CAPTURE_TOKEN=/d' "$snapshot"
  printf '\nPOCKETSHELL_ATTEMPT_CAPTURE_TOKEN=%s\n' "$token" >> "$snapshot"
  sha="$(sha256sum "$snapshot" | awk '{ print $1 }')"
  size="$(wc -c < "$snapshot")"
  sed -i \
    -e "s/^activity_processes_sha256=.*/activity_processes_sha256=$sha/" \
    -e "s/^activity_processes_size_bytes=.*/activity_processes_size_bytes=$size/" \
    "$manifest"
}
mutate_issue788_manifest_hex_field() {
  local manifest="$1" field="$2"
  local -a values=()
  local original replacement mutated observed

  mapfile -t values < <(sed -n "s/^${field}=//p" "$manifest")
  [[ "${#values[@]}" -eq 1 && "${values[0]}" =~ ^[0-9a-f]{64}$ ]] \
    || fail "(#788-focus-$field) expected exactly one valid 64-hex source value"
  original="${values[0]}"
  replacement=0
  [[ "${original:0:1}" == 0 ]] && replacement=1
  mutated="$replacement${original:1}"
  [[ "$mutated" =~ ^[0-9a-f]{64}$ && "$mutated" != "$original" ]] \
    || fail "(#788-focus-$field) mutation precondition did not guarantee a different valid value"

  sed -i "s/^${field}=.*/${field}=$mutated/" "$manifest"
  observed="$(sed -n "s/^${field}=//p" "$manifest")"
  [[ "$observed" == "$mutated" && "$observed" != "$original" ]] \
    || fail "(#788-focus-$field) manifest mutation was a no-op or changed the wrong value"
}
assert_issue788_focus_red() {
  local label="$1" case_root="$2"
  run_signature "$case_root/ci-journey/summary.md" "$case_root/ci-journey"
  [[ "$SIG_CLASS" == "product_failure" || "$SIG_CLASS" == "unclassified" ]] \
    || { printf '%s\n' "$SIG_OUT"; fail "(#788-focus-$label) unsafe evidence classified '$SIG_CLASS'"; }
}

case_root="$SANDBOX/issue788-focus-app-window"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
sed -i 's/active_window_pkg=android/active_window_pkg=com.pocketshell.app/g' \
  "$(issue788_focus_xml "$case_root" "$ISSUE788_OCCLUSION_KEY" 1)"
assert_issue788_focus_red app-window "$case_root"

case_root="$SANDBOX/issue788-focus-mixed-window"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
sed -i 's/active_window_class=android.widget.FrameLayout/active_window_class=android.widget.FrameLayout active_window_pkg=com.pocketshell.app/' \
  "$(issue788_focus_xml "$case_root" "$ISSUE788_FILEVIEWER_KEY" 1)"
assert_issue788_focus_red mixed-window "$case_root"

case_root="$SANDBOX/issue788-focus-app-anr"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
sed -i 's/com.google.android.apps.nexuslauncher/com.pocketshell.app.i788/g' \
  "$(issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 1)"
rebind_issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 1
assert_issue788_focus_red app-anr "$case_root"

case_root="$SANDBOX/issue788-focus-missing-snapshot"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
unlink "$(issue788_focus_snapshot "$case_root" "$ISSUE788_FILEVIEWER_KEY" 1)"
assert_issue788_focus_red missing-snapshot "$case_root"

case_root="$SANDBOX/issue788-focus-multiple-anr"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
snapshot="$(issue788_focus_snapshot "$case_root" "$ISSUE788_FILEVIEWER_KEY" 1)"
cat >> "$snapshot" <<'EOF'
  *APP* UID 10200 ProcessRecord{bbbbbbb 2200:com.example.second/u0a200}
    mNotResponding=true [com.android.server.am.AppNotRespondingDialog@bbbbbbb]
EOF
rebind_issue788_focus_snapshot "$case_root" "$ISSUE788_FILEVIEWER_KEY" 1
assert_issue788_focus_red multiple-anr "$case_root"

# Exact reviewer repro: first establish a validly bound PocketShell-owned
# attempt (3/4 environmental matches), then overwrite that regular file with
# attempt 2's launcher dump.  Path/class/attempt metadata remains plausible,
# but the copied bytes carry attempt 2's token/hash and must not turn 3/4 into
# 4/4 or license INFRA.
case_root="$SANDBOX/issue788-focus-regular-sibling-copy"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
sed -i 's/com.google.android.apps.nexuslauncher/com.pocketshell.app.i788/g' \
  "$(issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 1)"
rebind_issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 1
run_signature "$case_root/ci-journey/summary.md" "$case_root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || fail "(#788-focus-regular-sibling-copy) app-owned baseline was not product_failure"
grep -q '^journey_signature_matches=3$' <<<"$SIG_OUT" \
  || fail "(#788-focus-regular-sibling-copy) app-owned baseline was not 3/4"
cp "$(issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 2)" \
  "$(issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 1)"
assert_issue788_focus_red regular-sibling-copy "$case_root"
grep -q '^journey_signature_matches=3$' <<<"$SIG_OUT" \
  || fail "(#788-focus-regular-sibling-copy) copied sibling snapshot laundered 3/4 evidence"

case_root="$SANDBOX/issue788-focus-regular-other-class"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
cp "$(issue788_focus_snapshot "$case_root" "$ISSUE788_FILEVIEWER_KEY" 1)" \
  "$(issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 1)"
assert_issue788_focus_red regular-other-class "$case_root"

donor_root="$SANDBOX/issue788-focus-stale-root-donor"
write_issue788_focus_handoff_attempt "$donor_root" "$OCCLUSION_CLASS" \
  "$ISSUE788_OCCLUSION_KEY" 1
case_root="$SANDBOX/issue788-focus-regular-stale-root"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
cp "$(issue788_focus_snapshot "$donor_root" "$ISSUE788_OCCLUSION_KEY" 1)" \
  "$(issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 1)"
assert_issue788_focus_red regular-stale-root "$case_root"

for mutation in missing-hash mismatched-hash mismatched-token stale-capture-time; do
  case_root="$SANDBOX/issue788-focus-$mutation"; mkdir -p "$case_root"
  cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
  manifest="$case_root/ci-journey/class-attempts/app/$ISSUE788_OCCLUSION_KEY/attempt-1/manifest.txt"
  case "$mutation" in
    missing-hash) sed -i '/^activity_processes_sha256=/d' "$manifest" ;;
    mismatched-hash)
      mutate_issue788_manifest_hex_field "$manifest" activity_processes_sha256
      ;;
    mismatched-token)
      mutate_issue788_manifest_hex_field "$manifest" capture_token
      ;;
    stale-capture-time)
      sed -i 's/^activity_processes_captured_at_utc=.*/activity_processes_captured_at_utc=2026-08-02T12:00:00Z/' "$manifest"
      ;;
  esac
  assert_issue788_focus_red "$mutation" "$case_root"
done

case_root="$SANDBOX/issue788-focus-attempt-root"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
snapshot="$(issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 1)"
unlink "$snapshot"
ln -s "$(issue788_focus_snapshot "$case_root" "$ISSUE788_OCCLUSION_KEY" 2)" "$snapshot"
assert_issue788_focus_red wrong-attempt-root "$case_root"

for mutation in wrong-attempt inverted-time; do
  case_root="$SANDBOX/issue788-focus-$mutation"; mkdir -p "$case_root"
  cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
  manifest="$case_root/ci-journey/class-attempts/app/$ISSUE788_FILEVIEWER_KEY/attempt-1/manifest.txt"
  case "$mutation" in
    wrong-attempt) sed -i 's/^attempt=1$/attempt=2/' "$manifest" ;;
    inverted-time)
      sed -i \
        -e 's/^started_at_utc=.*/started_at_utc=2026-08-02T13:59:59Z/' \
        -e 's/^finished_at_utc=.*/finished_at_utc=2026-08-02T13:00:00Z/' \
        "$manifest"
      ;;
  esac
  assert_issue788_focus_red "$mutation" "$case_root"
done

case_root="$SANDBOX/issue788-focus-product"; mkdir -p "$case_root"
cp -a "$ISSUE788_FOCUS_FIXTURE/ci-journey" "$case_root/ci-journey"
sed -i 's/the sent-snippet modal must release input focus before the keyboard-up shell-composer phase:/composer launcher must be fully on-screen (keyboard up);/' \
  "$(issue788_focus_xml "$case_root" "$ISSUE788_OCCLUSION_KEY" 1)"
assert_issue788_focus_red ordinary-product "$case_root"
pass "(#788-focus-a) app/mixed owners, app/multiple ANRs, copied/symlinked/cross-root or missing/mismatched/stale bindings, and ordinary product assertions stay RED"
issue1919_snapshot() {
  printf '%s/ci-journey/class-attempts/app/%s/attempt-%s/activity-processes.txt\n' \
    "$1" "$2" "$3"
}
issue1919_xml() {
  printf '%s/ci-journey/class-attempts/app/%s/attempt-%s/android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 15-_app-.xml\n' \
    "$1" "$2" "$3"
}
clone_issue1919_fixture() {
  mkdir -p "$1"
  cp -a "$ISSUE1919_FIXTURE/ci-journey" "$1/ci-journey"
}
assert_issue1919_red() {
  local label="$1" case_root="$2"
  run_signature "$case_root/ci-journey/summary.md" "$case_root/ci-journey"
  [[ "$SIG_CLASS" == "product_failure" || "$SIG_CLASS" == "unclassified" ]] \
    || { printf '%s\n' "$SIG_OUT"; fail "(#1919-$label) unsafe evidence classified '$SIG_CLASS'"; }
  mkdir -p "$case_root/ci-journey-attempt-1"
  cp -a "$case_root/ci-journey" "$case_root/ci-journey-attempt-1/ci-journey"
  shard_verdict_for "$case_root"
  [[ "$SHARD_TOKEN" == "RED" ]] \
    || fail "(#1919-$label) unsafe evidence must keep shard RED, got $SHARD_TOKEN"
}

# PocketShell itself, including arbitrary connected-test applicationIdSuffix
# variants, can own the same framework dialog (#796). They are never infra.
for owner in com.pocketshell.app com.pocketshell.app.i1919; do
  case_root="$SANDBOX/issue1919-owner-${owner##*.}"
  clone_issue1919_fixture "$case_root"
  sed -i "s/com.google.android.apps.nexuslauncher/$owner/g" \
    "$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
  assert_issue1919_red "app-owner-${owner##*.}" "$case_root"
done
pass "(#1919-a) PocketShell and applicationIdSuffix ANR owners stay RED"

# Every ownership ambiguity fails closed: a second app/foreign owner, malformed
# ProcessRecord package, dialog outside the owner block, false not-responding
# state, or missing dialog identity.
case_root="$SANDBOX/issue1919-mixed-owner"; clone_issue1919_fixture "$case_root"
snapshot="$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
cat >> "$snapshot" <<'EOF'
  *APP* UID 10200 ProcessRecord{bbbbbbb 2200:com.pocketshell.app.i1919/u0a200}
    mNotResponding=true [com.android.server.am.AppNotRespondingDialog@bbbbbbb]
EOF
assert_issue1919_red mixed-owner "$case_root"

case_root="$SANDBOX/issue1919-two-foreign"; clone_issue1919_fixture "$case_root"
snapshot="$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
cat >> "$snapshot" <<'EOF'
  *APP* UID 10201 ProcessRecord{ccccccc 2201:com.example.second/u0a201}
    mNotResponding=true [com.android.server.am.AppNotRespondingDialog@ccccccc]
EOF
assert_issue1919_red two-foreign "$case_root"

for mutation in malformed false missing-id; do
  case_root="$SANDBOX/issue1919-$mutation"; clone_issue1919_fixture "$case_root"
  snapshot="$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
  case "$mutation" in
    malformed) sed -i 's/com.google.android.apps.nexuslauncher/not-a-package/g' "$snapshot" ;;
    false) sed -i 's/mNotResponding=true/mNotResponding=false/' "$snapshot" ;;
    missing-id) sed -i 's/AppNotRespondingDialog@8fc5bd/AppNotRespondingDialog/' "$snapshot" ;;
  esac
  assert_issue1919_red "$mutation" "$case_root"
done

case_root="$SANDBOX/issue1919-dialog-outside"; clone_issue1919_fixture "$case_root"
snapshot="$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
sed -i 's/mNotResponding=true \[com.android.server.am.AppNotRespondingDialog@8fc5bd\]/mNotResponding=true/' "$snapshot"
sed -i '1i mNotResponding=true [com.android.server.am.AppNotRespondingDialog@8fc5bd]' "$snapshot"
assert_issue1919_red dialog-outside "$case_root"
pass "(#1919-b) mixed/multiple/malformed/out-of-block ownership stays RED"

# Missing, empty, malformed, and cross-attempt evidence cannot borrow another
# bundle's valid launcher snapshot.
for mutation in missing empty malformed-bytes; do
  case_root="$SANDBOX/issue1919-snapshot-$mutation"; clone_issue1919_fixture "$case_root"
  snapshot="$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
  case "$mutation" in
    missing) rm "$snapshot" ;;
    empty) : > "$snapshot" ;;
    malformed-bytes) printf '\377\376\375' > "$snapshot" ;;
  esac
  assert_issue1919_red "snapshot-$mutation" "$case_root"
done
case_root="$SANDBOX/issue1919-snapshot-unreadable"; clone_issue1919_fixture "$case_root"
snapshot="$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
chmod 000 "$snapshot"
run_signature "$case_root/ci-journey/summary.md" "$case_root/ci-journey"
[[ "$SIG_CLASS" == "product_failure" ]] \
  || fail "(#1919-snapshot-unreadable) unreadable snapshot must be product_failure"
chmod 600 "$snapshot"
mkdir -p "$case_root/ci-journey-attempt-1"
cp -a "$case_root/ci-journey" "$case_root/ci-journey-attempt-1/ci-journey"
chmod 000 "$snapshot" \
  "$case_root/ci-journey-attempt-1/ci-journey/class-attempts/app/$ISSUE1919_SAT_KEY/attempt-1/activity-processes.txt"
shard_verdict_for "$case_root"
[[ "$SHARD_TOKEN" == "RED" ]] \
  || fail "(#1919-snapshot-unreadable) unreadable snapshot must keep shard RED"
for app_attempt in 1 2; do
  case_root="$SANDBOX/issue1919-attempt-$app_attempt-app"; clone_issue1919_fixture "$case_root"
  snapshot="$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" "$app_attempt")"
  sed -i 's/com.google.android.apps.nexuslauncher/com.pocketshell.app.i1919/g' "$snapshot"
  assert_issue1919_red "attempt-$app_attempt-app" "$case_root"
done
case_root="$SANDBOX/issue1919-cross-class"; clone_issue1919_fixture "$case_root"
rm "$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
assert_issue1919_red cross-class "$case_root"

# A testcase may consume only the snapshot beside its OWN canonical class key.
# This is deliberately one bundle: the SaturatedIme attempt XML is poisoned
# with a wanted ShowKeyboard testcase while no ShowKeyboard attempt exists. A
# classifier that checks only "testcase is wanted" launders both failures
# through SaturatedIme's one valid launcher snapshot and incorrectly emits INFRA.
case_root="$SANDBOX/issue1919-wrong-bundle-classname"
mkdir -p "$case_root"
write_summary "$case_root" 2910 "$SATURATED_CLASS" "$SHOW_KEYBOARD_CLASS"
write_issue1919_attempt "$case_root" "$SATURATED_CLASS" "$ISSUE1919_SAT_KEY" 1
xml="$(issue1919_xml "$case_root" "$ISSUE1919_SAT_KEY" 1)"
python3 - "$xml" "$SHOW_KEYBOARD_CLASS" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET

path, classname = sys.argv[1:]
tree = ET.parse(path)
suite = tree.getroot()
case = ET.SubElement(
    suite,
    "testcase",
    {
        "name": "showKeyboardChipBringsUpSoftInput",
        "classname": f"{classname}[emulator-5554 - 15]",
    },
)
failure = ET.SubElement(case, "failure")
failure.text = (
    "java.lang.AssertionError: The app window never held input focus, so the "
    "system refused every showSoftInput() call (\"is not served\"). The "
    "show-keyboard chip cannot be measured in that state, so this is NOT a "
    "chip failure (cycle 1): app_window_focused=false "
    "active_window_pkg=android active_window_class=android.widget.FrameLayout."
)
suite.set("tests", "2")
suite.set("failures", "2")
tree.write(path, encoding="UTF-8", xml_declaration=True)
PYEOF
run_signature "$case_root/ci-journey/summary.md" "$case_root/ci-journey"
printf '%s\n' "$SIG_OUT" | sed 's/^/    wrong-bundle: /'
[[ "$SIG_CLASS" == "product_failure" ]] \
  || { printf '%s\n' "$SIG_OUT"; fail "(#1919-wrong-bundle-classname) mismatched testcase must be product_failure, got '$SIG_CLASS'"; }
grep -q '^journey_failing_testcases=2$' <<<"$SIG_OUT" \
  || fail "(#1919-wrong-bundle-classname) both failing testcases must remain visible"
grep -q 'artifact-key-classname-mismatch.*ShowKeyboardChipE2eTest' <<<"$SIG_OUT" \
  || { printf '%s\n' "$SIG_OUT"; fail "(#1919-wrong-bundle-classname) mismatch offender is not explicit"; }
mkdir -p "$case_root/ci-journey-attempt-1"
cp -a "$case_root/ci-journey" "$case_root/ci-journey-attempt-1/ci-journey"
shard_verdict_for "$case_root"
[[ "$SHARD_TOKEN" == "RED" ]] \
  || fail "(#1919-wrong-bundle-classname) mismatched testcase must keep shard RED, got $SHARD_TOKEN"

case_root="$SANDBOX/issue1919-cross-attempt-symlink"; clone_issue1919_fixture "$case_root"
snapshot="$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 1)"
rm "$snapshot"
ln -s "$(issue1919_snapshot "$case_root" "$ISSUE1919_SAT_KEY" 2)" "$snapshot"
assert_issue1919_red cross-attempt-symlink "$case_root"
pass "(#1919-c) missing/malformed/wrong-attempt/cross-class/wrong-bundle evidence stays RED"

# XML coverage remains all-or-nothing. Duplicate or corrupt attempt XML,
# containment/anchor/chip/timeout text, and unrelated or uncovered classes all
# veto the downgrade even beside valid foreign ANR snapshots.
case_root="$SANDBOX/issue1919-duplicate-xml"; clone_issue1919_fixture "$case_root"
xml="$(issue1919_xml "$case_root" "$ISSUE1919_SAT_KEY" 1)"
cp "$xml" "${xml%/*}/TEST-duplicate.xml"
assert_issue1919_red duplicate-xml "$case_root"

case_root="$SANDBOX/issue1919-corrupt-xml"; clone_issue1919_fixture "$case_root"
xml="$(issue1919_xml "$case_root" "$ISSUE1919_SAT_KEY" 1)"
printf '<testsuite><testcase>' > "$xml"
assert_issue1919_red corrupt-xml "$case_root"

for mutation in containment chip timeout; do
  case_root="$SANDBOX/issue1919-failure-$mutation"; clone_issue1919_fixture "$case_root"
  if [[ "$mutation" == containment ]]; then
    xml="$(issue1919_xml "$case_root" "$ISSUE1919_SAT_KEY" 1)"
    sed -i 's/The real system input-method window never became visible\./Composer anchor escaped keyboard containment./' "$xml"
  else
    xml="$(issue1919_xml "$case_root" "$ISSUE1919_SHOW_KEY" 1)"
    if [[ "$mutation" == chip ]]; then
      sed -i '0,/The app window never held input focus/{s/The app window never held input focus/Show-keyboard chip remained hidden while the app window held focus/}' "$xml"
    else
      sed -i '0,/The app window never held input focus/{s/The app window never held input focus/androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 15000 ms/}' "$xml"
    fi
  fi
  assert_issue1919_red "failure-$mutation" "$case_root"
done

case_root="$SANDBOX/issue1919-unrelated"; clone_issue1919_fixture "$case_root"
append_summary_line "$case_root" '- `com.pocketshell.app.proof.SomeOtherJourneyE2eTest`'
write_case_xml "$case_root" com.pocketshell.app.proof.SomeOtherJourneyE2eTest 1 'unrelated:containment'
assert_issue1919_red unrelated "$case_root"

case_root="$SANDBOX/issue1919-uncovered"; clone_issue1919_fixture "$case_root"
append_summary_line "$case_root" '- `com.pocketshell.app.proof.MissingJourneyE2eTest`'
assert_issue1919_red uncovered "$case_root"
pass "(#1919-d) corrupt/duplicate/uncovered and genuine co-located failures stay RED"

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

# classify_expressions <journey-outcome> [first-timeout] [first-failure] — the
# step-output map for one scenario.
classify_expressions() {
  local journey_outcome="$1" first_timeout="${2:-false}" first_failure="${3:-true}"
  cat <<JSONEOF
{
  "steps.journey.outcome": "$journey_outcome",
  "steps.journey_retry.outcome": "failure",
  "steps.journey.conclusion": "$journey_outcome",
  "steps.journey_retry.conclusion": "failure",
  "steps.journey_summary.outputs.first_timeout": "$first_timeout",
  "steps.journey_summary.outputs.first_failure": "$first_failure",
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

# (#1919-t2) The exact four-attempt fixture must traverse the REAL workflow
# body, receive the new typed reason/warning, and remain timeout-ineligible.
sandbox="$SANDBOX/wf-foreign-framework-anr"; mkdir -p "$sandbox/artifacts"
cp -a "$ISSUE1919_FIXTURE/ci-journey" "$sandbox/artifacts/ci-journey"
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions failure)"
[[ "$CLASSIFY_TOKEN" == "INFRA" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(#1919-t2) real body must write INFRA/nonzero for proven foreign ANR"; }
grep -q '^shard_verdict_reason=foreign_framework_anr_focus$' <<<"$CLASSIFY_GH_OUTPUT" \
  || { printf '%s\n' "$CLASSIFY_GH_OUTPUT"; fail "(#1919-t2) workflow lost the typed foreign-ANR reason"; }
grep -q '::warning title=Emulator journey INFRA — foreign framework ANR owned focus' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(#1919-t2) workflow lost the typed foreign-ANR warning"; }
grep -q '::error' <<<"$CLASSIFY_OUT" \
  && fail "(#1919-t2) proven foreign ANR emitted a RED annotation"

sandbox="$SANDBOX/wf-foreign-framework-anr-timeout"; mkdir -p "$sandbox/artifacts"
cp -a "$ISSUE1919_FIXTURE/ci-journey" "$sandbox/artifacts/ci-journey"
snapshot_first_attempt "$sandbox"
run_classify_body "$sandbox" "$(classify_expressions failure true true)"
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(#1919-t2) timeout must override foreign-ANR evidence and stay RED"; }
grep -q 'foreign framework ANR owned focus' <<<"$CLASSIFY_OUT" \
  && fail "(#1919-t2) timeout entered the foreign-ANR INFRA branch"
pass "(#1919-t2) real workflow maps proven foreign ANR to INFRA, while timeout stays RED"

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
run_classify_body "$sandbox" "$(classify_expressions success false false)"
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
