package com.pocketshell.core.tmux

/**
 * Issue #2160: build the tmux invocation used to **read** anything whose bytes
 * PocketShell parses — a user option, a `-F` format listing, a `display-message
 * -p` expansion.
 *
 * ## Why a bare `tmux` is unsafe for reads
 *
 * tmux decides at client start-up whether it is in UTF-8 mode, from the
 * invoking process's `LC_ALL` / `LC_CTYPE` / `LANG`. When it is **not**, every
 * string it prints goes through `utf8_sanitize()`, which replaces each
 * non-printable byte AND each multi-byte UTF-8 sequence with a single `_`.
 * The stored value is untouched — this is purely read-side — which is why the
 * listing form (`show-options` without `-v`) looks healthy while the value
 * form comes back corrupted.
 *
 * That is not a hypothetical: an SSH **exec** channel gets whatever environment
 * the host's sshd hands it, and a minimal host (a container, BusyBox/Alpine, a
 * hardened sshd with no `AcceptEnv`/PAM locale) hands it none. Measured on the
 * repository's own `agents` Docker fixture (tmux 3.6b, no locale in the exec
 * environment) with `@ps_agent_source` set to `GEN123<TAB>/home/testuser/x.jsonl`:
 *
 * ```
 * $ tmux    show-options -v -t '=s:' @ps_agent_source | od -c
 *   G E N 1 2 3   _   / h o m e / ...        # TAB -> 0x5f, delimiter destroyed
 * $ tmux -u show-options -v -t '=s:' @ps_agent_source | od -c
 *   G E N 1 2 3  \t   / h o m e / ...        # intact
 * ```
 *
 * The same corruption was reproduced on tmux 3.4 for `list-sessions -F` and
 * `display-message -p`, so it is a property of the **client's locale**, not of
 * the tmux version or of one verb. Non-ASCII is destroyed the same way
 * (`ПРИВЕТ` -> `______`), so a Cyrillic session name or project path is just as
 * exposed as a TAB delimiter.
 *
 * ## The fix
 *
 * `-u` is a client flag (it must precede the sub-command; `show-options -u` is
 * "unknown flag") that forces the client into UTF-8 mode regardless of the
 * environment, which bypasses the sanitiser. It has existed since tmux 1.x, is
 * a no-op on a host that already has a UTF-8 locale (verified on the
 * maintainer's box, tmux 3.4, `LANG=en_US.UTF-8`), changes nothing about what
 * is stored, and carries no host-CLI/client lockstep risk — no on-wire format
 * moves.
 *
 * Use [TmuxRead.CLIENT] for **every** command whose stdout is parsed. Writes
 * (`set-option`, `send-keys`, `kill-session`) do not need it: they print
 * nothing PocketShell reads.
 *
 * ## What is deliberately NOT on this client yet
 *
 * Stated here rather than left to be rediscovered, and each one is an explicit
 * allowlist entry in `Issue2160LocaleProofReadSiteSourceGuardTest` (the
 * source-level ratchet over every read site) rather than a silent gap:
 *
 *  - **#2177** — `TmuxSessionViewModel`'s `@ps_agent_kind` / `@ps_agent_profile`
 *    reads (blocked on the lane that owns that file).
 *  - **#2175** — the `-CC` control-mode leg; the flag would have to move to the
 *    `tmux -CC` attach line itself, which is connection-core surface.
 *  - **#2174** — the `capture-pane` / `display-message -p` heal lanes, i.e.
 *    terminal CONTENT capture rather than user-option reads.
 */
public object TmuxRead {

    /**
     * The locale-proof tmux command prefix for a read: `tmux -u`.
     *
     * Interpolate it in place of the literal `"tmux"`, e.g.
     * `"${TmuxRead.CLIENT} show-options -v -t $target @ps_agent_source"`.
     */
    public const val CLIENT: String = "tmux -u"

    /**
     * True when [command] — a single shell word sequence starting with `tmux` —
     * is the locale-proof read form. Used by the guard tests that keep every
     * production read site on [CLIENT] (issue #2160 acceptance criterion 2).
     */
    public fun isLocaleProofInvocation(command: String): Boolean =
        Regex("""\btmux\s+-u\b""").containsMatchIn(command)
}
