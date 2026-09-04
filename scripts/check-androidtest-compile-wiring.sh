#!/usr/bin/env bash
# check-androidtest-compile-wiring.sh — issues #1720, #2117
#
# THE HAZARD THIS EXISTS FOR
#
# For a long time NO per-PR CI job compiled the `androidTest` source set. The
# required `Unit tests` lane compiles `main` + unit `test` only; `androidTest`
# compiled solely in the batched emulator job that runs after merge. So a change
# that privatises, renames, or removes a test-visible API passed every required
# PR check and only broke AFTER merge — where it breaks EVERY connected /
# emulator / nightly / release gate at once.
#
# It happened. #1635 made `OutboundQueueAutoFlushController`'s constructor
# private, added a `boundTo` factory, and updated its OWN unit test — but left
# the sibling androidTest `Issue1686QueueDrainWireOracleDockerTest` on the
# removed 1-arg form. `Unit tests` stayed green, `:app:compileDebugAndroidTestKotlin`
# went red on `main` (#1720), and it was found only because an unrelated PR's
# rebase happened to compile that source set.
#
# #2117 closes the gap by compiling the source set in the `dex` job. But a
# compile step is only a gate while it is WIRED: a step that exists in a job the
# required `Unit tests` check does not need, or a step someone deletes in a
# hurry, closes nothing while still reading like protection. That failure would
# be invisible — the workflow keeps a plausible-looking step and the class of
# breakage silently returns. So the wiring is asserted mechanically, per push:
#
#   W1  `unit-gate` still exists and is named exactly `Unit tests`
#       (the literal check name `main`'s ruleset requires). Without this, "in a
#       required lane" is not a property anything holds.
#   W2  `unit-gate`'s `needs:` list is non-empty and parses
#       (a gate that needs nothing gates nothing).
#   W3  EVERY module with tracked `src/androidTest/` sources has its androidTest
#       Kotlin compiled by a real `gradlew` invocation inside a job that
#       `unit-gate` needs. Not "somewhere in the workflow" — inside the required
#       lane. Comment lines do not count.
#   W4  at least one androidTest source root was discovered
#       ("I could not find any" must never read the same as "all are covered").
#
# W3 is deliberately per-MODULE rather than per-task-string. Today only `:app`
# has an androidTest source set, so a literal grep would look identical; the
# moment a `shared/core-*` module grows one, a literal grep stays green over an
# uncompiled source set while this reddens. That is the class, not the instance.
#
# An `assemble<Variant>AndroidTest` task strictly implies the Kotlin compile, so
# it is accepted as an equivalent. Running the tests is NOT required and NOT
# checked — running stays in the batched emulator lane (#2117 non-goal).
#
#   scripts/check-androidtest-compile-wiring.sh              # check the real workflow
#   scripts/check-androidtest-compile-wiring.sh --self-test  # prove the checks can go red
#
# No JVM, no Gradle, no Android SDK, no network. Runs in `guards-static`, which
# is itself one of the jobs the required check needs.

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_WORKFLOW="$ROOT_DIR/.github/workflows/tests.yml"

# The aggregator that carries the protected-branch check name.
GATE_JOB="unit-gate"
GATE_CHECK_NAME="Unit tests"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# --- parsing -----------------------------------------------------------------
# The workflow's shape is fixed and small, so these are targeted extractors, not
# a YAML parser. Each fails loudly when it finds nothing.

job_keys() {
  # Top-level job keys: exactly two spaces of indent, inside the `jobs:` mapping.
  awk '
    !injobs { if ($0 ~ /^jobs:[[:space:]]*$/) injobs = 1; next }
    /^  [A-Za-z0-9_-]+:[[:space:]]*(#.*)?$/ {
      key = $0
      sub(/^  /, "", key)
      sub(/:.*$/, "", key)
      print key
    }
  ' "$1"
}

job_block() {
  # Everything under job `$2`, up to the next top-level job key.
  awk -v want="$2" '
    !injobs { if ($0 ~ /^jobs:[[:space:]]*$/) injobs = 1; next }
    /^  [A-Za-z0-9_-]+:[[:space:]]*(#.*)?$/ {
      key = $0
      sub(/^  /, "", key)
      sub(/:.*$/, "", key)
      inblock = (key == want)
      next
    }
    inblock { print }
  ' "$1"
}

live_gradle_lines() {
  # stdin -> the `gradlew` COMMANDS a shell would actually execute, one per line.
  #
  # Two things a naive grep gets wrong, and both are silent:
  #   - a comment is not an invocation (the whole point of #2117 is that a step
  #     which merely MENTIONS the task protects nothing), so `#` lines go first;
  #   - a task listed on a `\`-continuation line belongs to the command that
  #     started above it, so continuations are JOINED before matching. Without
  #     the join, moving a task onto its own line silently un-covers the module
  #     while the workflow still runs it — a false RED that invites the wrong fix.
  grep -v '^[[:space:]]*#' |
    awk '{
      cur = $0
      if (buf != "") { cur = buf " " cur; buf = "" }
      if (cur ~ /\\[ \t]*$/) { sub(/\\[ \t]*$/, "", cur); buf = cur; next }
      print cur
    } END { if (buf != "") print buf }' |
    grep -F 'gradlew' || true
}

sorted_unique() {
  { grep -v '^[[:space:]]*$' || true; } | LC_ALL=C sort -u
}

# --- module discovery --------------------------------------------------------

androidtest_modules() {
  # Gradle project paths of every module with TRACKED androidTest sources.
  # `app/src/androidTest/...` -> `:app`; `shared/core-ssh/src/androidTest/...`
  # -> `:shared:core-ssh`. Tracked-only on purpose: an untracked scratch file in
  # a sibling worktree must not change this guard's verdict.
  #
  # Every filter is `|| true`-guarded: under `pipefail` a no-match `grep` exits 1
  # and would kill the script with NO message, which is the W4 case (no sources
  # found) silently wearing a crash's clothes. W4 must be able to SAY it.
  local scan_root="$1"
  git -C "$scan_root" ls-files |
    { grep -E '(^|/)src/androidTest/' || true; } |
    sed -E 's#(^|/)src/androidTest/.*$##' |
    { grep -v '^$' || true; } |
    sed -E 's#^#:#; s#/#:#g' |
    sorted_unique
}

check_workflow() {
  local workflow="$1"
  local scan_root="${2:-$ROOT_DIR}"
  [ -f "$workflow" ] || fail "workflow not found: $workflow"

  if ! git -C "$scan_root" rev-parse --git-dir >/dev/null 2>&1; then
    fail "\`$scan_root\` is not a git checkout, so the androidTest source-set scan could not run. 'I could not check' is not 'I checked and it is fine'."
  fi

  local all_jobs
  all_jobs="$(job_keys "$workflow" | sorted_unique)"
  [ -n "$all_jobs" ] || fail "no top-level jobs parsed out of $workflow — the extractor is broken or the file changed shape"

  printf '%s\n' "$all_jobs" | grep -qx "$GATE_JOB" ||
    fail "W1: the aggregator job \`$GATE_JOB\` is gone from $workflow; nothing carries the required \`$GATE_CHECK_NAME\` check, so no job is in a required lane"

  local block
  block="$(job_block "$workflow" "$GATE_JOB")"
  [ -n "$block" ] || fail "W1: could not read the \`$GATE_JOB\` block out of $workflow"

  # --- W1: the ruleset-visible check name ---------------------------------
  local gate_name
  gate_name="$(printf '%s\n' "$block" | sed -n 's/^    name:[[:space:]]*\(.*[^[:space:]]\)[[:space:]]*$/\1/p' | head -1)"
  [ -n "$gate_name" ] || fail "W1: \`$GATE_JOB\` has no \`name:\`"
  if [ "$gate_name" != "$GATE_CHECK_NAME" ]; then
    fail "W1: \`$GATE_JOB\` is named '$gate_name', but branch protection requires the literal check name '$GATE_CHECK_NAME'. The androidTest compile would no longer sit behind a required check."
  fi

  # --- W2: the required lane's membership ---------------------------------
  if ! printf '%s\n' "$block" | grep -qE '^    needs:[[:space:]]*\['; then
    fail "W2: \`$GATE_JOB\` has no flow-sequence \`needs: [...]\` line. This guard reads that exact form on purpose — if the list is reformatted, update the guard rather than letting it read nothing."
  fi
  local needs_line needs
  needs_line="$(printf '%s\n' "$block" | sed -n 's/^    needs:[[:space:]]*\[\(.*\)\][[:space:]]*$/\1/p' | head -1)"
  needs="$(printf '%s' "$needs_line" | tr ',' '\n' | tr -d ' ' | sorted_unique)"
  [ -n "$needs" ] || fail "W2: \`$GATE_JOB\` needs nothing — no job is in the required lane, so nothing it might compile is gated"

  # The executable Gradle lines of every job the required check actually needs.
  local required_lane_gradle=""
  local j
  while IFS= read -r j; do
    printf '%s\n' "$all_jobs" | grep -qx "$j" || continue
    required_lane_gradle="$required_lane_gradle
$(job_block "$workflow" "$j" | live_gradle_lines)"
  done <<< "$needs"

  # --- W4/W3: every androidTest source set is compiled in that lane --------
  local modules
  modules="$(androidtest_modules "$scan_root")"
  if [ -z "$modules" ]; then
    fail "W4: no tracked \`src/androidTest/\` sources found under $scan_root. Either the discovery is broken or the source sets are gone; a guard that finds nothing to check must not report success."
  fi

  local uncovered="" covered=""
  local m m_re task_re
  while IFS= read -r m; do
    [ -n "$m" ] || continue
    # `:app` -> `:app:compileDebugAndroidTestKotlin` (or any variant), or the
    # strictly-stronger `:app:assembleDebugAndroidTest`. The module path is
    # regex-escaped: a `.` in a directory name would otherwise be a wildcard,
    # and a wildcard here fails OPEN (one module's task covering another).
    m_re="$(printf '%s' "$m" | sed -e 's/[.[\*^$+?(){}|]/\\&/g')"
    task_re="${m_re}:(compile[A-Za-z0-9]*AndroidTestKotlin|assemble[A-Za-z0-9]*AndroidTest)([[:space:]]|$|\\\\)"
    if printf '%s\n' "$required_lane_gradle" | grep -qE -- "$task_re"; then
      covered="$covered $m"
    else
      uncovered="$uncovered $m"
    fi
  done <<< "$modules"

  if [ -n "${uncovered// /}" ]; then
    fail "W3: module(s) with an androidTest source set that NO job in the required \`$GATE_CHECK_NAME\` lane compiles:${uncovered}

That is the #1720 gap. Privatising, renaming or removing a test-visible API in
one of those modules will pass every required PR check and go red only AFTER
merge, breaking every connected/emulator/nightly/release gate at once.

Add \`<module>:compileDebugAndroidTestKotlin\` to a gradlew invocation in a job
that \`$GATE_JOB\` needs (today: \`dex\`). Compiling is enough — running the
tests stays in the batched emulator lane.
Required-lane jobs checked: $(printf '%s' "$needs" | tr '\n' ' ')"
  fi

  echo "OK: every androidTest source set is compiled inside the required \`$GATE_CHECK_NAME\` lane."
  echo "    required-lane jobs : $(printf '%s' "$needs" | tr '\n' ' ')"
  echo "    androidTest modules: ${covered# }"
}

# --- self-test ---------------------------------------------------------------
# Each case mutates a COPY of the real workflow (or points the source scan at a
# sandbox git repo) and asserts this guard goes red for the intended reason. The
# pass count is asserted at the end, so the anti-vacuous guard cannot itself pass
# vacuously. Nothing under the real worktree is ever written.

SELFTEST_EXPECTED_CASES=10
selftest_passed=0
selftest_failed=0
EXPECT_SCAN_ROOT=""

st_ok() {
  echo "  ok   $*"
  selftest_passed=$((selftest_passed + 1))
}

st_bad() {
  echo "  FAIL $*" >&2
  selftest_failed=$((selftest_failed + 1))
}

run_guard_under_test() {
  if [ -n "$EXPECT_SCAN_ROOT" ]; then
    "${BASH_SOURCE[0]}" --workflow "$1" --scan-root "$EXPECT_SCAN_ROOT" 2>&1
  else
    "${BASH_SOURCE[0]}" --workflow "$1" 2>&1
  fi
}

expect_red() {
  local label="$1" want="$2" file="$3" out rc
  set +e
  out="$(run_guard_under_test "$file")"
  rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    st_bad "$label: guard stayed GREEN on the mutated input"
    return
  fi
  case "$out" in
    *"$want"*) st_ok "$label (red, matched '$want')" ;;
    *) st_bad "$label: red, but for the wrong reason — wanted '$want', got:
$out" ;;
  esac
}

expect_green() {
  local label="$1" file="$2" out rc
  set +e
  out="$(run_guard_under_test "$file")"
  rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    st_ok "$label (green)"
  else
    st_bad "$label: guard went RED on unmutated input:
$out"
  fi
}

self_test() {
  local src="$DEFAULT_WORKFLOW"
  [ -f "$src" ] || fail "self-test needs the real workflow at $src"
  # Global, not `local`: the EXIT trap fires after this function returns, and a
  # `local` would be unbound there — turning a PASSING self-test into exit 1
  # from the cleanup handler.
  SELFTEST_SANDBOX="$(mktemp -d)"
  trap 'rm -rf "${SELFTEST_SANDBOX:-}"' EXIT
  local sandbox="$SELFTEST_SANDBOX"

  echo "== check-androidtest-compile-wiring self-test =="

  # Case 0 — the real workflow must be green, or every red below is meaningless.
  expect_green "0 real workflow" "$src"

  # Case 1 — THE HEADLINE CASE: the compile invocation deleted. This is the state
  #          the repo was in before #2117, and the state a hurried revert restores.
  local m1="$sandbox/m1.yml"
  grep -v '^          ./gradlew --no-daemon --stacktrace --max-workers=4 \\$' "$src" > "$m1"
  cmp -s "$src" "$m1" && st_bad "1 mutation did not apply" || \
    expect_red "1 compile invocation removed" "W3" "$m1"

  # Case 2 — THE OTHER HEADLINE CASE: the step still exists and still compiles
  #          the source sets, but in a job the required check does NOT need. It
  #          reads exactly like protection and gates nothing. This is why W3 is
  #          scoped to the required lane rather than to the whole file.
  local m2="$sandbox/m2.yml"
  sed 's/^    needs: \[unit, guards-static, guards-ci-harness, guards-test-selection, dex\]$/    needs: [unit, guards-static, guards-ci-harness, guards-test-selection]/' "$src" > "$m2"
  cmp -s "$src" "$m2" && st_bad "2 mutation did not apply" || \
    expect_red "2 compiling job dropped out of the required lane" "W3" "$m2"

  # Case 3 — the invocation commented out. A step that MENTIONS the task is not
  #          a step that runs it, and a `#`-blind grep would stay green here.
  local m3="$sandbox/m3.yml"
  sed 's|^          ./gradlew --no-daemon --stacktrace --max-workers=4 \\$|          # ./gradlew --no-daemon --stacktrace --max-workers=4|' "$src" > "$m3"
  cmp -s "$src" "$m3" && st_bad "3 mutation did not apply" || \
    expect_red "3 invocation commented out" "W3" "$m3"

  # Case 3b — SELECTIVITY: drop ONE module's task and leave the others. An
  #           all-or-nothing check stays green here; W3 must name exactly the
  #           module that lost coverage, and only it.
  local m3b="$sandbox/m3b.yml"
  grep -v '^            :shared:core-terminal:compileDebugAndroidTestKotlin \\$' "$src" > "$m3b"
  if cmp -s "$src" "$m3b"; then
    st_bad "3b mutation did not apply"
  else
    local out3b rc3b
    set +e
    out3b="$(run_guard_under_test "$m3b")"
    rc3b=$?
    set -e
    case "$rc3b:$out3b" in
      0:*) st_bad "3b: guard stayed GREEN with :shared:core-terminal uncovered" ;;
      *":app"*) st_bad "3b: over-broad — reddened for :app too, which is still compiled:
$out3b" ;;
      *":shared:core-terminal"*) st_ok "3b one module dropped (red, and only for :shared:core-terminal)" ;;
      *) st_bad "3b: red for the wrong reason:
$out3b" ;;
    esac
  fi

  # Case 4 — the required check renamed out from under branch protection: the
  #          compile still runs, behind nothing.
  local m4="$sandbox/m4.yml"
  awk '
    /^  unit-gate:$/ { ingate = 1 }
    ingate && /^    name: Unit tests$/ { print "    name: Unit tests (fast)"; ingate = 0; next }
    { print }
  ' "$src" > "$m4"
  cmp -s "$src" "$m4" && st_bad "4 mutation did not apply" || \
    expect_red "4 required check renamed" "W1" "$m4"

  # Case 5 — an empty needs list: nothing is in the required lane at all.
  local m5="$sandbox/m5.yml"
  awk '
    /^    needs: \[unit, guards-static/ { print "    needs: []"; next }
    { print }
  ' "$src" > "$m5"
  cmp -s "$src" "$m5" && st_bad "5 mutation did not apply" || \
    expect_red "5 empty needs list" "W2" "$m5"

  # Cases 6 and 7 exercise the per-MODULE class coverage against a SANDBOX git
  # checkout. Planting files in the real worktree to test a `git ls-files` scan
  # would be exactly the shared-file cross-agent damage the process catalogue
  # documents, so the sandbox is its own tiny git repository.
  local scan_sandbox="$sandbox/scan-repo"
  mkdir -p "$scan_sandbox/app2/src/androidTest/java"
  git -C "$scan_sandbox" init -q
  git -C "$scan_sandbox" config user.email selftest@example.invalid
  git -C "$scan_sandbox" config user.name selftest
  printf 'class AppProof\n' > "$scan_sandbox/app2/src/androidTest/java/AppProof.kt"
  git -C "$scan_sandbox" add -A
  git -C "$scan_sandbox" commit -qm base

  EXPECT_SCAN_ROOT="$scan_sandbox"
  expect_green "6 sandbox with only :app2 androidTest" "$src"

  # Case 7 — a SECOND module grows an androidTest source set that no required-lane
  #          job compiles. A literal `:app2:compileDebugAndroidTestKotlin` grep
  #          stays green here; the class check is what reddens.
  mkdir -p "$scan_sandbox/shared/core-newmod/src/androidTest/java"
  printf 'class NewModProof\n' > "$scan_sandbox/shared/core-newmod/src/androidTest/java/NewModProof.kt"
  git -C "$scan_sandbox" add -A
  expect_red "7 a new module's androidTest source set is uncompiled" ":shared:core-newmod" "$src"
  EXPECT_SCAN_ROOT=""

  # Case 8 — no androidTest sources discovered at all. A guard that finds nothing
  #          must say so, not report success.
  local empty_sandbox="$sandbox/empty-repo"
  mkdir -p "$empty_sandbox"
  git -C "$empty_sandbox" init -q
  git -C "$empty_sandbox" config user.email selftest@example.invalid
  git -C "$empty_sandbox" config user.name selftest
  printf 'x\n' > "$empty_sandbox/README.md"
  git -C "$empty_sandbox" add -A
  git -C "$empty_sandbox" commit -qm base
  EXPECT_SCAN_ROOT="$empty_sandbox"
  expect_red "8 no androidTest sources discovered" "W4" "$src"
  EXPECT_SCAN_ROOT=""

  echo
  echo "$selftest_passed passed, $selftest_failed failed"
  if [ "$selftest_failed" -ne 0 ]; then
    fail "self-test had $selftest_failed failing case(s)"
  fi
  if [ "$selftest_passed" -ne "$SELFTEST_EXPECTED_CASES" ]; then
    fail "self-test ran $selftest_passed case(s), expected exactly $SELFTEST_EXPECTED_CASES — a case was skipped or silently dropped"
  fi
  echo "OK: all $SELFTEST_EXPECTED_CASES self-test cases behaved as expected."
}

# --- entrypoint ---------------------------------------------------------------
MODE="check"
WORKFLOW="$DEFAULT_WORKFLOW"
SCAN_ROOT="$ROOT_DIR"
while [ $# -gt 0 ]; do
  case "$1" in
    --self-test) MODE="selftest"; shift ;;
    --workflow) WORKFLOW="$2"; shift 2 ;;
    --scan-root) SCAN_ROOT="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,53p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

case "$MODE" in
  selftest) self_test ;;
  check) check_workflow "$WORKFLOW" "$SCAN_ROOT" ;;
esac
