package com.pocketshell.app.proof

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class ToxiproxyControl(
    baseUrl: String,
    private val transport: ToxiproxyTransport = HttpToxiproxyTransport(baseUrl),
) {

    fun reset() {
        runCatching { transport.request("DELETE", "/proxies/$PROXY_NAME", null) }
        createProxy()
    }

    fun addBlackhole() {
        addToxic(
            name = "blackhole_upstream",
            type = "timeout",
            stream = "upstream",
            attributesJson = """{"timeout":0}""",
        )
        addToxic(
            name = "blackhole_downstream",
            type = "timeout",
            stream = "downstream",
            attributesJson = """{"timeout":0}""",
        )
    }

    fun addLatencyModel() {
        addToxic(
            name = "latency_upstream",
            type = "latency",
            stream = "upstream",
            attributesJson = """{"latency":120,"jitter":40}""",
        )
        addToxic(
            name = "latency_downstream",
            type = "latency",
            stream = "downstream",
            attributesJson = """{"latency":160,"jitter":60}""",
        )
    }

    fun clearToxics() {
        KNOWN_TOXICS.forEach { name ->
            runCatching { transport.request("DELETE", "/proxies/$PROXY_NAME/toxics/$name", null) }
        }
    }

    /** Drop active connections and refuse new ones until [enable] restores the proxy. */
    fun disable() {
        transport.request("POST", "/proxies/$PROXY_NAME", """{"enabled":false}""")
    }

    /** Restore the link after [disable]; new connections are accepted again. */
    fun enable() {
        transport.request("POST", "/proxies/$PROXY_NAME", """{"enabled":true}""")
    }

    private fun createProxy() {
        transport.request(
            "POST",
            "/proxies",
            """{"name":"$PROXY_NAME","listen":"0.0.0.0:2228","upstream":"agents:22","enabled":true}""",
        )
    }

    private fun addToxic(name: String, type: String, stream: String, attributesJson: String) {
        transport.request(
            "POST",
            "/proxies/$PROXY_NAME/toxics",
            """{"name":"$name","type":"$type","stream":"$stream","toxicity":1.0,"attributes":$attributesJson}""",
        )
    }

    private companion object {
        const val PROXY_NAME: String = "agents_ssh"
        val KNOWN_TOXICS: List<String> = listOf(
            "blackhole_upstream",
            "blackhole_downstream",
            "latency_upstream",
            "latency_downstream",
        )
    }
}

fun interface ToxiproxyTransport {
    fun request(method: String, path: String, body: String?): String
}

class HttpToxiproxyTransport(private val baseUrl: String) : ToxiproxyTransport {
    override fun request(method: String, path: String, body: String?): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            doInput = true
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(code in 200..299) {
            "toxiproxy $method $path failed: HTTP $code $response"
        }
        return response
    }
}
