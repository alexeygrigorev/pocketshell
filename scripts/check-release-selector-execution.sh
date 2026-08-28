#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib/instrumentation-evidence.sh"

MODE=""
LOG_FILE=""
SELECTOR=""
SELECTED_FILE=""
ATTENDANCE_FILE=""
RUN_ID=""
NEWER_THAN=""

usage() {
  cat <<'USAGE'
Usage:
  scripts/check-release-selector-execution.sh --verify-log \
    --selector CLASS[#METHOD] --log FILE
  scripts/check-release-selector-execution.sh --verify-attendance \
    --selected-file FILE --attendance FILE --run-id ID --newer-than MARKER

--verify-log proves one raw Android instrumentation log has a positive
executed-test count, runner success, and the exact selected status block.
--verify-attendance proves every selected selector has that same raw proof in
the current run, with a digest and a raw-log mtime newer than MARKER.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --verify-log) MODE="log"; shift ;;
    --verify-attendance) MODE="attendance"; shift ;;
    --selector) SELECTOR="$2"; shift 2 ;;
    --log) LOG_FILE="$2"; shift 2 ;;
    --selected-file) SELECTED_FILE="$2"; shift 2 ;;
    --attendance) ATTENDANCE_FILE="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --newer-than) NEWER_THAN="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      printf 'unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ "$MODE" == "log" ]]; then
  [[ -n "$LOG_FILE" && -n "$SELECTOR" ]] || {
    printf '%s\n' '--verify-log requires --selector and --log' >&2
    exit 1
  }
  count="$(pocketshell_instrumentation_assert_log "$LOG_FILE" "$SELECTOR")"
  printf 'PASS: selector=%s executed_count=%s raw_log=%s\n' "$SELECTOR" "$count" "$LOG_FILE"
  exit 0
fi

[[ "$MODE" == "attendance" ]] || {
  printf '%s\n' 'one of --verify-log or --verify-attendance is required' >&2
  usage >&2
  exit 1
}
[[ -n "$SELECTED_FILE" && -n "$ATTENDANCE_FILE" && -n "$RUN_ID" && -n "$NEWER_THAN" ]] || {
  printf '%s\n' '--verify-attendance requires --selected-file, --attendance, --run-id, and --newer-than' >&2
  exit 1
}
[[ "$RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]] || {
  printf 'invalid release selector attendance run id: %s\n' "$RUN_ID" >&2
  exit 1
}
[[ -f "$SELECTED_FILE" ]] || {
  printf 'selected selector file is missing: %s\n' "$SELECTED_FILE" >&2
  exit 1
}
[[ -s "$SELECTED_FILE" ]] || {
  printf 'selected selector file is empty: %s\n' "$SELECTED_FILE" >&2
  exit 1
}
[[ -f "$ATTENDANCE_FILE" ]] || {
  printf 'current-run selector attendance ledger is missing: %s\n' "$ATTENDANCE_FILE" >&2
  exit 1
}
[[ -f "$NEWER_THAN" ]] || {
  printf 'current-run selector marker is missing: %s\n' "$NEWER_THAN" >&2
  exit 1
}
grep -Fqx '# pocketshell-release-selector-attendance v1' "$ATTENDANCE_FILE" || {
  printf 'selector attendance ledger has an unknown format: %s\n' "$ATTENDANCE_FILE" >&2
  exit 1
}
grep -Fqx $'run_id\t'"$RUN_ID" "$ATTENDANCE_FILE" || {
  printf 'selector attendance ledger is stale or belongs to another run: expected run_id=%s\n' "$RUN_ID" >&2
  exit 1
}

declare -A selected=()
selected_count=0
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  pocketshell_instrumentation_selector_parts "$line" || exit 1
  if [[ -n "${selected[$line]:-}" ]]; then
    continue
  fi
  selected["$line"]=1
  selected_count=$((selected_count + 1))
done < "$SELECTED_FILE"
[[ "$selected_count" -gt 0 ]] || {
  printf 'selected selector file contains no selectors: %s\n' "$SELECTED_FILE" >&2
  exit 1
}

declare -A attended=()
row_count=0
while IFS=$'\t' read -r kind row_selector count raw_log digest extra || [[ -n "$kind" ]]; do
  [[ -z "$kind" || "$kind" == \#* || "$kind" == run_id* ]] && continue
  [[ "$kind" == "selector" && -z "${extra:-}" ]] || {
    printf 'malformed selector attendance row in %s\n' "$ATTENDANCE_FILE" >&2
    exit 1
  }
  pocketshell_instrumentation_selector_parts "$row_selector" || exit 1
  [[ -n "${selected[$row_selector]:-}" ]] || {
    printf 'attendance contains an unselected selector: %s\n' "$row_selector" >&2
    exit 1
  }
  [[ "$count" =~ ^[1-9][0-9]*$ ]] || {
    printf 'attendance has a non-positive executed count for %s\n' "$row_selector" >&2
    exit 1
  }
  [[ -f "$raw_log" ]] || {
    printf 'raw instrumentation evidence is missing for %s: %s\n' "$row_selector" "$raw_log" >&2
    exit 1
  }
  [[ "$raw_log" -nt "$NEWER_THAN" ]] || {
    printf 'raw instrumentation evidence is stale for %s: %s\n' "$row_selector" "$raw_log" >&2
    exit 1
  }
  actual_digest="$(sha256sum "$raw_log" | awk '{print $1}')"
  [[ "$actual_digest" == "$digest" ]] || {
    printf 'raw instrumentation evidence digest changed for %s: %s\n' "$row_selector" "$raw_log" >&2
    exit 1
  }
  actual_count="$(pocketshell_instrumentation_assert_log "$raw_log" "$row_selector")" || exit 1
  [[ "$actual_count" == "$count" ]] || {
    printf 'attendance count disagrees with raw instrumentation evidence for %s\n' "$row_selector" >&2
    exit 1
  }
  attended["$row_selector"]=1
  row_count=$((row_count + 1))
done < "$ATTENDANCE_FILE"

missing=()
for selector in "${!selected[@]}"; do
  [[ -n "${attended[$selector]:-}" ]] || missing+=("$selector")
done
if [[ "${#missing[@]}" -gt 0 ]]; then
  printf 'current run is missing selector attendance for %s selector(s):\n' "${#missing[@]}" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

printf 'PASS: current-run selector attendance selected=%s attended=%s run_id=%s\n' \
  "$selected_count" "$row_count" "$RUN_ID"
printf 'Raw instrumentation evidence is retained and digest-verified in %s\n' "$ATTENDANCE_FILE"
