package com.pocketshell.app.session

import com.pocketshell.app.sessions.LeaseSessionBlockTimeoutException
import com.pocketshell.app.sessions.LeaseSessionTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPathTapControllerTest {

    @Test
    fun extensionlessFileRoutesByRemoteFactNotParserShape() = runTest {
        val controller = controller { _, _ -> ConversationRemotePathFact.RegularFile }
        controller.bindScope("pane-a")

        controller.open(request(rawPath = "README", cwd = "/work/project"))
        advanceUntilIdle()

        assertEquals(
            ConversationPathTapState.OpenFile(1L, "/work/project/README"),
            controller.state.value,
        )
    }

    @Test
    fun dottedDirectoryRoutesByRemoteFactNotExtensionHeuristic() = runTest {
        val controller = controller { _, _ -> ConversationRemotePathFact.Directory }
        controller.bindScope("pane-a")

        controller.open(request(rawPath = "release.v2", cwd = "/work/project"))
        advanceUntilIdle()

        assertEquals(
            ConversationPathTapState.BrowseDirectory(1L, "/work/project/release.v2"),
            controller.state.value,
        )
    }

    @Test
    fun relativeAndRootedTargetsUseFileViewerResolutionRules() = runTest {
        val resolved = mutableListOf<String>()
        val controller = controller { _, path ->
            resolved += path
            ConversationRemotePathFact.RegularFile
        }
        controller.bindScope("pane-a")

        controller.open(request(rawPath = "../notes", cwd = "/work/project"))
        advanceUntilIdle()
        controller.consume(1L)
        controller.open(request(rawPath = "/srv/exact", cwd = "/stale/cwd"))
        advanceUntilIdle()

        assertEquals(listOf("/work/notes", "/srv/exact"), resolved)
    }

    @Test
    fun rootedAttachmentPathStaysHomeRooted() = runTest {
        var resolved = ""
        val controller = controller { _, path ->
            resolved = path
            ConversationRemotePathFact.RegularFile
        }
        controller.bindScope("pane-a")

        controller.open(
            request(
                rawPath = "~/.pocketshell/attachments/host/screenshot.png",
                cwd = "/work/project",
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "/home/agent/.pocketshell/attachments/host/screenshot.png",
            resolved,
        )
    }

    @Test
    fun authoritativeRemoteHomeResolutionReplacesConventionalFallbackInResult() = runTest {
        val controller = ConversationPathTapController(this) { _, _ ->
            ConversationRemotePathProbeResult(
                resolvedPath = "/srv/custom-home/missing",
                fact = ConversationRemotePathFact.Failure("Path does not exist."),
            )
        }
        controller.bindScope("pane-a")

        controller.open(request(rawPath = "~/missing", cwd = "/work/project"))
        advanceUntilIdle()

        assertEquals(
            ConversationPathTapState.Failed(
                requestId = 1L,
                resolvedPath = "/srv/custom-home/missing",
                reason = "Path does not exist.",
            ),
            controller.state.value,
        )
    }

    @Test
    fun missingOrUnusablePaneCwdFallsBackToAbsoluteRemoteHome() {
        assertEquals(
            "/srv/custom-home/notes/today.txt",
            ConversationPathTapViewModel.resolvePath(
                rawPath = "notes/today.txt",
                cwd = null,
                remoteHome = "/srv/custom-home",
            ),
        )
        assertEquals(
            "/srv/custom-home/notes/today.txt",
            ConversationPathTapViewModel.resolvePath(
                rawPath = "notes/today.txt",
                cwd = "stale-relative-cwd",
                remoteHome = "/srv/custom-home",
            ),
        )
    }

    @Test
    fun missingTargetErrorKeepsExactResolvedPathAndReason() = runTest {
        val controller = controller { _, _ ->
            ConversationRemotePathFact.Failure("Path does not exist.")
        }
        controller.bindScope("pane-a")

        controller.open(request(rawPath = "missing/output", cwd = "/work/project"))
        advanceUntilIdle()

        assertEquals(
            ConversationPathTapState.Failed(
                requestId = 1L,
                resolvedPath = "/work/project/missing/output",
                reason = "Path does not exist.",
            ),
            controller.state.value,
        )
    }

    @Test
    fun permissionAndTimeoutHaveActionableReasons() {
        assertEquals(
            ConversationRemotePathFact.Failure("Permission denied."),
            ConversationPathTapViewModel.parseProbe("__PS_DENIED__\n", "", 0),
        )
        assertEquals(
            "Timed out while checking the path.",
            ConversationPathTapViewModel.probeFailureReason(
                LeaseSessionBlockTimeoutException(8_000),
            ),
        )
        assertEquals(
            "Couldn't check the path: channel refused",
            ConversationPathTapViewModel.probeFailureReason(
                IllegalStateException("channel refused"),
            ),
        )
    }

    @Test
    fun nonReturningProbeTimesOutAtOneTapDeadlineAndKeepsExactResolvedPath() = runTest {
        val controller = ConversationPathTapController(
            scope = this,
            probeTimeoutMs = 1_000L,
        ) { _, _ ->
            awaitCancellation()
        }
        controller.bindScope("pane-a")
        controller.open(request(rawPath = "slow-target", cwd = "/work/project"))
        runCurrent()

        advanceTimeBy(1_001L)
        runCurrent()

        assertEquals(
            ConversationPathTapState.Failed(
                requestId = 1L,
                resolvedPath = "/work/project/slow-target",
                reason = "Timed out while checking the path.",
            ),
            controller.state.value,
        )
    }

    @Test
    fun stalePaneScopeSuppressesCancellationResistantLateResult() = runTest {
        val started = CompletableDeferred<Unit>()
        val controller = controller { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                // Model an SSH implementation that reports a result after its
                // blocking operation was cancelled. The identity guard, not
                // cooperative cancellation, must prevent stale navigation.
                ConversationRemotePathFact.RegularFile
            }
        }
        controller.bindScope("pane-a")
        controller.open(request(rawPath = "old.txt", cwd = "/old/cwd"))
        runCurrent()
        started.await()

        controller.bindScope("pane-b")
        advanceUntilIdle()

        assertEquals(ConversationPathTapState.Idle, controller.state.value)
    }

    @Test
    fun lifecycleCancelSuppressesCancellationResistantLateResult() = runTest {
        val started = CompletableDeferred<Unit>()
        val controller = controller { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                ConversationRemotePathFact.Directory
            }
        }
        controller.bindScope("pane-a")
        controller.open(request(rawPath = "old-folder", cwd = "/old/cwd"))
        runCurrent()
        started.await()

        controller.cancel()
        advanceUntilIdle()

        assertEquals(ConversationPathTapState.Idle, controller.state.value)
    }

    @Test
    fun duplicateTapStartsExactlyOneProbeUntilResultIsConsumed() = runTest {
        var probes = 0
        val release = CompletableDeferred<Unit>()
        val controller = controller { _, _ ->
            probes++
            release.await()
            ConversationRemotePathFact.RegularFile
        }
        controller.bindScope("pane-a")
        val request = request(rawPath = "README", cwd = "/work/project")

        controller.open(request)
        controller.open(request)
        runCurrent()
        assertEquals(1, probes)

        release.complete(Unit)
        advanceUntilIdle()
        controller.open(request)
        advanceUntilIdle()
        assertEquals("ready-but-unconsumed result must still suppress duplicates", 1, probes)
    }

    @Test
    fun probeCommandQuotesPathAndParserCoversMissingAndUnknownType() {
        val command = ConversationPathTapViewModel.probeCommand("/tmp/a'; touch /tmp/pwned; '")

        assertTrue(command.contains("'/tmp/a'\\''; touch /tmp/pwned; '\\'''"))
        assertEquals(
            ConversationRemotePathFact.Failure("Path does not exist."),
            ConversationPathTapViewModel.parseProbe(
                stdout = "",
                stderr = "stat: cannot stat '/tmp/missing': No such file or directory",
                exitCode = 1,
            ),
        )
        assertEquals(
            ConversationRemotePathFact.Failure("The target is not a regular file or directory."),
            ConversationPathTapViewModel.parseProbe("__PS_OTHER__\n", "", 0),
        )
    }

    @Test
    fun dismissClearsFailureAndAllowsRetry() = runTest {
        var probes = 0
        val controller = controller { _, _ ->
            probes++
            ConversationRemotePathFact.Failure("Path does not exist.")
        }
        controller.bindScope("pane-a")
        val request = request(rawPath = "missing", cwd = "/work")
        controller.open(request)
        advanceUntilIdle()

        val failed = controller.state.value as ConversationPathTapState.Failed
        controller.consume(failed.requestId)
        assertEquals(ConversationPathTapState.Idle, controller.state.value)

        controller.open(request)
        advanceUntilIdle()
        assertEquals(2, probes)
        assertFalse(controller.state.value is ConversationPathTapState.Idle)
    }

    private fun TestScope.controller(
        probe: suspend (ConversationPathTapRequest, String) -> ConversationRemotePathFact,
    ) = ConversationPathTapController(this) { request, resolvedPath ->
        ConversationRemotePathProbeResult(
            resolvedPath = resolvedPath,
            fact = probe(request, resolvedPath),
        )
    }

    private fun request(
        rawPath: String,
        cwd: String?,
        scopeKey: String = "pane-a",
    ) = ConversationPathTapRequest(
        scopeKey = scopeKey,
        rawPath = rawPath,
        cwd = cwd,
        target = LeaseSessionTarget(
            hostId = 7L,
            hostname = "host",
            port = 22,
            username = "agent",
            keyPath = "/tmp/key",
            passphrase = null,
        ),
    )
}
