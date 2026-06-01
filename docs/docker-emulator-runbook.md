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
scripts/start-local-avd.sh
```

The helper uses the shared AVD lock, starts the local `test` AVD with the
review-safe headless flags, waits for `sys.boot_completed=1`, and writes
diagnostics under `build/local-avd-start/<run-id>/` if the emulator exits before
adb device discovery. For connected-test review evidence, keep it open in a
dedicated terminal:

```bash
AVD_HOLD=1 scripts/start-local-avd.sh
```

Then run `:app:connectedDebugAndroidTest` from another terminal. If the emulator
exits after boot, the held helper records the failure in the same run directory.

To run the same command manually:

```bash
"$EMULATOR" -avd test \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -gpu swiftshader_indirect \
  -no-snapshot-load \
  -no-snapshot-save
```

Wait for boot if you start it manually:

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
  walkthrough journeys, usage/jobs/agent fixture checks, and the APK pre-release gate.
- `bootstrap-ready`: builds `pocketshell-test:bootstrap-ready`, maps host port
  `2230`, and contains the shared bootstrap base plus `pocketshell` and
  `systemctl` shims in `/usr/local/bin`; the
  `systemctl` shim reports `pocketshell-jobs.service` as active and enabled. This
  represents a host where server tools and the user daemon are already ready.
  Used by `HostBootstrapScenarioSuiteTest#ready`.
- `bootstrap-uv-install`: builds `pocketshell-test:bootstrap-uv-install`, maps
  host port `2231`, and contains the shared bootstrap base plus `uv` and
  `systemctl` shims in `/usr/local/bin`; `pocketshell` starts absent and can be copied into
  `/home/testuser/.local/bin` by the `uv tool install <package>` shim. The
  `systemctl` shim reports `pocketshell-jobs.service` as active and enabled. Used
  by `HostBootstrapScenarioSuiteTest#uvInstall`.
- `bootstrap-uv-upgrade`: builds `pocketshell-test:bootstrap-uv-upgrade`, maps
  host port `2236`, and contains a stale `pocketshell` fixture plus `uv` and
  `systemctl` shims in `/usr/local/bin`; `uv tool install --upgrade pocketshell`
  refreshes the fixture version under `/home/testuser/.local/bin`. Used by
  `HostBootstrapScenarioSuiteTest#uvUpgrade`.
- `bootstrap-unsupported`: builds `pocketshell-test:bootstrap-unsupported`,
  maps host port `2232`, and contains only the shared bootstrap base. No
  `pocketshell`, `uv`, or `systemctl` shim is
  installed on `PATH`; the fixture daemon state is inactive and disabled. This
  represents a host with missing tools and no supported installer. Used by
  `HostBootstrapScenarioSuiteTest#unsupported`.
- `bootstrap-daemon-disabled`: builds
  `pocketshell-test:bootstrap-daemon-disabled`, maps host port `2233`, and
  contains the shared bootstrap base plus `pocketshell` and `systemctl` shims in
  `/usr/local/bin`; the `systemctl` shim reports `pocketshell-jobs.service` as active but disabled. This
  represents a host where tools are present but `pocketshell-jobs.service` is
  disabled. Used by `HostBootstrapScenarioSuiteTest#daemonDisabled`.
- `bootstrap-user-local-path`: builds
  `pocketshell-test:bootstrap-user-local-path`, maps host port `2234`, and
  contains the shared bootstrap base plus a `pocketshell` shim in
  `/home/testuser/.local/bin` and a `systemctl`
  shim in `/usr/local/bin`; the `systemctl` shim reports
  `pocketshell-jobs.service` as active and enabled. This represents a host where
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
for port in 2224 2230 2231 2232 2233 2234 2235 2236; do
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
- bootstrap profiles on `2230` through `2236`.

Do not run `sshd` and `agents` together without changing one of their host
ports because both claim `2222`. The standard safe parallel sets are:

- Android connected smoke plus manual tmux: `agents` + `tmux`.
- Bootstrap setup suite: all seven `bootstrap-*` services.
- JVM Testcontainers suites plus any compose service: safe by default because
  Testcontainers uses ephemeral host ports.

If a new compose profile must run in parallel, add a new explicit host port in
`tests/docker/docker-compose.yml`; do not reuse `2222`, `2224`, or `2230`
through `2236`. Update the service list above, any Android fixture constants,
and the host-side sanity command in this runbook in the same change.

## Standard Commands

Start the Docker agent target used by normal connected Android smoke:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
```

### Container readiness via compose health checks (issue #150)

Every service in `tests/docker/docker-compose.yml` and
`tests/docker/real-agent/compose.yml` declares a `healthcheck:` block
that runs `ssh -o ConnectTimeout=2 -i /root/test_key testuser@localhost
true` inside the container. Reaching `healthy` proves SSH and key
authentication are working end-to-end, so callers should wait on
`docker inspect --format='{{.State.Health.Status}}'` instead of
polling SSH from the host with a retry-sleep loop.

Inline check:

```bash
docker inspect --format='{{.Name}}: {{.State.Health.Status}}' \
  $(docker compose -f tests/docker/docker-compose.yml ps -q)
```

Reusable shell helper (used by every harness script under `scripts/`):

```bash
source tests/docker/lib/wait-for-healthy.sh
wait_for_container_healthy tests/docker/docker-compose.yml agents \
  /tmp/agent-health.log 60
```

Healthcheck shape (identical for every service):

```yaml
healthcheck:
  test: ["CMD-SHELL", "ssh -o BatchMode=yes -o ConnectTimeout=2 ..."]
  interval: 2s
  timeout: 5s
  retries: 10
  start_period: 5s
```

A warm image usually settles to `healthy` within 1–6 s of `compose up`.
Existing host-side SSH retry loops in `scripts/*.sh` and the CI
workflow have been migrated to consume health status first and then
run a single follow-up SSH probe only to record the same
tool-availability evidence reviewers look for (`tmux -V`,
`command -v tmuxctl heru …`).

Host-side SSH sanity check (now optional — health status already
proves SSH + auth work):

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

Run the fast local phone-walkthrough reproduction harness on an already-booted
emulator:

```bash
scripts/phone-walkthrough.sh terminal-lab
scripts/phone-walkthrough.sh visual-audit
scripts/phone-walkthrough.sh setup-detection
scripts/phone-walkthrough.sh setup-detection:ready
```

The harness starts/verifies the Docker `agents` target, checks emulator boot
state with the explicit `adb` path, runs only the selected scenario, and writes
screenshots, timings, logcat, instrumentation output, Docker logs, command
logs, and crash diagnostics under `build/phone-walkthrough/<run-id>/`.
`setup-detection` starts the `bootstrap-*` services on ports `2230` through
`2236`; use `setup-detection:<profile>` to run one profile.
`visual-audit` writes normalized reviewer screenshots under
`build/phone-walkthrough/<run-id>/screenshots/visual-audit/` and raw pulled device
output under
`build/phone-walkthrough/<run-id>/device-artifacts/walkthrough-visual-pass/`.

Run the deterministic terminal reviewer workbench:

```bash
scripts/terminal-workbench.sh
```

Use a stable run ID when the artifact path will be cited in an issue comment:

```bash
RUN_ID=issue-<number>-review scripts/terminal-workbench.sh
```

The deterministic workbench starts or verifies the emulator, starts the Docker
`agents` service on host port `2222`, waits for SSH readiness, runs
`TerminalLabDockerTest#terminalWorkbenchKeepsDockerShellOpenForVisualIteration`,
and writes:

- `build/terminal-workbench/<run-id>/artifacts/terminal-lab/*-viewport.png`
  direct terminal viewport renders. These are authoritative for terminal
  content.
- `build/terminal-workbench/<run-id>/artifacts/terminal-lab/*-visible-terminal.txt`
  visible terminal text from the terminal emulator.
- `build/terminal-workbench/<run-id>/artifacts/terminal-lab/*-summary.txt` and
  `build/terminal-workbench/<run-id>/artifact-summary.txt` with capture policy,
  viewport hashes, visible-character counts, and advisory full-device/window
  screenshot status.
- `build/terminal-workbench/<run-id>/artifacts/terminal-lab/timings.txt`.
- `build/terminal-workbench/<run-id>/07-run-workbench.log`,
  `docker-ssh-readiness.log`, `docker-agents.log`, `logcat.txt`, and
  `final-screen.png`.

Full-device or final-screen screenshots are advisory for terminal content unless
the summary proves they agree with the direct `*-viewport.png` render and
visible terminal text. Reviewers should reject stale, blank, contradictory, or
missing authoritative viewport/text/timing/log artifacts.

Run the real-agent CLI workbench only when the issue needs real provider CLI
rendering rather than deterministic shims:

```bash
REAL_AGENTS=1 scripts/terminal-workbench.sh
```

This switches to `tests/docker/real-agent/compose.yml`, starts the
`real-agents` service, uses SSH port `2240`, and runs
`TerminalLabDockerTest#terminalWorkbenchCapturesRealAgentCliScreens`. The same
artifact authority rules apply: direct `*-viewport.png` terminal renders and
visible terminal text are authoritative; full-device screenshots are advisory.
The script fails if authoritative viewport PNGs, visible-terminal sidecars,
timings, summaries, PTY sizing evidence, or expected real-agent CLI screen text
are missing. It also verifies every summary hash against the pulled PNG and
rejects duplicate non-hold viewport hashes as stale capture evidence.

For terminal-focused release confidence, run the guarded release validation
with the optional terminal gate enabled:

```bash
TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh
```

That command runs the normal pre-release confidence gate first, then runs the
real-agent terminal workbench from the emulator over SSH into Docker, validates
the artifact bundle, and continues with the standard phone-walkthrough and visual
audit release evidence. The terminal gate is intentionally manual/optional and
is not part of every local or CI release validation run unless
`TERMINAL_RELEASE_GATE=1` is set or the matching GitHub Actions workflow input
is enabled. Use it before release candidates that include terminal input,
viewport rendering, SSH/PTY, or agent CLI usability changes.

When the release also needs long-running terminal/tmux stability evidence, add
the opt-in 10-minute hold:

```bash
TERMINAL_RELEASE_GATE=1 LONG_RUNNING_TEST=1 scripts/release-emulator-validation.sh
```

The long-running hold remains optional for unrelated small releases. Link
`build/long-running-session/<run-id>-long-running/` from the release issue or
PR, and inspect
`artifacts/long-running-session/long-running-summary.txt` for `tick_count=6`,
`reconnect_events=0`, memory growth below the recorded 50 MB budget, and a
final visible transcript that still contains the last tick marker.

For release tagging, use the guarded emulator-only wrapper from clean pushed
`main`:

```bash
scripts/release-emulator-validation.sh
scripts/push-release-tag.sh --visual-audit-inspected v0.2.1 build/release-emulator-validation/<run-id>/summary.md
```

The wrapper runs the pre-release confidence gate, terminal-lab phone walkthrough,
tmux existing-session phone walkthrough, the setup-detection matrix, and visual
screenshot capture. Attach or link every artifact directory listed in
`build/release-emulator-validation/<run-id>/summary.md` in the release issue
and tag notes. Pass `--visual-audit-inspected` only after inspecting the
visual-audit screenshots.

When local emulator capacity is unavailable, run the same validation manually
from GitHub Actions: Actions -> Release Emulator Validation -> Run workflow.
Choose the release branch or `main`; optionally provide a `run_id`. Read the
job summary first, then download the
`release-emulator-validation-<run-id>` artifact for logs, screenshots, and the
release summary. The tested debug APK is included inside that artifact at
`release-emulator-validation/<run-id>/app-debug.apk`; locally, the same file is
written under `build/release-emulator-validation/<run-id>/app-debug.apk`.
Inspect the visual-audit screenshots before using the artifact as release
evidence. The manual workflow does not push the tag and does not relax the
stable-main tag rule.

## APK Pre-Release Gate

Before pushing a version tag, run the local confidence gate from
the repository root:

```bash
scripts/pre-release-confidence-gate.sh
```

The gate uses the explicit SDK paths documented in [agents.md](../agents.md):

- `adb`: `/home/alexey/Android/Sdk/platform-tools/adb`
- `emulator`: `/home/alexey/Android/Sdk/emulator/emulator`
- AVD: `test`

It writes timestamped output under
`build/pre-release-confidence-gate/<run-id>/`. Each step gets its own log file
and the script exits at the first failed step with the log directory printed.
Each pass or fail also persists
`build/pre-release-confidence-gate/<run-id>/summary.txt` with the commit SHA,
run directory, APK path, emulator serial when available, Docker target,
step statuses/log paths, focused selector statuses, final install status, and
the final result. On failure, use the summary first because it records the
failing step and focused instrumentation diagnostics/logcat paths when present.
Unless `GRADLE_USER_HOME` is already set, the gate uses
`build/pre-release-confidence-gate/gradle-home` for its Gradle cache and daemon
registry. This isolates the release gate from unrelated local Gradle daemon
stops and generated-output churn in other worktrees. Gate Gradle invocations
also pass `--no-build-cache`, `--no-parallel`, and `--max-workers=2` to avoid
cache-packing races, generated-source ordering races, and resource
oversubscription when other local Gradle jobs are active.

By default, the script copies the current working tree to
`build/pre-release-confidence-gate/<run-id>/worktree` and re-execs from that
copy, excluding `.git`, `.gradle`, and `build` directories. This protects the
gate from unrelated local work mutating shared `app/build` or module build
outputs while still validating the current source files. Set
`GATE_ISOLATED_WORKTREE=0` only when the checkout is idle and direct in-place
execution is intentional.

The fast pre-release gate does all of the following:

1. Runs normal compile/unit checks. In a fresh isolated Gradle home, the gate
   first runs focused app KSP/Hilt generated-source tasks for debug, release,
   androidTest, and unit-test variants so lint has deterministic generated
   source inputs, then runs
   `./gradlew --no-daemon --no-build-cache --no-parallel --max-workers=2 assembleDebug check -x lint -x lintDebug --stacktrace`.
   Lint is intentionally excluded from this local pre-release gate so unrelated
   dirty-worktree lint findings do not block the install and focused
   instrumentation checks; run lint separately from a clean checkout before
   release.
2. Starts or verifies the deterministic Docker `agents` target:
   `docker compose -f tests/docker/docker-compose.yml up -d --build agents`.
3. SSHes into the Docker target on `127.0.0.1:2222` and verifies the expected
   shims: `claude`, `codex`, `opencode`, `heru`, `agent-log-explorer`,
   `tmuxctl`, and `uv`.
4. Verifies emulator readiness with the explicit `adb` path and
   `sys.boot_completed`.
5. Runs focused connected walkthrough journeys:
   - `:shared:core-terminal:connectedDebugAndroidTest` for keyboard/input.
   - Builds the app and Android test APKs, runs an explicit cold-reset setup
     for deterministic walkthrough tests, clears existing app/test package data
     once, replace-installs both APKs once with explicit-path `adb install -r`,
     stops any restored app/test process before each focused selector, then runs direct
     `adb shell am instrument -e class <selector>` invocations covering both
     `PromptComposerSmokeTest` methods,
     `SnippetTerminalE2eTest`, both `InlineDictationUiTest` methods,
     `VoiceCommandPlannerE2eTest`, and both `EmulatorDockerSshSmokeTest`
     methods.
6. Rebuilds `app/build/outputs/apk/debug/app-debug.apk`.
7. Runs the separate data-preserving update gate via
   `scripts/install-update-apk.sh app/build/outputs/apk/debug/app-debug.apk`.
   That helper runs only `adb install -r` and never clears app data or
   uninstalls the package.

Before the focused app instrumentation phase, the gate force-stops
`com.pocketshell.app.test` and `com.pocketshell.app`, clears existing package
data if either package is already installed, and waits for the package-manager
handler queues to go idle. This is the cold-reset walkthrough path, not the user
update path. It then replace-installs the app/test APKs once and waits for
package-manager idle plus a stable package path check before starting
instrumentation. During that stability window it also watches logcat for delayed
PocketShell package removal broadcasts left over from earlier emulator work; if
one appears, it waits for package-manager idle, reinstalls both APKs, and repeats
the stability check before instrumentation. Uninstall is only used as a logged
fallback when replace install reports an incompatible existing package in this
cold-reset path, and that fallback also waits for package-manager idle before
retrying install. Before
each focused selector, the gate force-stops the app/test packages, waits until
no PocketShell process is visible, verifies both packages report `stopped=true`,
and holds that state through a short settle window. If prior instrumentation
teardown starts the app/test package again during that settle window, the gate
repeats the force-stop/idle/settle cycle up to three times. The focused tests
run as direct instrumentation invocations without deleting or reinstalling packages between selectors, which
keeps delayed `deletePackageX`, package replacement force-stops, restored
tasks, the quiesce force-stop itself, and previous-run instrumentation teardown
out of the running selector window.

Each focused app instrumentation invocation clears logcat immediately before
running. If Android reports `Process crashed` with no app exception and logcat
shows the app was externally force-stopped while instrumentation was running,
the selector is retried once after another package-manager idle wait. If the
runner exits non-zero, does not report `INSTRUMENTATION_CODE: -1`, or the retry
also fails, the step log prints the instrumentation output, filtered crash
context from logcat, recent app-crash dropbox entries, and the tombstone
listing. The run directory also keeps a bounded full logcat artifact for the
failed focused invocation.

By default, the script expects an already booted emulator. For local
release-gate evidence, start the shared `test` AVD with the helper in hold mode
from a dedicated terminal before running the gate:

```bash
AVD_HOLD=1 RUN_ID=pre-release-hold scripts/start-local-avd.sh
```

Leave that terminal open while `scripts/pre-release-confidence-gate.sh` or any
focused `connectedDebugAndroidTest` command runs in another terminal. Hold mode
keeps the startup helper attached to the emulator and records diagnostics under
`build/local-avd-start/pre-release-hold/` if the AVD exits while Gradle is still
collecting connected-test evidence.

If you need to start the AVD manually instead of using the helper, use the same
flag set:

```bash
"$EMULATOR" -avd test \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -gpu swiftshader_indirect \
  -no-snapshot-load \
  -no-snapshot-save
```

The script accepts these environment overrides when a local machine differs:

```bash
ANDROID_SDK=/path/to/sdk
ADB=/path/to/adb
EMULATOR=/path/to/emulator
AVD_NAME=test
LOG_ROOT=build/pre-release-confidence-gate
GRADLE_USER_HOME=/tmp/pocketshell-gate-gradle-home
GRADLE_FLAGS="--no-daemon --no-build-cache --no-parallel --max-workers=2"
GATE_ISOLATED_WORKTREE=1
TEST_APK_DIR=app/build/outputs/apk/androidTest/debug
TEST_APK_PATH="$TEST_APK_DIR/app-debug-androidTest.apk"
export ANDROID_SDK ADB EMULATOR AVD_NAME LOG_ROOT GRADLE_USER_HOME GRADLE_FLAGS GATE_ISOLATED_WORKTREE TEST_APK_PATH
scripts/pre-release-confidence-gate.sh
```

Slower opt-in suites are not part of the fast APK pre-release gate:

- Full connected Android sweep:
  `./gradlew --no-daemon connectedDebugAndroidTest --stacktrace`. Run this
  before a public release candidate or when shared instrumentation fixtures
  change.
- Bootstrap/setup scenarios:
  `HostBootstrapScenarioSuiteTest` with
  `pocketshellBootstrapScenarios=true`. Run this before a release that changes
  host setup, tool detection, daemon enablement, `uv` install behavior, or
  SSH environment/PATH handling.
- Manual visual audit and screenshots. Run this for visual or navigation
  changes, and store screenshots beside the issue/release evidence.

Start all bootstrap profiles:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build \
  bootstrap-ready \
  bootstrap-uv-install \
  bootstrap-uv-upgrade \
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
