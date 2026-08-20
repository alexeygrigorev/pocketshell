#!/usr/bin/env bash
# Issue #2252: keep the Nightly Extensive workflow parseable and event-bound.
#
# A malformed workflow can produce a failed push run with zero jobs and no
# downloadable log. That is not a useful nightly verdict. The workflow must
# have only its supported schedule/manual triggers, and its release-gating
# fault-verdict job must remain wired to the guard and extensive suite.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
DEFAULT_WORKFLOW="$ROOT_DIR/.github/workflows/nightly-extensive.yml"

fail() {
  echo "FAIL: $*" >&2
  return 1
}

check_workflow() {
  local workflow="$1"

  [[ -f "$workflow" ]] || { fail "workflow missing: $workflow"; return 1; }
  command -v ruby >/dev/null 2>&1 || {
    fail "ruby is required to parse GitHub Actions YAML"
    return 1
  }

  ruby - "$workflow" <<'RUBY'
require "yaml"

path = ARGV.fetch(0)

begin
  document = YAML.safe_load_file(path, aliases: true)
rescue Psych::Exception => error
  abort "FAIL: #{path} is not valid YAML: #{error.message.lines.first.strip}"
end

abort "FAIL: #{path} must contain a mapping at the document root" unless document.is_a?(Hash)

# Psych's YAML 1.1 loader treats the YAML 1.2 `on` key as boolean true. Accept
# either representation so this check validates the document rather than the
# parser's schema choice.
events = document["on"] || document[true]
abort "FAIL: #{path} has no top-level on: mapping" unless events.is_a?(Hash)

event_names = events.keys.map(&:to_s).sort
expected_events = %w[schedule workflow_dispatch]
unless event_names == expected_events
  abort "FAIL: Nightly Extensive triggers must be exactly schedule + workflow_dispatch (got #{event_names.inspect})"
end

dispatch = events["workflow_dispatch"]
abort "FAIL: workflow_dispatch must remain a mapping with inputs" unless dispatch.is_a?(Hash)
force_run = dispatch.dig("inputs", "force_run")
abort "FAIL: workflow_dispatch.inputs.force_run is missing" unless force_run.is_a?(Hash)
abort "FAIL: force_run must remain a boolean input" unless force_run["type"] == "boolean"
abort "FAIL: force_run must remain enabled by default" unless force_run["default"] == true

schedule = events["schedule"]
unless schedule.is_a?(Array) && schedule.any? { |entry| entry.is_a?(Hash) && entry["cron"] == "17 2 * * *" }
  abort "FAIL: the 02:17 UTC scheduled nightly trigger is missing"
end

jobs = document["jobs"]
abort "FAIL: jobs must be a non-empty mapping" unless jobs.is_a?(Hash) && !jobs.empty?

guard = jobs["guard"]
extensive = jobs["extensive"]
fault = jobs["fault-verdict"]
abort "FAIL: guard job is missing" unless guard.is_a?(Hash)
abort "FAIL: extensive job is missing" unless extensive.is_a?(Hash)
abort "FAIL: fault-verdict job is missing" unless fault.is_a?(Hash)

guard_steps = guard["steps"]
abort "FAIL: guard job has no steps" unless guard_steps.is_a?(Array)
guard_self_test = guard_steps.find { |step| step.is_a?(Hash) && step["name"].to_s.include?("fault-verdict logic") }
unless guard_self_test &&
       guard_self_test["run"].to_s.include?("scripts/lib/nightly-fault-verdict.sh --self-test") &&
       guard_self_test["run"].to_s.include?("scripts/check-nightly-fault-run.sh --self-test")
  abort "FAIL: guard must self-test the fault-verdict logic"
end

fault_needs = Array(fault["needs"]).map(&:to_s)
unless %w[guard extensive].all? { |job| fault_needs.include?(job) }
  abort "FAIL: fault-verdict must need both guard and extensive (got #{fault_needs.inspect})"
end

fault_if = fault["if"].to_s
unless fault_if.include?("always()") && fault_if.include?("needs.guard.outputs.should_run == 'true'")
  abort "FAIL: fault-verdict must run whenever the guard enables the suite"
end
abort "FAIL: fault-verdict must remain fail-closed" if fault["continue-on-error"] == true

fault_steps = fault["steps"].select { |step| step.is_a?(Hash) }
evaluation = fault_steps.find { |step| step["name"].to_s.include?("Evaluate fault-injection safety verdict") }
abort "FAIL: fault-verdict evaluation step is missing" unless evaluation

# The release gate must parse the machine-readable verdict field itself. Do not
# accept a diagnostic mention of `fault_verdict`: a mutant can keep that text in
# an echo while changing the grep to another field and silently stop gating on
# the actual verdict.
expected_verdict_extraction = %q{verdict="$(grep -E '^fault_verdict=' "$file" | head -1 | cut -d= -f2)"}
unless evaluation["run"].to_s.lines.any? { |line| line.strip == expected_verdict_extraction }
  abort "FAIL: fault-verdict evaluation must extract the ^fault_verdict= field into verdict"
end

puts "PASS: #{path} has valid YAML, only schedule/workflow_dispatch triggers, and intact fault-verdict wiring"
RUBY
}

self_test() {
  local temp_dir valid syntax_mutant trigger_mutant fault_mutant verdict_extract_mutant
  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/nightly-workflow-check.XXXXXX")"
  trap 'rm -rf "$temp_dir"' RETURN

  valid="$temp_dir/valid.yml"
  # The current workflow is the fixture's source so the test stays aligned with
  # the real job graph. The one-line repair creates the known-good control while
  # the mutants below prove each load-bearing check can go red.
  cp "$DEFAULT_WORKFLOW" "$valid"
  sed -i 's/^           if-no-files-found: error$/          if-no-files-found: error/' "$valid"
  check_workflow "$valid"

  syntax_mutant="$temp_dir/syntax-mutant.yml"
  cp "$valid" "$syntax_mutant"
  sed -i 's/^          if-no-files-found: error$/           if-no-files-found: error/' "$syntax_mutant"
  if check_workflow "$syntax_mutant" >"$temp_dir/syntax.out" 2>&1; then
    fail "YAML syntax mutant was accepted"
  fi
  grep -q 'not valid YAML' "$temp_dir/syntax.out" || {
    cat "$temp_dir/syntax.out" >&2
    fail "YAML syntax mutant did not fail through the parser"
  }
  echo "  ok: malformed YAML is rejected"

  trigger_mutant="$temp_dir/trigger-mutant.yml"
  cp "$valid" "$trigger_mutant"
  sed -i '/^  workflow_dispatch:/i\  push:' "$trigger_mutant"
  if check_workflow "$trigger_mutant" >"$temp_dir/trigger.out" 2>&1; then
    fail "push-trigger mutant was accepted"
  fi
  grep -q 'triggers must be exactly' "$temp_dir/trigger.out" || {
    cat "$temp_dir/trigger.out" >&2
    fail "push-trigger mutant did not fail through the event contract"
  }
  echo "  ok: push trigger is rejected"

  fault_mutant="$temp_dir/fault-mutant.yml"
  cp "$valid" "$fault_mutant"
  sed -i 's/^  fault-verdict:/  fault-verdict-broken:/' "$fault_mutant"
  if check_workflow "$fault_mutant" >"$temp_dir/fault.out" 2>&1; then
    fail "fault-verdict wiring mutant was accepted"
  fi
  grep -q 'fault-verdict job is missing' "$temp_dir/fault.out" || {
    cat "$temp_dir/fault.out" >&2
    fail "fault-verdict mutant did not fail through the gate contract"
  }
  echo "  ok: fault-verdict wiring mutant is rejected"

  verdict_extract_mutant="$temp_dir/verdict-extraction-mutant.yml"
  cp "$valid" "$verdict_extract_mutant"
  # This is the exact evaluator mutation found by the reviewer: preserve the
  # surrounding diagnostics while replacing the field that feeds `verdict`.
  sed -i "s|grep -E '\\^fault_verdict='|grep -E '^network_fault_status='|" "$verdict_extract_mutant"
  grep -Fq "grep -E '^network_fault_status='" "$verdict_extract_mutant" || {
    cat "$verdict_extract_mutant" >&2
    fail "fault-verdict extraction self-test mutation did not apply"
  }
  if check_workflow "$verdict_extract_mutant" >"$temp_dir/verdict-extraction.out" 2>&1; then
    fail "fault-verdict extraction mutant was accepted"
  fi
  grep -q 'must extract the \^fault_verdict= field into verdict' "$temp_dir/verdict-extraction.out" || {
    cat "$temp_dir/verdict-extraction.out" >&2
    fail "fault-verdict extraction mutant did not fail through the evaluator contract"
  }
  echo "  ok: exact fault-verdict extraction mutation is rejected"
  echo "PASS: check-nightly-workflow self-test"
}

case "${1:-}" in
  --self-test)
    self_test
    ;;
  "")
    check_workflow "$DEFAULT_WORKFLOW"
    ;;
  *)
    check_workflow "$1"
    ;;
esac
