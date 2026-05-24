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

### Manual visual validation (reviewer evidence)

For visual or user-facing Android changes, the implementer should provide the
commands they ran and any screenshots or artifact paths they produced. Reviewer
approval owns the final evidence: the reviewer reproduces the relevant emulator
flow, inspects the visible result, and records the command, artifact path, Docker
involvement when relevant, and observed result in the issue.

1. Start from the latest implementer status for the scoped issue
2. `./gradlew installDebug`, or use the issue's documented dogfood command
3. Compare side-by-side with `docs/mockups/<screen>.html` open in Chrome at
   412 × 915
4. Capture reviewer evidence:
   `adb exec-out screencap -p > /tmp/screen.png`
5. Post the command, screenshot or artifact path, and observed result in the
   reviewer issue comment

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
| Manual smoke | Emulator + Docker | Issue-based implementer/reviewer flow, with reviewer emulator evidence before approval |

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

For terminal reviewer approval, use the stricter terminal workbench commands in
[docker-emulator-runbook.md](docker-emulator-runbook.md#standard-commands) and
the artifact rejection checklist in [process.md](../process.md#terminal-artifact-review).
Direct terminal viewport renders plus visible terminal text are authoritative;
full-device screenshots are advisory for terminal content unless the run summary
proves they are reliable.

The host setup matrix is available through the same harness. It starts the
bootstrap Docker services on ports `2230` through `2235`, drives the emulator UI
for each profile, and stores per-profile screenshots, UI assertion output,
remote probes, timings, logcat, Docker logs, and crash diagnostics:

```bash
scripts/phone-dogfood.sh setup-detection
scripts/phone-dogfood.sh setup-detection:uv-install
```

For release visual review without a physical phone, run:

```bash
scripts/phone-dogfood.sh visual-audit
```

This runs the Docker-backed visual screenshot instrumentation and the composer
state renderer, then writes reviewer-facing PNGs under
`build/phone-dogfood/<run-id>/screenshots/visual-audit/`. The normalized set is
`01-host-list.png`, `02-host-setup-session-picker.png`,
`03-terminal-session-input-controls.png`, `04-snippets.png`,
`05-composer-draft.png`, `06-composer-recording.png`, and
`07-composer-transcribing.png`; raw pulled device output remains under
`build/phone-dogfood/<run-id>/device-artifacts/dogfood-visual-pass/`.

This differs from CI and the pre-release confidence gate: it is a local
reproduction loop for one dogfood journey and reviewer-visible artifacts. It
does not replace unit tests, connected CI, or the slower release gate. A
physical phone is not required for basic release confidence; emulator + Docker
evidence is the release blocker, and phone testing is final user acceptance.

### APK dogfood pre-release confidence gate

Before tagging an APK for dogfood testing, run the documented local gate from
the repository root:

```bash
scripts/pre-release-confidence-gate.sh
```

For an actual release tag, the confidence gate is only the first step. Run the
guarded emulator-only release validation from clean pushed `main`, after
confirming `HEAD == origin/main`:

```bash
scripts/release-emulator-validation.sh
```

That wrapper requires `HEAD == origin/main`, then runs the confidence gate,
`scripts/phone-dogfood.sh terminal-lab`,
`scripts/phone-dogfood.sh tmux-existing-session`,
`scripts/phone-dogfood.sh setup-detection`, and visual-audit screenshot
capture. It writes `build/release-emulator-validation/<run-id>/summary.md`
with the artifact directories that must be attached or linked in the issue and
tag notes. Push the tag only through:

```bash
scripts/push-release-tag.sh --visual-audit-inspected <tag> build/release-emulator-validation/<run-id>/summary.md
```

Use `--visual-audit-inspected` only after reviewing the visual-audit
screenshots. Physical phone testing is final user acceptance only; it does not
replace the emulator/Docker release blockers above.

The same validation can be run manually from GitHub Actions when local emulator
capacity is unavailable: Actions -> Release Emulator Validation -> Run
workflow. Select `main` or the already-reviewed release branch, optionally set
`run_id`, wait for the job summary, download
`release-emulator-validation-<run-id>`, and inspect the visual-audit screenshots
before using the artifact as release evidence. This workflow produces evidence
only; it does not push the tag and does not relax the stable-main tag rule.

This combines the normal compile/unit check, deterministic Docker `agents`
target verification, explicit-path emulator readiness checks, focused connected
dogfood journeys for keyboard/input, snippets/composer, dictation, planner, and
Docker SSH/tmux smoke, then builds and installs
`app/build/outputs/apk/debug/app-debug.apk` on the emulator. Logs are written
under `build/pre-release-confidence-gate/<run-id>/`. By default the gate also
uses `build/pre-release-confidence-gate/gradle-home` as an isolated
`GRADLE_USER_HOME`, so unrelated local Gradle daemon/cache activity cannot stop
or corrupt the scripted run. Gate Gradle invocations use `--no-build-cache`,
`--no-parallel`, and `--max-workers=2` to avoid cache-packing,
generated-source races, and local resource oversubscription. The
compile/check phase pre-generates focused app KSP/Hilt sources for debug,
release, androidTest, and unit-test variants before `check`, which keeps lint
from depending on stale generated files in the checkout without building a full
release APK inside the fast gate. Lint is excluded from this local dogfood gate
so unrelated dirty-worktree lint issues cannot prevent the install and focused
instrumentation checks from running; run lint separately before release when the
checkout is clean.
By default the gate also copies the current working tree to
`build/pre-release-confidence-gate/<run-id>/worktree` and re-execs there,
excluding `.git`, `.gradle`, and `build` directories. That keeps shared
`app/build` output from unrelated local work out of the release gate while still
testing the current source files. Set `GATE_ISOLATED_WORKTREE=0` only when the
checkout is otherwise idle.
Every run also writes
`build/pre-release-confidence-gate/<run-id>/summary.txt`, including the commit,
run directory, APK path, emulator serial when available, Docker target,
step-by-step status/log paths, focused selector status, final install status,
and the final pass/fail result. On failures, start review from that summary:
it names the failing step and, for focused instrumentation failures, the
diagnostics and bounded logcat artifact paths.

The focused app dogfood selectors run through direct
`adb shell am instrument -e class <selector>` invocations after one app/test
package reset and one explicit app/test APK install for the whole focused
phase. This makes the gate repeatable on a reused emulator, avoids stale Gradle
connected-test runner arguments, and keeps package deletion/replacement work out
of the selector window.
The reset clears existing package data without uninstalling in the normal path,
then replace-installs both APKs and waits for package-manager handlers to go
idle. Uninstall is only used as a logged fallback for incompatible existing
packages. After install, the gate watches a stability window for delayed
PocketShell package removal broadcasts from earlier emulator work and reinstalls
before instrumentation if one appears. The gate then force-stops app/test
packages before each selector and waits until no PocketShell process is running
and both packages report
`stopped=true`, followed by a short stable settle window. If Android restarts
the app/test package during that settle window due prior instrumentation
teardown, the gate repeats the force-stop/idle/settle cycle up to three times.
That keeps delayed
package deletion, the quiesce force-stop itself, Android's normal `start instr`
force-stop, prior selector teardown, and any restored task cleanup from killing
the running instrumentation process. Each focused invocation clears logcat; if
Android reports a process-crashed instrumentation result with no app exception
and logcat shows the app was externally force-stopped while instrumentation was
running, the selector is retried once after another package-manager idle wait.
If the retry also fails, or the failure is not that exact transient shape, the
gate keeps the final failure. If
instrumentation crashes or reports a non-success code, the step log includes
filtered crash context and points to the bounded full logcat artifact in the same
run directory.

See [docker-emulator-runbook.md](docker-emulator-runbook.md#apk-dogfood-pre-release-gate)
for the exact steps, SDK paths, focused test list, APK location, and slower
opt-in suites that remain outside the fast gate.

### Opt-in end-to-end scenario suites

Some workflows need real app UI plus multiple remote-host states, but are too
slow and stateful for every issue. These live as opt-in scenario suites:
automated, repeatable, and documented, but run manually when the issue scope
requires them, before releases, or while investigating regressions.

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

## Process verification checklist

PocketShell uses the issue-based implementer/reviewer loop in
[process.md](../process.md). After reviewer `APPROVED` and before committing or
pushing an approved issue, the orchestrator follows the
[process verification checklist](../process.md#verification-checklist).
For testing-specific work, the minimum local checks are:

1. `./gradlew assembleDebug` — does it build?
2. `./gradlew check` — do unit tests pass?
3. For UI changes: install on emulator, eyeball against the matching mockup
4. For SSH / tmux / agent / usage changes: run the relevant Testcontainers
   integration test
5. For user-facing Android, terminal/input, SSH/tmux/agent, setup, or
   release-gate changes: verify the reviewer-owned emulator evidence includes
   commands, logs/screenshots, Docker involvement when relevant, and observed
   results

[agents.md](../agents.md) is only the quick local agent rule sheet.
