# Scheduled usage capture

PocketShell's usage screen is stale-while-revalidate (issue #689): the host
captures provider usage on a schedule and the app renders the last captured
reading **instantly** before its own live foreground refresh swaps in fresh
data.

This directory holds the host-side scheduler units that drive the capture.
Server-side scheduling is fine — the foreground-only rule (D21) applies to the
Android app, not the host CLI.

## What `--capture` does

```bash
pocketshell usage --capture
```

Fetches usage live (via the daemon when one is running, else a one-shot
subprocess), then writes two artifacts under
`${XDG_STATE_HOME:-~/.local/state}/pocketshell/usage/`:

- `usage-latest.json` — the cached latest reading
  (`{"captured_at": "...Z", "records": [ … ]}`), mode `0600`.
- `usage-history.jsonl` — an append-only history log, one capture per line,
  trimmed to the most recent 2000 lines (~83 days at hourly capture; the file
  stays well under ~1 MB). No external logrotate dependency.

A failed live fetch is **not** cached, so a transient provider hiccup never
pins a bad reading.

The app reads the cache with:

```bash
pocketshell usage --cached
```

which prints `usage-latest.json` instantly (exit 3 with a friendly note when no
capture has run yet, so the app falls back to a pure live fetch).

## Install (systemd user timer — recommended)

```bash
mkdir -p ~/.config/systemd/user
cp pocketshell-usage-capture.service ~/.config/systemd/user/
cp pocketshell-usage-capture.timer   ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now pocketshell-usage-capture.timer

# Keep the timer running after you log out (so the laptop/server captures
# on schedule without an active session):
loginctl enable-linger "$USER"
```

Check it:

```bash
systemctl --user list-timers pocketshell-usage-capture.timer
journalctl --user -u pocketshell-usage-capture.service --no-pager
```

The `.service` uses `%h/.local/bin/pocketshell` (the `uv tool install` /
`pipx install` location). Adjust `ExecStart` if `pocketshell` lives elsewhere
on your `PATH`.

## Install (cron alternative)

For hosts without systemd, an hourly cron line works the same way:

```cron
# m h dom mon dow command
17 * * * * $HOME/.local/bin/pocketshell usage --capture >/dev/null 2>&1
```

(The minute offset spreads the call off the top of the hour.)
