package com.pocketshell.next.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The one network seam the sync feature has (issue #2633).
 *
 * `HttpURLConnection`, like [com.pocketshell.next.release.ReleaseChecker] —
 * a handful of small JSON/form calls do not justify pulling OkHttp into app2.
 * Everything above this interface (Google's token endpoint, the sync API) is
 * driven through it, so every JVM test in this package scripts responses
 * instead of touching the network.
 */
data class SyncHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
)

data class SyncHttpResponse(val code: Int, val body: String)

fun interface SyncHttpClient {
    /** @throws IOException on a transport failure (never for a non-2xx code). */
    fun send(request: SyncHttpRequest): SyncHttpResponse
}

/** Form-encodes [fields] for the `application/x-www-form-urlencoded` calls. */
fun formEncode(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }

private fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

/**
 * Production client: always `disconnect()`, and read the error stream on a
 * non-2xx so a 401/409 body still reaches the caller (both carry the detail
 * the sync flow branches on) and the keep-alive socket is not leaked.
 */
class HttpUrlConnectionSyncClient(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : SyncHttpClient {

    override fun send(request: SyncHttpRequest): SyncHttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            request.contentType?.let { setRequestProperty("Content-Type", it) }
            if (request.body != null) doOutput = true
        }
        return try {
            request.body?.let { body ->
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            SyncHttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
