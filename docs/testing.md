# Testing & QA

Two emulation surfaces let us test PocketShell end-to-end without touching real devices or real hosts:

1. **Android emulator** — runs the app, validates UI/UX, exercises Compose interactions
2. **Docker remote server** — emulates the SSH target, with tmux + agents + helper tools installed

Together they cover the full feature surface without leaving the dev machine.

---

## Android emulator

Standard Android Studio AVDs. Recommended set:

| AVD | API | Why |
|---|---|---|
| **Pixel 7** | 34 (Android 14) | Matches design target (412 × 915 dp, same as mockups in `docs/mockups/`) |
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

### Manual visual validation (orchestrator's loop)

For visual changes:

1. Make the change
2. `./gradlew installDebug`
3. Compare side-by-side with `docs/mockups/<screen>.html` open in Chrome at 412 × 915
4. Screenshot for the PR: `adb shell screencap -p > /tmp/screen.png && adb pull /tmp/screen.png`

---

## Docker remote server

The remote-side target for SSH, tmux, port-forwarding, agent detection, and usage panel tests. Image is built **layered** so different phases pull in different surfaces:

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

### Adding the agent CLIs

`Dockerfile.agents` adds the three coding agents and the helper tools. Two ways to populate:

**Option A — install fresh in the container (CI default).** Pin versions. No credentials. Tests use fixtures.

```dockerfile
FROM pocketshell-test:tmux
# Claude Code via npm
RUN apk add --no-cache nodejs npm \
 && npm install -g @anthropic-ai/claude-code
# Codex CLI
RUN pip install --break-system-packages openai-codex
# OpenCode
RUN curl -L https://opencode.ai/install | bash
# Helper tools PocketShell delegates to
RUN pip install --break-system-packages heru agent-log-explorer
```

**Option B — bind-mount your local install (local dev only — never CI).**

```bash
docker run \
  -v "$HOME/.claude:/root/.claude:ro" \
  -v "$HOME/.codex:/root/.codex:ro" \
  -v "$HOME/.local/share/opencode:/root/.local/share/opencode:ro" \
  pocketshell-test:agents
```

This mirrors your real setup including configured providers — fast for local debugging, **never use in CI** because credentials would leak.

### Fixture JSONLs for agent tests

For `core-agents` tests we need representative JSONL files that match real Claude Code / Codex output, committed at `tests/fixtures/agent-jsonl/`.

Generation recipe:

1. Run the CLI once in the Docker container against a trivial prompt
2. Capture the resulting JSONL
3. Sanitize: replace sensitive paths, session IDs, credentials with `<REDACTED>`
4. Commit as fixture

Fixtures are the source of truth for parser tests — they must not require live API calls.

---

## What gets tested where

| Layer | Where | Targets |
|---|---|---|
| Unit (pure Kotlin) | `*/src/test/` | Parsers, data classes, business logic with mocked I/O |
| Integration — SSH | `*/src/test/` via Testcontainers | `core-ssh` against `pocketshell-test:ssh` |
| Integration — tmux | `*/src/test/` via Testcontainers | `tmux -CC` parser + events against `pocketshell-test:tmux` |
| Integration — agents | `*/src/test/` | JSONL parsers against fixtures + tail behaviour against running agents in container |
| Integration — usage | `*/src/test/` | `core-usage` parses real `heru usage --json` output |
| Instrumented UI | `app/src/androidTest/` on emulator | Compose screen tests, navigation, full user flows |
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

---

## CI matrix (eventual)

GitHub Actions runs:

1. `./gradlew check` — unit tests
2. `docker compose up -d` → `./gradlew :shared:core-ssh:test` (Testcontainers picks up the running container)
3. (Later) `./gradlew connectedDebugAndroidTest` on a Firebase Test Lab emulator

Manual emulator smoke testing stays in the orchestrator's loop.

---

## Orchestrator's pre-merge QA checkpoint

Before merging any PR, the orchestrator runs **at minimum**:

1. `./gradlew assembleDebug` — does it build?
2. `./gradlew check` — do unit tests pass?
3. For UI changes: install on emulator, eyeball against the matching mockup
4. For SSH / tmux / agent / usage changes: run the relevant Testcontainers integration test

See [agents.md](../agents.md) for the full verification checklist.
