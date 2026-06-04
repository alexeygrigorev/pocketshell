package com.pocketshell.app.settings

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.proof.PreGrantPermissionsRule
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #496 end-to-end coverage for the Settings → conversation font-size
 * slider.
 *
 * The journey under test:
 *
 * 1. User opens Settings on a freshly-wiped prefs file (default 13sp).
 * 2. User drags the "Conversation font size" slider toward the maximum.
 * 3. The new value is written synchronously to SharedPreferences, so a
 *    cold-read from a freshly-built [SettingsRepository] — the same path a
 *    real process restart takes — observes the bumped value.
 *
 * A screenshot of the Settings card with both font sliders is captured for
 * the reviewer.
 *
 * Mirrors [SettingsPersistenceE2eTest] for the launch/wipe/cold-read
 * mechanics (and the rationale for not invoking `am force-stop`). No Docker,
 * SSH, or remote fixture; runs on the bare emulator in seconds.
 */
@RunWith(AndroidJUnit4::class)
class ConversationFontSizeSettingE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @Before
    fun wipeSettings() {
        ctx().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Baseline sanity: the conversation font starts at the compact default.
        assertEquals(
            "expected conversation font default after wipe",
            AppSettings.DEFAULT_CONVERSATION_FONT_SP,
            SettingsRepository(ctx()).settings.value.conversationFontSizeSp,
            0f,
        )
    }

    @After
    fun cleanup() {
        launchedActivity?.close()
        launchedActivity = null
        ctx().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun conversationFontSlider_persistsBumpedValue() {
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            compose.onAllNodesWithTag(SETTINGS_TITLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Scroll the conversation font slider into view and capture the
        // Terminal card with both font sliders visible.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(CONVERSATION_FONT_SLIDER_TAG))
        compose.onNodeWithTag(CONVERSATION_FONT_VALUE_TAG, useUnmergedTree = true).assertExists()
        captureFullDevice(File(artifactDir(), "issue-496-settings-conversation-slider.png"))

        // Drive the slider to the maximum via its SetProgress semantics
        // action — the deterministic way to move a Compose Slider in a test
        // (a raw touch swipe on the thumb is flaky across emulator densities).
        // We assert the value strictly increased above the 13sp default,
        // which is the user-visible contract.
        compose.onNodeWithTag(CONVERSATION_FONT_SLIDER_TAG).performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { setProgress -> setProgress(AppSettings.MAX_CONVERSATION_FONT_SP) }
        compose.waitForIdle()

        val bumped = SettingsRepository(ctx()).settings.value.conversationFontSizeSp
        assertTrue(
            "slider drag should raise the conversation font above the 13sp default " +
                "(got $bumped)",
            bumped > AppSettings.DEFAULT_CONVERSATION_FONT_SP,
        )
        assertNotEquals(
            "bumped value must differ from default",
            AppSettings.DEFAULT_CONVERSATION_FONT_SP,
            bumped,
        )
        assertTrue(
            "bumped value must not exceed the documented max",
            bumped <= AppSettings.MAX_CONVERSATION_FONT_SP,
        )
    }

    private fun ctx(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/issue-496-conversation-font")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-496 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-496 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_496_SETTINGS_SLIDER_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val WAIT_TIMEOUT_MS: Long = 10_000
        const val SETTINGS_PREFS_NAME: String = "app_settings"
    }
}
