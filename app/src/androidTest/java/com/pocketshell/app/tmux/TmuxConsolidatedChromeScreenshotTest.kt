package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #189 screenshot evidence — captures the new consolidated 56dp
 * top chrome ([TmuxImeAwareTopChrome] / [ConsolidatedTopChrome]) in
 * every meaningful permutation so the reviewer and the maintainer can
 * eyeball the chrome reduction without driving a live tmux session.
 *
 * Three artifacts get written to
 * `<media>/additional_test_output/tmux-consolidated-chrome/`:
 *
 *  - `consolidated-chrome-single-window-no-agent.png` — the minimal
 *    case (no agent, single window). Just back + session name + kebab.
 *  - `consolidated-chrome-multi-window-with-agent.png` — the loaded
 *    case (Conversation tab pill + `session › Window 2` crumb).
 *  - `consolidated-chrome-ime-up-compact.png` — IME-up compressed
 *    chrome (the [CompactBreadcrumb] 40dp strip, no tabs).
 *
 * Combined with [TmuxSessionScreenImeChromeTest] (which asserts the
 * 56dp height limit and the IME-down/up tag visibility contract), this
 * is the visual gate for the "≤ 56dp single-toolbar chrome" acceptance
 * criterion on issue #189.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConsolidatedChromeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureSingleWindowNoAgentChrome() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        // Pad below the system status bar so the
                        // chrome doesn't overlap the system clock /
                        // notification icons in the screenshot. The
                        // real screen pulls this padding from its
                        // root scaffold; here we hard-code a Pixel-7
                        // status-bar height (24dp) so the test stays
                        // independent of the device's window insets
                        // (which createAndroidComposeRule does not
                        // always populate).
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    TmuxImeAwareTopChrome(
                        chromeCompressed = false,
                        crumbs = sampleCrumbs(windowLabel = "Window 1"),
                        sessionName = "scratch",
                        onBack = {},
                        onMore = {},
                        // Single-tab list -> Conversation pill is not
                        // rendered; only the Terminal label would be
                        // selected. The chrome stays minimal.
                        tabLabels = listOf("Terminal"),
                        selectedTabIndex = 0,
                        onTabSelected = {},
                        windows = sampleWindows(1),
                        currentWindowId = "@1",
                        onOpenWindowSwitcher = {},
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-single-window-no-agent.png"))
    }

    @Test
    fun captureMultiWindowWithAgentChrome() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        // Pad below the system status bar so the
                        // chrome doesn't overlap the system clock /
                        // notification icons in the screenshot. The
                        // real screen pulls this padding from its
                        // root scaffold; here we hard-code a Pixel-7
                        // status-bar height (24dp) so the test stays
                        // independent of the device's window insets
                        // (which createAndroidComposeRule does not
                        // always populate).
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    TmuxImeAwareTopChrome(
                        chromeCompressed = false,
                        crumbs = sampleCrumbs(windowLabel = "Window 2"),
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = {},
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 1,
                        onTabSelected = {},
                        windows = sampleWindows(3),
                        currentWindowId = "@2",
                        onOpenWindowSwitcher = {},
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-multi-window-with-agent.png"))
    }

    @Test
    fun captureImeUpCompactChrome() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        // Pad below the system status bar so the
                        // chrome doesn't overlap the system clock /
                        // notification icons in the screenshot. The
                        // real screen pulls this padding from its
                        // root scaffold; here we hard-code a Pixel-7
                        // status-bar height (24dp) so the test stays
                        // independent of the device's window insets
                        // (which createAndroidComposeRule does not
                        // always populate).
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    TmuxImeAwareTopChrome(
                        chromeCompressed = true,
                        crumbs = sampleCrumbs(windowLabel = "Window 2"),
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = {},
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 0,
                        onTabSelected = {},
                        windows = sampleWindows(3),
                        currentWindowId = "@2",
                        onOpenWindowSwitcher = {},
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-ime-up-compact.png"))
    }

    @androidx.compose.runtime.Composable
    private fun PaneProxy() {
        // A muted band below the chrome so the screenshots show the
        // boundary between the toolbar and the terminal viewport. The
        // actual terminal renderer is too heavy to mount here; the
        // proxy is enough to make the chrome reduction visible.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(PocketShellColors.TermBg)
                .padding(12.dp),
        )
    }

    private fun sampleCrumbs(windowLabel: String): List<Crumb> = listOf(
        Crumb(label = "hetzner", isCurrent = false, onClick = {}),
        Crumb(label = "claude-main", isCurrent = false, onClick = {}),
        Crumb(label = windowLabel, isCurrent = false, onClick = {}),
        Crumb(label = "Pane 1", isCurrent = true, onClick = {}),
    )

    private fun sampleWindows(count: Int): List<WindowSummary> =
        (1..count).map { idx -> WindowSummary(windowId = "@$idx", title = "Window $idx") }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/tmux-consolidated-chrome")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create consolidated-chrome screenshot dir: ${dir.absolutePath}"
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
                    "Could not write consolidated-chrome screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_CONSOLIDATED_CHROME_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val SCREENSHOT_ROOT_TAG = "tmux:consolidated-chrome-screenshot"
    }
}
