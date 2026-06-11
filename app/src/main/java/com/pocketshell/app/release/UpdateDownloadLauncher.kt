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
 *
 * Issue #515: the intent carries [Intent.FLAG_ACTIVITY_NEW_TASK] so the
 * launch succeeds regardless of whether [context] is an `Activity` or a
 * non-Activity context (application / service). Without the flag, a
 * non-Activity context makes `startActivity` throw
 * `android.util.AndroidRuntimeException` ("Calling startActivity() from
 * outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag"),
 * which the old code did NOT catch — so the tap silently threw and the
 * download never happened and the fallback never ran. We also widen the
 * catch to any [RuntimeException] so that mismatch (and the equivalent
 * exotic launch failures) degrade to the release-page fallback +
 * [onFailed] callback instead of crashing the tap. This is the same
 * primary route + flag the update notification already uses
 * (`UpdateAvailableNotifications`).
 */
internal fun launchUpdateDownload(
    context: Context,
    info: ReleaseInfo,
    onStarted: (tagName: String) -> Unit,
    onFailed: (reason: String) -> Unit,
) {
    try {
        context.startActivity(apkViewIntent(info))
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
    } catch (e: RuntimeException) {
        // Notably android.util.AndroidRuntimeException when a non-Activity
        // context launch is mishandled, but any other launch RuntimeException
        // should also degrade gracefully rather than crash the tap.
        launchReleasePageFallback(
            context = context,
            info = info,
            reason = e.message ?: "couldn't open the download link",
            onFailed = onFailed,
        )
    }
}

/**
 * `ACTION_VIEW` against the APK download URL with
 * [Intent.FLAG_ACTIVITY_NEW_TASK] so the launch works from any context type
 * (issue #515). Exposed at module scope so tests can pin the built intent.
 */
internal fun apkViewIntent(info: ReleaseInfo): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun releasePageIntent(info: ReleaseInfo): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun launchReleasePageFallback(
    context: Context,
    info: ReleaseInfo,
    reason: String,
    onFailed: (reason: String) -> Unit,
) {
    try {
        context.startActivity(releasePageIntent(info))
    } catch (_: ActivityNotFoundException) {
        // The failure callback below is still the observable result.
    } catch (_: SecurityException) {
    } catch (_: RuntimeException) {
    }
    onFailed(reason)
}
