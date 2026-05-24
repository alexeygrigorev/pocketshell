# Agent Roles

PocketShell uses the agent workflow defined in [process.md](process.md). That
file is the source of truth; this file is only the quick local checklist.

## Process Quick Rules

- Work from GitHub issues. Implementers and reviewers report through issue
  comments; the orchestrator relays between them.
- Keep orchestration asynchronous and nonblocking when possible. Launch agents
  only in asynchronous mode; do not use blocked agent runs while useful
  non-overlapping coordinator work is available.
- Implementers edit and test, then report changed files and verification. They
  do not commit, push, close issues, or edit outside scope.
- Reviewers inspect the latest issue evidence and working-tree diff, run the
  relevant checks, and post exactly `APPROVED` or `CHANGES REQUESTED`. They do
  not edit code.
- User-facing Android, terminal, SSH, tmux, agent, setup, and release-gate work
  needs reviewer emulator evidence. Terminal reviewers must inspect the
  authoritative viewport screenshots, visible terminal text, timings, and
  Docker/emulator logs required by [process.md](process.md#terminal-artifact-review).
- Commit only after reviewer `APPROVED` and the orchestrator's final
  verification checklist in [process.md](process.md). Make one small commit
  after each approved task.
- Release tags come only after the version bump is committed to `main`, pushed,
  and confirmed with `HEAD == origin/main`; tags label stable reviewed `main`
  commits.

## Local Android Tooling

This workspace has Android SDK tools installed even when they are not on
`PATH`. Do not report emulator validation as blocked by `adb: command not
found` until these explicit paths have been tried:

- `adb`: `/home/alexey/Android/Sdk/platform-tools/adb`
- `emulator`: `/home/alexey/Android/Sdk/emulator/emulator`
- SDK root from `local.properties`: `/home/alexey/Android/Sdk`
- Available local AVD: `test`

Before claiming a mobile flow cannot be checked, run:

```bash
/home/alexey/Android/Sdk/platform-tools/adb devices
/home/alexey/Android/Sdk/emulator/emulator -list-avds
```

For Docker images, port allocation, emulator startup, and connected test
commands, use [docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md).

Canonical role definitions live in [.claude/agents/](.claude/agents/):

- [.claude/agents/implementer.md](.claude/agents/implementer.md) — implementer prompt
- [.claude/agents/reviewer.md](.claude/agents/reviewer.md) — reviewer prompt
