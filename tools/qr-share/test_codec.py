"""Smoke test for the Python emitter's chunking codec (issue #129).

Runs without external dependencies and verifies round-trip parity with
the Kotlin ``QrChunkCodec``: encoding ``A * 3500`` produces three parts
with consistent ids / checksums, and the assembled bytes match the
input.

Invoke directly:

    python3 tools/qr-share/test_codec.py
"""

from __future__ import annotations

import base64
import binascii
import sys
from pathlib import Path

# Run as a script — append the parent dir to sys.path so the import works
# without an explicit package layout.
sys.path.insert(0, str(Path(__file__).resolve().parent))

import main as emitter  # noqa: E402


def test_round_trip_small() -> None:
    payload = "hello world"
    parts = emitter.encode_envelopes(payload, id="deadbeef")
    assert len(parts) == 1, f"expected 1 part, got {len(parts)}: {parts}"
    assert parts[0].startswith("pocketshell.qr.v1?"), parts[0]
    assert "part=1/1" in parts[0]
    assert "id=deadbeef" in parts[0]


def test_round_trip_multi_part() -> None:
    payload = "A" * (emitter.CHUNK_SIZE * 2 + 17)
    parts = emitter.encode_envelopes(payload, id="cafef00d")
    assert len(parts) == 3, f"expected 3 parts, got {len(parts)}: lens={[len(p) for p in parts]}"
    for idx, part in enumerate(parts, start=1):
        assert f"part={idx}/3" in part, part
        assert "id=cafef00d" in part, part

    # Reassemble.
    assembled = bytearray()
    for part in parts:
        query = part[len(emitter.ENVELOPE_PREFIX):]
        params = dict(seg.split("=", 1) for seg in query.split("&"))
        encoded = params["payload"]
        padding = "=" * (-len(encoded) % 4)
        chunk = base64.urlsafe_b64decode(encoded + padding)
        expected = params["checksum"]
        actual = f"{binascii.crc32(chunk) & 0xFFFFFFFF:08x}"
        assert actual == expected, f"checksum mismatch part {params['part']}: {actual} != {expected}"
        assembled.extend(chunk)
    assert assembled.decode("utf-8") == payload


def test_envelope_prefix() -> None:
    [single] = emitter.encode_envelopes("x", id="00112233")
    assert single.startswith("pocketshell.qr.v1?")


if __name__ == "__main__":
    test_round_trip_small()
    test_round_trip_multi_part()
    test_envelope_prefix()
    print("OK")
