"""`pocketshell agent <kind> --dir <dir>` subcommand.

Launch a coding-agent CLI (``codex`` / ``claude`` / ``opencode``) in a
folder, server-side, replacing the giant inline ``env -u VAR1 -u VAR2 …``
line the Android app used to type into the new tmux pane (issue #703).

Why this exists
---------------

The app previously reconstructed the *entire* launch chain inline:

```
eval "$(pocketshell env export --dir '<dir>')"; env -u VAR1 … (71 vars) … codex --dangerously-bypass-approvals-and-sandbox
```

That is ~1500 characters of brittle shell typed into the pane. Worse, the
agent then **parked on a first-run modal prompt** the user never knew to
dismiss:

- ``codex 0.137.0`` halts on *"Update available 0.137.0 → 0.139.0 — Press
  enter to continue"*.
- ``claude`` in a fresh folder halts on *"Is this a project you trust?
  1. Yes / 2. No"*.

So the agent *appeared* but never actually became usable. This wrapper
replaces the whole inline chain with one short line —
``pocketshell agent <kind> --dir <dir> [--skip-permissions]
[--config-dir <dir>]`` — and **suppresses those first-run prompts** so the
agent UI is immediately usable.

What it does
------------

1. ``cd <dir>`` (validated, like ``env``'s ``_resolve_dir``).
2. Merge the folder's ``.env`` / ``.envrc`` into the environment
   (reuses :func:`pocketshell.env.merged_exports`) — this replaces the
   ``eval "$(pocketshell env export …)"`` prelude.
3. Apply the env-strip **for every agent kind** (see below).
4. Suppress the agent's first-run prompt.
5. ``os.execvpe`` the agent so it replaces the wrapper process and owns the
   pty cleanly.

Env-strip scope (issue #703 — maintainer decision: ALL three agents)
--------------------------------------------------------------------

Maintainer decision (2026-06-11, issue #703): strip the provider API-key
vars for **all three** agents — ``codex``, ``claude``, and ``opencode`` —
so each falls back to its *subscription* auth instead of a per-token env
API key (which bills per token). Subscription billing across the board.

This matches the old app behaviour (which stripped for all three) but now
lives in the concise ``pocketshell agent`` wrapper instead of being
reconstructed inline by the app. The 71-var list is
:data:`PROVIDER_ENV_UNSET_VARS`.

Prompt suppression (the part that fixes "the agent doesn't start")
------------------------------------------------------------------

- **codex** — ``-c check_for_update_on_startup=false`` disables the
  startup update check, so codex never parks on the
  "Update available … Press enter to continue" modal. The project-trust
  prompt does not appear in codex 0.137.0 (verified), so no extra trust
  seeding is needed.
- **claude** — the workspace-trust dialog is gated by
  ``hasTrustDialogAccepted`` per project in ``~/.claude.json``. Even
  ``--dangerously-skip-permissions`` does NOT skip it (issue #703). The
  wrapper pre-seeds ``projects.<dir>.hasTrustDialogAccepted = true`` before
  exec, so claude starts straight into the usable agent prompt.
- **opencode** — config-driven; no first-run modal to suppress.
"""

from __future__ import annotations

import json
import os
import shutil
from pathlib import Path
from typing import Optional

import click

from pocketshell.env import merged_exports


# ---------------------------------------------------------------------------
# Provider API-key env vars stripped for EVERY agent (subscription billing).
# ---------------------------------------------------------------------------
#
# CANONICAL SOURCE: the maintainer's dotfiles at
# ``config/opencode/env_unset.txt`` (installed as
# ``~/git/.claude/config/opencode/env_unset.txt``). This list is a verbatim
# copy of that file (71 entries). With these unset, an agent falls back to
# the maintainer's *subscription* auth instead of a per-token env API key
# (which bills per token). Keeping the list here makes the wrapper
# self-contained — it does not require the ``oc`` function or
# ``env_unset.txt`` to be present on the host.
#
# Maintainer decision (issue #703): strip these for ALL three agents
# (codex / claude / opencode), not opencode-only — subscription billing
# across the board.
#
# The Android picker (SessionTypePickerSheet.kt) used to carry an identical
# copy; with the wrapper owning the launch, the app no longer needs it.
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

# Recognised agent kinds. Order is the picker's order (claude, codex,
# opencode) but the wrapper is keyed by name, not ordinal.
AGENT_KINDS: tuple[str, ...] = ("codex", "claude", "opencode")


# ---------------------------------------------------------------------------
# Pure helpers (unit-tested without exec)
# ---------------------------------------------------------------------------


def build_env(
    kind: str,
    base_env: dict[str, str],
    folder_exports: dict[str, str],
    *,
    config_dir: Optional[str] = None,
    extra_env: Optional[dict[str, str]] = None,
) -> dict[str, str]:
    """Return the environment to exec the agent with.

    Starts from ``base_env`` (normally ``os.environ``), layers the
    folder's merged ``.env`` / ``.envrc`` exports on top, then the profile's
    ``extra_env`` (from ``profiles.yaml``'s ``env:`` block, issue #718/#732),
    then:

    - For **every agent kind** (codex / claude / opencode), removes every
      var in :data:`PROVIDER_ENV_UNSET_VARS` so the agent uses its
      subscription auth instead of a per-token env API key (maintainer
      decision, issue #703 — subscription billing across the board). This
      strip runs **last** among the env layers, so even a provider key that
      a profile's ``extra_env`` tries to inject is still stripped — the
      #703 subscription-billing guarantee always wins over profile env.
    - When ``config_dir`` is given, sets the agent's config-dir env var
      (``CODEX_HOME`` for codex, ``CLAUDE_CONFIG_DIR`` for claude). Ignored
      for opencode (no profile env var).
    """
    env = dict(base_env)
    env.update(folder_exports)

    # A profile's `env:` block (profiles.yaml) layers on top of the folder
    # exports (issue #732). It is applied BEFORE the provider strip below so
    # the strip still wins for provider keys — a profile can set arbitrary
    # non-provider vars, but cannot re-inject a stripped API key.
    if extra_env:
        env.update(extra_env)

    # Strip the provider API-key vars for EVERY agent kind so each falls
    # back to its subscription auth (maintainer decision, issue #703 —
    # subscription billing across the board for codex / claude / opencode).
    # Runs last so it overrides any provider key from base/folder/profile env.
    for name in PROVIDER_ENV_UNSET_VARS:
        env.pop(name, None)

    if config_dir:
        if kind == "codex":
            env["CODEX_HOME"] = config_dir
        elif kind == "claude":
            env["CLAUDE_CONFIG_DIR"] = config_dir

    return env


def build_argv(kind: str, *, skip_permissions: bool) -> list[str]:
    """Return the argv (program + args) used to exec the agent.

    The argv carries the per-agent first-run-prompt suppression and the
    skip-permissions flag:

    - **codex** — ``-c check_for_update_on_startup=false`` suppresses the
      startup update-check modal (issue #703).
      ``--dangerously-bypass-approvals-and-sandbox`` when
      ``skip_permissions`` (the maintainer's ``cy`` alias).
    - **claude** — ``--dangerously-skip-permissions`` when
      ``skip_permissions`` (the ``csp`` alias). The trust dialog is
      suppressed out-of-band by pre-seeding ``~/.claude.json`` (see
      :func:`seed_claude_trust`), not via argv.
    - **opencode** — no skip flag (permissions are config-driven in
      ``opencode.json``); the billing fix is the env strip, not a flag.
    """
    if kind == "codex":
        argv = ["codex", "-c", "check_for_update_on_startup=false"]
        if skip_permissions:
            argv.append("--dangerously-bypass-approvals-and-sandbox")
        return argv
    if kind == "claude":
        argv = ["claude"]
        if skip_permissions:
            argv.append("--dangerously-skip-permissions")
        return argv
    if kind == "opencode":
        return ["opencode"]
    raise ValueError(f"unknown agent kind: {kind!r}")


def claude_config_path(env: dict[str, str]) -> Path:
    """Return the ``~/.claude.json`` path claude reads its trust state from.

    Honours ``CLAUDE_CONFIG_DIR`` (set when a non-default profile is
    selected) — claude stores ``.claude.json`` inside that dir; otherwise
    it lives at ``$HOME/.claude.json``.
    """
    config_dir = env.get("CLAUDE_CONFIG_DIR")
    if config_dir:
        return Path(config_dir).expanduser() / ".claude.json"
    home = env.get("HOME") or os.path.expanduser("~")
    return Path(home) / ".claude.json"


def seed_claude_trust(config_path: Path, directory: str) -> None:
    """Pre-accept claude's workspace-trust dialog for ``directory``.

    claude gates the *"Is this a project you trust?"* modal on
    ``projects.<dir>.hasTrustDialogAccepted`` in ``~/.claude.json``. Even
    ``--dangerously-skip-permissions`` does NOT skip it (issue #703), so
    the wrapper seeds the flag before exec.

    Best-effort and non-destructive: it reads the existing config (an
    object), sets only the one nested flag, and writes it back. Any I/O or
    parse error is swallowed — a missing/corrupt config simply means
    claude shows its own trust prompt, the pre-existing behaviour, so the
    wrapper never makes the launch *worse* by failing here.
    """
    try:
        if config_path.exists():
            data = json.loads(config_path.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                return
        else:
            data = {}
        projects = data.setdefault("projects", {})
        if not isinstance(projects, dict):
            return
        entry = projects.setdefault(directory, {})
        if not isinstance(entry, dict):
            return
        if entry.get("hasTrustDialogAccepted") is True:
            return  # already trusted; nothing to write
        entry["hasTrustDialogAccepted"] = True
        config_path.parent.mkdir(parents=True, exist_ok=True)
        config_path.write_text(
            json.dumps(data, ensure_ascii=False), encoding="utf-8"
        )
    except (OSError, ValueError):
        # Trust seeding is best-effort; a failure here only means claude
        # shows its own prompt (the old behaviour), never a broken launch.
        return


# ---------------------------------------------------------------------------
# Click surface
# ---------------------------------------------------------------------------


def _agent_missing_message(kind: str) -> str:
    """Friendly install hint shown when the agent CLI is not on PATH.

    Mirrors the missing-binary wording used by ``pocketshell.sessions`` /
    ``pocketshell.usage`` / ``pocketshell.jobs`` so the user sees a
    consistent ``127`` + install-hint message whichever subcommand
    surfaces the failure first, instead of a raw ``FileNotFoundError``
    traceback from ``os.execvpe``.
    """
    return (
        f"pocketshell: `{kind}` is not installed on this host (not on PATH). "
        f"Install the {kind} CLI and re-run."
    )


def _resolve_dir(ctx: click.Context, directory: str) -> Path:
    """Expand ``directory`` and require it to be an existing folder."""
    path = Path(os.path.expanduser(directory))
    if not path.is_dir():
        click.echo(
            f"pocketshell agent: directory does not exist: {path}", err=True
        )
        ctx.exit(2)
    return path


def launch_agent(
    ctx: click.Context,
    kind: str,
    directory: str,
    *,
    skip_permissions: bool,
    config_dir: Optional[str],
    extra_env: Optional[dict[str, str]] = None,
    execvpe=None,
) -> None:
    """Resolve the dir, build env+argv, suppress prompts, exec the agent.

    ``extra_env`` carries the selected profile's ``env:`` block (issue
    #732); it layers onto the launch environment under the #703 provider
    strip (see :func:`build_env`).

    ``execvpe`` is injected so tests can assert the exact call without
    actually replacing the process. When ``None`` (production) it resolves
    to :func:`os.execvpe` *at call time* — looking it up on the module's
    ``os`` so a monkeypatch on ``agents.os.execvpe`` is honoured (a default
    argument would bind the original at def-time and bypass the patch).
    :func:`os.execvpe` never returns on success.
    """
    if execvpe is None:
        execvpe = os.execvpe

    path = _resolve_dir(ctx, directory)
    resolved_dir = str(path)

    folder_exports = merged_exports(path)
    env = build_env(
        kind,
        dict(os.environ),
        folder_exports,
        config_dir=config_dir,
        extra_env=extra_env,
    )
    argv = build_argv(kind, skip_permissions=skip_permissions)

    # Preflight: confirm the agent CLI is on PATH *before* os.chdir + exec.
    # Without this, a missing `claude`/`codex`/`opencode` makes os.execvpe
    # raise FileNotFoundError and dump a raw Python traceback to the SSH
    # client. Emit the same friendly 127 + install hint every other
    # subcommand uses instead (#774 §3).
    if shutil.which(argv[0]) is None:
        click.echo(_agent_missing_message(kind), err=True)
        ctx.exit(127)
        return

    # Run from the folder so the agent's cwd is correct.
    os.chdir(resolved_dir)

    if kind == "claude":
        seed_claude_trust(claude_config_path(env), resolved_dir)

    # Replace this process with the agent so it owns the pty cleanly.
    execvpe(argv[0], argv, env)


def _resolve_config_dir(
    ctx: click.Context,
    kind: str,
    config_dir: Optional[str],
    profile: Optional[str],
) -> tuple[Optional[str], dict[str, str]]:
    """Resolve config dir + extra env from ``--config-dir`` / ``--profile``.

    Returns ``(config_dir, extra_env)``. ``--config-dir`` and ``--profile``
    are mutually exclusive (passing both is an error). When ``--profile`` is
    given, it resolves the named host profile (via
    :func:`pocketshell.profiles.resolve_profile`) to its ``config_dir`` AND
    its ``env:`` block (issue #732) — an unknown profile is a clear error. A
    default profile resolves to ``None`` config dir (the engine's built-in
    location); ``--config-dir`` carries no profile env. Omitting both flags
    returns ``(None, {})``.
    """
    if config_dir is not None and profile is not None:
        click.echo(
            "pocketshell agent: --config-dir and --profile are mutually "
            "exclusive",
            err=True,
        )
        ctx.exit(2)
    if profile is None:
        return config_dir, {}

    # Lazy import keeps the agent launch path from importing yaml unless a
    # profile is actually requested.
    from pocketshell.profiles import resolve_profile

    try:
        resolved = resolve_profile(profile, kind)
    except KeyError:
        click.echo(
            f"pocketshell agent: unknown {kind} profile: {profile!r} "
            f"(see `pocketshell profiles list --engine {kind}`)",
            err=True,
        )
        ctx.exit(2)
    return resolved.config_dir, dict(resolved.env)


def _make_agent_command(kind: str):
    """Build the Click command for one agent kind."""

    @click.command(
        name=kind,
        context_settings={"help_option_names": ["-h", "--help"]},
        help=f"Launch `{kind}` in --dir with first-run prompts suppressed.",
    )
    @click.option(
        "--dir",
        "directory",
        required=True,
        type=str,
        help="Folder to launch the agent in (its cwd).",
    )
    @click.option(
        "--skip-permissions/--no-skip-permissions",
        default=True,
        show_default=True,
        help=(
            "Launch with per-action approval prompts disabled "
            "(codex YOLO / claude bypass). No-op for opencode."
        ),
    )
    @click.option(
        "--config-dir",
        "config_dir",
        default=None,
        type=str,
        help=(
            "Profile config dir: CODEX_HOME (codex) / CLAUDE_CONFIG_DIR "
            "(claude). Ignored for opencode. Mutually exclusive with "
            "--profile."
        ),
    )
    @click.option(
        "--profile",
        "profile",
        default=None,
        type=str,
        help=(
            "Named host profile (see `pocketshell profiles list`); resolves "
            "to its config dir. Mutually exclusive with --config-dir."
        ),
    )
    @click.pass_context
    def _cmd(
        ctx: click.Context,
        directory: str,
        skip_permissions: bool,
        config_dir: Optional[str],
        profile: Optional[str],
    ) -> None:
        config_dir, extra_env = _resolve_config_dir(
            ctx, kind, config_dir, profile
        )
        launch_agent(
            ctx,
            kind,
            directory,
            skip_permissions=skip_permissions,
            config_dir=config_dir,
            extra_env=extra_env,
        )

    return _cmd


@click.group(
    name="agent",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Launch a coding-agent CLI in a folder, server-side.\n\n"
        "Replaces the giant inline `env -u … <agent>` line the app used to "
        "type into the pane. Merges the folder's `.env`/`.envrc`, strips "
        "provider API-key vars for every agent (subscription billing), and "
        "suppresses each agent's first-run modal (codex update check / "
        "claude folder-trust) so the agent is immediately usable. "
        "See issue #703."
    ),
)
def agent_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


for _kind in AGENT_KINDS:
    agent_group.add_command(_make_agent_command(_kind))
