package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #506 — screenshot evidence that host-list usage is reachable via
 * the host kebab "Usage" item. The per-host usage chip that briefly
 * rendered under each host card (issue #483) was dropped because it read
 * as a cryptic floating row; usage is server-tied and now stays one tap
 * away through the kebab.
 *
 *  - `host-usage-kebab-usage-item.png` — the host kebab expanded showing
 *    the labelled "Usage" item, the discoverable one-tap path to the
 *    Usage detail for that host.
 *
 * Rendered in isolation (no Hilt / SSH) so the surface is deterministic
 * for the reviewer; the wiring into the live host list is covered by the
 * `HostListScreen` call site.
 */
@RunWith(AndroidJUnit4::class)
class HostUsagePerHostAccessScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
                        onEdit = {},
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
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
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
        const val KEBAB_ROOT_TAG: String = "host-usage-per-host:kebab"
    }
}
