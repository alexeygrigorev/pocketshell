package com.pocketshell.next.settings

import android.app.Instrumentation
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.next.MainActivity
import com.pocketshell.next.crash.CRASH_REPORTS_SHARE_ALL_TAG
import com.pocketshell.next.crash.CrashReportMetadata
import com.pocketshell.next.crash.CrashReporter
import com.pocketshell.next.crash.DIAGNOSTICS_PAGE_TAG
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.hosts.HOST_LIST_SETTINGS_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * J16: the production Settings/support path on a real Android window.
 *
 * The seed writes through the same Room/SharedPreferences/crash-report stores
 * the app uses. The assertions then drive the rendered Quiet rows and verify a
 * real chooser intent, so no preview-only route or second store can satisfy
 * this journey.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J16SettingsSupportJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val settings = graph.settingsRepository()
        settings.setTerminalTextSizePx(AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_PX)
        settings.setVoiceLanguage(AppSettings.VOICE_LANGUAGE_AUTO)
        settings.setVoiceSilenceThresholdSeconds(AppSettings.DEFAULT_VOICE_SILENCE_SECONDS)
        settings.setUsageWarnThresholdPercent(AppSettings.DEFAULT_USAGE_WARN_PERCENT)
        settings.setBackgroundGraceMillis(AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS)
        settings.setAgentSubmitEnterDelayMs(AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "crash-reports").deleteRecursively()
        CrashReporter.store(context).save(
            throwable = RuntimeException("j2610 support handoff"),
            threadName = "main",
            metadata = CrashReportMetadata(
                appVersion = "0.5.0",
                androidRelease = "15",
                sdkInt = 35,
                device = "connected-test",
            ),
        )
        println("J16_SEED ${description.methodName}")
    }

    @Test
    fun settingsCategoriesPersistChoicesAndDiagnosticsUseNativeShare() {
        awaitTag(HOST_LIST_SETTINGS_TAG)
        compose.onNodeWithTag(HOST_LIST_SETTINGS_TAG).performClick()
        awaitTag(SETTINGS_LIST_TAG)
        JourneyScreenshots.capture("01-settings-index", JOURNEY)

        compose.onNodeWithTag(settingsCategoryTag("voice")).performClick()
        awaitTag(SETTINGS_VOICE_PAGE_TAG)
        compose.onNodeWithTag("settings-voice-language").performClick()
        awaitTag(SETTINGS_LANGUAGE_PAGE_TAG)
        compose.onNodeWithTag(voiceLanguageOptionTag("ru")).performClick()
        assertEquals("ru", appGraph().settingsRepository().settings.value.voiceLanguage)
        JourneyScreenshots.capture("02-language", JOURNEY)
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        awaitTag(SETTINGS_VOICE_PAGE_TAG)
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        awaitTag(SETTINGS_LIST_TAG)

        compose.onNodeWithTag(settingsCategoryTag("connections")).performClick()
        awaitTag(SETTINGS_CONNECTIONS_PAGE_TAG)
        compose.onNodeWithTag("settings-connection-grace").performClick()
        awaitTag(SETTINGS_GRACE_PAGE_TAG)
        compose.onNodeWithTag(backgroundGraceOptionTag(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS))
            .performClick()
        assertEquals(
            AppSettings.BACKGROUND_GRACE_5_MINUTES_MS,
            appGraph().settingsRepository().settings.value.backgroundGraceMillis,
        )
        JourneyScreenshots.capture("03-grace", JOURNEY)
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        awaitTag(SETTINGS_CONNECTIONS_PAGE_TAG)
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        awaitTag(SETTINGS_LIST_TAG)

        compose.onNodeWithTag(settingsCategoryTag("about")).performClick()
        awaitTag(SETTINGS_ABOUT_PAGE_TAG)
        compose.onNodeWithText("Installed version").assertIsDisplayed()
        JourneyScreenshots.capture("04-about", JOURNEY)
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        awaitTag(SETTINGS_LIST_TAG)

        compose.onNodeWithTag(settingsCategoryTag("diagnostics")).performClick()
        awaitTag(DIAGNOSTICS_PAGE_TAG)
        compose.onNodeWithText("j2610 support handoff", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Share all (1)").assertIsDisplayed().assertIsEnabled()
        JourneyScreenshots.capture("05-diagnostics", JOURNEY)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithTag(CRASH_REPORTS_SHARE_ALL_TAG).performClick()
        assertTrue(
            "Share all must hand the real archive to Android's native chooser",
            waitForNativeChooser(instrumentation),
        )
        JourneyScreenshots.capture("06-share-chooser", JOURNEY)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForNativeChooser(instrumentation: Instrumentation): Boolean {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val activityDump = runCatching {
                val descriptor = instrumentation.uiAutomation.executeShellCommand(
                    "dumpsys activity activities",
                )
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                    reader.readText()
                }
            }.getOrDefault("")
            if ("com.android.intentresolver/.ChooserActivityLauncher" in activityDump) return true
            SystemClock.sleep(250L)
        }
        return false
    }

    companion object {
        private const val JOURNEY = "j16-settings-support"
    }
}
