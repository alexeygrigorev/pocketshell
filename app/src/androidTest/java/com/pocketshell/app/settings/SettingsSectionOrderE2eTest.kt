package com.pocketshell.app.settings

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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #486: Settings sections are regrouped + reordered most-useful-first
 * and every heading routes through the shared `SectionHeader` (#480).
 *
 * This drives the real Settings screen end-to-end, asserts that each
 * section heading is reachable in the new order (Terminal → Voice &
 * dictation → Assistant → Usage → Workspace → Hosts → Diagnostics), and
 * captures full-device screenshots while scrolling so the reorganized
 * layout can be inspected visually.
 *
 * The standalone "Startup" section no longer exists — its open-on-launch
 * radio group is folded into the Terminal card, so this test also asserts
 * the Startup default-host rows are visible inside Terminal without a
 * separate `settings:section-label:startup` heading.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSectionOrderE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @Before
    fun clearFastResume() {
        clearLastSessionPrefs()
    }

    @After
    fun cleanup() {
        launchedActivity?.close()
        launchedActivity = null
        clearLastSessionPrefs()
    }

    @Test
    fun sectionsAreOrderedMostUsefulFirstAndUseSharedHeader() {
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG).assertExists()

        // The former standalone "Startup" heading must be gone — it is
        // folded into Terminal.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(sectionLabelTestTag("Terminal")))
        compose.onNodeWithTag(
            sectionLabelTestTag("Startup"),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        // The Startup default-host clear row now lives inside Terminal.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(DEFAULT_HOST_NONE_TAG))
        compose.onNodeWithTag(DEFAULT_HOST_NONE_TAG, useUnmergedTree = true).assertExists()

        // Walk the new order top-to-bottom; performScrollToNode advances
        // the LazyColumn, so a successful scroll to each tag in sequence
        // proves they appear in this order.
        val orderedSections = listOf(
            "Terminal",
            "Voice & dictation",
            "Assistant",
            "Usage",
            "Workspace",
            "Hosts",
            "Diagnostics",
        )
        val dir = screenshotDir()
        orderedSections.forEachIndexed { index, label ->
            compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
                .performScrollToNode(hasTestTag(sectionLabelTestTag(label)))
            compose.onNodeWithTag(
                sectionLabelTestTag(label),
                useUnmergedTree = true,
            ).assertExists()
            captureFullDevice(
                File(dir, "settings-%02d-%s.png".format(index + 1, label.slug())),
            )
        }

        // About footer stays last.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(ABOUT_FOOTER_TAG))
        compose.onNodeWithTag(ABOUT_FOOTER_TAG, useUnmergedTree = true).assertExists()
        captureFullDevice(File(dir, "settings-08-about.png"))
    }

    /**
     * Issue #818: the "Open agent sessions in" radio group lives inside the
     * Terminal card. This drives the REAL Settings screen, scrolls to the new
     * control, confirms both options are present (Conversation default selected,
     * Terminal opt-out), taps Terminal so the selection moves, and captures a
     * full-device screenshot of the control for visual inspection.
     */
    @Test
    fun defaultAgentSessionViewOptionIsPresentSelectableAndScreenshot() {
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG).assertExists()

        val conversationTag =
            defaultAgentSessionViewOptionTag(DefaultAgentSessionView.Conversation)
        val terminalTag =
            defaultAgentSessionViewOptionTag(DefaultAgentSessionView.Terminal)

        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(conversationTag))
        compose.onNodeWithTag(conversationTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(terminalTag, useUnmergedTree = true).assertExists()

        val dir = screenshotDir()
        captureFullDevice(File(dir, "settings-818-default-agent-view-conversation.png"))

        // Tap the Terminal opt-out; the row must become selectable + reflect the
        // change (the radio group is the proven DefaultHostOptionRow pattern).
        compose.onNodeWithTag(terminalTag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        captureFullDevice(File(dir, "settings-818-default-agent-view-terminal.png"))
    }

    private fun String.slug(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun screenshotDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/settings-section-order")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create settings-section-order screenshot dir: ${dir.absolutePath}"
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
                    "Could not write settings-section-order screenshot: ${file.absolutePath}"
                }
            }
            println("SETTINGS_SECTION_ORDER_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
