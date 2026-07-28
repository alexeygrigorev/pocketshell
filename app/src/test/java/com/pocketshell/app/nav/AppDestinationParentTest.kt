package com.pocketshell.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1831 — the empty-back-stack fallback used by
 * `MainActivity.AppNavigator.back()`.
 *
 * The reported symptom (Back from an agent session landing on the host list
 * instead of the sessions screen) came from `back()` falling back to
 * [AppDestination.HostList] whenever the hand-rolled back stack was empty. This
 * pins the replacement rule, for EVERY destination rather than only the
 * reported `TmuxSession` instance (G2):
 *
 *  - the root host list has NO parent, so Back there keeps the Activity default
 *    and exits the app (`shouldTrapSystemBack`, #520);
 *  - every host-scoped screen that carries the full SSH tuple resolves to that
 *    host's session/folder list — the "sessions screen" the maintainer expects;
 *  - a `WatchedFolders` opened from the Settings host picker (no credentials)
 *    cannot rebuild a folder list, so it falls back to the host list;
 *  - everything reached from the host list resolves back to the host list.
 *
 * The end-to-end Back chain from a session is asserted too: session ->
 * sessions list -> host list -> exit.
 */
class AppDestinationParentTest {

    private val passphrase = charArrayOf('s', 'e', 'c')

    private fun session(
        sessionName: String = "claude-main",
    ) = AppDestination.TmuxSession(
        hostId = 7L,
        hostName = "dev box",
        hostname = "host.example",
        port = 2222,
        username = "agent",
        keyPath = "/data/key",
        passphrase = passphrase,
        sessionName = sessionName,
    )

    private fun folderList() = AppDestination.FolderList(
        hostId = 7L,
        hostName = "dev box",
        hostname = "host.example",
        port = 2222,
        username = "agent",
        keyPath = "/data/key",
        passphrase = passphrase,
    )

    private fun assertIsHostFolderList(actual: AppDestination?) {
        val expected = folderList()
        val resolved = actual as? AppDestination.FolderList
            ?: throw AssertionError("expected the host's FolderList, got $actual")
        assertEquals("hostId", expected.hostId, resolved.hostId)
        assertEquals("hostName", expected.hostName, resolved.hostName)
        assertEquals("hostname", expected.hostname, resolved.hostname)
        assertEquals("port", expected.port, resolved.port)
        assertEquals("username", expected.username, resolved.username)
        assertEquals("keyPath", expected.keyPath, resolved.keyPath)
        assertEquals("passphrase", expected.passphrase, resolved.passphrase)
    }

    @Test
    fun rootHostListHasNoParentSoBackStillExitsTheApp() {
        assertNull(
            "Back on the ROOT host list must keep the Activity default (exit the app) — " +
                "ColdInstall / EmulatorWorkflow assert this",
            AppDestination.HostList.parentDestination(),
        )
    }

    @Test
    fun tmuxSessionFallsBackToItsHostsSessionList() {
        // The #1831 report: "I want to go back to the sessions screen, not to
        // the Host screen."
        assertIsHostFolderList(session().parentDestination())
    }

    @Test
    fun backChainFromASessionIsSessionListThenHostListThenExit() {
        val sessionList = session().parentDestination()
        assertIsHostFolderList(sessionList)
        assertEquals(
            "the sessions list must fall back to the host list",
            AppDestination.HostList,
            sessionList!!.parentDestination(),
        )
        assertNull(
            "and the host list ends the chain (Back exits the app)",
            AppDestination.HostList.parentDestination(),
        )
    }

    @Test
    fun everyHostScopedScreenCarryingCredentialsFallsBackToItsHostsSessionList() {
        val hostScoped: List<AppDestination> = listOf(
            session(),
            AppDestination.RepoBrowser(
                hostId = 7L,
                hostName = "dev box",
                hostname = "host.example",
                port = 2222,
                username = "agent",
                keyPath = "/data/key",
                passphrase = passphrase,
            ),
            AppDestination.EnvFiles(
                hostId = 7L,
                hostName = "dev box",
                hostname = "host.example",
                port = 2222,
                username = "agent",
                keyPath = "/data/key",
                passphrase = passphrase,
                directory = "/home/agent/git/app",
                folderLabel = "app",
                copySources = emptyList(),
            ),
            AppDestination.FileViewer(
                hostId = 7L,
                hostName = "dev box",
                hostname = "host.example",
                port = 2222,
                username = "agent",
                keyPath = "/data/key",
                passphrase = passphrase,
                remotePath = "/home/agent/notes.md",
                cwd = "/home/agent",
            ),
            AppDestination.FileExplorer(
                hostId = 7L,
                hostName = "dev box",
                hostname = "host.example",
                port = 2222,
                username = "agent",
                keyPath = "/data/key",
                passphrase = passphrase,
                startDir = "/home/agent",
            ),
            AppDestination.GitHistory(
                hostId = 7L,
                hostName = "dev box",
                hostname = "host.example",
                port = 2222,
                username = "agent",
                keyPath = "/data/key",
                passphrase = passphrase,
                dir = "/home/agent/git/app",
            ),
            AppDestination.RecurringJobs(
                hostId = 7L,
                hostName = "dev box",
                hostname = "host.example",
                port = 2222,
                username = "agent",
                keyPath = "/data/key",
                passphrase = passphrase,
                sessionName = "claude-main",
            ),
            AppDestination.WatchedFolders(
                hostId = 7L,
                hostName = "dev box",
                hostname = "host.example",
                port = 2222,
                username = "agent",
                keyPath = "/data/key",
                passphrase = passphrase,
            ),
        )
        hostScoped.forEach { destination ->
            val parent = destination.parentDestination()
            try {
                assertIsHostFolderList(parent)
            } catch (error: AssertionError) {
                throw AssertionError("$destination must fall back to its host's session list", error)
            }
        }
    }

    @Test
    fun watchedFoldersWithoutSshCredentialsFallsBackToTheHostList() {
        // The Settings host-picker route arrives without a decrypted key, so a
        // folder list could not connect — the host list is the honest parent.
        val fromSettings = AppDestination.WatchedFolders(hostId = 7L, hostName = "dev box")
        assertEquals(
            "a credential-less WatchedFolders must fall back to the host list",
            AppDestination.HostList,
            fromSettings.parentDestination(),
        )
    }

    @Test
    fun everyHostListChildFallsBackToTheHostList() {
        val children: List<AppDestination> = listOf(
            folderList(),
            AppDestination.AddHost,
            AppDestination.AddFirstHost,
            AppDestination.FirstHostTestConnect(hostId = 7L),
            AppDestination.EditFirstHost(hostId = 7L),
            AppDestination.EditHost(hostId = 7L),
            AppDestination.Scan,
            AppDestination.CrashReports,
            AppDestination.Settings,
            AppDestination.Usage,
            AppDestination.AiCosts,
            AppDestination.PortForwardChooser,
            AppDestination.PortForwardPanel(
                hostId = 7L,
                keyPath = "/data/key",
                passphrase = passphrase,
            ),
        )
        children.forEach { destination ->
            assertEquals(
                "$destination must fall back to the host list",
                AppDestination.HostList,
                destination.parentDestination(),
            )
        }
    }

    @Test
    fun noNonRootDestinationResolvesToItself() {
        // A self-parent would make Back a no-op / infinite loop on that screen.
        val everyKind: List<AppDestination> = listOf(
            session(),
            folderList(),
            AppDestination.AddHost,
            AppDestination.AddFirstHost,
            AppDestination.FirstHostTestConnect(hostId = 7L),
            AppDestination.EditFirstHost(hostId = 7L),
            AppDestination.EditHost(hostId = 7L),
            AppDestination.Scan,
            AppDestination.CrashReports,
            AppDestination.Settings,
            AppDestination.Usage,
            AppDestination.AiCosts,
            AppDestination.PortForwardChooser,
            AppDestination.PortForwardPanel(hostId = 7L, keyPath = "/k", passphrase = null),
            AppDestination.WatchedFolders(hostId = 7L, hostName = "dev box"),
            AppDestination.RepoBrowser(
                hostId = 7L,
                hostName = "dev box",
                hostname = "host.example",
                port = 2222,
                username = "agent",
                keyPath = "/data/key",
                passphrase = null,
            ),
        )
        everyKind.forEach { destination ->
            val parent = destination.parentDestination()
            if (parent != null && parent::class == destination::class && parent == destination) {
                throw AssertionError("$destination must not be its own Back target")
            }
        }
    }
}
