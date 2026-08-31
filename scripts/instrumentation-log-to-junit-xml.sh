#!/usr/bin/env bash
# `adb shell am instrument -w -r` output -> JUnit XML (issue #2435).
#
# WHY THIS EXISTS
#
# A handful of androidTest classes are deliberately NOT run through Gradle's
# connectedAndroidTest task. They need one host-owned `am instrument` process
# per method (external force-stop between phases, #2264), or a detached
# on-device process the host only polls (the 10-minute LongRunning hold, #148).
# Gradle is what normally turns the runner's `INSTRUMENTATION_STATUS` stream
# into `build/outputs/androidTest-results/**/TEST-*.xml`; a direct
# `am instrument` call produces no host-side XML at all.
#
# That is an ACCOUNTING gap, not a coverage gap, and it had a real consequence:
# `check-test-execution-ledger.sh --verify` (#2063/#2082) reported
# `LongRunningSessionStabilityTest` and `LastSessionProcessRestartProofTest` as
# NEVER EXECUTED even though both run on a lane every night / every release, so
# the release job's ledger step could never conclude success and the #2356
# `validated-rc` marker was unreachable (#2435).
#
# This converter closes the gap the honest way: it reads what the runner
# actually reported and re-encodes it in the format the ledger already parses.
# It does NOT synthesise a result from an exit code — no `INSTRUMENTATION_STATUS`
# block for a test means no testcase for that test, and zero testcases is a hard
# failure, never an empty-but-passing XML.
#
# STATUS CODE VOCABULARY (androidx.test InstrumentationResultPrinter)
#     1  test started        0  passed
#    -1  error              -2  assertion failure
#    -3  @Ignore            -4  assumption failure (org.junit.Assume)
# -3/-4 are emitted as `<skipped/>`, which the ledger deliberately does NOT
# count as coverage — an all-skipped class stays uncredited, exactly as it does
# through the Gradle path.
#
# USAGE
#   instrumentation-log-to-junit-xml.sh --log FILE --out FILE [--suite NAME]
#   instrumentation-log-to-junit-xml.sh --self-test
#
#   --log FILE     raw `am instrument -w -r` transcript (\r tolerated)
#   --out FILE     JUnit XML destination; parent directories are created
#   --suite NAME   <testsuite name="..."> (default: the first class seen)
#   --require-class FQCN
#                  fail unless that class produced at least one NON-skipped
#                  testcase. Use it wherever the caller already knows which
#                  class the run was supposed to credit, so a runner that
#                  silently selected nothing cannot be mistaken for evidence.

set -uo pipefail

LOG=""
OUT=""
SUITE=""
REQUIRE_CLASS=""
SELF_TEST=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --log) LOG="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --suite) SUITE="$2"; shift 2 ;;
    --require-class) REQUIRE_CLASS="$2"; shift 2 ;;
    --self-test) SELF_TEST=1; shift ;;
    -h|--help) sed -n '2,46p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# convert <log> <out> <suite> <require-class>
#
# Returns 1 (and writes nothing) when the transcript contains no testcase, or
# when --require-class named a class with no non-skipped case. Failing closed
# is the point: an absent result must not become a green ledger entry.
# ---------------------------------------------------------------------------
instrumentation_junit_convert() {
  local log="$1" out="$2" suite="$3" require_class="$4"

  if [[ ! -f "$log" ]]; then
    echo "::error title=Instrumentation JUnit XML (issue #2435)::instrumentation log not found: $log" >&2
    return 1
  fi

  local tmp
  tmp="$(mktemp)" || return 1

  # Emit one TSV row per completed test: class \t method \t state \t detail.
  # `stack=` is multi-line free text, so the parser only trusts lines that
  # carry the `INSTRUMENTATION_STATUS: ` prefix and resets its accumulator on
  # every terminal status code.
  tr -d '\r' < "$log" | awk -F'\t' '
    function flush_detail() { detail = ""; in_stack = 0 }
    /^INSTRUMENTATION_STATUS: class=/ {
      cls = substr($0, length("INSTRUMENTATION_STATUS: class=") + 1)
      in_stack = 0
      next
    }
    /^INSTRUMENTATION_STATUS: test=/ {
      test = substr($0, length("INSTRUMENTATION_STATUS: test=") + 1)
      in_stack = 0
      next
    }
    /^INSTRUMENTATION_STATUS: stack=/ {
      detail = substr($0, length("INSTRUMENTATION_STATUS: stack=") + 1)
      in_stack = 1
      next
    }
    /^INSTRUMENTATION_STATUS: / { in_stack = 0; next }
    /^INSTRUMENTATION_STATUS_CODE: / {
      code = substr($0, length("INSTRUMENTATION_STATUS_CODE: ") + 1)
      gsub(/[[:space:]]/, "", code)
      if (code == "1") { flush_detail(); next }
      if (cls == "" || test == "") { flush_detail(); next }
      state = "unknown"
      if (code == "0") state = "ran"
      else if (code == "-1" || code == "-2") state = "failed"
      else if (code == "-3" || code == "-4") state = "skipped"
      else { flush_detail(); next }
      d = detail
      gsub(/\t/, " ", d)
      printf "%s\t%s\t%s\t%s\n", cls, test, state, substr(d, 1, 400)
      flush_detail()
      next
    }
    {
      # A `stack=` body continues until the next INSTRUMENTATION_ line.
      if (in_stack == 1) { detail = detail " " $0 }
    }
  ' > "$tmp"

  local rows
  # NOT `grep -c . || printf 0`: on an empty file grep prints "0" AND exits 1,
  # so the fallback appends a second "0" and the "$rows" -eq 0 test becomes a
  # syntax error that `set -uo pipefail` (no -e) walks straight past — an empty
  # <testsuite tests="0"> would then be written as if it were evidence.
  rows="$(awk 'END { print NR }' "$tmp")"
  if [[ "$rows" -eq 0 ]]; then
    rm -f "$tmp"
    echo "::error title=Instrumentation JUnit XML (issue #2435)::no completed INSTRUMENTATION_STATUS test in $log — refusing to write an empty JUnit XML" >&2
    return 1
  fi

  if [[ -n "$require_class" ]]; then
    if ! awk -F'\t' -v want="$require_class" '$1 == want && $3 == "ran" { found = 1 } END { exit found ? 0 : 1 }' "$tmp"; then
      rm -f "$tmp"
      echo "::error title=Instrumentation JUnit XML (issue #2435)::$log has no NON-skipped result for $require_class — an all-skipped or absent class is not evidence" >&2
      return 1
    fi
  fi

  local first_class
  first_class="$(head -n 1 "$tmp" | cut -f1)"
  [[ -n "$suite" ]] || suite="$first_class"

  mkdir -p "$(dirname "$out")" || { rm -f "$tmp"; return 1; }

  # `time` is not reported per-test by the runner, so it is emitted as 0. The
  # ledger keys off ran/skipped, not duration; nothing downstream reads it as a
  # budget. Writing a made-up duration would be worse than writing none.
  awk -F'\t' -v suite="$suite" '
    function esc(s) {
      gsub(/&/, "\\&amp;", s); gsub(/</, "\\&lt;", s); gsub(/>/, "\\&gt;", s)
      gsub(/"/, "\\&quot;", s)
      gsub(/[^\t\n\r\040-\176]/, "?", s)
      return s
    }
    { rows[NR] = $0; total++ ; if ($3 == "failed") failures++; else if ($3 == "skipped") skipped++ }
    END {
      printf "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      printf "<testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" errors=\"0\" skipped=\"%d\" time=\"0\">\n",
        esc(suite), total, failures + 0, skipped + 0
      for (i = 1; i <= NR; i++) {
        n = split(rows[i], f, "\t")
        printf "  <testcase name=\"%s\" classname=\"%s\" time=\"0\"",
          esc(f[2]), esc(f[1])
        if (f[3] == "ran") { printf "/>\n" }
        else if (f[3] == "skipped") {
          printf ">\n    <skipped message=\"%s\"/>\n  </testcase>\n", esc(f[4])
        } else {
          printf ">\n    <failure message=\"%s\"/>\n  </testcase>\n", esc(f[4])
        }
      }
      printf "</testsuite>\n"
    }
  ' "$tmp" > "$out" || { rm -f "$tmp"; return 1; }

  local ran
  ran="$(awk -F'\t' '$3 == "ran"' "$tmp" | wc -l | tr -d ' ')"
  rm -f "$tmp"
  printf 'instrumentation JUnit XML: %s (%s testcase(s), %s non-skipped)\n' "$out" "$rows" "$ran"
  return 0
}

# ---------------------------------------------------------------------------
# Self-test. No adb, no emulator: fixtures are verbatim-shaped runner
# transcripts, and the load-bearing assertion is that the REAL ledger script
# credits (or refuses to credit) the produced XML.
# ---------------------------------------------------------------------------
_self_test() {
  local root failures=0
  root="$(mktemp -d)"
  trap 'rm -rf "$root"' RETURN

  local ledger_script
  ledger_script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-test-execution-ledger.sh"

  _ok() { printf '  ok: %s\n' "$1"; }
  _bad() { printf '  FAIL: %s\n' "$1"; failures=$((failures + 1)); }

  # --- fixture 1: one passing test, one @Ignore, one assertion failure ------
  cat > "$root/pass.log" <<'LOG'
INSTRUMENTATION_STATUS: class=com.example.FooTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: stream=
com.example.FooTest:
INSTRUMENTATION_STATUS: test=alpha
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.FooTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: test=alpha
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: class=com.example.FooTest
INSTRUMENTATION_STATUS: current=2
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: test=beta
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.FooTest
INSTRUMENTATION_STATUS: current=2
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: test=beta
INSTRUMENTATION_STATUS_CODE: -3
INSTRUMENTATION_STATUS: class=com.example.FooTest
INSTRUMENTATION_STATUS: current=3
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: test=gamma
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.FooTest
INSTRUMENTATION_STATUS: current=3
INSTRUMENTATION_STATUS: numtests=3
INSTRUMENTATION_STATUS: stack=junit.framework.AssertionFailedError: expected:<1> but was:<2>
	at com.example.FooTest.gamma(FooTest.kt:42)
INSTRUMENTATION_STATUS: test=gamma
INSTRUMENTATION_STATUS_CODE: -2
INSTRUMENTATION_RESULT: stream=
Time: 1.234
OK (1 test)
INSTRUMENTATION_CODE: -1
LOG

  if instrumentation_junit_convert "$root/pass.log" "$root/out/TEST-Foo.xml" "" "" >/dev/null 2>&1; then
    _ok "converts a mixed pass/ignore/failure transcript"
  else
    _bad "conversion of the mixed transcript failed"
  fi

  if grep -q '<testcase name="alpha" classname="com.example.FooTest" time="0"/>' "$root/out/TEST-Foo.xml"; then
    _ok "a passing test is a self-closing (non-skipped) testcase"
  else
    _bad "passing test was not emitted as a self-closing testcase"
    cat "$root/out/TEST-Foo.xml" >&2 || true
  fi
  if grep -q 'name="beta"' "$root/out/TEST-Foo.xml" &&
    awk '/name="beta"/,/<\/testcase>/' "$root/out/TEST-Foo.xml" | grep -q '<skipped'; then
    _ok "an @Ignore test is emitted as <skipped/>"
  else
    _bad "@Ignore test was not emitted as <skipped/>"
  fi
  if awk '/name="gamma"/,/<\/testcase>/' "$root/out/TEST-Foo.xml" | grep -q '<failure'; then
    _ok "an assertion failure is emitted as <failure/>"
  else
    _bad "assertion failure was not emitted as <failure/>"
  fi
  if grep -q 'expected:&lt;1&gt; but was:&lt;2&gt;' "$root/out/TEST-Foo.xml"; then
    _ok "failure detail is XML-escaped"
  else
    _bad "failure detail was not XML-escaped"
  fi

  # The load-bearing assertion: the REAL ledger script credits the class.
  local ledger="$root/ledger.tsv"
  if bash "$ledger_script" --record "$root/out" --ledger "$ledger" --tier selftest --now 1700000000 >/dev/null 2>&1 &&
    grep -q '^com.example.FooTest	1700000000	selftest$' "$ledger"; then
    _ok "check-test-execution-ledger.sh --record credits the converted class"
  else
    _bad "the real ledger script did not credit the converted class"
    cat "$ledger" 2>/dev/null >&2 || true
  fi

  # --- fixture 2: an ALL-skipped class must NOT be credited ----------------
  cat > "$root/skipped.log" <<'LOG'
INSTRUMENTATION_STATUS: class=com.example.SkippedTest
INSTRUMENTATION_STATUS: test=onlyOne
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.SkippedTest
INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: opt-in flag absent
INSTRUMENTATION_STATUS: test=onlyOne
INSTRUMENTATION_STATUS_CODE: -4
INSTRUMENTATION_CODE: -1
LOG
  instrumentation_junit_convert "$root/skipped.log" "$root/skip/TEST-Skipped.xml" "" "" >/dev/null 2>&1
  local skip_ledger="$root/skip-ledger.tsv"
  bash "$ledger_script" --record "$root/skip" --ledger "$skip_ledger" --tier selftest --now 1700000000 >/dev/null 2>&1
  if ! grep -q 'com.example.SkippedTest' "$skip_ledger" 2>/dev/null; then
    _ok "an assumption-skipped class is NOT credited by the real ledger"
  else
    _bad "an all-skipped class was credited as executed"
  fi

  # --- fixture 3: --require-class rejects an all-skipped run ---------------
  if instrumentation_junit_convert "$root/skipped.log" "$root/req/TEST-x.xml" "" \
    "com.example.SkippedTest" >/dev/null 2>&1; then
    _bad "--require-class accepted an all-skipped class"
  else
    _ok "--require-class rejects an all-skipped class"
  fi
  if [[ -e "$root/req/TEST-x.xml" ]]; then
    _bad "a rejected conversion still wrote an XML file"
  else
    _ok "a rejected conversion writes no XML"
  fi

  # --- fixture 4: a transcript with NO completed test fails closed ---------
  cat > "$root/empty.log" <<'LOG'
INSTRUMENTATION_STATUS: id=ActivityManagerService
INSTRUMENTATION_STATUS: Error=Unable to find instrumentation target package
INSTRUMENTATION_STATUS_CODE: -1
INSTRUMENTATION_FAILED: com.example.test/androidx.test.runner.AndroidJUnitRunner
LOG
  if instrumentation_junit_convert "$root/empty.log" "$root/empty/TEST-y.xml" "" "" >/dev/null 2>&1; then
    _bad "a transcript with no completed test produced an XML"
  else
    _ok "a transcript with no completed test fails closed"
  fi

  # --- fixture 5: CRLF transcript (adb shell line endings) -----------------
  printf 'INSTRUMENTATION_STATUS: class=com.example.CrlfTest\r\nINSTRUMENTATION_STATUS: test=one\r\nINSTRUMENTATION_STATUS_CODE: 1\r\nINSTRUMENTATION_STATUS: class=com.example.CrlfTest\r\nINSTRUMENTATION_STATUS: test=one\r\nINSTRUMENTATION_STATUS_CODE: 0\r\n' \
    > "$root/crlf.log"
  if instrumentation_junit_convert "$root/crlf.log" "$root/crlf/TEST-crlf.xml" "" \
    "com.example.CrlfTest" >/dev/null 2>&1; then
    _ok "a CRLF adb transcript converts"
  else
    _bad "a CRLF adb transcript did not convert"
  fi

  # --- fixture 6: a missing log file fails closed --------------------------
  if instrumentation_junit_convert "$root/does-not-exist.log" "$root/none/TEST-z.xml" "" "" >/dev/null 2>&1; then
    _bad "a missing instrumentation log was accepted"
  else
    _ok "a missing instrumentation log fails closed"
  fi

  if [[ "$failures" -eq 0 ]]; then
    printf 'PASS: instrumentation-log-to-junit-xml self-test\n'
    return 0
  fi
  printf 'FAIL: instrumentation-log-to-junit-xml self-test (%s failure(s))\n' "$failures" >&2
  return 1
}

if [[ "$SELF_TEST" -eq 1 ]]; then
  _self_test
  exit $?
fi

if [[ -z "$LOG" || -z "$OUT" ]]; then
  echo "error: --log FILE and --out FILE are required (or --self-test)" >&2
  exit 1
fi

instrumentation_junit_convert "$LOG" "$OUT" "$SUITE" "$REQUIRE_CLASS"
exit $?
