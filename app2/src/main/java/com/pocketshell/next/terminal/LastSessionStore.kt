package com.pocketshell.next.terminal

import android.content.Context
import com.pocketshell.core.hostapi.SessionRow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The session a host was last opened on, as this device remembers it. */
data class RememberedSession(
    val sessionName: String,
    /** Absolute remote workspace path, when the route carried one. */
    val workspacePath: String?,
)

/**
 * Which session the user was last in, per host (issue #2632).
 *
 * The maintainer's report was tap count: "when I click on the session I
 * already want to open the first thing in the session, or the last thing I was
 * previously in". app2 already resumes the last HOST on cold launch
 * ([com.pocketshell.next.settings.AppSettings.defaultHostId]); it had nothing
 * one level down, so every launch still cost a workspace tap plus a session tap
 * to get back to the terminal that was open thirty seconds earlier.
 *
 * ## SharedPreferences, like app2's other small stores
 *
 * One key per host, one write per session open, never queried relationally —
 * the same call [com.pocketshell.next.workspaces.WorkspaceOrderStore] and
 * `ShowAllPortsStore` made. Room would need a schema bump for state that is
 * pure UI resume.
 *
 * ## What is deliberately NOT stored
 *
 * No timestamp, no ordering, no history. The resume is validated against the
 * host's LIVE session listing before it is honoured (see
 * [resolveResumeTarget]), so a session that died overnight simply is not in
 * the listing and the user lands on the workspace list instead. That check is
 * what makes an expiry heuristic unnecessary: the host is the authority on
 * whether the remembered session still exists, not a clock on the phone.
 */
@Singleton
class LastSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Records the session [sessionName] the user just opened on [hostId].
     *
     * TWO memories, not one. The host-scoped memory answers "where was I on
     * this machine" (the cold-launch resume). The workspace-scoped memory
     * answers "where was I in THIS project" — the question a workspace tap
     * asks, and a different answer whenever the last thing you touched was in
     * some other project.
     */
    fun record(hostId: Long, sessionName: String, workspacePath: String?) {
        if (hostId <= 0L || sessionName.isBlank()) return
        val path = workspacePath?.let(::workspaceKeyPath).orEmpty()
        val editor = preferences.edit().putString(nameKey(hostId), sessionName)
        if (path.isEmpty()) {
            editor.remove(pathKey(hostId))
        } else {
            editor.putString(pathKey(hostId), path)
            editor.putString(workspaceKey(hostId, path), sessionName)
        }
        editor.apply()
    }

    /**
     * The session [hostId]'s workspace at [workspacePath] was last opened on,
     * or null when this device has never opened one there.
     */
    fun getForWorkspace(hostId: Long, workspacePath: String): String? {
        val path = workspaceKeyPath(workspacePath).ifEmpty { return null }
        return runCatching { preferences.getString(workspaceKey(hostId, path), null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** What [hostId] was last opened on, or null when this device has no memory of it. */
    fun get(hostId: Long): RememberedSession? {
        val name = runCatching { preferences.getString(nameKey(hostId), null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val path = runCatching { preferences.getString(pathKey(hostId), null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return RememberedSession(sessionName = name, workspacePath = path)
    }

    /** Forgets [hostId]'s resume target — used when the host itself is gone. */
    fun clear(hostId: Long) {
        preferences.edit().remove(nameKey(hostId)).remove(pathKey(hostId)).apply()
    }

    private fun nameKey(hostId: Long): String = "$KEY_PREFIX$hostId:name"

    private fun pathKey(hostId: Long): String = "$KEY_PREFIX$hostId:workspace"

    private fun workspaceKey(hostId: Long, path: String): String = "$KEY_PREFIX$hostId:ws:$path"

    /**
     * One spelling per workspace identity. The host reports `/home/a/git/x`
     * and `/home/a/git/x/` for the same directory depending on which command
     * answered, and a trailing slash must not split one project's memory into
     * two entries.
     */
    private fun workspaceKeyPath(path: String): String = path.trim().trimEnd('/')

    private companion object {
        const val PREFERENCES = "last_session"
        const val KEY_PREFIX = "host-"
    }
}

/**
 * The live session a resume should open, or null to stay on the workspace list.
 *
 * Pure, so the rule is testable without a database, a connection or a
 * `SharedPreferences`: a resume is honoured ONLY when the host still lists a
 * session with exactly the remembered name. Matching on the name is matching on
 * the identity the host CLI resolves against (plan §B.0) — not on a display
 * label, which two sessions can share.
 *
 * The returned row is the HOST's row, not the remembered one, so the
 * navigation carries the workspace path the host reports today rather than
 * whatever it was when the session was last opened.
 */
fun resolveResumeTarget(
    remembered: RememberedSession?,
    sessions: List<SessionRow>,
): SessionRow? {
    val name = remembered?.sessionName ?: return null
    return sessions.firstOrNull { it.name == name }
}

/**
 * The session a WORKSPACE tap should open, or null when the workspace has none
 * yet (issue #2632, maintainer follow-up 2026-09-10).
 *
 * Deliberately stricter than [resolveResumeTarget] in one direction and much
 * looser in the other, because the two answer different questions.
 * [resolveResumeTarget] answers "should this launch skip ahead?", where doing
 * nothing is a perfectly good outcome — so an unrecognised memory means stay
 * put. This answers "the user tapped a project; show it to me", where doing
 * nothing means the intermediate session-picker screen the maintainer asked to
 * delete ("I don't want to have another screen"). So the only outcome that
 * falls back is a workspace with literally nothing running in it.
 *
 * The ladder:
 *  1. [rememberedName], when the host still lists it — the session you were
 *     last in, in THIS project.
 *  2. Otherwise the most recently ACTIVE session, which is the best available
 *     guess at "what you were doing here" for a workspace this device has no
 *     memory of (a fresh install, a workspace opened from another device, or a
 *     remembered session that has since ended).
 *  3. Ties, and sessions the host reports no activity for, fall back to the
 *     host's own listing order, so the choice is stable across refreshes
 *     rather than flipping between two equally-idle terminals.
 */
fun resolveWorkspaceEntrySession(
    rememberedName: String?,
    sessions: List<SessionRow>,
): SessionRow? {
    if (sessions.isEmpty()) return null
    rememberedName
        ?.let { name -> sessions.firstOrNull { it.name == name } }
        ?.let { return it }
    // `maxByOrNull` keeps the FIRST maximum, so an all-null-activity listing
    // resolves to the host's first row rather than its last.
    return sessions.maxByOrNull { it.activityEpoch ?: Long.MIN_VALUE }
}
