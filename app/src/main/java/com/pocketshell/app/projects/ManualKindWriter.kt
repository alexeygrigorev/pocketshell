package com.pocketshell.app.projects

import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.shellSingleQuote
import com.pocketshell.core.tmux.TmuxTarget
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.tmuxOptionValue

/**
 * Epic #821 Slice 1 — the user-driven sibling of the host-side
 * `record_agent_kind` wrapper write (`tools/pocketshell/src/pocketshell/agents.py`).
 *
 * `record_agent_kind` writes the durable per-session tmux user option
 * `@ps_agent_kind` from inside the process the `pocketshell agent` wrapper
 * launches, so a session WE start is classified at birth. But a FOREIGN
 * session — one we did not launch (a tmux session the user started by hand,
 * or one created before record-at-start shipped) — carries no recorded
 * option, so the tree surfaces it as [SessionAgentKind.Unknown].
 *
 * The maintainer's Option B decision (no cgroup guess): rather than detecting
 * what such a session is, the app shows a picker ("we don't know this session
 * — choose") and, on pick, writes the same `@ps_agent_kind` option HERE, over
 * the warm SSH/tmux session, so the chosen kind becomes the durable recorded
 * kind. The SAME write also drives the "change kind" affordance for an
 * already-classified session — rewriting the option flips the recorded kind
 * and clears any launch-recorded transcript source so a stale
 * `@ps_agent_source` cannot keep the Conversation bound to the previous
 * identity.
 *
 * Writing the SAME tmux user option that `record_agent_kind` writes means the
 * value round-trips through the unchanged read-back path
 * ([SshFolderListGateway.recordedKindFromOption] →
 * `FolderSessionRow.recordedKind`): no third cache, one source of truth, and
 * it survives reconnect / app restart / app-kill / reinstall exactly like a
 * wrapper-recorded kind (tmux session options live for the life of the
 * session).
 *
 * Mirror of the server-side argv (`agents.py`):
 * ```
 * tmux set-option -t =<session>: @ps_agent_kind <value> \; \
 *   set-option -u -q -t =<session>: @ps_agent_source_generation \; \
 *   set-option -u -q -t =<session>: @ps_agent_source
 * ```
 * The wrapper omits `-t` (it runs inside the target session); the client is
 * NOT inside the session, so it must target it explicitly with `-t`. The
 * option is session-scoped (no `-g`), matching the wrapper. Issue #1820: the
 * `=<session>:` form is tmux's EXACT pane-target match — a bare `<session>`
 * prefix-matches a `<session>-2` sibling and would write the kind onto the
 * wrong session (see [com.pocketshell.core.tmux.TmuxTarget]).
 */
internal object ManualKindWriter {

    /**
     * Build the manual recorded-kind command for [sessionName] and [kind].
     * Returns `null` when [kind] has no durable recorded value (Probing /
     * Exited / Unknown — see [SessionAgentKind.tmuxOptionValue]); the caller
     * must not write a non-classification kind. [sessionName] is single-quoted
     * so a name with shell metacharacters cannot break out of the argument.
     */
    fun buildSetOptionCommand(sessionName: String, kind: SessionAgentKind): String? {
        val value = kind.tmuxOptionValue() ?: return null
        // Issue #1820: EXACT target. `set-option` resolves `-t` through tmux's
        // pane-ish lookup, where a bare `<name>` prefix-matches — so with
        // `<name>` gone (or simply alphabetically shadowed) and `<name>-2` alive
        // the user's chosen kind is written onto the NEIGHBOUR session, and this
        // writer is the SOLE kind authority. Reproduced on tmux 3.4:
        // `set-option -t 'foo' @x LEAK` landed on `foo-2`. The trailing `:` is
        // required for a pane target — see [TmuxTarget].
        val target = shellSingleQuote(TmuxTarget.pane(sessionName))
        return "tmux set-option -t $target @ps_agent_kind $value" +
            " \\; set-option -u -q -t $target @ps_agent_source_generation" +
            " \\; set-option -u -q -t $target @ps_agent_source"
    }

    /**
     * Write the recorded [kind] for tmux session [sessionName] over the warm
     * [session]. Throws [IllegalArgumentException] for a blank session name or
     * a non-classification [kind]; surfaces a tmux failure (non-zero exit) as a
     * [RuntimeException] so the caller can report the user that the change did
     * not land — unlike the server-side wrapper, this write is the user's
     * explicit intent, so a silent swallow would mislead.
     */
    suspend fun write(
        session: SshSession,
        sessionName: String,
        kind: SessionAgentKind,
    ) {
        val target = sessionName.trim()
        require(target.isNotEmpty()) { "No session to classify." }
        val command = buildSetOptionCommand(target, kind)
            ?: throw IllegalArgumentException("$kind is not a recordable session kind.")
        val result = session.exec(command)
        if (result.exitCode != 0) {
            throw RuntimeException(
                result.stderr.ifBlank { result.stdout }.trim()
                    .ifBlank { "tmux set-option @ps_agent_kind failed for '$target'." },
            )
        }
    }

}
