#!/usr/bin/env bash
# Aplexer-only product guard (issue #2561).
#
# The product session runtime must not grow a tmux fallback or discriminator.
# Operational tmux used by the agent runner is outside this scan and remains
# covered by the runner-specific scripts and docs. Historical Room migration
# code is also excluded because it must retain old column names to upgrade an
# existing database.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

scan_roots() {
  local -a roots=("$@")
  local root
  for root in "${roots[@]}"; do
    if [[ ! -d "$root" ]]; then
      echo "check-product-tmux-absent: FAIL — missing scan root: $root" >&2
      return 1
    fi
  done

  local matches
  if command -v rg >/dev/null 2>&1; then
    matches="$(rg -n -i 'tmux' "${roots[@]}" \
      --glob '!**/build/**' \
      --glob '!shared/core-terminal/src/main/java/com/termux/**' \
      --glob '!shared/core-storage/src/main/java/com/pocketshell/core/storage/AppDatabase.kt' \
      --glob '!shared/core-storage/src/main/java/com/pocketshell/core/storage/LegacyVersionOneMigration.kt' \
      || true)"
  else
    # Ubuntu runners have grep but may not have ripgrep. Keep this fallback's
    # exclusions aligned with the product surface above so the guard remains
    # blocking instead of silently skipping its scan.
    matches="$(grep -RniE 'tmux' "${roots[@]}" \
      --exclude-dir=build \
      --exclude-dir=termux \
      --exclude=AppDatabase.kt \
      --exclude=LegacyVersionOneMigration.kt \
      || true)"
  fi
  if [[ -n "$matches" ]]; then
    echo "check-product-tmux-absent: FAIL — tmux reference found in product code:" >&2
    printf '%s\n' "$matches" >&2
    return 1
  fi
  return 0
}

cleanup_tmp() {
  python3 - "$1" <<'PY'
import shutil
import sys
shutil.rmtree(sys.argv[1], ignore_errors=True)
PY
}

self_test() {
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-product-tmux.XXXXXX")"
  trap 'cleanup_tmp "$tmp"' EXIT

  mkdir -p "$tmp/product"
  printf '%s\n' 'val backend = "tmux"' > "$tmp/product/Runtime.kt"
  if scan_roots "$tmp/product"; then
    echo "check-product-tmux-absent: self-test FAIL — planted product hit was missed" >&2
    return 1
  fi

  printf '%s\n' 'val backend = "aplexer"' > "$tmp/product/Runtime.kt"
  if ! scan_roots "$tmp/product"; then
    echo "check-product-tmux-absent: self-test FAIL — clean product tree was rejected" >&2
    return 1
  fi
  echo "check-product-tmux-absent: self-test OK"
}

cd "$REPO_ROOT"
if [[ "${1:-}" == "--self-test" ]]; then
  self_test
  exit 0
fi
if [[ "${1:-}" != "" ]]; then
  echo "Usage: scripts/check-product-tmux-absent.sh [--self-test]" >&2
  exit 2
fi

scan_roots \
  tools/pocketshell/src \
  app2/src/main \
  shared/*/src/main
echo "check-product-tmux-absent: OK — product session paths are aplexer-only"
