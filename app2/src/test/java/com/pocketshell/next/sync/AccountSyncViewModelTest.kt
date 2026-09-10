package com.pocketshell.next.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * [AccountSyncViewModel] over a real Room `hosts` table, a real selection
 * store, real crypto, and a scripted HTTP client (issue #2633).
 *
 * The containment tests are the reason this file exists. "Tokens never reach
 * the UI layer" and "the passphrase is never persisted" are the two properties
 * the desktop app gets structurally (tokens live in a different OS process
 * from the renderer); on Android there is one process, so they have to be
 * asserted instead of assumed — against the ACTUAL rendered state and the
 * ACTUAL files on disk, not against a comment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AccountSyncViewModelTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val prefsFile = "test-sync-vm-selection"
    private val passphrase = "correct horse battery staple"

    private val idToken = "eyJhbGciOiJSUzI1NiJ9.the-id-token-nobody-should-see.signature"
    private val refreshToken = "1//0gRefreshTokenNobodyShouldSee"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(prefsFile)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteSharedPreferences(prefsFile)
        Dispatchers.resetMain()
    }

    @Test
    fun `projects the saved hosts as tickable rows`() = runTest {
        seedHosts()
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(404, "{}"))
        val vm = subscribed(http)

        val state = vm.state.first { it.hosts.isNotEmpty() }
        assertEquals(listOf("builder", "hetzner"), state.hosts.map { it.name })
        assertEquals("alexey@135.181.114.209:22", state.hosts.first { it.name == "hetzner" }.subtitle)
        assertTrue(state.hosts.none { it.checked })
    }

    @Test
    fun `ticking a host persists the selection`() = runTest {
        seedHosts()
        val vm = subscribed(RecordingSyncHttpClient.scripted(SyncHttpResponse(404, "{}")))
        vm.state.first { it.hosts.isNotEmpty() }

        vm.setHostChecked("hetzner", true)

        assertTrue(vm.state.value.hosts.first { it.name == "hetzner" }.checked)
        // A NEW store over the same file: the tick survived the write, which is
        // what stops a relaunch from pushing an empty set over the account.
        assertEquals(listOf("hetzner"), SyncSelectionStore(context, prefsFile).selected.value)
    }

    @Test
    fun `syncing with no passphrase refuses before touching the network`() = runTest {
        seedHosts()
        val http = RecordingSyncHttpClient.scripted(SyncHttpResponse(200, "{}"))
        val vm = subscribed(http)

        vm.push("")

        assertEquals(
            SyncOutcome.Failed("Enter your sync passphrase first."),
            vm.state.value.outcome,
        )
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `a successful sync reports what was uploaded`() = runTest {
        seedHosts()
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) SyncHttpResponse(404, "{}") else SyncHttpResponse(200, """{"version":1}""")
        }
        val vm = subscribed(http)
        vm.state.first { it.hosts.isNotEmpty() }
        vm.setHostChecked("hetzner", true)

        vm.push(passphrase)

        assertEquals(SyncOutcome.Pushed(uploaded = 1, version = 1), vm.state.value.outcome)
    }

    @Test
    fun `pulling surfaces account-only aliases as rows the user can untick`() = runTest {
        seedHosts()
        val account = SyncCrypto.encryptToEnvelope(
            serializeSyncPayload(listOf(SyncHostEntry("laptop-only", "10.1.1.1", 22, "alexey"))),
            passphrase,
        )
        val http = RecordingSyncHttpClient.scripted(
            SyncHttpResponse(
                200,
                JSONObject().put("slot", "main").put("version", 2).put("data", account).toString(),
            ),
        )
        val vm = subscribed(http)
        vm.state.first { it.hosts.isNotEmpty() }

        vm.pull(passphrase)

        val state = vm.state.value
        assertEquals(SyncOutcome.Pulled(1), state.outcome)
        val accountOnly = state.hosts.single { it.accountOnly }
        assertEquals("laptop-only", accountOnly.name)
        // Auto-ticked, because this device has never materialised it — that is
        // what stops the next push from deleting it from the account.
        assertTrue(accountOnly.checked)
    }

    @Test
    fun `a wrong passphrase fails closed with a message`() = runTest {
        seedHosts()
        val account = SyncCrypto.encryptToEnvelope("""{"hosts":[]}""", passphrase)
        val http = RecordingSyncHttpClient.scripted(
            SyncHttpResponse(
                200,
                JSONObject().put("slot", "main").put("version", 1).put("data", account).toString(),
            ),
        )
        val vm = subscribed(http)

        vm.pull("not the passphrase")

        val outcome = vm.state.value.outcome
        assertTrue(outcome is SyncOutcome.Failed)
        assertTrue((outcome as SyncOutcome.Failed).message.contains("wrong passphrase"))
    }

    /* --- containment -------------------------------------------------------- */

    @Test
    fun `no token ever reaches the rendered UI state`() = runTest {
        seedHosts()
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) SyncHttpResponse(404, "{}") else SyncHttpResponse(200, """{"version":1}""")
        }
        val vm = subscribed(http)
        vm.state.first { it.hosts.isNotEmpty() }
        vm.setHostChecked("hetzner", true)
        vm.push(passphrase)

        // Everything the screen can read, rendered exhaustively.
        val rendered = vm.state.value.toString()
        assertFalse("the ID token reached the UI state:\n$rendered", rendered.contains(idToken))
        assertFalse("the refresh token reached the UI state:\n$rendered", rendered.contains(refreshToken))
        assertFalse("the passphrase reached the UI state:\n$rendered", rendered.contains(passphrase))
        // The Authorization header did carry the token — i.e. the sync really
        // happened, so the absence above is containment and not a no-op.
        assertTrue(http.requests.any { it.headers["Authorization"] == "Bearer $idToken" })
    }

    @Test
    fun `the passphrase is never written to any app preferences file`() = runTest {
        seedHosts()
        val http = RecordingSyncHttpClient { _, index ->
            if (index == 0) SyncHttpResponse(404, "{}") else SyncHttpResponse(200, """{"version":1}""")
        }
        val vm = subscribed(http)
        vm.state.first { it.hosts.isNotEmpty() }
        vm.setHostChecked("hetzner", true)
        vm.push(passphrase)

        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val files = prefsDir.listFiles().orEmpty()
        assertTrue("expected preference files to have been written", files.isNotEmpty())
        files.forEach { file ->
            val text = file.readText()
            assertFalse("passphrase persisted in ${file.name}:\n$text", text.contains(passphrase))
            assertFalse("ID token persisted in cleartext in ${file.name}", text.contains(idToken))
        }
    }

    /* --- harness ------------------------------------------------------------ */

    private suspend fun seedHosts() {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/dev/null", fingerprint = "fp", hasPassphrase = false),
        )
        db.hostDao().insert(
            HostEntity(name = "hetzner", hostname = "135.181.114.209", port = 22, username = "alexey", keyId = keyId),
        )
        db.hostDao().insert(
            HostEntity(name = "builder", hostname = "10.0.0.7", port = 2022, username = "root", keyId = keyId),
        )
    }

    /**
     * `state` is a `WhileSubscribed` `stateIn`, so with no collector it never
     * leaves its initial value and every assertion below would pass or fail on
     * a snapshot nothing produced. The screen is that collector in production;
     * this is it in a test, kept alive for the whole test by `backgroundScope`.
     */
    private fun kotlinx.coroutines.test.TestScope.subscribed(
        http: SyncHttpClient,
    ): AccountSyncViewModel = viewModel(http).also { vm ->
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { } }
    }

    private fun viewModel(http: SyncHttpClient): AccountSyncViewModel {
        val tokens = InMemorySyncTokenStore(
            StoredAuth(
                sub = "sub-123",
                email = "person@example.com",
                idToken = idToken,
                refreshToken = refreshToken,
                obtainedAtMs = 0,
                expiresInS = 3600,
            ),
        )
        val auth = GoogleAuth(
            tokens = tokens,
            http = RecordingSyncHttpClient.scripted(tokenResponse(idToken)),
            dispatcher = Dispatchers.Unconfined,
            clientId = "1035162854462-abc.apps.googleusercontent.com",
            now = { 0L },
        )
        val selection = SyncSelectionStore(context, prefsFile)
        val api = SyncApiClient(auth, http, Dispatchers.Unconfined)
        return AccountSyncViewModel(
            coordinator = SyncSignInCoordinator(
                auth = auth,
                launcher = { _, _ -> },
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            ),
            auth = auth,
            repository = SyncRepository(api, selection),
            selection = selection,
            hostDao = db.hostDao(),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }
}
