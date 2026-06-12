"""Server-side agent profile discovery + the `pocketshell profiles` group.

Issue [#718](https://github.com/alexeygrigorev/pocketshell/issues/718),
slice 1 (server). Profiles are defined **once on the host** — never edited
on the mobile client — so the client can fetch them with
``pocketshell profiles list`` and feed its picker.

A *profile* names a coding-agent config dir:

- **claude** → ``CLAUDE_CONFIG_DIR``
- **codex** → ``CODEX_HOME``

(opencode has no profile env var, so it is out of scope — see
``build_env`` in :mod:`pocketshell.agents`.)

Two complementary sources are merged (the explicit config file wins on a
name collision):

1. **Conventional-dir auto-discovery** (the zero-config path). For each
   engine, scan the top level of ``$HOME`` for config dirs that carry a
   real *marker* file:

   - claude marker: ``.claude.json`` **or** ``settings.json``
   - codex marker: ``config.toml`` **or** ``auth.json``

   The engine's **default** dir (``~/.claude`` / ``~/.codex``) becomes the
   default profile, named after the engine ("Claude" / "Codex") and sorted
   first; the picker pre-selects it. Any *sibling* dir that matches the
   engine's name pattern and carries a marker becomes a non-default profile
   (e.g. ``~/.zlaude`` → "Claude (Z.AI)" via a small known-alias map, with
   a humanised dir-stem fallback). Discovery is deliberately conservative:
   top-level ``~/.<name>`` dirs only, a real marker file required, never
   recursive, so a stray empty dir never becomes a phantom profile.

2. **Optional explicit config** ``~/.config/pocketshell/profiles.yaml``
   (``XDG_CONFIG_HOME`` honoured). A list of
   ``{name, engine, config_dir, env?}`` entries. It augments and overrides
   discovery: an explicit profile wins on a ``name`` collision, and its
   ``config_dir`` claims that ``(engine, dir)`` so discovery won't add a
   duplicate.

Security: a profile references **config_dirs only, never keys**. Discovery
stats a handful of dirs and reads marker *names* — it never reads inside a
config dir (those hold ``auth.json`` / ``.env``). ``profiles list`` emits
``{name, engine, config_dir, default}`` and nothing else.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import click

try:  # pragma: no cover - import guard
    import yaml
except ImportError:  # pragma: no cover - yaml is a hard dep, guard is defensive
    yaml = None  # type: ignore[assignment]


# Engines that support a profile config dir. opencode has no profile env var.
PROFILE_ENGINES: tuple[str, ...] = ("claude", "codex")

# Per-engine discovery rules: the default top-level dir name and the marker
# files that prove a directory is that engine's config dir.
_ENGINE_DEFAULT_DIRNAME: dict[str, str] = {
    "claude": ".claude",
    "codex": ".codex",
}
_ENGINE_MARKERS: dict[str, tuple[str, ...]] = {
    "claude": (".claude.json", "settings.json"),
    "codex": ("config.toml", "auth.json"),
}
# Sibling-dir name substrings that flag a non-default profile for an engine.
# A top-level ``~/.<name>`` dir whose stem contains ANY of these (and isn't
# the default dir) is a candidate profile for that engine. The suffix hints
# (``laude`` / ``odex``) catch the maintainer's single-leading-letter swaps
# like ``zlaude`` (Z.AI) where ``claude`` itself isn't a substring.
_ENGINE_NAME_HINTS: dict[str, tuple[str, ...]] = {
    "claude": ("claude", "laude"),
    "codex": ("codex", "odex"),
}

# Display name for the engine's default profile (sorted first, pre-selected).
_ENGINE_DEFAULT_DISPLAY: dict[str, str] = {
    "claude": "Claude",
    "codex": "Codex",
}

# Known dir-stem → display-name aliases for non-default profiles. The
# maintainer's ``~/.zlaude`` is the Z.AI-routed Claude profile.
_KNOWN_ALIASES: dict[str, str] = {
    "zlaude": "Claude (Z.AI)",
}


@dataclass(frozen=True)
class Profile:
    """A named agent profile resolving to a config dir for one engine.

    ``config_dir`` is ``None`` for the engine's built-in default (which maps
    to an empty ``--config-dir`` at launch — the agent uses its own default
    location). ``default`` flags the engine's default profile, which the
    picker pre-selects. ``env`` carries optional extra environment from the
    explicit config file (server-side only; never secrets via the wire).
    """

    name: str
    engine: str
    config_dir: Optional[str] = None
    default: bool = False
    env: dict[str, str] = field(default_factory=dict)


def _humanise_stem(stem: str) -> str:
    """Turn a dir stem like ``zlaude`` into a display name ``Zlaude``."""
    cleaned = stem.lstrip(".").replace("-", " ").replace("_", " ").strip()
    if not cleaned:
        return stem
    return " ".join(part.capitalize() for part in cleaned.split())


def _display_name_for_sibling(engine: str, stem: str) -> str:
    """Display name for a non-default sibling dir of ``engine``."""
    key = stem.lstrip(".")
    if key in _KNOWN_ALIASES:
        return _KNOWN_ALIASES[key]
    return _humanise_stem(stem)


def _home_dir(env: Optional[dict[str, str]] = None) -> Path:
    """Resolve ``$HOME`` (honouring an injected env for tests)."""
    source = env if env is not None else os.environ
    home = source.get("HOME")
    if home:
        return Path(home)
    return Path(os.path.expanduser("~"))


def _has_marker(directory: Path, markers: tuple[str, ...]) -> bool:
    """True if ``directory`` is a dir carrying one of ``markers``."""
    if not directory.is_dir():
        return False
    return any((directory / marker).is_file() for marker in markers)


def _config_file_path(env: Optional[dict[str, str]] = None) -> Path:
    """Path to the optional ``profiles.yaml`` (XDG_CONFIG_HOME honoured)."""
    source = env if env is not None else os.environ
    xdg = source.get("XDG_CONFIG_HOME")
    if xdg:
        base = Path(xdg)
    else:
        base = _home_dir(source) / ".config"
    return base / "pocketshell" / "profiles.yaml"


def discover_profiles(
    env: Optional[dict[str, str]] = None,
) -> list[Profile]:
    """Auto-discover conventional config-dir profiles per engine.

    Conservative: only top-level ``~/.<name>`` dirs carrying a real marker
    file (see module docstring). The default dir → the engine default
    profile (``config_dir=None``, ``default=True``); matching sibling dirs →
    non-default profiles with their absolute ``config_dir``.
    """
    home = _home_dir(env)
    out: list[Profile] = []

    for engine in PROFILE_ENGINES:
        markers = _ENGINE_MARKERS[engine]
        default_dirname = _ENGINE_DEFAULT_DIRNAME[engine]
        hints = _ENGINE_NAME_HINTS[engine]

        # Default dir first (config_dir=None = the engine's built-in default).
        default_dir = home / default_dirname
        if _has_marker(default_dir, markers):
            out.append(
                Profile(
                    name=_ENGINE_DEFAULT_DISPLAY[engine],
                    engine=engine,
                    config_dir=None,
                    default=True,
                )
            )

        # Sibling dirs: top-level ~/.<name> matching the engine hint,
        # carrying a marker, that aren't the default dir. Sorted for a
        # deterministic order.
        siblings: list[Profile] = []
        try:
            entries = sorted(home.iterdir())
        except OSError:
            entries = []
        for entry in entries:
            stem = entry.name
            if not stem.startswith("."):
                continue
            if stem == default_dirname:
                continue
            if not any(hint in stem.lower() for hint in hints):
                continue
            if not _has_marker(entry, markers):
                continue
            siblings.append(
                Profile(
                    name=_display_name_for_sibling(engine, stem),
                    engine=engine,
                    config_dir=str(entry),
                    default=False,
                )
            )
        out.extend(siblings)

    return out


def _expand_config_dir(raw: Optional[str], env: Optional[dict[str, str]]) -> Optional[str]:
    """Expand ``~`` / ``$VAR`` in a config-file ``config_dir`` to an abspath."""
    if raw is None:
        return None
    text = str(raw).strip()
    if not text:
        return None
    home = str(_home_dir(env))
    if text == "~":
        return home
    if text.startswith("~/"):
        text = home + text[1:]
    text = os.path.expandvars(text)
    return str(Path(text))


def load_config_profiles(
    env: Optional[dict[str, str]] = None,
) -> list[Profile]:
    """Load explicit profiles from ``~/.config/pocketshell/profiles.yaml``.

    Returns ``[]`` when the file is absent or empty. Malformed entries
    (missing ``name`` / unknown ``engine``) are skipped quietly so a
    typo never breaks discovery — the conventional-dir path still works.
    """
    path = _config_file_path(env)
    if not path.is_file():
        return []
    if yaml is None:  # pragma: no cover - yaml is a hard dependency
        return []
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError):  # type: ignore[union-attr]
        return []
    if not isinstance(raw, dict):
        return []
    entries = raw.get("profiles")
    if not isinstance(entries, list):
        return []

    out: list[Profile] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        name = entry.get("name")
        engine = entry.get("engine")
        if not isinstance(name, str) or not name.strip():
            continue
        if engine not in PROFILE_ENGINES:
            continue
        config_dir = _expand_config_dir(entry.get("config_dir"), env)
        extra_env = entry.get("env")
        env_map: dict[str, str] = {}
        if isinstance(extra_env, dict):
            env_map = {str(k): str(v) for k, v in extra_env.items()}
        out.append(
            Profile(
                name=name.strip(),
                engine=engine,
                config_dir=config_dir,
                default=(config_dir is None),
                env=env_map,
            )
        )
    return out


def load_profiles(
    env: Optional[dict[str, str]] = None,
    *,
    engine: Optional[str] = None,
) -> list[Profile]:
    """Merge explicit-config + discovered profiles into the final list.

    Resolution: explicit config-file profiles first (they win on a ``name``
    collision and claim their ``(engine, config_dir)`` so discovery won't
    duplicate), then discovered profiles whose name and ``(engine, dir)``
    aren't already taken. Within each engine the default profile sorts
    first, then config-file order, then discovery order. ``engine`` filters
    the result to a single engine.
    """
    config_profiles = load_config_profiles(env)
    discovered = discover_profiles(env)

    taken_names: set[str] = set()
    taken_dirs: set[tuple[str, Optional[str]]] = set()
    merged: list[Profile] = []

    for profile in config_profiles:
        taken_names.add(profile.name)
        taken_dirs.add((profile.engine, profile.config_dir))
        merged.append(profile)

    for profile in discovered:
        if profile.name in taken_names:
            continue
        if (profile.engine, profile.config_dir) in taken_dirs:
            continue
        taken_names.add(profile.name)
        taken_dirs.add((profile.engine, profile.config_dir))
        merged.append(profile)

    if engine is not None:
        merged = [p for p in merged if p.engine == engine]

    # Stable sort: engine order, default first, otherwise keep insertion order.
    engine_rank = {eng: i for i, eng in enumerate(PROFILE_ENGINES)}
    indexed = list(enumerate(merged))
    indexed.sort(
        key=lambda pair: (
            engine_rank.get(pair[1].engine, len(PROFILE_ENGINES)),
            0 if pair[1].default else 1,
            pair[0],
        )
    )
    return [profile for _, profile in indexed]


def resolve_profile(
    name: str,
    engine: str,
    env: Optional[dict[str, str]] = None,
) -> Profile:
    """Resolve a profile ``name`` for ``engine`` to its :class:`Profile`.

    Raises :class:`KeyError` when no profile of that name exists for the
    engine (the CLI turns this into a clear error). Matching is exact on
    the display ``name`` first; if that misses, a case-insensitive match is
    attempted so a client can pass either ``"Claude (Z.AI)"`` or a slug.
    """
    profiles = load_profiles(env, engine=engine)
    for profile in profiles:
        if profile.name == name:
            return profile
    lowered = name.strip().lower()
    for profile in profiles:
        if profile.name.lower() == lowered:
            return profile
    raise KeyError(name)


def _profile_payload(profile: Profile) -> dict[str, object]:
    """Serialisable, secret-free dict for one profile."""
    return {
        "name": profile.name,
        "engine": profile.engine,
        "config_dir": profile.config_dir,
        "default": profile.default,
    }


# ---------------------------------------------------------------------------
# Click surface
# ---------------------------------------------------------------------------


@click.group(
    name="profiles",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Inspect the host's coding-agent profiles (claude / codex).\n\n"
        "Profiles are defined ONCE on the host — auto-discovered from "
        "conventional config dirs (~/.claude, ~/.zlaude, ~/.codex …) and an "
        "optional ~/.config/pocketshell/profiles.yaml — so the mobile "
        "client fetches them instead of storing them per-host. See #718."
    ),
)
def profiles_group() -> None:
    """Top-level `profiles` group registered onto the root CLI."""


@profiles_group.command("list")
@click.option(
    "--engine",
    type=click.Choice(PROFILE_ENGINES),
    default=None,
    help="Limit to one engine (claude / codex). Default: all engines.",
)
@click.option(
    "--json",
    "as_json",
    is_flag=True,
    help="Emit JSON instead of YAML (the client parses JSON today).",
)
def profiles_list(engine: Optional[str], as_json: bool) -> None:
    """List the host's agent profiles as YAML (default) or JSON.

    Each entry is ``{name, engine, config_dir, default}``. Never prints
    anything from inside a config dir (no keys / secrets).
    """
    profiles = load_profiles(engine=engine)
    payload = {"profiles": [_profile_payload(p) for p in profiles]}

    if as_json:
        import json

        click.echo(json.dumps(payload, indent=2, sort_keys=False))
        return

    if yaml is None:  # pragma: no cover - yaml is a hard dependency
        raise click.ClickException(
            "PyYAML is required for YAML output; pass --json instead."
        )
    text = yaml.safe_dump(payload, sort_keys=False, default_flow_style=False)
    click.echo(text.rstrip("\n"))
