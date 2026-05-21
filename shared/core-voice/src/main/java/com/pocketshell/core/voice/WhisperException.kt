package com.pocketshell.core.voice

/**
 * Sealed hierarchy covering every failure mode surfaced by
 * [OkHttpWhisperClient.transcribe].
 *
 * Callers can `when`-branch exhaustively to drive UI:
 *   - [Auth] -> prompt the user to fix their API key
 *   - [RateLimited] -> back off and retry (Whisper may also send a
 *     `Retry-After` header — we expose [retryAfterSeconds] when present)
 *   - [Server] -> transient OpenAI-side failure; retry with backoff is fine
 *   - [Network] -> wraps the underlying [java.io.IOException] (DNS, TLS,
 *     timeout, ...)
 *   - [Parse] -> the response wasn't the JSON we expected. Usually means the
 *     endpoint or response format changed; surface a diagnostic message
 *     instead of pretending the transcription succeeded.
 *
 * The original [Throwable] (if any) is preserved on [Throwable.cause] for
 * logging.
 */
public sealed class WhisperException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** HTTP 401 — bad or missing API key. */
    public class Auth(message: String, cause: Throwable? = null) : WhisperException(message, cause)

    /**
     * HTTP 429 — quota or per-minute rate limit exceeded.
     *
     * @param retryAfterSeconds value of the `Retry-After` header if OpenAI
     *   sent one, otherwise `null`. Callers can use it to schedule a retry.
     */
    public class RateLimited(
        message: String,
        public val retryAfterSeconds: Long? = null,
        cause: Throwable? = null,
    ) : WhisperException(message, cause)

    /** HTTP 5xx — transient server-side failure. */
    public class Server(message: String, public val statusCode: Int, cause: Throwable? = null) :
        WhisperException(message, cause)

    /** Anything else — IO failure, DNS, TLS, timeout, unexpected status. */
    public class Network(message: String, cause: Throwable? = null) : WhisperException(message, cause)

    /** Response was 2xx but the body wasn't the expected `{"text": "..."}` JSON. */
    public class Parse(message: String, cause: Throwable? = null) : WhisperException(message, cause)
}
