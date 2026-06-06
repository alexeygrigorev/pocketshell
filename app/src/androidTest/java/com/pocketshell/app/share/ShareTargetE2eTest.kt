package com.pocketshell.app.share

import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end connected-emulator test for the Android share-target
 * upload path (issue #138 acceptance: "drives the share intent ->
 * host picker -> upload -> verifies file exists on the Docker remote
 * at the expected path with the expected contents").
 *
 * Mirrors the shape of [EmulatorDockerSshSmokeTest] — same Docker
 * fixture, same emulator host alias, same in-memory database setup
 * that pre-seeds a single host. The difference is the launching
 * intent: we hand `ShareActivity` an `ACTION_SEND` payload built from
 * a temp file under cache, then drive the picker to the seeded host
 * and verify the SCP upload landed under `~/inbox/pocketshell/`.
 */
@RunWith(AndroidJUnit4::class)
class ShareTargetE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<ShareActivity>? = null
    private var stagedFile: File? = null
    private val stagedFiles = mutableListOf<File>()

    // Issue #507: live `tmux -CC` client + registry handle for the
    // open-session-project journey. Torn down here so a re-run starts
    // clean and the shared Docker fixture is not leaked a named session.
    private var tmuxClient: TmuxClient? = null
    private var sshSession: SshSession? = null
    private var registeredHostId: Long? = null
    private var registeredSessionName: String = ""

    @After
    fun teardown() {
        launchedActivity?.close()
        launchedActivity = null
        stagedFile?.delete()
        stagedFile = null
        stagedFiles.forEach { it.delete() }
        stagedFiles.clear()

        registeredHostId?.let { hostId ->
            runCatching {
                val ctx = InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .applicationContext
                EntryPointAccessors
                    .fromApplication(ctx, ShareTestAccessEntryPoint::class.java)
                    .activeTmuxClients()
                    .unregister(hostId)
            }
        }
        registeredHostId = null
        runCatching { tmuxClient?.close() }
        tmuxClient = null
        runCatching { sshSession?.close() }
        sshSession = null
        if (registeredSessionName.isNotBlank()) {
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
    fun shareIntentUploadsFileToHostInbox() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = "psshare${System.currentTimeMillis()}"
        val hostId = seedHost(targetContext, key, marker)
        // Stage the file under the *target app's* cache directory.
        // The FileProvider lives in the target app's manifest, runs
        // in the target app's process (same process the
        // instrumentation test executes in), and its configured paths
        // resolve relative to the target app's data dir.
        val (sharedFile, fileContents) = stageSharedFile(targetContext, marker)
        stagedFile = sharedFile

        try {
            cleanInboxOnRemote(key)
            // FileProvider authority is the *target* app's
            // applicationId — the provider component is declared in
            // the production manifest so it runs in the same process
            // as the staged file and `ContentResolver.openInputStream`
            // can serve it without a cross-process FD handoff.
            val sharedUri = FileProvider.getUriForFile(
                targetContext,
                "${targetContext.packageName}.shareprovider",
                sharedFile,
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                setClassName(targetContext, ShareActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, sharedUri)
                putExtra(Intent.EXTRA_TITLE, sharedFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            launchedActivity = ActivityScenario.launch(shareIntent)
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_PICKER_ROOT_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val hostTag = SHARE_HOST_ROW_TAG_PREFIX + hostId
            try {
                compose.waitUntil(timeoutMillis = 15_000) {
                    compose.onAllNodesWithTag(hostTag, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            } catch (e: Throwable) {
                val allTags = compose.onAllNodesWithTag(SHARE_PICKER_ROOT_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .joinToString { it.toString() }
                throw IllegalStateException(
                    "host row $hostTag never appeared; picker tree: $allTags",
                    e,
                )
            }
            compose.onNodeWithTag(hostTag, useUnmergedTree = true).performClick()
            // Issue #473: the host tap now opens the target chooser;
            // pick "Host inbox" to keep the original upload path.
            clickHostInboxTarget()

            try {
                compose.waitUntil(timeoutMillis = 60_000) {
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
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "upload result tag never appeared after picker click",
                    e,
                )
            }
            // Surface the failure path as a clean assertion message,
            // including the detail text so the reviewer can tell at a
            // glance whether the upload was rejected by the remote.
            val showedFailure = compose
                .onAllNodesWithTag(SHARE_RESULT_FAILURE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (showedFailure) {
                val detailText = compose
                    .onAllNodesWithTag(SHARE_RESULT_DETAIL_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .joinToString(" / ") { it.toString() }
                throw AssertionError("upload reported a failure state in the share UI: $detailText")
            }

            val remoteListing = withTimeout(20_000) {
                pollRemoteUntilFileExists(key, marker)
            }
            assertNotNull(
                "expected at least one share-target file under ~/inbox/pocketshell/ on remote",
                remoteListing,
            )
            val remotePath = remoteListing!!
            assertTrue(
                "expected the remote path to live under ~/inbox/pocketshell/ but got $remotePath",
                remotePath.contains("/inbox/pocketshell/"),
            )
            assertTrue(
                "expected the remote filename to carry the marker '$marker' but got $remotePath",
                remotePath.contains(marker),
            )
            // The success surface copies the user-visible display path
            // (`~/inbox/pocketshell/<name>`), while [remotePath] is the
            // `$HOME`-expanded form used for the remote `cat`. Compare the
            // clipboard against the display form built from the same
            // remote filename.
            val displayPath = "~/inbox/pocketshell/" + remotePath.substringAfterLast('/')
            compose.onNodeWithTag(SHARE_RESULT_COPY_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                clipboardText(targetContext) == displayPath
            }

            val readBack = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec("cat \"$remotePath\"")
            }
            assertEquals(
                "expected uploaded contents to round-trip from the remote, got stderr='${readBack.stderr}'",
                0,
                readBack.exitCode,
            )
            assertEquals(
                "expected uploaded contents to match the local payload",
                fileContents,
                readBack.stdout,
            )
        } finally {
            // Best-effort cleanup so re-runs don't accumulate junk on
            // the Docker remote.
            cleanInboxOnRemote(key)
        }
        Unit
    }

    /**
     * Issue #258: selecting several files and sharing them must upload
     * EVERY one, not just the first. Drives an `ACTION_SEND_MULTIPLE`
     * intent carrying three staged files, picks the seeded host, and
     * asserts all three land under `~/inbox/pocketshell/` with their
     * round-tripped contents.
     */
    @Test
    fun shareMultipleIntentUploadsEveryFileToHostInbox() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = "psmulti${System.currentTimeMillis()}"
        val hostId = seedHost(targetContext, key, marker)

        // Stage three distinct files; each carries its own marker suffix
        // so we can verify all three land independently.
        val markers = listOf("$marker-a", "$marker-b", "$marker-c")
        val staged = markers.map { fileMarker ->
            val (file, contents) = stageSharedFile(targetContext, fileMarker)
            stagedFiles += file
            Triple(fileMarker, file, contents)
        }

        try {
            cleanInboxOnRemote(key)
            val uris = ArrayList(
                staged.map { (_, file, _) ->
                    FileProvider.getUriForFile(
                        targetContext,
                        "${targetContext.packageName}.shareprovider",
                        file,
                    )
                },
            )

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setClassName(targetContext, ShareActivity::class.java.name)
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            launchedActivity = ActivityScenario.launch(shareIntent)
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_PICKER_ROOT_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val hostTag = SHARE_HOST_ROW_TAG_PREFIX + hostId
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(hostTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostTag, useUnmergedTree = true).performClick()
            // Issue #473: pick "Host inbox" in the per-host target chooser.
            clickHostInboxTarget()

            compose.waitUntil(timeoutMillis = 90_000) {
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
                throw AssertionError("multi-file upload reported a failure state: $detailText")
            }

            // Every staged file must have landed under the inbox with
            // its own marker and round-tripped contents.
            for ((fileMarker, _, contents) in staged) {
                val remoteListing = withTimeout(30_000) {
                    pollRemoteUntilFileExists(key, fileMarker)
                }
                assertNotNull(
                    "expected file with marker '$fileMarker' under ~/inbox/pocketshell/",
                    remoteListing,
                )
                val remotePath = remoteListing!!
                assertTrue(
                    "expected remote path under ~/inbox/pocketshell/ but got $remotePath",
                    remotePath.contains("/inbox/pocketshell/"),
                )
                val readBack = SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                ).getOrThrow().use { session ->
                    session.exec("cat \"$remotePath\"")
                }
                assertEquals(
                    "expected contents of '$fileMarker' to round-trip, stderr='${readBack.stderr}'",
                    0,
                    readBack.exitCode,
                )
                assertEquals(
                    "expected uploaded contents of '$fileMarker' to match local payload",
                    contents,
                    readBack.stdout,
                )
            }

            // Sanity: exactly three files should be present in the inbox.
            val count = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec(
                    "ls -1 \"\$HOME/inbox/pocketshell\" 2>/dev/null | grep \"$marker\" | wc -l",
                )
            }
            assertEquals(
                "expected all 3 shared files in the inbox, listing said '${count.stdout.trim()}'",
                "3",
                count.stdout.trim(),
            )
        } finally {
            cleanInboxOnRemote(key)
        }
        Unit
    }

    /**
     * Issue #473: share a file targeting a specific PROJECT. Seeds a
     * watched project root on the host, drives the share intent → host
     * picker → target chooser → project row, then verifies the file
     * landed in `<project>/.inbox/<file>` on the Docker remote with its
     * round-tripped contents (and that `.inbox/` was created on demand).
     */
    @Test
    fun shareIntentUploadsFileToProjectInbox() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = "psproj${System.currentTimeMillis()}"
        // A project path under $HOME. `.inbox/` is created on demand by
        // the uploader; we resolve the absolute path for the assertions.
        val projectRelative = "psproject-$marker"
        val homePath = remoteHome(key)
        val projectPath = "$homePath/$projectRelative"
        val hostId = seedHostWithProject(targetContext, key, marker, projectPath)
        val (sharedFile, fileContents) = stageSharedFile(targetContext, marker)
        stagedFile = sharedFile

        try {
            cleanProjectOnRemote(key, projectPath)
            val sharedUri = FileProvider.getUriForFile(
                targetContext,
                "${targetContext.packageName}.shareprovider",
                sharedFile,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                setClassName(targetContext, ShareActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, sharedUri)
                putExtra(Intent.EXTRA_TITLE, sharedFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            launchedActivity = ActivityScenario.launch(shareIntent)
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_PICKER_ROOT_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val hostTag = SHARE_HOST_ROW_TAG_PREFIX + hostId
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(hostTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostTag, useUnmergedTree = true).performClick()

            // Issue #473: the per-host target chooser must surface the
            // seeded project row; tap it to route into <project>/.inbox/.
            val projectTag = SHARE_TARGET_PROJECT_ROW_TAG_PREFIX + projectPath
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(projectTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(projectTag, useUnmergedTree = true).performClick()

            compose.waitUntil(timeoutMillis = 60_000) {
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
                throw AssertionError("project upload reported a failure state: $detailText")
            }

            // The file must exist in <project>/.inbox/ on the remote.
            val remoteName = withTimeout(20_000) {
                pollRemoteUntilProjectFileExists(key, projectPath, marker)
            }
            assertNotNull(
                "expected a share file under $projectPath/.inbox/ on remote",
                remoteName,
            )
            val remotePath = "$projectPath/.inbox/$remoteName"
            val readBack = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec("cat \"$remotePath\"")
            }
            assertEquals(
                "expected project-inbox contents to round-trip, stderr='${readBack.stderr}'",
                0,
                readBack.exitCode,
            )
            assertEquals(
                "expected uploaded contents to match the local payload",
                fileContents,
                readBack.stdout,
            )
        } finally {
            cleanProjectOnRemote(key, projectPath)
        }
        Unit
    }

    /**
     * Issue #507: the share destination picker must offer the
     * current/open session's PROJECT (its active-pane cwd), not only the
     * top-level watched roots. This connected journey brings up a real
     * `tmux -CC` session whose pane is `cd`'d into a project directory on
     * the Docker fixture, registers it against the production
     * [ActiveTmuxClients] singleton, then drives the share intent and
     * verifies:
     *
     *  1. the picker surfaces the open-session project row
     *     ([SHARE_TARGET_ACTIVE_PROJECT_TAG]) — proving the destination
     *     list includes session projects, not just watched roots; and
     *  2. tapping it lands the file in that project's `.inbox/`.
     *
     * A screenshot of the destination picker (showing the session
     * project) is captured as authoritative UI evidence.
     */
    @Test
    fun shareIntentUploadsFileToOpenSessionProject() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = "pssession${System.currentTimeMillis()}"
        val sessionName = "issue507-$marker"
        registeredSessionName = sessionName
        val projectRelative = "psproject-$marker"
        val homePath = remoteHome(key)
        val projectPath = "$homePath/$projectRelative"
        val hostId = seedHost(targetContext, key, marker)
        val (sharedFile, fileContents) = stageSharedFile(targetContext, marker)
        stagedFile = sharedFile

        val entryPoint = EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            ShareTestAccessEntryPoint::class.java,
        )
        val activeClients: ActiveTmuxClients = entryPoint.activeTmuxClients()
        val tmuxFactory: TmuxClientFactory = entryPoint.tmuxClientFactory()

        try {
            // Create the project dir on the remote so tmux can `cd` into
            // it; clean any stale `.inbox/` from a prior run.
            cleanProjectOnRemote(key, projectPath)
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).getOrThrow().use { session ->
                    session.exec("mkdir -p \"$projectPath\"")
                }
            }

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
            runCatching { ssh.exec("tmux kill-session -t '$sessionName' 2>/dev/null || true") }

            // Start the session with its working directory set to the
            // project so `pane_current_path` resolves there.
            val client = tmuxFactory.create(
                session = ssh,
                sessionName = sessionName,
                startDirectory = projectPath,
            )
            tmuxClient = client
            client.connect()

            // Wait until the live client reports the project as the
            // active pane's cwd (tmux start-directory takes a beat).
            withTimeout(15_000) {
                while (true) {
                    val resp = client.sendCommand("display-message -p '#{pane_current_path}'")
                    val cwd = resp.output.firstOrNull()?.trim().orEmpty()
                    if (!resp.isError && cwd.trimEnd('/') == projectPath.trimEnd('/')) break
                    delay(200)
                }
            }

            activeClients.register(
                hostId = hostId,
                hostName = "Share Session Docker $marker",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = "/tmp/${marker}-test-key",
                client = client,
            )
            registeredHostId = hostId

            val sharedUri = FileProvider.getUriForFile(
                targetContext,
                "${targetContext.packageName}.shareprovider",
                sharedFile,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                setClassName(targetContext, ShareActivity::class.java.name)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, sharedUri)
                putExtra(Intent.EXTRA_TITLE, sharedFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            launchedActivity = ActivityScenario.launch(shareIntent)
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_PICKER_ROOT_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val hostTag = SHARE_HOST_ROW_TAG_PREFIX + hostId
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(hostTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostTag, useUnmergedTree = true).performClick()

            // Issue #507: the open-session project must surface as a
            // prominent quick target (NOT just the watched roots).
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_TARGET_ACTIVE_PROJECT_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            captureScreenshot("issue507-share-session-project-picker")

            compose.onNodeWithTag(SHARE_TARGET_ACTIVE_PROJECT_TAG, useUnmergedTree = true)
                .performClick()

            compose.waitUntil(timeoutMillis = 60_000) {
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
                throw AssertionError("session-project upload reported a failure: $detailText")
            }

            val remoteName = withTimeout(20_000) {
                pollRemoteUntilProjectFileExists(key, projectPath, marker)
            }
            assertNotNull(
                "expected a share file under $projectPath/.inbox/ on remote",
                remoteName,
            )
            val remotePath = "$projectPath/.inbox/$remoteName"
            val readBack = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec("cat \"$remotePath\"")
            }
            assertEquals(
                "expected session-project inbox contents to round-trip, stderr='${readBack.stderr}'",
                0,
                readBack.exitCode,
            )
            assertEquals(
                "expected uploaded contents to match the local payload",
                fileContents,
                readBack.stdout,
            )
        } finally {
            cleanProjectOnRemote(key, projectPath)
        }
        Unit
    }

    /**
     * Issue #560: an active session must be offered as a share destination,
     * and picking it must stage the shared file into that session's
     * `.pocketshell/attachments/host-<id>-<session>/` scope (the #544
     * mechanic) rather than an inbox folder. This connected journey brings up
     * a real `tmux -CC` session, registers it against the production
     * [ActiveTmuxClients], drives the share intent → host picker → ACTIVE
     * SESSION row, then verifies the file landed under the per-session
     * attachment scope on the Docker fixture.
     *
     * A screenshot of the destination picker (showing the active-session
     * row) is captured as authoritative UI evidence.
     */
    @Test
    fun shareIntentStagesFileIntoActiveSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = "psinto${System.currentTimeMillis()}"
        val sessionName = "issue560-$marker"
        registeredSessionName = sessionName
        val projectRelative = "psinto-$marker"
        val homePath = remoteHome(key)
        val projectPath = "$homePath/$projectRelative"
        val hostId = seedHost(targetContext, key, marker)
        val (sharedFile, fileContents) = stageSharedFile(targetContext, marker)
        stagedFile = sharedFile

        val entryPoint = EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            ShareTestAccessEntryPoint::class.java,
        )
        val activeClients: ActiveTmuxClients = entryPoint.activeTmuxClients()
        val tmuxFactory: TmuxClientFactory = entryPoint.tmuxClientFactory()

        // The per-session attachment scope the #544 mechanic uses.
        val attachmentScopeDir = "\$HOME/.pocketshell/attachments/host-$hostId-$sessionName"

        try {
            cleanProjectOnRemote(key, projectPath)
            runCatching { cleanAttachmentScope(key, hostId, sessionName) }
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).getOrThrow().use { session ->
                    session.exec("mkdir -p \"$projectPath\"")
                }
            }

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
            // Issue #560: the picker lists EVERY tmux session on the server
            // and leads with the focused one. The shared Docker fixture can
            // carry leftover sessions from sibling tests, so kill them all
            // first to guarantee this test's session is the sole (focused)
            // active session the picker surfaces.
            runCatching { ssh.exec("tmux kill-server 2>/dev/null || true") }
            delay(500)

            val client = tmuxFactory.create(
                session = ssh,
                sessionName = sessionName,
                startDirectory = projectPath,
            )
            tmuxClient = client
            client.connect()

            withTimeout(15_000) {
                while (true) {
                    val resp = client.sendCommand("display-message -p '#{pane_current_path}'")
                    val cwd = resp.output.firstOrNull()?.trim().orEmpty()
                    if (!resp.isError && cwd.trimEnd('/') == projectPath.trimEnd('/')) break
                    delay(200)
                }
            }

            activeClients.register(
                hostId = hostId,
                hostName = "Share Into Session Docker $marker",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = "/tmp/${marker}-test-key",
                client = client,
            )
            registeredHostId = hostId

            val sharedUri = FileProvider.getUriForFile(
                targetContext,
                "${targetContext.packageName}.shareprovider",
                sharedFile,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                setClassName(targetContext, ShareActivity::class.java.name)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, sharedUri)
                putExtra(Intent.EXTRA_TITLE, sharedFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            launchedActivity = ActivityScenario.launch(shareIntent)
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_PICKER_ROOT_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val hostTag = SHARE_HOST_ROW_TAG_PREFIX + hostId
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(hostTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostTag, useUnmergedTree = true).performClick()

            // Issue #560: the active session must surface as a destination
            // above the inbox folders.
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SHARE_TARGET_ACTIVE_SESSION_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            captureScreenshot("issue560-share-active-session-picker")

            compose.onNodeWithTag(SHARE_TARGET_ACTIVE_SESSION_TAG, useUnmergedTree = true)
                .performClick()

            // The file must land in the per-session attachment scope.
            val remoteName = withTimeout(30_000) {
                pollRemoteUntilAttachmentExists(key, attachmentScopeDir, marker)
            }
            assertNotNull(
                "expected a staged file under $attachmentScopeDir/ on remote",
                remoteName,
            )
            val remotePath = "$attachmentScopeDir/$remoteName"
            val readBack = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec("cat \"$remotePath\"")
            }
            assertEquals(
                "expected staged-into-session contents to round-trip, stderr='${readBack.stderr}'",
                0,
                readBack.exitCode,
            )
            assertEquals(
                "expected staged contents to match the local payload",
                fileContents,
                readBack.stdout,
            )
        } finally {
            cleanProjectOnRemote(key, projectPath)
            runCatching { cleanAttachmentScope(key, hostId, sessionName) }
        }
        Unit
    }

    private suspend fun pollRemoteUntilAttachmentExists(
        key: String,
        attachmentScopeDir: String,
        marker: String,
    ): String? {
        while (true) {
            val listing = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec(
                    "ls -1 \"$attachmentScopeDir\" 2>/dev/null | grep \"$marker\" | head -n 1",
                )
            }
            if (listing.exitCode == 0) {
                val name = listing.stdout.trim()
                if (name.isNotEmpty()) return name
            }
            delay(500)
        }
    }

    private suspend fun cleanAttachmentScope(key: String, hostId: Long, sessionName: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec(
                    "rm -rf \"\$HOME/.pocketshell/attachments/host-$hostId-$sessionName\"",
                )
            }
        }
    }

    private fun captureScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/share-target")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write screenshot to ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE507_SCREENSHOT ${file.absolutePath}")
    }

    private fun readTestKeyOrNull(): String? = runCatching {
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
    }.getOrNull()

    private fun clickHostInboxTarget() {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(SHARE_TARGET_HOST_INBOX_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(SHARE_TARGET_HOST_INBOX_TAG, useUnmergedTree = true).performClick()
    }

    private suspend fun remoteHome(key: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow().use { session ->
            session.exec("printf '%s' \"\$HOME\"")
        }
        return result.stdout.trim().ifEmpty { "/root" }
    }

    private suspend fun pollRemoteUntilProjectFileExists(
        key: String,
        projectPath: String,
        marker: String,
    ): String? {
        while (true) {
            val listing = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec(
                    "ls -1 \"$projectPath/.inbox\" 2>/dev/null | grep \"$marker\" | head -n 1",
                )
            }
            if (listing.exitCode == 0) {
                val name = listing.stdout.trim()
                if (name.isNotEmpty()) return name
            }
            delay(500)
        }
    }

    private suspend fun cleanProjectOnRemote(key: String, projectPath: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec("rm -rf \"$projectPath\"")
            }
        }
    }

    private suspend fun seedHostWithProject(
        context: Context,
        key: String,
        marker: String,
        projectPath: String,
    ): Long {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = context,
                sshKeyDao = db.sshKeyDao(),
                name = "share-project-test-key-$marker",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Share Docker",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            db.projectRootDao().insert(
                com.pocketshell.core.storage.entity.ProjectRootEntity(
                    hostId = hostId,
                    label = "Share Project",
                    path = projectPath,
                ),
            )
            return hostId
        } finally {
            db.close()
        }
    }

    private suspend fun pollRemoteUntilFileExists(key: String, marker: String): String? {
        while (true) {
            val listing = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec(
                    "ls -1 \"\$HOME/inbox/pocketshell\" 2>/dev/null | grep \"$marker\" | head -n 1",
                )
            }
            if (listing.exitCode == 0) {
                val name = listing.stdout.trim()
                if (name.isNotEmpty()) {
                    return "\$HOME/inbox/pocketshell/$name"
                }
            }
            delay(500)
        }
    }

    private suspend fun cleanInboxOnRemote(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow().use { session ->
                session.exec("rm -rf \"\$HOME/inbox/pocketshell\"")
            }
        }
    }

    private suspend fun seedHost(context: Context, key: String, marker: String): Long {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = context,
                sshKeyDao = db.sshKeyDao(),
                name = "share-target-test-key-$marker",
                content = key,
            )
            return db.hostDao().insert(
                HostEntity(
                    name = "Share Docker",
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

    private fun clipboardText(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }

    private fun stageSharedFile(context: Context, marker: String): Pair<File, String> {
        val cacheRoot = context.cacheDir
        // Use java.nio.Files for creation — File.mkdirs() can race
        // with the platform's lazy cacheDir creation in
        // instrumentation contexts and yield a deceptive
        // "doesn't exist" result. Files.createDirectories is
        // idempotent and throws on real failures, which is what we
        // actually want here.
        java.nio.file.Files.createDirectories(cacheRoot.toPath())
        val dir = File(cacheRoot, "share-target-tests")
        java.nio.file.Files.createDirectories(dir.toPath())
        val file = File(dir, "share-${marker}.txt")
        val contents = "pocketshell-share-target-payload-$marker\n"
        file.writeText(contents)
        return file to contents
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}
