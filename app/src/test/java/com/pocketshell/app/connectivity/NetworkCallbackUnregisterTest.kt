package com.pocketshell.app.connectivity

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager

/**
 * Issue #956 (#935 S3-3): [TerminalNetworkObserver] — the reconnect-signal
 * observer — used to register a [ConnectivityManager.NetworkCallback] with NO
 * matching unregister, a latent resource leak / ghost-callback risk.
 *
 * Robolectric's [ShadowConnectivityManager] tracks live callbacks in
 * [ShadowConnectivityManager.getNetworkCallbacks] (both
 * `registerNetworkCallback` and `registerDefaultNetworkCallback` land in the
 * same set), and removes them on `unregisterNetworkCallback`. So:
 *
 *  - after construction the callback set must be non-empty (registered), and
 *  - after `close()` the callback set must NOT contain the observer's
 *    callback (unregistered).
 *
 * On the BASE (no `close()` / no unregister) these go RED because the callback
 * never leaves the live set; with the fix they go GREEN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NetworkCallbackUnregisterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shadow(): ShadowConnectivityManager {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return shadowOf(cm)
    }

    @Test
    fun `TerminalNetworkObserver registers a callback then unregisters it on close`() {
        val before = shadow().networkCallbacks.toSet()

        val observer = TerminalNetworkObserver(context)

        val afterConstruct = shadow().networkCallbacks.toSet()
        val registered = afterConstruct - before
        assertTrue(
            "TerminalNetworkObserver must register exactly one new NetworkCallback",
            registered.size == 1,
        )
        val callback = registered.single()

        observer.close()

        assertFalse(
            "TerminalNetworkObserver.close() must unregister its NetworkCallback (no leak)",
            shadow().networkCallbacks.contains(callback),
        )
    }

    @Test
    fun `TerminalNetworkObserver close is idempotent`() {
        val observer = TerminalNetworkObserver(context)
        observer.close()
        val remaining = shadow().networkCallbacks.size
        observer.close()
        assertTrue(remaining == shadow().networkCallbacks.size)
    }
}
