package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentDetection
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #495: process-scoped memory of which tmux windows are agent
 * sessions, so a reconnect/reattach can restore the Conversation tab
 * immediately instead of bouncing the user to Terminal until live
 * re-detection finishes a round-trip.
 *
 * ## Why a separate memory from [TmuxSessionRuntimeCache]
 *
 * The warm runtime cache already carries `agentConversations` across a
 * **same-host fast switch** (the socket stays alive — see
 * [TmuxSessionViewModel.restoreCachedRuntime]). It does NOT help the case
 * this issue is about: a genuine reconnect where the SSH socket died and
 * the #444 auto-reconnect path tears the runtime down and re-attaches from
 * scratch. On that path `_agentConversations` is empty and only repopulates
 * after `startAgentDetectionForPane` finishes its SSH round-trip, so the
 * user who was IN Conversation reads as a plain shell and is dropped to
 * Terminal in the meantime.
 *
 * This memory survives that teardown because it is keyed by a **stable**
 * identity rather than by the rotating tmux pane id:
 *
 *  - `hostId` + `sessionName` + `windowId`.
 *
 * `windowId` (tmux `@N`) is assigned by the tmux server and is stable for
 * the lifetime of that server across detach/reattach — exactly the property
 * the brief relies on ("the remote tmux session identity is stable"). The
 * pane id (`%N`) can rotate on reattach, which is why the per-pane
 * `_agentConversations` map cannot carry the status by itself.
 *
 * ## Reconcile, don't blindly trust
 *
 * A remembered entry is only an optimistic seed. Live detection still runs
 * on reconnect; when it confirms a *different* agent the seed is overwritten,
 * and when it reports **no agent** for the window the caller calls [forget]
 * so a genuinely-exited agent does not leave a phantom Conversation tab
 * lingering forever.
 *
 * Foreground-only and in-memory: no persistence, no background work. The
 * process holds it for the lifetime of the app, which is all that is needed
 * — the remote tmux session keeps the real state and we only need to bridge
 * the brief reconnect gap.
 */
@Singleton
public class AgentSessionMemory @Inject constructor() {
    private val statuses: MutableMap<Key, RememberedAgentStatus> = ConcurrentHashMap()

    /**
     * Record (or refresh) the remembered agent status for a window.
     *
     * @param wasOnConversation whether the user currently has the
     * Conversation tab selected for this window. Restored on reconnect so a
     * user who was reading/dictating in Conversation stays there.
     */
    internal fun remember(
        hostId: Long,
        sessionName: String,
        windowId: String,
        detection: AgentDetection,
        wasOnConversation: Boolean,
    ) {
        val key = keyOf(hostId, sessionName, windowId) ?: return
        statuses[key] = RememberedAgentStatus(
            detection = detection,
            wasOnConversation = wasOnConversation,
        )
    }

    /**
     * Returns the remembered status for the window, or null when nothing is
     * remembered (never been an agent window, or it was [forget]-ten after
     * the agent exited).
     */
    internal fun recall(
        hostId: Long,
        sessionName: String,
        windowId: String,
    ): RememberedAgentStatus? {
        val key = keyOf(hostId, sessionName, windowId) ?: return null
        return statuses[key]
    }

    /**
     * Drop the remembered status for a window. Called when live detection
     * reports that the window no longer hosts an agent (the agent exited),
     * so a later reconnect does not resurrect a phantom Conversation tab.
     */
    internal fun forget(
        hostId: Long,
        sessionName: String,
        windowId: String,
    ) {
        val key = keyOf(hostId, sessionName, windowId) ?: return
        statuses.remove(key)
    }

    /**
     * Drop every remembered window for a tmux session after a confirmed kill.
     * A newly-created session can reuse the same name and even the same tmux
     * window ids, so per-window forget is not enough at session teardown.
     */
    internal fun forgetSession(hostId: Long, sessionName: String) {
        val trimmed = sessionName.trim()
        if (trimmed.isEmpty()) return
        statuses.keys.removeIf { key ->
            key.hostId == hostId && key.sessionName == trimmed
        }
    }

    private fun keyOf(hostId: Long, sessionName: String, windowId: String): Key? {
        if (sessionName.isBlank() || windowId.isBlank()) return null
        return Key(hostId, sessionName, windowId)
    }

    private data class Key(
        val hostId: Long,
        val sessionName: String,
        val windowId: String,
    )
}

/**
 * Issue #495: a remembered "this window is an agent window" verdict plus the
 * user's last tab choice for it.
 */
internal data class RememberedAgentStatus(
    val detection: AgentDetection,
    val wasOnConversation: Boolean,
)
