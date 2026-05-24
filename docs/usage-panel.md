# Usage Panel

Provider quota tracking for Claude Code, Codex, OpenCode, and other agents — surfaced as a dedicated screen and a dashboard widget. Lets the user check "how much budget have I burned through this week" without leaving PocketShell.

## Key principle: zero credentials on the phone

PocketShell never holds Anthropic / OpenAI / GitHub credentials. All usage data is fetched server-side over SSH by invoking an existing tool on the host (e.g. `heru usage --json`) and parsing the result. The phone is a viewer; the host is the source of truth.

Consequences:
- No OAuth flow in the app
- No API key prompts during onboarding
- If a host doesn't have the usage tool installed, the usage panel for that host is simply hidden — no error, no setup nag inside the panel
- Adding support for a new agent provider means installing/updating the tool on the server, not shipping a new APK

## Inspiration

Two sibling tools already implement this concept:

- `heru usage` — polls provider quota APIs (Anthropic OAuth, ChatGPT, GitHub Copilot). Normalizes to a common shape per provider. Has a `--json` flag.
- `litehive status` — tracks local invocation success/failure counts per engine.

PocketShell delegates to whichever the user has installed. Same composition pattern as `tmuxctl` (server-side daemon, PocketShell is the mobile frontend).

## What we show

Per provider, one card with:
- Status pill (ok / blocked / error / unsupported)
- Short-term window (e.g. Claude's 5-hour bucket) with progress bar
- Long-term window (e.g. Claude's 7-day bucket, Codex weekly, Copilot monthly)
- Reset time (countdown if soon, absolute date if days away)
- Last error if status is `error`

## Surfaces

| Where | What |
|---|---|
| Usage screen | Full per-provider cards with both windows + details. Pull-to-refresh. |
| Dashboard | Compact strip near top — one row per provider: name, status dot, percent of the most-constraining window |
| Session row badge | Warning chip if the agent running in that session is blocked or near the limit |

## Distribution model

For each connected host, PocketShell:

1. On host bootstrap, detects whether a supported usage tool is installed: `command -v heru` over SSH
2. If present: enables the usage panel for that host
3. If absent: silently omits the host from the usage panel. (The bootstrap flow may offer to install `heru` as a separate, optional step — alongside `tmuxctl`.)
4. Runs the usage command on a configurable interval: default 60s while the usage screen is open, 5m in background
5. Parses the normalized JSON, renders mobile UI

## Pluggable usage source

Per-host setting: which command to invoke for usage. Default: `heru usage --json`. Users can override with their own script (e.g. a custom wrapper around `claude usage` or a corporate quota endpoint) as long as it emits the expected JSON shape.

## Expected JSON shape

PocketShell parses an array of normalized provider records:

```json
[
  {
    "provider": "claude",
    "status": "ok",
    "windows": [
      {"name": "5h",  "used": 45.2, "limit": 100, "unit": "percent",
       "reset_at": "2026-05-21T14:23:00Z"},
      {"name": "7d",  "used": 18.0, "limit": 100, "unit": "percent",
       "reset_at": "2026-05-28T09:00:00Z"}
    ]
  },
  {
    "provider": "codex",
    "status": "blocked",
    "block_reason": "weekly limit reached",
    "windows": [
      {"name": "weekly", "used": 100, "limit": 100, "unit": "percent",
       "reset_at": "2026-05-26T00:00:00Z"}
    ]
  }
]
```

`heru usage --json` already emits this shape (modulo array wrapping — PocketShell will accept either single-record or array).

## Out of scope (v1)

- Historical usage charts (monthly burn graphs) — the server-side tools don't track time series; neither will we
- Cost dollarization — provider APIs don't report dollar cost reliably across plans
- Per-session attribution (which session burned which tokens) — not exposed by the source tools
- Push notifications when quota approaches limit — phase 4 polish
- App-side credential storage of any kind — explicitly never
