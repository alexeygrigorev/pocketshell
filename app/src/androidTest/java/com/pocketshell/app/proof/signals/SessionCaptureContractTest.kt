package com.pocketshell.app.proof.signals

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_PULL_TO_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_RECREATE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression proof for issue #2297's shared capture/assertion contract.
 *
 * These are deliberately small on-device fixtures: the contract itself is
 * under test, not the production connection state machine. The three named
 * Docker journeys consume the same functions below.
 *
 * Mutations that must redden this class (D32/G6):
 *
 *  - Move [SessionCaptureContract.recordSemantic] after the bitmap provider:
 *    [semanticSnapshotIsWrittenBeforeA failingBitmapAttempt] fails, proving a
 *    capture exception cannot erase status/booleans/timings.
 *  - Restore terminal-only capture for [SessionCaptureRoute.SESSION_FRAME]:
 *    [recoveryRouteUsesSessionFrameInsteadOfTerminalViewport] fails because
 *    its terminal provider throws. The ordinary terminal route test remains
 *    green under that mutation and the existing #2135 hard-failure tests stay
 *    load-bearing.
 *  - Remove the action-tag guard from [assertRecoveryCardVisible]:
 *    [recoveryCardRequiresBothCardAndActionTags] fails with the action absent
 *    or off-screen.
 *  - Replace the explicit indicator-tag check with a status-only proxy:
 *    [recoveryIndicatorRequiresRenderedTag] fails with a session root and no
 *    indicator tag. Removing its displayed-node check also fails when the tag
 *    is off-screen. A transient enum is never accepted as visual proof.
 *    Re-adding the pull-to-refresh gesture wrapper as an allowed indicator
 *    reddens [gestureWrapperAloneIsNotRecoveryEvidence], because the wrapper
 *    is not a user-facing recovery affordance.
 *  - Omit the authoritative identity from [SessionRouteSnapshot], or skip its
 *    expected-snapshot comparison: [sameSessionRootRejectsAtoBIdentityRebind]
 *    fails even though the same route root keeps the same tag, count, and
 *    Compose node id.
 */
@RunWith(AndroidJUnit4::class)
class SessionCaptureContractTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun semanticSnapshotIsWrittenBeforeA_failingBitmapAttempt() {
        val events = mutableListOf<String>()
        val contract = contractFor(events)

        val error = runCatching {
            contract.capture(
                label = "issue2297-order",
                semantics = semantics(
                    route = SessionCaptureRoute.TERMINAL_VIEWPORT,
                    status = "Failed",
                    booleans = mapOf("bandVisible" to true),
                    timings = listOf("resume_to_failed_status_ms=123"),
                ),
                captureTerminalViewport = {
                    events += "bitmap"
                    throw AssertionError("synthetic #2135 terminal capture failure")
                },
                captureSessionFrame = {
                    error("session frame must not be selected")
                },
            )
        }.exceptionOrNull()

        assertNotNull("the synthetic terminal capture must remain a hard failure", error)
        assertTrue(error is AssertionError)
        assertEquals(
            "semantic state must be recorded before the bitmap provider is entered",
            listOf("semantic", "bitmap"),
            events,
        )
    }

    @Test
    fun semanticArtifactContainsStatusBooleansTimingsAndVisibleTags() {
        var artifactText = ""
        val contract = SessionCaptureContract(
            recordSemantic = { _, text -> artifactText = text },
            writeBitmap = { _, _, _ -> },
        )
        val bitmap = contract.capture(
            label = "issue2297-semantic-fields",
            semantics = semantics(
                route = SessionCaptureRoute.SESSION_FRAME,
                status = "Reconnecting(attempt=2)",
                booleans = mapOf("indicatorVisible" to true),
                timings = listOf("drop_to_indicator_ms=456"),
                visibleTags = setOf("tmux:session", "tmux:session:error"),
            ),
            captureTerminalViewport = { error("terminal frame must not be selected") },
            captureSessionFrame = {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            },
        )
        bitmap.recycle()

        assertTrue(artifactText.contains("status=Reconnecting(attempt=2)"))
        assertTrue(artifactText.contains("session.name=session-a"))
        assertTrue(artifactText.contains("session.id=\$0"))
        assertTrue(artifactText.contains("session.created=100"))
        assertTrue(artifactText.contains("boolean.indicatorVisible=true"))
        assertTrue(artifactText.contains("timing.drop_to_indicator_ms=456"))
        assertTrue(artifactText.contains("visible_tag=tmux:session:error"))
    }

    @Test
    fun recoveryRouteUsesSessionFrameInsteadOfTerminalViewport() {
        val events = mutableListOf<String>()
        val contract = contractFor(events)

        val bitmap = contract.capture(
            label = "issue2297-recovery",
            semantics = semantics(route = SessionCaptureRoute.SESSION_FRAME, status = "Failed"),
            captureTerminalViewport = {
                throw AssertionError("terminal-only capture is invalid for a recovery frame")
            },
            captureSessionFrame = {
                events += "session-frame"
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            },
        )
        bitmap.recycle()

        assertEquals(
            "a recovery frame must not ask for an absent TerminalView",
            listOf("semantic", "session-frame", "write:SESSION_FRAME"),
            events,
        )
    }

    @Test
    fun ordinaryTerminalRouteStillUsesHardTerminalCapture() {
        val events = mutableListOf<String>()
        val contract = contractFor(events)

        val bitmap = contract.capture(
            label = "issue2297-terminal",
            semantics = semantics(route = SessionCaptureRoute.TERMINAL_VIEWPORT, status = "Connected"),
            captureTerminalViewport = {
                events += "terminal"
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            },
            captureSessionFrame = {
                throw AssertionError("session frame must not replace terminal content evidence")
            },
        )
        bitmap.recycle()

        assertEquals(
            listOf("semantic", "terminal", "write:TERMINAL_VIEWPORT"),
            events,
        )
    }

    @Test
    fun recoveryCardRequiresBothCardAndActionTags() {
        val includeAction = mutableStateOf(true)
        val actionOffscreen = mutableStateOf(false)
        compose.setContent {
            recoveryContent(
                includeAction = includeAction.value,
                actionOffscreen = actionOffscreen.value,
            )
        }
        compose.waitForIdle()
        compose.assertRecoveryCardVisible(
            label = "issue2297-card",
            sessionIdentity = TEST_SESSION_A,
        )

        includeAction.value = false
        compose.waitForIdle()
        val error = runCatching {
            compose.assertRecoveryCardVisible(
                label = "issue2297-card-missing-action",
                sessionIdentity = TEST_SESSION_A,
            )
        }.exceptionOrNull()
        assertNotNull("a card without its user action must fail the contract", error)
        assertTrue(error is AssertionError)

        includeAction.value = true
        actionOffscreen.value = true
        compose.waitForIdle()
        val offscreenError = runCatching {
            compose.assertRecoveryCardVisible(
                label = "issue2297-card-offscreen-action",
                sessionIdentity = TEST_SESSION_A,
            )
        }.exceptionOrNull()
        assertNotNull("an off-screen card action must fail the visibility contract", offscreenError)
        assertTrue(offscreenError is AssertionError)
    }

    @Test
    fun recoveryIndicatorRequiresRenderedTag() {
        val includeIndicator = mutableStateOf(true)
        val indicatorOffscreen = mutableStateOf(false)
        compose.setContent {
            indicatorContent(
                includeIndicator = includeIndicator.value,
                indicatorOffscreen = indicatorOffscreen.value,
            )
        }
        compose.waitForIdle()
        val observed = compose.assertSessionRecoveryIndicatorVisible(
            label = "issue2297-indicator",
            sessionIdentity = TEST_SESSION_A,
            indicatorTags = setOf(TMUX_SESSION_ERROR_TAG),
        )
        assertEquals(setOf(TMUX_SESSION_ERROR_TAG), observed.visibleTags)

        includeIndicator.value = false
        compose.waitForIdle()
        val error = runCatching {
            compose.assertSessionRecoveryIndicatorVisible(
                label = "issue2297-indicator-missing",
                sessionIdentity = TEST_SESSION_A,
                indicatorTags = setOf(TMUX_SESSION_ERROR_TAG),
            )
        }.exceptionOrNull()
        assertNotNull("a non-Connected state without a rendered tag is not visual proof", error)
        assertTrue(error is AssertionError)

        includeIndicator.value = true
        indicatorOffscreen.value = true
        compose.waitForIdle()
        val offscreenError = runCatching {
            compose.assertSessionRecoveryIndicatorVisible(
                label = "issue2297-indicator-offscreen",
                sessionIdentity = TEST_SESSION_A,
                indicatorTags = setOf(TMUX_SESSION_ERROR_TAG),
            )
        }.exceptionOrNull()
        assertNotNull("an off-screen indicator must fail the visibility contract", offscreenError)
        assertTrue(offscreenError is AssertionError)
    }

    @Test
    fun gestureWrapperAloneIsNotRecoveryEvidence() {
        compose.setContent {
            indicatorContent(includeIndicator = false, includeGestureWrapper = true)
        }
        compose.waitForIdle()
        val error = runCatching {
            compose.assertSessionRecoveryIndicatorVisible(
                label = "issue2297-gesture-wrapper-only",
                sessionIdentity = TEST_SESSION_A,
            )
        }.exceptionOrNull()
        assertNotNull(
            "the pull gesture wrapper alone is not a user-facing recovery indicator",
            error,
        )
        assertTrue(error is AssertionError)
    }

    @Test
    fun sameSessionRootRejectsAtoBIdentityRebind() {
        val reboundIdentity = mutableStateOf(TEST_SESSION_A)
        compose.setContent {
            // Deliberately keep one Compose root mounted: the identity source
            // changes as a same-root A -> B rebind, without a route key or node
            // replacement to make the mutation obvious.
            Box(Modifier.fillMaxSize().testTag(TMUX_SESSION_SCREEN_TAG))
        }
        compose.waitForIdle()
        val before = compose.assertSessionRouteStable(
            label = "issue2297-route-before",
            sessionIdentity = reboundIdentity.value,
        )

        reboundIdentity.value = TEST_SESSION_B
        val afterRebind = compose.assertSessionRouteStable(
            label = "issue2297-route-after-rebind",
            sessionIdentity = reboundIdentity.value,
        )
        assertEquals("the rebind must keep one session root", 1, afterRebind.sessionScreenCount)
        assertEquals(
            "the rebind fixture must keep the same Compose node so identity is load-bearing",
            before.sessionScreenNodeIds,
            afterRebind.sessionScreenNodeIds,
        )
        val error = runCatching {
            compose.assertSessionRouteStable(
                label = "issue2297-route-after-identity-check",
                sessionIdentity = reboundIdentity.value,
                expected = before,
            )
        }.exceptionOrNull()
        assertNotNull("an in-place A -> B identity rebind must fail route stability", error)
        assertTrue(error is AssertionError)
    }

    @Test
    fun routeStabilityRejectsDuplicateSessionRoutes() {
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.size(160.dp).testTag(TMUX_SESSION_SCREEN_TAG))
                Box(Modifier.size(160.dp).testTag(TMUX_SESSION_SCREEN_TAG))
            }
        }
        compose.waitForIdle()
        val error = runCatching {
            compose.assertSessionRouteStable(
                label = "issue2297-route-duplicate",
                sessionIdentity = TEST_SESSION_A,
            )
        }.exceptionOrNull()
        assertNotNull("two session roots are not a stable single route", error)
        assertTrue(error is AssertionError)
    }

    private fun contractFor(events: MutableList<String>): SessionCaptureContract =
        SessionCaptureContract(
            recordSemantic = { _, _ -> events += "semantic" },
            writeBitmap = { _, route, _ -> events += "write:$route" },
        )

    private fun semantics(
        route: SessionCaptureRoute,
        status: String,
        sessionIdentity: SessionIdentity = TEST_SESSION_A,
        booleans: Map<String, Boolean> = emptyMap(),
        timings: List<String> = emptyList(),
        visibleTags: Set<String> = emptySet(),
    ) = SessionCaptureSemantics(
        route = route,
        sessionIdentity = sessionIdentity,
        status = status,
        booleans = booleans,
        timings = timings,
        visibleTags = visibleTags,
    )

    @androidx.compose.runtime.Composable
    private fun recoveryContent(includeAction: Boolean, actionOffscreen: Boolean = false) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.size(160.dp).testTag(TMUX_SESSION_SCREEN_TAG))
            Box(Modifier.size(160.dp).testTag(TMUX_TERMINAL_SURFACE_ERROR_TAG))
            if (includeAction) {
                Box(
                    Modifier
                        .then(if (actionOffscreen) Modifier.offset(x = 2000.dp, y = 2000.dp) else Modifier)
                        .size(48.dp)
                        .testTag(TMUX_TERMINAL_SURFACE_RECREATE_TAG),
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun indicatorContent(
        includeIndicator: Boolean,
        indicatorOffscreen: Boolean = false,
        includeGestureWrapper: Boolean = false,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.size(160.dp).testTag(TMUX_SESSION_SCREEN_TAG))
            if (includeGestureWrapper) {
                Box(
                    Modifier
                        .size(160.dp)
                        .testTag(TMUX_PULL_TO_RECONNECT_TAG),
                )
            }
            if (includeIndicator) {
                Box(
                    Modifier
                        .then(if (indicatorOffscreen) Modifier.offset(x = 2000.dp, y = 2000.dp) else Modifier)
                        .size(48.dp)
                        .testTag(TMUX_SESSION_ERROR_TAG),
                )
            }
        }
    }
}

private val TEST_SESSION_A = SessionIdentity(
    name = "session-a",
    id = "\$0",
    createdEpochSeconds = 100L,
)

private val TEST_SESSION_B = SessionIdentity(
    name = "session-b",
    id = "\$1",
    createdEpochSeconds = 200L,
)
