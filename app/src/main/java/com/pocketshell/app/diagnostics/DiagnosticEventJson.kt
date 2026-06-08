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
    fun redact(fields: Map<String, Any?>): Map<String, Any?> =
        fields.mapKeys { (key, _) -> sanitizeMetadataKey(key) }
            .mapValues { (key, value) -> redactValue(key, value) }

    private fun redactValue(key: String, value: Any?): Any? {
        if (value == null) return null
        if (isSensitiveKey(key) || looksSensitive(value)) return REDACTED
        if (isStableContextKey(key)) return DiagnosticPrivacy.stableFingerprint(value)
        return when (value) {
            is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value
            is CharArray -> mapOf("chars" to value.size)
            is ByteArray -> mapOf("bytes" to value.size)
            is Collection<*> -> mapOf("count" to value.size)
            is Map<*, *> -> mapOf("count" to value.size)
            is Throwable -> value.javaClass.simpleName
            else -> value.toString()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .filterNot { it.isISOControl() }
                .take(MAX_VALUE_CHARS)
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

    private val STABLE_CONTEXT_KEY_SUFFIXES = setOf(
        "path",
        "directory",
        "folder",
        "filename",
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
