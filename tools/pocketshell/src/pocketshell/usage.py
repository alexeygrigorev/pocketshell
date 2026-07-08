"""`pocketshell usage` subcommand.

Implementation delegates to the **pinned** `quse` CLI via `subprocess.run`
and FLATTENS its provider-keyed `--json` document into the per-provider
NDJSON the Android app consumes. Human output is proxied verbatim.

quse is the single source of truth for the unified schema (issue #1318,
D22 hard-cut): pocketshell does NOT re-derive windows / resets / percentages
downstream. It parses quse's provider-keyed object, injects the `provider`
name from each key, and passes quse's unified fields through unchanged. A
schema mismatch (non-JSON / non-object payload) raises loudly rather than
silently emptying the panel.

quse is a hard dependency of pocketshell (see `pyproject.toml`), so its
console-script ships in the SAME bin directory as the running interpreter.
`_resolve_quse_binary` resolves that pinned copy next to `sys.executable`
and NEVER falls back to PATH — a host-level `quse` upgrade must not shadow
the pinned copy, and a missing pinned copy is a packaging-integrity error
(fail loud), not a user "install quse" nag.

Daemon mode (issue #219)
------------------------

When the IPC daemon is running, ``pocketshell usage --json`` sends a
``usage.fetch`` JSON-RPC request to ``$XDG_RUNTIME_DIR/pocketshell/daemon.sock``
instead of forking ``quse`` itself. The daemon caches the ALREADY-FLATTENED
NDJSON for 30 s, so a polled usage row in the Android app returns the
second-and-later calls in microseconds. The daemon performs the flatten
before caching; the CLI proxies the daemon's NDJSON verbatim (it is NOT
re-flattened — that would fail, NDJSON is not a provider-keyed object).

The fall-through is intentional and matches the spike's Q6 parity rule:

- ``--no-daemon`` forces the one-shot subprocess path even when a
  daemon is up (debugging / belt-and-braces).
- ``--no-cache`` is honoured by the daemon (cache miss + populate)
  but is intentionally a no-op on the subprocess path: there is no
  cache to bypass when every call is a fresh subprocess.
- The probe is best-effort: any error talking to the daemon
  (``ECONNREFUSED``, stale socket, version-skew RPC error, slow
  reply) falls through silently to the subprocess path. The daemon
  never blocks correctness.
"""

from __future__ import annotations

import subprocess
import sys
import json
from pathlib import Path
from typing import Any, Optional, Sequence

import click

_CLAUDE_USAGE_AUTH_SETUP_MESSAGE = (
    "Claude usage authentication needs setup on this host. "
    "Open Claude Code on the host and complete sign-in, then refresh usage."
)

# quse is bundled WITH pocketshell as a pinned dependency (issue #1318). A
# missing pinned quse is therefore a packaging-integrity error, NOT a user
# "install quse" nag: the fix is reinstalling pocketshell, not installing a
# separate host tool. Exit non-zero (but NOT 127 — 127 is reserved for
# "pocketshell itself not found" on the app side) with a clear message.
_QUSE_MISSING_MESSAGE = (
    "pocketshell: the bundled `quse` usage backend is missing from this "
    "pocketshell install. Reinstall pocketshell (e.g. "
    "`uv tool install --force pocketshell`) to restore it."
)
_QUSE_MISSING_EXIT_CODE = 1


def _resolve_quse_binary() -> Optional[str]:
    """Resolve the PINNED `quse` console-script shipped with pocketshell.

    quse is a hard dependency (pyproject ``dependencies``), so its
    console-script lands in the SAME ``bin`` directory as the ``pocketshell``
    interpreter (the venv / ``uv tool`` install dir). We resolve it there —
    next to ``sys.executable`` — and NEVER fall back to ``PATH``: a
    host-level ``quse`` on PATH must not shadow the pinned copy (a host
    upgrade must not reach us), and a missing pinned copy is a
    packaging-integrity error, not a "please install quse" nag. Returns the
    absolute path, or ``None`` when the pinned copy is absent. Pulled out so
    the unit suite can monkeypatch it.

    Console-scripts live next to the UNRESOLVED ``sys.executable``: in a venv
    (or a ``uv tool`` install) ``bin/python`` is a symlink to the underlying
    interpreter, so ``Path(sys.executable).resolve()`` points at the shared
    interpreter dir where ``quse`` is NOT installed. We therefore check the
    interpreter's own ``bin`` dir first, and only fall through to the
    resolved dir for layouts where the two coincide. Both candidates are
    anchored to ``sys.executable`` — this is NOT a PATH search.
    """
    exe_dir = Path(sys.executable).parent
    candidates = [exe_dir]
    resolved_dir = Path(sys.executable).resolve().parent
    if resolved_dir != exe_dir:
        candidates.append(resolved_dir)
    for bin_dir in candidates:
        candidate = bin_dir / "quse"
        if candidate.exists():
            return str(candidate)
    return None


def _run_quse(args: Sequence[str]) -> int:
    """Invoke the pinned `quse` with [args]; proxy stdout/stderr and exit.

    Used for the human-readable (non-JSON) path where output stays
    byte-identical to `quse`.
    """
    quse_path = _resolve_quse_binary()
    if quse_path is None:
        click.echo(_QUSE_MISSING_MESSAGE, err=True)
        return _QUSE_MISSING_EXIT_CODE

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


def _actionable_error(provider: str, error: Any) -> Optional[str]:
    """Rewrite a provider `error` string into an actionable, human message.

    This is genuine error-message UX, NOT schema re-derivation: quse owns the
    schema and reports the raw upstream error; pocketshell only translates a
    couple of known auth failures into a "here is what to do" message so the
    app surfaces "sign in on the host" instead of a bare "HTTP Error 401".
    Idempotent — the rewritten messages do not re-match these patterns.
    """
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
        return _CLAUDE_USAGE_AUTH_SETUP_MESSAGE
    if provider == "claude" and (
        "http error 401" in lower
        or "unauthorized" in lower
        or lower in {"no-credentials", "no credentials"}
    ):
        return _CLAUDE_USAGE_AUTH_SETUP_MESSAGE
    if provider == "codex" and lower in {"no auth token", "no-auth-token", "no credentials"}:
        return (
            "Codex authentication is missing on this host. "
            "Run `codex login` in the host shell, then refresh usage."
        )
    return text


def normalize_usage_stdout(stdout: str) -> str:
    """Flatten quse's provider-keyed `--json` object into per-provider NDJSON.

    quse v0.0.9 emits ONE JSON object keyed by provider name; each value is
    the unified per-provider record (``status`` / ``short_term`` /
    ``long_term`` / ``error`` / ``details``). This is a THIN flatten — one
    NDJSON line per provider — that injects the provider name (from the
    object key) as a top-level ``provider`` field and passes quse's unified
    fields through unchanged. quse is the single source of truth for the
    schema (D22): pocketshell does NOT re-derive windows / resets /
    percentages here.

    Strict / fail-loud: non-JSON stdout, a non-object top-level payload, or a
    non-object provider value all raise ``ValueError`` so a schema mismatch
    fails visibly instead of silently emptying the usage panel. Handles both
    the multi-provider and single-provider (``{"<p>": {...}}``) shapes — both
    are provider-keyed objects. Empty/blank stdout passes through untouched
    (the caller decides what an empty read means).
    """
    if not stdout.strip():
        return stdout
    try:
        parsed = json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise ValueError(f"quse --json did not emit valid JSON: {exc}") from exc
    if not isinstance(parsed, dict):
        raise ValueError(
            "quse --json must be a provider-keyed JSON object, got "
            f"{type(parsed).__name__}"
        )
    lines: list[str] = []
    for provider, record in parsed.items():
        if not isinstance(record, dict):
            raise ValueError(
                f"quse --json provider '{provider}' is not a JSON object "
                f"(got {type(record).__name__})"
            )
        flattened: dict[str, Any] = {"provider": provider, **record}
        if flattened.get("error") is not None:
            flattened["error"] = _actionable_error(provider, flattened["error"])
        lines.append(json.dumps(flattened, sort_keys=True))
    return "\n".join(lines) + "\n"


def _try_daemon_usage_fetch(
    provider: Optional[str],
    *,
    no_cache: bool,
) -> Optional[dict[str, Any]]:
    """Probe the daemon and dispatch ``usage.fetch``; return None on miss.

    Returns the JSON-RPC ``result`` envelope (a dict with
    ``stdout``/``stderr``/``returncode`` keys) on success, or ``None``
    when the daemon is unreachable / errors out and the caller should
    fall through to the one-shot subprocess path. The envelope's ``stdout``
    is ALREADY-FLATTENED per-provider NDJSON (the daemon flattens before
    caching), so callers proxy it verbatim — they must NOT re-flatten it.

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
    help="Emit machine-readable per-provider NDJSON (flattened from `quse --json`).",
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
@click.option(
    "--capture",
    "capture",
    is_flag=True,
    help=(
        "Fetch usage live, write the cached latest reading + append to the "
        "history log under $XDG_STATE_HOME/pocketshell/usage/, then exit. "
        "Run this on a schedule (cron / systemd timer). Implies --json."
    ),
)
@click.option(
    "--cached",
    "cached",
    is_flag=True,
    help=(
        "Emit the last captured reading instantly (no live fetch) as a JSON "
        "document {captured_at, records} for the app's stale-while-revalidate "
        "render. Exits non-zero if no capture exists yet. Implies --json."
    ),
)
@click.option(
    "--reset-events",
    "reset_events",
    is_flag=True,
    help=(
        "Emit the recorded limit/session reset events (#690) as a JSON "
        "document {reset_events: [...]} from the history log. The app reads "
        "this to surface 'limits reset at <time>' on next open. Emits an "
        "empty list when no resets have been detected yet. Implies --json."
    ),
)
@click.pass_context
def usage_command(
    ctx: click.Context,
    provider: Optional[str] = None,
    json_output: bool = False,
    no_daemon: bool = False,
    no_cache: bool = False,
    capture: bool = False,
    cached: bool = False,
    reset_events: bool = False,
) -> None:
    """Report quota / usage for coding-agent providers on this host.

    By default the command probes the IPC daemon's Unix socket
    (``$XDG_RUNTIME_DIR/pocketshell/daemon.sock``) and dispatches a
    ``usage.fetch`` JSON-RPC call when the daemon is live. Otherwise
    it falls through to the pinned ``quse [provider] [--json]`` as a one-shot
    subprocess. JSON output is FLATTENED from quse's provider-keyed document
    into per-provider NDJSON — quse owns the schema, pocketshell just reshapes
    it into the one-record-per-line wire format the app reads.

    ``--capture`` and ``--cached`` implement the stale-while-revalidate
    cache (#689): a scheduled ``--capture`` writes the latest reading +
    history log on the host, and the app reads ``--cached`` for an instant
    populated render before its own live foreground refresh swaps in fresh
    data.
    """
    # `--cached` emits the last captured reading instantly; `--capture`
    # fetches live and persists the cache + history. Both bypass the
    # normal stdout-proxy path below. They imply JSON output.
    if reset_events:
        exit_code = _emit_reset_events()
        if exit_code != 0:
            ctx.exit(exit_code)
        return
    if cached:
        exit_code = _emit_cached_usage()
        if exit_code != 0:
            ctx.exit(exit_code)
        return
    if capture:
        exit_code = _capture_usage(provider, no_daemon=no_daemon)
        if exit_code != 0:
            ctx.exit(exit_code)
        return

    # JSON output is the format the daemon caches against (per-provider NDJSON
    # already flattened by the daemon). Human-readable output is rare and not
    # on the Android hot path, so it does not get the daemon speed-up —
    # falling through to subprocess is simpler than teaching the daemon to
    # render two formats.
    if json_output and not no_daemon:
        envelope = _try_daemon_usage_fetch(provider, no_cache=no_cache)
        if envelope is not None:
            # The daemon already flattened quse's document into NDJSON before
            # caching; proxy it verbatim (re-flattening NDJSON would raise).
            if envelope.get("stdout"):
                sys.stdout.write(str(envelope["stdout"]))
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


def _fetch_usage_ndjson(
    provider: Optional[str],
    *,
    no_daemon: bool,
) -> tuple[Optional[str], str, int]:
    """Fetch live usage NDJSON for the cache capture.

    Returns ``(stdout, stderr, returncode)`` where ``stdout`` is the flattened
    per-provider NDJSON (or ``None`` when the pinned ``quse`` is missing).
    Mirrors the command's own daemon-then-subprocess fall-through so a
    scheduled ``--capture`` benefits from the daemon cache when one is live.
    """
    if not no_daemon:
        envelope = _try_daemon_usage_fetch(provider, no_cache=False)
        if envelope is not None:
            # Daemon envelope stdout is already-flattened NDJSON — use as-is.
            stdout = str(envelope.get("stdout") or "")
            stderr = str(envelope.get("stderr") or "")
            return stdout, stderr, int(envelope.get("returncode", 0))

    quse_path = _resolve_quse_binary()
    if quse_path is None:
        return (None, _QUSE_MISSING_MESSAGE + "\n", _QUSE_MISSING_EXIT_CODE)
    args: list[str] = [quse_path]
    if provider:
        args.append(provider)
    args.append("--json")
    completed = subprocess.run(args, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        # A failed live fetch is not flattened/cached — return quse's raw
        # stdout so the caller can decide (it will not be persisted).
        return completed.stdout, completed.stderr, completed.returncode
    return normalize_usage_stdout(completed.stdout), completed.stderr, completed.returncode


def _capture_usage(provider: Optional[str], *, no_daemon: bool) -> int:
    """Fetch usage live and persist the cache + history log, then report.

    Designed to run on a schedule. On a successful fetch it writes
    ``usage-latest.json`` and appends to ``usage-history.jsonl`` under
    ``$XDG_STATE_HOME/pocketshell/usage/`` and echoes the cache object so a
    cron/systemd log shows what landed. A failed fetch is NOT cached so a
    transient provider hiccup never pins a bad reading.
    """
    from pocketshell import usage_capture as _capture

    stdout, stderr, returncode = _fetch_usage_ndjson(provider, no_daemon=no_daemon)
    if returncode != 0 or stdout is None:
        if stderr:
            sys.stderr.write(stderr)
        return returncode if returncode != 0 else 1

    cache_obj = _capture.write_capture(stdout)
    sys.stdout.write(json.dumps(cache_obj, sort_keys=True) + "\n")
    return 0


def _emit_cached_usage() -> int:
    """Emit the last captured reading as a JSON document, or exit non-zero.

    Returns exit code 0 when a cache exists (its JSON document is written to
    stdout), or 3 with a friendly stderr note when no capture has run yet so
    the app can fall back to a pure live fetch.
    """
    from pocketshell import usage_capture as _capture

    document = _capture.cached_document()
    if document is None:
        sys.stderr.write(
            "pocketshell: no captured usage yet. "
            "Run `pocketshell usage --capture` (or wait for the scheduled "
            "capture) to populate the cache.\n"
        )
        return 3
    sys.stdout.write(document)
    return 0


def _emit_reset_events() -> int:
    """Emit recorded reset events (#690) as a JSON document.

    Always exits 0: the document is ``{"reset_events": [...]}`` (empty list
    when no resets have been detected/logged yet), so the app can read it
    unconditionally and surface "limits reset at <time>" when present.
    """
    from pocketshell import usage_reset as _reset

    sys.stdout.write(_reset.reset_events_document())
    return 0


def _run_quse_json(args: Sequence[str]) -> int:
    """Invoke the pinned `quse --json` and flatten its output before proxying.

    quse emits a provider-keyed JSON object; ``normalize_usage_stdout``
    flattens it into per-provider NDJSON for the app. On a non-zero exit the
    raw quse stdout/stderr is proxied verbatim (a failed fetch is not
    flattened) so the app's exit!=0 provider-error path sees the real output.
    """
    quse_path = _resolve_quse_binary()
    if quse_path is None:
        click.echo(_QUSE_MISSING_MESSAGE, err=True)
        return _QUSE_MISSING_EXIT_CODE

    completed = subprocess.run(
        [quse_path, *args],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        if completed.stdout:
            sys.stdout.write(completed.stdout)
        if completed.stderr:
            sys.stderr.write(completed.stderr)
        return completed.returncode
    if completed.stdout:
        sys.stdout.write(normalize_usage_stdout(completed.stdout))
    if completed.stderr:
        sys.stderr.write(completed.stderr)
    return completed.returncode
