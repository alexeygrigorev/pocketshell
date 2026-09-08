# Testing and QA

PocketShell has two end-to-end surfaces:

1. the Android emulator, which runs the app and validates visible UI behavior;
2. disposable Docker SSH hosts, which provide the host CLI, aplexer, agent
   fixtures, and fault-injection targets.

The app and host fixture must be tested together for session work. A test that
only finds a canned row or checks an internal ViewModel state is not evidence
that attach, input, or stop works for a user.

## Fast local checks

Run these from the repository root:

```bash
scripts/full-jvm-gate.py
uv run --project tools/pocketshell pytest -q
scripts/assemble-debug.sh
git diff --check
```

The JVM gate runs every unit test with one Gradle worker and no build cache. The
Python command covers the published host helper. `assemble-debug.sh` is the
local APK path; it does not run the release-gate profile or build connected
tests.

For a session-runtime change, also run the focused fixture contract checks:

```bash
scripts/test-agents-fixture-aplexer.sh
scripts/test-agents-fixture-aplexer.sh --docker
```

The first command statically checks the image, shims, and journey sources. The
Docker mode builds the image, runs the bundled-aplexer lifecycle self-check, and
probes create → list → attach → kill against an actual container.

## Disk preflight

The canonical local gates check free space before claiming an emulator, Docker
fixture, or Gradle output lock. This keeps an `ENOSPC` capacity problem distinct
from a product or test failure (issue #1989).

| Free space on the gate's filesystem | Behaviour |
|---|---|
| below 10 GiB | refuse to start, exit **76**, and print the cleanup command |
| 10–20 GiB | run with a `WARN: disk preflight` line naming the cleanup command |
| above 20 GiB | run silently |

`connected-test.sh --cleanup-suffixes` is exempt because it builds nothing and
is the recovery path for a full box. Use `scripts/disk-cleanup.sh` for the
serialized safe-list cleanup; it defaults to a dry run and `--apply` performs
the bounded cleanup.

Release validation has a larger fixed admission budget:

| Free space on the release-validation filesystem | Behaviour |
|---|---|
| below 24 GiB | refuse the release validation, exit **76**, and print the safe cleanup command |
| 24 GiB or more | reclaim stale copied worktrees, then start normally |

## Android emulator

The maintained local AVD is `test`. The SDK paths on the maintainer box are:

```text
/home/alexey/Android/Sdk/platform-tools/adb
/home/alexey/Android/Sdk/emulator/emulator
```

If the shell cannot access `/dev/kvm`, start the emulator with
`AVD_HOLD=1 scripts/start-local-avd.sh`. Do not kill an emulator that another
lane already owns.

Build and install the debug APK, then run the connected suite against a running
emulator and the Docker agents fixture:

```bash
scripts/assemble-debug.sh --install
docker compose -f tests/docker/docker-compose.yml up -d --build agents
scripts/connected-test.sh --suffix i2561
```

The connected lane is unfiltered. It runs every app2 journey in one process so
session creation, attach, background grace, and cleanup are tested in the same
state model. A lane that uses a non-default fixture port passes
`-Pandroid.testInstrumentationRunnerArguments.agentsPort=<port>` through the
wrapper or uses the agents-pool workflow described in
[docker-emulator-runbook.md](docker-emulator-runbook.md).

Network-fault journeys under `connected-test.sh --pool` are isolated per lane
(issue #2128): the claimed agents port derives that lane's Toxiproxy SSH/API
ports and compose project. The default `--no-pool` path keeps the single
fixture ports for local and nightly runs. If a pool lane's proxy is recreated
mid-run, the wrapper fails with a fixture error rather than reporting an empty
session list as an app result.

User-facing changes require reviewer evidence from the real app: capture the
screen with `adb exec-out screencap -p > /tmp/pocketshell-screen.png`, include
the Docker target and command, and report the visible result on the issue.

## Docker targets

All Dockerfiles live in `tests/docker/`.

| Target | Purpose |
|---|---|
| `pocketshell-test:ssh` | Minimal OpenSSH host for transport and port-forward tests |
| `pocketshell-test:agents` | Main emulator fixture with real pinned aplexer and deterministic agent/helper shims |
| `pocketshell-test:agents-old-cli` | Real aplexer host whose wrapper rejects newer connect probes |
| `pocketshell-test:agents-daemon` | Real aplexer host with the durable Python tree registry enabled |
| `pocketshell-test:bootstrap-*` | Setup-detection scenarios for installation and service state |

The `agents` image is based on glibc because the production aplexer release
publishes glibc binaries. Its build derives the pinned version from
`tools/pocketshell/pyproject.toml`, installs `/usr/bin/a` beside the Python
interpreter and its sibling `/usr/bin/aplexer` worker, and runs
`agents-aplexer-selfcheck.py`. The self-check creates a named session, lists a
live `phase: running` / `alive: true` row, kills it, and verifies that it is
gone. A zero-row or stub implementation fails the image build.

The fixture's `/usr/local/bin/pocketshell` command keeps deterministic answers
for non-session probes. Its `sessions`, engine, and tree paths delegate to
`/usr/local/bin/pocketshell-real`, which runs the repository's real Python
package against the real aplexer binaries. The entrypoint seeds one idle
aplexer session; journeys create their own records and clean them up.

Build and inspect the targets manually:

```bash
docker compose -f tests/docker/docker-compose.yml build agents agents-old-cli agents-daemon
docker compose -f tests/docker/docker-compose.yml up -d agents
ssh -i tests/docker/test_key -p 2222 \
  -o StrictHostKeyChecking=no testuser@127.0.0.1 \
  'command -v a && command -v aplexer && pocketshell sessions list --json'
```

Port 2222 belongs to the default `agents` fixture. It is reserved for the
Docker `agents` target and must not be taken over by an unrelated container.
Use `scripts/agents-pool.sh` for isolated ports when parallel lanes are needed.

## Session journeys

The load-bearing app2 journeys use real aplexer records:

| Journey | Evidence |
|---|---|
| J02 session tree | Seeds and lists independent live aplexer sessions, then compares the rendered tree with a separate host listing |
| J03 attach and type | Creates a session, attaches through the app, writes a marker, and verifies it through the host |
| J04 create session | Submits the create sheet and verifies the new aplexer row, workspace, and cleanup |
| J14 stop session | Stops the selected record and verifies the row disappears from a fresh aplexer listing |
| J15 terminal scroll | Attaches the alternate-screen fixture and proves a drag does not become a cursor key |

J05/J06 exercise reconnect and bounded background grace against the same PTY
path. J07/J08 exercise composer and voice input, and J12 verifies that the
usage refresh and live session enumeration do not overwrite each other.

Every journey asserts the rendered viewport or an independent host-side oracle.
Internal state is useful diagnostics but does not satisfy the acceptance bar.
No journey is skipped to accommodate a missing session runtime; a fixture
capability failure is an infrastructure failure.

## Storage migration coverage

Schema 21 removes `HostEntity.tmuxInstalled`. Migration 20→21 rebuilds the
`hosts` table without that column. Earlier migration code, the exported schemas
for versions 1–20, and old-schema test setup SQL retain the literal column name
because they must read databases produced by older APKs. That SQL is historical
input, not a product feature: current Room entities, DAOs, queries, and schema
21 contain no such field. `AppDatabaseTest` covers both opening old shapes and
the post-migration column list. The upgrade-preservation and pre-release gate
scripts use the same old-schema setup SQL to exercise that data-preserving
boundary; their literals are fixtures for migration history, not executable
session behavior.

The documented product grep excludes only those old migration/schema files and
the vendored `shared/core-terminal/src/main/java/com/termux/**` comments. The
vendored source is kept byte-compatible with upstream. Operational tmux socket
and cgroup code is also excluded from the product grep because it belongs to
the agent runner; its paths are listed in the final audit command below.

## Product grep audit

The blocking static guard runs this audit on every unit-gate invocation:

```bash
scripts/check-product-tmux-absent.sh --self-test
scripts/check-product-tmux-absent.sh
```

To inspect the same product surface manually after a session-runtime change:

```bash
rg -n -i 'tmux' \
  tools/pocketshell/src app2/src/main shared/*/src/main \
  --glob '!shared/core-terminal/src/main/java/com/termux/**' \
  --glob '!shared/core-storage/src/main/java/com/pocketshell/core/storage/AppDatabase.kt' \
  --glob '!shared/core-storage/src/main/java/com/pocketshell/core/storage/LegacyVersionOneMigration.kt'
```

The only permitted hits are the historical Room migration boundary explained
above and upstream vendored comments. Keep the agent-runner socket safeguards
in `AGENTS.md`, `process.md`, `scripts/lib/scope-run.sh`,
`scripts/test-full-jvm-gate-detached.sh`, `scripts/avd-pool.sh`,
`scripts/connected-test.sh`, and [tmux-socket-recovery.md](tmux-socket-recovery.md).

## CI and release evidence

The required unit lanes run the JVM and Python suites plus static guards. The
app2 workflow runs the unfiltered emulator journey lane and the real SSH
integration lanes. The pre-release confidence gate repeats the APK identity,
Docker fixture, emulator, and release-test ledger checks before a tag.

Release work follows [release.md](release.md). A release note or status report
must name the exact commands and distinguish a green product check from a
fixture, emulator, or host-capability blocker.
