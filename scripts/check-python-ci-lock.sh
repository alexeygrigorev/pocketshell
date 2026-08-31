#!/usr/bin/env bash
# Structural frozen-lock guard for issue #2430 (audit F21).
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() { printf 'FAIL: %s\n' "$*" >&2; return 1; }

check_files() {
  local workflow="$1" pyproject="$2" lockfile="$3"
  local required pinned
  [[ -f "$workflow" && -f "$pyproject" && -f "$lockfile" ]] || {
    fail "workflow, pyproject, or uv.lock is missing"
    return 1
  }

  required="$(sed -n 's/^required-version = "==\([^"]*\)"$/\1/p' "$pyproject")"
  pinned="$(sed -n '/uses: astral-sh\/setup-uv@v7/{n;n;s/^[[:space:]]*version: "\([^"]*\)"/\1/p;}' "$workflow")"
  [[ -n "$required" ]] || { fail "pyproject does not pin [tool.uv] required-version"; return 1; }
  [[ "$pinned" == "$required" ]] || {
    fail "workflow uv version '$pinned' does not match pyproject required-version '$required'"
    return 1
  }
  grep -Fq 'uv sync --frozen --group dev' "$workflow" || {
    fail "Python CI does not install the dev environment with frozen lock resolution"
    return 1
  }
  grep -Fq 'uv run --frozen pytest -v' "$workflow" || {
    fail "Python CI pytest is not bound to the frozen environment"
    return 1
  }
  grep -Fq 'revision = ' "$lockfile" || { fail "uv.lock has no revision header"; return 1; }
  printf 'PASS: Python CI pins uv %s and executes the committed frozen lock\n' "$required"
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  printf '%s\n' '[tool.uv]' 'required-version = "==0.10.11"' > "$tmp/pyproject.toml"
  printf '%s\n' 'version = 1' 'revision = 3' > "$tmp/uv.lock"
  cat > "$tmp/bad.yml" <<'EOF'
- uses: astral-sh/setup-uv@v7
  with:
    version: "latest"
- run: uv pip install -e ".[dev]"
- run: uv run pytest -v
EOF
  if check_files "$tmp/bad.yml" "$tmp/pyproject.toml" "$tmp/uv.lock" >/dev/null 2>&1; then
    fail "floating-installer/unlocked fixture passed"
    return 1
  fi
  cat > "$tmp/good.yml" <<'EOF'
- uses: astral-sh/setup-uv@v7
  with:
    version: "0.10.11"
- run: uv sync --frozen --group dev
- run: uv run --frozen pytest -v
EOF
  check_files "$tmp/good.yml" "$tmp/pyproject.toml" "$tmp/uv.lock" >/dev/null || {
    fail "pinned/frozen fixture failed"
    return 1
  }
  printf 'PASS: Python lock guard rejects floating/unlocked CI and accepts pinned/frozen CI\n'
}

case "${1:-}" in
  --self-test) run_self_test ;;
  "") check_files "$ROOT_DIR/.github/workflows/tests.yml" \
      "$ROOT_DIR/tools/pocketshell/pyproject.toml" \
      "$ROOT_DIR/tools/pocketshell/uv.lock" ;;
  *) printf 'usage: %s [--self-test]\n' "$0" >&2; exit 2 ;;
esac
