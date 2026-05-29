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
 * entity would force a schema bump + `fallbackToDestructiveMigration`
 * which under D22 would nuke the user's saved hosts/keys for no functional
 * gain. DataStore would add a version-catalog entry without buying any
 * feature we need. Future issues are free to migrate.
 *
 * ## Hard-cut (D22)
 *
 * There is no legacy shape to honour — this is a brand new store. A blob
 * written by a previous build that does not parse cleanly is simply
 * discarded ([read] returns null), exactly the behaviour the maintainer
 * accepts ("if it means nuking the current settings, it's fine"). We do
 * not carry an `if (old shape)` compatibility branch.
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
        Log.i(
            LAST_SESSION_LOG_TAG,
            "last-session-save trigger=onStop hostId=${session.hostId} " +
                "host=${session.hostname} port=${session.port} user=${session.username} " +
                "session=${session.sessionName} startDirectory=${session.startDirectory}",
        )
        prefs.edit()
            .putLong(KEY_HOST_ID, session.hostId)
            .putString(KEY_HOST_NAME, session.hostName)
            .putString(KEY_HOSTNAME, session.hostname)
            .putInt(KEY_PORT, session.port)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_KEY_PATH, session.keyPath)
            .putString(KEY_SESSION_NAME, session.sessionName)
            .putString(KEY_START_DIR, session.startDirectory)
            .putString(KEY_COMPOSER_DRAFT, session.composerDraft)
            .putLong(KEY_SAVED_AT, session.savedAtMillis)
            .apply()
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
