package com.pocketshell.app.usage

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #522 item 4 — the usage panel humanizes raw snake_case window keys.
 * Seeds a provider whose windows are named `short_term` / `long_term` (the keys
 * the `pocketshell usage` JSON carries for some providers) and asserts the
 * detail screen renders them as "Short term" / "Long term" with no underscore,
 * capturing a screenshot for the reviewer.
 */
@RunWith(AndroidJUnit4::class)
class UsageWindowLabelScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val now: Instant = Instant.parse("2026-06-05T12:00:00Z")

    @Test
    fun humanizesShortAndLongTermWindowLabels() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(ROOT_TAG),
                ) {
                    UsageScreen(
                        state = UsageScreenState(hosts = listOf(seededHost())),
                        onBack = {},
                        onRefresh = {},
                        now = now,
                    )
                }
            }
        }

        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()
        // #522 item 4: humanized labels render (no underscore), and the raw keys
        // never reach the screen.
        compose.onAllNodesWithText("Short term").assertCountEquals(1)
        compose.onAllNodesWithText("Long term").assertCountEquals(1)
        compose.onAllNodesWithText("Short_term").assertCountEquals(0)
        compose.onAllNodesWithText("Long_term").assertCountEquals(0)
        SystemClock.sleep(200)

        captureFullDevice(File(ensureArtifactDir(), "usage-window-labels-humanized.png"))
    }

    private fun seededHost(): UsageHostSnapshot = UsageHostSnapshot(
        hostId = 1L,
        hostName = "dev-box",
        lastSyncedAt = now,
        records = listOf(
            UsageProviderRecord(
                provider = "codex",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(
                    UsageWindow(
                        name = "short_term",
                        used = 23.0,
                        limit = 100.0,
                        unit = "percent",
                        resetAt = Instant.parse("2026-06-05T17:00:00Z"),
                    ),
                    UsageWindow(
                        name = "long_term",
                        used = 64.0,
                        limit = 100.0,
                        unit = "percent",
                        resetAt = Instant.parse("2026-06-12T12:00:00Z"),
                    ),
                ),
            ),
        ),
    )

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/usage-window-labels")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create usage-window-labels screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write usage-window-labels screenshot: ${file.absolutePath}"
                }
            }
            println("USAGE_WINDOW_LABEL_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG: String = "usage-window-labels:root"
    }
}
