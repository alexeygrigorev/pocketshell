package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.session.LastSessionStore
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1239 — connected end-to-end proof for the host-card one-tap
 * "Resume last session" affordance (AC1: "Host card exposes one-tap resume of
 * the last-attached session"; AC4: card-affordance evidence).
 *
 * The journey seeds a persisted `last_session` snapshot for host A (the same
 * store `MainActivity.onStop` writes), plus two saved hosts A and B. It launches
 * the real [MainActivity] (full Hilt ViewModel graph — no stubbing of the
 * production wiring), lands on the host list, and asserts:
 *
 *  1. The Resume row (`host:resume:<A.id>`) is DISPLAYED under host A — the card
 *     surfaces the one-tap resume affordance for the matching host.
 *  2. NO Resume row exists for host B (`host:resume:<B.id>`) — the affordance is
 *     scoped to the host the snapshot belongs to, so the other card falls back
 *     to normal navigation (AC1: no dead end / no spurious affordance).
 *  3. Tapping Resume LEAVES the host list (the `host-list:content` list is gone),
 *     i.e. it deep-links straight into the live session screen rather than the
 *     folder tree.
 *
 * No Docker / SSH fixture is required: the session screen renders optimistically
 * on navigation, so "left the host list" is observable regardless of whether the
 * background SSH handshake completes. The seeded host carries Ready bootstrap
 * columns + a fresh `lastBootstrapAt` so the cold-launch reprobe is a no-op and
 * never races this UI-only assertion.
 *
 * This test does NOT self-skip on CI (no `assumeTrue` / `assumeFalse(isRunningOnCi())`)
 * so it durably guards the affordance per-push once wired into
 * `scripts/ci-journey-suite.sh` (G3/G9).
 */
@RunWith(AndroidJUnit4::class)
class HostResumeLastSessionE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Grant runtime permissions before the activity launches so the system
    // permission dialog never steals focus from the Compose hierarchy.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        cleanupAppDatabase()
        clearLastSession()
    }

    @Test
    fun resumeRow_showsForMatchingHost_absentForOthers_andTapDeepLinksIntoSession() {
        cleanupAppDatabase()
        clearLastSession()

        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val nonce = System.nanoTime() % 100_000L
        val hostAName = "resume-A-$nonce"
        val hostBName = "resume-B-$nonce"

        val (hostAId, hostBId) = seedTwoHosts(hostAName, hostBName)

        // Seed the persisted last-session snapshot for host A — the exact store
        // MainActivity.onStop writes. Fresh `savedAtMillis` so it is inside the
        // 24h recency cap `LastSessionStore.peek` enforces.
        LastSessionStore(appContext).save(
            LastSessionStore.LastSession(
                hostId = hostAId,
                hostName = hostAName,
                hostname = "10.0.2.2",
                port = 2222,
                username = "testuser",
                keyPath = "/data/local/tmp/no-such-key-$nonce",
                sessionName = "resume-session-$nonce",
                startDirectory = null,
                composerDraft = "",
                savedAtMillis = System.currentTimeMillis(),
            ),
        )

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Wait for the host list to render both cards.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(hostAName).fetchSemanticsNodes().isNotEmpty()
        }

        val resumeATag = HOST_RESUME_ROW_TAG_PREFIX + hostAId
        val resumeBTag = HOST_RESUME_ROW_TAG_PREFIX + hostBId

        // The Resume affordance is armed by the ViewModel's off-main peek; wait
        // for it to publish before asserting.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(resumeATag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        capture("01-resume-affordance-visible")

        // AC1: the matching host card exposes the one-tap resume affordance.
        compose.onNodeWithTag(resumeATag, useUnmergedTree = true).assertIsDisplayed()

        // AC1: the OTHER host has no resume row (affordance is snapshot-scoped).
        check(
            compose.onAllNodesWithTag(resumeBTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        ) {
            "host B must not render a Resume row — the affordance is scoped to " +
                "the host the persisted snapshot belongs to"
        }

        // AC1: tapping Resume deep-links into the session — the host list is
        // gone (we navigated away, not stayed on the tree).
        compose.onNodeWithTag(resumeATag, useUnmergedTree = true).performClick()

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(HOST_LIST_CONTENT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        capture("02-after-resume-tap-left-host-list")

        check(
            compose.onAllNodesWithTag(HOST_LIST_CONTENT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        ) {
            "tapping Resume must leave the host list and open the session screen"
        }
    }

    private fun seedTwoHosts(hostAName: String, hostBName: String): Pair<Long, Long> {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            runBlocking {
                val keyId = db.sshKeyDao().insert(
                    SshKeyEntity(
                        name = "resume-test-key",
                        privateKeyPath = "/data/local/tmp/no-such-key",
                    ),
                )
                val aId = db.hostDao().insert(readyHost(hostAName, keyId))
                val bId = db.hostDao().insert(readyHost(hostBName, keyId))
                aId to bId
            }
        } finally {
            db.close()
        }
    }

    private fun readyHost(name: String, keyId: Long): HostEntity =
        HostEntity(
            name = name,
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = keyId,
            // Ready bootstrap columns + fresh freshness stamps so the
            // cold-launch reprobe is a no-op (never opens an SSH session).
            tmuxInstalled = true,
            pocketshellInstalled = true,
            lastBootstrapAt = System.currentTimeMillis(),
            pocketshellLastDetectedAt = System.currentTimeMillis(),
        )

    private fun cleanupAppDatabase() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            runBlocking { db.clearAllTables() }
        } finally {
            db.close()
        }
    }

    private fun clearLastSession() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        LastSessionStore(appContext).clear()
    }

    private fun capture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val directory = File(mediaRoot, "additional_test_output/host-resume-last-session")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create host-resume artifact directory: ${directory.absolutePath}"
        }
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write host-resume screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("HOST_RESUME_SCREENSHOT ${file.absolutePath}")
        return file
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}
