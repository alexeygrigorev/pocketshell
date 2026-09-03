#!/usr/bin/env bash
# check-nightly-workflow.sh — issue #2252, decision D37.
#
# WHAT THIS GUARDS, AND WHY IT MOVED.
#
# An invalid or mis-wired scheduled workflow creates a failed run with zero jobs
# and no log — the #2252 failure — and a scheduled cadence that silently does
# not run is indistinguishable from one that ran green. D37 exists because
# waiving the scheduled fault gate was routine (v0.4.31-v0.4.38, v0.4.45) and
# #1671 traced the #1610 reconnect storm reaching the maintainer to exactly that.
#
# It used to validate .github/workflows/nightly-extensive.yml's guard /
# extensive / fault-verdict job graph. That workflow was deleted with the `:app`
# module every one of its five Gradle invocations targeted, and D37's cadence
# moved to the `app2 journey suite` job of .github/workflows/app2.yml — the lane
# that now runs the toxiproxy network-fault journeys (issue #2474 runs them
# inside one unfiltered instrumentation pass rather than as a separate phase, so
# the job's own conclusion IS the fault verdict; see
# scripts/check-nightly-fault-run.sh, which consumes it).
#
# So this guard now pins the properties D37 actually depends on for that lane:
#   1. the workflow is valid YAML with a job mapping;
#   2. the `app2-journey` job exists and is named what the verdict consumer
#      greps for;
#   3. a `schedule:` trigger carries the 02:17 UTC cron (the cadence itself);
#   4. the journey job is FAIL-CLOSED — no `continue-on-error: true`;
#   5. the journey job has no bypass knob — its `if:` reads no `inputs.`, so no
#      workflow_dispatch input can turn the gate off;
#   6. a SCHEDULED run actually reaches the job — its `if:` excludes only
#      pull_request, never schedule;
#   7. a scheduled run is not cancellable by an unrelated push — `schedule` gets
#      its own concurrency group and is exempt from cancel-in-progress. Without
#      this a push could kill the nightly mid-flight and the cadence would read
#      as `cancelled`, not `failure`: a bypass by accident, which is precisely
#      the D37 shape.
#
# Usage:
#   scripts/check-nightly-workflow.sh              # check the real workflow
#   scripts/check-nightly-workflow.sh --self-test  # red/green proof per check

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_WORKFLOW="$ROOT_DIR/.github/workflows/app2.yml"
JOURNEY_JOB="app2-journey"
JOURNEY_JOB_NAME_NEEDLE="app2 journey suite"
JOURNEY_CRON="17 2 * * *"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

check_workflow() {
  local workflow="$1"
  command -v ruby >/dev/null 2>&1 || {
    fail "ruby is required to parse GitHub Actions YAML"
  }

  ruby - "$workflow" "$JOURNEY_JOB" "$JOURNEY_JOB_NAME_NEEDLE" "$JOURNEY_CRON" <<'RUBY'
require "yaml"

path, job_key, job_needle, cron = ARGV.values_at(0, 1, 2, 3)

begin
  document = YAML.safe_load_file(path, aliases: true)
rescue Psych::Exception => error
  abort "FAIL: #{path} is not valid YAML: #{error.message.lines.first.strip}"
end

abort "FAIL: #{path} must contain a mapping at the document root" unless document.is_a?(Hash)

# Psych's YAML 1.1 loader treats the YAML 1.2 `on` key as boolean true. Accept
# either representation so this validates the document, not the parser's schema.
events = document["on"] || document[true]
abort "FAIL: #{path} has no top-level on: mapping" unless events.is_a?(Hash)

# 3. the cadence itself.
schedule = events["schedule"]
unless schedule.is_a?(Array) && schedule.any? { |e| e.is_a?(Hash) && e["cron"] == cron }
  abort "FAIL: the #{cron} scheduled trigger is missing — D37 requires this lane to run on a cadence, not only on push"
end

jobs = document["jobs"]
abort "FAIL: jobs must be a non-empty mapping" unless jobs.is_a?(Hash) && !jobs.empty?

# 2. the job exists, and is named what the verdict consumer greps for.
journey = jobs[job_key]
abort "FAIL: the #{job_key} job is missing" unless journey.is_a?(Hash)
unless journey["name"].to_s.include?(job_needle)
  abort "FAIL: #{job_key}.name must contain #{job_needle.inspect} — scripts/check-nightly-fault-run.sh identifies the job by that substring, so renaming it silently removes the release gate's only fault signal (got #{journey["name"].inspect})"
end

# 4. fail-closed.
if journey["continue-on-error"] == true
  abort "FAIL: #{job_key} must not set continue-on-error: true — a fault verdict that cannot fail is not a verdict"
end

journey_if = journey["if"].to_s

# 5. no bypass knob.
if journey_if.include?("inputs.")
  abort "FAIL: #{job_key}'s if: reads a workflow input (#{journey_if}) — D37 forbids an off switch for this gate"
end

# 6. a scheduled run must reach the job.
if journey_if.include?("schedule")
  abort "FAIL: #{job_key}'s if: mentions the schedule event (#{journey_if}) — the cadence must not be able to skip its own job"
end

# 7. a scheduled run must not be cancellable by an unrelated push.
concurrency = document["concurrency"]
abort "FAIL: #{path} has no concurrency mapping" unless concurrency.is_a?(Hash)
group = concurrency["group"].to_s
cancel = concurrency["cancel-in-progress"].to_s
unless group.include?("schedule")
  abort "FAIL: the concurrency group does not special-case schedule (#{group}) — a scheduled run sharing a push's group can be cancelled mid-flight, and a cancelled cadence reads as 'not failed' while proving nothing"
end
unless cancel.include?("schedule")
  abort "FAIL: cancel-in-progress does not exempt schedule (#{cancel}) — see the group check above"
end

puts "PASS: #{path} is valid YAML, carries the #{cron} cadence, and its #{job_key} job is fail-closed, bypass-free, schedule-reachable and cancellation-safe"
RUBY
}

self_test() {
  local temp_dir valid m
  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/journey-workflow-check.XXXXXX")"
  trap 'rm -rf "$temp_dir"' RETURN

  # The REAL workflow is the known-good control, so the fixture cannot drift
  # away from the job graph it claims to validate.
  valid="$temp_dir/valid.yml"
  cp "$DEFAULT_WORKFLOW" "$valid"
  check_workflow "$valid" >/dev/null || fail "self-test control: the real workflow does not pass its own guard"
  echo "  ok: the shipped workflow is the green control"

  # Each mutation removes exactly one pinned property and must be named.
  expect_red() {  # $1 = label, $2 = mutant file, $3 = expected substring
    if check_workflow "$2" >"$temp_dir/out" 2>&1; then
      cat "$temp_dir/out" >&2
      fail "$1: mutant was accepted"
    fi
    grep -qF "$3" "$temp_dir/out" || {
      cat "$temp_dir/out" >&2
      fail "$1: reddened for the wrong reason (wanted: $3)"
    }
    echo "  ok: $1"
  }

  m="$temp_dir/syntax.yml"; cp "$valid" "$m"
  printf '\n  bad: [unclosed\n' >> "$m"
  expect_red "malformed YAML is rejected" "$m" "not valid YAML"

  m="$temp_dir/nocron.yml"; cp "$valid" "$m"
  sed -i 's/    - cron: "17 2 \* \* \*"/    - cron: "17 2 * * 0"/' "$m"
  expect_red "a moved/removed cadence is rejected" "$m" "scheduled trigger is missing"

  m="$temp_dir/renamed.yml"; cp "$valid" "$m"
  sed -i 's/^    name: app2 journey suite.*$/    name: something else entirely/' "$m"
  expect_red "renaming the job away from the verdict needle is rejected" "$m" "identifies the job by that substring"

  m="$temp_dir/soft.yml"; cp "$valid" "$m"
  sed -i "/^  app2-journey:/a\\    continue-on-error: true" "$m"
  expect_red "a continue-on-error journey job is rejected" "$m" "not a verdict"

  m="$temp_dir/bypass.yml"; cp "$valid" "$m"
  sed -i "s|^    if: \${{ !cancelled() && github.event_name != 'pull_request'|    if: \${{ !cancelled() \&\& inputs.skip_journeys != true \&\& github.event_name != 'pull_request'|" "$m"
  expect_red "a workflow-input bypass is rejected" "$m" "D37 forbids an off switch"

  m="$temp_dir/cancellable.yml"; cp "$valid" "$m"
  sed -i "s|^  cancel-in-progress: .*$|  cancel-in-progress: true|" "$m"
  expect_red "a schedule-cancellable lane is rejected" "$m" "cancel-in-progress does not exempt schedule"

  m="$temp_dir/sharedgroup.yml"; cp "$valid" "$m"
  sed -i "s|^  group: .*$|  group: \${{ github.workflow }}-\${{ github.ref }}|" "$m"
  expect_red "a shared concurrency group is rejected" "$m" "does not special-case schedule"

  echo "PASS: check-nightly-workflow self-test."
}

case "${1:-}" in
  --self-test) self_test ;;
  "") check_workflow "$DEFAULT_WORKFLOW" ;;
  *) echo "unknown argument: $1" >&2; echo "usage: $0 [--self-test]" >&2; exit 1 ;;
esac
