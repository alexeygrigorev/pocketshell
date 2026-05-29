# pocketshell

Unified server-side Python utility for the [PocketShell](https://github.com/alexeygrigorev/pocketshell)
Android client. Replaces the separately-installed `quse` and `tmuxctl`
utilities the app currently probes for on every remote host.

This first release ships the **skeleton plus the `pocketshell usage`
subcommand only**. Follow-up rounds will add `jobs`, `agent-log`,
`sessions`, `repos`, and an optional daemon mode. See
[issue #170](https://github.com/alexeygrigorev/pocketshell/issues/170) for
the design spike and phased roll-out plan.

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

```text
pocketshell usage           # human-readable lines, one per provider
pocketshell usage --json    # machine-readable JSON (consumed by the app)
pocketshell usage codex     # filter to a single provider
```

The output shape is byte-identical to `quse [provider] [--json]` so any
consumer that already parses `quse` output keeps working when the app
routes through `pocketshell usage` instead. Under the hood the first
release delegates to the `quse` CLI via subprocess; later rounds will
fold the provider-detection logic in directly and drop the subprocess
hop.

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

`install` is idempotent (running twice adds nothing new). Handler scripts
and the event bus live under `~/.cache/pocketshell/hooks/` (override with
`$POCKETSHELL_HOOKS_DIR`); each handler appends a normalized record
`{ts, engine, state, source, session_id, cwd, ...}` to
`events.jsonl`.

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

The event bus (`events.jsonl`) is preserved on uninstall so
already-emitted records stay readable; only the generated handler
scripts are cleaned up.

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

The tests stub `quse.usage.collect_usage` so they run in seconds without
hitting any provider API.

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
scripts/check-pypi-version.sh --check-tag v0.3.0
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

The PocketShell app previously probed for two binaries (`quse`,
`tmuxctl`) on every host. That meant two installs to keep up to date,
two probes to surface failures from, and two PATH-discovery edge cases
(see [issue #41](https://github.com/alexeygrigorev/pocketshell/issues/41)).
A single `pocketshell` binary collapses those into one install, one
probe, one bootstrap row. The app keeps detecting `quse` and `tmuxctl`
as a parallel path while `pocketshell` ramps up to feature parity; once
parity is reached, the legacy probes are removed in a hard-cut follow-up
(no compat shim — see decision D22 in `docs/decisions.md`).
