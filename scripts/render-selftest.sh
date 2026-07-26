#!/usr/bin/env bash
#
# Deterministic regression proof for scripts/render.sh (issue #1766).
# Uses a fake Gradle/cgroup harness only: no Gradle daemon, Android SDK,
# Roborazzi, emulator, or Docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP_ROOT="$(mktemp -d)"
FIXTURE_ROOT="$TMP_ROOT/repo"
SELFTEST_RUN=0
SELFTEST_FAILED=0

cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

pass() {
  SELFTEST_RUN=$((SELFTEST_RUN + 1))
  printf 'PASS: %s\n' "$1"
}

fail_case() {
  SELFTEST_RUN=$((SELFTEST_RUN + 1))
  SELFTEST_FAILED=$((SELFTEST_FAILED + 1))
  printf 'FAIL: %s\n' "$1" >&2
}

expect_status() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$actual" -eq "$expected" ]]; then
    pass "$label"
  else
    fail_case "$label (expected status $expected, got $actual)"
  fi
}

write_valid_source() {
  mkdir -p "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render"
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
    @Test
    fun firstRender() = render("first-label") {}

    @Test
    fun secondRender() = render("second-special-label") {}
}
KOTLIN
}

write_missing_mapping_source() {
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
    @Test
    fun firstRender() = render("first-label") {}

    @Test
    fun secondRender() = Unit
}
KOTLIN
}

write_duplicate_label_source() {
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
    @Test
    fun firstRender() = render("same-label") {}

    @Test
    fun secondRender() = render("same-label") {}
}
KOTLIN
}

write_duplicate_test_method_source() {
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
    @Test
    fun repeatedRender() = render("first-label") {}

    @Test
    fun repeatedRender() = render("second-special-label") {}
}
KOTLIN
}

write_duplicate_method_mapping_source() {
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
    @Test
    fun firstRender() = render("first-label") {}

    fun firstRender() = render("second-special-label") {}
}
KOTLIN
}

write_mismatched_mapping_source() {
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
    @Test
    fun firstRender() = render("first-label") {}

    fun secondRender() = render("second-special-label") {}
}
KOTLIN
}

write_unsafe_label_source() {
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
    @Test
    fun firstRender() = render("../unsafe-label") {}
}
KOTLIN
}

write_multiline_mapping_source() {
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
    @Test
    fun firstRender() =
        render("first-label") {}
}
KOTLIN
}

write_horizontal_whitespace_source() {
  cat > "$FIXTURE_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt" <<'KOTLIN'
class DesignRenders {
	@Test
	fun	firstRender (	)	=	render("first-label") {}
}
KOTLIN
}

mkdir -p "$FIXTURE_ROOT/scripts" "$FIXTURE_ROOT/build" \
  "$FIXTURE_ROOT/shared/ui-kit/build/renders"
cp "$REPO_ROOT/scripts/render.sh" "$FIXTURE_ROOT/scripts/render.sh"
chmod +x "$FIXTURE_ROOT/scripts/render.sh"

cat > "$FIXTURE_ROOT/scripts/cgroup-run.sh" <<'SHELL'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "--unit" ]] || exit 91
shift 2
[[ "${1:-}" == "--" ]] || exit 92
shift
exec "$@"
SHELL
chmod +x "$FIXTURE_ROOT/scripts/cgroup-run.sh"

cat > "$FIXTURE_ROOT/gradlew" <<'SHELL'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> build/fake-gradle-invocations.log
forced=0
selector=""
while (($#)); do
  case "$1" in
    -Ppocketshell.forceDesignRender=true)
      forced=1
      ;;
    --tests)
      shift
      selector="${1:-}"
      ;;
  esac
  shift
done

method="${selector##*.}"
output_dir="shared/ui-kit/build/renders"
mkdir -p "$output_dir"

# Model the original #1766 failure: without the opt-in force property, the
# first filtered invocation executes but a different second filter exits 0
# from cache/up-to-date state without producing its side-effect artifact.
if [[ "$forced" -ne 1 ]]; then
  if [[ "$method" == "firstRender" ]]; then
    printf 'first\n' > "$output_dir/first-label.png"
  fi
  exit 0
fi

case "${FAKE_RENDER_MODE:-fresh}" in
  fresh)
    case "$method" in
      firstRender) printf 'first\n' > "$output_dir/first-label.png" ;;
      secondRender) printf 'second\n' > "$output_dir/second-special-label.png" ;;
      DesignRenders)
        printf 'first\n' > "$output_dir/first-label.png"
        printf 'second\n' > "$output_dir/second-special-label.png"
        ;;
    esac
    ;;
  absent)
    ;;
  stale)
    printf 'stale\n' > "$output_dir/second-special-label.png"
    touch -t 200001010000 "$output_dir/second-special-label.png"
    ;;
  mismatched)
    printf 'wrong mapping\n' > "$output_dir/second-render.png"
    ;;
  incomplete-all)
    printf 'first only\n' > "$output_dir/first-label.png"
    ;;
  overlap)
    active_dir="build/fake-gradle-active"
    if mkdir "$active_dir" 2>/dev/null; then
      # The first invocation pauses while the self-test starts the second. If
      # they overlap, it deliberately does NOT create its own output: the
      # concurrent invocation below creates that foreign artifact instead.
      sleep 0.25
      if [[ ! -e build/fake-gradle-overlap-detected ]]; then
        case "$method" in
          firstRender) printf 'first owned\n' > "$output_dir/first-label.png" ;;
          secondRender) printf 'second owned\n' > "$output_dir/second-special-label.png" ;;
        esac
      fi
      rmdir "$active_dir" 2>/dev/null || true
    else
      printf 'overlap\n' > build/fake-gradle-overlap-detected
      # Model the dangerous false success precisely: a concurrent invocation
      # creates both its own output and the first invocation's expected output.
      # Without serialization, the first call can accept this foreign PNG.
      printf 'foreign first\n' > "$output_dir/first-label.png"
      printf 'second overlap\n' > "$output_dir/second-special-label.png"
    fi
    ;;
  *)
    printf 'unknown fake mode: %s\n' "$FAKE_RENDER_MODE" >&2
    exit 93
    ;;
esac
SHELL
chmod +x "$FIXTURE_ROOT/gradlew"

run_render() {
  local mode="$1"
  shift
  (
    cd "$FIXTURE_ROOT"
    FAKE_RENDER_MODE="$mode" scripts/render.sh "$@"
  )
}

write_valid_source

# RED on the old harness: the second call exits 0 but has no mapped PNG.
set +e
run_render fresh firstRender > "$TMP_ROOT/first.log" 2>&1
first_status=$?
run_render fresh secondRender > "$TMP_ROOT/second.log" 2>&1
second_status=$?
set -e
if [[ "$first_status" -eq 0 && "$second_status" -eq 0 &&
      -f "$FIXTURE_ROOT/shared/ui-kit/build/renders/first-label.png" &&
      -f "$FIXTURE_ROOT/shared/ui-kit/build/renders/second-special-label.png" ]]; then
  pass "two sequential filtered renders each produce their own mapped artifact"
else
  fail_case "two sequential filtered renders each produce their own mapped artifact"
fi
if [[ "$(grep -c -- '-Ppocketshell.forceDesignRender=true' \
    "$FIXTURE_ROOT/build/fake-gradle-invocations.log" || true)" -eq 2 ]] &&
    ! grep -q -- '--rerun-tasks' "$FIXTURE_ROOT/build/fake-gradle-invocations.log"; then
  pass "filtered calls use the narrow force property without blanket rerun"
else
  fail_case "filtered calls did not use only the opt-in render-task force property"
fi
if [[ "$(awk '{
    count = 0
    for (i = 1; i <= NF; i++) {
      if ($i == "--no-daemon") {
        count++
      }
    }
    if (count == 1) {
      exact++
    }
  }
  END { print exact + 0 }' \
    "$FIXTURE_ROOT/build/fake-gradle-invocations.log")" -eq 2 ]]; then
  pass "filtered calls pass exactly one --no-daemon to Gradle"
else
  fail_case "filtered calls did not pass exactly one --no-daemon to Gradle"
fi
if grep -q 'second-special-label[.]png' "$TMP_ROOT/second.log" &&
    ! grep -q 'first-label[.]png' "$TMP_ROOT/second.log"; then
  pass "filtered success reports only its selected verified artifact"
else
  fail_case "filtered success did not report only its selected verified artifact"
fi

before_invocations="$(wc -l < "$FIXTURE_ROOT/build/fake-gradle-invocations.log")"
set +e
run_render fresh unknownRender > "$TMP_ROOT/unknown.log" 2>&1
status=$?
set -e
expect_status "unknown filter fails before Gradle" 1 "$status"
after_invocations="$(wc -l < "$FIXTURE_ROOT/build/fake-gradle-invocations.log")"
if [[ "$before_invocations" -eq "$after_invocations" ]]; then
  pass "unknown filter did not invoke Gradle"
else
  fail_case "unknown filter invoked Gradle"
fi

write_missing_mapping_source
set +e
run_render fresh firstRender > "$TMP_ROOT/missing-mapping.log" 2>&1
status=$?
set -e
expect_status "missing test-to-label mapping is rejected" 1 "$status"

write_duplicate_label_source
set +e
run_render fresh firstRender > "$TMP_ROOT/duplicate-mapping.log" 2>&1
status=$?
set -e
expect_status "duplicate render label mapping is rejected" 1 "$status"

write_duplicate_test_method_source
set +e
run_render fresh repeatedRender > "$TMP_ROOT/duplicate-test-method.log" 2>&1
status=$?
set -e
expect_status "duplicate @Test method is rejected" 1 "$status"

write_duplicate_method_mapping_source
set +e
run_render fresh firstRender > "$TMP_ROOT/duplicate-method-mapping.log" 2>&1
status=$?
set -e
expect_status "duplicate method mapping is rejected" 1 "$status"

write_mismatched_mapping_source
set +e
run_render fresh firstRender > "$TMP_ROOT/mismatched-source-mapping.log" 2>&1
status=$?
set -e
expect_status "render definition without matching test mapping is rejected" 1 "$status"

write_unsafe_label_source
set +e
run_render fresh firstRender > "$TMP_ROOT/unsafe-label.log" 2>&1
status=$?
set -e
expect_status "unsafe render label is rejected" 1 "$status"

write_multiline_mapping_source
set +e
run_render fresh firstRender > "$TMP_ROOT/multiline-mapping.log" 2>&1
status=$?
set -e
expect_status "multiline method-to-label mapping is intentionally rejected" 1 "$status"

write_horizontal_whitespace_source
set +e
run_render fresh firstRender > "$TMP_ROOT/horizontal-whitespace.log" 2>&1
status=$?
set -e
expect_status "spaces and tabs within one mapping line are accepted" 0 "$status"

write_valid_source
set +e
run_render mismatched secondRender > "$TMP_ROOT/mismatched-artifact.log" 2>&1
status=$?
set -e
expect_status "artifact at a method-derived instead of mapped label fails" 1 "$status"

set +e
run_render absent secondRender > "$TMP_ROOT/absent.log" 2>&1
status=$?
set -e
expect_status "missing selected artifact fails" 1 "$status"

set +e
run_render stale secondRender > "$TMP_ROOT/stale.log" 2>&1
status=$?
set -e
expect_status "stale selected artifact fails" 1 "$status"

set +e
run_render incomplete-all > "$TMP_ROOT/incomplete-all.log" 2>&1
status=$?
set -e
expect_status "all-renders mode fails when one declared artifact is absent" 1 "$status"

unlink "$FIXTURE_ROOT/shared/ui-kit/build/renders/first-label.png" 2>/dev/null || true
unlink "$FIXTURE_ROOT/shared/ui-kit/build/renders/second-special-label.png" 2>/dev/null || true
unlink "$FIXTURE_ROOT/build/fake-gradle-overlap-detected" 2>/dev/null || true
set +e
run_render overlap firstRender > "$TMP_ROOT/overlap-first.log" 2>&1 &
first_pid=$!
active_observed=0
for _ in {1..100}; do
  if [[ -d "$FIXTURE_ROOT/build/fake-gradle-active" ]]; then
    active_observed=1
    break
  fi
  sleep 0.01
done
run_render overlap secondRender > "$TMP_ROOT/overlap-second.log" 2>&1 &
second_pid=$!
wait "$first_pid"
first_status=$?
wait "$second_pid"
second_status=$?
set -e
if [[ "$active_observed" -eq 1 &&
      "$first_status" -eq 0 && "$second_status" -eq 0 &&
      ! -e "$FIXTURE_ROOT/build/fake-gradle-overlap-detected" &&
      -f "$FIXTURE_ROOT/shared/ui-kit/build/renders/first-label.png" &&
      -f "$FIXTURE_ROOT/shared/ui-kit/build/renders/second-special-label.png" ]]; then
  pass "overlapping render calls serialize the complete output-critical section"
else
  fail_case "overlapping render calls were not serialized with invocation-owned outputs"
fi

printf 'stale unregistered render\n' > \
  "$FIXTURE_ROOT/shared/ui-kit/build/renders/unregistered-old.png"
run_render fresh > "$TMP_ROOT/fresh-all.log" 2>&1
if [[ -f "$FIXTURE_ROOT/shared/ui-kit/build/renders/first-label.png" &&
      -f "$FIXTURE_ROOT/shared/ui-kit/build/renders/second-special-label.png" &&
      ! -e "$FIXTURE_ROOT/shared/ui-kit/build/renders/unregistered-old.png" ]]; then
  pass "fresh all-renders invocation replaces the directory with every mapped artifact"
else
  fail_case "fresh all-renders invocation left stale output or missed a mapped artifact"
fi

if [[ "$SELFTEST_RUN" -ne 20 ]]; then
  printf 'FAIL: self-test ran %s checks, expected 20\n' "$SELFTEST_RUN" >&2
  exit 1
fi
if [[ "$SELFTEST_FAILED" -ne 0 ]]; then
  printf 'FAIL: %s/%s render self-test checks failed\n' "$SELFTEST_FAILED" "$SELFTEST_RUN" >&2
  exit 1
fi
printf 'PASS: %s/%s render self-test checks (issue #1766)\n' "$SELFTEST_RUN" "$SELFTEST_RUN"
