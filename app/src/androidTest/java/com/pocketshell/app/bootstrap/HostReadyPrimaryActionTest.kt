package com.pocketshell.app.bootstrap

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Issue #836 — on the "Host ready" sheet (the [HostBootstrapSheetState.Success]
 * state) the PROMINENT, filled, primary call-to-action must be **Continue**
 * (go to the host's sessions / folder list), NOT "Open Usage".
 *
 * The maintainer's dogfood: *"after I update the CLI on the host, why exactly
 * Open Usage — it doesn't make sense."* Jumping to the quota panel is not the
 * natural next step after a host becomes ready; continuing to the sessions is.
 * This is the recurring "Usage shouldn't be the post-host-ready destination"
 * class (sibling of #427).
 *
 * This drives the REAL [HostBootstrapSheet] in its `Success` state with a
 * non-null `onOpenUsage` (the two-button row) on the emulator, and proves:
 *
 *  - Tapping **Continue** invokes `onContinue` (and NOT `onOpenUsage`) — the
 *    post-host-ready destination is the host's sessions, not Usage.
 *  - **Continue** is the filled PRIMARY button: its interior samples to the
 *    accent fill (`PocketShellColors.Accent`).
 *  - **Open Usage** is the SECONDARY outline button: its interior does NOT
 *    sample to the accent fill (transparent container over the dark sheet).
 *
 * The pixel-fill assertions are the load-bearing regression guard: if the
 * `ButtonVariant`s are ever inverted again (Continue=Secondary,
 * Open Usage=Primary), the Continue interior stops being accent-filled and the
 * Open Usage interior becomes accent-filled — and this test goes red.
 *
 * Direct-Compose (no `ActivityScenario.launch`) so it does not depend on the
 * full host-connect journey; the live Docker bootstrap journey is exercised
 * separately by [HostBootstrapScenarioSuiteTest].
 */
class HostReadyPrimaryActionTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun hostReadySheet_continueIsPrimary_openUsageIsSecondary() {
        // In the Success state the sheet wires the Continue button's onClick to
        // `onDismiss` (Continue == leave the sheet and land on the host's
        // sessions / folder list). Open Usage is wired to `onOpenUsage`.
        var continueClicks = 0
        var openUsageClicks = 0

        compose.setContent {
            PocketShellTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                ) {
                    HostBootstrapSheet(
                        state = HostBootstrapSheetState.Success,
                        hostName = "hetzner",
                        onInstall = {},
                        onInstallTool = {},
                        onSkip = {},
                        onDismiss = { continueClicks++ },
                        onOpenUsage = { openUsageClicks++ },
                    )
                }
            }
        }

        compose.waitForIdle()
        SystemClock.sleep(250)

        // Both actions are present (the affordance is preserved — #483).
        compose.onNodeWithTag(HOST_BOOTSTRAP_CONTINUE_TAG).assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_OPEN_USAGE_TAG).assertExists()

        capture("issue-836-host-ready.png")

        // The PRIMARY (filled) treatment must be on Continue, NOT Open Usage.
        val continueIsAccentFilled = nodeInteriorIsAccent(HOST_BOOTSTRAP_CONTINUE_TAG)
        val openUsageIsAccentFilled = nodeInteriorIsAccent(HOST_BOOTSTRAP_OPEN_USAGE_TAG)

        assertTrue(
            "Continue must be the filled PRIMARY action (accent-filled interior); " +
                "if this fails the host-ready button variants are inverted (#836).",
            continueIsAccentFilled,
        )
        assertTrue(
            "Open Usage must be the SECONDARY (outline) action — its interior must " +
                "NOT be accent-filled; if this fails Open Usage is the primary CTA again (#836).",
            !openUsageIsAccentFilled,
        )

        // Tapping the primary Continue button must route to Continue (the
        // host's sessions), NOT open Usage (the #836/#427-class guard).
        continueClicks = 0
        openUsageClicks = 0
        compose.onNodeWithTag(HOST_BOOTSTRAP_CONTINUE_TAG).performClick()
        compose.waitForIdle()
        assertTrue(
            "Tapping the primary Continue button must invoke the continue/sessions " +
                "path (onDismiss), got continueClicks=$continueClicks.",
            continueClicks == 1,
        )
        assertTrue(
            "Tapping the primary Continue button must NOT open Usage (#836/#427 class), " +
                "got openUsageClicks=$openUsageClicks.",
            openUsageClicks == 0,
        )
    }

    /**
     * Captures the node's own image and reports whether its INTERIOR is
     * accent-FILLED (the PRIMARY treatment) vs merely accent-bordered/lettered
     * (the SECONDARY outline treatment).
     *
     * Sampling a single centre pixel is unreliable — it can land on the cyan
     * label glyph of the outline button and falsely read as "filled". Instead we
     * compute the FRACTION of accent-coloured pixels across the whole button
     * rect: a filled PRIMARY button is mostly accent (≫50%); a SECONDARY outline
     * button is a transparent fill with only a thin accent border + accent text,
     * so well under half its pixels are accent.
     */
    private fun nodeInteriorIsAccent(tag: String): Boolean {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        var accent = 0
        var total = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                total++
                if (colorsClose(bitmap.getPixel(x, y), PocketShellColors.Accent)) {
                    accent++
                }
            }
        }
        val fraction = if (total == 0) 0.0 else accent.toDouble() / total
        return fraction > 0.5
    }

    private fun colorsClose(argb: Int, target: Color, tolerance: Int = 40): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val tr = (target.red * 255f).toInt()
        val tg = (target.green * 255f).toInt()
        val tb = (target.blue * 255f).toInt()
        return abs(r - tr) <= tolerance && abs(g - tg) <= tolerance && abs(b - tb) <= tolerance
    }

    private fun capture(name: String) {
        val dir = ensureArtifactDir()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("HOST_READY_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/host-ready")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create host-ready screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }
}
