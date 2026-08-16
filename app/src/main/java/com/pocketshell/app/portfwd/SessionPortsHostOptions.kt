package com.pocketshell.app.portfwd

import com.pocketshell.core.tmux.TmuxTarget

/**
 * Issue #2176: the two tmux commands that read and write the durable
 * per-session ports list, `@ps_session_ports`.
 *
 * Both are pure string builders so the shapes that matter — exact targeting,
 * locale-proof reads, fail-safe `|| true` — are pinned by cheap JVM tests
 * instead of only by a Docker journey.
 *
 * ## The read MUST force UTF-8 mode (`tmux -u`)
 *
 * tmux decides at client start-up whether it is in UTF-8 mode, from the
 * invoking process's `LC_ALL` / `LC_CTYPE` / `LANG`. When it is not, everything
 * it prints goes through `utf8_sanitize()`, which replaces each non-printable
 * byte and each multi-byte UTF-8 sequence with `_`. An SSH **exec** channel gets
 * whatever environment the host's sshd hands it, and a minimal host (a
 * container, Alpine/BusyBox, a hardened sshd with no `AcceptEnv`/PAM locale)
 * hands it none — the repository's own Docker `agents` fixture is exactly such a
 * host. That is issue #2160, measured there, and it is read-side only: the
 * stored value is untouched, which is why the corruption is invisible from a
 * `docker exec` shell that does have a locale.
 *
 * `-u` is a **client** flag, so it must precede the sub-command (`show-options
 * -u` is an unknown flag). It is a no-op on a host that already has a UTF-8
 * locale, and it moves no on-wire format, so it carries no host-CLI/client
 * lockstep risk.
 *
 * `@ps_session_ports` is additionally stored as pure printable ASCII (see
 * [SessionPortMentionCodec]), so the sanitiser would have nothing to destroy
 * even without `-u`. Both layers are deliberate: the encoding protects the data
 * we control, `-u` protects the read path from a future field that forgets.
 *
 * **Duplication note:** issue #2160 introduces `TmuxRead.CLIENT` in
 * `:shared:core-tmux` as the single home for this prefix. That change is in
 * review and is not on this branch's base, so [READ_CLIENT] is the same literal
 * here. When #2160 lands, delete [READ_CLIENT] and interpolate `TmuxRead.CLIENT`
 * — the string is byte-identical, so it is a pure rename.
 *
 * ## Targeting
 *
 * `show-options` and `set-option` both resolve `-t` as a target-**pane**, so
 * they take [TmuxTarget.pane]'s `=<name>:` form. A bare `-t <name>` prefix
 * matches, which with a `<base>` / `<base>-2` sibling pair (the routine outcome
 * of #1820's unique-name resolution) reads — or worse, WRITES — the neighbour's
 * ports list. That would be an attribution bug in the feature whose entire
 * purpose is attribution.
 */
internal object SessionPortsHostOptions {

    /** The per-session tmux user option holding the encoded mention list. */
    const val OPTION: String = "@ps_session_ports"

    /**
     * Locale-proof tmux client prefix for any command whose stdout is parsed.
     * Mirrors `TmuxRead.CLIENT` from issue #2160 (see the class KDoc).
     */
    const val READ_CLIENT: String = "tmux -u"

    /**
     * Read the option for [sessionName].
     *
     * `2>/dev/null || true` keeps a missing option, a dead session, or an old
     * tmux from turning a best-effort read into an error the caller has to
     * handle: the panel's honest answer for "we could not read it" is the same
     * as for "nothing recorded yet" — an empty list.
     */
    fun readCommand(sessionName: String): String =
        "$READ_CLIENT show-options -v -t ${shellQuote(TmuxTarget.pane(sessionName))} " +
            "$OPTION 2>/dev/null || true"

    /**
     * Write [encoded] as the option for [sessionName].
     *
     * No `-g`: the option is SESSION-scoped, exactly like `@ps_agent_kind`, so
     * two sessions on one host can never see each other's ports. The write does
     * not need `-u` (it prints nothing PocketShell reads) and is fired
     * best-effort — see [SessionPortsStore.recordAndPersist] for why it must
     * never block the caller.
     */
    fun writeCommand(sessionName: String, encoded: String): String =
        "tmux set-option -t ${shellQuote(TmuxTarget.pane(sessionName))} " +
            "$OPTION ${shellQuote(encoded)} 2>/dev/null || true"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
