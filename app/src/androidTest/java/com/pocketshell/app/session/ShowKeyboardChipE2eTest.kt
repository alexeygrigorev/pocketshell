package com.pocketshell.app.session

import android.graphics.Bitmap
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
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import com.pocketshell.core.storage.migrations.MIGRATION_2_3
import com.pocketshell.core.storage.migrations.MIGRATION_3_4
import com.pocketshell.core.storage.migrations.MIGRATION_4_5
import com.pocketshell.core.storage.migrations.MIGRATION_5_6
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #131: connected validation for the show-keyboard chip on the
 * session screen.
 *
 * The bug the chip fixes is "no obvious way to bring up the soft keyboard
 * from the session screen" — phone users had to discover the tap-on-
 * viewport gesture on their own. This test drives the real
 * `MainActivity` host-tap → raw SSH route, lands on `SessionScreen`,
 * confirms the keyboard chip is rendered in the bottom toolbar, and
 * verifies that tapping it actually surfaces the soft keyboard at the
 * system level (not just routes the click).
 *
 * The IME-visibility check uses `waitForInputMethodVisible` from the
 * `com.pocketshell.app.proof.signals` package (see #140). That helper
 * polls `WindowInsetsCompat.Type.ime()` on the activity decor view —
 * the same signal app code uses for keyboard-aware layouts and the
 * one the framework propagates as soon as the IME's window attaches
 * its insets to the focused window. We avoid `dumpsys input_method |
 * grep mInputShown` because the IME process updates `mInputShown`
 * asynchronously after the insets have already been attached, which
 * makes dumpsys lag visibly on swiftshader CI emulators.
 *
 * Artifacts (per run, under
 * `/sdcard/Android/media/com.pocketshell.app/additional_test_output/
 * show-keyboard-chip/`):
 *
 *  - `01-before-tap-viewport.png` — full-device screenshot of the
 *    session with the chip row visible and the IME hidden.
 *  - `02-after-tap-viewport.png`  — full-device screenshot with the IME
 *    raised after the chip tap.
 *  - `summary.txt`                — human-readable run summary with the
 *    observed IME-visibility values, latencies, and chip-row tag info.
 */
@RunWith(AndroidJUnit4::class)
class ShowKeyboardChipE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun showKeyboardChipBringsUpSoftInput() = runBlocking {
        // Issue #171 (round 2, D22 hard-cut): the "Continue with SSH"
        // raw-shell escape hatch was deleted when the host-tap surface
        // flipped to FolderListScreen. The chip behaviour this test
        // exercises is identical on the tmux-attach path that
        // `ShowKeyboardChipDockerTest` already covers, so skipping this
        // raw-SSH-only variant is acceptable per D22 (no compatibility
        // shim for a deleted surface).
        org.junit.Assume.assumeTrue(
            "Raw-SSH escape hatch removed by #171 (D22). Tmux-attach chip behaviour is covered by ShowKeyboardChipDockerTest.",
            false,
        )

        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        val hostName = "ShowKeyboard ${System.currentTimeMillis()}"
        val hostRowTag = seedHost(key, hostName)
        val artifactsDir = ensureArtifactDir()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        launchedActivity = scenario

        // Wait for the host row to render then tap it to start the
        // connect attempt.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

        // (Unreachable — Assume.assumeTrue(false) above skips the test.)
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Continue with SSH", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Continue with SSH", useUnmergedTree = true).performClick()

        // Wait for the session screen to settle and the chip row to
        // become visible (chips only render while the IME is hidden).
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Snapshot the baseline state.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        captureFullDevice(File(artifactsDir, "01-before-tap-viewport.png"))
        val shownBefore = waitForInputMethodVisible(
            scenario = scenario,
            expected = false,
            timeoutMs = 1_000,
        )

        // Tap the chip. We perform the click via the stable test tag so
        // the assertion stays robust if the caption is later renamed
        // ("keyboard" → "show keyboard"). The compose harness sends the
        // click on the main thread.
        val tapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).performClick()

        // Wait for the IME to actually become visible using the signal
        // helper from #140. The helper polls
        // `WindowInsetsCompat.Type.ime()` on the activity decor view —
        // the same signal app code uses for keyboard-aware layouts and
        // the one the framework attaches the moment the IME window's
        // insets land on the focused window. This is the deterministic
        // source of truth and resolves in well under 1 s on healthy
        // runs; the 30 s timeout is the "this is broken, not slow"
        // ceiling for swiftshader CI emulators.
        val shown = waitForInputMethodVisible(
            scenario = scenario,
            expected = true,
            timeoutMs = 30_000,
        )
        val raisedMs = SystemClock.elapsedRealtime() - tapAt

        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        captureFullDevice(File(artifactsDir, "02-after-tap-viewport.png"))

        File(artifactsDir, "summary.txt").writeText(
            buildString {
                appendLine("issue=131 scenario=show-keyboard-chip")
                appendLine("chip_test_tag=$SHOW_KEYBOARD_CHIP_TAG")
                appendLine("ime_visible_before_tap=$shownBefore")
                appendLine("ime_visible_after_tap=$shown")
                appendLine("tap_to_ime_visible_ms=$raisedMs")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("artifacts:")
                appendLine("  01-before-tap-viewport.png")
                appendLine("  02-after-tap-viewport.png")
            },
        )

        // Hard assertion: the IME must be visible after the chip tap.
        // We allow `shownBefore` to be either false (clean state) or
        // true (a flaky residual from a previous test sharing the
        // emulator) — the chip's contract is "ensure the IME is up",
        // which is idempotent when the IME is already raised.
        assertTrue(
            "expected the IME to be visible after tapping the show-keyboard chip; " +
                "observed ime_visible_before_tap=$shownBefore " +
                "ime_visible_after_tap=$shown raisedMs=$raisedMs",
            shown,
        )
    }

    // --- Host seeding ------------------------------------------------------

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedHost(key: String, hostName: String): String {
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
                name = "show-keyboard-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    // Pretend bootstrap already happened so the host-list
                    // tap goes straight to the picker path (Continue
                    // with SSH → SessionScreen).
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    // --- Artifact helpers --------------------------------------------------

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create show-keyboard-chip artifact directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write show-keyboard screenshot: ${file.absolutePath}"
                }
            }
            println("SHOW_KEYBOARD_CHIP_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "show-keyboard-chip"
    }
}
