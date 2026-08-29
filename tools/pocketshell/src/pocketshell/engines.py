"""Host-side declarative registry for coding-agent engines.

The registry is the single source for the engine id used by the wrapper and
the metadata sent to the Android picker.  Built-in entries provide the
existing engines; ``~/.config/pocketshell/engines.yaml`` can add, override,
enable, or disable entries without a Kotlin change.

Registry ids are intentionally open.  ``family`` is the closed detection
projection used by the client (for example, ``godex`` can use the ``codex``
family), while ``id`` is the durable value recorded as ``@ps_agent_kind``.

Availability (issue #2276) is an observation this module makes at manifest
build time, never a config bit: see :func:`resolve_harnesses`.  The single
deliberate escape hatch is ``force_available:`` in ``engines.yaml``.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import signal
import subprocess
from dataclasses import dataclass, replace
from glob import glob
from pathlib import Path
from typing import Mapping, Optional

import click

from pocketshell import aplexer

try:  # pragma: no cover - import guard
    import yaml
except ImportError:  # pragma: no cover - yaml is a hard dependency
    yaml = None  # type: ignore[assignment]


# The provider strip is launch policy, not an app-side engine list.  Keeping
# it in the registry makes custom engines inherit the same billing safeguard.
PROVIDER_ENV_UNSET_VARS: tuple[str, ...] = (
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_PROFILE",
    "AWS_REGION",
    "AWS_BEARER_TOKEN_BEDROCK",
    "AWS_WEB_IDENTITY_TOKEN_FILE",
    "AWS_ROLE_ARN",
    "OPENAI_API_KEY",
    "OPENAI_BASE_URL",
    "OPENAI_ORG_ID",
    "OPENAI_PROJECT_ID",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_AUTH_TOKEN",
    "GROQ_API_KEY",
    "GOOGLE_APPLICATION_CREDENTIALS",
    "GOOGLE_CLOUD_PROJECT",
    "GOOGLE_API_KEY",
    "VERTEX_LOCATION",
    "VERTEX_AI_PROJECT",
    "DEEPSEEK_API_KEY",
    "XAI_API_KEY",
    "FIREWORKS_API_KEY",
    "CEREBRAS_API_KEY",
    "OPENROUTER_API_KEY",
    "TOGETHER_API_KEY",
    "TOGETHER_AI_API_KEY",
    "AZURE_API_KEY",
    "AZURE_RESOURCE_NAME",
    "AZURE_COGNITIVE_SERVICES_RESOURCE_NAME",
    "AZURE_OPENAI_API_KEY",
    "AZURE_OPENAI_ENDPOINT",
    "CLOUDFLARE_API_TOKEN",
    "CLOUDFLARE_ACCOUNT_ID",
    "CLOUDFLARE_GATEWAY_ID",
    "CLOUDFLARE_API_KEY",
    "HUGGING_FACE_API_KEY",
    "HF_TOKEN",
    "HF_API_TOKEN",
    "MOONSHOT_API_KEY",
    "MOONSHOTAI_API_KEY",
    "MINIMAX_API_KEY",
    "NEBIUS_API_KEY",
    "DEEPINFRA_API_KEY",
    "BASETEN_API_KEY",
    "VENICE_API_KEY",
    "SCALEWAY_API_KEY",
    "OVH_API_KEY",
    "CORTECS_API_KEY",
    "IONET_API_KEY",
    "VERCEL_API_KEY",
    "ZENMUX_API_KEY",
    "ZAI_API_KEY",
    "HELICONE_API_KEY",
    "OPENCODE_API_KEY",
    "OPENCODE_ZEN_API_KEY",
    "GITLAB_TOKEN",
    "GITLAB_INSTANCE_URL",
    "GITLAB_AI_GATEWAY_URL",
    "GITLAB_OAUTH_CLIENT_ID",
    "AICORE_SERVICE_KEY",
    "AICORE_DEPLOYMENT_ID",
    "AICORE_RESOURCE_GROUP",
    "OPENAI_COMPATIBLE_API_KEY",
    "LMSTUDIO_API_KEY",
    "OLLAMA_API_KEY",
    "302AI_API_KEY",
    "FIRMWARE_API_KEY",
    "2AI_API_KEY",
    "GEMINI_API_KEY",
)


@dataclass(frozen=True)
class ProfileSpec:
    """Declarative profile discovery and launch environment metadata."""

    env_var: str
    default_dirname: str
    markers: tuple[str, ...] = ()
    name_hints: tuple[str, ...] = ()
    default_label: str = ""


def _ordered_env_unset_union(*groups: tuple[str, ...]) -> tuple[str, ...]:
    """Keep the built-in provider strip when config adds custom variables."""
    return tuple(
        dict.fromkeys(
            name
            for group in groups
            for name in group
            if name.strip()
        )
    )


@dataclass(frozen=True)
class LaunchSpec:
    """Declarative launch argv/env/profile behavior for one engine."""

    argv: tuple[str, ...]
    skip_permissions_argv: tuple[str, ...] = ()
    env_unset: tuple[str, ...] = PROVIDER_ENV_UNSET_VARS
    env_set: tuple[tuple[str, str], ...] = ()
    profile_env: Optional[str] = None
    profile: Optional[ProfileSpec] = None

    def __post_init__(self) -> None:
        # A custom registry entry may add launch.env.unset entries, but it can
        # never opt out of the host-wide subscription/API-key safety policy.
        object.__setattr__(
            self,
            "env_unset",
            _ordered_env_unset_union(PROVIDER_ENV_UNSET_VARS, self.env_unset),
        )

    @property
    def supports_skip_permissions(self) -> bool:
        return bool(self.skip_permissions_argv)


@dataclass(frozen=True)
class EngineManifest:
    """One host registry entry, including current availability/config state.

    ``available`` is an OBSERVATION (see :func:`resolve_harnesses`), never an
    input the config can go stale on.  ``force_available`` is the deliberate
    escape hatch: an ``engines.yaml`` entry may set it to pin availability on
    a host whose layout the resolver cannot anticipate.  ``force_available``
    is deliberately a different key from the ``available`` output field so a
    copied/stale manifest row can never be mistaken for that intent.
    """

    id: str
    family: str
    harness: str
    label: str
    provider_mark: str
    launch: LaunchSpec
    usage_provider: Optional[str] = None
    enabled: bool = True
    available: bool = True
    unavailable_reason: Optional[str] = None
    # Explicit `force_available:` from engines.yaml. None = probe decides.
    force_available: Optional[bool] = None
    # Explicit `unavailable_reason:` from engines.yaml, kept so the probe
    # cannot silently discard a host-authored explanation.
    configured_unavailable_reason: Optional[str] = None

    @property
    def available_for_create(self) -> bool:
        return self.enabled and self.available

    def to_payload(self) -> dict[str, object]:
        launch: dict[str, object] = {
            "argv": list(self.launch.argv),
            "skip_permissions_argv": list(self.launch.skip_permissions_argv),
            "supports_skip_permissions": self.launch.supports_skip_permissions,
            "env": {
                "set": dict(self.launch.env_set),
                "unset": list(self.launch.env_unset),
            },
            "profile_env": self.launch.profile_env,
        }
        if self.launch.profile is not None:
            profile = self.launch.profile
            launch["profile"] = {
                "env_var": profile.env_var,
                "default_dirname": profile.default_dirname,
                "markers": list(profile.markers),
                "name_hints": list(profile.name_hints),
                "default_label": profile.default_label,
            }
        return {
            "id": self.id,
            "family": self.family,
            "harness": self.harness,
            "label": self.label,
            "provider_mark": self.provider_mark,
            "usage_provider": self.usage_provider,
            "enabled": self.enabled,
            "available": self.available,
            "available_for_create": self.available_for_create,
            "unavailable_reason": self.unavailable_reason,
            "force_available": self.force_available,
            "launch": launch,
        }


_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
DISABLED_ENGINE_REASON = "disabled in the host registry"
FORCED_UNAVAILABLE_REASON = "pinned unavailable by `force_available: false`"


def _missing_harness_reason(item: EngineManifest) -> str:
    return f"`{item.harness}` is not installed on this host (not on PATH)."


def _availability_reason(item: EngineManifest, available: bool) -> Optional[str]:
    """Derive the user-facing reason for a non-createable engine.

    A disabled engine whose harness is ALSO missing used to report only the
    disablement, which silently lost the more actionable half of the state.
    Both halves are reported when both apply.
    """
    if item.enabled and available:
        return None
    if not available and item.force_available is False:
        return FORCED_UNAVAILABLE_REASON
    if not available and not item.enabled:
        return (
            f"`{item.harness}` is not installed on this host (not on PATH) "
            f"and is {DISABLED_ENGINE_REASON}."
        )
    if not available:
        return _missing_harness_reason(item)
    return DISABLED_ENGINE_REASON


def _effective_reason(item: EngineManifest, available: bool) -> Optional[str]:
    """Prefer a host-authored reason over the derived one when unavailable."""
    if item.enabled and available:
        return None
    if item.configured_unavailable_reason:
        return item.configured_unavailable_reason
    return _availability_reason(item, available)


# ---------------------------------------------------------------------------
# Harness resolution (issue #2276)
# ---------------------------------------------------------------------------
#
# The manifest is built over the app's NON-interactive SSH `exec` channel
# (`PocketshellCommand.wrap` -> `pocketshell engines list --json`), but the
# harness itself is launched by `send-keys` into a tmux pane, i.e. from a
# LOGIN shell.  Those two environments have different PATHs whenever a
# harness is installed by a version manager: on the maintainer's host
# `codex`/`opencode` live in `~/.nvm/versions/node/v24.13.1/bin`, which the
# exec channel never sees, so a plain `shutil.which` reported two installed
# engines as "not installed" and the picker hid them.
#
# This is the same defect class #484/#490 fixed for the `pocketshell` binary
# itself, one level down.  The ladder below mirrors that fix: resolve the way
# the harness will actually be launched (login shell), then fall back to the
# absolute install locations a login shell can still miss (#484 rejected the
# login shell as a SOLE mechanism because a `~/.bashrc`-only PATH export never
# runs for a non-interactive login shell).
#
# Cost stays "one cheap manifest-time observation" per the issue's non-goals:
# the plain-PATH lookup answers the happy path with zero subprocesses, and the
# login shell is consulted at most once per manifest build (cached per env),
# only when at least one harness is still unresolved.

LOGIN_SHELL_PATH_TIMEOUT_S = 5.0
# Kill switch for hosts where spawning a login shell is undesirable.
LOGIN_SHELL_PROBE_KILL = "POCKETSHELL_ENGINE_LOGIN_SHELL_PROBE"
#: How the login shell is asked for its PATH, most portable form first.
#: `printenv PATH` is shell-agnostic; fish, for example, expands `"$PATH"` to a
#: SPACE-separated list, so the POSIX `printf` form is only the fallback for a
#: host without `printenv`.
LOGIN_SHELL_PATH_COMMANDS: tuple[str, ...] = (
    "printenv PATH",
    'printf %s "$PATH"',
)

#: Absolute install locations probed when neither the exec `PATH` nor the
#: login shell resolves a harness.  Globs are expanded (newest match first);
#: `$HOME`-relative patterns are skipped when the environment has no `HOME`.
LAUNCH_PATH_PATTERNS: tuple[str, ...] = (
    # Plain per-user/system bin dirs (mirrors PocketshellCommand.PATH_PREFIX_DIRS).
    "$HOME/.local/bin",
    "$HOME/bin",
    "$HOME/.cargo/bin",
    "$HOME/.pixi/bin",
    "/usr/local/bin",
    "/opt/homebrew/bin",
    # Node version managers — every agent harness in the registry today ships
    # as an npm package, so this is where they usually land.
    "$HOME/.nvm/versions/node/*/bin",
    "$HOME/.local/share/fnm/node-versions/*/installation/bin",
    "$HOME/.volta/bin",
    "$HOME/.bun/bin",
    "$HOME/.deno/bin",
    "$HOME/.npm-global/bin",
    "$HOME/.npm-packages/bin",
    "$HOME/.yarn/bin",
    "$HOME/.config/yarn/global/node_modules/.bin",
    # Generic language/tool version managers.
    "$HOME/.asdf/shims",
    "$HOME/.asdf/installs/*/*/bin",
    "$HOME/.local/share/mise/shims",
    "$HOME/.rye/shims",
    "$HOME/go/bin",
)

_LOGIN_SHELL_PATH_CACHE: dict[tuple[str, str, str], tuple[str, ...]] = {}

# Snapshot Popen (same rationale as aplexer._Popen): helper tests that patch
# ``subprocess.Popen`` to block a launched child must not swallow — or crash —
# this read-only PATH observation.
_Popen = subprocess.Popen
_TimeoutExpired = subprocess.TimeoutExpired


def _run_capture(argv: list[str], env: dict[str, str]) -> Optional[str]:
    """Run ``argv`` and return stdout, or None on any failure/timeout."""
    try:
        proc = _Popen(
            argv,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env=env,
            start_new_session=True,
        )
        try:
            stdout, _stderr = proc.communicate(timeout=LOGIN_SHELL_PATH_TIMEOUT_S)
        except _TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except OSError:
                proc.kill()
            proc.communicate()
            return None
    except (OSError, ValueError):
        return None
    if proc.returncode != 0:
        return None
    return stdout


def clear_resolution_cache() -> None:
    """Drop the memoised login-shell PATH (used by tests and long-lived procs)."""
    _LOGIN_SHELL_PATH_CACHE.clear()


def _login_shell_dirs(source: Mapping[str, str]) -> tuple[str, ...]:
    """Return the PATH entries a LOGIN shell exports, memoised per environment.

    This is the environment the harness is actually launched in (tmux runs the
    user's shell as a login shell, and the create flow `send-keys`-types the
    wrapper into that pane).  Failures are silent: the caller still has the
    absolute-candidate ladder below.
    """
    if source.get(LOGIN_SHELL_PROBE_KILL) == "0":
        return ()
    shells = tuple(
        dict.fromkeys(shell for shell in (source.get("SHELL"), "/bin/sh") if shell)
    )
    key = (shells[0], source.get("HOME", ""), source.get("PATH", ""))
    cached = _LOGIN_SHELL_PATH_CACHE.get(key)
    if cached is not None:
        return cached
    env = {str(name): str(value) for name, value in source.items()}
    dirs: tuple[str, ...] = ()
    for shell in shells:
        for command in LOGIN_SHELL_PATH_COMMANDS:
            stdout = _run_capture([shell, "-lc", command], env)
            if stdout is None:
                continue
            dirs = tuple(
                dict.fromkeys(
                    entry
                    for entry in stdout.strip().split(os.pathsep)
                    if entry.strip()
                )
            )
            if dirs:
                break
        if dirs:
            break
    _LOGIN_SHELL_PATH_CACHE[key] = dirs
    return dirs


def _launch_candidate_dirs(source: Mapping[str, str]) -> tuple[str, ...]:
    """Expand :data:`LAUNCH_PATH_PATTERNS` to existing directories."""
    home = source.get("HOME")
    found: list[str] = []
    for pattern in LAUNCH_PATH_PATTERNS:
        expanded = pattern
        if expanded.startswith("$HOME"):
            if not home:
                continue
            expanded = f"{home}{expanded[len('$HOME'):]}"
        if any(ch in expanded for ch in "*?["):
            # Newest version directory first for `.../node/*/bin`-style layouts.
            matches = sorted(glob(expanded), reverse=True)
        else:
            matches = [expanded]
        found.extend(match for match in matches if os.path.isdir(match))
    return tuple(dict.fromkeys(found))


def resolve_harnesses(
    harnesses: tuple[str, ...],
    source: Mapping[str, str],
) -> dict[str, Optional[str]]:
    """Resolve every harness the way it will be LAUNCHED, not merely exec'd.

    Returns ``{harness: absolute path or None}``.  The ladder is
    ``PATH`` -> login-shell ``PATH`` -> absolute candidate directories, and
    each rung only runs for the harnesses still unresolved, so a host whose
    engines are all on ``PATH`` spawns no subprocess at all.
    """
    ordered = tuple(dict.fromkeys(harnesses))
    resolved: dict[str, Optional[str]] = {
        name: shutil.which(name, path=source.get("PATH")) for name in ordered
    }
    missing = [name for name in ordered if resolved[name] is None]
    if not missing:
        return resolved
    for extra_dirs in (_login_shell_dirs(source), _launch_candidate_dirs(source)):
        if not extra_dirs:
            continue
        search = os.pathsep.join(extra_dirs)
        for name in tuple(missing):
            found = shutil.which(name, path=search)
            if found is not None:
                resolved[name] = found
        missing = [name for name in missing if resolved[name] is None]
        if not missing:
            break
    return resolved


def _profile(
    env_var: str,
    default_dirname: str,
    markers: tuple[str, ...],
    name_hints: tuple[str, ...],
    default_label: str,
) -> ProfileSpec:
    return ProfileSpec(
        env_var=env_var,
        default_dirname=default_dirname,
        markers=markers,
        name_hints=name_hints,
        default_label=default_label,
    )


def builtin_manifests() -> tuple[EngineManifest, ...]:
    """Return the shipped registry entries in the established picker order."""
    return (
        EngineManifest(
            id="claude",
            family="claude",
            harness="claude",
            label="Claude",
            provider_mark="Anthropic",
            usage_provider="claude",
            launch=LaunchSpec(
                argv=("claude",),
                skip_permissions_argv=("--dangerously-skip-permissions",),
                profile_env="CLAUDE_CONFIG_DIR",
                profile=_profile(
                    "CLAUDE_CONFIG_DIR",
                    ".claude",
                    (".claude.json", "settings.json"),
                    ("claude", "laude"),
                    "Claude",
                ),
            ),
        ),
        EngineManifest(
            id="codex",
            family="codex",
            harness="codex",
            label="Codex",
            provider_mark="OpenAI",
            usage_provider="codex",
            launch=LaunchSpec(
                argv=("codex", "-c", "check_for_update_on_startup=false"),
                skip_permissions_argv=(
                    "--dangerously-bypass-approvals-and-sandbox",
                ),
                profile_env="CODEX_HOME",
                profile=_profile(
                    "CODEX_HOME",
                    ".codex",
                    ("config.toml", "auth.json"),
                    ("codex", "odex"),
                    "Codex",
                ),
            ),
        ),
        EngineManifest(
            id="opencode",
            family="opencode",
            harness="opencode",
            label="OpenCode",
            provider_mark="OpenCode",
            # Issue #2293: `usage_provider` names the quse provider whose quota
            # belongs to this engine, and quse has no provider called
            # "opencode" — the OpenCode-on-Go backend is reported as `go`
            # (quse.opencode_go_quota). The old value would have made
            # `pocketshell usage opencode` fail with "Unknown provider".
            usage_provider="go",
            launch=LaunchSpec(argv=("opencode",)),
        ),
        EngineManifest(
            id="grok",
            family="grok",
            harness="grok",
            label="Grok",
            provider_mark="xAI",
            usage_provider="grok",
            launch=LaunchSpec(
                argv=("grok",),
                skip_permissions_argv=("--always-approve",),
                profile_env="GROK_HOME",
            ),
        ),
    )


def builtin_engine_ids() -> tuple[str, ...]:
    return tuple(item.id for item in builtin_manifests())


def registry_config_path(env: Optional[Mapping[str, str]] = None) -> Path:
    source = os.environ if env is None else env
    xdg = source.get("XDG_CONFIG_HOME")
    if xdg:
        base = Path(xdg)
    else:
        home = source.get("HOME") or os.path.expanduser("~")
        base = Path(home) / ".config"
    return base / "pocketshell" / "engines.yaml"


def _read_config(env: Optional[Mapping[str, str]]) -> list[Mapping[str, object]]:
    path = registry_config_path(env)
    if not path.is_file() or yaml is None:
        return []
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError):  # type: ignore[union-attr]
        return []
    if not isinstance(raw, Mapping):
        return []
    entries = raw.get("engines")
    if not isinstance(entries, list):
        return []
    return [entry for entry in entries if isinstance(entry, Mapping)]


def _string_tuple(raw: object, default: tuple[str, ...] = ()) -> tuple[str, ...]:
    if not isinstance(raw, (list, tuple)):
        return default
    return tuple(str(item) for item in raw if str(item).strip())


def _profile_from_mapping(
    raw: object,
    base: Optional[ProfileSpec],
) -> Optional[ProfileSpec]:
    if raw is None:
        return base
    if not isinstance(raw, Mapping):
        return base
    return ProfileSpec(
        env_var=str(raw.get("env_var", base.env_var if base else "")),
        default_dirname=str(
            raw.get("default_dirname", base.default_dirname if base else "")
        ),
        markers=_string_tuple(raw.get("markers"), base.markers if base else ()),
        name_hints=_string_tuple(
            raw.get("name_hints"), base.name_hints if base else ()
        ),
        default_label=str(
            raw.get("default_label", base.default_label if base else "")
        ),
    )


def _launch_from_mapping(
    raw: object,
    base: LaunchSpec,
) -> LaunchSpec:
    if not isinstance(raw, Mapping):
        return base
    argv = _string_tuple(raw.get("argv"), base.argv)
    skip = _string_tuple(
        raw.get("skip_permissions_argv"), base.skip_permissions_argv
    )
    env_raw = raw.get("env")
    env_set = base.env_set
    env_unset = base.env_unset
    if isinstance(env_raw, Mapping):
        set_raw = env_raw.get("set")
        if isinstance(set_raw, Mapping):
            env_set = tuple((str(key), str(value)) for key, value in set_raw.items())
        env_unset = _string_tuple(env_raw.get("unset"), base.env_unset)
    profile_raw = raw.get("profile", base.profile)
    return LaunchSpec(
        argv=argv,
        skip_permissions_argv=skip,
        env_unset=env_unset,
        env_set=env_set,
        profile_env=(
            str(raw["profile_env"])
            if "profile_env" in raw and raw["profile_env"] is not None
            else base.profile_env
        ),
        profile=_profile_from_mapping(profile_raw, base.profile),
    )


def _force_available(
    raw: Mapping[str, object],
    base: Optional[EngineManifest],
) -> Optional[bool]:
    """Read the explicit `force_available:` escape hatch (booleans only).

    The `available:` key is NOT accepted as an override: it is an output field
    of `pocketshell engines list --json`, so honouring it as input made a
    stale/copied value hide an installed engine forever (#2276 round 3).
    """
    value = raw.get("force_available")
    if isinstance(value, bool):
        return value
    return base.force_available if base is not None else None


def _configured_reason(
    raw: Mapping[str, object],
    base: Optional[EngineManifest],
) -> Optional[str]:
    if raw.get("unavailable_reason") is not None:
        return str(raw["unavailable_reason"])
    return base.configured_unavailable_reason if base is not None else None


def _manifest_from_mapping(
    raw: Mapping[str, object],
    base: Optional[EngineManifest],
) -> Optional[EngineManifest]:
    raw_id = raw.get("id")
    if not isinstance(raw_id, str):
        return None
    engine_id = raw_id.strip().lower()
    if not _ID_RE.fullmatch(engine_id):
        return None
    if base is None:
        launch = _launch_from_mapping(
            raw.get("launch"),
            LaunchSpec(argv=(str(raw.get("harness", engine_id)),)),
        )
        return EngineManifest(
            id=engine_id,
            family=str(raw.get("family", engine_id)),
            harness=str(raw.get("harness", engine_id)),
            label=str(raw.get("label", engine_id)),
            provider_mark=str(raw.get("provider_mark", "")),
            usage_provider=(
                str(raw["usage_provider"])
                if raw.get("usage_provider") is not None
                else None
            ),
            launch=launch,
            enabled=bool(raw.get("enabled", True)),
            available=bool(raw.get("available", True)),
            unavailable_reason=(
                str(raw["unavailable_reason"])
                if raw.get("unavailable_reason") is not None
                else None
            ),
            force_available=_force_available(raw, None),
            configured_unavailable_reason=_configured_reason(raw, None),
        )

    return replace(
        base,
        family=str(raw.get("family", base.family)),
        harness=str(raw.get("harness", base.harness)),
        label=str(raw.get("label", base.label)),
        provider_mark=str(raw.get("provider_mark", base.provider_mark)),
        usage_provider=(
            str(raw["usage_provider"])
            if raw.get("usage_provider") is not None
            else base.usage_provider
        ),
        launch=_launch_from_mapping(raw.get("launch"), base.launch),
        enabled=bool(raw.get("enabled", base.enabled)),
        available=bool(raw.get("available", base.available)),
        unavailable_reason=(
            str(raw["unavailable_reason"])
            if raw.get("unavailable_reason") is not None
            else base.unavailable_reason
        ),
        force_available=_force_available(raw, base),
        configured_unavailable_reason=_configured_reason(raw, base),
    )


# Engines aplexer lists that must not appear in the PocketShell agent picker.
_HIDDEN_APLEXER_ENGINES = frozenset({"shell"})


def _aplexer_engine_rows(
    env: Optional[Mapping[str, str]] = None,
) -> Optional[list[Mapping[str, object]]]:
    payload = aplexer.run_json(["engines"], env=env, feature="engines")
    if not isinstance(payload, list):
        return None
    return [row for row in payload if isinstance(row, Mapping)]


def _overlay_aplexer_engines(
    manifests: dict[str, EngineManifest],
    env: Optional[Mapping[str, str]] = None,
) -> None:
    """Overlay argv / env_unset from ``a engines --json``.

    Presentation fields (label, family, provider_mark, skip_permissions_argv)
    stay PocketShell's. Unknown aplexer engines (``shell``, ``gemini`` unless
    already in this registry) are not added. aplexer's own ``available`` bit is
    deliberately NOT read: availability is this host's own resolution of the
    harness (:func:`resolve_harnesses`), and inheriting a second tool's cached
    answer is exactly what hid an installed engine in #2276.
    """
    rows = _aplexer_engine_rows(env)
    if rows is None:
        return
    for row in rows:
        name = row.get("name")
        if not isinstance(name, str) or name in _HIDDEN_APLEXER_ENGINES:
            continue
        item = manifests.get(name)
        if item is None:
            continue
        command = row.get("command")
        argv = (
            tuple(str(part) for part in command if str(part).strip())
            if isinstance(command, list) and command
            else item.launch.argv
        )
        unset = row.get("env_unset")
        env_unset = (
            tuple(str(part) for part in unset if str(part).strip())
            if isinstance(unset, list)
            else item.launch.env_unset
        )
        launch = LaunchSpec(
            argv=argv,
            skip_permissions_argv=item.launch.skip_permissions_argv,
            env_unset=env_unset,
            env_set=item.launch.env_set,
            profile_env=item.launch.profile_env,
            profile=item.launch.profile,
        )
        manifests[name] = replace(item, launch=launch)


def load_registry(
    env: Optional[Mapping[str, str]] = None,
    *,
    probe: bool = True,
) -> list[EngineManifest]:
    """Load built-ins plus declarative overrides/additions.

    Availability is a host observation, not config state: when probing is
    enabled every configured harness is resolved once through
    :func:`resolve_harnesses`, which searches the exec ``PATH``, then the
    LOGIN shell's ``PATH`` (the environment the harness is really launched
    in), then the known absolute install locations. A stale configured or
    aplexer availability bit cannot override that observation.

    The one deliberate override is ``force_available:`` in ``engines.yaml``:
    an explicit boolean there pins availability for a host layout the resolver
    cannot anticipate, in either direction. It is intentionally NOT the
    ``available`` output key.

    The CLI emits the full registry, including disabled/unavailable entries,
    so the picker can hide only entries that are not createable while existing
    sessions continue to render from their recorded identity.

    When ``a`` is present, argv / env_unset for matching ids come from
    ``a engines --json`` (Phase A2). ``engines.yaml`` still wins on
    presentation and can add engines aplexer does not know.
    """
    manifests: dict[str, EngineManifest] = {
        item.id: item for item in builtin_manifests()
    }
    order = list(manifests)
    # Aplexer supplies argv/env_unset/available for known ids; user yaml
    # still wins if it then overrides the same id.
    _overlay_aplexer_engines(manifests, env)
    for raw in _read_config(env):
        item = _manifest_from_mapping(raw, manifests.get(str(raw.get("id", "")).lower()))
        if item is None:
            continue
        if item.id not in manifests:
            order.append(item.id)
        manifests[item.id] = item

    source = os.environ if env is None else env
    items = [manifests[engine_id] for engine_id in order]
    resolved: dict[str, Optional[str]] = {}
    if probe:
        resolved = resolve_harnesses(
            tuple(item.harness for item in items),
            source,
        )
    out: list[EngineManifest] = []
    for item in items:
        if item.force_available is not None:
            available = item.force_available
        elif probe:
            available = resolved.get(item.harness) is not None
        else:
            available = item.available
        out.append(
            replace(
                item,
                available=available,
                unavailable_reason=_effective_reason(item, available),
            )
        )
    return out


def registry_payload(registry: list[EngineManifest]) -> dict[str, object]:
    return {"engines": [item.to_payload() for item in registry]}


def engine_for(
    engine_id: str,
    env: Optional[Mapping[str, str]] = None,
    *,
    probe: bool = False,
) -> EngineManifest:
    wanted = engine_id.strip().lower()
    for item in load_registry(env, probe=probe):
        if item.id == wanted:
            return item
    raise KeyError(engine_id)


def createable_registry(
    env: Optional[Mapping[str, str]] = None,
) -> list[EngineManifest]:
    return [item for item in load_registry(env) if item.available_for_create]


def json_payload(registry: list[EngineManifest]) -> str:
    return json.dumps(registry_payload(registry), indent=2, sort_keys=False)


@click.group(
    name="engines",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Inspect the host engine registry. The Android picker reads this "
        "same manifest before displaying create choices."
    ),
)
def engines_group() -> None:
    """Top-level registry inspection group."""


@engines_group.command("list")
@click.option(
    "--json",
    "as_json",
    is_flag=True,
    help="Emit the registry as JSON (the client uses this form).",
)
def engines_list(as_json: bool) -> None:
    """List configured engines and their current host availability."""
    registry = load_registry()
    if as_json:
        click.echo(json_payload(registry))
        return
    for item in registry:
        state = "enabled" if item.enabled else "disabled"
        if not item.available:
            state = f"{state}, unavailable"
        if item.force_available is not None:
            state = f"{state}, force_available={str(item.force_available).lower()}"
        if item.unavailable_reason:
            state = f"{state} ({item.unavailable_reason})"
        click.echo(f"{item.id}\t{item.label}\t{state}")
