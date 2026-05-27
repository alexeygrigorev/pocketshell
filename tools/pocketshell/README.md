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
