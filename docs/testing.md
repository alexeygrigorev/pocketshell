# Testing & QA

Two emulation surfaces let us test PocketShell end-to-end without touching real devices or real hosts:

1. Android emulator — runs the app, validates UI/UX, exercises Compose interactions
2. Docker remote server — emulates the SSH target, with tmux + agents + helper tools installed

Together they cover the full feature surface without leaving the dev machine.

---

## Android emulator

Standard Android Studio AVDs. Recommended set:

| AVD | API | Why |
|---|---|---|
| Pixel 7 | 34 (Android 14) | Matches design target (412 × 915 dp, same as mockups in `docs/mockups/`) |
| Pixel 7 | 26 (Android 8.0) | Minimum supported; spot-check before releases |

### Running

Command-line launch (no Android Studio):

```bash
$ANDROID_HOME/emulator/emulator -avd pixel_7_api_34 -no-snapshot-load &
./gradlew installDebug
adb shell am start -n com.pocketshell.app/.MainActivity
```

### Automated UI tests

Compose UI tests on the emulator via `./gradlew connectedDebugAndroidTest`. Use:

- `createComposeRule()` for component-level tests
- `createAndroidComposeRule<MainActivity>()` for screen-level tests
- Espresso interop where needed

The connected test suite also includes a local end-to-end SSH + agent smoke
test. Start the deterministic agent Docker target first, then run the connected
debug tests on an already-running emulator:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
./gradlew connectedDebugAndroidTest
docker compose -f tests/docker/docker-compose.yml down --volumes --remove-orphans
```

### Manual visual validation (orchestrator's loop)

For visual changes:

1. Make the change
2. `./gradlew installDebug`
3. Compare side-by-side with `docs/mockups/<screen>.html` open in Chrome at 412 × 915
4. Screenshot for the PR: `adb shell screencap -p > /tmp/screen.png && adb pull /tmp/screen.png`

---

## Docker remote server

The remote-side target for SSH, tmux, port-forwarding, agent detection, and usage panel tests. Image is built layered so different phases pull in different surfaces:

| Tag | Adds | Used by |
|---|---|---|
| `pocketshell-test:ssh` | openssh + test user with key auth | `core-ssh`, `core-portfwd` |
| `pocketshell-test:tmux` | tmux + `tmuxctl` | `core-tmux`, recurring jobs |
| `pocketshell-test:agents` | Claude Code, Codex, OpenCode CLIs + `heru` + `agent-log-explorer` + sample JSONL fixtures | `core-agents`, `core-usage`, host bootstrap |

All Dockerfiles live in `tests/docker/`.

### Base SSH image

Adapted from `../ssh-auto-forward/docker/`. Alpine + openssh, test key in `authorized_keys`. Compose maps host port 2222 → container 22.

```bash
docker compose -f tests/docker/docker-compose.yml up -d
ssh -i tests/docker/test_key -p 2222 -o StrictHostKeyChecking=no testuser@127.0.0.1
```

### Adding tmux + tmuxctl

`Dockerfile.tmux` extends the base:

```dockerfile
FROM pocketshell-test:ssh
RUN apk add --no-cache tmux python3 py3-pip \
 && pip install --break-system-packages tmuxctl
```

### Agent target

`Dockerfile.agents` is the local deterministic target for emulator smoke tests.
It does not install real provider CLIs and never needs live API credentials.
Instead it ships stable shims for:

- `claude`, `codex`, `opencode` — credential-free version stubs
- `heru usage --json` — normalized multi-provider usage JSON
- `agent-log-explorer detect --cwd <path>` — stable agent-candidate rows
- `tmuxctl jobs list/add/edit/remove` — stable recurring-job command shapes
- `uv tool install <package>` — bootstrap installer shim
- `systemctl --user is-active/is-enabled tmuxctl-jobs.service` — systemd-like status shim

Run it on the host SSH port used by the Android emulator smoke:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
ssh -i tests/docker/test_key -p 2222 -o StrictHostKeyChecking=no testuser@127.0.0.1 \
  'for tool in heru agent-log-explorer tmuxctl uv; do command -v "$tool"; done && heru usage --json && tmuxctl jobs list --session codex'
```

The `agents` and `sshd` compose services both map host port 2222. Run one at a
time.

### Fixture JSONLs for agent tests

The agent target seeds `testuser`'s home with recent deterministic fixtures on
container start:

- `$HOME/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl`
- `$HOME/.codex/sessions/2026/05/22/pocketshell-codex.jsonl`
- `$HOME/.local/share/opencode/pocketshell-rows.jsonl`

The Claude path is deliberately shaped so PocketShell's exact runtime detection
command for cwd `/workspace/pocketshell` finds it via `find ... -mmin -5`.

---

## What gets tested where

| Layer | Where | Targets |
|---|---|---|
| Unit (pure Kotlin) | `*/src/test/` | Parsers, data classes, business logic with mocked I/O |
| Integration — SSH | `*/src/test/` via Testcontainers | `core-ssh` against `pocketshell-test:ssh` |
| Integration — tmux | `*/src/test/` via Testcontainers | `tmux -CC` parser + events against `pocketshell-test:tmux` |
| Integration — agents | `*/src/test/` | JSONL parsers and deterministic Docker command fixture contracts |
| Integration — usage | `*/src/test/` | `core-usage` parses deterministic `heru usage --json` output |
| Instrumented UI / smoke | `app/src/androidTest/` on emulator | Compose screen tests, navigation, local emulator-to-Docker agent smoke |
| Manual smoke | Emulator + Docker | Each PR before merge — orchestrator validates with eyes |

---

## Connecting the emulator to the Docker server

Inside an Android emulator, `10.0.2.2` is the host machine's loopback. So the app connects to Docker's mapped port like this:

```
hostname: 10.0.2.2
port:     2222
user:     testuser
key:      tests/docker/test_key (imported via the app's host-add flow)
```

Quick sanity check from the emulator shell:

```bash
adb shell
$ nc -zv 10.0.2.2 2222
```

### Local emulator + agent smoke

1. Start an emulator.
2. Start the deterministic agent target:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
```

3. Confirm host-side SSH and command fixtures:

```bash
ssh -i tests/docker/test_key -p 2222 -o StrictHostKeyChecking=no testuser@127.0.0.1 \
  'for tool in heru agent-log-explorer tmuxctl; do command -v "$tool"; done && heru usage --json && tmuxctl jobs list --session codex'
```

4. Run the connected Android smoke:

```bash
./gradlew connectedDebugAndroidTest
```

The smoke test authenticates with `tests/docker/test_key`, connects to
`10.0.2.2:2222`, asserts the helper commands are on PATH, parses
`heru usage --json`, runs `tmuxctl jobs list`, checks `agent-log-explorer`, and
uses PocketShell's Claude JSONL detection/read path over SSH. It also seeds a
deterministic saved host in the debug app, opens it through the real host-list
UI, sends dogfood shell commands through the prompt composer, verifies visible
terminal transcript output for `ls`, `pwd`, and tmux, verifies the remote
artifacts, and cleans up the remote temp directory and tmux session.

### Local phone dogfood reproduction

For fast visual feedback without installing an APK on a physical phone, run the
local phone-dogfood harness against an already-booted emulator:

```bash
scripts/phone-dogfood.sh terminal-lab
```

The harness verifies the explicit SDK paths from `agents.md`, fails clearly if
no booted emulator is connected, starts/verifies the Docker `agents` SSH
fixture, builds and installs the debug app/test APKs, runs only the selected
scenario, and writes one artifact bundle under
`build/phone-dogfood/<run-id>/`.
By default it uses `build/phone-dogfood/gradle-home` as an isolated
`GRADLE_USER_HOME`, disables the Gradle build cache and parallel execution for
the APK build, and removes the app module's generated build output directory so
stale KSP/Hilt/Javac transaction state cannot be reused. Set
`PHONE_DOGFOOD_CLEAN_GENERATED=0` only when investigating those generated
outputs directly.

The first supported scenario is `terminal-lab`. It opens the isolated terminal
lab activity, connects from the emulator to Docker SSH, sends commands through
the terminal input path, captures named screenshots, records transition timing,
and collects bounded logcat, instrumentation output, Docker logs, command
timings, and crash diagnostics. Use `BUILD_APKS=0` to reuse existing debug APKs
when iterating on harness behavior:

```bash
BUILD_APKS=0 scripts/phone-dogfood.sh terminal-lab
```

This differs from CI and the pre-release confidence gate: it is a local
reproduction loop for one dogfood journey and reviewer-visible artifacts. It
does not replace unit tests, connected CI, or the slower release gate.

### Opt-in end-to-end scenario suites

Some workflows need real app UI plus multiple remote-host states, but are too
slow and stateful for every PR. These live as opt-in scenario suites: automated,
repeatable, and documented, but run manually before releases or while
investigating regressions.

Scenario suites should follow these rules:

- Use Docker host profiles/containers, never real hosts or private keys.
- Drive the Android app through emulator UI when the behavior is user-facing.
- Support running one scenario by name and running the full suite.
- Keep fast CI green without requiring the full suite on every push.
- Clean up remote files, tmux sessions, and containers after each scenario.
- Record the exact command for each scenario in this document.

The first suite is host setup/bootstrap. It should cover at least:

- `ready`: all tools and the daemon are already available; no install prompt.
- `uv-install`: tools are missing but `uv` is available; install succeeds.
- `unsupported`: tools are missing and no installer is available; clear manual
  setup state.
- `daemon-disabled`: tools are present but the jobs daemon is disabled; only
  daemon enablement is offered.
- `user-local-path`: tools are installed under user-local directories that are
  absent from a default non-login SSH `PATH`; detection still succeeds.

The false-positive setup bug is tracked in #70. The reusable opt-in scenario
suite is tracked in #71.

### Host setup/bootstrap scenario suite

The bootstrap suite is implemented as opt-in Android instrumentation tests
against five deterministic Docker SSH hosts. It seeds a disposable host in the
app database, launches PocketShell, taps the host row, and asserts the visible
setup sheet/action state for each profile. Direct SSH inside the suite is
limited to pre/post scenario reset and post-action probes. It is skipped unless
the instrumentation argument is set, so normal `connectedDebugAndroidTest` runs
do not need these containers.

Start all bootstrap host profiles:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build \
  bootstrap-ready \
  bootstrap-uv-install \
  bootstrap-unsupported \
  bootstrap-daemon-disabled \
  bootstrap-user-local-path \
  bootstrap-fish-user-local-path
```

Run the whole opt-in suite on an already-running emulator:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest
```

Run one scenario by name:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest#uvInstall
```

Scenario-to-service mapping:

| Scenario | Service | Host port |
|---|---|---|
| `ready` | `bootstrap-ready` | `2230` |
| `uv-install` | `bootstrap-uv-install` | `2231` |
| `unsupported` | `bootstrap-unsupported` | `2232` |
| `daemon-disabled` | `bootstrap-daemon-disabled` | `2233` |
| `user-local-path` | `bootstrap-user-local-path` | `2234` |
| `fish-user-local-path` | `bootstrap-fish-user-local-path` | `2235` |

Cleanup:

```bash
docker compose -f tests/docker/docker-compose.yml down --volumes --remove-orphans
```

The mutable `uv-install` and `daemon-disabled` scenarios reset their remote
state before and after each test, so running a single scenario repeatedly
against the same containers starts from the documented pristine profile.

---

## CI matrix

GitHub Actions runs:

1. `./gradlew test --stacktrace` — unit tests
2. `./gradlew :shared:core-ssh:integrationTest :shared:core-portfwd:integrationTest` — Docker-backed JVM integration tests via Testcontainers
3. Local emulator + Docker agent smoke after the fast gates pass:
   - starts `tests/docker`'s `agents` target on host port 2222
   - runs `./gradlew connectedDebugAndroidTest` on an Android emulator
   - uploads Android test reports and Docker logs as workflow artifacts

---

## Orchestrator's pre-merge QA checkpoint

Before merging any PR, the orchestrator runs at minimum:

1. `./gradlew assembleDebug` — does it build?
2. `./gradlew check` — do unit tests pass?
3. For UI changes: install on emulator, eyeball against the matching mockup
4. For SSH / tmux / agent / usage changes: run the relevant Testcontainers integration test

See [agents.md](../agents.md) for the full verification checklist.
