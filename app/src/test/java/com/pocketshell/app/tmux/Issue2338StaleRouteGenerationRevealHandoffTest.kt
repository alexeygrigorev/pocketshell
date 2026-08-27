package com.pocketshell.app.tmux

import com.pocketshell.core.connection.ConnectionPhase
import com.pocketshell.core.connection.RevealIdentityAdoption
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.Seed
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.connection.targetIdOrNull
import com.pocketshell.core.connection.terminalHeld
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #2338 — "terminal never attaches on the 2nd+ MainActivity launch in a process".
 *
 * ## The class of bug
 *
 * A tmux session NAME is unique per server, so a route's `(tmuxSessionId,
 * sessionCreated)` pair is only ever as fresh as whatever produced it: the
 * persisted last-session record, the cached session tree the picker renders, or
 * a deep link. Whenever any of those was written for an EARLIER generation of a
 * same-named session — which is exactly what "the 2nd+ launch in one process"
 * means, because the FIRST launch is what wrote them — the cold attach reaches
 * [TmuxSessionViewModel.reconcilePanes] with a STALE exact generation.
 *
 * Since #2294 the first authoritative `list-panes` adopts the LIVE generation
 * into `activeTarget` / `connectingTarget` / `latestConnectIntent` and re-keys
 * the reveal reducer + connection controller onto it. The screen, however, is
 * still composed with the route id. Before this fix the render selector treated
 * "the route carries a generation" as authoritative and refused to follow the
 * adoption, so [sessionSurfaceState] saw `reveal.targetId != screen targetId`
 * and HELD the terminal forever: SSH up, tmux attached, `paneCount=1`, and no
 * `TerminalView` ever built (no `tmux-client-size-known`).
 *
 * The assertions below are on the fused surface state — the thing that decides
 * whether the terminal is exposed — not on the metadata promotion, so a green
 * here means the user-visible symptom is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2338StaleRouteGenerationRevealHandoffTest : TmuxSessionViewModelTestBase() {

    /**
     * The reported defect, on the production VM path: a route carrying the
     * PREVIOUS generation of a same-named session must still reveal its
     * terminal once list-panes supplies the live generation.
     */
    @Test
    fun staleRouteGenerationStillRevealsTerminalAfterExactAdoption() = runTest(scheduler) {
        val vm = newConnectedVm(
            routeTmuxSessionId = PREVIOUS_SESSION_ID,
            routeSessionCreated = PREVIOUS_CREATED,
            livePaneSessionId = LIVE_SESSION_ID,
            livePaneCreated = LIVE_CREATED,
        )
        advanceUntilIdle()

        val routeTargetId = tmuxTargetSessionId(
            hostId = HOST_ID,
            sessionName = SESSION_NAME,
            tmuxSessionId = PREVIOUS_SESSION_ID,
            sessionCreated = PREVIOUS_CREATED,
        )
        val liveTargetId = tmuxTargetSessionId(
            hostId = HOST_ID,
            sessionName = SESSION_NAME,
            tmuxSessionId = LIVE_SESSION_ID,
            sessionCreated = LIVE_CREATED,
        )

        // Non-vacuity: the fixture MUST have entered the failing state — the
        // reducer re-keyed away from the route id the screen still holds. If the
        // route and the live generation ever agreed, this test would be proving
        // nothing, so hard-fail instead of passing quietly.
        assertNotEquals(
            "the route generation must differ from the live one for this to be the #2338 state",
            routeTargetId,
            liveTargetId,
        )
        val adoption = vm.revealIdentityAdoption.value
        assertEquals(
            "the production reconcile must publish the identity handoff it performed",
            RevealIdentityAdoption(from = routeTargetId, to = liveTargetId),
            adoption,
        )
        val reveal = vm.revealState.value
        assertEquals(
            "the reveal reducer moved onto the live generation",
            liveTargetId,
            reveal.targetIdOrNull(),
        )

        // The load-bearing assertion: the rendered surface must expose the
        // terminal. On base the selector returns the stale route id and the
        // fusion holds it forever.
        val renderedTargetId = tmuxSessionSurfaceTargetId(
            routeTargetId = routeTargetId,
            routeHostId = HOST_ID,
            routeSessionName = SESSION_NAME,
            routeTmuxSessionId = PREVIOUS_SESSION_ID,
            routeSessionCreated = PREVIOUS_CREATED,
            reveal = reveal,
            adoption = adoption,
        )
        val surface = sessionSurfaceState(
            reveal = reveal,
            phase = ConnectionPhase.Live(HOST, PORT, USER),
            targetId = renderedTargetId,
        )
        assertFalse(
            "REGRESSION (#2338): a stale route generation must not hold the terminal forever " +
                "(SSH up, tmux attached, panes listed — and no TerminalView ever built); " +
                "rendered=$renderedTargetId reveal=${reveal.targetIdOrNull()} surface=$surface",
            surface.terminalHeld,
        )
        assertTrue("the surface must be Live", surface is SessionSurfaceState.Live)
        assertEquals(
            "the screen must follow the adoption of its OWN route id",
            liveTargetId,
            renderedTargetId,
        )
    }

    /**
     * G2 class coverage, other half: the #686 stale-id fence must survive. A
     * reveal that merely HOLDS another generation — with no adoption of this
     * route's id — must never retarget this screen.
     */
    @Test
    fun heldForeignGenerationWithoutAdoptionStaysFenced() {
        val routeTargetId = SessionId("tmux:42:\$9:1700000005")
        val foreignTargetId = SessionId("tmux:42:\$8:1700000004")
        val foreignReveal = RevealState.Live(
            targetId = foreignTargetId,
            targetName = SESSION_NAME,
            panes = listOf(Seed(foreignTargetId, "%0", "stale")),
        )

        val renderedTargetId = tmuxSessionSurfaceTargetId(
            routeTargetId = routeTargetId,
            routeHostId = 42L,
            routeSessionName = SESSION_NAME,
            routeTmuxSessionId = "\$9",
            routeSessionCreated = 1_700_000_005L,
            reveal = foreignReveal,
            adoption = null,
        )
        val surface = sessionSurfaceState(
            reveal = foreignReveal,
            phase = ConnectionPhase.Connecting(HOST, PORT, USER),
            targetId = renderedTargetId,
        )

        assertEquals(
            "an un-adopted foreign generation must not retarget this route",
            routeTargetId,
            renderedTargetId,
        )
        assertTrue("the foreign frame must stay held", surface.terminalHeld)
    }

    /**
     * G2 class coverage: an adoption recorded for a DIFFERENT route must not
     * leak onto this screen either — the handoff is keyed to `from`, not merely
     * to "an adoption happened".
     */
    @Test
    fun adoptionFromAnotherRouteDoesNotRetargetThisScreen() {
        val routeTargetId = SessionId("tmux:42:\$9:1700000005")
        val otherRouteId = SessionId("tmux:42:\$1:1700000001")
        val adoptedTargetId = SessionId("tmux:42:\$8:1700000004")
        val reveal = RevealState.Live(
            targetId = adoptedTargetId,
            targetName = SESSION_NAME,
            panes = listOf(Seed(adoptedTargetId, "%0", "other")),
        )

        val renderedTargetId = tmuxSessionSurfaceTargetId(
            routeTargetId = routeTargetId,
            routeHostId = 42L,
            routeSessionName = SESSION_NAME,
            routeTmuxSessionId = "\$9",
            routeSessionCreated = 1_700_000_005L,
            reveal = reveal,
            adoption = RevealIdentityAdoption(from = otherRouteId, to = adoptedTargetId),
        )

        assertEquals(
            "only the screen whose route id WAS adopted may follow the handoff",
            routeTargetId,
            renderedTargetId,
        )
    }

    /**
     * G2 class coverage: a name-only route keeps the #2294 behaviour — it
     * follows a matching exact reveal even before any adoption record exists.
     */
    @Test
    fun nameOnlyRouteStillFollowsExactRevealWithoutAdoptionRecord() {
        val routeTargetId = tmuxTargetSessionId(
            hostId = 42L,
            sessionName = SESSION_NAME,
            tmuxSessionId = null,
            sessionCreated = null,
        )
        val exactTargetId = SessionId("tmux:42:\$7:1700000003")
        val reveal = RevealState.Live(
            targetId = exactTargetId,
            targetName = SESSION_NAME,
            panes = listOf(Seed(exactTargetId, "%0", "prompt")),
        )

        val renderedTargetId = tmuxSessionSurfaceTargetId(
            routeTargetId = routeTargetId,
            routeHostId = 42L,
            routeSessionName = SESSION_NAME,
            routeTmuxSessionId = null,
            routeSessionCreated = null,
            reveal = reveal,
            adoption = null,
        )

        assertEquals("the name-only cold route still adopts the exact reveal", exactTargetId, renderedTargetId)
    }

    /**
     * The ORDERING dependency itself: a fresh navigation ends the previous
     * handoff, so the next screen's route can never be retargeted by the
     * previous attach's adoption.
     */
    @Test
    fun renavigationClearsThePreviousIdentityHandoff() = runTest(scheduler) {
        val vm = newConnectedVm(
            routeTmuxSessionId = PREVIOUS_SESSION_ID,
            routeSessionCreated = PREVIOUS_CREATED,
            livePaneSessionId = LIVE_SESSION_ID,
            livePaneCreated = LIVE_CREATED,
        )
        advanceUntilIdle()
        assertNotNullAdoption(vm)

        vm.connect(
            hostId = HOST_ID,
            hostName = HOST_NAME,
            host = HOST,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = "another-session",
        )

        assertNull(
            "a genuine renavigation must end the previous identity handoff",
            vm.revealIdentityAdoption.value,
        )
    }

    private fun assertNotNullAdoption(vm: TmuxSessionViewModel) {
        assertTrue(
            "precondition: the stale-route attach must have adopted the live generation",
            vm.revealIdentityAdoption.value != null,
        )
    }

    /**
     * Drive the real production path: `connect()` navigates the reveal reducer
     * with the ROUTE identity, then the attach runs list-panes ->
     * applyParsedPanes -> exact-generation adoption -> capture seed. Nothing
     * here calls the reveal/controller adoption directly.
     */
    private fun TestScope.newConnectedVm(
        routeTmuxSessionId: String,
        routeSessionCreated: Long,
        livePaneSessionId: String,
        livePaneCreated: Long,
    ): TmuxSessionViewModel {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            sshLeaseManager = testLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 0L,
            ),
        )
        val newClient = FakeTmuxClient()
        newClient.responses += CommandResponse(
            number = 0L,
            output = listOf(
                exactGenerationPaneRow(
                    paneId = "%0",
                    sessionId = livePaneSessionId,
                    sessionName = SESSION_NAME,
                    sessionCreated = livePaneCreated,
                ),
            ),
            isError = false,
        )
        newClient.capturePaneResponses += CommandResponse(
            number = 1L,
            output = listOf("shell prompt"),
            isError = false,
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals(SESSION_NAME, sessionName)
            newClient
        }
        vm.replaceClientForTest(
            hostId = HOST_ID,
            hostName = HOST_NAME,
            host = HOST,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            sessionName = "some-other-warm-session",
            client = FakeTmuxClient(),
            session = session,
        )
        vm.connect(
            hostId = HOST_ID,
            hostName = HOST_NAME,
            host = HOST,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = SESSION_NAME,
            tmuxSessionId = routeTmuxSessionId,
            sessionCreated = routeSessionCreated,
        )
        return vm
    }

    private fun exactGenerationPaneRow(
        paneId: String,
        sessionId: String,
        sessionName: String,
        sessionCreated: Long?,
    ): String = listOf(
        paneId,
        "@0",
        "0",
        sessionId,
        sessionName,
        "shell",
        "0",
        "/tmp",
        "bash",
        "/dev/pts/1",
        "0",
        "123",
        "0",
        sessionCreated?.toString().orEmpty(),
        "",
    ).joinToString(LIST_PANES_FIELD_SEPARATOR)

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        private var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession : SshSession {
        @Volatile
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val HOST_ID = 42L
        const val HOST_NAME = "docker"
        const val HOST = "10.0.2.2"
        const val PORT = 2222
        const val USER = "alex"
        const val KEY_PATH = "/keys/a"
        const val SESSION_NAME = "cold"

        // The generation the FIRST launch in this process recorded.
        const val PREVIOUS_SESSION_ID = "\$0"
        const val PREVIOUS_CREATED = 1_787_759_746L

        // The generation tmux actually has now (the session was recreated).
        const val LIVE_SESSION_ID = "\$0"
        const val LIVE_CREATED = 1_787_759_789L
    }
}
