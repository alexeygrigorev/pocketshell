package com.pocketshell.core.terminal.selection

/**
 * Classification of a tapped `http(s)` terminal URL into "points at a port on
 * the SSH server (loopback)" vs "a real remote host the phone can open in a
 * browser" — issue #488.
 *
 * The motivating problem: an agent / dev server printed
 * `http://localhost:3000` (or `127.0.0.1:5173`, `0.0.0.0:8080`, `[::1]:9000`)
 * in the terminal. That URL is reachable *on the server*, not from the phone.
 * Tapping it should route into the port-forward flow (forward the port if it
 * isn't already, then open the working local URL) instead of firing a browser
 * `Intent.ACTION_VIEW` at an address the phone cannot reach.
 *
 * This file is the pure, Android-free classification core so it is unit-tested
 * on the JVM. The terminal surface / tmux screen consume [classifyLocalhostUrl]
 * on the URL-tap path to decide whether to take the forward route.
 */

/**
 * Host literals that mean "this machine" (the SSH server, from the terminal's
 * point of view). Compared case-insensitively. `0.0.0.0` is included because a
 * dev server that *binds* to `0.0.0.0` commonly prints that address as its URL
 * even though the user would dial it as `localhost` — from the phone's side it
 * is still a server-local port that must be forwarded.
 */
internal val LOOPBACK_HOST_LITERALS: Set<String> = setOf(
    "localhost",
    "127.0.0.1",
    "0.0.0.0",
    // IPv6 loopback, with and without the bracketed form the URL host carries.
    "::1",
    "[::1]",
)

/**
 * A server-local (loopback) URL the user tapped, decomposed into the parts the
 * forward flow needs.
 *
 * @property remotePort the port ON THE SERVER the URL targets. This is the port
 *   the forward flow checks/creates.
 * @property scheme `http` or `https`, lower-cased — preserved so the opened
 *   local URL keeps the original scheme.
 * @property pathAndQuery everything after the authority (`/foo?bar#baz`), or an
 *   empty string. Preserved so a deep link like `http://localhost:3000/admin`
 *   re-targets the local port without dropping the path.
 */
public data class LocalhostUrl(
    public val remotePort: Int,
    public val scheme: String,
    public val pathAndQuery: String,
) {
    /**
     * Rebuild the URL pointed at [localPort] on the phone's loopback so it can
     * be opened in a browser once the forward is up. Uses `127.0.0.1` rather
     * than the `localhost` literal because some Android browsers resolve
     * `localhost` oddly; `127.0.0.1` is unambiguous for the forwarded socket.
     */
    public fun toLocalUrl(localPort: Int): String =
        "$scheme://127.0.0.1:$localPort$pathAndQuery"
}

/**
 * A loopback host/port reference found inside plain terminal or conversation
 * text. [text] is the literal substring to underline/click; [localhostUrl]
 * carries the normalized forwarding target. Bare references
 * (`localhost:5173`) default to `http` when retargeted to the phone.
 */
public data class LocalhostPortReference(
    public val text: String,
    public val start: Int,
    public val endExclusive: Int,
    public val localhostUrl: LocalhostUrl,
)

/**
 * `true` if [host] is one of the loopback/server-local literals in
 * [LOOPBACK_HOST_LITERALS]. Case-insensitive; tolerates the bracketed IPv6
 * form. A real hostname (`example.com`, `myserver.internal`) returns `false`.
 */
public fun isLocalhostHost(host: String): Boolean =
    host.trim().lowercase() in LOOPBACK_HOST_LITERALS

/**
 * Classify a tapped `http(s)` URL or bare loopback `host:port` reference.
 * Returns a [LocalhostUrl] when the host is server-local (loopback) AND it
 * carries an explicit port, otherwise `null` (the caller opens it as a normal
 * browser link or ignores it).
 *
 * Why an explicit port is required: a server-local URL is only actionable for
 * the forward flow if we know which port to forward. `http://localhost/` with
 * no port has no actionable target here, so it falls back to the normal browser
 * route. In practice every dev-server URL the agent prints carries the port.
 *
 * Parsing is deliberately hand-rolled (not `java.net.URI`) so it stays in the
 * Android-free `core-terminal` test surface and tolerates the exact shapes that
 * appear in terminal output, including the bracketed IPv6 authority
 * `http://[::1]:9000/foo` and bare dev-server references like
 * `localhost:5173`.
 */
public fun classifyLocalhostReference(reference: String): LocalhostUrl? {
    val trimmed = reference.trim().trimEndLocalhostReferencePunctuation()
    if (trimmed.isEmpty()) return null

    val schemeSep = trimmed.indexOf("://")
    val scheme: String
    val afterScheme: String
    if (schemeSep > 0) {
        scheme = trimmed.substring(0, schemeSep).lowercase()
        if (scheme != "http" && scheme != "https") return null
        afterScheme = trimmed.substring(schemeSep + 3)
    } else {
        scheme = "http"
        afterScheme = trimmed
    }

    // Authority ends at the first '/', '?' or '#'.
    val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
    val pathAndQuery = if (authorityEnd < 0) "" else afterScheme.substring(authorityEnd)

    // Strip any userinfo (`user:pass@host:port`) — loopback dev URLs never have
    // it, but be defensive so `@` inside userinfo isn't mistaken for the host.
    val hostPort = authority.substringAfterLast('@')

    val (host, portText) = splitHostAndPort(hostPort) ?: return null
    if (!isLocalhostHost(host)) return null
    val port = portText?.toIntOrNull() ?: return null
    if (port !in 1..65_535) return null

    return LocalhostUrl(remotePort = port, scheme = scheme, pathAndQuery = pathAndQuery)
}

/**
 * Backwards-compatible name for existing URL-tap callers.
 */
public fun classifyLocalhostUrl(url: String): LocalhostUrl? =
    classifyLocalhostReference(url)

/**
 * Detect loopback host/port references inside plain text. This intentionally
 * does not match real hosts or schemeless domains: only localhost literals that
 * the phone cannot reach directly are emitted. Callers can turn each result
 * into a port-forward affordance.
 */
public fun detectLocalhostPortReferences(line: String): List<LocalhostPortReference> {
    if (line.isEmpty()) return emptyList()
    val out = mutableListOf<LocalhostPortReference>()
    val matcher = LOCALHOST_REFERENCE_PATTERN.matcher(line)
    while (matcher.find()) {
        var raw = matcher.group() ?: continue
        var endTrim = raw.length
        while (endTrim > 0 && raw[endTrim - 1] in LOCALHOST_REFERENCE_TRAILING_PUNCTUATION) {
            endTrim--
        }
        if (endTrim <= 0) continue
        if (endTrim != raw.length) raw = raw.substring(0, endTrim)
        val local = classifyLocalhostReference(raw) ?: continue
        out += LocalhostPortReference(
            text = raw,
            start = matcher.start(),
            endExclusive = matcher.start() + raw.length,
            localhostUrl = local,
        )
    }
    return out
}

/**
 * Split a `host:port` (or bracketed-IPv6 `[host]:port`) authority into its host
 * and optional port text. Returns `null` if the authority is malformed.
 */
private fun splitHostAndPort(hostPort: String): Pair<String, String?>? {
    if (hostPort.isEmpty()) return null
    if (hostPort.startsWith("[")) {
        // Bracketed IPv6: `[::1]:9000` or `[::1]`.
        val close = hostPort.indexOf(']')
        if (close < 0) return null
        val host = hostPort.substring(0, close + 1) // keep brackets for isLocalhostHost
        val rest = hostPort.substring(close + 1)
        val port = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1)
            else -> return null
        }
        return host to port
    }
    val colon = hostPort.lastIndexOf(':')
    return if (colon < 0) {
        hostPort to null
    } else {
        hostPort.substring(0, colon) to hostPort.substring(colon + 1)
    }
}

private fun String.trimEndLocalhostReferencePunctuation(): String {
    var end = length
    while (end > 0 && this[end - 1] in LOCALHOST_REFERENCE_TRAILING_PUNCTUATION) end--
    return substring(0, end)
}

/**
 * Loopback host/port references, with optional `http(s)://` scheme and optional
 * path/query/fragment tail. The leading negative lookbehind prevents matches
 * inside larger hostnames (`notlocalhost:3000`, `xlocalhost:3000`,
 * `foo.localhost:3000`) or after unsupported schemes (`ftp://localhost:21`).
 * The trailing lookahead requires a real delimiter after a bare port so larger
 * tokens (`localhost:5173abc`, `localhost:5173_ms`, `localhost:5173.evil`) do
 * not emit partial `localhost:5173` references.
 * The port is filtered by [classifyLocalhostReference].
 */
private val LOCALHOST_REFERENCE_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile(
    "(?<![\\w.-])(?<!://)(?:https?://)?" +
        "(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1\\])" +
        ":\\d{1,5}(?!\\d)(?:(?:[/?#][\\w./?=&%+#~@:!,;-]*)|" +
        "(?=$|[\\s,;:)\\]!?'\"><]|\\.(?:$|\\s|[)\\]!?'\"><,;:])))",
    java.util.regex.Pattern.CASE_INSENSITIVE,
)

private val LOCALHOST_REFERENCE_TRAILING_PUNCTUATION: Set<Char> = setOf(
    '.', ',', ';', ':', ')', ']', '!', '?', '\'', '"', '>', '<',
)
