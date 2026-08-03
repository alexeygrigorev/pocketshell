package com.pocketshell.app.bootstrap

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pocketshell.app.hosts.AppUpdateWarningBanner
import com.pocketshell.app.hosts.HOST_LIST_APP_UPDATE_WARNING_TAG
import com.pocketshell.app.hosts.HOST_LIST_UPDATE_CHECK_FAILED_TAG
import com.pocketshell.app.hosts.HostListViewModel
import com.pocketshell.app.hosts.UpdateCheckFailedBanner
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppUpdateDismissSelectorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appUpdateDismissTargetsOnlyItsBannerWhenAnotherDismissActionIsVisible() {
        val showAppUpdate = mutableStateOf(true)
        val showUnrelated = mutableStateOf(true)

        compose.setContent {
            PocketShellTheme {
                Column {
                    if (showUnrelated.value) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.testTag(HOST_LIST_UPDATE_CHECK_FAILED_TAG),
                        ) {
                            UpdateCheckFailedBanner(
                                reason = "fixture failure",
                                onRetry = {},
                                onDismiss = { showUnrelated.value = false },
                            )
                        }
                    }
                    if (showAppUpdate.value) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.testTag(HOST_LIST_APP_UPDATE_WARNING_TAG),
                        ) {
                            AppUpdateWarningBanner(
                                warning = HostListViewModel.AppUpdateWarning(
                                    hostId = 1L,
                                    remoteVersion = "9.9.9",
                                    appVersion = "1.0.0",
                                ),
                                onUpdate = null,
                                onRetry = {},
                                onDismiss = { showAppUpdate.value = false },
                            )
                        }
                    }
                }
            }
        }

        val dismissActions = compose.onAllNodesWithText("Dismiss")
        assertEquals(
            "fixture must render two globally ambiguous Dismiss actions",
            2,
            dismissActions.fetchSemanticsNodes().size,
        )
        dismissActions[0].assertIsDisplayed()
        dismissActions[1].assertIsDisplayed()

        dismissActionWithin(HOST_LIST_APP_UPDATE_WARNING_TAG).performClick()

        compose.onNodeWithTag(HOST_LIST_APP_UPDATE_WARNING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(HOST_LIST_UPDATE_CHECK_FAILED_TAG).assertIsDisplayed()
        dismissActionWithin(HOST_LIST_UPDATE_CHECK_FAILED_TAG).assertIsDisplayed()
    }

    private fun dismissActionWithin(ancestorTag: String) = compose.onNode(
        hasText("Dismiss") and hasAnyAncestor(hasTestTag(ancestorTag)),
    )
}
