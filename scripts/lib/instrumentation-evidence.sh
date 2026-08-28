#!/usr/bin/env bash

# Shared release-gate instrumentation evidence helpers (issue #2300).
#
# The Android runner's exit status and INSTRUMENTATION_CODE describe the
# runner, not whether the selected test body executed. Keep the three facts
# separate: a positive OK count proves execution, status blocks prove the
# requested selector attended this run, and the raw log remains the audit
# source for both.

pocketshell_instrumentation_selector_parts() {
  local selector="$1"
  local selector_class="${selector%%#*}"
  local selector_method=""
  if [[ "$selector" == *"#"* ]]; then
    selector_method="${selector#*#}"
  fi
  if [[ -z "$selector_class" || "$selector_class" == *[!A-Za-z0-9_.]* ]]; then
    printf 'invalid instrumentation selector: %s\n' "$selector" >&2
    return 1
  fi
  if [[ "$selector" == *"#"* ]]; then
    if [[ -z "$selector_method" || "$selector_method" == *[!A-Za-z0-9_]* ]]; then
      printf 'invalid instrumentation selector: %s\n' "$selector" >&2
      return 1
    fi
  fi
  [[ "$selector" != *"#"*"#"* ]] || {
    printf 'invalid instrumentation selector: %s\n' "$selector" >&2
    return 1
  }
  POCKETSHELL_INSTRUMENTATION_SELECTOR_CLASS="$selector_class"
  POCKETSHELL_INSTRUMENTATION_SELECTOR_METHOD="$selector_method"
}

pocketshell_instrumentation_ok_count() {
  local log_file="$1"
  [[ -f "$log_file" ]] || return 1
  local count
  count="$(sed -nE 's/^OK \(([1-9][0-9]*) tests?[[:space:]]*\)$/\1/p' "$log_file" | tail -n 1)"
  [[ "$count" =~ ^[1-9][0-9]*$ ]] || return 1
  printf '%s\n' "$count"
}

pocketshell_instrumentation_has_runner_success_code() {
  local log_file="$1"
  [[ -f "$log_file" ]] || return 1
  awk '
    {
      line = $0
      sub(/\r$/, "", line)
      if (line == "INSTRUMENTATION_CODE: -1") { found = 1 }
    }
    END { exit(found ? 0 : 1) }
  ' "$log_file"
}

pocketshell_instrumentation_has_positive_success() {
  local log_file="$1"
  pocketshell_instrumentation_has_runner_success_code "$log_file" &&
    pocketshell_instrumentation_ok_count "$log_file" >/dev/null
}

pocketshell_instrumentation_selector_attended() {
  local log_file="$1"
  local selector="$2"
  [[ -f "$log_file" ]] || return 1
  pocketshell_instrumentation_selector_parts "$selector" || return 1
  local expected_class="$POCKETSHELL_INSTRUMENTATION_SELECTOR_CLASS"
  local expected_method="$POCKETSHELL_INSTRUMENTATION_SELECTOR_METHOD"

  awk -v expected_class="$expected_class" -v expected_method="$expected_method" '
    function reset_block() {
      saw_class = 0
      saw_method = 0
    }
    {
      line = $0
      sub(/\r$/, "", line)
      if (line == "INSTRUMENTATION_STATUS: class=" expected_class) {
        saw_class = 1
      }
      if (expected_method != "" &&
          line == "INSTRUMENTATION_STATUS: test=" expected_method) {
        saw_method = 1
      }
      if (line ~ /^INSTRUMENTATION_STATUS_CODE:/) {
        if (saw_class && (expected_method == "" || saw_method)) {
          found = 1
        }
        reset_block()
      }
    }
    END {
      if (saw_class && (expected_method == "" || saw_method)) { found = 1 }
      exit(found ? 0 : 1)
    }
  ' "$log_file"
}

pocketshell_instrumentation_has_failure_markers() {
  local log_file="$1"
  [[ -f "$log_file" ]] || return 1
  grep -Eq '(^FAILURES!!!$|^FAILURE: |^INSTRUMENTATION_STATUS_CODE: -[0-9]+$|^INSTRUMENTATION_STATUS: stack=|^INSTRUMENTATION_RESULT: shortMsg=Process crashed[.]|^[[:space:]]*at (com[.]pocketshell|androidx[.]test|org[.]junit|kotlin[.]|java[.]|android[.])|^[[:alnum:]_.]*(Exception|Error): |^Process crashed[.])' "$log_file"
}

pocketshell_instrumentation_assert_log() {
  local log_file="$1"
  local selector="$2"
  local count
  [[ -f "$log_file" ]] || {
    printf 'instrumentation evidence is missing: %s\n' "$log_file" >&2
    return 1
  }
  if ! count="$(pocketshell_instrumentation_ok_count "$log_file")"; then
    printf '%s did not report a positive executed-test count (`OK (N test[s])`, N > 0)\n' "$selector" >&2
    return 1
  fi
  if ! pocketshell_instrumentation_has_runner_success_code "$log_file"; then
    printf '%s did not report INSTRUMENTATION_CODE: -1\n' "$selector" >&2
    return 1
  fi
  if ! pocketshell_instrumentation_selector_attended "$log_file" "$selector"; then
    printf '%s did not appear in an instrumentation status block for this run\n' "$selector" >&2
    return 1
  fi
  if pocketshell_instrumentation_has_failure_markers "$log_file"; then
    printf '%s instrumentation log contains failure markers\n' "$selector" >&2
    return 1
  fi
  printf '%s\n' "$count"
}

pocketshell_initialize_release_selector_attendance() {
  local attendance_file="$1"
  local run_id="$2"
  local marker_file="$3"
  shift 3
  [[ "$run_id" =~ ^[A-Za-z0-9._-]+$ ]] || {
    printf 'invalid release selector attendance run id: %s\n' "$run_id" >&2
    return 1
  }
  [[ "$#" -gt 0 ]] || {
    printf 'release selector attendance selected set is empty\n' >&2
    return 1
  }
  mkdir -p "$(dirname "$attendance_file")" "$(dirname "$marker_file")"
  : > "$marker_file"
  touch "$marker_file"
  {
    printf '# pocketshell-release-selector-attendance v1\n'
    printf 'run_id\t%s\n' "$run_id"
  } > "$attendance_file"
  local selector
  for selector in "$@"; do
    pocketshell_instrumentation_selector_parts "$selector" || return 1
  done
}

pocketshell_record_release_selector_attendance() {
  local attendance_file="$1"
  local run_id="$2"
  local selector="$3"
  local log_file="$4"
  local count digest
  [[ -f "$attendance_file" ]] || {
    printf 'release selector attendance ledger is missing: %s\n' "$attendance_file" >&2
    return 1
  }
  grep -Fqx $'run_id\t'"$run_id" "$attendance_file" || {
    printf 'release selector attendance ledger has the wrong run id: %s\n' "$attendance_file" >&2
    return 1
  }
  pocketshell_instrumentation_selector_parts "$selector" || return 1
  count="$(pocketshell_instrumentation_assert_log "$log_file" "$selector")" || return 1
  digest="$(sha256sum "$log_file" | awk '{print $1}')"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || {
    printf 'could not hash raw instrumentation evidence: %s\n' "$log_file" >&2
    return 1
  }
  printf 'selector\t%s\t%s\t%s\t%s\n' \
    "$selector" "$count" "$log_file" "$digest" >> "$attendance_file"
}

# Keep release-gate attendance tied to the command that validates the current
# run. Callers pass the real checker and its arguments; this wrapper must return
# the checker result unchanged so a successful-looking selector token cannot
# bypass current-run validation.
pocketshell_run_release_selector_checker() {
  local checker="$1"
  shift
  [[ -x "$checker" ]] || {
    printf 'release selector execution checker is missing or not executable: %s\n' "$checker" >&2
    return 1
  }
  "$checker" "$@"
}
