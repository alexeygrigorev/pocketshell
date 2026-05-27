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
