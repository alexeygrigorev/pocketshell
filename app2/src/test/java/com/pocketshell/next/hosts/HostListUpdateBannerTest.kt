package com.pocketshell.next.hosts

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Host-list update banner (issue #2531): visible on UpdateAvailable, gone
 * after dismiss of that tag's notice, and a failure is not painted as
 * "Up to date".
 */
@RunWith(RobolectricTestRunner::class)
class HostListUpdateBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val available = HostListUpdateNotice.Available(
        text = "v0.5.1 is available — you are on v0.5.0 · 5 Sep 2026",
        apkUrl = "https://example.com/pocketshell-0.5.1.apk",
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.5.1",
    )

    @Test
    fun bannerNamesNewTagCurrentVersionAndDateWithoutTime() {
        setContent(notice = available)

        composeRule.onNodeWithTag(HOST_LIST_UPDATE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(available.text, substring = true).assertIsDisplayed()
        assertFalse(available.text.contains(":"))
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_DOWNLOAD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_NOTES_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_DISMISS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Up to date").assertDoesNotExist()
    }

    @Test
    fun downloadAndNotesFireTheirUrls() {
        var downloaded: String? = null
        var notes: String? = null
        setContent(
            notice = available,
            onDownloadUpdate = { downloaded = it },
            onOpenReleaseNotes = { notes = it },
        )

        composeRule.onNodeWithTag(HOST_LIST_UPDATE_DOWNLOAD_TAG).performClick()
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_NOTES_TAG).performClick()

        assertEquals(available.apkUrl, downloaded)
        assertEquals(available.htmlUrl, notes)
    }

    @Test
    fun dismissHidesTheBanner_andANewerTagShowsAgain() {
        val notice = mutableStateOf<HostListUpdateNotice?>(available)
        composeRule.setContent {
            HostListScreen(
                state = populatedState(),
                onOpenHost = {},
                onAddHost = {},
                onEditHost = {},
                onScanQr = {},
                onOpenSettings = {},
                onDeleteHost = {},
                updateNotice = notice.value,
                onDismissUpdate = { notice.value = null },
            )
        }

        composeRule.onNodeWithTag(HOST_LIST_UPDATE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_DISMISS_TAG).performClick()
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_BANNER_TAG).assertDoesNotExist()

        composeRule.runOnIdle {
            notice.value = HostListUpdateNotice.Available(
                text = "v0.5.2 is available — you are on v0.5.0 · 6 Sep 2026",
                apkUrl = "https://example.com/pocketshell-0.5.2.apk",
                htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.5.2",
            )
        }
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("v0.5.2 is available", substring = true).assertIsDisplayed()
    }

    @Test
    fun failedBanner_isNotUpToDate_andRetryFires() {
        var retries = 0
        setContent(
            notice = HostListUpdateNotice.Failed("rate-limited, try again later"),
            onRetryUpdateCheck = { retries += 1 },
        )

        composeRule.onNodeWithTag(HOST_LIST_UPDATE_FAILURE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't check for updates", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Up to date").assertDoesNotExist()
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_RETRY_TAG).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun noBannerWhenIdle() {
        setContent(notice = null)
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_BANNER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(HOST_LIST_UPDATE_FAILURE_TAG).assertDoesNotExist()
    }

    private fun setContent(
        notice: HostListUpdateNotice?,
        onDownloadUpdate: (String) -> Unit = {},
        onOpenReleaseNotes: (String) -> Unit = {},
        onDismissUpdate: () -> Unit = {},
        onRetryUpdateCheck: () -> Unit = {},
        onDismissUpdateFailure: () -> Unit = {},
    ) {
        composeRule.setContent {
            HostListScreen(
                state = populatedState(),
                onOpenHost = {},
                onAddHost = {},
                onEditHost = {},
                onScanQr = {},
                onOpenSettings = {},
                onDeleteHost = {},
                updateNotice = notice,
                onDownloadUpdate = onDownloadUpdate,
                onOpenReleaseNotes = onOpenReleaseNotes,
                onDismissUpdate = onDismissUpdate,
                onRetryUpdateCheck = onRetryUpdateCheck,
                onDismissUpdateFailure = onDismissUpdateFailure,
            )
        }
    }

    private fun populatedState() = HostListUiState(
        loaded = true,
        hosts = listOf(HostRow(1, "hetzner", "alexey@135.181.114.209")),
    )
}
