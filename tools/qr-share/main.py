#!/usr/bin/env python3
"""Desktop emitter for the PocketShell QR-import flow (issue #129).

Reads a host from ``~/.ssh/config`` (via ``ssh -G``) or accepts explicit
``--host``, ``--user``, ``--port``, ``--key`` flags, builds the JSON payload
expected by ``SshImportPayloadCodec`` (``pocketshell.ssh-import.v1``), wraps
that payload in one or more ``pocketshell.qr.v1`` chunked envelopes, and
emits the resulting QR codes.

When ``stdout`` is a TTY the QRs are rendered inline as a Unicode block
matrix. Otherwise (or when ``--png`` is passed explicitly) the QRs are
written as a numbered PNG sequence: ``qr-share-01.png``, ``qr-share-02.png``,
... A multi-QR sequence pauses between codes with a "Press Enter for next
QR" prompt so the user can scan each in turn.

The chunking format is implemented inline here so the script has no
runtime dependency on the Android codebase. The ``qrcode`` Python package
is required for terminal / PNG output; install it with::

    pip install --user qrcode[pil]

The script will emit a clear error and exit 2 if the dependency is missing.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import pathlib
import secrets
import subprocess
import sys
from typing import Iterable, List, Optional


CHUNK_SIZE = 1500
ENVELOPE_PREFIX = "pocketshell.qr.v1?"


def _crc32_hex(data: bytes) -> str:
    """CRC-32 of ``data`` as 8-lowercase-hex matching the Kotlin codec."""
    return f"{binascii.crc32(data) & 0xFFFFFFFF:08x}"


def _generate_id() -> str:
    """Match ``QrChunkCodec.generateId`` (8 hex chars from a CSPRNG)."""
    return secrets.token_hex(4)


def _b64_urlsafe(chunk: bytes) -> str:
    """URL-safe base64 without padding — matches ``Base64.URL_SAFE | NO_PADDING``."""
    return base64.urlsafe_b64encode(chunk).decode("ascii").rstrip("=")


def encode_envelopes(payload: str, *, chunk_size: int = CHUNK_SIZE, id: Optional[str] = None) -> List[str]:
    """Encode ``payload`` (str) into one or more envelope strings.

    Mirrors ``QrChunkCodec.encode`` in the Kotlin codebase so a phone
    running this issue's scanner can decode the output round-trip.
    """
    transmission_id = id or _generate_id()
    raw = payload.encode("utf-8")
    if not raw:
        raise ValueError("payload is empty")
    chunks = [raw[i : i + chunk_size] for i in range(0, len(raw), chunk_size)]
    total = len(chunks)
    out = []
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


def _resolve_with_ssh_config(alias: str) -> dict:
    """Resolve ``alias`` against ``~/.ssh/config`` using ``ssh -G``."""
    try:
        resolved = subprocess.check_output(["ssh", "-G", alias], text=True)
    except FileNotFoundError as exc:
        raise SystemExit("ssh: command not found; install OpenSSH or pass flags directly") from exc
    except subprocess.CalledProcessError as exc:
        raise SystemExit(f"ssh -G {alias} failed: {exc}")
    config: dict = {}
    for line in resolved.splitlines():
        if not line or " " not in line:
            continue
        key, value = line.split(None, 1)
        config.setdefault(key.lower(), value.strip())
    return config


def _read_private_key(path: pathlib.Path) -> str:
    if not path.exists():
        raise SystemExit(f"key file not found: {path}")
    return path.read_text().strip()


def build_payload(args: argparse.Namespace) -> dict:
    """Assemble the JSON dict matching ``SshImportPayloadCodec``."""
    if args.host is None:
        if args.alias is None:
            raise SystemExit("either --host or a positional ssh-config alias is required")
        config = _resolve_with_ssh_config(args.alias)
        host = args.host or config.get("hostname", args.alias)
        port = int(args.port or config.get("port", "22"))
        user = args.user or config.get("user") or os.environ.get("USER") or ""
        identity_str = args.key or config.get("identityfile")
        if not identity_str:
            raise SystemExit(f"{args.alias}: no IdentityFile resolved; pass --key explicitly")
        identity = pathlib.Path(identity_str).expanduser()
        name = args.name or args.alias
        key_name = args.key_name or identity.name
        private_key_pem = _read_private_key(identity)
    else:
        host = args.host
        port = int(args.port or 22)
        user = args.user or os.environ.get("USER") or ""
        if args.key is None:
            raise SystemExit("--key is required when --host is passed explicitly")
        identity = pathlib.Path(args.key).expanduser()
        name = args.name or host
        key_name = args.key_name or identity.name
        private_key_pem = _read_private_key(identity)

    if not user:
        raise SystemExit("could not determine SSH user; pass --user explicitly")

    return {
        "type": "pocketshell.ssh-import.v1",
        "version": 1,
        "name": name,
        "host": host,
        "port": port,
        "username": user,
        "auth": {
            "type": "privateKey",
            "name": key_name,
            "privateKeyPem": private_key_pem,
        },
    }


def _try_import_qrcode():
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
        raise SystemExit(
            "The `qrcode` Python package is required for terminal output. "
            "Install it with `pip install --user qrcode[pil]`."
        )
    envelopes = list(envelopes)
    total = len(envelopes)
    for idx, envelope in enumerate(envelopes, start=1):
        print(f"\n# Part {idx} of {total}")
        q = qrcode.QRCode(error_correction=ERROR_CORRECT_M, border=1)
        q.add_data(envelope)
        q.make(fit=True)
        # ``invert=True`` so the QR is light-on-dark, the right visual for
        # most terminal colour schemes.
        q.print_ascii(out=sys.stdout, invert=True)
        if idx < total:
            try:
                input("Press Enter for next QR (Ctrl-C to abort)…")
            except EOFError:
                pass


def _render_png(envelopes: Iterable[str], output_dir: pathlib.Path, prefix: str) -> List[pathlib.Path]:
    qrcode, ERROR_CORRECT_M = _try_import_qrcode()
    if qrcode is None:
        raise SystemExit(
            "The `qrcode` Python package is required for PNG output. "
            "Install it with `pip install --user qrcode[pil]`."
        )
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


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Emit PocketShell QR-import codes from an ssh-config alias or explicit flags.",
    )
    parser.add_argument(
        "alias",
        nargs="?",
        help="ssh-config alias to resolve (uses `ssh -G`). Mutually exclusive with --host.",
    )
    parser.add_argument("--host", help="SSH host name or IP (bypasses ssh-config)")
    parser.add_argument("--user", help="SSH username (defaults to ssh-config / $USER)")
    parser.add_argument("--port", type=int, help="SSH port (defaults to ssh-config / 22)")
    parser.add_argument("--key", help="Path to the SSH private key (defaults to IdentityFile)")
    parser.add_argument("--name", help="PocketShell display name (defaults to the alias / host)")
    parser.add_argument("--key-name", help="PocketShell key name (defaults to the key file's basename)")
    parser.add_argument(
        "--png",
        action="store_true",
        help="Write a numbered PNG sequence even when stdout is a TTY",
    )
    parser.add_argument(
        "--out-dir",
        type=pathlib.Path,
        default=pathlib.Path("."),
        help="Directory for PNG output (default: current directory)",
    )
    parser.add_argument(
        "--prefix",
        default="qr-share",
        help="Filename prefix for PNG output (default: qr-share)",
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=CHUNK_SIZE,
        help=f"Raw bytes per chunk before base64 (default: {CHUNK_SIZE}; do not change unless you know why)",
    )
    parser.add_argument(
        "--id",
        help="Override the transmission id (8 hex chars). Only useful for reproducible test runs.",
    )
    parser.add_argument(
        "--dump-payload",
        action="store_true",
        help="Print the JSON payload to stderr before emitting QRs (debugging)",
    )
    parser.add_argument(
        "--print-only",
        action="store_true",
        help="Print the envelope strings to stdout instead of rendering QRs",
    )
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    payload = build_payload(args)
    payload_text = json.dumps(payload, separators=(",", ":"))
    if args.dump_payload:
        print(payload_text, file=sys.stderr)
    envelopes = encode_envelopes(
        payload_text,
        chunk_size=args.chunk_size,
        id=args.id,
    )
    print(f"# {len(envelopes)} QR part(s) for {payload['name']}", file=sys.stderr)

    if args.print_only:
        for envelope in envelopes:
            print(envelope)
        return 0

    write_png = args.png or not sys.stdout.isatty()
    if write_png:
        paths = _render_png(envelopes, args.out_dir, args.prefix)
        for path in paths:
            print(path)
    else:
        _render_terminal(envelopes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
