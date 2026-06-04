package com.pocketshell.app.hosts

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
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #483 — screenshot evidence that usage is now surfaced *per-host*
 * after the global cross-host strip was removed from the top of the host
 * list. Two artifacts:
 *
 *  - `host-usage-per-host-list.png` — a host-list region with NO global
 *    usage card at the top; each host card carries its own compact
 *    [HostUsageChip] (top provider % + soonest reset, threshold-tinted)
 *    so usage reads as server-tied.
 *  - `host-usage-kebab-usage-item.png` — the host kebab expanded showing
 *    the labelled "Usage" item, the discoverable one-tap path to the
 *    Usage detail for that host.
 *
 * Rendered in isolation (no Hilt / SSH) so the surfaces are deterministic
 * for the reviewer; the wiring into the live host list is covered by the
 * `HostListScreen` call site and the `hostUsageSummary` unit tests.
 */
@RunWith(AndroidJUnit4::class)
class HostUsagePerHostAccessScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturePerHostUsageChipsOnHostList() {
        val now = Instant.parse("2026-06-04T10:00:00Z")
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(horizontal = 12.dp, vertical = 20.dp)
                        .testTag(LIST_ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HostRowWithChip(
                        name = "hetzner",
                        subtitle = "alex@65.108.42.11:22",
                        summary = HostUsageSummary(
                            topProvider = "Claude Code",
                            percent = 41.0,
                            thresholdState = UsageThresholdState.Ok,
                            soonestReset = now.plusSeconds(4 * 3_600),
                        ),
                        now = now,
                    )
                    HostRowWithChip(
                        name = "build-box",
                        subtitle = "ci@10.0.0.7:2222",
                        summary = HostUsageSummary(
                            topProvider = "Codex",
                            percent = 96.0,
                            thresholdState = UsageThresholdState.Critical,
                            soonestReset = now.plusSeconds(40 * 60),
                        ),
                        now = now,
                    )
                }
            }
        }
        compose.onNodeWithTag(LIST_ROOT_TAG).assertExists()
        compose.waitForIdle()
        // Per-host chips are present, one per host.
        compose.onNodeWithTag(HOST_USAGE_CHIP_TAG_PREFIX + "hetzner").assertExists()
        compose.onNodeWithTag(HOST_USAGE_CHIP_TAG_PREFIX + "build-box").assertExists()
        compose.onAllNodesWithText("Claude Code").assertCountEquals(1)
        compose.onAllNodesWithText("Codex").assertCountEquals(1)
        compose.onAllNodesWithText("41%").assertCountEquals(1)
        compose.onAllNodesWithText("96%").assertCountEquals(1)
        SystemClock.sleep(200)
        captureFullDevice(File(ensureArtifactDir(), "host-usage-per-host-list.png"))
    }

    @Test
    fun captureKebabUsageItem() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(horizontal = 12.dp, vertical = 40.dp)
                        .testTag(KEBAB_ROOT_TAG),
                ) {
                    HostOverflowMenuAnchor(
                        expanded = true,
                        onExpand = {},
                        onDismiss = {},
                        usageRecord = null,
                        usageBadgeTestTag = HOST_USAGE_BADGE_TAG_PREFIX + "kebab",
                        onOpenUsage = {},
                        usageMenuItemTestTag = HOST_USAGE_MENU_ITEM_TAG_PREFIX + "kebab",
                        onOpenPorts = {},
                        onOpenWatchedFolders = {},
                        onShare = {},
                        onRecheckSetup = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(KEBAB_ROOT_TAG).assertExists()
        compose.waitForIdle()
        // The labelled per-host "Usage" item is present in the kebab.
        compose.onNodeWithTag(HOST_USAGE_MENU_ITEM_TAG_PREFIX + "kebab").assertExists()
        compose.onAllNodesWithText("Usage").assertCountEquals(1)
        SystemClock.sleep(200)
        captureFullDevice(File(ensureArtifactDir(), "host-usage-kebab-usage-item.png"))
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/host-usage-per-host-access")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create per-host usage screenshot directory: ${dir.absolutePath}"
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
                    "Could not write per-host usage screenshot: ${file.absolutePath}"
                }
            }
            println("HOST_USAGE_PER_HOST_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val LIST_ROOT_TAG: String = "host-usage-per-host:list"
        const val KEBAB_ROOT_TAG: String = "host-usage-per-host:kebab"
    }
}

/**
 * Mirror of the live host-list row composition for issue #483: a
 * [HostCard] with the per-host [HostUsageChip] rendered directly beneath
 * it. The chip's test tag uses the host name as the key so the screenshot
 * test can assert one chip per host.
 */
@androidx.compose.runtime.Composable
private fun HostRowWithChip(
    name: String,
    subtitle: String,
    summary: HostUsageSummary,
    now: Instant,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HostCard(
            name = name,
            subtitle = subtitle,
            status = HostStatus.NoActiveSessions,
            onClick = {},
        )
        HostUsageChip(
            summary = summary,
            onClick = {},
            modifier = Modifier.testTag(HOST_USAGE_CHIP_TAG_PREFIX + name),
            now = now,
        )
    }
}
