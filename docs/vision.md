# PocketShell vision

PocketShell is a mobile-first Android SSH client for persistent host sessions,
agent-aware workflows, and fast terminal navigation. It makes remote work
comfortable on a phone through touch controls, voice input, and a small set of
clear session actions.

## Core principles

### Host-managed sessions

The host owns session identity and lifetime through the bundled aplexer runtime.
PocketShell presents the live rows returned by `pocketshell sessions list`,
creates a session with `pocketshell sessions create`, attaches with the host
session identifier, and ends it explicitly with `pocketshell sessions kill`.
The phone never chooses among session managers or invents a second session
registry. A session remains available while the app disconnects and can be
attached again after reconnecting.

### Fast mobile navigation

Workspace roots, recent paths, repositories, and session rows reduce typing.
Breadcrumbs, touch targets, key-bar controls, snippets, and bounded host-side
search keep common actions reachable without a desktop keyboard.

### Agent-aware workflows

PocketShell helps supervise coding agents and other long-running CLI work from
mobile. Live host metadata can show the agent kind and state, while the
conversation view reads the current session's agent log over SSH. The usage
panel fetches provider quotas through server-side tools, so provider secrets do
not live on the phone. See [agent-awareness.md](agent-awareness.md) and
[usage-panel.md](usage-panel.md).

### Voice-first terminal interaction

Voice is a first-class way to compose an agent prompt. The key bar supplies
Esc, Tab, Ctrl, Alt, and arrows above the keyboard; command chips and snippets
cover repeated actions; terminal selection makes paths and errors easy to copy.
See [input-methods.md](input-methods.md).

### Session-centric home screen

PocketShell opens on hosts, workspaces, and live session rows rather than a
blank terminal. A user can inspect the current agent state, choose a workspace,
start another session, or return to an existing aplexer session.

### Clear connection behavior

SSH transport, terminal attachment, and remote session lifetime are separate
facts. The app reconnects the visible terminal within its bounded grace and
retry policy, while the host session remains owned by aplexer. See
[architecture.md](architecture.md) and [reconnect-policy.md](reconnect-policy.md).

## Positioning

Short: a mobile-first SSH client for persistent host sessions and AI-agent
workflows.

Long: a modern Android SSH client that makes remote terminals usable on mobile
through touch-first navigation, voice composition, live aplexer sessions, and
agent-aware supervision.

## Inspiration and inputs

- [aplexer-integration.md](aplexer-integration.md) defines the shipped host
  session contract.
- `ssh-auto-forward` provides port-forwarding and reconnect references.
- Termius sets a useful bar for a polished Android terminal experience.
