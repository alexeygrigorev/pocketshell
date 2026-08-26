package com.pocketshell.app.proof.signals

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_PULL_TO_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SURFACE_RECONNECT_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_RECREATE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The kind of visual artifact a journey is allowed to request.
 *
 * A terminal viewport is authoritative only when terminal content is the
 * property under test. Recovery UI replaces that view intentionally, so its
 * evidence is a session frame, while the semantic card/tag assertions remain
 * the authoritative proof that the recovery UI was visible.
 */
internal enum class SessionCaptureRoute(val artifactSuffix: String) {
    TERMINAL_VIEWPORT("viewport"),
    SESSION_FRAME("session"),
}

/**
 * Authoritative identity of the tmux session generation represented by an artifact.
 *
 * The name is what the user sees, but it is mutable and can be reused. The tmux
 * server-assigned id paired with its creation epoch identifies the same session
 * object across a reconnect and rejects an A -> B rebind that leaves the Compose
 * route root in place.
 */
internal data class SessionIdentity(
    val name: String,
    val id: String,
    val createdEpochSeconds: Long,
) {
    init {
        require(name.isNotBlank()) { "session identity name must not be blank" }
        require(id.isNotBlank()) { "session identity id must not be blank" }
        require(createdEpochSeconds > 0L) {
            "session identity creation epoch must be positive"
        }
    }
}

/**
 * Semantic state captured alongside a screenshot. This is deliberately a
 * value object so callers snapshot identity, status, booleans, and timings
 * before any bitmap provider is entered.
 */
internal data class SessionCaptureSemantics(
    val route: SessionCaptureRoute,
    val sessionIdentity: SessionIdentity,
    val status: String,
    val booleans: Map<String, Boolean> = emptyMap(),
    val timings: List<String> = emptyList(),
    val visibleTags: Set<String> = emptySet(),
) {
    fun toArtifactText(): String = buildString {
        appendLine("route=${route.name}")
        appendLine("session.name=${sessionIdentity.name}")
        appendLine("session.id=${sessionIdentity.id}")
        appendLine("session.created=${sessionIdentity.createdEpochSeconds}")
        appendLine("status=$status")
        booleans.toSortedMap().forEach { (name, value) ->
            appendLine("boolean.$name=$value")
        }
        appendLine("timings.count=${timings.size}")
        timings.forEach { appendLine("timing.$it") }
        visibleTags.sorted().forEach { appendLine("visible_tag=$it") }
    }
}

/**
 * Shared capture ordering contract for recovery/error journeys.
 *
 * [recordSemantic] is called before either bitmap provider. If a terminal
 * view disappears while a recovery card is rendered, the semantic verdict
 * and timing snapshot therefore still exist in the artifact directory even
 * when an authoritative terminal capture throws its hard #2135 assertion.
 */
internal class SessionCaptureContract(
    private val recordSemantic: (label: String, text: String) -> Unit,
    private val writeBitmap: (
        label: String,
        route: SessionCaptureRoute,
        bitmap: Bitmap,
    ) -> Unit,
) {
    fun capture(
        label: String,
        semantics: SessionCaptureSemantics,
        captureTerminalViewport: () -> Bitmap,
        captureSessionFrame: () -> Bitmap,
    ): Bitmap {
        recordSemantic("$label-semantic.txt", semantics.toArtifactText())

        val bitmap = when (semantics.route) {
            SessionCaptureRoute.TERMINAL_VIEWPORT -> captureTerminalViewport()
            SessionCaptureRoute.SESSION_FRAME -> captureSessionFrame()
        }
        writeBitmap(label, semantics.route, bitmap)
        return bitmap
    }
}

/** Stable identity of the Compose session route, bound to an authoritative tmux identity. */
internal data class SessionRouteSnapshot(
    val sessionIdentity: SessionIdentity,
    val sessionScreenCount: Int,
    val sessionScreenNodeIds: List<Int>,
)

/** The explicit recovery tags observed at one semantic checkpoint. */
internal data class SessionRecoveryIndicatorSnapshot(
    val route: SessionRouteSnapshot,
    val visibleTags: Set<String>,
)

/**
 * Assert that exactly one session screen is still mounted. A status enum is
 * not a route oracle: a stale status can survive navigation, while this tag
 * proves the visible tree is still a session surface. The caller must also
 * provide the authoritative tmux identity; node count and Compose node id
 * alone cannot detect an in-place A -> B rebind.
 */
internal fun ComposeTestRule.assertSessionRouteStable(
    label: String,
    sessionIdentity: SessionIdentity,
    expected: SessionRouteSnapshot? = null,
): SessionRouteSnapshot {
    val sessionNodes = onAllNodesWithTag(
        TMUX_SESSION_SCREEN_TAG,
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
    val actual = SessionRouteSnapshot(
        sessionIdentity = sessionIdentity,
        sessionScreenCount = sessionNodes.size,
        sessionScreenNodeIds = sessionNodes.map { it.id },
    )
    assertEquals(
        "$label must keep exactly one session route ($TMUX_SESSION_SCREEN_TAG)",
        1,
        actual.sessionScreenCount,
    )
    onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
    if (expected != null) {
        assertEquals("$label changed session route while capturing", expected, actual)
    }
    return actual
}

/**
 * Assert a recovery card is actually visible in the still-mounted session.
 * Both the card and its action are required; a VM enum alone cannot prove
 * that the user-visible affordance rendered.
 */
internal fun ComposeTestRule.assertRecoveryCardVisible(
    label: String,
    sessionIdentity: SessionIdentity,
    cardTag: String = TMUX_TERMINAL_SURFACE_ERROR_TAG,
    actionTag: String = TMUX_TERMINAL_SURFACE_RECREATE_TAG,
): SessionRouteSnapshot {
    val route = assertSessionRouteStable(label, sessionIdentity = sessionIdentity)
    assertExactlyOneDisplayedTag(label, cardTag)
    assertExactlyOneDisplayedTag(label, actionTag)
    return route
}

/**
 * Assert a connection-loss surface by its rendered tags, never by treating a
 * transient non-Connected enum as an indicator. The caller may require the
 * error band and reconnect action together, or accept any one of the explicit
 * recovery surfaces used by the production chrome.
 */
internal fun ComposeTestRule.assertSessionRecoveryIndicatorVisible(
    label: String,
    sessionIdentity: SessionIdentity,
    indicatorTags: Set<String> = DEFAULT_SESSION_RECOVERY_INDICATOR_TAGS,
    requiredTags: Set<String> = emptySet(),
): SessionRecoveryIndicatorSnapshot {
    val route = assertSessionRouteStable(label, sessionIdentity = sessionIdentity)
    val visibleTags = indicatorTags.filterTo(linkedSetOf()) { tag ->
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    assertTrue(
        "$label must show at least one explicit recovery indicator tag; " +
            "allowed=$indicatorTags observed=$visibleTags",
        visibleTags.isNotEmpty(),
    )
    requiredTags.forEach { tag ->
        assertExactlyOneDisplayedTag(label, tag)
    }
    visibleTags.forEach { tag ->
        assertExactlyOneDisplayedTag(label, tag)
    }
    return SessionRecoveryIndicatorSnapshot(route = route, visibleTags = visibleTags)
}

private fun ComposeTestRule.assertExactlyOneDisplayedTag(label: String, tag: String) {
    val count = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    assertEquals("$label must render exactly one visible tag '$tag'", 1, count)
    onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
}

internal val DEFAULT_SESSION_RECOVERY_INDICATOR_TAGS: Set<String> = setOf(
    // These are rendered user-facing recovery surfaces. The pull-to-refresh
    // wrapper is intentionally absent: it is a gesture container, not visible
    // recovery evidence, and is not painted while the surface is showing
    // "Attaching…". The switching loader is included because it is the actual
    // centered spinner + "Attaching…" indicator during an active reattach.
    TMUX_CONNECTION_STATUS_PILL_TAG,
    TMUX_SWITCHING_LOADING_TAG,
    TMUX_SESSION_ERROR_TAG,
    TMUX_SESSION_RECONNECT_TAG,
    TMUX_SURFACE_RECONNECT_BUTTON_TAG,
    TMUX_PULL_TO_RECONNECT_TAG,
)
