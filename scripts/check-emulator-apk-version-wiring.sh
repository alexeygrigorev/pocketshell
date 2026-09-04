#!/usr/bin/env bash
# Issue #2381 — an emulator job must build an APK with a REAL derived version,
# and the Docker `agents` fixture it runs against must report the same one.
#
# WHY
#
# Issue #2356 made `versionName` derive from the git tag
# (scripts/derive-version.sh). The app treats the RELEASE CORE of its own
# versionName as the `pocketshell` CLI version it expects on the host, so the
# derived value is load-bearing product input, not decoration:
#
#   * A shallow checkout has no `v*` tag reachable, so the derivation falls back
#     to `0.0.0-dev+<sha>`. `0.0.0` sits BELOW every real CLI version, so the
#     setup-detection scenarios that need "the host CLI is outdated"
#     (HostBootstrapScenarioSuiteTest#uvUpgrade / #uvUpgradeFailure) cannot be
#     expressed at all, and the #515 "New version available" banner is on for
#     the whole run.
#   * tests/docker/Dockerfile.agents used to sed the pre-#2356 `versionName =
#     "X.Y.Z"` literal out of a COPYed app/build.gradle.kts, which kept the
#     fixture and the APK in lockstep by construction. That literal is gone,
#     the sed silently matched nothing, and the fixture froze — so the app
#     reported VersionMismatch and the bootstrap "Host setup needed" sheet took
#     over journeys that were asserting some other screen entirely. The fixture
#     is now stamped from POCKETSHELL_AGENT_FIXTURE_VERSION at container start
#     (tests/docker/agent-entrypoint.sh).
#
# The two halves must move together: full tag history WITHOUT the fixture stamp
# re-breaks every `agents` journey, and the stamp without tag history leaves the
# setup gate untestable. This guard pins both.
#
# CHECKS
#   1. Every workflow job that uses reactivecircus/android-emulator-runner
#      checks out with `fetch-depth: 0`.
#   2. tests/docker/docker-compose.yml's `agents` service forwards
#      POCKETSHELL_AGENT_FIXTURE_VERSION into the container.
#   3. tests/docker/agent-entrypoint.sh stamps it into the version file.
#   4. tests/docker/Dockerfile.agents no longer parses app/build.gradle.kts
#      (the dead pre-#2356 derivation, removed under D22).
#   5. THE CALL SITES. Every script that brings the `agents` service up must
#      first stamp it (scripts/lib/agents-fixture-version.sh). Checks 2-4 pin
#      the container half and check 1 pins the workflow half, but the coupling
#      has a third leg: a script can satisfy both and still hand the fixture
#      nothing, in which case it falls back to the baked `0.0.0-dev` and the
#      app it installs sees a version mismatch. That is exactly how
#      scripts/pre-release-confidence-gate.sh and the visual-audit capture
#      shipped broken in round 1 of #2381 — enforced by convention, not by a
#      gate. Enforcing it only by convention is what produced this whole issue.
#      Covers scripts/ AND .github/workflows/ (a workflow may bring the fixture
#      up inline), and --self-test additionally pins the stamping helper's own
#      precedence, since "you must call it" is worthless if the call returns the
#      wrong version.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

EMULATOR_ACTION="reactivecircus/android-emulator-runner"
FIXTURE_VERSION_VAR="POCKETSHELL_AGENT_FIXTURE_VERSION"

failures=0

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

# Emit "<workflow>\t<job>\t<uses-emulator:0|1>\t<fetch-depth-0:0|1>" per job.
emulator_job_rows() {
  local workflow_dir="$1"
  ruby - "$workflow_dir" "$EMULATOR_ACTION" <<'RUBY'
require "yaml"

dir, action = ARGV
Dir.glob(File.join(dir, "*.yml")).sort.each do |path|
  begin
    doc = YAML.safe_load_file(path, aliases: true)
  rescue Psych::Exception => e
    warn "FAIL: #{path} is not valid YAML: #{e.message.lines.first.to_s.strip}"
    exit 3
  end
  next unless doc.is_a?(Hash)
  jobs = doc["jobs"]
  next unless jobs.is_a?(Hash)

  jobs.each do |job_name, job|
    next unless job.is_a?(Hash)
    steps = job["steps"]
    next unless steps.is_a?(Array)

    uses_emulator = steps.any? do |s|
      s.is_a?(Hash) && s["uses"].to_s.start_with?(action)
    end
    # A job may legitimately have several checkouts; require that EVERY
    # actions/checkout in an emulator job asks for full history, because any
    # one of them can be the tree the APK is built from.
    checkouts = steps.select do |s|
      s.is_a?(Hash) && s["uses"].to_s.start_with?("actions/checkout")
    end
    full_history = !checkouts.empty? && checkouts.all? do |s|
      with = s["with"]
      with.is_a?(Hash) && with["fetch-depth"].to_s == "0"
    end

    printf("%s\t%s\t%d\t%d\n", File.basename(path), job_name,
           uses_emulator ? 1 : 0, full_history ? 1 : 0)
  end
end
RUBY
}

check_emulator_jobs() {
  local workflow_dir="$1"
  local rows seen=0
  rows="$(emulator_job_rows "$workflow_dir")" || {
    fail "could not parse the workflows under $workflow_dir"
    return
  }

  while IFS=$'\t' read -r workflow job uses_emulator full_history; do
    [[ -n "${workflow:-}" ]] || continue
    [[ "$uses_emulator" == "1" ]] || continue
    seen=$((seen + 1))
    if [[ "$full_history" != "1" ]]; then
      fail "$workflow job '$job' boots an emulator but does not check out with fetch-depth: 0 —" \
        "its APK would report the 0.0.0-dev placeholder versionName (#2381)"
    else
      printf '  ok: %s job %s checks out full tag history\n' "$workflow" "$job"
    fi
  done <<< "$rows"

  if [[ "$seen" -eq 0 ]]; then
    fail "found no $EMULATOR_ACTION job under $workflow_dir — this guard would pass vacuously"
  fi
}

check_fixture_version_wiring() {
  local root="$1"
  local compose="$root/tests/docker/docker-compose.yml"
  local entrypoint="$root/tests/docker/agent-entrypoint.sh"
  local dockerfile="$root/tests/docker/Dockerfile.agents"

  if grep -q "$FIXTURE_VERSION_VAR" "$compose" 2>/dev/null; then
    printf '  ok: docker-compose.yml forwards %s to the agents fixture\n' "$FIXTURE_VERSION_VAR"
  else
    fail "tests/docker/docker-compose.yml must forward $FIXTURE_VERSION_VAR into the agents service (#2381)"
  fi

  if grep -q "$FIXTURE_VERSION_VAR" "$entrypoint" 2>/dev/null; then
    printf '  ok: agent-entrypoint.sh stamps %s into the fixture version file\n' "$FIXTURE_VERSION_VAR"
  else
    fail "tests/docker/agent-entrypoint.sh must stamp $FIXTURE_VERSION_VAR into the version file (#2381)"
  fi

  # Comments explaining the removal are fine; an INSTRUCTION referencing it is
  # the dead derivation coming back.
  if grep -v '^[[:space:]]*#' "$dockerfile" 2>/dev/null | grep -q 'build\.gradle\.kts'; then
    fail "tests/docker/Dockerfile.agents still references app/build.gradle.kts — the pre-#2356" \
      "versionName literal it parsed no longer exists, so that derivation is dead (#2381, D22)"
  else
    printf '  ok: Dockerfile.agents no longer parses the removed build.gradle.kts literal\n'
  fi
}

# --------------------------------------------------------------------------
# Check 5 — call sites.
# --------------------------------------------------------------------------

# Files under scripts/ that CONTAIN a matching line but never RUN it: the
# command text is quoted data (a synthetic fixture script, a grep pattern, this
# guard's own docs/self-test). Anything else that brings `agents` up is a real
# caller and must stamp the fixture.
AGENTS_UP_TEXT_ONLY_ALLOWLIST=(
  # Asserts the text of the CI journey fixture-retry wrapper; the compose line
  # lives inside a quoted expectation string, not an invocation.
  "test-ci-journey-fixture-health.sh"
  # This guard: the pattern appears in the header docs and in the self-test.
  "check-emulator-apk-version-wiring.sh"
)

# Echo the 1-based line number of the first line that actually brings the
# `agents` service up via `docker compose ... up ...`, or nothing.
first_agents_up_line() {
  awk '
    /^[[:space:]]*#/ { next }
    {
      idx = index($0, "docker compose")
      if (idx == 0) next
      rest = substr($0, idx)
      p = index(rest, " up ")
      if (p == 0) next
      svc = substr(rest, p + 4)
      # The service list must actually name `agents` (or the AGENT_SERVICE
      # variable that defaults to it). `packet-loss-proxy`, `sshd`, the
      # bootstrap-* profiles and a bare `up` with no service do not count.
      if (svc ~ /(^|[^A-Za-z0-9_])agents([^A-Za-z0-9_]|$)/ || svc ~ /AGENT_SERVICE/) {
        print NR
        exit
      }
    }
  ' "$1"
}

# Echo the 1-based line number of the first non-comment CALL to the stamping
# helper, or nothing.
#
# "Call", not "mention" (round-3 review finding): scripts/lib/agents-pool.sh
# opens with `if ! declare -F export_agents_fixture_version >/dev/null; then`,
# which names the helper without invoking it — the detector credited that line
# and would therefore have accepted a file that only ever *mentions* the name.
# `declare -F` / `command -v` / `type` are introspection, not invocation.
first_fixture_version_call_line() {
  awk '
    /^[[:space:]]*#/ { next }
    /(declare[[:space:]]+-[a-zA-Z]*F|command[[:space:]]+-v|[^A-Za-z0-9_]type[[:space:]])/ { next }
    /export_agents_fixture_version/ { print NR; exit }
  ' "$1"
}

check_agents_up_call_sites() {
  local scripts_dir="$1"
  local file base up_line stamp_line
  local callers=0 allowlisted=0

  while IFS= read -r file; do
    up_line="$(first_agents_up_line "$file")"
    [[ -n "$up_line" ]] || continue
    base="$(basename "$file")"

    local skip=0 entry
    for entry in "${AGENTS_UP_TEXT_ONLY_ALLOWLIST[@]}"; do
      if [[ "$base" == "$entry" ]]; then
        skip=1
        break
      fi
    done
    if [[ "$skip" == "1" ]]; then
      allowlisted=$((allowlisted + 1))
      printf '  ok: %s is on the text-only allowlist (quotes the command, never runs it)\n' "$base"
      continue
    fi

    callers=$((callers + 1))
    stamp_line="$(first_fixture_version_call_line "$file")"
    if [[ -z "$stamp_line" ]]; then
      fail "$base brings the agents fixture up (line $up_line) but never calls" \
        "export_agents_fixture_version — the fixture falls back to its baked 0.0.0-dev while the" \
        "APK reports a derived versionName, so the app lands on the bootstrap \"Host setup needed\"" \
        "sheet (#2381). Source scripts/lib/agents-fixture-version.sh and stamp it before the" \
        "compose up, or add the file to AGENTS_UP_TEXT_ONLY_ALLOWLIST with a reason."
    elif [[ "$stamp_line" -gt "$up_line" ]]; then
      fail "$base calls export_agents_fixture_version at line $stamp_line, AFTER it brings the" \
        "agents fixture up at line $up_line — compose reads the environment at \`up\` time, so the" \
        "stamp never reaches that container (#2381)"
    else
      printf '  ok: %s stamps the agents fixture (line %s) before bringing it up (line %s)\n' \
        "$base" "$stamp_line" "$up_line"
    fi
  done < <(find "$scripts_dir" -type f -name '*.sh' | sort)

  if [[ "$callers" -eq 0 ]]; then
    fail "found no script under $scripts_dir that brings the agents fixture up —" \
      "the call-site detector matched nothing, so this check would pass vacuously"
  fi
  printf '  ok: %d agents-fixture caller(s) checked, %d text-only allowlisted\n' \
    "$callers" "$allowlisted"
}

# Same rule, workflow layer: a CI job can bring `agents` up with an inline
# `docker compose ... up`, which check 5 (scripts/ only) would never see. Such a
# line is fine when it is wrapped by scripts/ci-journey-fixture-retry.sh (which
# stamps), or when the workflow stamps earlier in the same file.
check_workflow_agents_up_call_sites() {
  local workflow_dir="$1"
  local file base up_line stamp_line seen=0

  while IFS= read -r file; do
    up_line="$(first_agents_up_line "$file")"
    [[ -n "$up_line" ]] || continue
    base="$(basename "$file")"
    seen=$((seen + 1))

    if sed -n "${up_line}p" "$file" | grep -q 'ci-journey-fixture-retry\.sh'; then
      printf '  ok: %s brings agents up through ci-journey-fixture-retry.sh (line %s)\n' "$base" "$up_line"
      continue
    fi

    stamp_line="$(first_fixture_version_call_line "$file")"
    if [[ -z "$stamp_line" || "$stamp_line" -gt "$up_line" ]]; then
      fail "$base brings the agents fixture up at line $up_line without stamping it first —" \
        "wrap it in scripts/ci-journey-fixture-retry.sh or source" \
        "scripts/lib/agents-fixture-version.sh and call export_agents_fixture_version above it (#2381)"
    else
      printf '  ok: %s stamps the agents fixture (line %s) before bringing it up (line %s)\n' \
        "$base" "$stamp_line" "$up_line"
    fi
  done < <(find "$workflow_dir" -maxdepth 1 -type f -name '*.yml' | sort)

  if [[ "$seen" -eq 0 ]]; then
    fail "found no workflow under $workflow_dir that brings the agents fixture up —" \
      "the workflow call-site detector matched nothing, so this check would pass vacuously"
  fi
}

# The two release/visual-audit entry points #2381 round 1 left unstamped. If a
# rename or refactor takes them out of the detector's reach, say so loudly
# rather than reporting a green over an unchecked path.
check_named_release_call_sites() {
  local scripts_dir="$1"
  local required=(
    "pre-release-confidence-gate.sh"
    "capture-walkthrough-screenshots.sh"
  )
  local name
  for name in "${required[@]}"; do
    if [[ ! -f "$scripts_dir/$name" ]]; then
      fail "scripts/$name is gone — check 5's coverage of the documented standalone release" \
        "gate / visual-audit path can no longer be asserted (#2381)"
      continue
    fi
    if [[ -z "$(first_agents_up_line "$scripts_dir/$name")" ]]; then
      fail "scripts/$name no longer matches the agents-fixture call-site detector; either it" \
        "stopped bringing the fixture up, or the detector went blind to it (#2381)"
    else
      printf '  ok: %s is inside check 5'"'"'s coverage\n' "$name"
    fi
  done
}

run_self_test() {
  local sandbox
  sandbox="$(mktemp -d)"
  trap 'rm -rf "$sandbox"' RETURN

  mkdir -p "$sandbox/wf"
  cat > "$sandbox/wf/bad.yml" <<'YAML'
name: Bad
on: { push: {} }
jobs:
  journey:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: reactivecircus/android-emulator-runner@v2
        with: { api-level: 35 }
YAML

  local out rc
  set +e
  out="$(check_emulator_jobs "$sandbox/wf" 2>&1; printf 'failures=%s' "$failures")"
  set -e
  if [[ "$out" != *"does not check out with fetch-depth: 0"* ]]; then
    printf 'SELF-TEST FAILED: a shallow emulator job was not rejected.\n%s\n' "$out" >&2
    return 1
  fi
  printf '  ok: self-test red — shallow emulator job rejected\n'

  failures=0
  cat > "$sandbox/wf/bad.yml" <<'YAML'
name: Good
on: { push: {} }
jobs:
  journey:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0
      - uses: reactivecircus/android-emulator-runner@v2
        with: { api-level: 35 }
YAML
  check_emulator_jobs "$sandbox/wf"
  rc="$failures"
  if [[ "$rc" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: a full-history emulator job was rejected.\n' >&2
    return 1
  fi
  printf '  ok: self-test green — full-history emulator job accepted\n'

  # A workflow set with no emulator job must NOT pass vacuously.
  failures=0
  rm -f "$sandbox/wf/bad.yml"
  cat > "$sandbox/wf/none.yml" <<'YAML'
name: None
on: { push: {} }
jobs:
  unit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
YAML
  check_emulator_jobs "$sandbox/wf" >/dev/null 2>&1 || true
  if [[ "$failures" -eq 0 ]]; then
    printf 'SELF-TEST FAILED: an emulator-job-free workflow set passed vacuously.\n' >&2
    return 1
  fi
  printf '  ok: self-test — a workflow set with no emulator job is rejected, not vacuously green\n'

  # ---- check 5: agents-fixture call sites ----
  mkdir -p "$sandbox/scripts"

  # RED: a caller that brings the fixture up without stamping it. This is
  # byte-for-byte the shape scripts/pre-release-confidence-gate.sh and the
  # visual-audit capture had at #2381 round 1.
  failures=0
  cat > "$sandbox/scripts/broken-gate.sh" <<'SH'
#!/usr/bin/env bash
run_step "docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build agents
SH
  # NOT a command substitution: that runs in a subshell, so $failures would
  # never come back and this self-test case could only ever check the message.
  check_agents_up_call_sites "$sandbox/scripts" > "$sandbox/out.txt" 2>&1 || true
  out="$(cat "$sandbox/out.txt")"
  if [[ "$out" != *"never calls"* || "$failures" -eq 0 ]]; then
    printf 'SELF-TEST FAILED: an unstamped agents call site was not rejected.\n%s\n' "$out" >&2
    return 1
  fi
  printf '  ok: self-test red — an unstamped agents call site is rejected\n'

  # GREEN: the same caller with the stamp before the bring-up.
  failures=0
  cat > "$sandbox/scripts/broken-gate.sh" <<'SH'
#!/usr/bin/env bash
source "$ROOT_DIR/scripts/lib/agents-fixture-version.sh"
export_agents_fixture_version_for_run "$BUILD_APKS" "$APP_APK"
run_step "docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build agents
SH
  check_agents_up_call_sites "$sandbox/scripts" >/dev/null 2>&1 || true
  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: a correctly stamped agents call site was rejected.\n' >&2
    return 1
  fi
  printf '  ok: self-test green — a stamped agents call site is accepted\n'

  # RED: stamped, but AFTER the bring-up (compose reads the env at `up` time).
  failures=0
  cat > "$sandbox/scripts/broken-gate.sh" <<'SH'
#!/usr/bin/env bash
run_step "docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build agents
source "$ROOT_DIR/scripts/lib/agents-fixture-version.sh"
export_agents_fixture_version
SH
  check_agents_up_call_sites "$sandbox/scripts" > "$sandbox/out.txt" 2>&1 || true
  out="$(cat "$sandbox/out.txt")"
  if [[ "$out" != *"AFTER it brings the"* || "$failures" -eq 0 ]]; then
    printf 'SELF-TEST FAILED: a too-late stamp was not rejected.\n%s\n' "$out" >&2
    return 1
  fi
  printf '  ok: self-test red — stamping after the compose up is rejected\n'

  # RED (round-3 review finding): the helper name appears only inside a
  # `declare -F` / `command -v` introspection guard. That is a MENTION, not a
  # call — the fixture is never stamped — and the detector must not credit it.
  # scripts/lib/agents-pool.sh opens with exactly this line, so before the fix
  # it was credited at its `declare -F` line instead of its real call.
  failures=0
  cat > "$sandbox/scripts/broken-gate.sh" <<'SH'
#!/usr/bin/env bash
if ! declare -F export_agents_fixture_version >/dev/null 2>&1; then
  source "$ROOT_DIR/scripts/lib/agents-fixture-version.sh"
fi
command -v export_agents_fixture_version >/dev/null || true
docker compose -f "$COMPOSE_FILE" up -d --build agents
SH
  check_agents_up_call_sites "$sandbox/scripts" > "$sandbox/out.txt" 2>&1 || true
  out="$(cat "$sandbox/out.txt")"
  if [[ "$out" != *"never calls"* || "$failures" -eq 0 ]]; then
    printf 'SELF-TEST FAILED: a declare -F/command -v MENTION of the stamping helper was credited as a call.\n%s\n' "$out" >&2
    return 1
  fi
  printf '  ok: self-test red — a declare -F/command -v mention is not credited as a stamp\n'

  # GREEN: the same introspection guard, now with the real call after it. The
  # accepted stamp line must be the CALL, not the `declare -F` line.
  failures=0
  cat > "$sandbox/scripts/broken-gate.sh" <<'SH'
#!/usr/bin/env bash
if ! declare -F export_agents_fixture_version >/dev/null 2>&1; then
  source "$ROOT_DIR/scripts/lib/agents-fixture-version.sh"
fi
export_agents_fixture_version "$APP_APK"
docker compose -f "$COMPOSE_FILE" up -d --build agents
SH
  check_agents_up_call_sites "$sandbox/scripts" > "$sandbox/out.txt" 2>&1 || true
  out="$(cat "$sandbox/out.txt")"
  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: an introspection guard followed by a real call was rejected.\n%s\n' "$out" >&2
    return 1
  fi
  if [[ "$out" != *"(line 5)"* ]]; then
    printf 'SELF-TEST FAILED: the credited stamp line is not the real call at line 5.\n%s\n' "$out" >&2
    return 1
  fi
  printf '  ok: self-test green — the credited stamp line is the real call, not the introspection guard\n'

  # Not a caller: a service list that does not name `agents`.
  failures=0
  cat > "$sandbox/scripts/broken-gate.sh" <<'SH'
#!/usr/bin/env bash
docker compose -f "$COMPOSE_FILE" up -d --build --no-deps packet-loss-proxy
SH
  check_agents_up_call_sites "$sandbox/scripts" >/dev/null 2>&1 || true
  if [[ "$failures" -eq 0 ]]; then
    printf 'SELF-TEST FAILED: a scripts dir with no agents caller passed vacuously.\n' >&2
    return 1
  fi
  printf '  ok: self-test — a scripts dir with no agents caller is rejected, not vacuously green\n'

  # ---- check 5, workflow layer ----
  failures=0
  rm -f "$sandbox/wf"/*.yml
  cat > "$sandbox/wf/journey.yml" <<'YAML'
name: Journey
on: { push: {} }
jobs:
  journey:
    steps:
      - run: docker compose -f tests/docker/docker-compose.yml up -d --build --no-deps agents
YAML
  check_workflow_agents_up_call_sites "$sandbox/wf" > "$sandbox/out.txt" 2>&1 || true
  out="$(cat "$sandbox/out.txt")"
  if [[ "$out" != *"without stamping it first"* || "$failures" -eq 0 ]]; then
    printf 'SELF-TEST FAILED: an unstamped workflow agents call site was not rejected.\n%s\n' "$out" >&2
    return 1
  fi
  printf '  ok: self-test red — an unstamped workflow agents call site is rejected\n'

  failures=0
  cat > "$sandbox/wf/journey.yml" <<'YAML'
name: Journey
on: { push: {} }
jobs:
  journey:
    steps:
      - run: scripts/ci-journey-fixture-retry.sh agents -- docker compose -f tests/docker/docker-compose.yml up -d --build --no-deps agents
YAML
  check_workflow_agents_up_call_sites "$sandbox/wf" >/dev/null 2>&1 || true
  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: the ci-journey-fixture-retry.sh wrapper was rejected.\n' >&2
    return 1
  fi
  printf '  ok: self-test green — the ci-journey-fixture-retry.sh wrapper is accepted\n'

  failures=0
  rm -f "$sandbox/wf"/*.yml
  cat > "$sandbox/wf/none.yml" <<'YAML'
name: None
on: { push: {} }
jobs:
  unit:
    steps:
      - run: echo hi
YAML
  check_workflow_agents_up_call_sites "$sandbox/wf" >/dev/null 2>&1 || true
  if [[ "$failures" -eq 0 ]]; then
    printf 'SELF-TEST FAILED: a workflow set with no agents caller passed vacuously.\n' >&2
    return 1
  fi
  printf '  ok: self-test — a workflow set with no agents caller is rejected, not vacuously green\n'

  run_helper_self_test "$sandbox" || return 1

  failures=0
  printf 'SELF-TEST OK\n'
  return 0
}

# The stamping helper's PRECEDENCE, which is the whole reason check 5 can trust
# a call site that merely calls it. Requiring the call and then getting the
# wrong version back would be the same silent breakage one level down.
#
# No emulator, no Docker, no real APK: `aapt2` is stubbed on PATH, so this
# exercises the resolve -> badging -> parse -> export chain that reads a
# release-chain APK, not a mock of it.
run_helper_self_test() {
  local sandbox="$1"
  local lib="$ROOT_DIR/scripts/lib/agents-fixture-version.sh"

  [[ -f "$lib" ]] || {
    printf 'SELF-TEST FAILED: missing %s\n' "$lib" >&2
    return 1
  }

  mkdir -p "$sandbox/bin"
  cat > "$sandbox/bin/aapt2" <<'SH'
#!/usr/bin/env bash
# Minimal `aapt2 dump badging` stand-in: prints the badging line shape the
# helper parses, with the versionName recorded next to the "APK".
printf "package: name='com.pocketshell.app' versionCode='46' versionName='%s' compileSdkVersion='36'\n" \
  "$(cat "${3:-/dev/null}" 2>/dev/null)"
printf "sdkVersion:'26'\n"
SH
  chmod +x "$sandbox/bin/aapt2"
  printf '9.9.9-apk\n' > "$sandbox/app-debug.apk"

  local got

  # 1. An APK wins over an already-set variable: the installed binary is the
  #    only ground truth, and the release chain sets the variable from a
  #    DIFFERENT checkout than the one that built the APK (#2064).
  got="$(
    PATH="$sandbox/bin:$PATH" \
    POCKETSHELL_AGENT_FIXTURE_VERSION="0.4.45-4-gDEADBEEF" \
    bash -c "source '$lib'; export_agents_fixture_version '$sandbox/app-debug.apk'; printf '%s' \"\$POCKETSHELL_AGENT_FIXTURE_VERSION\""
  )"
  if [[ "$got" != "9.9.9-apk" ]]; then
    printf 'SELF-TEST FAILED: an APK argument did not override a preset stamp (got %q, want 9.9.9-apk).\n' "$got" >&2
    return 1
  fi
  printf '  ok: self-test — the installed APK'"'"'s versionName wins over a preset stamp\n'

  # 2. No APK: an explicit caller pin survives (the pre-release gate pins the
  #    exact string it then asserts).
  got="$(
    PATH="$sandbox/bin:$PATH" \
    POCKETSHELL_AGENT_FIXTURE_VERSION="0.4.45-4-gDEADBEEF" \
    bash -c "source '$lib'; export_agents_fixture_version; printf '%s' \"\$POCKETSHELL_AGENT_FIXTURE_VERSION\""
  )"
  if [[ "$got" != "0.4.45-4-gDEADBEEF" ]]; then
    printf 'SELF-TEST FAILED: an explicit caller pin was not honoured (got %q).\n' "$got" >&2
    return 1
  fi
  printf '  ok: self-test — an explicit caller pin survives when no APK is given\n'

  # 3. Nothing set, no APK: fall back to THIS checkout's derivation, and it must
  #    equal scripts/derive-version.sh — not a second, drifting implementation.
  local derived
  derived="$(bash "$ROOT_DIR/scripts/derive-version.sh" version-name 2>/dev/null || true)"
  got="$(
    env -u POCKETSHELL_AGENT_FIXTURE_VERSION PATH="$sandbox/bin:$PATH" \
    bash -c "source '$lib'; export_agents_fixture_version; printf '%s' \"\$POCKETSHELL_AGENT_FIXTURE_VERSION\""
  )"
  if [[ -z "$derived" || "$got" != "$derived" ]]; then
    printf 'SELF-TEST FAILED: the no-APK fallback (%q) is not scripts/derive-version.sh'"'"'s answer (%q).\n' \
      "$got" "$derived" >&2
    return 1
  fi
  printf '  ok: self-test — the fallback is scripts/derive-version.sh, not a second derivation\n'

  # 4. A named-but-absent APK (a standalone run stamps before it builds) must
  #    fall back silently rather than fail or emit an empty stamp.
  got="$(
    env -u POCKETSHELL_AGENT_FIXTURE_VERSION PATH="$sandbox/bin:$PATH" \
    bash -c "source '$lib'; export_agents_fixture_version '$sandbox/not-built-yet.apk'; printf '%s' \"\$POCKETSHELL_AGENT_FIXTURE_VERSION\"" 2>/dev/null
  )"
  if [[ "$got" != "$derived" ]]; then
    printf 'SELF-TEST FAILED: a not-yet-built APK path did not fall back to the derivation (got %q).\n' "$got" >&2
    return 1
  fi
  printf '  ok: self-test — a not-yet-built APK path falls back to the derivation\n'

  # 5. The BUILD_APKS-shaped wrapper routes both ways: "1" (rebuild from this
  #    checkout) must NOT read the stale APK, anything else must read it.
  got="$(
    env -u POCKETSHELL_AGENT_FIXTURE_VERSION PATH="$sandbox/bin:$PATH" \
    bash -c "source '$lib'; export_agents_fixture_version_for_run 1 '$sandbox/app-debug.apk' 2>/dev/null; printf '%s' \"\$POCKETSHELL_AGENT_FIXTURE_VERSION\""
  )"
  if [[ "$got" != "$derived" ]]; then
    printf 'SELF-TEST FAILED: BUILD_APKS=1 read the about-to-be-replaced APK (got %q, want %q).\n' \
      "$got" "$derived" >&2
    return 1
  fi
  got="$(
    env -u POCKETSHELL_AGENT_FIXTURE_VERSION PATH="$sandbox/bin:$PATH" \
    bash -c "source '$lib'; export_agents_fixture_version_for_run 0 '$sandbox/app-debug.apk' 2>/dev/null; printf '%s' \"\$POCKETSHELL_AGENT_FIXTURE_VERSION\""
  )"
  if [[ "$got" != "9.9.9-apk" ]]; then
    printf 'SELF-TEST FAILED: BUILD_APKS=0 did not read the installed APK (got %q, want 9.9.9-apk).\n' "$got" >&2
    return 1
  fi
  printf '  ok: self-test — the BUILD_APKS wrapper derives when rebuilding and reads the APK otherwise\n'

  return 0
}

main() {
  command -v ruby >/dev/null 2>&1 || {
    printf 'FAIL: ruby is required to parse GitHub Actions YAML\n' >&2
    exit 1
  }

  if [[ "${1:-}" == "--self-test" ]]; then
    run_self_test
    exit $?
  fi

  check_emulator_jobs "$ROOT_DIR/.github/workflows"
  check_fixture_version_wiring "$ROOT_DIR"
  check_agents_up_call_sites "$ROOT_DIR/scripts"
  check_workflow_agents_up_call_sites "$ROOT_DIR/.github/workflows"
  check_named_release_call_sites "$ROOT_DIR/scripts"

  if [[ "$failures" -ne 0 ]]; then
    printf 'check-emulator-apk-version-wiring: FAIL — %d problem(s).\n' "$failures" >&2
    exit 1
  fi
  printf 'check-emulator-apk-version-wiring: OK\n'
}

main "$@"
