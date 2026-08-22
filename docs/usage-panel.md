# Usage Panel

Provider quota tracking for Claude Code, Codex, GitHub Copilot, Grok Build, Z.AI, and other coding-agent CLIs — surfaced as a dedicated screen and a dashboard widget. The pinned backend currently reports five providers; a separate canonical producer contract can add providers such as OpenCode Go.

## Key principle: zero credentials on the phone

PocketShell never holds Anthropic / OpenAI / GitHub credentials. All usage data is fetched server-side over SSH by invoking `pocketshell usage --json` on the host and parsing the result. The phone is a viewer; the host is the source of truth.

Consequences:
- No OAuth flow in the app
- No API key prompts during onboarding
- If a host doesn't have the usage tool installed, the usage panel for that host is simply hidden — no error, no setup nag inside the panel
- Adding support for a new agent provider means shipping a server-side helper update, not shipping a new APK

## Server-side helper

PocketShell delegates to the `pocketshell` Python helper. Its `usage`
subcommand emits one normalized JSON record per provider. In current
releases the helper may proxy through the host's usage collector
internally, but the Android app probes and invokes the `pocketshell`
binary.

Install methods (any one works):

- `uv tool install pocketshell`
- `pipx install pocketshell`

PocketShell's host-bootstrap probe checks for `command -v pocketshell`,
validates `pocketshell --version` against the app version, and uses
whichever Python tool installer the host has (`uv` or `pipx`) for
install/upgrade. App-driven `uv` installs add
`--exclude-newer-package pocketshell=2099-12-31` so host-level uv
`exclude-newer` settings do not hold back the PocketShell helper.

## What we show

Per provider, one card with:
- Status pill (ok / blocked / error / unsupported)
- Short-term window (Codex primary, Claude 5h, Copilot fixed at 100%, Z.AI tokens) with progress bar
- Long-term window (Codex weekly, Claude 7d, Copilot monthly, Z.AI fixed at 100%) with progress bar
- Reset time (countdown if soon, absolute date if days away)
- Codex-only reset-credit inventory when quse reports it (available count,
  source title, and expiry)
- Last error if status is `error`

### Window labels are data-driven, by genuine span (issue #800)

The card frames each window by the **concrete span the provider's quota
actually uses**, never by a hardcoded provider check in the UI. The window
NAME each record carries drives the label:

| Carried span | Rendered label |
|---|---|
| `5h` | `5h window` |
| `7d` | `7d window` |
| `weekly` | `Weekly limit` |
| `monthly` | `Monthly limit` |
| anything else (`short_term` / `long_term` / unknown) | humanized (`Short term` / `Long term` / `Custom span`, #522) |

The window NAME comes STRAIGHT from the canonical `windows` map key — the
producer is the single source of truth for the span (issue #1318). For the
pinned PyPI quse 0.0.14 wheel, the host maps each legacy `window` value into
that key and falls back to `short_term` or `long_term` when the legacy value is
null. The two main coding-agent providers — **Codex and Claude Code** — both
use the same 5h + 7d windows, so they render the identical concrete `5h
window` / `7d window` labels. **Monthly-cadence providers keep their real
cadence**: GitHub Copilot's long-term quota carries `monthly` and renders
`Monthly limit`, NOT a 7d window. A source span with
`percent_remaining: null` does not apply to that provider and is omitted at the
host boundary. There is no downstream re-derivation of the span from
`details`. The app ignores `details` for quota windows; the one narrow
exception is Codex reset-credit inventory described below.

### Codex reset credits (issue #1789)

For Codex only, PocketShell reads the additive inventory fields
`details.reset_credits`, `details.reset_credits_available`, and
`details.reset_credits_error`. These credits are already available. Their
timestamps are expiry times, never quota resets, and they do not feed the
dashboard's soonest-reset value, reset banners/events, notifications, push,
or any reset action.

The panel preserves source order and duplicates, and includes only rows whose
status is exactly `available`. It shows an honest zero-count header. Missing
inventory fields omit the section; a supplementary inventory error leaves the
valid quota windows visible and adds only `Reset credits unavailable` (never
the raw error). A malformed expiry or count/list mismatch fails the whole
usage panel loudly. Other providers ignore these detail fields.

## Surfaces

| Where | What |
|---|---|
| Usage screen | Full per-provider cards with both windows + details. Pull-to-refresh. |
| Dashboard | Compact strip near top — one row per provider: name, status dot, percent of the most-constraining window |
| Session row badge | Warning chip if the agent running in that session is blocked or near the limit |

## Distribution model

For each connected host, PocketShell:

1. On host bootstrap, detects whether `pocketshell` is installed: `command -v pocketshell` over SSH.
2. If present: enables the usage panel for that host.
3. If absent or version-mismatched: the host setup flow offers to install or upgrade the helper.
4. Runs the usage command on a configurable interval while the app is in the foreground.
5. Parses the normalized NDJSON, renders mobile UI.

## Stale-while-revalidate cache (issue #689)

The usage screen is **always populated + instant**: it renders the last
captured reading immediately, labelled with its capture time, then refreshes
live in the foreground and swaps in fresh data. The user never stares at a
spinner and never sees only-stale data without knowing it.

### Server-side capture + history

The host captures usage on a schedule (cron / systemd timer — server-side
scheduling is fine; the foreground-only rule **D21** applies to the Android
app, not the host CLI):

```bash
pocketshell usage --capture
```

`--capture` fetches usage live (via the daemon when one is running, else a
one-shot subprocess) and writes two artifacts under
`${XDG_STATE_HOME:-~/.local/state}/pocketshell/usage/`:

- `usage-latest.json` — the cached latest reading
  (`{"captured_at": "...Z", "records": [ … ]}`), mode `0600`.
- `usage-history.jsonl` — an append-only history log, one capture per line,
  **trimmed to the most recent 2000 lines** (~83 days at hourly capture; the
  file stays well under ~1 MB). No external logrotate dependency. The history
  enables usage tracking over time and powers the future reset-detection
  follow-up.

A failed live fetch is **not** cached, so a transient provider hiccup never
pins a bad reading.

The scheduler units (systemd user `.timer`/`.service` + a cron example) and
install instructions live in
[`tools/pocketshell/scheduler/`](../tools/pocketshell/scheduler/README.md).
The recommended install is an hourly `systemctl --user` timer.

### App read path

The app reads the cache instantly with:

```bash
pocketshell usage --cached
```

which prints `usage-latest.json` (exit 3 with a friendly note when no capture
has run yet, so the app falls back to a pure live fetch). The app:

1. Renders the cached records at once with a "Last captured at HH:mm · refreshing…" label.
2. Runs the live `pocketshell usage --json` fetch in the foreground.
3. On success, swaps to the fresh value and updates the timestamp.
4. On failure/timeout, keeps the cached value with an honest
   "Couldn't refresh — showing cached from HH:mm" note (no scary blocking error).

A per-host `usageCommandOverride` disables the cache path (an override is an
arbitrary script that does not speak `--cached`); those hosts go straight to
the live fetch.

## Pluggable usage source

Per-host setting: which command to invoke for usage. Default:
`pocketshell usage --json`. Users can override with their own script
(e.g. a corporate quota endpoint) as long as it emits the expected NDJSON
schema.

## Expected JSON Schema

The pinned PyPI `quse==0.0.14` backend is the source for the published usage
values (issue #1318). Its `--json` output is a **provider-keyed object** with
five providers and legacy `short_term` / `long_term` records. The host helper
flattens that object into newline-delimited JSON (NDJSON), injects the provider
name from the key, and translates the two legacy records into the canonical
`windows` map. It does not re-derive windows, resets, percentages, or provider
details (hard-cut, D22). The published raw shape looks like this:

```json
{
  "codex": {
    "status": "ok",
    "short_term": {"percent_remaining": null, "reset_at": null, "window": null},
    "long_term": {"percent_remaining": 56.0, "reset_at": "2026-08-27T03:30:22Z", "window": "7d"},
    "error": null,
    "details": { ... extra fields; Codex reset-credit inventory is the sole app exception ... }
  },
  "claude": {
    "status": "ok",
    "short_term": {"percent_remaining": 99.0, "reset_at": "2026-08-22T15:49:59Z", "window": "5h"},
    "long_term": {"percent_remaining": 93.0, "reset_at": "2026-08-27T14:59:59Z", "window": "7d"},
    "error": null,
    "details": { ... }
  }
}
```

which `pocketshell usage --json` flattens to one record per line:

```json
{"provider":"codex","status":"ok","windows":{"7d":{"percent_remaining":56.0,"reset_at":"2026-08-27T03:30:22Z"}},"error":null,"details":{...}}
{"provider":"claude","status":"ok","windows":{"5h":{"percent_remaining":99.0,"reset_at":"2026-08-22T15:49:59Z"},"7d":{"percent_remaining":93.0,"reset_at":"2026-08-27T14:59:59Z"}},"error":null,"details":{...}}
```

The app reads `provider`, `status`, canonical top-level `windows`, and `error`.
The host producer requires each input provider record to contain either
canonical `windows` or at least one legacy `short_term` / `long_term` field;
missing all three is a schema error and fails loudly. Legacy records may carry
both fields with null values; the host then emits `windows: {}`. Canonical
producers should emit the empty map explicitly when no span is renderable.
The Android parser defensively treats an absent or null map as zero renderable
windows, but `pocketshell usage --json` does not emit that incomplete shape.

`reset_at`, when present, is canonical ISO-8601 UTC; an absent or null value
means that no reset time is available. Each window label comes straight from
the `windows` map key. A window object with `percent_remaining: null` is
non-renderable. A non-object window or malformed numeric value or timestamp is
a schema error. The app ignores `details` except for Codex's strict
reset-credit inventory fields documented above; it never reads
`details.windows` or re-derives quota state from details.

Boundary compatibility contract (issue #2274): the pinned PyPI wheel uses the
legacy producer fields above, while newer or custom producers may send
canonical top-level `windows` records directly. The host helper handles both
forms and emits only canonical NDJSON. The legacy bridge is covered by the
published five-provider fixture tests. Canonical OpenCode Go support is covered
by separate contract tests; the pinned wheel does not publish a `go` provider.

The pinned wheel provides `codex`, `claude`, `copilot`, `grok` (Grok Build; alias
`grok-build`), and `zai`. The canonical app wire also supports `go` (OpenCode
Go) when a canonical producer supplies it. `gemini` is accepted but reports
`status: "unsupported"` because Gemini does not currently expose a usage
endpoint.

`status` values:

- `ok` — quota healthy.
- `limited` / `blocked` — quota wall hit. `block_reason` carries the human-readable explanation when one is available.
- `error` — the upstream API failed; `error` carries a free-form message (e.g. `"login required: run codex login"`).
- `unsupported` — the provider has no usage endpoint yet (currently only `gemini`).

Any `windows` entry may be null-percent when a provider does not expose that
span. A canonical producer uses an empty map for `unsupported` / `error`
statuses with no renderable span; a legacy producer can keep both source fields
null and the host will produce that empty map. PocketShell maps
`percent_remaining = R` to `used = 100 - R, limit = 100, unit = "percent"` so
the existing progress-bar UI keeps the same data contract.

## Out of scope (v1)

- Historical usage charts (monthly burn graphs) — the current helper does not track time series
- Cost dollarization — provider APIs don't report dollar cost reliably across plans
- Per-session attribution (which session burned which tokens) — not exposed by the source tools
- Push notifications when quota approaches limit — phase 4 polish
- App-side credential storage of any kind — explicitly never
