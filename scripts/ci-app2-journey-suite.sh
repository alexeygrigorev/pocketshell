#!/usr/bin/env bash
# Issue #2474 — run app2's WHOLE instrumented journey set on one emulator.
#
# This is the script `.github/workflows/app2.yml`'s `app2-journey` job hands to
# reactivecircus/android-emulator-runner: by the time it starts, the emulator is
# booted and the Docker `agents` (2222) + `network-fault-proxy` (2228 / API
# 8474) fixtures are up.
#
# WHY ONE UNFILTERED RUN, NOT A CLASS LIST OR A PER-CLASS MATRIX
#
# The old module's `scripts/ci-journey-suite.sh` iterates an explicit FQCN
# registry, one `connectedDebugAndroidTest` invocation per class. That shape
# buys sharding and per-class retry, and it costs the ONE property app2's set
# most needs today: every journey runs in a FRESH instrumentation process, so
# any state one journey leaks into the next is structurally invisible. Running
# all 11 classes together in a single instrumentation run is exactly how issue
# #2477's cross-journey pollution was found; a per-class lane would have been
# green through it. So: no `-Pandroid.testInstrumentationRunnerArguments.class`
# filter, ever, and the self-test below asserts that no filter can creep back
# in. Revisit only when app2's suite is large enough to need sharding — and then
# shard so each shard still runs its classes in ONE process.
#
# EXIT-CODE DISCIPLINE
#
# The gradle invocation is piped through `tee`, which is exactly the shape
# docs/ci-pitfalls.md catalogues as exit-code laundering. `run_suite` reads
# PIPESTATUS and returns gradle's own rc (falling back to tee's, so a broken
# pipe cannot pass either). `--self-test` drives that red->green with a stub
# gradle, no emulator required.
#
# USAGE
#   scripts/ci-app2-journey-suite.sh            # the CI entry point
#   scripts/ci-app2-journey-suite.sh --self-test
#
# Overridable for the self-test and for a local reproduction:
#   POCKETSHELL_APP2_JOURNEY_GRADLE     gradle launcher (default ./gradlew)
#   POCKETSHELL_APP2_JOURNEY_ARTIFACTS  artifact root (default artifacts/app2-journey)
#   POCKETSHELL_APP2_JOURNEY_APP_ID     applicationId whose external files dir
#                                       holds the journey screenshots
#   POCKETSHELL_APP2_JOURNEY_GRADLE_EXTRA_ARGS
#                                       extra gradle args, space-separated (e.g.
#                                       -Pandroid.testInstrumentationRunnerArguments.agentsPort=2474
#                                       for a local run against a private fixture)

set -uo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

GRADLE_CMD="${POCKETSHELL_APP2_JOURNEY_GRADLE:-./gradlew}"
ARTIFACT_DIR="${POCKETSHELL_APP2_JOURNEY_ARTIFACTS:-artifacts/app2-journey}"
APP_ID="${POCKETSHELL_APP2_JOURNEY_APP_ID:-com.pocketshell.next}"
ADB="${ADB:-adb}"

# The one task this lane runs. Kept in a named variable so the self-test can
# assert it, and so the "no class filter" property is checkable rather than a
# comment.
JOURNEY_TASK=":app2:connectedDebugAndroidTest"

gradle_args() {
  printf '%s\n' \
    "$JOURNEY_TASK" \
    "--no-build-cache" \
    "--no-daemon" \
    "--stacktrace" \
    "--console=plain" \
    "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
  local extra
  for extra in ${POCKETSHELL_APP2_JOURNEY_GRADLE_EXTRA_ARGS:-}; do
    printf '%s\n' "$extra"
  done
}

# Runs the suite, tees the output to $1, and returns GRADLE's exit code.
run_suite() {
  local log="$1"
  local -a args=()
  local line
  while IFS= read -r line; do args+=("$line"); done < <(gradle_args)

  "$GRADLE_CMD" "${args[@]}" 2>&1 | tee "$log"
  local -a status=("${PIPESTATUS[@]}")
  local gradle_rc="${status[0]:-1}"
  local tee_rc="${status[1]:-1}"
  if [[ "$gradle_rc" -ne 0 ]]; then
    return "$gradle_rc"
  fi
  if [[ "$tee_rc" -ne 0 ]]; then
    echo "::error title=app2 journey suite::gradle succeeded but tee failed (rc=$tee_rc); treating the run as failed rather than trusting a truncated log." >&2
    return "$tee_rc"
  fi
  return 0
}

start_logcat() {
  "$ADB" logcat -c >/dev/null 2>&1 || true
  "$ADB" logcat -v threadtime > "$ARTIFACT_DIR/logcat.txt" 2>&1 &
  echo $!
}

collect_device_evidence() {
  # Journey screenshots (JourneyScreenshots.capture) land in the app's external
  # files dir. Best-effort: absent screenshots must never redden a green suite.
  "$ADB" pull "/sdcard/Android/data/$APP_ID/files" "$ARTIFACT_DIR/screenshots" \
    >/dev/null 2>&1 || true
  "$ADB" shell dumpsys meminfo > "$ARTIFACT_DIR/meminfo.txt" 2>&1 || true
}

main() {
  cd "$ROOT_DIR" || exit 1
  mkdir -p "$ARTIFACT_DIR"

  echo "app2 journey lane: running the FULL suite in ONE instrumentation run (issue #2474)"
  echo "gradle command: $GRADLE_CMD"
  gradle_args | sed 's/^/  arg: /'

  local logcat_pid=""
  logcat_pid="$(start_logcat)"

  run_suite "$ARTIFACT_DIR/gradle.log"
  local rc=$?

  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" 2>/dev/null || true
  fi
  collect_device_evidence

  if [[ "$rc" -ne 0 ]]; then
    echo "::error title=app2 journey suite::$JOURNEY_TASK failed (rc=$rc). Reports: app2/build/reports/androidTests/connected/, raw log: $ARTIFACT_DIR/gradle.log, device log: $ARTIFACT_DIR/logcat.txt" >&2
  fi
  return "$rc"
}

self_test() {
  local failures=0
  local checks=0

  check() {
    checks=$((checks + 1))
    if [[ "$2" != "$3" ]]; then
      echo "FAIL $1: expected '$3', got '$2'" >&2
      failures=$((failures + 1))
    fi
  }

  local tmp
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" RETURN

  # 1. A failing gradle must fail the script — the tee-laundering trap. `false`
  #    exits 1 and prints nothing, so a script that returned tee's rc (0) would
  #    pass here.
  GRADLE_CMD="false" run_suite "$tmp/red.log"
  check "failing gradle propagates" "$?" "1"

  # 2. ...and a green one must still pass, so check 1 is not vacuous.
  GRADLE_CMD="true" run_suite "$tmp/green.log"
  check "passing gradle passes" "$?" "0"

  # 3. Gradle's own exit code, not a flattened 1, reaches the caller.
  cat > "$tmp/gradle-42" <<'STUB'
#!/usr/bin/env bash
exit 42
STUB
  chmod +x "$tmp/gradle-42"
  GRADLE_CMD="$tmp/gradle-42" run_suite "$tmp/rc42.log"
  check "gradle rc is preserved" "$?" "42"

  # 4. The task really is app2's connected suite.
  check "task" "$(gradle_args | head -n 1)" ":app2:connectedDebugAndroidTest"

  # 5. THE load-bearing property (issue #2477): no class filter, so all 11
  #    journeys share one instrumentation process and cross-journey state
  #    pollution is observable. Checked against the assembled argv AND the
  #    script source, so neither a code path nor a copy-paste can smuggle one in.
  local filtered=no
  if gradle_args | grep -q 'testInstrumentationRunnerArguments\.class'; then
    filtered=yes
  fi
  check "no class filter in argv" "$filtered" "no"

  # Executable lines only: everything above `self_test() {`, minus comments —
  # the header comment above deliberately NAMES the forbidden flag so a reader
  # knows what is banned and why, and that must not trip its own guard.
  local in_source=no
  if sed -n '1,/^self_test() {/p' "${BASH_SOURCE[0]}" |
    grep -v '^[[:space:]]*#' |
    grep -q 'testInstrumentationRunnerArguments\.class'; then
    in_source=yes
  fi
  check "no class filter in source" "$in_source" "no"

  # ...and that scan is not vacuous: the same scan over a copy WITH a filter
  # line spliced in must find it.
  cp "${BASH_SOURCE[0]}" "$tmp/mutant.sh"
  sed -i '1a MUTANT_ARG="-Pandroid.testInstrumentationRunnerArguments.class=Foo"' "$tmp/mutant.sh"
  local mutant_caught=no
  if sed -n '1,/^self_test() {/p' "$tmp/mutant.sh" |
    grep -v '^[[:space:]]*#' |
    grep -q 'testInstrumentationRunnerArguments\.class'; then
    mutant_caught=yes
  fi
  check "source scan catches a spliced-in filter" "$mutant_caught" "yes"

  # 6. A caller-supplied extra arg is forwarded (the local-fixture-port path).
  local forwarded=no
  if POCKETSHELL_APP2_JOURNEY_GRADLE_EXTRA_ARGS="-PsomeFlag=1" gradle_args |
    grep -qx -- "-PsomeFlag=1"; then
    forwarded=yes
  fi
  check "extra args are forwarded" "$forwarded" "yes"

  # 7. run_suite is not the entry point CI calls — `main` is, and it does work
  #    AFTER the suite (killing logcat, pulling screenshots) that could swallow
  #    the failure. Drive the real entry point end to end with a stub gradle and
  #    a stub adb, both directions.
  local prev_artifacts="$ARTIFACT_DIR" prev_adb="$ADB"
  ARTIFACT_DIR="$tmp/main-red"
  ADB="/bin/true"
  GRADLE_CMD="$tmp/gradle-42" main >/dev/null 2>&1
  check "main propagates a failing suite" "$?" "42"
  ARTIFACT_DIR="$tmp/main-green"
  GRADLE_CMD="true" main >/dev/null 2>&1
  check "main passes a green suite" "$?" "0"
  ARTIFACT_DIR="$prev_artifacts"
  ADB="$prev_adb"

  if [[ "$failures" -ne 0 ]]; then
    echo "ci-app2-journey-suite.sh --self-test: $failures/$checks check(s) FAILED" >&2
    return 1
  fi
  echo "ci-app2-journey-suite.sh --self-test: $checks check(s) passed"
  return 0
}

case "${1:-}" in
  --self-test)
    self_test
    exit $?
    ;;
  "")
    main
    exit $?
    ;;
  *)
    echo "usage: $0 [--self-test]" >&2
    exit 2
    ;;
esac
