package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #445 (epic #432 slice A): the tmux session kebab must expose a
 * "Port forwarding" item that, when tapped, fires the navigation callback
 * MainActivity wires to `navigate(AppDestination.PortForwardPanel(...))`.
 *
 * This drives the real [TmuxMoreMenu] composable (the menu rendered by the
 * live session screen's kebab), so it verifies both that the item is
 * present in the menu and that the click is routed to
 * `onOpenPortForwarding`. The return-to-session path itself is the
 * hand-rolled `MainActivity` back-stack (`onBack = ::back`), identical to
 * the proven `onOpenJobs` precedent, so a full nav round-trip is not
 * re-asserted here.
 */
@RunWith(AndroidJUnit4::class)
class TmuxMoreMenuPortForwardingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun portForwardingItemIsPresentAndInvokesCallback() {
        var portForwardingClicks = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                val expanded = mutableStateOf(true)
                TmuxMoreMenu(
                    expanded = expanded.value,
                    currentWindowId = "@1",
                    multipleWindows = true,
                    onDismiss = { expanded.value = false },
                    onCreateSession = {},
                    onRenameSession = {},
                    onKillSession = {},
                    onSwitchSession = {},
                    onOpenJobs = {},
                    onOpenUsage = {},
                    onOpenPortForwarding = { portForwardingClicks++ },
                    onNewWindow = {},
                    onRenameWindow = {},
                    onKillWindow = {},
                    onDetach = {},
                    onSwitchWindow = {},
                )
            }
        }

        compose.onNodeWithText("Port forwarding").assertIsDisplayed()
        // Visual evidence: the open kebab with the new "Port forwarding"
        // item, before the click dismisses nothing (menu stays in this
        // harness because expanded is a constant). Reviewer artifact.
        captureFullDevice(File(artifactDir(), "kebab-port-forwarding-item.png"))

        compose
            .onNodeWithTag(TMUX_PORT_FORWARDING_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitForIdle()

        assertTrue(
            "Port forwarding kebab item should invoke onOpenPortForwarding",
            portForwardingClicks == 1,
        )
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/tmux-port-forwarding-kebab")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create port-forwarding-kebab screenshot dir: ${dir.absolutePath}"
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
                    "Could not write port-forwarding-kebab screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_PORT_FORWARDING_KEBAB_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
