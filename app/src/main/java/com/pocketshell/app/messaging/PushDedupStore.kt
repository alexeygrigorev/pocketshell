package com.pocketshell.app.messaging

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.prefs.DeferredPrefs

/**
 * Persistent "already notified for this reset" guard (issue #690, #619
 * don't-renotify).
 *
 * A reset push carries the server-side `reset_key` (the same reset keeps the
 * same key across re-deliveries). [markNotifiedIfNew] records the key and
 * returns true only the FIRST time it's seen, so an FCM retry of the same reset
 * does not produce a second notification. The set is bounded so a long-running
 * install doesn't grow it without limit; the oldest keys age out first.
 */
public class PushDedupStore internal constructor(
    private val deferredPrefs: DeferredPrefs,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    /** Direct-injection / test seam: wrap an already-built prefs. */
    public constructor(prefs: SharedPreferences, maxKeys: Int = DEFAULT_MAX_KEYS) : this(
        DeferredPrefs(opener = { prefs }),
        maxKeys,
    )

    // Issue #1125: open the prefs file off the Main thread (the FCM-token
    // registrar graph builds this on Main at usage-panel injection; the
    // messaging service builds it on its FCM callback thread).
    public constructor(context: Context, maxKeys: Int = DEFAULT_MAX_KEYS) : this(
        DeferredPrefs(context, PREFS_NAME),
        maxKeys,
    )

    private val prefs: SharedPreferences get() = deferredPrefs.get()

    @VisibleForTesting
    internal fun awaitPrefsBuildThreadNameForTest(): String =
        deferredPrefs.awaitBuildThreadNameForTest()

    /**
     * Record [resetKey] as notified. Returns true if it was NOT previously
     * known (i.e. the caller should notify), false if it was already seen.
     */
    public fun markNotifiedIfNew(resetKey: String): Boolean {
        val key = resetKey.trim()
        if (key.isEmpty()) return false
        val existing = readOrder()
        if (existing.contains(key)) return false
        val updated = (existing + key).takeLast(maxKeys)
        prefs.edit().putString(KEY_ORDER, updated.joinToString(SEPARATOR)).apply()
        return true
    }

    public fun hasNotified(resetKey: String): Boolean = readOrder().contains(resetKey.trim())

    private fun readOrder(): List<String> {
        val raw = prefs.getString(KEY_ORDER, "")?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotEmpty() }
    }

    public companion object {
        public const val PREFS_NAME: String = "pocketshell_push_dedup"
        private const val KEY_ORDER: String = "notified_reset_keys"
        private const val SEPARATOR: String = "\n"
        public const val DEFAULT_MAX_KEYS: Int = 200
    }
}
