#!/usr/bin/env bash
# Guard: the Docker `agents` fixture must run a REAL, pinned aplexer — and must
# FAIL LOUDLY if it ever stops (issue #2563, slice 1 of #2561).
#
# The failure mode this exists for is not "aplexer is broken". It is "aplexer
# is absent and nothing says so". `pocketshell.aplexer` resolves `a` next to
# `sys.executable` and nowhere else (PATH is a D22 hard cut, #2543), so a
# fixture whose `a` sits only on PATH makes every aplexer probe return None:
# `sessions list --json` reports `managers: ["tmux"]`, zero aplexer rows and
# exit 0. Every downstream journey stays green while proving nothing — the
# vacuous-pass shape docs/ci-pitfalls.md catalogues.
#
#   scripts/test-agents-fixture-aplexer.sh            # static, no Docker
#   scripts/test-agents-fixture-aplexer.sh --docker   # + real container
#
# The static half runs per-push in `guards-ci-harness`; `--docker` runs in
# `Integration tests (Docker)`.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKERFILE="$ROOT_DIR/tests/docker/Dockerfile.agents"
SELFCHECK="$ROOT_DIR/tests/docker/agents-aplexer-selfcheck.py"
PYPROJECT="$ROOT_DIR/tools/pocketshell/pyproject.toml"

pass_count=0

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

ok() {
  pass_count=$((pass_count + 1))
  printf '  ok  %s\n' "$*"
}

# ---------------------------------------------------------------------------
# Static invariants (no Docker)
# ---------------------------------------------------------------------------
printf 'Static agents-fixture aplexer invariants...\n'

[[ -f "$DOCKERFILE" ]] || fail "missing $DOCKERFILE"
[[ -f "$SELFCHECK" ]] || fail "missing $SELFCHECK"

grep -Eq '^FROM[[:space:]]+debian:trixie-slim([[:space:]]|$)' "$DOCKERFILE" \
  || fail "Dockerfile.agents must be FROM debian:trixie-slim — aplexer ships
  glibc-only manylinux binaries (musl cannot run them), and bookworm's OpenSSH
  9.2 REFUSES TO START on the shared sshd_config's PerSourcePenalties keyword
  (#2150), killing the whole fixture at container start"
ok "base is debian:trixie-slim"

# The binaries must land next to the interpreter, not merely on PATH.
grep -Fq '/usr/bin/a' "$DOCKERFILE" && grep -Fq '/usr/bin/aplexer' "$DOCKERFILE" \
  || fail "Dockerfile.agents must install BOTH aplexer binaries into /usr/bin
  (next to /usr/bin/python3). \`a\` alone dies with 'spawn worker: No such file
  or directory'; \`a\` anywhere else is invisible to the real CLI."
ok "a + sibling worker install into /usr/bin, next to /usr/bin/python3"

grep -Eq 'ln -s[^\n]*/usr/local/bin/a([[:space:]]|$)' "$DOCKERFILE" \
  && fail "Dockerfile.agents symlinks \`a\` onto /usr/local/bin. A PATH-facing
  copy is exactly the shape that makes the fixture enumerate nothing while
  exiting 0; /usr/bin is already on PATH, so the symlink buys nothing and
  invites the bug back."
ok "no PATH-only \`a\` symlink"

[[ -e "$ROOT_DIR/tests/docker/agent-bin/a" ]] \
  && fail "tests/docker/agent-bin/a is back. That 61-line shell stub answered
  only the dead \`a --json sessions\` verb and hosted no attachable process; it
  would also shadow the real binary on PATH. D22: no legacy fallback."
ok "the fake shell \`a\` stub stays deleted"

# The pin is DERIVED, never typed. A literal version in the Dockerfile is the
# drift this guard exists to prevent.
grep -Fq 'sed -n' "$DOCKERFILE" && grep -Fq 'pyproject.toml' "$DOCKERFILE" \
  || fail "Dockerfile.agents must derive the aplexer version from
  tools/pocketshell/pyproject.toml at build time"
ok "aplexer version is derived from pyproject.toml"

if grep -Eq 'aplexer/releases/download/v[0-9]' "$DOCKERFILE"; then
  fail "Dockerfile.agents hardcodes an aplexer release version in the download
  URL; it must interpolate the pin derived from pyproject.toml"
fi
if grep -Eq 'APLEXER_VERSION[[:space:]]*=[[:space:]]*[0-9]' "$DOCKERFILE"; then
  fail "Dockerfile.agents carries a literal APLEXER_VERSION default; the pin
  must come from pyproject.toml, not a --build-arg literal"
fi
ok "no hardcoded aplexer version literal"

# Run the Dockerfile's OWN derivation expression against the real pyproject and
# check it yields the real pin. A sed that silently stops matching would
# otherwise leave `ver` empty (the build's `test -n` catches that) or, worse,
# match the wrong line.
derivation="$(grep -F 'sed -n' "$DOCKERFILE" | head -1 | sed -e 's/^[^"]*"\$(//' -e 's/)".*$//')"
[[ -n "$derivation" ]] || fail "could not extract the pin-derivation command from $DOCKERFILE"
derived="$(eval "${derivation//\/opt\/pocketshell-real\/pyproject.toml/$PYPROJECT}")"
expected="$(sed -n 's/^ *"aplexer==\([0-9][^"; ]*\).*/\1/p' "$PYPROJECT" | head -1)"
[[ -n "$expected" ]] || fail "no aplexer==X.Y.Z pin found in $PYPROJECT"
[[ "$derived" == "$expected" ]] \
  || fail "the Dockerfile's derivation yields '$derived' but pyproject.toml pins '$expected'"
ok "derivation reproduces the pyproject pin ($expected)"

grep -Fq 'pocketshell-fixture-aplexer-selfcheck' "$DOCKERFILE" \
  || fail "Dockerfile.agents must install the self-check"
grep -Eq '^RUN su testuser .*pocketshell-fixture-aplexer-selfcheck' "$DOCKERFILE" \
  || fail "Dockerfile.agents must RUN the self-check at BUILD time. Without it
  the image can be built hollow and every downstream journey stays green while
  aplexer enumerates nothing."
ok "the self-check runs at image-build time"

# Prose may DISCUSS `a --version`; code must never invoke it. Look for the
# string literal an invocation would need.
if grep -Eq "[\"']--version[\"']" "$SELFCHECK"; then
  fail "the self-check invokes \`--version\`. AGENTS.md: a local build and a
  published wheel both self-reported the same version while differing by 143
  commits. Assert BEHAVIOUR."
fi
if grep -Eq "[\"']--version[\"']" "$DOCKERFILE"; then
  fail "Dockerfile.agents asserts the pin with a \`--version\` call; the pin
  must be asserted by behaviour (the self-check), not a version string."
fi
ok "the pin is asserted by behaviour, never \`a --version\`"

python3 -c "import ast,sys; ast.parse(open(sys.argv[1]).read())" "$SELFCHECK" \
  || fail "$SELFCHECK does not parse"
ok "self-check parses"

printf 'Static invariants passed (%d checks).\n' "$pass_count"

case "$#:${1:-}" in
  0:) exit 0 ;;
  1:--docker) ;;
  *) fail "usage: $0 [--docker]" ;;
esac

# ---------------------------------------------------------------------------
# Real-container half
# ---------------------------------------------------------------------------
#
# EVERY assertion below that exercises the product's own path goes over SSH on
# the container's published port, NOT `docker exec`. That distinction is not
# cosmetic: `docker exec … su testuser` and sshd's non-interactive `exec`
# channel differ in environment in exactly the way this image's own #2276
# `login-only-agent` rung exists to demonstrate, and the app only ever uses the
# second one. `docker exec` is reserved for the root-level surgery the negative
# cases need (relocating a binary), which is not something the app does.
command -v docker >/dev/null || fail "docker is required for --docker"
command -v ssh >/dev/null || fail "ssh is required for --docker"

SUFFIX="${AGENTS_APLEXER_GUARD_SUFFIX:-$$}"
TAG="pocketshell-test:agents-aplexer-guard-${SUFFIX}"
NEG_TAG="pocketshell-test:agents-aplexer-guard-neg-${SUFFIX}"
CONTAINER="pocketshell-agents-aplexer-guard-${SUFFIX}"
tmp_dir="$(mktemp -d)"

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker image rm -f "$NEG_TAG" "$TAG" >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

printf '\nBuilding %s...\n' "$TAG"
if ! docker build --file "$DOCKERFILE" --tag "$TAG" "$ROOT_DIR" >"$tmp_dir/build.log" 2>&1; then
  tail -n 40 "$tmp_dir/build.log" >&2
  fail "docker build failed for Dockerfile.agents"
fi
grep -Eq 'pinned aplexer=[0-9]+\.[0-9]+\.[0-9]+ linux-(amd64|arm64) \(derived from pyproject.toml\)' \
  "$tmp_dir/build.log" \
  || printf 'note: pin line not in build log (cached layer); derivation is covered statically above\n'

# An EPHEMERAL published port, so parallel agents and CI lanes never collide on
# a literal. Bound to loopback: this fixture is for tests, not the network.
docker run -d --name "$CONTAINER" -p 127.0.0.1::22 "$TAG" >/dev/null
SSH_PORT="$(docker port "$CONTAINER" 22/tcp | head -1 | sed 's/.*://')"
[[ -n "$SSH_PORT" ]] || fail "could not read the container's published SSH port"

# The committed key is 0644 in the tree and OpenSSH refuses it (#1876); copy it
# to a private file rather than chmod-ing a tracked file.
KEY="$tmp_dir/test_key"
cp "$ROOT_DIR/tests/docker/test_key" "$KEY"
chmod 600 "$KEY"

SSH_OPTS=(
  -i "$KEY" -p "$SSH_PORT"
  -o BatchMode=yes -o ConnectTimeout=5
  -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
  -o LogLevel=ERROR
)

# The app's channel: a NON-INTERACTIVE `exec`, one command, no TTY.
ssh_exec() { timeout 90 ssh "${SSH_OPTS[@]}" testuser@127.0.0.1 "$@"; }
# The attach channel: a real PTY, which is what `a attach` / `sessions attach`
# need and what `docker exec` in this script cannot give them.
ssh_pty() { timeout 90 ssh -tt "${SSH_OPTS[@]}" testuser@127.0.0.1 "$@"; }

printf 'Waiting for sshd on 127.0.0.1:%s...\n' "$SSH_PORT"
for _ in $(seq 1 60); do
  ssh_exec true >/dev/null 2>&1 && break
  sleep 1
done
ssh_exec true >/dev/null 2>&1 \
  || fail "sshd in $CONTAINER never accepted the fixture key on port $SSH_PORT"
ok "sshd reachable on the fixture's own port ($SSH_PORT)"

# attach_and_type — drive an attach command over a real SSH PTY, type MARKER,
# then detach with aplexer's Ctrl-b d. The sleeps bracket the typing rather
# than racing it: input is piped in all at once, so without them the marker can
# land before the session's shell has drawn a prompt and is silently lost.
attach_and_type() {
  local marker="$1" log="$2"
  shift 2
  {
    sleep 3
    printf 'echo %s\r' "$marker"
    sleep 3
    printf '\002d'
    sleep 1
  } | ssh_pty "$@" >"$log" 2>&1 || true
}

# capture_shows — the INDEPENDENT oracle, on its own connection: aplexer
# renders the live screen and we look for the marker. Requires >= 2 hits (the
# typed command line AND its output), because 1 would also match a marker that
# was echoed but never executed.
capture_shows() {
  local marker="$1" tag="$2" hits
  hits="$(ssh_exec "a capture --screen --plain --workspace /home/testuser --tag '$tag'" \
    | grep -c -- "$marker" || true)"
  [[ "${hits:-0}" -ge 2 ]]
}

# --- POSITIVE 1: the build-time self-check, re-run over SSH ---------------
printf '\nPOSITIVE 1: build-time self-check, re-run over the SSH exec channel...\n'
ssh_exec /usr/local/bin/pocketshell-fixture-aplexer-selfcheck \
  || fail "the self-check failed inside a freshly built agents container"
ok "self-check passes in the built image, over SSH"

# --- POSITIVE 2: the real CLI, end to end, INCLUDING attach --------------
# This is acceptance criterion 1. The attach leg is the reason this arm exists:
# attachability is the single property the deleted 61-line stub lacked (the old
# image text said an aplexer name "hosts no attachable process"), so a gate that
# stops at create -> list -> kill would let an image whose `a attach` is broken
# build green and ship.
printf '\nPOSITIVE 2: real CLI create -> list -> PTY attach -> capture -> kill...\n'
CLI_TAG="guard-cli-${SUFFIX}"
CLI_MARKER="GUARD_CLI_MARKER_${SUFFIX}"
# `--mem none` (issue #2562): session creates are memory-capped, aplexer's
# limits fail closed, and this container has no user systemd to delegate a
# cgroup-v2 scope. The opt-out is explicit here rather than silent in the CLI.
create_json="$(ssh_exec "pocketshell-real-send sessions create '$CLI_TAG' --backend aplexer --cwd /home/testuser --mem none --json")" \
  || fail "\`sessions create --backend aplexer\` failed over SSH"
CLI_NAME="$(printf '%s' "$create_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("name",""))')"
[[ -n "$CLI_NAME" ]] || fail "create envelope carried no name: $create_json"
printf '%s' "$create_json" | grep -Fq '"manager": "aplexer"' \
  || fail "create did not route to aplexer: $create_json"
ok "create routed to aplexer as '$CLI_NAME'"

# A SEPARATE connection must see it — the session outlives the channel that
# made it, which a shell-stub `a` could never do.
ssh_exec "pocketshell-real-send sessions list --json" > "$tmp_dir/list.json" \
  || fail "\`sessions list --json\` failed over SSH"
python3 - "$tmp_dir/list.json" "$CLI_TAG" "$CLI_NAME" <<'PY' || fail "the real CLI did not enumerate the session it just created"
import json, sys
payload = json.load(open(sys.argv[1]))
tag, name = sys.argv[2], sys.argv[3]
rows = [r for r in payload["sessions"] if r["manager"] == "aplexer" and r["tag"] == tag]
if not rows:
    sys.stderr.write("guard: zero aplexer rows; managers=%r errors=%r\n"
                     % (payload.get("managers"), payload.get("errors")))
    raise SystemExit(1)
row = rows[0]
for key, want in (("name", name), ("phase", "running"), ("alive", True),
                  ("workspace", "/home/testuser")):
    if row.get(key) != want:
        sys.stderr.write("guard: row %s=%r, expected %r\n" % (key, row.get(key), want))
        raise SystemExit(1)
if "aplexer" not in payload.get("managers", []):
    sys.stderr.write("guard: aplexer answered with rows but is absent from managers\n")
    raise SystemExit(1)
print("guard: %s manager=aplexer phase=running alive=true" % (name,))
PY
ok "a separate SSH connection sees the row (manager=aplexer, phase=running)"

attach_and_type "$CLI_MARKER" "$tmp_dir/cli-attach.log" \
  "pocketshell-real-send sessions attach '$CLI_NAME'"
capture_shows "$CLI_MARKER" "$CLI_TAG" || {
  sed 's/^/    /' "$tmp_dir/cli-attach.log" >&2
  fail "\`sessions attach\` over an SSH PTY did not reach a live shell: the typed
  marker $CLI_MARKER is not on the session's screen. THIS IS THE PROPERTY THE
  DELETED STUB LACKED — an unattachable session is exactly what the old image
  documented and what this rebase exists to replace."
}
ok "PTY attach typed a marker that a separate \`a capture\` connection saw"

# Detach is not death: the session must still be there afterwards.
ssh_exec "pocketshell-real-send sessions list --json" \
  | grep -Fq "\"tag\": \"$CLI_TAG\"" \
  || fail "the session vanished after detaching; detach must not kill it"
ok "detach left the session running"

kill_json="$(ssh_exec "pocketshell-real-send sessions kill '$CLI_NAME' --json")" \
  || fail "\`sessions kill\` failed over SSH"
printf '%s' "$kill_json" | grep -Fq '"killed": true' \
  || fail "kill did not report killed=true: $kill_json"
printf '%s' "$kill_json" | grep -Fq '"reaped": true' \
  || fail "kill did not report reaped=true: $kill_json"
ssh_exec "pocketshell-real-send sessions list --json" \
  | grep -Fq "\"tag\": \"$CLI_TAG\"" \
  && fail "the session is still listed after a successful kill"
ok "kill reported killed+reaped and the row is gone"

# --- POSITIVE 3: bare `a`, the way AC2 words it --------------------------
printf '\nPOSITIVE 3: bare `a start` / `a attach` over the same SSH exec channel...\n'
BARE_TAG="guard-bare-${SUFFIX}"
BARE_MARKER="GUARD_BARE_MARKER_${SUFFIX}"
ssh_exec "a start --workspace /home/testuser --tag '$BARE_TAG' -- /bin/sh" >/dev/null \
  || fail "bare \`a start\` failed over the non-interactive SSH exec channel"
# A SECOND, INDEPENDENT channel. The first one is gone; a session that did not
# survive it would not be a session.
ssh_exec "a --json list" > "$tmp_dir/bare-list.json" \
  || fail "bare \`a --json list\` failed on a second SSH channel"
python3 - "$tmp_dir/bare-list.json" "$BARE_TAG" <<'PY' || fail "the second SSH channel did not see the session the first one started"
import json, sys
rows = [r for r in json.load(open(sys.argv[1])) if r.get("tag") == sys.argv[2]]
if not rows:
    sys.stderr.write("guard: tag %r absent from `a --json list`\n" % (sys.argv[2],))
    raise SystemExit(1)
row = rows[0]
if row.get("phase") != "running" or row.get("worker_alive") is not True:
    sys.stderr.write("guard: phase=%r worker_alive=%r\n"
                     % (row.get("phase"), row.get("worker_alive")))
    raise SystemExit(1)
print("guard: bare session %s phase=running worker_alive=true" % (sys.argv[2],))
PY
ok "a second, independent SSH channel sees the bare session running"

attach_and_type "$BARE_MARKER" "$tmp_dir/bare-attach.log" \
  "a attach --workspace /home/testuser --tag '$BARE_TAG'"
capture_shows "$BARE_MARKER" "$BARE_TAG" || {
  sed 's/^/    /' "$tmp_dir/bare-attach.log" >&2
  fail "bare \`a attach\` over an SSH PTY did not reach a live shell"
}
ok "bare \`a attach\` typed a marker a separate \`a capture\` connection saw"
ssh_exec "a --json kill --workspace /home/testuser --tag '$BARE_TAG'" >/dev/null \
  || fail "bare \`a kill\` failed"
ok "bare \`a kill\` cleaned up"

# --- POSITIVE 4: tmux is untouched ---------------------------------------
# This slice ADDS aplexer; later slices remove tmux, and `main` must stay green
# in between.
printf '\nPOSITIVE 4: tmux capability is intact...\n'
ssh_exec '
  set -eu
  tmux -L aplexer-guard-smoke new-session -d -s guardsmoke
  tmux -L aplexer-guard-smoke has-session -t guardsmoke
  tmux -L aplexer-guard-smoke kill-session -t guardsmoke
' || fail "tmux no longer works in the agents image; this slice must not remove tmux"
ok "tmux capability is intact"

# --- NEGATIVE 1: the bundle is half-installed ----------------------------
# Issue #2553 (landed after this slice started) made `resolve_a` REJECT a
# bundled `a` whose sibling worker is missing, rather than let `a` fall back to
# an unpinned `aplexer` off PATH. The fixture must therefore fail loudly for
# that state too — otherwise "both binaries ship" is an unenforced comment.
printf '\nNEGATIVE 1: sibling worker removed — the fixture must fail LOUDLY...\n'
docker exec "$CONTAINER" sh -ec '
  mkdir -p /opt/guard-stash
  mv /usr/bin/aplexer /opt/guard-stash/aplexer
' || fail "could not stage the worker-less state"
set +e
worker_out="$(ssh_exec /usr/local/bin/pocketshell-fixture-aplexer-selfcheck 2>&1)"
worker_rc=$?
set -e
printf '  self-check output:\n%s\n' "$(printf '%s\n' "$worker_out" | sed 's/^/    /')"
[[ $worker_rc -ne 0 ]] \
  || fail "the self-check PASSED with no sibling \`aplexer\` worker installed"
grep -Fq 'FIXTURE-APLEXER-SELFCHECK FAIL [placement]' <<<"$worker_out" \
  || fail "expected a [placement] diagnostic for a worker-less bundle, got: $worker_out"
ok "worker-less bundle exits $worker_rc with FAIL [placement] (#2553)"
docker exec "$CONTAINER" sh -ec 'mv /opt/guard-stash/aplexer /usr/bin/aplexer' \
  || fail "could not restore the worker"
ssh_exec /usr/local/bin/pocketshell-fixture-aplexer-selfcheck >/dev/null \
  || fail "the self-check did not recover after restoring the worker"
ok "restoring the worker makes it green again (so the red above was the cause)"

# --- NEGATIVE 2: `a` on PATH only ----------------------------------------
# THE landmine. `pocketshell.aplexer` resolves `a` next to `sys.executable` and
# nowhere else (D22 hard cut, #2543), so this state is invisible to everything
# except a guard that looks for it.
printf '\nNEGATIVE 2: `a` on PATH only — the fixture must fail LOUDLY...\n'
docker exec "$CONTAINER" sh -ec '
  mkdir -p /opt/path-only-aplexer
  mv /usr/bin/a /opt/path-only-aplexer/a
  mv /usr/bin/aplexer /opt/path-only-aplexer/aplexer
  ln -sf /opt/path-only-aplexer/a /usr/local/bin/a
  ln -sf /opt/path-only-aplexer/aplexer /usr/local/bin/aplexer
' || fail "could not stage the PATH-only state"

# 1. `a` really is on PATH and really does work — the state is "misplaced",
#    not "absent". Otherwise the negative case proves the wrong thing. Checked
#    over SSH, because PATH is the thing under test and sshd owns it here.
ssh_exec 'command -v a && a --json list >/dev/null && echo "PATH-only a is present and functional"' \
  || fail "staging is wrong: \`a\` is not usable from PATH over SSH, so the
  negative case would be testing 'aplexer missing', not 'aplexer misplaced'"
ok "staged state: \`a\` works from PATH over SSH, absent from /usr/bin"

# 2. THE SILENCE. Over the app's own channel, this is all a journey would see:
#    exit 0, managers ["tmux"], no aplexer rows, no errors.
silent="$(ssh_exec '
  pocketshell-real-send sessions list --json |
  python3 -c "import json,sys; d=json.load(sys.stdin); print(\"exit0 managers=%r aplexer_rows=%d errors=%r\" % (d[\"managers\"], len([r for r in d[\"sessions\"] if r[\"manager\"]==\"aplexer\"]), d[\"errors\"]))"
')" || fail "could not observe the silent shape over SSH"
printf '  silent shape a journey would see: %s\n' "$silent"
case "$silent" in
  *"aplexer_rows=0"*) ok "confirmed: the real CLI stays silent over SSH (exit 0, zero aplexer rows)" ;;
  *) fail "expected the misplaced-\`a\` state to yield zero aplexer rows, got: $silent" ;;
esac

# 3. THE GUARD. The self-check must turn that silence into a named failure.
set +e
neg_out="$(ssh_exec /usr/local/bin/pocketshell-fixture-aplexer-selfcheck 2>&1)"
neg_rc=$?
set -e
printf '  self-check output:\n%s\n' "$(printf '%s\n' "$neg_out" | sed 's/^/    /')"
[[ $neg_rc -ne 0 ]] || fail "the self-check PASSED with \`a\` on PATH only — the
  guard is not guarding anything"
grep -Fq 'FIXTURE-APLEXER-SELFCHECK FAIL [placement]' <<<"$neg_out" \
  || fail "the self-check failed for the wrong reason; expected a [placement]
  diagnostic, got: $neg_out"
ok "self-check exits $neg_rc with FAIL [placement]"

# 4. And the BUILD must go red for the same state, so the image cannot ship
#    hollow in the first place. Derived from the built image so this is the
#    real Dockerfile RUN line under a relocated binary, not a model of it.
cat > "$tmp_dir/Dockerfile.negative" <<NEGEOF
FROM $TAG
RUN mkdir -p /opt/path-only-aplexer \\
    && mv /usr/bin/a /opt/path-only-aplexer/a \\
    && mv /usr/bin/aplexer /opt/path-only-aplexer/aplexer \\
    && ln -sf /opt/path-only-aplexer/a /usr/local/bin/a \\
    && ln -sf /opt/path-only-aplexer/aplexer /usr/local/bin/aplexer
RUN su testuser -s /bin/sh -c 'HOME=/home/testuser /usr/local/bin/pocketshell-fixture-aplexer-selfcheck'
NEGEOF
set +e
docker build --file "$tmp_dir/Dockerfile.negative" --tag "$NEG_TAG" "$tmp_dir" \
  >"$tmp_dir/negative-build.log" 2>&1
neg_build_rc=$?
set -e
[[ $neg_build_rc -ne 0 ]] \
  || fail "a build with \`a\` on PATH only SUCCEEDED; the build-time gate is dead"
grep -Fq 'FIXTURE-APLEXER-SELFCHECK FAIL [placement]' "$tmp_dir/negative-build.log" \
  || { tail -n 30 "$tmp_dir/negative-build.log" >&2; \
       fail "the negative build failed for the wrong reason"; }
ok "a build with a misplaced \`a\` fails with FAIL [placement] (rc=$neg_build_rc)"

printf '\nDocker agents-fixture aplexer guard passed (%d checks).\n' "$pass_count"
