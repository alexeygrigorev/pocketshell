#!/usr/bin/env bash
# Self-test for scripts/ci-retry-signature.py (issue #2459).
#
# Stubs `gh` on PATH so this runs with no network, no real repo, no
# authentication — `gh api` calls return canned JSON, and `gh run download`
# writes canned JUnit XML fixtures into the requested destination directory
# (exactly what the real `gh run download` would do, just deterministic).
# Exercises: job-level failure names, class-level failure names extracted
# from journey-prefixed artifacts via the REAL
# scripts/nightly-failure-recurrence.py `emit_from_xml` (imported, not
# reimplemented), union-across-shards + dedup, a shard artifact with no
# failing XML (silently skipped, not degraded), a failed jobs-listing call
# (status=degraded, class-level lookup still attempted independently), a
# failed artifacts-listing call (status=degraded), and the --out file mirror.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-retry-signature.py"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"
command -v python3 >/dev/null 2>&1 || fail "python3 is required for this self-test"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/bin"

FAILING_XML='<?xml version="1.0" encoding="UTF-8"?>
<testsuite>
  <testcase classname="com.pocketshell.app.hosts.ReconnectJourneyE2eTest" name="reconnectAfterBackground" time="1.2">
    <failure message="boom">stack trace</failure>
  </testcase>
  <testcase classname="com.pocketshell.app.hosts.ReconnectJourneyE2eTest" name="passingOne" time="0.5"/>
</testsuite>'

FAILING_XML_SHARD2='<?xml version="1.0" encoding="UTF-8"?>
<testsuite>
  <testcase classname="com.pocketshell.app.projects.MultiSessionSwitchJourneyE2eTest" name="switchesBetweenSessions" time="2.0">
    <error message="timeout">stack</error>
  </testcase>
</testsuite>'

# $1 = mode
write_fake_gh() {
  local mode="$1"
  cat > "$SANDBOX/bin/gh" <<FAKEGH
#!/usr/bin/env bash
mode="$mode"
if [[ "\$1 \$2" == "api"* ]]; then :; fi
if [[ "\$1" == "api" ]]; then
  path="\$2"
  case "\$path" in
    *"/jobs?per_page=100")
      if [[ "\$mode" == "jobs_fail" ]]; then
        echo "boom" >&2
        exit 1
      fi
      echo '{"jobs": [
        {"name": "Unit tests", "conclusion": "failure"},
        {"name": "Python utility tests (pocketshell)", "conclusion": "success"},
        {"name": "Emulator journey subset (load-bearing, Docker agents) (0)", "conclusion": "failure"},
        {"name": "Emulator journey subset (load-bearing, Docker agents) (2)", "conclusion": "failure"}
      ]}'
      ;;
    *"/artifacts?per_page=100")
      if [[ "\$mode" == "artifacts_fail" ]]; then
        echo "boom" >&2
        exit 1
      fi
      if [[ "\$mode" == "no_journey_artifacts" ]]; then
        echo '{"artifacts": [{"name": "unit-test-reports-debug"}]}'
      else
        echo '{"artifacts": [
          {"name": "emulator-journey-android-test-reports-shard-0"},
          {"name": "emulator-journey-android-test-reports-shard-1"},
          {"name": "emulator-journey-android-test-reports-shard-2"}
        ]}'
      fi
      ;;
    *) echo '{}' ;;
  esac
  exit 0
elif [[ "\$1" == "run" && "\$2" == "download" ]]; then
  # args: run download RUN_ID --repo REPO -n NAME -D DEST
  name=""
  dest=""
  shift 2
  while [[ \$# -gt 0 ]]; do
    case "\$1" in
      -n) name="\$2"; shift 2 ;;
      -D) dest="\$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  mkdir -p "\$dest/androidTest-results"
  case "\$name" in
    *shard-0)
      cat > "\$dest/androidTest-results/TEST-shard0.xml" <<'XML'
$FAILING_XML
XML
      ;;
    *shard-2)
      cat > "\$dest/androidTest-results/TEST-shard2.xml" <<'XML'
$FAILING_XML_SHARD2
XML
      ;;
    *shard-1)
      : # no XML at all for this shard (empty dir) -> emit_from_xml raises,
        # caught and skipped, must NOT degrade overall status.
      ;;
  esac
  exit 0
else
  echo "unhandled fake gh invocation: \$*" >&2
  exit 1
fi
FAKEGH
  chmod +x "$SANDBOX/bin/gh"
}

run_target() {
  PATH="$SANDBOX/bin:$PATH" python3 "$TARGET" \
    --repo owner/repo --run-id 123 --workdir "$SANDBOX/work-$RANDOM" "$@"
}

# 1. Normal case: 2 shards with failing XML (0 and 2), shard 1 has no XML.
write_fake_gh normal
out="$(run_target --journey-artifact-prefix emulator-journey-android-test-reports-shard-)"
echo "$out" | grep -qx "job:Unit tests" || fail "must include failed job Unit tests: $out"
echo "$out" | grep -qx "job:Emulator journey subset (load-bearing, Docker agents) (0)" || fail "must include failed journey shard 0 job: $out"
echo "$out" | grep -q "^job:Python" && fail "must NOT include Python (it succeeded): $out"
echo "$out" | grep -qx "class:com.pocketshell.app.hosts.ReconnectJourneyE2eTest#reconnectAfterBackground" || fail "must include the failing class from shard 0: $out"
echo "$out" | grep -qx "class:com.pocketshell.app.projects.MultiSessionSwitchJourneyE2eTest#switchesBetweenSessions" || fail "must include the failing class from shard 2: $out"
echo "$out" | grep -q "passingOne" && fail "must NOT include a passing test: $out"
echo "$out" | grep -qx "# status=ok" || fail "a fully successful capture must report status=ok: $out"
pass "job-level + class-level failures aggregate across shards, dedup passing tests excluded, status=ok"

# 2. No journey-prefixed artifacts present at all -> job-level only, still ok.
write_fake_gh no_journey_artifacts
out="$(run_target --journey-artifact-prefix emulator-journey-android-test-reports-shard-)"
echo "$out" | grep -qx "job:Unit tests" || fail "job-level lines must still appear: $out"
echo "$out" | grep -q "^class:" && fail "no class lines expected when no journey artifacts match: $out"
echo "$out" | grep -qx "# status=ok" || fail "no matching artifacts is not a degraded capture: $out"
pass "no matching journey artifacts -> job-level signature only, still status=ok"

# 3. No --journey-artifact-prefix supplied at all -> class-level lookup skipped entirely.
write_fake_gh normal
out="$(run_target)"
echo "$out" | grep -q "^class:" && fail "omitting --journey-artifact-prefix must skip class-level lookup entirely: $out"
pass "omitting --journey-artifact-prefix skips class-level lookup"

# 4. Jobs API call fails -> status=degraded, but class-level lookup is
#    attempted independently and still succeeds.
write_fake_gh jobs_fail
out="$(run_target --journey-artifact-prefix emulator-journey-android-test-reports-shard-)"
echo "$out" | grep -q "^job:" && fail "a failed jobs listing must produce zero job: lines, not invented ones: $out"
echo "$out" | grep -qx "class:com.pocketshell.app.hosts.ReconnectJourneyE2eTest#reconnectAfterBackground" || fail "class-level lookup must still run independently of the jobs-listing failure: $out"
echo "$out" | grep -q "^# status=degraded:job listing failed" || fail "a failed jobs listing must report a degraded status: $out"
pass "a failed jobs-listing API call degrades status but does not abort class-level lookup"

# 5. Artifacts API call fails -> status=degraded, job-level still present.
write_fake_gh artifacts_fail
out="$(run_target --journey-artifact-prefix emulator-journey-android-test-reports-shard-)"
echo "$out" | grep -qx "job:Unit tests" || fail "job-level lines must still appear when only the artifacts listing fails: $out"
echo "$out" | grep -q "^class:" && fail "no class lines expected when the artifacts listing failed: $out"
echo "$out" | grep -q "^# status=degraded:journey artifact listing failed" || fail "a failed artifacts listing must report a degraded status: $out"
pass "a failed artifacts-listing API call degrades status but does not abort job-level lookup"

# 6. --out file mirrors stdout.
write_fake_gh normal
out_file="$SANDBOX/sig-out.txt"
stdout_out="$(PATH="$SANDBOX/bin:$PATH" python3 "$TARGET" --repo owner/repo --run-id 123 \
  --workdir "$SANDBOX/work-out" --journey-artifact-prefix emulator-journey-android-test-reports-shard- \
  --out "$out_file")"
[[ -f "$out_file" ]] || fail "--out file was not written"
diff <(printf '%s\n' "$stdout_out") <(cat "$out_file") >/dev/null || fail "--out file must mirror stdout exactly"
pass "--out file mirrors stdout exactly"

echo "OK: $pass_count self-test case(s) passed."
