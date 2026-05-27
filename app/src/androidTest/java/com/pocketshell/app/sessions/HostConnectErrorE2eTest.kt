package com.pocketshell.app.sessions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import com.pocketshell.core.storage.migrations.MIGRATION_2_3
import com.pocketshell.core.storage.migrations.MIGRATION_3_4
import com.pocketshell.core.storage.migrations.MIGRATION_4_5
import com.pocketshell.core.storage.migrations.MIGRATION_5_6
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #109: connected coverage for the SSH connect-failure sheet.
 *
 * The test drives the real `MainActivity` host-tap flow against a
 * refused-port fixture (the emulator host `10.0.2.2` on a port nobody
 * is listening to; the Docker `agents` service stays on 2222 so picking
 * something different guarantees the kernel returns ECONNREFUSED).
 * It asserts the visible sheet shows the user-facing summary
 * ("Connection refused.") and NOT the raw `ConnectException` text, that
 * the title is "Connection failed" rather than "Tmux sessions", and
 * that the Retry / Open raw shell (skip tmux) actions are present.
 */
@RunWith(AndroidJUnit4::class)
class HostConnectErrorE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun connectFailureSheetShowsUserFacingMessage() = runBlocking {
        val key = readFixtureKey()
        // Use a port that is intentionally not bound. The Docker
        // `agents` service listens on 2222; 2299 is unused so the
        // kernel returns ECONNREFUSED quickly and the test does not
        // wait for the full 30s SSH connect timeout.
        val refusedPort = 2299
        val hostName = "Refused Connect ${System.currentTimeMillis()}"
        val hostRowTag = seedRefusedHost(key, hostName, refusedPort)

        val artifactsDir = ensureArtifactDir()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Wait for the host card and capture the before-tap state.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        captureFullDevice(File(artifactsDir, "01-before-attempt-viewport.png"))

        // Tap the host card to start the connect attempt.
        val tapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

        // The connecting state should appear (and be observable) before
        // the kernel rejects the connect. The kernel typically rejects
        // immediately, so we tolerate the race: either we catch the
        // spinner or we go straight to the error sheet. Both prove the
        // app handed control to the picker view-model — the bug was a
        // raw exception leaking, not a missing transition.
        val connectingObserved = runCatching {
            compose.waitUntil(timeoutMillis = 4_000) {
                compose.onAllNodesWithTag(HOST_PICKER_CONNECTING_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            captureFullDevice(File(artifactsDir, "02-during-connecting-viewport.png"))
            true
        }.getOrDefault(false)

        // Wait for the error sheet — short overall timeout because the
        // kernel returns ECONNREFUSED quickly. We allow up to 40s only
        // as a safety margin for a slow emulator.
        compose.waitUntil(timeoutMillis = 40_000) {
            compose.onAllNodesWithText("Connection failed", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val errorLatencyMs = SystemClock.elapsedRealtime() - tapAt

        // Title must be "Connection failed", not "Tmux sessions".
        compose.onNodeWithText("Connection failed", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Tmux sessions", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .let {
                assertTrue(
                    "expected sheet title to be 'Connection failed' not 'Tmux sessions' on the error path; " +
                        "found ${it.size} 'Tmux sessions' nodes",
                    it.isEmpty(),
                )
            }

        // Body must be the user-facing summary.
        compose.onNodeWithText(
            "Couldn't reach $DEFAULT_USER@$DEFAULT_HOST:$refusedPort. Connection refused.",
            useUnmergedTree = true,
        ).assertExists()

        // Actions must be present.
        compose.onNodeWithTag(HOST_PICKER_RETRY_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(HOST_PICKER_RAW_SHELL_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Retry", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Open raw shell (skip tmux)", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(HOST_PICKER_SHOW_DETAILS_TAG, useUnmergedTree = true).assertExists()

        // Snapshot the visible sheet text for the audit trail before any
        // assertions about negative content (so the artifact survives
        // failures).
        captureFullDevice(File(artifactsDir, "03-failure-shown-viewport.png"))

        // Negative-text checks: the raw exception strings that the old
        // sheet leaked must not appear in the visible (collapsed) body.
        // We deliberately check the collapsed body — the Show details
        // disclosure is opt-in and can legitimately surface the raw
        // text, but only after the user asks for it.
        listOf(
            "ConnectException",
            "ECONNREFUSED",
            "isConnected failed",
            "after 30000ms",
        ).forEach { needle ->
            val hits = compose.onAllNodesWithText(needle, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue(
                "raw exception text '$needle' must not appear in the collapsed sheet body, found ${hits.size} node(s)",
                hits.isEmpty(),
            )
        }

        // Sidecar text for review:
        File(artifactsDir, "03-failure-shown-visible-sheet.txt").writeText(
            buildString {
                appendLine("title=Connection failed")
                appendLine("body=Couldn't reach $DEFAULT_USER@$DEFAULT_HOST:$refusedPort. Connection refused.")
                appendLine("retry_action=Retry")
                appendLine("secondary_action=Open raw shell (skip tmux)")
                appendLine("disclosure=Show details (collapsed by default)")
                appendLine("connecting_state_observed=$connectingObserved")
                appendLine("error_latency_ms=$errorLatencyMs")
                appendLine("port=$refusedPort")
            },
        )
        File(artifactsDir, "timings.txt").writeText(
            buildString {
                appendLine("CONNECT_ERROR_TIMING tap_to_error_sheet_ms=$errorLatencyMs")
                appendLine("CONNECT_ERROR_TIMING connecting_state_observed=$connectingObserved")
            },
        )
        File(artifactsDir, "summary.txt").writeText(
            buildString {
                appendLine("scenario=host-connect-refused")
                appendLine("host=$DEFAULT_HOST port=$refusedPort user=$DEFAULT_USER")
                appendLine("capture_policy=full-device screenshots of the connect-error sheet flow")
                appendLine("artifacts:")
                appendLine("  01-before-attempt-viewport.png")
                appendLine("  02-during-connecting-viewport.png (best-effort, race with kernel reject)")
                appendLine("  03-failure-shown-viewport.png")
                appendLine("  03-failure-shown-visible-sheet.txt")
                appendLine("  timings.txt")
            },
        )

        // Final sanity: tapping Retry must keep the sheet usable.
        // (The retry will fail again — the refused port is still
        // refused — but the sheet must not get stuck in a broken state.)
        compose.onNodeWithTag(HOST_PICKER_RETRY_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 40_000) {
            compose.onAllNodesWithText("Connection failed", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Connection failed", useUnmergedTree = true).assertExists()

        // Hard assertion: the disclosure must reveal the raw details
        // only AFTER it is tapped.
        val detailsBeforeOpen = compose.onAllNodesWithText("ECONNREFUSED", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertFalse(
            "raw ECONNREFUSED must stay hidden until the user opens the details disclosure",
            detailsBeforeOpen.isNotEmpty(),
        )
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedRefusedHost(key: String, hostName: String, port: Int): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "connect-error-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    // Pretend bootstrap already happened so the
                    // host-list tap goes straight to the picker path
                    // and we exercise the connect-error sheet directly.
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create connect-error artifact directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write connect-error screenshot: ${file.absolutePath}"
                }
            }
            val brightness = countBrightPixels(file)
            println("CONNECT_ERROR_SCREENSHOT ${file.absolutePath} bright_pixels=$brightness")
        } finally {
            bitmap.recycle()
        }
    }

    private fun countBrightPixels(file: File): Int {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return 0
        return try {
            var bright = 0
            for (y in 0 until bitmap.height step 4) {
                for (x in 0 until bitmap.width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    val luminance = (
                        Color.red(pixel) * 299 +
                            Color.green(pixel) * 587 +
                            Color.blue(pixel) * 114
                        ) / 1000
                    if (luminance > 120) bright++
                }
            }
            bright
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "connect-error-e2e"
    }
}
