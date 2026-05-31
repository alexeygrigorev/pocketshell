#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

helpers="$tmpdir/pre-release-version-helper.sh"
{
  printf 'ROOT_DIR=%q\n' "$ROOT_DIR"
  printf 'source "$ROOT_DIR/scripts/lib/app-version.sh"\n'
  awk '
    /^docker_agents_pocketshell_version_script\(\)/ { copy = 1 }
    copy {
      print
      if ($0 == "}") exit
    }
  ' "$ROOT_DIR/scripts/pre-release-confidence-gate.sh"
} > "$helpers"

# shellcheck source=/dev/null
source "$helpers"

fakebin="$tmpdir/bin"
mkdir -p "$fakebin"
cat > "$fakebin/ssh" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$FAKE_SSH_OUTPUT"
SH
chmod +x "$fakebin/ssh"

SSH_KEY="$tmpdir/test_key"
touch "$SSH_KEY"

check_script="$(docker_agents_pocketshell_version_script 0.3.10)"

FAKE_SSH_OUTPUT="pocketshell fixture 0.3.10" PATH="$fakebin:$PATH" bash -lc "$check_script"

if FAKE_SSH_OUTPUT="pocketshell fixture 0.3.100" PATH="$fakebin:$PATH" bash -lc "$check_script"; then
  printf 'expected exact pre-release fixture version check to reject 0.3.100 for 0.3.10\n' >&2
  exit 1
fi

printf 'PASS: pre-release Docker fixture version exact check\n'
