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

    /**
     * Issue #817 (Rank-1 measurement): add a symmetric, deterministic latency
     * toxic on BOTH directions so a single SSH round-trip costs ~2 × [oneWayMs]
     * (one-way client→server + one-way server→client). No jitter — the goal is a
     * stable, citable RTT figure, not a fuzz model. With [oneWayMs] = 75 the
     * round-trip is ~150 ms, modelling a phone on a typical mobile/wifi link to a
     * remote host; ~40 ms one-way models a closer/better link (~80 ms RTT).
     *
     * Folded into the standard `latency_upstream` / `latency_downstream` toxic
     * names so [clearToxics] / [reset] remove them like the canned model.
     */
    fun addSymmetricLatency(oneWayMs: Int) {
        addToxic(
            name = "latency_upstream",
            type = "latency",
            stream = "upstream",
            attributesJson = """{"latency":$oneWayMs,"jitter":0}""",
        )
        addToxic(
            name = "latency_downstream",
            type = "latency",
            stream = "downstream",
            attributesJson = """{"latency":$oneWayMs,"jitter":0}""",
        )
    }

    /**
     * Cap the downstream (server -> client) throughput with the stock toxiproxy
     * `bandwidth` toxic. [rateKbps] is the sustained rate in kilobytes/second
     * (toxiproxy's `rate` attribute). Used by the #576 Codex-redraw overflow
     * proof to make a heavy tmux `-CC` `%output` redraw take longer than the
     * `TmuxClient` 10 s command-timeout to drain, so an in-flight control
     * command's `%begin`/`%end` response sits behind the backlog and trips
     * `FatalClose` -> reader EOF -> reconnect. The bandwidth cap turns that
     * timing-dependent race into a pinned, deterministic threshold
     * (`redraw_bytes / rate > commandTimeoutMs`).
     */
    fun addBandwidthLimit(rateKbps: Int) {
        addToxic(
            name = "bandwidth_downstream",
            type = "bandwidth",
            stream = "downstream",
            attributesJson = """{"rate":$rateKbps}""",
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
        // Tolerate HTTP 409 "proxy already exists": toxiproxy is a single shared
        // instance, so when more than one connected lane resets concurrently the
        // DELETE+POST pair can interleave (one lane's POST lands after another
        // re-created the proxy). The post-condition of [reset] — an enabled
        // `agents_ssh` proxy on :2228 — still holds, so a 409 is benign and must
        // not fail the reset.
        runCatching {
            transport.request(
                "POST",
                "/proxies",
                """{"name":"$PROXY_NAME","listen":"0.0.0.0:2228","upstream":"agents:22","enabled":true}""",
            )
        }.onFailure { error ->
            if (error.message?.contains("HTTP 409") != true) throw error
        }
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
            "bandwidth_downstream",
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
