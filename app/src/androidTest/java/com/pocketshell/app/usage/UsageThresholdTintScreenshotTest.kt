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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshot test for issue #214 — captures the three in-app usage
 * warning surfaces (host card / sessions chip / banner) for each
 * threshold tint (approaching / critical / exceeded) so the reviewer
 * can visually confirm the green-amber-red mapping without spinning up
 * the full dashboard.
 *
 * Three artifacts:
 *  - `usage-threshold-tints-state-list.png` — the per-provider state
 *    list rendered in Settings → Usage, with one row per threshold
 *    state (Ok / Approaching / Critical / Exceeded).
 *  - `usage-threshold-tints-badges.png` — the inline pill emitted by
 *    [UsageSessionBlockedBadge] for the three warning states.
 *  - `usage-threshold-tints-banners.png` — the dismissible banner
 *    rendered above the host list for the three warning states.
 *
 * Acceptance criteria addressed:
 *  - "Connected E2E or screenshot for the threshold states" — covered
 *    by capturing every non-Ok state with the canonical record shape.
 *  - "Host card shows tinted usage indicator (green/amber/red)" — the
 *    badge artifact pins the colour mapping per state.
 *  - "Settings → Usage section shows per-provider state" — the state
 *    list artifact proves the row renders the threshold-aware tint
 *    alongside the worst-case percent.
 *  - "Dismissible in-app banner when approaching/critical/exceeded —
 *    one banner per provider per session" — the banner artifact proves
 *    the three banner tints render side by side.
 */
@RunWith(AndroidJUnit4::class)
class UsageThresholdTintScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturePerProviderStateList() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                        .testTag(STATE_LIST_ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    UsageProviderStateList(
                        records = listOf(
                            providerRecord(name = "claude", percent = 30.0),
                            providerRecord(name = "codex", percent = 85.0),
                            providerRecord(name = "opencode", percent = 96.0),
                            providerRecord(
                                name = "copilot",
                                percent = 100.0,
                                status = UsageStatus.Blocked,
                            ),
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag(STATE_LIST_ROOT_TAG).assertExists()
        compose.waitForIdle()
        compose.onAllNodesWithText("Claude Code").assertCountEquals(1)
        compose.onAllNodesWithText("Codex").assertCountEquals(1)
        compose.onAllNodesWithText("Approaching limit").assertCountEquals(1)
        compose.onAllNodesWithText("Critical — close to limit").assertCountEquals(1)
        compose.onAllNodesWithText("Exceeded — provider blocked").assertCountEquals(1)
        SystemClock.sleep(200)

        captureFullDevice(File(ensureArtifactDir(), "usage-threshold-tints-state-list.png"))
    }

    @Test
    fun captureInlineBadges() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                        .testTag(BADGES_ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    UsageSessionBlockedBadge(
                        provider = providerRecord(name = "claude", percent = 85.0),
                    )
                    UsageSessionBlockedBadge(
                        provider = providerRecord(name = "codex", percent = 96.0),
                    )
                    UsageSessionBlockedBadge(
                        provider = providerRecord(
                            name = "copilot",
                            percent = 100.0,
                            status = UsageStatus.Blocked,
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag(BADGES_ROOT_TAG).assertExists()
        compose.waitForIdle()
        compose.onAllNodesWithText("APPROACHING").assertCountEquals(1)
        compose.onAllNodesWithText("CRITICAL").assertCountEquals(1)
        compose.onAllNodesWithText("EXCEEDED").assertCountEquals(1)
        SystemClock.sleep(200)

        captureFullDevice(File(ensureArtifactDir(), "usage-threshold-tints-badges.png"))
    }

    @Test
    fun captureBannerSet() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(vertical = 20.dp)
                        .testTag(BANNERS_ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UsageWarningBanner(
                        provider = providerRecord(name = "claude", percent = 85.0),
                        onDismiss = {},
                        onTap = {},
                    )
                    UsageWarningBanner(
                        provider = providerRecord(name = "codex", percent = 96.0),
                        onDismiss = {},
                        onTap = {},
                    )
                    UsageWarningBanner(
                        provider = providerRecord(
                            name = "copilot",
                            percent = 100.0,
                            status = UsageStatus.Blocked,
                        ),
                        onDismiss = {},
                        onTap = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(BANNERS_ROOT_TAG).assertExists()
        compose.waitForIdle()
        compose.onNodeWithTag(usageBannerTagFor("claude")).assertExists()
        compose.onNodeWithTag(usageBannerTagFor("codex")).assertExists()
        compose.onNodeWithTag(usageBannerTagFor("copilot")).assertExists()
        SystemClock.sleep(200)

        captureFullDevice(File(ensureArtifactDir(), "usage-threshold-tints-banners.png"))
    }

    private fun providerRecord(
        name: String,
        percent: Double,
        status: UsageStatus = UsageStatus.Ok,
    ): UsageProviderRecord = UsageProviderRecord(
        provider = name,
        status = status,
        windows = listOf(
            UsageWindow(
                name = "5h",
                used = percent,
                limit = 100.0,
                unit = "percent",
                resetAt = null,
            ),
        ),
        rawStatus = status.name.lowercase(),
    )

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/usage-threshold-tints")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create threshold-tint screenshot directory: ${dir.absolutePath}"
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
                    "Could not write threshold-tint screenshot: ${file.absolutePath}"
                }
            }
            println("USAGE_THRESHOLD_TINT_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val STATE_LIST_ROOT_TAG: String = "usage-threshold-tints:state-list"
        const val BADGES_ROOT_TAG: String = "usage-threshold-tints:badges"
        const val BANNERS_ROOT_TAG: String = "usage-threshold-tints:banners"
    }
}
