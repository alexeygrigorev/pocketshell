"""Unit tests for `pocketshell usage` (issue #1318).

quse v0.0.9 is the single source of truth for the unified usage schema. Its
``--json`` output is a provider-keyed object; ``pocketshell usage --json``
FLATTENS it into per-provider NDJSON (one record per line, ``provider``
injected from the key, quse's unified fields passed through unchanged). There
is no downstream re-derivation of windows / resets / percentages — pocketshell
expects quse's exact schema and fails loudly on a mismatch (D22 hard-cut).

The tests stub ``pocketshell.usage._resolve_quse_binary`` and
``subprocess.run`` so they never invoke a real ``quse`` binary; the contract
under test is "pocketshell resolves the PINNED quse and flattens its schema
correctly", not "the provider check works".
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell.cli import cli, main
from pocketshell.usage import (
    _CLAUDE_USAGE_AUTH_SETUP_MESSAGE,
    _QUSE_MISSING_EXIT_CODE,
    _actionable_error,
    _resolve_quse_binary,
    normalize_usage_stdout,
    usage_command,
)

_PYPROJECT = Path(__file__).resolve().parent.parent / "pyproject.toml"
_FIXTURE = Path(__file__).resolve().parent / "data" / "quse-0.0.9-usage.json"


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


def _quse_keyed_json() -> str:
    """The real quse-0.0.9 provider-keyed --json document (4 providers)."""
    return _FIXTURE.read_text()


# ---------------------------------------------------------------------------
# quse is a PINNED dependency, resolved next to sys.executable, never PATH
# ---------------------------------------------------------------------------


def test_pyproject_pins_quse_exactly() -> None:
    # AC: pocketshell pins quse==0.0.9 as a hard dependency (frozen contract).
    text = _PYPROJECT.read_text()
    assert '"quse==0.0.9"' in text, "pyproject must pin quse==0.0.9 in dependencies"


def test_resolve_quse_binary_uses_pinned_env_next_to_interpreter(tmp_path: Path) -> None:
    # AC: `pocketshell usage` invokes the PINNED quse (next to sys.executable),
    # not PATH. A quse living next to the interpreter is resolved.
    bin_dir = tmp_path / "venv" / "bin"
    bin_dir.mkdir(parents=True)
    (bin_dir / "python").write_text("#!/bin/sh\n")
    pinned = bin_dir / "quse"
    pinned.write_text("#!/bin/sh\n")
    pinned.chmod(0o755)

    with patch.object(sys, "executable", str(bin_dir / "python")):
        resolved = _resolve_quse_binary()

    assert resolved == str(pinned)


def test_resolve_quse_binary_does_not_fall_back_to_path(tmp_path: Path) -> None:
    # AC: NO PATH fallback. A quse on PATH but NOT next to the interpreter must
    # NOT be resolved — absence next to sys.executable => None (fail loud).
    interp_dir = tmp_path / "venv" / "bin"
    interp_dir.mkdir(parents=True)
    (interp_dir / "python").write_text("#!/bin/sh\n")
    # A decoy quse on PATH in a different dir.
    path_dir = tmp_path / "elsewhere"
    path_dir.mkdir()
    decoy = path_dir / "quse"
    decoy.write_text("#!/bin/sh\n")
    decoy.chmod(0o755)

    with patch.object(sys, "executable", str(interp_dir / "python")), patch.dict(
        "os.environ", {"PATH": str(path_dir)}
    ):
        resolved = _resolve_quse_binary()

    assert resolved is None, "a PATH-only quse must not shadow the pinned copy"


# ---------------------------------------------------------------------------
# normalize_usage_stdout: thin flatten of quse's provider-keyed object
# ---------------------------------------------------------------------------


def test_flatten_emits_per_provider_ndjson_with_window_and_iso_reset() -> None:
    # AC: `pocketshell usage --json` fed quse-0.0.9 keyed JSON emits strict
    # per-provider NDJSON (provider + unified fields + window + ISO reset_at).
    out = normalize_usage_stdout(_quse_keyed_json())
    lines = [json.loads(ln) for ln in out.splitlines()]
    by_provider = {r["provider"]: r for r in lines}

    assert set(by_provider) == {"claude", "codex", "copilot", "zai"}

    claude = by_provider["claude"]
    assert claude["short_term"]["window"] == "5h"
    assert claude["short_term"]["reset_at"] == "2026-07-07T23:19:59Z"
    assert claude["short_term"]["percent_remaining"] == 91.0
    assert claude["long_term"]["window"] == "7d"
    assert claude["long_term"]["reset_at"] == "2026-07-09T14:59:59Z"

    codex = by_provider["codex"]
    assert codex["short_term"]["window"] == "5h"
    assert codex["long_term"]["window"] == "7d"

    copilot = by_provider["copilot"]
    assert copilot["long_term"]["window"] == "monthly"
    # quse passes null short-term window/reset through unchanged.
    assert copilot["short_term"]["window"] is None
    assert copilot["short_term"]["reset_at"] is None

    zai = by_provider["zai"]
    assert zai["short_term"]["window"] == "5h"
    assert zai["long_term"]["window"] == "weekly"

    # Every line carries an injected `provider` field.
    assert all("provider" in json.loads(ln) for ln in out.splitlines())


def test_flatten_handles_single_provider_shape() -> None:
    keyed = json.dumps(
        {
            "codex": {
                "status": "ok",
                "short_term": {"percent_remaining": 77.0, "reset_at": None, "window": "5h"},
                "long_term": {"percent_remaining": 88.0, "reset_at": None, "window": "7d"},
                "error": None,
                "details": {},
            }
        }
    )
    out = normalize_usage_stdout(keyed)
    lines = out.splitlines()
    assert len(lines) == 1
    record = json.loads(lines[0])
    assert record["provider"] == "codex"
    assert record["short_term"]["window"] == "5h"


def test_flatten_passes_unified_fields_through_unchanged() -> None:
    keyed = json.dumps(
        {
            "claude": {
                "status": "ok",
                "short_term": {"percent_remaining": 59.0, "reset_at": "2026-05-24T14:30:00Z", "window": "5h"},
                "long_term": {"percent_remaining": 15.0, "reset_at": "2026-05-28T14:59:59Z", "window": "7d"},
                "error": None,
                "details": {"anything": "the app ignores this"},
            }
        }
    )
    record = json.loads(normalize_usage_stdout(keyed).splitlines()[0])
    # Quota math + spans are passed through verbatim (no re-derivation).
    assert record["short_term"] == {
        "percent_remaining": 59.0,
        "reset_at": "2026-05-24T14:30:00Z",
        "window": "5h",
    }
    assert record["long_term"] == {
        "percent_remaining": 15.0,
        "reset_at": "2026-05-28T14:59:59Z",
        "window": "7d",
    }


def test_flatten_raises_on_non_json() -> None:
    try:
        normalize_usage_stdout("this is not json")
    except ValueError as exc:
        assert "valid JSON" in str(exc)
    else:  # pragma: no cover - fail-loud contract
        raise AssertionError("expected ValueError on non-JSON stdout")


def test_flatten_raises_on_non_object_top_level() -> None:
    try:
        normalize_usage_stdout('[{"provider": "codex"}]')
    except ValueError as exc:
        assert "provider-keyed JSON object" in str(exc)
    else:  # pragma: no cover
        raise AssertionError("expected ValueError on a non-object top-level payload")


def test_flatten_raises_on_non_object_provider_value() -> None:
    try:
        normalize_usage_stdout('{"codex": "not-an-object"}')
    except ValueError as exc:
        assert "codex" in str(exc)
    else:  # pragma: no cover
        raise AssertionError("expected ValueError on a non-object provider value")


def test_flatten_blank_passes_through() -> None:
    assert normalize_usage_stdout("") == ""
    assert normalize_usage_stdout("   \n") == "   \n"


# ---------------------------------------------------------------------------
# _actionable_error: genuine error-message UX (kept), applied in the flatten
# ---------------------------------------------------------------------------


def test_flatten_maps_claude_401_to_actionable_error() -> None:
    keyed = json.dumps(
        {
            "claude": {
                "status": "error",
                "short_term": None,
                "long_term": None,
                "error": "HTTP Error 401: Unauthorized",
                "details": {},
            }
        }
    )
    record = json.loads(normalize_usage_stdout(keyed).splitlines()[0])
    assert record["error"] == _CLAUDE_USAGE_AUTH_SETUP_MESSAGE
    assert "HTTP Error 401" not in record["error"]


def test_actionable_error_is_idempotent() -> None:
    # The rewritten friendly message must not re-match the auth patterns.
    once = _actionable_error("claude", "HTTP Error 401: Unauthorized")
    twice = _actionable_error("claude", once)
    assert once == _CLAUDE_USAGE_AUTH_SETUP_MESSAGE
    assert twice == _CLAUDE_USAGE_AUTH_SETUP_MESSAGE


def test_claude_stale_auth_telemetry_error_is_usage_unavailable() -> None:
    stale_error = (
        "Claude Code authentication "
        + "failed on this host. Run `claude "
        + "/login` in the host shell."
    )
    assert _actionable_error("claude", stale_error) == _CLAUDE_USAGE_AUTH_SETUP_MESSAGE


# ---------------------------------------------------------------------------
# CLI wiring: forwards to the pinned quse and flattens JSON output
# ---------------------------------------------------------------------------


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


def test_usage_json_flattens_quse_keyed_object() -> None:
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=_quse_keyed_json()),
    ) as run, patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        result = runner.invoke(usage_command, ["--json", "--no-daemon"])
    assert result.exit_code == 0, result.output
    providers = [json.loads(ln)["provider"] for ln in result.output.splitlines()]
    assert providers == ["claude", "codex", "copilot", "zai"]
    # Args forwarded to the pinned quse subprocess must include `--json`.
    invoked: Sequence[str] = run.call_args.args[0]
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
    single = json.dumps({"claude": {"status": "ok", "short_term": None, "long_term": None, "error": None}})
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=single),
    ) as run, patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        result = runner.invoke(usage_command, ["claude", "--json", "--no-daemon"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/quse", "claude", "--json"]
    assert json.loads(result.output.splitlines()[0])["provider"] == "claude"


def test_usage_fails_loud_when_pinned_quse_missing() -> None:
    # AC: quse missing => packaging-integrity error, fail loud (NOT a PATH nag,
    # NOT exit 127 which the app reserves for "pocketshell not found").
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value=None), patch(
        "pocketshell.usage.subprocess.run"
    ) as run, patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        result = runner.invoke(usage_command, ["--json", "--no-daemon"], catch_exceptions=False)
    assert result.exit_code == _QUSE_MISSING_EXIT_CODE
    assert result.exit_code != 127
    assert "quse" in result.output.lower()
    assert "reinstall pocketshell" in result.output.lower()
    run.assert_not_called()


def test_usage_proxies_nonzero_exit_from_quse() -> None:
    runner = CliRunner()
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stderr="error: unknown provider\n", returncode=2),
    ):
        result = runner.invoke(usage_command, ["wat"])
    assert result.exit_code == 2
    assert "unknown provider" in result.output


def test_main_returns_int_on_success() -> None:
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=_quse_keyed_json()),
    ), patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        exit_code = main(["usage", "--json", "--no-daemon"])
    assert isinstance(exit_code, int)
    assert exit_code == 0


def test_main_returns_nonzero_on_unknown_subcommand() -> None:
    exit_code = main(["bogus-subcommand-that-does-not-exist"])
    assert exit_code != 0
