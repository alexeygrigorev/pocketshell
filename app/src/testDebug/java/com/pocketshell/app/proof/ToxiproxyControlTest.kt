package com.pocketshell.app.proof

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun stateReadsTheIndependentToxiproxyOracle() {
        val transport = RecordingTransport(
            responses = mapOf(
                RecordedRequest("GET", "/proxies/agents_ssh", null) to
                    """{"name":"agents_ssh","listen":"0.0.0.0:2228","upstream":"agents:22","enabled":false}""",
            ),
        )

        assertEquals(
            ToxiproxyControl.ProxyState(
                enabled = false,
                listen = "0.0.0.0:2228",
                upstream = "agents:22",
            ),
            ToxiproxyControl(baseUrl = "http://unused", transport = transport).state(),
        )
    }

    @Test
    fun disabledScopeOrdersRealCutBeforeCaptureAndRestoresAfterward() {
        val events = mutableListOf<String>()
        val transport = RecordingTransport { request ->
            events += if (request.body == """{"enabled":false}""") "disable" else "enable"
        }

        runBlocking {
            ToxiproxyControl(baseUrl = "http://unused", transport = transport)
                .withDisabledProxy {
                    events += "assert-and-capture"
                }
        }

        assertEquals(listOf("disable", "assert-and-capture", "enable"), events)
    }

    @Test
    fun disabledScopeRestoresProxyWhenCaptureAssertionFails() {
        val events = mutableListOf<String>()
        val transport = RecordingTransport { request ->
            events += if (request.body == """{"enabled":false}""") "disable" else "enable"
        }

        assertThrows(AssertionError::class.java) {
            runBlocking {
                ToxiproxyControl(baseUrl = "http://unused", transport = transport)
                    .withDisabledProxy {
                        events += "assert-and-capture"
                        throw AssertionError("visible pill missing")
                    }
            }
        }

        assertEquals(listOf("disable", "assert-and-capture", "enable"), events)
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

    private class RecordingTransport(
        private val responses: Map<RecordedRequest, String> = emptyMap(),
        private val onRequest: (RecordedRequest) -> Unit = {},
    ) : ToxiproxyTransport {
        val requests = mutableListOf<RecordedRequest>()

        override fun request(method: String, path: String, body: String?): String {
            val request = RecordedRequest(method, path, body)
            requests += request
            onRequest(request)
            return responses[request] ?: "{}"
        }
    }
}
