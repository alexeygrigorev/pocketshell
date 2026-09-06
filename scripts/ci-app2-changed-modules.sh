#!/usr/bin/env bash
# scripts/ci-app2-changed-modules.sh — rewrite task M-2.
#
# Per-JOB path filtering for .github/workflows/app2.yml.
#
# GitHub's `on.<event>.paths:` filter is WORKFLOW-level: it decides whether the
# whole run happens, not which jobs inside it do. app2.yml used to carry that
# filter; issue #2509 removed it because it is the #2354 required-check footgun
# and empirically suppressed the D37 `schedule:` cadence. Per-job selection
# stays here: this script computes one boolean per new module from the push/PR
# diff and writes them to $GITHUB_OUTPUT for the downstream jobs' `if:` guards.
# A schedule/dispatch run passes an empty --base and fail-opens every lane.
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
# itself, tests/docker/** (the Testcontainers sshd image the transport
# integration lane builds) and tools/pocketshell/** (the fixture images COPY it
# — see below). A catalog bump touches no module directory but can break every
# lane.
#
# A FIXTURE INPUT DOES NOT HAVE TO LIVE UNDER tests/docker/ (issue #2592).
# tests/docker/Dockerfile.agents — the image the app2-journey lane runs its
# WHOLE instrumented suite against — builds from repo paths outside its own
# directory: `COPY tools/pocketshell/pyproject.toml` (the file the build then
# derives the pinned aplexer release from, and curls that release's binaries)
# and `COPY tools/pocketshell/src/` (the fixture runs the REAL CLI);
# Dockerfile.agents-daemon COPYs the whole `tools/pocketshell/` tree. So a
# CLI-side change rebuilds the fixture while touching nothing under
# tests/docker/. Before #2592 that selected NO lane: the aplexer pin bump
# (#2588, d09471e2b) produced a green app2 run in which every app2 job skipped.
# The prefix is the directory, not the two files, because the whole-tree COPY
# makes the whole tree an image input — measured cost of the wider prefix over
# 200 `main` commits: 3 extra fan-outs (19 commits touch tools/pocketshell/, 16
# of them already in src/ or pyproject.toml).
#
# This is not a list anyone has to remember to update: --self-test parses every
# COPY/ADD in tests/docker/Dockerfile.*, and any source resolving outside
# tests/docker/ that SHARED_PREFIXES does not cover fails the selector's own
# gate. The next fixture input added from elsewhere in the repo reddens here
# instead of silently under-selecting.
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
  # Issue #2592: COPY source of tests/docker/Dockerfile.agents (pyproject.toml
  # -> the derived aplexer download; src/ -> the real CLI the fixture runs) and,
  # whole-tree, of Dockerfile.agents-daemon. Changing it rebuilds the image the
  # journey lane runs against without touching tests/docker/ at all. The
  # --self-test COPY-source guard keeps this entry honest.
  "tools/pocketshell/"
  ".github/workflows/app2.yml"
  "scripts/ci-app2-changed-modules.sh"
  "scripts/check-app2-lane-execution.py"
  # Issue #2474: the app2-journey lane's runner. It is app2-only in effect, but
  # listing it here rather than under the app2 module means a runner change
  # fail-opens every lane instead of starting a run whose every job deselects
  # itself (the runner path is not under app2/).
  "scripts/ci-app2-journey-suite.sh"
)

# True when <path> is at or under any of the remaining arguments (prefix list).
# A prefix ending in "/" is a directory; a bare filename matches exactly (and,
# harmlessly, anything that extends it — no two entries here are prefixes of a
# different real path).
matches_any_prefix() {
  local path="$1"
  shift
  local prefix
  for prefix in "$@"; do
    [[ -z "$prefix" ]] && continue
    if [[ "$path" == "$prefix" || "$path" == "$prefix"* ]]; then
      return 0
    fi
  done
  return 1
}

# Issue #2592 — the anti-drift half of the COPY-source rule.
#
# Prints, one per line, every repo path that a tests/docker/Dockerfile.* COPYs
# (or ADDs) into a fixture image, normalised to repo-root-relative form. The
# fixture Dockerfiles use two different build contexts: the compose services
# built from the repo root spell their sources "tests/docker/..." / "tools/...",
# while the ones built with tests/docker as context spell them bare
# ("sshd_config"). Both are resolved here, and anything that resolves to
# NEITHER is a hard failure rather than a silent skip — an unparsed COPY is
# exactly the invisible input this guard exists to stop.
fixture_copy_sources() {
  local root="$1"
  local -a files=()
  local df
  while IFS= read -r df; do
    [[ -n "$df" ]] && files+=("$df")
  done < <(find "$root/tests/docker" -maxdepth 1 -name 'Dockerfile.*' -type f 2>/dev/null | sort)

  if [[ ${#files[@]} -eq 0 ]]; then
    echo "fixture_copy_sources: no tests/docker/Dockerfile.* under '${root}'" >&2
    return 2
  fi

  local line n src i first last
  local -a tok=()
  for df in "${files[@]}"; do
    n=0
    while IFS= read -r line || [[ -n "$line" ]]; do
      n=$((n + 1))
      line="${line%$'\r'}"
      [[ "$line" =~ ^[[:space:]]*(COPY|ADD)[[:space:]] ]] || continue
      if [[ "$line" == *'<<'* || "$line" == *'['* || "$line" =~ \\[[:space:]]*$ ]]; then
        echo "fixture_copy_sources: unsupported COPY form at ${df}:${n}: ${line}" >&2
        return 2
      fi
      tok=()
      read -r -a tok <<<"$line"
      first=1
      local from_stage=0
      while [[ $first -lt ${#tok[@]} && "${tok[first]}" == --* ]]; do
        # `COPY --from=<stage|image> ...` copies out of another build stage, not
        # out of the repo: its sources are container paths and must not be
        # resolved against the checkout.
        [[ "${tok[first]}" == --from=* ]] && from_stage=1
        first=$((first + 1))
      done
      if [[ $from_stage -eq 1 ]]; then
        continue
      fi
      last=$((${#tok[@]} - 2)) # the final token is the destination
      if [[ $last -lt $first ]]; then
        echo "fixture_copy_sources: cannot parse sources at ${df}:${n}: ${line}" >&2
        return 2
      fi
      for ((i = first; i <= last; i++)); do
        src="${tok[i]}"
        src="${src#./}"
        # A glob widens to its directory: coverage of the directory covers
        # every file the glob could pick up.
        if [[ "$src" == *[*?]* ]]; then
          src="$(dirname "$src")/"
        fi
        if [[ -e "$root/$src" ]]; then
          printf '%s\n' "$src"
        elif [[ -e "$root/tests/docker/$src" ]]; then
          printf '%s\n' "tests/docker/$src"
        else
          echo "fixture_copy_sources: COPY source '${src}' at ${df}:${n} resolves to no repo path" >&2
          return 2
        fi
      done
    done <"$df"
  done
}

# Fails (rc 1) when a fixture-image COPY source is NOT covered by the prefix
# list passed after <root>. rc 2 means the Dockerfiles could not be read/parsed.
check_fixture_copy_sources_covered() {
  local root="$1"
  shift
  local -a prefixes=("$@")
  local srcs src rc=0
  srcs="$(fixture_copy_sources "$root")" || return 2
  while IFS= read -r src; do
    [[ -z "$src" ]] && continue
    if ! matches_any_prefix "$src" "${prefixes[@]}"; then
      echo "fixture COPY source '${src}' is not covered by SHARED_PREFIXES -> a change to it would rebuild a fixture image while selecting NO lane (issue #2592)" >&2
      rc=1
    fi
  done <<<"$srcs"
  return $rc
}

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
  local path
  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    if matches_any_prefix "$path" "${SHARED_PREFIXES[@]}"; then
      echo "app2 lane selection: shared path '${path}' changed -> selecting ALL lanes" >&2
      emit true true true true
      return 0
    fi
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

  # Issue #2474: the journey lane's runner. A change to it always starts a run
  # (no trigger-level `paths:` — issue #2509); this list must still select lanes
  # so the run is not an empty skip-fest.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "scripts/ci-app2-journey-suite.sh"
  check "the journey runner (shared)" true true true true

  # Issue #2592: tools/pocketshell/ is a COPY source of the fixture images the
  # app2 lanes run against, so a CLI-side change rebuilds them while touching
  # nothing under tests/docker/. The aplexer pin bump (#2588, d09471e2b) is the
  # instance: it selected NOTHING and produced a green app2 run in which no app2
  # job executed. Both halves of Dockerfile.agents' COPY set get a case.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "tools/pocketshell/pyproject.toml"
  check "CLI pyproject (Dockerfile.agents COPY source, shared)" true true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "tools/pocketshell/src/pocketshell/cli.py"
  check "CLI source (Dockerfile.agents COPY source, shared)" true true true true

  # Discriminator: the prefix is tools/pocketshell/, not tools/. A sibling tool
  # is no fixture input and must not fan the lanes out.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "tools/some-other-tool/x.py"
  check "an unrelated tools/ path is not shared" false false false false

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

  # Issue #2592 — the anti-drift property, asserted against the REAL tree: every
  # path a fixture Dockerfile COPYs from outside tests/docker/ must be covered by
  # SHARED_PREFIXES. This is what stops the list going stale the next time a
  # fixture grows an input from elsewhere in the repo.
  local -a pruned=()
  local prefix grc
  checks=$((checks + 2))
  if check_fixture_copy_sources_covered "$REPO_ROOT" "${SHARED_PREFIXES[@]}"; then
    echo "ok   [every fixture COPY source is covered by SHARED_PREFIXES]"
  else
    echo "FAIL [fixture COPY source not covered by SHARED_PREFIXES] (see above)" >&2
    status=1
  fi

  # ...and the guard is LIVE: strip the tools/ prefixes and it must go red.
  # A guard that cannot fail is decoration (G6).
  for prefix in "${SHARED_PREFIXES[@]}"; do
    [[ "$prefix" == tools/* ]] && continue
    pruned+=("$prefix")
  done
  check_fixture_copy_sources_covered "$REPO_ROOT" "${pruned[@]}" 2>/dev/null
  grc=$?
  if [[ $grc -eq 1 ]]; then
    echo "ok   [COPY-source guard reddens when the tools/ prefix is removed]"
  else
    echo "FAIL [COPY-source guard did not redden without the tools/ prefix: rc=$grc]" >&2
    status=1
  fi

  # ...and a fixture input added from ELSEWHERE IN THE REPO reddens too — the
  # literal drift scenario #2592 is about, on a synthetic tree so it stays true
  # after tools/pocketshell/ is covered.
  mkdir -p "$tmp/fixture-drift/tests/docker" "$tmp/fixture-drift/tools/newthing"
  echo x >"$tmp/fixture-drift/tools/newthing/x.txt"
  printf 'FROM scratch\nCOPY tools/newthing/x.txt /x\n' \
    >"$tmp/fixture-drift/tests/docker/Dockerfile.fake"
  checks=$((checks + 1))
  check_fixture_copy_sources_covered "$tmp/fixture-drift" "${SHARED_PREFIXES[@]}" 2>/dev/null
  grc=$?
  if [[ $grc -eq 1 ]]; then
    echo "ok   [a NEW fixture COPY source outside the prefix list reddens the guard]"
  else
    echo "FAIL [drift of a new fixture COPY source went undetected: rc=$grc]" >&2
    status=1
  fi

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

  # Bumped 13 -> 14 by issue #2474's journey-runner case, 14 -> 20 by issue
  # #2592's three tools/pocketshell diff cases plus the three COPY-source
  # drift-guard checks.
  if [[ $checks -ne 20 ]]; then
    echo "FAIL: expected 20 checks, ran $checks" >&2
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
# The real checkout, for the --self-test COPY-source drift guard only. `plan`
# never reads it: selection must stay pure git so it works on any checkout.
REPO_ROOT="$(cd "$(dirname "$SELF")/.." && pwd)"

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
      # The whole leading comment block, however long it grows — a fixed line
      # range silently truncated --help the moment the header did (issue #2592).
      awk 'NR == 1 { next } !/^#/ { exit } { sub(/^# ?/, ""); print }' "$SELF"
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
