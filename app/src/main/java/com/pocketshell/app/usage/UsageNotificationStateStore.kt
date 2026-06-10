package com.pocketshell.app.usage

import android.content.Context
import com.pocketshell.core.usage.UsageThresholdState

/**
 * Durable record of which usage warning crossings have already been
 * notified or explicitly dismissed.
 *
 * Issue #619: the notifier de-dupe used to live only in an in-memory
 * `Set<UsageNotificationKey>` on the `@Singleton` notifier. PocketShell is
 * foreground-only (decision D21): the OS kills the process in the background
 * and recreates it on next foreground, wiping that in-memory set. The next
 * usage sync then re-posts the same "quota exceeded" notification on every
 * cold launch.
 *
 * This store persists the notified-crossing set across process death so a
 * crossing fires exactly once until it re-arms (usage drops below threshold
 * or the window resets), and a user dismissal durably suppresses re-firing
 * until the crossing re-arms.
 *
 * The interface is deliberately tiny so JVM unit tests pass a fake instead
 * of touching a real Android preference store.
 */
public interface UsageNotificationStateStore {
    /** Keys for crossings that have already been notified (or dismissed). */
    public fun notifiedKeys(): Set<UsageNotificationKey>

    /**
     * Replace the persisted notified set with exactly [keys].
     *
     * Keys that warrant a warning this tick but are not yet persisted get
     * added; keys that no longer warrant a warning get pruned so the
     * crossing re-arms for a future genuine crossing. Dismissed keys that
     * still warrant a warning must be retained by the caller.
     */
    public fun setNotifiedKeys(keys: Set<UsageNotificationKey>)
}

/**
 * Stable, parseable string identity of a usage warning crossing.
 *
 * Encodes host id, provider, threshold state, and the constrained window
 * name so the persisted set can be reconstructed across process death and
 * a delete-intent broadcast can carry the key as a single extra.
 */
public data class UsageNotificationKey(
    val hostId: Long,
    val provider: String,
    val state: UsageThresholdState,
    val windowName: String?,
) {
    public fun encode(): String =
        listOf(
            hostId.toString(),
            provider,
            state.name,
            windowName ?: "",
        ).joinToString(FIELD_SEPARATOR)

    public companion object {
        // Unit Separator (0x1F) — never appears in provider/window names.
        private const val FIELD_SEPARATOR = "\u001F"

        public fun decode(raw: String): UsageNotificationKey? {
            val parts = raw.split(FIELD_SEPARATOR)
            if (parts.size != 4) return null
            val hostId = parts[0].toLongOrNull() ?: return null
            val state = runCatching { UsageThresholdState.valueOf(parts[2]) }.getOrNull()
                ?: return null
            val windowName = parts[3].takeIf { it.isNotEmpty() }
            return UsageNotificationKey(
                hostId = hostId,
                provider = parts[1],
                state = state,
                windowName = windowName,
            )
        }
    }
}

/**
 * [UsageNotificationStateStore] backed by a small dedicated
 * [android.content.SharedPreferences] file. Deliberately separate from
 * user-facing settings (`app_settings`) so it never appears in the Settings
 * UI or backup/restore of user preferences.
 */
public class SharedPreferencesUsageNotificationStateStore(
    context: Context,
) : UsageNotificationStateStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun notifiedKeys(): Set<UsageNotificationKey> =
        prefs.getStringSet(KEY_NOTIFIED, emptySet())
            .orEmpty()
            .mapNotNull(UsageNotificationKey::decode)
            .toSet()

    override fun setNotifiedKeys(keys: Set<UsageNotificationKey>) {
        prefs.edit()
            .putStringSet(KEY_NOTIFIED, keys.map { it.encode() }.toSet())
            .apply()
    }

    public companion object {
        public const val PREFS_NAME: String = "usage_notification_state"
        private const val KEY_NOTIFIED = "notified_keys"
    }
}
