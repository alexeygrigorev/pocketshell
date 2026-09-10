package com.pocketshell.next.sync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the Google authorization URL in the SYSTEM browser (issue #2633).
 *
 * A Custom Tab, never a `WebView`. That is not a style preference: an OAuth
 * request rendered inside the requesting app's own WebView lets that app read
 * the credentials typed into it, so Google refuses the flow outright, and the
 * user loses the browser's existing Google session and its address bar. A
 * Custom Tab is the system browser with the app's colours — same cookie jar,
 * same origin indicator, out of the app's reach.
 *
 * Split behind an interface because the sign-in flow is otherwise entirely
 * headless: a test can assert which URL would be opened without an Activity.
 */
fun interface AuthorizationLauncher {
    /** @throws SyncAuthError if the device has no browser at all. */
    fun open(context: Context, url: String)
}

class CustomTabsAuthorizationLauncher : AuthorizationLauncher {
    override fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            // No Custom Tabs provider AND no default browser resolved the
            // implicit VIEW intent CustomTabsIntent falls back to. One more
            // plain attempt so a device with an unusual browser still works,
            // then a message the settings screen can show.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                throw SyncAuthError("no browser is available to complete Google sign-in")
            }
        }
    }
}
