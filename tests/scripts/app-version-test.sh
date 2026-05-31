#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

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
[[ "$parsed" == "9.8.7" ]] || {
  printf 'expected 9.8.7, got %s\n' "$parsed" >&2
  exit 1
}

expected_fixture_line="$(pocketshell_agent_fixture_version_output 0.3.10)"
[[ "$expected_fixture_line" == "pocketshell fixture 0.3.10" ]] || {
  printf 'expected exact fixture line for 0.3.10, got %s\n' "$expected_fixture_line" >&2
  exit 1
}
[[ "$expected_fixture_line" != "pocketshell fixture 0.3.100" ]] || {
  printf '0.3.10 exact fixture line matched 0.3.100\n' >&2
  exit 1
}

repo_version="$(pocketshell_app_version_name "$ROOT_DIR")"
repo_fixture_output="$(
  POCKETSHELL_PROJECT_ROOT="$ROOT_DIR" "$ROOT_DIR/tests/docker/agent-bin/pocketshell" --version
)"
[[ "$repo_fixture_output" == "pocketshell fixture $repo_version" ]] || {
  printf 'expected exact repo fixture version, got %s\n' "$repo_fixture_output" >&2
  exit 1
}

suffix_fixture_output="$(
  POCKETSHELL_AGENT_FIXTURE_VERSION=0.3.100 "$ROOT_DIR/tests/docker/agent-bin/pocketshell" --version
)"
if [[ "$suffix_fixture_output" == "pocketshell fixture 0.3.10" ]]; then
  printf 'suffix fixture unexpectedly matched exact app version\n' >&2
  exit 1
fi
if [[ "$suffix_fixture_output" != "pocketshell fixture 0.3.100" ]]; then
  printf 'expected exact suffix fixture version, got %s\n' "$suffix_fixture_output" >&2
  exit 1
fi

printf 'PASS: app version helper and pocketshell fixture version\n'
