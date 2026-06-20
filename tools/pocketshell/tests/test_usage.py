"""Unit tests for `pocketshell usage`.

The first PR exercises:
 - `pocketshell --help` lists the `usage` subcommand.
 - `pocketshell usage --help` shows the click usage line.
 - `pocketshell usage --json` forwards through to `quse --json` and
   normalizes provider quirks into PocketShell's schema.
 - `pocketshell usage <provider>` forwards the positional arg.
 - Missing `quse` produces a friendly stderr message + exit 127.
 - stdout/stderr/exit-code from the subprocess are proxied verbatim.

The tests stub `pocketshell.usage._resolve_quse_binary` and
`subprocess.run` so they never invoke a real `quse` binary; the contract
under test is "pocketshell delegates correctly to whatever quse exists
on the host and repairs known schema mismatches", not "the provider
check works".
"""

from __future__ import annotations

import json
import subprocess
from datetime import datetime, timezone
from typing import Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell.cli import cli, main
from pocketshell.usage import (
    _CLAUDE_USAGE_AUTH_SETUP_MESSAGE,
    _actionable_error,
    normalize_usage_record,
    usage_command,
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
    # Non-provider-shaped JSON is left untouched.
    assert result.output == payload
    # Args forwarded to the quse subprocess must include `--json`.
    call_args = run.call_args
    invoked: Sequence[str] = call_args.args[0]
    assert invoked == ["/fake/quse", "--json"]


def test_usage_json_normalizes_codex_detail_windows_and_epoch_resets() -> None:
    raw = "\n".join(
        [
            json.dumps(
                {
                    "provider": "codex",
                    "status": "ok",
                    "short_term": {"percent_remaining": 100.0, "reset_at": None},
                    "long_term": {"percent_remaining": 69.0, "reset_at": None},
                    "block_reason": None,
                    "error": None,
                    "details": {
                        "limit_reached": False,
                        "windows": {
                            "primary_window": {
                                "used_percent": 12,
                                "limit_window_seconds": 18000,
                                "reset_at": 1780828285,
                            },
                            "secondary_window": {
                                "used_percent": 31,
                                "limit_window_seconds": 604800,
                                "reset_at": 1781137638,
                            },
                        },
                    },
                },
            ),
            json.dumps(
                {
                    "provider": "claude",
                    "status": "error",
                    "short_term": {"percent_remaining": None, "reset_at": None},
                    "long_term": {"percent_remaining": None, "reset_at": None},
                    "block_reason": None,
                    "error": "HTTP Error 401: Unauthorized",
                    "details": {},
                },
            ),
        ],
    )
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=raw + "\n"),
    ):
        result = runner.invoke(usage_command, ["--json"])

    assert result.exit_code == 0, result.output
    lines = [json.loads(line) for line in result.output.splitlines()]
    codex = lines[0]
    assert codex["short_term"] == {
        "percent_remaining": 88.0,
        "reset_at": "2026-06-07T10:31:25Z",
        "window": "5h",
    }
    assert codex["long_term"] == {
        "percent_remaining": 69.0,
        "reset_at": "2026-06-11T00:27:18Z",
        "window": "7d",
    }
    assert lines[1]["error"] == _CLAUDE_USAGE_AUTH_SETUP_MESSAGE
    assert "claude " + "/login" not in lines[1]["error"]
    assert "authentication " + "failed" not in lines[1]["error"].lower()
    assert "HTTP Error 401" not in lines[1]["error"]


def test_normalize_claude_seeds_concrete_5h_and_7d_window_spans() -> None:
    # #800: Claude Code's windows are the same concrete 5h/7d spans as Codex.
    # quse emits them with no `window` span, so normalize seeds the canonical
    # span so the app frames them as "5h window" / "7d window".
    record = {
        "provider": "claude",
        "status": "ok",
        "short_term": {"percent_remaining": 59.0, "reset_at": "2026-05-24T14:30:00Z"},
        "long_term": {"percent_remaining": 15.0, "reset_at": "2026-05-28T14:59:59Z"},
        "block_reason": None,
        "error": None,
        "details": {},
    }

    normalized = normalize_usage_record(record)

    assert normalized["short_term"]["window"] == "5h"
    assert normalized["long_term"]["window"] == "7d"
    # The quota math is untouched.
    assert normalized["short_term"]["percent_remaining"] == 59.0
    assert normalized["long_term"]["percent_remaining"] == 15.0


def test_normalize_copilot_long_term_is_monthly_not_7d() -> None:
    # #800: Copilot's long-term quota is a monthly cycle — keep its real
    # cadence, NOT a 7d window. Its short-term window stays generic.
    record = {
        "provider": "copilot",
        "status": "ok",
        "short_term": {"percent_remaining": 100.0, "reset_at": None},
        "long_term": {"percent_remaining": 96.6, "reset_at": "2026-07-01T00:00:00Z"},
        "block_reason": None,
        "error": None,
        "details": {},
    }

    normalized = normalize_usage_record(record)

    assert normalized["long_term"]["window"] == "monthly"
    assert normalized["long_term"]["window"] != "7d"
    # Short-term keeps no forced span (renders the generic humanized label).
    assert normalized["short_term"].get("window") is None


def test_normalize_zai_keeps_generic_window_spans() -> None:
    # #800 regression guard: providers with unknown spans must NOT be forced
    # into 5h/7d/monthly — leave `window` unset so the app humanizes them.
    record = {
        "provider": "zai",
        "status": "ok",
        "short_term": {"percent_remaining": 100.0, "reset_at": "2026-05-27T10:31:58Z"},
        "long_term": {"percent_remaining": 100.0, "reset_at": None},
        "block_reason": None,
        "error": None,
        "details": {},
    }

    normalized = normalize_usage_record(record)

    assert normalized["short_term"].get("window") is None
    assert normalized["long_term"].get("window") is None


def test_normalize_claude_keeps_detail_window_span_when_present() -> None:
    # When quse DOES carry a concrete span via limit_window_seconds, the
    # detail span wins over the default seed (still 5h/7d for Claude here).
    record = {
        "provider": "claude",
        "status": "ok",
        "short_term": {"percent_remaining": None, "reset_at": None},
        "long_term": {"percent_remaining": None, "reset_at": None},
        "block_reason": None,
        "error": None,
        "details": {
            "windows": {
                "five_hour": {"used_percent": 40, "limit_window_seconds": 18000},
                "seven_day": {"used_percent": 10, "limit_window_seconds": 604800},
            },
        },
    }

    normalized = normalize_usage_record(record)

    assert normalized["short_term"]["window"] == "5h"
    assert normalized["long_term"]["window"] == "7d"
    assert normalized["short_term"]["percent_remaining"] == 60.0
    assert normalized["long_term"]["percent_remaining"] == 90.0


def test_claude_stale_auth_telemetry_error_is_usage_unavailable() -> None:
    stale_error = (
        "Claude Code authentication "
        + "failed on this host. Run `claude "
        + "/login` in the host shell."
    )

    assert _actionable_error("claude", stale_error) == _CLAUDE_USAGE_AUTH_SETUP_MESSAGE


def test_usage_json_patches_codex_resets_from_source_when_quse_dropped_them() -> None:
    raw = json.dumps(
        {
            "provider": "codex",
            "status": "ok",
            "short_term": {"percent_remaining": 100.0, "reset_at": None},
            "long_term": {"percent_remaining": 69.0, "reset_at": None},
            "block_reason": None,
            "error": None,
            "details": {
                "limit_reached": False,
                "windows": {
                    "primary_window": {"used_percent": 13, "reset_at": None},
                    "secondary_window": {"used_percent": 31, "reset_at": None},
                },
            },
        },
    )
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=raw + "\n"),
    ), patch(
        "pocketshell.usage._fetch_codex_detail_windows",
        return_value={
            "primary_window": {
                "used_percent": 13,
                "limit_window_seconds": 18000,
                "reset_at": 1780828285,
            },
            "secondary_window": {
                "used_percent": 31,
                "limit_window_seconds": 604800,
                "reset_at": 1781137638,
            },
        },
    ):
        result = runner.invoke(usage_command, ["--json"])

    assert result.exit_code == 0, result.output
    codex = json.loads(result.output)
    assert codex["short_term"] == {
        "percent_remaining": 87.0,
        "reset_at": "2026-06-07T10:31:25Z",
        "window": "5h",
    }
    assert codex["long_term"] == {
        "percent_remaining": 69.0,
        "reset_at": "2026-06-11T00:27:18Z",
        "window": "7d",
    }


def test_usage_json_normalizes_openai_compatible_detail_windows() -> None:
    raw = json.dumps(
        {
            "provider": "openai",
            "status": "ok",
            "short_term": {"percent_remaining": 100.0, "reset_at": None},
            "long_term": {"percent_remaining": 35.0, "reset_at": None},
            "block_reason": None,
            "error": None,
            "details": {
                "windows": {
                    "primary_window": {
                        "used_percent": 22,
                        "limit_window_seconds": 18000,
                        "reset_at": "2026-06-08T02:19:59Z",
                    },
                    "secondary_window": {
                        "used_percent": 65,
                        "limit_window_seconds": 604800,
                        "reset_at": "2026-06-11T00:27:17Z",
                    },
                },
            },
        },
    )
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=raw + "\n"),
    ):
        result = runner.invoke(usage_command, ["--json"])

    assert result.exit_code == 0, result.output
    record = json.loads(result.output)
    assert record["short_term"] == {
        "percent_remaining": 78.0,
        "reset_at": "2026-06-08T02:19:59Z",
        "window": "5h",
    }
    assert record["long_term"] == {
        "percent_remaining": 35.0,
        "reset_at": "2026-06-11T00:27:17Z",
        "window": "7d",
    }


def test_normalize_usage_record_preserves_codex_reset_after_seconds() -> None:
    record = normalize_usage_record(
        {
            "provider": "codex",
            "status": "ok",
            "short_term": {"percent_remaining": 35.0, "reset_at": None},
            "long_term": {"percent_remaining": 69.0, "reset_at": None},
            "block_reason": None,
            "error": None,
            "details": {
                "windows": {
                    "primary_window": {
                        "used_percent": 65,
                        "window_minutes": 300,
                        "reset_after_seconds": 3600,
                    },
                    "secondary_window": {
                        "used_percent": 31,
                        "limit_window_seconds": 604800,
                        "reset_after_seconds": "604800",
                    },
                },
            },
        },
        now=datetime(2026, 6, 8, 10, 0, tzinfo=timezone.utc),
    )

    assert record["short_term"] == {
        "percent_remaining": 35.0,
        "reset_at": "2026-06-08T11:00:00Z",
        "window": "5h",
    }
    assert record["long_term"] == {
        "percent_remaining": 69.0,
        "reset_at": "2026-06-15T10:00:00Z",
        "window": "7d",
    }


def test_normalize_usage_record_converts_top_level_reset_after_seconds() -> None:
    with patch("pocketshell.usage._fetch_codex_detail_windows", return_value=None):
        record = normalize_usage_record(
            {
                "provider": "codex",
                "status": "ok",
                "short_term": {
                    "percent_remaining": 35.0,
                    "reset_at": None,
                    "reset_after_seconds": 3600,
                    "window": "5h",
                },
                "long_term": None,
                "block_reason": None,
                "error": None,
                "details": {},
            },
            now=datetime(2026, 6, 8, 10, 0, tzinfo=timezone.utc),
        )

    assert record["short_term"] == {
        "percent_remaining": 35.0,
        "reset_at": "2026-06-08T11:00:00Z",
        "window": "5h",
    }


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
