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
scripts/start-local-avd.sh
./gradlew installDebug
adb shell am start -n com.pocketshell.app/.MainActivity
```

The local startup helper defaults to `AVD_NAME=test` and the headless review
flags used by the pre-release gate:
`-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
-no-snapshot-load -no-snapshot-save`. It records
`adb devices`, `getprop`, accelerator status, AVD config, process matching, and
the emulator log under `build/local-avd-start/<run-id>/`, which is the first
artifact to attach when an AVD exits before adb sees a device.

For `connectedDebugAndroidTest` evidence, run the helper with
`AVD_HOLD=1` in a dedicated terminal and leave it open while Gradle runs in
another terminal. This keeps the startup monitor attached and records a clear
failure if the emulator exits after initially reporting boot complete.

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
2. `./gradlew installDebug`, or use the issue's documented walkthrough command
3. Compare side-by-side with `docs/mockups/<screen>.html` open in Chrome at
   412 × 915
4. Capture reviewer evidence:
   `adb exec-out screencap -p > /tmp/screen.png`
5. Post the command, screenshot or artifact path, and observed result in the
   reviewer issue comment

### Device tap capture and replay

For physical-phone debugging sessions that need to be handed off for replay,
record the screen, raw touchscreen trace, and logcat together:

```bash
ANDROID_SERIAL=<phone-or-emulator-serial> scripts/capture-device-session.sh <run-id>
```

The capture writes `screen.mp4`, `getevent-touchscreen.txt`,
`logcat.txt`, `logcat-final-dump.txt`, `getevent-lp.txt`, `getprop.txt`,
and `metadata.env` under `build/device-sessions/<run-id>/`. The script
auto-detects the touchscreen from `adb shell getevent -lp` by looking for a
multitouch input device with X/Y absolute axes, preferring direct devices when
Android reports that input property. It records only that device's
`getevent -lt` stream, so other keys and sensors are ignored.

Stop the capture with Enter, or use `CAPTURE_SECONDS=<seconds>` for a bounded
smoke run:

```bash
ANDROID_SERIAL=<serial> CAPTURE_SECONDS=5 scripts/capture-device-session.sh tap-smoke
```

Replay the same raw touch events with their original inter-event timing:

```bash
ANDROID_SERIAL=<serial> scripts/replay-device-session.sh <run-id>
```

Replay regenerates `build/device-sessions/<run-id>/replay-sendevent.sh`,
pushes it to `/data/local/tmp/`, runs it with `adb shell sh`, and writes
`replay.log` plus `replay-summary.txt`. The replay script substitutes the
currently auto-detected touchscreen path for the captured `/dev/input/eventN`,
so changed event numbering does not break the run.

Assumptions for deterministic replay:

- Use the same phone or emulator profile, orientation, and display size as the
  capture. The Pixel 7a debug target is portrait `1080x2400`; the scripts store
  `wm size`, `wm density`, and the touchscreen ABS X/Y min/max in metadata and
  warn if the current `wm size` differs at replay time.
- Raw touchscreen units are replayed unchanged through `sendevent`; no
  coordinate scaling is attempted. This is deliberate for Pixel 7a sessions,
  where the goal is "record this exact phone session, replay it later."
- If multiple adb devices are connected, set `ANDROID_SERIAL` or `ADB_SERIAL`.
- During connected validation, acquire the shared local AVD lock around the
  full adb sequence, for example:

```bash
flock /home/alexey/git/pocketshell/build/.avd-lock -c \
  'cd /home/alexey/git/pocketshell/.worktrees/issue-275 && ANDROID_SERIAL=<serial> CAPTURE_SECONDS=5 scripts/capture-device-session.sh tap-smoke && ANDROID_SERIAL=<serial> scripts/replay-device-session.sh tap-smoke'
```

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
UI, sends walkthrough shell commands through the prompt composer, verifies visible
terminal transcript output for `ls`, `pwd`, and tmux, verifies the remote
artifacts, and cleans up the remote temp directory and tmux session.

### Local phone walkthrough reproduction

For fast visual feedback without installing an APK on a physical phone, run the
local phone-walkthrough harness against an already-booted emulator:

```bash
scripts/phone-walkthrough.sh terminal-lab
```

The harness verifies the explicit SDK paths from `agents.md`, fails clearly if
no booted emulator is connected, starts/verifies the Docker `agents` SSH
fixture, builds and installs the debug app/test APKs, runs only the selected
scenario, and writes one artifact bundle under
`build/phone-walkthrough/<run-id>/`.
By default it uses `build/phone-walkthrough/gradle-home` as an isolated
`GRADLE_USER_HOME`, disables the Gradle build cache and parallel execution for
the APK build, and removes the app module's generated build output directory so
stale KSP/Hilt/Javac transaction state cannot be reused. Set
`PHONE_WALKTHROUGH_CLEAN_GENERATED=0` only when investigating those generated
outputs directly.

The first supported scenario is `terminal-lab`. It opens the isolated terminal
lab activity, connects from the emulator to Docker SSH, sends commands through
the terminal input path, captures named screenshots, records transition timing,
and collects bounded logcat, instrumentation output, Docker logs, command
timings, and crash diagnostics. Use `BUILD_APKS=0` to reuse existing debug APKs
when iterating on harness behavior:

```bash
BUILD_APKS=0 scripts/phone-walkthrough.sh terminal-lab
```

For terminal reviewer approval, use the stricter terminal workbench commands in
[docker-emulator-runbook.md](docker-emulator-runbook.md#standard-commands) and
the artifact rejection checklist in [process.md](../process.md#terminal-artifact-review).
Direct terminal viewport renders plus visible terminal text are authoritative;
full-device screenshots are advisory for terminal content unless the run summary
proves they are reliable.
The workbench deletes stale pulled artifacts before each run, verifies SSH,
terminal command input, PTY sizing, direct viewport renders, visible terminal
sidecars, timings, and summary hashes, and fails on missing, blank, duplicate
non-hold, or contradictory authoritative terminal evidence. Set
`REAL_AGENTS=1` when the issue requires real interactive agent CLI screens.

The host setup matrix is available through the same harness. It starts the
bootstrap Docker services on ports `2230` through `2236`, drives the emulator UI
for each profile, and stores per-profile screenshots, UI assertion output,
remote probes, timings, logcat, Docker logs, and crash diagnostics:

```bash
scripts/phone-walkthrough.sh setup-detection
scripts/phone-walkthrough.sh setup-detection:uv-install
```

For release visual review without a physical phone, run:

```bash
scripts/phone-walkthrough.sh visual-audit
```

This runs the Docker-backed visual screenshot instrumentation and the composer
state renderer, then writes reviewer-facing PNGs under
`build/phone-walkthrough/<run-id>/screenshots/visual-audit/`. The normalized set is
`01-host-list.png`, `02-host-setup-folder-list.png`,
`03-terminal-session-input-controls.png`, `04-snippets.png`,
`05b-composer-idle-draft.png`, `06-composer-recording.png`, and
`07-composer-transcribing.png`; raw pulled device output remains under
`build/phone-walkthrough/<run-id>/device-artifacts/walkthrough-visual-pass/`.

This differs from CI and the pre-release confidence gate: it is a local
reproduction loop for one walkthrough journey and reviewer-visible artifacts. It
does not replace unit tests, connected CI, or the slower release gate. A
physical phone is not required for basic release confidence; emulator + Docker
evidence is the release blocker, and phone testing is final user acceptance.

### APK pre-release confidence gate

Before tagging a release APK, run the documented local gate from
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
`scripts/phone-walkthrough.sh terminal-lab`,
`scripts/phone-walkthrough.sh tmux-existing-session`,
`scripts/phone-walkthrough.sh setup-detection`, and visual-audit screenshot
capture. It writes `build/release-emulator-validation/<run-id>/summary.md`
with the artifact directories that must be attached or linked in the issue and
tag notes. Push the tag only through:

```bash
scripts/push-release-tag.sh --visual-audit-inspected <tag> build/release-emulator-validation/<run-id>/summary.md
```

Use `--visual-audit-inspected` only after reviewing the visual-audit
screenshots. Physical phone testing is final user acceptance only; it does not
replace the emulator/Docker release blockers above.

#### AVD lock for parallel-worktree contention

The release-gate scripts that touch the shared local Android emulator
(`scripts/release-emulator-validation.sh`,
`scripts/pre-release-confidence-gate.sh`,
`scripts/phone-walkthrough.sh`,
`scripts/terminal-workbench.sh`, and
`scripts/release-terminal-gate.sh`) each acquire an exclusive `flock` on
`build/.avd-lock` (relative to the repo root) before installing APKs or
running instrumentation. If a sibling worktree is already running an
emulator-touching gate, the second invocation prints
`Another emulator-touching script holds the AVD lock; waiting...` and
blocks until the first one exits. The lock is released automatically when
the holding script exits (the open file descriptor closes).

Individual `./gradlew :app:connectedDebugAndroidTest` invocations from
implementer or reviewer worktrees do NOT take this lock: they are cheap
to retry on collision and locking them would serialise all parallel
worktree work. The lock is scoped to the release-gate scripts because
their long sequential workflows cannot tolerate a sibling `adb install`
mid-test (see issue #182).

To override the lock-file path (rare; only useful when chaining gates by
hand under a custom build directory):

```bash
POCKETSHELL_AVD_LOCK_FILE=/tmp/my-avd-lock scripts/release-emulator-validation.sh
```

When one gate script invokes another (for example,
`release-emulator-validation.sh` runs `pre-release-confidence-gate.sh`,
`phone-walkthrough.sh`, and `terminal-workbench.sh` in sequence) the inner
scripts inherit `POCKETSHELL_AVD_LOCK_ACQUIRED=1` from the outer one and
skip re-acquiring; the outer lock holds for the entire chain.

Terminal-heavy release candidates can opt into the slower real-agent terminal
release gate:

```bash
TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh
```

The optional step runs after the normal pre-release confidence gate and before
the rest of the release evidence. It starts
`tests/docker/real-agent/compose.yml`, SSHes from the emulator into Docker on
port `2240`, drives at least one real interactive agent CLI screen through
`TerminalLabDockerTest`, validates the authoritative viewport and visible-text
artifacts, and writes
`build/terminal-workbench/<run-id>-terminal-release/artifact-summary.txt`. It
is manual/optional unless explicitly enabled through the environment or the
GitHub Actions workflow input.

For terminal/tmux-heavy releases where short connected tests are not enough
evidence, add the opt-in 10-minute stability hold:

```bash
TERMINAL_RELEASE_GATE=1 LONG_RUNNING_TEST=1 scripts/release-emulator-validation.sh
```

This remains optional for unrelated small releases. The long-running hold writes
its artifact bundle under
`build/long-running-session/<run-id>-long-running/`; the primary file to inspect
and link is
`build/long-running-session/<run-id>-long-running/artifacts/long-running-session/long-running-summary.txt`.
Treat the hold as acceptable only when the wrapper passes, the summary reports
`tick_count=6`, `reconnect_events=0`, `memory_growth_kb` under the recorded
50 MB budget, and the final visible transcript still contains the last tick.
Failures should be evaluated from `long-running-summary.txt` first, then the
same directory's `long-running-logcat-tail.txt`,
`long-running-visible-terminal.txt`, `instrumentation.log`, and
`docker-agents.log`.

#### Real-agent CLI interaction test (issue #146)

When `TERMINAL_RELEASE_GATE=1` is set, the release validation also runs
`RealAgentReleaseGateTest`
(`app/src/androidTest/java/com/pocketshell/app/proof/RealAgentReleaseGateTest.kt`)
against the same `tests/docker/real-agent/compose.yml` fixture. The test:

- Connects through the real PocketShell app UI to `testuser@10.0.2.2:2240`,
  attaches a tmux pane, and types commands through the same `TerminalView`
  input connection the phone user hits.
- Invokes the actual installed `claude --print '<prompt>'` and
  `codex exec --skip-git-repo-check '<prompt>'` binaries inside the tmux pane
  (Claude Code 2.x and Codex CLI 0.x via the fixture's `Dockerfile`). The
  real-agent image deliberately ships without API credentials, so the
  deterministic visible substrings the test matches against are the CLI-emitted
  startup texts — `Not logged in` for Claude and `OpenAI Codex v` for Codex —
  using `TerminalTextMatcher.containsWrapTolerant` so the soft-wrap at the
  Compose grid boundary does not flake the assertion.
- Reads the JSONL conversation log back over SSH from
  `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` and
  `~/.codex/sessions/<YYYY>/<MM>/<DD>/rollout-<ts>-<session>.jsonl`, then
  asserts on a minimal schema (`sessionId` field for Claude; `session_meta`
  payload with `id`/`cwd` for Codex). This is the load-bearing JSONL contract
  PocketShell's
  [com.pocketshell.app.session.AgentConversationRepository](../app/src/main/java/com/pocketshell/app/session/AgentConversationRepository.kt)
  parses, so a CLI version bump that broke the schema would surface here.

The test is opt-in via the instrumentation runner argument
`pocketshellRealAgentReleaseGate=1`, set automatically by
`scripts/release-emulator-validation.sh` when `TERMINAL_RELEASE_GATE=1`. Without
the argument the test class is skipped by `Assume.assumeTrue`, so normal
`connectedDebugAndroidTest` runs and the default release gate are unaffected.
Artifacts (instrumentation log, Docker compose log, SSH readiness probe,
emulator logcat) are written under
`build/real-agent-release-gate/<run-id>-real-agent-release-gate/`.

To run it locally (a booted emulator and the real-agent Docker image are both
required):

```bash
REAL_AGENTS=1 TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh
```

`REAL_AGENTS=1` is consumed by the underlying `scripts/terminal-workbench.sh`
step; `TERMINAL_RELEASE_GATE=1` opts both the workbench step and the new
`RealAgentReleaseGateTest` step in. To exercise the test in isolation against a
running emulator + real-agent fixture without the rest of the release gate:

```bash
docker compose -f tests/docker/real-agent/compose.yml up -d --build real-agents
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.RealAgentReleaseGateTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellRealAgentReleaseGate=1
```

The same validation can be run manually from GitHub Actions when local emulator
capacity is unavailable: Actions -> Release Emulator Validation -> Run
workflow. Choose the release branch or `main`; optionally provide a `run_id`.
Read the job summary first, then download the
`release-emulator-validation-<run-id>` artifact for logs, screenshots, and the
release summary. The tested debug APK is included inside that artifact at
`release-emulator-validation/<run-id>/app-debug.apk`; locally, the same file is
written under `build/release-emulator-validation/<run-id>/app-debug.apk`.
Inspect the visual-audit screenshots before using the artifact as release
evidence. This workflow produces evidence only; it does not push the tag and
does not relax the stable-main tag rule.

This combines the normal compile/unit check, deterministic Docker `agents`
target verification, explicit-path emulator readiness checks, focused connected
walkthrough journeys for keyboard/input, snippets/composer, dictation, planner, and
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
release APK inside the fast gate. Lint is excluded from this local pre-release gate
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
step-by-step status/log paths, focused selector status, the focused walkthrough
cold-reset install status, the final data-preserving update install status, and
the final pass/fail result. On failures, start review from that summary: it
names the failing step and, for focused instrumentation failures, the diagnostics
and bounded logcat artifact paths.

The focused app walkthrough selectors run through direct
`adb shell am instrument -e class <selector>` invocations after one app/test
package reset and one explicit app/test APK install for the whole focused phase.
This is a destructive cold-reset path for deterministic walkthrough tests, not
the user update path. It makes the gate repeatable on a reused emulator, avoids
stale Gradle connected-test runner arguments, and keeps package
deletion/replacement work out of the selector window.
The cold-reset setup clears existing package data without uninstalling in the
normal path, then replace-installs both APKs and waits for package-manager
handlers to go idle. Uninstall is only used as a logged fallback for incompatible
existing packages in that cold-reset setup. After install, the gate watches a
stability window for delayed PocketShell package removal broadcasts from earlier
emulator work and reinstalls before instrumentation if one appears. The gate then
force-stops app/test packages before each selector and waits until no PocketShell
process is running and both packages report
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

The final APK install in the pre-release gate is the data-preserving update gate:
`scripts/install-update-apk.sh app/build/outputs/apk/debug/app-debug.apk`. That
helper runs exactly `adb install -r <apk>` and intentionally has no `pm clear`,
uninstall fallback, or cold-install flags.

See [docker-emulator-runbook.md](docker-emulator-runbook.md#apk-pre-release-gate)
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

Bootstrap PATH precedence: the probe first asks the remote user's configured
interactive shell for its rc-derived PATH (`.bashrc` for bash, `.zshrc` for
zsh, fish config for fish, `.profile` for POSIX fallback). It then prepends
PocketShell's default user-bin locations (`$HOME/.local/bin`, `$HOME/bin`,
`$HOME/.cargo/bin`) before running `command -v` and install commands. Those
defaults win over rc-derived entries, and rc additions win over the remote
SSH daemon's bare non-login PATH. There is no manual Extra PATH directories
field in the app.

The false-positive setup bug is tracked in #70. The reusable opt-in scenario
suite is tracked in #71.

### Network fault resilience suite

The network fault proofs are opt-in connected Android instrumentation tests.
They require a booted emulator plus the Docker `agents` target behind
Toxiproxy. Default CI does not start these services, and the tests also skip
when the instrumentation process detects CI, so run them manually for reviewer
evidence or after explicitly changing the workflow.

Start the ride-through fixture:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents network-fault-proxy
```

For the full network-fault proof family, including packet loss, also start the
netem proxy:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build \
  agents network-fault-proxy packet-loss-proxy
```

Run the issue #552 ride-through proof:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.RideThroughInterruptionE2eTest
```

To isolate one case, append `#briefLinkCutRidesThroughWithoutDisconnectOrTeardown`
or `#sustainedLinkCutReconnectsCleanlyWithoutHang` to the class selector. The
brief case uses a non-closing Toxiproxy timeout toxic for a short byte-starved
link; the sustained case disables the proxy long enough to force an explicit
reconnect. Summaries are written under
`/sdcard/Android/media/com.pocketshell.app/additional_test_output/issue342-network-faults/`.

The Toxiproxy request-shape unit test is part of the normal debug JVM suite and
does not need Docker, an emulator, or an unstable network:

```bash
./gradlew :app:testDebugUnitTest --tests com.pocketshell.app.proof.ToxiproxyControlTest
```

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
  bootstrap-uv-upgrade \
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
| `uv-upgrade` | `bootstrap-uv-upgrade` | `2236` |

Cleanup:

```bash
docker compose -f tests/docker/docker-compose.yml down --volumes --remove-orphans
```

The mutable `uv-install` and `daemon-disabled` scenarios reset their remote
state before and after each test, so running a single scenario repeatedly
against the same containers starts from the documented pristine profile.

---

## Real LLM assistant loop tests

`AssistantAgentLoopRealLlmTest` is an opt-in JVM integration test for the
in-app assistant's product-level structured action output. The test class is
excluded from the normal app unit-test tasks, so `./gradlew test`, release
gates, and CI do not call external model providers. Run the dedicated task and
set `POCKETSHELL_REAL_LLM_TESTS=1` explicitly:

```bash
POCKETSHELL_REAL_LLM_TESTS=1 ./gradlew :app:realLlmTest
```

The test reads provider credentials only from the PocketShell repo root `.env`.
It does not read sibling repos. It also ignores `app/.env`, `.env.local`,
shell history, and unrelated dotenv files. Do not commit `.env` with real keys.
Values are never printed by the test, and skip messages name only missing
variable names.

When running from a git worktree, put `.env` in that worktree root. The
harness intentionally does not read the primary checkout or any parent
directory.

ZAI is the primary target provider. The implementation uses the
Anthropic-compatible Messages wire format for ZAI, but this is only a wire
protocol detail. Put keys and optional overrides in the repo root `.env`:

```bash
ZAI_API_KEY=...
ZAI_MODEL=glm-4.6
ZAI_BASE_URL=https://api.z.ai/api/anthropic
```

The same scenario also covers the Anthropic-compatible configuration slot used
for ZAI-compatible endpoints. `ZAI_API_KEY` / `ZAI_BASE_URL` / `ZAI_MODEL`
take precedence when both sets are present; `ANTHROPIC_API_KEY` /
`ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` are accepted as aliases for the same
ZAI Anthropic-compatible Messages endpoint:

```bash
ANTHROPIC_API_KEY=...
ANTHROPIC_BASE_URL=https://api.z.ai/api/anthropic
ANTHROPIC_MODEL=glm-4.6
```

OpenAI coverage uses the OpenAI wire client and defaults from
`AssistantSettings`:

```bash
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-4o
OPENAI_BASE_URL=https://api.openai.com/v1
```

Scenario catalog:

| Scenario | Providers | Expected structured tools |
|---|---|---|
| `openAi_llmZoomcampEmojiSequence_callsExpectedTools` | OpenAI when `OPENAI_API_KEY` is present | exact user request `убери все эможди в ллм зумкампе` resolves to `llm-zoomcamp`, starts `codex`, and sends normalized prompt `убери все эмоджи` |
| `zai_llmZoomcampEmojiSequence_callsExpectedTools` | ZAI when `ZAI_API_KEY` or `ANTHROPIC_API_KEY` is present | exact user request `убери все эможди в ллм зумкампе` resolves to `llm-zoomcamp`, starts `codex`, and sends normalized prompt `убери все эмоджи` |
| `zai_llmZoomcampEmojiSequence_revisesAfterCorrection` | ZAI when `ZAI_API_KEY` or `ANTHROPIC_API_KEY` is present | first `start_session` candidate is corrected, then the model emits a revised `start_session` followed by `send_prompt_to_session` |

The assertions inspect model tool calls and executed fake actions, not prose:
tool names, ordering, and important arguments are checked. The model must look
up the project before choosing it, start `codex` in the expected project
directory, and call `send_prompt_to_session` with the requested task prompt.
The current project target is `/home/dev/projects/llm-zoomcamp`. The fake
actions used by the tests never open SSH sessions or execute shell commands.

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
