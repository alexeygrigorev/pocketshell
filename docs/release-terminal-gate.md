# Release terminal gate

High-confidence terminal release gate for PocketShell. Runs the
terminal-focused emulator + Docker connected tests as a chain, validates
the resulting authoritative artifacts, and writes a compact summary so a
reviewer can decide whether terminal usability is release-ready without
relying on manual phone testing.

This gate is **manual and optional**. It is not enabled in CI by default.
It is also not part of the routine pre-release confidence gate. Run it
locally before tagging when terminal usability is in release scope, or
from `scripts/release-emulator-validation.sh` with
`TERMINAL_RELEASE_GATE=1`.

## Quick start

```bash
# Make sure the AVD is running and clean Docker containers are not blocking
# ports 2222 (deterministic agents) or 2240 (real-agent fixture).
RUN_ID=preflight-$(date +%Y%m%d-%H%M%S) scripts/release-terminal-gate.sh
```

Artifacts land under `build/release-terminal-gate/<run-id>/` with the
top-level summary at `build/release-terminal-gate/<run-id>/summary.md`.

For terminal/tmux-heavy releases that need long-running stability evidence,
run the release validation wrapper with both optional gates enabled:

```bash
RUN_ID=release-v0.3.11 TERMINAL_RELEASE_GATE=1 LONG_RUNNING_TEST=1 scripts/release-emulator-validation.sh
```

The 10-minute stability hold is opt-in and remains skipped for unrelated small
releases. Its artifacts land under
`build/long-running-session/<run-id>-long-running/`, with the primary audit file
at
`build/long-running-session/<run-id>-long-running/artifacts/long-running-session/long-running-summary.txt`.

## What it runs

After the normal Gradle build/unit checks
(`:app:assembleDebug`, `:app:test`, `:shared:core-terminal:test`,
`:shared:core-tmux:test`, `:shared:core-ssh:test`), the gate runs the
following connected tests in order. Each step is wrapped by
`scripts/terminal-workbench.sh` (or its SSH-only sibling) so the existing
artifact validator fires per step:

| Step | Test selector | Purpose |
|---|---|---|
| `step-02-terminal-lab` | `TerminalLabDockerTest#terminalWorkbenchKeepsDockerShellOpenForVisualIteration` | Deterministic agents fixture. Command input, PTY sizing, viewport + visible-text capture. Builds and installs the APK pair. |
| `step-01-ssh-smoke` | `EmulatorDockerSshSmokeTest#debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias` | SSH into Docker. Asserts deterministic agent tools on PATH (claude, codex, opencode, heru, agent-log-explorer, tmuxctl, uv) and that the heru/tmuxctl/agent-log-explorer fixtures respond. |
| `step-03-tmux-attach-prefill` | `TmuxAttachPrefillDockerTest#attachExistingTmuxSessionPrefillsFullScreenQuickly` | Attach to a seeded tmux session; prove the visible terminal shows the first and last seeded line within the perf target. Wraps `scripts/tmux-attach-prefill.sh` (which has its own validator that tolerates the intentionally-empty pre-attach viewport). |
| `step-04-tmux-external-update` | `TmuxExternalUpdateDockerTest#externalTmuxWriteRepaintsAttachedPocketShellViewport` | External write to the attached pane; prove PocketShell repaints. |
| `step-05-real-agent-cli` | `TerminalLabDockerTest#terminalWorkbenchCapturesRealAgentCliScreens` | Real-agent Docker fixture (host port 2240). Drives at least one interactive Claude/Codex/OpenCode CLI screen. The terminal-workbench validator enforces `Ask anything`/`Welcome to Codex`/`Welcome to Claude Code` visible-text presence. |

## Warm switch latency budget

Issue #337 adds an explicit same-host warm-switch timing budget to the
terminal release evidence. Run this connected benchmark when release risk
includes tmux session switching, SSH reuse, or terminal first-frame latency:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.TmuxSessionSwitchSameHostReusesSshE2eTest#sameHostWarmSwitchBenchmarkReportsRepeatedStats
```

The benchmark repeatedly switches between two tmux sessions on the same Docker
host and writes `timings.txt` under the device artifact directory
`additional_test_output/issue178-same-host-switch/`. Pull it with `adb pull` or
from the connected-test artifact bundle.

Interpret the key lines as follows:

- `warm_switch_first_new_frame_p50_ms`, `warm_switch_first_new_frame_p95_ms`,
  and `warm_switch_first_new_frame_max_ms`: selection dispatch to the first
  visible terminal frame from the new session.
- `warm_switch_stale_frame_cleared_p50_ms`,
  `warm_switch_stale_frame_cleared_p95_ms`, and
  `warm_switch_stale_frame_cleared_max_ms`: selection dispatch to the old
  session frame disappearing.
- `warm_switch_local_p95_budget_ms=100`: the local budget for an
  already-warmed target runtime display. This is the release target for p95.
- `warm_switch_ci_advisory_p95_ms=5000`: CI/emulator advisory ceiling while
  emulator variance and the uncached runtime path remain noisy.
- `tmux_latency_list_panes_ms`, `tmux_latency_capture_pane_ms`,
  `tmux_latency_cursor_query_ms`, `tmux_latency_first_visible_output_ms`, and
  `tmux_latency_terminal_output_append_to_buffer_ms`: command-path timings
  emitted by `TmuxSessionViewModel`.

Structural failures are the hard gate: a warm same-host switch must not
increase `SSH_HANDSHAKE_ATTEMPTS` and must not record new SSH opens from tmux
connect, warm-host connect, session-picker list, folder-list probe, or start
directory autocomplete sources. A new tmux control attach is expected on each
switch until the runtime-cache work lands separately.

CI records the same ViewModel command-path timing lines through
`TmuxSessionViewModelTest#attachReadinessRecordsTmuxLatencyMetrics`; those
lines live in the uploaded unit-test reports. Connected warm-switch timing
thresholds are advisory on CI until emulator variance is low enough to make the
100 ms local p95 budget a stable hard assertion.

Step ordering note: `step-01-ssh-smoke` is numbered first by acceptance-
criterion intent (SSH must work) but runs second after the terminal-lab
step builds and installs the debug APK pair. Numbering is preserved in
the summary for traceability against the acceptance criteria.

## What makes the gate fail

The gate fails when any step fails. Step failures come either from the
connected test reporting a non-zero exit, or from the
`terminal-workbench.sh` validator detecting one of:

- Missing authoritative artifacts (no `*-viewport.png`, missing
  `timings.txt`, missing capture summaries).
- Blank or header-only authoritative viewport captures (bright-pixel
  counts must be positive, summary `viewport_sha256` must match the file
  sha256, visible-terminal sidecars must contain printable text).
- Stale viewport hashes across before/after captures in the same step
  (duplicate sha256 across distinct named captures is rejected).
- Failed SSH (Docker fixture does not become reachable on host port 2222
  or 2240).
- Failed PTY sizing (timings missing `send_to_output_*_pty_size_ms=`,
  summary missing positive `terminal_grid_columns` / `terminal_grid_rows`).
- Missing expected visible text (per scenario: tmux seed-line markers for
  the attach-prefill step; "Ask anything"/"Welcome to Codex"/"Welcome to
  Claude Code" for the real-agent step).

## Reading the artifact bundle when a step fails

Every step writes to a dedicated sub-directory:

```
build/release-terminal-gate/<run-id>/
  summary.md
  step-02-terminal-lab.log
  step-01-ssh-smoke.log
  step-03-tmux-attach-prefill.log
  step-04-tmux-external-update.log
  step-05-real-agent-cli.log
  workbench/<run-id>-step-XX-<name>/
    artifacts/terminal-lab/
      *-viewport.png            # authoritative
      *-visible-terminal.txt    # authoritative
      *-summary.txt             # authoritative
      timings.txt               # authoritative
      [non-viewport].png        # advisory / diagnostic only
    artifact-summary.txt        # terminal-workbench's per-run summary
    07-run-workbench.log        # instrumentation log
    docker-agents.log           # docker compose logs
    docker-ssh-readiness.log
    logcat.txt
    final-screen.png            # full-device, diagnostic only
```

When reviewing a failed run:

1. Open `summary.md`. The failing step is named at the top.
2. Open the step's `artifact-summary.txt`; it lists which authoritative
   artifacts were pulled and the validation result.
3. Cross-check the visible-terminal sidecar against the viewport PNG.
4. If the failure is in `step-01-ssh-smoke`, inspect
   `docker-ssh-readiness.log`, the instrumentation log, and the Docker
   compose logs for the agents service.

## Long-running stability evidence

The terminal release gate proves short interactive terminal, SSH, tmux, and
real-agent behavior. It does not by itself prove that a foreground terminal/tmux
session stays healthy across a longer hold. For release candidates that change
terminal input, tmux attachment/repaint, SSH transport behavior, PTY sizing, or
agent CLI terminal workflows, add the opt-in 10-minute stability hold:

```bash
RUN_ID=release-v0.3.11 TERMINAL_RELEASE_GATE=1 LONG_RUNNING_TEST=1 scripts/release-emulator-validation.sh
```

Evidence to link from the release PR/issue:

- Release wrapper summary:
  `build/release-emulator-validation/<run-id>/summary.md`
- Terminal workbench artifacts:
  `build/terminal-workbench/<run-id>-terminal-release/`
- Real-agent release-gate artifacts:
  `build/real-agent-release-gate/<run-id>-real-agent-release-gate/`
- Long-running stability artifacts:
  `build/long-running-session/<run-id>-long-running/`

Acceptable long-running result:

- `LongRunningSessionStabilityTest` exits successfully and the wrapper summary
  marks the optional long-running session hold as passed.
- `long-running-summary.txt` records `tick_count=6` with tick latency lines for
  ticks 0 through 5.
- `reconnect_events=0`; any `ssh-read-eof` or `ssh-read-failed` event is a
  release-blocking SSH/tmux stability failure unless independently explained
  and rerun cleanly.
- `memory_growth_kb` is below the recorded `memory_growth_budget_mb=50.0`
  budget.
- `long-running-visible-terminal.txt` still contains the final tick marker,
  proving the pane is live at the end of the hold.

If the hold fails, evaluate `long-running-summary.txt` first, then inspect
`long-running-logcat-tail.txt`, `long-running-visible-terminal.txt`,
`instrumentation.log`, and `docker-agents.log` in the same artifact root. Link
the failed bundle in the release issue if the release is paused or the failure
drives follow-up work. Do not make this gate mandatory for unrelated small
releases; use it when the release risk is terminal/tmux stability.

## How long it takes

Cold (Gradle build, Docker pull, APK build, install, 5 connected tests):
roughly 15-25 minutes on a developer workstation. Warm (Gradle and Docker
caches present, APK rebuild only):
roughly 8-12 minutes. The real-agent step (`step-05-real-agent-cli`)
dominates because the real CLIs settle slowly.

## Useful overrides

| Env var | Default | Purpose |
|---|---|---|
| `RUN_ID` | UTC timestamp | Set this to a stable name (e.g. `release-v0.2.2`) so the artifacts directory is easy to cite in an issue or tag note. |
| `SKIP_GRADLE_CHECKS` | `0` | Reuse existing build outputs. Useful when iterating on the gate itself; do not use for release evidence. |
| `SKIP_SSH_SMOKE` | `0` | Skip `step-01-ssh-smoke`. Only safe if you have separate, recent SSH evidence. |
| `SKIP_REAL_AGENT` | `0` | Skip `step-05-real-agent-cli`. Use only when the real-agent compose fixture is unavailable; document the skip in the release notes. |
| `LOG_ROOT` | `build/release-terminal-gate` | Override where artifacts land. |
| `DETERMINISTIC_COMPOSE_FILE` | `tests/docker/docker-compose.yml` | Override the deterministic compose file. |
| `REAL_AGENT_COMPOSE_FILE` | `tests/docker/real-agent/compose.yml` | Override the real-agent compose file. |

## CI status

This gate is not part of any GitHub Actions workflow. The Build workflow
is intentionally packaging-only and the Tests workflow does not run
emulator-heavy chains. Wiring the gate into CI is a separate decision
that the release process owners will make after the manual gate proves
stable across releases.
