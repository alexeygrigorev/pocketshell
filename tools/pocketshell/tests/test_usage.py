"""Unit tests for `pocketshell usage`.

The first PR exercises:
 - `pocketshell --help` lists the `usage` subcommand.
 - `pocketshell usage --help` shows the click usage line.
 - `pocketshell usage --json` forwards through to `quse --json`.
 - `pocketshell usage <provider>` forwards the positional arg.
 - Missing `quse` produces a friendly stderr message + exit 127.
 - stdout/stderr/exit-code from the subprocess are proxied verbatim.

The tests stub `pocketshell.usage._resolve_quse_binary` and
`subprocess.run` so they never invoke a real `quse` binary; the contract
under test is "pocketshell delegates correctly to whatever quse exists
on the host", not "the provider check works".
"""

from __future__ import annotations

import json
import subprocess
from typing import Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell.cli import cli, main
from pocketshell.usage import usage_command


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


def _fake_json_payload() -> str:
    return json.dumps(
        {
            "claude": {
                "plan": "Pro",
                "session_used_percent": 12.5,
                "session_window_resets_at": "2026-05-27T15:00:00Z",
            },
            "codex": {
                "plan": "Plus",
                "session_used_percent": 4.0,
                "session_window_resets_at": "2026-05-27T16:00:00Z",
            },
        },
        indent=2,
        sort_keys=True,
    )


def test_top_level_help_lists_usage_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0, result.output
    assert "usage" in result.output


def test_usage_help_does_not_call_quse() -> None:
    runner = CliRunner()
    with patch("pocketshell.usage.subprocess.run") as run, patch(
        "pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"
    ):
        result = runner.invoke(usage_command, ["--help"])
    assert result.exit_code == 0, result.output
    assert "provider" in result.output.lower()
    run.assert_not_called()


def test_usage_json_forwards_to_quse_and_proxies_stdout() -> None:
    payload = _fake_json_payload()
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=payload),
    ) as run:
        result = runner.invoke(usage_command, ["--json"])
    assert result.exit_code == 0, result.output
    # The stdout we got back must be the exact bytes `quse --json` emitted.
    assert result.output == payload
    # Args forwarded to the quse subprocess must include `--json`.
    call_args = run.call_args
    invoked: Sequence[str] = call_args.args[0]
    assert invoked == ["/fake/quse", "--json"]


def test_usage_forwards_provider_argument() -> None:
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout="claude — 12.5% used\n"),
    ) as run:
        result = runner.invoke(usage_command, ["claude"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/quse", "claude"]
    assert "claude" in result.output


def test_usage_forwards_provider_and_json_flag_together() -> None:
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout="{\n  \"claude\": {}\n}\n"),
    ) as run:
        result = runner.invoke(usage_command, ["claude", "--json"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/quse", "claude", "--json"]


def test_usage_returns_127_when_quse_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value=None), patch(
        "pocketshell.usage.subprocess.run"
    ) as run:
        result = runner.invoke(usage_command, ["--json"], catch_exceptions=False)
    # 127 is the POSIX exit code for "command not found" and matches the
    # signal `UsageRemoteSource.fetchUsage` already special-cases on the
    # Kotlin side.
    assert result.exit_code == 127, result.output
    # The friendly message lands on stderr; CliRunner mixes stdout+stderr
    # by default but `mix_stderr=False` is removed in click>=8.2, so just
    # check that the install hint was emitted somewhere.
    assert "quse" in result.output.lower()
    run.assert_not_called()


def test_usage_proxies_nonzero_exit_from_quse() -> None:
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stderr="error: unknown provider\n", returncode=2),
    ):
        result = runner.invoke(usage_command, ["wat"])
    assert result.exit_code == 2
    # stderr from the subprocess must reach the user (otherwise debugging
    # a failing provider would be opaque).
    assert "unknown provider" in result.output


def test_main_returns_int_on_success() -> None:
    """`main` is the canonical entrypoint for the console-script and
    `python -m pocketshell`. It must translate Click's exit-code object
    into a plain int and never raise SystemExit through to the caller.
    """
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=_fake_json_payload()),
    ):
        exit_code = main(["usage", "--json"])
    assert isinstance(exit_code, int)
    assert exit_code == 0


def test_main_returns_nonzero_on_unknown_subcommand() -> None:
    exit_code = main(["bogus-subcommand-that-does-not-exist"])
    assert exit_code != 0
