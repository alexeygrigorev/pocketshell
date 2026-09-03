package com.pocketshell.next.connect

import androidx.test.platform.app.InstrumentationRegistry
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * The knob a journey turns to cut the network (rewrite task U-7, journey J05).
 *
 * The `network-fault-proxy` compose service (`tests/docker/docker-compose.yml`)
 * is a Toxiproxy in front of the `agents` SSH fixture: the app dials
 * `10.0.2.2:<faultSshPort>` instead of the fixture's own port, and this class
 * disables/enables that proxy over Toxiproxy's HTTP control API.
 *
 * Why a proxy rather than stopping the container: an outage has to be
 * INSTANTANEOUS, TOTAL and REVERSIBLE ON DEMAND for the reconnect ladder to be
 * observable — disabling a proxy drops every live connection and refuses new
 * ones the moment the call returns, and enabling it restores service just as
 * fast. Restarting sshd takes seconds we cannot bound, and killing the app's
 * session process alone leaves the port listening, so the very next dial
 * succeeds and the reconnect banner is never on screen long enough to assert.
 *
 * Bring the fixture up alongside the `agents` service:
 * ```
 * docker compose -f tests/docker/docker-compose.yml up -d --build agents network-fault-proxy
 * ```
 *
 * Cleartext HTTP to the control API is why `app2/src/debug/AndroidManifest.xml`
 * sets `usesCleartextTraffic` — debug source set only.
 */
class ToxiproxyControl(
    private val apiPort: Int = apiPortArg(),
    private val listen: String = CONTAINER_LISTEN,
    private val upstream: String = CONTAINER_UPSTREAM,
) {

    /** Toxiproxy's own view of the proxy — the oracle for "the cut really engaged". */
    data class ProxyState(val enabled: Boolean, val listen: String, val upstream: String)

    /**
     * (Re)creates the proxy, enabled, so a journey never inherits a previous
     * run's toxics or a left-over disabled proxy.
     */
    fun reset() {
        runCatching { request("DELETE", "/proxies/$PROXY_NAME", null) }
        request(
            "POST",
            "/proxies",
            """{"name":"$PROXY_NAME","listen":"$listen","upstream":"$upstream","enabled":true}""",
        )
    }

    /** Drops every live connection and refuses new ones until [enable]. */
    fun disable() {
        request("POST", "/proxies/$PROXY_NAME", """{"enabled":false}""")
    }

    /** Restores the link. */
    fun enable() {
        request("POST", "/proxies/$PROXY_NAME", """{"enabled":true}""")
    }

    /**
     * Reads the state back, so an HTTP 2xx alone can never stand in for "the
     * link is actually cut" — the same trap the pre-rewrite fault journeys had
     * to be hardened against.
     */
    fun state(): ProxyState {
        val json = JSONObject(request("GET", "/proxies/$PROXY_NAME", null))
        return ProxyState(
            enabled = json.getBoolean("enabled"),
            listen = json.getString("listen"),
            upstream = json.getString("upstream"),
        )
    }

    private fun request(method: String, path: String, body: String?): String {
        val url = URL("http://$API_HOST:$apiPort$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
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
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8)
                .use { it.write(body) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(code in 200..299) {
            "toxiproxy $method $path failed: HTTP $code $response. Is the fixture up? " +
                "`docker compose -f tests/docker/docker-compose.yml up -d --build agents " +
                "network-fault-proxy`"
        }
        return response
    }

    companion object {
        /** The emulator's route to the host machine's loopback, as everywhere else. */
        const val API_HOST: String = "10.0.2.2"

        const val SINGLE_LANE_AGENTS_PORT: Int = 2222
        const val SINGLE_LANE_FAULT_SSH_PORT: Int = 2228
        const val SINGLE_LANE_API_PORT: Int = 8474

        /** Container-internal listen/upstream; the host publish is the fault SSH port. */
        const val CONTAINER_LISTEN: String = "0.0.0.0:2228"
        const val CONTAINER_UPSTREAM: String = "agents:22"

        private const val PROXY_NAME: String = "agents_ssh"

        /**
         * The port the APP dials to reach the fixture THROUGH the proxy.
         *
         * A `--pool` lane claims its own agents port and brings up its own
         * proxy, so both the SSH and the API port are derived from it rather
         * than pinned — a lane sharing the single-lane 2228/8474 proxy would be
         * attaching to a sibling lane's fixture. The offsets match
         * `scripts/lib/agents-pool.sh`.
         */
        fun faultSshPort(agentsPort: Int = AgentsFixture.port): Int =
            if (agentsPort == SINGLE_LANE_AGENTS_PORT) SINGLE_LANE_FAULT_SSH_PORT
            else agentsPort + 10

        fun defaultApiPort(agentsPort: Int = AgentsFixture.port): Int =
            if (agentsPort == SINGLE_LANE_AGENTS_PORT) SINGLE_LANE_API_PORT
            else SINGLE_LANE_API_PORT + (agentsPort - SINGLE_LANE_AGENTS_PORT)

        /** Overridable for a manual run against a proxy on a non-standard port. */
        private fun instrumentationPort(key: String): Int? =
            InstrumentationRegistry.getArguments().getString(key)?.trim()?.toIntOrNull()

        fun faultSshPortArg(): Int = instrumentationPort("faultSshPort") ?: faultSshPort()

        fun apiPortArg(): Int = instrumentationPort("toxiproxyApiPort") ?: defaultApiPort()
    }
}
