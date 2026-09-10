package com.pocketshell.next.sync

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * The sync API client — the Android side of the contract documented in the
 * aws-infra repo (`sandbox/pocketshell-sync/docs/CLIENT-INTEGRATION.md`), and
 * a port of the desktop app's `src/main/sync/SyncService.ts` (issue #2633).
 *
 * Every request carries `Authorization: Bearer <ID token>`; a 401 means the
 * token aged out mid-flight, so the request is retried ONCE through a forced
 * token refresh before giving up (the server authorizes independently, so this
 * is a convenience, never a security mechanism). A 409 on push is the
 * multi-device conflict signal: the stored version moved under us, and the
 * caller is told the current version so it can re-pull, re-absorb, re-push.
 *
 * This client speaks ENVELOPES, not plaintext: what goes over the wire is
 * exactly what [SyncCrypto] produced. Plaintext never reaches this class, and
 * neither does the passphrase.
 */

/** The stored blob moved between our pull and our push. */
class SyncConflictError(val currentVersion: Int) :
    Exception("slot changed under us (stored version $currentVersion)")

/** The API refused the request for a reason worth showing the user. */
class SyncApiError(val status: Int, message: String) : Exception(message)

/** One slot as `GET /settings/{slot}` returns it; [data] is the envelope string. */
data class PulledSlot(val slot: String, val version: Int, val data: String)

/** The Lambda rejects `data` over 8 KB; the app enforces it before upload. */
const val SYNC_DATA_LIMIT_BYTES: Int = 8 * 1024

class SyncApiClient(
    private val auth: IdTokenSource,
    private val http: SyncHttpClient,
    private val dispatcher: CoroutineDispatcher,
    baseUrl: String = SyncConfig.SYNC_API_URL,
) {

    private val baseUrl: String = baseUrl.trimEnd('/')

    /** `GET /me` — who the server thinks we are. Also the connectivity check. */
    suspend fun me(): GoogleIdentity {
        val json = jsonBody(request("GET", "/me"))
        return GoogleIdentity(
            sub = json.optString("sub"),
            email = json.optString("email").takeIf { it.isNotEmpty() },
        )
    }

    /**
     * `GET /settings/{slot}`, or null when the account has no blob there yet —
     * a fresh account is a normal state, not an error.
     */
    suspend fun pull(slot: String = SyncConfig.SYNC_SLOT): PulledSlot? {
        val response = request("GET", "/settings/$slot")
        if (response.code == 404) return null
        val json = jsonBody(response)
        return PulledSlot(
            slot = json.optString("slot", slot),
            version = json.optInt("version"),
            data = json.optString("data"),
        )
    }

    /**
     * `PUT /settings/{slot}` with the envelope and the base version we last
     * saw (`0` creates). Returns the new stored version; a 409 throws
     * [SyncConflictError] with the version to re-base on.
     */
    suspend fun push(
        envelope: String,
        baseVersion: Int,
        slot: String = SyncConfig.SYNC_SLOT,
    ): Int {
        if (envelope.toByteArray(StandardCharsets.UTF_8).size > SYNC_DATA_LIMIT_BYTES) {
            throw SyncApiError(0, "settings blob exceeds the 8 KB sync limit")
        }
        val body = JSONObject().put("data", envelope).put("version", baseVersion).toString()
        val response = request("PUT", "/settings/$slot", body)
        if (response.code == 409) {
            val current = runCatching { JSONObject(response.body).optInt("currentVersion") }.getOrDefault(0)
            throw SyncConflictError(current)
        }
        return jsonBody(response).optInt("version")
    }

    /** `DELETE /settings/{slot}` — remove the blob from the account. */
    suspend fun remove(slot: String = SyncConfig.SYNC_SLOT) {
        val response = request("DELETE", "/settings/$slot")
        if (response.code !in 200..299 && response.code != 404) {
            throw SyncApiError(response.code, detailOf(response))
        }
    }

    /* --- internals --------------------------------------------------------- */

    /**
     * One API call with the 401-refresh-retry. The retry is single-shot: a 401
     * AFTER a forced refresh means the account is genuinely unauthorized
     * (allowlist, revoked grant) and retrying again would only spin.
     */
    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        alreadyRefreshed: Boolean = false,
    ): SyncHttpResponse {
        val token = try {
            auth.idToken(alreadyRefreshed)
        } catch (e: NotSignedInError) {
            throw e
        } catch (e: Exception) {
            throw SyncApiError(0, "could not obtain an ID token: ${e.message}")
        }
        val response = withContext(dispatcher) {
            http.send(
                SyncHttpRequest(
                    method = method,
                    url = "$baseUrl$path",
                    headers = mapOf("Authorization" to "Bearer $token"),
                    body = body,
                    contentType = if (body != null) "application/json" else null,
                ),
            )
        }
        if (response.code == 401 && !alreadyRefreshed) {
            return request(method, path, body, alreadyRefreshed = true)
        }
        return response
    }

    /** Non-2xx becomes [SyncApiError] with whatever detail the body had. */
    private fun jsonBody(response: SyncHttpResponse): JSONObject {
        if (response.code !in 200..299) throw SyncApiError(response.code, detailOf(response))
        return try {
            JSONObject(response.body)
        } catch (_: JSONException) {
            throw SyncApiError(response.code, "sync API returned an unreadable response")
        }
    }

    private fun detailOf(response: SyncHttpResponse): String =
        runCatching { JSONObject(response.body).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: "sync API returned HTTP ${response.code}"
}
