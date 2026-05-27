#!/usr/bin/env bash
# Issue #150 — shared health-status polling for the deterministic Docker
# fleet under tests/docker/. Replaces per-script `wait_for_ssh_fixture`
# retry-poll loops with a single call site that reads the declarative
# `healthcheck:` declared in each compose service.
#
# The function expects the compose service container to already be
# created (e.g. via `docker compose -f <file> up -d <service>`); it
# then polls `docker inspect --format='{{.State.Health.Status}}'` until
# the container reports `healthy`, the deadline elapses, or the health
# state becomes `unhealthy`.
#
# Callers append a one-line readiness record per attempt to the
# requested log file so the existing artifact-collection conventions
# (`docker-ssh-readiness.log`, `06-docker-bootstrap-readiness-*.log`,
# etc.) keep working.
#
# Usage:
#   source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"
#   wait_for_container_healthy "$compose_file" "$service" "$log_file" \
#       [deadline_seconds]
#
# Exit:
#   0 — container reached `healthy` within the deadline
#   non-zero — deadline elapsed without `healthy`, or the container
#              entered `unhealthy`, or `docker inspect` could not find
#              the container
#
# Default deadline: 60 seconds. The compose healthcheck itself uses
# interval=2s retries=10 start_period=5s, so a fully cold start usually
# settles in ~1–10 s; the 60 s deadline is generous slack for image
# build + service start on cold CI runners.

if [[ -n "${POCKETSHELL_WAIT_FOR_HEALTHY_SH:-}" ]]; then
  return 0
fi
POCKETSHELL_WAIT_FOR_HEALTHY_SH=1

wait_for_container_healthy() {
  local compose_file="$1"
  local service="$2"
  local log_file="${3:-/dev/null}"
  local deadline_seconds="${4:-60}"

  if [[ -z "$compose_file" || -z "$service" ]]; then
    printf 'wait_for_container_healthy: compose_file and service are required\n' >&2
    return 2
  fi

  : > "$log_file" 2>/dev/null || true

  local start_ts now elapsed status container_id

  start_ts="$(date +%s)"

  while :; do
    now="$(date +%s)"
    elapsed=$(( now - start_ts ))
    container_id="$(docker compose -f "$compose_file" ps -q "$service" 2>/dev/null | head -n1)"
    if [[ -z "$container_id" ]]; then
      status="missing"
    else
      status="$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id" 2>/dev/null || true)"
      [[ -z "$status" ]] && status="unknown"
    fi
    {
      printf '[%s] elapsed=%ss service=%s container=%s status=%s\n' \
        "$(date -Is)" "$elapsed" "$service" "${container_id:-<none>}" "$status"
    } >> "$log_file" 2>/dev/null || true

    case "$status" in
      healthy)
        return 0
        ;;
      unhealthy)
        printf 'wait_for_container_healthy: %s reached unhealthy state\n' "$service" >&2
        return 1
        ;;
      none)
        # Service has no healthcheck declared — fall back to "container
        # is running" so we don't loop forever waiting for a health
        # signal that will never arrive.
        local running
        running="$(docker inspect --format='{{.State.Running}}' "$container_id" 2>/dev/null || true)"
        if [[ "$running" == "true" ]]; then
          return 0
        fi
        ;;
    esac

    if (( elapsed >= deadline_seconds )); then
      printf 'wait_for_container_healthy: %s did not become healthy within %ss (last status=%s)\n' \
        "$service" "$deadline_seconds" "$status" >&2
      return 1
    fi

    sleep 1
  done
}
