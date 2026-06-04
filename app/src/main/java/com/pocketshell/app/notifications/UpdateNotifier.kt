package com.pocketshell.app.notifications

import android.content.Context
import com.pocketshell.app.release.ReleaseInfo

/**
 * Seam between the foreground release check in
 * [com.pocketshell.app.hosts.HostListViewModel] and the Android
 * notification surface ([UpdateAvailableNotifications]) for issue #502.
 *
 * Pulling the post behind an interface keeps the ViewModel unit-testable
 * without a real `NotificationManager`: the test injects a recording
 * fake and asserts that detection notifies once and that a repeat
 * detection of the same version is de-duped.
 */
fun interface UpdateNotifier {
    /** Surface (or suppress, when de-duped) the update-available notification for [info]. */
    fun notifyUpdateAvailable(info: ReleaseInfo)
}

/**
 * Production [UpdateNotifier]: de-dupes per release tag via
 * [UpdateNotificationStore] and, only when this version has not been
 * surfaced before, posts the local notification via
 * [UpdateAvailableNotifications].
 *
 * De-dupe is the contract from the issue ("don't re-notify the same
 * version repeatedly"): the checker re-runs on every cold launch and
 * pull-to-refresh, so without the store the user would get a fresh
 * notification on every app open until they updated.
 */
class DefaultUpdateNotifier(
    context: Context,
    private val store: UpdateNotificationStore = UpdateNotificationStore(context),
    private val poster: (ReleaseInfo) -> Unit = { info ->
        UpdateAvailableNotifications.show(context.applicationContext, info)
    },
) : UpdateNotifier {

    override fun notifyUpdateAvailable(info: ReleaseInfo) {
        if (!store.shouldNotify(info.tagName)) return
        poster(info)
        store.markNotified(info.tagName)
    }
}
