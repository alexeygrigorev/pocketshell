package com.pocketshell.next.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.appGraph
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journey J11 — share a file from another app into PocketShell and watch it land
 * on a real host over real SFTP (rewrite task P-9).
 *
 * ## Why this has to be a device journey
 *
 * `ShareUploaderTest` drives the same uploader over `core-transport`'s in-memory
 * SFTP fixture and cannot see any of what breaks here: an `ACTION_SEND` intent
 * the manifest filter does not actually match, an activity that is not exported,
 * a `ContentResolver` read that returns nothing on a real device, an
 * `$HOME`-expanding `mkdir` that a non-interactive exec channel word-splits
 * differently, or an SFTP write to a path the server refuses. Everything from
 * the delivered intent to the bytes on the host is production code here.
 *
 * ## The oracle is the host, not the app
 *
 * The load-bearing assertion is an INDEPENDENT `cat` over the journey's own SSH
 * connection ([AgentsFixture.exec]) after the upload. A screen that reported a
 * path it never wrote to would pass an in-app assertion and fail this one.
 *
 * ## What this journey does NOT cover
 *
 * The share sheet's own `content://` grant. The intent is built and delivered in
 * process with a `file://` URI to the app's own cache (StrictMode's file-URI
 * detection is switched off for that, in [relaxFileUriPolicy]) because hosting a
 * second app purely to be the sender is not something an instrumentation run can
 * do. Everything downstream of the delivered intent — the manifest filter, the
 * decode, the `ContentResolver` read, the host pick, the SFTP write — is the
 * real path. `ShareIntentDecodeTest` covers the `content://`-shaped intents the
 * decoder sees from real senders.
 *
 * ## Fixture
 *
 * The Docker `agents` fixture on `10.0.2.2:2222` (see [AgentsFixture]). Bring it
 * up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J11ShareUploadJourney {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    /**
     * Empty rule, not `createAndroidComposeRule<ShareActivity>()`: the subject
     * IS the intent, and the activity rule would launch with a bare one (which
     * this activity correctly refuses and finishes). Each test launches its own
     * scenario with the share intent under test.
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    private var fixtureHostId: Long = 0
    private var otherHostId: Long = 0

    @Before
    // VOID BLOCK BODY, not `fun seed() = runBlocking { … }` (V1,
    // scripts/check-test-validity.sh). An expression-body @Before/@Test is one
    // refactor away from returning a non-Unit value, and JUnit rejects the WHOLE
    // class at load with InvalidTestClassError when it does — silently, so the
    // journey simply never runs.
    fun seed() {
        runBlocking {
            relaxFileUriPolicy()

            val graph = appGraph()
            graph.connectionsRegistry().closeAll()
            graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
            graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

            val fingerprint = AgentsFixture.probeHostKeyFingerprint()
            println("J11_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

            // Removed, not just created: a rerun that found the previous run's file
            // would pass its "the host holds the bytes" assertion without uploading.
            AgentsFixture.exec("rm -rf \"\$HOME/inbox/pocketshell\"")

            val keyPath = AgentsFixture.installPrivateKey(fileName = "j11_fixture_key")
            val keyId = graph.sshKeyDao().insert(
                SshKeyEntity(name = "j11-key", privateKeyPath = keyPath),
            )
            fixtureHostId = graph.hostDao().insert(
                HostEntity(
                    name = "docker-fixture",
                    hostname = AgentsFixture.host,
                    port = AgentsFixture.port,
                    username = AgentsFixture.USER,
                    keyId = keyId,
                    trustedHostKeyAlgorithm = "SHA256",
                    trustedHostKeySha256 = fingerprint,
                ),
            )
            otherHostId = 0
        }
    }

    /**
     * The headline journey: one file, one configured host, no picker — the file
     * is on the host when the screen says it is.
     */
    @Test
    fun aSharedFileLandsInTheHostInboxOverRealSftp() {
        val payload = "shared from the phone at ${System.nanoTime()}\n"
        val uri = stageLocalFile("release-notes.txt", payload)

        ActivityScenario.launch<ShareActivity>(sendIntent(uri, "release-notes.txt")).use {
            // One configured host: the picker is skipped and the upload starts
            // by itself, which is the whole point of the shortcut.
            awaitUploadSucceeded()
            JourneyScreenshots.capture("01-uploaded", JOURNEY)

            val remotePath = remotePathOnScreen()
            // THE load-bearing assertion: an INDEPENDENT connection reads the
            // file the app claims to have written.
            assertEquals(payload, AgentsFixture.exec("cat '$remotePath'"))
            assertTrue(
                "the file must be in the inbox, got $remotePath",
                remotePath.endsWith("/inbox/pocketshell/${remotePath.substringAfterLast('/')}") &&
                    remotePath.endsWith("-release-notes.txt"),
            )
        }
    }

    /** Two hosts, no live connection: the user picks, and the pick is honoured. */
    @Test
    fun withSeveralHostsThePickerIsShownAndTheChosenHostReceivesTheFile() {
        seedSecondHost()
        val payload = "picked deliberately ${System.nanoTime()}\n"
        val uri = stageLocalFile("picked.txt", payload)

        ActivityScenario.launch<ShareActivity>(sendIntent(uri, "picked.txt")).use {
            awaitTag(shareHostRowTag(fixtureHostId))
            JourneyScreenshots.capture("02-picker", JOURNEY)
            // The unreachable host is on screen too — the picker must not
            // silently drop hosts it cannot reach.
            compose.onNodeWithText("unreachable").assertIsDisplayed()

            compose.onNodeWithTag(shareHostRowTag(fixtureHostId)).performClick()

            awaitUploadSucceeded()
            JourneyScreenshots.capture("03-uploaded-after-pick", JOURNEY)
            val remotePath = remotePathOnScreen()
            assertEquals(payload, AgentsFixture.exec("cat '$remotePath'"))
        }
    }

    /** A multi-file share puts EVERY file on the host, not just the first. */
    @Test
    fun aMultiFileShareUploadsEveryFile() {
        val first = "first file ${System.nanoTime()}\n"
        val second = "second file ${System.nanoTime()}\n"
        val uris = arrayListOf(
            stageLocalFile("one.txt", first),
            stageLocalFile("two.txt", second),
        )
        val intent = Intent(context(), ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        ActivityScenario.launch<ShareActivity>(intent).use {
            awaitUploadSucceeded()
            JourneyScreenshots.capture("04-multi-uploaded", JOURNEY)

            // Read the whole inbox back from the host: both files, both intact.
            val listing = AgentsFixture.exec("ls \"\$HOME/inbox/pocketshell\"").trim().lines()
            assertEquals("expected two files in the inbox, got $listing", 2, listing.size)
            val contents = listing
                .map { AgentsFixture.exec("cat \"\$HOME/inbox/pocketshell/$it\"") }
                .toSet()
            assertEquals(setOf(first, second), contents)
            assertTrue(
                "both original names must survive, got $listing",
                listing.any { it.endsWith("-one.txt") } && listing.any { it.endsWith("-two.txt") },
            )
        }
    }

    // ------------------------------------------------------------- helpers

    private fun seedSecondHost() = runBlocking {
        val graph = appGraph()
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j11-other-key", privateKeyPath = "/dev/null"),
        )
        otherHostId = graph.hostDao().insert(
            HostEntity(
                name = "unreachable",
                hostname = "127.0.0.1",
                port = 1,
                username = "nobody",
                keyId = keyId,
            ),
        )
    }

    /** Writes [content] into the app's cache and returns a URI a sender would send. */
    private fun stageLocalFile(name: String, content: String): Uri {
        val dir = File(context().cacheDir, "j11-share").also { it.mkdirs() }
        val file = File(dir, name)
        file.writeText(content)
        return Uri.fromFile(file)
    }

    private fun sendIntent(uri: Uri, title: String): Intent =
        Intent(context(), ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
        }

    /**
     * The absolute remote path the success screen is showing.
     *
     * Read off the RENDERED tree rather than recomputed: the path the user is
     * told about is the thing under test, and re-deriving it here would let a
     * screen that shows one path and writes another pass.
     */
    private fun remotePathOnScreen(): String {
        val texts = compose
            .onAllNodesWithText("/inbox/pocketshell/", substring = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }
        val path = texts.firstOrNull { it.startsWith("/") && it.contains("/inbox/pocketshell/") }
        return checkNotNull(path) {
            "the success screen must show the absolute remote path; on screen: $texts"
        }
    }

    private fun awaitTag(tag: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Waits for the share to REACH A CONCLUSION, then fails with what the screen
     * actually says if that conclusion is a failure.
     *
     * Waiting for the success tag alone produces a 60-second "condition still not
     * satisfied" with no clue why — the screen's own error message (a refused
     * dial, an untrusted key, a `mkdir` the host rejected) is the one piece of
     * information a failing run needs, so it is read off the tree and put in the
     * assertion message.
     */
    private fun awaitUploadSucceeded(timeoutMs: Long = 90_000) {
        // The screen itself must exist first; if THIS times out the harness is
        // not seeing the composition at all, which is a different bug from a
        // failed upload.
        awaitTag(SHARE_SCREEN_TAG, timeoutMs = 20_000)
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithTag(SHARE_SUCCESS_TAG).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag(SHARE_FAILURE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag(SHARE_FAILURE_TAG).fetchSemanticsNodes().isNotEmpty()) {
            JourneyScreenshots.capture("99-failure", JOURNEY)
            throw AssertionError("the share failed on device: ${textsOnScreen()}")
        }
    }

    private fun textsOnScreen(): List<String> = compose.onAllNodesWithTag(SHARE_SCREEN_TAG)
        .fetchSemanticsNodes()
        .flatMap { it.collectTexts() }

    private fun androidx.compose.ui.semantics.SemanticsNode.collectTexts(): List<String> =
        buildList {
            config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            children.forEach { addAll(it.collectTexts()) }
        }

    private fun context(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Turns off StrictMode's file-URI exposure detection for this process.
     *
     * The journey has to BE the sending app, and the only URI it can hand over
     * without a second installed app is a `file://` one to its own cache. On a
     * device the sender is a different app supplying `content://`, which is what
     * `ShareIntentDecodeTest` covers; nothing downstream of the decode behaves
     * differently, because both go through the same `ContentResolver`.
     */
    private fun relaxFileUriPolicy() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    }

    private companion object {
        const val JOURNEY = "j11-share-upload"
    }
}
