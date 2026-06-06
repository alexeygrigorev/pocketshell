package com.pocketshell.app

import com.pocketshell.app.sessions.ActiveTmuxClients
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
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
 * `runTest` virtual time drives [kotlinx.coroutines.delay] deterministically
 * so the 60 s window is exercised without wall-clock waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundGraceControllerTest {

    private val graceMillis = 60_000L

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
    fun `real app fanout does not detach tmux or stop leases on within-grace resume`() = runTest {
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
                if (!resumedWithinGrace) {
                    activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onForeground() }
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
            "a short process background must not detach tmux or stop SSH leases",
            listOf("ssh:start"),
            events,
        )
    }

    @Test
    fun `real app fanout drops deferred network reconnect on within-grace resume`() = runTest {
        val events = mutableListOf<String>()
        var pendingNetworkChange = true
        val controller = BackgroundGraceController(
            scope = backgroundScope,
            graceMillis = graceMillis,
            onGraceElapsed = {
                events += "tmux:background"
                events += "ssh:stop"
            },
            onForeground = { resumedWithinGrace ->
                events += "ssh:start"
                if (!resumedWithinGrace) {
                    events += "tmux:foreground"
                    if (pendingNetworkChange) {
                        events += "network:dispatch"
                        pendingNetworkChange = false
                    }
                } else {
                    pendingNetworkChange = false
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
            "a short background must not replay deferred network changes as reconnects",
            listOf("ssh:start"),
            events,
        )
        assertFalse("pending network change should be consumed", pendingNetworkChange)
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
                if (!resumedWithinGrace) {
                    activeTmuxClients.lifecycleHooksSnapshot().forEach { it.onForeground() }
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
            listOf("tmux:background", "ssh:stop", "ssh:start", "tmux:foreground"),
            events,
        )
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
        )
}
