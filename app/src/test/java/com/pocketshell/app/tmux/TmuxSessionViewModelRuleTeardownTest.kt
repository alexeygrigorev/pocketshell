package com.pocketshell.app.tmux

import com.pocketshell.app.hosts.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class TmuxSessionViewModelRuleTeardownTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun registeredTeardownRunsBeforeTheRuleResetsMain() {
        val ranOnInstalledMain = AtomicBoolean(false)
        mainDispatcherRule.beforeResetMain {
            CoroutineScope(Dispatchers.Main).launch {
                ranOnInstalledMain.set(true)
            }
            mainDispatcherRule.runCurrent()
            assertTrue(
                "registered teardown must run while Dispatchers.Main is still installed",
                ranOnInstalledMain.get(),
            )
        }
    }

    @Test
    fun automaticRuleFinallyDrainsRegisteredTeardownInLifoOrder() {
        var activeChildren = 1
        // Register the assertion first. MainDispatcherRule runs callbacks LIFO,
        // so the registry created below must drain before this assertion. This
        // test deliberately never calls drain(): removing the registry's
        // beforeResetMain(::drain) registration leaves activeChildren == 1 and
        // fails from the rule's real finally boundary.
        mainDispatcherRule.beforeResetMain {
            assertEquals(
                "the automatic registry callback must run before an earlier rule cleanup",
                0,
                activeChildren,
            )
        }
        val teardown = mainDispatcherRule.tmuxSessionViewModelTeardown(timeoutMs = 100L)
        teardown.track(
            clear = {},
            cancelOwnScopes = { activeChildren = 0 },
            activeOwnScopeChildCount = { activeChildren },
        )
    }

    @Test
    fun omittedVmCancellationHardFailsInsteadOfLeakingAcrossTheRuleBoundary() {
        val events = mutableListOf<String>()
        var activeChildren = 1
        val teardown = mainDispatcherRule.tmuxSessionViewModelTeardown(timeoutMs = 100L)
        teardown.track(
            clear = { events += "clear" },
            cancelOwnScopes = {
                events += "cancel"
                activeChildren = 0
            },
            activeOwnScopeChildCount = { activeChildren },
        )

        teardown.drain()

        assertEquals(listOf("clear", "cancel", "cancel"), events)
        assertEquals(0, activeChildren)
    }

    @Test
    fun ownedRealIoRootIsCancelledAndJoinedBeforeMainReset() {
        val started = AtomicBoolean(false)
        val root = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val teardown = mainDispatcherRule.tmuxSessionViewModelTeardown(timeoutMs = 5_000L)
        teardown.trackRoot("fixture", root)
        root.launch {
            started.set(true)
            awaitCancellation()
        }
        while (!started.get()) {
            Thread.yield()
        }

        teardown.drain()

        assertTrue(
            "the tracked root Job must be fully completed, not merely cancellation-requested",
            root.coroutineContext[kotlinx.coroutines.Job]!!.isCompleted,
        )
    }
}
