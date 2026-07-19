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
     * Issue #1681 — the pinned MOBILE-SPIKE profile: a symmetric one-way latency
     * of [MOBILE_ONE_WAY_LATENCY_MS] on BOTH directions, so a single SSH
     * round-trip costs ~2 × that ≈ [MOBILE_RTT_MS] ms. That RTT is the
     * deterministic "WiFi ok, mobile breaks" pin (#1680 Track B): a bounded
     * remote exec is a channel-open + exec + read (~2–3 round-trips + host CLI),
     * so at ~1.8 s RTT it costs ~5 s and OVERRUNS the 3.5 s bounded-exec budget
     * (`AgentKindRemoteSource`, `SessionCardsRemoteSource`, `FolderListGateway`),
     * while the `-CC` liveness probe (5 s per-probe, a single-round-trip
     * refresh-client), the transport keepalive (30 s interval), the tmux command
     * timeout (10 s), AND the 8 s TransportDispatcher per-op wall-clock ceiling
     * (#937/#1567) all stay green — only the 3.5 s bounded-exec threshold
     * crosses. Jitter is 0 so the RTT is a stable, citable figure (the #817
     * fixed-RTT model, not a fuzz model), which is what makes the red→green gate
     * deterministic.
     *
     * NB (#1681 implementation finding): the RTT is deliberately ~1.8 s, NOT the
     * ~4.0 s the original #1681 recipe pinned. That recipe's budget analysis
     * omitted the 8 s TransportDispatcher per-op ceiling; at ~4.0 s RTT a raw
     * multi-round-trip exec exceeds 8 s and is killed by the TRANSPORT ceiling,
     * confounding the classify's 3.5 s self-close attribution. ~1.8 s RTT (a
     * realistic loaded-LTE figure — the design's own table calls RTT 0.5–4 s
     * routine) crosses the 3.5 s classify bound while a fresh exec still finishes
     * under 8 s, keeping the self-inflicted signal surgical (G6).
     *
     * The matching under-threshold extreme is [addSymmetricLatency] with
     * [WIFI_ONE_WAY_LATENCY_MS] (RTT ≈ 150 ms) — the #1633 both-extremes pin:
     * the same journey must show ZERO overruns on WiFi, bracketing the monotonic
     * RTT-vs-3.5 s-bound variable at both ends.
     *
     * Folded into the standard `latency_upstream` / `latency_downstream` toxic
     * names (via [addSymmetricLatency]) so [clearToxics] / [reset] remove it.
     */
    fun addMobileProfile() = addSymmetricLatency(MOBILE_ONE_WAY_LATENCY_MS)

    /**
     * Issue #970 (the realistic-wifi stability gate): a STABLE-but-JITTERY link.
     * A steady base [latencyMs] one-way latency on BOTH directions WITH a
     * [jitterMs] random jitter band models a physically stable wifi/mobile link
     * that is never down but whose RTT wobbles — exactly the conditions a stable
     * client must ride through without redialing. Unlike [addSymmetricLatency]
     * (jitter 0 — a citable fixed RTT) this is deliberately fuzzed so the
     * `-CC` `refresh-client` round-trip cost varies tick-to-tick the way a real
     * jittery link does.
     *
     * Folded into the standard `latency_upstream` / `latency_downstream` toxic
     * names so [clearToxics] / [reset] remove them like the canned model.
     *
     * NB (per #970 / the issue's toxiproxy caveat): toxiproxy `toxicity` is
     * per-NEW-connection, so it cannot fuzz a SINGLE long-lived SSH connection's
     * jitter the way real jitter does — but the `latency` toxic's own `jitter`
     * attribute DOES vary the per-packet delay within an established connection,
     * which is what this uses. The deterministic ride-through proof does NOT lean
     * on toxiproxy at all (it uses the in-app probe/keepalive seams); this toxic
     * is the OPT-IN realistic-link variant.
     */
    fun addJitterLatency(latencyMs: Int, jitterMs: Int) {
        addToxic(
            name = "latency_upstream",
            type = "latency",
            stream = "upstream",
            attributesJson = """{"latency":$latencyMs,"jitter":$jitterMs}""",
        )
        addToxic(
            name = "latency_downstream",
            type = "latency",
            stream = "downstream",
            attributesJson = """{"latency":$latencyMs,"jitter":$jitterMs}""",
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

    companion object {
        /**
         * Issue #1681 — the mobile-spike one-way latency. RTT ≈ 2× ≈ 1.8 s; a
         * fresh multi-round-trip exec (~5 s) clears the 3.5 s bounded-exec budget
         * while staying under the 5 s liveness-probe, 8 s transport per-op
         * ceiling, 10 s tmux-command, and 30 s keepalive thresholds (see
         * [addMobileProfile] for why this is ~1.8 s, not the recipe's ~4.0 s).
         */
        const val MOBILE_ONE_WAY_LATENCY_MS: Int = 900

        /** The resulting round-trip time for [MOBILE_ONE_WAY_LATENCY_MS] (documentation only). */
        const val MOBILE_RTT_MS: Int = MOBILE_ONE_WAY_LATENCY_MS * 2

        /**
         * Issue #1681 — the WiFi baseline one-way latency (RTT ≈ 150 ms), the
         * #1633 both-extremes under-threshold pin: a classify at this RTT never
         * overruns the 3.5 s bound, so the same journey shows ZERO storm.
         */
        const val WIFI_ONE_WAY_LATENCY_MS: Int = 75

        private const val PROXY_NAME: String = "agents_ssh"
        private val KNOWN_TOXICS: List<String> = listOf(
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
