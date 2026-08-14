#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

CASES=0
pass_case() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

fake_adb="$tmpdir/adb"
cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" > "${FAKE_ADB_ARGS:?}"
EOF
chmod +x "$fake_adb"

apk="$tmpdir/app-debug.apk"
touch "$apk"

FAKE_ADB_ARGS="$tmpdir/adb-args"
export FAKE_ADB_ARGS
ADB="$fake_adb" "$ROOT_DIR/scripts/install-update-apk.sh" "$apk" > "$tmpdir/stdout.log"

args="$(cat "$FAKE_ADB_ARGS")"
[[ "$args" == "install -r $apk" ]] ||
  fail "expected update helper to run exactly 'adb install -r <apk>', got: $args"
pass_case "runs exactly 'adb install -r <apk>'"

if grep -Eq 'pm clear|INSTALL_FAILED_UPDATE_INCOMPATIBLE|adb[[:space:]].*uninstall|"\$ADB"[[:space:]]+uninstall|install[[:space:]].*(-d|-t)' "$ROOT_DIR/scripts/install-update-apk.sh"; then
  fail "update helper contains destructive cleanup, uninstall fallback, or cold-install flags"
fi
pass_case "carries no destructive cleanup / uninstall fallback / cold-install flags"

grep -q 'Data-preserving update install' "$tmpdir/stdout.log" ||
  fail "update helper did not label the path as data-preserving"
pass_case "labels the path as data-preserving"

# Issue #2113: the count line is what makes the JVM assertion about behaviour
# rather than about bash's exit status.
(( CASES == 3 )) || fail "expected 3 cases to run, saw $CASES"
printf 'PASS: update install helper (%s cases)\n' "$CASES"
