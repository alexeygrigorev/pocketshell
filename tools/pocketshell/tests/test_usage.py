"""Unit tests for `pocketshell usage` (issue #1318).

Published ``quse==0.0.14`` emits a provider-keyed object whose records carry
``short_term`` / ``long_term`` windows. ``pocketshell usage --json`` performs
the one explicit producer-boundary translation into per-provider NDJSON with a
canonical ``windows`` map; it does not re-derive quota values, reset times, or
provider details. The app parser consumes only that canonical wire shape.

The tests use a live output captured from the published 0.0.14 wheel. The
unreleased ``a86959e`` six-provider/top-level-``windows`` producer is not part
of the fixture or contract.

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

# Load-bearing Grok auth UX (#2195): must mention signing in with `grok` on
# the host. Kept as a test-side literal so a generic/raw "no-credentials"
# rewrite cannot silently satisfy the assertion.
_EXPECTED_GROK_AUTH = (
    "Grok authentication is missing on this host. "
    "Sign in with `grok` on the host, then refresh usage."
)

_PYPROJECT = Path(__file__).resolve().parent.parent / "pyproject.toml"
# Captured LIVE from the published quse 0.0.14 wheel (`quse --json`) on
# 2026-08-22. The release still emits short_term/long_term and has five
# providers; see the adjacent .provenance.txt sidecar for the wheel digest.
_FIXTURE_0014 = Path(__file__).resolve().parent / "data" / "quse-0.0.14-usage.json"
_FIXTURE_0014_PROVENANCE = (
    Path(__file__).resolve().parent / "data" / "quse-0.0.14-usage.provenance.txt"
)
_LOCK = Path(__file__).resolve().parent.parent / "uv.lock"

_NULL_PUBLISHED_WINDOWS = {
    "short_term": {"percent_remaining": None, "reset_at": None, "window": None},
    "long_term": {"percent_remaining": None, "reset_at": None, "window": None},
}


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
    """The live published quse-0.0.14 provider-keyed document (5 providers)."""
    return _FIXTURE_0014.read_text()


# ---------------------------------------------------------------------------
# quse is a PINNED dependency, resolved next to sys.executable, never PATH
# ---------------------------------------------------------------------------


def test_pyproject_pins_quse_exactly() -> None:
    # AC (#2274): pocketshell pins the published quse==0.0.14 release. The
    # producer must remain aligned with that release's old short/long schema;
    # it must not claim the unreleased a86959e output.
    text = _PYPROJECT.read_text()
    assert '"quse==0.0.14"' in text, "pyproject must pin quse==0.0.14 in dependencies"
    assert '"quse==0.0.13"' not in text, "the 0.0.13 pin must not remain"
    lock = _LOCK.read_text()
    assert 'name = "quse"' in lock
    assert 'version = "0.0.14"' in lock
    assert 'specifier = "==0.0.14"' in lock
    assert 'specifier = "==0.0.13"' not in lock


def test_pyproject_documents_published_quse_0014_schema() -> None:
    """The dependency comment must describe the wheel we actually pin."""
    text = _PYPROJECT.read_text()
    assert "short_term" in text and "long_term" in text
    assert "REPLACED by one unified top-level" not in text
    assert "new `go`" not in text


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
# normalize_usage_stdout: published quse compatibility translation
# ---------------------------------------------------------------------------


def test_quse_0014_fixture_matches_published_contract() -> None:
    raw = json.loads(_quse_keyed_json())
    assert set(raw) == {"claude", "codex", "copilot", "grok", "zai"}
    assert "go" not in raw
    assert "Wheel SHA-256:" in _FIXTURE_0014_PROVENANCE.read_text()
    for record in raw.values():
        assert set(record) == {"details", "error", "long_term", "short_term", "status"}
        assert "windows" not in record


def test_flatten_emits_per_provider_ndjson_with_canonical_windows_and_iso_reset() -> None:
    # AC (#2274): the producer translates the REAL published quse-0.0.14
    # short_term/long_term records into the app-facing windows map. Provider
    # identity is injected from the object key and reset_at stays unchanged.
    out = normalize_usage_stdout(_quse_keyed_json())
    lines = [json.loads(ln) for ln in out.splitlines()]
    by_provider = {r["provider"]: r for r in lines}

    assert set(by_provider) == {"claude", "codex", "copilot", "grok", "zai"}

    claude = by_provider["claude"]
    assert set(claude["windows"]) == {"5h", "7d"}
    assert claude["windows"]["5h"]["percent_remaining"] == 99.0
    assert claude["windows"]["5h"]["reset_at"] == "2026-08-22T15:49:59Z"
    assert claude["windows"]["7d"]["percent_remaining"] == 93.0

    codex = by_provider["codex"]
    # Published quse's dropped short-term span has a null percentage. The
    # producer omits that non-renderable source span rather than emitting a
    # malformed canonical window that the Android parser would have to skip.
    assert set(codex["windows"]) == {"7d"}
    assert codex["windows"]["7d"]["percent_remaining"] == 56.0

    assert by_provider["copilot"]["windows"]["monthly"]["percent_remaining"] == 100.0
    assert by_provider["grok"]["windows"]["weekly"]["percent_remaining"] == 0.0
    assert by_provider["zai"]["windows"]["5h"]["percent_remaining"] == 100.0

    for record in lines:
        assert "provider" in record
        assert "short_term" not in record
        assert "long_term" not in record


def test_flatten_handles_single_provider_shape() -> None:
    keyed = json.dumps(
        {
            "codex": {
                "status": "ok",
                "short_term": {"percent_remaining": None, "reset_at": None, "window": None},
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
    assert record["windows"]["7d"]["percent_remaining"] == 88.0
    assert set(record["windows"]) == {"7d"}


def test_flatten_rejects_record_without_published_windows() -> None:
    keyed = json.dumps(
        {
            "codex": {
                "status": "error",
                "error": "usage unavailable",
                "details": {},
            }
        }
    )
    try:
        normalize_usage_stdout(keyed)
    except ValueError as exc:
        assert "short_term" in str(exc)
        assert "long_term" in str(exc)
    else:  # pragma: no cover - fail-loud contract
        raise AssertionError("a record without published windows must be rejected")


def test_flatten_translates_published_window_labels_without_rederiving_values() -> None:
    keyed = json.dumps(
        {
            "claude": {
                "status": "ok",
                "short_term": {
                    "percent_remaining": 59.0,
                    "reset_at": "2026-05-24T14:30:00Z",
                    "window": "5h",
                },
                "long_term": {
                    "percent_remaining": 15.0,
                    "reset_at": "2026-05-28T14:59:59Z",
                    "window": "7d",
                },
                "error": None,
                "details": {"anything": "the app ignores this"},
            }
        }
    )
    record = json.loads(normalize_usage_stdout(keyed).splitlines()[0])
    assert record["windows"] == {
        "5h": {"percent_remaining": 59.0, "reset_at": "2026-05-24T14:30:00Z"},
        "7d": {"percent_remaining": 15.0, "reset_at": "2026-05-28T14:59:59Z"},
    }
    assert record["details"] == {"anything": "the app ignores this"}


def test_flatten_rejects_unreleased_top_level_windows_schema() -> None:
    keyed = json.dumps(
        {
            "go": {
                "status": "ok",
                "windows": {"5h": {"percent_remaining": 100.0, "reset_at": None}},
            }
        }
    )
    try:
        normalize_usage_stdout(keyed)
    except ValueError as exc:
        assert "unsupported top-level 'windows'" in str(exc)
    else:  # pragma: no cover - fail-loud contract
        raise AssertionError("unreleased quse schema must not be accepted as published")


def test_flatten_quse_0014_codex_reset_credits_pass_through() -> None:
    # Fix-shape verification: the published 0.0.14 codex details entries carry
    # `{expires_at, status, title}` and the exact-match inventory fields the
    # app's strict parser validates — passed through untouched here.
    out = normalize_usage_stdout(_FIXTURE_0014.read_text())
    codex = next(
        json.loads(ln) for ln in out.splitlines()
        if json.loads(ln)["provider"] == "codex"
    )
    credits = codex["details"]["reset_credits"]
    assert len(credits) == 1
    assert credits[0]["status"] == "available"
    assert credits[0]["title"] == "Full reset"
    assert credits[0]["expires_at"] == "2026-09-21T00:13:17Z"
    assert codex["details"]["reset_credits_available"] == 1


def test_flatten_handles_grok_only_object() -> None:
    # AC: flattening a grok-only object `{"grok": {...}}` emits one NDJSON
    # line with provider "grok" and maps the published long-term window.
    keyed = json.dumps(
        {
            "grok": {
                "status": "ok",
                "short_term": {"percent_remaining": None, "reset_at": None, "window": None},
                "long_term": {
                    "percent_remaining": 95.0,
                    "reset_at": "2026-08-25T00:08:17Z",
                    "window": "weekly",
                },
                "error": None,
                "details": {"subscription": "SuperGrokPlus"},
            }
        }
    )
    out = normalize_usage_stdout(keyed)
    lines = out.splitlines()
    assert len(lines) == 1
    record = json.loads(lines[0])
    assert record["provider"] == "grok"
    assert record["windows"]["weekly"] == {
        "percent_remaining": 95.0,
        "reset_at": "2026-08-25T00:08:17Z",
    }


def test_flatten_passes_grok_both_windows_through_unchanged() -> None:
    # quse reports grok's weekly + monthly spans as short/long. Flatten must
    # retain the provider-owned labels.
    keyed = json.dumps(
        {
            "grok": {
                "status": "ok",
                "short_term": {
                    "percent_remaining": 62.5,
                    "reset_at": "2026-08-25T00:08:17Z",
                    "window": "weekly",
                },
                "long_term": {
                    "percent_remaining": 75.0,
                    "reset_at": "2026-09-01T00:00:00Z",
                    "window": "monthly",
                },
                "error": None,
                "details": {},
            }
        }
    )
    record = json.loads(normalize_usage_stdout(keyed).splitlines()[0])
    assert record["provider"] == "grok"
    assert record["windows"]["weekly"]["percent_remaining"] == 62.5
    assert record["windows"]["monthly"]["percent_remaining"] == 75.0


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
                **_NULL_PUBLISHED_WINDOWS,
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


def test_flatten_maps_grok_no_credentials_to_actionable_error() -> None:
    keyed = json.dumps(
        {
            "grok": {
                "status": "error",
                **_NULL_PUBLISHED_WINDOWS,
                "error": "no-credentials",
                "details": {},
            }
        }
    )
    record = json.loads(normalize_usage_stdout(keyed).splitlines()[0])
    assert record["error"] == _EXPECTED_GROK_AUTH
    assert "no-credentials" not in record["error"].lower()
    assert "Sign in with `grok` on the host" in record["error"]


def test_actionable_error_rewrites_grok_auth_json_miss() -> None:
    rewritten = _actionable_error("grok", "grok auth.json not found")
    assert rewritten == _EXPECTED_GROK_AUTH
    assert _actionable_error("grok", "no credentials") == _EXPECTED_GROK_AUTH
    assert _actionable_error("grok-build", "no-credentials") == _EXPECTED_GROK_AUTH
    # Idempotent — the rewritten message must not re-match.
    assert _actionable_error("grok", rewritten) == _EXPECTED_GROK_AUTH


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
    assert providers == ["claude", "codex", "copilot", "grok", "zai"]
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
    single = json.dumps(
        {
            "claude": {
                "status": "ok",
                **_NULL_PUBLISHED_WINDOWS,
                "error": None,
            }
        }
    )
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=single),
    ) as run, patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        result = runner.invoke(usage_command, ["claude", "--json", "--no-daemon"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/quse", "claude", "--json"]
    assert json.loads(result.output.splitlines()[0])["provider"] == "claude"


def test_usage_forwards_grok_provider_and_json() -> None:
    # AC: `pocketshell usage grok` is an accepted provider filter. Flatten of
    # the published short/long grok object is the load-bearing JSON contract;
    # the CLI must forward `grok` + `--json`.
    runner = CliRunner()
    single = json.dumps(
        {
            "grok": {
                "status": "ok",
                "short_term": {"percent_remaining": None, "reset_at": None, "window": None},
                "long_term": {
                    "percent_remaining": 95.0,
                    "reset_at": "2026-08-25T00:08:17Z",
                    "window": "weekly",
                },
                "error": None,
                "details": {},
            }
        }
    )
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=single),
    ) as run, patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        result = runner.invoke(usage_command, ["grok", "--json", "--no-daemon"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/quse", "grok", "--json"]
    record = json.loads(result.output.splitlines()[0])
    assert record["provider"] == "grok"
    assert record["windows"]["weekly"]["percent_remaining"] == 95.0


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
