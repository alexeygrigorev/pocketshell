package com.pocketshell.app.usage

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
import java.time.ZoneId
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #690 connected screenshot test — captures the in-app "limits just
 * reset" banner (the non-push fallback) on the real [UsageScreen] with a seeded
 * recent reset event, so the reviewer can confirm the banner is visible,
 * prominent, and reads the maintainer's reported scenario ("learn the moment
 * limits reset on app open") without a live `pocketshell usage --reset-events`.
 *
 * The banner state is built from the SAME [usageResetBannerState] the view model
 * uses, so the screenshot reflects production formatting, not a hand-written
 * string. `now` is pinned so the relative clock + recency check are
 * deterministic.
 */
@RunWith(AndroidJUnit4::class)
class UsageResetBannerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-06-11T18:00:00Z")

    @Test
    fun captureUsageScreenWithResetBanner() {
        val recentReset = UsageResetEvent(
            provider = "codex",
            window = "short_term",
            detectedAt = now.minusSeconds(600),
            statedResetAt = now.minusSeconds(900),
            newResetAt = Instant.parse("2026-06-11T17:00:00Z"),
            timing = "early",
            minutesEarly = 15,
            resetKey = "codex|short_term|2026-06-11T17:00:00Z",
        )
        val banner = usageResetBannerState(
            events = listOf(recentReset),
            now = now,
            zoneId = ZoneId.systemDefault(),
        )
        assertNotNull("seeded recent reset should produce a banner", banner)

        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(ROOT_TAG),
                ) {
                    UsageScreen(
                        state = UsageScreenState(
                            hosts = listOf(seededHost()),
                            resetBanner = banner,
                        ),
                        onBack = {},
                        onRefresh = {},
                        now = now,
                    )
                }
            }
        }

        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()
        // The banner is present and reachable above the provider cards. The
        // banner title formats the reset time in the DEVICE's local zone (the
        // same `ZoneId.systemDefault()` production uses), so assert on the
        // timezone-independent prefix + the early-than-stated detail rather
        // than a hardcoded clock string.
        compose.onNodeWithTag(USAGE_RESET_BANNER_TAG).assertExists()
        compose.onAllNodesWithText("Codex limits reset at ", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("earlier than stated", substring = true).assertCountEquals(1)
        SystemClock.sleep(200)

        // Capture the actual Compose composition (not a full-device grab, which
        // is unreliable with parallel emulators / window foreground races) so
        // the artifact authoritatively shows the rendered banner.
        captureComposeRoot(File(ensureArtifactDir(), "usage-reset-banner.png"))
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
                        used = 4.0,
                        limit = 100.0,
                        unit = "percent",
                        resetAt = Instant.parse("2026-06-11T22:00:00Z"),
                    ),
                ),
            ),
        ),
    )

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/usage-reset-banner")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create reset-banner screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureComposeRoot(file: File) {
        compose.waitForIdle()
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write reset-banner screenshot: ${file.absolutePath}"
            }
        }
        println("USAGE_RESET_BANNER_SCREENSHOT ${file.absolutePath}")
    }

    private companion object {
        const val ROOT_TAG: String = "usage-reset-banner:root"
    }
}
