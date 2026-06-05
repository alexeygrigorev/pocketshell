package com.pocketshell.app.proof

import android.util.Log
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
import com.pocketshell.app.costs.COSTS_TITLE_TAG
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.settings.SETTINGS_LAZY_COLUMN_TAG
import com.pocketshell.app.settings.SETTINGS_TITLE_TAG
import com.pocketshell.app.settings.VOICE_AI_COSTS_ROW_TAG
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #520 — on-device foreground-package proof for the navigator-level
 * [androidx.activity.compose.BackHandler], using a non-SSH journey so it runs
 * deterministically even on a contended/low-memory AVD (the Docker-connected
 * host-detail/terminal journey is exercised by
 * [SystemBackForegroundE2eTest], but the in-app SSH host-open can stall on a
 * cold/contended AVD — #470 — so this companion proves the same back-routing
 * fix without any SSH dependency).
 *
 * The AI Costs screen ([com.pocketshell.app.costs.CostsScreen]) is a non-root
 * destination that did NOT register its own `BackHandler` before this fix —
 * so before #520, system Back on it fell through to the Activity default and
 * exited the app to the launcher. It is reached with zero network:
 *
 *   host list -> Settings -> AI Costs
 *
 * The test then dispatches a real system Back via the activity's
 * [androidx.activity.OnBackPressedDispatcher] (the same dispatcher the
 * framework routes the hardware Back key through) and asserts:
 *
 *  1. Back from AI Costs returns to Settings (not the launcher).
 *  2. Back from Settings returns to the host list (not the launcher).
 *  3. At every step the resumed activity is `com.pocketshell.app/.MainActivity`,
 *     the process is IMPORTANCE_FOREGROUND, and MainActivity is not finishing.
 *
 * The foreground state is read from inside the app process (resumed activity
 * component name + process importance) rather than via
 * `UiAutomation.executeShellCommand("dumpsys ...")`, because that shares the
 * single UiAutomation connection the Compose test framework drives and a
 * blocking shell command mid-journey desyncs it.
 */
@RunWith(AndroidJUnit4::class)
class NavigatorBackForegroundNoSshE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val foregroundLog = mutableListOf<String>()

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun systemBackOnNonRootScreensKeepsAppForegrounded() {
        clearLastSessionPrefs()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Land on the host list (the Settings button is in its app bar).
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        recordForeground("00-host-list")

        // host list -> Settings (Settings has its own BackHandler; used here
        // only as the parent of the AI Costs screen).
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SETTINGS_TITLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        recordForeground("01-settings")

        // Settings -> AI Costs (CostsScreen — NO own BackHandler before #520).
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(VOICE_AI_COSTS_ROW_TAG))
        compose.onNodeWithTag(VOICE_AI_COSTS_ROW_TAG).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COSTS_TITLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        recordForeground("02-ai-costs")

        // --- System Back from AI Costs must return to Settings (the navigator
        // BackHandler fires; before #520 this exited the app).
        pressSystemBack()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SETTINGS_TITLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        recordForeground("03-after-back-from-ai-costs")
        assertActivityAlive("AI Costs Back")
        compose.onNodeWithTag(SETTINGS_TITLE_TAG, useUnmergedTree = true).assertExists()

        // --- System Back from Settings must return to the host list.
        pressSystemBack()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        recordForeground("04-after-back-from-settings")
        assertActivityAlive("Settings Back")
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).assertExists()

        writeForegroundArtifact()

        foregroundLog.forEach { line ->
            assertTrue(
                "foreground must stay PocketShell after system Back; got: $line",
                line.contains("com.pocketshell.app"),
            )
            assertFalse(
                "foreground must NOT be the launcher after system Back; got: $line",
                line.contains("com.android.launcher"),
            )
            assertTrue(
                "PocketShell must remain foreground (process importance) after Back; got: $line",
                line.contains("foreground=true"),
            )
            assertTrue(
                "MainActivity must not be finishing after Back; got: $line",
                line.contains("finishing=false"),
            )
        }
    }

    private fun assertActivityAlive(label: String) {
        var finishing = true
        launchedActivity?.onActivity { activity ->
            finishing = activity.isFinishing || activity.isDestroyed
        }
        assertFalse(
            "MainActivity must NOT be finishing/destroyed after $label (app must not exit)",
            finishing,
        )
    }

    private fun pressSystemBack() {
        launchedActivity?.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun recordForeground(label: String) {
        var resumedComponent = "<no activity>"
        var finishing = true
        launchedActivity?.onActivity { activity ->
            resumedComponent = activity.componentName.flattenToShortString()
            finishing = activity.isFinishing || activity.isDestroyed
        }
        val am = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val myPid = android.os.Process.myPid()
        val importance = am.runningAppProcesses
            ?.firstOrNull { it.pid == myPid }
            ?.importance
            ?: -1
        val foreground = importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val line = "$label => resumedActivity=$resumedComponent finishing=$finishing " +
            "processImportance=$importance foreground=$foreground"
        foregroundLog += line
        Log.i(LOG_TAG, "ISSUE520_FOREGROUND $line")
        println("ISSUE520_FOREGROUND $line")
    }

    private fun writeForegroundArtifact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        val file = File(dir, "foreground-package-no-ssh.txt")
        file.writeText(foregroundLog.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE520_FOREGROUND_ARTIFACT ${file.absolutePath}")
    }

    private companion object {
        const val LOG_TAG: String = "Issue520NavBack"
        const val DEVICE_DIR_NAME: String = "issue520-system-back"
    }
}
