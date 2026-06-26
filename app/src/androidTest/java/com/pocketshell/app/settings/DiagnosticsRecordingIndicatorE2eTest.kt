package com.pocketshell.app.settings

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.clearLastSessionPrefs
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #969 (was #549): the diagnostics flight recorder now defaults ON so a
 * FRESH install captures the FIRST reconnect (the one that matters, previously
 * lost under #549's opt-in). The Diagnostics section shows a clear "REC"
 * indicator while recording is enabled, and the Settings toggle still turns it
 * OFF.
 *
 * This drives the real Settings screen end-to-end:
 *   1. On a fresh install (app_settings cleared) the recording switch is ON and
 *      the REC indicator is PRESENT (the #969 default-on behaviour).
 *   2. Tapping the switch turns recording OFF and the REC indicator disappears
 *      (the opt-out path still works).
 *
 * It captures full-device screenshots in both states for visual inspection of
 * the default-on copy + indicator.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsRecordingIndicatorE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstall() {
        clearLastSessionPrefs()
        // Issue #969: prove default-ON from a genuinely fresh settings store.
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun cleanup() {
        launchedActivity?.close()
        launchedActivity = null
        clearLastSessionPrefs()
    }

    @Test
    fun recordingIsOnByDefaultAndIndicatorDisappearsWhenDisabled() {
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG).assertExists()

        // Scroll to the Diagnostics section.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(sectionLabelTestTag("Diagnostics")))
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(DIAGNOSTICS_RECORDING_SWITCH_TAG))

        // Issue #969: default ON — the REC indicator is present on a fresh
        // install, so the first reconnect is captured.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(
                DIAGNOSTICS_RECORDING_INDICATOR_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(DIAGNOSTICS_RECORDING_INDICATOR_TAG, useUnmergedTree = true)
            .assertExists()
        // The default-on value is persisted on a genuinely fresh settings store.
        assertEquals(
            true,
            SettingsRepository(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).settings.value.diagnosticsRecordingEnabled,
        )
        val dir = screenshotDir()
        captureFullDevice(File(dir, "diagnostics-01-on-default.png"))

        // Turn recording OFF (the opt-out path still works).
        compose.onNodeWithTag(DIAGNOSTICS_RECORDING_SWITCH_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(
                DIAGNOSTICS_RECORDING_INDICATOR_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isEmpty()
        }

        // Recording OFF: REC indicator is gone.
        compose.onNodeWithTag(DIAGNOSTICS_RECORDING_INDICATOR_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        captureFullDevice(File(dir, "diagnostics-02-off-disabled.png"))

        // Persisted OFF in settings, proving the opt-out toggle path.
        assertEquals(
            false,
            SettingsRepository(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).settings.value.diagnosticsRecordingEnabled,
        )
    }

    private fun screenshotDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/diagnostics-indicator")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create diagnostics-indicator screenshot dir: ${dir.absolutePath}"
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
                    "Could not write diagnostics-indicator screenshot: ${file.absolutePath}"
                }
            }
            println("DIAGNOSTICS_INDICATOR_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
