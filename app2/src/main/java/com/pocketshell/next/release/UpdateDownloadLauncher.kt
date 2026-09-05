package com.pocketshell.next.release

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens [url] with `ACTION_VIEW` so the system browser / download manager
 * handles the sideload. No in-app install, no `REQUEST_INSTALL_PACKAGES`.
 *
 * [Intent.FLAG_ACTIVITY_NEW_TASK] so this works from an Activity or a
 * non-Activity context (issue #515).
 */
internal fun launchUpdateUrl(context: Context, url: String) {
    try {
        context.startActivity(viewIntent(url))
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    } catch (_: RuntimeException) {
    }
}

internal fun viewIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
