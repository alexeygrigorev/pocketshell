package com.pocketshell.next.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sync policy end to end on the host JVM (issue #2633): pull → absorb →
 * assemble → encrypt → push, plus the 409 conflict retry.
 *
 * The HTTP layer is scripted, but everything above it is real — the real
 * envelope, the real 600k-iteration KDF, the real selection store. That is
 * deliberate: the interesting failures here are "what got encrypted" and "what
 * happened after a conflict", and a mocked crypto layer would answer neither.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncRepositoryTest {

    private lateinit var context: Context
    private val prefsFile = "test-sync-repo-selection"
    private val passphrase = "correct horse battery staple"

    private val localHosts = listOf(
        host(1, "hetzner", "135.181.114.209", 22, "alexey"),
        host(2, "builder", "10.0.0.7", 2022, "root"),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(prefsFile)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(prefsFile)
    }

    @Test
    fun `pull on a fresh account reports absent`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(404, """{"message":"Not Found"}"""))
        assertEquals(
            SyncRepository.PullResult.Absent,
            repository(http).first.pull(passphrase, localHosts),
        )
    }

    @Test
    fun `pull decrypts the account blob and auto-ticks unseen aliases`() = runTest {
        val account = envelopeOf(
            SyncHostEntry("hetzner", "old.example", 22, "alexey"),
            SyncHostEntry("laptop-only", "10.1.1.1", 22, "alexey"),
        )
        val http = RecordingSyncHttpClient.scripted(slotResponse(account, version = 3))
        val (repo, selection) = repository(http)

        val result = repo.pull(passphrase, localHosts) as SyncRepository.PullResult.Ok

        assertEquals(3, result.version)
        assertEquals(listOf("hetzner", "laptop-only"), result.hosts.map { it.name })
        // `hetzner` exists locally — the user has decided about it, so their
        // untick stands. `laptop-only` has never materialised here.
        assertEquals(listOf("laptop-only"), selection.selected.value)
    }

    @Test
    fun `pull with the wrong passphrase fails closed`() = runTest {
        val http = RecordingSyncHttpClient.scripted(
            slotResponse(envelopeOf(SyncHostEntry("a", "a.example")), version = 1),
        )
        val result = repository(http).first.pull("not the passphrase", localHosts)
        assertTrue(result is SyncRepository.PullResult.Failed)
        assertTrue(
            (result as SyncRepository.PullResult.Failed).message.contains("wrong passphrase"),
        )
    }

    @Test
    fun `push uploads only the ticked hosts, encrypted`() = runTest {
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) SyncHttpResponse(404, "{}") else SyncHttpResponse(200, """{"version":1}""")
        }
        val (repo, selection) = repository(http)
        selection.setChecked("hetzner", true)

        val result = repo.push(passphrase, localHosts) as SyncRepository.PushResult.Ok
        assertEquals(1, result.version)
        assertEquals(1, result.uploaded)

        val uploaded = JSONObject(http.requests[1].body!!)
        assertEquals(0, uploaded.getInt("version"))
        val envelope = uploaded.getString("data")

        // What went over the wire is an envelope, not settings.
        assertFalse("plaintext hostname on the wire", envelope.contains("135.181.114.209"))
        assertFalse("the passphrase on the wire", envelope.contains(passphrase))

        // And decrypting it gives back exactly the ticked host, nothing else.
        val hosts = parseSyncPayload(SyncCrypto.decryptEnvelope(envelope, passphrase))
        assertEquals(listOf("hetzner"), hosts.map { it.name })
        assertEquals("135.181.114.209", hosts.single().hostname)
    }

    @Test
    fun `an unticked host is never uploaded`() = runTest {
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) SyncHttpResponse(404, "{}") else SyncHttpResponse(200, """{"version":1}""")
        }
        val (repo, selection) = repository(http)
        selection.setChecked("builder", true)

        repo.push(passphrase, localHosts)

        val envelope = JSONObject(http.requests[1].body!!).getString("data")
        val hosts = parseSyncPayload(SyncCrypto.decryptEnvelope(envelope, passphrase))
        assertEquals(listOf("builder"), hosts.map { it.name })
    }

    @Test
    fun `push re-bases on the pulled version`() = runTest {
        val account = envelopeOf(SyncHostEntry("hetzner", "old.example", 22, "alexey"))
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) slotResponse(account, version = 9) else SyncHttpResponse(200, """{"version":10}""")
        }
        val (repo, selection) = repository(http)
        selection.setChecked("hetzner", true)

        val result = repo.push(passphrase, localHosts) as SyncRepository.PushResult.Ok
        assertEquals(10, result.version)
        assertEquals(9, JSONObject(http.requests[1].body!!).getInt("version"))
    }

    @Test
    fun `a 409 re-pulls, re-absorbs and retries`() = runTest {
        val first = envelopeOf(SyncHostEntry("hetzner", "old.example", 22, "alexey"))
        val second = envelopeOf(
            SyncHostEntry("hetzner", "old.example", 22, "alexey"),
            SyncHostEntry("from-other-device", "10.2.2.2", 22, "alexey"),
        )
        val http = RecordingSyncHttpClient { _, index ->
            when (index) {
                0 -> slotResponse(first, version = 1)
                1 -> SyncHttpResponse(409, """{"currentVersion":2}""")
                2 -> slotResponse(second, version = 2)
                else -> SyncHttpResponse(200, """{"version":3}""")
            }
        }
        val (repo, selection) = repository(http)
        selection.setChecked("hetzner", true)

        val result = repo.push(passphrase, localHosts) as SyncRepository.PushResult.Ok
        assertEquals(3, result.version)

        // The retry re-based on the version the conflict reported…
        assertEquals(2, JSONObject(http.requests[3].body!!).getInt("version"))
        // …and absorbed the other device's alias instead of deleting it, which
        // is the whole point of re-pulling rather than force-pushing.
        assertTrue("from-other-device" in selection.selected.value)
        val hosts = parseSyncPayload(
            SyncCrypto.decryptEnvelope(JSONObject(http.requests[3].body!!).getString("data"), passphrase),
        )
        assertEquals(setOf("hetzner", "from-other-device"), hosts.map { it.name }.toSet())
    }

    @Test
    fun `a persistent conflict gives up after the bounded retries`() = runTest {
        val account = envelopeOf(SyncHostEntry("hetzner", "old.example", 22, "alexey"))
        val http = RecordingSyncHttpClient { request, _ ->
            if (request.method == "GET") slotResponse(account, version = 1)
            else SyncHttpResponse(409, """{"currentVersion":42}""")
        }
        val (repo, selection) = repository(http)
        selection.setChecked("hetzner", true)

        val result = repo.push(passphrase, localHosts)
        assertEquals(SyncRepository.PushResult.Conflict(42), result)
        // Bounded: 1 + MAX_CONFLICT_RETRIES attempts, each a GET plus a PUT.
        assertEquals(
            2 * (SyncRepository.MAX_CONFLICT_RETRIES + 1),
            http.requests.size,
        )
    }

    @Test
    fun `a fresh device push re-uploads the account instead of wiping it`() = runTest {
        // The self-healing flow: this device has NO local hosts, so without the
        // auto-tick its first "Sync now" would upload an empty list and destroy
        // the account for every other device.
        val account = envelopeOf(
            SyncHostEntry("a", "a.example", 22, "u"),
            SyncHostEntry("b", "b.example", 22, "u"),
        )
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) slotResponse(account, version = 5) else SyncHttpResponse(200, """{"version":6}""")
        }
        val (repo, _) = repository(http)

        val result = repo.push(passphrase, localHosts = emptyList()) as SyncRepository.PushResult.Ok
        assertEquals(2, result.uploaded)

        val hosts = parseSyncPayload(
            SyncCrypto.decryptEnvelope(JSONObject(http.requests[1].body!!).getString("data"), passphrase),
        )
        assertEquals(listOf("a", "b"), hosts.map { it.name })
    }

    @Test
    fun `a signed-out push fails rather than throwing`() = runTest {
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(200, "{}"))
        val selection = SyncSelectionStore(context, prefsFile)
        val repo = SyncRepository(
            SyncApiClient(
                auth = object : IdTokenSource {
                    override suspend fun idToken(forceRefresh: Boolean): String =
                        throw NotSignedInError("not signed in")
                },
                http = http,
                dispatcher = Dispatchers.Unconfined,
            ),
            selection,
        )
        assertEquals(
            SyncRepository.PushResult.Failed("not signed in"),
            repo.push(passphrase, localHosts),
        )
    }

    @Test
    fun `an empty passphrase never produces an upload`() = runTest {
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) SyncHttpResponse(404, "{}") else SyncHttpResponse(200, """{"version":1}""")
        }
        val (repo, selection) = repository(http)
        selection.setChecked("hetzner", true)

        val result = repo.push("", localHosts)
        assertTrue(result is SyncRepository.PushResult.Failed)
        // The GET happened; the PUT must not have.
        assertTrue(http.requests.none { it.method == "PUT" })
    }

    /** A [SyncRepository] over a scripted HTTP client and a real selection store. */
    private fun repository(http: SyncHttpClient): Pair<SyncRepository, SyncSelectionStore> {
        val selection = SyncSelectionStore(context, prefsFile)
        val api = SyncApiClient(
            auth = object : IdTokenSource {
                override suspend fun idToken(forceRefresh: Boolean): String = "id-token"
            },
            http = http,
            dispatcher = Dispatchers.Unconfined,
        )
        return SyncRepository(api, selection) to selection
    }

    private fun envelopeOf(vararg hosts: SyncHostEntry): String =
        SyncCrypto.encryptToEnvelope(serializeSyncPayload(hosts.toList()), passphrase)

    private fun slotResponse(envelope: String, version: Int): SyncHttpResponse =
        SyncHttpResponse(
            200,
            JSONObject().put("slot", "main").put("version", version).put("data", envelope).toString(),
        )

    private fun host(id: Long, name: String, hostname: String, port: Int, user: String) =
        HostEntity(id = id, name = name, hostname = hostname, port = port, username = user, keyId = 1)
}
