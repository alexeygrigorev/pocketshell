package com.pocketshell.app.proof

import org.junit.Assert.assertEquals
import org.junit.Test

class ToxiproxyControlTest {

    @Test
    fun resetRecreatesTheAgentsProxy() {
        val transport = RecordingTransport()
        ToxiproxyControl(baseUrl = "http://unused", transport = transport).reset()

        // reset() has an ordering contract: the stale proxy MUST be DELETEd
        // before the fresh one is POSTed, so this stays an ordered assertion.
        assertEquals(
            listOf(
                RecordedRequest("DELETE", "/proxies/agents_ssh", null),
                RecordedRequest(
                    "POST",
                    "/proxies",
                    """{"name":"agents_ssh","listen":"0.0.0.0:2228","upstream":"agents:22","enabled":true}""",
                ),
            ),
            transport.requests,
        )
    }

    @Test
    fun blackholeAddsTimeoutToxicsInBothDirections() {
        val transport = RecordingTransport()
        ToxiproxyControl(baseUrl = "http://unused", transport = transport).addBlackhole()

        // The upstream and downstream timeout toxics are independent and
        // symmetric — their relative order is not part of the contract, so
        // assert the set of requests rather than an ordered list.
        assertEquals(
            setOf(
                RecordedRequest(
                    "POST",
                    "/proxies/agents_ssh/toxics",
                    """{"name":"blackhole_upstream","type":"timeout","stream":"upstream","toxicity":1.0,"attributes":{"timeout":0}}""",
                ),
                RecordedRequest(
                    "POST",
                    "/proxies/agents_ssh/toxics",
                    """{"name":"blackhole_downstream","type":"timeout","stream":"downstream","toxicity":1.0,"attributes":{"timeout":0}}""",
                ),
            ),
            transport.requests.toSet(),
        )
    }

    @Test
    fun disableAndEnableUseToxiproxyProxyToggle() {
        val transport = RecordingTransport()
        val control = ToxiproxyControl(baseUrl = "http://unused", transport = transport)

        control.disable()
        control.enable()

        assertEquals(
            listOf(
                RecordedRequest("POST", "/proxies/agents_ssh", """{"enabled":false}"""),
                RecordedRequest("POST", "/proxies/agents_ssh", """{"enabled":true}"""),
            ),
            transport.requests,
        )
    }

    @Test
    fun clearToxicsDeletesEveryKnownFaultModel() {
        val transport = RecordingTransport()
        ToxiproxyControl(baseUrl = "http://unused", transport = transport).clearToxics()

        // Every known fault model is DELETEd independently; the deletion order
        // is not part of the contract, so assert the set of DELETEs. This also
        // catches a missing toxic regardless of where it lands in the list.
        assertEquals(
            setOf(
                RecordedRequest("DELETE", "/proxies/agents_ssh/toxics/blackhole_upstream", null),
                RecordedRequest("DELETE", "/proxies/agents_ssh/toxics/blackhole_downstream", null),
                RecordedRequest("DELETE", "/proxies/agents_ssh/toxics/latency_upstream", null),
                RecordedRequest("DELETE", "/proxies/agents_ssh/toxics/latency_downstream", null),
                RecordedRequest("DELETE", "/proxies/agents_ssh/toxics/bandwidth_downstream", null),
            ),
            transport.requests.toSet(),
        )
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String?,
    )

    private class RecordingTransport : ToxiproxyTransport {
        val requests = mutableListOf<RecordedRequest>()

        override fun request(method: String, path: String, body: String?): String {
            requests += RecordedRequest(method, path, body)
            return "{}"
        }
    }
}
