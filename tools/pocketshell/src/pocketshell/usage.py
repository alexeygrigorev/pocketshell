"""`pocketshell usage` subcommand.

Implementation delegates to the existing `quse` CLI via `subprocess.run`
and normalizes JSON records into PocketShell's app-facing schema. Human
output is proxied verbatim.

Daemon mode (issue #219, first PR scope)
----------------------------------------

When the IPC daemon is running, ``pocketshell usage --json`` sends a
``usage.fetch`` JSON-RPC request to ``$XDG_RUNTIME_DIR/pocketshell/daemon.sock``
instead of forking ``quse`` itself. The daemon caches the result for
30 s, so a polled usage row in the Android app returns the second-and-
later calls in microseconds.

The fall-through is intentional and matches the spike's Q6 parity rule:

- ``--no-daemon`` forces the one-shot subprocess path even when a
  daemon is up (debugging / belt-and-braces).
- ``--no-cache`` is honoured by the daemon (cache miss + populate)
  but is intentionally a no-op on the subprocess path: there is no
  cache to bypass when every call is a fresh subprocess. This keeps
  the CLI surface uniform without paying for client-side caching that
  duplicates the daemon's logic.
- The probe is best-effort: any error talking to the daemon
  (``ECONNREFUSED``, stale socket, version-skew RPC error, slow
  reply) falls through silently to the subprocess path. The daemon
  never blocks correctness.

Why subprocess instead of `import quse`:

- `quse` is currently not published to PyPI; declaring it as a normal
  `pyproject.toml` dependency would break `uv tool install
  pocketshell` for any user (including the maintainer's dev box).
- Subprocess delegation keeps `pocketshell` decoupled from `quse`'s
  internal module layout, so updates to `quse` don't break the wrapper.
- The PATH-discovery story for `quse` is solved by the Android bootstrap
  wrapper, which derives PATH from the user's shell rc before probing
  tools. Delegating to whatever `quse` is on PATH keeps this wrapper
  decoupled from that bootstrap plumbing.

Later PRs will fold the provider-detection logic in directly so
`pocketshell` is the canonical implementation and the subprocess
hop disappears, but that is explicit non-scope here per the brief on
issue [#170](https://github.com/alexeygrigorev/pocketshell/issues/170).
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from datetime import datetime, timezone
import json
from pathlib import Path
from typing import Any, Optional, Sequence
import urllib.error
import urllib.request

import click

_CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
_CODEX_AUTH_PATH = Path.home() / ".codex" / "auth.json"
_CODEX_COMPATIBLE_PROVIDERS = {
    "codex",
    "openai",
    "openai-codex",
    "openai_codex",
    "chatgpt",
}


def _resolve_quse_binary() -> Optional[str]:
    """Locate the `quse` CLI on PATH, or return ``None`` if absent.

    Pulled out as a function so the unit suite can monkeypatch it.
    `shutil.which` returns the same path the user would see from
    `command -v quse`, which is the probe the Android app already runs.
    """
    return shutil.which("quse")


def _run_quse(args: Sequence[str]) -> int:
    """Invoke `quse` with [args]; proxy stdout/stderr and exit code.

    Using `subprocess.run(..., check=False)` and forwarding the captured
    output rather than `os.execvp` keeps the call testable (the test
    suite can monkeypatch `subprocess.run`) and lets us decorate the
    failure mode with a friendly hint when `quse` is missing.
    """
    quse_path = _resolve_quse_binary()
    if quse_path is None:
        # Same wording the bootstrap sheet uses so the user sees a
        # consistent message whether they hit the bin via `pocketshell
        # usage` or the app's poll loop.
        click.echo(
            "pocketshell: `quse` is not installed on this host. "
            "Install it via `uv tool install quse` or `pipx install quse` "
            "and re-run.",
            err=True,
        )
        return 127

    completed = subprocess.run(
        [quse_path, *args],
        check=False,
        capture_output=True,
        text=True,
    )
    # Human-readable output stays byte-identical to `quse`.
    if completed.stdout:
        sys.stdout.write(completed.stdout)
    if completed.stderr:
        sys.stderr.write(completed.stderr)
    return completed.returncode


def _normalize_reset_at(value: Any) -> Optional[str]:
    """Return an ISO-8601 UTC reset timestamp, accepting provider quirks.

    OpenAI's Codex usage endpoint currently returns ``reset_at`` as Unix epoch
    seconds. Older ``quse`` releases attempted ISO parsing only, which turned a
    valid reset into ``null`` and left the app rendering ``resets —``.
    """
    if value is None:
        return None
    parsed: datetime
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        try:
            parsed = datetime.fromtimestamp(float(value), tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            return None
    else:
        text = str(value).strip()
        if not text:
            return None
        try:
            if text.isdigit():
                parsed = datetime.fromtimestamp(float(text), tz=timezone.utc)
            else:
                parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        except (OverflowError, OSError, ValueError):
            try:
                parsed = datetime.strptime(text, "%Y-%m-%d")
            except ValueError:
                return None
            parsed = parsed.replace(tzinfo=timezone.utc)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _percent_remaining_from_used(value: Any) -> Optional[float]:
    try:
        used = float(value)
    except (TypeError, ValueError):
        return None
    return round(max(0.0, min(100.0, 100.0 - used)), 2)


def _reset_after_seconds_to_iso(
    value: Any,
    *,
    now: Optional[datetime] = None,
) -> Optional[str]:
    if value is None:
        return None
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return None
    if seconds < 0:
        return None
    base = now or datetime.now(timezone.utc)
    if base.tzinfo is None:
        base = base.replace(tzinfo=timezone.utc)
    reset_at = base.astimezone(timezone.utc).timestamp() + seconds
    try:
        parsed = datetime.fromtimestamp(reset_at, tz=timezone.utc)
    except (OverflowError, OSError, ValueError):
        return None
    return parsed.strftime("%Y-%m-%dT%H:%M:%SZ")


def _window_label_from_seconds(value: Any) -> Optional[str]:
    try:
        seconds = int(float(value))
    except (TypeError, ValueError, OverflowError):
        return None
    if seconds <= 0:
        return None
    units = (
        (24 * 60 * 60, "d"),
        (60 * 60, "h"),
        (60, "m"),
    )
    for unit_seconds, suffix in units:
        if seconds >= unit_seconds and seconds % unit_seconds == 0:
            return f"{seconds // unit_seconds}{suffix}"
    return f"{seconds}s"


def _window_label_from_detail(detail: dict[str, Any]) -> Optional[str]:
    label = _window_label_from_seconds(detail.get("limit_window_seconds"))
    if label is not None:
        return label
    try:
        minutes = float(detail.get("window_minutes"))
    except (TypeError, ValueError):
        return None
    return _window_label_from_seconds(minutes * 60)


def _window_from_detail(
    detail: Any,
    *,
    now: Optional[datetime] = None,
) -> Optional[dict[str, Any]]:
    if not isinstance(detail, dict):
        return None
    percent_remaining = _percent_remaining_from_used(detail.get("used_percent"))
    reset_at = _normalize_reset_at(detail.get("reset_at")) or _reset_after_seconds_to_iso(
        detail.get("reset_after_seconds"),
        now=now,
    )
    window = _window_label_from_detail(detail)
    if percent_remaining is None and reset_at is None and window is None:
        return None
    return {
        "percent_remaining": percent_remaining,
        "reset_at": reset_at,
        "window": window,
    }


def _merge_window(
    current: Any,
    detail: Any,
    *,
    prefer_detail_percent: bool = False,
    now: Optional[datetime] = None,
) -> Any:
    if not isinstance(current, dict):
        if not isinstance(detail, dict):
            return current
        current = {"percent_remaining": None, "reset_at": None}
    else:
        current = dict(current)

    detail_window = _window_from_detail(detail, now=now)
    if detail_window is not None:
        if prefer_detail_percent or current.get("percent_remaining") is None:
            current["percent_remaining"] = detail_window.get("percent_remaining")
        if current.get("reset_at") is None:
            current["reset_at"] = detail_window.get("reset_at")
        if current.get("window") is None and detail_window.get("window") is not None:
            current["window"] = detail_window.get("window")

    reset_after_seconds = current.get("reset_after_seconds")
    current["reset_at"] = _normalize_reset_at(current.get("reset_at")) or _reset_after_seconds_to_iso(
        reset_after_seconds,
        now=now,
    )
    current.pop("reset_after_seconds", None)
    return current


def _actionable_error(provider: str, error: Any) -> Optional[str]:
    if error is None:
        return None
    text = str(error).strip()
    if not text:
        return None
    lower = text.lower()
    if provider == "claude" and (
        "claude " + "/login" in lower
        or "run `claude" in lower
        or "run claude" in lower
        or "authentication " + "failed" in lower
    ):
        return "Usage data unavailable"
    if provider == "claude" and (
        "http error 401" in lower
        or "unauthorized" in lower
        or lower in {"no-credentials", "no credentials"}
    ):
        return f"Usage data unavailable: {text}"
    if provider == "codex" and lower in {"no auth token", "no-auth-token", "no credentials"}:
        return (
            "Codex authentication is missing on this host. "
            "Run `codex login` in the host shell, then refresh usage."
        )
    return text


def _read_codex_bearer_token(auth_path: Path = _CODEX_AUTH_PATH) -> Optional[str]:
    try:
        data = json.loads(auth_path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None
    token = data.get("tokens", {}).get("access_token")
    return token if isinstance(token, str) and token.strip() else None


def _fetch_codex_detail_windows() -> Optional[dict[str, dict[str, Any]]]:
    token = _read_codex_bearer_token()
    if token is None:
        return None
    req = urllib.request.Request(
        _CODEX_USAGE_URL,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=10.0) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError, OSError):
        return None
    rate_limit = payload.get("rate_limit")
    if not isinstance(rate_limit, dict):
        return None
    windows: dict[str, dict[str, Any]] = {}
    for name in ("primary_window", "secondary_window"):
        value = rate_limit.get(name)
        if isinstance(value, dict):
            windows[name] = value
    return windows or None


def _codex_needs_source_patch(record: dict[str, Any], detail_windows: dict[str, Any]) -> bool:
    has_window_shape = any(
        isinstance(record.get(key), dict) for key in ("short_term", "long_term")
    ) or bool(detail_windows)
    if not has_window_shape:
        return False
    for top_level_key, detail_key in (
        ("short_term", "primary_window"),
        ("long_term", "secondary_window"),
    ):
        top_level = record.get(top_level_key)
        detail = detail_windows.get(detail_key)
        top_level_reset = None
        if isinstance(top_level, dict):
            top_level_reset = top_level.get("reset_at")
            if top_level_reset is None:
                top_level_reset = top_level.get("reset_after_seconds")
        detail_reset = None
        if isinstance(detail, dict):
            detail_reset = detail.get("reset_at")
            if detail_reset is None:
                detail_reset = detail.get("reset_after_seconds")
        if top_level_reset is None and detail_reset is None:
            return True
    return False


def _is_codex_compatible_provider(provider: str) -> bool:
    return provider.replace(" ", "_").lower() in _CODEX_COMPATIBLE_PROVIDERS


def normalize_usage_record(
    record: dict[str, Any],
    *,
    now: Optional[datetime] = None,
) -> dict[str, Any]:
    """Normalize a provider record emitted by ``quse --json``.

    PocketShell owns the app-facing schema even when it delegates provider
    probing to ``quse``. Keep this post-processing focused on schema repairs
    that older ``quse`` releases cannot express correctly.
    """
    normalized = dict(record)
    provider = str(normalized.get("provider", "")).lower()
    details = normalized.get("details")
    detail_windows = details.get("windows") if isinstance(details, dict) else None
    if not isinstance(detail_windows, dict):
        detail_windows = {}

    if _is_codex_compatible_provider(provider):
        # Codex's ChatGPT usage response exposes the real primary/secondary
        # windows under details. Older quse versions hard-code short_term to
        # 100% and lose epoch reset timestamps, which regressed issue #501.
        if _codex_needs_source_patch(normalized, detail_windows):
            fetched_windows = _fetch_codex_detail_windows()
            if fetched_windows is not None:
                detail_windows = {**detail_windows, **fetched_windows}
                details_obj = dict(details) if isinstance(details, dict) else {}
                details_obj["windows"] = detail_windows
                normalized["details"] = details_obj
        short_term = _merge_window(
            normalized.get("short_term"),
            detail_windows.get("primary_window"),
            prefer_detail_percent=True,
            now=now,
        )
        if short_term is not None:
            normalized["short_term"] = short_term
        long_term = _merge_window(
            normalized.get("long_term"),
            detail_windows.get("secondary_window"),
            prefer_detail_percent=True,
            now=now,
        )
        if long_term is not None:
            normalized["long_term"] = long_term
    elif provider == "claude":
        short_term = _merge_window(
            normalized.get("short_term"),
            detail_windows.get("five_hour"),
            now=now,
        )
        if short_term is not None:
            normalized["short_term"] = short_term
        long_term = _merge_window(
            normalized.get("long_term"),
            detail_windows.get("seven_day"),
            now=now,
        )
        if long_term is not None:
            normalized["long_term"] = long_term
    else:
        if isinstance(normalized.get("short_term"), dict):
            normalized["short_term"] = _merge_window(normalized.get("short_term"), None, now=now)
        if isinstance(normalized.get("long_term"), dict):
            normalized["long_term"] = _merge_window(normalized.get("long_term"), None, now=now)

    actionable = _actionable_error(provider, normalized.get("error"))
    if actionable != normalized.get("error"):
        normalized["error"] = actionable
    return normalized


def normalize_usage_stdout(
    stdout: str,
    *,
    now: Optional[datetime] = None,
) -> str:
    """Normalize NDJSON stdout from ``quse --json`` for app consumption."""
    if not stdout.strip():
        return stdout
    lines: list[str] = []
    changed = False
    for raw_line in stdout.splitlines():
        if not raw_line.strip():
            lines.append(raw_line)
            continue
        try:
            parsed = json.loads(raw_line)
        except json.JSONDecodeError:
            return stdout
        if not isinstance(parsed, dict):
            return stdout
        normalized = normalize_usage_record(parsed, now=now)
        changed = changed or normalized != parsed
        lines.append(json.dumps(normalized, sort_keys=True))
    suffix = "\n" if stdout.endswith("\n") else ""
    return "\n".join(lines) + suffix if changed else stdout


def _try_daemon_usage_fetch(
    provider: Optional[str],
    *,
    no_cache: bool,
) -> Optional[dict[str, Any]]:
    """Probe the daemon and dispatch ``usage.fetch``; return None on miss.

    Returns the JSON-RPC ``result`` envelope (a dict with
    ``stdout``/``stderr``/``returncode`` keys) on success, or ``None``
    when the daemon is unreachable / errors out and the caller should
    fall through to the one-shot subprocess path.

    Lazy import keeps the cold-cache CLI start fast for users who
    never hit the daemon path.
    """
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if not socket_path.exists():
        return None

    params: dict[str, Any] = {}
    if provider is not None:
        params["provider"] = provider
    if no_cache:
        params["no_cache"] = True

    try:
        result = _daemon.call(
            "usage.fetch",
            params=params,
            socket_path=socket_path,
            timeout=5.0,
        )
    except (_daemon.DaemonClientError, RuntimeError, OSError):
        # Daemon unreachable, returned an error, or socket dance went
        # wrong. The contract is "daemon is an optimisation, never a
        # dependency" — fall through silently.
        return None
    if not isinstance(result, dict):
        return None
    return result


@click.command(
    context_settings={"help_option_names": ["-h", "--help"], "ignore_unknown_options": True},
)
@click.argument("provider", required=False)
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit machine-readable JSON output identical to `quse --json`.",
)
@click.option(
    "--no-daemon",
    "no_daemon",
    is_flag=True,
    help=(
        "Skip the IPC daemon and run `quse` as a one-shot subprocess "
        "even if a daemon is available. Useful for debugging."
    ),
)
@click.option(
    "--no-cache",
    "no_cache",
    is_flag=True,
    help=(
        "Bypass the daemon's per-method cache (30 s for `usage.fetch`). "
        "No effect on the one-shot subprocess path, which always runs fresh."
    ),
)
@click.pass_context
def usage_command(
    ctx: click.Context,
    provider: Optional[str] = None,
    json_output: bool = False,
    no_daemon: bool = False,
    no_cache: bool = False,
) -> None:
    """Report quota / usage for coding-agent providers on this host.

    By default the command probes the IPC daemon's Unix socket
    (``$XDG_RUNTIME_DIR/pocketshell/daemon.sock``) and dispatches a
    ``usage.fetch`` JSON-RPC call when the daemon is live. Otherwise
    it falls through to ``quse [provider] [--json]`` as a one-shot
    subprocess. JSON output is normalized into PocketShell's schema so
    the app is not pinned to provider-specific `quse` quirks.
    """
    # JSON output is the format the daemon caches against (NDJSON
    # straight from `quse --json`). Human-readable output is rare and
    # not on the Android hot path, so it does not get the daemon
    # speed-up — falling through to subprocess is simpler than
    # teaching the daemon to render two formats.
    if json_output and not no_daemon:
        envelope = _try_daemon_usage_fetch(provider, no_cache=no_cache)
        if envelope is not None:
            if envelope.get("stdout"):
                sys.stdout.write(normalize_usage_stdout(str(envelope["stdout"])))
            if envelope.get("stderr"):
                sys.stderr.write(envelope["stderr"])
            exit_code = int(envelope.get("returncode", 0))
            if exit_code != 0:
                ctx.exit(exit_code)
            return

    args: list[str] = []
    if provider:
        args.append(provider)
    if json_output:
        args.append("--json")
    if json_output:
        exit_code = _run_quse_json(args)
    else:
        exit_code = _run_quse(args)
    # Click ignores the return value of a callback by default; we need to
    # explicitly propagate non-zero exit codes through `ctx.exit` so the
    # outer `main()` (and the OS) sees the same exit code `quse` reported.
    if exit_code != 0:
        ctx.exit(exit_code)


def _run_quse_json(args: Sequence[str]) -> int:
    """Invoke ``quse`` and normalize JSON stdout before proxying it."""
    quse_path = _resolve_quse_binary()
    if quse_path is None:
        click.echo(
            "pocketshell: `quse` is not installed on this host. "
            "Install it via `uv tool install quse` or `pipx install quse` "
            "and re-run.",
            err=True,
        )
        return 127

    completed = subprocess.run(
        [quse_path, *args],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.stdout:
        sys.stdout.write(normalize_usage_stdout(completed.stdout))
    if completed.stderr:
        sys.stderr.write(completed.stderr)
    return completed.returncode
