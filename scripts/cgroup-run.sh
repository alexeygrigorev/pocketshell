#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/scope-run.sh"

usage() {
  cat <<'USAGE'
Usage:
  scripts/cgroup-run.sh [--unit <name>] -- <command> [args...]

Runs a heavy local reproduction command in a transient systemd --user scope
with memory caps. Use this wrapper for local emulator/connected-test debugging
when a purpose-built script does not already scope the command.

Fail-closed rule:
  If user systemd is unavailable, this wrapper refuses to run locally instead
  of running the command uncapped in the caller's cgroup. Set
  POCKETSHELL_SCOPE_ALLOW_BARE=1 only when debugging cgroup setup. CI may fall
  back automatically.

Defaults:
  POCKETSHELL_TEST_MEM=8G
  POCKETSHELL_TEST_HIGH=<85% of MemoryMax>
  POCKETSHELL_TEST_SWAP=8G
  POCKETSHELL_SCOPE_SLICE=robust.slice
  POCKETSHELL_SCOPE_ALLOW_BARE=<unset>

Examples:
  scripts/cgroup-run.sh -- ./gradlew --no-daemon :app:compileDebugKotlin
  POCKETSHELL_TEST_MEM=6G scripts/cgroup-run.sh --unit local-repro -- bash -lc '...'

Prefer purpose-built wrappers when available:
  AVD_HOLD=1 scripts/start-local-avd.sh
  scripts/connected-test.sh --suffix i123 -Pandroid.testInstrumentationRunnerArguments.class=...
USAGE
}

unit=""
args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --unit)
      [[ $# -ge 2 ]] || { printf 'FAIL: --unit needs a value\n' >&2; exit 2; }
      unit="$2"
      shift 2
      ;;
    --unit=*)
      unit="${1#--unit=}"
      shift
      ;;
    --)
      shift
      args+=("$@")
      break
      ;;
    *)
      args+=("$1")
      shift
      ;;
  esac
done

if [[ "${#args[@]}" -eq 0 ]]; then
  usage >&2
  exit 2
fi

if [[ -z "$unit" ]]; then
  base="$(basename "${args[0]}")"
  unit="pocketshell-local-${base//[^A-Za-z0-9._-]/_}-$$"
fi

pocketshell_scope_run "$unit" "${args[@]}"
