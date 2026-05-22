# Agent Awareness

PocketShell detects when Claude Code is running in the active tmux pane and surfaces a clean conversation view of *that session* — solving the "I can't see what the agent just asked me" scrollback problem.

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

Two complementary heuristics, run together for Claude Code:

1. Cwd + recent file modification. From the active pane's `pwd`, derive the expected Claude Code JSONL path. Pick the most-recently-modified file within the last few minutes.
2. Process scan. `ps` on the host. If `claude` appears, confirm the detection.

If both miss → no Conversation tab. Silent.

### Agent sources

| Agent | Source | Live? |
|---|---|---|
| Claude Code | `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` | Yes (append-only) |
| Codex (OpenAI) | `~/.codex/sessions/**/*.jsonl` | Runtime disabled |
| OpenCode | `~/.local/share/opencode/opencode.db` | Runtime disabled |

Codex and OpenCode runtime detection are intentionally disabled for now. Codex session JSONL files can mention another project's cwd in prompts, tool output, errors, or command arguments; treating a free-text match as pane correlation can expose another session. OpenCode's current SQLite store is global at `~/.local/share/opencode/opencode.db`; without a reliable cwd/session/project key, showing its latest messages in an arbitrary pane can expose another project's conversation. `CodexParser` and `OpenCodeReader` stay in `core-agents` with tests so they can be re-enabled once PocketShell can prove project/session correlation safely.

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
│  [ Search in conversation ]   [ Top ]   │
└─────────────────────────────────────────┘
```

Behaviours:
- Markdown rendering — no ANSI noise
- Tool calls collapsible (default collapsed); tap to expand command + output + diff
- Auto-tails the file/db as the agent writes new messages
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
├── ClaudeCodeParser.kt     # JSONL → ConversationEvent
├── CodexParser.kt          # JSONL → ConversationEvent
├── OpenCodeReader.kt       # SQLite → ConversationEvent
├── AgentDetector.kt        # Claude cwd + ps heuristics; Codex/OpenCode runtime disabled
└── ConversationEvent.kt    # normalized model
```

## What's explicitly out of v1

- Cross-session / cross-project search (that's agent-log-explorer's job)
- Sending messages *from* the conversation view (use the prompt composer; conversation view is read-only)
- Editing / replaying past tool calls
- Agent-specific UI (e.g. Claude Code's todo list as a structured widget) — generic message rendering for now
- Auto-installing or managing agent-log-explorer on the host
