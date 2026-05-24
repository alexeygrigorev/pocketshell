# Usage Panel

Provider quota tracking for Claude Code, Codex, GitHub Copilot, Z.AI, and other coding-agent CLIs — surfaced as a dedicated screen and a dashboard widget. Lets the user check "how much budget have I burned through this week" without leaving PocketShell.

## Key principle: zero credentials on the phone

PocketShell never holds Anthropic / OpenAI / GitHub credentials. All usage data is fetched server-side over SSH by invoking an existing tool on the host (`quse --json` by default) and parsing the result. The phone is a viewer; the host is the source of truth.

Consequences:
- No OAuth flow in the app
- No API key prompts during onboarding
- If a host doesn't have the usage tool installed, the usage panel for that host is simply hidden — no error, no setup nag inside the panel
- Adding support for a new agent provider means shipping a new `quse` release on the server, not shipping a new APK

## The `quse` library

PocketShell delegates to [`quse`](https://github.com/alexeygrigorev/terminal-usage-tracker) — a small Python CLI that polls each provider's usage endpoint and emits one normalized JSON record per provider. Issue #128 swapped PocketShell over from the previous `heru` CLI to `quse`; the seam was already generic (per-host command override added in #117), so only the default command, parser, and bootstrap install needed to change.

Install methods (any one works):

- `pipx install quse` — default in the bootstrap install flow
- `uv tool install quse` — when the host has `uv` but not `pipx`
- `uvx quse` — one-off run without persistent install

PocketShell's host-bootstrap probe checks for `command -v quse`. The install command picked by the install sheet matches whichever Python tool installer the host has (`pipx install quse` for pipx-equipped hosts, `uv tool install quse` for uv-equipped hosts).

## What we show

Per provider, one card with:
- Status pill (ok / blocked / error / unsupported)
- Short-term window (Codex primary, Claude 5h, Copilot fixed at 100%, Z.AI tokens) with progress bar
- Long-term window (Codex weekly, Claude 7d, Copilot monthly, Z.AI fixed at 100%) with progress bar
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

1. On host bootstrap, detects whether `quse` is installed: `command -v quse` over SSH.
2. If present: enables the usage panel for that host.
3. If absent: silently omits the host from the usage panel. The bootstrap flow may offer to install `quse` as a separate, optional step — alongside `tmuxctl`.
4. Runs the usage command on a configurable interval: default 60s while the usage screen is open, 5m in background.
5. Parses the normalized NDJSON, renders mobile UI.

## Pluggable usage source

Per-host setting: which command to invoke for usage. Default: `quse --json`. Users can override with their own script (e.g. a custom wrapper around `claude usage` or a corporate quota endpoint) as long as it emits the expected NDJSON shape.

## Expected JSON shape

`quse --json` emits newline-delimited JSON (NDJSON) — one object per line per provider:

```json
{
  "provider": "codex",
  "status": "ok",
  "short_term": {"percent_remaining": 77.0, "reset_at": "2026-05-24T15:53:01Z", "window": null},
  "long_term":  {"percent_remaining": 88.0, "reset_at": "2026-05-30T20:33:54Z", "window": null},
  "block_reason": null,
  "error": null,
  "details": {"limit_reached": false, "windows": {"primary_window": {...}, "secondary_window": {...}}}
}
{
  "provider": "claude",
  "status": "ok",
  "short_term": {"percent_remaining": 41.0, "reset_at": "2026-05-24T14:30:00Z"},
  "long_term":  {"percent_remaining": 85.0, "reset_at": "2026-05-28T14:59:59Z"},
  "block_reason": null,
  "error": null,
  "details": {...}
}
```

Supported providers: `codex`, `claude`, `copilot`, `zai`. `gemini` is accepted but reports `status: "unsupported"` because Gemini does not currently expose a usage endpoint.

`status` values:

- `ok` — quota healthy.
- `limited` / `blocked` — quota wall hit. `block_reason` carries the human-readable explanation when one is available.
- `error` — the upstream API failed; `error` carries a free-form message (e.g. `"login required: run codex login"`).
- `unsupported` — the provider has no usage endpoint yet (currently only `gemini`).

Either `short_term` or `long_term` may be `null` when a provider only exposes one of them; both can also be `null` for `unsupported` / `error` statuses. PocketShell maps `percent_remaining = R` to `used = 100 - R, limit = 100, unit = "percent"` so the existing progress-bar UI keeps the same shape.

## Out of scope (v1)

- Historical usage charts (monthly burn graphs) — `quse` doesn't track time series; neither will we
- Cost dollarization — provider APIs don't report dollar cost reliably across plans
- Per-session attribution (which session burned which tokens) — not exposed by the source tools
- Push notifications when quota approaches limit — phase 4 polish
- App-side credential storage of any kind — explicitly never
