package com.pocketshell.app.projects

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #1509: the production default for the coordinator's notifications step —
 * true when the Android 13+ POST_NOTIFICATIONS runtime grant is still needed
 * (SDK 33+ and not yet granted). No-op (false) below API 33 (install-time there)
 * and when [applicationContext] is null (unit paths). Injectable at the
 * [FolderListViewModel] boundary so tests drive the setup matrix deterministically.
 */
internal fun defaultNotificationPermissionNeeded(applicationContext: Context?): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val context = applicationContext ?: return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) != PackageManager.PERMISSION_GRANTED
}

/**
 * Issue #1509 — THE single session-tree setup coordinator.
 *
 * The maintainer's dogfood (v0.4.28): on app open the host-version-mismatch
 * **Update PocketShell** banner appeared AND, as a SECOND sequential prompt, the
 * **enable notifications** system dialog — two uncoordinated triggers (the
 * version banner computed off the session tree, the notification prompt fired
 * independently from `MainActivity.onCreate` on app open).
 *
 * The intended design is a relocation + dedup (hard-cut, D22): the notifications
 * request is FOLDED INTO the same one-shot, lazy, background setup pass that owns
 * the version-mismatch check, driven from the session tree the moment it is shown
 * — never from app open, never from two places. This class is that single code
 * path; [FolderListViewModel] delegates to it and the old `onCreate` notification
 * trigger is deleted.
 *
 * Guards:
 *  - The version-mismatch check runs EXACTLY ONCE per session-tree open. The
 *    `tree` payload can arrive with a null version first (empty seed / old CLI)
 *    and a concrete version on a later reconcile, so [maybeRunVersionCheck] only
 *    "completes" on the first non-null signal — after which automatic reconcile
 *    polls no longer re-raise a dismissed banner. Re-armed on a host change via
 *    [onHostChanged]; a same-host re-entry keeps a dismissed banner dismissed.
 *  - The notifications request is offered at most ONCE per app session (the view
 *    model is activity-scoped) via [runSetup], so re-opening a host's tree never
 *    re-prompts — no second sequential prompt.
 */
internal class SessionTreeSetupCoordinator(
    private val notificationPermissionNeeded: () -> Boolean,
) {
    private var versionCheckDone: Boolean = false
    private var notificationRequested: Boolean = false

    private val _notificationPermissionRequest: MutableStateFlow<Boolean> =
        MutableStateFlow(false)

    /**
     * Raised once by [runSetup] when the Android 13+ POST_NOTIFICATIONS runtime
     * permission is still needed; the session-tree screen collects it, launches
     * the system request, and calls [onNotificationPermissionRequestConsumed].
     */
    val notificationPermissionRequest: StateFlow<Boolean> =
        _notificationPermissionRequest.asStateFlow()

    /** Re-arm the one-shot version-mismatch check for a freshly opened host tree. */
    fun onHostChanged() {
        versionCheckDone = false
    }

    /**
     * The single setup pass. Folds in the notifications-permission request so it
     * is no longer a separate app-open trigger. Idempotent: safe to call on every
     * bind (host change or same-host re-entry); the request is offered once.
     */
    fun runSetup() {
        if (notificationRequested) return
        notificationRequested = true
        if (notificationPermissionNeeded()) {
            _notificationPermissionRequest.value = true
        }
    }

    /**
     * Run the passive host-CLI-version check EXACTLY ONCE per session-tree open,
     * invoking [raise] with the first non-null payload version. The user-initiated
     * post-upgrade recheck deliberately bypasses this guard (it calls the raw
     * evaluator directly).
     */
    fun maybeRunVersionCheck(hostCliVersion: String?, raise: (String) -> Unit) {
        if (versionCheckDone) return
        if (hostCliVersion.isNullOrBlank()) return
        versionCheckDone = true
        raise(hostCliVersion)
    }

    /** The session-tree screen consumed the notification-permission request. */
    fun onNotificationPermissionRequestConsumed() {
        _notificationPermissionRequest.value = false
    }
}
