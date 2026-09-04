"""Unit tests for `pocketshell usage` (issue #1318, repinned by #2293).

Published ``quse==0.0.15`` (upstream ``a86959e``) IS the canonical producer:
every provider record carries a top-level ``windows`` map and there are no
``short_term`` / ``long_term`` fields anywhere. ``pocketshell usage --json``
therefore performs NO schema translation any more — it injects the provider
name from the object key and forwards the record verbatim into per-provider
NDJSON. The legacy translation that #2283 added for the 0.0.14 wheel is
hard-cut (D22): a legacy-shaped record is now REJECTED loudly rather than
silently re-shaped.

The tests use a live output captured from the published 0.0.15 wheel
(``tests/data/quse-0.0.15-usage.json``, provenance in the adjacent
``.provenance.txt``). That capture contains the ``go`` provider — the OpenCode
Go quota the Usage panel needs — which the 0.0.14 pin could not report at all.

Most tests stub ``pocketshell.usage._resolve_quse_binary`` and
``subprocess.run`` so they never invoke a real ``quse`` binary; the contract
under test is "pocketshell resolves the PINNED quse and flattens its schema
correctly". The two ``test_pinned_quse_*`` tests deliberately do NOT stub: they
exercise the REAL pinned wheel installed alongside the interpreter, which is
what actually broke the maintainer's ``pocketshell usage go --json`` (#2293).
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Sequence
from unittest.mock import patch

import pytest
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
# Captured LIVE from the published quse 0.0.15 wheel (`quse --json`) on
# 2026-08-28. The release is the canonical `windows` producer and has six
# providers including `go`; see the adjacent .provenance.txt sidecar for the
# wheel digest and the exact capture command.
_DATA = Path(__file__).resolve().parent / "data"
_FIXTURE_0015 = _DATA / "quse-0.0.15-usage.json"
_FIXTURE_0015_PROVENANCE = _DATA / "quse-0.0.15-usage.provenance.txt"
# The EXACT `normalize_usage_stdout` output for that capture. Committed so the
# Android parser test can consume the real producer bytes instead of a Kotlin
# re-implementation of the producer.
_FIXTURE_0015_NDJSON = _DATA / "quse-0.0.15-usage.ndjson"
_KOTLIN_FIXTURE_0015_NDJSON = (
    Path(__file__).resolve().parents[3]
    / "shared"
    / "core-usage"
    / "src"
    / "test"
    / "resources"
    / "quse-0.0.15-usage.ndjson"
)
# The connected usage-render journey embeds the same producer NDJSON as a
# Kotlin literal (the app module has no androidTest resource pipeline).
_ANDROID_TEST_JOURNEY = (
    Path(__file__).resolve().parents[3]
    / "app"
    / "src"
    / "androidTest"
    / "java"
    / "com"
    / "pocketshell"
    / "app"
    / "usage"
    / "Usage1318StrictSchemaRenderE2eTest.kt"
)
_LOCK = Path(__file__).resolve().parent.parent / "uv.lock"

# The published 0.0.15 shape for a span that does not apply to a provider: the
# key is PRESENT with a null percentage (it is not omitted upstream).
_NULL_PUBLISHED_WINDOWS = {
    "windows": {
        "5h": {"percent_remaining": None, "reset_at": None, "rolling": False},
        "7d": {"percent_remaining": None, "reset_at": None},
        "monthly": {"percent_remaining": None, "reset_at": None},
    }
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
    """The live published quse-0.0.15 provider-keyed document (6 providers)."""
    return _FIXTURE_0015.read_text()


# ---------------------------------------------------------------------------
# quse is a PINNED dependency, resolved next to sys.executable, never PATH
# ---------------------------------------------------------------------------


def test_pyproject_pins_quse_exactly() -> None:
    # AC (#2293): pocketshell pins the published quse==0.0.15 release — the
    # first wheel containing a86959e (unified `windows` map + the `go`
    # provider). The 0.0.14 pin (and its boundary translation) is hard-cut.
    text = _PYPROJECT.read_text()
    assert '"quse==0.0.15"' in text, "pyproject must pin quse==0.0.15 in dependencies"
    assert '"quse==0.0.14"' not in text, "the 0.0.14 pin must not remain"
    lock = _LOCK.read_text()
    assert 'name = "quse"' in lock
    assert 'version = "0.0.15"' in lock
    assert 'specifier = "==0.0.15"' in lock
    assert 'specifier = "==0.0.14"' not in lock


def test_pyproject_documents_canonical_windows_contract() -> None:
    """The dependency comment must describe the pin we actually ship.

    #2283's comment described 0.0.14 as a "five-provider" legacy producer and
    explicitly disclaimed OpenCode Go support. 0.0.15 IS the canonical
    producer and DOES report `go`, so that disclaimer must be gone.
    """
    text = _PYPROJECT.read_text()
    assert "published 0.0.15 wheel" in text
    assert "six-provider" in text
    assert "five-provider" not in text
    assert "OpenCode Go" in text
    # The legacy translation must be described as RETIRED, not as live
    # behaviour of the pinned wheel.
    assert "hard-cut" in text
    # The #2283 disclaimer that OpenCode Go was NOT part of the pinned
    # wheel's provider list must be gone — with 0.0.15 it is.
    assert "separate top-level `windows` producer contract" not in text
    assert "not to the published-wheel provider list" not in text


# ---------------------------------------------------------------------------
# #2293 reproduction: the PINNED wheel itself must know the `go` provider.
# These two tests deliberately use the REAL installed quse — the maintainer's
# symptom was produced by the pinned wheel, not by pocketshell's own code, so
# a stubbed subprocess could never have caught it.
# ---------------------------------------------------------------------------


def test_pinned_quse_wheel_advertises_the_go_provider() -> None:
    """RED on quse==0.0.14: the pinned wheel had no `go` provider at all.

    `pocketshell usage` has NO provider allowlist of its own — the positional
    `provider` argument is forwarded verbatim to the pinned quse, which owns
    validation. So "Unknown provider 'go'" could only be fixed by the pin.
    """
    from quse.usage import SUPPORTED_USAGE_PROVIDERS, USAGE_PROVIDER_CHOICES

    assert "go" in USAGE_PROVIDER_CHOICES, (
        "the PINNED quse must advertise the `go` provider; got "
        f"{USAGE_PROVIDER_CHOICES}"
    )
    assert "go" in SUPPORTED_USAGE_PROVIDERS, (
        "`go` must be a SUPPORTED provider, not an unsupported placeholder; got "
        f"{SUPPORTED_USAGE_PROVIDERS}"
    )


def test_pocketshell_usage_go_json_reaches_the_pinned_quse() -> None:
    """End-to-end reproduction of the maintainer's #2293 symptom.

    Runs the REAL `pocketshell usage go --json` CLI against the REAL pinned
    quse console-script next to the interpreter (no subprocess stub, no
    daemon). On quse==0.0.14 this exits 1 with
    `Unknown provider 'go'. Valid provider names: codex, claude, zai, copilot,
    grok, gemini, grok-build.` — exactly what the maintainer saw on the dev
    box. On 0.0.15 it must emit one NDJSON record for provider `go`.

    Credential-independent: an unauthenticated host still gets a `go` record
    with `status: "error"`, so this asserts the PROVIDER REACHABILITY that
    broke, never a live quota number.
    """
    assert _resolve_quse_binary() is not None, (
        "the pinned quse console-script must be installed next to the "
        "interpreter for this end-to-end check"
    )
    runner = CliRunner()
    result = runner.invoke(usage_command, ["go", "--json", "--no-daemon"])

    assert "Unknown provider" not in result.output, (
        "#2293 symptom: the pinned quse rejected the `go` provider — "
        f"{result.output.strip()}"
    )
    assert result.exit_code == 0, result.output
    lines = [ln for ln in result.output.splitlines() if ln.strip()]
    assert len(lines) == 1, f"expected exactly one `go` NDJSON record, got {lines}"
    record = json.loads(lines[0])
    assert record["provider"] == "go"
    assert isinstance(record["windows"], dict)


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
# normalize_usage_stdout: canonical passthrough at the producer boundary
# ---------------------------------------------------------------------------


def test_quse_0015_fixture_matches_published_contract() -> None:
    raw = json.loads(_quse_keyed_json())
    assert set(raw) == {"claude", "codex", "copilot", "go", "grok", "zai"}
    assert (
        "Wheel SHA-256: "
        "a9138ed2e4f12a01812103332fe27b94de9f96a2413212c59558052481815793"
        in _FIXTURE_0015_PROVENANCE.read_text()
    )
    for provider, record in raw.items():
        assert set(record) == {"details", "error", "status", "windows"}, provider
        # The whole point of a86959e: NO legacy window fields survive anywhere.
        assert "short_term" not in record, provider
        assert "long_term" not in record, provider
        assert set(record["windows"]) == {"5h", "7d", "monthly"}, provider


def test_flatten_passes_every_published_record_through_unchanged() -> None:
    """AC (#2293): the host boundary FORWARDS 0.0.15 records unchanged.

    The only mutation allowed is injecting the `provider` key from the
    object key (quse's `--json` document is provider-keyed, the app wire is
    one self-describing record per line). Everything else — windows,
    percentages, reset times, `rolling`, provider-owned `details` — must be
    byte-identical to the captured wheel output.
    """
    raw = json.loads(_quse_keyed_json())
    out = normalize_usage_stdout(_quse_keyed_json())
    lines = [json.loads(ln) for ln in out.splitlines()]
    by_provider = {r["provider"]: r for r in lines}

    assert set(by_provider) == set(raw) == {
        "claude",
        "codex",
        "copilot",
        "go",
        "grok",
        "zai",
    }
    for provider, source in raw.items():
        flattened = by_provider[provider]
        assert flattened == {"provider": provider, **source}, provider

    # Spot-check the load-bearing `go` values so a passthrough that silently
    # emptied the map could not satisfy the equality above vacuously.
    go = by_provider["go"]
    assert go["status"] == "ok"
    assert go["windows"]["5h"] == {
        "percent_remaining": 36.0,
        "reset_at": "2026-08-28T13:36:26Z",
        "rolling": True,
    }
    assert go["windows"]["7d"]["percent_remaining"] == 74.0
    assert go["windows"]["monthly"]["percent_remaining"] == 86.0
    assert go["details"]["max_used_percent"] == 64.0


def test_committed_producer_ndjson_matches_normalize_output() -> None:
    """The committed `.ndjson` is the producer's real output, not a hand copy.

    The Android parser test consumes this exact file, so a producer change
    that stopped matching it would otherwise leave the Kotlin side asserting
    against stale bytes.
    """
    assert _FIXTURE_0015_NDJSON.read_text() == normalize_usage_stdout(_quse_keyed_json())


def test_kotlin_test_resource_matches_the_python_producer_fixture() -> None:
    """Cross-language single-source guard for the shared NDJSON fixture."""
    assert _KOTLIN_FIXTURE_0015_NDJSON.exists(), (
        f"missing Android test resource {_KOTLIN_FIXTURE_0015_NDJSON}"
    )
    assert (
        _KOTLIN_FIXTURE_0015_NDJSON.read_text() == _FIXTURE_0015_NDJSON.read_text()
    ), "the Android test resource has drifted from the Python producer fixture"


@pytest.mark.skip(reason="app/ deleted for app2 rewrite; re-enable once app2 has the journey fixture")
def test_kotlin_androidtest_literal_matches_the_python_producer_fixture() -> None:
    """The connected usage-render journey must assert against REAL producer bytes.

    `Usage1318StrictSchemaRenderE2eTest` embeds the NDJSON as a Kotlin literal
    (the app module has no androidTest resource pipeline). Guard it here so a
    producer change cannot leave the on-device acceptance asserting a stale
    wire shape — the #2274 → #2293 handover is exactly that kind of change.
    """
    assert _ANDROID_TEST_JOURNEY.exists(), f"missing {_ANDROID_TEST_JOURNEY}"
    kotlin = _ANDROID_TEST_JOURNEY.read_text()
    for line in _FIXTURE_0015_NDJSON.read_text().splitlines():
        assert line in kotlin, (
            "the connected usage journey's embedded NDJSON has drifted from the "
            f"producer fixture; missing line for provider "
            f"{json.loads(line)['provider']!r}"
        )


def test_flatten_handles_single_provider_shape() -> None:
    keyed = json.dumps(
        {
            "codex": {
                "status": "ok",
                "windows": {
                    "5h": {"percent_remaining": None, "reset_at": None, "rolling": False},
                    "7d": {"percent_remaining": 88.0, "reset_at": None},
                    "monthly": {"percent_remaining": None, "reset_at": None},
                },
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
    # Non-applicable spans stay PRESENT with a null percentage — the producer
    # no longer edits the map, and the Android parser omits the null rows.
    assert set(record["windows"]) == {"5h", "7d", "monthly"}


def test_flatten_rejects_record_without_canonical_windows() -> None:
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
        assert "canonical 'windows'" in str(exc)
    else:  # pragma: no cover - fail-loud contract
        raise AssertionError("a record without a canonical windows map must be rejected")


def test_flatten_rejects_legacy_short_long_record_after_hard_cut() -> None:
    """D22 hard cut: the retired 0.0.14 shape must fail LOUD, not be re-shaped.

    #2283's boundary translation existed only because the published wheel was
    behind quse HEAD. With the pin at 0.0.15 that producer is gone, so a
    legacy-shaped record can only mean a mis-installed / shadowed quse — a
    schema drift the panel must surface, never silently absorb.
    """
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
                "details": {},
            }
        }
    )
    try:
        normalize_usage_stdout(keyed)
    except ValueError as exc:
        assert "canonical 'windows'" in str(exc)
    else:  # pragma: no cover - fail-loud contract
        raise AssertionError("the legacy short_term/long_term shape must be rejected")


def test_flatten_go_record_keeps_producer_owned_window_labels() -> None:
    keyed = json.dumps(
        {
            "go": {
                "status": "ok",
                "windows": {
                    "5h": {
                        "percent_remaining": 100.0,
                        "reset_at": "2026-08-22T11:21:36Z",
                        "rolling": True,
                    },
                    "monthly": {"percent_remaining": 97.0, "reset_at": None},
                },
                "details": {"max_used_percent": 3.0},
            }
        }
    )
    record = json.loads(normalize_usage_stdout(keyed).splitlines()[0])
    assert record["provider"] == "go"
    assert record["windows"] == json.loads(keyed)["go"]["windows"]
    assert record["details"] == {"max_used_percent": 3.0}


def test_flatten_rejects_canonical_windows_non_object() -> None:
    keyed = json.dumps({"go": {"status": "ok", "windows": "not-an-object"}})
    try:
        normalize_usage_stdout(keyed)
    except ValueError as exc:
        assert "top-level 'windows'" in str(exc)
    else:  # pragma: no cover - fail-loud contract
        raise AssertionError("a non-object canonical windows map must be rejected")


def test_flatten_quse_0015_codex_reset_credits_pass_through() -> None:
    # Fix-shape verification: the published 0.0.15 codex details entries carry
    # `{expires_at, status, title}` and the exact-match inventory fields the
    # app's strict parser validates — passed through untouched here.
    out = normalize_usage_stdout(_FIXTURE_0015.read_text())
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
    # line with provider "grok" and forwards the published windows map.
    keyed = json.dumps(
        {
            "grok": {
                "status": "ok",
                "windows": {
                    "5h": {"percent_remaining": None, "reset_at": None, "rolling": False},
                    "7d": {
                        "percent_remaining": 95.0,
                        "reset_at": "2026-08-25T00:08:17Z",
                    },
                    "monthly": {"percent_remaining": None, "reset_at": None},
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
    assert record["windows"]["7d"] == {
        "percent_remaining": 95.0,
        "reset_at": "2026-08-25T00:08:17Z",
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
    assert providers == ["claude", "codex", "copilot", "go", "grok", "zai"]
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
    # AC: `pocketshell usage grok` is an accepted provider filter. Passthrough
    # of the published grok object is the load-bearing JSON contract; the CLI
    # must forward `grok` + `--json`.
    runner = CliRunner()
    single = json.dumps(
        {
            "grok": {
                "status": "ok",
                "windows": {
                    "5h": {"percent_remaining": None, "reset_at": None, "rolling": False},
                    "7d": {
                        "percent_remaining": 95.0,
                        "reset_at": "2026-08-25T00:08:17Z",
                    },
                    "monthly": {"percent_remaining": None, "reset_at": None},
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
    assert record["windows"]["7d"]["percent_remaining"] == 95.0


def test_usage_forwards_go_provider_and_json_without_an_allowlist() -> None:
    """#2293: pocketshell has NO provider allowlist of its own.

    The positional `provider` argument is forwarded verbatim to the pinned
    quse, which owns validation — so the fix for the maintainer's
    "Unknown provider 'go'" is the PIN, and nothing in this CLI may start
    filtering provider names.
    """
    runner = CliRunner()
    single = json.dumps(
        {
            "go": {
                "status": "ok",
                "windows": {
                    "5h": {
                        "percent_remaining": 36.0,
                        "reset_at": "2026-08-28T13:36:26Z",
                        "rolling": True,
                    },
                    "7d": {"percent_remaining": 74.0, "reset_at": "2026-08-31T00:00:00Z"},
                    "monthly": {
                        "percent_remaining": 86.0,
                        "reset_at": "2026-09-22T06:20:28Z",
                    },
                },
                "error": None,
                "details": {"limit_reached": False, "max_used_percent": 64.0},
            }
        }
    )
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/fake/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stdout=single),
    ) as run, patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        result = runner.invoke(usage_command, ["go", "--json", "--no-daemon"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/quse", "go", "--json"]
    record = json.loads(result.output.splitlines()[0])
    assert record["provider"] == "go"
    assert record["windows"]["monthly"]["percent_remaining"] == 86.0


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
