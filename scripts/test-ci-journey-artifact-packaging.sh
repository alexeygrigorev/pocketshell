#!/usr/bin/env bash
# shellcheck disable=SC2034,SC2154 # Nameref-populated fixture arrays.
# Issue #1781: deterministic, JVM-free regression for first-cold-boot artifact
# packaging. Exercises the production snapshot helper and the actual workflow
# upload-path block against #1458-shaped evidence.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
HELPER="${CI_JOURNEY_ARTIFACT_HELPER:-$SCRIPT_DIR/ci-journey-preserve-android-outputs.sh}"
SETTINGS_PARSER="${CI_JOURNEY_SETTINGS_PARSER:-$SCRIPT_DIR/ci-journey-settings-project-dirs.py}"
WORKFLOW="${CI_JOURNEY_ARTIFACT_WORKFLOW:-$REPO_ROOT/.github/workflows/tests.yml}"
SETTINGS="${CI_JOURNEY_SETTINGS_FILE:-$REPO_ROOT/settings.gradle.kts}"
BUDGET_FUNCTIONS="$SCRIPT_DIR/ci-journey-budget-functions.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

hash_tree() {
  local root="$1"
  (
    cd "$root" || exit 1
    find . -type f -print0 \
      | LC_ALL=C sort -z \
      | xargs -0 -r sha256sum
  )
}

seed_fixture() {
  local root="$1"
  local attempt="$root/artifacts/ci-journey/class-attempts/app/com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest--dca691208a3658f4/attempt-1"
  local focus_attempt="$root/artifacts/ci-journey/class-attempts/app/com.pocketshell.app.session.ShowKeyboardChipE2eTest--8569a6475d403956/attempt-2"

  mkdir -p \
    "$root/build/reports/androidTests/connected/debug" \
    "$root/app/build/reports/androidTests/connected/debug" \
    "$root/shared/group/deep module/build/outputs/androidTest-results/connected/debug" \
    "$root/shared/group/deep module/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/diagnostics" \
    "$attempt/android-test-outputs/app/build/reports/androidTests/connected/debug" \
    "$attempt/android-test-outputs/app/build/outputs/androidTest-results/connected/debug" \
    "$attempt/android-test-outputs/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/agent-conversation-reconnect" \
    "$focus_attempt/android-test-outputs/app/build/outputs/androidTest-results/connected/debug" \
    "$root/artifacts/ci-journey-attempt-1"

  {
    echo 'rootProject.name = "fixture"'
    echo 'include(":app", ":deep")'
    echo 'project(":deep").projectDir = file("shared/group/deep module")'
  } > "$root/settings.gradle.kts"

  printf 'root module report\n' > "$root/build/reports/androidTests/connected/debug/index.html"
  printf 'app module report\n' > "$root/app/build/reports/androidTests/connected/debug/index.html"
  printf 'deep raw result\n' > "$root/shared/group/deep module/build/outputs/androidTest-results/connected/debug/TEST-deep.xml"
  printf 'deep logcat\n' > "$root/shared/group/deep module/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/diagnostics/logcat.txt"

  printf 'per-attempt report\n' > "$attempt/android-test-outputs/app/build/reports/androidTests/connected/debug/index.html"
  printf 'per-attempt raw junit\n' > "$attempt/android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 15-_app-.xml"
  printf 'open_ms=814\nswitch_ms=0\n' > "$attempt/android-test-outputs/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/agent-conversation-reconnect/timings.txt"
  printf 'attempt console\n' > "$attempt/attempt.log"
  printf 'device logcat\n' > "$attempt/device-logcat.txt"
  printf 'device processes\n' > "$attempt/device-processes.txt"
  printf 'activity processes\n' > "$attempt/activity-processes.txt"
  printf 'activity top\n' > "$attempt/activity-top.txt"
  printf 'fallback screenshot bytes\n' > "$attempt/failure-screen.png"
  printf '<testsuite name="journey-harness" tests="1" failures="0"/>\n' > "$attempt/journey-harness-verdict.xml"
  {
    echo 'format_version=1'
    echo 'raw_junit_status=present'
    echo 'raw_junit_count=1'
    echo 'snapshot_status=complete'
    echo 'harness_verdict_xml=journey-harness-verdict.xml'
    echo 'cleanup_status=failed'
    echo 'gradle_cleanup_exit_code=0'
    echo 'device_cleanup_exit_code=1'
    echo 'status=complete'
  } > "$attempt/manifest.txt"
  printf '<testsuite name="ShowKeyboardChipE2eTest" tests="1" failures="1"/>\n' \
    > "$focus_attempt/android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 15-_app-.xml"
  printf 'foreign launcher ANR owned dialog for show-keyboard attempt 2\n' \
    > "$focus_attempt/activity-processes.txt"
  printf 'journey summary\n' > "$root/artifacts/ci-journey/summary.md"
  printf 'first-attempt metadata\n' > "$root/artifacts/ci-journey-attempt-1/attempt-metadata.md"
}

# Issue #1809: the shard verdict token lives in its OWN directory, deliberately
# outside artifacts/ci-journey/, because it is now pre-seeded at job start and a
# token inside artifacts/ci-journey/ would land in the #1781 first-attempt
# snapshot. Keeping it separate makes "the snapshot never carries a shard token"
# structural instead of ordering-dependent.
write_current_shard_token() {
  mkdir -p "$1/artifacts/ci-journey-shard-verdict"
  printf 'CLEAN\n' > "$1/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
}

# The pre-seed the emulator-journey job writes right after checkout, BEFORE the
# suite runs and therefore before the first-attempt snapshot is taken.
write_preseeded_shard_token() {
  mkdir -p "$1/artifacts/ci-journey-shard-verdict"
  printf 'INFRA\n' > "$1/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
}

extract_workflow_snapshot_copy() {
  awk '
    /^[[:space:]]+if \[\[ -d artifacts\/ci-journey \]\]; then$/ { in_copy=1 }
    in_copy && /^[[:space:]]+scripts\/ci-journey-preserve-android-outputs\.sh / { exit }
    in_copy {
      sub(/^          /, "")
      print
    }
  ' "$WORKFLOW"
}

run_production_snapshot() {
  local root="$1"
  local snapshot="artifacts/ci-journey-attempt-1"
  local snapshot_copy

  snapshot_copy="$(extract_workflow_snapshot_copy)"
  [[ -n "$snapshot_copy" ]] || return 1
  (
    cd "$root" || exit 1
    # shellcheck disable=SC2294 # Execute the trusted production workflow block.
    eval "$snapshot_copy"
    CI_JOURNEY_REPO_ROOT="$root" \
      CI_JOURNEY_SETTINGS_FILE="$root/settings.gradle.kts" \
      CI_JOURNEY_SETTINGS_PARSER="$SETTINGS_PARSER" \
      "$HELPER" "$snapshot"
  )
}

legacy_snapshot_and_scan() {
  local root="$1"
  local snapshot="artifacts/ci-journey-attempt-1"

  (
    cd "$root" || exit 1
    mkdir -p "$snapshot/ci-journey"
    cp -a artifacts/ci-journey/. "$snapshot/ci-journey/"
    shopt -s globstar nullglob
    for path in **/build/reports/androidTests \
                **/build/outputs/androidTest-results \
                **/build/outputs/connected_android_test_additional_output; do
      [[ -e "$path" ]] || continue
      dest="$snapshot/android-test-outputs/$path"
      mkdir -p "$(dirname "$dest")"
      cp -a "$path" "$dest"
    done
  )
}

assert_real_attempt_paths() {
  local root="$1"
  local current="$root/artifacts/ci-journey/class-attempts/app/com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest--dca691208a3658f4/attempt-1"
  local first="$root/artifacts/ci-journey-attempt-1/ci-journey/class-attempts/app/com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest--dca691208a3658f4/attempt-1"
  local relative

  for relative in \
    "android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 15-_app-.xml" \
    "android-test-outputs/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/agent-conversation-reconnect/timings.txt" \
    journey-harness-verdict.xml \
    manifest.txt \
    device-logcat.txt \
    device-processes.txt \
    activity-processes.txt \
    activity-top.txt \
    failure-screen.png; do
    [[ -f "$current/$relative" ]] || fail "current #1458 evidence missing: $relative"
    [[ -f "$first/$relative" ]] || fail "first-cold-boot #1458 evidence missing: $relative"
    cmp -s "$current/$relative" "$first/$relative" \
      || fail "first-cold-boot evidence bytes changed: $relative"
  done
  grep -qx 'cleanup_status=failed' "$current/manifest.txt" \
    || fail "cleanup status was not modeled in the real manifest path"
  grep -qx 'device_cleanup_exit_code=1' "$first/manifest.txt" \
    || fail "cleanup exit evidence changed in the first-cold-boot manifest"

  local focus_current="$root/artifacts/ci-journey/class-attempts/app/com.pocketshell.app.session.ShowKeyboardChipE2eTest--8569a6475d403956/attempt-2"
  local focus_first="$root/artifacts/ci-journey-attempt-1/ci-journey/class-attempts/app/com.pocketshell.app.session.ShowKeyboardChipE2eTest--8569a6475d403956/attempt-2"
  local focus_xml="android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 15-_app-.xml"
  [[ -f "$focus_current/$focus_xml" && -f "$focus_current/activity-processes.txt" ]] \
    || fail "current #1919 class-attempt lost its paired XML/process snapshot"
  [[ -f "$focus_first/$focus_xml" && -f "$focus_first/activity-processes.txt" ]] \
    || fail "preserved #1919 class-attempt lost its paired XML/process snapshot"
  cmp -s "$focus_current/$focus_xml" "$focus_first/$focus_xml" \
    || fail "preserved #1919 class-attempt XML bytes changed"
  cmp -s "$focus_current/activity-processes.txt" "$focus_first/activity-processes.txt" \
    || fail "preserved #1919 attempt-local process snapshot bytes changed"
  cmp -s "$current/activity-processes.txt" "$focus_current/activity-processes.txt" \
    && fail "distinct class-attempt process snapshots were cross-associated"
}

echo "== #1781 legacy recursion reproduction =="
legacy_root="$SANDBOX/legacy"
seed_fixture "$legacy_root"
legacy_snapshot_and_scan "$legacy_root"
write_current_shard_token "$legacy_root"

legacy_nested_current="$legacy_root/artifacts/ci-journey-attempt-1/android-test-outputs/artifacts/ci-journey"
legacy_nested_first="$legacy_root/artifacts/ci-journey-attempt-1/android-test-outputs/artifacts/ci-journey-attempt-1/ci-journey"
[[ -d "$legacy_nested_current" ]] \
  || fail "legacy scan did not reproduce android-test-outputs/artifacts/ci-journey"
[[ -d "$legacy_nested_first" ]] \
  || fail "legacy scan did not reproduce android-test-outputs/artifacts/ci-journey-attempt-1"
legacy_recursive_roots="$(find "$legacy_root/artifacts/ci-journey-attempt-1/android-test-outputs/artifacts" \
  -type d \( -path '*/artifacts/ci-journey' -o -path '*/artifacts/ci-journey-attempt-1/ci-journey' \) | wc -l)"
legacy_archive_files="$(find "$legacy_root/artifacts" -type f | wc -l)"
legacy_archive_bytes="$(du -sb "$legacy_root/artifacts" | awk '{print $1}')"
[[ "$legacy_recursive_roots" -eq 2 ]] \
  || fail "expected two legacy recursive artifact roots, got $legacy_recursive_roots"
pass "real-shaped legacy fixture reproduces both recursive artifact roots (count=2)"

echo
echo "== #1781 authoritative Gradle settings inventory =="
[[ -x "$HELPER" ]] || fail "artifact helper missing or not executable: $HELPER"
[[ -f "$SETTINGS_PARSER" ]] || fail "settings parser missing: $SETTINGS_PARSER"
[[ -f "$WORKFLOW" && -f "$SETTINGS" ]] || fail "workflow/settings source missing"

parse_settings_into() {
  local settings_file="$1"
  local result_name="$2"
  local output="$SANDBOX/settings-$RANDOM.bin"
  local -n result="$result_name"

  python3 "$SETTINGS_PARSER" "$settings_file" > "$output" \
    || fail "settings parser rejected supported fixture: $settings_file"
  mapfile -d '' -t result < "$output"
}

assert_arrays_equal() {
  local label="$1"
  local expected_name="$2"
  local actual_name="$3"
  local -n expected="$expected_name"
  local -n actual="$actual_name"
  local index

  [[ "${#expected[@]}" -eq "${#actual[@]}" ]] \
    || fail "$label count mismatch: expected ${#expected[@]}, got ${#actual[@]}"
  for index in "${!expected[@]}"; do
    [[ "${expected[$index]}" == "${actual[$index]}" ]] \
      || fail "$label element $index mismatch: expected <${expected[$index]}>, got <${actual[$index]}>"
  done
}

assert_nul_settings_rejected() {
  local label="$1"
  local settings_text="$2"
  local root="$SANDBOX/nul-$label"
  local parser_output="$SANDBOX/nul-$label-parser.bin"
  local parser_log="$SANDBOX/nul-$label-parser.log"
  local helper_log="$SANDBOX/nul-$label-helper.log"
  local parser_rc
  local helper_rc

  mkdir -p \
    "$root/a/build/reports/androidTests/connected/debug" \
    "$root/b/build/reports/androidTests/connected/debug" \
    "$root/artifacts/ci-journey-attempt-1"
  printf 'must not copy from a\n' > "$root/a/build/reports/androidTests/connected/debug/index.html"
  printf 'must not copy from b\n' > "$root/b/build/reports/androidTests/connected/debug/index.html"
  printf '%s\n' "$settings_text" > "$root/settings.gradle.kts"

  python3 "$SETTINGS_PARSER" "$root/settings.gradle.kts" \
    > "$parser_output" 2> "$parser_log"
  parser_rc=$?
  CI_JOURNEY_REPO_ROOT="$root" \
    CI_JOURNEY_SETTINGS_FILE="$root/settings.gradle.kts" \
    CI_JOURNEY_SETTINGS_PARSER="$SETTINGS_PARSER" \
    "$HELPER" "artifacts/ci-journey-attempt-1" > "$helper_log" 2>&1
  helper_rc=$?

  [[ "$parser_rc" -ne 0 ]] || fail "$label embedded NUL was accepted by settings parser"
  [[ ! -s "$parser_output" ]] || fail "$label embedded NUL split the parser's NUL-delimited records"
  grep -q 'embedded NUL' "$parser_log" \
    || fail "$label embedded NUL parser rejection was not deterministic"
  [[ "$helper_rc" -ne 0 ]] || fail "$label embedded NUL was accepted by artifact helper"
  grep -q 'could not derive Gradle project directories' "$helper_log" \
    || fail "$label embedded NUL parser failure was not propagated by artifact helper"
  [[ -z "$(find "$root/artifacts/ci-journey-attempt-1" -type f -print -quit)" ]] \
    || fail "$label embedded NUL copied Android output before helper failure"
}

expected_repo_projects=(
  .
  app
  shared/core-ssh
  shared/core-portfwd
  shared/core-tmux
  shared/core-terminal
  shared/core-agents
  shared/core-usage
  shared/core-storage
  shared/core-voice
  shared/core-assistant
  shared/core-connection
  shared/ui-kit
  shared/test-support
)
parse_settings_into "$SETTINGS" parsed_repo_projects
assert_arrays_equal "repository settings inventory" expected_repo_projects parsed_repo_projects
pass "settings parser derives root + all ${#parsed_repo_projects[@]} repository projects element-wise"

multi_settings="$SANDBOX/settings-multi.gradle.kts"
printf 'include(":new:a", ":new:b")\n' > "$multi_settings"
expected_multi=(. new/a new/b)
parse_settings_into "$multi_settings" parsed_multi
assert_arrays_equal "multi-argument include" expected_multi parsed_multi

multiline_settings="$SANDBOX/settings-multiline.gradle.kts"
printf 'include(\n  ":multi:a",\n  ":multi:b",\n)\n' > "$multiline_settings"
expected_multiline=(. multi/a multi/b)
parse_settings_into "$multiline_settings" parsed_multiline
assert_arrays_equal "multiline include" expected_multiline parsed_multiline

remap_settings="$SANDBOX/settings-remap.gradle.kts"
{
  echo 'include(":app")'
  echo 'project(":app").projectDir = file("modules/app remap")'
} > "$remap_settings"
expected_remap=(. "modules/app remap")
parse_settings_into "$remap_settings" parsed_remap
assert_arrays_equal "projectDir remap with space" expected_remap parsed_remap

ambiguous_left="$SANDBOX/settings-ambiguous-left.gradle.kts"
ambiguous_right="$SANDBOX/settings-ambiguous-right.gradle.kts"
printf 'include(":a b", ":c")\n' > "$ambiguous_left"
printf 'include(":a", ":b c")\n' > "$ambiguous_right"
parse_settings_into "$ambiguous_left" parsed_ambiguous_left
parse_settings_into "$ambiguous_right" parsed_ambiguous_right
[[ "${parsed_ambiguous_left[*]}" == "${parsed_ambiguous_right[*]}" ]] \
  || fail "ambiguous-array fixture no longer demonstrates joined-space aliasing"
arrays_are_equal=1
if [[ "${#parsed_ambiguous_left[@]}" -ne "${#parsed_ambiguous_right[@]}" ]]; then
  arrays_are_equal=0
else
  for index in "${!parsed_ambiguous_left[@]}"; do
    [[ "${parsed_ambiguous_left[$index]}" == "${parsed_ambiguous_right[$index]}" ]] \
      || arrays_are_equal=0
  done
fi
[[ "$arrays_are_equal" -eq 0 ]] \
  || fail "element-wise inventory comparison collapsed ambiguous space-containing paths"

assert_nul_settings_rejected \
  "include" \
  'include(":a\u0000b")'
assert_nul_settings_rejected \
  "remap" \
  $'include(":app")\nproject(":app").projectDir = file("a\\u0000b")'
pass "multi-arg, multiline, remap-with-space, and element-wise ambiguity fixtures derive exact directories"
pass "decoded include/remap NULs fail parser + helper with no record split or copied output"

echo
echo "== #1781 corrected production snapshot + module packaging =="
fixed_root="$SANDBOX/fixed"
seed_fixture "$fixed_root"
# Issue #1809: model the pre-seeded token existing BEFORE the snapshot is taken.
write_preseeded_shard_token "$fixed_root"
current_root="$fixed_root/artifacts/ci-journey"
snapshot_root="$fixed_root/artifacts/ci-journey-attempt-1"
current_hashes_before="$(hash_tree "$current_root")"
current_tree_files="$(wc -l <<<"$current_hashes_before")"
current_tree_digest="$(sha256sum <<<"$current_hashes_before" | awk '{print $1}')"
root_report_hash="$(sha256sum "$fixed_root/build/reports/androidTests/connected/debug/index.html" | awk '{print $1}')"
app_report_hash="$(sha256sum "$fixed_root/app/build/reports/androidTests/connected/debug/index.html" | awk '{print $1}')"
deep_raw_hash="$(sha256sum "$fixed_root/shared/group/deep module/build/outputs/androidTest-results/connected/debug/TEST-deep.xml" | awk '{print $1}')"
deep_logcat_hash="$(sha256sum "$fixed_root/shared/group/deep module/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/diagnostics/logcat.txt" | awk '{print $1}')"
timings_hash="$(sha256sum "$current_root/class-attempts/app/com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest--dca691208a3658f4/attempt-1/android-test-outputs/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/agent-conversation-reconnect/timings.txt" | awk '{print $1}')"

run_production_snapshot "$fixed_root" || fail "production workflow snapshot path failed"

[[ "$(hash_tree "$current_root")" == "$current_hashes_before" ]] \
  || fail "production helper modified current canonical ci-journey bytes"
[[ "$(hash_tree "$snapshot_root/ci-journey")" == "$current_hashes_before" ]] \
  || fail "production helper did not byte-copy the canonical first-attempt tree"

[[ "$(sha256sum "$snapshot_root/android-test-outputs/build/reports/androidTests/connected/debug/index.html" | awk '{print $1}')" == "$root_report_hash" ]] \
  || fail "root-project Android report hash changed"
[[ "$(sha256sum "$snapshot_root/android-test-outputs/app/build/reports/androidTests/connected/debug/index.html" | awk '{print $1}')" == "$app_report_hash" ]] \
  || fail "app Android report hash changed"
[[ "$(sha256sum "$snapshot_root/android-test-outputs/shared/group/deep module/build/outputs/androidTest-results/connected/debug/TEST-deep.xml" | awk '{print $1}')" == "$deep_raw_hash" ]] \
  || fail "nested/space project raw result hash changed"
[[ "$(sha256sum "$snapshot_root/android-test-outputs/shared/group/deep module/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/diagnostics/logcat.txt" | awk '{print $1}')" == "$deep_logcat_hash" ]] \
  || fail "nested/space project diagnostic hash changed"
[[ ! -e "$snapshot_root/android-test-outputs/artifacts" ]] \
  || fail "production helper recursively copied an artifact tree"

write_current_shard_token "$fixed_root"
assert_real_attempt_paths "$fixed_root"
[[ -f "$fixed_root/artifacts/ci-journey-shard-verdict/shard-verdict.txt" ]] \
  || fail "current shard token missing"
[[ ! -e "$current_root/shard-verdict.txt" ]] \
  || fail "the shard token must not live inside artifacts/ci-journey/ (issue #1809: it is pre-seeded, and artifacts/ci-journey/ is byte-copied into the first-attempt snapshot)"
[[ ! -e "$snapshot_root/ci-journey/shard-verdict.txt" ]] \
  || fail "first-cold-boot snapshot incorrectly contains the later-written shard token"
[[ -f "$current_root/summary.md" && -f "$snapshot_root/ci-journey/summary.md" ]] \
  || fail "current or first-cold-boot summary missing"

current_attempt_count="$(find "$current_root/class-attempts" -type d -name 'attempt-*' | wc -l)"
first_attempt_count="$(find "$snapshot_root/ci-journey/class-attempts" -type d -name 'attempt-*' | wc -l)"
module_output_root_count="$(find "$snapshot_root/android-test-outputs" \
  -type d \( -path '*/build/reports/androidTests' \
             -o -path '*/build/outputs/androidTest-results' \
             -o -path '*/build/outputs/connected_android_test_additional_output' \) | wc -l)"
fixed_archive_files="$(find "$fixed_root/artifacts" -type f | wc -l)"
fixed_archive_bytes="$(du -sb "$fixed_root/artifacts" | awk '{print $1}')"
[[ "$current_attempt_count" -eq 2 && "$first_attempt_count" -eq 2 ]] \
  || fail "canonical attempt counts changed: current=$current_attempt_count first=$first_attempt_count"
[[ "$module_output_root_count" -eq 4 ]] \
  || fail "expected four checked-project output roots, got $module_output_root_count"
[[ "$fixed_archive_files" -lt "$legacy_archive_files" && "$fixed_archive_bytes" -lt "$legacy_archive_bytes" ]] \
  || fail "corrected archive did not shrink: legacy=$legacy_archive_files/$legacy_archive_bytes fixed=$fixed_archive_files/$fixed_archive_bytes"
pass "deep/space project and root project copied; hashes unchanged; recursive roots=0"
pass "byte digests: attempt_tree_files=$current_tree_files attempt_tree=$current_tree_digest timings=$timings_hash root=$root_report_hash app=$app_report_hash deep_raw=$deep_raw_hash deep_logcat=$deep_logcat_hash"
pass "archive counts: current_attempts=2 first_attempts=2 module_output_roots=4 legacy_files=$legacy_archive_files fixed_files=$fixed_archive_files"
pass "archive bytes: legacy=$legacy_archive_bytes fixed=$fixed_archive_bytes"

echo
echo "== #1781 symlink escape rejection before copy =="
outside_root="$SANDBOX/outside"
mkdir -p "$outside_root/build/reports/androidTests"
printf 'outside secret\n' > "$outside_root/build/reports/androidTests/secret.txt"

project_link_root="$SANDBOX/project-link"
mkdir -p "$project_link_root/artifacts/ci-journey" "$project_link_root/artifacts/ci-journey-attempt-1"
printf 'must not copy\n' > "$project_link_root/artifacts/ci-journey/summary.md"
ln -s "$outside_root" "$project_link_root/outside module"
printf 'include(":outside module")\n' > "$project_link_root/settings.gradle.kts"
project_link_rc=0
CI_JOURNEY_REPO_ROOT="$project_link_root" \
  CI_JOURNEY_SETTINGS_FILE="$project_link_root/settings.gradle.kts" \
  CI_JOURNEY_SETTINGS_PARSER="$SETTINGS_PARSER" \
  "$HELPER" "artifacts/ci-journey-attempt-1" \
  > "$SANDBOX/project-link.out" 2>&1 || project_link_rc=$?
[[ "$project_link_rc" -ne 0 ]] || fail "symlinked outside projectDir was accepted"
grep -q 'projectDir has symlink ancestry' "$SANDBOX/project-link.out" \
  || fail "projectDir symlink rejection was not deterministic"
[[ ! -e "$project_link_root/artifacts/ci-journey-attempt-1/android-test-outputs" ]] \
  || fail "projectDir validation failure copied Android outputs"

build_link_root="$SANDBOX/build-link"
mkdir -p "$build_link_root/app" "$build_link_root/artifacts/ci-journey" "$build_link_root/artifacts/ci-journey-attempt-1"
printf 'must not copy\n' > "$build_link_root/artifacts/ci-journey/summary.md"
ln -s "$outside_root/build" "$build_link_root/app/build"
printf 'include(":app")\n' > "$build_link_root/settings.gradle.kts"
build_link_rc=0
CI_JOURNEY_REPO_ROOT="$build_link_root" \
  CI_JOURNEY_SETTINGS_FILE="$build_link_root/settings.gradle.kts" \
  CI_JOURNEY_SETTINGS_PARSER="$SETTINGS_PARSER" \
  "$HELPER" "artifacts/ci-journey-attempt-1" \
  > "$SANDBOX/build-link.out" 2>&1 || build_link_rc=$?
[[ "$build_link_rc" -ne 0 ]] || fail "symlinked outside build root was accepted"
grep -q 'build path has symlink ancestry' "$SANDBOX/build-link.out" \
  || fail "build-root symlink rejection was not deterministic"
[[ ! -e "$build_link_root/artifacts/ci-journey-attempt-1/android-test-outputs" ]] \
  || fail "build-root validation failure copied Android outputs"
pass "outside projectDir and build-root symlinks fail before any Android-output copy"

echo
echo "== #1781 snapshot/destination symlink rejection =="
snapshot_link_root="$SANDBOX/snapshot-link"
mkdir -p "$snapshot_link_root/artifacts/real-snapshot"
printf 'rootProject.name = "snapshot-link"\n' > "$snapshot_link_root/settings.gradle.kts"
ln -s real-snapshot "$snapshot_link_root/artifacts/snapshot-link"
snapshot_link_rc=0
CI_JOURNEY_REPO_ROOT="$snapshot_link_root" \
  CI_JOURNEY_SETTINGS_FILE="$snapshot_link_root/settings.gradle.kts" \
  CI_JOURNEY_SETTINGS_PARSER="$SETTINGS_PARSER" \
  "$HELPER" "artifacts/snapshot-link" \
  > "$SANDBOX/snapshot-link.out" 2>&1 || snapshot_link_rc=$?
[[ "$snapshot_link_rc" -ne 0 ]] || fail "repo-local snapshot symlink was accepted"
grep -q 'snapshot path has symlink ancestry: artifacts/snapshot-link' "$SANDBOX/snapshot-link.out" \
  || fail "repo-local snapshot symlink rejection was not deterministic"
[[ ! -e "$snapshot_link_root/artifacts/real-snapshot/android-test-outputs-missing.txt" ]] \
  || fail "snapshot symlink rejection wrote through the link"

destination_link_root="$SANDBOX/destination-link"
destination_outside="$SANDBOX/destination-outside"
mkdir -p \
  "$destination_link_root/app/build/reports/androidTests/connected/debug" \
  "$destination_link_root/artifacts/ci-journey-attempt-1" \
  "$destination_outside"
printf 'include(":app")\n' > "$destination_link_root/settings.gradle.kts"
printf 'must remain source-only\n' > "$destination_link_root/app/build/reports/androidTests/connected/debug/index.html"
destination_source_hash="$(sha256sum "$destination_link_root/app/build/reports/androidTests/connected/debug/index.html" | awk '{print $1}')"
ln -s "$destination_outside" "$destination_link_root/artifacts/ci-journey-attempt-1/android-test-outputs"
destination_link_rc=0
CI_JOURNEY_REPO_ROOT="$destination_link_root" \
  CI_JOURNEY_SETTINGS_FILE="$destination_link_root/settings.gradle.kts" \
  CI_JOURNEY_SETTINGS_PARSER="$SETTINGS_PARSER" \
  "$HELPER" "artifacts/ci-journey-attempt-1" \
  > "$SANDBOX/destination-link.out" 2>&1 || destination_link_rc=$?
[[ "$destination_link_rc" -ne 0 ]] || fail "symlinked android-test-outputs destination was accepted"
grep -q 'snapshot destination has symlink ancestry: artifacts/ci-journey-attempt-1/android-test-outputs' \
  "$SANDBOX/destination-link.out" \
  || fail "android-test-outputs symlink rejection was not deterministic"
[[ -z "$(find "$destination_outside" -mindepth 1 -print -quit)" ]] \
  || fail "destination symlink escape copied files outside the snapshot"
[[ "$(sha256sum "$destination_link_root/app/build/reports/androidTests/connected/debug/index.html" | awk '{print $1}')" == "$destination_source_hash" ]] \
  || fail "destination rejection modified the source report"
pass "repo-local snapshot link and outside android-test-outputs link fail before writes"

echo
echo "== #1781 missing-output semantics =="
empty_root="$SANDBOX/empty"
mkdir -p "$empty_root/artifacts/ci-journey-attempt-1"
printf 'rootProject.name = "empty"\n' > "$empty_root/settings.gradle.kts"
CI_JOURNEY_REPO_ROOT="$empty_root" \
  CI_JOURNEY_SETTINGS_FILE="$empty_root/settings.gradle.kts" \
  CI_JOURNEY_SETTINGS_PARSER="$SETTINGS_PARSER" \
  bash -c '
    set -euo pipefail
    root="$1"
    helper="$2"
    snapshot="artifacts/ci-journey-attempt-1"
    snapshot_copy="$3"
    cd "$root"
    eval "$snapshot_copy"
    CI_JOURNEY_REPO_ROOT="$root" CI_JOURNEY_SETTINGS_FILE="$root/settings.gradle.kts" \
      CI_JOURNEY_SETTINGS_PARSER="$4" \
      "$helper" "$snapshot"
  ' _ "$empty_root" "$HELPER" "$(extract_workflow_snapshot_copy)" "$SETTINGS_PARSER" \
  || fail "production workflow failed on a no-output/missing-summary first attempt"
grep -qx 'No Android connected-test output directories existed after the first attempt.' \
  "$empty_root/artifacts/ci-journey-attempt-1/android-test-outputs-missing.txt" \
  || fail "missing-output marker text changed"
grep -qx 'artifacts/ci-journey was missing after the first attempt.' \
  "$empty_root/artifacts/ci-journey-attempt-1/ci-journey-missing.txt" \
  || fail "missing ci-journey marker text changed"
grep -qx 'artifacts/ci-journey/summary.md was missing after the first attempt.' \
  "$empty_root/artifacts/ci-journey-attempt-1/summary-missing.txt" \
  || fail "missing summary marker text changed"
pass "all pre-existing missing-evidence marker semantics are unchanged"

echo
echo "== #1781 actual workflow snapshot/upload guards =="
[[ -f "$BUDGET_FUNCTIONS" ]] || fail "missing attempt capture helper: $BUDGET_FUNCTIONS"
grep -Fq '"activity processes" "$attempt_dir/activity-processes.txt"' "$BUDGET_FUNCTIONS" \
  || fail "failed attempts no longer capture activity-processes.txt inside their own class-attempt directory"
grep -Fq 'journey_adb -s "$serial" shell dumpsys activity processes' "$BUDGET_FUNCTIONS" \
  || fail "attempt-local activity-processes.txt is no longer sourced from dumpsys activity processes"
pass "#1919 process ownership snapshot is captured inside each failed class-attempt bundle"
preserve_step="$(sed -n \
  '/- name: Preserve first journey attempt diagnostics/,/- name: Check remaining job wall before journey retry/p' \
  "$WORKFLOW")"
# shellcheck disable=SC2016 # Literal workflow shell variable, not a test var.
grep -Fq 'scripts/ci-journey-preserve-android-outputs.sh "$snapshot"' <<<"$preserve_step" \
  || fail "workflow does not call the production snapshot helper"
# shellcheck disable=SC2016 # Literal production workflow variable, not a test var.
grep -Fq 'cp -a artifacts/ci-journey/. "$snapshot/ci-journey/"' <<<"$preserve_step" \
  || fail "production workflow lost the canonical ci-journey copy"
if grep -q '\*\*/build' "$HELPER" || grep -q '\*\*/build' <<<"$preserve_step"; then
  fail "broad recursive Android build-output scan was restored"
fi
# Issue #1809 replaced the ordering guarantee with a STRUCTURAL one: the shard
# token is written outside artifacts/ci-journey/, so no step order can leak it
# into the first-attempt snapshot. Pin that instead of the old line ordering
# (the token is now pre-seeded at job start, so an ordering pin would be false).
grep -q 'path: artifacts/ci-journey-shard-verdict/shard-verdict.txt' "$WORKFLOW" \
  || fail "shard verdict token upload must read artifacts/ci-journey-shard-verdict/shard-verdict.txt"
grep -q 'artifacts/ci-journey/shard-verdict.txt' "$WORKFLOW" \
  && fail "the shard token must not be written or uploaded from inside artifacts/ci-journey/ — it would land in the first-attempt snapshot"

upload_step="$(sed -n \
  '/- name: Upload Android test reports/,/- name: Upload Docker logs/p' \
  "$WORKFLOW")"
mapfile -t upload_paths < <(
  awk '
    /^[[:space:]]+path: \|[[:space:]]*$/ { in_paths=1; next }
    in_paths && /^[[:space:]]+if-no-files-found:/ { exit }
    in_paths {
      sub(/^[[:space:]]+/, "")
      if (length > 0) print
    }
  ' <<<"$upload_step"
)
printf '%s\n' "${upload_paths[@]}" | grep -Fxq 'artifacts/ci-journey/' \
  || fail "actual Android-report upload step lost artifacts/ci-journey/"
printf '%s\n' "${upload_paths[@]}" | grep -Fxq 'artifacts/ci-journey-attempt-1/' \
  || fail "actual Android-report upload step lost artifacts/ci-journey-attempt-1/"
printf '%s\n' "${upload_paths[@]}" | grep -Fxq 'artifacts/ci-journey-shard-verdict/' \
  || fail "actual Android-report upload step lost artifacts/ci-journey-shard-verdict/ (issue #1809: the token must stay in the forensic bundle)"

upload_manifest="$SANDBOX/upload-manifest.txt"
: > "$upload_manifest"
for upload_path in "${upload_paths[@]}"; do
  [[ "$upload_path" == *'*'* ]] && continue
  upload_source="$fixed_root/${upload_path%/}"
  [[ -d "$upload_source" ]] || continue
  while IFS= read -r -d '' uploaded_file; do
    uploaded_relative="${uploaded_file#"$fixed_root/"}"
    printf '%s  %s\n' "$(sha256sum "$uploaded_file" | awk '{print $1}')" "$uploaded_relative" \
      >> "$upload_manifest"
  done < <(find "$upload_source" -type f -print0)
done
grep -q '  artifacts/ci-journey/summary.md$' "$upload_manifest" \
  || fail "actual upload paths do not package the canonical summary"
grep -q '  artifacts/ci-journey-shard-verdict/shard-verdict.txt$' "$upload_manifest" \
  || fail "actual upload paths do not package the current shard token"
grep -q '  artifacts/ci-journey-attempt-1/ci-journey/summary.md$' "$upload_manifest" \
  || fail "actual upload paths do not package the first-cold-boot summary"
if grep -q 'artifacts/ci-journey-attempt-1/ci-journey/shard-verdict.txt$' "$upload_manifest"; then
  fail "upload model incorrectly found a later-written token in the first snapshot"
fi
upload_manifest_count="$(wc -l < "$upload_manifest")"
upload_manifest_digest="$(sha256sum "$upload_manifest" | awk '{print $1}')"
pass "actual upload block packages both canonical roots ($upload_manifest_count hashed files, digest=$upload_manifest_digest)"

echo
echo "ALL TESTS PASSED: scripts/test-ci-journey-artifact-packaging.sh"
