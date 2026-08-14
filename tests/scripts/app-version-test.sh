#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

CASES=0
pass_case() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

# shellcheck source=/dev/null
source "$ROOT_DIR/scripts/lib/app-version.sh"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

mkdir -p "$tmpdir/app"
cat > "$tmpdir/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionName = "9.8.7"
    }
}
GRADLE

parsed="$(pocketshell_app_version_name "$tmpdir")"
[[ "$parsed" == "9.8.7" ]] || fail "expected 9.8.7, got $parsed"
pass_case "parses versionName out of the Gradle DSL"

expected_fixture_line="$(pocketshell_agent_fixture_version_output 0.3.10)"
[[ "$expected_fixture_line" == "pocketshell fixture 0.3.10" ]] ||
  fail "expected exact fixture line for 0.3.10, got $expected_fixture_line"
pass_case "builds the exact fixture line for a version"
[[ "$expected_fixture_line" != "pocketshell fixture 0.3.100" ]] ||
  fail "0.3.10 exact fixture line matched 0.3.100"
pass_case "the exact fixture line does not match a prefix-extended version"

repo_version="$(pocketshell_app_version_name "$ROOT_DIR")"
repo_fixture_output="$(
  POCKETSHELL_PROJECT_ROOT="$ROOT_DIR" "$ROOT_DIR/tests/docker/agent-bin/pocketshell" --version
)"
[[ "$repo_fixture_output" == "pocketshell fixture $repo_version" ]] ||
  fail "expected exact repo fixture version, got $repo_fixture_output"
pass_case "the repo fixture binary reports this checkout's version"

suffix_fixture_output="$(
  POCKETSHELL_AGENT_FIXTURE_VERSION=0.3.100 "$ROOT_DIR/tests/docker/agent-bin/pocketshell" --version
)"
if [[ "$suffix_fixture_output" == "pocketshell fixture 0.3.10" ]]; then
  fail "suffix fixture unexpectedly matched exact app version"
fi
pass_case "an overridden fixture version does not collide with the exact one"
if [[ "$suffix_fixture_output" != "pocketshell fixture 0.3.100" ]]; then
  fail "expected exact suffix fixture version, got $suffix_fixture_output"
fi
pass_case "an overridden fixture version is reported verbatim"

# Issue #2113: a harness that exits 0 having run nothing is the vacuous green
# process.md catalogues. The count line is what makes the JVM assertion about
# behaviour rather than about bash's exit status.
(( CASES == 6 )) || fail "expected 6 cases to run, saw $CASES"
printf 'PASS: app version helper and pocketshell fixture version (%s cases)\n' "$CASES"
