"""Contract tests for ``pocketshell serve`` (issue #2333).

The command is intentionally a foreground process.  The caller owns its
process/channel and reads the one-line JSON port announcement before using
the HTTP server.  The real subprocess tests below exercise that boundary;
the remaining tests pin path containment and the Click wiring without
depending on a desktop client.
"""

from __future__ import annotations

import json
import os
import select
import signal
import subprocess
import sys
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen

import pytest
from click.testing import CliRunner

from pocketshell import serve as serve_mod
from pocketshell.cli import cli


def _readline_with_timeout(stream, timeout: float = 5.0) -> str:
    """Read one server announcement without allowing a broken server to hang pytest."""
    ready, _, _ = select.select([stream], [], [], timeout)
    assert ready, "pocketshell serve did not announce its selected port"
    line = stream.readline()
    assert line, "pocketshell serve exited before announcing its selected port"
    return line


def _start_server(root: Path) -> tuple[subprocess.Popen[str], int]:
    """Start the installed module from this checkout and parse its port line."""
    env = dict(os.environ)
    src = str(Path(__file__).resolve().parents[1] / "src")
    env["PYTHONPATH"] = os.pathsep.join(part for part in (src, env.get("PYTHONPATH", "")) if part)
    process = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "pocketshell",
            "serve",
            "--dir",
            str(root),
            "--port",
            "0",
        ],
        cwd=str(Path(__file__).resolve().parents[1]),
        env=env,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert process.stdout is not None
    announcement = _readline_with_timeout(process.stdout)
    payload = json.loads(announcement)
    assert payload.keys() == {"port"}
    port = payload["port"]
    assert isinstance(port, int)
    assert 1 <= port <= 65535
    return process, port


def _stop_server(process: subprocess.Popen[str]) -> None:
    """Stop a foreground server started by a test and drain its pipes."""
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
    try:
        process.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate(timeout=2)


def _get(port: int, path: str) -> tuple[int, str, bytes]:
    """Fetch one response and return status, content type, and body."""
    with urlopen(f"http://127.0.0.1:{port}{path}", timeout=2) as response:
        return response.status, response.headers.get("Content-Type", ""), response.read()


def test_top_level_help_lists_serve() -> None:
    result = CliRunner().invoke(cli, ["--help"])

    assert result.exit_code == 0, result.output
    assert "serve" in result.output


def test_serve_help_describes_foreground_port_contract() -> None:
    result = CliRunner().invoke(cli, ["serve", "--help"])

    assert result.exit_code == 0, result.output
    assert "--dir" in result.output
    assert "--port" in result.output
    assert "--bind" in result.output
    assert "foreground" in result.output.lower()


def test_serve_requires_directory() -> None:
    result = CliRunner().invoke(cli, ["serve"])

    assert result.exit_code == 2
    assert "--dir" in result.output


def test_serve_expands_and_resolves_directory_like_agent_helper(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    home = tmp_path / "home"
    root = home / "site"
    root.mkdir(parents=True)
    captured: dict[str, object] = {}

    class FakeServer:
        server_address = ("127.0.0.1", 43123)

        def serve_forever(self) -> None:
            pass

        def server_close(self) -> None:
            pass

    def fake_create_server(directory: Path, *, bind: str, port: int) -> FakeServer:
        captured.update(directory=directory, bind=bind, port=port)
        return FakeServer()

    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setattr(serve_mod, "create_server", fake_create_server)
    result = CliRunner().invoke(cli, ["serve", "--dir", "~/site"])

    assert result.exit_code == 0, result.output
    assert captured["directory"] == root.resolve()


def test_serve_rejects_missing_directory(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["serve", "--dir", str(tmp_path / "missing")])

    assert result.exit_code == 2
    assert "directory does not exist" in result.output


def test_serve_uses_localhost_default_and_forwards_explicit_options(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "site"
    root.mkdir()
    captured: dict[str, object] = {}

    class FakeServer:
        server_address = ("192.0.2.10", 43123)

        def serve_forever(self) -> None:
            captured["served"] = True

        def server_close(self) -> None:
            captured["closed"] = True

    def fake_create_server(directory: Path, *, bind: str, port: int) -> FakeServer:
        captured.update(directory=directory, bind=bind, port=port)
        return FakeServer()

    monkeypatch.setattr(serve_mod, "create_server", fake_create_server)
    result = CliRunner().invoke(
        cli,
        ["serve", "--dir", str(root), "--port", "43124", "--bind", "192.0.2.20"],
    )

    assert result.exit_code == 0, result.output
    assert captured == {
        "directory": root.resolve(),
        "bind": "192.0.2.20",
        "port": 43124,
        "served": True,
        "closed": True,
    }
    assert json.loads(result.output) == {"port": 43123}

    captured.clear()
    result = CliRunner().invoke(cli, ["serve", "--dir", str(root)])

    assert result.exit_code == 0, result.output
    assert captured["bind"] == "127.0.0.1"
    assert captured["port"] == 0


def test_foreground_server_announces_port_serves_index_and_guesses_mime(tmp_path: Path) -> None:
    root = tmp_path / "site"
    root.mkdir()
    (root / "index.html").write_text("<h1>home</h1>", encoding="utf-8")
    (root / "app.js").write_text("console.log('ok');", encoding="utf-8")

    process, port = _start_server(root)
    try:
        status, content_type, body = _get(port, "/")
        assert status == 200
        assert content_type.startswith("text/html")
        assert body == b"<h1>home</h1>"

        status, content_type, body = _get(port, "/app.js")
        assert status == 200
        assert "javascript" in content_type
        assert body == b"console.log('ok');"
    finally:
        _stop_server(process)


def test_directory_request_falls_back_to_nested_index(tmp_path: Path) -> None:
    root = tmp_path / "site"
    nested = root / "docs"
    nested.mkdir(parents=True)
    (nested / "index.html").write_text("nested", encoding="utf-8")

    process, port = _start_server(root)
    try:
        status, _, body = _get(port, "/docs/")
        assert status == 200
        assert body == b"nested"
    finally:
        _stop_server(process)


def test_resolved_containment_rejects_parent_traversal_and_prefix_sibling(
    tmp_path: Path,
) -> None:
    root = tmp_path / "root"
    sibling = tmp_path / "root-sibling"
    root.mkdir()
    sibling.mkdir()
    (sibling / "secret.txt").write_text("secret", encoding="utf-8")
    (root / "escape").symlink_to(sibling, target_is_directory=True)

    process, port = _start_server(root)
    try:
        with pytest.raises(HTTPError) as symlink_error:
            urlopen(f"http://127.0.0.1:{port}/escape/secret.txt", timeout=2)
        assert symlink_error.value.code == 403

        with pytest.raises(HTTPError) as parent_error:
            urlopen(
                f"http://127.0.0.1:{port}/%2e%2e/root-sibling/secret.txt",
                timeout=2,
            )
        assert parent_error.value.code == 403
    finally:
        _stop_server(process)


def test_index_symlink_cannot_escape_root(tmp_path: Path) -> None:
    root = tmp_path / "root"
    outside = tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    (outside / "index.html").write_text("outside", encoding="utf-8")
    (root / "index.html").symlink_to(outside / "index.html")

    process, port = _start_server(root)
    try:
        with pytest.raises(HTTPError) as error:
            urlopen(f"http://127.0.0.1:{port}/", timeout=2)
        assert error.value.code == 403
    finally:
        _stop_server(process)
