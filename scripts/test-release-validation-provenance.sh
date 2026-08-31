#!/usr/bin/env bash
# Structural red/green guard for issue #2430 (audit F20).
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW_DEFAULT="$ROOT_DIR/.github/workflows/release-emulator-validation.yml"

fail() { printf 'FAIL: %s\n' "$*" >&2; return 1; }

check_workflow() {
  local workflow="$1"
  # shellcheck disable=SC2016 # GitHub expressions are literal workflow text.
  local current='--run-url "${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}"'
  # shellcheck disable=SC2016 # GitHub expressions are literal workflow text.
  local trigger='--trigger-run-url "${{ github.event.workflow_run.html_url }}"'
  local current_count trigger_count legacy_count

  [[ -f "$workflow" ]] || { fail "missing workflow: $workflow"; return 1; }
  current_count="$(grep -F -c -- "$current" "$workflow" || true)"
  trigger_count="$(grep -F -c -- "$trigger" "$workflow" || true)"
  # shellcheck disable=SC2016 # GitHub expressions are literal workflow text.
  legacy_count="$(grep -F -c -- '--run-url "${{ github.event.workflow_run.html_url }}"' "$workflow" || true)"

  [[ "$current_count" -eq 2 ]] || {
    fail "expected both marker and issue steps to receive the current validation run URL (found $current_count)"
    return 1
  }
  [[ "$trigger_count" -eq 2 ]] || {
    fail "expected both marker and issue steps to receive the triggering Tests run URL (found $trigger_count)"
    return 1
  }
  [[ "$legacy_count" -eq 0 ]] || {
    fail "workflow still labels the triggering Tests URL as --run-url"
    return 1
  }
  printf 'PASS: validation and triggering Tests provenance are distinct in both consumers\n'
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  cat > "$tmp/bad.yml" <<'EOF'
run: ci-nightly-rc-mark.sh --run-url "${{ github.event.workflow_run.html_url }}"
run: ci-nightly-rc-issue.sh --run-url "${{ github.event.workflow_run.html_url }}"
EOF
  if check_workflow "$tmp/bad.yml" >/dev/null 2>&1; then
    fail "conflated provenance fixture passed"
    return 1
  fi

  cat > "$tmp/good.yml" <<'EOF'
run: ci-nightly-rc-mark.sh --run-url "${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}" --trigger-run-url "${{ github.event.workflow_run.html_url }}"
run: ci-nightly-rc-issue.sh --run-url "${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}" --trigger-run-url "${{ github.event.workflow_run.html_url }}"
EOF
  check_workflow "$tmp/good.yml" >/dev/null || {
    fail "distinct provenance fixture failed"
    return 1
  }
  printf 'PASS: provenance guard rejects conflation and accepts distinct run URLs\n'
}

case "${1:-}" in
  --self-test) run_self_test ;;
  "") check_workflow "$WORKFLOW_DEFAULT" ;;
  *) printf 'usage: %s [--self-test]\n' "$0" >&2; exit 2 ;;
esac
