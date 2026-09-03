package com.pocketshell.next

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.nav.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real [AppNavHost] composition on the host JVM (Robolectric), the
 * way `:shared:ui-kit` tests its primitives.
 *
 * [com.pocketshell.next.nav.DestinationsTest] pins the route *strings*; this
 * pins that `NavHost` accepts each pattern and that a route built by
 * [Destination] actually resolves to its screen with its arguments intact.
 * Those are different failures: a pattern `NavHost` cannot parse, or an
 * argument that does not survive percent-encoding, is invisible to a
 * string-only test and only shows up as a crash/blank screen on device.
 */
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `start destination renders the host list`() {
        setContentWithNav()

        composeRule.onNodeWithText("Hosts").assertExists()
    }

    @Test
    fun `every destination resolves with its arguments`() {
        val nav = setContentWithNav()

        assertNavigatesTo(nav, Destination.Tree.route(hostId = 7), "Tree(hostId=7)")
        assertNavigatesTo(
            nav,
            Destination.Session.route(hostId = 7, sessionName = "git-pocketshell"),
            "Session(hostId=7, name=git-pocketshell)",
        )
        assertNavigatesTo(
            nav,
            Destination.Files.route(hostId = 7, path = "/home/alexey/notes.md"),
            "Files(hostId=7, path=/home/alexey/notes.md)",
        )
        assertNavigatesTo(nav, Destination.Files.route(hostId = 7), "Files(hostId=7, path=null)")
        assertNavigatesTo(nav, Destination.Settings.route(), "Settings")
        assertNavigatesTo(nav, Destination.Usage.route(), "Usage")
        assertNavigatesTo(nav, Destination.Hosts.route(), "Hosts")
    }

    /**
     * The encoding contract, end to end: a session name with a space and a `:`
     * must arrive at the screen byte-identical. This is the assertion that goes
     * red if `encodeSegment` ever emits form encoding (`+` for a space) again.
     */
    @Test
    fun `session name survives encoding round-trip`() {
        val nav = setContentWithNav()

        assertNavigatesTo(
            nav,
            Destination.Session.route(hostId = 3, sessionName = "my project:review"),
            "Session(hostId=3, name=my project:review)",
        )
    }

    private fun setContentWithNav(): NavHostController {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            AppNavHost(navController = controller)
        }
        composeRule.waitForIdle()
        return controller
    }

    private fun assertNavigatesTo(
        nav: NavHostController,
        route: String,
        expectedLabel: String,
    ) {
        composeRule.runOnUiThread { nav.navigate(route) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(expectedLabel).assertExists("route '$route' did not render '$expectedLabel'")
    }
}
