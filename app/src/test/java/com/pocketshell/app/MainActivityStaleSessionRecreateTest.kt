package com.pocketshell.app

import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.projects.SessionCreateOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStaleSessionRecreateTest {
    @Test
    fun failedStaleRecreateReportsReasonAndDoesNotNavigate() = runTest {
        var navigatedTo: String? = null
        var visibleFailure: String? = null

        recreateStaleSession(
            create = { Result.failure(IllegalStateException("SSH create refused")) },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertNull("failed create must not navigate as if the stale session exists", navigatedTo)
        assertEquals("SSH create refused", visibleFailure)
    }

    @Test
    fun thrownStaleRecreateFailureIsAlsoReported() = runTest {
        var navigatedTo: String? = null
        var visibleFailure: String? = null

        recreateStaleSession(
            create = { throw IllegalStateException("host lookup crashed") },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertNull("thrown create failure must not navigate", navigatedTo)
        assertEquals("host lookup crashed", visibleFailure)
    }

    @Test
    fun successfulStaleRecreateNavigatesWithoutFailure() = runTest {
        var navigatedTo: String? = null
        var visibleFailure: String? = null

        recreateStaleSession(
            create = { Result.success(SessionCreateOutcome.Created("work-2")) },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertEquals("work-2", navigatedTo)
        assertNull(visibleFailure)
    }

    /**
     * Issue #1928 — the stale-session-recovery half of the caller sweep.
     *
     * Recovery must not treat a PARTIAL success as a full one. If it navigated
     * on [SessionCreateOutcome.LaunchFailed] the user would be attached to a
     * session that is not the agent they were recovering, with nothing on screen
     * saying so — the same lie the folder tree and the in-session sheet used to
     * tell. The visible reason must name the session AND the host's cause.
     */
    @Test
    fun launchFailedStaleRecreateReportsInsteadOfNavigating() = runTest {
        var navigatedTo: String? = null
        var visibleFailure: String? = null

        recreateStaleSession(
            create = {
                Result.success(
                    SessionCreateOutcome.LaunchFailed("work-2", "can't find pane: =work-2:"),
                )
            },
            onSuccess = { navigatedTo = it },
            onFailure = { visibleFailure = it },
        )

        assertNull("a launch failure must not navigate as if the agent started", navigatedTo)
        val message = visibleFailure.orEmpty()
        assertTrue("must name the created session: $message", message.contains("work-2"))
        assertTrue(
            "must carry the host's reason: $message",
            message.contains("can't find pane: =work-2:"),
        )
    }

    // ------------------------------------------------------------- Issue #2237
    //
    // The dialog's OTHER action: leaving without creating. The connected journey
    // `ColdRestoreGoneSessionNoResurrectE2eTest` proves the two real paths
    // end-to-end (in-tree tap and cold restore); these pin the resolution itself,
    // including the missing-connection-details case that no real journey can enter
    // (every path that raises this dialog has just attached to the host, so `base`
    // is always present there) and the copy that names the landing.

    private fun session(
        hostId: Long = 42L,
        hostName: String = "hetzner",
        sessionName: String = "claude-main",
    ) = AppDestination.TmuxSession(
        hostId = hostId,
        hostName = hostName,
        hostname = "10.0.2.2",
        port = 2222,
        username = "testuser",
        keyPath = "/data/keys/id_ed25519",
        passphrase = charArrayOf('p', 'w'),
        sessionName = sessionName,
    )

    @Test
    fun staleDialogUsesColdRestoreRouteWhenRememberedDestinationIsMissing() {
        val requested = session()
        val base = staleSessionBase(
            lastTmuxDestination = null,
            requestedDestination = requested,
            staleHostId = requested.hostId,
        )

        val action = staleSessionDismissAction(base)
        assertTrue(
            "cold restore already carries this host's SSH tuple, so dismiss must " +
                "build its session tree even before the navigation observer runs",
            action.destination is AppDestination.FolderList &&
                action.label == "Back to sessions",
        )
    }

    @Test
    fun staleDialogSelectsTheStaleHostWhenRememberedDestinationIsAnotherHost() {
        val staleHost = session(hostId = 42L, hostName = "stale-host")
        val rememberedOtherHost = session(
            hostId = 99L,
            hostName = "remembered-other-host",
            sessionName = "other-session",
        )

        val base = staleSessionBase(
            lastTmuxDestination = rememberedOtherHost,
            requestedDestination = staleHost,
            staleHostId = staleHost.hostId,
        )

        val tree = staleSessionDismissAction(base).destination as? AppDestination.FolderList
            ?: throw AssertionError("a stale host with connection details must have a tree")
        assertEquals("the stale prompt host must win over another remembered host", staleHost.hostId, tree.hostId)
        assertEquals("the stale prompt host name must be retained", staleHost.hostName, tree.hostName)
        assertEquals("the stale prompt SSH host must be retained", staleHost.hostname, tree.hostname)
    }

    @Test
    fun staleDialogDismissCallbackRoutesToHostsOwnSessionTree() {
        val base = session()

        var promptVisible = true
        val backStack = mutableListOf<AppDestination>(AppDestination.Settings)
        var destination: AppDestination? = AppDestination.HostList
        val onDismiss = staleSessionDismissCallback(
            action = staleSessionDismissAction(base),
            clearPrompt = { promptVisible = false },
            clearBackStack = backStack::clear,
            setCurrentDestination = { destination = it },
        )
        onDismiss()

        assertFalse("the stale prompt must be cleared", promptVisible)
        assertTrue("recovery history must be cleared", backStack.isEmpty())
        val tree = destination as? AppDestination.FolderList
            ?: throw AssertionError(
                "REGRESSION (#2237): dismissing must land on THIS host's session tree; " +
                    "got $destination",
            )
        // The whole SSH tuple must survive, or the tree lands somewhere it cannot
        // connect (or on a different host).
        assertEquals("hostId", base.hostId, tree.hostId)
        assertEquals("hostName", base.hostName, tree.hostName)
        assertEquals("hostname", base.hostname, tree.hostname)
        assertEquals("port", base.port, tree.port)
        assertEquals("username", base.username, tree.username)
        assertEquals("keyPath", base.keyPath, tree.keyPath)
        assertEquals("passphrase", base.passphrase, tree.passphrase)
    }

    /**
     * The accepted edge case: with no connection details for the stale session's
     * host there is nothing to build a folder list from, so the dismiss still lands
     * on the host list — exactly as the `onConfirm`/create branch's own null
     * fallback does. This is deliberate, not an oversight, so it is pinned.
     */
    @Test
    fun staleDialogDismissCallbackFallsBackToHostListWhenTreeIsUnavailable() {
        var promptVisible = true
        val backStack = mutableListOf<AppDestination>(AppDestination.Settings)
        var destination: AppDestination? = null
        val onDismiss = staleSessionDismissCallback(
            action = staleSessionDismissAction(base = null),
            clearPrompt = { promptVisible = false },
            clearBackStack = backStack::clear,
            setCurrentDestination = { destination = it },
        )
        onDismiss()

        assertEquals(
            "with no connection details there is no folder list to build",
            AppDestination.HostList,
            destination,
        )
        assertFalse("the stale prompt must still be cleared", promptVisible)
        assertTrue("recovery history must still be cleared", backStack.isEmpty())
    }

    /**
     * The label and message clause travel WITH the destination, so the dialog can
     * never offer "Back to sessions" while landing on the host list (or the
     * reverse). A
     * mutation that swaps either branch's destination without its copy reddens here.
     */
    @Test
    fun dismissCopyDescribesTheScreenTheDismissActuallyOpens() {
        val toTree = staleSessionDismissAction(session())
        assertTrue(
            "a dismiss that opens the session tree must not be labelled as going home; " +
                "label=${toTree.label} clause=${toTree.messageClause}",
            toTree.destination is AppDestination.FolderList &&
                toTree.label == "Back to sessions" &&
                toTree.messageClause == "go back to this host's sessions",
        )

        val toHostList = staleSessionDismissAction(base = null)
        assertTrue(
            "a dismiss that opens the host list must say so; " +
                "label=${toHostList.label} clause=${toHostList.messageClause}",
            toHostList.destination == AppDestination.HostList &&
                toHostList.label == "Go to hosts" &&
                toHostList.messageClause == "go to the host list",
        )
    }
}
