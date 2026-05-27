package com.pocketshell.app.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground-only network presence signal — issue #180.
 *
 * The composer wants to know "is the device on the internet right now?"
 * so that:
 *
 *  - Recording finishes that land while offline can skip the Whisper
 *    call entirely and go straight to the queued state with a "Waiting
 *    for network" hint, instead of burning a 60s OkHttp timeout.
 *  - When the device comes back online and the composer is visible, the
 *    queued items can be auto-retried.
 *
 * This is **not** a background service. The Android
 * [ConnectivityManager.NetworkCallback] is registered with
 * [ConnectivityManager.registerNetworkCallback] but the callbacks run
 * inside the app process; nothing keeps the JVM alive when the user
 * leaves the app. The class is `@Singleton` so the same callback
 * registration is shared across the composer ViewModel's lifetime.
 *
 * D21 (no-background-work) compliance: the observer never schedules work
 * itself. It exposes a [StateFlow] that the composer collects only while
 * the sheet is visible. When the user backgrounds the app, the
 * `viewModelScope` collectors stop; when they return, the latest
 * connectivity value is read again and any auto-retry is fired from the
 * foreground.
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext appContext: Context,
) {

    private val cm: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _hasNetwork: MutableStateFlow<Boolean> = MutableStateFlow(currentNetwork())

    /**
     * Hot snapshot of whether the device currently has *any* validated
     * network (cellular, wifi, ethernet, vpn). Defaults to `true` if the
     * [ConnectivityManager] is missing — the conservative choice is to
     * attempt Whisper rather than to falsely route a viable upload into
     * the offline queue.
     */
    val hasNetwork: StateFlow<Boolean> = _hasNetwork.asStateFlow()

    init {
        // Register a long-running callback. ConnectivityManager's API
        // surface here is not subject to background-execution limits the
        // way WorkManager is — the callback is delivered while the app
        // process is alive; nothing keeps the process pinned. Per D21
        // we are explicitly avoiding background work, but a passive
        // notify-when-changed callback is on-tap during foreground
        // sessions only.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _hasNetwork.value = true
                }

                override fun onLost(network: Network) {
                    // `onLost` fires per-network. Re-evaluate the global
                    // signal so a VPN drop with cellular still active
                    // does not flip the flag.
                    _hasNetwork.value = currentNetwork()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    // Capability changes (e.g. losing INTERNET on a
                    // captive portal) re-evaluate too.
                    _hasNetwork.value = currentNetwork()
                }

                override fun onUnavailable() {
                    _hasNetwork.value = false
                }
            })
        }
    }

    /**
     * One-shot read. Used at recording-stop time to decide between the
     * Whisper-and-queue-on-failure path and the queue-directly path. The
     * StateFlow value is always up-to-date thanks to the callback above,
     * but calling [refresh] explicitly forces a re-evaluation against
     * the platform state (defensive — the callback can lag on emulators).
     */
    fun refresh(): Boolean {
        val current = currentNetwork()
        _hasNetwork.value = current
        return current
    }

    private fun currentNetwork(): Boolean {
        val manager = cm ?: return true
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
