"""Unit tests for `pocketshell github status`.

First slice of the Git + GitHub integration epic (#644 / #645). Exercises:

- `pocketshell --help` lists the `github` subcommand.
- `pocketshell github --help` lists the `status` subcommand.
- gh missing (`shutil.which` -> None): installed=False, authenticated=False,
  install hint present, account null.
- gh installed but unauthenticated (`gh auth status` exit != 0):
  installed=True, authenticated=False, auth hint present, account null.
- gh installed and authenticated (`gh auth status` exit 0): installed=True,
  authenticated=True, account parsed, hint null.
- The `--json` envelope shape is stable and machine-parseable.
- The command exits 0 in every state (missing/unauthed are reportable
  states, not probe failures).
- Human (non-JSON) output for each state.

The tests stub `pocketshell.github._resolve_gh_binary` and
`subprocess.run` so they never invoke a real `gh` binary or hit the
network; the contract under test is "pocketshell reports gh state
correctly", not "the GitHub CLI works".
"""

from __future__ import annotations

import json
import subprocess
from typing import Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell.cli import cli
from pocketshell.github import (
    AUTH_HINT,
    INSTALL_HINT,
    gh_status,
    github_group,
)


def _fake_completed(
    stdout: str = "",
    stderr: str = "",
    returncode: int = 0,
) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(
        args=[],
        returncode=returncode,
        stdout=stdout,
        stderr=stderr,
    )


# Realistic `gh auth status` output for an authenticated host (gh 2.x puts
# this on stdout with exit 0).
_AUTHED_STDOUT = (
    "github.com\n"
    "  ✓ Logged in to github.com account alexeygrigorev "
    "(/home/alexey/.config/gh/hosts.yml)\n"
    "  - Active account: true\n"
    "  - Git operations protocol: ssh\n"
    "  - Token scopes: 'repo', 'read:org'\n"
)

# `gh auth status` when no token is configured: non-zero exit, text on stderr.
_UNAUTHED_STDERR = (
    "You are not logged into any GitHub hosts. "
    "To log in, run: gh auth login\n"
)


# ----- help wiring ---------------------------------------------------


def test_top_level_help_lists_github_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0, result.output
    assert "github" in result.output


def test_github_help_lists_status_subcommand() -> None:
    runner = CliRunner()
    with patch("pocketshell.github.subprocess.run") as run:
        result = runner.invoke(github_group, ["--help"])
    assert result.exit_code == 0, result.output
    assert "status" in result.output
    run.assert_not_called()


# ----- gh missing ----------------------------------------------------


def test_status_json_when_gh_missing() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.github._resolve_gh_binary", return_value=None
    ), patch("pocketshell.github.subprocess.run") as run:
        result = runner.invoke(github_group, ["status", "--json"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload == {
        "installed": False,
        "authenticated": False,
        "account": None,
        "hint": INSTALL_HINT,
    }
    # gh missing -> we must NOT have tried to run `gh auth status`.
    run.assert_not_called()


def test_status_human_when_gh_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.github._resolve_gh_binary", return_value=None):
        result = runner.invoke(github_group, ["status"])
    assert result.exit_code == 0, result.output
    assert "not installed" in result.output
    assert INSTALL_HINT in result.output


# ----- gh installed but unauthenticated ------------------------------


def test_status_json_when_installed_unauthed() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.github._resolve_gh_binary", return_value="/fake/gh"
    ), patch(
        "pocketshell.github.subprocess.run",
        return_value=_fake_completed(stderr=_UNAUTHED_STDERR, returncode=1),
    ) as run:
        result = runner.invoke(github_group, ["status", "--json"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload == {
        "installed": True,
        "authenticated": False,
        "account": None,
        "hint": AUTH_HINT,
    }
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/gh", "auth", "status"]


def test_status_human_when_installed_unauthed() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.github._resolve_gh_binary", return_value="/fake/gh"
    ), patch(
        "pocketshell.github.subprocess.run",
        return_value=_fake_completed(stderr=_UNAUTHED_STDERR, returncode=1),
    ):
        result = runner.invoke(github_group, ["status"])
    assert result.exit_code == 0, result.output
    assert "not authenticated" in result.output
    assert AUTH_HINT in result.output


# ----- gh installed and authenticated --------------------------------


def test_status_json_when_installed_authed() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.github._resolve_gh_binary", return_value="/fake/gh"
    ), patch(
        "pocketshell.github.subprocess.run",
        return_value=_fake_completed(stdout=_AUTHED_STDOUT, returncode=0),
    ) as run:
        result = runner.invoke(github_group, ["status", "--json"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload == {
        "installed": True,
        "authenticated": True,
        "account": "alexeygrigorev",
        "hint": None,
    }
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/gh", "auth", "status"]


def test_status_human_when_installed_authed() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.github._resolve_gh_binary", return_value="/fake/gh"
    ), patch(
        "pocketshell.github.subprocess.run",
        return_value=_fake_completed(stdout=_AUTHED_STDOUT, returncode=0),
    ):
        result = runner.invoke(github_group, ["status"])
    assert result.exit_code == 0, result.output
    assert "authenticated as alexeygrigorev" in result.output


def test_status_authed_account_parsed_from_stderr_variant() -> None:
    """Older gh writes the report to stderr even when exit code is 0.

    Account parsing must scan both streams, so a stderr-only authed report
    still yields the username.
    """
    runner = CliRunner()
    with patch(
        "pocketshell.github._resolve_gh_binary", return_value="/fake/gh"
    ), patch(
        "pocketshell.github.subprocess.run",
        return_value=_fake_completed(stderr=_AUTHED_STDOUT, returncode=0),
    ):
        result = runner.invoke(github_group, ["status", "--json"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["authenticated"] is True
    assert payload["account"] == "alexeygrigorev"


def test_status_authed_without_parseable_account_yields_null() -> None:
    """Authenticated but no parseable 'account <name>' -> account null, still authed."""
    runner = CliRunner()
    with patch(
        "pocketshell.github._resolve_gh_binary", return_value="/fake/gh"
    ), patch(
        "pocketshell.github.subprocess.run",
        return_value=_fake_completed(stdout="Logged in to github.com\n", returncode=0),
    ):
        result = runner.invoke(github_group, ["status", "--json"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["authenticated"] is True
    assert payload["account"] is None
    assert payload["hint"] is None


# ----- JSON shape contract -------------------------------------------


def test_json_envelope_shape_has_exact_keys() -> None:
    """The app depends on a fixed key set; pin it so a rename is caught."""
    runner = CliRunner()
    with patch(
        "pocketshell.github._resolve_gh_binary", return_value="/fake/gh"
    ), patch(
        "pocketshell.github.subprocess.run",
        return_value=_fake_completed(stdout=_AUTHED_STDOUT, returncode=0),
    ):
        result = runner.invoke(github_group, ["status", "--json"])
    payload = json.loads(result.output)
    assert set(payload.keys()) == {
        "installed",
        "authenticated",
        "account",
        "hint",
    }
    # Single-line output so the app can read one line off stdout.
    assert result.output.strip().count("\n") == 0


# ----- in-process helper ---------------------------------------------


def test_gh_status_helper_returns_dict_without_gh() -> None:
    """The reusable `gh_status()` helper works independent of the CLI layer."""
    with patch("pocketshell.github._resolve_gh_binary", return_value=None):
        status = gh_status()
    assert status == {
        "installed": False,
        "authenticated": False,
        "account": None,
        "hint": INSTALL_HINT,
    }
