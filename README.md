# PocketShell

PocketShell is a mobile-first Android SSH and tmux client for supervising
remote development work from a phone. It is built around persistent tmux
sessions, touch-friendly navigation, voice/prompt composition, and AI-agent
workflows instead of treating the phone as a small desktop terminal.

The product direction is captured in [docs/vision.md](docs/vision.md). The
short version: connect to a host, see the remote workspaces that matter, attach
quickly, and send useful commands without fighting the mobile keyboard.

## Current Status

PocketShell is in active dogfood development. The Android app, shared SSH,
tmux, terminal, agent, usage, storage, voice, and UI-kit modules are present in
the Gradle build. The local QA setup can exercise the app against deterministic
Docker SSH targets and an Android emulator.

Dogfood confidence currently depends on:

- JVM unit tests and Gradle checks.
- Docker-backed integration tests for SSH and port forwarding.
- Emulator connected tests for Compose UI, terminal input, prompt composition,
  dictation/planner flows, and the app-to-Docker SSH/tmux journey.
- A pre-release confidence gate that builds, tests, installs the debug APK, and
  writes logs under `build/pre-release-confidence-gate/<run-id>/`.

The deterministic Docker host does not use real provider credentials. It ships
local shims for tools such as `claude`, `codex`, `opencode`, `heru`,
`agent-log-explorer`, `tmuxctl`, `uv`, and `systemctl` so emulator tests can run
repeatably.

## Repository Layout

- `app/` - Android application.
- `shared/core-ssh/` - SSH connection behavior.
- `shared/core-portfwd/` - Port-forwarding support.
- `shared/core-tmux/` - tmux parsing and session behavior.
- `shared/core-terminal/` - terminal emulator/view integration.
- `shared/core-agents/` - agent detection and log parsing surfaces.
- `shared/core-usage/` - remote usage/quota parsing.
- `shared/core-storage/` - persistence layer.
- `shared/core-voice/` - voice input support.
- `shared/ui-kit/` - shared visual components and tokens.
- `tests/docker/` - deterministic Docker SSH targets used by local and CI tests.
- `docs/` - product docs, architecture notes, mockups, and QA runbooks.

## Prerequisites

- JDK 17.
- Android SDK with platform tools and an emulator image.
- Docker with Compose support.
- A writable `local.properties` pointing at the Android SDK, for example:

```properties
sdk.dir=/home/alexey/Android/Sdk
```

In this workspace, Android tools may not be on `PATH`. The known local paths
are documented in [agents.md](agents.md) and
[docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md):

```bash
export ANDROID_SDK=/home/alexey/Android/Sdk
export ADB="$ANDROID_SDK/platform-tools/adb"
export EMULATOR="$ANDROID_SDK/emulator/emulator"
```

## Setup

From the repository root:

```bash
chmod +x ./gradlew
./gradlew assembleDebug
```

Install and launch on an already-running emulator:

```bash
./gradlew installDebug
adb shell am start -n com.pocketshell.app/.MainActivity
```

If `adb` is not on `PATH`, use the explicit SDK path:

```bash
/home/alexey/Android/Sdk/platform-tools/adb shell am start -n com.pocketshell.app/.MainActivity
```

## Build And Test Commands

Fast local build:

```bash
./gradlew assembleDebug
```

Unit tests:

```bash
./gradlew test --stacktrace
```

Full Gradle check:

```bash
./gradlew check --stacktrace
```

Docker-backed JVM integration tests:

```bash
./gradlew :shared:core-ssh:integrationTest \
  :shared:core-portfwd:integrationTest \
  --stacktrace
```

Connected Android tests on a running emulator:

```bash
./gradlew connectedDebugAndroidTest --stacktrace
```

The project test matrix and ownership expectations are documented in
[docs/testing.md](docs/testing.md).

## Docker And Emulator Testing

PocketShell uses two local emulation surfaces:

- Android emulator for UI, terminal, input, and navigation validation.
- Docker SSH targets for remote host behavior, tmux, agent tools, usage
  fixtures, setup/bootstrap states, and app-to-host smoke tests.

Start the normal deterministic agent target:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
```

Verify the SSH target from the host:

```bash
chmod 600 tests/docker/test_key
ssh -i tests/docker/test_key -p 2222 \
  -o BatchMode=yes \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  testuser@127.0.0.1 \
  'for tool in heru agent-log-explorer tmuxctl uv; do command -v "$tool"; done'
```

Run connected tests against that Docker target:

```bash
./gradlew --no-daemon connectedDebugAndroidTest --stacktrace
```

Clean up Docker state:

```bash
docker compose -f tests/docker/docker-compose.yml down --volumes --remove-orphans
```

Inside the Android emulator, Docker's host-mapped SSH port is reachable at
`10.0.2.2:2222`. Use the runbook for emulator startup, port conflicts, focused
connected checks, bootstrap profiles, and failure collection:
[docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md).

## Dogfood Pre-Release Gate

Before tagging an APK for dogfood, run:

```bash
scripts/pre-release-confidence-gate.sh
```

The gate runs compile/unit checks, verifies the deterministic Docker agent
target, checks emulator readiness through explicit Android SDK paths, runs
focused connected dogfood journeys, builds the debug APK, and installs it on
the emulator. Logs are written under:

```text
build/pre-release-confidence-gate/<run-id>/
```

See
[docs/docker-emulator-runbook.md#apk-dogfood-pre-release-gate](docs/docker-emulator-runbook.md#apk-dogfood-pre-release-gate)
for the exact sequence.

## Release Build Notes

Release APKs are created by pushing a version tag. The tag-triggered Build
workflow assembles the debug APK, uploads it as an artifact, and creates the
GitHub Release. The workflow is intentionally packaging-only; tests belong to
the Tests workflow and the local pre-tag verification gate.

Before tagging:

1. Pick the next semantic version after the latest GitHub Release/tag.
2. Update `app/build.gradle.kts` so `versionName` matches the tag without the
   leading `v`.
3. Increase `versionCode` monotonically.
4. Run the normal verification gate before committing the version bump.
5. Commit the version bump on `main` and push `main` first.
6. From clean `main`, with `HEAD` equal to `origin/main`, run
   `scripts/release-emulator-validation.sh`.
7. Inspect the visual-audit screenshots listed in that summary.
8. Push the matching tag with the guarded helper, for example
   `scripts/push-release-tag.sh --visual-audit-inspected v0.2.1 build/release-emulator-validation/<run-id>/summary.md`.
9. Watch the Build workflow and verify the uploaded APK artifact.

The same emulator-only release validation can be run from GitHub Actions
without a physical phone: Actions -> Release Emulator Validation -> Run
workflow. Choose the release branch or `main`; optionally provide a `run_id`.
Read the job summary first, then download the
`release-emulator-validation-<run-id>` artifact for logs, screenshots, and the
release summary.

Do not tag a release while the APK metadata still reports the previous
version. That can make the installed app offer the same release as an update.
Release issue/tag notes must attach or link the validation artifact directories
listed in `build/release-emulator-validation/<run-id>/summary.md`. Physical
phone testing is final user acceptance only; emulator/Docker validation catches
basic release blockers before a tag is pushed.

## Process And Runbooks

- [process.md](process.md) - implementer, reviewer, and orchestrator process.
- [agents.md](agents.md) - local Android tooling paths and agent role links.
- [docs/README.md](docs/README.md) - documentation index.
- [docs/testing.md](docs/testing.md) - testing matrix and QA expectations.
- [docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md) - Docker,
  emulator, focused connected test, and dogfood gate commands.
- [docs/design-language.md](docs/design-language.md) - visual design language.
- [docs/vision.md](docs/vision.md) - product vision and positioning.
