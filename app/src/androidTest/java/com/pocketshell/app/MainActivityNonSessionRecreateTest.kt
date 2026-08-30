package com.pocketshell.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.pocketshell.app.fileviewer.FILE_VIEWER_SCREEN_TAG
import com.pocketshell.app.hosts.ADD_HOST_NAME_FIELD_TAG
import com.pocketshell.app.hosts.HOST_LIST_ADD_FAB_TAG
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.settings.SETTINGS_BACK_TAG
import com.pocketshell.app.settings.SETTINGS_TITLE_TAG
import com.pocketshell.app.nav.AppDestination
import java.util.Collections
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test

class MainActivityNonSessionRecreateTest {
    private val reported = Collections.synchronizedList(mutableListOf<AppDestination>())
    private val captured = Collections.synchronizedList(mutableListOf<AppDestination>())
    private val probeRule = object : TestWatcher() {
        override fun starting(description: Description) {
            NavigationCallbackProbe.onReported = { reported += it }
            NavigationCallbackProbe.onCaptured = { captured += it }
        }

        override fun finished(description: Description) {
            NavigationCallbackProbe.reset()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:org.junit.Rule
    val rules: RuleChain = RuleChain.outerRule(probeRule).around(compose)

    @Test
    fun settingsRouteAndBackStackSurviveActivityRecreation() {
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        compose.onNodeWithTag(SETTINGS_TITLE_TAG).assertExists()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag(SETTINGS_TITLE_TAG).assertExists()
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG).assertExists()
    }

    @Test
    fun addHostRouteAndInProgressFormSurviveActivityRecreation() {
        compose.onNodeWithTag(HOST_LIST_ADD_FAB_TAG).performClick()
        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG).performTextInput("unfinished host")

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG)
            .assertExists()
            .assertTextContains("unfinished host")
    }

    @Test
    fun fileViewerRouteAndInMemoryCredentialsSurviveActivityRecreation() {
        val secret = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val destination = AppDestination.FileViewer(
            hostId = 42L,
            hostName = "test",
            hostname = "127.0.0.1",
            port = 1,
            username = "nobody",
            keyPath = "/missing/key",
            passphrase = secret,
            remotePath = "/tmp/image.png",
            cwd = "/tmp",
        )
        compose.activityRule.scenario.onActivity { activity ->
            val retained = ViewModelProvider(activity)[RetainedNavigationState::class.java]
            retained.backStack += AppDestination.HostList
            retained.current = destination
        }

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag(FILE_VIEWER_SCREEN_TAG).assertExists()
        compose.activityRule.scenario.onActivity { activity ->
            val retained = ViewModelProvider(activity)[RetainedNavigationState::class.java]
            val restored = retained.current as AppDestination.FileViewer
            org.junit.Assert.assertSame(secret, restored.passphrase)
            org.junit.Assert.assertEquals(destination, restored)
        }
    }

    @Test
    fun realNavigatorReportsEachTransitionOnceAcrossBackRevisitAndRecreation() {
        compose.waitUntil(5_000) { reported.size == 1 }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        compose.waitUntil(5_000) { reported.size == 4 }

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(SETTINGS_TITLE_TAG).assertExists()

        org.junit.Assert.assertEquals(
            listOf(
                AppDestination.HostList,
                AppDestination.Settings,
                AppDestination.HostList,
                AppDestination.Settings,
            ),
            reported.toList(),
        )
        org.junit.Assert.assertEquals(AppDestination.Settings, captured.last())
        org.junit.Assert.assertEquals(5, captured.size)
    }
}
