package com.pocketshell.next.sync

/**
 * Test doubles shared by the sync suite (issue #2633): a scripted HTTP client
 * that records every request, and an in-memory token store.
 *
 * Deliberately NOT a mocking framework. The assertions that matter here are
 * about the exact bytes of a request — that `client_secret` is absent, that
 * `code_verifier` is present, that the Bearer header carries the refreshed
 * token — and a recorded request list says that plainly.
 */
class RecordingSyncHttpClient(
    private val responder: (SyncHttpRequest, Int) -> SyncHttpResponse,
) : SyncHttpClient {

    val requests: MutableList<SyncHttpRequest> = mutableListOf()

    override fun send(request: SyncHttpRequest): SyncHttpResponse {
        requests += request
        return responder(request, requests.size - 1)
    }

    /** The form body of request [index], decoded back into a map. */
    fun formOf(index: Int): Map<String, String> =
        requests[index].body.orEmpty()
            .split("&")
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val (key, value) = pair.split("=", limit = 2)
                decode(key) to decode(value)
            }

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())

    companion object {
        /** Replies with [responses] in order, repeating the last one. */
        fun scripted(vararg responses: SyncHttpResponse): RecordingSyncHttpClient =
            RecordingSyncHttpClient { _, index -> responses[minOf(index, responses.size - 1)] }
    }
}

class InMemorySyncTokenStore(initial: StoredAuth? = null) : SyncTokenStore {
    var stored: StoredAuth? = initial
        private set

    var saves: Int = 0
        private set

    override fun load(): StoredAuth? = stored

    override fun save(auth: StoredAuth) {
        stored = auth
        saves += 1
    }

    override fun clear() {
        stored = null
    }
}

/** A syntactically real, cryptographically meaningless ID token. */
fun fakeIdToken(sub: String = "sub-123", email: String? = "person@example.com", exp: Long? = 1_700_000_000): String {
    val claims = buildString {
        append("""{"sub":"$sub"""")
        if (email != null) append(""","email":"$email"""")
        if (exp != null) append(""","exp":$exp""")
        append("}")
    }
    val payload = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(claims.toByteArray(Charsets.UTF_8))
    return "header.$payload.signature"
}

fun tokenResponse(idToken: String, refreshToken: String? = null, expiresIn: Long = 3600): SyncHttpResponse {
    val json = buildString {
        append("""{"id_token":"$idToken","expires_in":$expiresIn""")
        if (refreshToken != null) append(""","refresh_token":"$refreshToken"""")
        append("}")
    }
    return SyncHttpResponse(200, json)
}
