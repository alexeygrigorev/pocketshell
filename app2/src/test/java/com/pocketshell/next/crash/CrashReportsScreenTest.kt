package com.pocketshell.next.crash

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CrashReportsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        clearReports()
    }

    @After
    fun tearDown() {
        clearReports()
    }

    @Test
    fun `diagnostics names the latest report and exposes the review before sharing flow`() {
        seedReport()
        val vm = CrashReportsViewModel(context)
        var openedReport: String? = null

        composeRule.setContent {
            PocketShellTheme {
                DiagnosticsScreen(
                    onBack = {},
                    onOpenReport = { openedReport = it },
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Latest report").assertIsDisplayed()
        composeRule.onNodeWithText("Export latest report").assertIsDisplayed()
        composeRule.onNodeWithText("Review before sharing").assertIsDisplayed()
        composeRule.onNodeWithTag(DIAGNOSTICS_PAGE_TAG)
            .performScrollToNode(hasText("Clear local reports…"))
        composeRule.onNodeWithText("Clear local reports…").performClick()
        composeRule.onNodeWithText("Clear local reports?").assertIsDisplayed()
        composeRule.onNodeWithText("Your hosts, keys and remote sessions are unchanged.", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Export latest report").performClick()
        assertNotNull(openedReport)
    }

    @Test
    fun `report starts summarized and reveals technical details on demand`() {
        val report = seedReport()
        val vm = CrashReportsViewModel(context)

        composeRule.setContent {
            PocketShellTheme {
                DiagnosticReportScreen(
                    reportId = report.id,
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Connection report").assertIsDisplayed()
        composeRule.onNodeWithText("Technical details").assertIsDisplayed()
        composeRule.onNodeWithTag(DIAGNOSTIC_REPORT_PAGE_TAG)
            .performScrollToNode(hasTestTag(CRASH_REPORT_PRIVACY_TAG))
        composeRule.onNodeWithTag(CRASH_REPORT_PRIVACY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("PocketShell crash report", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag(CRASH_REPORT_TECHNICAL_DETAILS_TAG).performClick()
        composeRule.onNodeWithText("PocketShell crash report", substring = true).assertIsDisplayed()
    }

    private fun seedReport(): CrashReport = CrashReporter.store(context).save(
        throwable = IllegalStateException("connection interrupted"),
        threadName = "main",
        metadata = CrashReportMetadata(
            appVersion = "0.5.0",
            androidRelease = "15",
            sdkInt = 35,
            device = "test",
        ),
        context = CrashReportContext(screen = "Terminal", hostName = "hetzner"),
    )

    private fun clearReports() {
        File(context.filesDir, "crash-reports").deleteRecursively()
    }
}
