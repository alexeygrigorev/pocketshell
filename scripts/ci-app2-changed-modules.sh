#!/usr/bin/env bash
# scripts/ci-app2-changed-modules.sh — rewrite task M-2.
#
# Per-JOB path filtering for .github/workflows/app2.yml.
#
# GitHub's `on.<event>.paths:` filter is WORKFLOW-level: it decides whether the
# whole run happens, not which jobs inside it do. app2.yml wants the finer
# grain ("only the touched module's lane runs"), so this script computes one
# boolean per new module from the push/PR diff and writes them to
# $GITHUB_OUTPUT for the downstream jobs' `if:` guards.
#
# Deliberately a script, not an inline `run:` block or a third-party
# paths-filter action:
#   * the repo pins zero third-party filtering actions today (only
#     actions/*, astral-sh/setup-uv, and the SHA-pinned emulator runner), and
#     adding one would be a new supply-chain surface for ~20 lines of logic;
#   * the same reasoning scripts/ci-plan.sh records — the "never
#     under-select" safety property deserves a testable home rather than a
#     YAML expression nothing exercises. Self-test: --self-test.
#
# FAIL-OPEN, LIKE ci-plan.sh / select-test-areas.sh
# An unknown/unreadable diff base (first push to a new ref, force-push,
# shallow checkout without the base commit, workflow_dispatch) selects
# EVERYTHING. Under-selection is the only failure mode that hides a break, so
# every uncertain case latches "run it all".
#
# SHARED-INFRASTRUCTURE PATHS also select everything: the version catalog,
# settings/root build script, the Gradle wrapper, this script, the workflow
# itself, and tests/docker/** (the Testcontainers sshd image the transport
# integration lane builds). A catalog bump touches no module directory but can
# break every lane.
#
# USAGE
#   ci-app2-changed-modules.sh --base <sha|ref>      # push: github.event.before
#   ci-app2-changed-modules.sh --base ""             # unknown -> everything
#   ci-app2-changed-modules.sh --self-test
#
# OUTPUT (stdout, and $GITHUB_OUTPUT when set)
#   hostapi=true|false
#   transport=true|false
#   portfwd=true|false
#   app2=true|false
# plus a human-readable plan on stderr.

set -uo pipefail

ZERO="0000000000000000000000000000000000000000"

# Module directory, in the emit() order: hostapi, transport, portfwd, app2.
declare -a MODULE_DIRS=(
  "shared/core-hostapi"
  "shared/core-transport"
  "shared/core-portfwd"
  "app2"
)

# A change to any of these selects every lane.
declare -a SHARED_PREFIXES=(
  "gradle/"
  "settings.gradle.kts"
  "build.gradle.kts"
  "gradle.properties"
  "gradlew"
  "gradlew.bat"
  "tests/docker/"
  ".github/workflows/app2.yml"
  "scripts/ci-app2-changed-modules.sh"
  "scripts/check-app2-lane-execution.py"
  # Issue #2474: the app2-journey lane's runner. It is app2-only in effect, but
  # listing it here rather than under the app2 module keeps this list identical
  # to app2.yml's trigger-level `paths:` union — the two must not drift, or a
  # change to the runner triggers the workflow while every lane inside it
  # deselects itself.
  "scripts/ci-app2-journey-suite.sh"
)

emit() {
  # emit <hostapi> <transport> <portfwd> <app2>
  local out
  out="$(printf 'hostapi=%s\ntransport=%s\nportfwd=%s\napp2=%s\n' "$1" "$2" "$3" "$4")"
  printf '%s\n' "$out"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s\n' "$out" >>"$GITHUB_OUTPUT"
  fi
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf 'app2 lane selection: hostapi=%s transport=%s portfwd=%s app2=%s\n' \
      "$1" "$2" "$3" "$4" >>"$GITHUB_STEP_SUMMARY"
  fi
}

plan() {
  local base="$1"
  local changed=""

  if [[ -z "$base" || "$base" == "$ZERO" ]] || ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
    echo "app2 lane selection: base '${base}' is unusable -> selecting ALL lanes (fail-open)" >&2
    emit true true true true
    return 0
  fi

  if ! changed="$(git diff --name-only "$base" HEAD 2>/dev/null)"; then
    echo "app2 lane selection: git diff against '${base}' failed -> selecting ALL lanes (fail-open)" >&2
    emit true true true true
    return 0
  fi

  if [[ -z "$changed" ]]; then
    echo "app2 lane selection: empty diff against '${base}' -> selecting ALL lanes (fail-open)" >&2
    emit true true true true
    return 0
  fi

  # NUL-safe-ish: iterate line by line (git diff --name-only is one path per
  # line) rather than word-splitting, so a path containing a space is one path.
  local path prefix
  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    for prefix in "${SHARED_PREFIXES[@]}"; do
      if [[ "$path" == "$prefix" || "$path" == "$prefix"* ]]; then
        echo "app2 lane selection: shared path '${path}' changed -> selecting ALL lanes" >&2
        emit true true true true
        return 0
      fi
    done
  done <<<"$changed"

  local -a hit=(false false false false)
  local i
  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    for i in 0 1 2 3; do
      if [[ "$path" == "${MODULE_DIRS[i]}/"* ]]; then
        hit[i]=true
      fi
    done
  done <<<"$changed"

  # DEPENDENCY EDGES. A change to a dependency can break a dependant while
  # touching none of its paths, so each edge is walked here to keep the "never
  # under-select" property true:
  #   core-transport -> core-portfwd (task P-4: the tunnel engine runs over
  #     HostConnection and re-exports its types as `api`)
  #   core-transport -> app2         (task M-3: the connect package implements
  #     its TrustStore / AuthSecretResolver seams)
  #   core-portfwd   -> app2         (task P-4: the ports package drives the
  #     forwarder and supervisor directly)
  # app2 does NOT depend on core-hostapi yet; add the same edge when it does.
  if [[ "${hit[1]}" == "true" ]]; then
    hit[2]=true
    hit[3]=true
  fi
  if [[ "${hit[2]}" == "true" ]]; then
    hit[3]=true
  fi

  echo "app2 lane selection: base=${base} hostapi=${hit[0]} transport=${hit[1]} portfwd=${hit[2]} app2=${hit[3]}" >&2
  emit "${hit[0]}" "${hit[1]}" "${hit[2]}" "${hit[3]}"
}

self_test() {
  local tmp status=0 out checks=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  git -C "$tmp" init -q
  git -C "$tmp" config user.email t@example.com
  git -C "$tmp" config user.name t
  mkdir -p "$tmp/shared/core-hostapi" "$tmp/shared/core-transport" \
    "$tmp/shared/core-portfwd" "$tmp/app2" "$tmp/gradle" "$tmp/shared/ui-kit"
  echo seed >"$tmp/seed.txt"
  git -C "$tmp" add -A
  git -C "$tmp" commit -qm seed
  local base
  base="$(git -C "$tmp" rev-parse HEAD)"

  check() {
    # check <label> <hostapi> <transport> <portfwd> <app2>
    local label="$1" eh="$2" et="$3" ep="$4" ea="$5"
    checks=$((checks + 1))
    out="$(cd "$tmp" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$SELF" --base "$base" 2>/dev/null)"
    local want
    want="$(printf 'hostapi=%s\ntransport=%s\nportfwd=%s\napp2=%s' "$eh" "$et" "$ep" "$ea")"
    if [[ "$out" != "$want" ]]; then
      echo "FAIL [$label]: expected '$want', got '$out'" >&2
      status=1
    else
      echo "ok   [$label] -> hostapi=$eh transport=$et portfwd=$ep app2=$ea"
    fi
  }

  commit_file() {
    local rel="$1"
    mkdir -p "$tmp/$(dirname "$rel")"
    date +%s%N >"$tmp/$rel"
    git -C "$tmp" add -A
    git -C "$tmp" commit -qm "touch $rel"
  }

  commit_file "shared/core-hostapi/src/main/A.kt"
  check "hostapi only" true false false false

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-transport/src/main/B.kt"
  # portfwd and app2 ride along: both depend on core-transport (M-3 / P-4).
  check "transport pulls portfwd and app2 in" false true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-portfwd/src/main/P.kt"
  # app2 rides along: the ports package drives the forwarder directly (P-4).
  check "portfwd pulls app2 in" false false true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "app2/src/main/C.kt"
  check "app2 only" false false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/ui-kit/src/main/D.kt"
  check "unrelated module" false false false false

  git -C "$tmp" reset -q --hard "$base"
  commit_file "gradle/libs.versions.toml"
  check "version catalog (shared)" true true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "tests/docker/Dockerfile.ssh"
  check "docker fixture (shared)" true true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file ".github/workflows/app2.yml"
  check "the workflow itself (shared)" true true true true

  # Issue #2474: the journey lane's runner. app2.yml's trigger-level `paths:`
  # starts a run when it changes, so this list must select a lane for it — an
  # entry present in one place and missing in the other yields a run whose every
  # job deselects itself.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "scripts/ci-app2-journey-suite.sh"
  check "the journey runner (shared)" true true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-hostapi/x.kt"
  commit_file "app2/y.kt"
  check "two modules" true false false true

  # A path containing a space is ONE path, not several words — the reason the
  # matcher reads the diff line by line instead of word-splitting it. This case
  # discriminates: word-splitting yields the token "app2/summary.md", which
  # matches the app2 prefix and would wrongly select the app2 lane for a docs
  # file that merely has "app2" in its directory name.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "docs/notes on app2/summary.md"
  check "space in path is not two paths" false false false false

  # Fail-open: an unusable base selects everything.
  checks=$((checks + 3))
  out="$(cd "$tmp" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$SELF" --base "$ZERO" 2>/dev/null)"
  if [[ "$out" != "$(printf 'hostapi=true\ntransport=true\nportfwd=true\napp2=true')" ]]; then
    echo "FAIL [zero base fails open]: got '$out'" >&2
    status=1
  else
    echo "ok   [zero base fails open]"
  fi

  out="$(cd "$tmp" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$SELF" --base "" 2>/dev/null)"
  if [[ "$out" != "$(printf 'hostapi=true\ntransport=true\nportfwd=true\napp2=true')" ]]; then
    echo "FAIL [empty base fails open]: got '$out'" >&2
    status=1
  else
    echo "ok   [empty base fails open]"
  fi

  out="$(cd "$tmp" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$SELF" --base deadbeefdeadbeefdeadbeefdeadbeefdeadbeef 2>/dev/null)"
  if [[ "$out" != "$(printf 'hostapi=true\ntransport=true\nportfwd=true\napp2=true')" ]]; then
    echo "FAIL [unknown base fails open]: got '$out'" >&2
    status=1
  else
    echo "ok   [unknown base fails open]"
  fi

  # Bumped 13 -> 14 by issue #2474's journey-runner case.
  if [[ $checks -ne 14 ]]; then
    echo "FAIL: expected 14 checks, ran $checks" >&2
    status=1
  fi

  if [[ $status -eq 0 ]]; then
    echo "ci-app2-changed-modules self-test: $checks checks PASSED"
  else
    echo "ci-app2-changed-modules self-test: FAILED" >&2
  fi
  return $status
}

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"

BASE=""
MODE="plan"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      BASE="${2:-}"
      shift 2
      ;;
    --self-test)
      MODE="self-test"
      shift
      ;;
    -h | --help)
      sed -n '2,45p' "$SELF" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "$MODE" == "self-test" ]]; then
  self_test
  exit $?
fi

plan "$BASE"
