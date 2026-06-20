package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #862 evidence — the file viewer loading state now renders the shared
 * [com.pocketshell.uikit.components.LoadingIndicator.Spinner] (the spinner sweep
 * from the #756 design-consistency audit), not a raw
 * `CircularProgressIndicator`.
 *
 * Drives the production [FileViewerScaffold] in the `Loading` state — which
 * composes `LoadingPanel()`, the call site that was converted to
 * `LoadingIndicator.Spinner()` — asserts the loading affordance is fully within
 * the viewport, and captures a full-device emulator screenshot so the reviewer
 * can confirm the shared accent spinner is shown.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerLoadingSpinnerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loadingStateShowsSharedSpinner() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Loading("/home/agent/notes/big-report.pdf"),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        // The loading affordance (now LoadingIndicator.Spinner) is laid out and
        // fully within the viewport — the shared spinner replaces the raw
        // CircularProgressIndicator at the LoadingPanel call site.
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_LOADING_TAG)
        compose.waitForIdle()
        SystemClock.sleep(300)
        captureFullDevice("file-viewer-loading-spinner.png")
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/file-viewer-loading").apply { mkdirs() }
        val file = File(dir, name)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("FILE_VIEWER_LOADING_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
