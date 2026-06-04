# Server-side setup (the `pocketshell` CLI)

The PocketShell Android app drives a small server-side CLI over SSH for several
features:

- **Usage panel** → `pocketshell usage --json` (provider quota/limits)
- **Recurring jobs** → `pocketshell jobs ...`
- **Env / hooks / sessions** → `pocketshell env|hooks|...`

If the CLI isn't reachable over a **non-interactive** SSH command, the app shows
e.g. **"<host>: pocketshell not installed — server-side usage tracking
unavailable"**. Almost always this is a **PATH** problem, not a missing install.

## 1. Install the CLI on the server

The CLI is published to PyPI as [`pocketshell`](https://pypi.org/project/pocketshell/).
Install it per-user (preferred: `uv`):

```bash
uv tool install pocketshell          # lands in ~/.local/bin/pocketshell
# or:  pipx install pocketshell
# or:  python3 -m pip install --user pocketshell
```

Pin the version to match the app's release when it matters:

```bash
uv tool install 'pocketshell==0.3.21'
```

## 2. CRITICAL — put `~/.local/bin` on PATH for *non-interactive* SSH

This is the gotcha that makes a correctly-installed CLI look "not installed".

The app runs commands via **non-interactive** SSH (`ssh host 'pocketshell usage
--json'`). On Debian/Ubuntu, bash sources `~/.bashrc` for SSH-invoked shells, but
the stock `~/.bashrc` returns early for non-interactive shells:

```bash
# If not running interactively, don't do anything
case $- in
    *i*) ;;
    *) return;;      # <-- non-interactive SSH commands stop HERE
esac
```

If your `export PATH=...$HOME/.local/bin...` line is **below** this guard (the
default for most dotfiles), non-interactive SSH never sees `~/.local/bin`, so
`pocketshell` is "command not found".

**Fix: add `~/.local/bin` to PATH ABOVE the guard.** Put these lines at the very
top of `~/.bashrc`, before the `case $- in` block:

```bash
# Ensure ~/.local/bin (pocketshell CLI etc.) is on PATH for non-interactive SSH
# commands too. MUST be above the interactive early-return below.
export PATH="$HOME/.local/bin:$PATH"
```

Always back up first: `cp ~/.bashrc ~/.bashrc.bak`.

## 3. Verify (the same path the app uses)

```bash
ssh <host> 'command -v pocketshell && pocketshell usage --json'
```

You should see the absolute path to `pocketshell` followed by one JSON line per
provider (`claude`, `codex`, `copilot`, ...). If you get `command not found`,
the PATH fix in step 2 isn't in effect for non-interactive shells.

> Note: individual providers may report their own `error` (e.g. `zai`:
> `goz not on PATH`) if that provider's helper tool isn't installed — that's
> independent of the `pocketshell` PATH issue and only affects that one provider.

## 4. Update

```bash
uv tool upgrade pocketshell          # or: pipx upgrade pocketshell
#                                     # or: python3 -m pip install --user -U pocketshell
```

Keep the server CLI version in step with the app release (the app and the PyPI
package are bumped in lockstep on each release tag).

## 5. Troubleshooting

| Symptom (in app) | Cause | Fix |
|---|---|---|
| "pocketshell not installed" / "server-side usage tracking unavailable" | `~/.local/bin` not on non-interactive SSH PATH | Step 2 (PATH above the `.bashrc` guard) |
| `pocketshell: command not found` over `ssh host 'pocketshell ...'` | not installed, or PATH | Step 1 + Step 2 |
| One provider shows an error, others OK | that provider's helper (e.g. `goz`) missing | install that helper, or ignore |
| Worked before, broke after dotfile edit | PATH line moved below the guard | Step 2 |
