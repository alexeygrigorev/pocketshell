"""Unit tests for `pocketshell qr-share`.

Folds the codec round-trip tests previously living next to the
standalone QR emitter into the unified test suite and adds
Click-surface coverage for:

- `pocketshell --help` lists the `qr-share` subcommand.
- `pocketshell qr-share --help` succeeds without `qrcode` installed.
- Invoking `qr-share` without `qrcode` available surfaces the install
  hint on stderr and exits 127.
- `--png --out-dir <tmp>` writes the expected numbered PNG sequence
  when a stubbed `qrcode` module is available.

The render tests stub `pocketshell.qr_share._try_import_qrcode` and
`pocketshell.qr_share.build_payload` so they never touch the real
`qrcode` / Pillow chain (we only verify the wiring, not Pillow's PNG
output) and never read a real SSH key file.
"""

from __future__ import annotations

import base64
import binascii
import json
import pathlib
from typing import List
from unittest.mock import patch

import click
import pytest
from click.testing import CliRunner

from pocketshell import qr_share
from pocketshell.cli import cli
from pocketshell.qr_share import _read_private_key, build_payload, qr_share_command


# ---------------------------------------------------------------------------
# Codec round-trip tests (ported from the standalone QR emitter's codec
# smoke test; pin parity with the Kotlin `QrChunkCodec`).
# ---------------------------------------------------------------------------


def test_round_trip_small() -> None:
    payload = "hello world"
    parts = qr_share.encode_envelopes(payload, id="deadbeef")
    assert len(parts) == 1, f"expected 1 part, got {len(parts)}: {parts}"
    assert parts[0].startswith("pocketshell.qr.v1?"), parts[0]
    assert "part=1/1" in parts[0]
    assert "id=deadbeef" in parts[0]


def test_round_trip_multi_part() -> None:
    payload = "A" * (qr_share.CHUNK_SIZE * 2 + 17)
    parts = qr_share.encode_envelopes(payload, id="cafef00d")
    assert len(parts) == 3, (
        f"expected 3 parts, got {len(parts)}: lens={[len(p) for p in parts]}"
    )
    for idx, part in enumerate(parts, start=1):
        assert f"part={idx}/3" in part, part
        assert "id=cafef00d" in part, part

    # Reassemble — must match the original payload byte-for-byte.
    assembled = bytearray()
    for part in parts:
        query = part[len(qr_share.ENVELOPE_PREFIX):]
        params = dict(seg.split("=", 1) for seg in query.split("&"))
        encoded = params["payload"]
        padding = "=" * (-len(encoded) % 4)
        chunk = base64.urlsafe_b64decode(encoded + padding)
        expected = params["checksum"]
        actual = f"{binascii.crc32(chunk) & 0xFFFFFFFF:08x}"
        assert actual == expected, (
            f"checksum mismatch part {params['part']}: {actual} != {expected}"
        )
        assembled.extend(chunk)
    assert assembled.decode("utf-8") == payload


def test_envelope_prefix() -> None:
    [single] = qr_share.encode_envelopes("x", id="00112233")
    assert single.startswith("pocketshell.qr.v1?")


# ---------------------------------------------------------------------------
# Private-key reading — friendly errors over raw stack traces (#774 §6, #777 G2).
#
# `_read_private_key` is the single point where `qr-share` turns the resolved
# IdentityFile path into the PEM that lands in the payload's `privateKeyPem`.
# A bad path must surface as a Click-formatted error (exit 1 with a clear
# message), never an unhandled `FileNotFoundError` traceback.
# ---------------------------------------------------------------------------


def test_read_private_key_missing_file_raises_friendly_click_error(
    tmp_path: pathlib.Path,
) -> None:
    """A non-existent key path raises a friendly `ClickException` naming the
    path — not a raw `FileNotFoundError` / stack trace."""
    missing = tmp_path / "id_does_not_exist"
    assert not missing.exists()
    with pytest.raises(click.ClickException) as exc_info:
        _read_private_key(missing)
    # The message is user-facing and points at the offending path so the user
    # can fix the `--key` / IdentityFile they passed.
    message = exc_info.value.message
    assert "key file not found" in message
    assert str(missing) in message


def test_read_private_key_reads_and_strips_an_existing_key(
    tmp_path: pathlib.Path,
) -> None:
    """An existing key file is read and surrounding whitespace stripped (the
    PEM lands without a trailing newline in the payload)."""
    key_path = tmp_path / "id_ed25519"
    key_path.write_text(
        "\n-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n\n"
    )
    pem = _read_private_key(key_path)
    assert pem.startswith("-----BEGIN OPENSSH PRIVATE KEY-----")
    assert pem.endswith("-----END OPENSSH PRIVATE KEY-----")
    # `.strip()` removed the leading/trailing blank lines.
    assert not pem.startswith("\n")
    assert not pem.endswith("\n")


def test_read_private_key_empty_file_raises_friendly_click_error(
    tmp_path: pathlib.Path,
) -> None:
    """An existing-but-empty (or whitespace-only) key file is rejected with a
    friendly `ClickException` naming the path — never a silent "" PEM that would
    land an empty `privateKeyPem` in the payload.

    This was previously a characterization of the un-guarded behaviour (returned
    "" without raising). The #777 review recommended a small production guard;
    `_read_private_key` now raises for an empty file, mirroring the
    missing-file case.
    """
    empty = tmp_path / "id_empty"
    empty.write_text("   \n\t\n")
    with pytest.raises(click.ClickException) as exc_info:
        _read_private_key(empty)
    message = exc_info.value.message
    assert "private key file is empty" in message
    assert str(empty) in message


def test_build_payload_missing_explicit_key_file_surfaces_friendly_error(
    tmp_path: pathlib.Path,
) -> None:
    """End-to-end: `build_payload(--host ... --key <missing>)` propagates the
    friendly `_read_private_key` error rather than crashing — the path the CLI
    actually takes when a user points `--key` at a bad file."""
    missing = tmp_path / "nope_id"
    with pytest.raises(click.ClickException) as exc_info:
        build_payload(
            alias=None,
            host="prod.example.com",
            user="ubuntu",
            port=22,
            key=str(missing),
            name="prod",
            key_name=None,
        )
    assert "key file not found" in exc_info.value.message


# ---------------------------------------------------------------------------
# CLI-surface tests.
# ---------------------------------------------------------------------------


def test_top_level_help_lists_qr_share_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0, result.output
    assert "qr-share" in result.output


def test_qr_share_help_works_without_qrcode_installed() -> None:
    """Click `--help` must NOT trigger the lazy `qrcode` import.

    Pins the contract that listing the help text on a host without the
    optional `qr` extra installed still succeeds. Implemented by
    monkey-patching `_try_import_qrcode` to return `(None, None)` and
    checking that `--help` exits 0.
    """
    runner = CliRunner()
    with patch(
        "pocketshell.qr_share._try_import_qrcode",
        return_value=(None, None),
    ) as try_import:
        result = runner.invoke(qr_share_command, ["--help"])
    assert result.exit_code == 0, result.output
    assert "qr-share" in result.output.lower() or "QR" in result.output
    # `--help` is short-circuited by Click before the command body runs,
    # so the lazy import helper must never have been called.
    try_import.assert_not_called()


def test_qr_share_missing_qrcode_exits_127_with_install_hint(tmp_path: pathlib.Path) -> None:
    """Without `qrcode` installed, `qr-share ... --png` must surface the
    friendly install hint on stderr and exit 127.

    Uses `--print-only=False` plus `--png` so the render path is taken
    (otherwise `--print-only` would skip rendering entirely). The
    payload-build step is stubbed so the test does not need to touch a
    real SSH key or ssh-config alias.
    """
    runner = CliRunner()
    fake_payload = {
        "type": "pocketshell.ssh-import.v1",
        "version": 1,
        "name": "prod",
        "host": "prod.example.com",
        "port": 22,
        "username": "ubuntu",
        "auth": {
            "type": "privateKey",
            "name": "id_ed25519",
            "privateKeyPem": "-----BEGIN OPENSSH PRIVATE KEY-----\nx\n-----END OPENSSH PRIVATE KEY-----",
        },
    }
    with patch(
        "pocketshell.qr_share.build_payload",
        return_value=fake_payload,
    ), patch(
        "pocketshell.qr_share._try_import_qrcode",
        return_value=(None, None),
    ):
        result = runner.invoke(
            qr_share_command,
            ["prod", "--png", "--out-dir", str(tmp_path)],
            catch_exceptions=False,
        )
    assert result.exit_code == 127, result.output
    assert "qr" in result.output.lower()
    # The install hint must mention at least one of the install paths so
    # the user knows how to recover.
    assert (
        "pocketshell[qr]" in result.output
        or "qrcode[pil]" in result.output
    )


def test_qr_share_png_writes_expected_files(tmp_path: pathlib.Path) -> None:
    """With `qrcode` available (stubbed), `--png --out-dir <tmp>` writes
    the expected numbered PNG sequence.

    The `qrcode` module is stubbed end-to-end so the test does not need
    Pillow installed. We assert that:

    - The number of PNGs written equals the number of envelopes built
      (one per chunk, with a 1500-byte payload deliberately split into
      multiple chunks).
    - The filenames follow the `qr-share-NN.png` pattern.
    - Each file is written to the `--out-dir` we passed in.
    - The command exits 0.
    """
    # Large fake payload so the chunker emits multiple envelopes — pins
    # the contract that file count tracks envelope count, not payload
    # count.
    big_pem = "X" * (qr_share.CHUNK_SIZE * 2 + 50)
    fake_payload = {
        "type": "pocketshell.ssh-import.v1",
        "version": 1,
        "name": "prod",
        "host": "prod.example.com",
        "port": 22,
        "username": "ubuntu",
        "auth": {
            "type": "privateKey",
            "name": "id_ed25519",
            "privateKeyPem": big_pem,
        },
    }

    class _FakeImage:
        def __init__(self, data: str) -> None:
            self.data = data

        def save(self, path) -> None:
            # Write a tiny stub file so existence assertions hold. The
            # bytes are not a real PNG; the test doesn't decode them.
            pathlib.Path(path).write_bytes(b"FAKEPNG:" + self.data.encode("utf-8"))

    class _FakeQrCode:
        @staticmethod
        def make(data: str, error_correction=None) -> "_FakeImage":  # noqa: ARG004
            return _FakeImage(data)

    runner = CliRunner()
    with patch(
        "pocketshell.qr_share.build_payload",
        return_value=fake_payload,
    ), patch(
        "pocketshell.qr_share._try_import_qrcode",
        return_value=(_FakeQrCode, 0),  # ERROR_CORRECT_M sentinel
    ):
        result = runner.invoke(
            qr_share_command,
            ["prod", "--png", "--out-dir", str(tmp_path), "--id", "deadbeef"],
            catch_exceptions=False,
        )

    assert result.exit_code == 0, result.output

    payload_text = json.dumps(fake_payload, separators=(",", ":"))
    expected_envelopes = qr_share.encode_envelopes(
        payload_text,
        chunk_size=qr_share.CHUNK_SIZE,
        id="deadbeef",
    )
    expected_count = len(expected_envelopes)
    assert expected_count >= 2, (
        f"test setup error: expected multi-chunk payload, got {expected_count}"
    )

    written: List[pathlib.Path] = sorted(tmp_path.glob("qr-share-*.png"))
    assert len(written) == expected_count, (
        f"expected {expected_count} PNGs, found {len(written)}: {written}"
    )
    for idx, path in enumerate(written, start=1):
        assert path.name == f"qr-share-{idx:02d}.png", path
        assert path.parent == tmp_path
        assert path.read_bytes().startswith(b"FAKEPNG:")
