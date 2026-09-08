# Agent awareness

PocketShell reports the coding workload associated with the active aplexer
session. It does not inspect a separate terminal manager or infer an agent from
a display name.

## What is reported

The host CLI may report Claude Code, Codex, OpenCode, or Grok Build. The
normalised session row carries the agent kind, source, recent state, workspace,
and timestamps. A missing or ambiguous signal remains `null`; the host never
guesses from a session label.

The source is the live aplexer workload and its descendant process tree,
combined with the server-side hook/log records where available. This gives the
host the process identity that the Android app cannot safely observe through a
non-interactive SSH command.

## Session surface

The terminal screen always remains available. Agent metadata can decorate the
session tree or terminal chrome when the app surface supports it, but it is not
required for attach and it cannot change the session identity. A shell session
with no detected agent is a valid session.

Conversation history is a separate host-file concern. The existing parsers and
`agent-log-explorer` support history/search workflows, while the session list
uses only the small live-state contract needed to render the current host.

## Host data sources

| Agent | Source |
|---|---|
| Claude Code | `~/.claude/projects/<encoded-cwd>/*.jsonl` |
| Codex | `~/.codex/sessions/**/*.jsonl` filtered by rollout cwd |
| OpenCode | `~/.local/share/opencode/opencode.db` filtered by session directory |
| Grok Build | `$GROK_HOME/sessions/<encoded-cwd>/*/updates.jsonl` |

The host applies cwd and freshness filters before associating a record with the
live aplexer workload. If those filters or the process evidence do not agree,
the result is silent absence rather than a false Conversation affordance.

## Testing

The Docker agents fixture seeds representative logs and starts a real aplexer
session. Journey and Python tests verify the host-side row and the Android
rendered result independently. See [testing.md](testing.md) for the fixture
contract and [usage-panel.md](usage-panel.md) for the separate quota surface.
