#!/usr/bin/env bash
# Issue #2570: no Gradle module may declare a native build.
#
# HISTORY, IN ONE LINE
#
# `shared/core-terminal` was the only module with an `externalNativeBuild` (the
# vendored Termux local-pty JNI). Issue #2566 replaced the vendored
# `TerminalSession` with PocketShell's own remote-only class and deleted that
# machinery, so nothing in the build asks for an NDK or CMake any more. Issue
# #2570 then deleted the CI NDK-retry machinery (`ci-assemble-with-ndk-retry.sh`
# and its shell test) that existed solely to self-heal a corrupted NDK download
# on the release-critical Build workflow (#1581).
#
# WHY A GUARD AND NOT A COMMENT
#
# The retry machinery is gone (D22: hard cut, no dormant fallback). If a module
# ever grows an `externalNativeBuild` / `ndkVersion` / `abiFilters` / `jniLibs`
# block again, the Build workflow silently regains an on-demand NDK download
# with NO retry protection — the exact release-path flake #1581 was filed for,
# reintroduced by a change that looks unrelated to CI. This guard makes that a
# loud, cheap, per-push failure instead, and points the next person at the
# decision they have to make consciously.
#
# WHAT IT CHECKS
#
# No git-tracked `*.gradle.kts` / `*.gradle` file contains a native-build
# declaration. Matching is on the DECLARATION, not the word: a comment
# explaining that there is no `externalNativeBuild` here (core-terminal's
# build.gradle.kts carries exactly such a comment) must stay green, or the guard
# would be undone by the first person who documents its own invariant.
#
#   scripts/check-no-native-build.sh              # check the real tree
#   scripts/check-no-native-build.sh --self-test  # prove the check can go red
#
# Cheap (< 1 s), JVM-free, no Gradle. Wired through
# scripts/ci-build-profile-guards.sh (the tests.yml `guards-ci-harness` job).

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

# A native-build declaration: the keyword at the start of a statement (start of
# line or after `{`/`;`), NOT inside a `//` or `#` comment and not in a string.
# `[[:space:]]*(\{|=|\()` is what separates `externalNativeBuild {` (a real
# block) from the word `externalNativeBuild` in prose.
NATIVE_DECL_RE='^[[:space:]]*(externalNativeBuild[[:space:]]*\{|ndkVersion[[:space:]]*=|ndkPath[[:space:]]*=|abiFilters[[:space:]]*(\+?=|\()|jniLibs[[:space:]]*\{)'

fail() {
  printf 'FAIL: %s\n' "$1" >&2
}

# Print `path:line:text` for every native-build declaration under $1.
scan_tree() {
  local root="$1"
  local -a files=()
  local f

  if [[ -d "$root/.git" ]] && git -C "$root" rev-parse >/dev/null 2>&1; then
    mapfile -t files < <(git -C "$root" ls-files '*.gradle.kts' '*.gradle')
  else
    mapfile -t files < <(cd "$root" && find . -name '*.gradle.kts' -o -name '*.gradle' | sed 's|^\./||')
  fi

  for f in "${files[@]}"; do
    [[ -n "$f" ]] || continue
    # Strip full-line comments before matching so prose about the invariant
    # cannot trip it (and cannot be used to satisfy it either — a commented-out
    # `externalNativeBuild {` is not a native build).
    grep -nE "$NATIVE_DECL_RE" "$root/$f" 2>/dev/null \
      | grep -vE '^[0-9]+:[[:space:]]*(//|#|\*)' \
      | sed "s|^|$f:|" || true
  done
}

check_tree() {
  local root="${1:-$ROOT_DIR}"
  local hits
  hits="$(scan_tree "$root")"

  if [[ -n "$hits" ]]; then
    fail "a Gradle module declares a native build again (issue #2570):"
    printf '%s\n' "$hits" >&2
    cat >&2 <<'REMEDY'

  Nothing in PocketShell's build has needed an NDK or CMake since issue #2566.
  The CI NDK-retry machinery that used to self-heal the corrupted-NDK-download
  flake on the release-critical Build workflow (issue #1581) was deleted in
  #2570, so a native build reintroduced here ships with NO retry protection on
  the release path.

  If a native build is genuinely wanted again, that is a deliberate decision:
  say so on an issue, restore the download-retry protection the Build workflow
  needs, and update this guard in the same PR.
REMEDY
    return 1
  fi

  printf 'PASS: no Gradle module declares a native build (no NDK/CMake needed)\n'
  return 0
}

self_test() {
  local tmp failures=0 out
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/check-no-native-build.XXXXXX")"
  trap 'rm -rf "$tmp"' RETURN

  mkdir -p "$tmp/green" "$tmp/red-enb" "$tmp/red-ndkver" "$tmp/red-jni"

  # GREEN: a module that only TALKS about the invariant must not trip it.
  cat > "$tmp/green/build.gradle.kts" <<'KTS'
android {
    // Issue #2566 removed the local-pty machinery, so there is no
    // externalNativeBuild here any more and the module needs no NDK, no
    // ndkVersion, no abiFilters and no jniLibs block.
    namespace = "com.termux.view"
    defaultConfig {
        minSdk = 26
    }
}
KTS

  cat > "$tmp/red-enb/build.gradle.kts" <<'KTS'
android {
    defaultConfig {
        externalNativeBuild {
            cmake { cppFlags += "" }
        }
    }
}
KTS

  cat > "$tmp/red-ndkver/build.gradle.kts" <<'KTS'
android {
    ndkVersion = "27.0.12077973"
}
KTS

  cat > "$tmp/red-jni/build.gradle.kts" <<'KTS'
android {
    sourceSets["main"].jniLibs {
        srcDirs("src/main/jniLibs")
    }
    defaultConfig {
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }
}
KTS

  printf '== self-test: prose about the invariant (expect PASS) ==\n'
  out="$tmp/green.out"
  if check_tree "$tmp/green" >"$out" 2>&1; then
    printf '   -> PASS as expected\n'
  else
    printf '   -> UNEXPECTED FAIL on a comment-only mention\n' >&2
    cat "$out" >&2
    failures=$((failures + 1))
  fi

  local case_dir
  for case_dir in red-enb red-ndkver red-jni; do
    printf '== self-test: %s declares a native build (expect FAIL) ==\n' "$case_dir"
    out="$tmp/$case_dir.out"
    if check_tree "$tmp/$case_dir" >"$out" 2>&1; then
      printf '   -> UNEXPECTED PASS on a real native-build declaration\n' >&2
      cat "$out" >&2
      failures=$((failures + 1))
    elif grep -Fq 'declares a native build again' "$out"; then
      printf '   -> FAIL as expected\n'
    else
      printf '   -> FAIL used the wrong diagnostic\n' >&2
      cat "$out" >&2
      failures=$((failures + 1))
    fi
  done

  if (( failures > 0 )); then
    fail "scripts/check-no-native-build.sh --self-test ($failures case(s))"
    return 1
  fi
  printf 'PASS: scripts/check-no-native-build.sh --self-test\n'
}

case "${1:-}" in
  --self-test)
    self_test
    ;;
  -h|--help)
    cat <<'USAGE'
Usage: scripts/check-no-native-build.sh [ROOT]
       scripts/check-no-native-build.sh --self-test

Fails if any Gradle build file declares externalNativeBuild / ndkVersion /
ndkPath / abiFilters / jniLibs. Nothing in the build has needed an NDK since
issue #2566, and the CI NDK-retry machinery was deleted in #2570.
USAGE
    ;;
  *)
    check_tree "${1:-$ROOT_DIR}"
    ;;
esac
