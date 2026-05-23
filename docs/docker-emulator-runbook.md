# Docker + Emulator Runbook

This is the evaluator runbook for Android emulator + Docker checks. Use it
before claiming a mobile, SSH, tmux, agent, setup, or release-gate workflow is
blocked.

## Local Android Tools

The SDK tools may not be on `PATH`. In this workspace use explicit paths:

```bash
export ANDROID_SDK=/home/alexey/Android/Sdk
export ADB="$ANDROID_SDK/platform-tools/adb"
export EMULATOR="$ANDROID_SDK/emulator/emulator"

"$ADB" devices
"$EMULATOR" -list-avds
```

Known local AVD:

```text
test
```

Start the emulator:

```bash
"$EMULATOR" -avd test -no-snapshot-load -no-window -gpu swiftshader_indirect -no-audio
```

Wait for boot:

```bash
for i in {1..90}; do
  state=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$state" = "1" ] && break
  sleep 2
done
"$ADB" devices
```

## Docker Profiles

All reusable Docker targets live under `tests/docker/` and are driven by:

```bash
docker compose -f tests/docker/docker-compose.yml ...
```

Reusable compose services:

- `sshd`: builds `pocketshell-test:ssh`, maps host port `2222`, and contains
  Alpine, OpenSSH, `testuser`, and `tests/docker/test_key.pub` in
  `authorized_keys`. Use it for manual base SSH checks. `Dockerfile.ssh` is
  also built directly by the `shared/core-ssh`, `shared/core-portfwd`, and
  `ProofPipelineTest` Testcontainers suites on ephemeral host ports.
- `tmux`: builds `pocketshell-test:tmux`, maps host port `2224`, and adds
  `tmux`, Python, and real `tmuxctl` installed with `pip` on top of the base
  SSH image. Use it for manual tmux checks. `Dockerfile.tmux` is also built
  directly by the `shared/core-tmux` Testcontainers suite on ephemeral host
  ports.
- `agents`: builds `pocketshell-test:agents`, maps host port `2222`, and
  contains OpenSSH, tmux, `procps`, deterministic `claude`, `codex`,
  `opencode`, `heru`, `agent-log-explorer`, `tmuxctl`, `uv`, and `systemctl`
  shims plus seeded agent fixtures. Use it for normal connected Android smoke,
  dogfood journeys, usage/jobs/agent fixture checks, and the APK dogfood gate.
- `bootstrap-ready`: builds `pocketshell-test:bootstrap-ready`, maps host port
  `2230`, and contains the shared bootstrap base plus `tmuxctl`, `heru`,
  `agent-log-explorer`, and `systemctl` shims in `/usr/local/bin`; the
  `systemctl` shim reports `tmuxctl-jobs.service` as active and enabled. This
  represents a host where server tools and the user daemon are already ready.
  Used by `HostBootstrapScenarioSuiteTest#ready`.
- `bootstrap-uv-install`: builds `pocketshell-test:bootstrap-uv-install`, maps
  host port `2231`, and contains the shared bootstrap base plus `uv` and
  `systemctl` shims in `/usr/local/bin`; `tmuxctl`, `heru`, and
  `agent-log-explorer` start absent and can be copied into
  `/home/testuser/.local/bin` by the `uv tool install <package>` shim. The
  `systemctl` shim reports `tmuxctl-jobs.service` as active and enabled. Used
  by `HostBootstrapScenarioSuiteTest#uvInstall`.
- `bootstrap-unsupported`: builds `pocketshell-test:bootstrap-unsupported`,
  maps host port `2232`, and contains only the shared bootstrap base. No
  `tmuxctl`, `heru`, `agent-log-explorer`, `uv`, or `systemctl` shim is
  installed on `PATH`; the fixture daemon state is inactive and disabled. This
  represents a host with missing tools and no supported installer. Used by
  `HostBootstrapScenarioSuiteTest#unsupported`.
- `bootstrap-daemon-disabled`: builds
  `pocketshell-test:bootstrap-daemon-disabled`, maps host port `2233`, and
  contains the shared bootstrap base plus `tmuxctl`, `heru`,
  `agent-log-explorer`, and `systemctl` shims in `/usr/local/bin`; the
  `systemctl` shim reports `tmuxctl-jobs.service` as active but disabled. This
  represents a host where tools are present but `tmuxctl-jobs.service` is
  disabled. Used by `HostBootstrapScenarioSuiteTest#daemonDisabled`.
- `bootstrap-user-local-path`: builds
  `pocketshell-test:bootstrap-user-local-path`, maps host port `2234`, and
  contains the shared bootstrap base plus `tmuxctl`, `heru`, and
  `agent-log-explorer` shims in `/home/testuser/.local/bin` and a `systemctl`
  shim in `/usr/local/bin`; the `systemctl` shim reports
  `tmuxctl-jobs.service` as active and enabled. This represents a host where
  tools live under user-local paths that must be found by login/PATH handling.
  Used by
  `HostBootstrapScenarioSuiteTest#userLocalPath`.
- `bootstrap-fish-user-local-path`: builds
  `pocketshell-test:bootstrap-fish-user-local-path`, maps host port `2235`,
  and mirrors `bootstrap-user-local-path` with `fish` installed and
  `/usr/bin/fish` configured as `testuser`'s login shell. This represents a
  user-local tool installation that must be discovered when SSH starts a fish
  login environment. Used by `HostBootstrapScenarioSuiteTest#fishUserLocalPath`.

All `bootstrap-*` profiles share `Dockerfile.bootstrap`: Alpine with
`openssh-server`, real `tmux`, `procps`, `testuser`, `tests/docker/test_key.pub`
authorized for SSH, and fixture binaries copied to
`/opt/pocketshell-bootstrap-bin` for scenario-specific installation by
`bootstrap-entrypoint.sh`.

`sshd` and `agents` both bind host port `2222`; run one at a time unless you
change ports intentionally.

Testcontainers-based JVM integration tests build the Dockerfiles directly and
publish container port `22` on ephemeral host ports. They do not consume the
compose host ports above, except that `Dockerfile.tmux` requires a local
`pocketshell-test:ssh` base tag while building.

## Port Conflicts

Check before starting Docker profiles:

```bash
docker ps --format '{{.ID}} {{.Names}} {{.Ports}}'
ss -ltnp 'sport = :2222' || true
for port in 2224 2230 2231 2232 2233 2234 2235; do
  ss -ltnp "sport = :$port" || true
done
```

If port `2222` is occupied by a stale local test container, stop only that
container:

```bash
docker stop <container-name-or-id>
```

If the owner is not obvious, do not kill random processes. Report the conflict
with the `docker ps` / `ss` output.

For parallel testing, prefer existing non-overlapping ports:

- `agents` on `2222` for the normal connected Android smoke.
- `tmux` on `2224` for manual tmux checks.
- bootstrap profiles on `2230` through `2235`.

Do not run `sshd` and `agents` together without changing one of their host
ports because both claim `2222`. The standard safe parallel sets are:

- Android connected smoke plus manual tmux: `agents` + `tmux`.
- Bootstrap setup suite: all six `bootstrap-*` services.
- JVM Testcontainers suites plus any compose service: safe by default because
  Testcontainers uses ephemeral host ports.

If a new compose profile must run in parallel, add a new explicit host port in
`tests/docker/docker-compose.yml`; do not reuse `2222`, `2224`, or `2230`
through `2235`. Update the service list above, any Android fixture constants,
and the host-side sanity command in this runbook in the same change.

## Standard Commands

Start the Docker agent target used by normal connected Android smoke:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
```

Host-side SSH sanity check:

```bash
chmod 600 tests/docker/test_key
ssh -i tests/docker/test_key -p 2222 \
  -o BatchMode=yes \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  testuser@127.0.0.1 \
  'for tool in heru agent-log-explorer tmuxctl uv; do command -v "$tool"; done'
```

Run the full connected Android suite:

```bash
./gradlew --no-daemon connectedDebugAndroidTest --stacktrace
```

Run focused connected checks:

```bash
./gradlew --no-daemon :shared:core-terminal:connectedDebugAndroidTest --stacktrace
CLASS_ARG="-Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.EmulatorDockerSshSmokeTest"
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  "$CLASS_ARG"
```

Run the fast local phone-dogfood reproduction harness on an already-booted
emulator:

```bash
scripts/phone-dogfood.sh terminal-lab
```

The harness starts/verifies the Docker `agents` target, checks emulator boot
state with the explicit `adb` path, runs only the selected scenario, and writes
screenshots, timings, logcat, instrumentation output, Docker logs, command
logs, and crash diagnostics under `build/phone-dogfood/<run-id>/`. The first
supported scenario is `terminal-lab`; `tmux-existing-session`,
`setup-detection`, and `visual-audit` are planned follow-ups.


## Bootstrap Setup Suite

Start all bootstrap profiles:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build \
  bootstrap-ready \
  bootstrap-uv-install \
  bootstrap-unsupported \
  bootstrap-daemon-disabled \
  bootstrap-user-local-path \
  bootstrap-fish-user-local-path
```

Run the opt-in bootstrap suite:

```bash
BOOTSTRAP_ARG="-Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true"
CLASS_ARG="-Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest"
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  "$BOOTSTRAP_ARG" \
  "$CLASS_ARG"
```

Cleanup:

```bash
docker compose -f tests/docker/docker-compose.yml down --volumes --remove-orphans
```

## Adding a New Docker Fixture

Use this checklist for new profiles:

- Add a deterministic Dockerfile or extend an existing one under
  `tests/docker/`. Pin behavior in fixture scripts and checked-in data, not in
  live network calls.
- Add shims/fixtures under `tests/docker/*-bin/` or
  `tests/docker/*-fixtures/`. Prefer simple executable scripts and static JSON,
  JSONL, or text rows that parsers can assert exactly.
- Do not use real provider credentials, private hosts, or private keys. The
  reusable SSH identity is the public test keypair in `tests/docker/test_key`
  and `tests/docker/test_key.pub`.
- Add a compose service with a unique host port. Keep ports explicit so
  emulator tests can target `10.0.2.2:<port>` and parallel runs are predictable.
- Add or update an Android fixture constant when a connected test needs the new
  port. Use `10.0.2.2` from emulator tests, not `127.0.0.1`.
- Add a host-side sanity command to this runbook that checks SSH auth and the
  expected tools or files.
- Add a fast contract test that proves the fixture shape before using it in an
  emulator scenario. For command shims, follow
  `DockerAgentFixtureContractTest`: execute the checked-in shim directly and
  assert parser-compatible output.
- Keep cleanup/reset commands idempotent so scenarios can run repeatedly
  against warm containers.
- Update `docs/testing.md` when the new fixture changes the testing matrix, and
  update this runbook whenever images, ports, commands, or connected test
  selectors change.
