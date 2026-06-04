package com.pocketshell.app.usage

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
 * Issue #501 screenshot test — captures the "time until reset" display on
 * the Usage detail screen and the host-list summary strip with seeded
 * provider records that carry `reset_at` values and a fixed `now`, so the
 * reviewer can confirm the relative + absolute reset rendering and the
 * "compare providers at a glance" goal without live `pocketshell usage`.
 *
 * Two artifacts:
 *  - `usage-reset-times-detail.png` — the full [UsageScreen] with three
 *    providers whose windows reset at different times (soonest first,
 *    days out, and a null-reset window showing the "—" placeholder).
 *  - `usage-reset-times-strip.png` — the cross-host [UsageDashboardStrip]
 *    showing the soonest reset per provider side by side.
 *
 * `now` is pinned to 2026-06-04T12:00:00Z so the relative strings are
 * deterministic across runs (no wall-clock read in the captured path).
 */
@RunWith(AndroidJUnit4::class)
class UsageResetTimeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-06-04T12:00:00Z")

    @Test
    fun captureUsageDetailWithResetTimes() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(DETAIL_ROOT_TAG),
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
        compose.onNodeWithTag(DETAIL_ROOT_TAG).assertExists()
        compose.waitForIdle()
        compose.onAllNodesWithText("Claude Code").assertCountEquals(1)
        // Soonest 5h reset is ~1h10m out, 7d window is days out.
        compose.onAllNodesWithText("resets in 1h 10m").assertCountEquals(1)
        compose.onAllNodesWithText("resets in 5 days").assertCountEquals(1)
        // Codex weekly has no reset_at → placeholder.
        compose.onAllNodesWithText("resets —").assertCountEquals(1)
        SystemClock.sleep(200)

        captureFullDevice(File(ensureArtifactDir(), "usage-reset-times-detail.png"))
    }

    @Test
    fun captureDashboardStripWithSoonestReset() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(vertical = 20.dp)
                        .testTag(STRIP_ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val rows = UsageScreenState(hosts = listOf(seededHost())).dashboardRows()
                    UsageDashboardStrip(rows = rows, now = now)
                }
            }
        }
        compose.onNodeWithTag(STRIP_ROOT_TAG).assertExists()
        compose.waitForIdle()
        compose.onAllNodesWithText("Claude Code").assertCountEquals(1)
        compose.onAllNodesWithText("Codex").assertCountEquals(1)
        SystemClock.sleep(200)

        captureFullDevice(File(ensureArtifactDir(), "usage-reset-times-strip.png"))
    }

    private fun seededHost(): UsageHostSnapshot = UsageHostSnapshot(
        hostId = 1L,
        hostName = "dev-box",
        lastSyncedAt = now,
        records = listOf(
            UsageProviderRecord(
                provider = "claude",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(
                    UsageWindow(
                        name = "5h",
                        used = 42.0,
                        limit = 100.0,
                        unit = "percent",
                        // 1h10m out — the soonest reset.
                        resetAt = Instant.parse("2026-06-04T13:10:00Z"),
                    ),
                    UsageWindow(
                        name = "7d",
                        used = 61.0,
                        limit = 100.0,
                        unit = "percent",
                        // Exactly 5 days out.
                        resetAt = Instant.parse("2026-06-09T12:00:00Z"),
                    ),
                ),
            ),
            UsageProviderRecord(
                provider = "codex",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(
                    UsageWindow(
                        name = "weekly",
                        used = 18.0,
                        limit = 100.0,
                        unit = "percent",
                        // No reset_at → "—" placeholder.
                        resetAt = null,
                    ),
                ),
            ),
        ),
    )

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/usage-reset-times")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create reset-time screenshot directory: ${dir.absolutePath}"
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
                    "Could not write reset-time screenshot: ${file.absolutePath}"
                }
            }
            println("USAGE_RESET_TIME_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DETAIL_ROOT_TAG: String = "usage-reset-times:detail"
        const val STRIP_ROOT_TAG: String = "usage-reset-times:strip"
    }
}
