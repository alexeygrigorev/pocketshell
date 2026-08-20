package com.pocketshell.app.tmux

import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.ssh.SshSession

/**
 * Reads the relaunch generation only while a Conversation is bound.
 *
 * The caller invokes this from the existing liveness cadence; keeping the
 * eligibility gate here prevents plain shell sessions from gaining an SSH
 * probe while still letting an unchanged pane observe an in-session relaunch.
 */
internal suspend fun readConversationSourceGeneration(
    repository: AgentConversationRepository,
    session: SshSession?,
    sessionTarget: String?,
    hasBoundConversation: Boolean,
): String? {
    if (!hasBoundConversation) return null
    return repository.readRecordedSourceGeneration(
        session ?: return null,
        sessionTarget ?: return null,
    )
}
