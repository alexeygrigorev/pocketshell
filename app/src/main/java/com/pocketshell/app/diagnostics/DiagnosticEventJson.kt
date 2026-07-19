package com.pocketshell.app.diagnostics

import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

data class DiagnosticsEvent(
    val sequence: Long,
    val wallClockTime: Instant,
    val monotonicTimestampNanos: Long,
    val category: String,
    val name: String,
    val metadata: Map<String, Any?> = emptyMap(),
    // Issue #1669: every event is version-stamped at record time so the field
    // connection log is self-describing — the maintainer can read WHICH build
    // produced a storm straight from the log, without a `git ls-remote` to guess
    // the phone's version (which is what today's forensics had to do). Defaulted
    // so historical lines / synthetic test events decode without a stamp.
    val versionName: String = "",
    val versionCode: Long = 0L,
)

data class DiagnosticEventFilter(
    val category: String? = null,
    val name: String? = null,
    val sinceSequenceExclusive: Long? = null,
    val maxEvents: Int? = null,
) {
    init {
        require(maxEvents == null || maxEvents > 0) { "maxEvents must be positive when set" }
    }

    internal fun matches(event: DiagnosticsEvent): Boolean =
        (category == null || event.category == category) &&
            (name == null || event.name == name) &&
            (sinceSequenceExclusive == null || event.sequence > sinceSequenceExclusive)

    internal fun limit(events: List<DiagnosticsEvent>): List<DiagnosticsEvent> =
        maxEvents?.let(events::takeLast) ?: events

    internal fun limitLines(lines: List<Pair<String, DiagnosticsEvent>>): List<Pair<String, DiagnosticsEvent>> =
        maxEvents?.let(lines::takeLast) ?: lines

    companion object {
        val All: DiagnosticEventFilter = DiagnosticEventFilter()

        fun recent(maxEvents: Int): DiagnosticEventFilter =
            DiagnosticEventFilter(maxEvents = maxEvents)
    }
}

internal object DiagnosticEventJson {
    fun encode(event: DiagnosticsEvent): String {
        val root = JSONObject()
            .put("sequence", event.sequence)
            .put("wallClockTime", DateTimeFormatter.ISO_INSTANT.format(event.wallClockTime))
            .put("monotonicTimestampNanos", event.monotonicTimestampNanos)
            .put("category", sanitizeToken(event.category))
            .put("name", sanitizeToken(event.name))
            // Issue #1669: top-level (not under `metadata`) so a forensic `jq`/
            // `head` sees the build on every line without descending into metadata.
            .put("versionName", event.versionName)
            .put("versionCode", event.versionCode)
        val metadata = JSONObject()
        event.metadata.toSortedMap().forEach { (key, value) ->
            metadata.put(sanitizeKey(key), JSONObject.wrap(value))
        }
        root.put("metadata", metadata)
        return root.toString()
    }

    fun decode(line: String): DiagnosticsEvent? = runCatching {
        val root = JSONObject(line)
        val metadataJson = root.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap {
            metadataJson.keys().forEach { key ->
                put(key, metadataJson.opt(key).takeUnless { it == JSONObject.NULL })
            }
        }
        DiagnosticsEvent(
            sequence = root.getLong("sequence"),
            wallClockTime = Instant.parse(root.getString("wallClockTime")),
            monotonicTimestampNanos = root.getLong("monotonicTimestampNanos"),
            category = root.getString("category"),
            name = root.getString("name"),
            metadata = metadata,
            versionName = root.optString("versionName", ""),
            versionCode = root.optLong("versionCode", 0L),
        )
    }.getOrNull()

    private fun sanitizeKey(value: String): String =
        value.trim()
            .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') ch else '_' }
            .joinToString("")
            .ifBlank { "field" }
            .take(MAX_KEY_CHARS)

    private fun sanitizeToken(value: String): String =
        value.trim()
            .replace('\n', '_')
            .replace('\r', '_')
            .replace('\t', '_')
            .filterNot { it.isISOControl() }
            .ifBlank { "unknown" }
            .take(MAX_TOKEN_CHARS)

    private const val MAX_KEY_CHARS = 64
    private const val MAX_TOKEN_CHARS = 80
}

internal object DiagnosticRedactor {
    /**
     * [category] is the diagnostic event's category (e.g. `connection`,
     * `reconnect`). It is threaded through so the connection/reconnect
     * cause-trail can SURFACE which exception the -CC reader hit — which the
     * blanket key-based redaction would otherwise drop to `[redacted]`, making a
     * `reader_exception` teardown undiagnosable (issue #1610). Rather than pass
     * the raw exception `message` (whose free text could carry a secret a caller
     * folded in), the surfaced value is a BOUNDED, ALLOWLISTED classification of
     * the failure shape — see [classifyFailureMessage]. For every other category
     * the `message` key stays fully redacted exactly as before.
     */
    fun redact(fields: Map<String, Any?>, category: String? = null): Map<String, Any?> =
        fields.mapKeys { (key, _) -> sanitizeMetadataKey(key) }
            .mapValues { (key, value) -> redactValue(key, value, category) }

    private fun redactValue(key: String, value: Any?, category: String?): Any? {
        if (value == null) return null
        // Surface WHICH exception the -CC reader hit for the connection/reconnect
        // cause-trail so a `reader_exception` teardown is diagnosable — but as a
        // fixed classification token, never raw message text, so no secret a
        // caller folded into the exception message can ever leak (#1610).
        if (isDiagnosableMessageKey(key, category)) {
            return classifyFailureMessage(value.toString())
        }
        if (isSensitiveKey(key) || looksSensitive(value)) return REDACTED
        // Only an identity STRING is fingerprinted. A boolean/number under an
        // identity-shaped key (`hasSession`, …) is not an identity: digesting it
        // would destroy the signal AND present a yes/no as an opaque identity —
        // two possible digests, trivially reversible, and a lie to the reader.
        if (isStableContextKey(key) && value !is Boolean && value !is Number) {
            return DiagnosticPrivacy.stableFingerprint(value)
        }
        return when (value) {
            is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value
            is CharArray -> mapOf("chars" to value.size)
            is ByteArray -> mapOf("bytes" to value.size)
            is Collection<*> -> mapOf("count" to value.size)
            is Map<*, *> -> mapOf("count" to value.size)
            is Throwable -> value.javaClass.simpleName
            else -> sanitizeFreeText(value.toString())
        }
    }

    private fun sanitizeFreeText(text: String): String =
        text.replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .filterNot { it.isISOControl() }
            .take(MAX_VALUE_CHARS)

    /**
     * True only for the exception-message key of a connection/reconnect
     * cause-trail event. Deliberately narrow: it opens ONLY the message field
     * and ONLY for the reconnect-diagnosis categories, never `command` /
     * `prompt` / `body` / `content`, and never for other categories.
     */
    private fun isDiagnosableMessageKey(key: String, category: String?): Boolean {
        val normalisedCategory = category?.lowercase() ?: return false
        if (normalisedCategory !in DIAGNOSABLE_MESSAGE_CATEGORIES) return false
        return key.lowercase() in DIAGNOSABLE_MESSAGE_KEYS
    }

    /**
     * Maps a connection/reconnect failure message to a BOUNDED, ALLOWLISTED
     * classification token (#1610). This is deliberately NOT the raw message and
     * NOT a scrubbed variant of it: the return value is ALWAYS one of the fixed
     * lowercase `[a-z_]+` tokens below and NEVER a substring of [text]. That
     * gives a categorical secret-safety guarantee — an `authorization:` header, a
     * PEM private-key body, a high-entropy token, or any other secret a caller
     * folded into the exception message can never surface, because no part of
     * [text] is ever emitted. What the reconnect diagnosis needs (WHICH
     * SSHException the -CC reader hit — read-timeout vs reset vs EOF vs
     * channel-closed) is preserved; the risky free text is dropped. An
     * unrecognised shape collapses to [OTHER_FAILURE].
     *
     * This is strictly stronger than the old unconditional `[redacted]` for the
     * `message` key: it leaks strictly less (a fixed enum, never user data) while
     * surfacing the failure shape the maintainer needs to root-cause the mobile
     * -CC teardown.
     */
    private fun classifyFailureMessage(text: String): String {
        val lower = text.lowercase()
        return when {
            "broken pipe" in lower -> "broken_pipe"
            "reset by peer" in lower || "connection reset" in lower -> "connection_reset"
            "connection refused" in lower -> "connection_refused"
            "timed out" in lower || "timeout" in lower -> "timeout"
            "no route to host" in lower -> "no_route_to_host"
            "network is unreachable" in lower || "network unreachable" in lower -> "network_unreachable"
            "channel" in lower && ("closed" in lower || "not open" in lower || "eof" in lower) ->
                "channel_closed"
            "premature eof" in lower || "unexpected eof" in lower ||
                "end of file" in lower || "eof" in lower -> "eof"
            "socket closed" in lower || "connection closed" in lower ||
                "closed by" in lower || "disconnect" in lower -> "connection_closed"
            "auth" in lower && "fail" in lower -> "auth_failure"
            "host key" in lower || "verification failed" in lower -> "host_key_mismatch"
            else -> OTHER_FAILURE
        }
    }

    private fun sanitizeMetadataKey(key: String): String =
        key.trim()
            .ifBlank { "field" }
            .take(MAX_KEY_CHARS)

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        if (lower.endsWith("bytes") || lower.endsWith("count") || lower.endsWith("ms")) return false
        return SENSITIVE_KEY_MARKERS.any { marker -> lower.contains(marker) }
    }

    private fun isStableContextKey(key: String): Boolean {
        val normalised = key.lowercase().filter { it.isLetterOrDigit() }
        if (normalised in STABLE_CONTEXT_KEYS) return true
        return STABLE_CONTEXT_KEY_SUFFIXES.any { suffix -> normalised.endsWith(suffix) }
    }

    private fun looksSensitive(value: Any?): Boolean {
        val text = value?.toString() ?: return false
        if (text.length < 8) return false
        val lower = text.lowercase()
        return SENSITIVE_VALUE_MARKERS.any { marker -> lower.contains(marker) }
    }

    private const val MAX_KEY_CHARS = 64
    private const val MAX_VALUE_CHARS = 160
    private const val REDACTED = "[redacted]"
    private const val OTHER_FAILURE = "other"

    /**
     * Categories whose exception `message` is surfaced (secret-scrubbed) for
     * reconnect diagnosis (#1610): the connection lifecycle events and the
     * [ReconnectCauseTrail]. Everything else keeps `message` fully redacted.
     */
    private val DIAGNOSABLE_MESSAGE_CATEGORIES = setOf(
        "connection",
        ReconnectCauseTrail.CATEGORY,
    )

    private val DIAGNOSABLE_MESSAGE_KEYS = setOf(
        "message",
        "exceptionmessage",
    )

    private val STABLE_CONTEXT_KEYS = setOf(
        "host",
        "hostname",
        "hostlabel",
        "user",
        "username",
        "session",
        "sessionname",
        "path",
        "cwd",
        "directory",
        "folder",
        "filename",
    )

    /**
     * Matched against the key with non-alphanumerics stripped, so `pausedSession`
     * / `currentSession` / `intentSession` / `originSession` / `activeSession` all
     * fingerprint via the `session` suffix.
     *
     * The `session` suffix is the #1639 M1 fix. The rule used to match the EXACT
     * key `session` only, so the qualified variants fell through to
     * [sanitizeFreeText] and emitted **raw tmux session names — which are
     * directory paths by construction** ([com.pocketshell.app.tmux.SessionNameDerivation])
     * — into the `reconnect/cause_trail`, i.e. into a log that is mirrored to the
     * host AND routinely quoted into PUBLIC GitHub issues by agents. It is a
     * suffix rule rather than five literals so a NEW `*Session` field added
     * tomorrow cannot silently opt out of redaction, which is the failure mode
     * #1639 called out (the default-allow redactor). #1642's registry-based
     * default-deny (slice 7) supersedes this heuristic; until then this is the
     * line, and `Issue1642ConnectionMirrorTest` asserts it as a property over the
     * whole mirrored document rather than per key.
     */
    private val STABLE_CONTEXT_KEY_SUFFIXES = setOf(
        "path",
        "directory",
        "folder",
        "filename",
        "session",
    )

    private val SENSITIVE_KEY_MARKERS = listOf(
        "apikey",
        "api_key",
        "auth",
        "body",
        "command",
        "content",
        "cookie",
        "credential",
        "keypath",
        "message",
        "passphrase",
        "password",
        "privatekey",
        "prompt",
        "query",
        "secret",
        "token",
        "uri",
    )

    private val SENSITIVE_VALUE_MARKERS = listOf(
        "authorization:",
        "bearer ",
        "github_pat_",
        "password=",
        "sk-",
        "-----begin ",
    )
}

internal object DiagnosticPrivacy {
    fun stableFingerprint(value: Any?): String {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isBlank()) return "sha256:empty"
        val normalised = text.lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalised.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "sha256:${digest.take(FINGERPRINT_HEX_CHARS)}"
    }

    fun hostKind(host: String?): String {
        val value = host?.trim()?.lowercase().orEmpty()
        if (value.isBlank()) return "unknown"
        if (value == "localhost" || value == "::1" || value.startsWith("127.")) return "loopback"
        if (isPrivateIpv4(value)) return "private_ipv4"
        if (IPV4_REGEX.matches(value)) return "public_ipv4"
        if (":" in value && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' }) {
            return "ipv6"
        }
        return "dns"
    }

    fun connectionContextFields(
        host: String,
        user: String? = null,
        session: String? = null,
    ): List<Pair<String, Any?>> =
        buildList {
            add("hostFingerprint" to stableFingerprint(host))
            add("hostKind" to hostKind(host))
            user?.let { add("userFingerprint" to stableFingerprint(it)) }
            session?.let { add("sessionFingerprint" to stableFingerprint(it)) }
        }

    private fun isPrivateIpv4(value: String): Boolean {
        val parts = value.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31 ||
            parts[0] == 169 && parts[1] == 254
    }

    private const val FINGERPRINT_HEX_CHARS = 12
    private val IPV4_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
