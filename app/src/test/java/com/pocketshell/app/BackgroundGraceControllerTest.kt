package com.pocketshell.app

import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #450: the bounded background grace-window state machine.
 *
 * Verifies the three branches the maintainer's ask depends on:
 *  - background starts a single-shot grace timer (no immediate teardown),
 *  - foreground WITHIN the window cancels the timer and never tears down
 *    (so the user resumes instantly, no reconnect),
 *  - the window elapsing while still backgrounded runs the existing
 *    teardown exactly once, and the subsequent foreground signals a
 *    normal post-grace reattach.
 *
 * `runTest` virtual time drives [kotlinx.coroutines.delay] deterministically.
 * Tests inject a short duration so coverage does not depend on real minute
 * waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundGraceControllerTest {

    private val graceMillis = 1_000L

    @Test
    fun `background does not tear down immediately - it starts the grace timer`() = runTest {
        val events = mutableListOf<String>()
        val controller = controller(events)

        controller.onBackground()
        runCurrent()

        // No teardown yet; the timer is counting down.
        assertEquals(emptyList<String>(), events)
        assertTrue("grace timer must be pending after background", controller.isGracePendingForTest())
    }

    @Test
    fun `foreground within grace cancels teardown - no reconnect`() = runTest {
        val events = mutableListOf<String>()
        val controller = controller(events)

        controller.onBackground()
        runCurrent()
        // Return well within the window, like a quick app-switch to copy text.
        advanceTimeBy(graceMillis / 2)
        runCurrent()
        controller.onForeground()
        runCurrent()

        // Teardown never ran; foreground signalled an intact resume.
        assertEquals(listOf("foreground:resumedWithinGrace=true"), events)
        assertFalse("grace timer must be cancelled on within-grace resume", controller.isGracePendingForTest())

        // Advancing past the original deadline must NOT fire a late teardown.
        advanceTimeBy(graceMillis)
        runCurrent()
        assertEquals(listOf("foreground:resumedWithinGrace=true"), events)
    }

    @Test
    fun `foreground after deadline runs elapsed action before foreground when timer has not resumed`() = runTest {
        val events = mutableListOf<String>()
        val controller = controller(events)

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis)

        assertFalse(
            "deadline has passed even though the timer continuation has not resumed",
            controller.isGracePendingForTest(),
        )

        controller.onForeground()
        runCurrent()

        assertEquals(
            listOf("teardown", "foreground:resumedWithinGrace=false"),
            events,
        )

        runCurrent()
        assertEquals(
            "the cancelled timer must not run the elapsed action a second time",
            listOf("teardown", "foreground:resumedWithinGrace=false"),
            events,
        )
    }

    @Test
    fun `diagnostics distinguish within-grace foreground from elapsed deadline`() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val controller = BackgroundGraceController(
                scope = backgroundScope,
                graceMillis = graceMillis,
                onGraceElapsed = {},
                onForeground = {},
            )

            controller.onBackground()
            runCurrent()
            advanceTimeBy(graceMillis / 2)
            runCurrent()
            controller.onForeground()
            runCurrent()

            val withinGraceForeground = diagnostics.eventsNamed("background_grace_foreground").single()
            assertEquals(true, withinGraceForeground.fields["resumedWithinGrace"])
            assertEquals(true, withinGraceForeground.fields["withinGrace"])
            assertEquals("on_start", withinGraceForeground.fields["trigger"])
            val withinGraceTrail = diagnostics.eventsNamed("cause_trail")
                .filter { it.fields["stage"] == "background_grace" }
            assertEquals(
                listOf("start", "foreground_preserved"),
                withinGraceTrail.map { it.fields["outcome"] },
            )
            assertEquals("within_grace", withinGraceTrail.last().fields["cause"])
            assertTrue(
                "within-grace resume must not emit grace elapsed",
                diagnostics.eventsNamed("background_grace_elapsed").isEmpty(),
            )

            controller.onBackground()
            runCurrent()
            advanceTimeBy(graceMillis + 1)
            runCurrent()
            controller.onForeground()
            runCurrent()

            val elapsed = diagnostics.eventsNamed("background_grace_elapsed").single()
            assertEquals(true, elapsed.fields["deadlineElapsed"])
            assertEquals(null, elapsed.fields["teardown"])
            assertEquals("grace_timeout", elapsed.fields["trigger"])
            val postGraceForeground = diagnostics.eventsNamed("background_grace_foreground").last()
            assertEquals(false, postGraceForeground.fields["resumedWithinGrace"])
            assertEquals(false, postGraceForeground.fields["withinGrace"])
            val allGraceTrail = diagnostics.eventsNamed("cause_trail")
                .filter { it.fields["stage"] == "background_grace" }
            assertEquals(
                listOf("start", "foreground_preserved", "start", "elapsed", "foreground_reattach_needed"),
                allGraceTrail.map { it.fields["outcome"] },
            )
            assertEquals("post_grace", allGraceTrail.last().fields["cause"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun `staying backgrounded past grace runs the existing teardown once`() = runTest {
        val events = mutableListOf<String>()
        val controller = controller(events)

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()

        assertEquals(listOf("teardown"), events)
        assertFalse("timer must be complete after grace elapses", controller.isGracePendingForTest())

        // Coming back after teardown signals a normal post-grace reattach.
        controller.onForeground()
        runCurrent()
        assertEquals(listOf("teardown", "foreground:resumedWithinGrace=false"), events)
    }

    @Test
    fun `session foreground-service hold suppresses destructive teardown after grace elapses`() = runTest {
        val events = mutableListOf<String>()
        var sessionHoldActive = true
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                if (sessionHoldActive) {
                    events += "session:preserve"
                } else {
                    events += "tmux:background"
                    events += "ssh:stop"
                }
            },
            onForeground = { resumedWithinGrace ->
                events += "foreground:$resumedWithinGrace"
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()

        assertEquals(
            "when the session FGS is holding a live client, grace expiry must not detach tmux or stop SSH",
            listOf("session:preserve"),
            events,
        )

        controller.onForeground()
        runCurrent()
        events.clear()
        sessionHoldActive = false

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()

        assertEquals(
            "without a session hold, the old destructive teardown path must remain intact",
            listOf("tmux:background", "ssh:stop"),
            events,
        )
    }

    @Test
    fun `notification stop after held grace elapsed runs the destructive teardown path`() = runTest {
        val events = mutableListOf<String>()
        var sessionHoldActive = true
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                if (sessionHoldActive) {
                    events += "session:preserve"
                } else {
                    events += "tmux:background"
                    events += "runtime-cache:clear"
                    events += "ssh:stop"
                }
            },
            onForeground = { resumedWithinGrace ->
                events += "foreground:$resumedWithinGrace"
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()

        assertEquals(
            "the foreground service hold preserves the live connection at grace expiry",
            listOf("session:preserve"),
            events,
        )

        sessionHoldActive = false
        controller.onSessionHoldStoppedByNotification()
        runCurrent()

        assertEquals(
            "notification Stop after grace has elapsed must run the same destructive teardown " +
                "that would have run without the hold",
            listOf(
                "session:preserve",
                "tmux:background",
                "runtime-cache:clear",
                "ssh:stop",
            ),
            events,
        )

        controller.onSessionHoldStoppedByNotification()
        runCurrent()

        assertEquals(
            "repeated Stop delivery must not duplicate the teardown fanout",
            listOf(
                "session:preserve",
                "tmux:background",
                "runtime-cache:clear",
                "ssh:stop",
            ),
            events,
        )
    }

    @Test
    fun `a second background during the window does not restart the deadline`() = runTest {
        val events = mutableListOf<String>()
        val controller = controller(events)

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis - 1_000)
        runCurrent()
        // A redundant ON_STOP (e.g. transient activity churn) must keep the
        // original deadline, not push it out by another full window.
        controller.onBackground()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals("original deadline must still fire teardown", listOf("teardown"), events)
    }

    @Test
    fun `background after a previous teardown starts a fresh window`() = runTest {
        val events = mutableListOf<String>()
        val controller = controller(events)

        // First cycle: elapse and tear down.
        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()
        controller.onForeground()
        runCurrent()
        events.clear()

        // Second cycle: a within-grace resume must again avoid teardown.
        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis / 2)
        runCurrent()
        controller.onForeground()
        runCurrent()

        assertEquals(listOf("foreground:resumedWithinGrace=true"), events)
    }

    @Test
    fun `updated grace duration is used by the next background cycle`() = runTest {
        val events = mutableListOf<String>()
        val controller = controller(events)

        controller.setGraceMillis(200L)
        controller.onBackground()
        runCurrent()
        advanceTimeBy(199L)
        runCurrent()

        assertEquals(emptyList<String>(), events)

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(listOf("teardown"), events)
    }

    @Test
    fun `updating duration during an active grace window keeps the original deadline`() = runTest {
        val events = mutableListOf<String>()
        val controller = controller(events)

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis / 2)
        runCurrent()
        controller.setGraceMillis(200L)
        advanceTimeBy(graceMillis / 2 - 1)
        runCurrent()

        assertEquals(emptyList<String>(), events)

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(listOf("teardown"), events)
    }

    @Test
    fun `real app fanout runs foreground probe but does not detach tmux or stop leases on within-grace resume`() = runTest {
        val events = mutableListOf<String>()
        val activeTmuxClients = ActiveTmuxClients()
        activeTmuxClients.registerLifecycleHooks(
            hostId = 1L,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = { events += "tmux:background" },
                onForeground = { events += "tmux:foreground" },
            ),
        )
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onBackground() }
                events += "ssh:stop"
            },
            onForeground = { resumedWithinGrace ->
                events += "ssh:start"
                // Issue #548: always dispatch the foreground probe regardless
                // of grace status so stale transports are caught early.
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onForeground(resumedWithinGrace) }
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis / 2)
        runCurrent()
        controller.onForeground()
        runCurrent()

        assertEquals(
            "a short process background must not detach tmux or stop SSH leases, " +
                "but must run the foreground probe",
            listOf("ssh:start", "tmux:foreground"),
            events,
        )
    }

    @Test
    fun `six second app switch within grace emits proof diagnostics and no auto reconnect fanout`() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val events = mutableListOf<String>()
            val activeTmuxClients = ActiveTmuxClients()
            activeTmuxClients.registerLifecycleHooks(
                hostId = 1L,
                hooks = ActiveTmuxClients.LifecycleHooks(
                    onBackground = { events += "tmux:background" },
                    onForeground = { events += "tmux:foreground" },
                    onNetworkChanged = { change -> events += "network:${change.reason}" },
                ),
            )
            val gate = TerminalNetworkLifecycleGate(nowMillis = { currentTime })
            val networkChange = terminalNetworkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "six-second-switch-handoff",
            )
            val controller = BackgroundGraceController(
                scope = backgroundScope,
                graceMillis = BACKGROUND_GRACE_MILLIS,
                onGraceElapsed = {
                    events += "ssh:stop"
                    activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onBackground() }
                },
                onForeground = { resumedWithinGrace ->
                    events += "ssh:start"
                    val decision = gate.onForegroundResumeFinished(
                        resumedWithinGrace = resumedWithinGrace,
                        hasLiveTerminalRuntime = true,
                    )
                    // Issue #548: always dispatch the foreground probe regardless
                    // of grace status so stale transports are caught early.
                    activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onForeground(resumedWithinGrace) }
                    if (decision is TerminalNetworkDecision.Dispatch) {
                        activeTmuxClients.lifecycleHooksSnapshot().forEach {
                            it.onNetworkChanged(decision.change)
                        }
                    }
                },
                nowMillis = { currentTime },
            )

            gate.onBackground()
            controller.onBackground()
            runCurrent()

            advanceTimeBy(6_000L)
            runCurrent()
            assertTrue(gate.onNetworkChange(networkChange) is TerminalNetworkDecision.Defer)

            gate.onForegroundResumeStarted()
            controller.onForeground()
            runCurrent()

            assertEquals(
                "a 6s app-switch must not detach tmux or replay network reconnect, " +
                    "but must run the foreground probe",
                listOf("ssh:start", "tmux:foreground"),
                events,
            )
            assertTrue(
                "within-grace app-switch must not emit auto-reconnect decisions",
                diagnostics.eventsNamed("auto_reconnect_decision").isEmpty(),
            )
            assertTrue(
                "within-grace app-switch must not emit grace elapsed teardown",
                diagnostics.eventsNamed("background_grace_elapsed").isEmpty(),
            )
            val foreground = diagnostics.eventsNamed("background_grace_foreground").single()
            assertEquals(true, foreground.fields["resumedWithinGrace"])
            assertEquals(true, foreground.fields["withinGrace"])
            assertEquals(6_000L, foreground.fields["elapsedMs"])
            assertEquals(BACKGROUND_GRACE_MILLIS, foreground.fields["millis"])
            assertEquals(1L, foreground.fields["backgroundCycleId"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun `real app fanout drops deferred network reconnect on within-grace resume`() = runTest {
        val events = mutableListOf<String>()
        var pendingNetworkChange: TerminalNetworkChange? = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.NoValidatedNetwork,
            current = TerminalNetworkSnapshot.Validated("wifi"),
            previousValidated = null,
            reason = "initial-validated-network",
        )
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                events += "tmux:background"
                events += "ssh:stop"
            },
            onForeground = { resumedWithinGrace ->
                events += "ssh:start"
                // Issue #548: always run the foreground probe.
                events += "tmux:foreground"
                if (shouldDispatchPendingTerminalNetworkChange(
                        resumedWithinGrace = resumedWithinGrace,
                        pendingChange = pendingNetworkChange,
                        hasLiveTerminalRuntime = true,
                    )
                ) {
                    events += "network:dispatch"
                    pendingNetworkChange = null
                } else {
                    pendingNetworkChange = null
                }
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis / 2)
        runCurrent()
        controller.onForeground()
        runCurrent()

        assertEquals(
            "a short background must not replay deferred network changes as reconnects, " +
                "but must run the foreground probe",
            listOf("ssh:start", "tmux:foreground"),
            events,
        )
        assertEquals("pending network change should be consumed", null, pendingNetworkChange)
    }

    @Test
    fun `real app fanout suppresses deferred real network handoff on within-grace resume with live client`() = runTest {
        val events = mutableListOf<String>()
        val activeTmuxClients = ActiveTmuxClients()
        activeTmuxClients.registerLifecycleHooks(
            hostId = 1L,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = { events += "tmux:background" },
                onForeground = { events += "tmux:foreground" },
                onNetworkChanged = { change -> events += "network:${change.reason}" },
            ),
        )
        var pendingNetworkChange: TerminalNetworkChange? = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "wifi-cellular-handoff",
        )
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onBackground() }
                events += "ssh:stop"
            },
            onForeground = { resumedWithinGrace ->
                events += "ssh:start"
                // Issue #548: always run foreground probe, even within grace.
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onForeground(resumedWithinGrace) }
                if (!resumedWithinGrace) {
                    if (shouldDispatchPendingTerminalNetworkChange(
                            resumedWithinGrace = resumedWithinGrace,
                            pendingChange = pendingNetworkChange,
                            hasLiveTerminalRuntime = false,
                        )
                    ) {
                        val change = pendingNetworkChange!!
                        pendingNetworkChange = null
                        activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onNetworkChanged(change) }
                    } else {
                        pendingNetworkChange = null
                    }
                } else if (shouldDispatchPendingTerminalNetworkChange(
                        resumedWithinGrace = resumedWithinGrace,
                        pendingChange = pendingNetworkChange,
                        hasLiveTerminalRuntime = true,
                    )
                ) {
                    val change = pendingNetworkChange!!
                    pendingNetworkChange = null
                    activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onNetworkChanged(change) }
                } else {
                    pendingNetworkChange = null
                }
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis / 2)
        runCurrent()
        controller.onForeground()
        runCurrent()

        assertEquals(
            "a real validated handoff during grace must not force a visible reconnect while the client is live",
            listOf("ssh:start", "tmux:foreground"),
            events,
        )
        assertEquals("pending network change should be consumed", null, pendingNetworkChange)
    }

    @Test
    fun `real app fanout dispatches deferred real network handoff after grace teardown`() = runTest {
        val events = mutableListOf<String>()
        val activeTmuxClients = ActiveTmuxClients()
        activeTmuxClients.registerLifecycleHooks(
            hostId = 1L,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = { events += "tmux:background" },
                onForeground = { events += "tmux:foreground" },
                onNetworkChanged = { change -> events += "network:${change.reason}" },
            ),
        )
        var pendingNetworkChange: TerminalNetworkChange? = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "wifi-cellular-handoff",
        )
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onBackground() }
                events += "ssh:stop"
            },
            onForeground = { resumedWithinGrace ->
                events += "ssh:start"
                // Issue #548: always run foreground probe, even within grace.
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onForeground(resumedWithinGrace) }
                if (shouldDispatchPendingTerminalNetworkChange(
                        resumedWithinGrace = resumedWithinGrace,
                        pendingChange = pendingNetworkChange,
                        hasLiveTerminalRuntime = false,
                    )
                ) {
                    val change = pendingNetworkChange!!
                    pendingNetworkChange = null
                    activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onNetworkChanged(change) }
                } else {
                    pendingNetworkChange = null
                }
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()
        controller.onForeground()
        runCurrent()

        assertEquals(
            "after grace teardown, foreground must still run the normal reattach and network reconnect path",
            listOf(
                "tmux:background",
                "ssh:stop",
                "ssh:start",
                "tmux:foreground",
                "network:wifi-cellular-handoff",
            ),
            events,
        )
        assertEquals("pending network change should be consumed", null, pendingNetworkChange)
    }

    @Test
    fun `real app fanout still dispatches network changes while app is foreground active`() = runTest {
        val events = mutableListOf<String>()
        val activeTmuxClients = ActiveTmuxClients()
        activeTmuxClients.registerLifecycleHooks(
            hostId = 1L,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = {},
                onForeground = {},
                onNetworkChanged = { change -> events += "network:${change.reason}" },
            ),
        )

        val change = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "foreground-wifi-cellular-handoff",
        )
        activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onNetworkChanged(change) }

        assertEquals(
            listOf("network:foreground-wifi-cellular-handoff"),
            events,
        )
    }

    @Test
    fun `network change after on start waits for grace decision and suppresses with live runtime`() {
        val gate = TerminalNetworkLifecycleGate()
        val change = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "default-network-capabilities",
        )

        gate.onBackground()
        gate.onForegroundResumeStarted()

        val immediateDecision = gate.onNetworkChange(change)
        assertTrue(
            "network fanout must remain blocked until the foreground grace decision completes",
            immediateDecision is TerminalNetworkDecision.Defer,
        )
        assertEquals("defer", immediateDecision.gateDiagnostics.decision)
        assertEquals("foreground_resume_pending", immediateDecision.gateDiagnostics.reason)

        val foregroundDecision = gate.onForegroundResumeFinished(
            resumedWithinGrace = true,
            hasLiveTerminalRuntime = true,
        )
        val suppress = foregroundDecision as TerminalNetworkDecision.Suppress
        assertEquals(
            "a survived live runtime within grace must suppress queued network reconnect",
            change,
            suppress.change,
        )
        assertEquals("suppress", suppress.gateDiagnostics.decision)
        assertEquals("within_grace_live_runtime", suppress.gateDiagnostics.reason)
        assertEquals(true, suppress.gateDiagnostics.resumedWithinGrace)
        assertEquals(true, suppress.gateDiagnostics.hasLiveTerminalRuntime)
    }

    @Test
    fun `within grace foreground diagnostics classify pending network as suppressed with live runtime`() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val gate = TerminalNetworkLifecycleGate(nowMillis = { currentTime })
            val change = terminalNetworkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "default-network-capabilities",
            )
            val controller = BackgroundGraceController(
                scope = backgroundScope,
                graceMillis = graceMillis,
                onGraceElapsed = {},
                onForeground = { resumedWithinGrace ->
                    assertTrue(resumedWithinGrace)
                    assertTrue(
                        gate.onForegroundResumeFinished(
                            resumedWithinGrace = true,
                            hasLiveTerminalRuntime = true,
                        ) is TerminalNetworkDecision.Suppress,
                    )
                },
                nowMillis = { currentTime },
                foregroundDiagnosticFields = { resumedWithinGrace ->
                    listOf(
                        "hasLiveTerminalRuntime" to true,
                        "activeTmuxClientCount" to 1,
                        "liveActiveTmuxClientCount" to 1,
                        "cachedRuntimeCount" to 0,
                        "liveCachedRuntimeCount" to 0,
                        "clientDisconnected" to false,
                        "sessionConnected" to true,
                    ) + gate.previewForegroundResumeFinished(
                        resumedWithinGrace = resumedWithinGrace,
                        hasLiveTerminalRuntime = true,
                    ).diagnosticFields()
                },
            )

            gate.onBackground(graceMillis = graceMillis)
            controller.onBackground()
            runCurrent()
            advanceTimeBy(graceMillis / 2)
            runCurrent()
            assertTrue(gate.onNetworkChange(change, hasLiveTerminalRuntime = true) is TerminalNetworkDecision.Defer)

            gate.onForegroundResumeStarted()
            controller.onForeground()
            runCurrent()

            val foreground = diagnostics.eventsNamed("background_grace_foreground").single()
            assertEquals(true, foreground.fields["resumedWithinGrace"])
            assertEquals(true, foreground.fields["hasLiveTerminalRuntime"])
            assertEquals(1, foreground.fields["activeTmuxClientCount"])
            assertEquals(1, foreground.fields["liveActiveTmuxClientCount"])
            assertEquals(0, foreground.fields["cachedRuntimeCount"])
            assertEquals(0, foreground.fields["liveCachedRuntimeCount"])
            assertEquals(false, foreground.fields["clientDisconnected"])
            assertEquals(true, foreground.fields["sessionConnected"])
            assertEquals("suppress", foreground.fields["gateDecision"])
            assertEquals("within_grace_live_runtime", foreground.fields["gateReason"])
            assertEquals("suppressed", foreground.fields["reconnectOutcome"])
            assertEquals(true, foreground.fields["pendingNetworkChange"])
            assertEquals("real_validated_identity_change", foreground.fields["pendingNetworkClassification"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun `within grace foreground diagnostics classify pending network as scheduled with no live runtime`() = runTest {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val events = mutableListOf<String>()
            val gate = TerminalNetworkLifecycleGate(nowMillis = { currentTime })
            val change = terminalNetworkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "default-network-capabilities",
            )
            val controller = BackgroundGraceController(
                scope = backgroundScope,
                graceMillis = graceMillis,
                onGraceElapsed = {},
                onForeground = { resumedWithinGrace ->
                    val decision = gate.onForegroundResumeFinished(
                        resumedWithinGrace = resumedWithinGrace,
                        hasLiveTerminalRuntime = false,
                    )
                    if (decision is TerminalNetworkDecision.Dispatch) {
                        events += "network:${decision.change.reason}"
                    }
                },
                nowMillis = { currentTime },
                foregroundDiagnosticFields = { resumedWithinGrace ->
                    listOf(
                        "hasLiveTerminalRuntime" to false,
                        "activeTmuxClientCount" to 1,
                        "liveActiveTmuxClientCount" to 0,
                        "cachedRuntimeCount" to 0,
                        "liveCachedRuntimeCount" to 0,
                        "clientDisconnected" to true,
                        "sessionConnected" to false,
                    ) + gate.previewForegroundResumeFinished(
                        resumedWithinGrace = resumedWithinGrace,
                        hasLiveTerminalRuntime = false,
                    ).diagnosticFields()
                },
            )

            gate.onBackground(graceMillis = graceMillis)
            controller.onBackground()
            runCurrent()
            advanceTimeBy(graceMillis / 2)
            runCurrent()
            assertTrue(gate.onNetworkChange(change, hasLiveTerminalRuntime = false) is TerminalNetworkDecision.Defer)

            gate.onForegroundResumeStarted()
            controller.onForeground()
            runCurrent()

            assertEquals(listOf("network:default-network-capabilities"), events)
            val foreground = diagnostics.eventsNamed("background_grace_foreground").single()
            assertEquals(true, foreground.fields["resumedWithinGrace"])
            assertEquals(false, foreground.fields["hasLiveTerminalRuntime"])
            assertEquals(1, foreground.fields["activeTmuxClientCount"])
            assertEquals(0, foreground.fields["liveActiveTmuxClientCount"])
            assertEquals(0, foreground.fields["cachedRuntimeCount"])
            assertEquals(0, foreground.fields["liveCachedRuntimeCount"])
            assertEquals(true, foreground.fields["clientDisconnected"])
            assertEquals(false, foreground.fields["sessionConnected"])
            assertEquals("dispatch", foreground.fields["gateDecision"])
            assertEquals("within_grace_no_live_runtime_real_handoff", foreground.fields["gateReason"])
            assertEquals("scheduled", foreground.fields["reconnectOutcome"])
            assertEquals(true, foreground.fields["pendingNetworkChange"])
            assertEquals("real_validated_identity_change", foreground.fields["pendingNetworkClassification"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun `queued network change dispatches after post grace foreground decision`() {
        val gate = TerminalNetworkLifecycleGate()
        val change = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "default-network-capabilities",
        )

        gate.onBackground()
        gate.onForegroundResumeStarted()
        assertTrue(gate.onNetworkChange(change) is TerminalNetworkDecision.Defer)

        val foregroundDecision = gate.onForegroundResumeFinished(
            resumedWithinGrace = false,
            hasLiveTerminalRuntime = false,
        )

        val dispatch = foregroundDecision as TerminalNetworkDecision.Dispatch
        assertEquals(
            "after grace teardown, the foreground path must still replay the queued reconnect signal",
            change.copy(deferredFromBackground = true),
            dispatch.change,
        )
        assertEquals("dispatch", dispatch.gateDiagnostics.decision)
        assertEquals("post_grace_foreground", dispatch.gateDiagnostics.reason)
        assertEquals(false, dispatch.gateDiagnostics.resumedWithinGrace)
        assertEquals(false, dispatch.gateDiagnostics.hasLiveTerminalRuntime)
    }

    @Test
    fun `late post resume network callbacks within grace live runtime do not fan out`() = runTest {
        val events = mutableListOf<String>()
        val activeTmuxClients = ActiveTmuxClients()
        activeTmuxClients.registerLifecycleHooks(
            hostId = 1L,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = {},
                onForeground = {},
                onNetworkChanged = { change -> events += "network:${change.reason}" },
            ),
        )
        var now = 10_000L
        val gate = TerminalNetworkLifecycleGate(nowMillis = { now })
        val change = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "late-background-default-network-capabilities",
        )
        val secondChange = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("cell"),
            current = TerminalNetworkSnapshot.Validated("wifi"),
            previousValidated = TerminalNetworkSnapshot.Validated("cell"),
            reason = "late-background-network-available",
            sequence = 2L,
        )

        gate.onBackground()
        gate.onForegroundResumeStarted()
        val foregroundDecision = gate.onForegroundResumeFinished(
            resumedWithinGrace = true,
            hasLiveTerminalRuntime = true,
        ) as TerminalNetworkDecision.Suppress
        assertEquals(null, foregroundDecision.change)
        assertEquals("no_pending_change", foregroundDecision.gateDiagnostics.reason)

        now += 1_500L
        val lateDecision = gate.onNetworkChange(change)
        if (lateDecision is TerminalNetworkDecision.Dispatch) {
            activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onNetworkChanged(lateDecision.change) }
        }

        val suppress = lateDecision as TerminalNetworkDecision.Suppress
        assertEquals(
            "a live runtime that survived a within-grace background must suppress the first late network callback",
            change,
            suppress.change,
        )
        assertEquals("suppress", suppress.gateDiagnostics.decision)
        assertEquals("post_resume_within_grace_live_runtime", suppress.gateDiagnostics.reason)
        assertEquals(true, suppress.gateDiagnostics.resumedWithinGrace)
        assertEquals(true, suppress.gateDiagnostics.hasLiveTerminalRuntime)
        assertEquals(1L, suppress.gateDiagnostics.backgroundCycleId)

        now += 1_000L
        val secondLateDecision = gate.onNetworkChange(secondChange)
        if (secondLateDecision is TerminalNetworkDecision.Dispatch) {
            activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onNetworkChanged(secondLateDecision.change) }
        }
        val secondSuppress = secondLateDecision as TerminalNetworkDecision.Suppress
        assertEquals(
            "all late callbacks inside the attribution window must remain suppressed for the same background cycle",
            secondChange,
            secondSuppress.change,
        )
        assertEquals("post_resume_within_grace_live_runtime", secondSuppress.gateDiagnostics.reason)
        assertEquals(1L, secondSuppress.gateDiagnostics.backgroundCycleId)
        assertEquals("late post-resume callbacks must not fan out to terminal reconnect", emptyList<String>(), events)
    }

    @Test
    fun `suppressed pending background change still arms post resume suppression`() {
        var now = 20_000L
        val gate = TerminalNetworkLifecycleGate(nowMillis = { now })
        val pendingBackgroundChange = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "background-wifi-cellular-handoff",
        )
        val lateBackgroundChange = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("cell"),
            current = TerminalNetworkSnapshot.Validated("wifi"),
            previousValidated = TerminalNetworkSnapshot.Validated("cell"),
            reason = "late-background-default-network-capabilities",
            sequence = 2L,
        )

        gate.onBackground()
        assertTrue(gate.onNetworkChange(pendingBackgroundChange) is TerminalNetworkDecision.Defer)
        gate.onForegroundResumeStarted()
        val foregroundDecision = gate.onForegroundResumeFinished(
            resumedWithinGrace = true,
            hasLiveTerminalRuntime = true,
        ) as TerminalNetworkDecision.Suppress
        assertEquals(
            "the pending background handoff must be consumed without forcing a live runtime reconnect",
            pendingBackgroundChange,
            foregroundDecision.change,
        )
        assertEquals("within_grace_live_runtime", foregroundDecision.gateDiagnostics.reason)
        assertEquals(1L, foregroundDecision.gateDiagnostics.backgroundCycleId)

        now += 1_500L
        val lateDecision = gate.onNetworkChange(lateBackgroundChange) as TerminalNetworkDecision.Suppress
        assertEquals(
            "suppressing a pending change must still suppress later callbacks from that same background cycle",
            lateBackgroundChange,
            lateDecision.change,
        )
        assertEquals("post_resume_within_grace_live_runtime", lateDecision.gateDiagnostics.reason)
        assertEquals(1L, lateDecision.gateDiagnostics.backgroundCycleId)
    }

    @Test
    fun `foreground handoff after post resume attribution window dispatches after earlier suppression`() {
        var now = 20_000L
        val gate = TerminalNetworkLifecycleGate(nowMillis = { now })
        val lateBackgroundChange = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "late-background-default-network-capabilities",
        )
        val foregroundChange = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("cell"),
            current = TerminalNetworkSnapshot.Validated("ethernet"),
            previousValidated = TerminalNetworkSnapshot.Validated("cell"),
            reason = "foreground-ethernet-handoff",
            sequence = 2L,
        )

        gate.onBackground(graceMillis = 2_000L)
        gate.onForegroundResumeStarted()
        assertTrue(
            gate.onForegroundResumeFinished(
                resumedWithinGrace = true,
                hasLiveTerminalRuntime = true,
            ) is TerminalNetworkDecision.Suppress,
        )

        now += 1_500L
        assertTrue(gate.onNetworkChange(lateBackgroundChange) is TerminalNetworkDecision.Suppress)

        now += POST_RESUME_NETWORK_ATTRIBUTION_MILLIS + 1
        val foregroundDecision = gate.onNetworkChange(foregroundChange) as TerminalNetworkDecision.Dispatch
        assertEquals(
            "a real foreground handoff after the bounded attribution window must still reconnect",
            foregroundChange,
            foregroundDecision.change,
        )
        assertEquals("dispatch", foregroundDecision.gateDiagnostics.decision)
        assertEquals("foreground_active", foregroundDecision.gateDiagnostics.reason)
    }

    @Test
    fun `foreground handoff after post resume attribution bound dispatches`() {
        var now = 30_000L
        val gate = TerminalNetworkLifecycleGate(nowMillis = { now })
        val foregroundChange = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "later-foreground-wifi-cellular-handoff",
        )

        gate.onBackground(graceMillis = 2_000L)
        gate.onForegroundResumeStarted()
        assertTrue(
            gate.onForegroundResumeFinished(
                resumedWithinGrace = true,
                hasLiveTerminalRuntime = true,
            ) is TerminalNetworkDecision.Suppress,
        )

        now += POST_RESUME_NETWORK_ATTRIBUTION_MILLIS + 1
        val foregroundDecision = gate.onNetworkChange(foregroundChange) as TerminalNetworkDecision.Dispatch
        assertEquals(
            "a real foreground handoff after the bounded attribution window must still reconnect",
            foregroundChange,
            foregroundDecision.change,
        )
        assertEquals("dispatch", foregroundDecision.gateDiagnostics.decision)
        assertEquals("foreground_active", foregroundDecision.gateDiagnostics.reason)
    }

    @Test
    fun `six second app switch suppresses late network callback until configured grace deadline`() = runTest {
        val events = mutableListOf<String>()
        val activeTmuxClients = ActiveTmuxClients()
        activeTmuxClients.registerLifecycleHooks(
            hostId = 1L,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = { events += "tmux:background" },
                onForeground = { events += "tmux:foreground" },
                onNetworkChanged = { change -> events += "network:${change.reason}" },
            ),
        )
        var now = 100_000L
        val gate = TerminalNetworkLifecycleGate(nowMillis = { now })
        val lateBackgroundChange = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "late-background-callback-after-short-switch",
        )

        gate.onBackground(graceMillis = 30_000L)
        now += 6_000L
        gate.onForegroundResumeStarted()
        val foregroundDecision = gate.onForegroundResumeFinished(
            resumedWithinGrace = true,
            hasLiveTerminalRuntime = true,
        ) as TerminalNetworkDecision.Suppress
        assertEquals(null, foregroundDecision.change)
        assertEquals("no_pending_change", foregroundDecision.gateDiagnostics.reason)

        now += 6_000L
        val lateDecision = gate.onNetworkChange(lateBackgroundChange)
        if (lateDecision is TerminalNetworkDecision.Dispatch) {
            activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onNetworkChanged(lateDecision.change) }
        }

        val suppress = lateDecision as TerminalNetworkDecision.Suppress
        assertEquals(lateBackgroundChange, suppress.change)
        assertEquals("post_resume_within_grace_live_runtime", suppress.gateDiagnostics.reason)
        assertEquals(
            "a short app-switch under the configured grace must not fan out a late network reconnect",
            emptyList<String>(),
            events,
        )
    }

    @Test
    fun `post resume network callback dispatches when runtime did not survive`() {
        val gate = TerminalNetworkLifecycleGate()
        val foregroundChange = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "post-resume-no-live-runtime-handoff",
        )

        gate.onBackground()
        gate.onForegroundResumeStarted()
        assertTrue(
            gate.onForegroundResumeFinished(
                resumedWithinGrace = true,
                hasLiveTerminalRuntime = false,
            ) is TerminalNetworkDecision.Suppress,
        )

        val foregroundDecision = gate.onNetworkChange(foregroundChange) as TerminalNetworkDecision.Dispatch
        assertEquals(
            "when the runtime did not survive, a real handoff must still reconnect",
            foregroundChange,
            foregroundDecision.change,
        )
        assertEquals("dispatch", foregroundDecision.gateDiagnostics.decision)
        assertEquals("foreground_active", foregroundDecision.gateDiagnostics.reason)
    }

    @Test
    fun `foreground active network change dispatches immediately after resume decision`() {
        val gate = TerminalNetworkLifecycleGate()
        val change = terminalNetworkChange(
            previous = TerminalNetworkSnapshot.Validated("wifi"),
            current = TerminalNetworkSnapshot.Validated("cell"),
            previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "foreground-wifi-cellular-handoff",
        )

        gate.onForegroundResumeStarted()
        val emptyForegroundDecision = gate.onForegroundResumeFinished(
            resumedWithinGrace = false,
            hasLiveTerminalRuntime = false,
        ) as TerminalNetworkDecision.Suppress
        assertEquals(null, emptyForegroundDecision.change)
        assertEquals("no_pending_change", emptyForegroundDecision.gateDiagnostics.reason)

        val foregroundDecision = gate.onNetworkChange(change) as TerminalNetworkDecision.Dispatch
        assertEquals(
            "normal active foreground fanout should not stay blocked after the foreground decision",
            change,
            foregroundDecision.change,
        )
        assertEquals("dispatch", foregroundDecision.gateDiagnostics.decision)
        assertEquals("foreground_active", foregroundDecision.gateDiagnostics.reason)
    }

    @Test
    fun `real app fanout detaches tmux and stops leases only after grace elapses`() = runTest {
        val events = mutableListOf<String>()
        val activeTmuxClients = ActiveTmuxClients()
        activeTmuxClients.registerLifecycleHooks(
            hostId = 1L,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = { events += "tmux:background" },
                onForeground = { events += "tmux:foreground" },
            ),
        )
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onBackground() }
                events += "ssh:stop"
            },
            onForeground = { resumedWithinGrace ->
                events += "ssh:start"
                // Issue #548: always run foreground probe.
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onForeground(resumedWithinGrace) }
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()
        controller.onForeground()
        runCurrent()

        assertEquals(
            listOf("tmux:background", "ssh:stop", "ssh:start", "tmux:foreground"),
            events,
        )
    }

    @Test
    fun `foreground at grace boundary waits for background teardown before reattach`() = runTest {
        val events = mutableListOf<String>()
        val teardownStarted = CompletableDeferred<Unit>()
        val allowTeardownToFinish = CompletableDeferred<Unit>()
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                events += "tmux:background:start"
                teardownStarted.complete(Unit)
                allowTeardownToFinish.await()
                events += "tmux:background:finish"
            },
            onForeground = { resumedWithinGrace ->
                events += "tmux:foreground:resumedWithinGrace=$resumedWithinGrace"
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()
        teardownStarted.await()

        controller.onForeground()
        runCurrent()

        assertEquals(
            "post-grace foreground must not reattach while background teardown is still in flight",
            listOf("tmux:background:start"),
            events,
        )

        allowTeardownToFinish.complete(Unit)
        runCurrent()

        assertEquals(
            listOf(
                "tmux:background:start",
                "tmux:background:finish",
                "tmux:foreground:resumedWithinGrace=false",
            ),
            events,
        )
    }

    @Test
    fun `port forwarding remains active while terminal reconnects after grace teardown`() = runTest {
        val events = mutableListOf<String>()
        val forwardingActive = true
        var terminalRuntimeCached = true
        var sshLeaseStopped = false
        val activeTmuxClients = ActiveTmuxClients()
        activeTmuxClients.registerLifecycleHooks(
            hostId = 1L,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = { events += "terminal:background" },
                onForeground = { events += "terminal:foreground" },
            ),
        )
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onBackground() }
                terminalRuntimeCached = false
                sshLeaseStopped = true
                events += "terminal:runtime-cache-cleared"
                events += "ssh:stop"
            },
            onForeground = { resumedWithinGrace ->
                events += "ssh:start"
                // Issue #548: always run foreground probe.
                activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onForeground(resumedWithinGrace) }
            },
        )

        controller.onBackground()
        runCurrent()
        advanceTimeBy(graceMillis + 1)
        runCurrent()

        assertEquals(
            "terminal grace teardown must not disable the independent forwarding supervisor",
            true,
            forwardingActive,
        )
        assertEquals(false, terminalRuntimeCached)
        assertEquals(true, sshLeaseStopped)

        controller.onForeground()
        runCurrent()

        assertEquals(
            listOf(
                "terminal:background",
                "terminal:runtime-cache-cleared",
                "ssh:stop",
                "ssh:start",
                "terminal:foreground",
            ),
            events,
        )
        assertEquals(true, forwardingActive)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        events: MutableList<String>,
    ): BackgroundGraceController =
        BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = { events += "teardown" },
            onForeground = { resumedWithinGrace ->
                events += "foreground:resumedWithinGrace=$resumedWithinGrace"
            },
            nowMillis = { currentTime },
        )

    private fun terminalNetworkChange(
        previous: TerminalNetworkSnapshot,
        current: TerminalNetworkSnapshot.Validated,
        previousValidated: TerminalNetworkSnapshot.Validated?,
        reason: String,
        sequence: Long = 1L,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
        )
}
