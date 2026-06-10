"""`pocketshell github` subcommand group.

First slice of the Git + GitHub integration epic
([#644](https://github.com/alexeygrigorev/pocketshell/issues/644), slice 1 is
[#645](https://github.com/alexeygrigorev/pocketshell/issues/645)).

Server-side foundation: report whether the GitHub CLI (`gh`) is **installed**
and **authenticated** as structured JSON the Android app can consume to gate
GitHub features and show a "configure gh" hint when the tooling is missing.

Unlike `pocketshell sessions`/`usage`, which proxy another binary's output
byte-for-byte, this command generates its own JSON envelope. The shape is:

```json
{
  "installed": true,
  "authenticated": true,
  "account": "alexeygrigorev",
  "hint": null
}
```

- `installed` — `shutil.which("gh")` found the binary on PATH.
- `authenticated` — `gh auth status` exited 0 (a token is present and valid).
- `account` — the logged-in GitHub username parsed from `gh auth status`,
  or `null` when it could not be determined / not authenticated.
- `hint` — a short, actionable string when something is missing (install `gh`,
  or run `gh auth login`), or `null` when everything is ready.

Why subprocess (`gh auth status`) instead of reading `~/.config/gh/hosts.yml`
directly: `gh auth status` is the canonical liveness/validity check the user
themselves would run, it validates the token rather than just checking that a
config file exists, and it keeps this command decoupled from gh's on-disk
layout. No network call beyond what `gh auth status` itself performs (a single
token-validity check); the command does NOT hit the GitHub API.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from typing import Optional

import click

# Hints surfaced to the user (and the app) when tooling is missing. Kept as
# module constants so the test suite can assert on the exact wording.
INSTALL_HINT = (
    "install gh (https://cli.github.com) and run `gh auth login`"
)
AUTH_HINT = "run `gh auth login` to authenticate the GitHub CLI"

# `gh auth status` prints e.g.
#   ✓ Logged in to github.com account alexeygrigorev (keyring)
# across gh versions. Capture the username after "account ".
_ACCOUNT_RE = re.compile(r"account\s+(\S+)")


def _resolve_gh_binary() -> Optional[str]:
    """Locate the `gh` CLI on PATH, or return ``None`` if absent.

    Pulled out as a function so the unit suite can monkeypatch it.
    `shutil.which` returns the same path the user would see from
    `command -v gh`, which is the probe the app's bootstrap already runs.
    """
    return shutil.which("gh")


def _run_gh_auth_status(gh_path: str) -> subprocess.CompletedProcess:
    """Invoke ``gh auth status`` and capture its output.

    Modern `gh` writes the status report to stdout on success and to
    stderr when unauthenticated; we capture both and parse whichever
    carries the text. The exit code is the authoritative auth signal.
    """
    return subprocess.run(
        [gh_path, "auth", "status"],
        check=False,
        capture_output=True,
        text=True,
    )


def _parse_account(text: str) -> Optional[str]:
    """Best-effort extract of the logged-in GitHub username from gh output."""
    match = _ACCOUNT_RE.search(text)
    if match:
        return match.group(1)
    return None


def gh_status() -> dict[str, object]:
    """Build the structured gh install/auth status envelope.

    Returns a plain dict with the stable shape documented at module level so
    both the CLI (`--json`) and any in-process caller can reuse it.
    """
    gh_path = _resolve_gh_binary()
    if gh_path is None:
        return {
            "installed": False,
            "authenticated": False,
            "account": None,
            "hint": INSTALL_HINT,
        }

    completed = _run_gh_auth_status(gh_path)
    authenticated = completed.returncode == 0
    # `gh auth status` puts the report on stdout (authed) or stderr
    # (unauthed) depending on version; scan both so account parsing is
    # robust across gh releases.
    combined = (completed.stdout or "") + "\n" + (completed.stderr or "")
    account = _parse_account(combined) if authenticated else None

    return {
        "installed": True,
        "authenticated": authenticated,
        "account": account,
        "hint": None if authenticated else AUTH_HINT,
    }


@click.group(
    name="github",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "GitHub CLI (`gh`) integration helpers.\n\n"
        "`status` reports whether `gh` is installed and authenticated as "
        "structured JSON the PocketShell app uses to gate GitHub features "
        "and prompt the user to configure `gh` when it is missing (issue "
        "#645)."
    ),
)
def github_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


@github_group.command(
    "status",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option(
    "--json",
    "as_json",
    is_flag=True,
    help="Emit the status as a single-line JSON object (consumed by the app).",
)
@click.pass_context
def github_status(ctx: click.Context, as_json: bool) -> None:
    """Report whether `gh` is installed and authenticated.

    With ``--json`` emits a one-line JSON object the app parses to gate
    GitHub features; without it prints a short human-readable summary. The
    command always exits 0 — "gh missing" / "not authenticated" are normal,
    reportable states, not errors, so the app can poll the status without
    treating it as a failed probe.
    """
    status = gh_status()
    if as_json:
        click.echo(json.dumps(status, sort_keys=True))
        return

    if not status["installed"]:
        click.echo("gh: not installed")
        click.echo(f"hint: {status['hint']}")
        return
    if not status["authenticated"]:
        click.echo("gh: installed, not authenticated")
        click.echo(f"hint: {status['hint']}")
        return
    account = status["account"] or "(unknown account)"
    click.echo(f"gh: installed, authenticated as {account}")
