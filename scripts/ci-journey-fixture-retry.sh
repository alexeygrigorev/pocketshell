#!/usr/bin/env bash
# Issue #2095: retry only captured external Docker-registry failures during
# pre-journey fixture setup, and leave attempt-local evidence for the shard
# classifier. Unknown failures are never retried or softened.
set -euo pipefail

MAX_ATTEMPTS=2
DEFAULT_ARTIFACT_ROOT="artifacts/ci-journey-setup"

usage() {
  cat >&2 <<'EOF'
usage:
  ci-journey-fixture-retry.sh <fixture-name> -- <command> [args...]
  ci-journey-fixture-retry.sh --classify [setup-artifact-root] [journey-artifact-root]
EOF
  exit 2
}

registry_failure_reason() {
  local log="$1"
  local reason

  # Bind the provider URL and failure signature to ONE Docker error record.
  # BuildKit wraps the observed blob error over three contiguous lines:
  # operation failure -> provider URL -> HTTP status. A provider URL elsewhere
  # in the log (normal auth/pull chatter) cannot bless a later product error.
  reason="$(awk '
    function lower(s) { return tolower(s) }
    function provider(s, v) {
      v=lower(s)
      return v ~ /(registry-1\.docker\.io|auth\.docker\.io|production\.cloudflare\.docker\.com|docker\.io\/v2\/)/
    }
    function operation_failure(s, v) {
      v=lower(s)
      return v ~ /(failed to copy|failed open|failed to do request|failed to resolve|failed to fetch|error pulling|pull failed|unexpected status code|unexpected http status|request failed)/
    }
    function http_5xx(s) {
      return s ~ /(^|[^0-9])5[0-9][0-9]([^0-9]|$)/
    }
    function network(s, v) {
      v=lower(s)
      return v ~ /(tls handshake timeout|i\/o timeout|connection reset by peer|unexpected eof|temporary failure in name resolution|no such host|net\/http: request canceled|client\.timeout exceeded)/
    }
    { line[NR]=$0 }
    END {
      for (i=1; i<=NR; i++) {
        if (!operation_failure(line[i])) continue
        if (provider(line[i])) {
          if (http_5xx(line[i]) || http_5xx(line[i+1])) http_found=1
          if (network(line[i]) || network(line[i+1])) network_found=1
        }
        if (provider(line[i+1])) {
          if (http_5xx(line[i+1]) || http_5xx(line[i+2])) http_found=1
          if (network(line[i+1]) || network(line[i+2])) network_found=1
        }
      }
      if (http_found) print "docker_registry_http_5xx"
      else if (network_found) print "docker_registry_network"
    }
  ' "$log")"
  [[ -n "$reason" ]] || return 1
  printf '%s\n' "$reason"
}

write_failure_manifest() {
  local root="$1" fixture="$2" status="$3" reason="$4" attempts="$5"
  {
    printf 'status=%s\n' "$status"
    printf 'reason=%s\n' "$reason"
    printf 'fixture=%s\n' "$fixture"
    printf 'attempts=%s\n' "$attempts"
  } > "$root/failure.env"
}

read_field() {
  local key="$1" file="$2"
  sed -n "s/^${key}=//p" "$file" | tail -n 1
}

classify_exhausted_setup() {
  local root="${1:-$DEFAULT_ARTIFACT_ROOT}"
  local journey_root="${2:-artifacts/ci-journey}"
  local manifest="$root/failure.env"
  local status reason fixture attempts attempt log actual_reason rc

  # The downgrade is valid only before any journey evidence exists. This is
  # deliberately stronger than checking a missing summary: a partial class
  # attempt is a journey and must remain product-red/fail-closed.
  if [[ -d "$journey_root" ]] && find "$journey_root" -mindepth 1 -print -quit | grep -q .; then
    echo "pre-journey infra rejected: journey evidence exists under $journey_root" >&2
    return 1
  fi
  [[ -f "$manifest" ]] || {
    echo "pre-journey infra rejected: missing $manifest" >&2
    return 1
  }

  status="$(read_field status "$manifest")"
  reason="$(read_field reason "$manifest")"
  fixture="$(read_field fixture "$manifest")"
  attempts="$(read_field attempts "$manifest")"
  [[ "$status" == "infra_exhausted" && "$attempts" == "$MAX_ATTEMPTS" ]] || {
    echo "pre-journey infra rejected: status=$status attempts=$attempts" >&2
    return 1
  }
  [[ "$fixture" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || {
    echo "pre-journey infra rejected: invalid fixture identity" >&2
    return 1
  }
  [[ "$reason" == "docker_registry_http_5xx" || "$reason" == "docker_registry_network" ]] || {
    echo "pre-journey infra rejected: unknown reason=$reason" >&2
    return 1
  }

  for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
    log="$root/$fixture/attempt-$attempt.log"
    [[ -s "$log" ]] || {
      echo "pre-journey infra rejected: missing attempt log $log" >&2
      return 1
    }
    rc="$(read_field rc "$root/$fixture/attempt-$attempt.env")"
    [[ "$rc" =~ ^[1-9][0-9]*$ ]] || {
      echo "pre-journey infra rejected: attempt $attempt was not a captured failure" >&2
      return 1
    }
    actual_reason="$(registry_failure_reason "$log" || true)"
    [[ "$actual_reason" == "$reason" ]] || {
      echo "pre-journey infra rejected: attempt $attempt signature=${actual_reason:-unknown}, expected $reason" >&2
      return 1
    }
  done

  printf 'pre_journey_infra_verdict=INFRA\n'
  printf 'pre_journey_infra_reason=%s_exhausted\n' "$reason"
  printf 'pre_journey_infra_fixture=%s\n' "$fixture"
  printf 'pre_journey_infra_attempts=%s\n' "$attempts"
}

if [[ "${1:-}" == "--classify" ]]; then
  shift
  classify_exhausted_setup "${1:-$DEFAULT_ARTIFACT_ROOT}" "${2:-artifacts/ci-journey}"
  exit $?
fi

fixture="${1:-}"
[[ "$fixture" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || usage
shift
[[ "${1:-}" == "--" ]] || usage
shift
(( $# > 0 )) || usage

artifact_root="${CI_JOURNEY_SETUP_ARTIFACT_ROOT:-$DEFAULT_ARTIFACT_ROOT}"
fixture_dir="$artifact_root/$fixture"
retry_delay_seconds="${CI_JOURNEY_FIXTURE_RETRY_DELAY_SECONDS:-5}"
[[ "$retry_delay_seconds" =~ ^[0-9]+$ ]] || retry_delay_seconds=5
mkdir -p "$fixture_dir" || exit 1
rm -f "$artifact_root/failure.env"

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  log="$fixture_dir/attempt-$attempt.log"
  echo "fixture $fixture: attempt $attempt/$MAX_ATTEMPTS"
  set +e
  "$@" 2>&1 | tee "$log"
  rc="${PIPESTATUS[0]}"
  set -e
  printf 'rc=%s\n' "$rc" > "$fixture_dir/attempt-$attempt.env"

  if [[ "$rc" -eq 0 ]]; then
    if [[ "$attempt" -eq 1 ]]; then
      printf 'status=success\nattempts=1\n' > "$fixture_dir/result.env"
    else
      printf 'status=recovered\nattempts=%s\n' "$attempt" > "$fixture_dir/result.env"
      echo "fixture $fixture: captured registry failure recovered on bounded retry"
    fi
    exit 0
  fi

  reason="$(registry_failure_reason "$log" || true)"
  if [[ -z "$reason" ]]; then
    printf 'status=unknown_failure\nattempts=%s\n' "$attempt" > "$fixture_dir/result.env"
    write_failure_manifest "$artifact_root" "$fixture" unknown_failure unknown "$attempt"
    echo "fixture $fixture: unknown setup failure; refusing retry/INFRA classification" >&2
    exit "$rc"
  fi

  if [[ "$attempt" -lt "$MAX_ATTEMPTS" ]]; then
    echo "fixture $fixture: captured $reason; retrying once after ${retry_delay_seconds}s"
    sleep "$retry_delay_seconds"
    continue
  fi

  printf 'status=infra_exhausted\nreason=%s\nattempts=%s\n' "$reason" "$attempt" \
    > "$fixture_dir/result.env"
  write_failure_manifest "$artifact_root" "$fixture" infra_exhausted "$reason" "$attempt"
  echo "fixture $fixture: $reason exhausted after $attempt attempts" >&2
  exit "$rc"
done

exit 1
