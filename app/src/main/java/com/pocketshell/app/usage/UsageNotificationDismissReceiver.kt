package com.pocketshell.app.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Delete-intent target for usage warning notifications (issue #619).
 *
 * When the user swipes a usage notification away, Android fires this
 * notification's `deleteIntent`. We durably record the dismissed crossing
 * in [SharedPreferencesUsageNotificationStateStore] so a dismissed
 * "quota exceeded" notification does NOT re-fire on the next usage sync or
 * after a cold launch — it only re-arms once the crossing clears (usage
 * drops below threshold or the window resets) and crosses again.
 *
 * Without this, the notifier persists notified keys only while they still
 * warrant a warning; an explicit dismissal is a stronger, user-driven
 * suppression of the same key.
 */
class UsageNotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        val raw = intent.getStringExtra(EXTRA_NOTIFICATION_KEY) ?: return
        val key = UsageNotificationKey.decode(raw) ?: return
        runCatching {
            val store = storeFactory(context.applicationContext)
            store.setNotifiedKeys(store.notifiedKeys() + key)
        }.onFailure {
            Log.w(TAG, "usage notification dismissal record failed", it)
        }
    }

    companion object {
        private const val TAG = "UsageNotifyDismiss"

        const val ACTION_DISMISS: String =
            "com.pocketshell.app.usage.action.USAGE_NOTIFICATION_DISMISSED"
        const val EXTRA_NOTIFICATION_KEY: String =
            "com.pocketshell.app.usage.extra.NOTIFICATION_KEY"

        /**
         * Store factory, overridable in tests. Defaults to the real
         * SharedPreferences-backed store.
         */
        @JvmStatic
        var storeFactory: (Context) -> UsageNotificationStateStore = { ctx ->
            SharedPreferencesUsageNotificationStateStore(ctx)
        }
    }
}
