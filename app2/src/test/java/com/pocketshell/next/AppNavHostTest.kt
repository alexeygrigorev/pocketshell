package com.pocketshell.next

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.nav.Destination
import org.junit.After
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

    /**
     * The graph now composes the U-2 connect gate on its start destination, so
     * it needs a [com.pocketshell.next.connect.ConnectViewModel]. This suite is
     * still about route patterns and argument encoding — the gate's own
     * behaviour is covered by
     * `com.pocketshell.next.connect.ConnectGateNavigationTest` — so it gets the
     * shared test stack and never taps a host.
     */
    private val stack = TestConnectStack()

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `start destination renders the hosts route`() {
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
        assertNavigatesTo(
            nav,
            Destination.FileViewer.route(hostId = 7, path = "/home/alexey/notes.md"),
            "Viewer(hostId=7, path=/home/alexey/notes.md)",
        )
        assertNavigatesTo(nav, Destination.Ports.route(hostId = 7), "Ports")
        assertNavigatesTo(nav, Destination.Settings.route(), "Settings")
        assertNavigatesTo(nav, Destination.Usage.route(), "Usage")
        assertNavigatesTo(nav, Destination.SshKeys.route(), "SshKeys")
        assertNavigatesTo(nav, Destination.HostQr.route(hostId = 7), "HostQr")
        assertNavigatesTo(nav, Destination.QrScan.route(), "QrScan")
        assertNavigatesTo(nav, Destination.Hosts.route(), "Hosts")
    }

    /**
     * The add/edit form's argument is the whole point of the route: Add carries
     * the `-1` sentinel and must arrive as `null`, Edit carries a real id and
     * must arrive intact. A route that silently delivered the sentinel to the
     * form would make every Add look like an edit of host -1; a route that
     * dropped the id would make every Edit an Add. Both are the audit-F1 bug
     * class, one navigation layer below the ViewModel.
     */
    @Test
    fun `host form route distinguishes add from edit by its argument`() {
        val nav = setContentWithNav()

        assertNavigatesTo(nav, Destination.HostForm.route(), "HostForm(hostId=null)")
        assertNavigatesTo(nav, Destination.HostForm.route(hostId = 42), "HostForm(hostId=42)")
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
            AppNavHost(
                navController = controller,
                // The real host list resolves its ViewModel through
                // `hiltViewModel()`, which needs a Hilt-managed Activity this
                // plain compose rule does not provide. This suite is about the
                // graph — route patterns and argument encoding — so the hosts
                // route gets a stand-in label; the real screen inside the real
                // graph is covered by
                // `com.pocketshell.next.hosts.HostListNavigationTest`.
                hostsScreen = { Text("Hosts") },
                // Task P-6 added four host-management routes. Their screens
                // resolve ViewModels through `hiltViewModel()` for the same
                // reason as the ones above, so they get stand-ins that echo the
                // argument the route delivered — this suite pins the patterns
                // and their argument decoding, not the screens.
                hostFormScreen = { hostId, _, _ -> Text("HostForm(hostId=$hostId)") },
                sshKeysScreen = { Text("SshKeys") },
                hostQrScreen = { Text("HostQr") },
                qrScanScreen = { _, _ -> Text("QrScan") },
                connectViewModel = { stack.viewModel },
                // Same rationale as `hostsScreen`: the real session tree
                // resolves its ViewModel through `hiltViewModel()`. The
                // stand-in echoes the argument the route actually delivered, so
                // this suite still pins the Tree pattern's Long argument.
                treeScreen = { hostId, _, _ -> Text("Tree(hostId=$hostId)") },
                // Same rationale again for U-4's terminal: the real screen
                // resolves `SessionViewModel` through `hiltViewModel()` AND
                // dials a host. The stand-in echoes both route arguments, which
                // is what this suite is pinning — that a session name with a
                // space and a `:` survives the encode/decode round trip.
                sessionScreen = { hostId, sessionName, _, _ ->
                    Text("Session(hostId=$hostId, name=$sessionName)")
                },
                // Same rationale again: the P-4 port-forward route resolves its
                // ViewModel through `hiltViewModel()`. Its own behaviour is
                // covered by `com.pocketshell.next.ports.PortForwardScreenTest`
                // and `PortForwardViewModelTest`; what this suite pins is that
                // `NavHost` accepts the pattern and its Long argument.
                portsScreen = { Text("Ports") },
                // Task P-3: the file explorer and viewer resolve their
                // ViewModels through `hiltViewModel()` too. Their behaviour is
                // covered by `com.pocketshell.next.files.*`; the stand-ins here
                // echo the arguments their routes delivered, which is what this
                // suite pins.
                filesScreen = { hostId, path, _, _ -> Text("Files(hostId=$hostId, path=$path)") },
                viewerScreen = { hostId, path, _ -> Text("Viewer(hostId=$hostId, path=$path)") },
                // Task P-5: the real usage panel resolves `UsageViewModel`
                // through `hiltViewModel()`, same rationale as the others.
                usageScreen = { Text("Usage") },
            )
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
