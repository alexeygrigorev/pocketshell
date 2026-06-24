package com.pocketshell.app.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pocketshell.app.nav.AppDestination
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #177: persists the user's last in-session view so returning to
 * the app after an app-switch (or a process death) restores the previous
 * `tmux -CC` session optimistically instead of dumping the user back on
 * the host list and making them re-tap their way in.
 *
 * ## Why SharedPreferences (not Room / DataStore)
 *
 * This is the same trade-off [com.pocketshell.app.settings.SettingsRepository]
 * already made: the payload is tiny (one host tuple + a session name +
 * a composer draft + a timestamp), write traffic is one-edit-per-app-stop,
 * and SharedPreferences is already on the classpath transitively. A Room
 * entity would force a schema bump and migration for state that does not need
 * relational queries. DataStore would add a version-catalog entry without
 * buying any feature we need. Future issues are free to migrate.
 *
 * ## Hard-cut (D22)
 *
 * There is no legacy shape to honour — this is a brand new store. A blob
 * written by a previous build that does not parse cleanly is simply
 * discarded ([read] returns null). We do not carry an `if (old shape)`
 * compatibility branch for this auxiliary preference blob.
 *
 * ## Foreground-only (D21)
 *
 * Nothing here runs while backgrounded. [save] is invoked from the
 * activity's `onStop`; [read] from `onCreate` / resume routing. The store
 * is pure on-disk state — it never holds a connection or schedules work.
 *
 * Singleton scope so the activity and any future consumer share one
 * instance over the same prefs file.
 */
@Singleton
class LastSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Issue #834: identity of the most recently killed session, remembered in
     * memory for this process. A session the user just deleted (tree/host-detail
     * Stop or in-session Stop, both confirmed via
     * [com.pocketshell.app.tmux.SessionLifecycleSignals.emitKilled]) must NEVER
     * be persisted as the "last active" view again — otherwise the next
     * foreground/process-death restore re-opens the dead session, which #818
     * lands on its Conversation tab (showing a deleted session is the #686
     * hazard).
     *
     * Clearing the on-disk record alone is not enough: the user may still be
     * sitting on the now-dead session screen when they background the app, and
     * `MainActivity.onStop` would re-`save()` that exact dead session, re-arming
     * the restore. So [onSessionKilled] both clears any matching persisted
     * record AND records this tombstone, and [save] refuses to persist a session
     * whose identity matches it.
     */
    @Volatile
    private var killedTombstone: SessionIdentity? = null

    /** Minimal (hostId, sessionName) identity used for kill matching (#834). */
    private data class SessionIdentity(val hostId: Long, val sessionName: String)

    /**
     * Persisted snapshot of the last active `tmux -CC` session view.
     *
     * Only the fields needed to rebuild an
     * [AppDestination.TmuxSession] plus the composer draft and the wall
     * clock the snapshot was taken at (so [read] can age it out). The
     * key passphrase is intentionally NOT stored — the reattach path
     * resolves the key from disk by path, same as a cold attach, so we
     * never write a secret into prefs.
     */
    data class LastSession(
        val hostId: Long,
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val sessionName: String,
        val startDirectory: String?,
        val tmuxSessionId: String? = null,
        val sessionCreated: Long? = null,
        val composerDraft: String,
        val savedAtMillis: Long,
    )

    /**
     * Persist [session] as the last active view. Called from
     * `MainActivity.onStop` when the current destination is a
     * [AppDestination.TmuxSession]. A synchronous `apply()` is enough —
     * the value is only read on the next foreground.
     */
    fun save(session: LastSession) {
        // Issue #834: never persist a session the user just deleted. If the
        // user backgrounds the app while still on the now-dead session screen,
        // `onStop` would otherwise re-arm the restore for a session that no
        // longer exists, and the next foreground/process-death restore would
        // reopen it (→ #818 Conversation tab of a deleted session, the #686
        // hazard). Clear the on-disk record instead of writing the dead one.
        if (killedTombstone == session.identity()) {
            Log.i(
                LAST_SESSION_LOG_TAG,
                "last-session-save-suppressed trigger=onStop reason=killed " +
                    "hostId=${session.hostId} session=${session.sessionName}",
            )
            prefs.edit().clear().apply()
            return
        }
        Log.i(
            LAST_SESSION_LOG_TAG,
            "last-session-save trigger=onStop hostId=${session.hostId} " +
                "host=${session.hostname} port=${session.port} user=${session.username} " +
                "session=${session.sessionName} startDirectory=${session.startDirectory}",
        )
        val editor = prefs.edit()
            .putLong(KEY_HOST_ID, session.hostId)
            .putString(KEY_HOST_NAME, session.hostName)
            .putString(KEY_HOSTNAME, session.hostname)
            .putInt(KEY_PORT, session.port)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_KEY_PATH, session.keyPath)
            .putString(KEY_SESSION_NAME, session.sessionName)
            .putString(KEY_START_DIR, session.startDirectory)
            .putString(KEY_TMUX_SESSION_ID, session.tmuxSessionId)
            .putString(KEY_COMPOSER_DRAFT, session.composerDraft)
            .putLong(KEY_SAVED_AT, session.savedAtMillis)
        if (session.sessionCreated != null) {
            editor.putLong(KEY_SESSION_CREATED, session.sessionCreated)
        } else {
            editor.remove(KEY_SESSION_CREATED)
        }
        editor.apply()
    }

    /**
     * Read the last active session if one was persisted and it is fresh
     * enough to be worth restoring.
     *
     * Returns null when:
     *  - nothing was ever saved (cold install / user never opened a
     *    tmux session),
     *  - the blob is malformed (a previous build wrote a different shape;
     *    D22 — discard, do not migrate),
     *  - the snapshot is older than [maxAgeMillis] (stale; the user has
     *    moved on, restore would be surprising).
     *
     * @param nowMillis injectable clock for deterministic unit tests.
     */
    fun read(
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): LastSession? {
        val savedAt = prefs.safeLong(KEY_SAVED_AT, 0L) ?: return null
        if (savedAt <= 0L) return null
        if (nowMillis - savedAt > maxAgeMillis) return null
        val hostId = prefs.safeLong(KEY_HOST_ID, 0L) ?: return null
        val hostname = prefs.safeString(KEY_HOSTNAME, null) ?: return null
        val username = prefs.safeString(KEY_USERNAME, null) ?: return null
        val keyPath = prefs.safeString(KEY_KEY_PATH, null) ?: return null
        val sessionName = prefs.safeString(KEY_SESSION_NAME, null) ?: return null
        if (hostId <= 0L || hostname.isBlank() || keyPath.isBlank() || sessionName.isBlank()) {
            return null
        }
        return LastSession(
            hostId = hostId,
            hostName = prefs.safeString(KEY_HOST_NAME, hostname) ?: hostname,
            hostname = hostname,
            port = prefs.safeInt(KEY_PORT, DEFAULT_SSH_PORT) ?: DEFAULT_SSH_PORT,
            username = username,
            keyPath = keyPath,
            sessionName = sessionName,
            startDirectory = prefs.safeString(KEY_START_DIR, null),
            tmuxSessionId = prefs.safeString(KEY_TMUX_SESSION_ID, null)?.trim()?.ifBlank { null },
            sessionCreated = prefs.safeLong(KEY_SESSION_CREATED, 0L)?.takeIf { it > 0L },
            composerDraft = prefs.safeString(KEY_COMPOSER_DRAFT, "") ?: "",
            savedAtMillis = savedAt,
        ).also { session ->
            Log.i(
                LAST_SESSION_LOG_TAG,
                "last-session-restore trigger=cold-restore hostId=${session.hostId} " +
                    "host=${session.hostname} port=${session.port} user=${session.username} " +
                    "session=${session.sessionName} startDirectory=${session.startDirectory}",
            )
        }
    }

    private fun SharedPreferences.safeString(key: String, default: String?): String? =
        runCatching { getString(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private fun SharedPreferences.safeLong(key: String, default: Long): Long? =
        runCatching { getLong(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                null
            }

    private fun SharedPreferences.safeInt(key: String, default: Int): Int? =
        runCatching { getInt(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                null
            }

    /**
     * Clear the persisted snapshot. Called when the user explicitly walks
     * away from the session (Detach / back to the host list) so a later
     * resume does not silently re-route them into a session they left on
     * purpose.
     */
    fun clear() {
        Log.i(LAST_SESSION_LOG_TAG, "last-session-clear trigger=onStop")
        prefs.edit().clear().apply()
    }

    /**
     * Issue #834: a session was confirmed killed (tree/host-detail Stop or
     * in-session Stop). Drop it as a restore target so it is never re-opened:
     *
     *  1. If the persisted "last active" record points at this exact session,
     *     clear it — otherwise the next process-death resume restores a deleted
     *     session (→ #818 Conversation tab of a dead session, the #686 hazard).
     *  2. Remember the killed identity as a tombstone so a later `onStop`
     *     [save] for the SAME dead session (user still parked on the now-dead
     *     screen) is refused rather than re-arming the restore.
     *
     * Matching is on (hostId, sessionName) — the same identity
     * [com.pocketshell.app.tmux.SessionLifecycleSignals] broadcasts. A kill on
     * one session never invalidates a different stored session.
     */
    fun onSessionKilled(hostId: Long, sessionName: String) {
        val trimmed = sessionName.trim()
        if (trimmed.isEmpty()) return
        val killed = SessionIdentity(hostId = hostId, sessionName = trimmed)
        killedTombstone = killed
        val stored = read(maxAgeMillis = Long.MAX_VALUE)
        if (stored != null && stored.identity() == killed) {
            Log.i(
                LAST_SESSION_LOG_TAG,
                "last-session-clear trigger=killed hostId=$hostId session=$trimmed",
            )
            prefs.edit().clear().apply()
        }
    }

    /**
     * Issue #834: a session of [sessionName] on [hostId] was legitimately
     * (re)opened. Clears the kill tombstone for that exact identity so a
     * recreated same-name session is restorable again.
     *
     * tmux session names are user-chosen and habitually reused (`main`,
     * `work`, `claude-main`), so a kill tombstone must NOT outlive the
     * recreation of that identity — otherwise the next `onStop` [save] of the
     * recreated live session is wrongly suppressed and the #177 fast-resume
     * breaks for that name forever (the over-suppression the reviewer flagged).
     * Matching is on (hostId, sessionName), identical to [onSessionKilled], so
     * opening a DIFFERENT session never clears another session's tombstone.
     */
    fun onSessionOpened(hostId: Long, sessionName: String) {
        val trimmed = sessionName.trim()
        if (trimmed.isEmpty()) return
        if (killedTombstone == SessionIdentity(hostId = hostId, sessionName = trimmed)) {
            Log.i(
                LAST_SESSION_LOG_TAG,
                "last-session-tombstone-clear trigger=opened hostId=$hostId session=$trimmed",
            )
            killedTombstone = null
        }
    }

    private fun LastSession.identity(): SessionIdentity =
        SessionIdentity(hostId = hostId, sessionName = sessionName)

    /**
     * Rebuild the navigation destination from a persisted [LastSession].
     * The passphrase is null — the reattach path reads the key from disk
     * by [LastSession.keyPath], identical to a cold attach.
     */
    fun LastSession.toDestination(): AppDestination.TmuxSession =
        AppDestination.TmuxSession(
            hostId = hostId,
            hostName = hostName,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = null,
            sessionName = sessionName,
            startDirectory = startDirectory,
            tmuxSessionId = tmuxSessionId,
            sessionCreated = sessionCreated,
        )

    companion object {
        private const val PREFS_NAME = "last_session"
        private const val LAST_SESSION_LOG_TAG = "PsLastSession"
        private const val KEY_HOST_ID = "host_id"
        private const val KEY_HOST_NAME = "host_name"
        private const val KEY_HOSTNAME = "hostname"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_KEY_PATH = "key_path"
        private const val KEY_SESSION_NAME = "session_name"
        private const val KEY_START_DIR = "start_dir"
        private const val KEY_TMUX_SESSION_ID = "tmux_session_id"
        private const val KEY_SESSION_CREATED = "session_created"
        private const val KEY_COMPOSER_DRAFT = "composer_draft"
        private const val KEY_SAVED_AT = "saved_at"

        private const val DEFAULT_SSH_PORT = 22

        /**
         * Snapshots older than this are not restored — the user has moved
         * on and a surprise auto-route into a day-old session would be
         * worse than landing on the host list. Issue #177 acceptance:
         * "if the persisted state is recent (≤ 24h or configurable)".
         */
        const val DEFAULT_MAX_AGE_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}
