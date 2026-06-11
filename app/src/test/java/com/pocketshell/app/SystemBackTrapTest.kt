package com.pocketshell.app

import com.pocketshell.app.nav.AppDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #520: the navigator-level [androidx.activity.compose.BackHandler] only
 * intercepts the system/gesture Back button when [shouldTrapSystemBack] is
 * true. This locks in that:
 *
 *  - the root host list keeps the Activity default Back (exit the app), and
 *  - every other destination — crucially the host-detail FolderList and the
 *    plain-SSH Session that the #513 audit found exiting the app — routes Back
 *    through the hand-rolled `back()` stack instead of finishing the activity.
 */
class SystemBackTrapTest {

    private fun folderList() = AppDestination.FolderList(
        hostId = 1L,
        hostName = "dev",
        hostname = "host.example",
        port = 22,
        username = "user",
        keyPath = "/key",
        passphrase = null,
    )

    private fun session() = AppDestination.TmuxSession(
        hostId = 1L,
        hostName = "dev",
        hostname = "host.example",
        port = 22,
        username = "user",
        keyPath = "/key",
        passphrase = null,
        sessionName = "pocketshell",
    )

    @Test
    fun rootHostListIsNotTrapped() {
        assertFalse(
            "Back on the root host list must keep the Activity default (exit app)",
            shouldTrapSystemBack(AppDestination.HostList),
        )
    }

    @Test
    fun hostDetailFolderListIsTrapped() {
        assertTrue(
            "Back on host-detail must route through back() (return to host list)",
            shouldTrapSystemBack(folderList()),
        )
    }

    @Test
    fun terminalSessionIsTrapped() {
        assertTrue(
            "Back on the SSH terminal must route through back() (not exit app)",
            shouldTrapSystemBack(session()),
        )
    }

    @Test
    fun everyNonRootDestinationIsTrapped() {
        val nonRoot = listOf(
            AppDestination.AddHost,
            AppDestination.EditHost(hostId = 1L),
            AppDestination.Scan,
            AppDestination.CrashReports,
            AppDestination.Settings,
            AppDestination.Usage,
            AppDestination.AiCosts,
            AppDestination.PortForwardChooser,
            session(),
            AppDestination.PortForwardPanel(hostId = 1L, keyPath = "/key", passphrase = null),
            AppDestination.WatchedFolders(hostId = 1L, hostName = "dev"),
            folderList(),
        )
        nonRoot.forEach { dest ->
            assertTrue(
                "Non-root destination $dest must trap system Back",
                shouldTrapSystemBack(dest),
            )
        }
    }
}
