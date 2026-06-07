package com.pocketshell.app.share

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected E2E for issue #193: the share-target "paste into active
 * session" branch.
 *
 * The journey under test mirrors the feedback report that opened the
 * issue:
 *
 *  1. The user already has a live `tmux -CC` client attached on their
 *     phone (registered in the production [ActiveTmuxClients] singleton).
 *  2. They trigger an Android text share into PocketShell.
 *  3. The two-option dialog should now ENABLE "Paste into session" (the
 *     bug was that it was always disabled because
 *     [ShareViewModel.hasAttachedSession] was hardcoded `false`).
 *  4. Tapping "Paste into session" shows the host picker filtered to
 *     attached hosts. Tapping a host routes the text through
 *     `send-keys -l` to that host's active pane.
 *  5. Reading the pane content back over a fresh SSH `exec` shows the
 *     pasted text on the remote PTY.
 *
 * The fixture matches [ShareTargetE2eTest] (Docker `agents` service on
 * port 2222, in-memory Room database with a single seeded host) plus a
 * production tmux client opened by the test and registered against the
 * shared [ActiveTmuxClients] singleton via [ShareTestAccessEntryPoint].
 */
@RunWith(AndroidJUnit4::class)
class SharePasteIntoSessionE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<ShareActivity>? = null
    private var tmuxClient: TmuxClient? = null
    private var sshSession: SshSession? = null
    private var registeredHostId: Long? = null
    private var registeredSessionName: String = ""

    @After
    fun teardown() {
        launchedActivity?.close()
        launchedActivity = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext.applicationContext
        registeredHostId?.let { hostId ->
            runCatching {
                EntryPointAccessors
                    .fromApplication(ctx, ShareTestAccessEntryPoint::class.java)
                    .activeTmuxClients()
                    .forceUnregister(hostId)
            }
        }
        registeredHostId = null
        runCatching { tmuxClient?.close() }
        tmuxClient = null
        runCatching { sshSession?.close() }
        sshSession = null
        if (registeredSessionName.isNotBlank()) {
            // Best-effort: kill the tmux session we attached so re-runs
            // start clean. The Docker fixture is shared with sibling
            // tests; we must not leak named sessions.
            runCatching {
                runBlocking {
                    val key = readTestKeyOrNull() ?: return@runBlocking
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = SshKey.Pem(key),
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 10_000,
                    ).getOrNull()?.use { session ->
                        session.exec(
                            "tmux kill-session -t '$registeredSessionName' 2>/dev/null || true",
                        )
                    }
                }
            }
        }
        registeredSessionName = ""
    }

    @Test
    fun shareIntentPastesTextIntoAttachedSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = System.currentTimeMillis().toString()
        val sessionName = "issue193-$marker"
        registeredSessionName = sessionName
        val payload = "share-paste-$marker"
        val hostId = seedHost(targetContext, key, marker)
        val hostName = "Share Paste Docker $marker"

        // Bring up a real `tmux -CC` client against the Docker fixture
        // and register it against the production singleton — this is
        // the state the user is in when they trigger the share intent.
        val entryPoint = EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            ShareTestAccessEntryPoint::class.java,
        )
        val activeClients: ActiveTmuxClients = entryPoint.activeTmuxClients()
        val tmuxFactory: TmuxClientFactory = entryPoint.tmuxClientFactory()

        val ssh = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        sshSession = ssh
        // Make sure no stale session lingers.
        runCatching { ssh.exec("tmux kill-session -t '$sessionName' 2>/dev/null || true") }

        val client = tmuxFactory.create(session = ssh, sessionName = sessionName)
        tmuxClient = client
        client.connect()

        // Resolve the active pane id so we have a stable reference to
        // read from after the paste lands.
        val paneId = withTimeout(10_000) {
            var resp = client.sendCommand("display-message -p '#{pane_id}'")
            var attempts = 0
            while (resp.isError && attempts < 10) {
                delay(100)
                resp = client.sendCommand("display-message -p '#{pane_id}'")
                attempts += 1
            }
            assertFalse(
                "expected display-message -p '#{pane_id}' to succeed, got ${resp.output}",
                resp.isError,
            )
            val id = resp.output.firstOrNull()?.trim().orEmpty()
            assertTrue(
                "expected pane id starting with %, got '$id'",
                id.startsWith("%"),
            )
            id
        }

        activeClients.register(
            hostId = hostId,
            hostName = hostName,
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyPath = "/tmp/${marker}-test-key",
            client = client,
        )
        registeredHostId = hostId

        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                setClassName(targetContext, ShareActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload)
                putExtra(Intent.EXTRA_SUBJECT, "issue193")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            launchedActivity = ActivityScenario.launch(shareIntent)
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_PICKER_ROOT_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            // The dispatch dialog should now render with the Paste
            // button ENABLED because the production registry has at
            // least one entry (the tmux client we just registered).
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_TEXT_PASTE_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(SHARE_TEXT_PASTE_TAG, useUnmergedTree = true)
                .performClick()

            // The picker should now filter to attached hosts only.
            val hostTag = SHARE_HOST_ROW_TAG_PREFIX + hostId
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(hostTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostTag, useUnmergedTree = true).performClick()

            compose.waitUntil(timeoutMillis = 30_000) {
                val success = compose
                    .onAllNodesWithTag(SHARE_RESULT_SUCCESS_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
                val failure = compose
                    .onAllNodesWithTag(SHARE_RESULT_FAILURE_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
                success || failure
            }
            val showedFailure = compose
                .onAllNodesWithTag(SHARE_RESULT_FAILURE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (showedFailure) {
                val detailText = compose
                    .onAllNodesWithTag(SHARE_RESULT_DETAIL_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .joinToString(" / ") { it.toString() }
                throw AssertionError(
                    "paste reported a failure state in the share UI: $detailText",
                )
            }

            // Confirm the text actually arrived in the pane. We poll
            // `capture-pane -p` because send-keys is asynchronous from
            // tmux's perspective; the text might land a beat after the
            // UI surface flips to "Success".
            val arrived = withTimeout(15_000) {
                pollForPayloadInPane(client, paneId, payload)
            }
            assertNotNull("expected the pasted text to appear in pane $paneId", arrived)
        } finally {
            // teardown() unregisters and closes; nothing extra here.
        }
        Unit
    }

    private suspend fun pollForPayloadInPane(
        client: TmuxClient,
        paneId: String,
        payload: String,
    ): String? {
        while (true) {
            val response = client.sendCommand("capture-pane -p -t $paneId")
            if (!response.isError) {
                val joined = response.output.joinToString("\n")
                if (joined.contains(payload)) return joined
            }
            delay(250)
        }
    }

    private fun readTestKeyOrNull(): String? = runCatching {
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
    }.getOrNull()

    private suspend fun seedHost(context: Context, key: String, marker: String): Long {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = context,
                sshKeyDao = db.sshKeyDao(),
                name = "share-paste-test-key-$marker",
                content = key,
            )
            return db.hostDao().insert(
                HostEntity(
                    name = "Share Paste Docker $marker",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}
