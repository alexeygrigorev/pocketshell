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

# Load-bearing Grok auth UX (#2195): must mention signing in with `grok` on
# the host. Kept as a test-side literal so a generic/raw "no-credentials"
# rewrite cannot silently satisfy the assertion.
_EXPECTED_GROK_AUTH = (
    "Grok authentication is missing on this host. "
    "Sign in with `grok` on the host, then refresh usage."
)

_PYPROJECT = Path(__file__).resolve().parent.parent / "pyproject.toml"
_FIXTURE = Path(__file__).resolve().parent / "data" / "quse-0.0.9-usage.json"
# Captured LIVE from the pinned quse 0.0.11 (`quse --json`) on 2026-07-15 —
# the exact Codex "no 5h window" shape (#1564): Codex temporarily removed the
# 5h window, so its `primary_window` now carries the WEEKLY (604800s) span and
# `secondary_window` is `present: false`. quse 0.0.11 labels the primary from
# its actual `limit_window_seconds` (→ `short_term.window == "7d"`, NOT the old
# positional "5h") and emits the dropped window as a null placeholder
# (`long_term: {percent_remaining: null, ...}`) instead of a phantom
# "0% / unavailable" ghost row. Claude / Copilot / zai in the same document
# keep their correct 5h / 7d / monthly / weekly labels (class coverage).
_FIXTURE_0011 = Path(__file__).resolve().parent / "data" / "quse-0.0.11-usage.json"
# Captured LIVE from host quse 0.0.13 (`quse --json`) on 2026-08-18 — the
# first release that emits a `grok` provider (alias `grok-build`). Live
# shape is weekly-only: null short_term, weekly long_term. Claude / Codex /
# Copilot / zai stay in the same document (class coverage).
_FIXTURE_0013 = Path(__file__).resolve().parent / "data" / "quse-0.0.13-usage.json"
_LOCK = Path(__file__).resolve().parent.parent / "uv.lock"


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
    # AC (#2195): pocketshell pins quse==0.0.13 as a hard dependency so the
    # usage panel can see Grok. Reverting the pin to 0.0.11 must redden this
    # assertion (G6). 0.0.13 is the first quse that emits a `grok` provider.
    text = _PYPROJECT.read_text()
    assert '"quse==0.0.13"' in text, "pyproject must pin quse==0.0.13 in dependencies"
    assert '"quse==0.0.11"' not in text, "the 0.0.11 pin must not remain"
    lock = _LOCK.read_text()
    assert 'name = "quse"' in lock
    assert 'version = "0.0.13"' in lock
    assert 'specifier = "==0.0.13"' in lock
    assert 'specifier = "==0.0.11"' not in lock


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


def test_flatten_codex_0011_no_5h_window_weekly_only_no_ghost() -> None:
    """#1564: quse 0.0.11 fixes Codex's mislabeled/ghost windows at the source.

    Feeds the REAL captured quse 0.0.11 Codex "no 5h window" shape through the
    flatten and asserts the corrected wire shape the app consumes:

    - `short_term` is the WEEKLY window labeled **"7d"** (from Codex's real
      `limit_window_seconds=604800`) with its real reset — NOT the old phantom
      "5h" window that showed weekly data under a 5h label with a 5-day reset.
    - `long_term` is a null placeholder (`percent_remaining: null`) for the
      window Codex DROPPED — the app parser treats a null-percent window as
      absent, so there is NO "0% / unavailable" ghost row.

    The flatten passes quse's unified fields through verbatim (D22 / #1318):
    pocketshell does NOT relabel — the correct labels come from quse 0.0.11.
    """
    out = normalize_usage_stdout(_FIXTURE_0011.read_text())
    by_provider = {r["provider"]: r for r in (json.loads(ln) for ln in out.splitlines())}

    codex = by_provider["codex"]
    # The single real Codex window is the WEEKLY one, labeled "7d" — no phantom 5h.
    assert codex["short_term"]["window"] == "7d"
    assert codex["short_term"]["window"] != "5h"
    assert codex["short_term"]["reset_at"] == "2026-07-21T20:37:32Z"
    assert codex["short_term"]["percent_remaining"] == 69.0
    # The dropped window is a null placeholder → the app parser omits it (no
    # "0% / unavailable" ghost row). percent_remaining MUST be null here.
    assert codex["long_term"]["percent_remaining"] is None
    assert codex["long_term"]["window"] is None
    assert codex["long_term"]["reset_at"] is None


def test_flatten_codex_0011_leaves_other_providers_unchanged() -> None:
    """#1564 class coverage: the quse Codex fix does NOT regress other cards.

    Claude keeps its 5h + 7d windows, Copilot keeps its monthly window, and zai
    keeps its 5h + weekly windows — all labeled correctly in the same quse
    0.0.11 document. Only Codex's window shape changed.
    """
    out = normalize_usage_stdout(_FIXTURE_0011.read_text())
    by_provider = {r["provider"]: r for r in (json.loads(ln) for ln in out.splitlines())}

    claude = by_provider["claude"]
    assert claude["short_term"]["window"] == "5h"
    assert claude["short_term"]["reset_at"] == "2026-07-15T11:39:59Z"
    assert claude["long_term"]["window"] == "7d"
    assert claude["long_term"]["reset_at"] == "2026-07-16T14:59:59Z"

    copilot = by_provider["copilot"]
    assert copilot["long_term"]["window"] == "monthly"
    assert copilot["long_term"]["percent_remaining"] == 97.1

    zai = by_provider["zai"]
    assert zai["short_term"]["window"] == "5h"
    assert zai["long_term"]["window"] == "weekly"


def test_flatten_quse_0013_emits_grok_and_passes_windows_through() -> None:
    """#2195: quse 0.0.13 adds grok; flatten must emit it unchanged.

    Feeds the REAL captured quse 0.0.13 document (weekly-only grok: null
    short_term, weekly long_term) and asserts the wire shape the app consumes.
    Reverting the pin without a grok line in the 0.0.13 fixture reddens the
    provider set. The four historical providers stay present.
    """
    out = normalize_usage_stdout(_FIXTURE_0013.read_text())
    by_provider = {r["provider"]: r for r in (json.loads(ln) for ln in out.splitlines())}

    assert set(by_provider) == {"claude", "codex", "copilot", "grok", "zai"}
    grok = by_provider["grok"]
    assert grok["provider"] == "grok"
    assert grok["status"] == "ok"
    # Live 0.0.13 weekly-only shape: unused term is a null placeholder.
    assert grok["short_term"] == {
        "percent_remaining": None,
        "reset_at": None,
        "window": None,
    }
    assert grok["long_term"] == {
        "percent_remaining": 95.0,
        "reset_at": "2026-08-25T00:08:17Z",
        "window": "weekly",
    }
    # Historical four-provider cards stay in the same document.
    assert by_provider["claude"]["short_term"]["window"] == "5h"
    assert by_provider["claude"]["long_term"]["window"] == "7d"
    assert by_provider["copilot"]["long_term"]["window"] == "monthly"
    assert by_provider["zai"]["long_term"]["window"] == "weekly"


def test_flatten_handles_grok_only_object() -> None:
    # AC: flattening a grok-only object `{"grok": {...}}` emits one NDJSON
    # line with provider "grok" and passes weekly/monthly fields through.
    keyed = json.dumps(
        {
            "grok": {
                "status": "ok",
                "short_term": {
                    "percent_remaining": None,
                    "reset_at": None,
                    "window": None,
                },
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
    assert record["long_term"] == {
        "percent_remaining": 95.0,
        "reset_at": "2026-08-25T00:08:17Z",
        "window": "weekly",
    }
    assert record["short_term"]["percent_remaining"] is None
    assert record["short_term"]["window"] is None


def test_flatten_passes_grok_both_windows_through_unchanged() -> None:
    # quse maps weekly SuperGrok + monthly credit to short_term / long_term
    # when both windows are present. Flatten must not relabel or drop them.
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
    assert record["short_term"] == {
        "percent_remaining": 62.5,
        "reset_at": "2026-08-25T00:08:17Z",
        "window": "weekly",
    }
    assert record["long_term"] == {
        "percent_remaining": 75.0,
        "reset_at": "2026-09-01T00:00:00Z",
        "window": "monthly",
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


def test_flatten_maps_grok_no_credentials_to_actionable_error() -> None:
    keyed = json.dumps(
        {
            "grok": {
                "status": "error",
                "short_term": None,
                "long_term": None,
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


def test_usage_forwards_grok_provider_and_json() -> None:
    # AC: `pocketshell usage grok` is an accepted provider filter (quse 0.0.13
    # already accepts it once pinned). Flatten of the grok-only object is the
    # load-bearing JSON contract; the CLI must forward `grok` + `--json`.
    runner = CliRunner()
    single = json.dumps(
        {
            "grok": {
                "status": "ok",
                "short_term": {
                    "percent_remaining": None,
                    "reset_at": None,
                    "window": None,
                },
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
    assert record["long_term"]["window"] == "weekly"


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
