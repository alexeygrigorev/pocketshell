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

    /**
     * Issue #800 — Claude Code and Codex both carry the concrete 5h/7d spans
     * the parser now seeds, so both render "5h window" / "7d window" (same
     * labels). Copilot's long-term quota is monthly, so it renders
     * "Monthly limit", NOT a 7d window. This composes the real production
     * UsageScreen with records shaped exactly as the parser emits them and
     * asserts the on-screen labels, capturing a full-device screenshot.
     */
    @Test
    fun claudeAndCodexShow5hAnd7dWhileCopilotStaysMonthly() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(ROOT_TAG),
                ) {
                    UsageScreen(
                        state = UsageScreenState(hosts = listOf(seededConcreteSpanHost())),
                        onBack = {},
                        onRefresh = {},
                        now = now,
                    )
                }
            }
        }

        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()
        // Claude + Codex both render the identical concrete spans: one 5h and
        // one 7d window each → two of each label on screen.
        compose.onAllNodesWithText("5h window").assertCountEquals(2)
        compose.onAllNodesWithText("7d window").assertCountEquals(2)
        // Copilot's long-term quota keeps its real monthly cadence, never 7d.
        compose.onAllNodesWithText("Monthly limit").assertCountEquals(1)
        SystemClock.sleep(200)

        captureFullDevice(File(ensureArtifactDir(), "usage-window-labels-claude-5h-7d.png"))
    }

    private fun seededConcreteSpanHost(): UsageHostSnapshot = UsageHostSnapshot(
        hostId = 2L,
        hostName = "dev-box",
        lastSyncedAt = now,
        records = listOf(
            // Codex: detail windows yield "5h"/"7d" (existing behaviour).
            concreteSpanRecord("codex", shortUsed = 23.0, longUsed = 64.0),
            // Claude Code: parser seeds "5h"/"7d" (issue #800) so it matches
            // Codex exactly.
            concreteSpanRecord("claude", shortUsed = 41.0, longUsed = 15.0),
            // Copilot: long-term is monthly, short-term is the fixed bucket.
            UsageProviderRecord(
                provider = "copilot",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(
                    UsageWindow(
                        name = "short_term",
                        used = 0.0,
                        limit = 100.0,
                        unit = "percent",
                        resetAt = null,
                    ),
                    UsageWindow(
                        name = "monthly",
                        used = 3.4,
                        limit = 100.0,
                        unit = "percent",
                        resetAt = Instant.parse("2026-07-01T00:00:00Z"),
                    ),
                ),
            ),
        ),
    )

    private fun concreteSpanRecord(
        provider: String,
        shortUsed: Double,
        longUsed: Double,
    ): UsageProviderRecord = UsageProviderRecord(
        provider = provider,
        status = UsageStatus.Ok,
        rawStatus = "ok",
        windows = listOf(
            UsageWindow(
                name = "5h",
                used = shortUsed,
                limit = 100.0,
                unit = "percent",
                resetAt = Instant.parse("2026-06-05T17:00:00Z"),
            ),
            UsageWindow(
                name = "7d",
                used = longUsed,
                limit = 100.0,
                unit = "percent",
                resetAt = Instant.parse("2026-06-12T12:00:00Z"),
            ),
        ),
    )

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
