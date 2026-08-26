"""Foreground static-file server for ``pocketshell serve`` (issue #2333).

The command is deliberately a process-owned helper rather than a detached
daemon. It binds the HTTP socket, writes exactly one machine-readable JSON
announcement (``{"port": <selected-port>}``) to stdout, and then remains in
``serve_forever`` until its caller terminates the process. HTTP access logs
and errors stay on stderr so stdout remains a stable client-facing contract.

``SimpleHTTPRequestHandler`` supplies the stdlib's static-file handling and
MIME guessing. Its path translation is replaced here because the default
handler follows symlinks without checking their resolved target. Every
requested path, including an ``index.html`` fallback, must resolve beneath
the canonical serving root.
"""

from __future__ import annotations

import json
import os
import urllib.parse
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional

import click


DEFAULT_BIND = "127.0.0.1"
DEFAULT_PORT = 0


class _PathOutsideRoot(Exception):
    """Raised when a request resolves outside the configured serving root."""


def resolve_contained_path(root: Path, candidate: Path) -> Optional[Path]:
    """Resolve *candidate* and return it only when it is inside *root*.

    The comparison uses :attr:`Path.parents` after resolving both paths. It
    intentionally does not use a string-prefix comparison: ``/site`` must
    not contain ``/site-export``. ``strict=False`` for the candidate keeps a
    missing request on the normal HTTP 404 path while still resolving every
    existing symlink on the way to it.
    """
    try:
        resolved_root = root.resolve(strict=True)
        resolved_candidate = candidate.resolve(strict=False)
    except (OSError, RuntimeError, ValueError):
        # OSError covers unreadable/broken filesystem paths, RuntimeError
        # covers symlink loops, and ValueError covers malformed paths (for
        # example an embedded NUL). All are an unsafe/unservable request.
        return None

    if resolved_candidate == resolved_root or resolved_root in resolved_candidate.parents:
        return resolved_candidate
    return None


def _resolve_directory(ctx: click.Context, directory: str) -> Path:
    """Expand and resolve a required serving directory like other helpers."""
    path = Path(os.path.expanduser(directory))
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError):
        click.echo(f"pocketshell serve: directory does not exist: {path}", err=True)
        ctx.exit(2)
    if not resolved.is_dir():
        click.echo(f"pocketshell serve: directory does not exist: {path}", err=True)
        ctx.exit(2)
    return resolved


class StaticRequestHandler(SimpleHTTPRequestHandler):
    """Serve files below one resolved root, rejecting escaped real paths."""

    # The contract calls for index.html. Do not silently add another index
    # filename whose fallback semantics a client did not request.
    index_pages = ("index.html",)

    def __init__(self, *args, directory: str | os.PathLike[str] | None = None, **kwargs):
        configured_root = Path(directory if directory is not None else os.getcwd())
        self._serve_root = configured_root.expanduser().resolve(strict=True)
        super().__init__(*args, directory=str(self._serve_root), **kwargs)

    def translate_path(self, path: str) -> str:
        """Translate a URL path and enforce resolved-root containment."""
        # Match SimpleHTTPRequestHandler's query/fragment treatment, but keep
        # ``..`` components in the candidate so the resolved containment check
        # is the authority that rejects them instead of a lexical filter.
        path_without_fragment = path.split("#", 1)[0]
        raw_path = path_without_fragment.split("?", 1)[0]
        try:
            decoded_path = urllib.parse.unquote(raw_path, errors="surrogatepass")
        except UnicodeDecodeError:
            decoded_path = urllib.parse.unquote(raw_path)

        trailing_slash = decoded_path.endswith("/")
        candidate = self._serve_root / decoded_path.lstrip("/")
        resolved = resolve_contained_path(self._serve_root, candidate)
        if resolved is None:
            raise _PathOutsideRoot(decoded_path)

        # The stdlib handler uses the trailing slash to decide whether a
        # directory should redirect or fall through to its index page.
        rendered = str(resolved)
        if trailing_slash and not rendered.endswith(os.sep):
            rendered += os.sep
        return rendered

    def send_head(self):  # type: ignore[no-untyped-def]
        """Use stdlib response handling after validating all fallback paths."""
        try:
            translated = Path(self.translate_path(self.path))
            if translated.is_dir():
                # SimpleHTTPRequestHandler joins index.html after calling
                # translate_path, so validate that second path ourselves too.
                # Otherwise an in-root directory could contain an index symlink
                # pointing outside the configured root.
                for index_name in self.index_pages:
                    index_candidate = translated / index_name
                    if os.path.lexists(index_candidate):
                        if resolve_contained_path(self._serve_root, index_candidate) is None:
                            raise _PathOutsideRoot(str(index_candidate))
                        break
        except _PathOutsideRoot:
            self.send_error(
                HTTPStatus.FORBIDDEN,
                "Path escapes the serving directory",
            )
            return None

        try:
            return super().send_head()
        except _PathOutsideRoot:
            self.send_error(
                HTTPStatus.FORBIDDEN,
                "Path escapes the serving directory",
            )
            return None


class StaticHTTPServer(ThreadingHTTPServer):
    """Threaded HTTP server whose worker requests do not pin process exit."""

    allow_reuse_address = True
    daemon_threads = True


def create_server(directory: Path, *, bind: str, port: int) -> StaticHTTPServer:
    """Bind a serving server to *bind*/*port* before the port is announced."""
    handler = partial(StaticRequestHandler, directory=str(directory))
    return StaticHTTPServer((bind, port), handler)


def serve_directory(directory: Path, *, bind: str, port: int) -> None:
    """Run a foreground server and announce its actual bound port."""
    try:
        server = create_server(directory, bind=bind, port=port)
    except OSError as exc:
        raise click.ClickException(f"could not bind HTTP server to {bind}:{port}: {exc}") from exc

    try:
        selected_port = int(server.server_address[1])
        click.echo(json.dumps({"port": selected_port}, separators=(",", ":")))
        server.serve_forever()
    except KeyboardInterrupt:
        # Match the normal foreground lifecycle of the other host helpers:
        # Ctrl-C ends this process cleanly without a traceback.
        return
    finally:
        server.server_close()


@click.command(
    name="serve",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Serve a directory over HTTP in the foreground. The command writes "
        "one JSON port announcement to stdout, then stays alive until the "
        "owning process/channel terminates it."
    ),
)
@click.option(
    "--dir",
    "directory",
    required=True,
    type=str,
    help="Directory to serve.",
)
@click.option(
    "--port",
    type=click.IntRange(min=0, max=65535),
    default=DEFAULT_PORT,
    show_default=False,
    help="TCP port to bind; 0 (the default) asks the OS for a free port.",
)
@click.option(
    "--bind",
    default=DEFAULT_BIND,
    show_default=True,
    help="Local address to bind.",
)
@click.pass_context
def serve_command(ctx: click.Context, directory: str, port: int, bind: str) -> None:
    """Serve *directory* until the foreground owner terminates this process."""
    serve_directory(_resolve_directory(ctx, directory), bind=bind, port=port)
