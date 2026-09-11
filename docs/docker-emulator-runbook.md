# Docker and emulator runbook

Use the Docker SSH fixtures and the local Android emulator together for
session, terminal, agent, setup, and release-gate work. The fixture is part of
the product contract: session journeys must reach a real bundled aplexer
binary, not a canned session table.

## Targets and ports

The compose file is `tests/docker/docker-compose.yml`.

| Target | Port | Use |
|---|---:|---|
| `sshd` | 2222 | Minimal OpenSSH transport host |
| `agents` | 2222 | Main app2 fixture with real aplexer and deterministic agent tools |
| `bootstrap-*` | 2230–2236 | Host-install and setup-state scenarios |
| `agents-old-cli` | 2238 | Host helper/version mismatch behavior |
| `agents-daemon` | 2239 | Durable host-side tree registry |
| `sshd-rekeyed` | 2246 | Host-key rotation behavior |

Run one target at a time when they share a port. The `agents` target can use a
pool port and a separate compose project for parallel emulator lanes:

```bash
COMPOSE_PROJECT_NAME=psagents2243 \
AGENTS_HOST_PORT=2243 \
AGENTS_CONTAINER_NAME=pocketshell-test-agents-2243 \
docker compose -f tests/docker/docker-compose.yml up -d --build agents
```

The pool helper owns the standard candidate ports and their locks:

```bash
scripts/agents-pool.sh up 2243 2244
scripts/agents-pool.sh status
scripts/agents-pool.sh down 2243 2244
```

Do not assign port 2222 to a pool lane. It is the legacy single-lane identity
used by the default connected-test path.

## Build and inspect the real agents fixture

The `agents`, `agents-old-cli`, and `agents-daemon` images are glibc-based
because the pinned aplexer release publishes glibc binaries. Their Dockerfiles
derive the exact aplexer version from `tests/docker/fixture-pins.txt` (which
also pins the published `pocketshell` wheel the fixtures install — the CLI is
no longer built from this repo, issue #2643), install the
matching `/usr/bin/a` and sibling `/usr/bin/aplexer`, copy the current Python
helper, and run the fixture self-check. The self-check performs create, list,
attach-shape, kill, and gone checks against a real session registry.

```bash
scripts/test-agents-fixture-aplexer.sh
scripts/test-agents-fixture-aplexer.sh --docker
docker compose -f tests/docker/docker-compose.yml build agents agents-old-cli agents-daemon
docker compose -f tests/docker/docker-compose.yml up -d agents
ssh -i tests/docker/test_key -p 2222 \
  -o StrictHostKeyChecking=no testuser@127.0.0.1 \
  'command -v pocketshell && command -v a && command -v aplexer && pocketshell sessions list --json'
```

The fixture entrypoint seeds one idle aplexer shell. Journey tests create their
own records, verify them through the host listing or PTY, and clean them up.
The deterministic `/usr/local/bin/pocketshell` wrapper remains only for
non-session probes; its session, tree, and engine paths delegate to the real
Python helper at `/usr/local/bin/pocketshell-real`.

## Emulator setup

The maintained AVD is named `test`. Use the explicit SDK paths when they are
not on `PATH`:

```bash
export ANDROID_HOME=/home/alexey/Android/Sdk
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
AVD_HOLD=1 scripts/start-local-avd.sh
adb devices
```

Never kill an emulator owned by another lane. If `/dev/kvm` is unavailable,
`AVD_HOLD=1` lets the local starter retain the booted device for the connected
run. Install the debug APK with `scripts/assemble-debug.sh --install`.

## Connected journeys

Start the default fixture and run the unfiltered app2 suite:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
scripts/connected-test.sh --suffix i2561
```

The load-bearing journeys are J02 session tree, J03 attach and type, J04
create session, J05 reconnect after a drop, J06 background grace return, J07
composer send, J08 voice dictation, J12 usage refresh, J14 stop session, and
J15 terminal scroll. J02, J03, J04, and J14 use independent real aplexer
records; J05 and J06 use the same PTY and reconnect path; J15 uses the real
alternate-screen fixture. A journey must fail when the fixture capability is
missing rather than silently falling back to a canned row.

For a pool lane, pass `--pool` and use its allocated port through
`connected-test.sh`; the wrapper records the port and compose identity in the
run artifacts. Inspect the resulting screenshots and instrumentation output
when reviewing a user-facing change.

## Health and cleanup

Compose health checks validate sshd's effective configuration and authenticate
as `testuser` using the same key path as the app. A local probe that succeeds
inside a container is not enough evidence for the host-published port.

```bash
docker compose -f tests/docker/docker-compose.yml ps
docker inspect --format='{{.State.Health.Status}}' pocketshell-test-agents
docker compose -f tests/docker/docker-compose.yml logs --tail=100 agents
docker compose -f tests/docker/docker-compose.yml down --volumes --remove-orphans
```

The release workflow uses the same compose file and removes its targets in an
`always` cleanup step. Do not use a broad Docker prune while another lane is
running; it can delete a fixture or image that the lane still owns.

## Product grep boundary

The product has no session-runtime dependency on the operational tmux tooling.
The runner still needs isolated tmux sockets for agent processes; preserve the
rules in `AGENTS.md`, `process.md`, and
[`tmux-socket-recovery.md`](tmux-socket-recovery.md). Those operational paths
are excluded from the product grep described in [testing.md](testing.md).
