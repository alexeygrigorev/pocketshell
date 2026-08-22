package com.pocketshell.app.projects

import com.pocketshell.app.tmux.TmuxSessionGeneration
import com.pocketshell.app.tmux.tmuxSessionGenerationOrNull
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxRead
import com.pocketshell.core.tmux.TmuxTarget

/**
 * Generation-fenced remote rename. The name is checked first, but the actual
 * tmux mutation targets the stable session id so a predecessor disappearing
 * during the check cannot redirect the operation to a same-name successor.
 */
internal suspend fun renameSessionWithGeneration(
    host: HostEntity,
    keyPath: String,
    passphrase: CharArray?,
    oldName: String,
    newName: String,
    expectedGeneration: TmuxSessionGeneration,
    withLeaseSession: suspend (
        HostEntity,
        String,
        CharArray?,
        suspend (SshSession) -> Unit,
    ) -> Result<Unit>,
    pathAware: (String) -> String,
    shellQuote: (String) -> String,
): Result<Unit> {
    val oldTarget = oldName.trim()
    val newTarget = newName.trim()
    if (oldTarget.isEmpty() || newTarget.isEmpty()) {
        return Result.failure(IllegalArgumentException("Enter a session name."))
    }
    if (oldTarget == newTarget) return Result.success(Unit)
    val exactGeneration = tmuxSessionGenerationOrNull(
        expectedGeneration.sessionId,
        expectedGeneration.createdEpochSeconds,
    ) ?: return Result.failure(IllegalArgumentException("Invalid session generation."))

    return withLeaseSession(host, keyPath, passphrase) { session ->
        val quotedOld = shellQuote(TmuxTarget.session(oldTarget))
        // display-message resolves a pane/window target, not a target-session;
        // the bare `=name` form silently returns an empty format on tmux. Keep
        // the session target for has-session, but read the generation through
        // the exact current-pane form so the check is both exact and real.
        val quotedOldPane = shellQuote(TmuxTarget.pane(oldTarget))
        val observedBeforeRename = session.exec(
            pathAware(
                "${TmuxRead.CLIENT} display-message -p -t $quotedOldPane " +
                    "'#{session_id} #{session_created}'",
            ),
        )
        if (parseExactGeneration(observedBeforeRename) != exactGeneration) {
            throw RuntimeException(
                "tmux session '$oldTarget' changed before rename; refusing successor.",
            )
        }

        // A raw `$N` target addresses the captured tmux session id. If the
        // predecessor disappears now, tmux cannot resolve it to its successor.
        val rename = session.exec(
            pathAware(
                "tmux rename-session -t ${shellQuote(exactGeneration.sessionId)} " +
                    shellQuote(newTarget),
            ),
        )
        if (rename.exitCode != 0) {
            throw RuntimeException(rename.stderr.ifBlank { rename.stdout }.trim())
        }
        val oldExists = session.exec(pathAware("tmux has-session -t $quotedOld"))
        val observedAfterRename = session.exec(
            pathAware(
                "${TmuxRead.CLIENT} display-message -p -t " +
                    shellQuote(TmuxTarget.pane(newTarget)) +
                    " '#{session_id} #{session_created}'",
            ),
        )
        if (oldExists.exitCode == 0 || parseExactGeneration(observedAfterRename) != exactGeneration) {
            throw RuntimeException("tmux session '$oldTarget' was not renamed to '$newTarget'.")
        }
    }
}

private fun parseExactGeneration(result: ExecResult): TmuxSessionGeneration? {
    if (result.exitCode != 0) return null
    val fields = result.stdout.trim().split(Regex("\\s+"))
    if (fields.size != 2) return null
    return tmuxSessionGenerationOrNull(fields[0], fields[1].toLongOrNull())
}
