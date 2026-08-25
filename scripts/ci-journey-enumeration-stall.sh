#!/usr/bin/env bash
# Issue #2317: identify the narrow, attempt-local proof that the app's
# in-emulator tmux list-sessions call timed out. A generic suite timeout is
# deliberately not enough: this helper fails closed to NONE unless the device
# logcat and the completed harness manifest belong to the same failed attempt.
#
# The workflow-specific modes below keep the large shell out of
# .github/workflows/tests.yml. The default mode remains the artifact classifier
# consumed by the suite summary writer and by the direct self-test.
set -u

ENUMERATION_STALL_VERDICT=NONE
ENUMERATION_STALL_REASON=none
ENUMERATION_STALL_ATTEMPTS=0
ENUMERATION_STALL_EVIDENCE=""

classify_artifacts() {
  local artifacts_dir="${1:-artifacts}"
  local logcat attempt_dir manifest

  ENUMERATION_STALL_VERDICT=NONE
  ENUMERATION_STALL_REASON=none
  ENUMERATION_STALL_ATTEMPTS=0
  ENUMERATION_STALL_EVIDENCE=""

  [[ -d "$artifacts_dir" ]] || return 0

  while IFS= read -r -d '' logcat; do
    attempt_dir="${logcat%/device-logcat.txt}"
    manifest="$attempt_dir/manifest.txt"

    # The marker must come from device logcat. attempt.log is host-side and can
    # only describe what the runner believed it launched; it cannot prove the
    # Android app reached the bounded list-sessions call.
    grep -Fq 'JOURNEY_ENUMERATION_STALL: tmux list-sessions ' "$logcat" || continue
    [[ -s "$manifest" ]] || continue
    grep -Eq '^primary_classification=(failure|outer_timeout)$' "$manifest" || continue
    grep -Eq '^primary_exit_code=(-|[1-9][0-9]*)$' "$manifest" || continue
    grep -Fxq 'device_logcat=ok' "$manifest" || continue
    grep -Fxq 'snapshot_status=complete' "$manifest" || continue
    grep -Fxq 'harness_verdict_status=complete' "$manifest" || continue

    if [[ -z "$ENUMERATION_STALL_EVIDENCE" ]]; then
      ENUMERATION_STALL_EVIDENCE="$logcat"
    fi
    ENUMERATION_STALL_ATTEMPTS=$((ENUMERATION_STALL_ATTEMPTS + 1))
  done < <(
    find "$artifacts_dir" -type f -path '*/class-attempts/*/device-logcat.txt' -print0 2>/dev/null | sort -z
  )

  if (( ENUMERATION_STALL_ATTEMPTS > 0 )); then
    ENUMERATION_STALL_VERDICT=INFRA
    ENUMERATION_STALL_REASON=tmux_list_sessions_enumeration_stall
  fi
}

print_classifier_result() {
  classify_artifacts "${1:-artifacts}"
  printf 'enumeration_stall_verdict=%s\n' "$ENUMERATION_STALL_VERDICT"
  printf 'enumeration_stall_reason=%s\n' "$ENUMERATION_STALL_REASON"
  printf 'enumeration_stall_attempts=%s\n' "$ENUMERATION_STALL_ATTEMPTS"
  if (( ENUMERATION_STALL_ATTEMPTS > 0 )); then
    printf 'enumeration_stall_evidence=%s\n' "$ENUMERATION_STALL_EVIDENCE"
  fi
}

# `Inspect first journey summary` uses this mode through command substitution.
# It deliberately prints only the boolean consumed by the existing workflow
# branch; the caller still owns the GITHUB_OUTPUT contract.
print_first_summary_verdict() {
  local first_failure="${1:-false}" artifacts_dir="${2:-artifacts}"
  local first_enumeration_stall=false

  if [[ "$first_failure" != "true" ]]; then
    classify_artifacts "$artifacts_dir"
    [[ "$ENUMERATION_STALL_VERDICT" == "INFRA" ]] && first_enumeration_stall=true
  fi

  printf '%s\n' "$first_enumeration_stall"
}

# `Classify emulator-journey result` keeps its existing write_verdict and exit
# handling in the workflow. This mode owns only the long, typed warning and
# remains fail-safe when the retry has replaced the evidence directory.
print_enumeration_stall_warning() {
  local artifacts_dir="${1:-artifacts}"
  local evidence

  classify_artifacts "$artifacts_dir"
  evidence="${ENUMERATION_STALL_EVIDENCE:-<missing>}"
  printf '%s\n' "::warning title=Emulator journey INFRA — bounded tmux list-sessions enumeration stall::The failed attempt contains a complete device-logcat + harness-manifest proof that the app's bounded tmux list-sessions call stalled. Typed reason=tmux_list_sessions_enumeration_stall; evidence=${evidence}. The suite remains non-green and the aggregate will request a rerun; generic budget timeouts without this exact proof remain RED."
}

# Both timeout branches in tests.yml need the same class-list extraction but
# retain their distinct diagnostics. Keeping this in the helper prevents the
# workflow from growing another large inline shell block.
print_timeout_diagnostic() {
  local artifacts_dir="${1:-artifacts}" variant="${2:-both}"
  local summary="$artifacts_dir/ci-journey/summary.md"
  local timed_out_classes

  timed_out_classes="$(awk '/JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted/{f=1} f && /^- /{gsub(/`/,""); sub(/^- /,"    "); print}' "$summary" 2>/dev/null || true)"
  if [[ "$variant" == "first" ]]; then
    printf '%s\n' '::error title=Emulator journey TIMEOUT — load-bearing classes cut short (#835)::The journey suite booted and ran, but exhausted its own 4200s time budget before every load-bearing class reached a verdict. This is a HARD RED durable guard (#835, D31): a budget timeout means a load-bearing class was silently cut short. No exact JOURNEY_ENUMERATION_STALL proof was accepted, so the cause is intentionally unclassified; investigate the runner/artifacts and do not infer a list-sessions stall. Class(es) cut short / not run:'
  else
    printf '%s\n' '::error title=Emulator journey TIMEOUT — load-bearing classes cut short (#835)::The journey suite booted and ran, but exhausted its own 4200s time budget before every load-bearing class reached a verdict. This is a HARD RED durable guard (#835, D31), not advisory-green. No exact JOURNEY_ENUMERATION_STALL proof was accepted, so the cause is intentionally unclassified; this is distinct from a never-booted emulator (#771) and from a genuine test regression. Class(es) cut short / not run:'
  fi
  [[ -n "$timed_out_classes" ]] && printf '%s\n' "$timed_out_classes"
}

mode="${1:-}"
case "$mode" in
  --workflow-first-summary)
    shift
    print_first_summary_verdict "${1:-false}" "${2:-artifacts}"
    ;;
  --workflow-enumeration-warning)
    shift
    print_enumeration_stall_warning "${1:-artifacts}"
    ;;
  --workflow-timeout-diagnostic)
    shift
    print_timeout_diagnostic "${1:-artifacts}" "${2:-both}"
    ;;
  *)
    print_classifier_result "${1:-artifacts}"
    ;;
esac
