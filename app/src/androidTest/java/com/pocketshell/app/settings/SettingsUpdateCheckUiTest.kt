package com.pocketshell.app.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.release.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #515 reopened: Settings → About provides a deterministic manual
 * update path even when the host-list update banner was missed.
 */
@RunWith(AndroidJUnit4::class)
class SettingsUpdateCheckUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val appBuildInfo = AppBuildInfo(versionName = "0.3.22", versionCode = 322)
    private val releaseInfo = ReleaseInfo(
        tagName = "v9999.0.0",
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v9999.0.0",
        apkUrl = "https://example.com/pocketshell-9999.0.0-debug.apk",
    )

    @Test
    fun aboutFooter_idleCheckRowRunsManualUpdateCheck() {
        var checkCount = 0

        compose.setContent {
            AboutFooter(
                appBuildInfo = appBuildInfo,
                updateCheckState = SettingsUpdateCheckState.Idle,
                onCheckForUpdates = { checkCount += 1 },
            )
        }

        compose.onNodeWithTag(ABOUT_UPDATE_CHECK_ROW_TAG).assertExists()
        compose.onNodeWithText("Check for updates").assertExists()
        compose.onNodeWithTag(ABOUT_UPDATE_CHECK_ROW_TAG).performClick()

        assertEquals(1, checkCount)
    }

    @Test
    fun aboutFooter_availableUpdateInvokesDownloadCallback() {
        var requestedDownload: ReleaseInfo? = null

        compose.setContent {
            AboutFooter(
                appBuildInfo = appBuildInfo,
                updateCheckState = SettingsUpdateCheckState.UpdateAvailable(releaseInfo),
                onDownloadUpdate = { info -> requestedDownload = info },
            )
        }

        compose.onNodeWithText("Download v9999.0.0").assertExists()
        compose.onNodeWithTag(ABOUT_UPDATE_CHECK_ROW_TAG).performClick()

        assertEquals(releaseInfo, requestedDownload)
    }

    @Test
    fun aboutFooter_failedUpdateCheckShowsRetryableReason() {
        var retryCount = 0

        compose.setContent {
            AboutFooter(
                appBuildInfo = appBuildInfo,
                updateCheckState = SettingsUpdateCheckState.Failed("GitHub returned HTTP 403"),
                onCheckForUpdates = { retryCount += 1 },
            )
        }

        compose.onNodeWithText("Retry update check").assertExists()
        compose.onNodeWithText("Couldn't check for updates", substring = true).assertExists()
        compose.onNodeWithTag(ABOUT_UPDATE_CHECK_ROW_TAG).performClick()

        assertEquals(1, retryCount)
    }

    @Test
    fun aboutFooter_downloadFailedRetriesKnownUpdateDownload() {
        var checkCount = 0
        var requestedDownload: ReleaseInfo? = null

        compose.setContent {
            AboutFooter(
                appBuildInfo = appBuildInfo,
                updateCheckState = SettingsUpdateCheckState.DownloadFailed(
                    info = releaseInfo,
                    reason = "no app can open the download link",
                ),
                onCheckForUpdates = { checkCount += 1 },
                onDownloadUpdate = { info -> requestedDownload = info },
            )
        }

        compose.onNodeWithText("Open update again").assertExists()
        compose.onNodeWithText("Couldn't start the download", substring = true).assertExists()
        compose.onNodeWithTag(ABOUT_UPDATE_CHECK_ROW_TAG).performClick()

        assertEquals(0, checkCount)
        assertEquals(releaseInfo, requestedDownload)
    }

}
