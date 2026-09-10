package com.pocketshell.next.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Catches Google's redirect back into the app and finishes immediately
 * (issue #2633).
 *
 * The desktop app receives its authorization code on a loopback HTTP listener.
 * Android has no equivalent: the browser hands the code back by launching an
 * `Intent` at whatever activity declares the redirect URI's scheme, so the
 * app needs an activity to be that target. This is that activity, and it is
 * deliberately the whole of it — parse nothing, render nothing, own nothing.
 *
 * It is a SEPARATE activity rather than a second `<intent-filter>` on
 * `MainActivity` because the redirect must not be able to disturb the app's
 * task: `MainActivity` would need a launch mode that changes how every normal
 * launch behaves, and a Custom Tab returning to a re-created host screen is a
 * visible glitch. Finishing here drops the user back on the settings screen
 * they left, which is still below on the stack. Same shape as AppAuth's
 * `RedirectUriReceiverActivity`.
 *
 * The code goes straight into [SyncSignInCoordinator], which owns the
 * exchange; nothing about the token ever passes through an Activity result or
 * an Intent extra.
 */
@AndroidEntryPoint
class SyncOAuthRedirectActivity : ComponentActivity() {

    @Inject
    lateinit var coordinator: SyncSignInCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { coordinator.onRedirect(it.toString()) }
        finish()
    }
}
