"""Host-level PocketShell CLI configuration (``~/.config/pocketshell/config.toml``).

Today this file carries exactly one section — ``[backends]`` — the per-host
policy that decides which session backend a *new* session is created on:

.. code-block:: toml

    [backends]
    agent = "tmux"      # sessions created with --engine
    shell = "tmux"      # plain shell sessions

Why a pocketshell-owned config file rather than a flag or a backend-side
setting: routing is deployment policy that spans BOTH backends, and neither
backend can answer "not me" (see the simplification plan, §"Backend routing
policy"). The phone never sends a backend; the host decides.

Defaults are ``tmux`` for both keys. Flipping ``agent = "aplexer"`` is a
deliberate host config change the maintainer makes — never a code change and
never an automatic upgrade.

Parsing rules (deliberate, see ``load_config``):

* missing file / empty file -> defaults;
* malformed TOML, a non-table ``[backends]``, or an unknown backend value ->
  :class:`ConfigError`, i.e. **fail loud**. Silently falling back to the
  defaults would route agent sessions to tmux while the host owner believes
  aplexer is switched on — a wrong-path failure that shows up as "the feature
  just doesn't work", which is exactly the class of silent misroute this
  project rejects. A typo in a two-line file is cheap to fix once it is
  reported; a silent misroute is not.

``tomllib`` is used unconditionally: this package's ``requires-python`` is
``>=3.11``, where ``tomllib`` is stdlib, so there is no ``tomli`` fallback to
maintain (D22: no compatibility shims).
"""

from __future__ import annotations

import os
import tomllib
from pathlib import Path
from typing import Any, Mapping, Optional

#: Session backends this CLI knows how to create on.
BACKEND_TMUX = "tmux"
BACKEND_APLEXER = "aplexer"
BACKENDS = (BACKEND_TMUX, BACKEND_APLEXER)

#: Backend used when the config file says nothing.
DEFAULT_BACKEND = BACKEND_TMUX

#: ``[backends]`` keys: ``agent`` covers a session launched with an engine,
#: ``shell`` a plain shell session.
BACKEND_KEY_AGENT = "agent"
BACKEND_KEY_SHELL = "shell"
BACKEND_KEYS = (BACKEND_KEY_AGENT, BACKEND_KEY_SHELL)

DEFAULT_BACKENDS: dict[str, str] = {
    BACKEND_KEY_AGENT: DEFAULT_BACKEND,
    BACKEND_KEY_SHELL: DEFAULT_BACKEND,
}


class ConfigError(RuntimeError):
    """``config.toml`` exists but cannot be trusted (malformed / bad value)."""


def _env_source(env: Optional[Mapping[str, str]] = None) -> Mapping[str, str]:
    return env if env is not None else os.environ


def _home_dir(env: Optional[Mapping[str, str]] = None) -> Path:
    """Resolve ``$HOME`` (honouring an injected env for tests)."""
    home = _env_source(env).get("HOME")
    if home:
        return Path(home)
    return Path(os.path.expanduser("~"))


def config_path(env: Optional[Mapping[str, str]] = None) -> Path:
    """``$XDG_CONFIG_HOME``/``~/.config`` + ``pocketshell/config.toml``.

    Same resolution order as ``profiles.py``'s ``profiles.yaml`` so a host
    keeps all PocketShell config in one directory.
    """
    xdg = _env_source(env).get("XDG_CONFIG_HOME")
    base = Path(xdg) if xdg else _home_dir(env) / ".config"
    return base / "pocketshell" / "config.toml"


def default_config() -> dict[str, Any]:
    """A fresh copy of the fully-defaulted config document."""
    return {"backends": dict(DEFAULT_BACKENDS)}


def _normalize_backends(raw: Any, *, path: Path) -> dict[str, str]:
    """Validate the ``[backends]`` table and merge it over the defaults."""
    backends = dict(DEFAULT_BACKENDS)
    if raw is None:
        return backends
    if not isinstance(raw, Mapping):
        raise ConfigError(
            f"{path}: [backends] must be a table, got {type(raw).__name__}"
        )
    for key, value in raw.items():
        if key not in BACKEND_KEYS:
            raise ConfigError(
                f"{path}: unknown key [backends].{key} "
                f"(known keys: {', '.join(BACKEND_KEYS)})"
            )
        if not isinstance(value, str) or value.strip() not in BACKENDS:
            raise ConfigError(
                f"{path}: [backends].{key} must be one of "
                f"{', '.join(BACKENDS)}, got {value!r}"
            )
        backends[key] = value.strip()
    return backends


def load_config(env: Optional[Mapping[str, str]] = None) -> dict[str, Any]:
    """Read ``~/.config/pocketshell/config.toml``.

    Returns the parsed document with a fully-populated ``backends`` table
    (every key present, defaults filled in) so callers never have to probe for
    key presence. Any other top-level table the file happens to carry is
    passed through untouched — this loader owns ``[backends]`` only.

    A missing file returns the defaults. A file that exists but is malformed
    (bad TOML, non-table ``[backends]``, unknown key, unknown backend value,
    unreadable) raises :class:`ConfigError` — see the module docstring for why
    this fails loud instead of degrading to defaults.
    """
    path = config_path(env)
    if not path.is_file():
        return default_config()
    try:
        raw_bytes = path.read_bytes()
    except OSError as exc:
        raise ConfigError(f"{path}: cannot be read: {exc}") from exc
    try:
        document = tomllib.loads(raw_bytes.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        raise ConfigError(f"{path}: is not valid TOML: {exc}") from exc
    if not isinstance(document, dict):  # pragma: no cover - tomllib invariant
        raise ConfigError(f"{path}: top level must be a table")
    normalized = dict(document)
    normalized["backends"] = _normalize_backends(document.get("backends"), path=path)
    return normalized


def backend_for(config: Mapping[str, Any], key: str) -> str:
    """Read one ``[backends]`` key out of a (possibly partial) config dict.

    Tolerant on purpose: :func:`load_config` is the strict validator, and this
    accessor is also handed hand-built dicts (tests, callers that skipped the
    loader). Anything missing or not a usable string resolves to
    :data:`DEFAULT_BACKEND`.
    """
    backends = config.get("backends") if isinstance(config, Mapping) else None
    value = backends.get(key) if isinstance(backends, Mapping) else None
    if isinstance(value, str) and value.strip():
        return value.strip()
    return DEFAULT_BACKEND
