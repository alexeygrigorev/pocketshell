# Agent Awareness

PocketShell detects when Claude Code, Codex, or OpenCode is running in
the active tmux pane and surfaces a clean conversation view of *that
session* — solving the "I can't see what the agent just asked me"
scrollback problem.

## What this is, and what it isn't

| | This (PocketShell conversation view) | `agent-log-explorer` |
|---|---|---|
| Scope | One session, current pane, right now | All sessions, all projects, all time |
| Use case | "What did the agent just ask?" / "what did it propose?" | "Find that conversation last month where I debugged auth" |
| Transport | Direct JSONL tail over SSH | Server with SQLite + FTS5 |
| Lifecycle | Auto-detected per pane | User opens it explicitly |
| Dependency | None — no extra service on host | Runs as a daemon |

We may link from one to the other later ("search this conversation across all history" → deep-link into agent-log-explorer). Not in v1.

## Detection

Runtime detection is currently limited to tmux panes because PocketShell needs tmux's `#{pane_current_path}` for the live pane cwd. Non-tmux SSH exec channels cannot reliably observe the interactive shell's current directory after the user has changed directories.

Detection combines log candidates with pane/process evidence:

1. Cwd + recent activity. From the active pane's cwd, derive or filter
   candidate logs for the supported agents. Claude Code is cwd-encoded
   under `~/.claude/projects/`; Codex candidates are filtered by
   rollout `session_meta.cwd`; OpenCode candidates are filtered by
   SQLite session directory / project worktree.
2. Pane-scoped process scan. For tmux panes, PocketShell scopes `ps`
   output to the pane TTY and requires the matching agent command to be
   present before showing the Conversation tab.

If both miss → no Conversation tab. Silent.

### Agent sources

| Agent | Source | Live? |
|---|---|---|
| Claude Code | `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` | Yes (append-only) |
| Codex (OpenAI) | `~/.codex/sessions/**/*.jsonl` | Yes after the rollout JSONL flushes |
| OpenCode | `~/.local/share/opencode/opencode.db` | Yes, polled from SQLite |

Codex and OpenCode use wider freshness windows than Claude Code. Codex
flushes its rollout JSONL on turn completion, and OpenCode persists to a
global SQLite database; PocketShell keeps candidates for up to 2 hours
but still requires cwd/session filtering and pane process evidence before
attributing them to the visible pane.

Encoded-cwd format for Claude Code: `/home/alexey/git/pocketshell` → `-home-alexey-git-pocketshell`.

## UI

### Tab on the session view

```
┌─────────────────────────────────────────┐
│ <  agent-main · main pane         ...   │
├─────────────────────────────────────────┤
│ [ Terminal ]   [ Conversation • ]       │  tab only
├─────────────────────────────────────────┤  visible when
│  USER · 2m ago                          │  agent detected
│  check the deploy log and tell me       │
│  what failed in the last run            │
│                                         │
│  ASSISTANT · 1m ago                     │
│  I'll check the deploy logs.            │
│                                         │
│  > Tool: Bash                           │  collapsed
│    kubectl logs -n prod deploy-...      │  tap to expand
│                                         │
│  The deploy failed because the database │
│  migration timed out at step 4...       │
│                                         │
│  USER · 30s ago                         │
│  show me the migration                  │
│                                         │
│  ASSISTANT · streaming...               │
│  Here's the migration: _                │  tails live
├─────────────────────────────────────────┤
│  [ Message Claude Code...        ][Send]│
│  [ Search in conversation ]   [ Top ]   │
└─────────────────────────────────────────┘
```

Behaviours:
- Markdown rendering — no ANSI noise
- Tool calls collapsible (default collapsed); tap to expand command + output + diff
- Auto-tails the file/db as the agent writes new messages
- Reply-in-place composer sends to the current agent pane
- Search bar = full-text within this session
- Long-press any message → copy / quote-reply into prompt composer

### Hint chip (one-time discoverability)

```
┌───────────────────────────────────┐
│ Claude Code session detected      │
│ Tap to see full conversation  >   │
└───────────────────────────────────┘
```

- Appears once per detected session, inline in the terminal view
- Dismissible (X). Once dismissed, stays dismissed for that session.
- Tab stays available regardless.

## Transport & implementation

- SSH `tail -f <path>` for JSONL files, stream parsed in Kotlin
- Parsers live in a new shared module `core-agents` so they're unit-testable without an SSH connection
- Each parser produces a normalized `ConversationEvent` stream (user message, assistant message, tool call, tool result)
- Conversation view consumes that stream — agnostic to which agent produced it

### `core-agents` module

```
core-agents/
├── ClaudeCodeParser.kt     # JSONL -> ConversationEvent
├── CodexParser.kt          # JSONL -> ConversationEvent
├── OpenCodeReader.kt       # SQLite/JSON rows -> ConversationEvent
├── AgentDetector.kt        # path hints + freshness + process confirmation
└── ConversationEvent.kt    # normalized model
```

## What's explicitly out of v1

- Cross-session / cross-project search (that's agent-log-explorer's job)
- Editing / replaying past tool calls
- Agent-specific UI (e.g. Claude Code's todo list as a structured widget) — generic message rendering for now
- Auto-installing or managing agent-log-explorer on the host
