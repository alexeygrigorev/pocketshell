# pocketshell

Unified server-side Python utility for the [PocketShell](https://github.com/alexeygrigorev/pocketshell)
Android client. The app probes for this single helper on each remote
host and uses its subcommands for usage, tmux session/job metadata,
agent conversations, QR host setup, repository discovery, environment
files, hooks, logs, and daemon lifecycle checks.

## Install

The recommended path is `uv tool install`, which lands the binary on PATH
under `~/.local/bin/`:

```bash
uv tool install pocketshell
```

For local development from a clone:

```bash
cd tools/pocketshell
uv venv
uv pip install -e .
pocketshell --help
```

`pipx install pocketshell` works the same way for users who prefer
pipx. Both install paths produce a `pocketshell` binary that the
PocketShell app's bootstrap probe detects.

### Optional extras

`pocketshell qr-share` requires the `qrcode[pil]` package (Pillow) to
render QR images. Because Pillow is heavy and not needed by any other
subcommand, it ships behind an optional `qr` extra:

```bash
uv tool install pocketshell --with qrcode[pil]
# or
pip install pocketshell[qr]
```

Without the extra, every other subcommand keeps working; only
`pocketshell qr-share` exits 127 with a friendly install hint.

## Usage

Top-level commands in the current helper:

```text
pocketshell usage [provider] [--json]       # provider quota / usage
pocketshell send --pane %3 --token <id>     # acknowledged pane delivery
pocketshell sessions list [--by activity]   # tmux session summaries
pocketshell jobs ...                        # tmux recurring jobs
pocketshell agent-log ...                   # agent conversation logs
pocketshell repos list ...                  # local / GitHub repositories
pocketshell github status [--json]          # gh install / auth state
pocketshell env ...                         # .env / .envrc management
pocketshell hooks ...                       # Claude/Codex/OpenCode hooks
pocketshell logs ...                        # server-side trace sink
pocketshell daemon ...                      # IPC daemon lifecycle
pocketshell serve --dir PATH [--port N]     # foreground static HTTP server
pocketshell qr-share ...                    # SSH host QR import payloads
```

Run `pocketshell --help` or `pocketshell <command> --help` for the live
flag set. Some parity subcommands still proxy through the existing host
tools internally so their output remains byte-identical to what the app
already parses.

### `pocketshell send`

Deliver a payload into an exact tmux pane, **exactly once per token**. The
exit status IS the acknowledgement — the client no longer has to read the
terminal screen and guess whether its prompt landed (issue #2122, epic
#2121).

```bash
printf 'summarise the diff' | pocketshell send --pane %3 --token <row-id> --enter
pocketshell send --prune-older-than 30d
```

The payload is read from **stdin as raw bytes** and delivered byte-exact
(`load-buffer -` → `paste-buffer -d -r`, never argv). This command does not
add bracketed-paste markers: the client already frames its payload, and
framing twice put the inner markers into the receiving program as literal
text (issue #1854). Callers that want bracketed paste write the framed bytes
to stdin.

Exit codes are stable. Both renderings below — this table and `--help` — are
generated from the one `EXIT_CODE_TABLE` the code exits with, so neither can
drift from the other (issue #2153; regenerate with
`tools/pocketshell/scripts/sync-readme-exit-codes.py`, pinned by a test in the
`Python utility tests (pocketshell)` check):

<!-- BEGIN GENERATED: send exit codes (source: EXIT_CODE_TABLE in pocketshell/send.py) -->

| Exit | stdout reason | Meaning |
| ---- | ------------- | ------- |
| 0 | `delivered` \| `already-delivered` \| `pruned` | Success. 'delivered' = injected by THIS call and journaled. 'already-delivered' = the token was already journaled, nothing was injected. 'pruned' = --prune-older-than removed N records. |
| 2 | `bad-usage` | Invalid or missing arguments. Nothing was injected or journaled. |
| 3 | `pane-not-found` | The pane id does not exist on the tmux server, or it is dead. Nothing was injected; the token is NOT journaled and stays retryable. |
| 4 | `tmux-failed` | tmux is missing, no server is running, or a tmux command returned a definitive failure. This call put NOTHING into the pane and recorded no delivery, and it left the journal exactly as it found it: a claim this call took is released, and a pre-existing unresolved record it overwrote under --resend-interrupted is restored byte-for-byte. A retry therefore cannot duplicate — but 'unchanged' is not 'absent': if the token was already journaled-unresolved it still is, and the next plain call answers 'send-interrupted' rather than injecting. |
| 5 | `send-interrupted` \| `journal-corrupt` | Delivery is genuinely UNKNOWN and the token is left journaled-unresolved, so no plain call will ever inject it again. Two ways in. Either a PREVIOUS attempt died without an answer (or left an unreadable record) and its owning process is gone, in which case this call injected nothing; or THIS call got past the point of no return — tmux accepted the paste and then the Enter failed, or the delivery could not be journaled — in which case the payload may ALREADY be in the pane. Never auto-retry either reading. Re-run with --resend-interrupted only to accept a possible duplicate. |
| 6 | `timeout` | A tmux invocation exceeded --timeout. If the timeout hit at or after the commit the token is left in the unknown state above and the payload may ALREADY be in the pane; a retry then reports 'send-interrupted' rather than injecting again. |
| 7 | `journal-failed` | The durable token journal could not be read or written (permissions, disk). Nothing was injected — the journal is written BEFORE the pane is touched precisely so this failure is safe. |
| 8 | `send-in-progress` | Another send for this token is STILL RUNNING (its process is alive on this host). Nothing was injected by this call and nothing is unknown: the outcome is owned by that call. Retry shortly to read the answer — it will be 'delivered'/'already-delivered' or, if that process dies, 'send-interrupted'. --resend-interrupted does not override this: there is no unknown to resolve while the owner is alive, and forcing one would duplicate the payload. |

<!-- END GENERATED: send exit codes -->

(`pruned` prints the record count after the reason word: `pruned <n>`.)

**The paste is the point of no return, and that is the boundary between exit 4
and exit 5** (issue #2136). A client that branches on this table to decide
whether to auto-retry (#2124) needs one property, and it is exactly what exit 4
now means:

> **Exit 4 ⇒ this call put nothing into the pane, and left the journal exactly
> as it found it.**

Every exit-4 site satisfies it. Failures at the pane lookup or while filling
the paste buffer happen before the journal is written at all — nothing injected,
token unclaimed, cleanly retryable; this is the ordinary case. A definitive
`paste-buffer` failure (tmux answered "no", or tmux became unexecutable before
the paste) rolls this call's claim back: for a plain call that returns the token
to absent, and under `--resend-interrupted` the *pre-existing* unresolved record
is restored byte-for-byte rather than erased.

Note what "unchanged" does **not** mean. It does not mean "absent": a token that
was already journaled-unresolved still is, so the next plain call answers exit 5
rather than injecting. A retry after exit 4 can therefore never duplicate, but
it is not guaranteed to inject.

**Exit 5 in detail** — it carries two different facts, and a client must treat
both the same way (do not auto-retry; surface the choice):

- A **previous** attempt for this token died without an answer, or left an
  unreadable record, and its owning process is gone. This call injected nothing.
- **This** call got past the paste. tmux accepted the payload, so it **is** in
  the pane, and then either the `send-keys Enter` failed or the delivery could
  not be journaled. The claim is deliberately kept — rolling it back is what
  would let a plain retry paste the payload a second time.

Both readings leave the token journaled-unresolved, so the invariant is the same
for both: no plain call will ever inject it again, and `--resend-interrupted` is
the explicit opt-in that accepts a possible duplicate.

The `Enter` step has two failure shapes — tmux answers non-zero, or tmux stops
being executable between the paste and the `send-keys` — and they leave
byte-identical state. They report one outcome (exit 5). Until #2136 the second
reported exit 4, which told a client the pane was untouched while the payload
was sitting in it.

stdout is machine-readable: the first whitespace-delimited token is one of
the reasons above; human detail goes to stderr. Every retry path drains stdin
before exiting, so a caller piping a payload never takes SIGPIPE on a
successful acknowledgement. (Argument validation runs *before* stdin is read,
deliberately: a caller with an open-but-idle stdin gets `bad-usage`
immediately instead of blocking on a payload that will never arrive.)

**Durability invariant: at-most-once, except on an explicit opt-in.** A token
is never injected a second time unless the caller passes
`--resend-interrupted` on the injecting call itself; no sequence of failures,
kills, races or automatic housekeeping can turn an injected token back into a
state a plain call will inject. The journal under
`${XDG_STATE_HOME:-~/.local/state}/pocketshell/sends/` is two-phase — a
`pending` record is written (atomically, fsync'd) immediately before the one
command that can put bytes into the pane, then promoted to `delivered` once
tmux answers. A definitive tmux failure rolls that claim back, so ordinary
errors stay cleanly retryable; rolling back means undoing *this* call, so a
record this call created is removed and a pre-existing unresolved record it
overwrote is restored byte-for-byte rather than erased.

An unresolved record is then read against its owner process: gone ⇒ a previous
attempt died and delivery is genuinely unknown (exit 5, resolvable with
`--resend-interrupted`, so the state is never absorbing); still running ⇒
nothing is unknown and the outcome belongs to that call (exit 8, retryable).
`--resend-interrupted` does not override a live owner — there is no unknown to
resolve, and forcing one would simply duplicate the payload.

The invariant's honest edges: a definitive non-zero from `paste-buffer` is
taken as proof nothing reached the pane; the journal directory must survive
(delete it and the memory is gone); `--prune-older-than` is an operator
action that *can* clear unresolved records; and exit 8 is bounded by the owner
process's **liveness**, not by the owner's `--timeout`. A suspended owner
(reproduced with `SIGSTOP`) holds its token in `send-in-progress` for as long
as it stays stopped, because the liveness probe asks whether the process still
exists, not whether it is making progress, and `--timeout` bounds the tmux
calls of the process that passed it rather than some other process's lifetime.
This fails safe — the payload is never duplicated and the token never becomes
absorbing once the owner dies — and a client cannot reach it through its own
use, since it would have to suspend its own in-flight send. Nothing reaps a
suspended owner.

Records carry a timestamp and are pruned two ways: explicitly with
`--prune-older-than <30d|12h|90m|3600s>`, and automatically on delivery at a
**30-day default retention** (throttled to at most once every 6 h), so the
directory cannot grow without bound even if pruning is never invoked. The
automatic sweep only removes **resolved** records — ageing an unknown out of
the journal would silently make the token injectable again.

### `pocketshell usage`

```text
pocketshell usage           # human-readable lines, one per provider
pocketshell usage --json    # machine-readable JSON (consumed by the app)
pocketshell usage codex     # filter to a single provider
```

The output shape is byte-identical to `quse [provider] [--json]`. When
the IPC daemon is running, `usage --json` dispatches `usage.fetch` over
the daemon socket and uses the daemon's short TTL cache; otherwise an
absent/unavailable daemon or explicitly supported method skew falls through
to the one-shot subprocess path. Timeout, malformed-response, and
daemon-internal failures are surfaced instead of being retried locally.

All daemon-backed wrappers (`usage`, `repos`, `tree`, `jobs`, `sessions`, and
`agents kind`) use one typed fallback boundary. It emits the safe
`pocketshell.daemon_call` event with `reason`, `method`, `phase`, RPC code, and
available CLI/daemon versions. It never logs RPC parameters or command output.

If `quse` is not installed, `pocketshell usage` exits with code 127 and
prints an install hint to stderr.

### `pocketshell repos list`

Enumerate git repositories — either cloned on this host (`--local`) or
owned by the authenticated GitHub user (`--remote`). The two modes
share one unified JSON schema so a future merged view can interleave
them transparently.

```bash
pocketshell repos list --local            # scan ~/git for clones (human)
pocketshell repos list --local --json     # same, JSON output
pocketshell repos list --remote --json    # via owner-only `gh api user/repos`
pocketshell repos list --remote --limit 20
```

Schema (every entry):

```json
{
  "owner": "alexeygrigorev",          // null when remote URL is non-GitHub
  "name": "pocketshell",              // local dir basename, or GH repo name
  "full_name": "alexeygrigorev/pocketshell",  // null when owner unknown
  "local": {                          // populated by --local scans
    "path": "/home/alexey/git/pocketshell",
    "head": "main"
  },
  "remote": {                         // populated by --remote scans
    "default_branch": "main",
    "html_url": "https://github.com/alexeygrigorev/pocketshell",
    "ssh_url": "git@github.com:alexeygrigorev/pocketshell.git",
    "updated_at": "2026-05-27T12:00:00Z"
  }
}
```

`--local` scans `~/git` by default (override with one or more `--root`
flags or the colon-separated `POCKETSHELL_REPOS_ROOTS` env var) and
populates `local` for every entry. `owner` and `full_name` are
best-effort from the parsed `remote.origin.url`; non-GitHub remotes
leave them `null`.

`--remote` delegates to `gh api 'user/repos?affiliation=owner&sort=updated' --paginate --slurp`.
Requires `gh` on PATH (`apt install gh` on Debian/Ubuntu,
`brew install gh` on macOS) authenticated via
`gh auth login -s repo:read`. Sorted by `updated_at` descending so the
picker shows the most-recently-touched repos first. Missing `gh` exits
127 with an install hint; a non-zero `gh` exit (auth missing,
rate-limit, etc.) propagates the exit code and stderr verbatim.

With neither flag, defaults to `--local` and prints a one-line
discoverability hint mentioning `--remote`.

Daemon mode caches `repos.list_local` for 10 s and `repos.list_remote`
for 5 min. `--no-daemon` forces the in-process path; `--no-cache`
forces the daemon to re-run upstream on the next call.

### `pocketshell github status`

Reports whether the GitHub CLI (`gh`) is installed and authenticated, as
structured JSON the app consumes to gate GitHub features and prompt the
user to configure `gh` when it is missing (epic #644, slice #645).

```bash
pocketshell github status          # human-readable summary
pocketshell github status --json   # machine-readable JSON (consumed by the app)
```

Schema:

```json
{
  "installed": true,             // shutil.which("gh") found the binary
  "authenticated": true,         // `gh auth status` exited 0
  "account": "alexeygrigorev",   // logged-in username, or null
  "hint": null                   // actionable hint when something is missing
}
```

The command always exits 0 — "gh missing" and "not authenticated" are
normal, reportable states (not probe failures), so the app can poll the
status without treating it as an error. When `gh` is absent the `hint` tells
the user to install it and run `gh auth login`; when present but
unauthenticated the `hint` tells them to run `gh auth login`. The only
network access is whatever `gh auth status` itself performs (a token-validity
check); the command does NOT call the GitHub API.

### `pocketshell serve`

Serve a folder over HTTP for a client-owned SSH port forward:

```bash
pocketshell serve --dir /path/to/site
pocketshell serve --dir /path/to/site --port 8080 --bind 127.0.0.1
```

The server binds `127.0.0.1` by default. Omitting `--port` (or passing
`--port 0`) lets the OS select a free port; after binding, stdout contains
exactly one stable JSON line with the selected port:

```json
{"port":43123}
```

The process stays in the foreground so the caller owns its lifetime: keep the
SSH exec channel alive while the site is needed and terminate that process
when the view closes or the connection is lost. There is no detached server
registry or `--stop` command in this contract. HTTP access logs and errors go
to stderr, keeping stdout parseable.

Requests serve static files with stdlib MIME detection. A directory resolves
to its `index.html` when present; paths are resolved before the containment
check, so parent traversal and symlinks that leave the selected directory are
rejected rather than served.

### `pocketshell qr-share`

Builds a `pocketshell.ssh-import.v1` payload from an `~/.ssh/config`
alias (resolved via `ssh -G`) or from explicit flags, wraps it in one or
more `pocketshell.qr.v1` chunked envelopes (matching the Kotlin
`QrChunkCodec` byte-for-byte), and emits QR codes for the phone-side
scanner to consume (issue #129).

```bash
pocketshell qr-share prod                           # ssh-config alias
pocketshell qr-share --host h --user u --key ~/.ssh/id_ed25519 --name h
pocketshell qr-share prod --png --out-dir /tmp/qr   # write PNGs
pocketshell qr-share prod --print-only --id deadbeef  # debug envelopes
```

When stdout is a TTY the QRs are drawn inline as Unicode blocks; between
multi-part transmissions the command pauses on "Press Enter for next
QR" so the user can scan each in turn. When stdout is not a TTY (or
`--png` is passed) a numbered PNG sequence (`qr-share-01.png`,
`qr-share-02.png`, ...) is written to `--out-dir`.

Requires the optional `qr` extra (see [Optional extras](#optional-extras)).
Without it, the command exits 127 with the install hint and every other
subcommand keeps working.

#### Running from a repo clone (no install)

To run `qr-share` straight from a checkout without installing the tool,
use `uv run` from `tools/pocketshell` and include the `qr` extra:

```bash
cd tools/pocketshell
uv run --extra qr pocketshell qr-share prod
```

The first run creates `.venv` and installs the QR dependency; later runs
are instant. Run it in an interactive terminal so stdout is a TTY and the
QR renders inline — otherwise it falls back to writing PNGs (add
`--png --out-dir ./qr` to force PNGs). Omitting `--extra qr` makes the
command exit 127 with the install hint.

### `pocketshell hooks`

Installs agent **stop / idle-detection** hooks across Claude Code,
Codex, and OpenCode and normalizes their events into a single
append-only JSONL bus the app can read back. Server-side only;
integration only — no "tell the agent to continue" action yet (deferred;
see issue #267 and locked decision **D26** in `docs/decisions.md`).

```bash
pocketshell hooks install [--engine claude|codex|opencode|all]   # default: all
pocketshell hooks status  [--engine ...] [--json] [--last N]
pocketshell hooks events  [--since ISO8601] [--limit N] [--json]
pocketshell hooks uninstall [--engine ...]
```

`install` is **non-destructive — it merges, it never clobbers**:

- **Claude Code** — adds a `{type: "command", command: "python3 <handler>"}`
  entry under the `Stop`, `SubagentStop`, and `Notification` hook events
  in `~/.claude/settings.json`, only when absent. All other top-level
  keys and any pre-existing user hooks are preserved.
- **Codex** — sets the top-level `notify` program in `~/.codex/config.toml`
  to our handler (Codex hooks do not fire under `codex exec`, so `notify`
  is the headless-safe signal). If `notify` is already set to something
  else, it warns and **skips** rather than overwriting. The rest of the
  TOML is preserved.
- **OpenCode** — drops a `pocketshell-idle-signal.js` plugin into
  `~/.config/opencode/plugin/` without disturbing other plugins.

`install` is idempotent (running twice adds nothing new). Generated handler
scripts and `.installed` ownership metadata are durable data under
`$XDG_DATA_HOME/pocketshell/hooks/` (default
`~/.local/share/pocketshell/hooks/`). The volatile event bus stays at
`$XDG_CACHE_HOME/pocketshell/hooks/events.jsonl` (default
`~/.cache/pocketshell/hooks/events.jsonl`). A routine cache cleanup therefore
starts a fresh bus without breaking the absolute commands retained by Claude or
Codex; the next event recreates the cache directory and bus.

Path overrides are intentionally separate:

- `$POCKETSHELL_HOOKS_HANDLER_DIR` overrides the durable generated-handler dir.
- `$POCKETSHELL_HOOKS_EVENTS_FILE` overrides the event bus file.
- The historical `$POCKETSHELL_HOOKS_DIR` remains an alias for the **handler
  directory only** when the new handler variable is unset. It no longer moves
  the bus. Use both new variables and rerun `hooks install` when both paths need
  customization.

Each generated handler embeds the resolved bus path and appends a normalized
record `{ts, engine, state, source, session_id, cwd, ...}` there. `install`
also migrates PocketShell-owned Claude/Codex commands from the old cache path to
the durable path even when cache cleanup already removed the old scripts;
foreign hooks and foreign Codex `notify` programs remain untouched.

**Per-engine uninstall** (`pocketshell hooks uninstall`) removes only what
we added and is idempotent:

- **Claude Code** — drops our command group from each hook event; an
  event key (and the top-level `hooks` object) is deleted only if we
  created it and it ends up empty. A user's pre-existing hooks always
  survive, so a pre-populated `settings.json` comes back
  byte-equivalent for the unrelated parts.
- **Codex** — removes the top-level `notify` line only when it still
  points at our handler. A `notify` the user pointed elsewhere is left
  alone.
- **OpenCode** — deletes our plugin file; other plugins and the dir
  itself are left in place.

The event bus (`events.jsonl`) is preserved on uninstall so already-emitted
records stay readable; only PocketShell-owned current/legacy config entries,
generated executables, and durable ownership metadata are cleaned up.

## Development

```bash
cd tools/pocketshell
uv venv
uv pip install -e ".[dev]"
uv run pytest
```

Or via the dependency-group:

```bash
uv sync --group dev
uv run pytest
```

The tests stub `pocketshell.usage.subprocess.run` (and the `quse`/`tmuxctl`
binary resolvers) so they run in seconds without invoking any real binary or
hitting a provider API.

## Release flow

`pocketshell` ships in lockstep with the Android app. Every time the
maintainer cuts an Android release tag (`vX.Y.Z`), the
[`Build`](../../.github/workflows/build.yml) workflow assembles the APK
and **also** builds the Python sdist + wheel and publishes them to PyPI.

### Version coupling

Two files must agree on the release version:

- `app/build.gradle.kts` -> `versionName = "X.Y.Z"`
- `tools/pocketshell/pyproject.toml` -> `version = "X.Y.Z"`

[`scripts/check-pypi-version.sh`](../../scripts/check-pypi-version.sh)
enforces this. The release workflow runs it with `--check-tag vX.Y.Z`
before publishing, so a tag pushed with mismatched versions fails the
job loudly before anything reaches PyPI.

Run it locally before tagging:

```bash
scripts/check-pypi-version.sh                  # local match check
scripts/check-pypi-version.sh --check-tag vX.Y.Z
```

### Bumping a release

1. Pick the next semantic version after the latest GitHub Release/tag.
2. Update **both** version sources in the same commit:
   - `app/build.gradle.kts` -> bump `versionName` (and `versionCode`).
   - `tools/pocketshell/pyproject.toml` -> bump `version` to the
     same value as `versionName`.
3. Run `scripts/check-pypi-version.sh` to confirm they match.
4. Commit the bump on `main`, push, and run the emulator release
   validation gate (`scripts/release-emulator-validation.sh`) as
   described in [`process.md`](../../process.md) -> "Release Builds".
5. Push the tag with `scripts/push-release-tag.sh`. The tag-triggered
   `Build` workflow then:
   - builds and uploads the APK + creates the GitHub Release
   - runs `scripts/check-pypi-version.sh --check-tag vX.Y.Z`
   - builds the Python sdist + wheel
   - publishes them to PyPI via OIDC trusted publishing

The PyPI publish job depends on the APK build job, so a broken APK
build also aborts the PyPI publish. If only the PyPI publish fails the
maintainer can re-trigger the workflow at the same tag from the
Actions tab; the APK build is idempotent against an existing release
(`softprops/action-gh-release` updates the existing release rather
than failing).

## PyPI trusted publishing setup (one-time)

The `publish-pypi` job uses GitHub's OIDC token instead of a long-lived
API token. This avoids storing a `PYPI_API_TOKEN` secret in the repo
and means there is nothing to rotate. The trade-off is that the
project owner must complete one configuration step on pypi.org before
the first automated tag publish:

1. Sign in to https://pypi.org/ with the project owner account.
2. Open the `pocketshell` project page ->
   **Manage** -> **Publishing**.
3. Under **Trusted publishers**, click **Add a new pending publisher**
   (if the project is empty) or **Add a new publisher**, then fill in:
   - **PyPI Project Name**: `pocketshell`
   - **Owner**: `alexeygrigorev`
   - **Repository name**: `pocketshell`
   - **Workflow name**: `build.yml`
   - **Environment name**: `pypi`
4. Save the publisher.
5. In this repository on GitHub, open
   **Settings** -> **Environments** -> **New environment** -> name it
   `pypi`. No secrets or reviewers are required; the environment exists
   purely to scope the OIDC token. (If the environment already exists,
   confirm it has no protection rules that would block the workflow
   from running.)
6. Push the next release tag. The `Publish to PyPI via trusted
   publishing` step should succeed without any token configuration.

### Why trusted publishing (and not `PYPI_API_TOKEN`)?

- No long-lived secret to rotate, leak, or accidentally print in logs.
- The OIDC subject is scoped to `repo=alexeygrigorev/pocketshell`,
  `workflow=build.yml`, `environment=pypi`, so a compromised fork or
  a different workflow file in this repo cannot reuse it.
- D22 (no backwards-compat): we do not also maintain a token-fallback
  path. If trusted publishing breaks, fix it; do not add a token
  branch alongside.

If trusted publishing is ever unavailable for a tag (e.g. PyPI outage
on the OIDC verifier), the recommended manual escape hatch is:

```bash
cd tools/pocketshell
python -m build
python -m twine upload dist/*
```

with the maintainer's account. Do not re-add a `PYPI_API_TOKEN` secret
as a permanent fallback.

## Why a unified CLI?

The PocketShell app previously depended on multiple host-side tools.
That meant separate installs to keep up to date, separate probes to
surface failures from, and multiple PATH-discovery edge cases. A single
`pocketshell` binary collapses that app-facing contract into one install,
one probe, and one bootstrap row. The Android bootstrap probe now derives
PATH from the user's shell rc and prepends `$HOME/.local/bin`,
`$HOME/bin`, and `$HOME/.cargo/bin` before probing, so cloned-repo or
venv installs can be discovered without a manual app-side PATH field.
