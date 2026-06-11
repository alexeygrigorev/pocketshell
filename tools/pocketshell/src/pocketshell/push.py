"""Server-side FCM push delivery for usage-reset events (issue #690).

This module owns the *server* half of the reset-push pipeline. The detection
half (``usage_reset.record_resets``) and the entire Android receive path
(``PocketShellMessagingService`` / ``ResetPushPayload`` / ``PushDedupStore``)
already ship. Here we:

1. **Store the device token** the app delivers over a live foreground SSH
   session — ``pocketshell push register-token <token>`` (R1). The token is
   written atomically with mode ``0600`` alongside the usage state, mirroring
   :mod:`pocketshell.usage_capture`'s private-write style.
2. **Send a data push** for each NEW reset event the hourly ``--capture``
   produces (R2). The push is an FCM **HTTP v1** *data* message whose keys match
   the app's ``ResetPushPayload`` contract exactly (``type=usage_reset``,
   ``provider``, ``reset_key``, ``title``, ``body``). A short-lived OAuth2
   service-account bearer authenticates the call.

Design constraints
------------------

- **Service-account auth, not a legacy server key.** The HTTP v1 endpoint is
  ``POST https://fcm.googleapis.com/v1/projects/<project-id>/messages:send``
  with an ``Authorization: Bearer <oauth2-token>`` header. The bearer is minted
  + cached on the host via :mod:`google.auth` (an *optional* dependency).
- **Server-side per-``reset_key`` "already pushed" marker.** A successful send
  records the ``reset_key`` so it is NEVER re-POSTed, while a transient FCM
  failure leaves the marker absent so the next hourly capture RETRIES. The
  app's ``PushDedupStore`` is a second line of defense, not the primary guard.
- **Fail-soft.** If no service-account credential is configured, no token is
  registered, or :mod:`google.auth` is not installed, :func:`push_reset_events`
  no-ops and returns an empty list WITHOUT raising. The hourly ``--capture``
  must never break because push delivery is not set up (mirrors the best-effort
  ``record_resets`` hook in :mod:`pocketshell.usage_capture`).

Storage layout (under the usage state dir, ``$XDG_STATE_HOME/pocketshell/usage/``):

- ``push-token.json`` — the registered device token (mode ``0600``).
- ``push-sent.jsonl`` — append-only log of ``reset_key`` values successfully
  pushed (the server-side de-dup source of truth; mode ``0600``).

Credential discovery for the service-account JSON, in precedence order:

1. ``$POCKETSHELL_FCM_SERVICE_ACCOUNT`` — explicit path to the JSON.
2. ``<usage-state-dir>/fcm-service-account.json``.

The maintainer's one-time Firebase setup (S0) drops that JSON on the host; until
then every send path no-ops cleanly.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Optional

import click

from pocketshell.usage_capture import (
    UsagePaths,
    _append_history,
    _write_private,
    resolve_paths,
)

# Where the registered device token + the per-reset_key "already pushed" log
# live, under the same usage state dir as the cache/history/reset-events.
TOKEN_FILENAME = "push-token.json"
SENT_LOG_FILENAME = "push-sent.jsonl"

# Default service-account JSON name searched under the usage state dir when
# $POCKETSHELL_FCM_SERVICE_ACCOUNT is unset.
SERVICE_ACCOUNT_FILENAME = "fcm-service-account.json"
SERVICE_ACCOUNT_ENV = "POCKETSHELL_FCM_SERVICE_ACCOUNT"

# OAuth2 scope for the FCM HTTP v1 send endpoint.
FCM_SEND_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

# Bound the sent-log so it never grows without limit. Resets are rare, so a
# small cap is plenty for cross-run de-dup.
DEFAULT_SENT_LOG_MAX_LINES = 1000


def token_file(paths: UsagePaths) -> Path:
    """Return the registered-token path for ``paths``."""
    return paths.usage_dir / TOKEN_FILENAME


def sent_log_file(paths: UsagePaths) -> Path:
    """Return the per-``reset_key`` already-pushed log path for ``paths``."""
    return paths.usage_dir / SENT_LOG_FILENAME


# ---------------------------------------------------------------------------
# R1 — token registration store
# ---------------------------------------------------------------------------


def register_token(
    token: str,
    *,
    paths: Optional[UsagePaths] = None,
) -> Path:
    """Persist the device ``token`` atomically with mode ``0600``.

    The app calls this over a live foreground SSH session via
    ``pocketshell push register-token <token>``. Returns the path written.
    Raises :class:`ValueError` on an empty token (the CLI surfaces that as a
    non-zero exit) — a genuinely empty token is a caller bug, not a fail-soft
    condition.
    """
    trimmed = token.strip()
    if not trimmed:
        raise ValueError("token must not be empty")
    if paths is None:
        paths = resolve_paths()
    path = token_file(paths)
    _write_private(
        path,
        json.dumps({"token": trimmed}, sort_keys=True) + "\n",
    )
    return path


def read_token(paths: Optional[UsagePaths] = None) -> Optional[str]:
    """Return the registered device token, or ``None`` if none/unreadable."""
    if paths is None:
        paths = resolve_paths()
    path = token_file(paths)
    if not path.exists():
        return None
    try:
        parsed = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(parsed, dict):
        return None
    token = parsed.get("token")
    if isinstance(token, str) and token.strip():
        return token.strip()
    return None


# ---------------------------------------------------------------------------
# Server-side per-reset_key "already pushed" marker (cross-run de-dup)
# ---------------------------------------------------------------------------


def sent_reset_keys(paths: Optional[UsagePaths] = None) -> set[str]:
    """Return the set of ``reset_key`` values already successfully pushed."""
    if paths is None:
        paths = resolve_paths()
    path = sent_log_file(paths)
    if not path.exists():
        return set()
    keys: set[str] = set()
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return set()
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            parsed = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            key = parsed.get("reset_key")
            if isinstance(key, str) and key:
                keys.add(key)
    return keys


def _mark_sent(
    reset_key: str,
    *,
    paths: UsagePaths,
    sent_log_max_lines: int = DEFAULT_SENT_LOG_MAX_LINES,
) -> None:
    """Record ``reset_key`` as successfully pushed so it never re-POSTs."""
    _append_history(
        sent_log_file(paths),
        {"reset_key": reset_key},
        history_max_lines=sent_log_max_lines,
    )


# ---------------------------------------------------------------------------
# R2 — FCM HTTP v1 sender
# ---------------------------------------------------------------------------


def _resolve_service_account_path(
    paths: UsagePaths,
    *,
    env: Optional[dict[str, str]] = None,
) -> Optional[Path]:
    """Locate the service-account JSON, or ``None`` when unconfigured.

    Precedence: ``$POCKETSHELL_FCM_SERVICE_ACCOUNT`` then
    ``<usage-state-dir>/fcm-service-account.json``.
    """
    env_map = env if env is not None else os.environ
    explicit = env_map.get(SERVICE_ACCOUNT_ENV)
    if explicit:
        candidate = Path(os.path.expanduser(explicit))
        return candidate if candidate.exists() else None
    default = paths.usage_dir / SERVICE_ACCOUNT_FILENAME
    return default if default.exists() else None


def _service_account_project_id(path: Path) -> Optional[str]:
    """Read the ``project_id`` out of a service-account JSON file."""
    try:
        parsed = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if isinstance(parsed, dict):
        project_id = parsed.get("project_id")
        if isinstance(project_id, str) and project_id.strip():
            return project_id.strip()
    return None


class FcmSender:
    """Mint a service-account bearer and POST FCM HTTP v1 data messages.

    Split out from the module functions so the unit suite can subclass it and
    intercept :meth:`_post` (the only network boundary) without a real Firebase
    project or a real ``google.auth`` install. :meth:`from_service_account`
    returns ``None`` when credentials or :mod:`google.auth` are unavailable —
    the fail-soft path.
    """

    def __init__(self, *, project_id: str, credentials: Any) -> None:
        self._project_id = project_id
        self._credentials = credentials

    @property
    def endpoint(self) -> str:
        return f"https://fcm.googleapis.com/v1/projects/{self._project_id}/messages:send"

    @classmethod
    def from_service_account(cls, path: Path) -> Optional["FcmSender"]:
        """Build a sender from a service-account JSON, or ``None`` fail-soft.

        Returns ``None`` (never raises) when the JSON lacks a ``project_id`` or
        when :mod:`google.auth` is not installed, so an unconfigured host's
        hourly capture is unaffected.
        """
        project_id = _service_account_project_id(path)
        if project_id is None:
            return None
        try:
            # Optional dependency: import lazily so a host without google-auth
            # (the common pre-S0 state) simply no-ops instead of ImportError.
            from google.oauth2 import service_account as _sa  # type: ignore
        except Exception:
            return None
        try:
            credentials = _sa.Credentials.from_service_account_file(
                str(path),
                scopes=[FCM_SEND_SCOPE],
            )
        except Exception:
            return None
        return cls(project_id=project_id, credentials=credentials)

    def _bearer(self) -> Optional[str]:
        """Mint/refresh the short-lived OAuth2 bearer, or ``None`` on failure.

        ``google.auth`` caches the token on the credentials object and only
        hits the network when the cached token is missing/expired, so calling
        this once per capture is cheap on subsequent runs.
        """
        try:
            from google.auth.transport.requests import Request  # type: ignore

            self._credentials.refresh(Request())
        except Exception:
            return None
        token = getattr(self._credentials, "token", None)
        return token if isinstance(token, str) and token else None

    def _post(self, *, bearer: str, message: dict[str, Any]) -> bool:
        """POST one FCM HTTP v1 message. Returns True on a 2xx response.

        Network boundary — overridden in tests. Uses ``urllib`` to avoid a
        ``requests`` dependency (the rest of the CLI already uses ``urllib``).
        """
        import urllib.error
        import urllib.request

        body = json.dumps({"message": message}).encode("utf-8")
        req = urllib.request.Request(
            self.endpoint,
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {bearer}",
                "Content-Type": "application/json; charset=UTF-8",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=10.0) as resp:
                return 200 <= int(resp.status) < 300
        except urllib.error.HTTPError:
            return False
        except (urllib.error.URLError, OSError):
            return False

    def send_data_message(self, *, token: str, data: dict[str, str]) -> bool:
        """Send an FCM **data** message to ``token``. Returns True on success.

        Builds the HTTP v1 envelope ``{"message": {"token", "data"}}`` — a pure
        data message (no ``notification`` block) so the app builds the
        notification itself (matching ``ResetPushPayload`` semantics). Returns
        False (never raises) if the bearer can't be minted or the POST fails, so
        the caller leaves the ``reset_key`` un-marked and RETRIES next capture.
        """
        bearer = self._bearer()
        if bearer is None:
            return False
        message = {"token": token, "data": data}
        return self._post(bearer=bearer, message=message)


def _provider_display_name(provider: str) -> str:
    """Mirror the app's ``ResetPushPayload.providerDisplayName`` copy."""
    lowered = provider.strip().lower()
    if lowered in ("codex", "openai", "chatgpt"):
        return "Codex"
    if lowered in ("claude", "anthropic"):
        return "Claude"
    stripped = provider.strip()
    return stripped[:1].upper() + stripped[1:] if stripped else "Provider"


def reset_event_to_data(event: dict[str, Any]) -> Optional[dict[str, str]]:
    """Map a reset event to the ``ResetPushPayload`` data-message keys.

    Returns ``None`` when the event lacks the de-dup ``reset_key`` (the app
    drops such a push, so there is nothing to send). Pre-renders ``title`` /
    ``body`` to match the app's default copy so a foreground receive shows the
    same wording as a background one.
    """
    reset_key = event.get("reset_key")
    if not isinstance(reset_key, str) or not reset_key.strip():
        return None
    provider = event.get("provider")
    provider_str = provider.strip() if isinstance(provider, str) and provider.strip() else "provider"
    display = _provider_display_name(provider_str)
    return {
        "type": "usage_reset",
        "provider": provider_str,
        "reset_key": reset_key.strip(),
        "title": f"{display} limits reset",
        "body": f"Your {display} usage limits just reset. Heavy work can resume.",
    }


def push_reset_events(
    events: list[dict[str, Any]],
    *,
    paths: Optional[UsagePaths] = None,
    sender: Optional[FcmSender] = None,
    env: Optional[dict[str, str]] = None,
    sent_log_max_lines: int = DEFAULT_SENT_LOG_MAX_LINES,
) -> list[str]:
    """Push NEW reset ``events`` to the registered device. Fail-soft.

    Returns the list of ``reset_key`` values successfully pushed THIS call
    (empty when push isn't configured or nothing new was sent). Never raises —
    the hourly ``--capture`` calls this right after ``record_resets`` and must
    not break when Firebase isn't set up.

    Guards, in order:

    1. No events -> ``[]``.
    2. No registered device token -> ``[]`` (app hasn't handed one over yet).
    3. No service-account credential / no ``google.auth`` -> ``[]`` (pre-S0).
    4. Per ``reset_key``: skip if already in the server-side sent-log; on a
       successful send record the key so it NEVER re-POSTs; on a failed send
       leave it un-marked so the next capture RETRIES.
    """
    if not events:
        return []
    if paths is None:
        paths = resolve_paths()

    try:
        token = read_token(paths)
        if token is None:
            return []

        if sender is None:
            sa_path = _resolve_service_account_path(paths, env=env)
            if sa_path is None:
                return []
            sender = FcmSender.from_service_account(sa_path)
            if sender is None:
                return []

        already_sent = sent_reset_keys(paths)
        pushed: list[str] = []
        for event in events:
            data = reset_event_to_data(event)
            if data is None:
                continue
            reset_key = data["reset_key"]
            if reset_key in already_sent:
                continue
            if sender.send_data_message(token=token, data=data):
                _mark_sent(reset_key, paths=paths, sent_log_max_lines=sent_log_max_lines)
                already_sent.add(reset_key)
                pushed.append(reset_key)
            # On failure: deliberately do NOT mark - next capture retries.
        return pushed
    except Exception:
        # Absolute fail-soft backstop: push delivery must never wedge capture.
        return []


# ---------------------------------------------------------------------------
# CLI - `pocketshell push` subgroup
# ---------------------------------------------------------------------------


@click.group(
    name="push",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Manage push (FCM) delivery for usage-reset events (issue #690).\n\n"
        "The Android app delivers its device token over a live SSH session "
        "with `push register-token <token>`; the hourly `usage --capture` then "
        "sends a data push for each newly-detected limit reset. Push delivery "
        "is fail-soft: until a Firebase service-account credential is placed on "
        "the host, sends no-op and the in-app reset banner remains the fallback."
    ),
)
def push_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


@push_group.command("register-token")
@click.argument("token", required=True)
def push_register_token(token: str) -> None:
    """Persist the app's FCM device TOKEN for reset-push delivery.

    Atomic write, mode ``0600``, under the pocketshell usage state dir. The
    Android `FcmTokenRegistrar` invokes this over a live foreground SSH session
    (`pocketshell push register-token '<token>'`).
    """
    try:
        path = register_token(token)
    except ValueError as exc:
        raise click.ClickException(str(exc))
    click.echo(f"registered device token ({path})")


@push_group.command("token-path", hidden=True)
def push_token_path() -> None:
    """Print the resolved registered-token path (debug helper)."""
    click.echo(str(token_file(resolve_paths())))
