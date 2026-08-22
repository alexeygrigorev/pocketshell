#!/usr/bin/env bash
# Deterministic mutation/selectivity proof for issue #2274.
#
# This intentionally works on a temporary copy. The worktree source is never
# edited; the temporary source is restored before the script exits, including
# on an unexpected failure.

set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
PROJECT_REL="tools/pocketshell"
SOURCE_REL="$PROJECT_REL/src/pocketshell/usage.py"
SOURCE="$ROOT_DIR/$SOURCE_REL"

SANDBOX="$(mktemp -d "/tmp/pocketshell-issue-2274-mutation.XXXXXX")"
EVIDENCE="$SANDBOX/evidence"
CANDIDATE="$SANDBOX/pocketshell"
CANDIDATE_SOURCE="$CANDIDATE/src/pocketshell/usage.py"
PRISTINE_SOURCE="$SANDBOX/usage.py.pristine"
mkdir -p "$EVIDENCE" "$CANDIDATE"

PYTHON="${POCKETSHELL_PYTHON:-$ROOT_DIR/$PROJECT_REL/.venv/bin/python}"
if [[ ! -x "$PYTHON" ]]; then
  PYTHON="$(command -v python3 || true)"
fi

HEAD_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
WORKTREE_SOURCE_SHA_BEFORE="$(sha256sum "$SOURCE" | awk '{print $1}')"
WORKTREE_SOURCE_DIFF_SHA="$(git -C "$ROOT_DIR" diff --no-ext-diff -- "$SOURCE_REL" | sha256sum | awk '{print $1}')"

copy_candidate() {
  cp -a "$ROOT_DIR/$PROJECT_REL/pyproject.toml" "$CANDIDATE/"
  cp -a "$ROOT_DIR/$PROJECT_REL/uv.lock" "$CANDIDATE/"
  cp -a "$ROOT_DIR/$PROJECT_REL/src" "$CANDIDATE/"
  cp -a "$ROOT_DIR/$PROJECT_REL/tests" "$CANDIDATE/"
  cp "$SOURCE" "$PRISTINE_SOURCE"
  cp "$PRISTINE_SOURCE" "$CANDIDATE_SOURCE"
}

RUN_NAME=""
RUN_RC="not-run"
run_test() {
  RUN_NAME="$1"
  shift
  local log="$EVIDENCE/$RUN_NAME.log"
  printf '%s\n' "$PYTHON -m pytest -q $*" > "$EVIDENCE/$RUN_NAME.command"
  set +e
  (
    cd "$CANDIDATE"
    PYTHONPATH="$CANDIDATE/src${PYTHONPATH:+:$PYTHONPATH}" \
      "$PYTHON" -m pytest -q "$@"
  ) >"$log" 2>&1
  RUN_RC=$?
  set -e
  printf '%s\t%s\n' "$RUN_NAME" "$RUN_RC" >> "$EVIDENCE/rcs.tsv"
  printf '%-32s rc=%s\n' "$RUN_NAME" "$RUN_RC"
}

require_rc() {
  local expected="$1"
  local label="$2"
  if [[ "$RUN_RC" != "$expected" ]]; then
    printf 'FAIL: %s expected rc=%s, got rc=%s\n' "$label" "$expected" "$RUN_RC" >&2
    return 1
  fi
}

apply_missing_both_fields_mutant() {
  "$PYTHON" - "$CANDIDATE_SOURCE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
anchor = '''    if "short_term" not in record and "long_term" not in record:
        raise ValueError(
            f"quse provider '{provider}' is missing canonical 'windows' or both "
            "legacy window fields 'short_term' and 'long_term'"
        )
'''
if text.count(anchor) != 1:
    raise SystemExit(
        f"expected exactly one #2274 missing-both guard in {path}, found {text.count(anchor)}"
    )

# Exact regression mutant: remove the missing-both guard. The old loop then
# silently emits an empty canonical map, while both valid producer controls
# remain unaffected.
mutated = text.replace(anchor, "", 1)
if mutated == text or anchor in mutated:
    raise SystemExit("missing-both guard mutant was not applied exactly once")
path.write_text(mutated)
PY
}

write_manifest() {
  local final_rc="$1"
  local candidate_after_restore="missing"
  local worktree_after="missing"
  [[ -f "$CANDIDATE_SOURCE" ]] && candidate_after_restore="$(sha256sum "$CANDIDATE_SOURCE" | awk '{print $1}')"
  [[ -f "$SOURCE" ]] && worktree_after="$(sha256sum "$SOURCE" | awk '{print $1}')"
  {
    printf 'issue=2274\n'
    printf 'proof_script=%s\n' "$ROOT_DIR/scripts/test-usage-missing-window-mutation.sh"
    printf 'worktree=%s\n' "$ROOT_DIR"
    printf 'head_sha=%s\n' "$HEAD_SHA"
    printf 'source_rel=%s\n' "$SOURCE_REL"
    printf 'worktree_source_sha_before=%s\n' "$WORKTREE_SOURCE_SHA_BEFORE"
    printf 'worktree_source_diff_sha=%s\n' "$WORKTREE_SOURCE_DIFF_SHA"
    printf 'candidate=%s\n' "$CANDIDATE"
    printf 'candidate_source_sha_before=%s\n' "$(sha256sum "$PRISTINE_SOURCE" | awk '{print $1}')"
    printf 'candidate_source_sha_mutant=%s\n' "${MUTANT_SOURCE_SHA:-not-recorded}"
    printf 'candidate_source_sha_after_restore=%s\n' "$candidate_after_restore"
    printf 'worktree_source_sha_after=%s\n' "$worktree_after"
    printf 'baseline_affected_rc=%s\n' "${BASELINE_AFFECTED_RC:-not-run}"
    printf 'baseline_control_translation_rc=%s\n' "${BASELINE_CONTROL_TRANSLATION_RC:-not-run}"
    printf 'baseline_control_canonical_rc=%s\n' "${BASELINE_CONTROL_CANONICAL_RC:-not-run}"
    printf 'mutant_affected_rc=%s\n' "${MUTANT_AFFECTED_RC:-not-run}"
    printf 'mutant_control_translation_rc=%s\n' "${MUTANT_CONTROL_TRANSLATION_RC:-not-run}"
    printf 'mutant_control_canonical_rc=%s\n' "${MUTANT_CONTROL_CANONICAL_RC:-not-run}"
    printf 'restored_affected_rc=%s\n' "${RESTORED_AFFECTED_RC:-not-run}"
    printf 'final_rc=%s\n' "$final_rc"
    printf 'source_restored=%s\n' "$([[ "$candidate_after_restore" == "$WORKTREE_SOURCE_SHA_BEFORE" ]] && echo yes || echo no)"
    printf 'worktree_unchanged=%s\n' "$([[ "$worktree_after" == "$WORKTREE_SOURCE_SHA_BEFORE" ]] && echo yes || echo no)"
  } > "$EVIDENCE/manifest.txt"
}

BASELINE_AFFECTED_RC="not-run"
BASELINE_CONTROL_TRANSLATION_RC="not-run"
BASELINE_CONTROL_CANONICAL_RC="not-run"
MUTANT_SOURCE_SHA="not-recorded"
MUTANT_AFFECTED_RC="not-run"
MUTANT_CONTROL_TRANSLATION_RC="not-run"
MUTANT_CONTROL_CANONICAL_RC="not-run"
RESTORED_AFFECTED_RC="not-run"

on_exit() {
  local rc=$?
  set +e
  if [[ -f "$PRISTINE_SOURCE" && -f "$CANDIDATE_SOURCE" ]]; then
    cp "$PRISTINE_SOURCE" "$CANDIDATE_SOURCE"
  fi
  write_manifest "$rc"
  printf 'evidence=%s\n' "$EVIDENCE"
  exit "$rc"
}
trap on_exit EXIT

if [[ -z "$PYTHON" || ! -x "$PYTHON" ]]; then
  printf 'FAIL: no executable Python selected (set POCKETSHELL_PYTHON if needed)\n' >&2
  exit 1
fi
"$PYTHON" -m pytest --version > "$EVIDENCE/pytest-version.txt" 2>&1

copy_candidate
candidate_clean_sha="$(sha256sum "$CANDIDATE_SOURCE" | awk '{print $1}')"
if [[ "$candidate_clean_sha" != "$WORKTREE_SOURCE_SHA_BEFORE" ]]; then
  printf 'FAIL: candidate source does not match the worktree source before mutation\n' >&2
  exit 1
fi

run_test baseline_affected \
  tests/test_usage.py::test_flatten_rejects_record_without_any_window_fields
BASELINE_AFFECTED_RC="$RUN_RC"
require_rc 0 "fixed affected test"

run_test baseline_control_translation \
  tests/test_usage.py::test_flatten_translates_published_window_labels_without_rederiving_values
BASELINE_CONTROL_TRANSLATION_RC="$RUN_RC"
require_rc 0 "fixed translation control"

run_test baseline_control_canonical \
  tests/test_usage.py::test_flatten_accepts_separate_canonical_go_contract
BASELINE_CONTROL_CANONICAL_RC="$RUN_RC"
require_rc 0 "fixed canonical control"

apply_missing_both_fields_mutant
MUTANT_SOURCE_SHA="$(sha256sum "$CANDIDATE_SOURCE" | awk '{print $1}')"
if [[ "$MUTANT_SOURCE_SHA" == "$candidate_clean_sha" ]]; then
  printf 'FAIL: missing-both guard mutant did not change candidate source\n' >&2
  exit 1
fi

run_test mutant_affected \
  tests/test_usage.py::test_flatten_rejects_record_without_any_window_fields
MUTANT_AFFECTED_RC="$RUN_RC"
require_rc 1 "missing-both guard mutant affected test"

run_test mutant_control_translation \
  tests/test_usage.py::test_flatten_translates_published_window_labels_without_rederiving_values
MUTANT_CONTROL_TRANSLATION_RC="$RUN_RC"
require_rc 0 "missing-both guard mutant translation control"

run_test mutant_control_canonical \
  tests/test_usage.py::test_flatten_accepts_separate_canonical_go_contract
MUTANT_CONTROL_CANONICAL_RC="$RUN_RC"
require_rc 0 "missing-both guard mutant canonical control"

cp "$PRISTINE_SOURCE" "$CANDIDATE_SOURCE"
restored_sha="$(sha256sum "$CANDIDATE_SOURCE" | awk '{print $1}')"
if [[ "$restored_sha" != "$candidate_clean_sha" ]]; then
  printf 'FAIL: candidate source did not restore to its clean hash\n' >&2
  exit 1
fi

run_test restored_affected \
  tests/test_usage.py::test_flatten_rejects_record_without_any_window_fields
RESTORED_AFFECTED_RC="$RUN_RC"
require_rc 0 "restored affected test"

worktree_after_run="$(sha256sum "$SOURCE" | awk '{print $1}')"
if [[ "$worktree_after_run" != "$WORKTREE_SOURCE_SHA_BEFORE" ]]; then
  printf 'FAIL: worktree source changed during proof\n' >&2
  exit 1
fi

printf 'PASS: missing-both mutant red (affected rc=1), legacy/canonical controls green, source restored\n'
