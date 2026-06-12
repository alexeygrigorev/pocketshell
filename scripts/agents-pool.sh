#!/usr/bin/env bash
set -euo pipefail

# Agents-fixture pool CLI (issue #724) — the Docker half of parallel journey
# testing. The AVD half (a pool of N emulators) is scripts/avd-pool.sh; this
# brings up N ISOLATED deterministic `agents` SSH/tmux fixtures, each on its own
# host port, so two emulator lanes never corrupt each other's tmux state.
#
# Subcommands:
#   up [PORT...]     bring up an agents fixture lane for each PORT (default: the
#                    whole candidate pool), wait each healthy
#   down [PORT...]   tear down the fixture lane(s) for each PORT (default: pool)
#   status           show which candidate ports are claimed / fixture health
#
# Port scheme: candidate ports default to `2222 2243 2244 2245` (override via
# POCKETSHELL_AGENTS_POOL_PORTS). Port 2222 reproduces the legacy single-lane
# fixture (container `pocketshell-test-agents`) so existing tooling keeps
# working; other ports run under per-port container names + compose projects.
#
# The per-lane allocation (claim a free port + flock it for a run's lifetime) is
# done automatically by `scripts/connected-test.sh --pool`; this CLI is for
# explicit pool warm-up / teardown / inspection.
#
# Example — bring up two lanes, run two journey classes in parallel, tear down:
#   scripts/agents-pool.sh up 2222 2243
#   scripts/connected-test.sh --pool --suffix iA \
#     -Pandroid.testInstrumentationRunnerArguments.class=...DeepLinkSessionSwitchE2eTest &
#   scripts/connected-test.sh --pool --suffix iB \
#     -Pandroid.testInstrumentationRunnerArguments.class=...ReconnectRepaintE2eTest &
#   wait
#   scripts/agents-pool.sh down 2222 2243

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/agents-pool.sh"

usage() {
  cat <<'USAGE'
Usage: scripts/agents-pool.sh <up|down|status> [PORT...]

Brings up a pool of isolated deterministic `agents` SSH/tmux fixtures on
distinct host ports so parallel emulator lanes get independent tmux state.

  up [PORT...]     bring up + wait-healthy a fixture lane per PORT (default pool)
  down [PORT...]   tear down the fixture lane(s) per PORT (default pool)
  status           list candidate ports, claim state, and fixture health

Env: POCKETSHELL_AGENTS_POOL_PORTS="2222 2243 2244 2245"
USAGE
}

resolve_ports() {
  # Remaining args are explicit ports; otherwise the whole candidate pool.
  if [[ "$#" -gt 0 ]]; then
    printf '%s\n' "$@"
  else
    pocketshell_agents_pool_ports
  fi
}

cmd_up() {
  local ports
  # shellcheck disable=SC2046
  ports=$(resolve_ports "$@")
  local port failures=0
  for port in $ports; do
    if ! pocketshell_agents_fixture_up "$ROOT_DIR" "$port" \
      || ! pocketshell_agents_fixture_wait_healthy "$ROOT_DIR" "$port"; then
      printf 'FAIL: agents fixture lane on port %s did not come up healthy.\n' "$port" >&2
      failures=$((failures + 1))
    fi
  done
  if (( failures > 0 )); then
    return 1
  fi
  # shellcheck disable=SC2086  # $ports is an intentionally space-separated list
  printf 'agents pool up: %s\n' "$(printf '%s ' $ports)" >&2
}

cmd_down() {
  local ports
  # shellcheck disable=SC2046
  ports=$(resolve_ports "$@")
  local port
  for port in $ports; do
    pocketshell_agents_fixture_down "$ROOT_DIR" "$port"
  done
  # shellcheck disable=SC2086  # $ports is an intentionally space-separated list
  printf 'agents pool down: %s\n' "$(printf '%s ' $ports)" >&2
}

cmd_status() {
  local ports
  ports="$(pocketshell_agents_pool_ports)"
  printf '%-8s %-10s %-12s %s\n' "PORT" "CLAIMED" "HEALTH" "CONTAINER"
  local port
  for port in $ports; do
    local lock_file claimed container health
    lock_file="$(pocketshell_agents_lock_file_for_port "$ROOT_DIR" "$port")"
    # If a non-blocking flock succeeds, the port is FREE (no holder); if it
    # fails, a lane holds it.
    if [[ -e "$lock_file" ]] && ! ( flock -n 9 ) 9>"$lock_file" 2>/dev/null; then
      claimed="yes"
    else
      claimed="no"
    fi
    if [[ "$port" == "2222" ]]; then
      container="pocketshell-test-agents"
    else
      container="$(pocketshell_agents_container_name_for_port "$port")"
    fi
    health="$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "-")"
    [[ -z "$health" ]] && health="-"
    printf '%-8s %-10s %-12s %s\n' "$port" "$claimed" "$health" "$container"
  done
}

main() {
  local sub="${1:-}"
  if [[ "$#" -gt 0 ]]; then
    shift
  fi
  case "$sub" in
    up) cmd_up "$@" ;;
    down) cmd_down "$@" ;;
    status) cmd_status "$@" ;;
    -h|--help|"") usage ;;
    *) printf 'Unknown subcommand: %s\n\n' "$sub" >&2; usage; exit 2 ;;
  esac
}

main "$@"
