package com.pocketshell.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text via OpenAI's `whisper-1` model on the Audio Transcriptions
 * endpoint.
 *
 * The public API is intentionally a single suspend function so callers don't
 * have to learn OkHttp's threading model. Implementations are responsible for
 * mapping transport / HTTP failures to a [WhisperException] subtype — the
 * UI layer drives state off the exception class, not off raw HTTP codes.
 */
public interface WhisperClient {
    /**
     * Transcribe the given audio bytes.
     *
     * @param audio raw audio file contents (WAV / MP3 / m4a — Whisper sniffs
     *   the format itself; this module produces 16 kHz mono WAV via
     *   [AudioRecorder.stop]).
     * @param language optional ISO-639-1 hint (e.g. `"en"`, `"ru"`). Improves
     *   accuracy and reduces cost when known. `null` lets Whisper auto-detect.
     * @return [Result.success] with the transcribed text, or [Result.failure]
     *   carrying a [WhisperException] subtype.
     */
    public suspend fun transcribe(audio: ByteArray, language: String? = null): Result<String>
}

/**
 * Default [WhisperClient] implementation.
 *
 * Posts a `multipart/form-data` body to
 * `<baseUrl>/audio/transcriptions` with:
 *  - `model = "whisper-1"`
 *  - `file = audio.wav` (the [audio] bytes)
 *  - `language = <iso code>` when provided
 *
 * Reads the JSON response `{"text": "..."}` and returns the text.
 *
 * The default [OkHttpClient] uses generous timeouts because a long voice
 * recording can take several seconds to upload and transcribe — Whisper isn't
 * streaming, so the whole round-trip blocks. Callers wanting a shared
 * connection pool / custom interceptors can pass their own client.
 *
 * @param apiKey the user-supplied OpenAI API key (sent as
 *   `Authorization: Bearer ...`). Stored as a [CharArray] rather than a
 *   [String] so the caller can zero the buffer once the client is no longer
 *   needed (`Arrays.fill(array, ' ')`). The client makes a defensive copy
 *   on construction — modifying the caller's array does not retroactively
 *   change requests already in flight. The [String] used to build the
 *   Authorization header lives only for the duration of a single `transcribe`
 *   call. Never logged.
 * @param baseUrl override for self-hosted / proxied endpoints. Defaults to
 *   the official API.
 * @param client optional custom OkHttp client; the default has 15 s connect
 *   timeout, 60 s call timeout (cover long transcriptions).
 */
public class OkHttpWhisperClient(
    apiKey: CharArray,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val client: OkHttpClient = defaultClient(),
    private val priceCatalogue: PriceCatalogue = PriceCatalogue.fromBundledResource(),
    private val costRecorder: AiCostRecorder = AiCostRecorder.NoOp,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WhisperClient {

    // Defensive copy: callers who pass a `CharArray` typically want to zero it
    // soon after construction. Holding our own copy lets that work without
    // mutating in-flight headers.
    private val apiKey: CharArray = apiKey.copyOf()

    override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    audio.toRequestBody(WAV_MEDIA_TYPE),
                )
                .also { builder ->
                    if (!language.isNullOrBlank()) {
                        builder.addFormDataPart("language", language)
                    }
                }
                .build()

            // Build the Authorization header value in a local StringBuilder so
            // the plaintext key only materialises once, here, and the
            // resulting String is unreferenced as soon as the Request is built.
            val authHeader = StringBuilder("Bearer ").append(apiKey).toString()
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/audio/transcriptions")
                .addHeader("Authorization", authHeader)
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            classifyHttpFailure(response.code, response.headers, responseBody),
                        )
                    }
                    // Bound JSON nesting depth before scanning. A maliciously
                    // deep payload (`{{{{...}}}}`) could otherwise pin the
                    // parser thread on a pathological string scan; the cap
                    // also rejects responses that obviously aren't the flat
                    // `{"text": "..."}` shape OpenAI documents.
                    try {
                        assertJsonDepthBounded(responseBody, MAX_JSON_DEPTH)
                    } catch (parse: WhisperException.Parse) {
                        return@withContext Result.failure(parse)
                    }
                    val text = extractTextField(responseBody)
                        ?: return@withContext Result.failure(
                            WhisperException.Parse(
                                "Whisper response did not contain a `text` field",
                            ),
                        )

                    // Issue #181: log this call's snapshot price + computed
                    // cost. Done at success-only because Whisper documents
                    // that 4xx/5xx requests are not billed. Audio duration
                    // is derived from the WAV header — the same code that
                    // produced these bytes (AudioRecorder.wrapInWav) writes
                    // a canonical RIFF/WAVE header, so the math is exact.
                    // Errors here must never block the user — recordCostSafely
                    // swallows everything.
                    recordCostSafely(audio = audio, transcript = text)

                    Result.success(text)
                }
            } catch (io: IOException) {
                // OkHttp's IOException covers DNS, connect, TLS, read/write
                // timeouts, and cancellation. We wrap as Transport so callers
                // don't have to know about OkHttp's exception hierarchy.
                Result.failure(WhisperException.Transport(io.message ?: "Transport failure", io))
            }
        }

    private suspend fun recordCostSafely(audio: ByteArray, transcript: String) {
        try {
            val audioSeconds = audioDurationSecondsFromWav(audio)
            val unitCost = priceCatalogue.unitCost(PROVIDER_OPENAI, FEATURE_WHISPER)
            // `inputUnits * unitCost` is integer-exact: Whisper bills per
            // audio-second and our unit is millicents/second.
            val computedCost = audioSeconds * unitCost
            costRecorder.record(
                AiCostRecord(
                    timestampMillis = clock(),
                    provider = PROVIDER_OPENAI,
                    feature = FEATURE_WHISPER,
                    inputUnits = audioSeconds,
                    outputUnits = transcript.length.toLong(),
                    unitCostUsdMillicents = unitCost,
                    computedCostUsdMillicents = computedCost,
                    metadataJson = null,
                ),
            )
        } catch (_: Throwable) {
            // Cost tracking is best-effort: a failure here must never make
            // a successful transcription look broken to the user.
        }
    }

    private companion object {
        const val PROVIDER_OPENAI = "openai"
        const val FEATURE_WHISPER = "whisper"
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // `callTimeout` bounds the whole request including upload and
            // server-side transcription. Whisper can take a few seconds for
            // a long audio clip; 60s is enough headroom without leaking
            // forever on a hung server.
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Approximate audio duration in seconds for the canonical WAV produced by
 * [AudioRecorder.wrapInWav]. Reads the sample-rate, channel-count, and
 * bits-per-sample fields directly out of the RIFF header so the math is
 * exact for any reasonable PCM input — not just the 16 kHz mono 16-bit
 * shape `core-voice` records today.
 *
 * Returns `0` when the buffer is too short to be a valid WAV, when the
 * "data" chunk size is missing or invalid, or when the format declares a
 * zero block-align (would otherwise divide by zero). The Whisper
 * call-site treats the `0` as "we don't know how long the audio was" and
 * logs a zero-cost row — better than crashing the request.
 *
 * Exposed `internal` for direct unit testing.
 */
internal fun audioDurationSecondsFromWav(wav: ByteArray): Long {
    // Canonical RIFF/WAVE header is at least 44 bytes (see AudioRecorder.buildWav).
    if (wav.size < 44) return 0L
    if (!wav.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) return 0L
    if (!wav.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) return 0L

    fun le16(offset: Int): Int =
        (wav[offset].toInt() and 0xFF) or ((wav[offset + 1].toInt() and 0xFF) shl 8)
    fun le32(offset: Int): Long =
        ((wav[offset].toInt() and 0xFF).toLong()) or
            (((wav[offset + 1].toInt() and 0xFF).toLong()) shl 8) or
            (((wav[offset + 2].toInt() and 0xFF).toLong()) shl 16) or
            (((wav[offset + 3].toInt() and 0xFF).toLong()) shl 24)

    val channels = le16(22)
    val sampleRate = le32(24)
    val bitsPerSample = le16(34)
    val dataSize = le32(40)

    if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return 0L
    val bytesPerSecond = sampleRate * channels * bitsPerSample / 8L
    if (bytesPerSecond <= 0) return 0L

    // Round to nearest whole second so a 4.6 s recording bills as 5 s
    // (matching how Whisper rounds on its end — short clips are billed at
    // minimum granularity). Half-up rounding feels intuitive for the
    // cost-tracking surface: a "just under a second" recording still
    // shows as the unit the user expects.
    return (dataSize + bytesPerSecond / 2) / bytesPerSecond
}

/**
 * Map an HTTP failure (non-2xx) onto the right [WhisperException] subtype.
 *
 * Exposed at file scope (and `internal`) so the unit test can assert the
 * classification table directly without driving a full mock server for every
 * row.
 */
internal fun classifyHttpFailure(
    code: Int,
    headers: okhttp3.Headers,
    body: String,
): WhisperException = when (code) {
    401, 403 -> WhisperException.Auth("Whisper rejected credentials (HTTP $code): ${body.take(200)}")
    429 -> WhisperException.RateLimited(
        message = "Whisper rate limit hit (HTTP 429): ${body.take(200)}",
        retryAfterSeconds = headers["Retry-After"]?.toLongOrNull(),
    )
    in 500..599 -> WhisperException.Server(
        message = "Whisper server error (HTTP $code): ${body.take(200)}",
        statusCode = code,
    )
    else -> WhisperException.Transport(
        "Whisper returned unexpected HTTP $code: ${body.take(200)}",
    )
}

/**
 * Maximum JSON nesting depth we accept from the Whisper endpoint.
 *
 * Whisper's documented response is flat — a single object with a `text`
 * field and optional metadata. 16 levels is enough headroom for any
 * realistic future addition (segments-of-arrays-of-words is two levels)
 * while shutting the door on pathological inputs that could either pin the
 * scanner or, with a future JSON library swap, blow the stack.
 */
internal const val MAX_JSON_DEPTH: Int = 16

/**
 * Walk [json] once, tracking `{` / `[` nesting depth (ignoring braces inside
 * string literals), and throw [WhisperException.Parse] if depth ever exceeds
 * [maxDepth]. Treats input that opens-but-never-closes as in-bounds (the
 * subsequent `extractTextField` scan will surface that as a parse failure
 * for its own reasons).
 *
 * Exposed `internal` for direct testing.
 */
internal fun assertJsonDepthBounded(json: String, maxDepth: Int = MAX_JSON_DEPTH) {
    var depth = 0
    var i = 0
    val n = json.length
    while (i < n) {
        val c = json[i]
        when (c) {
            '"' -> {
                // Skip string literal content; quotes inside don't affect depth.
                i++
                while (i < n) {
                    val s = json[i]
                    if (s == '\\') {
                        // Skip the escape and the following character (which
                        // could itself be a `"` — we mustn't terminate on it).
                        i += 2
                        continue
                    }
                    if (s == '"') {
                        i++
                        break
                    }
                    i++
                }
                continue
            }
            '{', '[' -> {
                depth++
                if (depth > maxDepth) {
                    throw WhisperException.Parse(
                        "Whisper response exceeded JSON depth cap ($maxDepth)",
                    )
                }
            }
            '}', ']' -> {
                if (depth > 0) depth--
            }
            else -> Unit
        }
        i++
    }
}

/**
 * Pull the value of the top-level `"text"` field out of a Whisper JSON
 * response.
 *
 * Hand-rolled instead of pulling in a JSON library because:
 *  1. The response shape is fixed by OpenAI's API — `{"text": "..."}` plus
 *     optional metadata. We only need one field.
 *  2. Avoids a transitive `org.json` dependency that behaves differently
 *     between host JVM (absent) and Android (stub-only at `:test` time).
 *
 * Returns `null` if the field can't be located — the caller surfaces this as
 * [WhisperException.Parse]. Handles standard JSON escapes (`\"`, `\\`, `\n`,
 * `\r`, `\t`, `\/`, `\b`, `\f`, `\uXXXX`).
 *
 * Exposed `internal` for direct testing.
 */
internal fun extractTextField(json: String): String? {
    // Find the first `"text"` key. The OpenAI response always puts `text` at
    // the top level; scanning for the literal key is enough.
    val keyIndex = findUnescapedKey(json, "text") ?: return null

    // After the key we expect `: "value"` (with optional whitespace).
    var i = keyIndex
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != ':') return null
    i++
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != '"') return null
    i++ // past opening quote

    val out = StringBuilder()
    while (i < json.length) {
        val c = json[i]
        if (c == '"') return out.toString()
        if (c == '\\') {
            if (i + 1 >= json.length) return null
            when (val esc = json[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 5 >= json.length) return null
                    val hex = json.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    i += 4 // consume the 4 hex digits in addition to \u below
                }
                else -> {
                    // Unknown escape — keep the char to avoid losing data
                    out.append(esc)
                }
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    // Unterminated string.
    return null
}

private fun findUnescapedKey(json: String, key: String): Int? {
    val needle = "\"$key\""
    var from = 0
    while (true) {
        val idx = json.indexOf(needle, from)
        if (idx < 0) return null
        // Make sure the quote before `key` isn't escaped (e.g. inside a
        // larger string literal value containing `\"text\"`).
        if (idx == 0 || json[idx - 1] != '\\') {
            return idx + needle.length
        }
        from = idx + 1
    }
}
