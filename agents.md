# Agent Roles

PocketShell uses the agent workflow defined in [process.md](process.md).

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
