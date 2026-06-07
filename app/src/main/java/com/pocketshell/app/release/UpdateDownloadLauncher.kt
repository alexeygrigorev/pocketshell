package com.pocketshell.app.release

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Shared user-driven update download path. Opens the release APK URL with
 * `ACTION_VIEW` so the browser / download manager handles the sideload.
 * If the direct APK URL cannot be opened, falls back to the GitHub release
 * page and reports the concrete failure reason to the caller.
 */
internal fun launchUpdateDownload(
    context: Context,
    info: ReleaseInfo,
    onStarted: (tagName: String) -> Unit,
    onFailed: (reason: String) -> Unit,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)))
        onStarted(info.tagName)
    } catch (e: ActivityNotFoundException) {
        launchReleasePageFallback(
            context = context,
            info = info,
            reason = e.message ?: "no app can open the download link",
            onFailed = onFailed,
        )
    } catch (e: SecurityException) {
        launchReleasePageFallback(
            context = context,
            info = info,
            reason = e.message ?: "the download was blocked",
            onFailed = onFailed,
        )
    }
}

private fun launchReleasePageFallback(
    context: Context,
    info: ReleaseInfo,
    reason: String,
    onFailed: (reason: String) -> Unit,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
    } catch (_: ActivityNotFoundException) {
        // The failure callback below is still the observable result.
    } catch (_: SecurityException) {
    }
    onFailed(reason)
}
