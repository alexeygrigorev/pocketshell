package com.pocketshell.next.sync

import android.net.Uri
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Google sign-in for settings sync — the optional "Gmail login" (issue #2633).
 *
 * The Android counterpart of the desktop app's `src/main/sync/GoogleAuth.ts`.
 * The DATA is identical (PKCE `S256`, a `state` nonce, an ID token plus a
 * refresh token, one-hour expiry with a skew); the TRANSPORT is not, and could
 * not be:
 *
 * | | desktop | Android |
 * | --- | --- | --- |
 * | client type | "Desktop app" | "Android" |
 * | client secret | required by Google's token endpoint | **none** — a public client with PKCE |
 * | redirect | one-shot `http://127.0.0.1:<port>` listener | reversed-client-ID scheme, captured by [SyncOAuthRedirectActivity] |
 * | browser | `shell.openExternal` | Custom Tab (see [AuthorizationLauncher]) |
 * | tokens at rest | Electron `safeStorage` | [AndroidKeystoreSyncTokenStore] |
 *
 * A client secret in an APK is not a secret, so none is sent anywhere in this
 * file — that is the security property `GoogleAuthTest` pins, not a comment.
 *
 * The API Gateway JWT authorizer re-validates every token (signature, issuer,
 * audience, expiry) server-side, so nothing here is load-bearing for the
 * backend's security; [decodeIdTokenPayload] deliberately does not verify.
 */

/** Who is signed in, as far as anything above this class is allowed to know. */
data class GoogleIdentity(val sub: String, val email: String?, val expiresAtEpochS: Long? = null)

/**
 * The UI-visible sign-in state. Deliberately holds NO token: this is the
 * widest type that ever leaves [GoogleAuth] towards a ViewModel.
 */
data class SyncAuthStatus(
    val signedIn: Boolean = false,
    val email: String? = null,
    /** False until the maintainer registers the Android OAuth client (#2633). */
    val clientConfigured: Boolean = SyncConfig.isGoogleClientConfigured,
)

/** Signed out, or the stored sign-in expired with no refresh token. */
class NotSignedInError(message: String) : Exception(message)

/** Sign-in itself failed: a deny, a state mismatch, a token-endpoint refusal. */
class SyncAuthError(message: String) : Exception(message)

/** A fresh PKCE pair: the verifier stays here, the challenge travels in the URL. */
data class PkcePair(val verifier: String, val challenge: String)

/** The in-flight authorization: what was opened, and what must come back. */
data class PendingAuthorization(
    val authorizationUrl: String,
    val redirectUri: String,
    val state: String,
    val verifier: String,
)

/** What came back on the redirect URI. */
sealed interface AuthorizationCallback {
    data class Code(val code: String) : AuthorizationCallback
    data class Failed(val reason: String) : AuthorizationCallback
}

/** RFC 7636 §4.1/§4.2: a 43-character verifier and its S256 challenge. */
fun createPkcePair(random: SecureRandom = SecureRandom()): PkcePair {
    val verifierBytes = ByteArray(32).also(random::nextBytes)
    val verifier = base64Url(verifierBytes)
    val challenge = base64Url(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
    )
    return PkcePair(verifier, challenge)
}

/**
 * The ID token's claims, decoded WITHOUT verification — acceptable because the
 * token comes straight from Google's token endpoint over TLS, and the server
 * independently verifies everything it acts on.
 */
fun decodeIdTokenPayload(idToken: String): GoogleIdentity {
    val parts = idToken.split('.')
    if (parts.size != 3) throw SyncAuthError("malformed ID token")
    val claims = try {
        JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
    } catch (e: Exception) {
        when (e) {
            is JSONException, is IllegalArgumentException ->
                throw SyncAuthError("malformed ID token payload")
            else -> throw e
        }
    }
    val sub = claims.optString("sub").takeIf { it.isNotEmpty() }
        ?: throw SyncAuthError("ID token has no subject")
    return GoogleIdentity(
        sub = sub,
        email = claims.optString("email").takeIf { it.isNotEmpty() },
        expiresAtEpochS = if (claims.has("exp")) claims.optLong("exp") else null,
    )
}

/** Treat an ID token as gone this many seconds before its real expiry. */
const val ID_TOKEN_EXPIRY_SKEW_S: Long = 60

fun isIdTokenExpired(obtainedAtMs: Long, expiresInS: Long, nowMs: Long): Boolean =
    nowMs >= obtainedAtMs + expiresInS * 1000 - ID_TOKEN_EXPIRY_SKEW_S * 1000

/**
 * The authorization URL for a public Android client. No `client_secret` —
 * there is no place in the authorization request for one, and the token
 * exchange below does not send one either.
 */
fun buildAuthorizationUrl(
    clientId: String,
    redirectUri: String,
    pkce: PkcePair,
    state: String,
): String = Uri.parse(SyncConfig.AUTH_ENDPOINT).buildUpon()
    .appendQueryParameter("response_type", "code")
    .appendQueryParameter("client_id", clientId)
    .appendQueryParameter("redirect_uri", redirectUri)
    .appendQueryParameter("scope", SyncConfig.SCOPE)
    // offline + consent: a refresh token, so sync survives past the hour.
    .appendQueryParameter("access_type", "offline")
    .appendQueryParameter("prompt", "consent")
    .appendQueryParameter("code_challenge", pkce.challenge)
    .appendQueryParameter("code_challenge_method", "S256")
    .appendQueryParameter("state", state)
    .build()
    .toString()

/**
 * Classify the redirect. A `state` that does not match the request we opened
 * is rejected before the code is looked at — that check is the only thing
 * standing between this flow and an injected authorization code.
 */
fun parseAuthorizationCallback(callbackUri: String, expectedState: String): AuthorizationCallback {
    val uri = try {
        Uri.parse(callbackUri)
    } catch (_: Exception) {
        return AuthorizationCallback.Failed("sign-in response could not be read")
    }
    val error = uri.getQueryParameter("error")
    if (!error.isNullOrEmpty()) {
        return AuthorizationCallback.Failed("Google sign-in was not completed ($error).")
    }
    if (uri.getQueryParameter("state") != expectedState) {
        return AuthorizationCallback.Failed("sign-in response did not match this request (state).")
    }
    val code = uri.getQueryParameter("code")
    if (code.isNullOrEmpty()) {
        return AuthorizationCallback.Failed("Google did not return an authorization code.")
    }
    return AuthorizationCallback.Code(code)
}

/**
 * The one thing [SyncApiClient] needs from the sign-in: a usable ID token, and
 * a way to demand a fresh one after a 401.
 *
 * A separate interface so the API client depends on the CAPABILITY rather than
 * on `GoogleAuth` itself — a test can then script "this call gets a stale
 * token, the retry gets a fresh one" in two lines instead of standing up a
 * whole OAuth flow to produce it.
 */
interface IdTokenSource {
    /** @throws NotSignedInError when there is no usable sign-in. */
    suspend fun idToken(forceRefresh: Boolean = false): String
}

/**
 * Owns the sign-in: builds the request, exchanges the code, stores the tokens,
 * and serves a usable ID token to [SyncApiClient].
 *
 * Everything Android-specific is behind a seam ([SyncTokenStore],
 * [SyncHttpClient]), so the whole class runs on the host JVM in tests.
 */
class GoogleAuth(
    private val tokens: SyncTokenStore,
    private val http: SyncHttpClient,
    private val dispatcher: CoroutineDispatcher,
    private val clientId: String = SyncConfig.GOOGLE_ANDROID_CLIENT_ID,
    private val redirectUri: String = SyncConfig.redirectUri(clientId),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : IdTokenSource {

    private val _status = MutableStateFlow(readStatus())

    /** Signed-in state for the UI. Never carries a token. */
    val status: StateFlow<SyncAuthStatus> = _status.asStateFlow()

    @Volatile
    private var pending: PendingAuthorization? = null

    /** True once a real Android OAuth client ID has been configured (#2633). */
    val clientConfigured: Boolean get() = !clientId.contains(SyncConfig.UNCONFIGURED_CLIENT_ID_MARKER)

    /** Re-read the store (e.g. after a process restart) and republish. */
    fun refreshStatus() {
        _status.value = readStatus()
    }

    /**
     * Start a sign-in: mint PKCE + state, remember them, and return the URL a
     * Custom Tab should open. Starting twice replaces the in-flight request —
     * the last URL opened is the one whose state will be accepted.
     */
    fun beginAuthorization(): PendingAuthorization {
        if (!clientConfigured) {
            throw SyncAuthError(
                "Google sign-in is not configured in this build — no Android OAuth client ID (issue #2633).",
            )
        }
        val pkce = createPkcePair(random)
        val state = base64Url(ByteArray(16).also(random::nextBytes))
        return PendingAuthorization(
            authorizationUrl = buildAuthorizationUrl(clientId, redirectUri, pkce, state),
            redirectUri = redirectUri,
            state = state,
            verifier = pkce.verifier,
        ).also { pending = it }
    }

    /**
     * Finish the sign-in from the captured redirect. Consumes the pending
     * request either way, so a replayed redirect cannot be exchanged twice.
     */
    suspend fun completeAuthorization(callbackUri: String): GoogleIdentity {
        val request = pending ?: throw SyncAuthError("no sign-in is in progress")
        pending = null
        when (val callback = parseAuthorizationCallback(callbackUri, request.state)) {
            is AuthorizationCallback.Failed -> throw SyncAuthError(callback.reason)
            is AuthorizationCallback.Code -> {
                val response = exchangeCode(callback.code, request)
                val identity = decodeIdTokenPayload(response.idToken)
                store(response, response.refreshToken, identity)
                return identity
            }
        }
    }

    /**
     * A usable ID token: the cached one, or a fresh one from the refresh
     * token. The one call [SyncApiClient] needs before every request.
     */
    override suspend fun idToken(forceRefresh: Boolean): String {
        val stored = tokens.load() ?: throw NotSignedInError("not signed in")
        if (!forceRefresh && !isIdTokenExpired(stored.obtainedAtMs, stored.expiresInS, now())) {
            return stored.idToken
        }
        val refreshToken = stored.refreshToken
            ?: throw NotSignedInError("sign-in expired — sign in again")
        val response = post(
            SyncConfig.TOKEN_ENDPOINT,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
                // No client_secret: an Android OAuth client is a PUBLIC client.
            ),
            "token refresh",
        )
        // A refresh response carries no new refresh token; the old one stands.
        store(response, refreshToken, GoogleIdentity(stored.sub, stored.email))
        return response.idToken
    }

    /** Forget the tokens; best-effort revoke at Google so the grant dies too. */
    suspend fun signOut() {
        val stored = tokens.load()
        tokens.clear()
        pending = null
        refreshStatus()
        val refreshToken = stored?.refreshToken ?: return
        // Revocation is courtesy, not correctness — local sign-out already holds.
        runCatching {
            withContext(dispatcher) {
                http.send(
                    SyncHttpRequest(
                        method = "POST",
                        url = SyncConfig.REVOKE_ENDPOINT,
                        body = formEncode(mapOf("token" to refreshToken)),
                        contentType = FORM_CONTENT_TYPE,
                    ),
                )
            }
        }
    }

    /* --- internals --------------------------------------------------------- */

    private suspend fun exchangeCode(code: String, request: PendingAuthorization): TokenResponse =
        post(
            SyncConfig.TOKEN_ENDPOINT,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to request.verifier,
                "redirect_uri" to request.redirectUri,
                "client_id" to clientId,
                // No client_secret — PKCE is what proves this is the same app
                // that started the flow, and an APK cannot hold a secret.
            ),
            "token exchange",
        )

    private suspend fun post(url: String, form: Map<String, String>, what: String): TokenResponse {
        val response = withContext(dispatcher) {
            http.send(
                SyncHttpRequest(
                    method = "POST",
                    url = url,
                    body = formEncode(form),
                    contentType = FORM_CONTENT_TYPE,
                ),
            )
        }
        if (response.code !in 200..299) {
            throw SyncAuthError("$what failed (HTTP ${response.code})")
        }
        val json = try {
            JSONObject(response.body)
        } catch (_: JSONException) {
            throw SyncAuthError("$what returned an unreadable response")
        }
        val idToken = json.optString("id_token").takeIf { it.isNotEmpty() }
            ?: throw SyncAuthError("$what returned no ID token")
        return TokenResponse(
            idToken = idToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresInS = json.optLong("expires_in", DEFAULT_EXPIRES_IN_S),
        )
    }

    private fun store(response: TokenResponse, refreshToken: String?, identity: GoogleIdentity) {
        tokens.save(
            StoredAuth(
                sub = identity.sub,
                email = identity.email,
                idToken = response.idToken,
                refreshToken = refreshToken,
                obtainedAtMs = now(),
                expiresInS = response.expiresInS,
            ),
        )
        refreshStatus()
    }

    private fun readStatus(): SyncAuthStatus {
        val stored = tokens.load()
        return SyncAuthStatus(
            signedIn = stored != null,
            email = stored?.email,
            clientConfigured = clientConfigured,
        )
    }

    private data class TokenResponse(
        val idToken: String,
        val refreshToken: String?,
        val expiresInS: Long,
    )

    private companion object {
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

        /** Google always sends `expires_in`; one hour is its documented value. */
        const val DEFAULT_EXPIRES_IN_S = 3600L
    }
}

private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
