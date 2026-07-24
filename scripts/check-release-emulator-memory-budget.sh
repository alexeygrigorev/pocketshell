#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="${1:-$ROOT_DIR/.github/workflows/release-emulator-validation.yml}"
GATE="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"
WRAPPER="$ROOT_DIR/scripts/release-emulator-validation.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  return 1
}

extract_gradle_flags() {
  awk '
    /^[[:space:]]*GRADLE_FLAGS:[[:space:]]*>-[[:space:]]*$/ {
      found = 1
      next
    }
    found && /^[[:space:]]+-/ {
      sub(/^[[:space:]]+/, "")
      printf "%s ", $0
      next
    }
    found {
      exit
    }
  ' "$1"
}

extract_env_scalar() {
  local key="$1"
  local file="$2"
  awk -v key="$key" '
    $1 == key ":" {
      value = $2
      gsub(/^"|"$/, "", value)
      print value
      exit
    }
  ' "$file"
}

validate_flags() {
  local flags="$1"
  local required
  for required in \
    "--no-parallel" \
    "--max-workers=1" \
    "-Dorg.gradle.jvmargs=-Xmx1536m" \
    "-Pkotlin.daemon.jvmargs=-Xmx3072m"; do
    if [[ " $flags " != *" $required "* ]]; then
      fail "hosted GRADLE_FLAGS is missing '$required'"
      return 1
    fi
  done

  local token
  local -a tokens=()
  read -r -a tokens <<< "$flags"
  for token in "${tokens[@]}"; do
    case "$token" in
      --parallel)
        fail "hosted confidence-gate compilation must not enable parallel project execution"
        return 1
        ;;
      --max-workers=*)
        if [[ "$token" != "--max-workers=1" ]]; then
          fail "hosted confidence-gate compilation must remain single-worker"
          return 1
        fi
        ;;
      -Dorg.gradle.jvmargs=*)
        if [[ "$token" != "-Dorg.gradle.jvmargs=-Xmx1536m" ]]; then
          fail "hosted Gradle heap must remain capped at 1536 MiB"
          return 1
        fi
        ;;
      -Pkotlin.daemon.jvmargs=*)
        if [[ "$token" != "-Pkotlin.daemon.jvmargs=-Xmx3072m" ]]; then
          fail "hosted Kotlin daemon heap must remain capped at 3072 MiB"
          return 1
        fi
        ;;
    esac
  done
}

validate_memory_limit() {
  local memory_limit="$1"
  if [[ "$memory_limit" != "8G" ]]; then
    fail "hosted confidence-gate scope must pin POCKETSHELL_TEST_MEM to 8G"
    return 1
  fi
}

self_test() {
  local good
  good="--no-daemon --no-build-cache --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.daemon.jvmargs=-Xmx3072m"
  validate_flags "$good" >/dev/null
  validate_memory_limit "8G" >/dev/null

  local bad
  for bad in \
    "${good/--no-parallel/}" \
    "${good/--max-workers=1/--max-workers=2}" \
    "${good/-Dorg.gradle.jvmargs=-Xmx1536m/}" \
    "${good/-Pkotlin.daemon.jvmargs=-Xmx3072m/}" \
    "$good --parallel" \
    "$good -Dorg.gradle.jvmargs=-Xmx4g" \
    "$good -Pkotlin.daemon.jvmargs=-Xmx4g"; do
    if validate_flags "$bad" >/dev/null 2>&1; then
      fail "self-test accepted an unsafe hosted memory configuration"
      exit 1
    fi
  done

  if validate_memory_limit "12G" >/dev/null 2>&1; then
    fail "self-test accepted an unsafe hosted cgroup limit"
    exit 1
  fi

  printf 'PASS: release-emulator memory-budget guard self-test (10 checks)\n'
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
  exit 0
fi

[[ -f "$WORKFLOW" ]] || { fail "workflow not found: $WORKFLOW"; exit 1; }

flags="$(extract_gradle_flags "$WORKFLOW")"
[[ -n "$flags" ]] || { fail "hosted workflow has no folded GRADLE_FLAGS block"; exit 1; }
validate_flags "$flags"
memory_limit="$(extract_env_scalar "POCKETSHELL_TEST_MEM" "$WORKFLOW")"
validate_memory_limit "$memory_limit"

# The workflow-level override only works if the release wrapper preserves the
# environment and the confidence gate carries it into its isolated worktree.
# shellcheck disable=SC2016 # Match the wrapper's literal variable references.
grep -Fq 'env LOG_ROOT="$PRE_RELEASE_GATE_LOG_ROOT" RUN_ID="$PRE_RELEASE_RUN_ID" PRE_RELEASE_MANAGE_EMULATOR=1 scripts/pre-release-confidence-gate.sh' "$WRAPPER" ||
  { fail "release wrapper no longer inherits GRADLE_FLAGS into the confidence gate"; exit 1; }
grep -Eq '^  export .*GRADLE_FLAGS' "$GATE" ||
  { fail "confidence gate no longer exports GRADLE_FLAGS into its isolated worktree"; exit 1; }

printf 'PASS: hosted release-emulator compile budget is serialized and heap-bounded\n'
