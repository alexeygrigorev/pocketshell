"""`pocketshell qr-share` subcommand.

Fold of the previous standalone desktop QR emitter (issue #129) into the
unified `pocketshell` CLI. Per D22 (no backwards compatibility, hard
cuts only) the old standalone script was deleted in the same change;
this module is the only path going forward.

The command builds the `pocketshell.ssh-import.v1` JSON payload that the
Android-side `SshImportPayloadCodec` expects, wraps it in one or more
`pocketshell.qr.v1` chunked envelopes (matching the Kotlin `QrChunkCodec`
byte-for-byte), and renders the envelopes either as inline Unicode-block
QR codes in the terminal or as a numbered PNG sequence on disk.

Lazy `qrcode` import:

The `qrcode[pil]` dependency is heavy (Pillow) and is only needed for the
actual QR rendering — payload assembly, envelope encoding, and the
`--print-only` debug path all work without it. To keep the CLI's top-level
import cost low and to let users of the other subcommands (`usage`,
`jobs`, `sessions`, `agent-log`, `daemon`) skip the extra entirely, the
`qrcode` import lives inside the render helpers (`_render_terminal` and
`_render_png`). When the user invokes `pocketshell qr-share` without
having installed the optional `qr` extra, a friendly install hint is
written to stderr and the command exits 127 (matching the
missing-binary pattern used by `usage`, `jobs`, and `sessions`).

This module is self-contained: it does NOT depend on any other Python
package outside the standard library and Click. The chunking format is
implemented inline so the package has no runtime dependency on the
Android codebase or the upstream Kotlin sources.
"""

from __future__ import annotations

import base64
import binascii
import json
import os
import pathlib
import secrets
import subprocess
import sys
from typing import Iterable, List, Optional

import click


CHUNK_SIZE = 1500
ENVELOPE_PREFIX = "pocketshell.qr.v1?"

_QRCODE_MISSING_MESSAGE = (
    "pocketshell: QR rendering requires the optional `qr` extra. "
    "Install with: `pip install pocketshell[qr]` or "
    "`uv tool install pocketshell --with qrcode[pil]`."
)


# ---------------------------------------------------------------------------
# Envelope encoding — mirrors Kotlin `QrChunkCodec` byte-for-byte.
# ---------------------------------------------------------------------------


def _crc32_hex(data: bytes) -> str:
    """CRC-32 of ``data`` as 8-lowercase-hex matching the Kotlin codec."""
    return f"{binascii.crc32(data) & 0xFFFFFFFF:08x}"


def _generate_id() -> str:
    """Match ``QrChunkCodec.generateId`` (8 hex chars from a CSPRNG)."""
    return secrets.token_hex(4)


def _b64_urlsafe(chunk: bytes) -> str:
    """URL-safe base64 without padding — matches ``Base64.URL_SAFE | NO_PADDING``."""
    return base64.urlsafe_b64encode(chunk).decode("ascii").rstrip("=")


def encode_envelopes(
    payload: str,
    *,
    chunk_size: int = CHUNK_SIZE,
    id: Optional[str] = None,
) -> List[str]:
    """Encode ``payload`` (str) into one or more envelope strings.

    Mirrors ``QrChunkCodec.encode`` in the Kotlin codebase so a phone
    running the scanner can decode the output round-trip.
    """
    transmission_id = id or _generate_id()
    raw = payload.encode("utf-8")
    if not raw:
        raise ValueError("payload is empty")
    chunks = [raw[i : i + chunk_size] for i in range(0, len(raw), chunk_size)]
    total = len(chunks)
    out: List[str] = []
    for idx, chunk in enumerate(chunks, start=1):
        envelope = (
            ENVELOPE_PREFIX
            + f"part={idx}/{total}"
            + f"&id={transmission_id}"
            + f"&checksum={_crc32_hex(chunk)}"
            + f"&payload={_b64_urlsafe(chunk)}"
        )
        out.append(envelope)
    return out


# ---------------------------------------------------------------------------
# Payload assembly — resolves ssh-config alias OR explicit flags.
# ---------------------------------------------------------------------------


def _resolve_with_ssh_config(alias: str) -> dict:
    """Resolve ``alias`` against ``~/.ssh/config`` using ``ssh -G``."""
    try:
        resolved = subprocess.check_output(["ssh", "-G", alias], text=True)
    except FileNotFoundError as exc:
        raise click.ClickException(
            "ssh: command not found; install OpenSSH or pass flags directly"
        ) from exc
    except subprocess.CalledProcessError as exc:
        raise click.ClickException(f"ssh -G {alias} failed: {exc}")
    config: dict = {}
    for line in resolved.splitlines():
        if not line or " " not in line:
            continue
        key, value = line.split(None, 1)
        config.setdefault(key.lower(), value.strip())
    return config


def _read_private_key(path: pathlib.Path) -> str:
    if not path.exists():
        raise click.ClickException(f"key file not found: {path}")
    pem = path.read_text().strip()
    if not pem:
        raise click.ClickException(f"private key file is empty: {path}")
    return pem


def build_payload(
    *,
    alias: Optional[str],
    host: Optional[str],
    user: Optional[str],
    port: Optional[int],
    key: Optional[str],
    name: Optional[str],
    key_name: Optional[str],
) -> dict:
    """Assemble the JSON dict matching ``SshImportPayloadCodec``.

    Two resolution modes:

    - ``host`` provided -> skip ssh-config; ``key`` is required.
    - ``host`` not provided -> ``alias`` is required and is resolved via
      ``ssh -G``. Explicit flags override the ssh-config values.
    """
    if host is None:
        if alias is None:
            raise click.ClickException(
                "either --host or a positional ssh-config alias is required"
            )
        config = _resolve_with_ssh_config(alias)
        resolved_host = host or config.get("hostname", alias)
        resolved_port = int(port or config.get("port", "22"))
        resolved_user = user or config.get("user") or os.environ.get("USER") or ""
        identity_str = key or config.get("identityfile")
        if not identity_str:
            raise click.ClickException(
                f"{alias}: no IdentityFile resolved; pass --key explicitly"
            )
        identity = pathlib.Path(identity_str).expanduser()
        resolved_name = name or alias
        resolved_key_name = key_name or identity.name
        private_key_pem = _read_private_key(identity)
    else:
        resolved_host = host
        resolved_port = int(port or 22)
        resolved_user = user or os.environ.get("USER") or ""
        if key is None:
            raise click.ClickException("--key is required when --host is passed explicitly")
        identity = pathlib.Path(key).expanduser()
        resolved_name = name or host
        resolved_key_name = key_name or identity.name
        private_key_pem = _read_private_key(identity)

    if not resolved_user:
        raise click.ClickException("could not determine SSH user; pass --user explicitly")

    return {
        "type": "pocketshell.ssh-import.v1",
        "version": 1,
        "name": resolved_name,
        "host": resolved_host,
        "port": resolved_port,
        "username": resolved_user,
        "auth": {
            "type": "privateKey",
            "name": resolved_key_name,
            "privateKeyPem": private_key_pem,
        },
    }


# ---------------------------------------------------------------------------
# QR rendering — lazy `qrcode` import so the rest of the CLI doesn't pay
# the cost (and doesn't fail at top-level import if the extra is absent).
# ---------------------------------------------------------------------------


def _try_import_qrcode():
    """Return ``(qrcode_module, ERROR_CORRECT_M)`` or ``(None, None)``.

    Kept as a function so tests can monkeypatch it. The import is local
    so importing this module (or `pocketshell.cli`) never triggers the
    heavy `qrcode` / Pillow chain.
    """
    try:
        import qrcode  # type: ignore
        from qrcode.constants import ERROR_CORRECT_M  # type: ignore

        return qrcode, ERROR_CORRECT_M
    except ImportError:
        return None, None


def _render_terminal(envelopes: Iterable[str]) -> None:
    """Render each envelope as a Unicode block-character QR in the terminal."""
    qrcode, ERROR_CORRECT_M = _try_import_qrcode()
    if qrcode is None:
        raise click.ClickException(_QRCODE_MISSING_MESSAGE)
    envelopes = list(envelopes)
    total = len(envelopes)
    for idx, envelope in enumerate(envelopes, start=1):
        click.echo(f"\n# Part {idx} of {total}")
        q = qrcode.QRCode(error_correction=ERROR_CORRECT_M, border=1)
        q.add_data(envelope)
        q.make(fit=True)
        # `invert=True` so the QR is light-on-dark, the right visual for
        # most terminal colour schemes.
        q.print_ascii(out=sys.stdout, invert=True)
        if idx < total:
            try:
                input("Press Enter for next QR (Ctrl-C to abort)...")
            except EOFError:
                pass


def _render_png(
    envelopes: Iterable[str],
    output_dir: pathlib.Path,
    prefix: str,
) -> List[pathlib.Path]:
    """Write each envelope to ``<output_dir>/<prefix>-NN.png`` and return paths."""
    qrcode, ERROR_CORRECT_M = _try_import_qrcode()
    if qrcode is None:
        raise click.ClickException(_QRCODE_MISSING_MESSAGE)
    output_dir.mkdir(parents=True, exist_ok=True)
    envelopes = list(envelopes)
    paths: List[pathlib.Path] = []
    digits = max(2, len(str(len(envelopes))))
    for idx, envelope in enumerate(envelopes, start=1):
        img = qrcode.make(envelope, error_correction=ERROR_CORRECT_M)
        out = output_dir / f"{prefix}-{idx:0{digits}d}.png"
        img.save(out)
        paths.append(out)
    return paths


# ---------------------------------------------------------------------------
# Click command.
# ---------------------------------------------------------------------------


@click.command(
    name="qr-share",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Emit PocketShell QR-import codes from an ssh-config alias or "
        "explicit flags.\n\n"
        "Builds the `pocketshell.ssh-import.v1` payload (matching the "
        "Android-side `SshImportPayloadCodec`), wraps it in one or more "
        "`pocketshell.qr.v1` chunked envelopes (matching the Kotlin "
        "`QrChunkCodec`), and renders the result. When stdout is a TTY "
        "the QRs are drawn inline as Unicode blocks; otherwise (or when "
        "`--png` is passed) a numbered PNG sequence is written to "
        "`--out-dir`.\n\n"
        "QR rendering requires the optional `qr` extra. Install with: "
        "`pip install pocketshell[qr]` or "
        "`uv tool install pocketshell --with qrcode[pil]`."
    ),
)
@click.argument("alias", required=False)
@click.option("--host", default=None, help="SSH host name or IP (bypasses ssh-config).")
@click.option("--user", default=None, help="SSH username (defaults to ssh-config / $USER).")
@click.option("--port", type=int, default=None, help="SSH port (defaults to ssh-config / 22).")
@click.option(
    "--key",
    default=None,
    help="Path to the SSH private key (defaults to IdentityFile from ssh-config).",
)
@click.option(
    "--name",
    default=None,
    help="PocketShell display name (defaults to the alias / host).",
)
@click.option(
    "--key-name",
    default=None,
    help="PocketShell key name (defaults to the key file's basename).",
)
@click.option(
    "--png",
    "png",
    is_flag=True,
    help="Write a numbered PNG sequence even when stdout is a TTY.",
)
@click.option(
    "--out-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=pathlib.Path),
    default=pathlib.Path("."),
    show_default=True,
    help="Directory for PNG output.",
)
@click.option(
    "--prefix",
    default="qr-share",
    show_default=True,
    help="Filename prefix for PNG output.",
)
@click.option(
    "--chunk-size",
    type=int,
    default=CHUNK_SIZE,
    show_default=True,
    help="Raw bytes per chunk before base64 (do not change unless you know why).",
)
@click.option(
    "--id",
    "id_",
    default=None,
    help="Override the transmission id (8 hex chars). Only useful for reproducible test runs.",
)
@click.option(
    "--dump-payload",
    is_flag=True,
    help="Print the JSON payload to stderr before emitting QRs (debugging).",
)
@click.option(
    "--print-only",
    is_flag=True,
    help="Print the envelope strings to stdout instead of rendering QRs.",
)
@click.pass_context
def qr_share_command(
    ctx: click.Context,
    alias: Optional[str],
    host: Optional[str],
    user: Optional[str],
    port: Optional[int],
    key: Optional[str],
    name: Optional[str],
    key_name: Optional[str],
    png: bool,
    out_dir: pathlib.Path,
    prefix: str,
    chunk_size: int,
    id_: Optional[str],
    dump_payload: bool,
    print_only: bool,
) -> None:
    """Emit PocketShell QR-import codes.

    See the command's help text for full usage. The flow is:

    1. Resolve the host + key (via ssh-config alias or explicit flags).
    2. Build the `pocketshell.ssh-import.v1` JSON payload.
    3. Encode it into one or more `pocketshell.qr.v1` envelopes.
    4. Either print the envelopes (with ``--print-only``) or render them
       as terminal blocks / PNGs.
    """
    try:
        payload = build_payload(
            alias=alias,
            host=host,
            user=user,
            port=port,
            key=key,
            name=name,
            key_name=key_name,
        )
    except click.ClickException:
        # ClickException carries its own exit-code handling; let it
        # propagate. We catch it here only to make the control flow
        # explicit and easy to extend with extra context if needed.
        raise

    payload_text = json.dumps(payload, separators=(",", ":"))
    if dump_payload:
        click.echo(payload_text, err=True)

    try:
        envelopes = encode_envelopes(payload_text, chunk_size=chunk_size, id=id_)
    except ValueError as exc:
        raise click.ClickException(str(exc))

    click.echo(f"# {len(envelopes)} QR part(s) for {payload['name']}", err=True)

    if print_only:
        for envelope in envelopes:
            click.echo(envelope)
        return

    write_png = png or not sys.stdout.isatty()
    try:
        if write_png:
            paths = _render_png(envelopes, out_dir, prefix)
            for path in paths:
                click.echo(str(path))
        else:
            _render_terminal(envelopes)
    except click.ClickException as exc:
        # The missing-`qrcode`-extra path raises ClickException with the
        # `_QRCODE_MISSING_MESSAGE`. ClickException's default exit code
        # is 1; we want 127 here to mirror the missing-binary pattern
        # used by `usage`, `jobs`, and `sessions`. Re-emit + exit 127
        # rather than re-raising.
        if exc.message == _QRCODE_MISSING_MESSAGE:
            click.echo(exc.message, err=True)
            ctx.exit(127)
        raise
