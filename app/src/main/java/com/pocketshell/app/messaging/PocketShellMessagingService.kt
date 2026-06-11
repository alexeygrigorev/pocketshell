package com.pocketshell.app.messaging

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM data pushes (issue #690) and turns a usage-reset push into a
 * local notification that deep-links to the Usage screen.
 *
 * D21-safe by construction: PocketShell holds NO background connection of its
 * own. Google Play Services owns the FCM socket and wakes this service only
 * when a push arrives — there is no app-side `WorkManager` / foreground service
 * / polling loop. The service does the minimal work of building a notification
 * and returns; it never opens an SSH session or schedules background work.
 *
 * ## Token registration
 *
 * [onNewToken] is FCM's callback when the device token is minted or rotated.
 * Delivery of that token to the host (so the server's `pocketshell` reset
 * detector can target this device) is the maintainer's setup step described on
 * [FcmTokenRegistrar]; the token is cached locally there so the next foreground
 * SSH session can push it to the host. This service does NOT itself open SSH
 * (D21 — no background work); it only persists the freshest token.
 *
 * ## De-dup
 *
 * The push carries the server-side `reset_key` (#619 don't-renotify);
 * [PushDedupStore] suppresses a key that already notified, so an FCM retry of
 * the same reset never double-notifies.
 */
public class PocketShellMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = ResetPushPayload.fromData(message.data) ?: run {
            Log.d(TAG, "Ignoring non-reset push (type=${message.data[ResetPushPayload.KEY_TYPE]})")
            return
        }
        val dedup = PushDedupStore(applicationContext)
        if (!dedup.markNotifiedIfNew(payload.resetKey)) {
            Log.d(TAG, "Reset already notified for key=${payload.resetKey}; suppressing")
            return
        }
        ResetPushNotifications.show(
            context = applicationContext,
            title = payload.title,
            body = payload.body,
            resetKey = payload.resetKey,
        )
    }

    override fun onNewToken(token: String) {
        // D21: do NOT open SSH from here (no background work). Cache the freshest
        // token so the next FOREGROUND session can register it with the host.
        FcmTokenRegistrar(applicationContext).onTokenRefreshed(token)
        Log.d(TAG, "FCM token refreshed (len=${token.length})")
    }

    public companion object {
        private const val TAG: String = "PsMessaging"
    }
}
