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
 *   `Authorization: Bearer ...`). Never logged.
 * @param baseUrl override for self-hosted / proxied endpoints. Defaults to
 *   the official API.
 * @param client optional custom OkHttp client; the default has 15 s connect
 *   timeout, 60 s call timeout (cover long transcriptions).
 */
public class OkHttpWhisperClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val client: OkHttpClient = defaultClient(),
) : WhisperClient {

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

            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
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
                    val text = extractTextField(responseBody)
                        ?: return@withContext Result.failure(
                            WhisperException.Parse(
                                "Whisper response did not contain a `text` field",
                            ),
                        )
                    Result.success(text)
                }
            } catch (io: IOException) {
                // OkHttp's IOException covers DNS, connect, TLS, read/write
                // timeouts, and cancellation. We wrap as Network so callers
                // don't have to know about OkHttp's exception hierarchy.
                Result.failure(WhisperException.Network(io.message ?: "Network failure", io))
            }
        }

    private companion object {
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
    else -> WhisperException.Network(
        "Whisper returned unexpected HTTP $code: ${body.take(200)}",
    )
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
