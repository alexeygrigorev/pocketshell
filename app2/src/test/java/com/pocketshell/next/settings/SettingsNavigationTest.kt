package com.pocketshell.next.settings

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.crash.CRASH_REPORTS_SHARE_ALL_TAG
import com.pocketshell.next.crash.CrashReportMetadata
import com.pocketshell.next.crash.CrashReporter
import com.pocketshell.next.crash.CrashReportsScreen
import com.pocketshell.next.crash.CrashReportsViewModel
import com.pocketshell.next.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Settings, Workspace roots and the crash-report browser (issue #2476) through
 * the REAL screens inside the REAL navigation graph — task P-6's "wire it into
 * `MainActivity`" acceptance,
 * proven the same way [com.pocketshell.next.hosts.AddEditHostNavigationTest]
 * proves the host form is reachable and functional, not just that the route
 * string parses.
 *
 * The ViewModels are constructed explicitly (not through `hiltViewModel()`)
 * for the same reason as that suite: a Robolectric `createComposeRule()`
 * composition has no Hilt-managed Activity to resolve against.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h4000dp")
class SettingsNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var stack: TestConnectStack
    private var hostId: Long = 0

    @Before
    fun setUp() {
        clearCrashReports()
        stack = TestConnectStack()
        hostId = runBlocking {
            val keyId = stack.db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
            stack.db.hostDao().insert(
                HostEntity(name = "hetzner", hostname = "10.0.0.1", username = "alexey", keyId = keyId),
            )
        }
    }

    @After
    fun tearDown() {
        stack.close()
        clearCrashReports()
    }

    @Test
    fun `settings opens, and its host row opens that host's workspace roots`() {
        val nav = setContent()

        navigateToSettings(nav)
        composeRule.onNodeWithText("hetzner").assertExists()

        composeRule.onNodeWithText("hetzner").performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.WorkspaceRoots.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_EMPTY_TAG).assertExists()
    }

    @Test
    fun `adding a workspace root through the real screen persists it to Room`() {
        val nav = setContent()
        navigateToSettings(nav)
        composeRule.onNodeWithText("hetzner").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_PATH_FIELD_TAG).performTextInput("/home/alexey/git/pocketshell")
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_LABEL_FIELD_TAG).performTextInput("Pocketshell")
        composeRule.onNodeWithTag(WORKSPACE_ROOTS_ADD_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Pocketshell").assertExists()
        val stored = runBlocking { stack.db.projectRootDao().getByHostId(hostId).first() }
        assertEquals(1, stored.size)
        assertEquals("/home/alexey/git/pocketshell", stored.single().path)
    }

    @Test
    fun `back from workspace roots returns to settings, and back from settings leaves the graph`() {
        val nav = setContent()
        navigateToSettings(nav)
        composeRule.onNodeWithText("hetzner").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WORKSPACE_ROOTS_BACK_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.Settings.pattern, nav.currentBackStackEntry?.destination?.route)

        composeRule.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.Hosts.pattern, nav.currentBackStackEntry?.destination?.route)
    }

    /**
     * Issue #2476 — the regression this suite exists to keep fixed:
     * `CrashReportsScreen` was fully ported and unit-tested but had NO entry
     * point, so a crash `CrashReporter` had already written to `filesDir` could
     * only be retrieved with `adb pull`. The assertion is deliberately the
     * user-visible one: from Settings, a tap reaches the browser AND the
     * seeded report is on screen — a route-string check alone would still pass
     * with an empty or crashing screen behind it.
     */
    @Test
    fun `settings crash reports row opens the browser showing a recorded crash`() {
        val summary = seedCrashReport("kaboom-2476")
        val nav = setContent()
        navigateToSettings(nav)

        composeRule.onNodeWithTag(SETTINGS_CRASH_REPORTS_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.CrashReports.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithTag(CRASH_REPORTS_SHARE_ALL_TAG).assertExists()
        composeRule.onNodeWithText("Share all (1)").assertExists()
        assertTrue(
            "the seeded crash ('$summary') is not on the crash-reports screen",
            composeRule.onAllNodesWithText("kaboom-2476", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `back from crash reports returns to settings`() {
        seedCrashReport("kaboom-back")
        val nav = setContent()
        navigateToSettings(nav)
        composeRule.onNodeWithTag(SETTINGS_CRASH_REPORTS_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("‹").performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.Settings.pattern, nav.currentBackStackEntry?.destination?.route)
    }

    /** Writes one report through the production store, the way a real crash would. */
    private fun seedCrashReport(message: String): String {
        val report = CrashReporter.store(ApplicationProvider.getApplicationContext()).save(
            throwable = RuntimeException(message),
            threadName = "main",
            metadata = CrashReportMetadata(
                appVersion = "0.0.0",
                androidRelease = "15",
                sdkInt = 35,
                device = "test",
            ),
        )
        return report.summary
    }

    private fun clearCrashReports() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "crash-reports").deleteRecursively()
    }

    private fun navigateToSettings(nav: NavHostController) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Hosts").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread { nav.navigate(Destination.Settings.route()) }
        composeRule.waitForIdle()
    }

    private fun setContent(): NavHostController {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            AppNavHost(
                navController = controller,
                hostsScreen = { Text("Hosts") },
                connectViewModel = { stack.viewModel },
                treeScreen = { hostId, _, _, _ -> Text("Tree(hostId=$hostId)") },
                settingsScreen = { onBack, onOpenWorkspaceRoots, onOpenCrashReports ->
                    SettingsRoute(
                        onBack = onBack,
                        onOpenWorkspaceRoots = onOpenWorkspaceRoots,
                        onOpenCrashReports = onOpenCrashReports,
                        viewModel = SettingsViewModel(
                            SettingsRepository(ApplicationProvider.getApplicationContext()),
                            stack.db.hostDao(),
                            Dispatchers.Unconfined,
                        ),
                    )
                },
                // Issue #2476: the REAL crash-report browser over the REAL
                // on-device store, so this suite proves a user can reach and
                // read what `CrashReporter` recorded — not merely that a route
                // string resolves to a stand-in. Only the ViewModel is built by
                // hand, for the same Hilt reason as every other screen here.
                crashReportsScreen = { onBack ->
                    CrashReportsScreen(
                        onBack = onBack,
                        viewModel = CrashReportsViewModel(
                            ApplicationProvider.getApplicationContext(),
                        ),
                    )
                },
                workspaceRootsScreen = { _, onBack ->
                    WorkspaceRootsRoute(
                        onBack = onBack,
                        viewModel = WorkspaceRootsViewModel(
                            projectRootDao = stack.db.projectRootDao(),
                            hostDao = stack.db.hostDao(),
                            savedStateHandle = SavedStateHandle(
                                mapOf(Destination.ARG_HOST_ID to hostId),
                            ),
                            dispatcher = Dispatchers.Unconfined,
                        ),
                    )
                },
            )
        }
        composeRule.waitForIdle()
        return controller
    }
}
