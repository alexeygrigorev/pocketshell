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
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
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

    @After
    fun teardown() {
        launchedActivity?.close()
        launchedActivity = null
        stagedFile?.delete()
        stagedFile = null
        stagedFiles.forEach { it.delete() }
        stagedFiles.clear()
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
            compose.onNodeWithTag(SHARE_RESULT_COPY_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                clipboardText(targetContext) == remotePath
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
