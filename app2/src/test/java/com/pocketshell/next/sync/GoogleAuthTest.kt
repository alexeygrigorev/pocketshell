package com.pocketshell.next.sync

import android.net.Uri
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Android OAuth flow and the token lifetime (issue #2633).
 *
 * Covers the desktop suite's cases (PKCE correctness, claim decoding, expiry
 * arithmetic) plus the four things the Android transport changed and that
 * therefore have no desktop test to inherit:
 *
 * - **No client secret, anywhere.** An Android OAuth client is a public client;
 *   a secret in an APK is readable by anyone who unzips it. Asserted against
 *   the recorded request bodies, not read off the source.
 * - **The `state` nonce is enforced** on the redirect, because on Android the
 *   redirect arrives as an `Intent` any installed app can send.
 * - **Refresh happens on expiry and reuses the stored refresh token**, which
 *   Google does not re-issue on a refresh.
 * - **A pending sign-in is consumed once**, so a replayed redirect cannot be
 *   exchanged twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GoogleAuthTest {

    private val clientId = "1035162854462-abc.apps.googleusercontent.com"
    private val redirectUri = SyncConfig.redirectUri(clientId)

    /* --- pure helpers ------------------------------------------------------ */

    @Test
    fun `PKCE challenge is the S256 digest of the verifier`() {
        val pair = createPkcePair()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(pair.verifier.toByteArray(Charsets.US_ASCII)))
        assertEquals(expected, pair.challenge)
    }

    @Test
    fun `PKCE verifier has RFC 7636 length and is fresh per call`() {
        val pair = createPkcePair()
        assertTrue(pair.verifier.length in 43..128)
        assertNotEquals(createPkcePair().verifier, createPkcePair().verifier)
    }

    @Test
    fun `decodes sub and email from an ID token`() {
        val identity = decodeIdTokenPayload(fakeIdToken(sub = "sub-123", email = "a@b.c", exp = 42))
        assertEquals("sub-123", identity.sub)
        assertEquals("a@b.c", identity.email)
        assertEquals(42L, identity.expiresAtEpochS)
    }

    @Test
    fun `tolerates a missing email or exp`() {
        val identity = decodeIdTokenPayload(fakeIdToken(sub = "s", email = null, exp = null))
        assertEquals("s", identity.sub)
        assertNull(identity.email)
        assertNull(identity.expiresAtEpochS)
    }

    @Test
    fun `rejects a malformed ID token`() {
        assertThrows(SyncAuthError::class.java) { decodeIdTokenPayload("not.a") }
        assertThrows(SyncAuthError::class.java) { decodeIdTokenPayload("h.@@@.s") }
        assertThrows(SyncAuthError::class.java) {
            decodeIdTokenPayload(fakeIdToken(sub = "", email = "a@b.c"))
        }
    }

    @Test
    fun `expiry applies the skew`() {
        val obtained = 1_000_000L
        val ttl = 3600L
        assertFalse(isIdTokenExpired(obtained, ttl, obtained + 60_000))
        // Exactly at expiry-minus-skew: already considered gone.
        assertTrue(isIdTokenExpired(obtained, ttl, obtained + (ttl - 60) * 1000))
        // One second before the skew boundary: still good.
        assertFalse(isIdTokenExpired(obtained, ttl, obtained + (ttl - 61) * 1000))
    }

    /* --- authorization request --------------------------------------------- */

    @Test
    fun `authorization URL carries PKCE and no client secret`() {
        val pkce = PkcePair("verifier-value", "challenge-value")
        val uri = Uri.parse(buildAuthorizationUrl(clientId, redirectUri, pkce, "state-value"))

        assertEquals("accounts.google.com", uri.host)
        assertEquals("/o/oauth2/v2/auth", uri.path)
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals(clientId, uri.getQueryParameter("client_id"))
        assertEquals(redirectUri, uri.getQueryParameter("redirect_uri"))
        assertEquals("openid email profile", uri.getQueryParameter("scope"))
        assertEquals("offline", uri.getQueryParameter("access_type"))
        assertEquals("consent", uri.getQueryParameter("prompt"))
        assertEquals("challenge-value", uri.getQueryParameter("code_challenge"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals("state-value", uri.getQueryParameter("state"))
        // The whole reason this is an Android client and not the desktop one.
        assertNull(uri.getQueryParameter("client_secret"))
    }

    @Test
    fun `the redirect URI is the reversed client ID, never a loopback`() {
        assertEquals("com.googleusercontent.apps.1035162854462-abc:/oauth2redirect", redirectUri)
        assertFalse(redirectUri.contains("127.0.0.1"))
        assertFalse(redirectUri.startsWith("http"))
    }

    /* --- redirect handling -------------------------------------------------- */

    @Test
    fun `accepts a matching redirect`() {
        val callback = parseAuthorizationCallback("$redirectUri?code=abc123&state=nonce", "nonce")
        assertEquals(AuthorizationCallback.Code("abc123"), callback)
    }

    @Test
    fun `rejects a redirect whose state does not match`() {
        // On Android the redirect arrives as an Intent, which any installed app
        // can send. The state nonce is the only thing that makes an injected
        // authorization code unusable.
        val callback = parseAuthorizationCallback("$redirectUri?code=evil&state=attacker", "nonce")
        assertTrue(callback is AuthorizationCallback.Failed)
        assertTrue((callback as AuthorizationCallback.Failed).reason.contains("state"))
    }

    @Test
    fun `rejects a redirect with no state at all`() {
        assertTrue(parseAuthorizationCallback("$redirectUri?code=evil", "nonce") is AuthorizationCallback.Failed)
    }

    @Test
    fun `surfaces a user deny`() {
        val callback = parseAuthorizationCallback("$redirectUri?error=access_denied&state=nonce", "nonce")
        assertTrue((callback as AuthorizationCallback.Failed).reason.contains("access_denied"))
    }

    @Test
    fun `rejects a redirect with no code`() {
        val callback = parseAuthorizationCallback("$redirectUri?state=nonce", "nonce")
        assertTrue(callback is AuthorizationCallback.Failed)
    }

    /* --- the class --------------------------------------------------------- */

    /**
     * A placeholder client ID must refuse locally rather than send the user to
     * a browser that lands on a Google 400 with no way back.
     *
     * The placeholder is passed EXPLICITLY. This used to read
     * `SyncConfig.GOOGLE_ANDROID_CLIENT_ID` and rely on the shipped value being
     * the placeholder, so it silently changed meaning the moment the real
     * client was registered (#2635): it stopped testing the refusal and started
     * asserting that the build is unconfigured, which is the opposite of what
     * anyone wants. The guard and the shipped value are separate facts and are
     * now asserted separately.
     */
    @Test
    fun `refuses to start a sign-in while the client ID is a placeholder`() {
        val placeholder = "${SyncConfig.UNCONFIGURED_CLIENT_ID_MARKER}.apps.googleusercontent.com"
        val auth = auth(clientId = placeholder, http = RecordingSyncHttpClient.scripted())
        assertFalse(auth.clientConfigured)
        val error = assertThrows(SyncAuthError::class.java) { auth.beginAuthorization() }
        assertTrue(error.message!!.contains("not configured"))
    }

    /** …and the client this build actually ships is NOT a placeholder (#2635). */
    @Test
    fun `the shipped client ID starts a real sign-in`() {
        val auth = auth(
            clientId = SyncConfig.GOOGLE_ANDROID_CLIENT_ID,
            http = RecordingSyncHttpClient.scripted(),
        )

        assertTrue(
            "the registered Android OAuth client must be wired in",
            auth.clientConfigured,
        )
        val pending = auth.beginAuthorization()
        assertTrue(pending.authorizationUrl.startsWith(SyncConfig.AUTH_ENDPOINT))
        assertTrue(
            pending.authorizationUrl.contains(SyncConfig.GOOGLE_ANDROID_CLIENT_ID),
        )
        assertEquals(SyncConfig.redirectUri(), pending.redirectUri)
    }

    @Test
    fun `exchanges the code with the verifier and without a secret`() = runTest {
        val http = RecordingSyncHttpClient.scripted(tokenResponse(fakeIdToken(), refreshToken = "refresh-1"))
        val store = InMemorySyncTokenStore()
        val auth = auth(http = http, store = store)

        val pending = auth.beginAuthorization()
        val identity = auth.completeAuthorization("${pending.redirectUri}?code=the-code&state=${pending.state}")

        assertEquals("sub-123", identity.sub)
        assertEquals("person@example.com", identity.email)

        val form = http.formOf(0)
        assertEquals(SyncConfig.TOKEN_ENDPOINT, http.requests[0].url)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals(pending.verifier, form["code_verifier"])
        assertEquals(redirectUri, form["redirect_uri"])
        assertEquals(clientId, form["client_id"])
        assertFalse("a public Android client must send no secret", form.containsKey("client_secret"))

        assertEquals("refresh-1", store.stored!!.refreshToken)
        assertEquals(fakeIdToken(), store.stored!!.idToken)
        assertTrue(auth.status.value.signedIn)
        assertEquals("person@example.com", auth.status.value.email)
    }

    @Test
    fun `a pending sign-in is consumed exactly once`() = runTest {
        val http = RecordingSyncHttpClient.scripted(tokenResponse(fakeIdToken(), refreshToken = "r"))
        val auth = auth(http = http)
        val pending = auth.beginAuthorization()
        val redirect = "${pending.redirectUri}?code=the-code&state=${pending.state}"

        auth.completeAuthorization(redirect)
        // Replaying the same Intent must not buy a second exchange.
        assertThrows(SyncAuthError::class.java) { runBlocking { auth.completeAuthorization(redirect) } }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a mismatched redirect never reaches the token endpoint`() = runTest {
        val http = RecordingSyncHttpClient.scripted(tokenResponse(fakeIdToken()))
        val auth = auth(http = http)
        val pending = auth.beginAuthorization()

        assertThrows(SyncAuthError::class.java) {
            runBlocking { auth.completeAuthorization("${pending.redirectUri}?code=evil&state=attacker") }
        }
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `a token-endpoint failure surfaces and stores nothing`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(400, """{"error":"invalid_grant"}"""))
        val store = InMemorySyncTokenStore()
        val auth = auth(http = http, store = store)
        val pending = auth.beginAuthorization()

        val error = assertThrows(SyncAuthError::class.java) {
            runBlocking { auth.completeAuthorization("${pending.redirectUri}?code=c&state=${pending.state}") }
        }
        assertTrue(error.message!!.contains("400"))
        assertNull(store.stored)
        assertFalse(auth.status.value.signedIn)
    }

    /* --- token lifetime ----------------------------------------------------- */

    @Test
    fun `serves the cached ID token while it is fresh`() = runTest {
        val http = RecordingSyncHttpClient.scripted(tokenResponse(fakeIdToken()))
        val auth = auth(http = http, store = InMemorySyncTokenStore(storedAuth(obtainedAtMs = 0)), now = { 60_000 })
        assertEquals("cached-id-token", auth.idToken())
        assertEquals("no network call was needed for a fresh token", 0, http.requests.size)
    }

    @Test
    fun `refreshes when the token has aged past the skew`() = runTest {
        val http = RecordingSyncHttpClient.scripted(tokenResponse("fresh-id-token"))
        val store = InMemorySyncTokenStore(storedAuth(obtainedAtMs = 0))
        // 3600s TTL minus the 60s skew: expired at exactly 3_540_000 ms.
        val auth = auth(http = http, store = store, now = { 3_540_000 })

        assertEquals("fresh-id-token", auth.idToken())

        val form = http.formOf(0)
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("stored-refresh-token", form["refresh_token"])
        assertEquals(clientId, form["client_id"])
        assertFalse(form.containsKey("client_secret"))
        // Google does not re-issue a refresh token on a refresh; the old one
        // must survive, or the next expiry logs the user out for no reason.
        assertEquals("stored-refresh-token", store.stored!!.refreshToken)
        assertEquals("fresh-id-token", store.stored!!.idToken)
        assertEquals(3_540_000L, store.stored!!.obtainedAtMs)
    }

    @Test
    fun `a forced refresh bypasses a still-fresh cached token`() = runTest {
        val http = RecordingSyncHttpClient.scripted(tokenResponse("fresh-id-token"))
        val auth = auth(http = http, store = InMemorySyncTokenStore(storedAuth(obtainedAtMs = 0)), now = { 1_000 })
        assertEquals("fresh-id-token", auth.idToken(forceRefresh = true))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `an expired token with no refresh token means sign in again`() = runTest {
        val store = InMemorySyncTokenStore(storedAuth(obtainedAtMs = 0, refreshToken = null))
        val auth = auth(http = RecordingSyncHttpClient.scripted(), store = store, now = { 4_000_000 })
        val error = assertThrows(NotSignedInError::class.java) { runBlocking { auth.idToken() } }
        assertTrue(error.message!!.contains("sign in again"))
    }

    @Test
    fun `a signed-out store has no token to serve`() = runTest {
        val auth = auth(http = RecordingSyncHttpClient.scripted(), store = InMemorySyncTokenStore())
        assertThrows(NotSignedInError::class.java) { runBlocking { auth.idToken() } }
    }

    @Test
    fun `a failed refresh surfaces rather than returning a stale token`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(401, """{"error":"invalid_grant"}"""))
        val auth = auth(http = http, store = InMemorySyncTokenStore(storedAuth(obtainedAtMs = 0)), now = { 4_000_000 })
        assertThrows(SyncAuthError::class.java) { runBlocking { auth.idToken() } }
    }

    @Test
    fun `signing out clears the store and revokes the grant`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(200, "{}"))
        val store = InMemorySyncTokenStore(storedAuth(obtainedAtMs = 0))
        val auth = auth(http = http, store = store)

        auth.signOut()

        assertNull(store.stored)
        assertFalse(auth.status.value.signedIn)
        assertEquals(SyncConfig.REVOKE_ENDPOINT, http.requests.single().url)
        assertEquals("stored-refresh-token", http.formOf(0)["token"])
    }

    @Test
    fun `signing out still succeeds when revocation fails`() = runTest {
        val http = SyncHttpClient { throw java.io.IOException("offline") }
        val store = InMemorySyncTokenStore(storedAuth(obtainedAtMs = 0))
        auth(http = http, store = store).signOut()
        assertNull(store.stored)
    }

    @Test
    fun `the published status never carries a token`() {
        val store = InMemorySyncTokenStore(storedAuth(obtainedAtMs = 0))
        val status = auth(http = RecordingSyncHttpClient.scripted(), store = store).status.value
        val rendered = status.toString()
        assertFalse(rendered.contains("cached-id-token"))
        assertFalse(rendered.contains("stored-refresh-token"))
    }

    private fun storedAuth(
        obtainedAtMs: Long,
        refreshToken: String? = "stored-refresh-token",
    ) = StoredAuth(
        sub = "sub-123",
        email = "person@example.com",
        idToken = "cached-id-token",
        refreshToken = refreshToken,
        obtainedAtMs = obtainedAtMs,
        expiresInS = 3600,
    )

    private fun auth(
        http: SyncHttpClient,
        store: SyncTokenStore = InMemorySyncTokenStore(),
        clientId: String = this.clientId,
        now: () -> Long = { 0L },
    ) = GoogleAuth(
        tokens = store,
        http = http,
        // `Unconfined` so the suspending calls run inline in `runTest`: the
        // assertions here are about request bodies, not about scheduling.
        dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        clientId = clientId,
        now = now,
    )
}
