#!/usr/bin/env bash
# Issue #2310: keep every hosted emulator-runner invocation reproducible.
#
# The release-validation and app2 journey lanes all
# depend on reactivecircus/android-emulator-runner. A floating major tag lets
# the action implementation change underneath an otherwise identical commit,
# so every active workflow reference must use the one audited v2.37.0 commit.
# The version comment is checked on the same line so a reader can identify the
# pinned action without resolving the SHA manually.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
WORKFLOW_DIR="${POCKETSHELL_EMULATOR_ACTION_WORKFLOW_DIR:-$ROOT_DIR/.github/workflows}"

readonly EXPECTED_SHA="e89f39f1abbbd05b1113a29cf4db69e7540cae5a"
readonly EXPECTED_VERSION="v2.37.0"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  return 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/check-emulator-action-pin.sh [--self-test | WORKFLOW_DIR]

Checks every reactivecircus/android-emulator-runner reference under the GitHub
Actions workflow directory. Each reference must use the audited v2.37.0 commit
SHA and retain a same-line '# v2.37.0' comment.

--self-test runs a synthetic red->green proof for a valid pin, a floating tag,
a different commit SHA, and a missing human-readable version comment.
USAGE
}

check_workflows() {
  local workflow_dir="$1"
  [[ -d "$workflow_dir" ]] || {
    fail "workflow directory not found: $workflow_dir"
    return 1
  }

  local -a matches=()
  mapfile -t matches < <(
    grep -RIn \
      --include='*.yml' \
      --include='*.yaml' \
      -E '^[[:space:]]*uses:[[:space:]]*reactivecircus/android-emulator-runner@' \
      "$workflow_dir" || true
  )

  (( ${#matches[@]} > 0 )) || {
    fail "no reactivecircus/android-emulator-runner workflow references found under $workflow_dir"
    return 1
  }

  local match file remainder line_number source ref
  local reference_count=0
  for match in "${matches[@]}"; do
    file="${match%%:*}"
    remainder="${match#*:}"
    line_number="${remainder%%:*}"
    source="${remainder#*:}"
    ref="$(sed -E 's/.*reactivecircus\/android-emulator-runner@([^[:space:]#]+).*/\1/' <<<"$source")"

    if [[ "$ref" != "$EXPECTED_SHA" ]]; then
      if [[ "$ref" == v* || "$ref" =~ ^[0-9]+(\.[0-9]+)+$ ]]; then
        fail "$file:$line_number uses floating emulator-runner tag @$ref; pin @$EXPECTED_SHA"
      else
        fail "$file:$line_number uses emulator-runner @$ref; expected audited @$EXPECTED_SHA"
      fi
      return 1
    fi

    if ! grep -Eq "#[[:space:]]*${EXPECTED_VERSION//./\\.}([[:space:]]|$)" <<<"$source"; then
      fail "$file:$line_number pins @$EXPECTED_SHA but is missing the '# $EXPECTED_VERSION' comment"
      return 1
    fi

    reference_count=$((reference_count + 1))
  done

  printf 'PASS: %d emulator-runner reference(s) use @%s # %s\n' \
    "$reference_count" "$EXPECTED_SHA" "$EXPECTED_VERSION"
}

self_test() {
  local temp_dir valid_root floating_root wrong_sha_root missing_comment_root
  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/emulator-action-pin-check.XXXXXX")"
  trap 'rm -rf "$temp_dir"' RETURN

  valid_root="$temp_dir/valid/.github/workflows"
  mkdir -p "$valid_root"

  write_fixture() {
    local workflow="$1"
    local sha="$2"
    local comment="$3"
    {
      printf 'name: fixture\n'
      printf 'jobs:\n'
      printf '  emulator:\n'
      printf '    runs-on: ubuntu-latest\n'
      printf '    steps:\n'
      printf '      - name: Run emulator\n'
      printf '        uses: reactivecircus/android-emulator-runner@%s%s\n' "$sha" "$comment"
    } > "$workflow"
  }

  write_fixture "$valid_root/release-emulator-validation.yml" "$EXPECTED_SHA" " # $EXPECTED_VERSION"
  write_fixture "$valid_root/app2.yml" "$EXPECTED_SHA" " # $EXPECTED_VERSION"

  local failures=0
  printf '== self-test: valid pinned workflows (expect PASS) ==\n'
  if check_workflows "$valid_root"; then
    printf '   -> PASS as expected\n\n'
  else
    printf '   -> UNEXPECTED FAIL on valid pinned workflows\n\n' >&2
    failures=$((failures + 1))
  fi

  floating_root="$temp_dir/floating/.github/workflows"
  mkdir -p "$floating_root"
  cp "$valid_root"/*.yml "$floating_root/"
  sed -i "s/@$EXPECTED_SHA/@v2/" "$floating_root/release-emulator-validation.yml"
  printf '== self-test: floating major tag (expect FAIL) ==\n'
  if check_workflows "$floating_root" >"$temp_dir/floating.out" 2>&1; then
    printf '   -> UNEXPECTED PASS on floating tag\n\n' >&2
    failures=$((failures + 1))
  elif grep -Fq 'floating emulator-runner tag' "$temp_dir/floating.out"; then
    printf '   -> FAIL as expected\n\n'
  else
    cat "$temp_dir/floating.out" >&2
    printf '   -> FAIL used the wrong diagnostic\n\n' >&2
    failures=$((failures + 1))
  fi

  wrong_sha_root="$temp_dir/wrong-sha/.github/workflows"
  mkdir -p "$wrong_sha_root"
  cp "$valid_root"/*.yml "$wrong_sha_root/"
  sed -i "s/@$EXPECTED_SHA/@0000000000000000000000000000000000000000/" \
    "$wrong_sha_root/app2.yml"
  printf '== self-test: different commit SHA (expect FAIL) ==\n'
  if check_workflows "$wrong_sha_root" >"$temp_dir/wrong-sha.out" 2>&1; then
    printf '   -> UNEXPECTED PASS on different SHA\n\n' >&2
    failures=$((failures + 1))
  elif grep -Fq 'expected audited' "$temp_dir/wrong-sha.out"; then
    printf '   -> FAIL as expected\n\n'
  else
    cat "$temp_dir/wrong-sha.out" >&2
    printf '   -> FAIL used the wrong diagnostic\n\n' >&2
    failures=$((failures + 1))
  fi

  missing_comment_root="$temp_dir/missing-comment/.github/workflows"
  mkdir -p "$missing_comment_root"
  cp "$valid_root"/*.yml "$missing_comment_root/"
  sed -i "s/ # $EXPECTED_VERSION$//" "$missing_comment_root/release-emulator-validation.yml"
  printf '== self-test: missing human-readable version comment (expect FAIL) ==\n'
  if check_workflows "$missing_comment_root" >"$temp_dir/missing-comment.out" 2>&1; then
    printf '   -> UNEXPECTED PASS without version comment\n\n' >&2
    failures=$((failures + 1))
  elif grep -Fq 'missing the' "$temp_dir/missing-comment.out"; then
    printf '   -> FAIL as expected\n\n'
  else
    cat "$temp_dir/missing-comment.out" >&2
    printf '   -> FAIL used the wrong diagnostic\n\n' >&2
    failures=$((failures + 1))
  fi

  if (( failures != 0 )); then
    fail "emulator action pin self-test: $failures case(s) behaved incorrectly"
    return 1
  fi
  printf 'PASS: emulator action pin self-test (4 cases)\n'
}

case "${1:-}" in
  --help|-h)
    usage
    ;;
  --self-test)
    self_test
    ;;
  "")
    check_workflows "$WORKFLOW_DIR"
    ;;
  *)
    check_workflows "$1"
    ;;
esac
