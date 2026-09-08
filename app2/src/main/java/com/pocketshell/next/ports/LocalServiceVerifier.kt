package com.pocketshell.next.ports

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pocketshell.core.portfwd.TunnelInfo
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Checks the device-local end of a forward before offering a browser handoff.
 *
 * A listening TCP port is not enough evidence that a browser can use it: an
 * SSH, database, or arbitrary binary service can be listening there too. A
 * response from the HTTP stack is the narrow signal needed by the Quiet
 * Services screen. The probe never leaves the device and never follows a
 * remote host name.
 */
internal object LocalServiceVerifier {
    private const val TIMEOUT_MS = 1_200

    /** Returns the usable URL, or null when the local port is not HTTP(S). */
    fun verify(tunnel: TunnelInfo): String? {
        if (tunnel.status != TunnelInfo.Status.FORWARDING) return null
        if (tunnel.localPort !in 1..65_535) return null

        listOf("http", "https").forEach { scheme ->
            val url = "$scheme://127.0.0.1:${tunnel.localPort}"
            if (respondsAsHttp(url)) return url
        }
        return null
    }

    private fun respondsAsHttp(url: String): Boolean {
        val connection = runCatching {
            (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "HEAD"
                setRequestProperty("Connection", "close")
            }
        }.getOrNull() ?: return false

        return try {
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_BAD_METHOD || responseCode == 501) {
                connection.disconnect()
                getResponseForGet(url)
            } else {
                responseCode in 100..599
            }
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun getResponseForGet(url: String): Boolean {
        val connection = runCatching {
            (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Connection", "close")
            }
        }.getOrNull() ?: return false

        return try {
            val responseCode = connection.responseCode
            runCatching { connection.inputStream.use { it.read() } }
            responseCode in 100..599
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

/** Opens a previously verified local URL in the system browser. */
internal fun launchServiceUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    } catch (_: RuntimeException) {
    }
}
