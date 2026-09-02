package com.pocketshell.app.ssh

import com.pocketshell.core.ssh.ChangedHostKeyException
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.UnknownHostKeyException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2463 (mechanism-level class coverage for the release-branch regression).
 *
 * The v0.4.47 candidate navigated to the Trust/Test-connect screen on ANY
 * host-key failure report, from a retained `StateFlow<Long?>` on a process-wide
 * singleton. Two independent defects, both covered here:
 *
 *  1. a BACKGROUND failure (cold-launch reprobe, port-forward resume, pooled
 *     reconnect) must annotate only, never raise a navigation event;
 *  2. the prompt stream must not REPLAY — a collector that subscribes after the
 *     emission (a freshly created `MainActivity`) must receive nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostKeyTrustPromptRouterTest {

    // -------------------------------------------------- (1) background != nav

    @Test
    fun backgroundHostKeyFailuresAnnotateTheHostAndRaiseNoPrompt() = runTest {
        val router = HostKeyTrustPromptRouter()
        val prompts = collectPrompts(router)

        // The whole class of background reporters: an unknown key from the
        // cold-launch reprobe, a CHANGED key from a pooled reconnect, several
        // hosts at once (the migration case), and a repeat report.
        router.report(4, unknown())
        router.report(7, changed())
        router.report(9, unknown())
        router.report(4, unknown())

        assertEquals(
            "every background host-key failure must annotate its host card",
            setOf(4L, 7L, 9L),
            router.hostsNeedingTrust.value,
        )
        assertEquals(
            "a background host-key failure must never raise a navigation prompt",
            emptyList<Long>(),
            prompts,
        )
    }

    @Test
    fun foregroundUserInitiatedFailurePromptsAndStillAnnotates() = runTest {
        val router = HostKeyTrustPromptRouter()
        val prompts = collectPrompts(router)

        router.armUserInitiatedConnect(4)
        router.report(4, unknown())

        assertEquals(listOf(4L), prompts)
        assertEquals(setOf(4L), router.hostsNeedingTrust.value)
    }

    @Test
    fun bracketedUserInitiatedConnectPromptsEvenWhenTheWindowWouldHaveExpired() = runTest {
        var now = 1_000L
        val router = HostKeyTrustPromptRouter { now }
        val prompts = collectPrompts(router)

        router.withUserInitiatedConnect(11) {
            now += 10 * HostKeyTrustPromptRouter.USER_INTENT_WINDOW_MS
            router.report(11, changed())
        }

        assertEquals("a slow bracketed handshake must not lose the prompt", listOf(11L), prompts)
        assertFalse(
            "the bracket must not leak past the attempt",
            router.isUserInitiated(11),
        )
    }

    @Test
    fun theForegroundWindowIsPerHostAndExpires() = runTest {
        var now = 1_000L
        val router = HostKeyTrustPromptRouter { now }
        val prompts = collectPrompts(router)

        router.armUserInitiatedConnect(4)

        // A DIFFERENT host failing inside host 4's window is still background.
        router.report(5, unknown())
        assertEquals(emptyList<Long>(), prompts)

        // And host 4's own window does not outlive the tap: a reprobe minutes
        // later must not inherit it.
        now += HostKeyTrustPromptRouter.USER_INTENT_WINDOW_MS + 1
        router.report(4, unknown())
        assertEquals(emptyList<Long>(), prompts)
        assertEquals(setOf(4L, 5L), router.hostsNeedingTrust.value)
    }

    @Test
    fun disarmDropsTheForegroundWindow() = runTest {
        val router = HostKeyTrustPromptRouter()
        val prompts = collectPrompts(router)

        router.armUserInitiatedConnect(4)
        router.disarmUserInitiatedConnect(4)
        router.report(4, unknown())

        assertEquals(emptyList<Long>(), prompts)
    }

    // ------------------------------------------------------- (2) no replay

    @Test
    fun aPromptIsNeverReplayedIntoACollectorThatSubscribesLater() = runTest {
        val router = HostKeyTrustPromptRouter()

        // A foreground failure with NO collector present — e.g. the Activity was
        // destroyed between the tap and the failure.
        router.armUserInitiatedConnect(4)
        router.report(4, unknown())

        // A brand-new MainActivity subscribes. It must inherit nothing.
        val prompts = collectPrompts(router)
        assertEquals(
            "a freshly created Activity must not replay a stale pending host id",
            emptyList<Long>(),
            prompts,
        )

        // ...and the router is still live for the NEXT genuine foreground ask.
        router.armUserInitiatedConnect(4)
        router.report(4, unknown())
        assertEquals(listOf(4L), prompts)
    }

    @Test
    fun theAnnotationSetIsRetainedForALateCollectorButNavigationIsNot() = runTest {
        val router = HostKeyTrustPromptRouter()
        router.report(4, unknown())

        // The host card still learns about it (StateFlow, by design)...
        assertEquals(setOf(4L), router.hostsNeedingTrust.value)
        // ...while a navigator attaching now sees no prompt.
        assertEquals(emptyList<Long>(), collectPrompts(router))
    }

    // ----------------------------------- (3) per-attempt outcome != sticky set

    @Test
    fun theAttemptWatchAnswersForThisAttemptOnlyNotForTheStickyAnnotation() = runTest {
        val router = HostKeyTrustPromptRouter()

        // A background reprobe annotated host 4. The annotation is sticky by
        // design (only a successful connect clears it).
        router.report(4, unknown())
        assertTrue(4L in router.hostsNeedingTrust.value)

        // A later attempt that does NOT fail host-key verification must read as
        // "no host-key failure" even though the host is still annotated.
        val quiet = router.withHostKeyFailureWatch(4) { "no-report" }
        assertFalse(
            "issue #2463: reading the sticky annotation instead of this attempt's own outcome " +
                "is what turned a later non-host-key failure into a silent no-op tap",
            quiet.failedHostKeyVerification,
        )
        assertEquals("no-report", quiet.value)

        // An attempt that DOES fail host-key verification reads as one.
        val failing = router.withHostKeyFailureWatch(4) {
            router.report(4, unknown())
            null
        }
        assertTrue(failing.failedHostKeyVerification)

        // Another host's failure inside the window is not this host's.
        val neighbour = router.withHostKeyFailureWatch(4) { router.report(5, changed()) }
        assertFalse(neighbour.failedHostKeyVerification)
    }

    @Test
    fun resetForTestClearsEveryProcessScopedTrace() = runTest {
        val router = HostKeyTrustPromptRouter()
        val prompts = collectPrompts(router)
        router.armUserInitiatedConnect(4)
        router.report(4, unknown())
        assertEquals(listOf(4L), prompts)

        router.resetForTest()

        assertTrue("the annotation set must not survive", router.hostsNeedingTrust.value.isEmpty())
        assertFalse("the foreground window must not survive", router.isUserInitiated(4))
        // ...and the router is still live afterwards, so a reset between test
        // classes cannot silently disable it (which would make every later
        // journey pass vacuously).
        router.report(4, unknown())
        assertEquals(setOf(4L), router.hostsNeedingTrust.value)
        assertEquals("a reset also closes the foreground window", listOf(4L), prompts)
    }

    // ------------------------------------------------------- housekeeping

    @Test
    fun ordinarySshFailuresAndUnownedCredentialsNeverAnnotateOrPrompt() = runTest {
        val router = HostKeyTrustPromptRouter()
        val prompts = collectPrompts(router)

        router.armUserInitiatedConnect(7)
        router.report(7, SshException("offline"))
        router.report(null, unknown())

        assertTrue(router.hostsNeedingTrust.value.isEmpty())
        assertEquals(emptyList<Long>(), prompts)
    }

    @Test
    fun aSuccessfulConnectClearsTheHostCardAnnotation() = runTest {
        val router = HostKeyTrustPromptRouter()
        router.report(4, unknown())
        router.report(7, changed())

        router.clearTrustAttention(4)
        router.clearTrustAttention(null)

        assertEquals(setOf(7L), router.hostsNeedingTrust.value)
    }

    private fun kotlinx.coroutines.test.TestScope.collectPrompts(
        router: HostKeyTrustPromptRouter,
    ): List<Long> {
        val seen = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            router.trustPrompts.collect { seen += it }
        }
        return seen
    }

    private fun unknown() = UnknownHostKeyException("host", 22, "ssh-ed25519", "SHA256:new")
    private fun changed() = ChangedHostKeyException(
        "host", 22, "ssh-ed25519", "SHA256:old", "SHA256:new",
    )
}
