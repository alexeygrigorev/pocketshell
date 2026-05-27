package com.pocketshell.core.voice

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Arrays
import java.util.concurrent.TimeUnit

/**
 * Drives [OkHttpWhisperClient] against a local [MockWebServer].
 *
 * Tests are scoped to the contract callers actually depend on:
 *  - Success → returns the parsed text
 *  - Multipart request shape is right (model, language, file, auth header)
 *  - HTTP failures map to the right [WhisperException] subtype with the
 *    expected metadata (status code, Retry-After)
 *  - Malformed success body produces [WhisperException.Parse]
 */
class OkHttpWhisperClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpWhisperClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Tight timeouts so the suite stays snappy if the server hangs.
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        client = OkHttpWhisperClient(
            apiKey = "sk-test-key".toCharArray(),
            baseUrl = server.url("/v1").toString(),
            client = http,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun success_returns_transcribed_text() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text": "hello world"}"""),
        )

        val result = client.transcribe(audioBytes("xyz"), language = "en")

        assertTrue("expected success, got $result", result.isSuccess)
        assertEquals("hello world", result.getOrNull())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/audio/transcriptions", recorded.path)
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))

        val body = recorded.body.readUtf8()
        assertTrue("model field missing: $body", body.contains("name=\"model\""))
        assertTrue("model value missing: $body", body.contains("whisper-1"))
        assertTrue("file part missing: $body", body.contains("filename=\"audio.wav\""))
        assertTrue("language field missing: $body", body.contains("name=\"language\""))
        assertTrue("language value missing: $body", body.contains("\r\n\r\nen\r\n"))
        // Sanity: the literal API key must not leak into logs / repr; it's
        // only sent as an Authorization header. We can't intercept the
        // recorder's logs from here, but we can at least verify it doesn't
        // appear unexpectedly in the multipart body.
        assertFalse("api key leaked into body: $body", body.contains("sk-test-key"))
    }

    @Test
    fun success_without_language_omits_field() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hi"}"""))

        val result = client.transcribe(audioBytes("a"))

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertFalse(
            "language part should be absent when null was passed: $body",
            body.contains("name=\"language\""),
        )
    }

    @Test
    fun http_401_maps_to_auth_exception() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key"}}"""),
        )

        val result = client.transcribe(audioBytes("a"))

        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("expected Auth, got ${ex!!::class.simpleName}", ex is WhisperException.Auth)
    }

    @Test
    fun http_429_maps_to_rate_limited_with_retry_after() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "42")
                .setBody("""{"error":{"message":"slow down"}}"""),
        )

        val result = client.transcribe(audioBytes("a"))

        val ex = result.exceptionOrNull()
        assertTrue(ex is WhisperException.RateLimited)
        assertEquals(42L, (ex as WhisperException.RateLimited).retryAfterSeconds)
    }

    @Test
    fun http_429_without_retry_after_header_still_classifies() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("nope"))

        val result = client.transcribe(audioBytes("a"))

        val ex = result.exceptionOrNull()
        assertTrue(ex is WhisperException.RateLimited)
        assertNull((ex as WhisperException.RateLimited).retryAfterSeconds)
    }

    @Test
    fun http_503_maps_to_server_with_status_code() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream"))

        val result = client.transcribe(audioBytes("a"))

        val ex = result.exceptionOrNull()
        assertTrue(ex is WhisperException.Server)
        assertEquals(503, (ex as WhisperException.Server).statusCode)
    }

    @Test
    fun success_with_malformed_body_maps_to_parse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"oops":"no text key here"}"""),
        )

        val result = client.transcribe(audioBytes("a"))

        val ex = result.exceptionOrNull()
        assertTrue("expected Parse, got ${ex?.let { it::class.simpleName }}", ex is WhisperException.Parse)
    }

    @Test
    fun classifies_http_failures_at_unit_level() {
        // Direct unit assertion on the classifier — independent of MockWebServer.
        val headers = okhttp3.Headers.headersOf("Retry-After", "10")
        assertTrue(classifyHttpFailure(400, okhttp3.Headers.headersOf(), "") is WhisperException.Transport)
        assertTrue(classifyHttpFailure(401, okhttp3.Headers.headersOf(), "") is WhisperException.Auth)
        assertTrue(classifyHttpFailure(403, okhttp3.Headers.headersOf(), "") is WhisperException.Auth)
        val rate = classifyHttpFailure(429, headers, "")
        assertTrue(rate is WhisperException.RateLimited)
        assertEquals(10L, (rate as WhisperException.RateLimited).retryAfterSeconds)
        val server = classifyHttpFailure(500, okhttp3.Headers.headersOf(), "")
        assertTrue(server is WhisperException.Server)
        assertEquals(500, (server as WhisperException.Server).statusCode)
        assertTrue(classifyHttpFailure(599, okhttp3.Headers.headersOf(), "") is WhisperException.Server)
    }

    @Test
    fun deeply_nested_json_response_maps_to_parse() = runTest {
        // 200 nested objects — well beyond MAX_JSON_DEPTH (16). The bounding
        // pre-scan must reject this before extractTextField ever runs.
        val deep = "{".repeat(200) + "\"text\":\"x\"" + "}".repeat(200)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(deep),
        )

        val result = client.transcribe(audioBytes("a"))

        val ex = result.exceptionOrNull()
        assertTrue(
            "expected Parse, got ${ex?.let { it::class.simpleName }}",
            ex is WhisperException.Parse,
        )
        assertTrue(
            "Parse message should mention the depth cap, got: ${ex?.message}",
            ex?.message?.contains("depth", ignoreCase = true) == true,
        )
    }

    @Test
    fun deeply_nested_arrays_also_blocked() {
        // The depth-bounded scanner counts `[` the same as `{`; a deeply
        // nested *array* alone must trip the cap too.
        val deepArr = "[".repeat(MAX_JSON_DEPTH + 1) + "]".repeat(MAX_JSON_DEPTH + 1)
        val thrown = assertThrows(WhisperException.Parse::class.java) {
            assertJsonDepthBounded(deepArr, MAX_JSON_DEPTH)
        }
        assertTrue(thrown.message!!.contains("depth"))
    }

    @Test
    fun depth_bounded_passes_normal_payload() {
        // Whisper's documented response shape parses cleanly at any cap.
        // Also exercise the string-skipping path: a brace inside a quoted
        // value must not be counted.
        assertJsonDepthBounded("""{"text":"a { b } c [ d ]"}""")
        assertJsonDepthBounded(
            """{"task":"transcribe","text":"x","segments":[{"id":0,"text":"a"}]}""",
        )
    }

    @Test
    fun depth_bounded_handles_escaped_quote_in_string() {
        // `\"` inside a string literal must NOT terminate the string scan.
        // Otherwise the parser would resume counting braces inside content.
        assertJsonDepthBounded("""{"text":"she said \"hi\" "}""")
    }

    @Test
    fun api_key_char_array_is_defensively_copied() = runTest {
        // Construct with a CharArray, zero it, then send a request and verify
        // the Authorization header is still correct. Proves the client kept
        // its own copy.
        val key = "sk-zero-me".toCharArray()
        val zeroingClient = OkHttpWhisperClient(
            apiKey = key,
            baseUrl = server.url("/v1").toString(),
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        Arrays.fill(key, ' ')

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))
        val result = zeroingClient.transcribe(audioBytes("a"))
        assertTrue("expected success, got $result", result.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-zero-me", recorded.getHeader("Authorization"))
    }

    @Test
    fun extracts_text_field_with_escapes() {
        assertEquals(
            "she said \"hi\"\nthen left",
            extractTextField("""{"text": "she said \"hi\"\nthen left"}"""),
        )
        assertEquals("plain", extractTextField("""{"task":"transcribe","text":"plain","duration":1.2}"""))
        assertNull(extractTextField("""{"oops":1}"""))
        // Literal Cyrillic char (passes through UTF-8 unchanged).
        assertEquals("дa", extractTextField("""{"text":"дa"}"""))
        // `\u` escape sequence decoded: U+0434 = 'д'. Built via string
        // concatenation so the Kotlin compiler doesn't interpret `\u` itself.
        val backslashU = "\\" + "u0434"
        assertEquals("дa", extractTextField("{\"text\":\"" + backslashU + "a\"}"))
    }

    @Test
    fun successful_call_records_cost_with_snapshot_price() = runTest {
        // 16 kHz mono 16-bit PCM, 5 seconds of silence → 160 000 bytes
        // PCM + 44 byte RIFF header. The recorder is the seam that issue
        // #181 introduces; on success we expect exactly one record with
        // the snapshot unit-cost from the bundled catalogue.
        val wav = buildWav(
            pcm = ByteArray(16_000 * 2 * 5),
            sampleRateHz = 16_000,
            bitsPerSample = 16,
            channels = 1,
        )

        val captured = mutableListOf<AiCostRecord>()
        val recorder = object : AiCostRecorder {
            override suspend fun record(record: AiCostRecord) {
                captured += record
            }
        }
        val pricingClient = OkHttpWhisperClient(
            apiKey = "sk-test".toCharArray(),
            baseUrl = server.url("/v1").toString(),
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
            priceCatalogue = PriceCatalogue.fromBundledResource(),
            costRecorder = recorder,
            clock = { 123_456_789L },
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello"}"""))

        val result = pricingClient.transcribe(wav, language = "en")
        assertTrue("expected success, got $result", result.isSuccess)

        assertEquals(1, captured.size)
        val row = captured.single()
        assertEquals("openai", row.provider)
        assertEquals("whisper", row.feature)
        assertEquals(5L, row.inputUnits)
        assertEquals(5L, row.outputUnits) // "hello" = 5 chars
        // Whisper @ $0.006/min = 10 millicents / second; 5 seconds = 50.
        assertEquals(10L, row.unitCostUsdMillicents)
        assertEquals(50L, row.computedCostUsdMillicents)
        assertEquals(123_456_789L, row.timestampMillis)
    }

    @Test
    fun failed_call_does_not_record_cost() = runTest {
        val captured = mutableListOf<AiCostRecord>()
        val recorder = object : AiCostRecorder {
            override suspend fun record(record: AiCostRecord) {
                captured += record
            }
        }
        val pricingClient = OkHttpWhisperClient(
            apiKey = "sk-test".toCharArray(),
            baseUrl = server.url("/v1").toString(),
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
            costRecorder = recorder,
        )

        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))

        val result = pricingClient.transcribe(audioBytes("a"))
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(
            "no cost row should be recorded for a 4xx Whisper failure",
            captured.isEmpty(),
        )
    }

    @Test
    fun recorder_throwing_does_not_break_transcription() = runTest {
        val recorder = object : AiCostRecorder {
            override suspend fun record(record: AiCostRecord) {
                throw RuntimeException("recorder went boom")
            }
        }
        val pricingClient = OkHttpWhisperClient(
            apiKey = "sk-test".toCharArray(),
            baseUrl = server.url("/v1").toString(),
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
            costRecorder = recorder,
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))

        val result = pricingClient.transcribe(audioBytes("a"))
        // Even though the recorder threw, the transcription is reported
        // as success — cost tracking must never block the user.
        assertTrue("expected success despite recorder error, got $result", result.isSuccess)
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun audio_duration_from_wav_handles_canonical_header() {
        // 16 kHz mono 16-bit, 3 seconds of silence → 3 seconds rounded.
        val wav = buildWav(
            pcm = ByteArray(16_000 * 2 * 3),
            sampleRateHz = 16_000,
            bitsPerSample = 16,
            channels = 1,
        )
        assertEquals(3L, audioDurationSecondsFromWav(wav))

        // Empty / too-short input gives 0.
        assertEquals(0L, audioDurationSecondsFromWav(ByteArray(0)))
        assertEquals(0L, audioDurationSecondsFromWav(ByteArray(10)))
    }

    private fun audioBytes(seed: String): ByteArray =
        // Real WAV bytes aren't needed — the server is a mock and just echoes
        // the multipart wrapper. We hand the client a small payload to keep
        // the request body easy to inspect.
        seed.toByteArray()
}
