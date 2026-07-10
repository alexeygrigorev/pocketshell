package com.pocketshell.app.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground-only network presence signal — issue #180.
 *
 * The composer wants to know "is the device on the internet right now?"
 * so that a recording finish that lands while offline can skip the Whisper
 * call entirely and go straight to the queued state with a "Waiting for
 * network" hint, instead of burning a 60s OkHttp timeout, and so a queued
 * item can be auto-retried when the composer is next visible and online.
 *
 * This is **not** a background service and it holds no long-running
 * [ConnectivityManager.NetworkCallback]. The single consumer
 * ([com.pocketshell.app.di.VoiceModule]'s `ConnectivityObserverProbe`)
 * calls [refresh] on-tap from the foreground, so a one-shot read against
 * the platform state is all that is needed — D21 (no-background-work)
 * compliant by construction. The reconnect core uses a *separate* observer
 * ([com.pocketshell.app.connectivity.TerminalNetworkObserver]); this class
 * never feeds it.
 *
 * `@Singleton` so the composer ViewModel's lifetime shares one instance.
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext appContext: Context,
) {

    private val cm: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * One-shot read. Used at recording-stop time to decide between the
     * Whisper-and-queue-on-failure path and the queue-directly path.
     * Defaults to `true` when the [ConnectivityManager] is missing — the
     * conservative choice is to attempt Whisper rather than to falsely
     * route a viable upload into the offline queue.
     */
    fun refresh(): Boolean = currentNetwork()

    private fun currentNetwork(): Boolean {
        val manager = cm ?: return true
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
