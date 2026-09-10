package com.pocketshell.next

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.hosts.HostListRoute
import com.pocketshell.next.hosts.HostListViewModel
import com.pocketshell.next.hosts.noLiveHosts
import com.pocketshell.next.hosts.hostRowTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.usage.usageGlanceCache
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2632: the NAVIGATION half of "resume the session I was last in".
 *
 * `HostWorkspacesResumeTest` proves the ViewModel resumes the right session
 * when it is armed. This proves the arming itself, at the graph edge, because
 * that is where the rule lives and where it can silently invert: arm on
 * opening a HOST (a tap, or the cold-launch resume) and on nothing else. Get
 * that wrong in the permissive direction and every arrival at the workspace
 * list — including the one you reach by leaving a session — throws the user
 * back into the terminal they were trying to leave.
 *
 * The workspace destination is a stand-in that echoes the flag it was handed,
 * because the real screen resolves its ViewModel through `hiltViewModel()`,
 * which a plain Robolectric composition cannot provide. Everything else — the
 * graph, the gate, the host list, the registry — is production code.
 */
@RunWith(AndroidJUnit4::class)
class SessionResumeNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var stack: TestConnectStack
    private val resumeFlags = mutableListOf<Boolean>()
    private val openedSessions = mutableListOf<Triple<Long, String, String?>>()

    @After
    fun tearDown() {
        if (::stack.isInitialized) stack.close()
    }

    @Test
    fun `tapping a host arms the resume for the workspace screen it opens`() {
        stack = TestConnectStack()
        val hostId = stack.seedHost(name = "fixture")
        setContent()

        composeRule.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitText("Workspaces(host=$hostId)")

        assertEquals(listOf(true), resumeFlags)
    }

    @Test
    fun `the cold-launch host resume also arms the session resume`() {
        stack = TestConnectStack()
        val hostId = stack.seedHost(name = "startup-fixture")
        setContent(
            startupHostId = hostId,
            startupHostExists = { stack.db.hostDao().getById(it) != null },
        )

        awaitText("Workspaces(host=$hostId)")

        assertEquals(listOf(true), resumeFlags)
    }

    /**
     * #2635 N3: "Save → Test connection" from the host FORM is the same
     * gesture as a host-row tap — "take me to this machine" — so it arms the
     * resume too.
     *
     * Before this, the two entry points behaved differently for no reason a
     * user could see: tapping a host you already use landed you in your
     * terminal, while saving that same host landed you on the workspace list.
     * Fails on the un-armed form gate (`resumeFlags` reads `[false]`).
     */
    @Test
    fun `connecting from the host form arms the resume the same way a host tap does`() {
        stack = TestConnectStack()
        val hostId = stack.seedHost(name = "form-fixture")
        hostIdUnderTest = hostId
        val controller = setContent()

        composeRule.runOnUiThread {
            controller.navigate(Destination.HostForm.route(hostId))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("form-test-connection").performClick()
        awaitText("Workspaces(host=$hostId)")

        assertEquals(
            "the form's connect gate must arm the resume, like the host row does",
            listOf(true),
            resumeFlags,
        )
    }

    /**
     * A second arrival at the workspace list is NOT a host open — it is the
     * user going back, or a "new session" hop from a session with no workspace
     * path. Re-arming there is the trap this assertion exists to catch.
     */
    @Test
    fun `a later arrival at the same workspace route is not armed`() {
        stack = TestConnectStack()
        val hostId = stack.seedHost(name = "fixture")
        val nav = setContent()

        composeRule.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitText("Workspaces(host=$hostId)")
        assertEquals(listOf(true), resumeFlags)

        composeRule.runOnUiThread { nav.navigate(Destination.Workspaces.route(hostId)) }
        composeRule.waitForIdle()

        assertEquals(2, resumeFlags.size)
        assertTrue(resumeFlags.first())
        assertFalse(resumeFlags.last())
    }

    @Test
    fun `reaching a session route records it as the host's resume target`() {
        stack = TestConnectStack()
        val nav = setContent()

        composeRule.runOnUiThread {
            nav.navigate(Destination.Session.route(7L, "pocketshell:review", "/home/a/git/x"))
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf(Triple(7L, "pocketshell:review", "/home/a/git/x")),
            openedSessions,
        )
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Set by the N3 test so the form seam can hand the gate a real id. */
    private var hostIdUnderTest: Long = 0L

    private fun setContent(
        startupHostId: Long? = null,
        startupHostExists: suspend (Long) -> Boolean = { true },
    ): NavHostController {
        val hostListViewModel =
            HostListViewModel(stack.db.hostDao(), usageGlanceCache(), noLiveHosts(), Dispatchers.Unconfined)
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            AppNavHost(
                navController = controller,
                startupHostId = startupHostId,
                startupHostExists = startupHostExists,
                onSessionOpened = { hostId, name, path ->
                    openedSessions += Triple(hostId, name, path)
                },
                hostsScreen = { actions ->
                    HostListRoute(
                        onOpenHost = actions.onOpenHost,
                        onAddHost = actions.onAddHost,
                        onEditHost = actions.onEditHost,
                        onScanQr = actions.onScanQr,
                        onOpenSettings = actions.onOpenSettings,
                        viewModel = hostListViewModel,
                    )
                },
                connectViewModel = { stack.viewModel },
                workspacesScreen = { hostId, _, _, _, _, _, _, _, launch ->
                    resumeFlags += launch.resumeLastSession
                    Text("Workspaces(host=$hostId)")
                },
                sessionScreen = { hostId, name, _, _ ->
                    Text("Session($hostId/$name)")
                },
                // #2635 N3: the form's "Test connection" edge, so the test can
                // drive the same gate the Save button does.
                hostFormScreen = { _, _, _, onTestConnection ->
                    androidx.compose.material3.Button(
                        onClick = { onTestConnection(hostIdUnderTest) },
                        modifier = Modifier.testTag("form-test-connection"),
                    ) { Text("Test connection") }
                },
            )
        }
        composeRule.waitForIdle()
        return controller
    }
}
