package com.pocketshell.app.usage

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
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
 * Issue #689 — stale-while-revalidate usage screen. Renders the three
 * provenance states the maintainer should see and captures a screenshot of
 * each for the reviewer:
 *
 * 1. cached-first: cached value populated + "Last captured HH:mm · refreshing…".
 * 2. fresh: live data swapped in, plain "Last sync: host data".
 * 3. refresh-failure: cached value kept + honest
 *    "Couldn't refresh — showing cached from HH:mm" (no scary error).
 */
@RunWith(AndroidJUnit4::class)
class UsageStaleWhileRevalidateScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val now: Instant = Instant.parse("2026-06-11T12:00:00Z")
    private val capturedAt: Instant = Instant.parse("2026-06-11T09:00:00Z")

    @Test
    fun cachedFirst_showsLastCapturedAndRefreshing() {
        renderState(
            UsageScreenState(
                hosts = listOf(host(capturedAt = capturedAt)),
                isRefreshing = true,
                showingCached = true,
            ),
        )
        // Assert on the tagged provenance node with substring matching on the
        // zone-independent words (the rendered HH:mm depends on the device's
        // timezone, and the · / … glyphs trip exact-text finders).
        compose.onNodeWithTag(USAGE_PROVENANCE_TAG)
            .assertTextContains("Last captured", substring = true)
        compose.onNodeWithTag(USAGE_PROVENANCE_TAG)
            .assertTextContains("refreshing", substring = true)
        capture("usage-swr-cached-refreshing.png")
    }

    @Test
    fun fresh_showsPlainLiveStatus() {
        renderState(
            UsageScreenState(
                hosts = listOf(host(lastSyncedAt = now)),
                isRefreshing = false,
            ),
        )
        compose.onAllNodesWithText("Last sync: host data").assertCountEquals(1)
        capture("usage-swr-fresh.png")
    }

    @Test
    fun refreshFailure_keepsCachedValueWithHonestNote() {
        renderState(
            UsageScreenState(
                hosts = listOf(host(staleSince = capturedAt)),
                isRefreshing = false,
                showingCached = false,
            ),
        )
        compose.onNodeWithTag(USAGE_PROVENANCE_TAG)
            .assertTextContains("showing cached from", substring = true)
        capture("usage-swr-stale-cached.png")
    }

    private fun renderState(state: UsageScreenState) {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(ROOT_TAG),
                ) {
                    UsageScreen(state = state, onBack = {}, onRefresh = {}, now = now)
                }
            }
        }
        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
    }

    private fun host(
        capturedAt: Instant? = null,
        staleSince: Instant? = null,
        lastSyncedAt: Instant? = capturedAt ?: staleSince,
    ): UsageHostSnapshot = UsageHostSnapshot(
        hostId = 1L,
        hostName = "dev-box",
        lastSyncedAt = lastSyncedAt,
        capturedAt = capturedAt,
        staleSince = staleSince,
        records = listOf(
            UsageProviderRecord(
                provider = "codex",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(
                    UsageWindow(
                        name = "short_term",
                        used = 40.0,
                        limit = 100.0,
                        unit = "percent",
                        resetAt = Instant.parse("2026-06-11T17:00:00Z"),
                    ),
                ),
            ),
        ),
    )

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/usage-swr")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create usage-swr screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun capture(name: String) {
        val file = File(ensureArtifactDir(), name)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        // The screenshot is reviewer EVIDENCE, not the acceptance assertion
        // (the text assertions above are). A flaky UiAutomation registration
        // collision when several methods in one class each grab a screenshot
        // must NOT fail the test; swallow it and still emit a marker line.
        val bitmap: Bitmap = try {
            instrumentation.uiAutomation.takeScreenshot()
        } catch (t: Throwable) {
            println("USAGE_SWR_SCREENSHOT_SKIPPED $name: ${t.javaClass.simpleName}")
            null
        } ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write usage-swr screenshot: ${file.absolutePath}"
                }
            }
            println("USAGE_SWR_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG: String = "usage-swr:root"
    }
}
