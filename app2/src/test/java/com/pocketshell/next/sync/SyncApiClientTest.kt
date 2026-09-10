package com.pocketshell.next.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SyncApiClient] against a scripted HTTP client (issue #2633) — the Android
 * port of the desktop suite's `SyncService.test.ts`: header shape, the
 * 401-refresh-retry, the 409-to-conflict mapping, the 8 KB ceiling, and the
 * 404-is-nothing-yet read.
 *
 * The contract under test is the one written down in the aws-infra repo
 * (`sandbox/pocketshell-sync/docs/CLIENT-INTEGRATION.md`); the paths and body
 * shapes asserted here are that document, not this client's preferences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncApiClientTest {

    @Test
    fun `push sends the Bearer header and the documented JSON body`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(200, """{"slot":"main","version":1}"""))
        val version = client(http).push("envelope-json", baseVersion = 0)

        assertEquals(1, version)
        val request = http.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("${SyncConfig.SYNC_API_URL}/settings/main", request.url)
        assertEquals("Bearer id-token-1", request.headers["Authorization"])
        assertEquals("application/json", request.contentType)
        assertEquals("""{"data":"envelope-json","version":0}""", request.body)
    }

    @Test
    fun `pull returns the stored envelope and version`() = runTest {
        val http = RecordingSyncHttpClient.scripted(
            SyncHttpResponse(200, """{"slot":"main","version":4,"data":"the-envelope"}"""),
        )
        val slot = client(http).pull()
        assertEquals("main", slot!!.slot)
        assertEquals(4, slot.version)
        assertEquals("the-envelope", slot.data)
        assertEquals("GET", http.requests.single().method)
        assertEquals("${SyncConfig.SYNC_API_URL}/settings/main", http.requests.single().url)
    }

    @Test
    fun `a 404 on pull is a fresh account, not an error`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(404, """{"message":"Not Found"}"""))
        assertNull(client(http).pull())
    }

    @Test
    fun `retries once through a forced refresh on 401`() = runTest {
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) SyncHttpResponse(401, """{"message":"Unauthorized"}""")
            else SyncHttpResponse(200, """{"sub":"s","email":"a@b.c"}""")
        }
        val tokens = ScriptedTokenSource("stale", "fresh")
        val identity = SyncApiClient(tokens, http, Dispatchers.Unconfined).me()

        assertEquals("s", identity.sub)
        assertEquals(2, http.requests.size)
        // The retry asked for a FORCED refresh and carried the second token.
        assertEquals(listOf(false, true), tokens.forcedFlags)
        assertEquals("Bearer stale", http.requests[0].headers["Authorization"])
        assertEquals("Bearer fresh", http.requests[1].headers["Authorization"])
    }

    @Test
    fun `does not retry twice — a second 401 surfaces as an error`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(401, """{"message":"Unauthorized"}"""))
        val error = assertThrows(SyncApiError::class.java) { runBlocking { client(http).me() } }
        assertEquals(401, error.status)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `maps 409 to a conflict carrying the current version`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(409, """{"currentVersion":7}"""))
        val error = assertThrows(SyncConflictError::class.java) {
            runBlocking { client(http).push("env", baseVersion = 3) }
        }
        assertEquals(7, error.currentVersion)
    }

    @Test
    fun `refuses to push an envelope over the server limit`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(200, "{}"))
        val oversized = "x".repeat(SYNC_DATA_LIMIT_BYTES + 1)
        val error = assertThrows(SyncApiError::class.java) {
            runBlocking { client(http).push(oversized, baseVersion = 0) }
        }
        assertTrue(error.message!!.contains("8 KB"))
        // Enforced BEFORE upload, as the contract says — nothing hit the wire.
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `measures the 8 KB ceiling in bytes, not characters`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(200, """{"version":1}"""))
        // 4097 two-byte characters = 8194 bytes: under the character count,
        // over the byte limit the Lambda actually enforces.
        val multiByte = "ä".repeat(SYNC_DATA_LIMIT_BYTES / 2 + 1)
        assertThrows(SyncApiError::class.java) {
            runBlocking { client(http).push(multiByte, baseVersion = 0) }
        }
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `surfaces the body detail for other failures`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(403, """{"message":"forbidden email"}"""))
        val error = assertThrows(SyncApiError::class.java) { runBlocking { client(http).me() } }
        assertEquals("forbidden email", error.message)
        assertEquals(403, error.status)
    }

    @Test
    fun `normalises a trailing slash on the base URL`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(200, """{"sub":"s"}"""))
        SyncApiClient(
            auth = ScriptedTokenSource("tok"),
            http = http,
            dispatcher = Dispatchers.Unconfined,
            baseUrl = "https://sync.example///",
        ).me()
        assertEquals("https://sync.example/me", http.requests.single().url)
    }

    @Test
    fun `a signed-out account fails without a network call`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(200, "{}"))
        assertThrows(NotSignedInError::class.java) {
            runBlocking { SyncApiClient(SignedOutTokenSource, http, Dispatchers.Unconfined).pull() }
        }
        assertEquals(0, http.requests.size)
    }

    /** Hands out [tokens] in order and records whether each call forced a refresh. */
    private class ScriptedTokenSource(private vararg val tokens: String) : IdTokenSource {
        val forcedFlags: MutableList<Boolean> = mutableListOf()

        override suspend fun idToken(forceRefresh: Boolean): String {
            val token = tokens.getOrElse(forcedFlags.size) { tokens.last() }
            forcedFlags += forceRefresh
            return token
        }
    }

    /** A source that is signed out — the "no token, no request" case. */
    private object SignedOutTokenSource : IdTokenSource {
        override suspend fun idToken(forceRefresh: Boolean): String =
            throw NotSignedInError("not signed in")
    }

    private fun client(http: SyncHttpClient): SyncApiClient =
        SyncApiClient(ScriptedTokenSource("id-token-1"), http, Dispatchers.Unconfined)
}
